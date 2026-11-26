# Verifikasi langsung Android Studio Run + emulator — 27 November 2026 WIB

Permintaan pemilik: menguji sendiri langsung dari Android Studio dengan emulator sebelum memberi
persetujuan merge. Dokumen ini mencatat bukti bahwa tombol **Run** Android Studio benar-benar dipakai
( bukan `adb install` manual), lalu hasil uji runtime pada emulator yang sama.

## Lingkungan

- Android Studio **2026.1**, build `AI-261.23567.138.2611.15646644` (`/Applications/Android Studio.app`).
- Proyek `PAM` sudah terbuka; konfigurasi run **`app`** (modul `PAM.app`).
- Emulator **Pixel_10_Pro_XL** (`emulator-5554`), Android **17 / API 37**, `sdk_gphone64_arm64`,
  density 480. Ukuran layar di-override ke **1179×2627** (393 dp) untuk pengukuran utama; uji lebar
  memakai override 1080×2400 (360 dp) dan 1236×2748 (412 dp); font sistem 1.0 dan 2.0.
- APK debug `com.pam.recap.debug` versi `1.0.0-debug`.

## Bukti Android Studio Run literal

1. Android Studio diaktifkan, jendela proyek `PAM – TransactionRow.kt [PAM.app.main]` difokuskan.
2. Menu **Run → Run 'app'** diklik lewat GUI (bukan perintah Gradle/ADB).
3. `idea.log` Android Studio mencatat:

```
2026-11-27 00:42:17,611 - AndroidRunConfigurationExecutor - Launching on device Pixel_10_Pro_XL [emulator-5554]
2026-11-27 00:42:17,612 - DeployTask - Installing application: com.pam.recap.debug
2026-11-27 00:42:23,635 - ActivityLaunchOptionState - Activating Activity 'com.pam.recap.MainActivity' on device emulator-5554
2026-11-27 00:42:23,925 - AndroidProcessHandler - Adding device Pixel_10_Pro_XL [emulator-5554] to monitor for launched app: com.pam.recap.debug
```

Salinan lengkap: `stitch_redesign_2026-11-25/after/studio_run/idea_log_run.txt`.

4. Setelah Run, fokus jendela Android adalah
   `com.pam.recap.debug/com.pam.recap.MainActivity` dan proses aplikasi hidup (`pidof` = 3759).
   **Android Studio Run literal: PASS** (sebelumnya NOT RUN).

## Hasil uji runtime

Semua screenshot di bawah diambil dari proses yang diluncurkan Android Studio pada 27 Nov 2026
(`after/studio_run/`), bukan render Compose/preview.

### FAB dua opsi

`studio_fab_actions_393dp.png` — setelah FAB ditekan, dua opsi muncul:

- “Kategorikan dengan AI” `[674,1768][1071,1817]`
- “Tambah transaksi” `[759,1948][1071,1997]`

Sampel piksel pada area pill menghasilkan `#171915` (OnInk) dengan teks/ikon `#FAFAF7` (Ink) —
kontras terbaca. FAB utama tetap lime `#D9F449`. Tidak ada label yang keluar layar.

### Analisis

`studio_analisis_393dp.png` + dump `studio_analisis_393dp.xml` menunjukkan urutan yang sama dengan
Stitch: **Insight utama** (`Makanan (40%)`, body `Rp100.000 dari 1 transaksi pada bulan ini.`),
**Pengeluaran per kategori** (4 baris: Makanan 40%, Utilitas 30%, Transportasi 20%, Belanja 10%),
lalu **Temuan ringkas**. Tidak ada node toggle “Observasi/Saran”, pager, atau daftar saran.

- 360 dp (`studio_analisis_360dp.png/.xml`): label kategori berakhir x=631, nominal mulai x=655;
  seluruh baris berakhir ≤1080, tidak terpotong.
- 412 dp (`studio_analisis_412dp.png/.xml`): label berakhir x=787, nominal mulai x=811; berakhir
  ≤1236.
- Font 200% 393 dp (`studio_analisis_font200_393dp.png/.xml`): body membungkus dua baris; label
  kategori berakhir x=394/441, nominal mulai x=418/465 — tidak bertumpuk; nav bawah tetap muat.
- Setelah uji lebar/font, ukuran dipulihkan ke 1179×2627 dan font 1.0.

### Kategorisasi AI berulang

Akun uji hanya memiliki baris manual “Belum berkategori”, jadi tap “Kategorikan dengan AI”
menampilkan pesan jujur:
“Transaksi manual yang dibiarkan Belum berkategori tidak dikirim ke AI. Pilih kategori lewat detail
transaksi.” (`studio_classify_manual_393dp.png`). Tap kedua menghasilkan pesan yang sama tanpa
progress/request baru dan tanpa crash; banner berada inline (y=744–942) sehingga tidak menutupi
daftar. Skenario baris server `NEEDS_REVIEW` tetap tidak dapat diproduksi lewat akun uji dan
dibuktikan oleh unit test ledger + idempotensi backend (lihat
[AI_CATEGORY_SYNC_UI_2026-11-26](AI_CATEGORY_SYNC_UI_2026-11-26.md)).

### Navigasi dan stabilitas

Smoke test pada build yang sama: Ringkasan → Transaksi → Analisis → Pengaturan (scroll sampai
“Keluar”, terlihat di y≈2122–2182, di atas nav y≈2479) → Detail transaksi → FAB → Tambah transaksi →
back. Tidak ada crash/ANR aplikasi: buffer `logcat -b crash` kosong untuk `com.pam.recap.debug`, dan
`pidof` tetap 3759 sepanjang pengujian.

## Gate build

`./scripts/build.sh :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`:
**646 tes / 0 gagal**, `assembleDebug` lulus, lint **0 error / 264 warning / 1 hint**.

## Batas

- Verifikasi perilaku ledger untuk baris server `NEEDS_REVIEW` tetap lewat unit test; akun emulator
  sintetis tidak memilikinya.
- Data pemilik/produksi tidak diubah. Backend tidak diubah.
- Belum merge/deploy; menunggu persetujuan pemilik setelah laporan ini.
