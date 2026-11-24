# Laporan gate akhir — Gmail API, workflow setahun, retensi, pemisahan backend (24 November 2026)

Dokumen ini menjawab mandat PM
[PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW](../agent-prompts/PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW.md).
Isinya: apa yang sudah diimplementasikan, bukti yang benar-benar dijalankan, status per gate,
dan sisa pekerjaan yang hanya bisa ditutup pemilik. Tidak ada persentase penyelesaian: setiap
status menunjuk bukti atau alasan NOT RUN.

## 1. Pasangan build yang diverifikasi

| Komponen | Repo | Branch | HEAD | Status `main` |
|---|---|---|---|---|
| Android | [`NDI05/0037_FinancialMobile`](https://github.com/NDI05/0037_FinancialMobile) (publik) | `feat/c2-android` | `19e6860` | `c10ca67`, dilindungi (tanpa force-push/delete) |
| Backend | [`NDI05/0037-Backend-Financial`](https://github.com/NDI05/0037-Backend-Financial) (privat) | `integration/2026-11-24` | `40c4c31` (kode `f1625b5`) | `327d876`, belum diubah |

Commit kode Android pada laporan ini adalah `19e6860` (APK yang diuji); sesudahnya hanya
commit dokumentasi/evidence (HEAD branch bergerak tanpa mengubah APK).

Kedua repo punya Git root sendiri; tidak ada subtree/submodule. Android dibangun dari clone repo
Android saja (tidak ada source server di tree: `git ls-files | grep '^backend-go/'` = 0).
Backend dijalankan dari clone repo backend dengan `./scripts/go.sh` (toolchain Go di
`pam-app/.toolchain` tidak ikut repo backend). URL build:
- Android debug APK dari `./gradlew assembleDebug` (dipasang di emulator 5556 untuk UAT).
- Backend `GET /healthz` dan `/readyz` 200, DB baru 19 migrasi (`1..19`).

Lane branch yang menjadi asal perubahan (semua sudah di-merge ke branch integrasi di atas):

| Lane | Branch | HEAD | Isi |
|---|---|---|---|
| Gmail API backend | `feat/gmail-api` | `7376749` | OAuth, push OIDC, watch, backfill/delta, dedupe |
| Retensi backend | `feat/retention` | `a05994c` | jendela setahun, guard, sweep + audit |
| Integrasi | `integration/2026-11-24` | `f1625b5` | gabungan + perbaikan P2 + dokumen |
| Gmail UI Android | `feat/gmail-ui` | `f1e3e16` | layar tautkan, status, putuskan, deep link |
| Retensi UI Android | `feat/retention-ui` | `5eb25e3`,`a3e3e6e` | jendela setahun, label status, sheet |
| Audit UI | `fix/gmail-ui-audit` | `305956d`/`6ca30d9` | dedupe deep link, state 503 |

Semua branch lane di tabel ini sudah di-merge dan **dihapus** dari remote setelah bukti masuk
ke `feat/c2-android`/`integration/2026-11-24`. Commit SHA tetap dapat diperiksa.

## 2. Tabel gate

Legenda: **PASS** = bukti dijalankan pada pasangan build final · **PARTIAL** = sebagian bukti
ada, sisanya tercantum di kolom Sisa · **FAIL** = diuji dan gagal · **NOT RUN** = belum ada
lingkungan/kredensial atau sengaja menunggu pemilik.

| Gate | Implementasi | Bukti | Status | Sisa |
|---|---|---|---|---|
| G1. Pemisahan repository | Backend Go keluar dari Android ke `0037-Backend-Financial`; fixture interop, `scripts/go.sh`, `.env.example`, CI vet+test+build; `backend-go/`, Node `backend/` keluar dari tree Android | [UAT_BACKEND_SPLIT](../evidence/UAT_BACKEND_SPLIT_2026-11-24.md): clone bersih backend 167 tes PASS, `assembleDebug --rerun-tasks` rc=0, dua URL, DB 16→19 migrasi, smoke login sintetis + 2 transaksi; [audit split](../evidence/AUDIT_BACKEND_SPLIT_2026-11-24.md) tanpa P0 | **PASS** | — |
| G2. Parity API Android↔Go | Klien memakai kontrak backend final; artefak interop | [UAT_BACKEND_SPLIT](../evidence/UAT_BACKEND_SPLIT_2026-11-24.md) P1–P7, P8 PARTIAL; gate Android final `debug **601 tes/80 kelas/0 gagal**, assessment **611/81/0 gagal**, `lintDebug` **0 error/116 warning**, `lintAssessment` **0 error/124 warning**, `assembleDebug` + `assembleAssessment` PASS, APK sha256 debug `e5cba1dc…`, assessment `8f3254c7…`` | **PASS** (kontrak), P8 di G3 | — |
| G3. Rollback ke baseline | Prosedur rollback backend documented ([BACKEND_SPLIT](BACKEND_SPLIT_2026-11-24.md) §Rollback); `main` backend utuh `327d876` | Tidak ada restore DB/rollback nyata; hanya baseline repo + tag | **PARTIAL** | Drill rollback pada DB salinan + satu pair build lama dijalankan pemilik |
| G4. Lindung `main` | Proteksi tanpa force-push/delete. Android: rule aktif di GitHub. Backend privat di paket gratis: API branch protection tidak tersedia; disiplin merge lewat branch integrasi + review | `gh api repos/NDI05/0037_FinancialMobile/branches/main/protection` → `allow_force_pushes=false`, `allow_deletions=false`; backend 403 "Upgrade to GitHub Pro" | **PARTIAL** | Backend: upgrade plan atau proteksi manual oleh owner |
| G4b. Kebersihan branch/worktree | 3 worktree lane lama dihapus, branch lokal dan remote lane dihapus setelah merge; hanya branch utama tersisa | `git worktree list` = 2 tree; remote Android `main`/`develop`/`feat/c2-android`; backend `main`/`integration/2026-11-24` | **PASS** | — |
| G5. OAuth Google dari sesi PAM | `connect/start` (state single-use + PKCE, TTL), callback → deep link `pam://gmail/connected`, satu koneksi aktif/pemilik, `gmail.readonly` | `internal/gmail/*_test.go` + `handlers_gmail_test.go`; runtime: layar "Tautkan Gmail lewat browser", status `gmail_disabled` jujur ([ui_integrated/07](../evidence/ui_integrated/07_gmail_disabled.png)); kontrak di `docs/API_CONTRACT.md` §18 | **PASS** (kontrak + fake) | Consent screen, client ID/secret, restricted-scope verification/CASA = owner |
| G6. Refresh token & isolasi | Refresh token terenkripsi (`DATA_ENCRYPTION_KEY`), tidak pernah ke klien; disconnect mencabut token & menghentikan watch | Tes enkripsi/isolasi owner A/B, `TestGmail*` hijau; audit Gmail API tanpa P0 | **PASS** (unit) | Rotasi kunci nyata + audit log akses secret = owner ops |
| G7. Pub/Sub push | `POST /v1/internal/gmail/push`, verifikasi OIDC JWT (audience/issuer/email), ACK durable setelah commit, dedupe push ganda, `users.watch` + renew/stop | Tes push: JWT valid/invalid/expired, replay dedupe, dua push bersamaan, NEEDS_REAUTH | **PASS** (fake OIDC) | Topik Pub/Sub + service account grant + endpoint HTTPS = owner |
| G8. Backfill setahun WIB | `BACKFILL_MONTHS=12`, rolling window, bulan batas parsial, `MAX_PAGES_PER_RUN`/`PAGE_SIZE` | `internal/gmail` tes backfill (12 bulan, bulan batas), migrasi 016–019; dump UI "Riwayat yang bisa diakses: November 2025 sampai November 2026" | **PASS** (sintetis) | Backfill akun Gmail nyata = owner |
| G9. Delta & cursor recovery | `history.list`, cursor per pemilik, job DELTA unik (migrasi 019), recovery saat cursor kedaluwarsa (`NEEDS_REAUTH`/re-watch) | Tes delta, push+drain, cursor expiry recovery; migrasi 019 membersihkan job ganda | **PASS** (sintetis) | Latensi end-to-end dan push saat app tertutup = owner |
| G10. Dedupe IMAP→Gmail | Identitas server-derived, migrasi identitas IMAP lama, satu transaksi per email | Tes dedupe/parity identitas; migrasi 016 | **PASS** (sintetis) | Perbandingan pada data owner nyata = owner |
| G11. Job kategorisasi otomatis | Satu job logis per pemilik, 5 merchant/permintaan, maks 4 batch/2 jam, target 2 jam, consent + budget/override, cache | Tes plan/dispatch/budget/consent + drain; backend `test ./...` hijau | **PASS** (unit/integration) | Satu job riil dengan data owner + biaya Azure nyata = owner |
| G12. Retensi setahun — kebijakan | Jendela 12 bulan WIB, clamp bulan, guard 400 `outside_retention` di semua path periodik, FR-42 budget dikecualikan | `internal/retention` + `httpapi/retention_guard_test.go`; **[runtime smoke HEAD final](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/evidence/FINAL_RUNTIME_SMOKE_2026-11-24.md)**: lampiran by-id/import-job kedaluwarsa 400, list tanpa filter `items=0`, sweep report-only/delete 200–403 sesuai gate | **PASS** (integrasi + runtime) | — |
| G13. Retensi — audit & kegagalan | Baris audit `COMPLETED` satu transaksi dengan delete; baris `FAILED` best-effort tanpa PII saat abort | Tes sweep failure → baris FAILED; `f1625b5`; skema 018; runtime smoke: audit `REPORT_ONLY`/`DELETE` per run selesai | **PASS** | — |
| G14. Retensi — hapus data nyata | Sweep `DELETE` di balik `RETENTION_DELETE_ENABLED` + `INTERNAL_SWEEP_TOKEN` + konfirmasi | Hanya DB `/tmp`; UAT flags off; restore/rollback NOT RUN | **NOT RUN** (owner) | Salinan DB owner + drill hapus + restore; keputusan flags |
| G15. UI retensi + label ringkas | Bulan batas "sebagian", panah dibatasi jendela, sheet status ringkas, rentang retensi | [UAT_INTEGRATED](../evidence/UAT_INTEGRATED_2026-11-24.md) butir 4–6; [RETENTION_UI](../evidence/RETENTION_UI_2026-11-24.md) | **PASS** (runtime) | Font 200%/TalkBack di HEAD final = NOT RUN ulang (lihat G19) |
| G16. UI utama Bahasa Indonesia ringkas | Ringkasan, Transaksi, Analisis, Pengaturan, sheet status satu ikon; entri IMAP lama dihapus; label cakupan netral | Dump/screenshot `docs/evidence/ui_integrated/01–07`; tes Compose screenshot 20 kelas | **PASS** (runtime) | — |
| G17. Retire IMAP pada build target | UI entri lama dihapus; kode IMAP source/vault tetap ada untuk rollback (belum dipensiunkan penuh) | Commit `19e6860` (SettingsScreen, PamNavHost, string); grep 14 file IMAP tersisa | **PARTIAL** | Hapus dependency/scheduler IMAP setelah rollback teruji + persetujuan owner |
| G18. Aksesibilitas setahun | Target 48 dp, satu node TalkBack per kontrol, font 200%, 360/393/412 dp | [UAT_ACCESSIBILITY](../evidence/UAT_ACCESSIBILITY_2026-11-24.md); ditambah cek runtime APK final pada `font_scale=2.0` + 360 dp: Ringkasan/Transaksi merender data tanpa crash (`ui_integrated/09`,`10`) | **PARTIAL** | Walk TalkBack manual + 393/412 dp di APK final |
| G19. Pengujian Android | Unit debug + assessment, lint, assemble; 561/562/… tes per lane | `debug **601 tes/80 kelas/0 gagal**, assessment **611/81/0 gagal**, `lintDebug` **0 error/116 warning**, `lintAssessment` **0 error/124 warning**, `assembleDebug` + `assembleAssessment` PASS, APK sha256 debug `e5cba1dc…`, assessment `8f3254c7…``; APK sha256 di log gate | **PASS** | — |
| G20. Pengujian backend | `go vet`, `go test ./... -count=1`, `go build ./...`; 19 migrasi DB baru | ``go vet` + `go build` bersih; `go test ./... -count=1` **10 paket uji, 183 test top-level, 0 gagal**; DB baru 19 migrasi; smoke `/healthz`/`/readyz` 200`; log smoke `/tmp/pam-int-*` | **PASS** | — |
| G21. UAT pasangan build | Emulator + server terisolasi (port 18097, DB `/tmp`, reverse 18090) | [UAT_INTEGRATED](../evidence/UAT_INTEGRATED_2026-11-24.md) butir 1–8 | **PASS** (runtime sintetis) | — |
| G22. Audit independen | Dua audit terpisah: split dan Gmail API/retensi | [AUDIT_BACKEND_SPLIT](../evidence/AUDIT_BACKEND_SPLIT_2026-11-24.md), [AUDIT_GMAIL_API](../evidence/AUDIT_GMAIL_API_2026-11-24.md), [AUDIT_RETENTION](../evidence/AUDIT_RETENTION_INTEGRATION_2026-11-24.md); P0=0, P1=0; P2 ditutup `f1625b5`/`6ca30d9` | **PASS** | — |
| G23. Dokumentasi delivery | CR_007, checkpoints, matriks, README, PROGRESS_DAILY, kontrak API, panduan rollback | Commit dokumen ini; [CR_007](../changes/CR_007_DRAFT_GMAIL_API_YEARLY_WORKFLOW.md); [API_CONTRACT](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/API_CONTRACT.md) | **PASS** | SRS resmi direvisi owner setelah keputusan |
| G24. Merge/deploy/SRS resmi | — | Sengaja tidak dilakukan: menunggu persetujuan pemilik | **NOT RUN** (owner) | Keputusan owner atas CR-005/006/007 lalu merge `main`, deploy, revisi SRS |
| G25. Gmail live E2E | — | Tidak ada kredensial Google di lingkungan ini | **NOT RUN** (owner) | Consent screen + test users, client ID/secret, topik Pub/Sub + grant, endpoint HTTPS, email Livin nyata, ukur latensi |

## 3. Bukti kunci (jalur file)

- Split: `docs/evidence/UAT_BACKEND_SPLIT_2026-11-24.md`, `AUDIT_BACKEND_SPLIT_2026-11-24.md`,
  `docs/delivery/BACKEND_SPLIT_2026-11-24.md` (termasuk rollback dan URL).
- Gmail API: `0037-Backend-Financial/docs/GMAIL_API.md` (kontrak implementasi),
  [GMAIL_API_CONTRACT](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/GMAIL_API_CONTRACT.md), `AUDIT_GMAIL_API_2026-11-24.md`,
  `GMAIL_UI_2026-11-24.md`.
- Retensi: `0037-Backend-Financial/docs/RETENTION_POLICY_IMPLEMENTATION.md`,
  `AUDIT_RETENTION_INTEGRATION_2026-11-24.md`, `RETENTION_UI_2026-11-24.md`.
- UAT pasangan build: `docs/evidence/UAT_INTEGRATED_2026-11-24.md` + `ui_integrated/01–07`.
- Audit final: `AUDIT_GMAIL_API_2026-11-24.md`, `AUDIT_RETENTION_INTEGRATION_2026-11-24.md`.

## 4. Keputusan yang menunggu pemilik

1. Setujui CR-005 (retensi setahun), CR-006 (Gmail API), CR-007 (workflow tahunan + UI ringkas).
2. Sediakan Google Cloud project: OAuth client, consent screen + test users, Gmail API,
   Pub/Sub topic + publisher grant, endpoint HTTPS publik, lalu jalankan gate G5–G10, G25.
3. Jadwalkan drill rollback (G3) dan drill hapus/restore retensi (G14) pada salinan DB.
4. Putuskan penonaktifan penuh jalur IMAP (G17) setelah rollback teruji.
5. Merge ke `main`/deploy dan revisi SRS resmi hanya setelah 1–4 selesai.
