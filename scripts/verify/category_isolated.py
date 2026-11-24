#!/usr/bin/env python3
"""Isolated API-level verification of the Livin merchant reference handling for categories.

Runs a throwaway Go backend plus a local Azure-compatible *stub* provider, so this proves the
server-side contract without any Azure call, any cost, any owner data or the emulator:

  * AI_CATEGORIZATION consent is required (403 consent_required, no provider call);
  * a 19-digit reference and a short numeric reference become NEEDS_REVIEW /
    merchant_not_identifiable with ZERO provider calls;
  * an empty label becomes NEEDS_REVIEW / merchant_missing with zero provider calls;
  * an unsanitizable label becomes NEEDS_REVIEW / unsafe_merchant with zero provider calls;
  * a readable name is classified with exactly one provider call;
  * repeated merchants in one request are deduplicated into one provider case;
  * the result cache is per owner: the same merchant for another owner calls the provider once;
  * a USER category is never overwritten (job ends NEEDS_REVIEW / not_eligible);
  * more than 5 transaction ids in one request -> 400;
  * the provider stub never receives reference-only, empty or unsafe merchant strings.

Needs only the Go API binary; the stub listens on loopback. No secrets, no Azure, no owner DB.

Usage:
  ./scripts/go.sh build -o /tmp/pam-category-verify-api ./cmd/api   # from repo 0037-Backend-Financial:
  python3 scripts/verify/category_isolated.py --binary /tmp/pam-category-verify-api \
      --out docs/evidence/assets/pm_go_2026-11-24/category_isolated_verification.json
"""
import argparse
import json
import os
import secrets
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

RESULT = {"purpose": "Isolated API-level category reason-code / cache / consent verification; "
                    "local stub provider, synthetic accounts only, no owner data",
          "checks": [], "provider_calls": [], "environment": {}}


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class StubProvider:
    """OpenAI-compatible /chat/completions stub. Classifies every readable merchant as food."""

    def __init__(self) -> None:
        self.calls = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 (http.server API)
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                try:
                    body = json.loads(raw)
                    payload = json.loads(body["messages"][1]["content"])
                    cases = payload.get("cases", [])
                except (KeyError, ValueError, IndexError):
                    self.send_response(400)
                    self.end_headers()
                    return
                owner.calls.append([case.get("merchant") for case in cases])
                results = [{"caseId": case["caseId"], "categoryCode": "food",
                            "needsReview": False, "reasonCode": ""} for case in cases]
                response = json.dumps({
                    "choices": [{"finish_reason": "stop",
                                 "message": {"content": json.dumps({"results": results})}}],
                    "usage": {"prompt_tokens": 120, "completion_tokens": 40},
                }).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response)))
                self.end_headers()
                self.wfile.write(response)

            def log_message(self, *args):  # keep stdout clean
                return

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


class Api:
    def __init__(self) -> None:
        self.token = None

    def request(self, port, path, method="GET", body=None, expect=200, key=None):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}", method=method, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        try:
            response = urllib.request.urlopen(request, timeout=40)
        except urllib.error.HTTPError as err:
            response = err
        with response:
            data = json.load(response)
            if response.code != expect:
                raise AssertionError(f"{method} {path} -> HTTP {response.code} "
                                     f"code={data.get('code')} message={data.get('message')}")
            return data


def check(name, condition, detail):
    RESULT["checks"].append({"name": name, "pass": bool(condition), "detail": detail})
    if not condition:
        raise AssertionError(f"FAILED {name}: {detail}")
    print(f"PASS {name}: {detail}")


def wait_ready(port, proc, logpath):
    for _ in range(60):
        if proc.poll() is not None:
            sys.exit(f"backend exited during startup; see {logpath}")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/readyz", timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, ConnectionError):
            pass
        time.sleep(0.25)
    sys.exit(f"backend did not become ready; see {logpath}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", default="/tmp/pam-category-verify-api")
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    if not Path(args.binary).exists():
        sys.exit(f"binary not found: {args.binary}; build it with scripts/go.sh build")

    stub = StubProvider()
    stub.start()
    port = free_port()
    folder = Path(tempfile.mkdtemp(prefix="pam-category-verify-"))
    dbpath = folder / "verify.db"
    logpath = folder / "backend.log"
    env = {
        "HOME": str(Path.home()),
        "PATH": "/usr/bin:/bin:/usr/local/bin",
        "APP_ENV": "development",
        "PORT": str(port),
        "DATABASE_URL": f"file:{dbpath}",
        "LOG_LEVEL": "info",
        "REGISTRATION_INVITE_CODE": "isolated-synthetic",
        "CATEGORY_LLM_ENABLED": "true",
        "CATEGORY_LLM_PROVIDER": "azure",
        "AZURE_CLASSIFIER_BASE_URL": f"http://127.0.0.1:{stub.port}",
        "AZURE_CLASSIFIER_API_KEY": "stub-key",
        "AZURE_CLASSIFIER_DEPLOYMENT": "stub-deployment",
        "AZURE_CLASSIFIER_MODEL": "stub-category-model",
        "CATEGORY_LLM_TIMEOUT_MS": "5000",
        "CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD": "0.0001",
        "CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD": "0.0004",
        "CATEGORY_LLM_DAILY_CEILING_USD": "0.10",
        "CATEGORY_LLM_MONTHLY_CEILING_USD": "2.00",
    }
    log = logpath.open("w")
    proc = subprocess.Popen([args.binary], env=env, stdout=log, stderr=subprocess.STDOUT)

    def create_tx(api, merchant, description="Pembayaran QRIS", category_id=None, origin="IMPORTED"):
        body = {"direction": "DEBIT", "source": "MANUAL", "amountMinor": 25000,
                "currency": "IDR", "eventAt": "2026-11-19T04:10:00Z",
                "fingerprint": secrets.token_hex(16), "parserVersion": "synthetic-verify",
                "parserConfidence": 0.99, "description": description,
                "merchantLabel": merchant, "categoryOrigin": origin}
        if category_id:
            body["categoryId"] = category_id
        return api.request(port, "/v1/transactions", "POST", body, expect=201,
                           key="verify-tx-" + secrets.token_hex(8))

    def enqueue(api, ids, expect=200):
        return api.request(port, "/v1/category-jobs", "POST", {"transactionIds": ids},
                           expect=expect)

    def job_for(jobs, tx_id):
        return next(job for job in jobs if job["transactionId"] == tx_id)

    try:
        wait_ready(port, proc, logpath)
        api = Api()
        password = os.environ.get("PAM_SMOKE_PASSWORD") or secrets.token_urlsafe(18)
        email = f"category.verify.{secrets.token_hex(6)}@pam-uat.test"
        api.request(port, "/v1/accounts", "POST",
                    {"email": email, "password": password, "displayName": "Synthetic verify",
                     "inviteCode": "isolated-synthetic"}, expect=201)
        api.token = api.request(port, "/v1/sessions", "POST",
                                {"email": email, "password": password}, expect=201)["accessToken"]
        food = next(item["id"] for item in api.request(port, "/v1/categories")["categories"]
                    if item["code"] == "food")

        # Consent gate before any category job, with a readable merchant ready to classify.
        control_tx = create_tx(api, "KOPI UJI")
        denied = enqueue(api, [control_tx["id"]], expect=403)
        check("consent_gate", denied["code"] == "consent_required" and len(stub.calls) == 0,
              "403 consent_required before any provider call")
        api.request(port, "/v1/consents", "POST",
                    {"purpose": "AI_CATEGORIZATION", "decision": "GRANTED",
                     "noticeVersion": "v0.4-review"}, expect=201)

        # Reason codes that must finish without a provider call.
        reference_19 = create_tx(api, "1234567890123456789")
        reference_short = create_tx(api, "12345")
        missing = create_tx(api, "")
        unsafe = create_tx(api, "TOKO @ UJI")
        reference_like = create_tx(api, "1234 5678 9012 3456")
        for name, tx, reason in [
            ("reference_19_digit", reference_19, "merchant_not_identifiable"),
            ("reference_short_numeric", reference_short, "merchant_not_identifiable"),
            ("reference_grouped_numeric", reference_like, "merchant_not_identifiable"),
            ("missing_label", missing, "merchant_missing"),
            ("unsafe_label", unsafe, "unsafe_merchant"),
        ]:
            response = enqueue(api, [tx["id"]])
            job = job_for(response["jobs"], tx["id"])
            check(name, job["state"] == "NEEDS_REVIEW" and job["reasonCode"] == reason
                  and job["categoryCode"] == "uncategorized",
                  f"NEEDS_REVIEW uncategorized reason={job['reasonCode']}")
        check("no_provider_for_rejected", len(stub.calls) == 0,
              "reference/empty/unsafe labels never reached the provider")

        control = job_for(enqueue(api, [control_tx["id"]])["jobs"], control_tx["id"])
        check("named_control_classified", control["state"] == "CLASSIFIED"
              and control["categoryCode"] == "food"
              and control["provider"] == "azure-openai-compatible"
              and control["model"] == "stub-category-model",
              f"CLASSIFIED food via provider={control['provider']} model={control['model']}")
        check("control_one_provider_call", len(stub.calls) == 1
              and stub.calls[0] == ["KOPI UJI"],
              f"provider saw exactly {stub.calls[0]}")

        # One request with two identical merchants -> one provider case, both jobs classified.
        dedup_a = create_tx(api, "TEH UJI")
        dedup_b = create_tx(api, "TEH UJI")
        dedup_jobs = enqueue(api, [dedup_a["id"], dedup_b["id"]])["jobs"]
        check("duplicate_merchants_share_one_case",
              all(job["state"] == "CLASSIFIED" and job["categoryCode"] == "food"
                  for job in dedup_jobs)
              and len(stub.calls) == 2 and stub.calls[1] == ["TEH UJI"],
              "two rows, same merchant -> two CLASSIFIED jobs, one provider call")

        # Same owner, same merchant again: per-owner cache replay, no new provider call.
        cached_tx = create_tx(api, "TEH UJI")
        cached_job = job_for(enqueue(api, [cached_tx["id"]])["jobs"], cached_tx["id"])
        check("owner_cache_replay", cached_job["state"] == "CLASSIFIED"
              and cached_job["categoryCode"] == "food" and len(stub.calls) == 2,
              "same owner + same merchant -> cache hit, provider calls still 2")

        # A different owner must not read owner A's cache: one more provider call.
        second = Api()
        email_b = f"category.verify.b.{secrets.token_hex(6)}@pam-uat.test"
        second.request(port, "/v1/accounts", "POST",
                       {"email": email_b, "password": password, "displayName": "Synthetic B",
                        "inviteCode": "isolated-synthetic"}, expect=201)
        second.token = second.request(port, "/v1/sessions", "POST",
                                      {"email": email_b, "password": password},
                                      expect=201)["accessToken"]
        second.request(port, "/v1/consents", "POST",
                       {"purpose": "AI_CATEGORIZATION", "decision": "GRANTED",
                        "noticeVersion": "v0.4-review"}, expect=201)
        foreign_tx = create_tx(second, "TEH UJI")
        foreign_job = job_for(enqueue(second, [foreign_tx["id"]])["jobs"], foreign_tx["id"])
        check("cache_is_per_owner", foreign_job["state"] == "CLASSIFIED"
              and foreign_job["categoryCode"] == "food" and len(stub.calls) == 3,
              "second owner same merchant -> one additional provider call, cache not shared")

        # USER category is never overwritten by the classifier.
        override = create_tx(api, "KOPI UJI", category_id=food, origin="USER")
        override_job = job_for(enqueue(api, [override["id"]])["jobs"], override["id"])
        reread = api.request(port, "/v1/transactions?month=2026-11&limit=200")
        stored = next(item for item in reread["items"]
                      if item["id"] == override["id"])
        check("user_override_untouched", override_job["state"] == "NEEDS_REVIEW"
              and override_job["reasonCode"] == "not_eligible"
              and stored["categoryOrigin"] == "USER"
              and stored["categoryId"] == food
              and len(stub.calls) == 3,
              "USER-categorized row -> not_eligible, category/origin unchanged, no call")

        # More than five ids in one request is rejected before any work.
        six = [create_tx(api, f"TOKO UJI {index}")["id"] for index in range(6)]
        too_many = enqueue(api, six, expect=400)
        check("max_five_ids", "1..5" in (too_many.get("message") or ""),
              "six transaction ids -> 400 invalid_request")
        check("no_trailing_provider_calls", len(stub.calls) == 3,
              "still 3 provider calls after the rejected batch")

        # Durable side: cache rows are owner-scoped and the ledger recorded the three calls.
        with sqlite3.connect(dbpath) as con:
            cache_rows = con.execute("SELECT COUNT(*), COUNT(DISTINCT owner_id) "
                                     "FROM category_cache").fetchone()
            usage_rows = con.execute("SELECT scope, attempts, actual_usd "
                                     "FROM category_usage WHERE bucket LIKE 'day:%'").fetchall()
        check("cache_rows_owner_scoped", cache_rows[0] == 3 and cache_rows[1] == 2,
              f"{cache_rows[0]} cache rows across {cache_rows[1]} owners")
        global_rows = [row for row in usage_rows if row[0] == "*"]
        owner_rows = [row for row in usage_rows if row[0] != "*"]
        check("category_ledger_accounted",
              len(global_rows) == 1 and global_rows[0][1] == 3
              and global_rows[0][2] > 0
              and sum(row[1] for row in owner_rows) == 3
              and abs(sum(row[2] for row in owner_rows) - global_rows[0][2]) < 1e-9,
              "category_usage: " + ", ".join(
                  f"{'global' if row[0] == '*' else 'owner'} attempts={row[1]} "
                  f"usd={row[2]:.8f}" for row in usage_rows))

        RESULT["provider_calls"] = stub.calls
        RESULT["environment"] = {
            "backend": f"isolated Go API on 127.0.0.1:{port}",
            "database": "throwaway SQLite file " + dbpath.name,
            "provider": "local Azure-compatible stub on loopback (no Azure call, no cost)",
            "app_traffic": "none; API-level verification",
        }
    except AssertionError as error:
        RESULT["overall"] = "FAIL"
        print(str(error), file=sys.stderr)
        if args.out:
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text(json.dumps(RESULT, indent=2) + "\n")
        return 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
        log.close()
        stub.stop()

    RESULT["overall"] = "PASS"
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(RESULT, indent=2) + "\n")
        print("evidence:", args.out)
    print("PASS: category reason codes, consent gate, dedup, per-owner cache, max-5, USER override")
    return 0


if __name__ == "__main__":
    sys.exit(main())
