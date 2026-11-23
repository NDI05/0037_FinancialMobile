# PAM — instruksi kerja agent

## Git dan otorisasi pemilik

Pemilik mengklarifikasi pada 22 November 2026 bahwa push sebelumnya memang diotorisasi,
dan menginstruksikan: **setiap commit langsung dipush ke branch kerja yang sama**.

- Setelah commit berhasil, segera push ke branch yang sama di `origin`; gunakan upstream yang
  sesuai atau tetapkan upstream pada push pertama. Verifikasi hasilnya dan laporkan hash/ref.
- Aturan ini menggantikan larangan push pada prompt/checkpoint/review PAM yang lebih lama.
  Tidak perlu meminta izin push lagi untuk setiap commit.
- Commit perubahan yang koheren dan pertahankan pekerjaan aktif pihak lain. Instruksi ini
  tidak berarti otomatis men-stage atau membuat commit dari semua WIP yang belum selesai.
- Jika push gagal, pertahankan commit, tangani penyebab yang aman dan catat status aktual.
  Jangan menggunakan force-push, reset destruktif atau menghapus history untuk memaksakan sinkronisasi.
- Merge dan deployment tetap memerlukan instruksi pemilik tersendiri. Push feature branch
  tidak sama dengan penerimaan gate sprint atau izin merge/deploy.
- Setiap branch baru wajib dihapus dari lokal dan remote setelah pekerjaannya
  terintegrasi ke target yang diotorisasi dan hasil integrasi terverifikasi di remote.
  **Pengecualian: `main`.** Lepaskan worktree yang bersih lebih dahulu dan pastikan
  seluruh perubahan unik sudah terintegrasi. Bila masih ada WIP, tuntaskan atau
  amankan pekerjaannya sebelum penghapusan.

## Delivery aktif

Mandat gabungan S2–S4 berada di `docs/agent-prompts/MANDAT_S2_S4.md`.
Living checkpoint berada di `docs/DELIVERY_S2_S4_CHECKPOINT.md`.
Lanjutkan dari status repo/checkpoint terkini dan jaga perbedaan PASS/PARTIAL/NOT RUN.

## Android Studio dan kesetaraan konfigurasi UAT

- Kerjakan dan verifikasi perubahan Android dari proyek `pam-app` yang dibuka di
  Android Studio. Untuk acceptance fitur yang terlihat pengguna, pilih varian `debug`,
  jalankan konfigurasi `app` dari Android Studio pada emulator, lalu uji fitur lewat UI.
  Suite CLI tetap dipakai untuk regresi. Bila Android Studio atau emulator tidak tersedia,
  catat gate Android Studio sebagai `NOT RUN`.
- Untuk UAT pemilik, build dari **Run** Android Studio dan build verifikasi harus memakai
  varian, commit, endpoint Go, database, dan profil provider yang sama. Gunakan konfigurasi
  di [README bagian Android Studio](README.md#b-android-studio-dan-uat-pemilik), dengan
  `PAM_BACKEND_BASE_URL=http://10.0.2.2:18090` di `local.properties` untuk emulator.
  Periksa `BuildConfig` efektif dan `/readyz` dari emulator setelah mengganti konfigurasi.
- Jalankan backend UAT dengan launcher resmi yang memuat profil privat Turso, classifier
  Azure, dan provider analisis. Pertahankan batas biaya serta consent. Simpan secret di
  luar repo dan hindari mencetaknya di log atau laporan.
- Bukti dari backend fixture, port terisolasi, provider simulasi, atau varian
  `assessment` harus menyebut profilnya. Gate UAT pemilik memakai build dan profil UAT
  yang sama seperti yang akan dibuka pemilik. Laporkan varian, endpoint, status provider,
  dan hasil UI yang benar-benar dicoba sebelum serah terima.

## Transparansi progres harian

- Setiap penutupan pekerjaan memperbarui baris tanggal WIB yang sama pada
  [log harian](docs/PROGRESS_DAILY.md) dan ringkasan tabel harian di README.
- Gunakan satu baris per hari, diperbarui dengan hasil tambahan; detail evidence tetap
  pada checkpoint. Tidak perlu membuat file progres baru setiap hari.
- Cantumkan hasil, commit, scope test/evidence, serta gate FAIL/PARTIAL/NOT RUN berikut
  tindak lanjut. Bedakan implementasi, WIP, hasil terlapor dan pengujian fresh.
- Review yang menemukan kegagalan harus terlihat; jangan hanya mencatat keberhasilan,
  menyebut commit sebagai bukti runtime, atau memberi persentase selesai tanpa acceptance.
- Tanggal historis yang direkonstruksi dari Git harus diberi label rekonstruksi.
  Pembaruan README/progres tidak mengubah SRS resmi atau mengotorisasi merge/deploy.

## Kerapian dan penghentian implementasi lama

Mulai 23 November 2026, setiap perubahan yang menggantikan implementasi harus menyertakan
pemeriksaan cleanup sesuai [RETIREMENT_POLICY.md](docs/maintenance/RETIREMENT_POLICY.md).

- Setelah pengganti terverifikasi, telusuri referensi/runtime/test/build/script sebelum
  menghapus kode, dependency, konfigurasi atau aset yang sudah tidak dipakai. Perbarui
  import, DI, README, perintah dan tautan yang terdampak dalam perubahan yang koheren.
- Jangan menyimpan implementasi mati sebagai `old`, `backup`, `final2`, atau salinan baru
  di jalur aktif. Kode yang benar-benar pensiun cukup dipertahankan melalui riwayat Git.
- Dokumen historis yang masih diperlukan dapat dipindahkan ke `docs/archive/` dengan
  penanda tanggal/status dan tautan penggantinya. Dokumen aktif harus mudah ditemukan.
- Jangan menghapus migrasi database yang sudah diterapkan, regression tests yang masih
  membuktikan perilaku, fixture interop yang dibutuhkan, data/credential pengguna, atau WIP
  pihak lain. Build/cache bukan histori dokumen; jangan masukkan ke arsip/Git.
- **SRS resmi DOCX/PDF tidak direvisi, dipindahkan atau dihapus sekarang**; tetap ikuti
  instruksi pemilik untuk menyimpan perubahan kebutuhan sebagai draf sampai revisi diminta.
- Backend Node adalah kandidat audit retirement, bukan perintah menghapusnya segera.
  Pertahankan selama masih ada dependency interop/rollback yang dibuktikan; catat alasan,
  kriteria keluar dan commit referensinya. Instruksi ini tidak mengotorisasi final cutover,
  merge, deployment, penghapusan database atau migrasi data nyata.
- Laporkan ringkas apa yang dihapus/diarsipkan/dipertahankan beserta alasan dan pemeriksaan
  yang relevan. Cleanup masuk definition of done perubahan terkait, bukan alasan menunda
  perbaikan kritis atau memulai refactor seluruh repository di tengah WIP aktif.
