# UAT pasangan build terintegrasi — 24 November 2026

Pasangan yang diuji: Android `feat/c2-android` (HEAD `dc927c8` + perubahan kecil pemensiunan
entri IMAP lama, commit menyusul) × backend `integration/2026-11-24` HEAD `f1625b5`
(gabungan Gmail API + retensi). `main` kedua repo belum di-merge.

Provenans: lane QA menyiapkan emulator `emulator-5556`, server uji terisolasi (port 18097,
DB `/tmp`, `adb reverse tcp:18090 tcp:18097`) dan artefak tangkapan layar/dump di
`docs/evidence/ui_integrated/`. Lane tersebut berhenti sebelum menulis laporan; rangkuman ini
dirakit dari artefak yang tersisa dan ditutup oleh PM (lampu hijau Gmail diambil PM pada
sesi emulator yang sama). Status di bawah adalah status **runtime pada pasangan build ini**,
bukan pernyataan tentang gate live Google.

## Hasil per butir

| # | Butir | Bukti | Status |
|---|---|---|---|
| 1 | Login akun sintetis + sesi server (bukan data owner) | dump Ringkasan menampilkan nominal uji `Rp33.333,33`, 2 transaksi uji | PASS |
| 2 | Ringkasan bulan berjalan | `ui_integrated/02_ringkasan.png` + dump: "Dari email", badge "Sebagian", kartu masuk/keluar | PASS |
| 3 | Transaksi + paging harian | `03_transaksi.png` + dump: grup tanggal, 2 baris, filter kategori | PASS |
| 4 | Jendela retensi: bulan batas "sebagian" | `04_retensi_batas.png`: "November 2025 · sebagian", 0 transaksi, empty state jujur | PASS |
| 5 | Sheet status ringkas + rentang retensi | `05_status_sheet.png`: "Status pembaruan", "Riwayat yang bisa diakses: November 2025 sampai November 2026", label "Menunggu AI/Perlu tinjauan" tidak muncul karena tidak ada job tertunda — bagian daftar job kosong | PASS (bagian job hanya terbukti lewat unit test) |
| 6 | Analisis dengan consent belum diberikan | `06_analisis.png`: penjelasan agregat + CTA "Buka persetujuan"; tidak ada panggilan provider | PASS |
| 7 | Layar Tautkan Gmail saat `GMAIL_ENABLED=false` | `07_gmail_disabled.png`: "Server PAM belum mengaktifkan tautan Gmail…", tanpa CTA connect menyesatkan, ledger izin/retensi tampil | PASS |
| 8 | Entri IMAP lama tidak lagi tampil di Pengaturan | Sesudah perubahan: hanya "Tautkan Gmail lewat browser"; label cakupan menjadi "Email Livin hanya-baca (tanpa akses tulis)" | PASS |
| 9 | TalkBack/48 dp/font 200%/360–412 dp pada HEAD ini | Bukti aksesibilitas sebelumnya: [UAT_ACCESSIBILITY_2026-11-24](UAT_ACCESSIBILITY_2026-11-24.md); tidak diulang pada pasangan build ini | PARTIAL |
| 10 | OAuth Google nyata, Pub/Sub nyata, backfill akun pilot | Tidak ada kredensial Google di lingkungan ini | NOT RUN |
| 11 | Hapus data retensi nyata + restore | Hanya DB sintetis; tidak menyentuh DB owner | NOT RUN |

## Catatan

- Semua aksi merusak (hapus transaksi/lampiran) dilakukan pada DB `/tmp` terisolasi; DB/port
  owner `18090` tidak disentuh.
- Lambat email → Go → Android belum dapat diukur karena push Pub/Sub nyata belum aktif
  (gate NOT RUN di laporan akhir).
- Artefak `*.xml` adalah dump uiautomator mentah; `*.png` adalah tangkapan emulator.
