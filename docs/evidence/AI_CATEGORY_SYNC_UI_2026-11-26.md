# Kategorisasi AI, sinkronisasi, dan perbaikan UI — 26 November 2026

Branch `feature/stitch-redesign` · APK debug `com.pam.recap.debug` · backend
`https://pam-70-153-86-163.sslip.io` (`/healthz` dan `/readyz` 200).

## 1. Keluhan pemilik dan perbaikan

| # | Keluhan | Akar masalah | Perbaikan |
|---|---|---|---|
| 1 | Analisis & kategori AI “tidak bisa” padahal izin Analisis sudah aktif | Backend memakai consent **`AI_CATEGORIZATION`** yang terpisah dari `AI_PROCESSING`; Pengaturan hanya menampilkan izin analisis, sehingga consent kategori tidak pernah bisa diberikan. | Pengaturan kini punya switch **Kategorisasi AI** dan **Analisis AI** di section **AKSES APLIKASI**; `SettingsViewModel.setConsent()` menulis GRANTED/WITHDRAWN ke ledger. `AutoCategoryUseCase.classifyAll()` membaca consent sebelum satu pun request provider. Analisis tetap memakai `AI_PROCESSING`. |
| 2 | Hanya 5 baris yang dikategorikan | Klien memotong kandidat dengan `.take(5)` mengikuti batas satu request backend. | Batas 5 hanya dipakai **per request**; klien menyapu seluruh baris eligible (12 bulan, pagination) lalu mengirim berulang dalam chunk 5. Baru masuk langsung diproses setelah sync sukses. |
| 3 | Impor baru menunggu antrean 2 jam | Setelah commit sync hanya cursor yang maju; tidak ada pemicu klasifikasi. | `AutoSyncUseCases` memanggil `classifyImportJob()` untuk snapshot run yang baru diakui, lalu `enqueueOnly()` untuk sisa eligible; worker kategori 2 jam tetap menjadi jaring pengaman. |
| 4 | Tombol “Kategorikan AI” terpisah | Dua FAB terpisah. | Satu FAB lime membuka dua opsi: **Kategorikan dengan AI** dan **Tambah transaksi** (`AnimatedVisibility`, target 48 dp, tanpa menutupi nav). |
| 5 | Section Pengaturan berulang dan consent bukan switch | Section Gmail, Kategorisasi AI, dan Analisis AI terpisah; consent berupa baris status. | Section **AKSES APLIKASI** berisi switch **Notifikasi transaksi baru**, **Kategorisasi AI**, **Analisis AI**, plus tautan riwayat persetujuan. Section Koneksi Gmail dihapus (sudah ada di AKUN GOOGLE). |
| 6 | “Coba lagi” tidak center dan banner menutupi UI | CTA mengikuti lebar banner kiri. | State gagal koneksi Ringkasan/Transaksi memakai judul + body `textAlign = Center` dan `PillButton` yang benar-benar center; feedback klasifikasi menjadi blok inline di antara filter dan daftar, bukan overlay. |

## 2. Mekanisme sinkronisasi

### Otomatis

1. **Jadwal perangkat** — `WorkManagerAutoSyncScheduler` memasang periodic work 30 menit
   (`AutoSyncConfig.interval`; lantai platform 15 menit) dengan constraint jaringan dan backoff
   eksponensial 15 menit.
2. **Saat aplikasi dibuka** — `MainActivity` menjalankan satu delta jika aktivitas terakhir lebih tua
   dari `foregroundCooldown` 5 menit. Semua jalur memakai gate single-flight yang sama, jadi worker,
   foreground, dan tombol tidak dapat menulis cursor bersamaan.
3. **Delta, bukan impor ulang** — client membaca email Livin sejak watermark UID; saat watermark
   tidak dipercaya (UIDVALIDITY berubah / pertama setelah upgrade) dipakai overlap 6 jam. Email
   tanggal mundur dirutekan ke bulan yang benar.
4. **Commit lokal → upload server** — baris disimpan ke Room lebih dulu, lalu `ServerImportUploader`
   mengirim per bulan dengan idempotency key; cursor hanya maju setelah server mengakui seluruh
   bulan. Crash di tengah run dipulihkan dari journal, bukan diulang dari nol.
5. **Kategori setelah commit** — bila `importedCount > 0`, snapshot run tersebut langsung
   diklasifikasi (`classifyImportJob`, chunk 5 id) lalu sisa kandidat di-enqueue. Worker kategori
   terpisah berjalan tiap 2 jam dan memanggil drain server.

### Tombol manual

- **Sinkronkan sekarang** di sheet status memakai gate dan status holder yang sama dengan jalur
  otomatis. Pada mode Gmail server ia meng-enqueue job server (`POST /v1/gmail/sync`, 202/coalesced,
  429 `sync_cooldown`), lalu sheet memantau status/progres; pada mode perangkat ia menjalankan
  `AutoSyncUseCase.run()` langsung. Menutup sheet tidak membatalkan job server.
- **Kategorikan dengan AI** (opsi FAB) memindai seluruh baris eligible 12 bulan terakhir dan
  mengirim chunk 5 id sampai habis. Progress ditampilkan per baris; kegagalan jaringan/budget
  menghentikan sweep dengan hasil parsial yang jujur, bukan klaim selesai.
- Tidak ada penghitungan kategori yang dikarang: status hanya memakai jumlah dari respons backend.

## 3. Optimasi token

- Hanya baris `uncategorized`, bukan `USER`/transfer internal, yang dikirim.
- Backend membatasi satu request 5 kandidat, dedupe berdasarkan merchant, dan cache label per
  merchant; baris dengan merchant sama tidak memanggil provider lagi.
- Batas server: drain 5 per batch, maksimal 4 panggilan provider dan 4 batch per jendela 2 jam;
  enqueue maksimal 50 id/request; plafon biaya harian USD 0,10, bulanan USD 2,00, dan 20 percobaan
  provider per pemilik/hari. Client tidak menembus batas ini.
- Sweep manual memakai respons sukses yang sama; jika budget habis, sisa baris tetap di backlog dan
  pesan UI menyebut hasil parsial. Tidak ada retry agresif.

## 4. Bukti runtime (emulator Android 17, 393 dp override 1179×2627 @480 dpi)

| Bukti | Isi |
|---|---|
| `after/after_transaksi_fab_actions_393dp.png` | Satu FAB membuka dua opsi: “Kategorikan dengan AI” + “Tambah transaksi”; nav tidak tertutup. |
| `after/after_transaksi_classify_manual_393dp.png` | Feedback sweep: baris manual “Belum berkategori” dijelaskan tidak dikirim ke AI; tidak ada klaim palsu. |
| `after/after_pengaturan_akses_aplikasi_393dp.png` | AKSES APLIKASI berisi switch notifikasi + Kategorisasi AI (aktif) + Analisis AI (aktif) + riwayat; tidak ada section Gmail/AI terpisah. |
| `after/after_pengaturan_akses_360dp.png`, `_412dp.png`, `_font200_top.png` | Tidak ada clipping; switch tetap terbaca pada 360/412 dp dan font 200%. |
| `after/after_ringkasan_retry_centered_393dp.png` | Saat jaringan dimatikan: judul/body center, tombol “Coba lagi” center (bbox x494–685 pada layar 1179 px → tepat di tengah); pemulihan setelah jaringan hidup menghasilkan Ringkasan normal. |
| `after/after_analisis_393dp_v3.png` | Analisis berjalan dengan consent aktif (insight + temuan + batasan), bukan state kosong. |
| Dump switch consent | Kategorisasi AI diubah OFF → aplikasi di-force-stop dan dibuka ulang → switch tetap **OFF** (persisten di ledger), lalu diubah ON kembali → **ON**. Analisis AI tetap ON dan tidak terpengaruh, membuktikan kedua purpose terpisah. |
| `after/after_transaksi_fab_actions_393dp.txt`, `.txt` pendamping | Dump `uiautomator` sebagai bukti posisi terukur. |

## 5. Gate

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — **643 tes, 0 gagal**,
  lint **0 error / 253 warning** (warning lama + aksesibilitas string baru).
- Unit test baru: sweep 12 baris → batch `[5,5,2]`; consent kategori tidak ada → gagal
  `ai_category_consent_required` tanpa request provider; enqueue 110 kandidat → batch `[50,50,10]`;
  tes concurrency drain dibuat deterministik (bukan mengandalkan timing scheduler).
- Bukti runtime di atas dari APK hasil `assembleDebug` yang dipasang ulang via `adb install -r`.

## 6. Batas bukti

- **Android Studio Run literal tetap NOT RUN** (otomasi GUI macOS diblokir); verifikasi memakai ADB.
- Data emulator adalah akun uji sintetis dengan satu baris manual; sweep lintas ratusan baris nyata
  dan satu email Livin baru saat aplikasi tertutup **belum diuji** pada sesi ini.
- Screenshot 393 dp memakai override ukuran emulator; baris status/nav Android tetap ada, sehingga
  tinggi pandang berbeda beberapa dp dari perangkat fisik 393 dp.
- Backend tidak diubah/di-deploy pada sesi ini; batas budget dan cache adalah perilaku server yang
  sudah terpasang. Merge/deploy aplikasi menunggu izin pemilik.
