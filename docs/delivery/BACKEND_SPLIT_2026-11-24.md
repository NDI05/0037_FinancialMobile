# Pemisahan backend Go ke 0037 Backend Financial

Tanggal: **24 November 2026** (WIB). Status: **PARTIAL** — source terpisah dan build/test
independen sudah PASS; Android E2E terhadap server dari repo baru dan rollback masih
`NOT RUN`/menunggu verifikasi QA. Merge/deploy tetap menunggu instruksi pemilik.

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
| Android build tanpa source server | PENDING (build sedang dijalankan) | `:app:compileDebugKotlin` + `testDebugUnitTest` |
| APK Android login + baca data ke server dari repo backend | NOT RUN | dijadwalkan P4/P5 QA |
| Migrasi DB terisolasi | PASS (baseline) | DB file baru `/tmp/wqa-split-*/pam.db`, 16 migrasi |
| Rollback ke pasangan baseline | NOT RUN | baseline: `cd0f91a` + `backend-go/` dari commit itu |
| Cutover launcher UAT | NOT RUN | menunggu parity E2E + instruksi pemilik |

## Sisa

1. Verifikasi E2E Android debug (dibangun dari repo Android) login dan membaca data dari
   server yang dijalankan dari clone backend (QA lane, DB terisolasi).
2. Bukti rollback: APK/commit baseline masih dapat dijalankan dengan server dari commit
   `cd0f91a` (arsip Node dan riwayat Git tersedia).
3. Gate Gmail API (repo backend, lane w-go) belum masuk matriks ini.
