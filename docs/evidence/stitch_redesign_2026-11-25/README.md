# Bukti visual redesign Stitch — 25/26 November 2026 WIB

Branch `feature/stitch-redesign`, baseline `6569608`. Semua screenshot aplikasi diambil dari APK
debug yang sedang berjalan di HP fisik Redmi (`105397ba0507`, 1080×2400, density 440 → **393 dp**),
bukan render Compose/preview. Referensi Stitch dirender dari `code.html` dengan Playwright 1.64.1
pada viewport **393×852, DPR 2**, font Manrope dan ikon Material Symbols termuat.

## Metode

- APK: `:app:assembleDebug`, dipasang ulang setiap iterasi; screenshot + dump `uiautomator` disimpan
  berpasangan (`.png` + `.txt`) supaya posisi teks bisa diukur dalam dp.
- Perbandingan memakai periode, jumlah baris, nominal, state, dan skala yang sama. Data berasal dari
  akun pilot (77 transaksi Mandiri November 2026); tidak ada data karangan pada layar data nyata.
- Offset vertikal Android: status bar perangkat **37,5 dp** + navigation bar **±49 dp**. Konten Stitch
  dihitung dari y=0; konten aplikasi mulai y≈37,5 dp dan berakhir y≈759 dp. Karena itu layar 393 dp
  menampilkan sekitar 65 dp lebih sedikit daripada render 852 dp; bagian "Transaksi terbaru" di
  Ringkasan baru terlihat setelah scroll. Ini deviasi platform, bukan layout yang hilang.
- Rotasi dikunci (`accelerometer_rotation 0`, `user-rotation lock 0`) sebelum setiap capture agar
  tidak menghasilkan screenshot landscape.

## Matriks per layar

| Layar Stitch | Sebelum | Referensi | Sesudah (APK berjalan) | Perubahan struktur utama |
| --- | --- | --- | --- | --- |
| 1. Masuk/Daftar | `before/12_masuk_daftar_448dp_emulator.png` | `reference/1._masuk.png` | belum diambil ulang di sesi ini (state login) | top bar terpusat, kartu putih radius 24, CTA accent lebar penuh |
| 2. Tautkan Gmail | — | `reference/2._tautkan_gmail.png` | `after/after_gmail_link_393dp_v1.png` | hero + chip status, kartu izin/retensi, CTA "Tautkan Gmail" |
| 3. Ringkasan | `before/01_ringkasan_393dp.png` | `reference/3._ringkasan.png` | `after/after_ringkasan_393dp_v6.png` | kartu bank per bank (opsi B), hero pengeluaran, baris pemasukan, kartu kategori 6 track, daftar terbaru grouped card |
| 4. Transaksi | `before/02_transaksi_393dp.png` | `reference/4._transaksi.png` | `after/after_transaksi_393dp_v6.png` (daftar); `after/after_transaksi_fab_actions_dark_393dp.png` (FAB dua opsi gelap, 26 Nov malam), `_360dp.png`, `_412dp.png`, `_font200_393dp.png`; riwayat `after_transaksi_fab_actions_393dp.png` (pill putih yang tak terbaca); `after/after_transaksi_classify_manual_393dp.png` | search 48 dp, chip filter 36 dp, grup tanggal, baris 84 dp, FAB lime 56 dp radius 24; opsi AI + tambah dalam satu FAB, kini kontras `OnInk` di atas `Ink` |
| 5. Detail transaksi | `before/07_detail_transaksi_393dp.png` | `reference/5._detail_transaksi.png` | `after/after_detail_393dp_v1.png` | hero 24 dp: chip sumber, nominal 32/700, judul, caption; kartu Tanggal/Kategori/Catatan |
| 6. Tambah transaksi | `before/08_tambah_transaksi_393dp.png` | `reference/6._tambah_transaksi.png` | `after/after_manual_393dp_v2.png` | segmented 20 dp, kartu nominal (prefix Rp + pemisah ribuan), kartu field, CTA accent |
| 7. Analisis | `before/05_analisis_393dp.png` | `reference/7._analisis.png` | `after/after_analisis_stitch_393dp.png` (26 Nov malam), `_360dp.png`, `_412dp.png`, `_font200_393dp.png`; riwayat `after_analisis_393dp_v2/v3.png` | pill bulan, kartu insight lime dengan body `Rp… dari N transaksi`, kartu kategori 4 baris, temuan ringkas; toggle/pager/saran dihapus dari viewport pertama; batasan dipindah ke catatan bawah |
| 8. Pengaturan | `before/04_pengaturan_393dp.png` | `reference/8._pengaturan.png` | `after/after_pengaturan_akses_aplikasi_393dp.png` (26 Nov), `after/after_pengaturan_akses_360dp.png`, `after/after_pengaturan_akses_412dp.png`, `after/after_pengaturan_akses_font200_top.png` | section uppercase 12/600, kartu putih radius 24; section **AKSES APLIKASI** berisi switch notifikasi + Kategorisasi AI + Analisis AI + riwayat; section Gmail/AI terpisah dihapus |
| 9. Kelola kategori | `before/06_kelola_kategori_393dp.png` | `reference/9._kelola_kategori.png` | `after/after_kategori_393dp_v1.png` (state sesi berakhir) | judul + back, search 48 dp, grouped list radius 24, FAB accent |
| Sheet status pembaruan | `before/03_sync_sheet_393dp.png` | `reference/keadaan_sinkronisasi_semua_state.png` | `after/after_status_sheet_393dp_v2.png`, `after_status_running_393dp.png`, `after_status_reopen_393dp.png`, `after_status_result_393dp.png` | header ringkas + "Terakhir", kartu status padat, aksi tunggal, hasil/progress di sheet yang sama |
| 360 dp | `before/09_ringkasan_360dp.png` | `reference/pemeriksaan_360_dp_412_dp_font_200.png` | `after_ringkasan_360dp.png`, `after_transaksi_360dp.png`, `after_pengaturan_akses_360dp.png` | margin 16 dp; tidak ada teks terpotong |
| 412 dp | `before/10_ringkasan_412dp.png` | idem | `after_ringkasan_412dp.png`, `after_transaksi_412dp.png`, `after_pengaturan_akses_412dp.png` | "Transaksi terbaru" terlihat tepat di atas nav (785 dp), sama seperti referensi |
| Font 200% | `before/11_ringkasan_font200.png` | idem | `after_ringkasan_font200.png`, `after_pengaturan_akses_font200_top.png` | tidak ada clipping horizontal; konten bawah menjadi scroll |

## Ukuran yang dicocokkan (393 dp, dp dari atas window)

| Elemen | Stitch | APK akhir |
| --- | --- | --- |
| Judul top bar | y16 h24, 18/700, terpusat | y15,6 h24, terpusat |
| Ringkasan: kartu bank | y162, 353×245, radius 24 | y≈198 (termasuk 37,5 status), 353×245, radius 24 |
| Ringkasan: nominal pengeluaran | 32/700 | 32/700, tabular |
| Ringkasan: kartu kategori | y445, 353×295 | proporsi sama; tinggi minimum 274 dp agar nav tetap di bawah |
| Transaksi: search | 353×48, radius 16 | 353×48, radius 12 (`PamRadius.sm`), touch 48; deviasi radius visual 4 dp |
| Transaksi: baris | tinggi minimum 64 (padding 16) | ±74 (padding 48 total + konten), tetap > 48 |
| Transaksi: FAB | 56, radius 24, x317 y716 | 56, radius 24, x317 y689–745 |
| Nav bawah | konten 65 dp + 1 dp divider, y787 | konten 65 dp + 1 dp divider; divider di y759,4 dp + 37,5 dp status bar; bar kini **sibling layout** (bukan overlay), tinggi terukur 114 dp termasuk inset sistem |
| Kartu | putih, outline `#E2E3DB` 1 dp, tanpa shadow | sama |
| Palet | canvas `#FAFAF7`, ink `#171915`, lime `#D9F449` | sama |

## Deviasi yang disengaja / tersisa

1. **Inset Android**: status bar dan navigation bar memakan ±86,5 dp; jarak pandang 852→759 dp.
   Ringkasan menampilkan kartu kategori penuh tetapi header "Transaksi terbaru" ada di bawah fold.
2. **Kartu bank**: hanya Mandiri data nyata (`source == EMAIL`); Jago/BRI pratinjau berlabel karena
   backend belum punya dimensi bank. Tidak ada saldo/budget sesuai keputusan pemilik.
3. **Analisis**: banner "kedaluwarsa" adalah state nyata dan mendorong kartu insight ke bawah;
   prototipe tidak menggambarkan state ini.
4. **Pengaturan**: tidak ada toggle AI palsu — baris menampilkan status consent nyata dan membuka
   riwayat consent; tautan pratinjau notifikasi diganti pengaturan sistem; widget memakai pin flow
   nyata. Section "Data & privasi" dipertahankan. Iterasi 26 Nov: `SettingsCard` sebelumnya memakai
   `Row` padahal section mengirim beberapa baris vertikal, sehingga kartu putih membengkak dan semua
   section + CTA "Keluar" hilang. Kartu kini `Column`; baris tunggal (Akun, Kategori, Widget, Hapus
   data) dibungkus `Row` eksplisit. Bukti v2 di atas.
5. **Tambah transaksi**: keyboard sistem, bukan keypad in-app pada prototipe; hint bulan di atas CTA
   dipertahankan (copy fungsional). Nominal kini memakai prefix "Rp" dan pemisah ribuan.
6. **Detail transaksi**: baris manual menampilkan caption sumber, bukan kalimat notifikasi Livin;
   kartu provenance/edit/delete/audit di bawah fold dipertahankan karena fungsional.
7. **Kelola kategori**: baris menampilkan jenis kategori nyata, bukan jumlah transaksi yang tidak
   tersedia di kontrak.
8. **Kartu bank**: pager memakai halaman penuh selebar 353 dp dengan snapping dan tombol panah,
   tanpa "peek edge" kartu tetangga seperti render (garis lime bertumpuk di belakang kartu). Ini
   disengaja agar kartu tidak menyusut di 360 dp; label preview Jago/BRI tetap terlihat. Titik pager
   memakai target sentuh 48×24 dp (visual 6 dp, terpilih 24×6 dp sesuai referensi).
9. **State kritis**: `after_transaksi_393dp_v5.png` dan `after_kategori_393dp_v1.png` merekam state
   "Sesi Google berakhir" yang muncul saat verifikasi (token Gmail server kedaluwarsa). Daftar
   Transaksi versi data ada di `after_transaksi_393dp_v3.png`. Relink Gmail butuh interaksi OAuth
   pemilik, jadi tidak diuji ulang di sesi ini.

## Regresi fungsional yang dijalankan (HP fisik)

- Koreksi kategori pada baris "Pembayaran Berhasil!" (`Belum berkategori` → `Transportasi`) tersimpan
  dan tetap tampil setelah kembali ke daftar.
- Simpan transaksi manual Rp25.000 berhasil: Ringkasan naik dari Rp3.222.373/77 transaksi menjadi
  Rp3.247.373/78 transaksi.
- Back dari drill-down Transaksi kembali ke Ringkasan; tab Transaksi menampilkan search/filter/daftar.
- Sync server: sheet dibuka saat idle → "Sinkronkan sekarang" → ditutup → dibuka lagi saat berjalan
  menampilkan "Mengambil riwayat" + tombol "Sedang sinkron" (progres tetap terbaca), lalu selesai dan
  "Terakhir berhasil sinkron" berubah menjadi 3 menit lalu.
- 360 dp, 412 dp, dan font 200%: tidak ada clipping teks horizontal; nav tetap 4 tab.
- Nav bawah tidak lagi menimpa konten: `PamNavHost` menempatkan `BottomNavBar` sebagai sibling
  layout di dalam `Column`, jadi tinggi bar (termasuk inset sistem dan pertumbuhan font 200%) otomatis
  mengurangi viewport konten di keempat tab. Padding tetap 112 dp per layar dihapus; dump
  `after_pengaturan_bottom_393dp_v2` menunjukkan teks "Keluar" berakhir di y≈700 dp sedangkan divider
  nav di y≈759 dp, dan dump font 200% menunjukkan "Keluar" berakhir di y≈692 dp sedangkan nav mulai
  y≈753 dp.

## Batas bukti

- Android Studio **Run literal NOT RUN** (macOS memblokir kontrol GUI); verifikasi memakai ADB.
- Widget/notifikasi: regresi terpisah, bukti launcher/fixture ada di `docs/evidence/stitch_2026-11-25/`
  (test unit CR-010 tetap lulus); pengiriman dari email Livin nyata dan FCM live **NOT RUN**.
- Sesi Gmail server berakhir di akhir sesi; layar data yang butuh server (Transaksi/Kategori) hanya
  bisa direkam sebagai state error. Screenshot daftar diambil sebelum kedaluwarsa.
- Gate akhir: lihat laporan ringkas di `PROGRESS_DAILY` tanggal 26 November 2026.

## Iterasi 26 November 2026 — consent kategori, FAB gabungan, dan state koneksi

Perubahan yang diuji pada APK berjalan (emulator Android 17, override **393 dp** 1179×2627 @480 dpi;
360 dp 1080×2400; 412 dp 1236×2747; font 200%):

- **Kategorisasi AI**: consent `AI_CATEGORIZATION` sekarang punya switch nyata di Pengaturan. Sweep
  manual tidak lagi berhenti di 5 baris: seluruh kandidat 12 bulan dikirim dalam chunk 5 dan progress
  dilaporkan per baris. Baris masuk dari sync langsung diklasifikasi sebelum antrean 2 jam.
- **Satu FAB**: `after/after_transaksi_fab_actions_393dp.png` menunjukkan opsi “Kategorikan dengan AI”
  dan “Tambah transaksi” muncul dari FAB lime yang sama; nav bawah tetap utuh.
- **Feedback jujur**: `after/after_transaksi_classify_manual_393dp.png` menjelaskan baris manual
  “Belum berkategori” memang tidak dikirim ke AI, bukan menampilkan “semua selesai”.
- **Pengaturan**: `after/after_pengaturan_akses_aplikasi_393dp.png` menggantikan tiga section lama
  dengan satu **AKSES APLIKASI** berisi switch notifikasi, Kategorisasi AI (aktif), Analisis AI
  (aktif), dan tautan riwayat persetujuan. Diuji juga pada 360/412 dp dan font 200% tanpa clipping.
- **State gagal koneksi**: `after/after_ringkasan_retry_centered_393dp.png` (jaringan dimatikan)
  menunjukkan judul, body, dan tombol “Coba lagi” tepat di tengah layar; menyalakan jaringan lalu
  menekan tombol memulihkan Ringkasan. Feedback klasifikasi berada inline di antara filter dan daftar,
  sehingga tidak menutupi baris/nav.

Rincian mekanisme sinkronisasi otomatis vs tombol, batas token provider, gate, dan batas bukti ada di
[AI_CATEGORY_SYNC_UI_2026-11-26](../AI_CATEGORY_SYNC_UI_2026-11-26.md).

## Iterasi 26 November 2026 malam — kontras FAB, anti-klasifikasi-ulang, Analisis Stitch

APK berjalan (emulator Android 17, 393 dp override 1179×2627 @480 dpi; 360 dp 1080×2400; 412 dp
1236×2748; font 200%) membuktikan tiga koreksi terakhir:

- **Kontras FAB**: `after/after_transaksi_fab_actions_dark_393dp.png` dan varian 360/412/font 200%
  menunjukkan kedua opsi terbaca gelap-di-atas-lime; akar masalahnya `PamSurface` putih + `Ink`
  yang ternyata near-white, bukan tinta.
- **Anti-klasifikasi-ulang**: ledger `ClassificationAttemptStore` menyimpan `id -> revision` yang
  sudah `NEEDS_REVIEW` sehingga sweep kedua tidak mengirim ulang baris yang sama; revision bump
  membukanya kembali. Bukti di unit test (12 tes) + `after/after_transaksi_classify_manual_dark_393dp.png`
  untuk feedback jujur; data uji tidak bisa memproduksi baris server `NEEDS_REVIEW`.
- **Analisis**: `after/after_analisis_stitch_393dp.png` (plus 360/412/font 200%) hanya memuat tiga
  kartu Stitch — insight lime, kategori 4 baris, temuan ringkas — tanpa toggle/pager/saran; batasan
  menjadi catatan setelah disclosure. Body insight memakai aritmetika bulan berjalan.

Gate: `:app:testDebugUnitTest` **646 tes/0 gagal**, `assembleDebug` lulus, `lintDebug`
**0 error/264 warning/1 hint**. Android Studio Run literal tetap **NOT RUN**; merge/deploy menunggu
izin pemilik. Rincian ada di
[AI_CATEGORY_SYNC_UI_2026-11-26](../AI_CATEGORY_SYNC_UI_2026-11-26.md#7-koreksi-malam-26-november-2026-tiga-temuan-pemilik).
