# Delivery paralel — papan koordinasi

Tanggal mulai: **23 November 2026**. Basis integrasi: `feat/c2-android`, commit `296d37d`.
Mandat: [PROMPT_SE_PARALLEL_DELIVERY](../agent-prompts/PROMPT_SE_PARALLEL_DELIVERY.md).
SRS resmi tetap v0.5 dan tidak disentuh oleh worker mana pun.

## Worker nyata yang berjalan

| Worker | Worktree | Branch | Scope pertama | Status |
|---|---|---|---|---|
| A — sync/recovery | `../pam-wt-sync` | `work/se-sync-recovery` | P1 pending upload lintas run, journal durable, regresi 6 kasus | berjalan |
| B — Go/API | `../pam-wt-go` | `work/se-go-delivery` | pagination/search >100, budget/analysis, lampiran & admin S4 | berjalan |
| C — Android UI | `../pam-wt-android` | `work/se-android-delivery` | S2.4 pagination/mutasi, lalu S2.5 budget/freshness | berjalan |
| Koordinator | checkout utama | `feat/c2-android` | kontrak bersama, integrasi, QA, dokumentasi | berjalan |

Worktree berada di direktori sibling, bukan di dalam checkout utama. `.toolchain` dan
`local.properties` ditautkan dari checkout utama agar build tetap reproducible.

## Kepemilikan file

| Area | Pemilik | Catatan |
|---|---|---|
| `domain/usecase/AutoSyncUseCases.kt`, `ServerImportUploader.kt`, `data/security/ImportUploadJournal.kt`, DAO/repository import, tes sync | A | tidak boleh menambah migrasi backend |
| `backend-go/**` | B | nomor migrasi berikutnya 015+ |
| `ui/feature/transactions/**` (server pagination + mutasi), `ui/feature/budget/**`, string fitur terkait | C | cherry-pick kontrak `296d37d` lebih dulu |
| `PamApiClient.kt`, `ServerFinanceRepository.kt`, `ServerFinanceRepositoryImpl.kt`, DI, navigasi, Gradle, README/checkpoint | Koordinator | perubahan worker lewat patch yang dilaporkan |
| Dokumentasi harian, matriks acceptance, integrasi akhir | Koordinator | — |

## Aturan integrasi

1. Satu penulis per file; worker lain mengirim patch konkret, bukan mengedit bersamaan.
2. Build Gradle berat diserialkan dengan `/tmp/pam_build_lock.sh`; emulator, backend UAT dan
   database pemilik hanya dijalankan koordinator.
3. Worker push ke branch masing-masing (`git push -u origin <branch>`), tanpa merge ke
   `develop`/`main`. Koordinator memilih commit dan cherry-pick ke `feat/c2-android`.
4. Patch diterima hanya bila suite terkait hijau dan bukti ditulis di laporan worker; klaim
   tanpa run fresh dianggap belum ada.
5. Setelah P1 lulus, slot A dialihkan ke QA integrasi atau slice S3/S4 independen dengan
   ownership yang dinyatakan ulang.

## Urutan slice

1. P1 lintas run (A) — menghalangi penutupan S2.
2. S2.4 server pagination/search + mutasi (B kontrak, C UI).
3. S2.5 budget/analysis remote + freshness (B, C).
4. S4 backend lampiran/admin (B) → layar lampiran/admin (C atau A setelah P1).
5. S3 C01–C15 (kategori/anggaran/dynamic fields) setelah kontrak API tersedia.
6. QA integrasi, E2E emulator, matriks C01–C25 dan laporan akhir (koordinator).
