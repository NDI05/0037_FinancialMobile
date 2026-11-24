# Laporan gate akhir — Gmail API, workflow setahun, retensi, pemisahan backend (24 November 2026)

Dokumen ini menjawab mandat PM
[PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW](../agent-prompts/PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW.md).
Isinya: apa yang sudah diimplementasikan, bukti yang benar-benar dijalankan, status per gate,
dan sisa pekerjaan yang hanya bisa ditutup pemilik. Tidak ada persentase penyelesaian: setiap
status menunjuk bukti atau alasan NOT RUN.

Revisi 24 November 2026 (ronde 4): desain transport berubah dari **Pub/Sub push sebagai default**
menjadi **polling 15 menit sebagai default** dengan **push + `users.watch` + Pub/Sub hanya opt-in
rollback**; sinkron manual masuk antrean job Go durable; recovery `history.list` 404 diperbaiki;
status UI tidak lagi nyangkut "Menghubungkan". Tabel gate di bawah sudah memakai hasil fresh
Android/backend serta UAT perangkat FIXTURE.

Revisi 24 November 2026 (ronde 5): **verifikasi ulang gate teknis** pada HEAD dokumen
`pam-app@42823e8` / backend `240ecd7` (kode aplikasi tetap `e67380a`/`71013f5`, sesudahnya hanya
dokumen + skrip verifikasi). Hasil fresh: backend `go vet ./...`, `go build ./...`,
`go test ./... -count=1`, dan `go test -race auth/gmail/httpapi/store` **PASS**; Android
`testDebugUnitTest` **678 tes/88 suite/0 gagal**, `lintDebug` **0 error/123 warning/1 hint**,
`assembleDebug` **PASS** (APK 47.992.939 B, sha256 `8ce4763c…`). Status gate di bawah **tidak
berubah**: G25/G29/G36 tetap NOT RUN/open menunggu input pemilik.

Revisi 24 November 2026 (ronde 6): **UAT live pertama pemilik** dijalankan pada backend resmi
`127.0.0.1:18090` dengan kredensial OAuth nyata. Backfill pertama memindai ratusan email tetapi
**mengimpor 0 transaksi** karena templat produksi Livin memakai label **stacked** (`Penerima` di
baris sendiri, nilai di baris berikutnya) dan tanggal+waktu bertumpuk dengan nama bulan Indonesia
(`12 Okt 2026` + `pkl 09:30:45 WIB`); parser hanya mengenal bentuk fixture satu baris. Backend
diperbaiki lewat `5df6be6` (label/waktu stacked) dan `c98527b` (bulan Indonesia), diuji ulang
(`go vet/build/test ./...` + `-race` **PASS**), lalu runtime resmi dibangun ulang dan dijalankan
ulang. Hasil live: **transaksi Livin nyata masuk tanpa duplikat** (Nov 40/54, Okt 53/86, Agu
55/124; final **12/12 bulan COMPLETE dengan 914 baris unik, 0 duplikat** pada 20:16 WIB) —
rincian agregat di
[GMAIL_LIVE_IMPORT](../evidence/GMAIL_LIVE_IMPORT_2026-11-24.md). **G25 (Gmail live) berubah
menjadi PASS (live, akun pilot owner)**; G29/G37 (Rp0) tetap OPEN dan **G36 (SSO Google + tombol
Run pemilik) tetap NOT RUN** karena login Google nyata belum dicoba. Gate Gmail live dan SSO
dilaporkan terpisah sesuai mandat.

Revisi 24 November 2026 (ronde 7): **verifikasi ulang gate teknis** setelah UAT live, dengan
`local.properties` pemilik aktif (Web OAuth client ID terisi). Hasil fresh: backend `vet`/`build`/
`test ./... -count=1` (10 paket) dan `-race` auth/gmail/httpapi/store **PASS**; Android
`testDebugUnitTest` **678 tes/88 suite/0 gagal/0 dilewati**, `lintDebug` **0 error/123 warning**,
`assembleDebug` **PASS** (APK 47.992.939 B, sha256 `5368c92f…`). Satu tes diperbaiki:
`GoogleSsoAccountSourceTest` mengasumsikan checkout tanpa client ID sehingga gagal di mesin UAT;
kini tes memverifikasi pemetaan `PAM_GOOGLE_SSO_CLIENT_ID` → `PAM_GOOGLE_SSO_ENABLED`. Status gate
**tidak berubah**: G25 PASS (live, akun pilot owner), G29/G36/G37 NOT RUN/OPEN atas pemilik.

## 1. Pasangan build yang diverifikasi

| Komponen | Repo | Branch | HEAD | Status `main` |
|---|---|---|---|---|
| Android | [`NDI05/0037_FinancialMobile`](https://github.com/NDI05/0037_FinancialMobile) (publik) | `feat/c2-android` | `e67380a` (kode final + tindak lanjut P2 audit SSO; sesudahnya hanya commit dokumen/evidence) | `c10ca67`, dilindungi (tanpa force-push/delete) |
| Backend | [`NDI05/0037-Backend-Financial`](https://github.com/NDI05/0037-Backend-Financial) (privat) | `integration/2026-11-24` | `71013f5` (kode + perbaikan audit SSO ronde 1; sesudahnya hanya dokumen) | `327d876`, belum diubah |

Verifikasi fresh pada pasangan ini (24 November 2026):

| Artefak | Hasil |
|---|---|
| Android `:app:testDebugUnitTest` | **678 tes / 88 suite / 0 gagal** (640 tes lama + 38 tes SSO) |
| Android `:app:lintDebug` | **0 error / 123 warning / 1 hint** |
| Android `:app:assembleDebug` | **PASS**; APK debug **48.362.013 byte**, sha256 `41584bae…` (build integrasi `96337dc`, 24 Nov 2026 18:15 WIB; build lane `e67380a` sebelumnya 47.992.939 byte sha256 `1a4c8ddf…`; rebuild `--rerun-tasks` 47.992.939 byte sha256 `8ce4763c…` — isi setara, byte berbeda karena urutan dex/kompresi ZIP sehingga hash APK tidak reproducible). APK gate dibangun tanpa `PAM_GOOGLE_SSO_CLIENT_ID` (0 kemunculan `googleusercontent.com`) sehingga tombol SSO tersembunyi — bukan bukti UI SSO |
| Backend `vet` + `build` + `test ./... -count=1` | **PASS** semua paket; `-race` pada `internal/auth`, `internal/gmail`, `internal/store`, `internal/httpapi` **PASS** |
| Migrasi DB baru | 21 migrasi (`001`–`021`, termasuk `google_identities` dan nonce SSO) |
| QA E2E kontrak polling §9 (provider fake) | **PASS=82, FAIL=0, exit 0** |
| UAT perangkat fisik FIXTURE | **PASS** alur terkontrol (lihat G28) |
| Gmail live (G25) | **PASS** untuk akun pilot owner (12/12 bulan, 914 unik) — consent Pub/Sub nyata dan **Rp0 (G29/G37)** tetap **NOT RUN/OPEN** |

Kedua repo punya Git root sendiri; tidak ada subtree/submodule. Android dibangun dari clone repo
Android saja (tidak ada source server di tree). Backend dijalankan dari clone repo backend dengan
`./scripts/go.sh` (toolchain Go di `pam-app/.toolchain` tidak ikut repo backend).

Lane branch yang menjadi asal perubahan (semua sudah di-merge/cherry-pick ke branch di atas):

| Lane | Branch | HEAD | Isi |
|---|---|---|---|
| Gmail API backend | `feat/gmail-api` | `7376749` | OAuth, push OIDC, watch, backfill/delta, dedupe (jalur push kini opt-in) |
| Retensi backend | `feat/retention` | `a05994c` | jendela setahun, guard, sweep + audit |
| Integrasi | `integration/2026-11-24` | `1290910` | gabungan + perbaikan audit ronde 1–4 + dokumen |
| Gmail polling backend | `work/gmail-polling` | `f412c50` | polling default, fake provider guard, antrean sync manual, recovery history 404 |
| Gmail QA E2E | `work/gmail-qa-harness` | `cbec95a` | harness black-box + evidence 82/82 |
| Gmail status UI Android | `work/gmail-server-status` | `4b24624` | panel "Sinkron server Gmail", state dari proyeksi server |
| Gmail UI Android | `feat/gmail-ui` | `f1e3e16` | layar tautkan, status, putuskan, deep link |
| Retensi UI Android | `feat/retention-ui` | `5eb25e3`,`a3e3e6e` | jendela setahun, label status, sheet |
| Audit UI | `fix/gmail-ui-audit` | `305956d`/`6ca30d9` | dedupe deep link, state 503 |

Branch lane lama sudah di-merge dan **dihapus** dari remote setelah bukti masuk ke
`feat/c2-android`/`integration/2026-11-24`; commit SHA tetap dapat diperiksa. Branch lane polling
yang masih aktif dipertahankan sampai pemilik menutup merge.

## 2. Tabel gate

Legenda: **PASS** = bukti dijalankan pada pasangan build final · **PARTIAL** = sebagian bukti
ada, sisanya tercantum di kolom Sisa · **FAIL** = diuji dan gagal · **NOT RUN** = belum ada
lingkungan/kredensial atau sengaja menunggu pemilik.

| Gate | Implementasi | Bukti | Status | Sisa |
|---|---|---|---|---|
| G1. Pemisahan repository | Backend Go keluar dari Android ke `0037-Backend-Financial`; fixture interop, `scripts/go.sh`, `.env.example`, CI vet+test+build; `backend-go/`, Node `backend/` keluar dari tree Android | [UAT_BACKEND_SPLIT](../evidence/UAT_BACKEND_SPLIT_2026-11-24.md): clone bersih backend PASS, `assembleDebug --rerun-tasks` rc=0, dua URL, DB migrasi naik, smoke login sintetis; [audit split](../evidence/AUDIT_BACKEND_SPLIT_2026-11-24.md) tanpa P0 | **PASS** | — |
| G2. Parity API Android↔Go | Klien memakai kontrak backend final termasuk status sinkron server; artefak interop | [UAT_BACKEND_SPLIT](../evidence/UAT_BACKEND_SPLIT_2026-11-24.md) P1–P7, P8 PARTIAL; UAT FIXTURE butir 4–6, 10 (panel status server, total server = UI); `docs/API_CONTRACT.md` **§18–19** | **PASS** (kontrak) | P8 di G3 |
| G3. Rollback ke baseline | Prosedur rollback backend documented ([BACKEND_SPLIT](BACKEND_SPLIT_2026-11-24.md) §Rollback); `main` backend utuh `327d876` | Tidak ada restore DB/rollback nyata; hanya baseline repo + tag | **PARTIAL** | Drill rollback pada DB salinan + satu pair build lama dijalankan pemilik |
| G4. Lindung `main` | Proteksi tanpa force-push/delete. Android: rule aktif di GitHub. Backend privat di paket gratis: API branch protection tidak tersedia; disiplin merge lewat branch integrasi + review | `gh api repos/NDI05/0037_FinancialMobile/branches/main/protection` → `allow_force_pushes=false`, `allow_deletions=false`; backend 403 "Upgrade to GitHub Pro" | **PARTIAL** | Backend: upgrade plan atau proteksi manual oleh owner |
| G4b. Kebersihan branch/worktree | Worktree lane lama dihapus, branch lokal dan remote lane dihapus setelah merge; hanya branch utama + lane polling aktif tersisa | `git worktree list`; remote Android `main`/`develop`/`feat/c2-android`; backend `main`/`integration/2026-11-24` | **PASS** | — |
| G5. OAuth Google dari sesi PAM | `POST /v1/gmail/connect/start` (state single-use + PKCE, TTL ≤10 menit) → callback → deep link `pam://gmail/connected`; satu koneksi aktif/pemilik; `gmail.readonly` | Tes `internal/gmail/*_test.go` + `handlers_gmail_test.go`; E2E fake: `202` authorization_url loopback, consent, callback `pam://...success`, status termask tanpa token/message id; UAT FIXTURE butir 2; kontrak §1–2 | **PASS** (kontrak + fake) | Consent screen, client ID/secret, restricted-scope verification/CASA = owner |
| G6. Refresh token & isolasi | Refresh token terenkripsi (`DATA_ENCRYPTION_KEY`), tidak pernah ke klien; disconnect mencabut token (dan menghentikan watch hanya pada mode push) | Tes enkripsi/isolasi owner A/B, `TestGmail*` hijau; audit Gmail API tanpa P0 | **PASS** (unit) | Rotasi kunci nyata + audit log akses secret = owner ops |
| G7. Push opt-in rollback (bukan default) | `POST /v1/internal/gmail/push`, verifikasi OIDC JWT (audience/issuer/email), ACK durable, dedupe push ganda, `users.watch` + renew/stop — **hanya aktif bila `GMAIL_MODE=push` + topik/audience/service account diisi**. Default polling tidak memasang webhook dan tidak punya verifier | Tes push (JWT valid/invalid/expired, replay dedupe, dua push bersamaan, NEEDS_REAUTH) tetap hijau; config guard menolak `push` tanpa topik/audience; E2E default: rute push **404**, `watchCalls=0`, `stopCalls=0` | **PASS** (fake OIDC; opt-in) | Topik Pub/Sub + service account grant + endpoint HTTPS = owner; tidak diperlukan untuk default Rp0 |
| G8. Backfill setahun WIB (bertahap, mengalah pada delta) | `GMAIL_BACKFILL_MONTHS=12`, rolling window WIB, bulan berjalan dulu, halaman per run dibatasi (`GMAIL_MAX_PAGES_PER_RUN`, `GMAIL_PAGE_SIZE`), checkpoint per batch; delta diproses lebih dulu | Tes backfill (`internal/gmail`); E2E fake: `months_done=12/12`, transaksi = fixture tanpa duplikat; UAT FIXTURE butir 3: 12/12, 27 transaksi sintetis, rentang Nov 2025–Nov 2026; migrasi `020` | **PASS** (sintetis + perangkat FIXTURE) | Backfill akun Gmail nyata = owner |
| G9. Delta 15 menit & recovery cursor | Default **polling**: `GMAIL_DELTA_INTERVAL_SECONDS=900`; `history.list` dengan cursor per pemilik, satu job DELTA terbuka; **404 history kedaluwarsa** dipulihkan via baseline cursor + replay (termasuk rollover bulan WIB), tanpa duplikat; delta berjalan selama backend hidup walau app tertutup | Tes delta/cursor expiry; E2E `history_expired`: 404 → `sync_state=idle`, `last_error_code=null`, delta masuk tepat sekali; UAT FIXTURE butir 7–8: app force-stop, delta tersimpan (26→27), tanpa duplikat; migrasi `019`–`020` | **PASS** (sintetis + perangkat FIXTURE) | Latensi end-to-end pada Gmail nyata = owner |
| G10. Dedupe IMAP→Gmail | Identitas server-derived, migrasi identitas IMAP lama, satu transaksi per email | Tes dedupe/parity identitas; migrasi `016`; E2E 31 baris unik/0 duplikat; UAT FIXTURE restart tetap 27 baris | **PASS** (sintetis) | Perbandingan pada data owner nyata = owner |
| G11. Job kategorisasi otomatis | Satu job logis per pemilik, 5 merchant/permintaan, maks 4 batch/2 jam, target 2 jam, consent + budget/override, cache | Tes plan/dispatch/budget/consent + drain; backend `test ./...` hijau | **PASS** (unit/integration) | Satu job riil dengan data owner + biaya Azure nyata = owner |
| G12. Retensi setahun — kebijakan | Jendela 12 bulan WIB, clamp bulan, guard 400 `outside_retention` di semua path periodik, FR-42 budget dikecualikan | `internal/retention` + `httpapi/retention_guard_test.go`; [runtime smoke](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/evidence/FINAL_RUNTIME_SMOKE_2026-11-24.md) | **PASS** (integrasi + runtime) | — |
| G13. Retensi — audit & kegagalan | Baris audit `COMPLETED` satu transaksi dengan delete; baris `FAILED` best-effort tanpa PII saat abort, baik dari endpoint maupun sweep terjadwal | Tes `TestRetentionSweepRecordsFailedAudit` + `TestRetentionWorkerRecordsFailedAudit`; runtime smoke audit `REPORT_ONLY`/`DELETE` | **PASS** | — |
| G14. Retensi — hapus data nyata | Sweep `DELETE` di balik `RETENTION_DELETE_ENABLED` + `INTERNAL_SWEEP_TOKEN` + konfirmasi | Hanya DB `/tmp`; UAT flags off; restore/rollback NOT RUN | **NOT RUN** (owner) | Salinan DB owner + drill hapus + restore; keputusan flags |
| G15. UI retensi + label ringkas | Bulan batas "sebagian", panah dibatasi jendela, sheet status ringkas, rentang retensi | [UAT_INTEGRATED](../evidence/UAT_INTEGRATED_2026-11-24.md) butir 4–6; [RETENTION_UI](../evidence/RETENTION_UI_2026-11-24.md) | **PASS** (runtime) | Font 200%/TalkBack di HEAD final = NOT RUN ulang (lihat G18) |
| G16. UI utama Bahasa Indonesia ringkas | Ringkasan, Transaksi, Analisis, Pengaturan, sheet status satu ikon; entri IMAP lama dihapus; label cakupan netral | Dump/screenshot `docs/evidence/ui_integrated/01–10`; tes Compose screenshot; UAT FIXTURE `status_sheet_final.*` | **PASS** (runtime) | — |
| G17. Retire IMAP pada build target | UI entri lama dihapus; kode IMAP source/vault tetap ada untuk rollback (belum dipensiunkan penuh) | `a7abb14` (SettingsScreen, PamNavHost, string); grep file IMAP tersisa | **PARTIAL** | Hapus dependency/scheduler IMAP setelah rollback teruji + persetujuan owner |
| G18. Aksesibilitas setahun | Target 48 dp, satu node TalkBack per kontrol, font 200%, 360/393/412 dp | [UAT_ACCESSIBILITY](../evidence/UAT_ACCESSIBILITY_2026-11-24.md); cek runtime APK final `font_scale=2.0` + 360 dp (`ui_integrated/09`,`10`) | **PARTIAL** | Walk TalkBack manual + 393/412 dp di APK final |
| G19. Pengujian Android | Unit debug, lint, assemble pada kode final | Fresh 24 Nov ronde 7 (dengan `local.properties` owner): `testDebugUnitTest` **678 tes/88 suite/0 gagal/0 dilewati**, `lintDebug` **0 error/123 warning/1 hint**, `assembleDebug` PASS, APK debug **47.992.939 byte** sha256 `5368c92f…` (build UAT ronde 7; snapshot sebelumnya 46.433.080 byte sha256 `7efc1803…`). Tes `GoogleSsoAccountSourceTest` diperbaiki agar mengikuti client ID yang dikonfigurasi. Varian assessment tidak dijalankan ulang | **PASS** (debug) | — |
| G20. Pengujian backend | `go vet`, `go test ./... -count=1`, `go build ./...`; `-race` paket Gmail/store/httpapi; 20 migrasi | Fresh ronde 7: `4244f4f` vet/build/test semua paket PASS, race auth/gmail/httpapi/store PASS; setelah audit ronde 7, `e3b84b4`/`7bb16f0` (penjaga merchant field + WITA/WIT + alias bulan + tag `gmail-livin-v2`) vet/build/test/race PASS; DB baru **21 migrasi** (`001`–`021`, termasuk `google_identities` + nonce) | **PASS** | — |
| G21. UAT pasangan build | Emulator + server terisolasi (port 18097, DB `/tmp`, reverse 18090) **dan** UAT perangkat fisik FIXTURE (G28) | [UAT_INTEGRATED](../evidence/UAT_INTEGRATED_2026-11-24.md) butir 1–8; [UAT perangkat FIXTURE](../evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md) butir 1–10 | **PASS** (runtime sintetis + perangkat FIXTURE) | Tombol **Run** Android Studio literal **NOT RUN** (otomasi macOS diblokir; instalasi via `adb install -r`) — pemilik dapat mengulang Run sendiri |
| G22. Audit independen | Audit split, Gmail API/retensi, audit akhir retensi, **audit ronde 4** atas polling/status/sinkron manual, **audit ronde 5** re-audit perbaikan ronde 4 + bukti UAT | [AUDIT_BACKEND_SPLIT](../evidence/AUDIT_BACKEND_SPLIT_2026-11-24.md), [AUDIT_GMAIL_API](../evidence/AUDIT_GMAIL_API_2026-11-24.md), [AUDIT_RETENTION](../evidence/AUDIT_RETENTION_INTEGRATION_2026-11-24.md), [AUDIT_FINAL_FIXES](../evidence/AUDIT_FINAL_FIXES_2026-11-24.md); ronde 4 → `addc240` (Android) + `1290910` (backend); [AUDIT round 5](../evidence/AUDIT_GMAIL_POLLING_R5_2026-11-24.md); ronde 5 verdict: MAJOR/P0 nol, siap merge (keputusan pemilik); MINOR F2 ditutup di `7f218ed` + tes, F1/F3/F6 dicatat backlog non-blocking; **[audit ronde 7](../evidence/AUDIT_GMAIL_PARSER_R7_2026-11-24.md)** atas parser live + tes SSO + konsistensi bukti: P0 nol, 2 P1 ditutup (`e3b84b4`, rekonsiliasi dokumen) | **PASS** | — |
| G23. Dokumentasi delivery | CR_007 revisi polling, checkpoints, matriks, README, PROGRESS_DAILY, kontrak API, runbook UAT fixture | Commit dokumen ini; [CR_007](../changes/CR_007_DRAFT_GMAIL_API_YEARLY_WORKFLOW.md); [API_CONTRACT](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/API_CONTRACT.md); [GMAIL_API_SETUP](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/GMAIL_API_SETUP.md) | **PASS** | SRS resmi direvisi owner setelah keputusan |
| G24. Merge/deploy/SRS resmi | — | Sengaja tidak dilakukan: menunggu persetujuan pemilik | **NOT RUN** (owner) | Keputusan owner atas CR-005/006/007 lalu merge `main`, deploy, revisi SRS |
| G25. Gmail live E2E | Email Livin nyata terimpor dari Gmail nyata lewat polling; tidak ada duplikat; aplikasi menampilkan data tersimpan | Backend resmi owner `127.0.0.1:18090` (`APP_ENV=uat-local`, OAuth nyata, polling): backfill live memindai ratusan email; setelah perbaikan parser `5df6be6`/`c98527b` dan hardening audit `e3b84b4`, **12/12 bulan COMPLETE dengan 914 transaksi unik**, 0 baris duplikat (run final mengimpor 769 + 145 baris tersimpan dari percobaan pertama; 79 duplikat Sep = baris Sep yang sama). App memanggil `/v1/summary` + `/v1/transactions` normal. Bukti agregat: [GMAIL_LIVE_IMPORT](../evidence/GMAIL_LIVE_IMPORT_2026-11-24.md) | **PASS** (live, akun pilot owner) | Latensi/kuota/biaya nyata & Rp0 = G29/G37; SSO = G36; bukan bukti untuk akun lain |
| G26. Kontrak polling §9 (202/429/409/503) | `POST /v1/gmail/sync` → `202 {"queued":true,"coalesced":...}`; cooldown `429 {"code":"sync_cooldown","retryable":true,"retryAfterSeconds":N}`; `409 gmail_not_connected`/`gmail_needs_reauth`; `503 gmail_disabled`; status §9.2; tidak pernah impor sinkron | E2E fake: sync #1 `202 queued`, #2 `429 sync_cooldown` non-blocking; restart melanjutkan; `needs_reauth=false`; probe `409/503` sesuai tes handler; evidence [`GMAIL_CONTRACT_E2E`](../evidence/GMAIL_CONTRACT_E2E_2026-11-24.md) | **PASS** (fake) | Perilaku 429/409 nyata dengan kuota Google = owner (G25) |
| G27. QA E2E kontrak independen | Harness black-box `scripts/qa/gmail/contract_e2e.py` + `README.md`: register/login, OAuth dev, backfill 12/12, sync 202/429, restart, `history_expired`, `rate_limit`, cek `watch/stop/category=0`, push 404, tidak ada kebocoran token/email/ID | Run nyata pada integrasi `5d76c7f`, **diulang pada HEAD final `1290910`**, dan **diulang lagi pada integrasi `138b5c3` setelah lane SSO**: **PASS=82, FAIL=0, exit 0** pada ketiga run (fake, port terisolasi); harness `19c009a`, evidence `cbec95a` + [SSO_ONBOARDING_DEVICE_FIXTURE](../evidence/SSO_ONBOARDING_DEVICE_FIXTURE_2026-11-24.md) | **PASS** (fake; port terisolasi) | Tidak menggantikan Gmail live (G25) |
| G28. UAT perangkat fisik FIXTURE | HP fisik `105397ba0507` (varian debug, `adb reverse tcp:18090`), backend `1290910` profil fixture: tautkan Gmail, backfill 12/12, panel status server, manual sync 202 + drain, cooldown 429 + hitungan mundur UI, delta saat app ditutup, restart backend tidak menggandakan, rekonsiliasi UI vs DB | [UAT perangkat FIXTURE](../evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md) butir 1–10 **PASS** pada build awal `a7abb14` + **smoke build final `7f218ed`** (instal ulang, sheet "Menunggu" 12/12, tap → `202` → drain) + aset `docs/evidence/assets/gmail_polling_uat/`; penyimpangan dicatat (bukan Gmail live) | **PASS** (alur terkontrol) | Gmail live/consent nyata tetap G25; penyimpangan kecil UI "Perlu perhatian" dicatat di evidence |
| G29. Target biaya GCP Rp0 | Desain default polling tanpa Pub/Sub/endpoint publik/verifier OIDC; target Rp0 untuk pilot | Default config polling + tidak ada resource GCP berbayar di jalur default | **NOT RUN / OPEN** (owner) | Billing GCP masih tertaut; pemilik harus memverifikasi tagihan/kuota nyata sebelum gate Rp0 boleh PASS |

| G30. Google SSO Android (Credential Manager) | Tombol **Masuk dengan Google** utama, picker akun selalu tampil (`autoSelect=false`), ID token tidak masuk log/intent/state, `GoogleSignInGateway` dapat di-fake, fitur aman tersembunyi bila client ID kosong | Tes unit/Compose Android `GoogleSso*`; BuildConfig tanpa client ID → APK gate tidak memuat string client; [GOOGLE_SSO_OWNER_SETUP](../GOOGLE_SSO_OWNER_SETUP.md) | **PASS** (fixture/unit) | UAT HP akun Google nyata = G36 |
| G31. Verifikasi ID token Go | RS256-only (tolak `none`/HMAC), `kid` + JWKS cache/rotasi, iss whitelist `accounts.google.com`, aud = `GOOGLE_SSO_CLIENT_ID`, skew exp/nbf/iat, nonce claim cocok, token kedaluwarsa/salah ditolak 401 | Tes `internal/auth/google_test.go`; handler `POST /v1/auth/google` → sesi PAM existing + `googleLinked:true`; [AUDIT SSO ronde 1](../evidence/AUDIT_SSO_R1_2026-11-24.md) | **PASS** (unit/HTTP) | Butuh Web Client ID nyata untuk live |
| G32. Nonce sekali pakai & replay | Nonce 32 byte base64url TTL 600 dtk, dikonsumsi **sebelum** verifikasi secara atomik; replay/kedaluwarsa ditolak; nonce tidak owner-scoped dan tidak dapat dipakai dua kali | Tes store nonce + HTTP replay; audit ronde 1 mengonfirmasi consume-before-verify | **PASS** | Rate limit nonce endpoint dicatat sebagai risiko INFO (belum ada) |
| G33. Penautan akun lama (unik, atomik, tanpa auto-merge) | `google_identities` PK(issuer,subject) + UNIQUE(owner_id), FK cascade; link idempoten; konflik lintas owner 409 `google_identity_conflict`; akun sudah tertaut 409 `google_already_linked`; akun nonaktif 403; race owner sama tepat satu pemenang (tanpa 500); email sama beda `sub` tidak merge; transaksi/Gmail/role/owner utuh | Tes store + HTTP baru `TestConcurrentGoogleLinkSameOwnerDifferentSubjects`, `TestGoogleSSOSameEmailDifferentSubjectsDoNotMerge`; migrasi 021 diuji upgrade 020→021 | **PASS** (unit/HTTP) | UAT login ulang akun lama = G36 |
| G34. Onboarding Gmail pasca-login | Tawaran "Tautkan Gmail untuk impor Livin" bila Gmail belum tertaut; akun Google sama/berbeda; consent `gmail.readonly` terpisah dari identitas Google dan AI; tombol **Lewati** per-owner (DataStore) tidak memblokir sesi/transaksi manual | Tes ViewModel/Compose onboarding + skip persisten; kontrak Gmail existing (G26/G27); **uji HP fisik fixture** 24 Nov 18:30 WIB: sheet tawaran + Lewati tampil, sesi tetap aktif, Gmail `connected=false`, skip persisten setelah restart — [bukti perangkat](../evidence/SSO_ONBOARDING_DEVICE_FIXTURE_2026-11-24.md) | **PASS** (fixture/unit + HP fixture) | Consent/backfill live = G25/G36 |
| G35. Audit SSO independen | Verifier, nonce, link atomik, no-merge, tidak ada kebocoran token, docs jujur, gate terpisah; tanpa akses kredensial nyata | [AUDIT SSO ronde 1](../evidence/AUDIT_SSO_R1_2026-11-24.md): PASS teknis, 0 P0/P1, 8 P2; tindak lanjut P2 ditutup di `e67380a` (Android) + `71013f5` (backend) | **PASS** | Audit ronde 2 fokus P2: [AUDIT_SSO_R2](../evidence/AUDIT_SSO_R2_2026-11-24.md) |
| G36. UAT SSO HP fisik + Run Android Studio | Login Google nyata, link akun lama tanpa duplikat, skip onboarding, tautkan Gmail + consent, email Livin baru tersimpan saat app tertutup; dijalankan dari tombol Run | Prasyarat live sudah diverifikasi (nonce `200` + bentuk `nonce/expiresAt/expiresInSeconds`; ID token palsu → `401 google_invalid_token`; link tanpa sesi → `401`; preflight perangkat/SSO **exit 0**; `google_identities` masih 0). Login Google nyata + link akun + tombol Run tetap belum dijalankan karena butuh interaksi pemilik | **NOT RUN** (owner) | Input owner tercantum di §4 |
| G37. Gate SSO/Gmail live/Rp0 terpisah | SSO, Gmail live, dan Rp0 dilaporkan terpisah; Rp0 hanya PASS setelah Billing dilepas **dan** UAT live lulus; tanpa Cloud Identity Platform/Pub/Sub/paid service di jalur default | Kode tidak menambah layanan berbayar (audit ronde 1); Billing belum dilepas; tidak ada kredensial Google nyata | **NOT RUN / OPEN** (owner) | Sama dengan G25/G29 untuk lingkup live/Rp0 |

### Akuntansi gate final

Total **38 gate**: **29 PASS, 4 PARTIAL, 5 NOT RUN**.
- PASS: G1, G2, G4b, G5, G6, G7, G8, G9, G10, G11, G12, G13, G15, G16, G19, G20, G21, G22, G23, **G25 (live owner)**, G26, G27, G28, G30, G31, G32, G33, G34, G35.
- PARTIAL: G3 (drill rollback), G4 (proteksi `main` backend), G17 (retire IMAP penuh), G18 (TalkBack/lebar layar final).
- NOT RUN (owner): G14 (hapus/restore data nyata), G24 (merge/deploy/SRS), G29 (GCP Rp0 / OPEN), G36 (UAT SSO HP + Run), G37 (gate live/Rp0 terpisah).

Tidak ada gate FAIL. Angka ini tidak boleh dibaca sebagai kelulusan proyek: Gmail live sudah PASS
untuk akun pilot owner (G25), tetapi **SSO live (G36)**, **Rp0 (G29/G37)**, merge/deploy, dan revisi
SRS resmi masih milik pemilik.

## 3. Bukti kunci (jalur file)

- Split: `docs/evidence/UAT_BACKEND_SPLIT_2026-11-24.md`, `AUDIT_BACKEND_SPLIT_2026-11-24.md`,
  `docs/delivery/BACKEND_SPLIT_2026-11-24.md` (termasuk rollback dan URL).
- Gmail API/polling: [`GMAIL_API_CONTRACT`](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/GMAIL_API_CONTRACT.md) §1–9,
  [`GMAIL_API_SETUP`](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/GMAIL_API_SETUP.md),
  `AUDIT_GMAIL_API_2026-11-24.md`, `GMAIL_UI_2026-11-24.md`.
- Kontrak polling E2E: `docs/evidence/GMAIL_CONTRACT_E2E_2026-11-24.md` +
  `scripts/qa/gmail/contract_e2e.py` (+ `README.md`).
- UAT perangkat FIXTURE: `docs/evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md` +
  `docs/evidence/assets/gmail_polling_uat/`.
- Retensi: `0037-Backend-Financial/docs/RETENTION_POLICY_IMPLEMENTATION.md`,
  `AUDIT_RETENTION_INTEGRATION_2026-11-24.md`, `RETENTION_UI_2026-11-24.md`.
- UAT pasangan build: `docs/evidence/UAT_INTEGRATED_2026-11-24.md` + `ui_integrated/01–07`.
- Audit final: `AUDIT_GMAIL_API_2026-11-24.md`, `AUDIT_RETENTION_INTEGRATION_2026-11-24.md`,
  `AUDIT_FINAL_FIXES_2026-11-24.md`.

## 4. Keputusan yang menunggu pemilik

1. Setujui CR-005 (retensi setahun), CR-006 (Gmail API), CR-007 (workflow tahunan + UI ringkas,
   termasuk perubahan desain ke polling default 15 menit).
2. Sediakan Google Cloud project: OAuth Web client + secret, consent screen + test users, Gmail API,
   dan selesaikan verifikasi restricted scope/CASA; Pub/Sub topic + publisher grant **hanya bila**
   jalur push opt-in akan dipakai.
3. Verifikasi **target biaya Rp0** (Billing masih tertaut) dan ukur kuota/pemakaian Gmail API pada
   akun pilot sebelum gate G29 boleh PASS.
4. Jadwalkan drill rollback (G3) dan drill hapus/restore retensi (G14) pada salinan DB.
5. Putuskan penonaktifan penuh jalur IMAP (G17) setelah rollback teruji.
6. Merge ke `main`/deploy dan revisi SRS resmi hanya setelah 1–5 selesai.
7. **Cek kesiapan sebelum Run**: `./scripts/verify/uat_readiness.sh --backend-url http://127.0.0.1:18090 --apk app/build/outputs/apk/debug/app-debug.apk` (exit 0 = prasyarat otomatis siap; 2 = daftar kekurangan; tidak mencetak secret). Gate Rp0 tetap manual.
8. **Untuk gate SSO (G30–G37)**: daftarkan OAuth **Android** client untuk package
   `com.pam.recap.debug` + SHA-1 debug `FE:38:71:AB:6A:DC:D2:E5:B3:68:96:DE:59:84:D3:08:5F:27:C6:30`
   (SHA-256 `B3:9A:3C:FC:78:CF:EB:2E:AA:E3:9A:F0:B7:E1:7A:A5:31:7C:5D:92:43:E1:CA:13:C0:7D:1D:12:C2:D6:25:BC`),
   isi **Web Client ID** (bukan secret) ke `PAM_GOOGLE_SSO_CLIENT_ID`/`GOOGLE_SSO_CLIENT_ID`, dan
   siapkan akun Google uji yang sudah login di HP. Rahasia (client secret Gmail, password, OTP)
   **tidak boleh** dikirim ke chat/repo.
9. Jalankan tombol **Run** Android Studio pada HP fisik pemilik untuk G36: login Google, link akun
   lama (tanpa duplikat), skip onboarding, tautkan Gmail + consent, dan verifikasi email Livin baru
   tersimpan saat aplikasi tertutup. Gate SSO (**G36**), Gmail live (**G25**), dan Rp0 (**G29/G37**)
   dilaporkan terpisah; fixture bukan bukti live.
