# Delivery S2–S4 — living checkpoint

Status: **IN PROGRESS / belum selesai**, 22 November 2026.
Branch `feat/c2-android`, HEAD dicatat pada commit checkpoint ini. **Klarifikasi pemilik,
22 November 2026:** push R1 sebelumnya diotorisasi, dan setiap commit selanjutnya harus
langsung dipush ke branch kerja yang sama. Kebijakan ini menggantikan larangan push pada
prompt sebelumnya. Merge/deploy tetap memerlukan instruksi terpisah.

Aturan repo: [`AGENTS.md`](../AGENTS.md). Mandat terkini:
[`MANDAT_S2_S4.md`](agent-prompts/MANDAT_S2_S4.md). Koreksi review PM:
[`PM_REVIEW_FB68397.md`](reviews/PM_REVIEW_FB68397.md).

## Inventaris awal yang dipertahankan

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

## Tes aktual

- Android: **248 tes PASS**; `assembleDebug`; lint 0 error, 68 warning (termasuk unused string resource existing).
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
