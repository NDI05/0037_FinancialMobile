# Runbook pemilik — menutup G36 (Run literal Android Studio + onboarding Gmail + email alami)

Tujuan: menyelesaikan sisa gate **G36 PARTIAL** pada build UAT yang sama dengan yang akan dipakai
pemilik. Semua langkah di bawah dilakukan pemilik di HP fisik; agent hanya menyiapkan runbook dan
memverifikasi hasil yang dilaporkan. Jangan memakai akun pribadi pemilik: gunakan **akun uji**.

## Prasyarat

- HP fisik terbuka (bukan layar kunci `NotificationShade`) dan USB debugging aktif;
  `adb devices` menampilkan perangkat sebagai `device`.
- Android Studio terpasang (`/Applications/Android Studio.app`) dan proyek `pam-app` terbuka.
- Backend UAT resmi berjalan di host Azure: `https://pam-70-153-86-163.sslip.io` dengan
  `GET /healthz` dan `GET /readyz` = 200. Tidak perlu backend lokal maupun `adb reverse`.
- Akun Google uji dan kotak surat uji yang bisa menerima email Livin.
- `local.properties` sudah memakai `PAM_BACKEND_BASE_URL=https://pam-70-153-86-163.sslip.io`;
  build varian `debug` di Android Studio akan memakai endpoint host itu. Kabel USB hanya untuk
  memasang/menjalankan build, bukan syarat aplikasi berjalan (APK tetap jalan setelah kabel
  dicabut selama ada internet).

## Menyiapkan Test user Google (untuk SSO live)

Konsol: **Google Cloud Console** milik pemilik proyek OAuth yang dipakai build UAT.

1. Cari proyek yang memiliki **OAuth client** dengan Client ID yang sama seperti di
   `~/.config/pam/gmail-uat.env` (`GOOGLE_SSO_CLIENT_ID`, `GMAIL_CLIENT_ID`). Cocokkan di
   **APIs & Services → Credentials → OAuth 2.0 Client IDs**.
2. Buka **APIs & Services → OAuth consent screen**. Selama *Publishing status* masih **Testing**,
   hanya akun yang terdaftar yang boleh login.
3. Di bagian **Test users** klik **Add users**, masukkan **email Google pemilik/akun uji**
   (akun yang akan dipakai login di HP), lalu **Save**. Tunggu ±1 menit agar berlaku.
4. Pastikan **Google Sign-In** dan **Gmail API** aktif di proyek yang sama.
5. Di HP, buka aplikasi → **Masuk dengan Google** (atau Daftar dengan Google) → pilih akun uji →
   setujui consent. Bila muncul "Access blocked / app not verified", berarti email tersebut belum
   masuk daftar Test users atau memakai proyek OAuth yang berbeda.

Catatan: menambahkan Test user tidak memerlukan publikasi aplikasi; jangan mengubah status ke
Production saat uji. Jangan pernah menempelkan Client Secret/token ke chat atau dokumen.

## Waktu tunggu email Livin

Runtime UAT resmi memakai `GMAIL_MODE=polling` dengan `GMAIL_DELTA_INTERVAL_SECONDS=900` (15 menit).
Setelah email Livin masuk ke kotak surat tertaut, sinkronisasi berikutnya terjadi paling lambat
±15 menit; buka aplikasi/Transaksi lalu tarik sinkronisasi bila tersedia. Untuk uji "app tertutup",
tutup aplikasi (force stop) sebelum email masuk, tunggu minimal satu siklus polling, lalu buka lagi.
Email Livin harus benar-benar dikirim ke alamat Gmail yang ditautkan (bukan email lain).

## Langkah

1. **Catat baseline**: commit Android (`git rev-parse HEAD`), hash APK, commit backend, dan waktu
   mulai (WIB). Pastikan worktree bersih dan build dari `main`.
2. **Run literal**: pilih varian `debug`, konfigurasi `app`, lalu **Run** di Android Studio ke HP
   fisik. Jangan memakai `adb install` sebagai pengganti langkah ini.
3. **Login/logout/relogin** dengan akun Google uji: login → logout → login lagi. Pastikan sesi
   kembali normal dan tidak ada data akun lain yang terlihat.
4. **Onboarding Gmail**: pertama pilih **Lewati** (tanpa menautkan), lalu dari Pengaturan pilih
   **Tautkan Gmail** dan selesaikan consent. Pastikan consent yang diminta hanya baca email Livin
   (tanpa akses tulis) dan purpose consent terpisah tampil jelas.
5. **Email Livin alami**: kirim/terima satu email notifikasi Livin baru ke kotak surat uji saat
   aplikasi **tertutup**. Buka aplikasi, tunggu sinkronisasi, lalu periksa:
   - tepat **satu** transaksi baru (tidak dobel),
   - nominal tampil sesuai email dalam rupiah (contoh: email `Rp25.000` → aplikasi `Rp25.000`),
   - merchant/tanggal masuk akal dan tidak ada data mentah email di layar.
6. **Bukti**: screenshot layar Transaksi/Ringkasan dan status sinkronisasi, catat waktu WIB dan
   jumlah transaksi sebelum/sesudah (agregat saja). Jangan menyertakan email asli, token, OTP,
   nomor kartu, atau serial perangkat di laporan.
7. **Laporkan** hasil ke agent/PM: gate G36 PASS/PARTIAL/FAIL + bukti di atas. Agent akan
   memperbarui `GATE_RECHECK_2026-11-25.md` dan log harian; merge/deploy tetap keputusan pemilik.

## Baseline verifikasi otomatis (diisi agent, 25 Nov 19:30 WIB)

Sebelum uji pemilik, agent mencatat baseline **agregat tanpa PII** langsung dari DB Turso `pam-azure-uat`
(baca-saja, tanpa isi email/nama):

- transaksi non-deleted: **876**, semuanya `EMAIL`; transaksi terbaru `2026-11-25T10:49:51Z` (17:49 WIB)
- `gmail_connections`: **1**, terakhir diperbarui `2026-11-25T12:09:53Z` (19:09 WIB)
- `gmail_sync_jobs`: **19 COMPLETE**, terakhir `2026-11-25T12:09:12Z` (19:09 WIB) → polling hidup
- `category_jobs`: **358 CLASSIFIED / 515 NEEDS_REVIEW**, 0 pending

Setelah uji pemilik, agent akan mengulang kueri yang sama dan mengharapkan **tepat +1 transaksi
`EMAIL`** dengan nominal sesuai email; bila +0 atau +2, gate G36 tidak lulus dan tidak boleh
diulang berkali-kali (lihat bagian "Bila gagal").

## Kriteria lulus

- Run literal dari Android Studio berhasil pada varian `debug` di HP fisik.
- Login → logout → relogin berhasil tanpa kebocoran data akun lain.
- Lewati berfungsi (aplikasi tetap bisa dipakai tanpa Gmail), lalu Tautkan Gmail + consent selesai.
- Satu email Livin alami saat aplikasi tertutup menghasilkan **tepat satu** transaksi dengan
  nominal benar (minor IDR, contoh `2_500_000` = `Rp25.000`).

## Bila gagal

- Jangan mengulang impor berkali-kali; simpan `logcat` dan waktu kejadian, lalu laporkan.
- Bila nominal kembali 100x kecil atau transaksi dobel, hentikan uji dan laporkan — itu regresi
  P0, bukan masalah perangkat.
- Jangan melakukan force-push, merge, deploy, atau mengubah SRS resmi untuk "memperbaiki" hasil uji.
