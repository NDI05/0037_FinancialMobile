# Gmail polling contract E2E harness

Black-box acceptance harness for the Gmail API v2 polling contract
(`docs/GMAIL_API_CONTRACT.md` §9). It proves server behaviour through the public HTTP API with the
fake Gmail provider: no real Google account, no Azure, no Pub/Sub, no owner data.

## What it runs

| Scenario | What is proven |
| --- | --- |
| `normal` | Connect via fake OAuth (dev authorize/approve, no browser), 12-month `backfill` reaches `months_done=months_total=12`, stored transactions equal the fixture's unique message count with no duplicates, `POST /v1/gmail/sync` queues a DELTA and the immediate second call coalesces (`202 coalesced`) or is rejected with `429 sync_cooldown`, backend restart keeps the connection and resumes without duplicating transactions. |
| `history_expired` | The first `history.list` returns 404; the worker recovers via re-scan, keeps the new message, does not duplicate transactions, and ends `idle` with no permanent error. |
| `rate_limit` | The 2nd `list`/`history` call returns 429; the owner is never parked in `sync_state=error`, `needs_reauth` stays false, and retry/backoff is observable (`rate_limited` + retry timestamps, or recovery after internal retries). |
| all | Polling mode makes zero `users.watch`/`users.stop` calls (`GET /v1/gmail/dev/stats`: `watchCalls=0`, `stopCalls=0`, `categoryCalls=0`), `POST /v1/internal/gmail/push` is 404, and no API response leaks tokens, full mailbox addresses, message ids or raw email payloads. |

Exit codes: `0` every executed check passed, `1` at least one FAIL, `2` backend fake/polling mode
not available yet (capability probe; nothing is invented). Per-scenario PASS/FAIL/NOT RUN lines are
printed; `--json-out` writes the same result as JSON.

## Requirements

- macOS/Linux host with the backend Go worktree next to this repo:
  `../pam-wt-gmail-polling` (branch `work/gmail-polling`), or pass `--backend <path>`.
- Go toolchain through the worktree's `scripts/go.sh`. If the worktree has no `.toolchain/go`,
  `PAM_GOROOT` is auto-detected from `../pam-app/.toolchain/go` (override with `PAM_GOROOT`).
- Python 3.9+, standard library only (no `pip install`).
- **Never uses port 18090** (owner UAT server). Preferred port 18099, otherwise a free random port;
  `--port 18090` is refused and rewritten to 18099.
- A throw-away SQLite database and a synthetic fixture, both under a temp dir removed on exit
  (`--keep` to retain the run dir for debugging: server log + fixture JSON).

## Usage

```bash
cd pam-wt-gmail-qa
python3 scripts/qa/gmail/contract_e2e.py                 # build + all three scenarios
python3 scripts/qa/gmail/contract_e2e.py --scenarios normal
python3 scripts/qa/gmail/contract_e2e.py --keep --json-out /tmp/gmail-qa.json
```

The harness prints backend worktree + HEAD, isolated port, then one block per scenario. Example of
a passing line:

```
[PASS   ] normal/transaction count matches fixture
          unique=31 expected=31 duplicates=0 per_month_ok=True
```

Do not paste raw run output containing tokens into evidence docs; the harness never prints them.

## Backend interface assumed (agreed with the backend worker)

Fake-provider mode is opt-in: `APP_ENV=development` + `GMAIL_ALLOW_LOOPBACK_REDIRECT=true` +
`GMAIL_PROVIDER=fake` + redirect URI `http://127.0.0.1:<port>/v1/gmail/connect/callback`
(host loopback, path exact, port free).

Env set by the harness for each scenario:

| Variable | Value | Why |
| --- | --- | --- |
| `GMAIL_PROVIDER`, `GMAIL_MODE` | `fake`, `polling` | polling contract, no Pub/Sub/watches |
| `GMAIL_DEV_FIXTURE_FILE` | temp JSON (below) | deterministic fake mailbox |
| `GMAIL_DEV_SCENARIO` | `normal` / `history_expired` / `rate_limit` | scenario selection stays server-side |
| `GMAIL_WORKER_INTERVAL_SECONDS` | `1` | fast ticks for QA |
| `GMAIL_DELTA_INTERVAL_SECONDS` | `3600` | no surprise auto-delta during assertions |
| `GMAIL_SYNC_COOLDOWN_SECONDS` | `60` | contract default |
| `GMAIL_PAGE_SIZE` / `GMAIL_MAX_PAGES_PER_RUN` | `2` / `2` | forces multi-tick phased backfill |
| `GMAIL_BACKFILL_MONTHS` | `12` | contract §9.4 |
| `GMAIL_PUBSUB_TOPIC` etc. | empty | polling mode must not need Pub/Sub |

Fixture schema (synthetic values only, never owner data):

```json
{
  "profile": {"emailAddress": "pilot@pam.test", "historyId": "1000"},
  "messages": [
    {"id": "fx-2026-11-0", "eventAt": "2026-11-03T04:30:00Z",
     "amountMinor": 25000, "direction": "DEBIT", "merchantLabel": "QA MERCHANT"}
  ],
  "messagesByMonth": {"2026-11": {"pages": [["fx-2026-11-0", "fx-2026-11-1"]]}},
  "historyPages": [{"messageIds": ["fx-delta-2026-11-0"], "nextPageToken": "",
                    "latestHistoryId": "1010"}]
}
```

- One message id = one synthetic email = one transaction (PARSED). Months are WIB `YYYY-MM` keys;
  `eventAt` is set around midday WIB so the month is unambiguous.
- `historyPages` is the delta `history.list` order, with one new message so the delta imports once.
- Fake OAuth flow: `POST /v1/gmail/connect/start` returns a dev `authorization_url`
  (`/v1/gmail/connect/dev/authorize?state=...`); the harness opens it (200 consent page), calls
  `GET /v1/gmail/connect/dev/approve?state=...&approve=1` (302 to the loopback redirect URI), then
  the normal callback (302 to `pam://gmail/connected?status=success`). State is single-use, PKCE
  and token encryption are exercised — no bypass.
- `GET /v1/gmail/dev/stats` requires the owner Bearer token and returns non-PII counters
  (`watchCalls`, `stopCalls`, `listCalls`, `getCalls`, `historyCalls`, `refreshCalls`,
  `categoryCalls`, `exchanges`).

## What this harness does NOT prove

- Real Google OAuth, real Gmail quota/rate-limit behaviour, Pub/Sub push path, or the Google
  provider implementation (only the fake provider).
- The Android UI and deep-link handling (that is the Android/UI UAT gate).
- Category/Azure classification content (only that zero calls happen in polling mode).
- That fake-mode config validation matches the mandate for the real Google provider — the harness
  covers the fake/loopback relaxation only.
- Deployment, merge or owner UAT acceptance.

## Troubleshooting

- `exit 2` + `404: POST /v1/gmail/sync` → the tested backend worktree does not contain the polling
  contract v2 yet; ask the backend worker for the commit hash and rerun. Do not write evidence from
  this state beyond "NOT RUN".
- Backend exits early → `--keep` then read `<run-dir>/server.log`; most failures are config
  validation (fake opt-in flags, redirect host/path) which the log states explicitly.
- Port busy → the harness silently falls back to a free port; the used port is printed as a note.
  Never start a second server on 18090.
