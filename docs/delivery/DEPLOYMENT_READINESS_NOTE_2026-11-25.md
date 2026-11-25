# Catatan kesiapan deployment backend (tanpa menjalankan deploy) — 25 November 2026

Dokumen ini menyiapkan langkah deployment backend Go ke hosting tanpa biaya tambahan. **Tidak ada
deployment yang dijalankan pada ronde ini**; eksekusi menunggu instruksi terpisah dari pemilik.

## Pilihan hosting (Rp0, HTTPS)

| Pilihan | Kelebihan | Catatan |
|---|---|---|
| Fly.io free allowance / Render free / Koyeb free | HTTPS otomatis, health check, volume persisten | Perlu verifikasi kuota berjalan dan kebijakan sleep; DB file harus di volume persisten |
| Turso (libSQL) free tier + host aplikasi mana pun | DB remote cocok dengan driver yang sudah dipakai (`DATABASE_URL=libsql://…`) | Backup/restore lewat Turso; latensi lebih tinggi dari DB lokal |

Kebutuhan minimum: HTTPS publik, volume persisten bila memakai file SQLite, dan proses tunggal
(worker Gmail berjalan di proses yang sama, interval polling 15 menit).

## Variabel wajib (jangan commit nilainya)

- `APP_ENV` **bukan** `development`; `PORT` mengikuti host.
- `DATABASE_URL` (file di volume persisten atau `libsql://…` + `DATABASE_AUTH_TOKEN`).
- `DATA_ENCRYPTION_KEY` (base64 32 byte, **baru untuk produksi**), `DATA_KEY_VERSION`,
  `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `REFRESH_PEPPER`, `REGISTRATION_INVITE_CODE`.
- Gmail: `GMAIL_ENABLED=true`, `GMAIL_MODE=polling`, `GMAIL_PROVIDER=google`,
  `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `GMAIL_REDIRECT_URI=https://<host>/v1/gmail/connect/callback`,
  `GMAIL_APP_DEEP_LINK=pam://gmail/connected`.
- Google SSO: `GOOGLE_SSO_CLIENT_ID`, `GOOGLE_SSO_ENABLED=true`.
- AI kategori opsional: `CATEGORY_LLM_ENABLED=false` (default hemat) atau kredensial Azure Foundry.
- Retensi: `RETENTION_SWEEP_ENABLED`/`RETENTION_DELETE_ENABLED` sesuai keputusan produk.

Konfigurasi dengan secret development harus **ditolak** di `APP_ENV` produksi; cek log start untuk
peringatan "development secrets".

## Build dan migrasi

```bash
export PAM_GOROOT=/path/ke/go
./scripts/go.sh vet ./...
./scripts/go.sh test ./... -count=1
./scripts/go.sh build -trimpath -o /out/pam-api ./cmd/api
```

Migrasi dijalankan otomatis saat start (saat ini **22** migrasi, termasuk perbaikan nominal 022).
Backup DB sebelum deploy pertama dan setiap kali ada migrasi baru.

## Checklist sebelum deploy

1. Salin DB (file: `sqlite3 <db> ".backup '<db>.predeploy.db'"`; Turso: `turso db dump <db>`) dan
   catat sha256. Simpan binary versi sebelumnya.
2. Verifikasi `./scripts/go.sh vet ./...` dan `./scripts/go.sh test ./... -count=1` PASS pada commit
   yang akan dideploy.
3. Pastikan redirect URI OAuth Google dan deep link Android menunjuk host HTTPS baru, dan akun uji
   sudah diizinkan di Google Cloud Console.
4. Deploy, lalu cek `GET /healthz` dan `GET /readyz` = 200 serta log start tanpa error konfigurasi.
5. Uji manual: register/login, buat transaksi manual, hubungkan Gmail dengan akun uji, tunggu polling
   berikutnya, lalu pastikan tepat satu transaksi masuk dan nominalnya sesuai email.
6. Catat build hash dan commit di laporan gate.

## Backup, restore, rollback

- **Backup rutin:** salinan DB harian + sebelum deploy; simpan 7 salinan terakhir. Verifikasi file
  dapat dibuka (`PRAGMA integrity_check`).
- **Restore:** hentikan proses, ganti file DB dengan salinan, start ulang, cek `/readyz` dan jumlah
  transaksi. Untuk Turso gunakan mekanisme restore/point-in-time dari dashboard.
- **Rollback aplikasi:** hentikan proses, pasang binary versi sebelumnya, start, cek health.
  Rollback binary aman karena migrasi bersifat aditif; **jangan** memakai salinan DB pra-022 bila
  ingin nominal impor tetap benar (migrasi 022 sengaja memperbaiki data lama).
- **Rollback polling:** Pub/Sub bersifat opt-in rollback; default tetap polling 15 menit tanpa biaya
  GCP tambahan.

## Yang belum dilakukan

Deployment, pembuatan secret produksi, pendaftaran host OAuth, dan uji live di host nyata **belum
dijalankan**. Gate G24 tetap NOT RUN sampai pemilik memberi instruksi deploy terpisah.
