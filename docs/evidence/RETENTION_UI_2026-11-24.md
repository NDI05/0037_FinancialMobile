# Bukti UI retensi setahun + label status ringkas — 24 November 2026

Lane: `feat/retention-ui` (commit `5eb25e3`, `a3e3e6e`), digabung ke `feat/c2-android`.
Backend pendukung: `feat/retention` → `integration/2026-11-24` (`GET /v1/retention`,
guard `400 outside_retention`). Kebijakan backend: lihat
[RETENTION_POLICY_IMPLEMENTATION.md](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/RETENTION_POLICY_IMPLEMENTATION.md).

## Yang berubah

- `core/time/RetentionWindow.kt`: jendela setahun WIB dari `GET /v1/retention`, bulan batas
  parsial, perbandingan bulan (`JakartaMonth`), clamp 29 Feb → 28 Feb.
- Navigasi bulan Ringkasan/Transaksi/Analisis: panah maju mati di bulan berjalan, panah
  mundur mentok di bulan tertua jendela; label "sebagian" hanya pada bulan batas; maksimal
  13 label (12 bulan + batas parsial).
- Sheet Status pembaruan: satu ikon, satu sheet, label ringkas "Mengambil riwayat",
  "Menunggu AI", "Perlu tinjauan", "Coba lagi"; rincian periode "Riwayat yang bisa diakses:
  November 2025 sampai November 2026" dan kalimat khusus bulan batas.
- Menutup sheet tidak menghentikan job server; status tetap benar setelah buka/tutup.

## Bukti

- Unit/Compose test: `MonthSelectorTest`, `RetentionWindowTest`, `UpdateStatusLabelsTest`
  (bagian dari 77 kelas/561+ tes lane Android; jumlah final ada di laporan gate).
- Runtime emulator (pasangan build terintegrasi):
  `docs/evidence/ui_integrated/04_retensi_batas.png` (November 2025 "sebagian"),
  `05_status_sheet.png` (rentang retensi + label ringkas).
- Aksesibilitas pada layar ini: ikon 48 dp, satu node TalkBack per kontrol status, teks
  ringkas; font 200% dan lebar 360/393/412 dp dibuktikan di
  [UAT_ACCESSIBILITY_2026-11-24](UAT_ACCESSIBILITY_2026-11-24.md).

## Batas

- Penghapusan data kedaluwarsa nyata (sweep `DELETE` pada data UAT/produksi) **NOT RUN**;
  backend hanya diuji pada DB `/tmp` dan fixture.
- Belum ada walk emulator ulang khusus font 200% di HEAD final pasangan ini; bukti berasal
  dari run aksesibilitas 24 November sebelumnya.
