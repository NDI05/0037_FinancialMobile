# BUKTI PERANGKAT (FIXTURE) — Onboarding Gmail + Opsi Lewati

- **Tanggal**: 24 November 2026, 18:30–18:35 WIB
- **Sifat**: bukti **fixture di HP fisik**; **bukan** UAT akun Google nyata dan bukan Gmail live.
  Tidak ada akun Google, consent `gmail.readonly` nyata, email asli, atau data pemilik dalam uji ini.
- **Perangkat**: HP fisik `(serial diredaksi)` (model `21061119AG`), aplikasi `com.pam.recap.debug`.
- **Build**: Android `e67380a` (APK debug 48.362.013 B, sha256 `41584bae…`; dokumen di `062e674`);
  backend `integration/2026-11-24` @ `138b5c3` (kode `71013f5`), mode fixture Gmail, port 18090
  lewat `adb reverse`.
- **Akun uji**: sintetis `onboard.uat@pam.test` (owner `u_3d0e6b50…`), dibuat lewat API registrasi
  pada DB fixture terpisah; bukan akun pemilik.

## Langkah dan hasil

1. `pm clear` lalu buka aplikasi → login email/kata sandi akun sintetis (form normal).
2. Muncul layar **Persetujuan** (izin baca email Livin + AI terpisah), lalu tombol-tombol
   **Lanjut** / **Lanjut tanpa menghubungkan Gmail**.
3. Login selesai → sheet **"Tautkan Gmail untuk impor Livin"** tampil dengan **Tautkan Gmail** dan
   **Lewati**; teks "Anda bisa menautkan Gmail nanti dari Akun atau panel status."
4. Tap **Lewati** → masuk ke Ringkasan (November 2026, kosong), sesi tetap aktif, Gmail tidak
   tertaut (`GET /v1/gmail/status` → `connected=false`, `sync_state=null`).
5. Force-stop + buka ulang aplikasi → sheet **tidak muncul lagi** (pilihan Lewati tersimpan
   per-owner di DataStore), sesi tetap aktif di Ringkasan.
6. Tombol **"Masuk dengan Google"** tidak tampil pada build ini karena `PAM_GOOGLE_SSO_CLIENT_ID`
   kosong (fitur aman tersembunyi); endpoint backend SSO mengembalikan `503 google_sso_disabled`.

## Bukti gambar (tersanitasi, akun sintetis)

- [01_sheet_tautkan_gmail.png](assets/sso_onboarding_uat/01_sheet_tautkan_gmail.png) — sheet tawaran
  Tautkan Gmail + Lewati.
- [02_setelah_lewati_ringkasan.png](assets/sso_onboarding_uat/02_setelah_lewati_ringkasan.png) —
  setelah Lewati, Ringkasan kosong, sesi aktif.
- [03_setelah_restart.png](assets/sso_onboarding_uat/03_setelah_restart.png) — setelah restart,
  sheet tidak muncul lagi.

## Yang TIDAK dibuktikan di sini (NOT RUN, tetap owner)

- Penautan akun Google nyata dan consent `gmail.readonly` (butuh Web Client ID + OAuth Android
  client package `com.pam.recap.debug` + SHA-1 debug + akun penguji).
- Login Google nyata, penautan akun lama, login ulang (G36).
- Backfill Gmail live, cabut akses, dan gate GCP Rp0.
- Tombol **Run** Android Studio literal (APK dipasang via `adb install -r`; otomasi GUI macOS
  diblokir) — penyimpangan yang sama seperti lane Gmail polling.

## Catatan regresi Gmail

Kontrak Gmail polling dijalankan ulang black-box pada backend integrasi `138b5c3` (fake provider,
port terisolasi 18099): **PASS=82, FAIL=0, exit 0** — 3 skenario (`normal`, `history_expired`,
`rate_limit`), termasuk backfill 12/12, coalesce/cooldown, restart-safe, dan tanpa `watch/stop`.
Artinya lane Gmail tidak teregresi oleh integrasi SSO; ini tetap bukti fixture, bukan Gmail live.

## Validasi skrip kesiapan terhadap backend Go nyata

`scripts/verify/uat_readiness.sh` juga dijalankan terhadap backend Go integrasi yang benar-benar
dinyalakan (profil fixture, port isolasi 18091, SSO sengaja mati):

- `/readyz` → `200` (OK)
- `/v1/auth/google/nonce` → `503 google_sso_disabled` (dilaporkan BELUM, benar untuk profil tanpa client ID)
- `/v1/gmail/status` → `401` tanpa token (dilaporkan OK: rute terpasang)
- HP `(serial diredaksi)` terdeteksi, Play Services OK, paket debug terpasang, `adb reverse` belum ada

Exit `2` dengan daftar prasyarat kurang — sesuai harapan. Ini memvalidasi skrip terhadap rute
backend nyata, bukan hanya server HTTP tiruan. Temuan higiene: proses backend lama
(`/tmp/pam-be-merged`, PID 33035, 23 Nov) masih mendengarkan port 18091 dan sudah dihentikan +
binary-nya dihapus.
