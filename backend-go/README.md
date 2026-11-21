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

Production refuses short/absent JWT/refresh secrets and a `libsql://` URL without a token.

## Implemented slice

- health/readiness; register/login/refresh/logout/me; JWT + rotated refresh;
- consent ledger; account fingerprint key;
- transactions and categories CRUD, owner scoping, soft delete, revision, cursor search;
- server-side summary from active transactions + legacy sealed summary upload;
- budget plans/limits; analysis with consent/allowlist/cache/idempotency/fake/OpenAI adapter;
- deletion request + write blocking.

Not yet ported: import job/batch, admin users/invitations, deletion sweep worker, daily USD
ceiling, full Node analysis-validation/regeneration parity. See the parity matrix.
