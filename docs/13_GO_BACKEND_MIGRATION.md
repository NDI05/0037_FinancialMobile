# Migrasi backend PAM ke Go — status dan matriks parity

Status: **development slice berjalan**, 21 November 2026.
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
| `POST /v1/sessions/refresh` | — | **PASS** | rotation atomik, replay cabut keluarga, concurrent claim |
| `DELETE /v1/sessions` | PASS | **PASS** | revoke sesi + refresh family |
| `GET /v1/me` | PASS | **PASS** | profil/role/consent; activity null (belum ada agregasi) |
| `GET/POST /v1/consents` | PASS | **PASS** | append-only, latest per purpose |
| `PUT/GET /v1/summaries/{month}` | PASS | **PASS** | payload sealed v0.3 + computed v0.4 (gross, classified, coverage, revision) |
| Transaksi CRUD | lokal/Room | **PASS** | owner-scoped, soft delete, revision, cursor, search pasca-dekripsi |
| Kategori CRUD + reassign | lokal/Room | **PASS** | 11 built-in, field dinamis, reassign atomik |
| Budget plan/limits | PASS | **PASS** | integer minor, gap, revision, hard delete |
| `POST /v1/analyses` | PASS | **PARTIAL** | consent + allowlist + cache/idempotency + fake/OpenAI adapter; validator angka/instruksi disederhanakan, belum ada loop regenerasi penuh |
| `GET /v1/analyses/{month}` | PASS | **PASS** | latest sealed |
| `POST/GET /v1/deletion-requests` | PASS | **PARTIAL** | create/get + block writes; sweep worker belum diport |
| `POST /v1/import-jobs` + batch | — | **NOT PORTED** | target C1 berikutnya; tabel + acceptance sudah ada |
| Admin users/invitations | — | **NOT PORTED** | target D; DDL + guard sudah ada |
| `POST /v1/internal/sweep` | PASS | **NOT PORTED** | butuh worker/deletion sweep |
| Rate limit login | PASS | **PASS** | 5/15 menit; address class; no existence oracle |
| Analysis quota | PASS | **PARTIAL** | hour/day reservation atomik; daily USD ceiling belum dihitung |
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
- Rollback ke Node: skema tetap kompatibel untuk akun/consent/summary, tetapi token JWT harus
  diganti re-login dan transaksi/kategori v0.4 harus diekspor/direkonsiliasi sebelum APK lama.
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

Hasil 21 November 2026: **11 tes Go PASS**, `go test -race` PASS, `go vet`/build PASS; Node **124
PASS**. Android terhadap Go di emulator API 36: register 201 → login 201 → dua consent 201 →
Ringkasan tampil. Analisis/budget/deletion API diuji lewat HTTP Go; UI Android untuk fitur itu masih
memakai penyimpanan v0.3 sampai C2.

## 7. Risiko/pekerjaan tersisa

1. Import batch server-side, admin provisioning, deletion sweep, dan daily USD ceiling belum port.
2. Validator analisis Go belum selengkap Node (regenerasi maksimum, seluruh aturan angka/teks).
3. Turso Cloud + engine `libsql://` belum diuji; `json_valid`/trigger/transaksi remote perlu
   acceptance cloud.
4. Android C2 belum memakai refresh token/JWT baru secara penuh; UI transaksi/kategori/budget/
   lampiran/admin masih v0.3.
5. Perlu benchmark sebelum mengklaim peningkatan performa Go.
