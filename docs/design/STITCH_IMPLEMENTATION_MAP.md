# Peta implementasi redesign Stitch → Compose

Status: 26 November 2026 WIB, branch `feature/stitch-redesign`. Dokumen ini memetakan 9 layar
prototipe ke route, komponen, dan sumber data aktual sebelum perubahan UI. Sumber visual:
`docs/design/stitch_pam_finance_app_prototype/`; perilaku: SRS v0.7 + CR-009/CR-010.

| Mockup Stitch | Route Compose | Komponen utama | Sumber data / logika |
| --- | --- | --- | --- |
| 1. Masuk/Daftar | `Welcome`, `Account` (`SIGN_IN`/`REGISTER`) | `WelcomeScreen`, `AccountScreen` | `SessionGateViewModel`, `AccountViewModel`, SSO Google (CR-009) |
| 2. Tautkan Gmail | `GmailConnect`, `GmailLink`, `Consent` | `GmailConnectScreen`, `GmailLinkScreen`, `ConsentScreen` | `GmailConnectViewModel`, backend OAuth server, keputusan Gmail lokal |
| 3. Ringkasan | `Overview` | top bar terpusat + ikon status; kartu Pengeluaran/Pemasukan; **kartu bank carousel (opsi B)**; daftar transaksi terbaru | `OverviewViewModel`, `GET /v1/summary` bulanan; agregat bank diturunkan dari transaksi `EMAIL` per bulan (Mandiri), Jago/BRI preview sintetis |
| 4. Transaksi | `Transactions` (satu route; tab + drill-down dari Ringkasan) | pencarian, chip periode/arah/sumber, daftar + pagination | `TransactionsViewModel` + filter route (`monthId`, `direction`, `categoryCode`, `dayIso`) |
| 5. Detail Transaksi | `TransactionDetail` | detail read-only (impor) / edit-hapus (manual), koreksi kategori USER | `TransactionDetailViewModel` |
| 6. Tambah Transaksi | `ManualEntry` | toggle Pengeluaran/Pemasukan, nominal, tanggal (masa depan ditolak), kategori, catatan, IME aman | `ManualEntryViewModel` |
| 7. Analisis | `Analysis` | state consent, proses, hasil, kedaluwarsa, gagal; grafik tanpa warna saja | `AnalysisViewModel`, `GET/POST /v1/analysis` |
| 8. Pengaturan | `Settings`, `AccountSettings`, `ConsentHistory`, `DataDeletion` | grup Akun/Gmail/AI/Kategori, **toggle notifikasi transaksi baru (default off)**, Keluar | `SettingsScreen`, `AccountViewModel`; outlet CR-010 |
| 9. Kelola Kategori | `CategoryManagement` | daftar, tambah/ubah/arsip kategori | `CategoryManagementViewModel` |

## Status implementasi (26 November 2026)

Iterasi visual kedua menyamakan struktur/blok dan proporsi layar utama dengan render Stitch 393 dp:
Ringkasan (kartu bank opsi B, hero pengeluaran, kartu kategori 6 track, daftar terbaru grouped card),
Transaksi (search 48 dp, chip 36 dp, grup tanggal, FAB lime 56 dp di atas nav), Detail, Tambah,
Analisis, Pengaturan, Kelola Kategori, Gmail, dan sheet status ringkas. Screenshot sebelum/referensi/
sesudah, tabel ukuran dp, deviasi platform (status bar 37,5 dp + nav ±49 dp), dan regresi fungsional
ada di [evidence/stitch_redesign_2026-11-25](../evidence/stitch_redesign_2026-11-25/README.md).

## Status implementasi (25 November 2026)

Kesembilan layar sudah dipetakan dan diimplementasikan pada branch `feature/stitch-redesign`.
Bukti runtime per layar, batas bukti, dan screenshot ada di
[evidence/stitch_2026-11-25](../evidence/stitch_2026-11-25/README.md). Gate saat ini: 640 unit test
lulus, `assembleDebug` dan `lintDebug` (0 error) lulus. Widget/notifikasi CR-010 sudah terpasang
dan dirender nyata (widget 2×2 di launcher, 4×2 di host debug; copy notifikasi generik). Pengiriman notifikasi dari email Livin nyata dan Run Android Studio literal masih NOT RUN.

## Komponen bersama

- Tema `PamWarm*` diganti token Stitch: canvas `#FAFAF7`, surface `#FFFFFF`, ink `#171915`,
  lime `#D9F449`, success `#006D30`, warning `#9B4500`, error `#B91C1C`; radius 12/16/20/24.
- Tipografi **Manrope** offline (sudah ada `res/font/manrope.ttf`), angka `tnum` untuk nominal.
- Judul top bar selalu terpusat; ikon status sinkronisasi di kanan dengan label sementara.
- Satu bottom sheet status (mulai/retry, progres, hasil) yang tidak membatalkan proses saat ditutup.
- Kartu bank: satu kartu per bank, jumlah transaksi periode terpilih; Mandiri = data nyata
  (transaksi `EMAIL`), Jago/BRI = preview sintetis berlabel sampai backend menyediakan dimensi bank.
- Widget Glance 2×2/4×2 satu bank per widget dan notifikasi transaksi baru: lihat CR-010.

## Gap yang perlu keputusan pemilik

1. **Dimensi bank** belum ada di DTO transaksi/backend. Mandiri dihitung dari transaksi `EMAIL`
   (Livin/Mandiri); Jago/BRI hanya preview sintetis berlabel, tidak memfilter data nyata.
2. **FCM live** belum tersedia. Notifikasi diuji melalui event delta/WorkManager lokal + backend
   fixture; pengiriman FCM produksi menunggu konfigurasi dan persetujuan pemilik.
3. Mockup menampilkan beberapa state yang tidak ada di kontrak saat ini (mis. saldo bank); tidak
   diimplementasikan karena di luar SRS v0.7.
