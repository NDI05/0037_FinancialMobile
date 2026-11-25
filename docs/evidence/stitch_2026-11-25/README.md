# Bukti redesign Stitch PAM — 25 November 2026 (WIB)

Build final: APK debug `com.pam.recap.debug`, sha256 `d62ab519…` (21:25 WIB, pasca-fix audit + bukti widget/notifikasi). Screenshot 01–04, 09–11 diambil ulang dari build ini di HP fisik 393 dp; 360/412 dp dan font 200% juga dari build final. Bukti 12–18 dari emulator (debug-only helpers).
Perangkat: HP fisik Xiaomi (serial diredaksi), Android 13; lebar 393 dp (1080 px @ 440 dpi).
Screenshot diambil dengan `adb exec-out screencap` setelah memasang APK hasil
`scripts/build.sh :app:assembleDebug :app:lintDebug`.

| Layar / bukti | Status | Bukti |
|---|---|---|
| Ringkasan (judul terpusat, kartu Pengeluaran/Pemasukan, carousel bank, kategori, transaksi terbaru) | **PASS (runtime)** | `01_ringkasan_393dp.png` |
| Masuk/Daftar pra-login (emulator bersih, 448 dp) | **PASS (runtime)** | `12_masuk_daftar_448dp_emulator.png` |
| Transaksi satu layar dari bottom nav + filter periode/arah; kartu Ringkasan membuka Transaksi terfilter | **PASS (runtime)** | `02_transaksi_393dp.png`; drill-down "Lihat semua" → Transaksi → back ke Ringkasan terverifikasi lewat UI dump |
| Sheet "Status pembaruan" tunggal: state, progres bulan, percobaan/keberhasilan, jadwal, tanpa persentase palsu | **PASS (runtime)** | `03_sync_sheet_393dp.png` |
| Pengaturan: satu baris Notifikasi transaksi baru + banner izin yang jujur; tidak ada kartu data-source/Gmail duplikat | **PASS (runtime)** | `04_pengaturan_393dp.png` |
| Analisis | **PASS (runtime)** | `05_analisis_393dp.png` |
| Kelola Kategori | **PASS (runtime)** | `06_kelola_kategori_393dp.png` |
| Detail transaksi | **PASS (runtime)** | `07_detail_transaksi_393dp.png` |
| Tambah transaksi (FAB ikon saja) | **PASS (runtime)** | `08_tambah_transaksi_393dp.png` |
| Lebar 360 dp / 412 dp | **PASS (runtime, visual)** | `09_ringkasan_360dp.png`, `10_ringkasan_412dp.png` |
| Font 200% pada 360 dp | **PARTIAL (runtime dump)** | `11_ringkasan_font200_360dp.png`, `font200_360dp_dump.txt` — semua label utama & bottom nav ada; review visual piksel belum dilakukan model |
| Widget Glance 2×2 / 4×2 | **PASS (runtime, state signed-out)** | Instance nyata di launcher lewat `requestPinAppWidget` lalu dialog “Add to home screen”: `15_widget_2x2_launcher.png` (Mandiri + CTA). Render 4×2 nyata lewat host debug: `13_widget_4x2_debug_host.png` (Mandiri + November 2026 + CTA); 2×2 pembanding `14_widget_2x2_debug_host.png`. Provider terdaftar di `dumpsys appwidget` |
| Notifikasi transaksi baru (single/group, dedupe, privasi lockscreen) | **PASS (fixture runtime) + PARTIAL (live)** | Builder produksi dipanggil dengan kandidat sintetis: `16_notifikasi_single_fixture.png` (“Transaksi baru tercatat”, tanpa nominal/merchant) dan `17_notifikasi_group_fixture.png` (“3 transaksi baru tercatat”). Copy lockscreen generik; 9 unit test planner/privasi lulus. Pengiriman dari email Livin nyata **NOT RUN** (butuh baris baru + notifikasi app aktif; perangkat uji level aplikasi `importance=NONE`) |
| FCM/live background delivery | **NOT RUN** | Belum diimplementasikan; pipeline lokal hanya saat proses app hidup |
| Literal Android Studio Run (tombol Run hijau) | **NOT RUN** | Instalasi/launch dilakukan via `adb`; langkah Run manual ada di laporan akhir |

Batas bukti: angka Rp dan 77 transaksi berasal dari data akun uji hosted nyata, bukan fixture
sintetis; Jago/BRI pada carousel adalah pratinjau desain berlabel "Contoh tampilan, bukan data
akun" karena kontrak backend belum punya dimensi bank.

## Audit independen dan tindak lanjut (25 Nov, 20:10 WIB)

Audit read-only diff `ccbba22..5dad109`: verdict **PASS WITH FOLLOW-UPS**. Dua P1 diperbaiki:
1. **Deep link bisa terkirim ganda/tertahan** — alur `PamPendingNavigation` diganti dari
   `SharedFlow` + `consume()` menjadi polling slot tunggal yang selalu `consume()`; target
   dibersihkan saat gate tidak Ready agar tidak replay setelah logout/ganti akun. Menghilangkan
   race antara consume dan collector serta replay saat activity recreation.
2. **Hitungan bank/arah terpotong 100 baris** — `OverviewViewModel` kini memuat semua halaman
   `listMonth` (200 baris/halaman, maks 10 halaman) sebelum menghitung jumlah Mandiri dan
   arah, jadi kartu bank tidak lagi menampilkan "100 transaksi" sebagai total.
Ditambah perbaikan P2: transaksi baru dengan timestamp sama dengan watermark kini tetap
dinotifikasi, dengan penjaga `seenIds`. Gate ulang: **640 tes/0 gagal**, assembleDebug PASS,
lintDebug **0 error/153 warning**. Host widget debug-only (`src/debug`) ditambahkan untuk
membuktikan render Glance tanpa launcher; tidak ikut build release.

### Bukti launcher/pin dan notifikasi fixture (25 Nov 21:20 WIB)

- `15_widget_2x2_launcher.png`: widget 2×2 benar-benar terpasang di home screen emulator setelah
  `PinWidgetActivity` (debug-only) memanggil `AppWidgetManager.requestPinAppWidget` dan dialog
  sistem “Add to home screen” ditekan. State widget logged-out, sesuai perilaku.
- `16`/`17`: notifikasi dibangun oleh `TransactionNotifications.post` (builder produksi) dengan
  kandidat sintetis lewat `NotificationDemoActivity` (debug-only); tidak ada nominal, merchant,
  atau nomor akun pada judul/isi. Ini bukti fixture, bukan pengiriman email Livin nyata.
- Activity debug (`WidgetPreviewHostActivity`, `PinWidgetActivity`, `NotificationDemoActivity`)
  hanya ada di source set `debug`, tidak masuk APK release/assessment.

### Rekaman ulang build final (25 Nov 21:25 WIB)

Screenshot `01_ringkasan_393dp.png`, `02_transaksi_393dp.png`, `03_sync_sheet_393dp.png`,
`04_pengaturan_393dp.png`, `09_ringkasan_360dp.png`, `10_ringkasan_412dp.png`, dan
`11_ringkasan_font200_360dp.png` diambil ulang setelah semua perbaikan audit pada APK final
`d62ab519…`. Verifikasi teks runtime: Ringkasan menampilkan **77 transaksi** Mandiri (sama dengan
sebelum pagination, data uji <100 baris), Transaksi satu layar, sheet menampilkan "Bulan selesai:
13 dari 13 / Menunggu" tanpa persentase palsu, Pengaturan menampilkan toggle notifikasi dan
footer privasi. Navigasi back dari drill-down Transaksi kembali ke Ringkasan terverifikasi.
