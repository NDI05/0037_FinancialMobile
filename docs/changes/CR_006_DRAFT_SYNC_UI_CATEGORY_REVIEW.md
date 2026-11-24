# CR-006 — DRAF revisi SRS: UI sinkronisasi, penyederhanaan Ringkasan, alasan tinjauan kategori

Tanggal: **23 November 2026**.
Status: **arah UI disetujui pemilik dalam mandat PM; draf konsolidasi SRS, BELUM diterapkan**.
Full SRS resmi tetap v0.5; file DOCX/PDF, requirement IDs dan version history resmi tidak
berubah oleh CR ini. Nomor versi SRS berikut dan nomor FR/NFR/test final belum ditetapkan.

Dokumen ini melengkapi [CR-005](CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md) (auto-sync, batch
kategori, status proses) untuk perubahan yang dibuktikan pada 23 November 2026.

## 1. Alasan perubahan

1. Ringkasan menampilkan banner "Tidak ada email Livin yang cocok…" beserta CTA impor,
   kartu "Sumber data", dan ikon gear yang menduplikasi jalur pengaturan. Pengguna meminta
   Ringkasan menjadi ringkasan, bukan pusat aksi impor.
2. Status sinkronisasi sulit dipahami sebagai teks berjalan di top bar dan tidak
   menjelaskan partial/error. Pengguna meminta status dapat diketuk, menjaga kejelasan
   (status singkat di top bar, penjelasan lengkap di satu sheet), dan tidak memicu impor
   hanya dengan membuka panel.
3. Judul halaman di top app bar tidak selalu optis di tengah layar (bergeser oleh tombol
   back/ikon status/judul panjang).
4. Kategori AI tidak dapat memberi alasan ketika merchant dari email hanya berupa nomor
   referensi sehingga hasilnya "Perlu tinjauan" tanpa penjelasan di Android.
5. Implementasi lama impor (layar `ImportJob` + route sendiri) sudah tidak dipakai setelah
   sinkronisasi menjadi app-scoped; harus dihentikan sesuai RETIREMENT_POLICY.

## 2. Calon requirement (draf, belum resmi)

| ID draf | Rumusan calon requirement |
|---|---|
| DRAFT-UI-01 | Ringkasan tidak menampilkan banner ketidakcocokan bulan, CTA impor, kartu Sumber data, atau ikon gear; Pengaturan tetap dapat diakses dari navigasi bawah; keadaan bulan kosong yang jujur tetap ditampilkan. |
| DRAFT-UI-02 | Judul di setiap top app bar PAM dipusatkan terhadap lebar layar penuh, dan pusatnya tidak bergeser oleh tombol back, ikon status, judul panjang, maupun ukuran font 200%. |
| DRAFT-UI-03 | Top bar menampilkan status data sebagai ikon yang dapat diketuk (target sentuh ≥48 dp). Saat status berubah, label singkat tampil sementara (fade-in, tahan 2–3 detik, fade-out), icon tetap ada, animasi tidak diulang saat recomposition, dan TalkBack membacakan status lengkap setelah teks visual hilang. |
| DRAFT-UI-04 | Satu sheet "Status pembaruan" memuat bagian Data transaksi, Kategori, dan Perlu perhatian. Membuka sheet tidak menjalankan impor. Tombol "Sinkronkan sekarang" memakai periode berjalan, memicu koordinator impor yang ada (`runManualMonth`/`resumeUpload`), memakai single-flight yang sama untuk manual/otomatis/tap berulang, non-aktif selama proses, menampilkan tahap pipeline dan progress bar (indeterminate bila total belum diketahui, progress nyata bila diketahui), dan tetap berjalan ketika sheet ditutup atau pengguna berpindah halaman. |
| DRAFT-UI-05 | Hasil sinkronisasi dibedakan: metrik impor (cocok/tersimpan/duplikat/dilewati/belum terbaca) terpisah dari antrean kategori AI; percobaan terakhir dibedakan dari keberhasilan terakhir; unggahan lokal yang belum diakui ditampilkan sebagai pending; nol/partial dijelaskan; kegagalan menampilkan alasan yang relevan dan tombol "Coba lagi" yang melanjutkan unggahan pending, bukan memulai impor ulang. |
| DRAFT-UI-06 | Route/ layar impor lama tidak lagi dapat dijangkau dari aplikasi maupun shortcut; string dan referensi mati dihapus. |
| DRAFT-CAT-01 | Bila label merchant dari email Livin hanya berupa nomor referensi/tidak dapat diidentifikasi, transaksi ditandai "Perlu tinjauan" dengan alasan spesifik yang dapat dibaca pengguna (bukan kode internal), tidak dikirim ke provider AI, tidak menimpa kategori pengguna, dan detail transaksi menjelaskan bahwa nilai tersebut adalah nomor referensi. |
| DRAFT-CAT-02 | Alasan klasifikasi yang tidak dikenal pemetaan klien ditampilkan dengan salinan generik; kode internal/token tidak bocor ke UI. |

## 3. Dampak yang perlu diputuskan saat revisi resmi

- Penomoran FR/NFR dan test final untuk UI-01..06 dan CAT-01..02.
- Apakah status singkat top bar dihitung sebagai bagian status proses CR-005 atau
  requirement UI tersendiri.
- Batas verifikasi yang masih terbuka (jangan diubah menjadi klaim lulus): progress
  berjalan lama dan TalkBack live belum tertangkap di perangkat; alasan kategori baru sudah
  terverifikasi lewat unit/backend tapi belum tampil dengan data pemilik yang dapat
  diklasifikasi.
- Tidak ada perubahan pada batas biaya, consent AI, sanitasi merchant, isolasi cache per
  owner, dedup, atau maksimum 5 merchant per request.

## 4. Addendum 24 November 2026 — bahasa Indonesia ringkas dan periode riwayat

| ID draf | Calon requirement | Bukti penerimaan |
|---|---|---|
| DRAFT-UI-07 | UI utama berbahasa Indonesia yang ringkas. Judul, nominal, periode, aksi utama, serta info yang dibutuhkan untuk keputusan pengguna tetap terlihat. Pindahkan detail teknis, timestamp berulang, dan rincian hitungan ke sheet yang sudah ada. | Walk Ringkasan, Transaksi, Detail, Analisis, dan Pengaturan. Verifikasi pesan kosong, gagal, konflik, dan status AI tetap jelas, tanpa pengulangan instruksi. |
| DRAFT-UI-08 | Ikon top bar membuka satu sheet `Status pembaruan`. Tampilkan status singkat seperti **Mengambil riwayat**, **Menunggu AI**, **Perlu tinjauan**, atau **Coba lagi**. Tampilkan satu progress bar per tahap aktif. Persentase hanya muncul jika total diketahui. | Mock dan uji proses awal, antrean AI, hasil parsial, retry, serta buka ulang sheet. Progress tidak menghalangi penggunaan transaksi yang sudah tersimpan. |
| DRAFT-UI-09 | Pemilih periode pada Ringkasan, Transaksi, Analisis, dan sheet impor membatasi navigasi pada jendela setahun yang sama. Nonaktifkan panah maju saat bulan berjalan dan panah mundur saat bulan tertua. Bulan batas yang hanya tercakup sebagian dapat dijelaskan dengan label pendek. Editor anggaran mengikuti keputusan FR-42. | Uji dua ujung periode, bulan berikutnya, argumen rute/API, rollover WIB, font 200%, TalkBack, dan kepindahan Ringkasan–Transaksi. |

Pertahankan penjelasan penting tentang izin Gmail, penggunaan Azure, cakupan email,
dan retensi pada saat pengguna mengambil keputusan. Rincian tambahan dapat dibuka
dari tempat yang relevan. Gunakan [definisi periode pada CR-005](CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md#batas-periode-dan-dampak-retensi).
