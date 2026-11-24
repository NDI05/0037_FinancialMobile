# CR-008 — Cakupan PAM sebagai aplikasi keuangan pribadi

Keputusan pemilik pada 24 November 2026. Perubahan cakupan produk dicatat untuk revisi SRS. Pemetaan kepatuhan akademik masih menunggu verifikasi pedoman asli dan persetujuan pemilik atas setiap perubahan tambahan.

## Keputusan produk

| Bagian SRS v0.6 | Arahan pemilik untuk revisi |
|---|---|
| Rencana anggaran bulanan dan batas per kategori (§3.8, FR-42–45) | Keluarkan modul anggaran, anggaran tersimpan, dan alur memasukkan saran AI ke anggaran. Analisis keuangan tetap dalam lingkupnya. |
| Lampiran transaksi (§3.11, FR-59) | Keluarkan foto/PDF lampiran transaksi, alur kelola lampiran, dan metadata lampiran dari cakupan produk. |
| Administrasi pengguna (§3.12, FR-65) | Keluarkan dashboard admin dan alur undangan atau pengelolaan akun oleh admin. PAM memiliki satu peran aplikasi, USER. |
| Identitas dan kepemilikan (§3.1 dan §3.14) | Pertahankan SSO, sesi PAM, pengaturan akun sendiri, dan isolasi data tiap pengguna. Satu peran berarti semua akun punya kewenangan yang sama terhadap data masing-masing. Jalur yang sebelumnya bergantung pada undangan admin perlu ditinjau sebelum kontrak onboarding diubah. |

## Dampak pada penerimaan akademik

[Pemetaan pedoman yang tersimpan](../01_SCOPE_TRACEABILITY.md#6-rubrik-pedoman--bukti) mencatat minimal satu peran (D.2), empat entitas terkait dengan field dinamis (D.5), lima fitur CRUDS (D.6), serta simpan dan baca file lokal (D.9). PDF pedoman asli belum ditemukan saat keputusan ini dicatat. Konfirmasi kembali persyaratan tersebut sebelum mengesahkan matriks baru.

- D.2 perlu dibuktikan dengan akses pengguna terautentikasi dan isolasi antar-akun. Peran ADMIN adalah pilihan rancangan lama.
- D.5 dan D.6 perlu dipetakan ulang. Matriks lama memakai anggaran dan lampiran sebagai dua dari empat entitas utama, serta anggaran, lampiran, dan administrasi sebagai tiga dari lima fitur CRUDS.
- D.9 perlu diverifikasi terpisah dari pilihan desain lampiran transaksi. Bila aktivitas file lokal tetap wajib, persetujuan pemilik diperlukan untuk menentukan bentuk yang masuk cakupan PAM.

## Konsolidasi dan transisi

Saat menyusun revisi konsolidasi, selaraskan cakupan, FR/NFR/DR dan kriteria penerimaan terkait, daftar layar, peran, model data, diagram, dan matriks akademik. Gunakan jejak revisi untuk menandai requirement yang dipensiunkan. Fitur atau entitas pengganti memerlukan keputusan tersendiri.

Catatan ini merekam keputusan produk. Perubahan DOCX/PDF, pembersihan Android/Go, migrasi atau penghapusan data, dan uji regresi ditangani pada tahap lanjutan dengan pemetaan persyaratan dan rencana transisi yang telah ditinjau.
