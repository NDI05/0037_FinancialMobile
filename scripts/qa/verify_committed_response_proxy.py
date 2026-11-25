"""Prove proxy response loss and Go idempotency using an isolated synthetic DB.

This is NOT Android UI acceptance. Use the same proxy with the app for that gate.
"""

import argparse
import http.client
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

from committed_response_proxy import Evidence, Proxy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New result file; JSONL goes beside it")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    events_path = args.output.with_suffix(".events.jsonl")
    if args.output.exists() or events_path.exists():
        parser.error("Result/evidence exists; choose new per-run paths")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        api_port = sock.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="pam-proxy-verify-") as directory:
        db = Path(directory) / "synthetic.db"
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "APP_ENV": "development",
               "PORT": str(api_port), "DATABASE_URL": "file:" + str(db),
}
        with open(Path(directory) / "api.log", "w") as log:
            process = subprocess.Popen([str(args.binary.resolve())], env=env, stdout=log, stderr=log)
            evidence = Evidence(events_path, armed=True)
            proxy = Proxy(0, "http://127.0.0.1:" + str(api_port), evidence)
            thread = threading.Thread(target=proxy.serve_forever, daemon=True)
            thread.start()

            def request(method, path, body=None, token=None, key=None, direct=False):
                headers = {"Content-Type": "application/json"}
                if token:
                    headers["Authorization"] = "Bearer " + token
                if key:
                    headers["Idempotency-Key"] = key
                port = api_port if direct else proxy.server_port
                req = urllib.request.Request("http://127.0.0.1:" + str(port) + path,
                    data=json.dumps(body).encode() if body is not None else None,
                    headers=headers, method=method)
                try:
                    response = urllib.request.urlopen(req, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                payload = response.read()
                return response.status, json.loads(payload) if payload else {}

            def snapshot():
                with sqlite3.connect("file:" + str(db) + "?mode=ro", uri=True) as conn:
                    return {"rows": conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],
                            "month_revision": conn.execute("SELECT COALESCE(MAX(revision),0) FROM month_revisions").fetchone()[0],
                            "idempotency_records": conn.execute("SELECT COUNT(*) FROM http_idempotency").fetchone()[0]}

            try:
                for _ in range(100):
                    try:
                        if request("GET", "/healthz", direct=True)[0] == 200:
                            break
                    except OSError:
                        time.sleep(.05)
                else:
                    raise RuntimeError("Synthetic Go API startup failed")
                email, password = "qa-proxy@example.test", "synthetic-password"
                assert request("POST", "/v1/accounts", {"email": email, "password": password})[0] == 201
                code, session = request("POST", "/v1/sessions", {"email": email, "password": password})
                assert code == 201
                token, key = session["accessToken"], uuid.uuid4().hex
                body = {"direction": "DEBIT", "amountMinor": 12500000, "currency": "IDR",
                        "eventAt": "2026-11-22T05:00:00Z", "description": "Synthetic proxy check"}
                before = snapshot()
                lost = False
                try:
                    request("POST", "/v1/transactions", body, token, key)
                except (http.client.RemoteDisconnected, urllib.error.URLError, ConnectionError):
                    lost = True
                after_commit = snapshot()
                code, _ = request("POST", "/v1/transactions", body, token, key)
                after_retry = snapshot()
                events = [json.loads(line) for line in events_path.read_text().splitlines()]
                assert lost and len(events) == 2
                assert events[0]["upstream_status"] == 201
                assert events[0]["downstream"] == "dropped_before_headers"
                assert code == 200 and events[1]["downstream"] == "forwarded"
                assert events[0]["key_sha256"] == events[1]["key_sha256"]
                assert events[0]["body_sha256"] == events[1]["body_sha256"]
                assert events[0]["result_id_sha256"] == events[1]["result_id_sha256"]
                assert before == {"rows": 0, "month_revision": 0, "idempotency_records": 0}
                assert after_commit == after_retry == {"rows": 1, "month_revision": 1, "idempotency_records": 1}
                new_code, _ = request("POST", "/v1/transactions", body, token, uuid.uuid4().hex)
                after_new_action = snapshot()
                all_events = [json.loads(line) for line in events_path.read_text().splitlines()]
                assert new_code == 201 and len(all_events) == 3
                assert all_events[2]["downstream"] == "forwarded"
                assert all_events[2]["key_sha256"] != events[0]["key_sha256"]
                assert all_events[2]["body_sha256"] == events[0]["body_sha256"]
                assert after_new_action == {"rows": 2, "month_revision": 2, "idempotency_records": 2}
                result = {"status": "PASS", "scope": "Go + QA proxy + Python HTTP client; Android UI NOT RUN",
                          "first_response_lost": lost, "retry_status": code,
                          "before": before, "after_commit": after_commit, "after_retry": after_retry,
                          "new_identical_action_status": new_code, "after_new_action": after_new_action,
                          "same_key_body_and_result": True, "evidence_file": events_path.name}
                with args.output.open("x") as output:
                    json.dump(result, output, indent=2)
                    output.write("\n")
                print(json.dumps(result, indent=2))
            finally:
                proxy.shutdown()
                proxy.server_close()
                thread.join(timeout=5)
                evidence.close()
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
