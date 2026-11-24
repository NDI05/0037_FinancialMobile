# Kesiapan G24 — pengesahan SRS v0.7, merge, dan deploy (draf 24 Nov 2026)

Status gate: **NOT RUN** (menunggu keputusan pemilik). Dokumen ini mencatat kesiapan teknis dan
keputusan yang diperlukan; tidak menyatakan SRS resmi berubah, tidak melakukan merge/deploy.

## 1. Pasangan build yang diusulkan

- Android: repo `pam-app`, branch `feat/c2-android`, HEAD <commit final v0.7>, varian `debug`,
  APK `app/build/outputs/apk/debug/app-debug.apk` sha256 <hash>.
- Backend Go: repo `0037-Backend-Financial`, branch `integration/2026-11-24`, HEAD <commit final>,
  build dari working tree + overlay loopback UAT, hash binary <hash>.
- Endpoint UAT: `http://127.0.0.1:18090` (loopback + `adb reverse`), `/healthz`/`/readyz` 200.
- Profil: Turso privat 600, OAuth Web client pemilik, provider Azure terpisah untuk Analisis
  (consent terpisah), polling 15 menit.

## 2. Perubahan cakupan v0.7 yang sudah diimplementasikan

- Pensiun: anggaran bulanan/batas kategori/alur saran→anggaran; lampiran foto/PDF/kamera/OCR;
  administrasi pengguna, undangan admin, perubahan peran. Route API dihentikan; UI/navigasi/izin
  dihapus; tabel/migrasi/data lama dipertahankan.
- Dipertahankan: Tambah Transaksi manual (tanpa file), impor Gmail, Ringkasan, koreksi kategori,
  Analisis AI opsional dengan consent terpisah, akun sendiri/sesi/penautan Google.
- Deviasi ditutup: FR-16/NFR-15 (window setahun bergulir WIB), FR-32 (drill-down Kartu Masuk/
  Keluar), FR-67/FR-69 (antrean klasifikasi pasca-backfill, batch ≤5, caseId), FR-79/FR-80
  (sheet Status pembaruan tunggal, timestamp percobaan vs sukses terpisah).

## 3. Keputusan pemilik yang diperlukan sebelum merge/deploy

1. **Pengesahan SRS v0.7**: dokumen masih draft; halaman review/sign-off kosong. Perubahan
   requirement hanya sah lewat persetujuan pemilik. OI-27 (pemetaan akademik D.5/D.6/D.9) dan
   OI-28 (pendaftaran pengguna baru) masih terbuka.
2. **Merge**: Android `feat/c2-android` → `main` dan backend `integration/2026-11-24` → `main`.
   `main` saat ini: Android <hash>, backend <hash>, tidak disentuh sepanjang pekerjaan.
3. **Deploy**: pilihan host tanpa biaya tambahan (laptop saat terjaga vs host gratis), TLS/domain,
   registrasi OAuth redirect rilis, penempatan secret, dan jadwal cutover. Deploy belum dijalankan.
4. **Retensi/backup**: izin mengaktifkan `RETENTION_DELETE_ENABLED` pada data nyata setelah backup,
   restore, dan drill G14 disetujui; default tetap report-only.
5. **Rp0**: bukti Billing GCP tidak tertaut (G29/G37) diperlukan dari pemilik; perubahan billing
   tidak dilakukan dalam mandat ini.
6. **Pendaftaran**: keputusan OI-28 sebelum kontrak onboarding berubah; saat ini akun baru tetap
   memerlukan jalur undangan yang sudah ada, tidak ada pendaftaran publik/auto-create Google.

## 4. Bukti yang perlu dilampirkan saat penutupan

- Hasil tes/vet/build backend + hash binary; hasil tes/lint/build Android + hash APK.
- Bukti UAT HP (ADB-install) terpisah dari tombol Run Android Studio bila Run belum dijalankan.
- Matriks gate v0.7 dan tabel requirement→bukti.
- Status G14/G29/G36/G37 per sub-butir.
