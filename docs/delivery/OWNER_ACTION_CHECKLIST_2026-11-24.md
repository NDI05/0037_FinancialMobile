# Checklist aksi pemilik — penutupan gate Gmail/SSO/Rp0 (24 November 2026)

Dokumen ini merangkum satu-satunya pekerjaan yang tersisa. Semua prasyarat otomatis sudah
diverifikasi siap (`scripts/verify/uat_readiness.sh` → exit 0; backend `00bb515`, Android
`8824fff`, APK sha256 `5368c92f…`, DB live 914 transaksi / 21 job COMPLETE). Untuk putaran SRS v0.7, pasangan build yang berlaku adalah Android `a629c41` + backend `dd5bc88` ([gate v0.7](FINAL_GATES_SRS_V07_2026-11-24.md), [UAT final](../evidence/FINAL_UAT_SRS_V07_2026-11-24.md)); status gate pemilik di bawah tidak berubah.

> **Update 25 November 2026:** pasangan build terbaru adalah Android `0cf7faf` (APK sha256
> `5a2f71a1…`) + backend `d42d7c4` (dokumentasi `aa33fb8`, migrasi **22**). Backend UAT lokal sudah
> memakai binary perbaikan nominal; DB UAT lokal sudah dimigrasi sekali jalan (915 baris EMAIL
> diskalakan, 10 baris MANUAL utuh). Karena itu, setelah membuka aplikasi, **refresh daftar
> Transaksi/Ringkasan** dan pastikan nominal email Livin sudah sesuai (tidak lagi 100x kecil).
> Analisis lama yang tersimpan perlu dibuat ulang. Sisa gate pemilik tidak berubah; lihat
> [verifikasi ulang gate 25 Nov](GATE_RECHECK_2026-11-25.md). Untuk G36, ikuti langkah praktis di
> [runbook G36](OWNER_G36_RUNBOOK_2026-11-25.md) (prasyarat, Run literal, onboarding Gmail, email
> Livin alami, dan bukti yang perlu dikirim).

## 1. G36 — UAT SSO Google + tombol Run Android Studio

Prasyarat (sudah siap): Web Client ID terpasang di `local.properties`/`PAM_GOOGLE_SSO_CLIENT_ID`,
SHA-1 debug `com.pam.recap.debug` terdaftar pada OAuth Android client, backend berjalan di
`127.0.0.1:18090`, `adb reverse tcp:18090 tcp:18090` aktif, HP `(serial diredaksi)` terhubung.

**Sudah terbukti 24 Nov 21:49–21:50 WIB:** login Google nyata dari HP + sesi PAM terbit + binding
identitas + 914 transaksi tetap unik (0 duplikat), dengan bukti perangkat `MainActivity` resumed
dan picker Credential Manager — lihat [SSO_LIVE_PHONE](../evidence/SSO_LIVE_PHONE_2026-11-24.md).
Sisa G36: tombol **Run literal**, onboarding Gmail (tautkan/skip), dan email baru saat app tertutup.

Langkah:
1. Buka proyek `pam-app` di Android Studio, pilih konfigurasi **app**, tekan **Run** pada HP fisik.
2. Di aplikasi: tekan **Masuk dengan Google**, pilih akun Google uji pada picker.
3. Bila akun lama: login PAM dulu, lalu buka sheet **"Tautkan Gmail untuk impor Livin"** dan
   sambungkan (atau tekan **Lewati** untuk menguji jalur skip).
4. Catat hasil yang terlihat: login berhasil/gagal, akun tertaut tanpa duplikat transaksi,
   sheet onboarding tampil, tombol **Sinkronkan sekarang** berfungsi.

Bukti yang dikirim balik: tangkapan layar alur + konfirmasi bahwa tombol Run dipakai (bukan
`adb install`). Server akan mencatat `POST /v1/auth/google` (dan `/link` bila menautkan) —
tim dapat memverifikasi signature/issuer/audience/nonce serta munculnya baris `google_identities`
tanpa perlu data pribadi di chat.

## 2. G29/G37 — Gate biaya GCP Rp0

Langkah:
1. Buka Google Cloud Console untuk project OAuth yang dipakai, cek **Billing**.
2. Lepas tautan Billing (atau konfirmasi project memang tanpa Billing aktif).
3. Pastikan tidak ada Pub/Sub topic/subscription/Cloud Run/OIDC audience yang dipakai
   (mode default polling tidak memerlukannya).
4. Konfirmasi tagihan Rp0 dan kuota Gmail API gratis pada halaman Billing/Quotas.

Bukti yang dikirim balik: status Billing (tertaut/tidak) + tangkapan halaman biaya/kuota.
Gate Rp0 baru boleh PASS setelah Billing tidak tertaut **dan** alur live (G25/G36) lulus.

## 3. G24 — Keputusan merge/deploy/SRS (menunggu keputusan pemilik)

Langkah:
1. Tinjau tiga usulan perubahan: CR-005 (auto-sync batch/status), CR-006 (UI sync/kategori),
   CR-007 (Gmail API + workflow setahun; default polling 15 menit, backend harus hidup).
2. Beri keputusan: setujui/tolak/tunda per CR.
3. Setelah disetujui: merge `feat/c2-android`→`main` (Android) dan `integration/2026-11-24`→`main`
   (backend), deploy, lalu revisi SRS resmi.

## 4. G14 — Drill hapus retensi nyata (opsional, owner-gated)

Langkah: salin DB ke berkas terpisah, aktifkan `RETENTION_DELETE_ENABLED` + token internal pada
salinan, jalankan sweep hapus untuk data di luar jendela setahun, lalu restore dari backup dan
pastikan data kembali. Jangan jalankan pada DB live tanpa backup.

## Batasan yang tetap berlaku

- Proxy/runtime di sesi ini tidak punya akses `gcloud`; verifikasi Billing harus dilakukan pemilik.
- Timer/otomasi macOS tidak dapat menekan tombol Run di Android Studio; langkah 1 harus manual.
- Jangan mengirim password/OTP/token/secret ke chat atau repo; cukup status dan tangkapan layar
  yang tidak memuat nilai rahasia.
