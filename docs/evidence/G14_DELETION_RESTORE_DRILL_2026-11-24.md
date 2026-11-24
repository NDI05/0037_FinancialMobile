# G14 — Drill Retensi Hapus & Restore (Fixture Sintetis) — 2026-11-25

- **Hasil drill fixture:** `PASS` (60/60 check PASS)
- **Basis kode:** backend commit `00bb515` (build terisolasi dari `git archive`, tanpa menulis ke repo)
- **Lingkungan:** SQLite fixture di `/tmp`, port terpisah, data 100% sintetis; DB pemilik (`~/.config/pam/pam-gmail-uat.db`) tidak dibaca/disentuh
- **Catatan scope:** ini drill fixture, bukan drill data pemilik. Gate G14 untuk data pemilik tetap **NOT RUN** sampai drill dijalankan pada salinan data yang disetujui pemilik.

## 1. Lingkungan dan konfigurasi

| Item | Nilai |
| --- | --- |
| OS | macOS 27.3 |
| Go | 1.27.4 (toolchain repo, dipakai lewat `PAM_GOROOT`) |
| Binary | `/tmp/pam-g14/bin/pam-api` (sha256 `829ff015ee3cd4a718586c0a5d3ff8b85b488e4813e33e812599d823cd9f11f0`) |
| DB fixture | `/tmp/pam-g14/drill.db` (dihapus setelah drill) |
| Port | 18091 (fase seed/delete), 18092 (fase baca hasil restore) |
| Env kunci | `DATABASE_URL`, `PORT`, `INTERNAL_SWEEP_TOKEN`, `RETENTION_DELETE_ENABLED`, `RETENTION_SWEEP_INTERVAL_HOURS=168`, `JWT_SECRET`, `REFRESH_PEPPER`, `DATA_ENCRYPTION_KEY`, `REGISTRATION_INVITE_CODE` |
| Rahasia | dibuat acak saat runtime, tidak pernah ditulis ke disk/laporan |
| Migrasi | dijalankan otomatis oleh `store.Open` saat server start (embed Go) |

## 2. Langkah eksekusi

| Waktu (WIB) | Langkah | Detail |
| --- | --- | --- |
| 2026-11-24T22:02:42+07:00 | serve_A | server started with RETENTION_DELETE_ENABLED=false (seed phase) |
| 2026-11-24T22:02:42+07:00 | serve_B | server restarted with RETENTION_DELETE_ENABLED=true (delete phase) |
| 2026-11-24T22:02:42+07:00 | boot_sweep | mode=DELETE eligible=3 (expired seed batch removed by the scheduled worker) |
| 2026-11-24T22:02:42+07:00 | serve_C | server started on the restored copy (read-only proof) |

## 3. Hasil sweep

| Sweep | Mode | Eligible | tx | att | deleted |
| --- | --- | --- | --- | --- | --- |
| Scheduled worker (boot) | DELETE | 3 | 2 | 1 | ya |
| Manual report-only | REPORT_ONLY | 4 | 3 | 1 | tidak |
| Manual delete | DELETE | 4 | 3 | 1 | ya |

Jendela retensi WIB dari respons API: `2025-11-24T00:00:00+07:00` sampai `2026-11-24T22:02:42.233242+07:00`.

## 4. Verifikasi baris

| Asersi | Status |
| --- | --- |
| binary_present | PASS |
| readyz_A | PASS |
| retention_unauthenticated | PASS |
| sweep_rejects_wrong_token | PASS |
| register_fixture_owner | PASS |
| login_fixture_owner | PASS |
| me | PASS |
| default_categories_seeded | PASS |
| retention_info | PASS |
| create_T1 | PASS |
| create_T2 | PASS |
| create_T3 | PASS |
| create_T4 | PASS |
| create_T5 | PASS |
| create_T6 | PASS |
| create_attachment_A1 | PASS |
| create_attachment_A2 | PASS |
| serverA_stopped | PASS |
| seed_tx_count | PASS |
| seed_attachment_count | PASS |
| backup_created[backup_pre.db] | PASS |
| backup_pre_integrity | PASS |
| backup_pre_rows | PASS |
| scheduled_worker_audit_row | PASS |
| relogin_on_phase_B | PASS |
| expired_seed_deleted | PASS |
| boundary_and_window_kept | PASS |
| expired_attachment_deleted | PASS |
| retained_attachment_kept | PASS |
| create_batch2_1 | PASS |
| create_batch2_2 | PASS |
| create_batch2_3 | PASS |
| create_batch2_attachment | PASS |
| manual_report_sweep | PASS |
| report_sweep_does_not_delete | PASS |
| batch2_still_present_after_report | PASS |
| manual_delete_sweep | PASS |
| delete_sweep_counts | PASS |
| batch2_deleted | PASS |
| batch2_attachment_deleted | PASS |
| retained_rows_after_delete | PASS |
| no_expired_rows_left | PASS |
| audit_rows_complete | PASS |
| audit_manual_rows | PASS |
| serverB_stopped | PASS |
| backup_created[backup_post.db] | PASS |
| backup_post_integrity | PASS |
| backup_post_rows | PASS |
| restore_post_integrity | PASS |
| restore_post_matches_backup | PASS |
| restore_pre_integrity | PASS |
| restore_pre_rows_returned | PASS |
| login_on_restored_db | PASS |
| me_on_restored_db | PASS |
| retention_info_on_restored_db | PASS |
| retained_rows_readable_on_restored_db | PASS |
| retained_transaction_readable | PASS |
| deleted_transaction_unreadable | PASS |
| retained_attachment_readable | PASS |
| serverC_stopped | PASS |

Data seed (sintetis): 6 transaksi + 2 lampiran sebelum sweep; 2 transaksi (T5 = 1 detik sebelum batas jendela, T6 = 400 hari sebelum batas) dan lampiran A2 kedaluwarsa. T4 tepat pada `windowStart` dan tetap dipertahankan. Batch kedua (3 transaksi + 1 lampiran) disuntikkan setelah boot untuk menguji endpoint manual report-only lalu delete: report-only tidak menghapus, delete hanya menghapus baris kedaluwarsa.

## 5. Audit `retention_sweeps`

| # | Mode | Status | counts (tx/att/total) | windowStart | completedAt |
| --- | --- | --- | --- | --- | --- |
| 1 | DELETE | COMPLETED | 2/1/3 | 2025-11-23T17:00:00Z | 2026-11-24T15:02:42.301935Z |
| 2 | REPORT_ONLY | COMPLETED | 3/1/4 | 2025-11-23T17:00:00Z | 2026-11-24T15:02:42.576767Z |
| 3 | DELETE | COMPLETED | 3/1/4 | 2025-11-23T17:00:00Z | 2026-11-24T15:02:42.579162Z |

## 6. Backup dan restore

| Langkah | Berkas | sha256 | integrity | Baris (tx/att) |
| --- | --- | --- | --- | --- |
| Backup sebelum delete | `/tmp/pam-g14/backup_pre.db` | `03d05e04cdec5ea6f3680d0aae6454a68a36decc3a8e56588d573bd70f7a29c9` | ok | 6/2 |
| Backup setelah delete (server berhenti) | `/tmp/pam-g14/backup_post.db` | `2bda86e570c6f70a98d4f448e7ab876c4108a1a46ed0f385ec4aa140d569df73` | ok | 4/1 |
| Restore salinan post-delete (dibaca server C) | `/tmp/pam-g14/restore_post.db` | `2bda86e570c6f70a98d4f448e7ab876c4108a1a46ed0f385ec4aa140d569df73` | ok | 4/1 |
| Restore salinan pre-delete | `/tmp/pam-g14/restore_pre.db` | `03d05e04cdec5ea6f3680d0aae6454a68a36decc3a8e56588d573bd70f7a29c9` | ok | 6/2 |

Server C dijalankan di atas `restore_post.db` (salinan backup hasil restore): login berhasil, `GET /v1/me` 200, `GET /v1/retention` 200 dengan window sama, transaksi/lampiran yang dipertahankan terbaca, dan transaksi yang dihapus mengembalikan 404. Salinan pre-delete membuktikan baris yang dihapus dapat dikembalikan dari backup (6 transaksi + 2 lampiran kembali).

## 7. Housekeeping

- Dua server drill dihentikan dengan SIGTERM (exit code 0); port 18091/18092 dibebaskan.
- DB fixture, backup, salinan restore, log server, dan material rahasia dihapus dari `/tmp/pam-g14`.
- Repo tidak diubah: build memakai arsip commit `00bb515`, tanpa write/commit ke repo mana pun.

## 8. Kesimpulan

- Endpoint internal sweep menegakkan gate: token salah → 401; report-only default tidak menghapus; delete butuh `RETENTION_DELETE_ENABLED=true` + `?confirm=RETENTION_DELETE`.
- Hanya baris di luar jendela 12 bulan WIB yang terhapus; baris tepat pada `windowStart` dan transaksi/lampiran dalam jendela dipertahankan.
- Audit `retention_sweeps` mencatat mode, status COMPLETED, dan hitungan agregat yang cocok dengan data seed.
- Backup/restore SQLite utuh (`integrity_check=ok`, `foreign_key_check` bersih), baris yang dihapus kembali di salinan pre-delete, dan server membaca DB hasil restore tanpa error.
- **Gate penuh G14 untuk data pemilik masih NOT RUN** (drill ini fixture sintetis).

