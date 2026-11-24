# Gmail polling contract E2E — fake provider (2026-11-24)

Gate: Gmail API v2 polling contract (`docs/GMAIL_API_CONTRACT.md` §9), black-box HTTP harness.
Profile: **fake Gmail provider, polling mode, isolated QA backend** — not owner UAT, not real
Google.

- Date/time: 2026-11-24 ~16:48–16:50 WIB (run output timestamps are UTC in the backend log)
- Tested backend: `0037-Backend-Financial` @ **5d76c7f** branch `integration/2026-11-24`
  (cherry-pick of worker `work/gmail-polling` commits `450bc98`, `14e190d`, `f618289`, `f412c50`;
  worker head was `f412c50098811b7a73b79af1c0a9e7808427911b`)
- Harness: `scripts/qa/gmail/contract_e2e.py` from branch lane `work/gmail-qa-harness`
  (v1 `740c573`; branch sudah di-merge lalu dihapus, riwayat lewat SHA)
- Environment: `APP_ENV=development`, `GMAIL_PROVIDER=fake`, `GMAIL_MODE=polling`,
  `GMAIL_ALLOW_LOOPBACK_REDIRECT=true`, redirect `http://127.0.0.1:18099/v1/gmail/connect/callback`
  (isolated free port, **never port 18090**), throw-away SQLite DB, synthetic fixture
  (`profile+pilot@pam.test`, 30 base transactions across the rolling 12 WIB months + 1 delta
  message), `LLM_PROVIDER=fake`, `CATEGORY_LLM_ENABLED=false`, Azure/analysis disabled.
- Result: **exit code 0 — PASS=82, FAIL=0, NOT RUN=0.**
- Re-run after audit round 4/5 fixes: `0037-Backend-Financial` @ **1290910** (same harness,
  isolated port 18099, fresh DB/fixture) → **PASS=82, FAIL=0, exit 0**. The harness has not yet
  been extended to assert the new 429 `retryable`/camelCase shape or the cleared marker; those are
  covered by the Go unit/HTTP tests in `internal/gmail` and `internal/httpapi`.
- Re-run pada integrasi `138b5c3` (setelah lane SSO), `7bb16f0` (parser `gmail-livin-v2`), dan
  HEAD `00bb515` (tes semantik cooldown): masing-masing **PASS=82, FAIL=0, exit 0** pada port
  terisolasi 18099 dengan DB/fixture baru (log ronde 10/15; lihat PROGRESS_DAILY).

## Command

```bash
cd pam-wt-gmail-qa
export PAM_GOROOT=.../pam-app/.toolchain/go
python3 scripts/qa/gmail/contract_e2e.py \
  --backend .../0037-Backend-Financial \
  --json-out /tmp/gmail-qa-5d76c7f.json
```

The harness builds `./cmd/api` from the backend checkout, starts it on the isolated port, and drives
only the public API (register/login, connect start → fake consent → dev approve → callback, status,
transactions, sync). Backend process, SQLite DB(+wal/shm), fixture and temp run dir are removed at
the end (verified: `:18099` free, no `/tmp/pam-gmail-qa-*`, owner `:18090` untouched).

## Scenario results

| Scenario | Gate | Key observed result |
| --- | --- | --- |
| capability | **PASS** | `POST /v1/gmail/sync` exists (anon → 401), `GET /v1/gmail/dev/stats` owner-Bearer only (anon → 401), fake loopback config accepted on an arbitrary port |
| normal | **PASS** (27/27 checks) | OAuth callback → `302 pam://gmail/connected?status=success`; backfill `months_done=12/12`; transactions `unique=30 expected=30 duplicates=0`; sync #1 `202 queued=true`, #2 `429 sync_cooldown`, both non-blocking; delta imported exactly once (`unique=31`, no duplicate); restart keeps connection and state, still `unique=31 duplicates=0`; `watchCalls=0 stopCalls=0`; `categoryCalls=0`; `/v1/internal/gmail/push` → 404 |
| history_expired | **PASS** (22/22 checks) | first `history.list` 404 recovered; status ends `sync_state=idle last_error_code=null`; pre-delta `unique=30`, post-delta `unique=31`, per-month match, no duplicates; `watchCalls=0 stopCalls=0 historyCalls=2` |
| rate_limit | **PASS** (21/21 checks) | 2nd provider list/history call answered 429; never `sync_state=error`, `needs_reauth=false`; internal retries recovered within the tick (`sync_state=idle last_error_code=null`, `last_attempt_at=2026-11-24T09:49:19Z next_sync_at=2026-11-24T10:49:19Z`); `watchCalls=0 stopCalls=0` |

Assertions also verified in every scenario: `connected=true`, `watch_expires_at=null` (no watch in
polling), `progress.months_total=12`, masked account email only, and no token/full email/message
id/raw-payload leak in `GET /v1/gmail/status`.

## What is proven

- Contract v2 polling lifecycle works end-to-end through the public API with a fake provider:
  OAuth connect with single-use state/PKCE/exchange, 12-month phased backfill, delta import,
  manual sync queue/coalesce/cooldown, restart resume without duplicate transactions.
- `history_expired` (history 404) recovers via re-scan without losing the new message and without
  duplicates.
- Provider 429/rate-limit does not park the owner in a permanent FAILED state and does not demand
  re-authentication.
- Polling mode performs no `users.watch`/`users.stop`, exposes no Gmail push route, and makes no
  Azure/category call.
- Status projection hides sensitive data.

## Remaining gaps (not failures of this gate)

- Fake provider only: real Google OAuth, real quota/rate-limit behaviour, `invalid_grant →
  needs_reauth`, and the opt-in Pub/Sub push path are **not covered** here.
- In this run the `rate_limited` status projection was never surfaced because the backend's
  internal retries (max 3, exponential) succeeded inside the same tick; only the "recovers after
  internal retry / never permanent, no reauth" outcome is proven. Deterministically observing the
  projected `sync_state=delta + last_error_code=rate_limited` branch would need `GMAIL_MAX_PAGES_PER_RUN=1`
  plus a slower failure schedule — still valid per the agreed interface but not evidence collected today.
- Dev/provider counters are in-memory, so after the restart step they reset (`listCalls=0`); the
  post-restart zero-watch assertion is valid, "provider traffic happened" is only asserted
  pre-restart.
- No Android UI/deep-link handling and no owner-UAT profile (Azure category classifier, Turso)
  evidence. Those are separate gates.

## Reproduction notes

- Harness README: [`scripts/qa/gmail/README.md`](../../scripts/qa/gmail/README.md) (env matrix,
  fixture schema, exit codes 0/1/2, troubleshooting).
- Expected fixture totals: 30 base transactions (2–3 per WIB month) + 1 delta = 31 unique; a
  duplicate anywhere fails the "imported once"/"does not duplicate" checks.
- Do not reuse this evidence as owner-UAT or production proof; it is a fake-provider, isolated-port
  contract gate.
