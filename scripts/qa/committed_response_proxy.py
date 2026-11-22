"""Local QA proxy: drop one fresh manual-create response after upstream HTTP 201.

Development-only tooling, never imported by the app/API. Evidence contains hashes and
statuses, never request/response bodies, credentials, raw idempotency keys or receipts.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import threading
import time
from urllib.parse import urlsplit
import uuid


def digest(value):
    return hashlib.sha256(value).hexdigest()


class Evidence:
    def __init__(self, path, armed):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.file = path.open("x", encoding="utf-8", buffering=1)
        os.chmod(path, 0o600)
        self.run_id = uuid.uuid4().hex
        self.armed = armed
        self.lock = threading.Lock()
        self.attempts = Counter()

    def attempt(self, key):
        with self.lock:
            self.attempts[key] += 1
            return self.attempts[key]

    def record(self, event, eligible):
        with self.lock:
            drop = self.armed and eligible
            if drop:
                self.armed = False
            event.update(
                run_id=self.run_id,
                at=datetime.now(timezone.utc).isoformat(),
                downstream="dropped_before_headers" if drop else "forwarded",
            )
            self.file.write(json.dumps(event, sort_keys=True) + "\n")
            return drop

    def close(self):
        self.file.close()


class Proxy(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port, upstream, evidence):
        parsed = urlsplit(upstream)
        if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1")
                or parsed.username or parsed.password or parsed.path not in ("", "/")
                or parsed.query or parsed.fragment or not parsed.port):
            raise ValueError("Upstream must be an explicit loopback HTTP host:port without credentials/path")
        self.upstream_host, self.upstream_port = parsed.hostname, parsed.port
        self.evidence = evidence
        super().__init__(("127.0.0.1", port), Forwarder)


class Forwarder(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass  # The default logger echoes paths, including possible receipt secrets.

    def forward(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "Invalid content length")
            return
        if length < 0 or length > 256 * 1024 or self.headers.get("Transfer-Encoding"):
            self.send_error(400, "Only bounded content-length requests are supported by this QA proxy")
            return
        body = self.rfile.read(length)
        path = urlsplit(self.path)
        if path.scheme or path.netloc or not self.path.startswith("/"):
            self.send_error(400, "Expected a relative API path")
            return
        targeted = self.command == "POST" and path.path == "/v1/transactions"
        key = self.headers.get("Idempotency-Key", "")
        key_hash = digest(key.encode())
        attempt = self.server.evidence.attempt(key_hash) if targeted else None
        hop = {"host", "connection", "proxy-connection", "transfer-encoding", "content-length"}
        headers = {k: v for k, v in self.headers.items() if k.lower() not in hop}
        headers["Connection"] = "close"
        headers["Content-Length"] = str(len(body))
        conn = http.client.HTTPConnection(self.server.upstream_host, self.server.upstream_port, timeout=30)
        started = time.monotonic()
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            response = conn.getresponse()
            payload = response.read(1024 * 1024 + 1)
            if len(payload) > 1024 * 1024:
                raise ValueError("QA response limit exceeded")
            if targeted:
                try:
                    canonical = json.dumps(json.loads(body), sort_keys=True, separators=(",", ":")).encode()
                except (ValueError, UnicodeDecodeError):
                    canonical = body
                result_hash = None
                try:
                    result_id = json.loads(payload).get("id")
                    if isinstance(result_id, str):
                        result_hash = digest(result_id.encode())
                except (ValueError, AttributeError):
                    pass
                event = {
                    "operation": "transaction.create", "attempt_for_key": attempt,
                    "key_sha256": key_hash, "body_sha256": digest(canonical),
                    "result_id_sha256": result_hash, "upstream_status": response.status,
                    "upstream_complete": True,
                    "elapsed_ms": round((time.monotonic() - started) * 1000, 2),
                }
                # A replay 200 must not accidentally arm a stale action as a fresh commit.
                if self.server.evidence.record(event, eligible=bool(key) and response.status == 201):
                    self.close_connection = True
                    try:
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    self.connection.close()  # No HTTP status, headers or body reach the client.
                    return
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() not in hop | {"server", "date"}:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(payload)
            self.close_connection = True
        except (OSError, http.client.HTTPException, ValueError):
            self.close_connection = True
            try:
                self.send_error(502, "QA upstream unavailable")
            except OSError:
                pass
        finally:
            conn.close()

    do_GET = forward
    do_HEAD = forward
    do_POST = forward
    do_PUT = forward
    do_PATCH = forward
    do_DELETE = forward


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, help="Example: http://127.0.0.1:18080")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--evidence", type=Path, required=True, help="New JSONL file; existing files are refused")
    parser.add_argument("--drop-once", action="store_true", help="Arm one post-commit drop; otherwise only observe")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("Port must be 0..65535")
    try:
        evidence = Evidence(args.evidence, args.drop_once)
    except FileExistsError:
        parser.error("Evidence file already exists; choose a new per-run file")
    try:
        with Proxy(args.port, args.upstream, evidence) as proxy:
            print(json.dumps({"run_id": evidence.run_id, "listen_port": proxy.server_port,
                              "drop_once_armed": args.drop_once}), flush=True)
            try:
                proxy.serve_forever()
            except KeyboardInterrupt:
                pass
    finally:
        evidence.close()


if __name__ == "__main__":
    main()
