# PAM backend (Go)

Target service for v0.4. The Node tree in `../backend/` remains the behavioural reference and
rollback path until parity is accepted. See [`docs/13_GO_BACKEND_MIGRATION.md`](../docs/13_GO_BACKEND_MIGRATION.md).

## Toolchain

Go **1.27.4** (darwin/arm64 development). The checkout keeps it in `.toolchain/go`, which is
ignored by Git:

```bash
cd pam-app
mkdir -p .toolchain && cd .toolchain
curl -L -o go.tar.gz https://go.dev/dl/go1.27.4.darwin-arm64.tar.gz
mkdir go && tar -xzf go.tar.gz -C go --strip-components=1
cd .. && ./scripts/go.sh version
```

## Run

```bash
cd pam-app/backend-go
APP_ENV=development PORT=8080 \
  DATABASE_URL=file:./data/pam.db \
  REGISTRATION_INVITE_CODE=pam-dev \
  ../scripts/go.sh run ./cmd/api
```

`DATABASE_URL` may be `file:...` or `libsql://...`; a libSQL token is read only from
`DATABASE_AUTH_TOKEN`. Never put the token in the APK, repository, logs, or a report.

## Test

```bash
../scripts/go.sh test ./...
../scripts/go.sh test -race ./...
../scripts/go.sh vet ./...
../scripts/go.sh build ./cmd/api
```

Fixtures in `../fixtures/interop/` are synthetic and prove password/AES-GCM compatibility with
Node in both directions. Cloud Turso/engine acceptance is **NOT RUN** until isolated test
credentials exist.

## Configuration

| Variable | Default (development) |
|---|---|
| `APP_ENV` | `development` |
| `PORT` | `8080` |
| `DATABASE_URL` | `file:./data/pam.db` |
| `DATABASE_AUTH_TOKEN` | empty |
| `REGISTRATION_INVITE_CODE` | `pam-dev` |
| `JWT_SECRET` | dev-only 32-byte value |
| `JWT_ISSUER` / `JWT_AUDIENCE` | `pam-api` / `pam-android` |
| `ACCESS_TOKEN_TTL_SECONDS` | `900` |
| `REFRESH_TOKEN_TTL_DAYS` | `30` |
| `REFRESH_PEPPER` | dev-only value |
| `DATA_ENCRYPTION_KEY` | dev-only derived key |
| `DATA_KEY_VERSION` | `1` |
| `LLM_PROVIDER` | `fake` (`openai` for an OpenAI-compatible endpoint) |
| `UNKNOWN_AI_CALL_COST_USD` | `0.001` conservative cost when the provider omits usage |
| `INTERNAL_SWEEP_TOKEN` | empty: `/v1/internal/sweep` is closed (404) |

Production refuses short/absent JWT/refresh secrets and a `libsql://` URL without a token.

## Implemented slice

- health/readiness; register/login/refresh/logout/me; JWT + rotated refresh;
- consent ledger; account fingerprint key; production encryption-key guard;
- transactions and categories CRUD, owner scoping, soft delete, revision, cursor search,
  idempotent manual create (`Idempotency-Key`);
- server-side summary from active transactions + legacy sealed summary upload;
- import job/batch/finalize with batch-key replay, atomic insert and month revision;
- budget plans/limits; canonical analysis snapshot, strict FR-37 validator, consent/deletion/
  session/revision guard, cache by consent+snapshot, one regeneration, fake/OpenAI adapter;
- usage/cost recording with a global daily USD ceiling; deletion request, sweep, session revoke
  and last-admin protection; token-gated internal sweep;
- attachments metadata (FR-59) and admin accounts/invitations/audit (FR-65).

## Search, paging and budget list contracts

All list endpoints take `limit` (1..100, default 50), an opaque `nextCursor` and, where noted,
`q`. Cursors are bound to owner + filters, so reusing one with another owner/filter is a clean
`400 {"code":"invalid_cursor"}`; `q` is trimmed once and filtered case-insensitively, so a
whitespace-only `q` behaves exactly like no filter.

### Categories (C10)

```http
GET /v1/categories?q=kopi&kind=EXPENSE&limit=20&cursor=...
Authorization: Bearer <access token>
```

`kind` is optional and one of `INCOME, EXPENSE, TRANSFER, UNCATEGORIZED` (anything else is
`400 invalid_request`). `q` matches the decrypted label and the code.

```json
{
  "categories": [
    {"id": "cat_...", "code": "kopi-susu", "label": "Kopi Susu", "kind": "EXPENSE",
     "isBuiltin": false, "revision": 0, "attributes": {}, "createdAt": "...", "updatedAt": "..."}
  ],
  "search": {"scanned": 14, "truncated": false},
  "nextCursor": "eyJidWlsdGluIjpmYWxzZSw..."
}
```

`search.scanned` counts the owner rows examined to build the page; the scan stops one match
past `limit`, so `truncated` is always `false` for this bounded owner-scoped list. The same
`search` envelope is returned by `GET /v1/transactions` (C05), where a text search examines at
most a fixed 2000-row window and may report `truncated: true`.

### Attachments (C20)

```http
GET /v1/attachments?transactionId=tx_...&mime=application/pdf&q=struk&limit=20
```

`mime` is one of the upload MIME allow-list (`400 invalid_request` otherwise) and `q` matches
the sealed file name/hash. Unfiltered pages fetch only `limit+1` rows; a `q` search widens the
window because the name is encrypted.

### Admin accounts (C25)

```http
GET /v1/admin/accounts?q=alice&role=USER&status=ACTIVE&limit=20&cursor=...
```

`role` is `ADMIN|USER` and `status` is `ACTIVE|SUSPENDED|DELETED` (invalid values are
`400 invalid_request`). The default listing excludes `DELETED`; pass `status=DELETED` to see
soft-deleted accounts explicitly.

### Budget plans by period (C15)

The budget screen needs the months around the one it shows, not only the current month:

```http
GET /v1/budget-plans?from=2026-09&to=2026-11&limit=50&cursor=...
Authorization: Bearer <access token>
```

`from`/`to` are inclusive `YYYY-MM` months, must be supplied together and `from <= to` (a
violation is `400 invalid_request`); omitting both lists every month for the owner. Plans come
newest-first. Each item is identical to `GET /v1/budget-plans/{monthID}`:

```json
{
  "plans": [
    {"monthId": "2026-11", "expectedIncomeMinor": 6000000, "plannedSavingsMinor": 1500000,
     "limits": [{"categoryId": "cat_...", "categoryCode": "food", "amountMinor": 2000000}],
     "attributes": {"currency": "IDR"}, "revision": 1,
     "gapMinor": 2500000, "totalLimitMinor": 2000000,
     "createdAt": "...", "updatedAt": "..."}
  ],
  "nextCursor": "MC4yMDI2LTExLj..."
}
```

`gapMinor = expectedIncomeMinor - plannedSavingsMinor - totalLimitMinor` (contract §9). The
per-category actual is already in `GET /v1/summaries/{monthID}` under
`categoryTotals[].{categoryCode, amountMinor}`, joined to `limits[].categoryCode`, so the
actual-vs-limit view needs no extra server field.

Remaining parity gap: full Android C2 remote repository. See the parity matrix.
