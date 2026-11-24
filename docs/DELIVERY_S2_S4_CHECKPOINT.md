# Delivery S2–S4 — living checkpoint

## Perencanaan tambahan disetujui — 23 November 2026

Pemilik menyetujui sinkronisasi otomatis target 30 menit, pemeriksaan foreground,
antrean kategori target 2 jam/batch maksimal 5 merchant unik, serta ikon status top bar
yang membuka panel terpisah untuk data transaksi/kategori/perlu perhatian.
[Rencana, dependency dan acceptance](plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md) dan
[CR-005 DRAF revisi SRS](changes/CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md) tersedia.
Status scope baru **PLANNED / NOT RUN**; dimasukkan sebagai perluasan S2 sesudah gate
classifier aktif, dengan uji perangkat/background/resource pada S5. Tidak menimpa WIP SE
atau mengklaim scheduler sudah aktif. **SRS v0.5 DOCX/PDF tetap tidak diubah**;
revisi resmi nanti atas instruksi pemilik. Snapshot implementation lama di bawah tetap
historis dan perlu dibaca bersama laporan/source SE terbaru.

Status: **IN PROGRESS / belum selesai**, 22 November 2026.
Branch `feat/c2-android`, HEAD dicatat pada commit checkpoint ini. **Klarifikasi pemilik,
22 November 2026:** push R1 sebelumnya diotorisasi, dan setiap commit selanjutnya harus
langsung dipush ke branch kerja yang sama. Kebijakan ini menggantikan larangan push pada
prompt sebelumnya. Merge/deploy tetap memerlukan instruksi terpisah.

Aturan repo: [`AGENTS.md`](../AGENTS.md). Mandat terkini:
[`MANDAT_S2_S4.md`](agent-prompts/MANDAT_S2_S4.md). Koreksi review PM:
[`PM_REVIEW_FB68397.md`](reviews/PM_REVIEW_FB68397.md).

## Inventaris awal yang dipertahankan

**Perubahan terbaru setelah `74eab38`:** owner mengoreksi AWS menjadi Azure for Students dan
meminta kategorisasi LLM. CLI login Student Enabled/spendingLimit On serta region policy
diverifikasi. Foundry resource/project khusus PAM dan GPT-OSS120B v1 GlobalStandard Japan West
dibuat; satu request lima contoh sintetis HTTP200/expected PASS. Tidak memakai data asli.
Config privat 0600, classifier app flag tetap false. Full SRS v0.5 DOCX/PDF berasal dari v0.4;
CR-003 dikonsolidasikan dan FR-66…71/NFR-29/30/T13 ditambahkan. [Setup](AZURE_CLASSIFIER_SETUP.md),
[CR-004](changes/CR_004_AZURE_LLM_CATEGORIZATION.md),
[prompt SE](agent-prompts/PROMPT_SE_AZURE_CLASSIFICATION.md).
Runtime classifying/consent/cost enforcement/accuracy/Android E2E masih TARGET/NOT RUN.
Cloud smoke bukan klaim S2–S4 selesai atau akurasi 100%; snapshot lama di bawah tetap historical.

**Review terbaru setelah `0541342`:**
[`RESUME_0541342_CLOSE_CR003.md`](agent-prompts/RESUME_0541342_CLOSE_CR003.md).
PM targeted 15 tests PASS (GmailConnectPurge 4/vault 5/signout 2/classifier 4). CR-003 tetap
PARTIAL: failure/post-connect publication dan A→logout→A generation belum tertutup; reconnect
Overview sekali mengabaikan outcome; Connect masih memakai consent ledger lokal, bukan bukti
server acknowledgement. Transport/cache owner guard, category provenance/reclassification dan
emulator lifecycle/Android uploader Go/UI belum selesai. Temuan race berasal dari source,
bukan insiden akun asli. Review ini dokumen saja; tidak mengubah APK atau mengulang emulator.

**Review PM terbaru sesudah `c68dc38`:**
[`RESUME_C68DC38_CR003_ACCEPTANCE.md`](agent-prompts/RESUME_C68DC38_CR003_ACCEPTANCE.md).
PM targeted 13 tests PASS (vault 5/routing 2/signout 2/classifier 4). Vault per owner dan
whole-word classifier terimplementasi, tetapi CR-003 tetap PARTIAL: UI connect masih menolak
password kosong, restore setelah login belum ada, owner dapat berubah selama validation sebelum
vault.save memakai sesi baru, dan handle/folder/cache/state belum membuktikan generation guard.
Failure path ini diperoleh dari source, bukan insiden akun asli. Category provenance/reclassification
dan Android uploader→Go→UI acceptance masih terbuka. Review ini hanya dokumen, tidak patch APK.

**Prioritas terbaru sesudah checkpoint worker `2d279da`:** owner meminta Gmail tetap tertaut
setelah logout/login akun PAM yang sama dan kategori QRIS berbasis merchant diperbaiki.
[CR-003](changes/CR_003_GMAIL_SESSION_CATEGORY.md) mencatat perubahan lifecycle credential yang
sudah diminta; implementasi belum selesai. [Review PM](reviews/PM_REVIEW_2D279DA_GMAIL_CATEGORY.md)
mengonfirmasi clear-on-logout/global vault, keyword bakso/mie ayam/coffee yang belum ada, dan
substring false positives kos/air. [Prompt SE terbaru](agent-prompts/RESUME_2D279DA_GMAIL_CATEGORY.md).
PM targeted 9 tests PASS, enam probe classifier sintetis, mailbox read-only tanpa isi asli di repo.
Parser strict dipertahankan. Lost-response PASS terlapor dengan trace yang telah diperiksa PM;
import subset API→DB→UI bukan full Android uploader E2E. S2 PARTIAL; S3/S4 belum selesai.

Review PM terbaru sesudah worker `4c619f7`:
[`PM_REVIEW_4C619F7.md`](reviews/PM_REVIEW_4C619F7.md). Arahan lanjut:
[`RESUME_4C619F7_IMPORT_ACCEPTANCE.md`](agent-prompts/RESUME_4C619F7_IMPORT_ACCEPTANCE.md).
PM menjalankan 7 targeted tests PASS, tetapi probe parser menolak klaim strict validation
selesai: 31 Nov dan 24:00 masih dinormalisasi. Uploader awal ada; recovery retry, identity
job/batch durable, candidate/counts scope, key server dan UI refresh belum memenuhi acceptance.
Import server PARTIAL; refresh acceptance PM PENDING REVIEW; Android lost-response OPEN.
Catatan snapshot sebelumnya di bawah tetap historis, bukan status implementation terbaru.

Arahan resume terbaru: [`RESUME_LIVIN_S2_S4.md`](agent-prompts/RESUME_LIVIN_S2_S4.md).
Pada pemeriksaan PM sebelum commit prompt, HEAD lokal/remote `9571dc3`, worktree bersih.
Pemeriksaan read-only mailbox menemukan email `Pembayaran Berhasil!` dari sender Livin
yang sudah di-allowlist; tanggal/jam pada sampel HTML terpisah. Log emulator memuat
48 skip `malformed_event_time`, progress saved=0/skipped=48 dan terminal COMPLETE.
Ini observasi parsial dari log/mailbox, bukan PASS impor atau jumlah seluruh mailbox.
Parser mengharapkan datetime gabungan; import masih menulis Room sementara Overview/
Transactions mode backend membaca Go. Subject `Transfer dengan BI Fast Berhasil` juga
ditemukan, namun body/semantik template belum diverifikasi penuh. Patch parser/import
belum diterapkan. Prioritas SE: fixture sintetis → parser → import Go → UI, disertai
gate C2A dan seluruh sisa S2–S4. Akun/perangkat nyata tidak dipakai untuk QA destructive.

- C1 Go backend, migrasi 1–7, 42 tes Go (run terakhir), race/vet/build.
- F1–F4: server deletion failure/receipt, login/refresh generation, rejected-token coalescing,
  receipt log sanitization.
- C2A gate A: custom category/provenance mapping, strict wire failure, refresh bus,
  detail error, durable pending-action store.
- Consent v0.4 server-confirmed.
- R1 visual candidate: Manrope offline, warm tokens, B01–B04.

## Status per tahap

| Tahap | Deliverable | Status | Bukti/catatan |
|---|---|---|---|
| S2.1 | C2A gate mapping/provenance/wire | PASS lokal | `ServerFinanceRepositoryImplTest`, `ManualPendingActionStoreTest` |
| S2.1 | refresh <5s di emulator | **PENDING REVIEW** | worker mengukur 2,53s; mutation baru/baseline/key/row belum terikat evidence yang konsisten |
| S2.1 | response create hilang → retry satu row | **PASS worker; trace PM cocok** | gate2 JSONL: 201/drop→200, key/body/result sama; DB/UI menurut laporan worker, tidak diulang PM |
| S2.1 | GET detail 404/unavailable/parsing | PASS kode | error diteruskan; UI banner |
| S2.2 | R1 B01–B04 | **PARTIAL/PASS kandidat** | screenshot 393 dp; 360/412/200% belum lengkap |
| S2.3 | consent server-confirmed | PASS kode | local GRANTED bukan bukti server |
| S2.3 | fingerprint key server + import job/batch/resume | **PARTIAL** | port/uploader awal ada pada 72a9cf4; key server/recovery/counts/UI E2E belum; lihat review 4c619f7 |
| S2.4 | pagination/filter server | **NOT DONE** | UI halaman pertama; C2B1 |
| S2.4 | update/delete transaksi | **NOT DONE** | detail capability notice |
| S2.5 | budget/analysis remote + freshness UI | **NOT DONE** | masih lama/Room |
| S2.5 | deletion UI receipt lifecycle | **PARTIAL** | F1 + receipt store; polling/status UI belum |
| Gate S2 | emulator end-to-end | **BELUM** | jangan dianggap lulus |
| S3 | 15 aksi transaksi/kategori/anggaran | **NOT DONE** | kategori/budget UI belum remote |
| S3 | dynamic fields 4 entitas | **PARTIAL backend** | batas 10/2KB + tipe; editor UI belum |
| S4.1 | lampiran | **NOT DONE** | tahap D |
| S4.2 | admin/CLI/invite/audit | **NOT DONE** | tahap D |
| S4.3 | 25 aksi | **PARTIAL** | baru C01/C02 server; sisanya belum |
| S4.4 | Room/SQLCipher keluar | **NOT DONE** | Room masih dependency varian |

## Matriks 25 aksi (ringkas)

- C01 create manual: **PARTIAL/PASS lokal** — POST Go, idempotency; UI fault injection belum.
- C02 read list/detail: **PASS server** untuk halaman pertama; cursor/search penuh belum.
- C03 update, C04 delete: **NOT DONE** (C2B2).
- C06–C10 kategori: **PARTIAL backend**; UI CRUD belum.
- C11–C15 anggaran: **PARTIAL backend**; UI remote belum.
- C16–C20 lampiran: **NOT DONE**.
- C21–C25 admin: **NOT DONE**.
Tidak ada baris yang diberi PASS tanpa bukti.

### Koreksi status per 23 November 2026 (malam)

Matriks di atas adalah snapshot historis. Status sesudah lane paralel dan UAT emulator:

| Area | Status sekarang | Bukti / batas |
|---|---|---|
| P1 recovery upload | **fixed + diuji** | `f640f2e` (durable run identity `329552b`): run belum diakui dipulihkan sebelum cursor maju; test store/worker hijau |
| S2.4 paging/search + mutasi | **PARTIAL** | UI server paging/search ada; edit detail + atribut 200 di perangkat; halaman kedua/query C05 dan delete destruktif belum dijalankan |
| S2.5 anggaran/freshness | **PARTIAL** | plan server read/save/delete + editor atribut; perbandingan aktual dari ringkasan; UI perangkat belum |
| S3 kategori & anggaran remote | **PARTIAL/PASS API** | kategori CRUD + guard lulus test; C10 search kategori belum ada di port; lihat [matriks C01–C25](delivery/ACCEPTANCE_C01_C25.md) |
| S3 atribut dinamis 4 entitas | **PASS wire + runtime transaksi** | PATCH transaksi `{"sumber":"kamera"}` 200, dibaca ulang dari server, UI prefill setelah buka ulang; editor anggaran ber-test |
| S4.1 lampiran C16–C19 | **PASS runtime** | [UAT lampiran](evidence/UAT_S4_ATTACHMENTS_2026-11-23.md): create, read setelah restart, delete; 24 Nov upload dari picker, rename 200, konflik PATCH 409 + banner, DELETE stale 409 → reload → 204 di emulator (`ui_split/36`–`38`, QA doc §2.7); C20 search/konteks belum diulang di perangkat |
| S4.2 admin C21–C24 | **PASS runtime** | [UAT admin](evidence/UAT_S4_ADMIN_2026-11-23.md): invite, list/cari, edit + konflik 409, deactivate, audit setelah restart; C24 last-admin guard via test API/store, C25 cursor/filter peran lewat API/VM |
| S4.4 Room keluar | **NOT DONE** | Room masih dependency varian |

Tes fresh sesi ini: Android **452 tests / 63 class / 0 failure** (`--rerun-tasks`), lint
**0 error / 109 warning / 1 hint**, Go semua paket `ok`, `go vet` bersih.

## Tes aktual

- Android: **248 tes PASS** (snapshot lama); sesi 23 Nov malam: **452 tes PASS / 63 class**, `assembleDebug`, lint 0 error, 109 warning, 1 hint.
- Go: 42 PASS (terakhir); backend tidak berubah pada R1.
- Node: 124 (run sebelumnya); tidak dijalankan ulang.
- Screenshot R1: `app/build/screenshots/01-overview.png`, `02-transactions.png`,
  `03-transaction-detail.png`, `12-manual-entry.png`.

## Blocker/pekerjaan berikut

1. Rekonsiliasi evidence refresh <5s dan selesaikan lost-response retry UI dengan proxy QA standalone; lihat `QA_C2A_COMMITTED_RESPONSE_LOSS.md`.
2. C2B1: import job/batch/resume + fingerprint key + pagination/filter >100.
3. C2B2: edit/delete + conflict.
4. C2C: budget/analysis/deletion status UI.
5. S3: CRUD kategori/anggaran UI + dynamic fields.
6. S4: lampiran/admin + Room removal.
7. Visual 360/412/200%, TalkBack/contrast, owner UAT.

External NOT RUN: Turso cloud, backup/restore, impor Gmail nyata end-to-end ke Go/UI,
provider nyata, physical device. Pemeriksaan read-only mailbox dan observasi IMAP/parser
pada log nyata sudah dilakukan PM; keduanya belum membuktikan acceptance impor.

## Resume 22 November 2026 — gate C2A aktual

- Alasan run sebelumnya berhenti: agent menutup turn setelah checkpoint dokumentasi R1
  meskipun gate C2A dan C2B1 masih terbuka. Tidak ada blocker kredensial eksternal;
  sisa pekerjaan adalah backlog implementasi yang belum dikerjakan.
- **Observasi worker refresh <5 detik: 2,53 detik, acceptance PM PENDING REVIEW.**
  Worker melaporkan emulator → Go `/tmp/pam-gate.db`, DB sebelum satu row dan sesudah
  tap Simpan UI `1 transaksi`; DB transactions=1, revision=0. Bukti ini perlu mengikat
  fresh action/key/record baru dan membedakan record revision dari month revision.
  Snapshot DB saat review PM berikutnya berisi 3 rows dan month revision 3, sehingga
  tidak merekonstruksi kondisi historis percobaan tersebut.
- **Gate lost-response retry: OPEN / NOT PASS.** Percobaan fault injection lokal
  (proxy 8081 dan hook sementara response-drop) tidak menghasilkan bukti yang
  konsisten. UI success/list saja belum membuktikan fault gagal atau gate lulus;
  retry otomatis bisa sah bila trace memperlihatkan drop lalu replay key/body sama.
  Hook/proxy sementara worker sudah dihapus dan tidak masuk source. Gate Android
  masih OPEN sampai evidence konsisten tersedia.
- Setelah gate ini, import job/batch/resume, pagination/filter, update/delete,
  S3/S4 **belum diimplementasikan** pada resume ini.

## Dukungan QA PM setelah review ceea72d

- Proxy standalone ada di `scripts/qa/committed_response_proxy.py`, bukan hook produksi.
- Smoke Go + proxy + Python client PASS: 0/0/0 → commit dengan respons hilang 1/1/1 →
  replay HTTP 200 tetap 1/1/1 (rows/month revision/idempotency). Android UI NOT RUN oleh PM.
- Runbook dan acceptance trace: `QA_C2A_COMMITTED_RESPONSE_LOSS.md`; review `reviews/PM_REVIEW_CEEA72D.md`.
- Login akun asli dilakukan pemilik sendiri. QA fault injection/clear-data/deletion tetap
  hanya pada akun/AVD/database sintetis terpisah; jangan memakai password utama Gmail sebagai password PAM.

## Resume Livin parser/import — 22 November 2026

- **Parser fix PASS:** `Tanggal Transaksi` + `Waktu Transaksi` separate cells
  menghasilkan `2026-11-19T02:15:20Z`; synthetic MIME regression
  `LivinMimeImportTest` lulus. WITA/WIT dan pasangan tidak lengkap ditolak.
- **Server import upload PARTIAL:** `ServerImportUploader` membuat Go job,
  mengirim batch kandidat dan finalisasi setelah parse lokal. Unit test
  `ServerImportUploaderTest` PASS. Belum dibuktikan end-to-end di emulator
  dengan email fixture/Gmail because no run was completed; counts/reimport/resume
  UI belum diverifikasi.
- **C2A gate refresh PASS** (2,53 detik).
- **C2A lost-response gate masih OPEN**; fault harness tidak konklusif.
- Android fresh: **250 tests PASS**, assemble/lint 0 error. Go: 42 PASS (backend
  unchanged on this resume).
- Pagination/filter, import resume UI, update/delete, S3/S4 tetap belum.

## Resume import acceptance — 22 November 2026

- **Parser strict PASS:** impossible 31 Nov, 29 Feb non-leap, 24:00, missing time and
  WITA/WIT rejected; leap day, split WIB and NBSP accepted. 7 MIME/parser tests.
- **C2A lost-response Android gate PASS:** QA proxy `18081` → Go `18080`; attempt 1
  upstream 201 then `dropped_before_headers`; UI retained form/error; attempt 2 same
  key/body hash got 200/result hash same; DB stayed transactions=1, month revision=1,
  idempotency=1. Evidence `docs/evidence/c2a_android_lost_response_gate2.jsonl`.
- **Server import → DB → UI PARTIAL/PASS for synthetic API path:** one import job/batch
  with candidate fp `b…`, 2026-11-19T02:15:20Z, amount 175000, imported=1; app
  restarted and Overview showed **Rp350.000** (manual 175k + imported 175k), coverage
  **Lengkap**, Transactions showed **2 transaksi**. This is API→Go→DB→UI, not a real
  Gmail fetch/uploader end-to-end.
- **Reimport count defect observed:** sending the same batch to the finalized job
  returned imported=1/duplicates=0 while DB stayed 2 rows. Counts/finalization
  invariant is not trusted; **OPEN defect**, not PASS.
- Upload recovery journal/retry code added; not yet exercised end-to-end after a true
  server upload failure/restart. **PARTIAL**.
- Fresh Android: **251 PASS**, assemble/lint 0 error/71 warning. Go fresh tests PASS.

### Correction — reimport evidence

The earlier `imported=1/duplicates=0` observation was a **replay of the same
batch key** on a finished job, not a reimport. A true reimport uses a new job
and returns `imported=0, duplicates=1`, while the DB remains 2 transactions.
The batch replay result is correct idempotency behavior; the counts invariant is
PASS for this synthetic API path. Upload recovery after a true Android upload
failure/restart is still not exercised.

## CR-003 resume — Gmail persistence and merchant categories

- **Owner-scoped Gmail vault PARTIAL/PASS unit:** v2 keys are `ownerId`-scoped; legacy global v1
  is ignored. Ordinary sign-out now closes transport, marks connection DISCONNECTED, and preserves
  credential/metadata; explicit disconnect/withdrawal/deletion clears only that owner. A/B isolation
  and logout-preserves-credential tests pass. Emulator A link→logout→login A→restart and A→B→A are
  **NOT RUN**; connection auto-revalidation after login is not yet implemented.
- **Merchant classifier PASS unit:** Unicode/case/punctuation/whitespace normalization with whole
  token/phrase matching; `BAKSO UJI`, `MIE AYAM UJI`, `COFFEE UJI`, `KOPI UJI` → FOOD;
  `TOKO KOSMETIK UJI`, `CHAIR STORE UJI`, `KOPIAH UJI` → UNCATEGORIZED. Existing transfer/other
  rules preserve order. No real merchant data used.
- **Category Go round-trip PARTIAL:** uploader still sends categoryId but not separate category
  confidence/rule version/reason; Go hardcodes IMPORTED origin and has no reclassification endpoint.
  Old UNCATEGORIZED rows and USER overrides are **not yet proven**. This remains open.
- Fresh Android: **257 PASS**, assemble/lint 0 error/71 warning. Go not changed.

## CR-003 race/reconnect resume — 22 November 2026

- **Account-switch race guard PASS unit:** credential validation uses the owner captured at action
  start; late success after switching A→B is ignored (no vault save, connection row, label or error
  on B). Vault save returns checked `commit()` success; ConnectGmailUseCase rechecks owner before and
  after authorize.
- **Reconnect path implemented, PARTIAL:** `ReconnectGmailUseCase` is invoked from Overview after
  login/restart when the owner has a stored credential; it reuses the credential, checks server
  consent and revalidates IMAP without an App Password prompt. Explicit disconnect clears the vault
  so it never reconnects. No new auto-import/background sync.
- **Still NOT RUN:** emulator A link→logout→login A→restart, A→B→A labels/credentials, deferred
  queued IMAP search/fetch after switch, and UI button text/state for a stored-credential reconnect.
  `GmailConnectScreen` still shows the credential form for non-CONNECTED; auto reconnect happens
  before the user needs it.
- **Category:** parser/category confidence are now separate in read mapping; Go still does not emit
  categoryConfidence and has no reclassification endpoint, so old-row/USER-override acceptance is
  **NOT DONE**.
- Fresh Android: **258 PASS**, assemble/lint 0 error/71 warning.

## Azure classifier slice — 22 November 2026

- **Typed boundary PASS unit:** separate `categoryllm` package sanitizes merchant
  (max 120 chars, rejects email/URL/control/account-like digits), allows only verified direction,
  method and built-in category codes, batches max 5, strict schema output and semantic
  compatibility. Injection/unknown/duplicate/invalid-output tests PASS.
- **Config fail-closed PASS unit:** enabling requires Azure endpoint/key/deployment and verified
  prompt/completion prices; daily/monthly/attempt caps are parsed but **not enforced**.
- **Consent PASS unit/UI:** independent `AI_CATEGORIZATION` purpose, notice v2 explicitly names
  Microsoft Azure/GlobalStandard and its non-Indonesia/non-zero-retention limits; untouched means
  DECLINED. Consent history supports the third purpose.
- **Cloud smoke FAIL on this fresh run:** official `classification_smoke.py` got an HTTP 200
  response whose content failed `missing_or_duplicate_case`; the historical PM smoke PASS is not
  reused. The Go adapter against Azure returned no usable result in the subsequent synthetic probe
  (network EOF/invalid output). No runtime endpoint/job/apply, budget reservation or Android
  fixture→Azure→DB→UI path exists yet. `CATEGORY_LLM_ENABLED` remains false; no cloud data was sent.
- Fresh Go tests for changed packages PASS; Android full suite/lint was run before this Azure
  backend slice; no Android runtime import was re-run after it.

## Azure runtime closure attempt — 22 November 2026

- Fixed stable caseId/api-key/strict schema/truncation; synthetic Azure smoke now PASS with
  measured usage.
- Added global monthly budget, reservation IDs, measured/estimated/unknown ledger, LLM provenance,
  direction allowlists, get-or-create/claim jobs and deletion cleanup.
- Backend local Azure E2E PASS for one synthetic BAKSO transaction; Android list reads LLM origin.
- NOT PASS: Android tap→Azure→UI clean run, durable worker/lease recovery, coalescing, retry,
  stale revision/409 tests, owner B isolation tests, benchmark. S2 remains PARTIAL.
  — Item-item "NOT PASS" di atas (kecuali benchmark) telah dituntaskan dan diprove di
  section "Android E2E full — 23 November 2026" di bawah: tap→Azure→UI, cache/coalescing,
  retry-once, recovery/lease, stale revision/409, owner-B isolation (integration test
  `category_pipeline_test.go`), USER override end-to-end.

## Android E2E full — 23 November 2026 (real Azure, real emulator, fixture-only)

Environment: debug APK `PAM_BACKEND_BASE_URL=http://127.0.0.1:18090` + `adb reverse`,
backend UAT berdiri di `:18090` dengan `CATEGORY_LLM_ENABLED=true` (Azure gpt-oss-120b,
prices TERVERIFIKASI terhadap Retail Prices API — lihat `docs/evidence/azure_price_verification_2026-11-22.md`),
akun fixture `e2e.uat.1122@pam-uat.test`, 3 consent AI GRANTED. Tidak ada data pemilik.

- **Alur utama PASS:** 5 transaksi EMAIL IMPORTED bulan 2026-11 → tap "Kategorikan dengan AI"
  → POST /v1/category-jobs 200 `ms:7936` (2 panggilan provider untuk 4 kandidat) → banner
  app `Hasil kategorisasi: classified=3 needsReview=1 failed=0`; Ringkasan: Makanan ×2 Rp380,
  Transportasi Rp3.500, "1 belum berkategori". DB/API: kategori food/transport, origin LLM,
  reason merchant_match; TAGIHAN → NEEDS_REVIEW.
- **Persistence after relaunch PASS:** force-stop + start ulang → hanya GET, tidak ada re-call
  maupun job ulang; hasil menetap.
- **Cache/coalescing PASS:** transaksi ke-6 (KOPI#2) diimpor via import job → tap Kategorikan
  → POST 200 `ms:821` (1 panggilan provider), kategori food dari cache (`category_cache`,
  0 panggilan provider); DB origin LLM + reason merchant_match.
- **USER override PASS (dengan temuan + perbaikan):** PATCH API transaksi BAKSO ke kategori
  "shopping" → ditemukan BUG di runtime: response tetap `categoryOrigin: "LLM"` dan
  `categoryReason: merchant_match` (kolom classifier_origin/category_reason/dll tidak di-reset
  oleh `Store.UpdateTransaction`) → **diperbaiki**: saat kategori diubah user, seluruh provenans
  LLM dibersihkan (origin/confidence/reason/rule/taxonomy), guard USER pada ApplyLLMCategory
  tetap menolak overwrite; test `TestUpdateTransactionClearsClassifierProvenance` + unit test
  `TransactionReviewTest` (chip "Perlu tinjauan" tidak lagi muncul untuk pilihan user).
  Setelah perbaikan: PATCH → `categoryOrigin: USER`, reason kosong, rev 4; app menampilkan
  "Belanja" di daftar dan "Asal kategori: Dipilih pengguna" di detail tanpa chip review.
- **Override dikecualikan dari AI PASS:** tap Kategorikan setelah override →
  banner `classified=0 needsReview=1 failed=0` — BAKSO (USER) tidak dikirim (filter Android
  origin==USER + guard backend); TAGIHAN tidak dikirim ke provider karena
  `not_eligible_personal_transfer` (merchant berbentuk transfer — gate privasi runtime
  TERBUKTI bekerja; tidak ada nama/tanggal/amount yang keluar). API readback: BAKSO tetap
  shopping/USER rev 4 — tidak pernah ditimpa.
- **Observability:** route /v1/category-jobs kini tercatat sebagai `/v1/category-jobs`
  (bukan `{unknown}`) setelah `sanitizedRoute` dilengkapi.
- **Suite fresh PASS:** Go `test -race ./...` semua paket PASS; `go vet ./...` bersih;
  `go build ./cmd/api` OK; Android `testDebugUnitTest` 262 PASS, `assembleDebug` OK,
  `lintDebug` 0 error.
- **Tetap TERBUKA / jujur:** benchmark akurasi (≥95% auto precision berlabel manusia,
  eval disjoint-merchant, per-class F1) TIDAK DILAKUKAN — hanya REVIEW-SUGGESTION opsional.
  36 email event-time malformed tetap backlog parser terpisah (tidak menebak tanggal).
  Override manual TIDAK tersedia lewat UI Android pada build server-mode
  (by design `server_edit_not_available`; jalur USER override = API). Chip review memakai
  ambang konservatif 0.60 (LLM tidak mengirim confidence; kategori USER dikecualikan).

## Perbaikan hasil review a73bb38 — 23 November 2026

Review PM menemukan empat cacat nyata pada build `a73bb38`. Semuanya diperbaiki dengan
regresi yang mengunci perilakunya.

### 1. Pemetaan hasil klasifikasi berdasarkan `caseId`, bukan posisi array
- **Defect:** `classifyGroup` menulis `resultByShare[shares[i]] = res` (posisional). Output
  provider yang valid tetapi urutannya dibalik/diacak menempelkan kategori ke transaksi
  yang salah — PM mereproduksinya dengan output valid yang dibalik.
- **Perbaikan:** output divalidasi (`ValidateResults`: cakupan tepat, unik, rentang sah,
  kode diizinkan, `ReasonCode` ≤40 tanpa CRLF, `NeedsReview` ⇒ uncategorized), lalu dipetakan
  lewat `resultByCase[CaseIndex(caseId)]` → indeks share di grup request → semua transaksi
  koalesen. Output tidak valid → job `FAILED invalid_output` (bukan apply sebagian);
  `caseId` hilang/duplikat/asing ditolak; `MappingVersion = "caseid/1"`.
- **Regresi:** `TestCaseIDMappingIsNotPositional` (output dibalik → kategori benar per
  transaksi), `TestCaseIDMappingHandlesSplitBatches` (batch pecah lintas request),
  `TestCaseIDMappingRejectsInvalidSets` (missing/duplicate/out_of_range/malformed).
- **Cache lama:** baris `category_cache` pra-perbaikan punya `mapping_version = ''` sehingga
  otomatis miss (`CategoryCacheGet` menyaring `mapping_version = ?`), baris baru ditulis
  `caseid/1`. Tidak ada data yang dihapus. `TestCaseCacheIgnoresPreMappingVersionRows`
  menyuntik baris basi, membuktikan upgrade + kategori USER tidak pernah dikirim ulang/ditimpa.

### 2. Orphan sweep atomik
- **Perbaikan:** `SweepOrphanCategoryReservations` mengumpulkan orphan lalu setiap reservasi
  diselesaikan dalam SATU transaksi DB: `UPDATE ... WHERE id=? AND state='RESERVED'`
  (transisi bersyarat) + pelepasan seluruh counter owner/global harian/bulanan + COMMIT
  (rollback penuh jika salah satu gagal).
- **Regresi:** `TestSweepOrphanIsAtomicUnderCounterFailure` memakai trigger SQLite yang
  menggagalkan update counter instalasi → sweep error, reservasi tetap `RESERVED`, semua
  counter utuh; setelah trigger dibuang sweep sukses tepat sekali (0.10). 
  `TestSweepOrphanConcurrentWithSettlement` (8 run, sweep vs settle serentak) → biaya akhir
  0.10 bila UNKNOWN, 0.06 bila settle menang; `reserved` selalu 0, tidak ada double charge.

### 3. Lease fencing lengkap
- **Perbaikan:** setiap claim/reclaim menerbitkan `lease_token` baru (`NewID("catlease")`).
  Semua tulisan hasil (finish, requeue, apply kategori, cache put) disyaratkan
  `state='RUNNING' AND lease_token=? AND lease_until_unix > now` (dibandingkan numerik agar
  tidak salah urut karena pecahan detik RFC3339). `ErrLeaseLost` bukan 5xx: worker membaca
  ulang baris terkini dan berhenti.
- **Regresi:** `TestLeaseFencingRejectsStaleWorker` — A claim → lease kedaluwarsa → B reclaim
  (token berbeda) → finish/requeue/apply/cache-put A semuanya `ErrLeaseLost`, job & revisi
  transaksi tidak berubah, tidak ada cache yang ditulis; B apply+finish → terminal
  `CLASSIFIED/food`; A yang kembali terlambat tetap ditolak.

### 4. Koreksi kategori lewat Android (server mode)
- **Perbaikan:** `ServerFinanceRepository.updateCategory` → `PATCH /v1/transactions/{id}`
  dengan `{categoryId, revision}`; detail ViewModel memuat kategori server, mengirim revisi
  yang dimuat, dan pada `409` menampilkan konflik lalu me-refresh revisi agar tap berikutnya
  sukses. Amount/date tetap read-only (tidak ada use case yang menulisnya).
  Status "saran belum dikonfirmasi" dipisah dari keyakinan rendah:
  `hasUnconfirmedSuggestion = origin LLM && kategori bukan uncategorized`, chip "Saran AI";
  `isLowConfidence` tidak lagi dipakai untuk baris LLM/USER. Ambang 0.60 dinyatakan tegas
  sebagai ambang tinjauan parser/impor, bukan probabilitas akurasi.
- **E2E Android (emulator, backend UAT :18090, tanpa panggilan Azure baru):**
  1. Baris `RENTAL MOBIL UJI CARD` (origin LLM, transport) → detail menampilkan chip
     "Saran AI", teks "Kategori ini saran AI dan belum dikonfirmasi...", dan hint 0,60.
  2. Tap "Kesehatan" → `PATCH 200`; API readback `health / USER / rev 2`; chip "Saran AI"
     hilang, label asal menjadi pilihan pengguna.
  3. Force-stop + relaunch → daftar & detail tetap "Kesehatan" tanpa chip saran (persisten).
  4. `POST /v1/category-jobs` untuk transaksi itu → `NEEDS_REVIEW / not_eligible`, kategori
     tetap `health / USER / rev 2` (AI tidak menimpa; tidak ada panggilan provider).
  5. Konflik: revisi dinaikkan dari luar (rev 3→4→5), lalu tap kategori dengan revisi lama →
     `PATCH 409` di log backend, banner "Terjadi konflik data." tampil, app me-refresh;
     tap ulang sukses → `shopping / USER / rev 6`.
- Regresi unit: `ServerFinanceRepositoryImplTest` (PATCH mengirim `categoryId`+`revision`,
  409 → `AppError.Conflict`), `TransactionReviewTest` (LLM = saran, bukan keyakinan rendah).

### Gate yang dijalankan ulang (23 November 2026)
- Go: `test -race -count=1 ./...` → auth, categoryllm, config, crypto, finance, httpapi,
  store semua `ok`, exit 0, nol `DATA RACE`; `go vet ./...` bersih; `go build ./...` OK;
  `test ./internal/httpapi ./internal/store` PASS.
- Android: `testDebugUnitTest` PASS, `assembleDebug` OK, `lintDebug` BUILD SUCCESSFUL
  (4 error `MissingTranslation` untuk string baru diperbaiki dengan terjemahan `values-en`).
- **Migrasi 012 di DB UAT nyata:** binary baru dijalankan ulang di :18090 terhadap Turso
  UAT; server start bersih (kolom `lease_token`, `mapping_version`, `lease_until_unix`
  terpasang), 5 transaksi UAT utuh, dan `POST /v1/category-jobs` (jalur tulis job ber-lease)
  merespons 200 di 146 ms tanpa error worker.
- Catatan build: Gradle native file-watcher (FSEvents) menggantung build di host ini sebelum
  satu task pun jalan; `org.gradle.vfs.watch=false` ditambahkan agar build deterministik.
- **Masih TERBUKA (saat itu):** benchmark akurasi berlabel manusia (gate terpisah, tidak diklaim),
  36 email event-time malformed, S2 tetap PARTIAL. Auto-sync 30 menit / batch kategori 2 jam /
  indikator top-bar saat itu BELUM diimplementasikan; lihat bagian berikutnya.

## Auto-sync inkremental, antrean kategori, dan status UI — 23 November 2026

Rujukan: `docs/plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md`, `docs/changes/CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md`.
Commit: `f3e00ed` (delta sync), `7ff5e7f` (antrean kategori server), `7d6a2de` (klien antrean +
WorkManager + indikator/sheet + hidrasi status), semuanya di `feat/c2-android` dan sudah dipush.

### Yang diimplementasikan
- **Sinkronisasi inkremental (P1):** IMAP dan parser tetap di Android; cursor durable hanya
  menyimpan `uidValidity` + satu `highestUid` per pemilik (dibungkus at rest, dibersihkan saat
  logout/ganti pemilik/penghapusan). Delta dibatasi watermark yang diambil sebelum run, tidak ada
  rescan sebulan penuh. Commit finansial lokal + ack server sebelum cursor maju; upload idempoten
  per job/halaman (`ImportUploadJournal`); single-flight `Mutex` berbagi manual/foreground/background;
  email terlambat dirutekan ke bulan transaksinya; perubahan `uidValidity` membatalkan cursor.
- **Penjadwalan (P2/P3):** WorkManager periodik 30 menit untuk sync dan 2 jam untuk kategori,
  constraint jaringan, `ExistingPeriodicWorkPolicy.UPDATE`, unique work per tag, backoff eksponensial
  30 menit. Foreground hanya menjalankan satu delta bila cursor lebih tua dari cooldown 5 menit.
  Tidak ada IMAP permanen maupun foreground service. `SignOut`, `DisconnectGmail` dan
  `RequestDeletion` membatalkan kedua worker, menghapus cursor, dan mereset holder status;
  hasil yang telat untuk pemilik lama tetap dibuang (guard sebelum/dan sesudah upload).
- **Antrean kategori otomatis (P4):** kandidat hanya transaksi `uncategorized`, bukan origin USER,
  bukan transfer internal; enqueue dipanggil setelah commit+ack (bila ada baris baru) dan oleh
  worker 2 jam. Server yang menentukan jendela 2 jam (`category_queue_state.next_drain_at`),
  maksimum 5 merchant unik per request, sisa tetap PENDING, cache/USER menang, dan penolakan
  budget me-requeue alih-alih menggagalkan job. Antrean kosong dijawab `state:"empty"` tanpa
  menyentuh provider; poll terlalu awal dijawab `not_due` tanpa provider. Plafon biaya tetap
  USD0,10/hari dan USD2/bulan, 20 attempt/pemilik/hari, 100/instalasi/hari.
- **Status UI (P5):** pil di top bar Ringkasan/Transaksi/Analisis/Pengaturan + sheet
  "Status pembaruan" dengan tiga bagian (Data transaksi, Kategori, Perlu perhatian). Status
  diturunkan dari holder durable, bukan timer: `Memeriksa/Mengambil/Menyimpan/Mengategorikan`
  hanya menyala saat kerja nyata, `Terakhir berhasil` berbeda dari `Percobaan terakhir`, upload
  yang belum di-ack tidak pernah dilaporkan sebagai sukses, dan masalah kategori tidak pernah
  dilaporkan sebagai masalah Gmail. Membuka sheet tidak memulai impor maupun panggilan Azure;
  ikon + kata (bukan warna saja), target ≥48 dp, `contentDescription` berisi status, dan pada
  font ≥150% label disembunyikan sementara ikon/status suara tetap ada.
- **Hidrasi status:** `IncrementalSyncUseCase.hydrate(ownerId)` mengisi holder dari cursor lokal
  saat proses start, sehingga panel tidak lagi berkata "belum pernah" padahal cursor menyimpan
  keberhasilan; hidrasi tidak menyentuh IMAP/HTTP.
- **Observability:** `/v1/category-jobs/enqueue` dan `/v1/category-jobs/drain` tidak lagi
  dilipat menjadi `/v1/category-jobs/{id}` di log request, sehingga run otomatis terlihat.

### Bukti uji
- **Go (dijalankan ulang 23 November 2026):** `test -race -count=1 ./...` exit 0 untuk
  auth, categoryllm, config, crypto, finance, httpapi (93,6 s), store (15,3 s);
  `go vet ./...` bersih; suite baru
  `internal/httpapi/category_queue_test.go` (consent + idempotensi + USER override; jendela 2 jam,
  batch ≤5, sisa, cache; plafon 5 merchant; budget → backlog) dan
  `sanitized_route_test.go` (label route).
- **Android unit:** **304 test, 0 gagal** (47 kelas), termasuk
  `AutoCategoryUseCaseTest` (6: hanya kandidat layak; himpunan kosong = 0 enqueue + 1 drain;
  `not_due` = backlog + tanpa spinner; `budget_exhausted` = PAUSED; `Unauthorized` =
  NEEDS_ATTENTION; dua pemanggil paralel = 1 drain), `IncrementalSyncUseCaseTest` (11, termasuk
  hidrasi cursor dan tidak ada body download saat tidak ada email baru),
  `UpdateStatusMapperTest` (9), `UpdateStatusPillTest` (2: label 100% + target 48 dp, dan
  200% menyembunyikan kata namun status tetap diumumkan), `UpdateStatusTopBarTest`
  (judul + pil tetap muat di 360/393/412 dp), `AccountLifecycleCancellationTest` (2:
  disconnect dan penghapusan membatalkan kedua scheduler, menghapus cursor, mereset holder),
  `WorkSchedulerTest` (3: periode 30 menit, periode 2 jam, constraint jaringan, cancel +
  schedule ulang).
- **Lint:** `lintDebug` BUILD SUCCESSFUL, 0 error (88 warning lama, tidak ada di berkas baru).
- **Cacat yang ditemukan dan diperbaiki saat verifikasi ini:** `RequestDeletionUseCase`
  membatalkan worker dan mereset holder tetapi **tidak menghapus cursor durable**; setelah
  login ulang dengan pemilik yang sama, watermark lama akan melewati email yang transaksinya
  baru saja dihapus. Kini `syncCursors.clear(ownerId)` dijalankan sebelum baris dihapus
  (commit `00d7693`), dengan tes regresi.
- **E2E emulator otomatis (fixture-only, tanpa tap "Impor"):**
  `scripts/qa/e2e_update_status.py` menjalankan seluruh alur tanpa langkah manual: force-stop,
  cold start, tunggu status akhir, baca counter route dari log backend, buka sheet, tegaskan
  tiga bagian + pemisahan "percobaan terakhir"/"terakhir berhasil", lalu memastikan jumlah
  request **tidak bertambah** karena sheet dibuka. Output run terakhir:
  `PASS: automated status E2E (no import tap)` (tersimpan di `build/e2e-status-run.txt`,
  tangkapan layar `build/e2e-status/01-pill.png` dan `02-sheet.png`).
  Rincian run manual sebelumnya: instal APK debug
  (`PAM_BACKEND_BASE_URL=http://127.0.0.1:18090`, `adb reverse`), login sesi UAT, lalu buka app.
  Log backend: `POST /v1/category-jobs/enqueue` 200 (148 ms) dan `POST /v1/category-jobs/drain`
  200 (244 ms) tanpa satu pun panggilan provider — jendela 2 jam server menahan drain dan
  antrean kosong tidak memanggil Azure. Pil top bar menampilkan `Terbaru`; sheet menampilkan
  "Percobaan terakhir: baru saja", "Terakhir berhasil sinkron: baru saja", "Tidak ada transaksi
  baru pada pemeriksaan terakhir", "Tidak ada kategori tertunda", dan "Tidak ada masalah yang
  perlu tindakan". Bukti teks: dump uiautomator; gambar:
  `app/build/screenshots/status/*.png` (8 status pil + 6 sheet, termasuk 200% font) via
  `UpdateStatusScreenshotsTest` (`-Proborazzi.test.record=true`), plus
  `/tmp/e2e/status_pill_ringkasan_2026-11-23.png` dan `/tmp/e2e/status_sheet_success_2026-11-23.png`
  dari emulator.

### Yang TIDAK diklaim (tetap terbuka)
- **Tidak ada E2E "email baru benar-benar tiba" di emulator:** perangkat UAT tidak punya kredensial
  IMAP Gmail nyata, jadi jalur delta dibuktikan dengan mailbox fixture + unit test (no-new-mail →
  tanpa body download, crash sebelum/sesudah commit/upload, `uidValidity` berubah, email terlambat,
  pergantian bulan). Yang dijalankan di emulator adalah klien antrean kategori + status UI.
- **Interval 30 menit tidak ditunggu secara nyata:** yang diverifikasi adalah request WorkManager
  (periode, constraint, keunikan, cancel), bukan eksekusi 30 menit dinding jam.
- **"Data tetap terlihat selama refresh" belum diuji dengan run yang sengaja diperlambat:**
  daftar tidak dikosongkan saat sinkron (tidak ada kode yang melakukannya dan `OverviewScreen`
  membaca aliran lokal), tetapi tidak ada bukti E2E dengan delta lambat.
- **Drain dengan panggilan Azure nyata tidak diulang di run ini** demi biaya; batch/cache/backlog/
  budget dibuktikan di Go integration test dengan classifier palsu, dan jalur Azure nyata sudah
  dibuktikan pada E2E 23 November sebelumnya (satu BAKSO, 2 panggilan provider).
- **Benchmark akurasi (≥95% auto precision berlabel manusia) tetap TIDAK dilakukan**; 0,60 tetap
  ambang review, bukan akurasi. 36 email event-time malformed tetap backlog parser terpisah.
- **S2 tetap PARTIAL; S3/S4 tidak otomatis selesai.** Empat temuan review `a73bb38` tetap tertutup
  untuk cakupan regresi lokal.

## Penutupan lane auto-sync — 23 November 2026 (lanjutan)

Commit `f20b098`, `e033de3`, `82a1d22` pada `feat/c2-android` (masing-masing sudah dipush).
Bagian ini menggantikan klaim status AS/BC/UX/OBS di atas bila berbeda; SRS v0.5 DOCX/PDF
tidak disentuh.

### Yang berubah dan buktinya
- **Jendela kategori tidak lagi bisa dilewati recovery** (`f20b098`): migrasi `014` menambah
  `category_jobs.due_at_unix`; recovery hanya mengklaim `PENDING` yang sudah jatuh tempo atau
  `RUNNING` dengan lease kedaluwarsa. Worker server men-drain maksimal 4 owner jatuh tempo per
  tick tanpa poll Android, lalu hanya memulihkan lease kedaluwarsa.
  Bukti: `TestCategoryWindowIsNotSkippedByRecovery`, `TestCategoryWindowDrainsWithoutClientPoll`,
  `TestCategoryWorkerFairAcrossOwners`, `TestCategoryEightyUniqueMerchantsStayBounded`,
  `TestCategoryDuplicateMerchantsShareOneResult`, `TestMigrateTwiceAndLegacyRows`.
- **Cursor hanya maju atas rentang lengkap** (`f20b098`): `SyncOutcome.Incomplete` menyimpan
  cursor percobaan (bukan watermark) saat `PARTIAL`/`FAILED`/`PERMISSION_REQUIRED`/tanpa
  watermark; `cursor.save()` yang gagal berhenti sebagai `Failed("storage_unavailable")` dan
  tidak pernah tampil sebagai sukses. Bukti:
  `a page two failure keeps the cursor and reports a partial range`,
  `a cursor save failure is never shown as durable success`, `a deleted owner is never imported`.
- **Uploader memakai snapshot run dan bulan transaksi sebenarnya** (`f20b098`): kandidat dibaca
  dari `listByImportJob` (bukan query bulan), dikelompokkan per `JakartaMonth.of(eventAt)`,
  kunci batch stabil, jurnal multi-bulan persisten; kegagalan menyimpan jurnal = storage error,
  bukan progres. Bukti: `ServerImportUploaderTest` (email lintas bulan + decoy run lama,
  crash antar-ack bulan, state akhir per bulan).
- **Import manual berbagi satu koordinator** (`e033de3`): `ImportJobViewModel` memanggil
  `IncrementalSyncUseCase.runManualMonth`/`resumeUpload`; gate single-flight, status holder dan
  uploader sama dengan foreground/background. Bukti:
  `a manual import cannot run beside a background sync`, `manual and background runs never import concurrently`.
- **Status jujur** (`e033de3`): `SyncSnapshot`/`UpdateStatusUi` membawa `partial`, `savedCount`,
  `duplicateCount`, `skippedCount`, `unresolvedCount`, `storageFailure`. Mapper memaksa
  `NEEDS_ATTENTION` (tidak pernah "Terbaru") saat rentang belum selesai, cursor gagal disimpan,
  atau ada email belum terbaca; sheet menampilkan hitungan dan alasan berkode. Bukti:
  `a partial range never claims to be up to date`,
  `an unwritable cursor and unresolved mail stay visible in the panel`,
  `an unacknowledged upload is never shown as a completed sync`.
- **Tick scheduler mengimpor email baru tanpa membuka layar Impor** (`82a1d22`):
  baseline tick tanpa body fetch → email baru → tick 30 menit → delta fetch, simpan, upload,
  status `SUCCEEDED`, `savedCount=1`; tick berikutnya tidak membaca body lagi. Bukti:
  `the scheduled tick imports mail that arrived after the baseline without opening the screen`.

### Cleanup / retirement (RETIREMENT_POLICY)
| Artefak | Tindakan | Alasan |
|---|---|---|
| Jalur import/upload milik `ImportJobViewModel` (`ImportMonthUseCase`, `RetryImportUseCase`, `ImportUploader` di konstruktor) | DELETE (diganti `IncrementalSyncUseCase`) | Dua koordinator = risiko import ganda; regresi perilaku ditutup tes gate |
| `TransactionDao.observeByImport`/query per bulan untuk uploader | REPLACE dengan `listByImportJob` | Email lintas bulan bisa salah bulan; tes cross-month mengunci perilaku |
| Status "Terbaru" saat run parsial | REPLACE dengan `AttentionItem(partial_import)` | Label lama menyesatkan |
| Dokumen klaim "jendela 2 jam tidak bisa dilewati" (bagian historis di atas) | KEEP + supersede oleh bagian ini | Jejak keputusan dipertahankan |
| Tes lama drain 6/6 satu jendela | KEEP (diperbarui ke semantik due) | Masih mengunci batas 5 merchant/permintaan |

### Masih terbuka (jangan dibaca sebagai lulus)
- **E2E emulator "email benar-benar tiba" tetap belum ada**: tidak ada kredensial mailbox yang
  bisa disuntik email baru. Bukti delta sekarang berlapis: unit test di atas + Go handler tests;
  belum ada bukti perangkat dengan email nyata masuk.
- **S2.4 pagination/filter/search server di UI**: backend mendukung `q`/`categoryId`/`cursor`,
  tetapi `TransactionsViewModel` masih memuat halaman pertama (50) dan memfilter di memori;
  dataset >100 di UI belum lulus.
- **S2.5 budget/analysis remote + freshness**: belum diwire ke UI.
- **S3 (C01–C15) dan S4 (C16–C25) tetap belum selesai**: belum ada layar CRUD kategori/anggaran,
  lampiran, atau admin; backend juga belum punya endpoint lampiran/admin.

---

## Integrasi final 23 November 2026 (malam) — `feat/c2-android` @ `99cb3df`

Bagian ini menutup sprint S2–S4 pada branch kerja `feat/c2-android` (bukan merge ke
`develop`/`main`; itu tetap keputusan pemilik). Semua paket di bawah sudah melewati
review independen (peran Auditor/QA) sebelum digabung, dan gate dijalankan ulang pada
hasil gabungan, bukan hanya pada branch tugas.

### Paket yang diintegrasikan (urutan merge)

| Commit | Isi | Audit |
|---|---|---|
| `e3eeb17` | Paket 5 — P1-1/P1-2 recovery: identitas run ditulis untuk semua jalur commit finansial (termasuk retry FR-21), snapshot pasca-commit dari `listByImportJob`, pemetaan kode status upload (`pending_snapshot_incomplete`) | Re-audit **LULUS DENGAN CATATAN**; P1-1/P1-2 CLOSED; sisa LOW P5-1 (watermark null sementara tanpa kehilangan data), P5-2 (journal nullable default, test-only) |
| `94e2f37` (merge `f8a52c9`) | Paket 3 — acceptance Android: wiring C10 kategori search + C15 riwayat anggaran, generation guard/debounce, `totalLimitMinor`/`gapMinor`, decode `attributes:null`, bukti perangkat C04/C05/C10/C12/C15/C17/C18/C20, doc evidence | Re-audit **LULUS DENGAN CATATAN**; P3-1..P3-6 CLOSED; dry-run merge bersih; sisa LOW P3R-1 (sitasi, sudah diperbaiki `f8a52c9`), P3R-2/P3R-3/P3R-4 |
| `99cb3df` (merge `4c317eb`) | Paket 4 — S4.4 varian `assessment`: pemisahan Room/SQLCipher/WorkManager ke source-set standard, adapters outbox + repository + durable state Keystore, dedupe global fingerprint, commit() dihormati, test kontrak dependensi | Re-audit **LULUS DENGAN CATATAN**, lalu patch P6-1/P6-2 **LULUS** dan CLOSED; P4-1/P4-2/P4-4/P4-5 CLOSED |

### Gate fresh pada hasil gabungan (`99cb3df`)

- `:app:testDebugUnitTest --rerun-tasks`: **463 tests / 63 classes / 0 failure / 0 error**.
- `:app:testAssessmentUnitTest --rerun-tasks`: **473 tests / 64 classes / 0 failure / 0 error**.
- `:app:lintDebug`: **0 error, 115 warning, 1 hint**; `:app:lintAssessment`: **0 error,
  123 warning, 1 hint**; MissingTranslation 0.
- `:app:assembleDebug` + `:app:assembleAssessment` sukses.
- Go: `test -count=1 ./...` semua paket ok (httpapi 15.6 s, store 1.2 s), `go vet` bersih,
  `go test -race ./internal/store ./internal/httpapi` ok (19.2 s / 173.5 s).
- `assessmentRuntimeClasspath`: 0 entri `androidx.room|sqlcipher|androidx.work|androidx.sqlite`
  (kontrol positif `debugRuntimeClasspath` memuat semuanya); APK assessment 17 dex, 0 string
  terlarang, tanpa lib sqlcipher/sqlite.
- UAT emulator `emulator-5554`, backend `/tmp/pam-be-merged` di `127.0.0.1:18090`
  (`adb reverse`), akun sintetis `e2e.accept.c.6f6f536f@pam-uat.test`.

### Artefak APK akhir (dibangun dari `99cb3df`)

| Varian | Path | Ukuran | SHA-256 penuh | Konfigurasi |
|---|---|---|---|---|
| debug (standar, diuji perangkat) | `app/build/outputs/apk/debug/app-debug.apk` | 45.932.500 B | `24b768c9cf40a6d466d697baee0f96db95b46427e6d8ad145410b7512bc189c6` | `PAM_BACKEND_BASE_URL=http://127.0.0.1:18090`, `versionName=1.0.0`, package `com.pam.recap.debug` |
| assessment | `app/build/outputs/apk/assessment/app-assessment.apk` | 23.567.872 B | `b5eec2acb7caa901fed789a19406bc4ce438e91826f85eb480ae7711d3a2021b` | `PAM_BACKEND_BASE_URL=http://127.0.0.1:18090`, package `com.pam.recap.debug` (tanpa suffix) |

Catatan: APK debug di atas di-install ulang pada emulator dan smoke test berhasil memuat
ringkasan server (Rp4.706,95 / 8 transaksi November) serta daftar transaksi halaman pertama
(50 baris, footer "50 transaksi dimuat; masih ada halaman berikutnya."). APK yang dipakai
worker C untuk walk perangkat penuh adalah `cc6eef95…` dari commit branch yang isinya kini
sudah tergabung; angka sha di atas adalah artefak final hasil gabungan.

### Bukti perangkat (ringkas; detail di evidence)

`docs/evidence/UAT_S3_S4_DEVICE_2026-11-23.md` (baru): C04 delete 204 tanpa resurrect,
C05 search + halaman kedua 85/86 baris, C10 kategori `q` menyempit 11→1, C12 realisasi vs
rencana, C15 riwayat periode 200 + limit/selisih, C17 metadata setelah restart, C18 rename
200, C20 search match/empty. C01 tetap PARTIAL (create terpanggil tetapi 400 karena field
lama; tidak ada 201 di perangkat), C24/C25 tidak dijalankan di perangkat karena walk admin
akan mencabut sesi bukti — tetap API/unit.

### Branch cleanup (wajib, sudah diverifikasi di remote)

Dihapus setelah integrasi + verifikasi remote: `work/se-recovery-fix`,
`work/se-android-acceptance`, `work/se-assessment-variant` (lokal dan `origin`), termasuk
worktree `pam-wt-recfix`, `pam-wt-accept`, `pam-wt-assess`, `pam-wt-audit`. `git ls-remote
--heads origin` sekarang tidak memuat branch `work/*`; tersisa `feat/c2-android`,
`feat/go-backend`, `develop`, `main`. Tidak ada force-push; `main` dikecualikan.

### Masih terbuka (bukan lulus)

- **C01 create manual di perangkat** belum terbukti (status PARTIAL).
- **C24/C25 walk perangkat** belum; last-admin guard, filter status, cursor hanya API/unit.
- **Temuan LOW audit yang diterima sebagai follow-up**: P5-1/P5-2 (recovery), P3R-2
  (server `validateAttributes` masih menerima nilai non-string sementara decoder Android
  strict), P3R-3 (list kategori non-search mengabaikan `nextCursor`), P3R-4 (hint truncation
  belum ada bukti visual), P6-3/P6-4/P6-5 dan P4b-1/P4b-2 (consent hidrasi ownerId kosong,
  jalur 401 belum dites, owner-index import diabaikan, `clear()`/`deleteAll()` belum
  runCatching).
- **Gate eksternal tetap**: mailbox nyata + email baru tiba, benchmark berlabel manusia,
  36 email malformed, UAT pemilik, hosting/backup-restore, keputusan merge ke `develop`/`main`
  dan deployment.
- **S4.4 batas by-design**: sinkronisasi foreground-only (tanpa WorkManager/background sync),
  tanpa `applicationIdSuffix` (tidak bisa coexist dengan build debug), tidak menyimpan data
  finansial lokal (server-backed + encrypted outbox; consent/Gmail/import metadata durable).

## Penutupan gap S2–S4 — merged `7337559` (23 November 2026, malam)

Bagian ini menutup pekerjaan gap atas hasil integrasi `99cb3df`: tiga lane terakhir
digabung ke `feat/c2-android`, diaudit independen, lalu diverifikasi ulang pada checkout
utama dan perangkat. Tidak ada merge ke `develop`/`main` dan tidak ada deployment.

### Paket gap yang diintegrasikan

| Commit | Isi | Audit |
|---|---|---|
| `a512770` | Lane C24/C25 — admin: deaktivasi + pencabutan sesi, last-admin guard, pencarian/filter/paging/cursor, USER ditolak; evidence `UAT_GAP_ADMIN_2026-11-23.md` | Walk runtime teraudit; regression merged lulus |
| `aa61a7b` (merge `03d29ab`) | Lane C01 — bukti create manual bersih + identitas retry lost-response (doc + test kontrak; tanpa ubah perilaku produksi) | Audit C01: LULUS DENGAN CATATAN (sitasi diperbaiki; replay tetap unit/API) |
| `7337559` (merge `5fe623c` + `2bc5cb6`) | Lane S2.5 — analisis mode server: wiring ringkasan/analisis remote, freshness CURRENT/STALE/HISTORICAL, regenerasi, guard consent lokal, decode `suggestions:null` | Re-audit B: LULUS DENGAN CATATAN; B-RE-1/2/3 CLOSED |

Urutan di atas bukan urutan merge kronologis lengkap; lane C digabung lebih dulu.

### Gate fresh pada merged head `7337559`

- `:app:testDebugUnitTest --rerun-tasks`: **493 tests / 68 classes / 0 failure / 0 error / 0 skip**.
- `:app:testAssessmentUnitTest --rerun-tasks`: **503 tests / 69 classes / 0 failure / 0 error / 0 skip**.
- `:app:lintDebug`: 0 error, 115 warning, 1 hint; `:app:lintAssessment`: 0 error, 123 warning, 1 hint.
- `:app:assembleDebug -PPAM_BACKEND_BASE_URL=http://127.0.0.1:18090` + `:app:assembleAssessment` sukses.
- Go: `test -count=1 ./...` ok, `go vet` bersih, `build` ok, `-race ./internal/store ./internal/httpapi` ok.

### Artefak APK merged head

| Varian | Ukuran | SHA-256 | Konfigurasi |
|---|---|---|---|
| debug | 46.709.962 B | `6497a4066a32b00308fe7ee44291bcb713e039d54abd3909709a78c9b5e5db16` | `PAM_BACKEND_BASE_URL=http://127.0.0.1:18090` |
| assessment | 24.344.958 B | `fa0d224e7591400354184129c2096533c87cc4334dfa6218be22baa002d44fd7` | `PAM_BACKEND_BASE_URL=http://127.0.0.1:18090`, package sama tanpa suffix |

### Acceptance merged head (ringkas)

- **C01 PASS runtime** — create manual via UI UAT `18090` → `POST /v1/transactions` 201
  (23 Nov 20:33:56 WIB), ringkasan 92/89 → 93/90; cross-check DB terisolasi `tx_471df9b6…`.
- **S2.5 PASS runtime** — CURRENT → revision-STALE → regenerate → CURRENT →
  dependency-STALE (pasca sync foreground menulis import-job EMPTY), dengan guard coverage
  EMPTY yang memang dirancang. Detail dan batas lingkungan di
  [UAT_MERGED_HEAD_2026-11-23.md](evidence/UAT_MERGED_HEAD_2026-11-23.md).
- **C24/C25 PASS runtime** — walk teraudit + regresi merged; walk perangkat last-admin/cursor
  tidak diulang (batas dicatat).
- **S4.4 assessment** — 503 test hijau, APK sha `fa0d224e…`; batas by-design tetap
  foreground-only sync, tanpa `applicationIdSuffix`, tanpa data finansial lokal.

### Catatan tindak lanjut (bukan blocker merge ini)

- **INFO**: sinkronisasi foreground tanpa mailbox Gmail terhubung menulis import-job `EMPTY`
  yang mengubah dependency hash/coverage sehingga analisis tersimpan menjadi STALE dan
  regenerasi berikutnya tertahan guard `MONTH_HAS_NO_DATA`. UI sudah menampilkan status
  server dengan benar; pertanyaan desain (apakah job nol-halaman harus masuk dependency
  hash/coverage) diserahkan sebagai keputusan backend lanjutan.
- **LOW** yang tetap dibawa: P5-1/P5-2, P3R-2/P3R-3/P3R-4, P6-3/P6-4/P6-5, P4b-1/P4b-2,
  sitasi aset C01 lama, idempotency replay unit/API-only, dan copy penyedia on-device pada
  mode server.

### Cleanup

`git worktree list` hanya menyisakan checkout utama; branch `work/*` tidak ada lagi lokal
maupun di `origin`; `origin/feat/c2-android == 7337559`. Tidak ada force-push; `main`
dikecualikan.

### S5 — gate eksternal yang tetap terbuka

Mailbox nyata + email baru tiba, benchmark berlabel manusia, 36 email malformed, UAT pemilik
di perangkat, hosting/backup-restore, serta keputusan merge/deploy ke `develop`/`main`.

## Revisi UI sinkronisasi + kategori Livin — merged `a310067` (23 November 2026, malam)

Mandat PM kali ini merevisi permukaan UI/UX dan pesan alasan kategori, bukan menambah aksi
baru. Dua lane dikerjakan paralel, digabung, lalu diverifikasi ulang pada APK hasil merge.

### Commit

- Lane UI: `9aeea28` (merge `work/ui-summary-sheet` @ `1362c11`,
  `feat(android-ui): unify sync status sheet and simplify Ringkasan`).
- Lane kategori: `dda4084` (merge `work/category-livin` @ `5451db9`,
  `test(category): cover detail reference flag and correct evidence citations`).
- Perbaikan pasca-merge: `a310067` — 7 string Inggris untuk alasan tinjauan + notis nomor
  referensi (memperbaiki 7 `MissingTranslation` yang lolos karena lint lane kategori tidak
  dijalankan setelah string baru masuk).
- Semua commit dipush ke `origin/feat/c2-android`; `main` tidak disentuh.

### Hasil gate merged `a310067`

- Android: debug **509** tes / assessment **519** tes, 0 gagal; `lintDebug` 0 error
  (112 warning, 1 hint); `lintAssessment` 0 error (120 warning, 1 hint);
  `assembleDebug`+`assembleAssessment` PASS; BuildConfig `http://10.0.2.2:18090`.
- Go: `test -count=1 ./...`, `vet`, `build`, dan `-race ./internal/store ./internal/httpapi`
  PASS.
- Audit independen: verdict awal **FAIL** karena gate lint merged (B1) — diperbaiki,
  re-audit menyusul dokumen ini; laporan auditor di
  [AUDIT_MERGED_UI_CATEGORY_2026-11-23.md](evidence/AUDIT_MERGED_UI_CATEGORY_2026-11-23.md).

### Bukti runtime perangkat

Ringkasan bersih; judul terpusat (393 dp 540,0 px; 360 dp + font 200% 540,5 px); ikon status
48 dp; satu sheet tanpa request saat dibuka; satu tap = satu job impor; hasil sama setelah
tutup/buka dan minimize/relaunch; alasan kategori tampil di Android (`classified=0
needsReview=5 failed=0`, 0 panggilan Azure). Rincian: [UAT_MERGED_UI_CATEGORY_2026-11-23.md](evidence/UAT_MERGED_UI_CATEGORY_2026-11-23.md).

### Batas dan NOT RUN (jangan dibaca lulus)

Bingkai running/progress live, fade label live, TalkBack live, baris back+judul panjang dan
412 dp di perangkat, notis nomor referensi di detail owner, persistensi banner alasan setelah
restart, resume unggahan pending live, kategori AI terklasifikasi pada data owner, precision
≥95% (tanpa benchmark berlabel), "Buat Analisis" via Ollama dengan bulan EMPTY-coverage,
probe deep-link route impor lama.

### Gap/dokumen

Draf perubahan kebutuhan: [CR_006](changes/CR_006_DRAFT_SYNC_UI_CATEGORY_REVIEW.md) (melengkapi
CR-005); SRS resmi tidak diubah. Matriks acceptance diberi addendum bukti; UAT UI dan UAT
kategori diberi addendum pasca-merge.

### Cleanup

Worktree lane dan branch `work/*` dihapus setelah integrasi terverifikasi; hanya checkout
utama yang tersisa; `origin/feat/c2-android == 718eaf7` (verifikasi via `git ls-remote`); branch `work/*` dan worktree-nya sudah dihapus lokal+remote, `main` tetap `c10ca67`.

### S5 tetap terbuka

Mailbox nyata + email baru otomatis, benchmark berlabel manusia (precision), 36 email
malformed, UAT pemilik penuh, hosting/backup-restore, serta keputusan merge/deploy.

## Penutupan P0 owner 24 November 2026 — `feat/c2-android` @ `3b8a038`

Dua P0 yang dimandatkan pemilik dikerjakan, diuji, dan di-push; gate di bawah adalah keputusan
PM berdasarkan bukti yang ada, bukan status "source sudah ditulis".

### Paket perubahan

- Navigasi Transaksi satu sumber bulan: `0030a43` — `tabNavigationSpec` memakai
  `popUpTo(Overview)` non-inclusive + `singleTop`, `ActiveMonthStore` sebagai sumber bulan aktif,
  test `TabNavigationSpecTest` (3) dan `TransactionsTabMonthTest` (5). Bukti UI gabungan:
  [UAT_PM_UI_2026-11-24.md](evidence/UAT_PM_UI_2026-11-24.md) + dump sanitized.
- Parser Livin: `02ee6df` — nama merchant diambil dari `Penerima` (fallback `Merchant`/`Nama
  Merchant`), `Merchant PAN`/`Customer PAN`/`No. Referensi`/`No. Ref. QRIS` tidak pernah menjadi
  nama; test +5 parser dan +1 MIME.
- Identitas email server-derived: `b5ee7ae` — migrasi `016` (`source_ref_ciphertext/iv/tag/
  key_version`, `source_ref_hash`, UNIQUE parsial owner+hash), fingerprint HMAC, PATCH CAS repair;
  test `merchant_repair_test.go`.
- Repair label satu kali saat sync: `276b7e1` — marker `merchant_repair_v1_<bulan>`, bukti
  identitas lokal, `MAX_EMAIL_REFS=100`, marker hanya jika `failed==0`, temuan Go `patchHasOtherChanges`;
  `MerchantRepairUseCaseTest` (5) + regresi `ImportMonthUseCaseTest`.
- Celah klasifikasi pasca-repair: `6cc0f2a` (baris `uncategorized` tetap eligible; validasi
  analisis mencatat alasan penolakan) dan `3b8a038` (satu aturan eligibility, tanpa cabang
  redundan); test `TestAutoCategoryQueueReopensUncategorizedUserRow`.
- Audit P1: redaksi token aset QA (belum di-commit saat audit), redaksi email/owner-id pemilik
  di evidence 23 Nov (`0436fe2`), klaim dedupe re-import dipersempit (lihat batas).
- Evidence: [UAT_PM_GO_2026-11-24.md](evidence/UAT_PM_GO_2026-11-24.md) §D–E,
  [UAT_PM_UI_2026-11-24.md](evidence/UAT_PM_UI_2026-11-24.md),
  [UAT_PM_QA_2026-11-24.md](evidence/UAT_PM_QA_2026-11-24.md),
  [UAT_AUDIT_2026-11-24.md](evidence/UAT_AUDIT_2026-11-24.md).

### Hasil gate fresh (auditor independen, HEAD `6cc0f2a`)

- Go `test ./... -count=1` PASS 8/8 paket, 127 test top-level; `go vet` bersih.
- Android debug **533 test/74 suite/0 gagal**, assessment **543/75/0 gagal** (setelah hardening & state lokal pencarian admin `4860808`);
  `:app:lintDebug` 0 error, 112 warning, 1 hint.
- UAT API owner (read-only): 49 baris November 2026 tanpa duplikat setelah repair 13 baris;
  label merchant berupa nama usaha; 40 baris uncategorized di-enqueue, job `NEEDS_REVIEW`
  revisi 0 lama tidak memblokir, 8 baris terklasifikasi model (6 Makanan, 1 Belanja,
  1 Transportasi), biaya aktual $0.00101235.
- Emulator gabungan `wqa`: (c) kembali dari daftar Transaksi ke Ringkasan direproduksi sebelum
  perbaikan dan PASS sesudah; (a) tab kosong tidak tereproduksi; C09/C11/C14 PASS runtime.
- Perangkat pemilik (24 Nov, APK build `0030a43` = kode Android final): sheet status dibuka
  tanpa request, satu tap = satu percobaan sync; daftar menampilkan kategori Azure; detail
  menampilkan field **Merchant** berisi nama usaha (bukan PAN); analisis tersimpan
  `AVAILABLE/CURRENT`. [UAT_PM_OWNER_DEVICE_2026-11-24.md](evidence/UAT_PM_OWNER_DEVICE_2026-11-24.md).
- Retry UI C13 (emulator, akun sintetis): update batas lewat editor menaikkan revisi 0→1,
  simpan dengan revisi basi memunculkan banner konflik + "Muat data terbaru", lalu rencana
  dihapus kembali. C13 kini **PASS API + UI**.
  [UAT_PM_QA_2026-11-24.md](evidence/UAT_PM_QA_2026-11-24.md) §2.6.
- Hardening UI admin (`897e751`): field pencarian tidak dinonaktifkan selama pemuatan halaman
  (hanya saat aksi `busy`), unit test baru; APK perangkat pemilik dipasang ulang 11:51 dan
  auto-sync mengirim satu `POST /v1/import-jobs` 201. Saat walk ulang admin 12:05–12:15 temuan
  QA "pencarian tidak menyaring" **tereproduksi** sebagai teks teracak karena field terikat ke
  query ViewModel dan terekomposisi saat pemuatan; perbaikan final `4860808` menyimpan teks
  field secara lokal, pencarian live kini menyaring 9→1 tanpa *Muat ulang*, filter status
  SUSPENDED 9→1, dan SUSPENDED via dialog UI terbukti (DB rev 3).
  [UAT_PM_QA_2026-11-24.md](evidence/UAT_PM_QA_2026-11-24.md) §2.8, `ui_split/40`–`42`.

### Keputusan gate PM (24 November 2026)

Lembar keputusan bertabel (Sprint/Gate · Perubahan · Bukti · Status · Sisa) beserta audit
penutupan per sprint: [GATE_DECISIONS_S2_S4_2026-11-24.md](delivery/GATE_DECISIONS_S2_S4_2026-11-24.md).

- **S2 = PARTIAL (kondisional).** Perbaikan P0 dan bukti unit/integrasi lengkap, tetapi
  acceptance varian final di perangkat pemilik (Run Android Studio) dan bukti drill-down-after
  dengan data belum ada; klaim dedupe re-import via `sourceRef` tidak boleh dibaca PASS karena
  klien belum mengirim `sourceRef` di jalur impor. Gate tetap terbuka sampai Run pemilik.
- **S3 = PASS API/unit + PASS UI untuk aksi ber-UI yang diuji.** C01–C15 lulus sesuai
  matriks; C09 (guard + reassign), C11, C13 (update + banner konflik), C14 diuji ulang lewat
  UI 24 Nov; C02/C06–C08/C10/C12/C15 mengandalkan runtime 23 Nov atau API 24 Nov dan tidak
  dijalankan ulang. Keputusan acceptance final tetap di pemilik.
- **S4 = PARTIAL.** C16–C20 (termasuk konflik dan retry hapus lampiran di perangkat,
  QA doc §2.7), C21/C22/C24, C23 (API+UI, termasuk SUSPENDED via dialog), C25 (API + UI:
  pencarian live, filter peran/status, USER ditolak — QA doc §2.8), dan **Buat Analisis**
  (Azure 200, `AVAILABLE/CURRENT`, UI "ANALISIS TERSIMPAN") sudah terbukti. Sisa bukti
  UI: C20 konteks/owner-scope dan guard last-admin C24 di perangkat; cursor C25 lintas
  halaman hanya API/unit karena daftar UAT < page size.
- **S5 tetap terbuka:** merge/deploy, hosting/backup, benchmark berlabel, mailbox produksi.

### Batas yang tidak diklaim

Repair hanya memindai bulan yang sedang disinkronkan; `transactions.go` masih menulis
`category_origin='USER'` saat PATCH kategori (provenance berubah meski nilai kategori/note
tetap); klien belum mengirim `sourceRef` pada jalur impor sehingga dedupe antar-perangkat/
reinstall belum terbukti; filter/cursor C25 dan role-change C23 baru diuji sebagian;
perubahan kontrak PATCH `merchantLabel` (wajib `expectedFingerprint`) belum masuk dokumen
kontrak.
