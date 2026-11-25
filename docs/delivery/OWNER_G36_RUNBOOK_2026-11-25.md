# Runbook pemilik — menutup G36 (Run literal Android Studio + onboarding Gmail + email alami)

Tujuan: menyelesaikan sisa gate **G36 PARTIAL** pada build UAT yang sama dengan yang akan dipakai
pemilik. Semua langkah di bawah dilakukan pemilik di HP fisik; agent hanya menyiapkan runbook dan
memverifikasi hasil yang dilaporkan. Jangan memakai akun pribadi pemilik: gunakan **akun uji**.

## Prasyarat

- HP fisik terbuka (bukan layar kunci `NotificationShade`) dan USB debugging aktif;
  `adb devices` menampilkan perangkat sebagai `device`.
- Android Studio terpasang (`/Applications/Android Studio.app`) dan proyek `pam-app` terbuka.
- Backend UAT resmi berjalan dari launcher resmi; `GET /healthz` dan `GET /readyz` = 200.
- Akun Google uji dan kotak surat uji yang bisa menerima email Livin.
- `local.properties` memakai endpoint UAT yang sama dengan build verifikasi. Untuk HP fisik lewat
  kabel USB: `adb reverse tcp:18090 tcp:18090`, lalu `PAM_BACKEND_BASE_URL=http://127.0.0.1:18090`.
  (Emulator memakai `http://10.0.2.2:18090`.) Pastikan `BuildConfig` efektif menunjuk endpoint itu.

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
