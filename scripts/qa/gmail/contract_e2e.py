#!/usr/bin/env python3
"""Black-box E2E for the Gmail polling contract v2 (GMAIL_API_CONTRACT.md §9).

The harness builds and runs the Go backend from a sibling worktree in fake-provider mode on an
isolated port with a throw-away SQLite database, then drives only the public HTTP API (the same
endpoints the Android client uses). It never uses a real Google account, never calls Azure, never
touches the owner's UAT server on :18090 or the owner database, and never prints tokens, full
mailbox addresses, message ids or fixture contents.

Scenarios (each reported PASS/FAIL/NOT RUN separately):
  normal          12-month backfill from the fixture, then a delta: stored transaction count
                  equals the fixture's unique message count (no duplicates); manual sync queues,
                  the immediate second call coalesces or returns 429 sync_cooldown; a backend
                  restart keeps the connection and resumes without duplicating transactions.
  history_expired the fake provider answers the first history.list with 404; the worker recovers,
                  keeps the new message, and does not duplicate transactions.
  rate_limit      the fake provider answers the 2nd list/history call with 429; the owner must not
                  be parked in a permanent FAILED state and retry/backoff must be observable.
  all scenarios   polling mode makes zero users.watch/users.stop calls (fake dev counters),
                  /v1/internal/gmail/push is 404, category/Azure calls are zero, and no API
                  response leaks tokens, full email, message ids or raw payloads.

Exit codes: 0 = every executed check passed; 1 = at least one FAIL; 2 = backend fake mode or its
dev fixtures are not available yet (no result is invented). Standard library only (Python 3.9+).
Usage and environment are documented in README.md.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

DEFAULT_BACKEND = os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..",
                 "pam-wt-gmail-polling")
)
DEFAULT_PORT = 18099
DATABASE_FILE = "pam-qa.db"
DEEP_LINK_PREFIX = "pam://gmail/connected"
BACKLOG_MONTHS = 12
WIB = timezone(timedelta(hours=7), "WIB")


# --------------------------------------------------------------------------------------- helpers
class Result:
    def __init__(self, scenario: str, name: str, status: str, detail: str) -> None:
        self.scenario = scenario
        self.name = name
        self.status = status
        self.detail = detail


class Report:
    def __init__(self) -> None:
        self.items: list[Result] = []
        self.notes: list[str] = []
        self.ready = True

    def add(self, scenario: str, name: str, passed: bool, detail: str) -> None:
        self.items.append(Result(scenario, name, "PASS" if passed else "FAIL", detail))

    def not_run(self, scenario: str, name: str, detail: str) -> None:
        self.items.append(Result(scenario, name, "NOT RUN", detail))
        if scenario in ("setup", "capability"):
            self.ready = False

    def note(self, text: str) -> None:
        self.notes.append(text)

    def failed(self) -> bool:
        return any(item.status == "FAIL" for item in self.items)

    def exit_code(self) -> int:
        if self.failed():
            return 1
        if not self.ready or any(item.status == "NOT RUN" for item in self.items):
            return 2
        return 0

    def print_summary(self) -> None:
        for item in self.items:
            print(f"[{item.status:<7}] {item.scenario}/{item.name}")
            if item.detail:
                print(f"          {item.detail}")
        for note in self.notes:
            print(f"note: {note}")
        counts: dict[str, int] = {}
        for item in self.items:
            counts[item.status] = counts.get(item.status, 0) + 1
        print("summary: " + ", ".join(f"{key}={counts[key]}" for key in sorted(counts)))


def find_free_port(preferred: int) -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", preferred))
            return preferred
        except OSError:
            pass
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def wib_months(count: int) -> list[str]:
    """Rolling one-year window as YYYY-MM, current month first, in Asia/Jakarta (contract §9.4)."""
    today = datetime.now(WIB)
    year, month = today.year, today.month
    months = []
    for _ in range(count):
        months.append(f"{year:04d}-{month:02d}")
        month -= 1
        if month == 0:
            month, year = 12, year - 1
    return months


def mask_email(address: str) -> str:
    match = re.match(r"^(.{1,2})[^@]*(@.*)$", address or "")
    return (match.group(1) + "***" + match.group(2)) if match else "<masked>"


def is_loopback(host: str | None) -> bool:
    return host in ("127.0.0.1", "localhost", "::1")


def error_code(text: str) -> str:
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return ""
    return str(data.get("code") or "") if isinstance(data, dict) else ""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401 - urllib API
        return None


class HttpClient:
    """JSON client that keeps redirects manual: the OAuth callback ends in a pam:// deep link."""

    def __init__(self, base_url: str, timeout: float = 20.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(NoRedirect)

    def raw(self, method: str, path: str, body: bytes | None = None,
            headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], str]:
        url = path if path.startswith("http") else self.base_url + path
        request = urllib.request.Request(url, data=body, method=method)
        request.add_header("Accept", "application/json")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                return response.status, dict(response.headers), \
                    response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read().decode("utf-8", "replace")

    def json(self, method: str, path: str, payload: dict | None = None,
             token: str | None = None) -> tuple[int, dict, str]:
        headers = {}
        body = None
        if payload is not None:
            body = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        status, _, text = self.raw(method, path, body, headers)
        try:
            data = json.loads(text) if text.strip() else {}
        except json.JSONDecodeError:
            data = {}
        return status, data, text


# -------------------------------------------------------------------------------------- backend
class Backend:
    """Builds and owns one isolated backend process and its throw-away database."""

    def __init__(self, worktree: str, port: int, run_dir: str, fixture_path: str,
                 scenario: str) -> None:
        self.worktree = worktree
        self.port = port
        self.run_dir = run_dir
        self.fixture_path = fixture_path
        self.scenario = scenario
        self.binary = os.path.join(run_dir, "pam-api")
        self.db_path = os.path.join(run_dir, DATABASE_FILE)
        self.log_path = os.path.join(run_dir, "server.log")
        self.process: subprocess.Popen | None = None
        self.internal_token = uuid.uuid4().hex
        self.registration_code = "pam-qa-invite"
        self.email = "qa.gmail." + uuid.uuid4().hex[:10] + "@example.invalid"
        self.password = "qa-" + uuid.uuid4().hex
        self.redirect_uri = f"http://127.0.0.1:{port}/v1/gmail/connect/callback"
        # Frozen once per backend instance: a restart must reuse the same JWT secret, encryption
        # key and database path. Regenerating them would invalidate the owner Bearer token and
        # make the sealed Gmail token undecryptable - a harness bug, not backend behaviour.
        self._env = self._build_env()

    # -- build ---------------------------------------------------------------------------------
    def build(self, report: Report) -> bool:
        go_script = os.path.join(self.worktree, "scripts", "go.sh")
        if not os.path.isfile(go_script):
            report.not_run("setup", "build backend", f"scripts/go.sh missing in {self.worktree}")
            return False
        env = dict(os.environ)
        if not os.environ.get("PAM_GOROOT"):
            for candidate in (
                os.path.join(self.worktree, ".toolchain", "go"),
                os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                             "..", "..", "..", "..", "pam-app", ".toolchain", "go")),
            ):
                if os.path.isfile(os.path.join(candidate, "bin", "go")):
                    env["PAM_GOROOT"] = candidate
                    break
        started = time.time()
        proc = subprocess.run([go_script, "build", "-o", self.binary, "./cmd/api"],
                              cwd=self.worktree, env=env, capture_output=True, text=True)
        if proc.returncode != 0:
            report.add("setup", "build backend", False,
                       f"go build failed in {os.path.basename(self.worktree)}: "
                       f"{proc.stderr.strip().splitlines()[-1][:300] if proc.stderr.strip() else '?'}")
            return False
        report.add("setup", "build backend", True,
                   f"go build ./cmd/api ok in {time.time() - started:.1f}s "
                   f"from {os.path.basename(self.worktree)}")
        return True

    # -- env -----------------------------------------------------------------------------------
    def env(self) -> dict[str, str]:
        """The frozen env of this backend instance; restart() reuses exactly these values."""
        return dict(self._env)

    def _build_env(self) -> dict[str, str]:
        """Isolated dev profile: fake provider, no Google/Azure, throw-away SQLite."""
        return {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": os.environ.get("HOME", self.run_dir),
            "PORT": str(self.port),
            "APP_ENV": "development",
            "LOG_LEVEL": "info",
            "DATABASE_URL": "file:" + self.db_path,
            "JWT_SECRET": uuid.uuid4().hex + uuid.uuid4().hex,
            "REFRESH_PEPPER": uuid.uuid4().hex,
            "JWT_ISSUER": "pam-api",
            "JWT_AUDIENCE": "pam-android",
            "ACCESS_TOKEN_TTL_SECONDS": "900",
            "REGISTRATION_INVITE_CODE": self.registration_code,
            "DATA_ENCRYPTION_KEY": base64.b64encode(os.urandom(32)).decode(),
            "DATA_KEY_VERSION": "1",
            "LLM_PROVIDER": "fake",
            "CATEGORY_LLM_ENABLED": "false",
            "ANALYSIS_ENABLED": "false",
            "RETENTION_SWEEP_ENABLED": "false",
            "RETENTION_DELETE_ENABLED": "false",
            "INTERNAL_SWEEP_TOKEN": self.internal_token,
            "GMAIL_ENABLED": "true",
            "GMAIL_MODE": "polling",
            "GMAIL_PROVIDER": "fake",
            "GMAIL_CLIENT_ID": "qa-fake-client",
            "GMAIL_CLIENT_SECRET": "qa-fake-secret",
            "GMAIL_REDIRECT_URI": self.redirect_uri,
            "GMAIL_SCOPE": "https://www.googleapis.com/auth/gmail.readonly",
            "GMAIL_APP_DEEP_LINK": DEEP_LINK_PREFIX,
            "GMAIL_ALLOW_LOOPBACK_REDIRECT": "true",
            "GMAIL_DEV_FIXTURE_FILE": self.fixture_path,
            "GMAIL_DEV_SCENARIO": self.scenario,
            "GMAIL_WORKER_INTERVAL_SECONDS": "1",
            "GMAIL_DELTA_INTERVAL_SECONDS": "3600",
            "GMAIL_SYNC_COOLDOWN_SECONDS": "60",
            "GMAIL_PAGE_SIZE": "2",
            "GMAIL_MAX_PAGES_PER_RUN": "2",
            "GMAIL_BACKFILL_MONTHS": str(BACKLOG_MONTHS),
            "GMAIL_PUBSUB_TOPIC": "",
            "GMAIL_PUSH_AUDIENCE": "",
            "GMAIL_PUSH_SERVICE_ACCOUNT": "",
        }

    # -- lifecycle -----------------------------------------------------------------------------
    def start(self) -> None:
        log = open(self.log_path, "a", encoding="utf-8")
        log.write(f"\n===== start {utc_now_iso()} scenario={self.scenario} port={self.port} "
                  f"redirect={self.redirect_uri} =====\n")
        log.flush()
        self.process = subprocess.Popen([self.binary], cwd=self.worktree, env=self.env(),
                                        stdout=log, stderr=subprocess.STDOUT)
        self._wait_ready()

    def _wait_ready(self) -> None:
        client = HttpClient(f"http://127.0.0.1:{self.port}")
        deadline = time.time() + 45
        while time.time() < deadline:
            if self.process is not None and self.process.poll() is not None:
                raise RuntimeError(f"backend exited early rc={self.process.returncode}: "
                                   f"{self.tail_log()}")
            try:
                status, _, _ = client.raw("GET", "/readyz")
            except OSError:
                status = 0
            if status == 200:
                return
            time.sleep(0.5)
        raise RuntimeError("backend did not become ready in 45s: " + self.tail_log())

    def tail_log(self, lines: int = 25) -> str:
        try:
            with open(self.log_path, encoding="utf-8", errors="replace") as handle:
                return "".join(handle.readlines()[-lines:])
        except OSError:
            return "<no log>"

    def log_text(self) -> str:
        try:
            with open(self.log_path, encoding="utf-8", errors="replace") as handle:
                return handle.read()
        except OSError:
            return ""

    def stop(self, timeout: float = 20.0) -> None:
        if self.process is None:
            return
        if self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        self.process = None

    def restart(self) -> None:
        self.stop()
        time.sleep(1.0)
        self.start()


# ----------------------------------------------------------------------------------------- flow
class Session:
    """One synthetic PAM account and its access token (kept in memory only)."""

    def __init__(self, client: HttpClient, email: str, password: str, invite: str) -> None:
        self.client = client
        self.email = email
        self.password = password
        self.invite = invite
        self.access_token = ""

    def register(self) -> tuple[int, dict, str]:
        return self.client.json("POST", "/v1/accounts", {
            "email": self.email,
            "password": self.password,
            "displayName": "QA Gmail Harness",
            "inviteCode": self.invite,
        })

    def login(self) -> tuple[int, dict, str]:
        status, data, text = self.client.json("POST", "/v1/sessions", {
            "email": self.email, "password": self.password,
        })
        self.access_token = data.get("accessToken") or data.get("access_token") or ""
        return status, data, text


def build_fixture(months: list[str]) -> tuple[dict, dict, str]:
    """Emit the fake-provider fixture in the schema published by the backend worker.

    Top-level: profile{emailAddress,historyId}, messages[], messagesByMonth{month:{pages:[[id]]}},
    historyPages[{messageIds,nextPageToken,latestHistoryId}]. Only synthetic values; ids are opaque
    and never returned by the PAM API. One fixture message maps to exactly one transaction.
    """
    messages = []
    messages_by_month = {}
    per_month = {}
    for month_index, month in enumerate(months):
        count = 2 + (month_index % 2)  # 2..3 transactions per month
        ids = []
        for offset in range(count):
            message_id = f"fx-{month}-{offset}"
            ids.append(message_id)
            messages.append({
                "id": message_id,
                "eventAt": f"{month}-{3 + offset * 4:02d}T04:30:00Z",  # ~11:30 WIB
                "amountMinor": 100_000 + month_index * 1_000 + offset,
                "direction": "DEBIT",
                "merchantLabel": "QA MERCHANT",
            })
        pages = [ids[index:index + 2] for index in range(0, len(ids), 2)]
        messages_by_month[month] = {"pages": pages}
        per_month[month] = count
    delta_id = f"fx-delta-{months[0]}-0"
    messages.append({
        "id": delta_id,
        "eventAt": f"{months[0]}-20T05:00:00Z",
        "amountMinor": 55_000,
        "direction": "DEBIT",
        "merchantLabel": "QA DELTA",
    })
    fixture = {
        "profile": {"emailAddress": "pilot@pam.test", "historyId": "1000"},
        "messages": messages,
        "messagesByMonth": messages_by_month,
        "historyPages": [{"messageIds": [delta_id], "nextPageToken": "",
                          "latestHistoryId": "1010"}],
    }
    expected = {
        "per_month": per_month,
        "base_total": sum(per_month.values()),
        "delta_id": delta_id,
        "masked_email": mask_email("pilot@pam.test"),
    }
    return fixture, expected, delta_id


class GmailScenarios:
    def __init__(self, backend: Backend, report: Report, args: argparse.Namespace,
                 expected: dict) -> None:
        self.backend = backend
        self.report = report
        self.args = args
        self.expected = expected
        self.client = HttpClient(f"http://127.0.0.1:{backend.port}")
        self.session = Session(self.client, backend.email, backend.password,
                               backend.registration_code)
        self.scenario = backend.scenario

    # -- capability ----------------------------------------------------------------------------
    def capability_probe(self) -> bool:
        """Detect contract-v2 support instead of guessing. 404 on /v1/gmail/sync = not ready."""
        status, _, text = self.client.raw("POST", "/v1/gmail/sync")
        if status == 404:
            self.report.not_run(
                "capability", "POST /v1/gmail/sync",
                "404: backend fake/polling mode from w-go is not in the tested worktree yet "
                "(contract v2 §9.3); no result invented")
            return False
        if status not in (401, 403, 409, 503):
            self.report.not_run("capability", "POST /v1/gmail/sync",
                                f"unexpected unauthenticated status {status}: {error_code(text)}")
            return False
        self.report.add("capability", "POST /v1/gmail/sync registered", True,
                        f"unauthenticated probe -> {status} (route exists)")
        # dev/stats is owner-authenticated (w-go decision); unauthenticated must be 401/403.
        status, _, text = self.client.raw("GET", "/v1/gmail/dev/stats")
        if status == 404:
            self.report.not_run("capability", "GET /v1/gmail/dev/stats",
                                "404; fake dev counters missing, cannot prove zero watch/stop")
            return False
        if status not in (401, 403):
            self.report.not_run("capability", "GET /v1/gmail/dev/stats",
                                f"anonymous request -> {status}; expected 401/403 owner-only")
            return False
        self.report.add("capability", "GET /v1/gmail/dev/stats registered", True,
                        f"anonymous probe -> {status} (owner Bearer required)")
        status, _, text = self.client.raw("GET",
                                          self.backend.redirect_uri.rsplit(
                                              "/v1/gmail/connect", 1)[0] + "/nonexistent")
        if status == 404:
            self.report.add("capability", "loopback redirect host accepts fake", True,
                            f"probe on {self.backend.redirect_uri} (port not 18090)")
        return True

    # -- setup ---------------------------------------------------------------------------------
    def authenticate(self) -> bool:
        status, _, text = self.session.register()
        if status != 201:
            self.report.add(self.scenario, "register account", False,
                            f"POST /v1/accounts -> {status} {error_code(text)}")
            return False
        self.report.add(self.scenario, "register account", True, "POST /v1/accounts -> 201")
        status, _, text = self.session.login()
        if status != 201 or not self.session.access_token:
            self.report.add(self.scenario, "login", False,
                            f"POST /v1/sessions -> {status} {error_code(text)}")
            return False
        self.report.add(self.scenario, "login", True,
                        "POST /v1/sessions -> 201 (token kept in memory, never printed)")
        return True

    def connect_gmail(self) -> bool:
        status, data, text = self.client.json("POST", "/v1/gmail/connect/start", {},
                                              self.session.access_token)
        if status != 200 or not data.get("authorization_url"):
            self.report.add(self.scenario, "connect start", False,
                            f"POST /v1/gmail/connect/start -> {status} {error_code(text)}")
            return False
        authorization_url = data["authorization_url"]
        parsed = urllib.parse.urlparse(authorization_url)
        self.report.add(self.scenario, "connect start", True,
                        f"200 authorization_url host={parsed.hostname or '?'} "
                        f"loopback={is_loopback(parsed.hostname)}")

        status, _, _ = self.client.raw("GET", authorization_url)
        if status != 200:
            self.report.add(self.scenario, "fake consent page", False,
                            f"GET authorization_url -> {status}")
            return False
        self.report.add(self.scenario, "fake consent page", True, "GET dev/authorize -> 200")

        query = urllib.parse.parse_qs(parsed.query)
        state = (query.get("state") or [""])[0]
        if not state:
            self.report.add(self.scenario, "dev approve", False,
                            "authorization_url carries no state")
            return False
        # w-go final layout: /v1/gmail/connect/dev/authorize -> /v1/gmail/connect/dev/approve.
        approve_path = self.approve_path(authorization_url, state)
        status, headers, _ = self.client.raw("GET", approve_path)
        location = headers.get("Location") or headers.get("location") or ""
        if status != 302 or not location:
            self.report.add(self.scenario, "dev approve", False,
                            f"GET dev/approve -> {status} without Location")
            return False
        self.report.add(self.scenario, "dev approve", True,
                        "302 back to the loopback GMAIL_REDIRECT_URI")
        if not is_loopback(urllib.parse.urlparse(location).hostname):
            self.report.add(self.scenario, "oauth callback", False,
                            "approve redirect left loopback")
            return False
        status, headers, _ = self.client.raw("GET", location)
        deep_link = headers.get("Location") or headers.get("location") or ""
        if status != 302 or not deep_link.startswith(DEEP_LINK_PREFIX):
            self.report.add(self.scenario, "oauth callback", False,
                            f"GET callback -> {status} Location={(deep_link or '?')[:80]}")
            return False
        result = (urllib.parse.parse_qs(urllib.parse.urlparse(deep_link).query)
                  .get("status") or ["?"])[0]
        self.report.add(self.scenario, "oauth callback", result == "success",
                        f"302 {DEEP_LINK_PREFIX}?status={result}")
        return result == "success"

    @staticmethod
    def approve_path(authorization_url: str, state: str) -> str:
        """Derive the dev approve URL from the dev authorize URL the backend handed out."""
        parsed = urllib.parse.urlparse(authorization_url)
        path = parsed.path
        for marker in ("/dev/authorize", "/dev/consent"):
            if path.endswith(marker):
                path = path[: -len(marker)] + "/dev/approve"
                break
        else:
            path = "/v1/gmail/connect/dev/approve"
        return path + "?state=" + urllib.parse.quote(state) + "&approve=1"

    # -- shared checks -------------------------------------------------------------------------
    def status(self) -> tuple[int, dict, str]:
        return self.client.json("GET", "/v1/gmail/status", None, self.session.access_token)

    def wait_status(self, predicate, seconds: float) -> dict:
        deadline = time.time() + seconds
        last: dict = {}
        while time.time() < deadline:
            status, last, _ = self.status()
            if status == 200 and predicate(last):
                return last
            time.sleep(1.0)
        return last

    def wait_backfill(self) -> tuple[bool, dict]:
        def done(data: dict) -> bool:
            progress = data.get("progress") or {}
            return (data.get("sync_state") in ("idle", None)
                    and progress.get("months_total") == BACKLOG_MONTHS
                    and progress.get("months_done") == BACKLOG_MONTHS)

        final = self.wait_status(done, self.args.backfill_timeout)
        progress = final.get("progress") or {}
        return bool(done(final)), final

    def check_status_shape(self) -> dict:
        status, data, text = self.status()
        if status != 200:
            self.report.add(self.scenario, "status readable", False,
                            f"GET /v1/gmail/status -> {status}")
            return {}
        progress = data.get("progress") or {}
        watch = data.get("watch_expires_at")
        shape_ok = (
            data.get("connected") is True
            and data.get("sync_state") in ("idle", "backfill", "delta", "error")
            and watch in (None, "")
            and data.get("needs_reauth") in (False, None)
            and progress.get("months_total") == BACKLOG_MONTHS
        )
        self.report.add(self.scenario, "status projection", shape_ok,
                        f"connected={data.get('connected')} sync_state={data.get('sync_state')} "
                        f"watch_expires_at={watch!r} needs_reauth={data.get('needs_reauth')} "
                        f"months_total={progress.get('months_total')} "
                        f"progress={(progress or {})}")
        masked = data.get("account_email_masked") or ""
        masked_ok = bool(masked) and ("@" not in masked or "*" in masked)
        self.report.add(self.scenario, "status email masked", masked_ok,
                        "masked address only" if masked_ok
                        else "missing/unmasked mailbox address")
        leaks = scan_pii(text, self.session.access_token)
        self.report.add(self.scenario, "status has no token/message id", not leaks,
                        ", ".join(leaks) if leaks else "no token, message id or raw payload")
        return data

    def check_transactions(self) -> int:
        months = list(self.expected["per_month"].keys())
        by_month: dict[str, list] = {}
        for month in months:
            items, cursor = [], ""
            for _ in range(30):
                path = f"/v1/transactions?month={month}&limit=100"
                if cursor:
                    path += "&cursor=" + urllib.parse.quote(cursor)
                status, data, _ = self.client.json("GET", path, None, self.session.access_token)
                if status != 200:
                    self.report.add(self.scenario, "transactions readable", False,
                                    f"GET /v1/transactions?month={month} -> {status}")
                    return -1
                items.extend(data.get("items") or [])
                cursor = data.get("nextCursor") or data.get("next_cursor") or ""
                if not cursor:
                    break
            by_month[month] = items
        ids = [item.get("id") for items in by_month.values() for item in items]
        unique = len(set(ids))
        duplicates = len(ids) - unique
        per_month_ok = all(len(by_month[m]) == self.expected["per_month"][m] for m in months)
        passed = unique == self.expected["base_total"] and duplicates == 0 and per_month_ok
        self.report.add(self.scenario, "transaction count matches fixture", passed,
                        f"unique={unique} expected={self.expected['base_total']} "
                        f"duplicates={duplicates} per_month_ok={per_month_ok}")
        return unique

    def check_transactions_after_delta(self) -> None:
        """The fixture's delta message must be imported exactly once."""
        months = list(self.expected["per_month"].keys())
        # Give the delta time to finish before counting.
        self.wait_status(
            lambda data: data.get("sync_state") in ("idle", None)
            and data.get("last_error_code") in (None, ""),
            self.args.delta_timeout)
        by_month: dict[str, list] = {}
        for month in months:
            status, data, _ = self.client.json(
                "GET", f"/v1/transactions?month={month}&limit=100", None,
                self.session.access_token)
            if status != 200:
                self.report.add(self.scenario, "delta transactions readable", False,
                                f"GET /v1/transactions?month={month} -> {status}")
                return
            by_month[month] = data.get("items") or []
        ids = [item.get("id") for items in by_month.values() for item in items]
        unique = len(set(ids))
        duplicates = len(ids) - unique
        expected_total = self.expected["base_total"] + 1
        per_month_ok = all(
            len(by_month[m]) == self.expected["per_month"][m] + (1 if m == months[0] else 0)
            for m in months
        )
        self.report.add(self.scenario, "delta imported once (no duplicate)", 
                        unique == expected_total and duplicates == 0 and per_month_ok,
                        f"unique={unique} expected={expected_total} duplicates={duplicates} "
                        f"per_month_ok={per_month_ok}")

    def check_polling_evidence(self, require_traffic: bool = True) -> None:
        status, stats, _ = self.client.json("GET", "/v1/gmail/dev/stats", None,
                                            self.session.access_token)
        if status != 200:
            self.report.add(self.scenario, "polling counts", False,
                            f"GET /v1/gmail/dev/stats (Bearer) -> {status}")
            return
        watch = stats.get("watchCalls")
        stop = stats.get("stopCalls")
        category = stats.get("categoryCalls")
        list_calls = stats.get("listCalls")
        get_calls = stats.get("getCalls")
        history_calls = stats.get("historyCalls")
        self.report.add(self.scenario, "polling: zero watch/stop calls",
                        watch == 0 and stop == 0,
                        f"watchCalls={watch} stopCalls={stop} listCalls={list_calls} "
                        f"getCalls={get_calls} historyCalls={history_calls}")
        self.report.add(self.scenario, "no Azure/category call", category == 0,
                        f"categoryCalls={category}")
        if require_traffic:
            # Skip after a restart: dev counters are in-memory and legitimately reset while the
            # persisted state means no new provider call is needed on the first ticks.
            self.report.add(self.scenario, "provider traffic happened",
                            bool(list_calls or get_calls or history_calls),
                            f"listCalls={list_calls} getCalls={get_calls} "
                            f"historyCalls={history_calls}")
        status, _, text = self.client.raw("POST", "/v1/internal/gmail/push", b"{}",
                                          {"Content-Type": "application/json"})
        self.report.add(self.scenario, "polling: push route 404", status == 404,
                        f"POST /v1/internal/gmail/push -> {status} {error_code(text)}")
        log = self.backend.log_text()
        watch_markers = re.findall(r"(?i)users\.watch|users\.stop|watch_renew", log)
        self.report.add(self.scenario, "server log has no watch marker", not watch_markers,
                        f"{len(watch_markers)} marker(s) in log" if watch_markers
                        else "no users.watch/users.stop marker in log")

    def check_sync_double(self) -> None:
        status1, first, text1 = self.client.json("POST", "/v1/gmail/sync", {},
                                                 self.session.access_token)
        status2, second, text2 = self.client.json("POST", "/v1/gmail/sync", {},
                                                  self.session.access_token)
        first_ok = status1 == 202 and first.get("queued") is True
        second_ok = (status2 == 202 and second.get("coalesced") is True) or (
            status2 == 429 and error_code(text2) == "sync_cooldown")
        self.report.add(self.scenario, "manual sync queues", first_ok,
                        f"#1 -> {status1} queued={first.get('queued')} "
                        f"coalesced={first.get('coalesced')}")
        self.report.add(self.scenario, "sync coalesced/cooldown", second_ok,
                        f"#2 -> {status2} coalesced={second.get('coalesced')} "
                        f"code={error_code(text2) or '-'}")
        self.report.add(self.scenario, "sync endpoint non-blocking", True,
                        "both calls returned without waiting for a job")

    # -- scenarios -----------------------------------------------------------------------------
    def run_normal(self) -> None:
        if not self.capability_probe() or not self.authenticate() or not self.connect_gmail():
            return
        self.check_status_shape()
        backfilled, status = self.wait_backfill()
        self.report.add(self.scenario, "backfill reaches 12/12 months", backfilled,
                        f"sync_state={status.get('sync_state')} "
                        f"progress={(status.get('progress') or {})}")
        if not backfilled:
            return
        self.check_transactions()
        self.check_sync_double()
        self.check_transactions_after_delta()
        self.check_polling_evidence()

        token = self.session.access_token
        self.backend.restart()
        self.client = HttpClient(f"http://127.0.0.1:{self.backend.port}")
        self.session.client = self.client
        status_after, data_after, _ = self.client.json("GET", "/v1/gmail/status", None, token)
        self.report.add(self.scenario, "restart keeps connection",
                        status_after == 200 and data_after.get("connected") is True,
                        f"GET /v1/gmail/status after restart -> {status_after} "
                        f"connected={data_after.get('connected')}")
        resumed = self.wait_status(
            lambda data: data.get("sync_state") in ("idle", None)
            and data.get("last_error_code") in (None, ""),
            self.args.delta_timeout)
        self.report.add(self.scenario, "restart keeps job resumable",
                        resumed.get("sync_state") in ("idle", None),
                        f"sync_state={resumed.get('sync_state')} "
                        f"progress={(resumed.get('progress') or {})}")
        ids = self.count_ids_after_restart()
        self.report.add(self.scenario, "restart does not duplicate transactions",
                        ids["unique"] == self.expected["base_total"] + 1 and ids["duplicates"] == 0,
                        f"unique={ids['unique']} expected={self.expected['base_total'] + 1} "
                        f"duplicates={ids['duplicates']}")
        self.check_polling_evidence(require_traffic=False)  # counters reset at restart

    def count_ids_after_restart(self) -> dict:
        months = list(self.expected["per_month"].keys())
        ids: list = []
        for month in months:
            status, data, _ = self.client.json(
                "GET", f"/v1/transactions?month={month}&limit=100", None,
                self.session.access_token)
            if status == 200:
                ids.extend(item.get("id") for item in data.get("items") or [])
        return {"unique": len(set(ids)), "duplicates": len(ids) - len(set(ids))}

    def run_history_expired(self) -> None:
        if not self.capability_probe() or not self.authenticate() or not self.connect_gmail():
            return
        self.check_status_shape()
        backfilled, status = self.wait_backfill()
        self.report.add(self.scenario, "initial backfill", backfilled,
                        f"progress={(status.get('progress') or {})}")
        if not backfilled:
            return
        before = self.check_transactions()
        self.check_sync_double()
        final = self.wait_status(
            lambda data: data.get("sync_state") in ("idle", None)
            and data.get("last_error_code") in (None, ""),
            self.args.delta_timeout)
        recovered = final.get("sync_state") in ("idle", None) \
            and final.get("last_error_code") in (None, "")
        self.report.add(self.scenario, "history 404 recovered", recovered,
                        f"sync_state={final.get('sync_state')} "
                        f"last_error_code={final.get('last_error_code')} "
                        f"progress={(final.get('progress') or {})}")
        if recovered:
            self.check_transactions_after_delta()
        self.report.add(self.scenario, "no transactions lost during recovery",
                        before == self.expected["base_total"],
                        f"pre-delta unique={before} expected={self.expected['base_total']}")
        self.check_polling_evidence()

    def run_rate_limit(self) -> None:
        if not self.capability_probe() or not self.authenticate() or not self.connect_gmail():
            return
        self.check_status_shape()
        backfilled, status = self.wait_backfill()
        self.report.add(self.scenario, "initial backfill", backfilled,
                        f"progress={(status.get('progress') or {})}")
        if not backfilled:
            return
        self.check_sync_double()
        # w-go: rate_limit sets last_error_code=rate_limited, needs_reauth=false, and either
        # requeues the delta (sync_state=delta) or finishes after internal retries (idle+null).
        deadline = time.time() + self.args.delta_timeout
        saw_retryable = False
        permanent = False
        final: dict = {}
        while time.time() < deadline:
            _, final, _ = self.status()
            if final.get("needs_reauth") is True:
                permanent = True
                break
            if final.get("last_error_code") in ("rate_limited", "provider_unavailable"):
                saw_retryable = True
            if final.get("sync_state") == "error":
                permanent = True
                break
            if final.get("sync_state") in ("idle", None) \
                    and final.get("last_error_code") in (None, ""):
                break
            time.sleep(1.0)
        self.report.add(self.scenario, "rate limit not permanent FAILED", not permanent,
                        f"sync_state={final.get('sync_state')} "
                        f"last_error_code={final.get('last_error_code')} "
                        f"needs_reauth={final.get('needs_reauth')}")
        recovered_after_retry = (final.get("sync_state") in ("idle", None)
                                 and final.get("last_error_code") in (None, ""))
        self.report.add(self.scenario, "retry/backoff observable",
                        saw_retryable or recovered_after_retry,
                        f"rate_limited_seen={saw_retryable} "
                        f"recovered_after_internal_retry={recovered_after_retry} "
                        f"last_attempt_at={final.get('last_attempt_at')} "
                        f"next_sync_at={final.get('next_sync_at')}")
        self.report.add(self.scenario, "rate limit does not ask for reauth",
                        final.get("needs_reauth") in (False, None),
                        f"needs_reauth={final.get('needs_reauth')}")
        self.check_polling_evidence()

    # -- order ---------------------------------------------------------------------------------
    def run(self) -> None:
        if self.scenario == "normal":
            self.run_normal()
        elif self.scenario == "history_expired":
            self.run_history_expired()
        elif self.scenario == "rate_limit":
            self.run_rate_limit()
        else:
            self.report.not_run(self.scenario, self.scenario, "unknown scenario")


TOKEN_PATTERNS = (
    (re.compile(r"eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"), "JWT"),
    (re.compile(r"refresh_token", re.IGNORECASE), "refresh_token"),
    (re.compile(r"fx-[0-9]{4}-[0-9]{2}-[0-9]+"), "fixture message id"),
    (re.compile(r"y[a-z0-9_\-]{20,}"), "opaque provider token"),
)


def scan_pii(text: str, access_token: str) -> list[str]:
    leaks = []
    if access_token and access_token in text:
        leaks.append("access token echoed")
    for pattern, label in TOKEN_PATTERNS:
        if pattern.search(text):
            leaks.append(label)
    return leaks


# ----------------------------------------------------------------------------------------- main
def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--backend", default=DEFAULT_BACKEND,
                        help="backend Go worktree (default: sibling pam-wt-gmail-polling)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help="preferred port; a free random port is used when busy; 18090 refused")
    parser.add_argument("--run-dir", default="", help="run dir; default: temp dir removed at end")
    parser.add_argument("--keep", action="store_true", help="keep the run dir and fixture")
    parser.add_argument("--skip-build", action="store_true", help="reuse <run-dir>/pam-api")
    parser.add_argument("--backfill-timeout", type=float, default=240.0)
    parser.add_argument("--delta-timeout", type=float, default=120.0)
    parser.add_argument("--scenarios", default="normal,history_expired,rate_limit",
                        help="comma separated: normal,history_expired,rate_limit")
    parser.add_argument("--json-out", default="", help="also write the summary as JSON here")
    args = parser.parse_args(argv)
    if args.port == 18090:
        args.port = DEFAULT_PORT  # owner UAT server must never be touched
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    report = Report()
    worktree = os.path.abspath(args.backend)
    if not os.path.isdir(worktree):
        report.not_run("capability", "backend worktree", f"not found: {worktree}")
        report.print_summary()
        return report.exit_code()

    run_dir = args.run_dir or tempfile.mkdtemp(prefix="pam-gmail-qa-")
    os.makedirs(run_dir, exist_ok=True)
    port = find_free_port(args.port)
    months = wib_months(BACKLOG_MONTHS)
    fixture, expected, _ = build_fixture(months)
    fixture_path = os.path.join(run_dir, "gmail-fake-fixture.json")
    with open(fixture_path, "w", encoding="utf-8") as handle:
        json.dump(fixture, handle, indent=2)
    report.note(f"backend worktree: {worktree} @ {git_head(worktree)}")
    report.note(f"isolated port {port}; db file:{DATABASE_FILE} in temp run dir; "
                f"3 scenario profiles: normal, history_expired, rate_limit")
    report.note("fixture contract: profile+messages+messagesByMonth+historyPages "
                "(synthetic merchant 'QA MERCHANT'/'QA DELTA'); expected per_month "
                f"{sum(expected['per_month'].values())} + 1 delta transactions")

    try:
        for scenario in [name.strip() for name in args.scenarios.split(",") if name.strip()]:
            backend = Backend(worktree, port, run_dir, fixture_path, scenario)
            print(f"\n=== scenario: {scenario} ===")
            try:
                if args.skip_build:
                    if not os.path.isfile(backend.binary):
                        report.not_run(scenario, "build backend", "--skip-build but binary missing")
                        continue
                elif not backend.build(report):
                    continue
                backend.start()
                GmailScenarios(backend, report, args, expected).run()
            except RuntimeError as error:
                report.not_run(scenario, "runtime", str(error)[:400])
            finally:
                backend.stop()
                for suffix in ("", "-wal", "-shm"):
                    leftover = backend.db_path + suffix
                    if os.path.exists(leftover):
                        os.remove(leftover)
    finally:
        if not args.keep:
            shutil.rmtree(run_dir, ignore_errors=True)
        else:
            print(f"\nkept run dir: {run_dir}")

    report.print_summary()
    if args.json_out:
        payload = {
            "generated_at": utc_now_iso(),
            "backend_worktree": worktree,
            "backend_head": git_head(worktree),
            "items": [item.__dict__ for item in report.items],
        }
        with open(args.json_out, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2)
    return report.exit_code()


def git_head(worktree: str) -> str:
    try:
        proc = subprocess.run(["git", "-C", worktree, "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True)
        return proc.stdout.strip() or "?"
    except OSError:
        return "?"


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
