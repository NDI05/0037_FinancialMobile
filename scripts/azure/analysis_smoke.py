#!/usr/bin/env python3
"""Isolated end-to-end smoke for POST /v1/analyses against Azure Foundry.

Proves, on a throwaway backend (own port, own SQLite file, synthetic account only):
  * LLM_PROVIDER=azure path answers a real Azure call and stores provider label/model/counters;
  * AI_PROCESSING consent is required (403 consent_required without it);
  * repeat POST/GET replays return the stored analysis without a second provider call;
  * freshness CURRENT -> STALE (transaction revision changed) -> HISTORICAL (consent withdrawn);
  * DAILY_AI_CEILING_USD is enforced before any provider call (429 daily_ceiling_reached);
  * analysis usage is accounted separately from the category classifier ledger;
  * token usage (prompt/completion/reasoning/total) is observable from the sanitized server log.

Secrets only come from ~/.config/pam/azure-classifier.env and are never printed or written.
No owner database, no owner account, no Ollama. Max provider spend: one small analysis request
plus one blocked-by-ceiling request (zero provider calls).

Usage:
  ./scripts/go.sh build -o /tmp/pam-analysis-smoke-api ./cmd/api   # from repo 0037-Backend-Financial:
  python3 scripts/azure/analysis_smoke.py --binary /tmp/pam-analysis-smoke-api \
      --out docs/evidence/assets/pm_go_2026-11-24/azure_analysis_smoke.json

Exit code 0 only when every assertion passes.
"""
import argparse
import json
import os
import re
import secrets
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

PRIVATE_PROFILE = Path.home() / ".config/pam/azure-classifier.env"
RESULT = {"purpose": "Isolated Azure analysis E2E; synthetic account only, not owner UAT",
          "checks": [], "server_log_excerpt": [], "environment": {}}


def load_private_profile() -> dict:
    if not PRIVATE_PROFILE.exists():
        sys.exit("private profile missing: ~/.config/pam/azure-classifier.env")
    env = {}
    for line in PRIVATE_PROFILE.read_text().splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        env[key.strip()] = value.strip()
    required = ["AZURE_CLASSIFIER_BASE_URL", "AZURE_CLASSIFIER_API_KEY",
                "AZURE_CLASSIFIER_DEPLOYMENT", "CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD",
                "CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD"]
    missing = [key for key in required if not env.get(key)]
    if missing:
        sys.exit("private profile is missing required keys: " + ", ".join(missing))
    return env


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def base_url(port: int) -> str:
    return f"http://127.0.0.1:{port}"


class Api:
    def __init__(self) -> None:
        self.token = None

    def request(self, port, path, method="GET", body=None, key=None, expect=200):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(
            base_url(port) + path, method=method, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        try:
            response = urllib.request.urlopen(request, timeout=40)
        except urllib.error.HTTPError as err:
            response = err
        with response:
            data = json.load(response)
            if response.code != expect:
                raise AssertionError(
                    f"{method} {path} -> HTTP {response.code} "
                    f"code={data.get('code')} message={data.get('message')}")
            return data


def wait_ready(port, proc, logpath):
    for _ in range(60):
        if proc.poll() is not None:
            sys.exit(f"backend exited during startup; see {logpath}")
        try:
            with urllib.request.urlopen(base_url(port) + "/readyz", timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, ConnectionError):
            pass
        time.sleep(0.25)
    sys.exit(f"backend did not become ready; see {logpath}")


def start_backend(binary, port, dbpath, logpath, env_overrides):
    env = {
        "HOME": str(Path.home()),
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "APP_ENV": "development",
        "PORT": str(port),
        "DATABASE_URL": f"file:{dbpath}",
        "LOG_LEVEL": "info",
        "REGISTRATION_INVITE_CODE": "isolated-synthetic",
    }
    env.update(env_overrides)
    log = Path(logpath).open("w")
    proc = subprocess.Popen([binary], env=env, stdout=log, stderr=subprocess.STDOUT)
    return proc, log


def stop_backend(proc, log):
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)
    log.close()


def analysis_request(summary, revision):
    """Build the client aggregate envelope the Android app sends. Server rebuilds its own."""
    totals = []
    for item in summary.get("categoryTotals") or []:
        totals.append({"categoryCode": item.get("categoryCode"),
                       "amountMinor": item.get("amountMinor", 0),
                       "transactionCount": item.get("transactionCount", 0)})
    payload = {
        "schemaVersion": 1,
        "monthId": summary["monthId"],
        # The client envelope accepts COMPLETE/PARTIAL/UNKNOWN; a live summary can be EMPTY
        # (no email coverage), which the app reports as UNKNOWN.
        "coverage": "UNKNOWN" if summary["coverage"] == "EMPTY" else summary["coverage"],
        "grossInflowMinor": summary["grossInflowMinor"],
        "grossOutflowMinor": summary["grossOutflowMinor"],
        "netCashFlowMinor": summary["netMinor"],
        "classifiedIncomeMinor": summary["classifiedIncomeMinor"],
        "classifiedExpenseMinor": summary["classifiedExpenseMinor"],
        "transactionCount": summary["transactionCount"],
        "uncategorizedCount": summary["uncategorizedCount"],
        "manualEntryCount": summary["manualEntryCount"],
        "nonIdrExcludedCount": 0,
        "categoryTotals": totals,
    }
    return {"monthId": summary["monthId"], "inputRevision": revision, "payload": payload}


def db_scalar(dbpath, query, params=()):
    with sqlite3.connect(dbpath) as con:
        row = con.execute(query, params).fetchone()
    return row


def db_rows(dbpath, query, params=()):
    with sqlite3.connect(dbpath) as con:
        return con.execute(query, params).fetchall()


def parse_log(logpath):
    requests = []
    usage = []
    for line in Path(logpath).read_text().splitlines():
        if not line.startswith("{"):
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        if record.get("msg") == "request":
            requests.append({"correlationId": record.get("correlationId"),
                             "method": record.get("method"), "route": record.get("route"),
                             "status": record.get("status"), "ms": record.get("ms")})
        elif record.get("msg") == "analysis provider usage":
            usage.append({key: record.get(key) for key in
                          ("correlationId", "provider", "promptTokens", "completionTokens",
                           "reasoningTokens", "totalTokens")})
    return requests, usage


def find_request(requests, route, status):
    for record in reversed(requests):
        if record.get("route") == route and record.get("status") == status:
            return record
    return None


def check(name, condition, detail):
    entry = {"name": name, "pass": bool(condition), "detail": detail}
    RESULT["checks"].append(entry)
    if not condition:
        raise AssertionError(f"FAILED {name}: {detail}")
    print(f"PASS {name}: {detail}")


def run_primary(binary, profile, args):
    port = free_port()
    folder = Path(tempfile.mkdtemp(prefix="pam-analysis-smoke-"))
    dbpath = folder / "smoke.db"
    logpath = folder / "backend.log"
    overrides = {
        "LLM_PROVIDER": "azure",
        "LLM_BASE_URL": profile["AZURE_CLASSIFIER_BASE_URL"],
        "LLM_API_KEY": profile["AZURE_CLASSIFIER_API_KEY"],
        "LLM_MODEL": profile["AZURE_CLASSIFIER_DEPLOYMENT"],
        "PRICE_PROMPT_PER_1K_USD": profile["CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD"],
        "PRICE_COMPLETION_PER_1K_USD": profile["CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD"],
        "LLM_MAX_INPUT_TOKENS": "4000",
        "LLM_MAX_OUTPUT_TOKENS": str(args.max_output_tokens),
        "LLM_TIMEOUT_MS": str(args.timeout_ms),
        "DAILY_AI_CEILING_USD": str(args.ceiling),
        "CATEGORY_LLM_ENABLED": "false",
        "CATEGORY_LLM_DAILY_CEILING_USD": str(args.category_ceiling),
    }
    proc, log = start_backend(binary, port, dbpath, logpath, overrides)
    try:
        wait_ready(port, proc, logpath)
        api = Api()
        email = f"analysis.smoke.{secrets.token_hex(6)}@pam-uat.test"
        password = os.environ.get("PAM_SMOKE_PASSWORD") or secrets.token_urlsafe(18)
        api.request(port, "/v1/accounts", "POST",
                    {"email": email, "password": password, "displayName": "Synthetic smoke",
                     "inviteCode": "isolated-synthetic"}, expect=201)
        api.token = api.request(port, "/v1/sessions", "POST",
                                {"email": email, "password": password}, expect=201)["accessToken"]
        categories = api.request(port, "/v1/categories")["categories"]
        food = next(item["id"] for item in categories if item["code"] == "food")
        created = api.request(port, "/v1/transactions", "POST", {
            "direction": "DEBIT", "source": "MANUAL", "amountMinor": 17500000,
            "currency": "IDR", "eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
            "fingerprint": secrets.token_hex(16), "parserVersion": "synthetic-smoke",
            "parserConfidence": 0.99, "description": "QRIS", "merchantLabel": "KOPI UJI",
        }, key="smoke-tx-" + secrets.token_hex(6), expect=201)
        transaction_id = created["id"]
        summary = api.request(port, "/v1/summaries/2026-11")
        revision = summary["inputRevision"]
        request = analysis_request(summary, revision)

        # Consent gate: no provider call, no stored analysis, no usage reservation.
        denied = api.request(port, "/v1/analyses", "POST", request,
                             key="smoke-key-" + secrets.token_hex(6), expect=403)
        check("consent_gate", denied["code"] == "consent_required",
              "403 consent_required without AI_PROCESSING consent")
        check("no_provider_before_consent",
              db_scalar(dbpath, "SELECT COUNT(*) FROM analyses")[0] == 0,
              "0 stored analyses before consent")

        api.request(port, "/v1/consents", "POST",
                    {"purpose": "AI_PROCESSING", "decision": "GRANTED",
                     "noticeVersion": "v0.4-review"}, expect=201)
        key = "smoke-analysis-" + secrets.token_hex(6)
        started = time.monotonic()
        first = api.request(port, "/v1/analyses", "POST", request, key=key)
        elapsed = round(time.monotonic() - started, 2)
        check("azure_response", first.get("freshness") == "CURRENT"
              and first.get("promptVersion") == "analysis-v1"
              and len(first.get("observations") or []) >= 1
              and len(first.get("limitations") or []) >= 1,
              f"CURRENT analysis-v1, observations={len(first.get('observations') or [])}, "
              f"limitations={len(first.get('limitations') or [])}, {elapsed}s")
        check("token_counters", isinstance(first.get("promptTokens"), int)
              and isinstance(first.get("completionTokens"), int)
              and first["promptTokens"] > 0 and first["completionTokens"] > 0,
              f"promptTokens={first.get('promptTokens')} "
              f"completionTokens={first.get('completionTokens')}")

        row = db_scalar(dbpath, "SELECT provider, model, regeneration_count, status "
                               "FROM analyses")
        check("stored_provider_label", row[0] == "azure-openai-compatible"
              and row[1] == profile["AZURE_CLASSIFIER_DEPLOYMENT"] and row[2] == 0
              and row[3] == "AVAILABLE",
              f"provider={row[0]} model={row[1]} regenerations={row[2]} status={row[3]}")
        usage = db_scalar(dbpath, "SELECT provider_calls, tokens_prompt, tokens_completion, "
                                  "cost_usd FROM usage_counters "
                                  "WHERE scope='*' AND bucket LIKE 'day:%'")
        check("provider_call_accounted", usage[0] == 1 and usage[1] > 0 and usage[3] > 0,
              f"providerCalls={usage[0]} promptTokens={usage[1]} "
              f"completionTokens={usage[2]} costUSD={round(usage[3], 8)}")
        check("category_ledger_untouched",
              db_scalar(dbpath, "SELECT COUNT(*) FROM category_usage")[0] == 0,
              "0 category_usage rows: analysis accounting separate from classifier ledger")

        replayed_get = api.request(port, "/v1/analyses/2026-11")
        check("get_replay", replayed_get == first, "GET returns the same stored analysis (CURRENT)")
        replayed_post = api.request(port, "/v1/analyses", "POST", request, key=key)
        check("post_replay", replayed_post == first, "same Idempotency-Key returns stored analysis")
        after_replay = db_scalar(dbpath, "SELECT provider_calls FROM usage_counters "
                                         "WHERE scope='*' AND bucket LIKE 'day:%'")
        check("no_second_provider_call", after_replay[0] == 1,
              f"providerCalls still {after_replay[0]} after GET + POST replay")
        check("single_analysis_row",
              db_scalar(dbpath, "SELECT COUNT(*) FROM analyses")[0] == 1,
              "1 stored analysis row; replay did not create another")

        # Freshness STALE: a transaction change bumps the month revision/dependency hash.
        api.request(port, "/v1/transactions", "POST", {
            "direction": "DEBIT", "source": "MANUAL", "amountMinor": 5000,
            "currency": "IDR", "eventAt": "2026-11-20T04:10:00Z", "categoryId": food,
            "fingerprint": secrets.token_hex(16), "parserVersion": "synthetic-smoke",
            "parserConfidence": 0.99, "description": "QRIS", "merchantLabel": "TEH UJI",
        }, key="smoke-tx-" + secrets.token_hex(6), expect=201)
        stale_get = api.request(port, "/v1/analyses/2026-11")
        check("freshness_stale", stale_get["freshness"] == "STALE"
              and stale_get["promptTokens"] == first["promptTokens"],
              f"remaining transaction change turns freshness into "
              f"{stale_get['freshness']} without a provider call")
        stale_replay = api.request(port, "/v1/analyses", "POST", request, key=key)
        check("stale_replay_without_provider", stale_replay["freshness"] == "STALE"
              and db_scalar(dbpath, "SELECT provider_calls FROM usage_counters "
                                    "WHERE scope='*' AND bucket LIKE 'day:%'")[0] == 1,
              "same-key replay returns STALE analysis; provider calls still 1")

        # Freshness HISTORICAL + consent not silently reused.
        api.request(port, "/v1/consents", "POST",
                    {"purpose": "AI_PROCESSING", "decision": "WITHDRAWN",
                     "noticeVersion": "v0.4-review"}, expect=201)
        historical = api.request(port, "/v1/analyses/2026-11")
        check("freshness_historical", historical["freshness"] == "HISTORICAL"
              and historical["status"] == "HISTORICAL",
              "withdrawn AI_PROCESSING consent marks the stored analysis HISTORICAL")
        after_withdrawal = api.request(port, "/v1/analyses", "POST", request,
                                       key="smoke-key-" + secrets.token_hex(6), expect=403)
        check("consent_recheck", after_withdrawal["code"] == "consent_required",
              "consent is re-checked on every POST (no stale grant reuse)")

        requests, provider_usage = parse_log(logpath)
        post_ok = find_request(requests, "/v1/analyses", 200)
        post_denied = find_request(requests, "/v1/analyses", 403)
        check("provider_usage_log", len(provider_usage) == 1
              and provider_usage[0]["provider"] == "azure-openai-compatible",
              f"one 'analysis provider usage' log line: {provider_usage[0] if provider_usage else None}")
        RESULT["environment"] = {
            "backend": "isolated Go API on 127.0.0.1:" + str(port),
            "database": "throwaway SQLite file " + dbpath.name,
            "provider": "azure (LLM_PROVIDER=azure) profile ~/.config/pam/azure-classifier.env",
            "ollama": "not used",
            "limits": {"dailyCeilingUSD": args.ceiling,
                       "categoryDailyCeilingUSD": args.category_ceiling,
                       "maxOutputTokens": args.max_output_tokens,
                       "timeoutMs": args.timeout_ms},
            "syntheticTransaction": transaction_id,
            "monthId": "2026-11",
            "revision": revision,
        }
        RESULT["server_log_excerpt"] = {
            "postAnalyses200": post_ok,
            "postAnalyses403": post_denied,
            "providerUsage": provider_usage,
            "requestLatencyMs": first and (post_ok or {}).get("ms"),
        }
        RESULT["freshness"] = {"first": first["freshness"], "afterMutation": stale_get["freshness"],
                               "afterConsentWithdrawal": historical["freshness"]}
        RESULT["tokens"] = {"prompt": first.get("promptTokens"),
                            "completion": first.get("completionTokens"),
                            "logReasoning": (provider_usage[0] if provider_usage else {}).get("reasoningTokens"),
                            "logTotal": (provider_usage[0] if provider_usage else {}).get("totalTokens"),
                            "dbPrompt": usage[1], "dbCompletion": usage[2],
                            "dbCostUSD": round(usage[3], 10)}
        RESULT["correlationIds"] = {"postAnalyses200": (post_ok or {}).get("correlationId"),
                                    "postAnalyses403": (post_denied or {}).get("correlationId")}
    finally:
        stop_backend(proc, log)
    return folder


def run_ceiling(binary, profile, args):
    """Second throwaway instance with a ceiling below one call: request is rejected before Azure."""
    port = free_port()
    folder = Path(tempfile.mkdtemp(prefix="pam-analysis-ceiling-"))
    dbpath = folder / "smoke.db"
    logpath = folder / "backend.log"
    tiny = 0.000001
    overrides = {
        "LLM_PROVIDER": "azure",
        "LLM_BASE_URL": profile["AZURE_CLASSIFIER_BASE_URL"],
        "LLM_API_KEY": profile["AZURE_CLASSIFIER_API_KEY"],
        "LLM_MODEL": profile["AZURE_CLASSIFIER_DEPLOYMENT"],
        "PRICE_PROMPT_PER_1K_USD": profile["CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD"],
        "PRICE_COMPLETION_PER_1K_USD": profile["CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD"],
        "LLM_MAX_INPUT_TOKENS": "4000",
        "LLM_MAX_OUTPUT_TOKENS": str(args.max_output_tokens),
        "LLM_TIMEOUT_MS": str(args.timeout_ms),
        "DAILY_AI_CEILING_USD": str(tiny),
        "CATEGORY_LLM_ENABLED": "false",
        "CATEGORY_LLM_DAILY_CEILING_USD": str(args.category_ceiling),
    }
    proc, log = start_backend(binary, port, dbpath, logpath, overrides)
    try:
        wait_ready(port, proc, logpath)
        api = Api()
        email = f"analysis.ceiling.{secrets.token_hex(6)}@pam-uat.test"
        password = os.environ.get("PAM_SMOKE_PASSWORD") or secrets.token_urlsafe(18)
        api.request(port, "/v1/accounts", "POST",
                    {"email": email, "password": password, "displayName": "Synthetic smoke",
                     "inviteCode": "isolated-synthetic"}, expect=201)
        api.token = api.request(port, "/v1/sessions", "POST",
                                {"email": email, "password": password}, expect=201)["accessToken"]
        api.request(port, "/v1/consents", "POST",
                    {"purpose": "AI_PROCESSING", "decision": "GRANTED",
                     "noticeVersion": "v0.4-review"}, expect=201)
        summary = api.request(port, "/v1/summaries/2026-11")
        request = analysis_request(summary, summary["inputRevision"])
        blocked = api.request(port, "/v1/analyses", "POST", request,
                              key="smoke-ceiling-" + secrets.token_hex(6), expect=429)
        requests, provider_usage = parse_log(logpath)
        blocked_log = find_request(requests, "/v1/analyses", 429)
        usage_rows = db_rows(dbpath, "SELECT scope, provider_calls, cost_usd FROM usage_counters")
        # The analysis rate-limit ledger (owner scope) records the attempt, but no provider cost
        # is reserved and no '*' (global spend) row is written before the ceiling check.
        check("daily_ceiling_blocked", blocked["code"] == "daily_ceiling_reached"
              and len(provider_usage) == 0
              and all(row[1] == 0 and row[2] == 0 for row in usage_rows),
              "429 daily_ceiling_reached before any provider reservation/call")
        RESULT["ceilingEnforcement"] = {
            "configuredDailyCeilingUSD": tiny,
            "categoryDailyCeilingUSD": args.category_ceiling,
            "responseCode": blocked["code"],
            "providerUsageLines": len(provider_usage),
            "usageRows": [{"scope": "owner" if row[0] != "*" else "global",
                           "providerCalls": row[1], "costUSD": row[2]} for row in usage_rows],
            "correlationId": (blocked_log or {}).get("correlationId"),
        }
    finally:
        stop_backend(proc, log)
    return folder


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", default="/tmp/pam-analysis-smoke-api",
                        help="path to the Go API binary (build with scripts/go.sh)")
    parser.add_argument("--out", default="",
                        help="write the sanitized evidence JSON to this path")
    parser.add_argument("--ceiling", type=float, default=0.25,
                        help="DAILY_AI_CEILING_USD for the primary instance (UAT profile value)")
    parser.add_argument("--category-ceiling", type=float, default=0.10,
                        help="CATEGORY_LLM_DAILY_CEILING_USD, kept separate on purpose")
    parser.add_argument("--max-output-tokens", type=int, default=1200)
    parser.add_argument("--timeout-ms", type=int, default=12000)
    args = parser.parse_args()
    if not Path(args.binary).exists():
        sys.exit(f"binary not found: {args.binary}; build it with scripts/go.sh build")
    profile = load_private_profile()
    started = time.time()
    try:
        run_primary(args.binary, profile, args)
        run_ceiling(args.binary, profile, args)
    except AssertionError as error:
        RESULT["durationSeconds"] = round(time.time() - started, 1)
        RESULT["overall"] = "FAIL"
        print(str(error), file=sys.stderr)
        if args.out:
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text(json.dumps(RESULT, indent=2, default=str) + "\n")
        return 1
    RESULT["durationSeconds"] = round(time.time() - started, 1)
    RESULT["overall"] = "PASS"
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(RESULT, indent=2, default=str) + "\n")
        print("evidence:", args.out)
    print("PASS: Azure analysis E2E, consent, replay/cache, freshness, ceiling, token usage")
    return 0


if __name__ == "__main__":
    sys.exit(main())
