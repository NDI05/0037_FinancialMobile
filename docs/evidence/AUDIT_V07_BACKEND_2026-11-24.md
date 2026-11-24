# AUDIT INDEPENDEN — Backend PAM, mandat PM SRS v0.7 (CR-008)

- **Auditor:** auditor (independen, read-only; tanpa fix/commit/checkout/reset)
- **Waktu:** 2026-11-24 ~22:15–22:30 WIB
- **Repo target:** `0037-Backend-Financial`, branch `integration/2026-11-24`, HEAD **`231a8ba`** (clean, = origin), tanpa perubahan working tree oleh auditor.
- **Rentang audit:** `9cb5fe8..231a8ba` (fokus commit `1d020bc`, `aa24518`, `76b2664`, `231a8ba`), ditambah verifikasi regresi pada route aktif.
- **Verdict kode:** **PASS dengan 4 temuan P2 + 5 INFO; 0 P0 / 0 P1.** UAT perangkat & gerbang rilis **TIDAK dijalankan** auditor — verdict ini hanya code/test-level.
- **Batasan:** tidak menyentuh repo Android, data owner, port 18090; tidak mencetak PII/secret/owner_id/nominal/merchant; nilai bisnis disamarkan.

## Metode

1. Review diff `9cb5fe8..231a8ba` per file + baca kode penuh area yang berubah (bukan hanya diff).
2. Uji independen sendiri (bukan mengandalkan klaim SE):
   - `PAM_GOROOT=... ./scripts/go.sh vet ./...` → **exit 0**, tanpa temuan.
   - `./scripts/go.sh test ./... -count=1` → **11/11 paket ok** (auth, categoryllm, config, crypto, finance, gmail, httpapi, llm, retention, store, cmd) total ±23 s.
   - `./scripts/go.sh test -race -count=1 ./internal/gmail ./internal/retention ./internal/store ./internal/httpapi` → **ok** (gmail 25,8 s; retention 1,4 s; store 38,9 s; httpapi 243,8 s) tanpa race.
3. Pemeriksaan khusus FR-16/NFR-15, FR-67/69, FR-79/80, pensiun route, registrasi/undangan/OI-28, isolasi owner.
4. Verifikasi klaim SE terhadap bukti kode/test yang ada (lihat §Klaim SE).
5. Untuk batas request provider, auditor mengukur ulang estimasi token deterministik dengan harness terpisah di `/tmp/est` (menyalin `classifier.go` + `azure.go` apa adanya, **tanpa** mengubah repo target).

## Temuan P2

### P2-1 — Batas "maks. 4 request provider per jendela 2 jam" tidak ditegakkan kode; bisa mencapai ~12 request/jendela

- **Lokasi:** `internal/httpapi/handlers_category_queue.go:12-26` (konstanta + komentar), `internal/httpapi/handlers_category.go:324-338` (loop `SplitCandidates` di dalam loop batch), `docs/GMAIL_API_CONTRACT.md:75-77`, `docs/GMAIL_API_SETUP.md:173-177`.
- **Bukti:**
  - Kode: `CategoryDrainBatch = 5` (per request) dan `CategoryDrainMaxBatches = 4`. `drainOwnerQueue` melakukan ≤4 iterasi batch; **di dalam tiap iterasi** `classifyGroupWith` memanggil `classifier.SplitCandidates(unique)` dan melakukan **satu panggilan provider per grup** (baris 324 dst). Jadi `CategoryDrainMaxBatches` membatasi iterasi batch, bukan jumlah panggilan provider.
  - Pengukuran auditor (harness `/tmp/est`, salinan `azure.go`+`classifier.go` identik, kandidat DEBIT dengan daftar kategori produksi 11 kode dan merchant realistis): estimasi `MaxInputTokens` per 1 kandidat = **770–776 token** (batas 1024, `ConservativeBytesPerToken = 2`). `SplitCandidates` untuk 1/2/3/4/5 kandidat menghasilkan grup: 1; 1; 2 (2+1); 2 (2+2); **3 (2+2+1)**.
  - Akibatnya satu jendela drain dengan backlog ≥20 job dapat memanggil provider hingga 4 × 3 = **12 request**, bukan 4 seperti tertulis di kontrak.
  - Komentar `CategoryDrainMaxBatches` ("bounds how many provider requests one window may run") secara faktual salah.
- **Dampak:** kontrak/perencanaan biaya & rate limit yang disepakati PM tidak sesuai perilaku nyata. Gate harian (`OwnerAttemptsDay`, `DailyCeilingUSD`) tetap menjaga biaya total, tetapi backlog dapat terbelah dan tertunda karena tiap grup mengonsumsi attempt; juga menyulitkan verifikasi klaim efisiensi "≤4 request/2 jam". Bukan kerusakan data.
- **Rekomendasi:** hitung dan batasi **jumlah panggilan provider** (bukan iterasi batch) per jendela, atau perbarui kontrak/SETUP agar menyebut batas per-batch + batas iterasi dan konsekuensi split. Tambahkan tes yang mengassert total panggilan provider per jendela (mis. `≤4`) dengan 5 merchant panjang.

### P2-2 — Penerimaan undangan lama tetap mewajibkan `inviteCode`, kontradiksi dengan kontrak "invitationToken menggantikan inviteCode"

- **Lokasi:** `internal/httpapi/handlers_auth.go:59` (gate `inviteCode`) vs `docs/API_CONTRACT.md:54` (dan `docs/API_CONTRACT.md:120-121`).
- **Bukti:** handler `register` memeriksa `secureEqual(body.InviteCode, s.Config.InviteCode)` **tanpa syarat** sebelum cabang `if body.InvitationToken != ""` (baris 75-83). Kontrak: "Undangan admin memakai `invitationToken` menggantikan `inviteCode`". Permintaan `POST /v1/accounts` hanya dengan `invitationToken` (tanpa `inviteCode`) → `403` `code:"forbidden"` pesan `invalid_invite`; jalur undangan tidak pernah dieksekusi.
- **Dampak:** jalur fallback OI-28 (menerima undangan ADMIN lama) tidak dapat dipakai sesuai dokumen kecuali kode undangan global ikut dibagikan; dokumentasi internal saling bertentangan (baris 54 vs 121-123). Bukan eskalasi peran (role tetap dari token), tetapi operasional/PM.
- **Rekomendasi:** lewati pemeriksaan `inviteCode` bila `invitationToken` ada (token satu-kali + email-match sudah menjadi gate-nya), atau ubah kontrak menjadi "keduanya wajib" dan selaraskan catatan CR-008.

### P2-3 — Klaim SE "penerimaan undangan tetap dipertahankan" tidak memiliki test otomatis setelah tes admin dihapus

- **Lokasi:** `internal/httpapi/retired_modules_test.go` (hanya 404 + retensi baris), `internal/httpapi/handlers_auth.go:74-87` (kode produksi), riwayat `git show 1d020bc^:internal/httpapi/admin_test.go` (`TestAdminInvitationIsOneTimeAndControlled`).
- **Bukti:** `grep -rn 'invitationToken' --include=*_test.go internal/` → **nol hasil**. Satu-satunya tes HTTP jalur undangan (`TestAdminInvitationIsOneTimeAndControlled`, termasuk one-time & role-dari-token) terhapus bersama modul admin; `retired_modules_test.go:59,100` hanya menyisipkan baris `admin_invitations` dan mengassert 404 + jumlah baris. `go test ./...` hijau tanpa menyentuh jalur ini.
- **Dampak:** klaim commit `1d020bc` ("Penerimaan undangan yang sudah ada tetap dipertahankan lewat invitationToken") tidak dibuktikan tes; risiko regresi senyap pada satu-satunya jalur OI-28 (bug P2-2 pun tidak tertangkap karena tak ada tes). Bukan bug runtime saat ini.
- **Rekomendasi:** tambahkan tes HTTP: token valid → 201 + role sesuai undangan; token terpakai/kedaluwarsa/salah → 409/410/400; token milik email lain → ditolak; tanpa `inviteCode` (setelah P2-2 diputuskan) terdokumentasi.

### P2-4 — Runbook provisioning ADMIN menyebut subcommand yang tidak ada (`pam-admin bootstrap` vs flag `-promote`)

- **Lokasi:** `docs/API_CONTRACT.md:125` vs `cmd/pam-admin/main.go:26-31` (usage `pam-admin -promote <email>`).
- **Bukti:** CLI hanya menerima `-promote <email>`; `run()` mengembalikan error `usage: pam-admin -promote <email>` untuk argumen kosong; tidak ada subcommand `bootstrap`. Dokumen CR-008/OI-28 menyebut "CLI/ops `pam-admin bootstrap`" sebagai satu-satunya jalur pembuatan ADMIN baru.
- **Dampak:** operator yang mengikuti runbook tidak bisa memprovision ADMIN sesuai dokumen (OI-28 tidak dapat dimitigasi tanpa membaca kode). `BootstrapAdmin` sendiri sudah benar: idempoten, transaksional, menulis `admin_audit` (`internal/store/admin.go:504-531`) — hanya nama perintah di dokumen yang salah.
- **Rekomendasi:** perbaiki dokumen menjadi `pam-admin -promote <email>` (sekaligus README:134), atau tambahkan alias subcommand bila doc dianggap kontrak.

## Temuan INFO (bukan penghambat, untuk kerapian)

### INFO-1 — Label route legacy tersisa di `sanitizedRoute`
- **Lokasi:** `internal/httpapi/server.go:186-215`.
- **Bukti:** `sanitizedRoute` masih memetakan `/v1/admin/{route}`, `/v1/attachments/{id}`, `/v1/budget-plans/{monthId}` untuk keperluan log; tidak ada `mux.Handle` untuk path tersebut sehingga label tidak pernah dipakai.
- **Dampak:** dead mapping; tidak berefek pada routing/keamanan. **Rekomendasi:** hapus saat menyentuh file itu berikutnya.

### INFO-2 — Mapping error admin yang tidak lagi terjangkau
- **Lokasi:** `internal/httpapi/handlers_data.go:545-548` (`ErrLastAdmin`, `ErrSelfAdminChange`), `:618-619` (`ErrLastAdmin`).
- **Bukti:** `store.ErrSelfAdminChange` hanya dikembalikan oleh `internal/store/admin.go:329,375` (metode admin yang tidak lagi dirutekan). `ErrLastAdmin` masih terjangkau lewat `internal/store/deletion.go:52` (hapus workspace), jadi hanya `ErrSelfAdminChange` yang benar-benar dead.
- **Dampak:** dead branch; tidak berbahaya. **Rekomendasi:** hapus bersama sisa kode admin.

### INFO-3 — Retensi tetap menghitung/menjaga tabel legacy (intentional)
- **Lokasi:** `internal/httpapi/retention.go:138`, `internal/httpapi/retention_worker.go:80`.
- **Bukti:** audit/sweep masih menyertakan `attachments` metadata sebagai objek retensi; `retired_modules_test.go:TestRetiredModuleDataIsRetained` membuktikan baris tidak terhapus.
- **Dampak:** sesuai CR-008 (data lama dipertahankan, bukan dihapus). **Rekomendasi:** tidak ada; pastikan kebijakan retensi legacy disepakati PM.

### INFO-4 — `drainLocks` (sync.Map) tumbuh per owner tanpa eviction
- **Lokasi:** `internal/httpapi/handlers_category_queue.go` (peta lock single-flight per owner).
- **Bukti:** entry dibuat per `ownerID` dan tidak pernah dihapus; jumlah owner aktif nyata kecil.
- **Dampak:** pertumbuhan memori lambat bila jumlah owner sangat besar; bukan risiko rilis v0.7. **Rekomendasi:** monitor/catat batas, atau eviction saat drain selesai.

### INFO-5 — Pasca-CR-008, ADMIN tunggal tidak dapat menghapus workspace (aturan last-admin dipertahankan)
- **Lokasi:** `internal/store/deletion.go:47-54`; didokumentasikan `docs/API_CONTRACT.md:333`.
- **Bukti:** `CreateOrGetDeletion` menghitung `role='ADMIN' AND status='ACTIVE'` global dan mengembalikan `ErrLastAdmin` (409) bila ADMIN aktif ≤1. Setelah modul admin pensiun, ADMIN hanya lahir dari CLI ops, sehingga ADMIN tunggal terkunci dari hak penghapusan data.
- **Dampak:** sesuai dokumen, tetapi keputusan produk perlu dikonfirmasi PM (hak penghapusan vs perlindungan lockout admin yang fiturnya sudah tidak ada). **Rekomendasi:** putuskan di tingkat PM; jika admin tidak lagi relevan, relaksasi guard untuk role yang tidak dipakai.

## Verifikasi per area mandat (ringkas, semuanya lulus kecuali temuan di atas)

### 1) Pensiun route `/v1/admin/*`, `/v1/attachments*`, `/v1/budget-plans*`
- `server.go` tidak mendaftarkan path tersebut (50 `mux.Handle`; grep `admin|attachment|budget` hanya menyisakan label log INFO-1).
- `TestRetiredModulesAreNotRouted` (`retired_modules_test.go`): 8 path × GET/POST/PUT/PATCH/DELETE, dengan sesi valid **dan** tanpa sesi → semua **404** (lulus di suite).
- `TestRetiredModuleDataIsRetained`: baris `budget_plans`, `admin_invitations`, `admin_audit`, `attachments` disisipkan → tetap 404 dan jumlah baris utuh (lulus).
- Migrasi tetap 001–021; diff tanpa migrasi baru; tidak ada DROP yang menghapus data pada rentang ini (drop historis hanya di 002/005/008/015).
- Handler khusus dihapus (`handlers_admin.go`, `handlers_attachments.go`); store legacy tetap ada untuk CLI/CLI-bootstrap dan retensi (sesuai CR-008).
- Sisa test admin/attachments/budget memang dihapus bersama modulnya; `retention_guard_test.go` **tetap ada** (hanya bagian attachment yang dibuang), jadi guard retensi route aktif tidak hilang.

### 2) Registrasi/undangan/OI-28
- Registrasi publik wajib `inviteCode`; produksi menolak kode kosong/default (`config.go:426-428`) → tidak ada pendaftaran publik role tinggi. Role tanpa token selalu `USER`.
- Google SSO tidak membuat akun: callback hanya memetakan identitas yang sudah ter-link (`handlers_google.go:104-120`); tidak ada `CreateAccount` di jalur Google.
- Undangan lama: kode produksi masih ada (`CreateAccountFromInvitation`) tetapi terkendala P2-2/P2-3.
- OI-28 terdokumentasi di `docs/API_CONTRACT.md:115-127` (dampak + jalur CLI). Jalur pembuatan undangan baru memang hilang dari API (sesuai CR-008).

### 3) FR-16 / NFR-15 (window rolling 1 tahun WIB)
- `internal/retention/policy.go`: `Start` = tengah malam WIB satu tahun kalender sebelumnya, clamp 29 Feb → 28 Feb (`daysIn`), `End = now`; `Contains` inklusif; `ValidateEventAt` menolak 1 ns sebelum Start; `ValidateMonth` menerima bulan batas/parsial, menolak bulan masa depan; `ClampMonthStart` dipakai list/summary/transaksi.
- `internal/gmail/service.go:917-945`: `monthOf`/`monthWindow` memakai zona Asia/Jakarta; `backfillMonths` = 12 bulan rolling + bulan yang memuat `Window.Start` (13 bulan bila start di tengah bulan — didokumentasikan), `progress()` menurunkan `MonthsTotal/MonthsDone` dari baris durable.
- `internal/gmail/worker.go`: `recoverHistory` mengambil baseline sebelum scan, `rangeStart` di-clamp ke `retention.NewWindow(now).Start`, resume lewat state ter-seal, delta di-replay dari baseline; `routePlacements` memvalidasi `EventAt` exact-window → baris di luar window jadi `UNRESOLVED` reason `outside_retention` yang auditabel (identitas sumber dipertahankan), bukan kegagalan batch; bulan lain di dalam window dirutekan ke job bulan masing-masing.
- `internal/store/imports.go`: `CreateImportJob` validasi bulan; `ImportBatch` memvalidasi setiap kandidat (bulan + `ValidateEventAt`) dan idempoten per `(owner_id, job_id, batch_key)` + request hash.
- `summary.go`/`transactions.go` clamp awal bulan batas ke `Window.Start` sehingga agregasi hanya menghitung bagian yang dipertahankan.
- Tes hijau: `TestBackfillMonthsCoverPartialFirstMonthOfRetentionWindow` (13 bulan, start 2025-11-24 WIB, leap 2028-02-29→2027-02-28), `TestParsedEventTimeDecidesRetentionPeriodMembership` (partial-in diimpor, 1 hari di luar + future di-skip UNRESOLVED, replay dedupe), tes boundary/leap `internal/retention/policy_test.go`.

### 4) FR-67/69 (kategorisasi otomatis)
- Satu job logis per `(owner_id, transaction_id, expected_revision)`; `EnqueueAutoCategoryJob` `ON CONFLICT DO NOTHING`; baris + revision tetap; eligibility + consent dicek ulang di server.
- Impor hanya **enqueue** (tanpa provider) lalu satu jendela drain single-flight per owner (`acquireOwnerDrain` TryLock); state jendela durable (`NextDrainAt`, `SaveCategoryQueueState`), sisa didefer ke jendela berikutnya; antrean kosong = nol panggilan (dibuktikan tes).
- ≤5 merchant unik per request dijaga (`CategoryDrainBatch`, distinct key dari `ListQueuedCategoryJobs`); untuk manual, `transactionIds` dibatasi 1..5.
- `caseId` dipetakan lewat `CaseIDForIndex`/`CaseIndex` + `ValidateResults` (setiap index tercakup tepat sekali, tidak pernah posisi array); provenance provider/model/prompt/taxonomy/rule version dicatat di job dan transaksi.
- Late-result discard: `ApplyLLMCategory` menolak baris terhapus, origin USER, revisi berubah, deletion-request PENDING, consent bukan GRANTED, lease hilang/kadaluarsa (`ErrLeaseLost`/`ErrRevisionConflict`), dan kepemilikan owner; revision bulan naik dalam tx yang sama. Tes matriks FR-67 (deleted / owner-changed / CANCELLED) dan FR-69 (provenance, inputRevision summary naik) hijau.
- **Catatan:** lihat P2-1 untuk batas jumlah request provider per jendela.

### 5) FR-79/80 (status & sinkron manual)
- `RecordGmailAttempt` hanya menyentuh `last_attempt_at`; `RecordGmailSuccess` menyentuh `last_success_at` + membersihkan error; `Status` murni baca (tanpa panggilan provider), mengekspos kedua timestamp terpisah.
- `POST /v1/gmail/sync`: 202 `{queued, coalesced, requestedAt, nextAllowedAt}`; permintaan berulang saat job aktif → `coalesced=true`; cooldown default 60 s → 429 `sync_cooldown` `retryable` + `retryAfterSeconds` (sesuai kontrak); permintaan durable di baris (restart-safe), dedupe per owner+jenis; 401/invalid_grant menjadi `needs_reauth` terminal.
- Tes hijau: `TestGmailStatusAttemptSuccessSeparationAndManualSyncSemantics`, `TestGmailPollingSyncQueueStatusAndPushAbsence`, `TestManualSyncSurvivesRestartWithoutDuplicates` (polling_test.go) + suite `internal/gmail`.

### 6) Isolasi owner & regresi route aktif
- Store query route aktif memakai filter `owner_id`; tes cross-owner (mis. `TestOwnerCannotReadForeignJob`, job/transaksi/summary/analisis/import) hijau di suite `internal/httpapi` & `internal/store`.
- Route aktif (transactions/categories/summaries/analyses/gmail/consents/deletion/retention) tetap terdaftar dan berfungsi dalam tes; tidak ada perubahan kontrak tak terdokumentasi pada rentang ini selain catatan di atas.

## Klaim SE vs bukti independen

| Klaim | Verdict auditor |
|---|---|
| `1d020bc` — route admin/attachments/budget-plans 404 semua method dengan sesi valid; data/tabel/migrasi utuh; handler khusus dihapus; tidak ada pendaftaran publik role tinggi / auto-create Google; OI-28 didokumentasikan | **Terbukti** (kode + `retired_modules_test.go` + migrasi tidak berubah). **Sebagian tidak terbukti:** klaim "penerimaan undangan tetap dipertahankan" — lihat P2-2/P2-3 (jalur ada tapi wajib inviteCode & tanpa tes). |
| `aa24518` — 13 bulan (12 rolling + partial pertama), routing exact-window → skip UNRESOLVED auditabel, dedupe replay, `months_total` 12/13, tes FR-79 HTTP | **Terbukti** di kode & tes (suite hijau); satu-satunya penyimpangan perilaku adalah P2-1 (bukan klaim SE). |
| `76b2664` — tests-only untuk FR-67/69 | **Terbukti:** hanya 2 file test berubah (`category_pipeline_test.go`, `store/category_jobs_test.go`), tanpa diff produksi. |
| `231a8ba` — gofmt-only, gate gofmt bersih | **Terbukti:** diff hanya pemecahan baris/penyelarasan (termasuk `classifier.go`/`azure.go`), tanpa perubahan semantik. |

## Kesimpulan

- **Tidak ada P0/P1.** Semua temuan adalah P2 (batas request provider vs kontrak, kontrak vs implementasi undangan, celah tes undangan, runbook CLI) dan INFO.
- **UAT perangkat/HP, UAT e2e Gmail nyata, dan gerbang rilis TIDAK dijalankan** oleh auditor; status code/test-level PASS. Rekomendasi: PM/Lead memutuskan P2-1..P2-4 sebelum rilis v0.7; P2-1/P2-4 murni dokumentasi/limit, P2-2/P2-3 menyentuh jalur OI-28.
- Semua perintah uji dijalankan ulang oleh auditor pada `231a8ba` (vet bersih, seluruh test ok, race ok).
