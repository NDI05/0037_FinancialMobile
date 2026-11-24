# Pemisahan backend Go ke 0037 Backend Financial

Tanggal: **24 November 2026** (WIB). Status: **PARTIAL** — source terpisah, build/test
independen, dan E2E Android↔server-dari-repo-backend sudah **PASS** (QA, `aea555f`);
restore/rollback data nyata masih `NOT RUN` dan menunggu otorisasi pemilik. Merge/deploy
tetap menunggu instruksi pemilik.

## Keputusan dan URL

| Repo | URL | Branch kerja | Commit awal terverifikasi |
|---|---|---|---|
| Android (klien) | https://github.com/NDI05/0037_FinancialMobile | `feat/c2-android` | `cd0f91a` (sebelum pemisahan) |
| Backend Go | https://github.com/NDI05/0037-Backend-Financial (privat) | `main` | `327d876` (bootstrap standalone) |

Visibilitas backend: privat (default). Pemilik kontrak API dan migrasi adalah repo backend;
`docs/API_CONTRACT.md` dan `docs/GMAIL_API_CONTRACT.md` ada di sana.

## Metode ekstraksi

- `git subtree split --prefix=backend-go` dari `0037_FinancialMobile@cd0f91a` di clone
  terisolasi `/tmp/pam-split`; hasilnya 32 commit riwayat path backend dipertahankan dan
  dipush ke `main` repo backend (`0272c97` sebagai ujung riwayat sebelum bootstrap).
- Commit bootstrap `327d876`: fixture interop dipindah ke `fixtures/interop/` dan path tes
  diperbaiki, `scripts/go.sh` dibuat mandiri (PAM_GOROOT → `.toolchain/go` → `PATH`),
  `.env.example`, `.gitignore`, CI GitHub Actions (vet+test+build), README standalone,
  dan dokumentasi kontrak.

## Isi yang dipindahkan dari tree Android

- `backend-go/**` (Go source, migrasi 001–016, classifier, admin CLI, smoke script).
- `fixtures/interop/*.json` (fixture sintetis, kini di repo backend; tes Go memakai path lokal).
- `backend/` Node lama dipindahkan **keluar tree** ke
  `../archive/pam-node-backend-2026-11-24/` (bukan di repo). Riwayatnya tetap di Git Android
  `cd0f91a`; cara pulih: `git checkout cd0f91a -- backend/`. Alasan, kriteria keluar dan
  commit referensi dicatat di `docs/maintenance/RETIREMENT_POLICY.md`.
- `scripts/dev.sh` sekarang memakai `PAM_BACKEND_REPO` (default `../0037-Backend-Financial`)
  dan `scripts/go.sh` repo backend; README dan dokumen arsitektur/kontrak diperbarui.

## Build dari clone bersih (backend)

```bash
git clone https://github.com/NDI05/0037-Backend-Financial.git
cd 0037-Backend-Financial
export PAM_GOROOT=/path/to/go1.27.4     # atau sediakan go di PATH
./scripts/go.sh vet ./...
./scripts/go.sh test ./... -count=1
./scripts/go.sh build -o bin/pam-api ./cmd/api
APP_ENV=development PORT=18094 DATABASE_URL=file:./data/dev.db ./bin/pam-api
curl -fsS localhost:18094/healthz; curl -fsS localhost:18094/readyz
```

## Matriks parity

| Item | Status | Bukti |
|---|---|---|
| Dua URL repo + dua Git root | PASS | repo di atas; worktree Android terpisah dari clone backend |
| Go build/vet/test dari clone backend | PASS (baseline) | QA: 11 paket, 167 tes, 0 gagal; `vet`/`build` rc=0 |
| `/healthz` + `/readyz` server dari repo backend | PASS (baseline) | QA: 200 `{"status":"ok"}` / `{"status":"ready"}`, migrasi 16 baris, DB file terisolasi |
| Android build tanpa source server | PASS | QA `aea555f`: `:app:assembleDebug --rerun-tasks` rc=0, APK sha256 `ef183b75…`, identik dengan build baseline `cd0f91a` |
| APK Android login + baca data ke server dari repo backend | PASS | QA P7: akun sintetis → Ringkasan Rp22.222,21 / 2 transaksi, dump+screenshot `ui_split/14`–`15`; port uji 18095 + `adb reverse` agar 18090 milik owner tidak diganggu |
| Migrasi DB terisolasi | PASS | DB baru `/tmp/pam-split-run/db/pam.db`, `schema_migrations` 16 baris, DB owner tidak disentuh |
| Rollback ke pasangan baseline | PARTIAL | server dari `git show cd0f91a:backend-go` → health/ready 200, 16 migrasi; APK `cd0f91a` build rc=0 sha256 sama. Restore DB produksi/rollback data nyata **NOT RUN** (otorisasi owner) |
| Cutover launcher UAT | NOT RUN | menunggu rollback DB + instruksi pemilik |
| Audit independen | PASS tanpa P0 | `docs/evidence/AUDIT_BACKEND_SPLIT_2026-11-24.md`; temuan P2 diperbaiki (`4e6946b`: README §6, tautan/kontrak backend, dir kosong) |

## Sisa

1. Restore/rollback data nyata dan cutover launcher UAT — menunggu otorisasi pemilik.
2. Kode Gmail API ada di branch `feat/gmail-api` repo backend (6 commit, `9d388ae`);
   bukti: `docs/evidence/GMAIL_API_2026-11-24.md` + `docs/evidence/GMAIL_UI_2026-11-24.md`.
   Merge ke `main`/PR tetap menunggu instruksi pemilik.
3. Gap kontrak `attributes` (nilai JSON non-string) ditemukan QA — dicatat sebagai temuan
   pre-existing, bukan efek split; perlu keputusan desain terpisah.
