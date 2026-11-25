# Metrik visual Stitch (render 393×852, DPR 2)

Sumber: render lokal `code.html` dengan Playwright, viewport CSS 393×852, font Manrope termuat. Koordinat dalam **dp CSS**, origin = kiri-atas konten web (belum termasuk status bar Android ±24 dp). Full-page height tercatat per layar. File ini menjadi acuan implementasi Compose; screenshot referensi ada di `docs/evidence/stitch_redesign_2026-11-25/reference/`.


## Masuk / Daftar

Tinggi halaman terukur: **836 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 64 | 80 | 129×24 | SPAN | FinancialRecap | 18px/700 | a0, 0, 0, 0 | 0px |
| 92 | 80 | 117×18 | SPAN | Pencatat keuangan | 13px/400 | a0, 0, 0, 0 | 0px |
| 160 | 20 | 353×76 | H1 | Catat keuangan dengan tenang. | 32px/700 | a0, 0, 0, 0 | 0px |
| 248 | 20 | 353×40 | P | Rekap transaksi manual dan notifikasi Livin' dalam satu temp | 14px/500 | a0, 0, 0, 0 | 0px |
| 320 | 20 | 353×177 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 341 | 41 | 36×36 | SPAN |  | 16px/400 | 217, 244, 73 | 16px |
| 349 | 49 | 20×20 | SPAN | edit_note | 20px/400 | a0, 0, 0, 0 | 0px |
| 341 | 89 | 223×22 | P | Manual & Gmail | 16px/600 | a0, 0, 0, 0 | 0px |
| 365 | 89 | 223×18 | P | Impor notifikasi Livin' secara opsional. | 13px/400 | a0, 0, 0, 0 | 0px |
| 416 | 41 | 36×36 | SPAN |  | 16px/400 | 244, 244, 241 | 16px |
| 424 | 49 | 20×20 | SPAN | lock | 20px/400 | a0, 0, 0, 0 | 0px |
| 416 | 89 | 263×22 | P | Hanya pencatatan | 16px/600 | a0, 0, 0, 0 | 0px |
| 440 | 89 | 263×36 | P | Tanpa simpan nomor rekening atau akses transfer. | 13px/400 | a0, 0, 0, 0 | 0px |
| 758 | 20 | 353×48 | A |  | 16px/400 | 23, 25, 21 | 16px |
| 772 | 138 | 149×20 | SPAN | Masuk dengan Google | 14px/600 | a0, 0, 0, 0 | 0px |
| 818 | 20 | 353×18 | P | Riwayat transaksi hingga 1 tahun. | 13px/400 | a0, 0, 0, 0 | 0px |

## Tautkan Gmail

Tinggi halaman terukur: **852 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 17 | 21 | 22×22 | SPAN | arrow_back | 22px/400 | a0, 0, 0, 0 | 0px |
| 16 | 136 | 121×24 | H1 | Tautkan Gmail | 18px/700 | a0, 0, 0, 0 | 0px |
| 56 | 20 | 56×56 | DIV |  | 16px/400 | 217, 244, 73 | 24px |
| 70 | 34 | 28×28 | SPAN | mail | 28px/400 | a0, 0, 0, 0 | 0px |
| 132 | 20 | 353×30 | H2 | Baca notifikasi Livin' otomatis | 24px/700 | a0, 0, 0, 0 | 0px |
| 170 | 20 | 353×40 | P | Opsional. Hanya membaca email notifikasi Livin' by Mandiri. | 14px/500 | a0, 0, 0, 0 | 0px |
| 230 | 20 | 353×90 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 249 | 37 | 20×20 | SPAN | visibility | 20px/400 | a0, 0, 0, 0 | 0px |
| 247 | 69 | 230×18 | P | Akses baca-saja untuk email notifikasi. | 13px/400 | a0, 0, 0, 0 | 0px |
| 283 | 37 | 20×20 | SPAN | tune | 20px/400 | a0, 0, 0, 0 | 0px |
| 281 | 69 | 214×18 | P | Izin AI diatur terpisah di Pengaturan. | 13px/400 | a0, 0, 0, 0 | 0px |
| 744 | 20 | 353×48 | BUTTON |  | 16px/400 | 217, 244, 73 | 16px |
| 758 | 149 | 96×20 | SPAN | Tautkan Gmail | 14px/600 | a0, 0, 0, 0 | 0px |
| 804 | 20 | 353×48 | A |  | 16px/400 | 255, 255, 255 | 16px |
| 818 | 174 | 44×20 | SPAN | Lewati | 14px/600 | a0, 0, 0, 0 | 0px |

## Ringkasan

Tinggi halaman terukur: **1064 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 16 | 152 | 89×24 | H1 | Ringkasan | 18px/700 | a0, 0, 0, 0 | 0px |
| 17 | 350 | 22×22 | SPAN | sync | 22px/400 | a0, 0, 0, 0 | 0px |
| 64 | 72 | 249×58 | DIV |  | 16px/400 | 244, 244, 241 | 9999px |
| 83 | 93 | 20×20 | SPAN | chevron_left | 20px/400 | a0, 0, 0, 0 | 0px |
| 83 | 131 | 131×20 | SPAN | November 2026 | 14px/600 | a0, 0, 0, 0 | 0px |
| 83 | 280 | 20×20 | SPAN | chevron_right | 20px/400 | a0, 0, 0, 0 | 0px |
| 152 | 36 | 321×16 | DIV |  | 16px/400 | 217, 244, 73 | 24px 24px |
| 162 | 20 | 353×245 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 185 | 41 | 36×36 | DIV |  | 16px/400 | 244, 244, 241 | 20px |
| 183 | 87 | 96×22 | P | Bank Mandiri | 16px/600 | a0, 0, 0, 0 | 0px |
| 205 | 87 | 96×18 | P | Livin' by Mandiri | 13px/400 | a0, 0, 0, 0 | 0px |
| 193 | 278 | 20×20 | SPAN | chevron_left | 20px/400 | a0, 0, 0, 0 | 0px |
| 193 | 322 | 20×20 | SPAN | chevron_right | 20px/400 | a0, 0, 0, 0 | 0px |
| 248 | 41 | 125×16 | SPAN | Pengeluaran tercatat | 12px/600 | a0, 0, 0, 0 | 0px |
| 247 | 334 | 18×18 | SPAN | arrow_outward | 18px/400 | a0, 0, 0, 0 | 0px |
| 267 | 41 | 311×38 | P | Rp4.285.000 | 32px/700 | a0, 0, 0, 0 | 0px |
| 307 | 41 | 311×18 | P | 34 transaksi | 13px/400 | a0, 0, 0, 0 | 0px |
| 366 | 41 | 68×16 | SPAN | Pemasukan | 12px/600 | a0, 0, 0, 0 | 0px |
| 362 | 218 | 110×24 | SPAN | Rp8.500.000 | 18px/700 | a0, 0, 0, 0 | 0px |
| 365 | 334 | 18×18 | SPAN | chevron_right | 18px/400 | a0, 0, 0, 0 | 0px |
| 419 | 170 | 24×6 | BUTTON |  | 16px/400 | 23, 25, 21 | 9999px |
| 445 | 20 | 353×295 | SECTION |  | 16px/400 | 255, 255, 255 | 24px |
| 466 | 41 | 116×22 | H2 | Kategori utama | 16px/600 | a0, 0, 0, 0 | 0px |
| 469 | 306 | 46×16 | A | Analisis | 12px/600 | a0, 0, 0, 0 | 0px |
| 504 | 51 | 24×14 | SPAN | 38% | 11px/600 | a0, 0, 0, 0 | 0px |
| 526 | 47 | 32×96 | DIV |  | 16px/400 | 244, 244, 241 | 20px |
| 537 | 49 | 28×83 | DIV |  | 16px/400 | 217, 244, 73 | 16px |
| 630 | 45 | 36×36 | SPAN |  | 16px/400 | 23, 25, 21 | 20px |
| 639 | 54 | 18×18 | SPAN | restaurant | 18px/400 | a0, 0, 0, 0 | 0px |
| 504 | 107 | 24×14 | SPAN | 26% | 11px/600 | a0, 0, 0, 0 | 0px |
| 526 | 103 | 32×96 | DIV |  | 16px/400 | 244, 244, 241 | 20px |
| 561 | 105 | 28×59 | DIV |  | 16px/400 | 23, 25, 21 | 16px |
| 630 | 101 | 36×36 | SPAN |  | 16px/400 | 244, 244, 241 | 20px |
| 639 | 110 | 18×18 | SPAN | receipt_long | 18px/400 | a0, 0, 0, 0 | 0px |
| 504 | 163 | 24×14 | SPAN | 21% | 11px/600 | a0, 0, 0, 0 | 0px |
| 526 | 159 | 32×96 | DIV |  | 16px/400 | 244, 244, 241 | 20px |
| 572 | 161 | 28×48 | DIV |  | 16px/400 | 69, 71, 66 | 16px |
| 630 | 157 | 36×36 | SPAN |  | 16px/400 | 244, 244, 241 | 20px |
| 639 | 166 | 18×18 | SPAN | directions_car | 18px/400 | a0, 0, 0, 0 | 0px |
| 504 | 222 | 17×14 | SPAN | 8% | 11px/600 | a0, 0, 0, 0 | 0px |
| 526 | 215 | 32×96 | DIV |  | 16px/400 | 244, 244, 241 | 20px |
| 598 | 217 | 28×22 | DIV |  | 16px/400 | 118, 120, 113 | 16px |
| 630 | 213 | 36×36 | SPAN |  | 16px/400 | 244, 244, 241 | 20px |
| 639 | 222 | 18×18 | SPAN | shopping_bag | 18px/400 | a0, 0, 0, 0 | 0px |
| 504 | 278 | 17×14 | SPAN | 4% | 11px/600 | a0, 0, 0, 0 | 0px |
| 526 | 271 | 32×96 | DIV |  | 16px/400 | 244, 244, 241 | 20px |
| 607 | 273 | 28×13 | DIV |  | 16px/400 | 226, 227, 219 | 16px |
| 630 | 269 | 36×36 | SPAN |  | 16px/400 | 244, 244, 241 | 20px |
| 639 | 278 | 18×18 | SPAN | medical_services | 18px/400 | a0, 0, 0, 0 | 0px |
| 504 | 334 | 17×14 | SPAN | 3% | 11px/600 | a0, 0, 0, 0 | 0px |
| 526 | 327 | 32×96 | DIV |  | 16px/400 | 244, 244, 241 | 20px |
| 611 | 329 | 28×9 | DIV |  | 16px/400 | 226, 227, 219 | 16px |
| 630 | 325 | 36×36 | SPAN |  | 16px/400 | 244, 244, 241 | 20px |
| 639 | 334 | 18×18 | SPAN | movie | 18px/400 | a0, 0, 0, 0 | 0px |
| 700 | 41 | 94×18 | SPAN | Makan & Minum | 13px/400 | a0, 0, 0, 0 | 0px |
| 699 | 225 | 127×20 | SPAN | Rp1.640.000 · 38% | 14px/600 | a0, 0, 0, 0 | 0px |
| 773 | 20 | 131×22 | H2 | Transaksi terbaru | 16px/600 | a0, 0, 0, 0 | 0px |
| 760 | 291 | 82×48 | A | Lihat semua | 14px/600 | a0, 0, 0, 0 | 0px |
| 820 | 20 | 353×244 | UL |  | 16px/400 | 255, 255, 255 | 20px |
| 837 | 37 | 227×22 | P | Kopi Kenangan | 16px/600 | a0, 0, 0, 0 | 0px |
| 866 | 37 | 94×18 | SPAN | Makan & Minum | 13px/400 | a0, 0, 0, 0 | 0px |
| 866 | 146 | 75×18 | SPAN | Livin · QRIS | 11px/600 | 238, 238, 235 | 12px |
| 851 | 276 | 80×22 | SPAN | -Rp38.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 920 | 37 | 217×22 | P | PLN Pascabayar | 16px/600 | a0, 0, 0, 0 | 0px |
| 949 | 37 | 104×18 | SPAN | Tagihan & Utilitas | 13px/400 | a0, 0, 0, 0 | 0px |
| 949 | 156 | 63×18 | SPAN | Livin · VA | 11px/600 | 238, 238, 235 | 12px |
| 934 | 266 | 90×22 | SPAN | -Rp485.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 1003 | 37 | 227×22 | P | Warung Bu Sari | 16px/600 | a0, 0, 0, 0 | 0px |
| 1029 | 37 | 60×18 | SPAN | Saran AI | 11px/600 | 255, 237, 229 | 9999px |
| 1029 | 103 | 56×18 | SPAN | Manual | 11px/600 | 238, 238, 235 | 12px |
| 1014 | 276 | 80×22 | SPAN | -Rp27.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 787 | 0 | 393×65 | NAV |  | 16px/400 | 255, 255, 255 | 0px |
| 798 | 28 | 52×28 | SPAN |  | 16px/400 | 217, 244, 73 | 9999px |
| 802 | 44 | 20×20 | SPAN | home | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 26 | 56×14 | SPAN | Ringkasan | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 139 | 20×20 | SPAN | receipt_long | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 123 | 52×14 | SPAN | Transaksi | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 234 | 20×20 | SPAN | bar_chart | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 223 | 42×14 | SPAN | Analisis | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 329 | 20×20 | SPAN | settings | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 308 | 64×14 | SPAN | Pengaturan | 11px/600 | a0, 0, 0, 0 | 0px |

## Transaksi

Tinggi halaman terukur: **871 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 16 | 156 | 81×24 | H1 | Transaksi | 18px/700 | a0, 0, 0, 0 | 0px |
| 17 | 350 | 22×22 | SPAN | sync | 22px/400 | a0, 0, 0, 0 | 0px |
| 78 | 34 | 20×20 | SPAN | search | 20px/400 | a0, 0, 0, 0 | 0px |
| 64 | 20 | 353×48 | INPUT |  | 14px/500 | 255, 255, 255 | 16px |
| 128 | 20 | 99×36 | BUTTON |  | 12px/600 | 238, 238, 235 | 9999px |
| 138 | 32 | 16×16 | SPAN | calendar_today | 16px/400 | a0, 0, 0, 0 | 0px |
| 138 | 52 | 55×16 | SPAN | Nov 2026 | 12px/600 | a0, 0, 0, 0 | 0px |
| 128 | 127 | 68×36 | BUTTON | Semua | 12px/600 | 23, 25, 21 | 9999px |
| 128 | 203 | 104×36 | BUTTON | Pengeluaran | 12px/600 | 255, 255, 255 | 9999px |
| 128 | 315 | 98×36 | BUTTON | Pemasukan | 12px/600 | 255, 255, 255 | 9999px |
| 128 | 421 | 123×36 | BUTTON | Sumber: Semua | 12px/600 | 255, 255, 255 | 9999px |
| 184 | 20 | 353×16 | H2 | Hari ini · 25 Nov 2026 | 12px/600 | a0, 0, 0, 0 | 0px |
| 208 | 20 | 353×84 | UL |  | 16px/400 | 255, 255, 255 | 20px |
| 225 | 37 | 227×22 | P | Kopi Kenangan | 16px/600 | a0, 0, 0, 0 | 0px |
| 254 | 37 | 94×18 | SPAN | Makan & Minum | 13px/400 | a0, 0, 0, 0 | 0px |
| 254 | 146 | 75×18 | SPAN | Livin · QRIS | 11px/600 | 238, 238, 235 | 12px |
| 239 | 276 | 80×22 | SPAN | -Rp38.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 308 | 20 | 353×16 | H2 | Kemarin · 24 Nov 2026 | 12px/600 | a0, 0, 0, 0 | 0px |
| 332 | 20 | 353×167 | UL |  | 16px/400 | 255, 255, 255 | 20px |
| 349 | 37 | 217×22 | P | PLN Pascabayar | 16px/600 | a0, 0, 0, 0 | 0px |
| 378 | 37 | 104×18 | SPAN | Tagihan & Utilitas | 13px/400 | a0, 0, 0, 0 | 0px |
| 378 | 156 | 63×18 | SPAN | Livin · VA | 11px/600 | 238, 238, 235 | 12px |
| 363 | 266 | 90×22 | SPAN | -Rp485.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 432 | 37 | 227×22 | P | Warung Bu Sari | 16px/600 | a0, 0, 0, 0 | 0px |
| 461 | 37 | 60×18 | SPAN | Saran AI | 11px/600 | 255, 237, 229 | 9999px |
| 461 | 113 | 56×18 | SPAN | Manual | 11px/600 | 238, 238, 235 | 12px |
| 446 | 276 | 80×22 | SPAN | -Rp27.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 515 | 20 | 353×16 | H2 | 22 Nov 2026 | 12px/600 | a0, 0, 0, 0 | 0px |
| 539 | 20 | 353×84 | UL |  | 16px/400 | 255, 255, 255 | 20px |
| 556 | 37 | 200×22 | P | Gaji Bulanan | 16px/600 | a0, 0, 0, 0 | 0px |
| 585 | 37 | 73×18 | SPAN | Penghasilan | 13px/400 | a0, 0, 0, 0 | 0px |
| 585 | 126 | 56×18 | SPAN | Manual | 11px/600 | 238, 238, 235 | 12px |
| 570 | 249 | 107×22 | SPAN | +Rp8.500.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 639 | 20 | 353×16 | H2 | 21 Nov 2026 | 12px/600 | a0, 0, 0, 0 | 0px |
| 663 | 20 | 353×84 | UL |  | 16px/400 | 255, 255, 255 | 20px |
| 680 | 37 | 217×22 | P | Pembayaran QRIS | 16px/600 | a0, 0, 0, 0 | 0px |
| 709 | 37 | 85×18 | SPAN | Belanja Harian | 13px/400 | a0, 0, 0, 0 | 0px |
| 709 | 138 | 75×18 | SPAN | Livin · QRIS | 11px/600 | 238, 238, 235 | 12px |
| 694 | 266 | 90×22 | SPAN | -Rp112.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 763 | 20 | 353×16 | H2 | 20 Nov 2026 | 12px/600 | a0, 0, 0, 0 | 0px |
| 787 | 20 | 353×84 | UL |  | 16px/400 | 255, 255, 255 | 20px |
| 804 | 37 | 215×22 | P | Transfer Masuk — Raka | 16px/600 | a0, 0, 0, 0 | 0px |
| 833 | 37 | 46×18 | SPAN | Lainnya | 13px/400 | a0, 0, 0, 0 | 0px |
| 833 | 98 | 94×18 | SPAN | Livin · Transfer | 11px/600 | 238, 238, 235 | 12px |
| 818 | 264 | 92×22 | SPAN | +Rp250.000 | 16px/600 | a0, 0, 0, 0 | 0px |
| 716 | 317 | 56×56 | A |  | 16px/400 | 217, 244, 73 | 24px |
| 731 | 332 | 26×26 | SPAN | add | 26px/400 | a0, 0, 0, 0 | 0px |
| 787 | 0 | 393×65 | NAV |  | 16px/400 | 255, 255, 255 | 0px |
| 802 | 44 | 20×20 | SPAN | home | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 26 | 56×14 | SPAN | Ringkasan | 11px/600 | a0, 0, 0, 0 | 0px |
| 798 | 123 | 52×28 | SPAN |  | 16px/400 | 217, 244, 73 | 9999px |
| 802 | 139 | 20×20 | SPAN | receipt_long | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 123 | 52×14 | SPAN | Transaksi | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 234 | 20×20 | SPAN | bar_chart | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 223 | 42×14 | SPAN | Analisis | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 329 | 20×20 | SPAN | settings | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 308 | 64×14 | SPAN | Pengaturan | 11px/600 | a0, 0, 0, 0 | 0px |

## Detail Transaksi

Tinggi halaman terukur: **518 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 17 | 21 | 22×22 | SPAN | arrow_back | 22px/400 | a0, 0, 0, 0 | 0px |
| 16 | 130 | 133×24 | H1 | Detail transaksi | 18px/700 | a0, 0, 0, 0 | 0px |
| 64 | 20 | 353×180 | SECTION |  | 16px/400 | 255, 255, 255 | 24px |
| 85 | 41 | 79×22 | SPAN | Livin · QRIS | 11px/600 | 238, 238, 235 | 12px |
| 119 | 41 | 311×38 | P | -Rp38.000 | 32px/700 | a0, 0, 0, 0 | 0px |
| 169 | 41 | 311×24 | H2 | Kopi Kenangan | 18px/700 | a0, 0, 0, 0 | 0px |
| 205 | 41 | 311×18 | P | Nominal & tanggal dari notifikasi Livin'. | 13px/400 | a0, 0, 0, 0 | 0px |
| 264 | 20 | 353×254 | SECTION |  | 16px/400 | 255, 255, 255 | 24px |
| 281 | 37 | 52×20 | SPAN | Tanggal | 14px/500 | a0, 0, 0, 0 | 0px |
| 281 | 222 | 134×20 | SPAN | 25 Nov 2026 · 09:42 | 14px/600 | a0, 0, 0, 0 | 0px |
| 344 | 37 | 56×20 | SPAN | Kategori | 14px/500 | a0, 0, 0, 0 | 0px |
| 334 | 203 | 153×40 | BUTTON |  | 16px/400 | 244, 244, 241 | 16px |
| 344 | 215 | 105×20 | SPAN | Makan & Minum | 14px/600 | a0, 0, 0, 0 | 0px |
| 345 | 326 | 18×18 | SPAN | edit | 18px/400 | a0, 0, 0, 0 | 0px |
| 407 | 37 | 319×20 | LABEL | Catatan | 14px/500 | a0, 0, 0, 0 | 0px |
| 435 | 37 | 319×66 | TEXTAREA | Kopi pagi sebelum rapat | 14px/500 | 244, 244, 241 | 16px |

## Tambah Transaksi

Tinggi halaman terukur: **852 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 17 | 21 | 22×22 | SPAN | arrow_back | 22px/400 | a0, 0, 0, 0 | 0px |
| 16 | 121 | 150×24 | H1 | Tambah transaksi | 18px/700 | a0, 0, 0, 0 | 0px |
| 64 | 20 | 353×54 | DIV |  | 16px/400 | 244, 244, 241 | 20px |
| 69 | 25 | 170×44 | BUTTON | Pengeluaran | 14px/600 | 255, 255, 255 | 16px |
| 69 | 198 | 170×44 | BUTTON | Pemasukan | 14px/600 | a0, 0, 0, 0 | 16px |
| 134 | 20 | 353×94 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 151 | 37 | 319×16 | SPAN | Nominal | 12px/600 | a0, 0, 0, 0 | 0px |
| 185 | 37 | 23×24 | SPAN | Rp | 18px/700 | a0, 0, 0, 0 | 0px |
| 173 | 64 | 106×38 | SPAN | 45.000 | 32px/700 | a0, 0, 0, 0 | 0px |
| 244 | 20 | 353×384 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 261 | 37 | 319×16 | LABEL | Deskripsi | 12px/600 | a0, 0, 0, 0 | 0px |
| 283 | 37 | 319×48 | INPUT |  | 14px/500 | 244, 244, 241 | 16px |
| 347 | 37 | 319×16 | LABEL | Tanggal | 12px/600 | a0, 0, 0, 0 | 0px |
| 369 | 37 | 319×48 | INPUT |  | 14px/500 | 244, 244, 241 | 16px |
| 433 | 37 | 319×16 | SPAN | Kategori | 12px/600 | a0, 0, 0, 0 | 0px |
| 455 | 37 | 319×48 | BUTTON |  | 16px/400 | 244, 244, 241 | 16px |
| 469 | 52 | 102×20 | SPAN | Makan & Minum | 14px/500 | a0, 0, 0, 0 | 0px |
| 469 | 321 | 20×20 | SPAN | expand_more | 20px/400 | a0, 0, 0, 0 | 0px |
| 519 | 37 | 319×16 | LABEL | Catatan | 12px/600 | a0, 0, 0, 0 | 0px |
| 541 | 37 | 319×70 | TEXTAREA |  | 14px/500 | 244, 244, 241 | 16px |
| 535 | 0 | 393×317 | DIV |  | 16px/400 | 250, 250, 247 | 0px |
| 547 | 20 | 353×48 | BUTTON |  | 14px/600 | 217, 244, 73 | 9999px |
| 561 | 126 | 116×20 | SPAN | Simpan transaksi | 14px/600 | a0, 0, 0, 0 | 0px |
| 562 | 249 | 18×18 | SPAN | chevron_right | 18px/400 | a0, 0, 0, 0 | 0px |
| 607 | 0 | 393×245 | SECTION |  | 16px/400 | 244, 244, 241 | 0px |
| 620 | 12 | 118×48 | BUTTON | 1 | 18px/700 | 255, 255, 255 | 20px |
| 620 | 138 | 118×48 | BUTTON | 2 | 18px/700 | 255, 255, 255 | 20px |
| 620 | 263 | 118×48 | BUTTON | 3 | 18px/700 | 255, 255, 255 | 20px |
| 676 | 12 | 118×48 | BUTTON | 4 | 18px/700 | 255, 255, 255 | 20px |
| 676 | 138 | 118×48 | BUTTON | 5 | 18px/700 | 255, 255, 255 | 20px |
| 676 | 263 | 118×48 | BUTTON | 6 | 18px/700 | 255, 255, 255 | 20px |
| 732 | 12 | 118×48 | BUTTON | 7 | 18px/700 | 255, 255, 255 | 20px |
| 732 | 138 | 118×48 | BUTTON | 8 | 18px/700 | 255, 255, 255 | 20px |
| 732 | 263 | 118×48 | BUTTON | 9 | 18px/700 | 255, 255, 255 | 20px |
| 788 | 12 | 118×48 | BUTTON | 000 | 14px/600 | a0, 0, 0, 0 | 20px |
| 788 | 138 | 118×48 | BUTTON | 0 | 18px/700 | 255, 255, 255 | 20px |
| 801 | 311 | 22×22 | SPAN | backspace | 22px/400 | a0, 0, 0, 0 | 0px |

## Analisis

Tinggi halaman terukur: **852 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 16 | 163 | 67×24 | H1 | Analisis | 18px/700 | a0, 0, 0, 0 | 0px |
| 17 | 350 | 22×22 | SPAN | sync | 22px/400 | a0, 0, 0, 0 | 0px |
| 64 | 134 | 126×30 | SPAN | November 2026 | 12px/600 | 244, 244, 241 | 9999px |
| 110 | 20 | 353×144 | SECTION |  | 16px/400 | 255, 255, 255 | 24px |
| 131 | 41 | 95×22 | SPAN | Insight utama | 11px/600 | 217, 244, 73 | 9999px |
| 161 | 41 | 311×24 | H2 | Makan & Minum (38%) | 18px/700 | a0, 0, 0, 0 | 0px |
| 193 | 41 | 311×40 | P | Rp1.640.000 dari 18 transaksi, naik Rp190.000 dari Oktober. | 14px/500 | a0, 0, 0, 0 | 0px |
| 270 | 20 | 353×252 | SECTION |  | 16px/400 | 255, 255, 255 | 24px |
| 291 | 41 | 311×22 | H3 | Pengeluaran per kategori | 16px/600 | a0, 0, 0, 0 | 0px |
| 329 | 41 | 106×18 | SPAN | 1. Makan & Minum | 13px/500 | a0, 0, 0, 0 | 0px |
| 329 | 230 | 122×18 | SPAN | Rp1.640.000 (38%) | 13px/600 | a0, 0, 0, 0 | 0px |
| 351 | 41 | 311×12 | DIV |  | 16px/400 | 238, 238, 235 | 9999px |
| 351 | 41 | 118×12 | DIV |  | 16px/400 | 23, 25, 21 | 9999px |
| 375 | 41 | 120×18 | SPAN | 2. Tagihan & Utilitas | 13px/500 | a0, 0, 0, 0 | 0px |
| 375 | 230 | 122×18 | SPAN | Rp1.120.000 (26%) | 13px/600 | a0, 0, 0, 0 | 0px |
| 397 | 41 | 311×12 | DIV |  | 16px/400 | 238, 238, 235 | 9999px |
| 397 | 41 | 81×12 | DIV |  | 16px/400 | 69, 71, 66 | 9999px |
| 421 | 41 | 90×18 | SPAN | 3. Transportasi | 13px/500 | a0, 0, 0, 0 | 0px |
| 421 | 242 | 110×18 | SPAN | Rp890.000 (21%) | 13px/600 | a0, 0, 0, 0 | 0px |
| 443 | 41 | 311×12 | DIV |  | 16px/400 | 238, 238, 235 | 9999px |
| 443 | 41 | 65×12 | DIV |  | 16px/400 | 118, 120, 113 | 9999px |
| 467 | 41 | 101×18 | SPAN | 4. Belanja Harian | 13px/500 | a0, 0, 0, 0 | 0px |
| 467 | 242 | 110×18 | SPAN | Rp635.000 (15%) | 13px/600 | a0, 0, 0, 0 | 0px |
| 489 | 41 | 311×12 | DIV |  | 16px/400 | 238, 238, 235 | 9999px |
| 489 | 41 | 47×12 | DIV |  | 16px/400 | 226, 227, 219 | 9999px |
| 538 | 20 | 353×126 | SECTION |  | 16px/400 | 255, 255, 255 | 24px |
| 559 | 41 | 311×22 | H3 | Temuan ringkas | 16px/600 | a0, 0, 0, 0 | 0px |
| 595 | 41 | 18×18 | SPAN | qr_code_2 | 18px/400 | a0, 0, 0, 0 | 0px |
| 593 | 67 | 221×20 | SPAN | 22 dari 34 transaksi via QRIS Livin'. | 14px/500 | a0, 0, 0, 0 | 0px |
| 625 | 41 | 18×18 | SPAN | schedule | 18px/400 | a0, 0, 0, 0 | 0px |
| 623 | 67 | 239×20 | SPAN | Pengeluaran tertinggi di akhir pekan. | 14px/500 | a0, 0, 0, 0 | 0px |
| 787 | 0 | 393×65 | NAV |  | 16px/400 | 255, 255, 255 | 0px |
| 802 | 44 | 20×20 | SPAN | home | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 26 | 56×14 | SPAN | Ringkasan | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 139 | 20×20 | SPAN | receipt_long | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 123 | 52×14 | SPAN | Transaksi | 11px/600 | a0, 0, 0, 0 | 0px |
| 798 | 218 | 52×28 | SPAN |  | 16px/400 | 217, 244, 73 | 9999px |
| 802 | 234 | 20×20 | SPAN | bar_chart | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 223 | 42×14 | SPAN | Analisis | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 329 | 20×20 | SPAN | settings | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 308 | 64×14 | SPAN | Pengaturan | 11px/600 | a0, 0, 0, 0 | 0px |

## Pengaturan

Tinggi halaman terukur: **1017 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 16 | 146 | 100×24 | H1 | Pengaturan | 18px/700 | a0, 0, 0, 0 | 0px |
| 64 | 20 | 353×16 | H2 | Akun Google | 12px/600 | a0, 0, 0, 0 | 0px |
| 88 | 20 | 353×78 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 105 | 37 | 44×44 | DIV | NP | 16px/600 | 23, 25, 21 | 9999px |
| 107 | 93 | 163×22 | P | Nadya Pratama | 16px/600 | a0, 0, 0, 0 | 0px |
| 129 | 93 | 163×18 | P | nadya.pratama@gmail.com | 13px/400 | a0, 0, 0, 0 | 0px |
| 186 | 20 | 353×16 | H2 | Koneksi Gmail | 12px/600 | a0, 0, 0, 0 | 0px |
| 210 | 20 | 353×134 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 227 | 37 | 193×22 | P | Notifikasi Livin' by Mandiri | 16px/600 | a0, 0, 0, 0 | 0px |
| 249 | 37 | 193×18 | P | Baca-saja | 13px/400 | a0, 0, 0, 0 | 0px |
| 236 | 310 | 46×22 | SPAN | Aktif | 11px/600 | 230, 246, 236 | 9999px |
| 279 | 37 | 319×48 | BUTTON | Putuskan Gmail | 14px/600 | 244, 244, 241 | 16px |
| 364 | 20 | 353×16 | H2 | Izin AI | 12px/600 | a0, 0, 0, 0 | 0px |
| 388 | 20 | 353×147 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 405 | 37 | 143×22 | P | Kategorisasi AI | 16px/600 | a0, 0, 0, 0 | 0px |
| 427 | 37 | 143×18 | P | Saran kategori otomatis | 13px/400 | a0, 0, 0, 0 | 0px |
| 413 | 332 | 24×24 | INPUT |  | 16px/400 | 23, 25, 21 | 12px |
| 478 | 37 | 175×22 | P | Analisis AI | 16px/600 | a0, 0, 0, 0 | 0px |
| 500 | 37 | 175×18 | P | Ringkasan pola di tab Analisis | 13px/400 | a0, 0, 0, 0 | 0px |
| 486 | 332 | 24×24 | INPUT |  | 16px/400 | 23, 25, 21 | 12px |
| 555 | 20 | 353×16 | H2 | Notifikasi | 12px/600 | a0, 0, 0, 0 | 0px |
| 579 | 20 | 353×126 | DIV |  | 16px/400 | 255, 255, 255 | 24px |
| 596 | 37 | 283×22 | P | Notifikasi transaksi baru | 16px/600 | a0, 0, 0, 0 | 0px |
| 618 | 37 | 283×36 | P | Beri tahu saya saat transaksi Livin baru tercatat. | 13px/400 | a0, 0, 0, 0 | 0px |
| 613 | 332 | 24×24 | INPUT |  | 16px/400 | 255, 255, 255 | 12px |
| 666 | 37 | 66×22 | SPAN | Nonaktif | 11px/600 | 238, 238, 235 | 9999px |
| 669 | 245 | 111×16 | A | Pratinjau notifikasi | 12px/600 | a0, 0, 0, 0 | 0px |
| 725 | 20 | 353×16 | H2 | Kategori | 12px/600 | a0, 0, 0, 0 | 0px |
| 749 | 20 | 353×74 | A |  | 16px/400 | 255, 255, 255 | 24px |
| 775 | 37 | 22×22 | SPAN | label | 22px/400 | a0, 0, 0, 0 | 0px |
| 766 | 71 | 128×22 | P | Kelola kategori | 16px/600 | a0, 0, 0, 0 | 0px |
| 788 | 71 | 128×18 | P | Daftar & ikon kategori | 13px/400 | a0, 0, 0, 0 | 0px |
| 776 | 336 | 20×20 | SPAN | chevron_right | 20px/400 | a0, 0, 0, 0 | 0px |
| 843 | 20 | 353×16 | H2 | Widget | 12px/600 | a0, 0, 0, 0 | 0px |
| 867 | 20 | 353×74 | A |  | 16px/400 | 255, 255, 255 | 24px |
| 893 | 37 | 22×22 | SPAN | widgets | 22px/400 | a0, 0, 0, 0 | 0px |
| 884 | 71 | 156×22 | P | Widget layar utama | 16px/600 | a0, 0, 0, 0 | 0px |
| 906 | 71 | 156×18 | P | Pratinjau widget 2×2 & 4×2 | 13px/400 | a0, 0, 0, 0 | 0px |
| 894 | 336 | 20×20 | SPAN | chevron_right | 20px/400 | a0, 0, 0, 0 | 0px |
| 969 | 20 | 353×48 | BUTTON | Keluar | 14px/600 | 255, 237, 234 | 16px |
| 787 | 0 | 393×65 | NAV |  | 16px/400 | 255, 255, 255 | 0px |
| 802 | 44 | 20×20 | SPAN | home | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 26 | 56×14 | SPAN | Ringkasan | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 139 | 20×20 | SPAN | receipt_long | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 123 | 52×14 | SPAN | Transaksi | 11px/600 | a0, 0, 0, 0 | 0px |
| 802 | 234 | 20×20 | SPAN | bar_chart | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 223 | 42×14 | SPAN | Analisis | 11px/600 | a0, 0, 0, 0 | 0px |
| 798 | 313 | 52×28 | SPAN |  | 16px/400 | 217, 244, 73 | 9999px |
| 802 | 329 | 20×20 | SPAN | settings | 20px/400 | a0, 0, 0, 0 | 0px |
| 828 | 308 | 64×14 | SPAN | Pengaturan | 11px/600 | a0, 0, 0, 0 | 0px |

## Kelola Kategori

Tinggi halaman terukur: **828 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 17 | 21 | 22×22 | SPAN | arrow_back | 22px/400 | a0, 0, 0, 0 | 0px |
| 16 | 133 | 127×24 | H1 | Kelola kategori | 18px/700 | a0, 0, 0, 0 | 0px |
| 78 | 34 | 20×20 | SPAN | search | 20px/400 | a0, 0, 0, 0 | 0px |
| 64 | 20 | 353×48 | INPUT |  | 14px/500 | 255, 255, 255 | 16px |
| 128 | 20 | 353×487 | UL |  | 16px/400 | 255, 255, 255 | 24px |
| 145 | 37 | 48×48 | BUTTON |  | 16px/400 | 244, 244, 241 | 20px |
| 158 | 50 | 22×22 | SPAN | restaurant | 22px/400 | a0, 0, 0, 0 | 0px |
| 149 | 97 | 117×22 | P | Makan & Minum | 16px/600 | a0, 0, 0, 0 | 0px |
| 171 | 97 | 117×18 | P | 18 transaksi | 13px/400 | a0, 0, 0, 0 | 0px |
| 159 | 270 | 20×20 | SPAN | edit | 20px/400 | a0, 0, 0, 0 | 0px |
| 159 | 322 | 20×20 | SPAN | delete | 20px/400 | a0, 0, 0, 0 | 0px |
| 226 | 37 | 48×48 | BUTTON |  | 16px/400 | 244, 244, 241 | 20px |
| 239 | 50 | 22×22 | SPAN | directions_car | 22px/400 | a0, 0, 0, 0 | 0px |
| 230 | 97 | 96×22 | P | Transportasi | 16px/600 | a0, 0, 0, 0 | 0px |
| 252 | 97 | 96×18 | P | 7 transaksi | 13px/400 | a0, 0, 0, 0 | 0px |
| 240 | 270 | 20×20 | SPAN | edit | 20px/400 | a0, 0, 0, 0 | 0px |
| 240 | 322 | 20×20 | SPAN | delete | 20px/400 | a0, 0, 0, 0 | 0px |
| 307 | 37 | 48×48 | BUTTON |  | 16px/400 | 244, 244, 241 | 20px |
| 320 | 50 | 22×22 | SPAN | receipt_long | 22px/400 | a0, 0, 0, 0 | 0px |
| 311 | 97 | 131×22 | P | Tagihan & Utilitas | 16px/600 | a0, 0, 0, 0 | 0px |
| 333 | 97 | 131×18 | P | 4 transaksi | 13px/400 | a0, 0, 0, 0 | 0px |
| 321 | 270 | 20×20 | SPAN | edit | 20px/400 | a0, 0, 0, 0 | 0px |
| 321 | 322 | 20×20 | SPAN | delete | 20px/400 | a0, 0, 0, 0 | 0px |
| 388 | 37 | 48×48 | BUTTON |  | 16px/400 | 244, 244, 241 | 20px |
| 401 | 50 | 22×22 | SPAN | shopping_bag | 22px/400 | a0, 0, 0, 0 | 0px |
| 392 | 97 | 108×22 | P | Belanja Harian | 16px/600 | a0, 0, 0, 0 | 0px |
| 414 | 97 | 108×18 | P | 3 transaksi | 13px/400 | a0, 0, 0, 0 | 0px |
| 402 | 270 | 20×20 | SPAN | edit | 20px/400 | a0, 0, 0, 0 | 0px |
| 402 | 322 | 20×20 | SPAN | delete | 20px/400 | a0, 0, 0, 0 | 0px |
| 469 | 37 | 48×48 | BUTTON |  | 16px/400 | 244, 244, 241 | 20px |
| 482 | 50 | 22×22 | SPAN | payments | 22px/400 | a0, 0, 0, 0 | 0px |
| 473 | 97 | 93×22 | P | Penghasilan | 16px/600 | a0, 0, 0, 0 | 0px |
| 495 | 97 | 93×18 | P | 2 transaksi | 13px/400 | a0, 0, 0, 0 | 0px |
| 483 | 270 | 20×20 | SPAN | edit | 20px/400 | a0, 0, 0, 0 | 0px |
| 483 | 322 | 20×20 | SPAN | delete | 20px/400 | a0, 0, 0, 0 | 0px |
| 550 | 37 | 48×48 | BUTTON |  | 16px/400 | 244, 244, 241 | 20px |
| 563 | 50 | 22×22 | SPAN | category | 22px/400 | a0, 0, 0, 0 | 0px |
| 554 | 97 | 66×22 | P | Lainnya | 16px/600 | a0, 0, 0, 0 | 0px |
| 576 | 97 | 66×18 | P | 2 transaksi | 13px/400 | a0, 0, 0, 0 | 0px |
| 564 | 270 | 20×20 | SPAN | edit | 20px/400 | a0, 0, 0, 0 | 0px |
| 564 | 322 | 20×20 | SPAN | delete | 20px/400 | a0, 0, 0, 0 | 0px |
| 772 | 317 | 56×56 | BUTTON |  | 16px/400 | 217, 244, 73 | 24px |
| 787 | 332 | 26×26 | SPAN | add | 26px/400 | a0, 0, 0, 0 | 0px |

## Status Pembaruan (semua state)

Tinggi halaman terukur: **1016 dp** (viewport 852 dp).

| y | x | w×h | komponen | teks | font | bg | radius |
|---:|---:|---|---|---|---|---|---|
| 25 | 21 | 22×22 | SPAN | arrow_back | 22px/400 | a0, 0, 0, 0 | 0px |
| 20 | 64 | 309×24 | H1 | Keadaan "Status pembaruan" | 18px/700 | a0, 0, 0, 0 | 0px |
| 44 | 64 | 309×36 | P | Seluruh state bottom sheet sinkronisasi Gmail dalam satu tam | 13px/400 | a0, 0, 0, 0 | 0px |
| 96 | 20 | 353×158 | ARTICLE |  | 16px/400 | 255, 255, 255 | 24px |
| 113 | 37 | 106×20 | SPAN | 1. Belum tertaut | 14px/600 | a0, 0, 0, 0 | 0px |
| 114 | 257 | 99×18 | SPAN | Gmail nonaktif | 11px/600 | 238, 238, 235 | 9999px |
| 145 | 37 | 319×36 | P | Tautkan Gmail untuk membaca notifikasi Livin' secara otomati | 13px/400 | a0, 0, 0, 0 | 0px |
| 193 | 37 | 319×44 | A | Tautkan Gmail | 14px/600 | 217, 244, 73 | 16px |
| 270 | 20 | 353×104 | ARTICLE |  | 16px/400 | 255, 255, 255 | 24px |
| 287 | 37 | 278×20 | SPAN | 2. Menyinkronkan (total belum diketahui) | 14px/600 | a0, 0, 0, 0 | 0px |
| 319 | 37 | 319×8 | DIV |  | 16px/400 | 238, 238, 235 | 9999px |
| 319 | 37 | 106×8 | DIV |  | 16px/400 | 23, 25, 21 | 9999px |
| 339 | 37 | 319×18 | P | Memeriksa kotak masuk Gmail... (tanpa persentase). | 13px/400 | a0, 0, 0, 0 | 0px |
| 390 | 20 | 353×96 | ARTICLE |  | 16px/400 | 255, 255, 255 | 24px |
| 407 | 37 | 233×20 | SPAN | 3. Menyinkronkan (total diketahui) | 14px/600 | a0, 0, 0, 0 | 0px |
| 409 | 323 | 33×16 | SPAN | 8 / 14 | 12px/600 | a0, 0, 0, 0 | 0px |
| 435 | 37 | 319×8 | DIV |  | 16px/400 | 238, 238, 235 | 9999px |
| 435 | 37 | 191×8 | DIV |  | 16px/400 | 23, 25, 21 | 9999px |
| 451 | 37 | 319×18 | P | Lembar bisa ditutup; progres tetap berjalan. | 13px/400 | a0, 0, 0, 0 | 0px |
| 502 | 20 | 353×108 | ARTICLE |  | 16px/400 | 255, 255, 255 | 24px |
| 520 | 37 | 18×18 | SPAN | check_circle | 18px/400 | a0, 0, 0, 0 | 0px |
| 519 | 63 | 70×20 | SPAN | 4. Berhasil | 14px/600 | a0, 0, 0, 0 | 0px |
| 547 | 37 | 319×20 | P | 12 tersimpan · 2 dilewati | 14px/500 | a0, 0, 0, 0 | 0px |
| 575 | 37 | 319×18 | P | Kategorisasi AI: 8 menunggu kategori | 13px/400 | a0, 0, 0, 0 | 0px |
| 626 | 20 | 353×76 | ARTICLE |  | 16px/400 | 255, 255, 255 | 24px |
| 643 | 37 | 319×20 | SPAN | 5. Tidak ada transaksi baru | 14px/600 | a0, 0, 0, 0 | 0px |
| 667 | 37 | 319×18 | P | Semua notifikasi Livin' terbaru sudah tercatat. | 13px/400 | a0, 0, 0, 0 | 0px |
| 718 | 20 | 353×104 | ARTICLE |  | 16px/400 | 255, 237, 229 | 24px |
| 734 | 36 | 321×20 | SPAN | 6. Sebagian gagal | 14px/600 | a0, 0, 0, 0 | 0px |
| 762 | 36 | 321×18 | P | 9 tersimpan · 3 gagal dibaca | 13px/400 | a0, 0, 0, 0 | 0px |
| 788 | 36 | 321×18 | P | Terakhir berhasil penuh: Kemarin, 21:10 | 13px/400 | a0, 0, 0, 0 | 0px |
| 838 | 20 | 353×178 | ARTICLE |  | 16px/400 | 255, 237, 234 | 24px |
| 854 | 36 | 321×20 | SPAN | 7. Gagal | 14px/600 | a0, 0, 0, 0 | 0px |
| 882 | 36 | 321×36 | P | Percobaan terakhir (Baru saja): Koneksi ke Gmail terputus. | 13px/400 | a0, 0, 0, 0 | 0px |
| 926 | 36 | 321×18 | P | Terakhir berhasil: Hari ini, 09:40 | 13px/400 | a0, 0, 0, 0 | 0px |
| 956 | 36 | 321×44 | A | Coba lagi | 14px/600 | 23, 25, 21 | 16px |