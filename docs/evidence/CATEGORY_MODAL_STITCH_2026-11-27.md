# Bug kategori: modal “Tambah kategori” sesuai Stitch — 27 November 2026

Branch `feature/stitch-redesign`. Perbaikan atas laporan pemilik: UI menambah kategori di Pengaturan
belum sesuai Stitch. Backend, kontrak API, dan data pemilik tidak diubah.

## Masalah sebelumnya

Layar `CategoryManagementScreen` mengganti seluruh daftar dengan form halaman penuh saat pemilik
menekan FAB: judul top bar berubah menjadi “Kategori baru”, isinya field `Nama`, `Kode`, pill
`Pengeluaran/Pemasukan`, `Kolom tambahan`, lalu `Simpan kategori`/`Batal`. Stitch menampilkan
**modal putih di atas daftar** dengan judul “Tambah kategori”, tombol tutup, pratinjau ikon lime, satu
field nama, dan satu tombol gelap “Simpan”.

Bukti state lama: `stitch_redesign_2026-11-25/before/studio_run_category/before_add_category_393dp.png`.

## Perbaikan

`CategoryManagementScreen.kt`:

- Daftar + FAB tetap terlihat; editor dibuka sebagai `Dialog` di tengah, bukan mengganti layar.
- Kartu: putih, radius 24, border `#E2E3DB` 1 dp, padding 20, lebar maksimum **353 dp** (16 dp page
  margin di layar sempit), tinggi maksimum 640 dp dengan scroll internal.
- Header: judul 18/700, aksi `Opsi lanjutan` (tune) dan `Tutup`; dua target sentuh 48 dp.
- Baris input: pratinjau ikon lime 48 dp radius 16 + `BasicTextField` 48 dp radius 16 berlatar
  `#F4F4F1`, placeholder “Nama kategori...”, border fokus `Primary`.
- CTA: `Simpan` selebar kartu, 48 dp, radius 16, latar `#171915`, teks putih.
- Scrim memakai `DialogWindowProvider.window.setDimAmount(0.3f)` sehingga setara
  `light-primary/30` Stitch; sebelumnya scrim platform 0,6 membuat latar terlalu gelap.
- Semua perilaku server dipertahankan. `Kode`, jenis `Pengeluaran/Pemasukan`, dan `Kolom tambahan`
  tidak dihapus: tersedia lewat aksi tune agar look default tetap persis Stitch.
- Pratinjau ikon memakai pemetaan kode/label yang sama dengan baris daftar; tidak ada field ikon di
  kontrak server, jadi tidak ada data ikon palsu yang dikirim.

String (`values/` dan `values-en/`): `category_save` menjadi “Simpan”/“Save”;
`category_name_placeholder` = “Nama kategori...”/“Category name...”; `category_advanced_options` =
“Opsi lanjutan”/“Advanced options”. String mati dari form lama dihapus
(`category_new_title`, `category_field_label`, `category_field_kind`).

## Ukuran vs Stitch (393 dp)

| Elemen | Stitch | APK berjalan |
| --- | --- | --- |
| Kartu modal | 353×216, x=20, radius 24 | 351×214 (border antialias), x=20, radius 24 |
| Padding dalam | 20 | 20 |
| Pratinjau ikon | 48×48, radius 16, `#D9F449` | kotak 48×48, visual 46×46, radius 16, `#D9F449` |
| Pratinjau → atas modal | 85 | 84 |
| Tombol Simpan → atas modal | 148 | 147 |
| Tinggi Simpan | 48 | 48 |
| Scrim | primary 30% | rata-rata `#B2B2B2` di atas putih (setara 30% hitam) |
| Judul | 18/700 | `TitleL` 18/700 |

Perbedaan vertikal absolut kartu berasal dari window Dialog Android yang berpusat di area konten
(bukan viewport browser); posisi relatif semua elemen di dalam kartu sama.

## Verifikasi runtime

APK dipasang lewat **Android Studio Run → Run 'app'** (bukan `adb install` manual) ke
`Pixel_10_Pro_XL`, Android 17/API 37, emulator `emulator-5554`. Screenshot diambil dari proses hasil
Run:

- 393 dp (1179×2627 @480 dpi): `category_modal_393dp.png` + dump.
- 360 dp (1080×2400): `category_modal_360dp.png` + dump.
- 412 dp (1236×2748): `category_modal_412dp.png` + dump.
- Font 200%: `category_modal_font200_393dp.png` + dump; varian advanced
  `category_modal_font200_advanced_393dp.png`, dan setelah scroll
  `category_modal_font200_advanced_save_393dp.png` (Simpan tetap terjangkau).
- Advanced 393 dp: `category_modal_advanced_393dp.png` (jenis, kode, kolom tambahan, Simpan).
- Perbandingan langsung: `category_modal_stitch_vs_android_393dp.png`.

Regresi fungsi pada build yang sama:

- Tambah kategori uji “Uji Modal” dari modal → daftar berubah 11 → 12 kategori.
- Buka ulang baris tersebut → modal berjudul “Ubah kategori” dan field nama terisi.
- Hapus kategori uji lewat dialog hapus → daftar kembali 11 kategori; data uji bersih.
- Font 200% + advanced: modal dapat di-scroll sampai tombol `Simpan`; tidak ada teks terpotong
  horizontal.
- `logcat -b crash` kosong; tidak ada ANR/crash.

Gate akhir: `:app:testDebugUnitTest` **646 tes / 0 gagal / 0 error**, `assembleDebug` lulus,
`lintDebug` **0 error / 265 warning / 1 hint**. Merge/deploy menunggu izin pemilik.

## Batas

- Ikon kategori belum dapat diganti dari modal karena kontrak server tidak punya field ikon;
  pratinjau mengikuti kode/label. Jika pemilik ingin ikon persisten, perlu perubahan kontrak backend
  terpisah (tidak dilakukan di sesi ini).
- Aksi tune adalah tambahan aksesibilitas untuk mempertahankan field lanjutan; Stitch tidak
  menggambarkan state ini.
