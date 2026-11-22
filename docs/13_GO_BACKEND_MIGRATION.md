# Migrasi backend PAM ke Go — status dan matriks parity

Status: **C1 backend lokal PASS** (penutupan review R1–R5), 22 November 2026. Cloud/emulator/Gmail
tetap NOT RUN; C2 Android belum dikerjakan.
Keputusan pemilik: **backend PAM diganti ke Go** (21 November 2026). Turso Cloud Free/libSQL tetap
target database. Android tetap Kotlin/Compose/Navigation Compose/MVVM/Hilt.

- Baseline yang diterima worker: branch `design/v0.4-cloud-contract`, HEAD `71e5c33` (paket B).
- Branch kerja: `feat/go-backend`.
- Backend Node di `backend/` **tetap** sebagai referensi perilaku dan jalur rollback.
- Implementasi baru: `backend-go/`.
- Toolchain: **go1.27.4 darwin/arm64**, diunduh dari `go.dev`, disimpan di `.toolchain/go` (ignored),
  dijalankan lewat `scripts/go.sh`. `go.sum` mencatat dependency.

## 1. Struktur

```
backend-go/
├── cmd/api/                  bootstrap, graceful shutdown, timeouts
├── internal/config/          environment + production secret checks
├── internal/crypto/          AES-256-GCM format Node-compatible
├── internal/auth/            scrypt, JWT HS256, refresh hashing
├── internal/store/           database/sql libSQL, migrations, owner-scoped queries
│   └── migrations/           001–003 SQL copied from the applied history + v0.4
├── internal/finance/         deterministic BR-01…BR-06 calculator
├── internal/llm/             provider port: fake + OpenAI-compatible adapter
└── internal/httpapi/         net/http routes, middleware, validation, error envelope
```

Driver: `github.com/tursodatabase/libsql-client-go/libsql` + `modernc.org/sqlite` (pure Go) for
the local `file:` path. Both go through `database/sql`; no cgo, no Node process.

## 2. Matriks parity

Legend: **PASS** = implemented and tested in Go; **PARTIAL** = behaviour exists but a documented
subset/limitation remains; **NOT PORTED** = Node route remains the only implementation; **CHANGED** =
deliberate v0.4 contract change.

| Endpoint / perilaku | Node | Go | Bukti / catatan |
|---|---|---|---|
| `GET /healthz` | PASS | **PASS** | `httpapi` test + smoke |
| readiness database | — | **PASS** | `GET /readyz` ping |
| `POST /v1/accounts` | PASS | **PASS** | invite constant-time, scrypt, duplicate 409, role USER |
| `POST /v1/sessions` | opaque token | **CHANGED** | JWT access ≤15 mnt + opaque refresh; legacy `expiresAt`/`displayName` retained |
| `POST /v1/transactions` | 201 each retry | **PASS** | `Idempotency-Key` + hash + result row atomically; same key/different body 409; parallel one winner; replay after delete returns tombstone |
| `POST /v1/import-jobs`, batch, finalize | lokal/Room | **PASS** | owner/consent/deletion, ≤100 candidates, event-month boundary, atomic batch, batch-key/finalize retry, duplicate fingerprint, month revision, coverage, `inputRevision` |
| `POST /v1/sessions/refresh` | — | **PASS** | rotation atomik, replay cabut keluarga, concurrent claim |
| `DELETE /v1/sessions` | PASS | **PASS** | revoke sesi + refresh family |
| `GET /v1/me` | PASS | **PASS** | profil/role/consent; activity null (belum ada agregasi) |
| `GET/POST /v1/consents` | PASS | **PASS** | append-only, latest per purpose |
| `PUT/GET /v1/summaries/{month}` | PASS | **PASS** | payload sealed v0.3 + computed v0.4 (gross, classified, coverage, revision) |
| Transaksi CRUD | lokal/Room | **PASS** | owner-scoped, soft delete, revision, cursor, search pasca-dekripsi |
| Kategori CRUD + reassign | lokal/Room | **PASS** | 11 built-in, field dinamis, reassign atomik |
| Budget plan/limits | PASS | **PASS** | integer minor, gap, revision, hard delete |
| `POST /v1/analyses` | PASS | **PASS (C1)** | typed client schema + canonical server payload, stale revision 409, validator, one regeneration, dependency hash for target/prior/category/budget/coverage, atomic finalization, cache by consent/snapshot/dependency, freshness metadata |
| `GET /v1/analyses/{month}` | PASS | **PASS** | latest sealed |
| `POST/GET /v1/deletion-requests` | PASS | **PASS** | password reauth + literal confirmation, immediate session revoke, last-admin precheck, receipt status after auth loss, commit-time write guard, sweep removes data/keys |
| `POST /v1/internal/sweep` | PASS | **PASS** | token-gated internal sweep |
| `POST /v1/import-jobs` + batch | — | **PASS** | lihat baris import di atas |
| Admin users/invitations | — | **NOT PORTED** | target D; DDL + guard sudah ada |
| `POST /v1/internal/sweep` | PASS | **PASS** | token-gated; last-admin guard |
| Rate limit login | PASS | **PASS** | 5/15 menit; address class; no existence oracle |
| Analysis quota | PASS | **PASS** | hour/day reservation + global daily USD ceiling; used+outstanding+next check; max input/output reservation; accounting failure explicit; max-call cost is a soft provider ceiling |
| Owner isolation | PASS | **PASS** | cross-owner GET/list/reassign diuji |
| Cache & idempotency | PASS | **PASS** | cache revision/provider/model; idempotency hash |
| Concurrency | PASS | **PASS** | refresh claim, quota transaction; `go test -race` |

## 3. Kompatibilitas lintas bahasa

| Artefak | Node → Go | Go → Node | Status |
|---|---|---|---|
| Password scrypt (`N=32768,r=8,p=1`, 64 byte, salt 16) | fixture diverifikasi Go | fixture diverifikasi Node | **PASS** |
| AES-256-GCM (`ct|iv|tag`, AAD `purpose|owner|v`) | fixture Node dibuka Go | fixture Go dibuka Node | **PASS** |
| Owner ID (`u_` + sha256(`pam-owner:`+email) 32 hex) | fixture | — | **PASS** |
| JSON/enum/timestamp/amount | kontrak v0.4 | — | **PASS** pada tes HTTP; Android v0.3 membaca `accessToken`/`expiresAt` |
| Schema/migrasi 1–3 | SQL disalin dari history | Node masih menjalankan migrasi sendiri | **PASS lokal**; Go menjadi runner target, cloud NOT RUN |

Fixture ada di `fixtures/interop/` dan hanya memakai password/kunci development sintetis.

## 4. Auth transition

- Akun/password tetap kompatibel: hash Node dapat diverifikasi Go dan sebaliknya.
- Sesi opaque lama di tabel `sessions` **tidak** diterima Go. Go memakai `auth_sessions` +
  `refresh_tokens`; pengguna lama harus login ulang sekali. Tidak ada data keuangan yang hilang.
- Access JWT berisi `iss,aud,sub,sid,role,ver,iat,exp,jti`; middleware memeriksa signature,
  algorithm (`HS256` saja), issuer/audience, expiry, sesi server, status akun, `auth_version`, dan
  role. Logout/role change/revocation membuat token lama ditolak tanpa menunggu TTL.
- Android v0.3 tetap dapat login karena respons menyertakan `accessToken`, `expiresAt`, dan
  `displayName`; field v0.4 (`refreshToken`, `accessExpiresAt`, `user`) ditambahkan.

## 5. Migrasi dan cutover

- Go memakai `schema_migrations` dan SQL 001–003. Migrasi 001–002 disalin persis dari Node;
  003 adalah v0.4 yang sudah diuji Node. **Go menjadi satu-satunya runner** untuk database baru;
  Node tidak dijalankan bersamaan pada database yang sama.
- Database Node yang sudah ada dapat dibuka Go; tabel baru v0.4 ditambahkan dan baris lama tetap.
  Uji upgrade lokal PASS. Cloud Turso/engine **NOT RUN** sampai kredensial uji tersedia.
- Rollback ke Node: Node membaca `schema_migrations` 1–5 dan melewati migrasi yang sudah ada;
  tabel baru 004/005 tidak mengubah kolom yang dibaca Node. Token JWT tetap harus diganti re-login,
  dan transaksi/kategori v0.4 harus diekspor/direkonsiliasi sebelum APK lama. Node tidak boleh
  menjalankan migrasinya sendiri pada database yang sudah dimigrasikan Go tanpa backup.
- Jalankan Node dan Go pada **port + dataset terpisah** selama perbandingan. Jangan arahkan dua
  runner ke satu database.

## 6. Bukti uji

```bash
cd pam-app/backend-go
../scripts/go.sh test ./...
../scripts/go.sh test -race ./...
../scripts/go.sh vet ./...
../scripts/go.sh build ./cmd/api

cd pam-app/backend
npm test          # 124 tes termasuk 2 tes interop Node↔Go
```

Hasil 22 November 2026 (C1 penutup): **42 tes Go PASS**, `go test -race` PASS, `go vet`/build
PASS; Node **124 PASS**. Android terhadap Go di emulator API 36 (sebelum C1 lanjutan): register 201
→ login 201 → dua consent 201 → Ringkasan. Endpoint transaksi/import/analisis/anggaran/deletion
diuji lewat HTTP Go; UI Android untuk fitur itu masih v0.3 sampai C2.

## 7. Cakupan C1 lanjutan

| Temuan review | Perbaikan | Bukti |
|---|---|---|
| A. production tanpa encryption key | produksi menolak key kosong/invalid/32-byte salah/dev; juga menolak secret auth/invite development | `internal/config/config_test.go` |
| B. manual create tidak idempoten | `http_idempotency` + hash + result id, satu transaksi; 201/200; conflict 409; parallel one winner; delete replay tombstone | `TestManualTransactionIdempotency`, `TestIdempotencyFailureDoesNotReserveKey` |
| C. consent/deletion race | finalisasi analysis dalam write transaction memeriksa sesi, consent terbaru, deletion, revisi; withdrawal menandai report HISTORICAL; cache by consent+snapshot | `TestAnalysisRaceWithConsentAndDeletion`, `TestAnalysisLifecycleWithConsentWithdrawal` |
| D. nominal salah 100× / payload bebas | validator Rp→minor, bare-minor ambiguity, metric keys, empty arrays, suggestions; client schema strict + safe category codes; provider menerima payload canonical server | `TestValidatorRupiahUsesMajorUnits`, `TestValidatorRejectsEmptyAndUnsafeOutput`, `TestClientPayloadRejectsCanaryAndMissingNestedCount`, `TestAnalysisUsesCanonicalServerPayloadAndStaleRevisionFails` |
| E. usage 0 / ceiling | reservation provider per call, reconcile actual vs reserved, unknown usage counted, daily USD ceiling | `TestProviderUsageIsRecordedForEveryCall`, `TestDailyCostCeilingStopsMoreProviderCalls`, `TestInvalidAnalysisIsBilledButNotStored`, `TestAnalysisRegeneratesAtMostOnce` |
| F. concurrent refresh test weak | exactly one 200 and one 401 asserted | `TestRefreshRotationReplayAndConcurrentUse` |
| C1-R1 month/revision | event month checked before commit, batch revision returned, finalize retry | `TestImportRejectsEventOutsideJobMonthAtomically`, `TestImportDuplicateFingerprintAcrossMonthsAndFinalizeRetry` |
| C1-R2 dependencies | target/prior/category/budget/coverage token recomputed atomically | `TestAnalysisRaceWithConsentAndDeletion` (7 actions) |
| C1-R3 freshness | GET/replay metadata + CURRENT/STALE/HISTORICAL | `TestAnalysisFreshnessOnReadReplayAndDependencies` |
| C1-R4 reservation | used+outstanding+next, no reservation over ceiling, accounting context | `TestProviderReservationChecksUsedPlusNext`, `TestProviderReservationConcurrentBudget`, `TestReservationRejectsCeilingBelowOneCallBeforeProvider` |
| C1-R5 deletion | reauth/confirmation, session revoke, receipt, last admin | `TestDeletionRequiresReauthConfirmationAndRevokesSessions`, `TestDeletionSweepRemovesFinanceAndRevokesSessions`, `TestLastAdminCannotBeSwept` |

## 8. Risiko/pekerjaan tersisa

1. Admin (FR-65), lampiran (FR-59), dan UI lima CRUDS tetap tahap D/C2; backend endpoint admin belum
   diport.
2. Android C2 belum memakai refresh token/JWT baru, transaksi/import server, atau repository remote.
3. Turso Cloud + engine `libsql://` belum diuji; `json_valid`/trigger/transaksi remote perlu
   acceptance cloud.
4. OpenAI-compatible adapter belum diuji terhadap provider nyata; prompt Go lebih ringkas daripada
   prompt Node (payload tetap aggregate-only).
5. Perlu benchmark sebelum mengklaim peningkatan performa Go.


## 9. Irisan C2 Android (22 November 2026)

Branch `feat/c2-android` menambahkan access+refresh session, single-flight refresh,
rotasi atomik, REST client/DTO dan deletion reauth/receipt. Itu belum memindahkan
transaksi/import/summary/budget/analysis dari Room ke server; status rinci ada di
[`14_C2_INTEGRASI_ANDROID.md`](14_C2_INTEGRASI_ANDROID.md). C2 penuh tetap
memerlukan emulator→Go dan penghapusan Room pada D.


## 10. C2-F1–F4 (22 November 2026)

- F1: server-backed deletion failure is recoverable and never claims completion; a 202
  persists server id/state and a Keystore-wrapped receipt before local cleanup; password PAM is
  the reauthentication proof (Gmail not required); local-only cleanup is marked deviceOnly.
- F2: login captures a generation before the network and refuses a late write after logout or a
  newer login.
- F3: forced renewal is bound to the rejected access token; parallel 401s share one rotation.
- F4: Go request/panic logs use a closed route template and never include receipt tokens.


## 11. C2A manual/list/detail/overview

C2A is proven locally: `AddManualEntryUseCase` resolves the server category id
and calls `POST /v1/transactions` with the persisted pending-action key; the
transaction list/detail and overview read Go. Edit/delete remain explicitly
unavailable on the server-backed detail path. Import/budget/analysis and full
Room removal remain C2B/C2C/D.
