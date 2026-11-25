# Deployment API ke Vercel — 25 November 2026

Tujuan: menjawab permintaan pemilik apakah backend Go bisa di-deploy ke akun Vercel
gratisnya, lalu melakukannya bila memungkinkan. Dokumen ini mencatat hasil, batasan, dan
langkah lanjutan. **Ini demo, belum deploy produksi final**: G24 **PARTIAL** (demo Vercel; produksi NOT RUN) dan merge/deploy
produksi tetap keputusan pemilik.

## Hasil singkat

| Item | Nilai |
| --- | --- |
| Platform | Vercel Hobby, tim `andhika-pratamas-projects`, akun CLI `ndi05` |
| Project | `pam-api-vercel` |
| Alias produksi | <https://pam-api-vercel.vercel.app> |
| Deployment | `pam-api-vercel-fjgj9d1vk` (Go Framework Preset, Go 1.27.1 dari `go.mod`) |
| Database | Turso baru `pam-vercel` (libSQL, region `aws-ap-southeast-1`), migrasi **22/22** |
| Kode backend | working tree `main` setara `1325d3d` (tanpa perubahan fitur) |
| Auth Google | `GOOGLE_SSO_ENABLED=true` dengan Web OAuth client ID yang sama seperti UAT |
| Gmail | **nonaktif** (`GMAIL_ENABLED=false`) — lihat batasan di bawah |
| Kategori AI | tetap nonaktif (`CATEGORY_LLM_ENABLED=false`, `LLM_PROVIDER=fake`) — sama dengan gate Rp0. Sengaja **tidak** memakai Azure di demo publik ini; Azure penuh aktif hanya di UAT lokal pemilik ([AZURE_FULL_OPTIMIZATION](AZURE_FULL_OPTIMIZATION_2026-11-25.md)) |
| Vercel Authentication | dimatikan untuk project ini (`ssoProtection: null`) agar API bisa dipanggil HP |

Vercel sekarang punya **Go Framework Preset (Beta)** yang menjalankan server
`net/http` biasa sebagai proses panjang, jadi `cmd/api/main.go` dapat dipakai apa adanya
(tidak perlu adapter `api/*.go`). Syaratnya server mendengarkan `PORT` — sudah dipenuhi
`config.Load` + `cmd/api/main.go`.

## Bukti smoke (production alias, 25 Nov 2026 WIB)

Semua permintaan di bawah langsung ke <https://pam-api-vercel.vercel.app> tanpa
`vercel curl` (proteksi deployment sudah dimatikan). **Risiko demo publik:** registrasi terbuka dan belum ada rate-limit middleware, jadi siapa pun dapat membuat akun sintetis dan memakai kuota Hobby/Turso. Mitigasi saat ini: LLM tetap `fake` (tanpa biaya Azure), DB demo terpisah, hanya data sintetis; project dapat dihapus/di-pause pemilik kapan saja.

| Waktu (WIB) | Permintaan | Hasil |
| --- | --- | --- |
| 14:54:58 | `GET /healthz` | 200 `{"status":"ok"}` |
| 15:00 | `GET /readyz` | 200 `{"status":"ready"}` (DB Turso + migrasi siap) |
| 15:00 | `POST /v1/accounts` (akun sintetis `vercel-smoke-*@example.test`) | 201, role `USER` |
| 15:00 | `POST /v1/sessions` | 201 access+refresh token |
| 15:00 | `GET /v1/me` | 200 `googleLinked:false`, `status` normal |
| 15:00 | `GET /v1/categories` | 200 (kategori builtin ikut migrasi) |
| 15:00 | `POST /v1/transactions` `Idempotency-Key`, 4.500.000 IDR DEBIT 24 Nov | 201, `categoryCode:food`, `categoryOrigin:USER` |
| 15:00 | `GET /v1/transactions?month=2026-11` | 200, `items=1`, `amountMinor=4500000` |
| 15:00 | `GET /v1/summaries/2026-11` | 200, `categorizedCount=1`, `classifiedExpenseMinor=4500000` |
| 15:02 | `GET /v1/auth/google/nonce` | 200, `expiresInSeconds=600` |
| 15:02 | `POST /v1/auth/google` token Google palsu + nonce | 401 `google_invalid_token` (jalur SSO aktif dan memverifikasi) |
| 15:03 | `POST /v1/sessions` akun sintetis yang sama | 201 (data persist di Turso antar-instance) |
| 15:03 | `GET /v1/categories` tanpa token | 401 `unauthorized` |

Hanya akun/data sintetis yang dibuat; **tidak ada data pemilik** yang disentuh.

## Insiden saat deploy dan perbaikannya

Percobaan pertama gagal: setiap instance dingin menjalankan `store.Open` → `Migrate` secara
bersamaan pada DB kosong, sehingga muncul `migration 1: table users already exists` /
`migration 4: table http_idempotency already exists` dan server keluar (`exit status 1`).

Perbaikan: migrasi dijalankan **di luar jalur request** lebih dulu (binary lokal dengan env
Vercel diarahkan ke Turso) sampai `/readyz` 200 dan `schema_migrations` = 22. Setelah itu
deploy ulang berjalan normal. Catatan operasional: setiap deploy ke DB kosong baru harus
migrasi dulu (satu instance), jangan biarkan banyak instance balapan migrasi.

## Batasan yang harus diketahui pemilik

1. **Polling Gmail 15 menit tidak dijamin di Vercel Functions.** Koordinator Gmail berjalan
   sebagai goroutine di dalam proses server; Vercel Functions bisa scale-to-zero saat tidak
   ada request, jadi sinkronisasi otomatis bisa terlewat. Vercel Cron Hobby hanya
   **sekali per hari**, dan Daemon Vercel Hobby punya kuota
   **5 CPU-jam + 420 GB-jam memori/bulan** — tidak cukup untuk worker 24/7. Untuk Gmail
   otomatis yang andal tetap perlu host proses panjang (Fly.io/Render/Koyeb/VPS) seperti di
   `docs/delivery/DEPLOYMENT_READINESS_NOTE_2026-11-25.md`.
2. **Gmail belum diaktifkan** karena OAuth redirect harus HTTPS dan harus didaftarkan dulu di
   Google Cloud Console:
   `https://pam-api-vercel.vercel.app/v1/gmail/connect/callback`.
   Tanpa pendaftaran itu, tautkan Gmail akan gagal. SSO login tidak butuh redirect URI ini.
3. **Vercel Authentication harus tetap mati** untuk project `pam-api-vercel`; kalau diaktifkan
   lagi, HP akan menerima halaman login Vercel, bukan API.
4. **APK harus diarahkan ke URL Vercel.** Build debug yang terpasang sekarang masih menunjuk
   server UAT loopback (`local.properties`). Perintah build yang dipakai:
   `./gradlew :app:assembleDebug -PPAM_BACKEND_BASE_URL=https://pam-api-vercel.vercel.app -PPAM_GOOGLE_SSO_CLIENT_ID=<Web client ID>`
   Hasil: `app/build/outputs/apk/debug/app-debug.apk`
   (48.837.129 byte, sha256 `f95f8dea74a564e23aaf97c45e63f7c1e1c5c852b1f35f62271c9d93b6608d89`).
   APK ini **belum dipasang** agar alur G36 di server UAT lokal tidak terganggu.
5. **Bukan produksi.** Data demo ada di DB Turso terpisah; belum ada backup/restore otomatis,
   belum ada domain kustom, dan belum ada keputusan merge rilis.

## Langkah lanjutan (pemilik)

1. (Opsional) Daftarkan redirect URI Gmail di Google Cloud Console bila ingin tautkan Gmail
   lewat Vercel — sadari batasan polling di poin 1.
2. Pasang APK Vercel di HP bila ingin mencoba login Google/email ke API ini:
   `adb install -r app/build/outputs/apk/debug/app-debug.apk` (menimpa APK UAT lokal; Run dari
   Android Studio akan kembali ke `local.properties`).
3. Putuskan: cukup demo di Vercel (fitur inti + SSO; Gmail manual/terbatas), atau pindah ke
   host proses panjang untuk Gmail otomatis + produksi.
4. Bila Vercel dipakai serius: buat project terpisah untuk produksi, domain kustom, backup
   Turso, dan audit ulang env/secret sebelum dinyatakan PASS.

## Backup, restore, dan pemantauan (diuji 25 Nov 2026)

- **Backup Turso (diuji nyata):**
  `turso db export pam-vercel --output-file /tmp/pam-vercel-backup.db` berhasil; file SQLite
  581.632 B (568 KiB) + WAL 0 B berisi `schema_migrations` 22 dan 36 tabel; dapat dibuka `sqlite3` untuk
  `PRAGMA integrity_check` atau dipulihkan ke DB baru dengan `turso db import`.
- **Restore/rollback data:** buat DB Turso baru lalu `turso db import <file>`; atau gunakan
  fitur restore dashboard Turso. Rollback **kode** memakai `vercel rollback <deployment-url>`
  (deploy demo ini tidak mengubah migrasi; DB demo terpisah).
- **Kesehatan dan latensi:** `GET /healthz` dari jaringan publik ±15:09 WIB: **200** dalam
  **0,37–0,40 dtk** (3 kali berturut-turut). Belum ada alert/monitoring otomatis.
- **CI backend:** commit `5789f54` → run GitHub Actions **45573339049 success** (57 dtk).
  Satu annotation non-blocking: `actions/checkout@v4`/`setup-go@v5` masih Node 20.
- **Kuota Hobby:** Functions/transfer mengikuti kuota Hobby; Daemon Hobby 5 CPU-jam +
  420 GB-jam memori/bulan. Demo ini tidak memakai Daemon, jadi biaya tetap Rp0 selama di
  dalam kuota Hobby/Turso free.

## Perintah operasional

```bash
# dari repo backend
vercel link --yes --project pam-api-vercel
vercel deploy --prod --yes --archive=tgz
vercel rollback <deployment-url>      # rollback rilis
vercel env ls production              # daftar nama variabel (nilai tetap rahasia)

# cek cepat
curl -s https://pam-api-vercel.vercel.app/healthz
curl -s https://pam-api-vercel.vercel.app/readyz
```

Env produksi yang dipasang (nama saja; nilai rahasia tidak dicatat): `APP_ENV`,
`DATABASE_URL`, `DATABASE_AUTH_TOKEN`, `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`,
`REFRESH_PEPPER`, `DATA_ENCRYPTION_KEY`, `DATA_KEY_VERSION`, `LOG_LEVEL`, `LLM_PROVIDER`,
`CATEGORY_LLM_ENABLED`, `GMAIL_ENABLED`, `GOOGLE_SSO_ENABLED`, `GOOGLE_SSO_CLIENT_ID`.
