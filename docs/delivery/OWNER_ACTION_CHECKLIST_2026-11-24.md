# Checklist aksi pemilik — penutupan gate PAM (diperbarui 25 November 2026, 19:25 WIB)

Dokumen ini adalah **satu-satunya daftar pekerjaan tersisa** setelah seluruh verifikasi otomatis
selesai. Isinya sudah disesuaikan dengan kondisi pasca-merge 25 November; instruksi lama 24 Nov
(hapus kode undangan, pilih host Oracle/Render, merge branch kerja) **tidak berlaku lagi** dan
hanya tersimpan sebagai riwayat di [verifikasi ulang gate](GATE_RECHECK_2026-11-25.md).

Pasangan build yang berlaku:

- Android `main` **`1432557`** (kode terakhir `112c556`), APK terpasang sha256 `54095330…`
  (47.746.357 B), worktree bersih, `HEAD == origin/main`.
- Backend `main` **`54618f9`**, CI `45609200445` (backend) + `45609200337` (deploy-host) **success**,
  host Azure **`https://pam-70-153-86-163.sslip.io`** (`/healthz` + `/readyz` **200**), polling
  Gmail 15 menit aktif.

## 1. G36 — UAT SSO Google + onboarding Gmail + email Livin alami

**Sudah terbukti:** tombol **Run** dari Android Studio dipakai pemilik ke HP fisik; login →
logout → relogin Google terekam backend (204/200, sesi baru); test user OAuth tersedia.
**Sisa yang hanya bisa dilakukan pemilik:**

1. Buka `pam-app` di Android Studio → konfigurasi **app** varian `debug` → **Run** ke HP fisik
   (bukan `adb install`).
2. Login dengan **akun Google uji** (bukan akun pribadi).
3. Pertama tekan **Lewati** (aplikasi tetap bisa dipakai tanpa Gmail), lalu dari **Pengaturan →
   Akun → Tautkan Gmail** selesaikan consent (hanya baca email Livin).
4. **Force stop** aplikasi, lalu kirim satu email notifikasi Livin baru ke Gmail yang ditautkan.
   Tunggu minimal satu siklus polling (±15 menit), buka aplikasi lagi.
5. Periksa: **tepat satu** transaksi baru, nominal sesuai email (contoh `Rp25.000` → `Rp25.000`),
   tidak ada duplikat.

Detail prasyarat, waktu tunggu, dan bukti yang perlu dikirim: [runbook G36](OWNER_G36_RUNBOOK_2026-11-25.md).
Baseline verifikasi otomatis sudah dicatat (876 transaksi `EMAIL`; ekspektasi **+1** setelah uji).
Jangan mengulang impor berkali-kali bila gagal — laporkan waktu kejadian + `logcat`.

## 2. G29/G37 — Gate biaya GCP Rp0

1. Google Cloud Console → **Billing**: pastikan project OAuth tidak tertaut ke akun penagihan
   (atau konfirmasi tanpa Billing aktif).
2. Pastikan tidak ada Pub/Sub, Cloud Run, atau OIDC audience yang dipakai — mode default
   **polling 15 menit** tidak memerlukannya.
3. Cek **Billing/Quotas**: tagihan **Rp0** dan kuota Gmail API dalam batas gratis.

Bukti yang dikirim: status Billing (tertaut/tidak) + tangkapan halaman biaya/kuota. Gate Rp0 baru
boleh PASS setelah Billing tidak tertaut dan alur live G36 lulus.

## 3. G24 — Deploy produksi dan SRS resmi (keputusan pemilik)

Merge branch kerja ke `main` **sudah selesai** untuk kedua repo. Yang masih milik pemilik:

1. Tinjau usulan CR-005/CR-006/CR-007 dan keputusan cakupan SRS v0.7 (CR-008/CR-009).
2. Putuskan apakah host Azure saat ini dipakai sebagai produksi atau diganti; demo Vercel hanya
   demo (AI fake).
3. Setelah setuju: deploy produksi + revisi SRS resmi. Agent tidak melakukan merge/deploy/SRS.

## 4. OI-27 — Keputusan pemetaan akademik

Baca [memo keputusan OI-27/OI-28](DECISION_MEMO_OI27_OI28_2026-11-25.md) dan addendumnya; putuskan
pemetaan modul ke pedoman akademik. OI-28 sudah diputuskan lewat CR-009 (SSO langsung).

## 5. G14 — Drill hapus retensi pada data pemilik (opsional, owner-gated)

Drill fixture **59/59 PASS** sudah ada. Untuk data pemilik: setujui salinan DB terpisah, aktifkan
`RETENTION_DELETE_ENABLED` + token internal **hanya pada salinan**, jalankan sweep baris di luar
jendela setahun, lalu restore dari backup dan pastikan data kembali. Jangan dijalankan pada DB live
tanpa backup.

## Batasan

- Proxy sesi ini tidak punya `gcloud`; verifikasi Billing harus dilakukan pemilik.
- Timer/otomasi macOS tidak bisa menekan tombol Run; langkah G36 nomor 1 manual.
- Jangan mengirim password/OTP/token/secret/email asli/serial perangkat ke chat atau repo.
