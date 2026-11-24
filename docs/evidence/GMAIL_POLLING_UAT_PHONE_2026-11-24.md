# UAT perangkat fisik — Gmail polling (fixture), 24 November 2026

Status: **PASS untuk alur terkontrol**; Gmail live dan GCP Rp0 **NOT RUN** (milik owner).
Semua data pada dokumen ini berasal dari profil **FIXTURE** (provider palsu + pesan sintetis) dan
tidak boleh dibaca sebagai bukti kuota/akun Gmail nyata. Tidak ada secret, alamat email nyata,
nominal nyata, atau token di dokumen maupun aset.

## 1. Artefak yang diuji

| Hal | Nilai |
|---|---|
| Repo Android | `pam-app`, branch `feat/c2-android`; UAT penuh pada build `a7abb14`, build final **`7f218ed`** (perbaikan MINOR F2) di-smoke ulang |
| APK | `app/build/outputs/apk/debug/app-debug.apk`, varian debug, package `com.pam.recap.debug`, 46.433.080 byte; build UAT `a7abb14` 17:08 WIB sha256 `93b69cc0…`, build final `7f218ed` 17:28 WIB sha256 `7efc1803…` |
| Repo backend | `0037-Backend-Financial`, branch `integration/2026-11-24`, HEAD `1290910` |
| Binary backend | `/tmp/pam-gmail-uat/bin/pam-api`, dibangun dari `1290910` pukul 17:12 WIB |
| Perangkat | HP fisik `21061119AG`, serial `105397ba0507`, Android debug via `adb` |
| Transport | `adb reverse tcp:18090 tcp:18090`; backend hanya mendengarkan loopback `127.0.0.1:18090` |
| Profil backend | `~/.config/pam/gmail-uat-fixture*.env` (permission 600, di luar repo): `APP_ENV=development`, `GMAIL_PROVIDER=fake`, `GMAIL_DEV_SCENARIO=normal`, DB SQLite terpisah, `GMAIL_WORKER_INTERVAL_SECONDS=10`, `GMAIL_DELTA_INTERVAL_SECONDS=60`, `GMAIL_SYNC_COOLDOWN_SECONDS=60` |
| Akun uji | akun sintetis fixture (`uat.pm@pam.test`), Gmail tertaut lewat consent palsu; consent AI/Azure tetap nonaktif |

## 2. Hasil

| # | Butir uji | Hasil | Bukti |
|---|---|---|---|
| 1 | Aplikasi debug terpasang dan dijalankan pada HP fisik, `/readyz` backend terjangkau dari HP | PASS | instalasi `adb install -r` sukses; aplikasi menampilkan data dari backend `:18090` |
| 2 | Consent Gmail di perangkat + tautkan Gmail lewat browser, kembali ke aplikasi lewat `pam://gmail/connected` | PASS | layar "Gmail berhasil ditautkan"; akun tampil tersamar `p***@pam.test` |
| 3 | Backfill setahun WIB bertahap | PASS | "Bulan selesai: 12 dari 12"; "Riwayat yang bisa diakses: November 2025 sampai November 2026"; total tersimpan 27 transaksi sintetis |
| 4 | Panel status server menampilkan state, progres, percobaan/keberhasilan terakhir, jadwal berikutnya | PASS | `status_sheet_final.xml/.png`: "Menunggu", 12/12, "Terakhir berhasil sinkron: baru saja", "Sinkron berikutnya sekitar 17:16" |
| 5 | "Sinkronkan sekarang" meng-enqueue job server (`202`) dan status kembali idle setelah drain | PASS | `POST /v1/gmail/sync` → `202` (log backend 17:13:13); status "Menunggu" setelah drain, bukan "Menghubungkan" permanen |
| 6 | Cooldown `429` sesuai kontrak §9.3 dan tampil sebagai hitungan mundur di UI | PASS | body `{"code":"sync_cooldown","retryable":true,"retryAfterSeconds":59,...}`; UI "Tunggu 57 detik sebelum menyinkronkan lagi." + tombol nonaktif (`status_cooldown_429.xml/.png`) |
| 7 | Delta otomatis saat aplikasi **ditutup**, backend hidup | PASS | aplikasi di-force-stop 17:14:27; pesan sintetis baru masuk fixture; transaksi `Rp9.990` tersimpan 17:15:34; count 26→27 |
| 8 | Tidak ada duplikat saat aplikasi dibuka kembali | PASS | setelah buka 17:15:39, Ringkasan/Transaksi menampilkan `14:30 · Keluar · Rp9.990` tepat satu kali; total 27 baris |
| 9 | Restart backend tidak kehilangan data atau menggandakan baris | PASS | restart 17:16:30; `readyz=200`; count tetap 27; `sync_state=idle`, 12/12, `sync_requested_at=null`, `needs_reauth=false` |
| 10 | Rekonsiliasi jumlah/total server vs Ringkasan/Transaksi | PASS | DB November 2026: 5 baris, total 2.053.001 minor = **Rp20.530,01**; Ringkasan "Rp20.530,01", "5 belum berkategori"; daftar transaksi 5 baris |
| 11 | Build final `7f218ed` (perbaikan MINOR F2 audit ronde 5) di-smoke ulang di HP yang sama | PASS | `adb install -r` build final 17:28; sheet status "Menunggu", 12/12, "Sinkron berikutnya sekitar 17:31"; tap "Sinkronkan sekarang" → state "Mengambil riwayat"/"Sedang sinkron" → `POST /v1/gmail/sync` **202** (log 17:30:41) → drain "Menunggu" 17:30:51; tidak ada label lengket (`status_sheet_final_build.*`, `manual_sync_settled_build.xml`) |

**Catatan build final.** UAT butir 1–10 dijalankan pada build `a7abb14`; setelah audit ronde 5 menemukan MINOR F2 (hint "Menghubungkan" dapat lengket bila `GET /v1/gmail/status` terus gagal), perbaikan satu baris + tes diterapkan di `7f218ed` dan build final dipasang ulang. Butir 4–5 diverifikasi ulang pada build final (butir 11); delta, cooldown, restart, dan rekonsiliasi tidak berubah karena perbaikan hanya menyentuh cabang kegagalan pembacaan status.

## 3. Bukti sanitasi

Folder `docs/evidence/assets/gmail_polling_uat/`:

- `status_sheet_after_delta.*` — panel status sesudah delta pertama (masih memuat bug "Menghubungkan" pada build sebelum perbaikan audit ronde 4; disimpan sebagai pembanding).
- `status_sheet_final.*` — panel status pada build `a7abb14`: "Menunggu", 12/12, jadwal berikutnya.
- `status_cooldown_429.*` — hitungan mundur cooldown 429.
- `ringkasan_after_delta2.*` — Ringkasan setelah delta kedua (`Rp20.530,01`, 5 belum berkategori).
- `transactions_after_delta2.*` — daftar transaksi November 2026 (5 baris, `Rp9.990` sekali).
- `status_sheet_final_build.*`, `manual_sync_accepted_build.png`, `manual_sync_settled_build.xml` — build final `7f218ed`: sheet "Menunggu" 12/12 dan siklus tap → 202 → drain.

Aset berupa dump `uiautomator` dan tangkapan layar aplikasi sintetis; tidak memuat token, email
nyata, nominal nyata, maupun data pemilik.

## 4. Penyimpangan dan batas

- **Tombol Run Android Studio tidak dapat ditekan oleh agen.** Otomasi macOS diblokir (System
  Events exit 124, `screencapture` gagal, display tidur), sehingga instalasi dijalankan lewat
  `adb install -r` + `am start` pada HP fisik yang sama. Ini setara secara artefak (APK debug dari
  konfigurasi `app`) tetapi **bukan** bukti literal tombol Run; pemilik tetap dapat menekan Run
  sendiri untuk memenuhi mandat secara harfiah.
- Fixture memakai interval dipercepat (10 dtk/60 dtk) untuk demo; default produksi delta tetap
  **15 menit** dan tidak diubah.
- Gmail live, consent Google nyata, test user, OAuth Web client, kuota/limit nyata, Azure nyata,
  TalkBack manual, dan gate GCP Rp0 tetap **NOT RUN**.
- Panel "Perlu perhatian" masih menampilkan teks sisa status sinkron perangkat lama pada build ini;
  bagian server Gmail sendiri menunjukkan "Menunggu"/sukses. Dicatat sebagai temuan kecil UI, bukan
  kegagalan sinkronisasi server.
- Backend UAT hanya loopback; port tidak dibuka ke internet dan tidak ada reverse proxy publik.
- Temuan MINOR audit ronde 5 yang **tidak** ditutup dan dicatat sebagai backlog non-blocking: F1 (parameter `cutoff` `ClearGmailSyncRequest` belum melindungi request yang datang di tengah run; dampak ≤ satu interval delta), F3 (marker tidak dibersihkan saat disconnect/reconnect dan replay-expiry kedua; UI sudah mengabaikannya), F6 (409 `gmail_needs_reauth` di Android belum dipetakan ke CTA khusus, masih error generik).
