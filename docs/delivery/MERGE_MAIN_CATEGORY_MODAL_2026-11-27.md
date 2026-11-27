# Merge redesign Stitch + modal kategori ke `main` — 27 November 2026

Pemilik memberikan izin merge/deploy pada 27 November 2026 (“saya izinkan”). Tindakan yang
dilakukan hanya pada repo Android; backend dan kontrak API tidak diubah.

## Merge

| Item | Nilai |
| --- | --- |
| Pull request | [#1](https://github.com/NDI05/0037_FinancialMobile/pull/1) — `feature/stitch-redesign` → `main` |
| Waktu merge | 2026-11-27 13:18 WIB (06:18 UTC) |
| Merge commit | `e1498ec646f92d194024af50ce5485c34d13942b` |
| Metode | merge commit (bukan squash/force), 16 commit fitur dipertahankan |
| Branch setelah merge | `feature/stitch-redesign` dihapus dari lokal dan `origin`; tersisa `main` dan `develop` lama |
| Proteksi `main` | tanpa force-push/delete; tidak ada required review/status check yang dilanggar |

Isi merge mencakup seluruh redesign Stitch (Ringkasan, Transaksi, Analisis, Pengaturan, Kategori,
Gmail, widget/notifikasi, sheet status) dan perbaikan terakhir modal Tambah/Ubah kategori
`cc00ba6`.

## Gate yang berlaku

Dijalankan pada tree fitur sebelum merge, dan tree `main` hasil merge identik:

- `:app:testDebugUnitTest` — **646 tes / 0 gagal / 0 error**.
- `:app:assembleDebug` — **PASS**.
- `:app:lintDebug` — **0 error / 265 warning / 1 hint**.
- Bukti visual final: [`category_modal`](../evidence/stitch_redesign_2026-11-25/after/category_modal/)
  dan [CATEGORY_MODAL_STITCH_2026-11-27](../evidence/CATEGORY_MODAL_STITCH_2026-11-27.md).

## Deployment

Repo Android **tidak memiliki pipeline distribusi** (tidak ada signing config release, Play
Console service account, Firebase App Distribution, atau workflow CI). Karena itu deploy produksi
ke Play Store tidak dapat dijalankan dari repo ini dan tidak diklaim.

Yang benar-benar dijalankan sebagai deploy lingkungan uji:

- Build dari `main` pasca-merge: `:app:assembleDebug` **PASS**.
- APK debug: `app/build/outputs/apk/debug/app-debug.apk`, **50.901.935 byte**, sha256
  `30638e1050775e3248fb47c686ea9f278fbe93f1d0fde8240f17199d3fe8fd94`.
- Dipasang ulang ke emulator `emulator-5554` (Android 17/API 37, 393 dp), aplikasi hidup
  (pid terverifikasi), lalu modal kategori dibuka: teks “Tambah kategori / Opsi lanjutan / Tutup /
  Nama kategori... / Simpan” tampil dan scrim terukur `(178,178,178)` pada latar putih — sesuai
  target 30%, bukan scrim platform 60%.

Deployment backend tidak dijalankan karena tidak ada perubahan backend pada merge ini. Status
produksi Android tetap **NOT RUN** sampai pemilik menyediakan kanal rilis/signing.
