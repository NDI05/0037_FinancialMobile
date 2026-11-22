# PAM Warm R1 — handoff implementasi Compose

Status: **kandidat lokal**, 22 November 2026. Owner visual UAT belum dilakukan.
Sumber kontrak: `output/design-review-r1/PAM_DESIGN_CONTRACT_R1.md` dan
`pam_warm_tokens_r1.json`.

## Token dan font

- `Color.kt`: canvas `#FCF9F8`, surface `#FFFFFF`, primary `#004BC4`, container
  `#2764E7`, lime `#D3F048`, violet `#5936CA`, ink `#1C1B1B`, supporting `#434654`,
  income `#167650`, expense `#B54735`, danger `#BA1A1A`.
- Nama lama (`Ink`, `OnInk`, `Violet`, `GlassFill`) dipertahankan sebagai alias
  kompatibilitas agar layar non-R1 tetap terbaca.
- `Type.kt`: Manrope 400–800, display 36/44, money hero 32/40, body 16/24,
  label 14/20, caption 12/16, tabular figures.
- `Shape.kt`: card 20 dp, control 16 dp, sheet 28 dp.
- Font: `app/src/main/res/font/manrope.ttf` (Manrope variable, Google Fonts OFL),
  lisensi `app/src/main/assets/licenses/manrope_OFL.txt`. Offline.

## Screen → Compose

| Source | Implementasi |
|---|---|
| B01 Ringkasan | `OverviewScreen.kt` + `OverviewViewModel`; hero arus kas, month selector, metric cards, composition, category rows, recent |
| B02 Transaksi | `TransactionsScreen.kt` + ViewModel; month strip, search/filter, date-grouped rows, source/category badges |
| B03 Detail | `TransactionDetailScreen.kt` + ViewModel; hero, provenance/confidence, category/refund/note, capability notice |
| B04 Manual | `ManualEntryScreen.kt` + ViewModel; direction toggle, amount/description/date, category picker, sticky Simpan transaksi |

Shared: `PamScaffold`, `GlassCard`, `PillButton`, `StatusChip`, `MoneyText`,
`TransactionRow`, `PillToggleGroup`.

## Deviasi/koreksi PM yang diterapkan

- Manrope menggantikan fallback serif B01/B02.
- Hero net memakai teks putih/lime, bukan hitam di atas gradient.
- CTA primary solid `#004BC4`; tidak memakai gradient lime.
- B04 judul **Tambah transaksi**, satu CTA sticky, copy
  “Setelah tersimpan, transaksi ini masuk ke ringkasan bulan …”.
- Detail menampilkan notice bahwa edit/hapus server belum tersedia (C2B2);
  tidak menulis Room pada jalur server.
- Tidak ada camera/upload 10 MB, budget progress dummy, saldo rekening,
  subscription, investasi, ID email mentah atau file palsu.

## Screenshot

`app/build/screenshots/01-overview.png`, `02-transactions.png`,
`03-transaction-detail.png`, `12-manual-entry.png` (hasil Roborazzi lokal,
dataset fixture/demo; akun live tetap memakai data server).

## Batas

Pagination/load-more >100, filter/search server penuh, import job/batch, budget
server, analysis freshness UI, admin/lampiran dan Room removal belum selesai.
C2A gate mapping/strict/refresh/pending code sudah ada; dua verifikasi UI
(refresh <5 detik dan retry respons hilang) masih perlu dijalankan pada emulator
sebagai gate terpisah.
