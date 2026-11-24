# Audit independen — retensi backend (CR-005) + integrasi Gmail API (CR-006) + retensi setahun

**Tanggal:** 24 November 2026 (WIB)
**Peran:** auditor independen (bukan implementer) — audit baca-saja, **tidak ada perbaikan kode**,
tidak ada merge/deploy, tidak ada rewrite history.
**Mandat:** `pam-app/docs/agent-prompts/PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW.md` §6 (retensi setahun
+ UI ringkas) dan §7 (pengujian/penutupan), dengan fokus lane **retensi backend + integrasi**.

**Target audit (backend, repo terpisah):**

| Item | Nilai |
|---|---|
| Branch | `integration/2026-11-24` |
| Commit kode | `efb03e8` — `merge: retention backend lane into integration/2026-11-24` (gabungan `feat/gmail-api` `7376749` + `feat/retention` `a05994c` di atas `main` `327d876`) |
| Commit dokumen | `96fd74e` — `docs: integrated pair evidence and contract sections for Gmail and retention` |
| Delta dokumen | `git diff --stat efb03e8..96fd74e` = **2 file, +106 baris, 0 hapus** (`docs/API_CONTRACT.md`, `docs/evidence/INTEGRATION_BACKEND_2026-11-24.md`) → kode yang diaudit identik dengan `efb03e8` |
| Repo audit (evidence ini) | `pam-app`, branch `feat/c2-android` |

**Lingkup:** kebijakan jendela retensi setahun WIB + guard `400 outside_retention`, sweep
report-only vs delete (flag+token+konfirmasi), urutan hapus FK-safe, audit tanpa PII, migrasi
017/018/019 (fresh + upgrade 16→19), regresi integrasi Gmail+retensi, hook kategorisasi otomatis,
dan kejujuran dokumen baru (`docs/API_CONTRACT.md` §18–19, `docs/evidence/INTEGRATION_BACKEND_2026-11-24.md`,
`docs/RETENTION_POLICY_IMPLEMENTATION.md`, `docs/evidence/GMAIL_API_2026-11-24.md`).

**Cara audit:** review kode per file/baris + verifikasi fresh di clone bersih (`/tmp/pam-retention-audit`,
HEAD `96fd74e`) + smoke server terisolasi dengan DB `/tmp` (port 18137–18153, bukan data owner) +
uji upgrade dari binary `main` `327d876`. Server UAT/dev yang sudah berjalan (mis. port 18090/18097/18102)
**tidak disentuh/restart**; semua server uji dimatikan setelah dipakai.

---

## Ringkasan verdict

| # | Item audit | Verdict |
|---|---|---|
| 1 | Kebijakan retensi setahun bergulir (WIB, inklusif, bulan batas, 29 Feb, klaim) | **PASS** |
| 2 | Guard `400 outside_retention` di list/summary/analysis/import/create/attachment + jalur bypass | **PARTIAL** — celah guard pada lampiran by-id dan satu endpoint import job |
| 3 | Sweep `REPORT_ONLY` vs `DELETE` (gate token/flag/konfirmasi), audit tanpa PII, urutan FK-safe, batas 60 dtk, atomik | **PASS** dengan catatan P3 |
| 4 | Migrasi 017/018/019 additive + fresh DB 1..19 + upgrade 16→19 | **PASS** dengan catatan P3 (019 memutasi baris DELTA lama sebelum indeks) |
| 5 | Integrasi Gmail+retensi: regresi `vet`/`test`/`build`, config union, shutdown, `ImportBatch` arity, email lintas bulan, reconnect, cross-source | **PASS** |
| 6 | Hook kategorisasi otomatis: consent, budget, single-flight, batch 5 | **PASS** (verifikasi kode + tes; provider spend live **NOT RUN**) |
| 7 | Kejujuran dokumentasi (baru + yang diklaim ulang) | **PARTIAL** — 4 overclaim/drift P2/P3 |

Tidak ada temuan **P0** dan tidak ada **P1**. Gate merge: lihat §Rekomendasi.

---

## 1. Kebijakan retensi setahun — **PASS**

Diverifikasi di kode dan runtime.

- Implementasi tunggal `internal/retention/policy.go`: `NewWindow` (mulai = hari yang sama setahun
  lalu pukul 00:00 WIB; akhir = instan sekarang), `ValidateMonth`, `ClampMonthStart`,
  `ExpiredBeforeMonthID`, `ValidateNotExpired` (hanya batas bawah; perilaku future-date manual
  dipertahankan), `ValidateEventAt` (dua batas), `StartMillis`; leap-day clamp ada di `daysIn`.
- Loader WIB `loadJakarta()` memakai `Asia/Jakarta`, fallback `FixedZone` +7 jam — sama dengan
  asumsi WIB tetap dokumen.
- Fresh test: `internal/retention` ok (`0.481s`), termasuk `TestNewWindowRollingYear`,
  `TestNewWindowLeapDay`, `TestNewWindowNonLeapFebruaryAndMarch`, `TestWindowEventBoundaries`,
  `TestWindowMonthBoundaries`, `TestWindowMonthBoundaryAtExactMidnight`, `TestClampMonthStart`.
- Bukti runtime (DB `/tmp`, port 18141):

```text
GET /v1/retention -> 200
{"windowStart":"2025-11-24T00:00:00+07:00","windowEnd":"2026-11-24T14:51:32.396453+07:00",
 "timezone":"Asia/Jakarta","startMonthId":"2025-11","endMonthId":"2026-11","months":12}

create tx eventAt = windowStart tepat (2025-11-23T17:00:00Z)      -> 201, monthId=2025-11
create tx eventAt = windowStart - 1ms (2025-11-23T16:59:59.999Z)  -> 400 outside_retention
GET /v1/transactions?month=2025-11 (bulan batas parsial)          -> 200, memuat baris di batas
GET /v1/transactions?month=2025-10 / 2020-01 (bulan kedaluwarsa)  -> 400 outside_retention
POST /v1/transactions eventAt = now+2 hari (manual, masa depan)   -> 201 (perilaku lama sengaja
   dipertahankan; GET /v1/transactions/{id} -> 200)
```

- Bypass yang diperiksa: cursor keyset list transaksi dibatasi `event_at_ms >= StartMillis` setelah
  `ParseMonth` + `ValidateMonth` + `ClampMonthStart` (`internal/store/transactions.go`), jadi cursor
  tidak dapat melompati jendela; tidak ada endpoint export/backup finansial (tidak ditemukan route
  `export|backup|dump` di `internal/httpapi/server.go`); refund tertaut di-*unlink* oleh sweep.
  Tidak ada celah paging/cursor yang terbukti.

Catatan (P3, bukan pelanggaran gate): `POST /v1/analyses` pada bulan kedaluwarsa juga 400
`outside_retention` (diverifikasi runtime), konsisten dengan §19.

## 2. Guard `400 outside_retention` + jalur bypass — **PARTIAL**

Matriks hasil uji runtime (semua dengan JWT owner; baris kedaluwarsa disuntik langsung ke DB
supaya belum tersapu):

| Permintaan | Hasil | Verdict |
|---|---|---|
| `POST /v1/transactions` `eventAt` lama | 400 `outside_retention` | PASS |
| `GET /v1/transactions?month=<lama>` | 400 | PASS |
| `GET /v1/summaries/{bulan lama}` | 400 | PASS |
| `PUT /v1/summaries/{bulan lama}` | 400 | PASS |
| `GET /v1/analyses/{bulan lama}` | 400 | PASS |
| `POST /v1/analyses` `monthId` lama | 400 | PASS |
| `POST /v1/import-jobs` `monthId` lama | 400 | PASS |
| `POST /v1/import-jobs/{id}/batches` job bulan lama | 400 | PASS |
| `POST /v1/import-jobs/{id}/batches` candidate `eventAt` lama | 400 | PASS |
| `POST /v1/attachments` untuk transaksi kedaluwarsa | 400 | PASS |
| `GET /v1/attachments?transactionId=<tx kedaluwarsa>` | 400 | PASS |
| `GET /v1/transactions/{id}` baris kedaluwarsa | 400 | PASS |
| `PATCH`/`DELETE /v1/transactions/{id}` baris kedaluwarsa | 400 (guard di `handlers_data.go:362,385`) | PASS |
| **`GET /v1/attachments/{id}` baris kedaluwarsa** | **200** | **GAP (P2-1)** |
| **`PATCH /v1/attachments/{id}` baris kedaluwarsa** | **200**, `revision` naik | **GAP (P2-1)** |
| **`DELETE /v1/attachments/{id}` baris kedaluwarsa** | **204** | **GAP (P2-1)** |
| **`GET /v1/attachments` tanpa filter** | **200**, memuat lampiran transaksi kedaluwarsa | **GAP (P2-1)** |
| **`GET /v1/import-jobs/{id}` job bulan kedaluwarsa** | **200** (metadata: monthId/counter) | **GAP (P3-1)** |

Envelope error benar: `400`, `code=outside_retention`, `retryable=false`, objek `retention` berisi
`windowStart`, `windowEnd`, `startMonthId`, `endMonthId`, `timezone`, `months`
(`internal/httpapi/retention.go:33`). Kegagalan DB tetap 5xx (tidak dimasking; jalur
`mapWriteError`/`databaseOrInternal`).

**P2-1 — Guard retensi lampiran tidak lengkap (read & write by id, serta list tanpa filter).**
`getAttachment` (`internal/httpapi/handlers_attachments.go:119`), `updateAttachment` (`:129`),
`deleteAttachment` (`:145`) memanggil store langsung tanpa memeriksa `eventAt` transaksi induk;
`listAttachments` hanya mem- guard bila `transactionId` diisi (`:98–107`). Reproduksi: transaksi
recent + lampiran dibuat normal, lalu transaksi dibuat kedaluwarsa langsung di DB; hasil
`GET`=200, `PATCH`=200, `DELETE`=204, list tanpa filter memuat baris kedaluwarsa. Dampak: jaminan
retensi yang didokumentasikan (contract §19) tidak berlaku untuk metadata lampiran yang ID-nya
diketahui owner, sampai sweep kebetulan berjalan; tidak ada file byte dan tetap owner-scoped,
sehingga bukan kebocoran lintas-tenant. Rekomendasi (tidak diterapkan): guard batas-bawah pada
tiga operasi by-id (ambil `eventAt` induk) dan pada list tanpa `transactionId` (join/filter
transaksi), atau dokumentasikan pengecualian secara eksplisit.

**P3-1 — `GET /v1/import-jobs/{id}` tanpa guard** (`internal/httpapi/handlers_import.go:67–75`).
Metadata saja (`monthId`, counter, state) dan owner-scoped → dampak rendah; tetap menyimpang dari
klaim §19 bahwa "import job" dijaga.

## 3. Sweep report-only vs delete + audit — **PASS** (catatan P3)

Runtime (DB `/tmp`, port 18141/18142; tabel `retention_sweeps` diperiksa langsung):

```text
POST /v1/internal/retention-sweep tanpa header / token salah   -> 401 / 401
POST ... tanpa INTERNAL_SWEEP_TOKEN dikonfigurasi              -> 404 not_found
POST ... (token benar, flag delete mati)                       -> 200 mode=REPORT_ONLY,
   header X-Retention-Mode: REPORT_ONLY, deleted=false, counts.eligible={transactions:1,attachments:1,importJobs:1}
   baris DB tetap ada; audit row bertambah 1 (REPORT_ONLY/COMPLETED)
POST ...?mode=delete&confirm=RETENTION_DELETE (flag mati)      -> 403 retention_delete_disabled
POST ...?mode=delete (flag hidup, tanpa confirm)               -> 400 confirmation_required
POST ...?mode=delete&confirm=RETENTION_DELETE (flag hidup)     -> 200 mode=DELETE, deleted=true,
   transaksi/lampiran/job kedaluwarsa hilang, baris batas/recent tetap, audit row bertambah
POST ...?mode=nope                                             -> 400 invalid_request
mode=report-only / dry-run                                     -> 200 (alias diterima)
```

- Sweep terjadwal opt-in bekerja: dengan `RETENTION_DELETE_ENABLED=true` + token, worker langsung
  menjalankan satu sweep `DELETE` saat start dan menulis baris audit `DELETE/COMPLETED`
  (teramati di DB); flag default tetap report-only/off (`internal/config/config.go:121–125,237–246`).
- Audit `retention_sweeps` (migrasi `018.sql`) hanya menyimpan mode/window/timestamp/status/
  `counts_json`/`error_code` — **tanpa** owner id, id transaksi, nominal, email, token, path
  lampiran (diverifikasi skema + isi baris nyata). Log worker juga hanya count/window
  (`internal/httpapi/retention_worker.go:70–84`).
- Atomik: hitung → hapus → tulis audit row dalam satu transaksi; kegagalan me-rollback semuanya
  (`internal/store/retention.go:77` dst.; tes `TestRetentionDeleteSweepRemovesOnlyExpiredRowsAndKeepsBoundary`
  dan `TestRetentionReportOnlySweepWritesAuditAndDeletesNothing` lolos fresh). Urutan hapus
  FK-safe (lampiran → category_jobs termasuk orphan → import_skipped → import_batches →
  import_jobs → analyses → summaries → category_cache → unlink refund → transactions); setelah
  sweep `PRAGMA foreign_key_check` pada DB uji = **0 violations**.
- Cakupan tabel sweep sesuai daftar 9 entitas + refund unlink; `budget_plans`/`budget_limits`
  sengaja dikecualikan (FR-42) dan tidak tersentuh — konsisten dokumen.
- Batas 60 dtk: worker memakai `context.WithTimeout(..., 60*time.Second)`
  (`retention_worker.go:60`), jadi sweep pertama yang sangat besar akan gagal/rollback, bukan
  menggantung; tidak ada chunking (diakui sebagai known limit dokumen).

**P3-2 — Sweep yang gagal tidak meninggalkan baris audit padahal komentar kode & dokumen
mengklaim sebaliknya.** `retention_worker.go:14–15` berbunyi "Every tick writes one
retention_sweeps audit row", dan `RETENTION_POLICY_IMPLEMENTATION.md:88` mengklaim "every run writes
an audit row"; pada error worker hanya log `retention_sweep_failed` lalu `return` tanpa menulis
baris (`retention_worker.go:66–70`). Kolom `status='FAILED'` di `018.sql` tidak pernah dipakai.
Dampak: kegagalan sweep tak terlihat di tabel audit (observabilitas), bukan kebocoran data.

## 4. Migrasi 017/018/019 + fresh & upgrade — **PASS** (catatan P3)

- Fresh DB (binary baru, `/tmp`): `schema_migrations` = **19 baris, versi 1..19**.
- Isi migrasi: `017` membuat tabel Gmail (baru, tanpa UPDATE/DELETE data lama); `018` membuat
  `retention_sweeps` + indeks (murni additive); `019` membuat unique index parsial job DELTA —
  **tetapi** didahului `UPDATE gmail_sync_jobs SET state='FAILED', last_error_code='superseded'`
  untuk job DELTA ganda (`internal/store/migrations/019.sql:6–13`), jadi bukan "purely additive".
  Pada upgrade dari `main` (belum ada tabel Gmail) UPDATE ini no-op — aman.
- **Uji upgrade nyata**: binary `main` `327d876` (migrasi maks **16**) dijalankan pada DB `/tmp`,
  register user `201`; binary baru dijalankan pada DB yang sama → migrasi naik ke **1..19**,
  `GET /healthz` 200, login user lama berhasil (`upgrade` tanpa kehilangan akun), dan
  `PRAGMA foreign_key_check` = **0 violations**. Tidak ada tabel 001–016 yang diubah.
- Klaim "tidak ada perubahan destruktif pada 001–016" di `INTEGRATION_BACKEND_2026-11-24.md:27–28`
  akurat; klaim "migrasi 018 additive" akurat; klaim "019 delta" akurat.

**P3-3 — 019 memutasi state job DELTA lama sebelum indeks unik** (menandai `CREATED/RUNNING`
ganda menjadi `FAILED/superseded`). Ini backfill yang disengaja dan aman untuk jalur upgrade yang
diuji (main→integration), tetapi bila DB sudah berisi beberapa job DELTA open dari build
`feat/gmail-api` lama, state open-nya akan "hilang" (bukan data finansial). Sebaiknya disebut
eksplisit di dokumen migrasi/evidence, bukan sebagai "additive".

## 5. Integrasi Gmail + retensi (regresi) — **PASS**

- Fresh verifikasi di HEAD `96fd74e` (kode = `efb03e8`): `./scripts/go.sh vet ./...` bersih;
  `test ./... -count=1` semua paket **ok** — `internal/gmail 4.128s`, `internal/httpapi 31.134s`,
  `internal/retention 0.481s`, `internal/store 5.704s`, `internal/auth/config/crypto/finance/
  categoryllm/llm` ok; `build ./...` + `build -o bin/pam-api ./cmd/api` ok. (Log:
  `/tmp/retention-audit-fresh.log`.)
- Konflik integrasi yang dimaksud Lead terverifikasi tidak regresi:
  - **Config union**: `Gmail` + `Retention` hidup bersama di `internal/config/config.go`;
    tes `internal/config` lolos (default off, interval 1..168, `RETENTION_DELETE_ENABLED` tanpa
    token gagal start / fail-closed, delete mengimplikasikan sweep).
  - **Shutdown**: urutan di `cmd/api/main.go:154–164` = category worker → `gmailCoordinator.Stop()`
    → retention worker → `server.Shutdown` (10 dtk), sehingga tidak ada provider call baru saat
    shutdown (`internal/gmail/coordinator.go:50–58`). Catatan P3-4: `RetentionWorker.Stop()` menunggu
    sweep in-flight hingga 60 dtk sebelum HTTP drain; bila grace platform < ~70 dtk, proses bisa
    di-SIGKILL — transaksi sweep rollback (aman) tetapi shutdown bisa lambat.
  - **`ImportBatch` arity**: `store.ImportBatch` mengembalikan `(counts, importedIDs, replayed, err)`;
    HTTP memakai `counts` (`handlers_import.go:116`), Gmail memakai `importedIDs` untuk hook
    kategori (`internal/gmail/worker.go:230,406`) — konsisten, tes Gmail lolos.
  - **Email lintas bulan**: tes `TestLateEmailImportsIntoTransactionMonth` memakai pasangan bulan
    lampau yang masih di jendela; routing berdasarkan tanggal transaksi (bukan tanggal email)
    terbukti (`candidatePlacement`, `internal/gmail/worker.go:173–187`; baris di luar jendela
    dicatat `outside_retention` `:287–305`).
  - **Reconnect mailbox**: callback mematikan watch + revoke refresh token lama sebelum menyimpan
    (`internal/gmail/service.go:204–222`), dan `SaveGmailConnection` menghapus job/event lama
    (`internal/store/gmail.go:237–242`); tes `TestReconnectToDifferentMailboxResetsSyncState`,
    `TestGmailBackfillMergesExistingIMAPRow` lolos.
  - **Cross-source merge**: `crossSourceDuplicateTx` (`internal/store/imports.go:59–96`) hanya
    menggabungkan baris non-Gmail dengan natural key direction+amount+eventAt; baris Gmail nyata
    tetap tidak dianggap duplikat satu sama lain (komentar + tes).
  - **Recovery/single-flight/needs_reauth**: `recoverHistory` checkpoint resume, unique index DELTA
    (019), `TestRecoveryResumesFromCheckpoint`, `TestEnsureDeltaJobIsSingleFlight`,
    `TestNeedsReauthStopsAutomaticRetry`, `TestFailedPageRetriesWithoutLosingCheckpoint`,
    `TestWatchRenewalIsScheduledBeforeExpiry` — semua lolos fresh.
- Smoke integrasi: `GET /healthz`/`readyz` 200; route Gmail/retention tanpa JWT 401;
  callback saat Gmail mati → `302 pam://gmail/connected?reason=gmail_disabled&status=error`;
  `POST /v1/internal/gmail/push` saat Gmail mati 503; log request hanya route/status tanpa
  query/PII/token. `GET /v1/gmail/status` saat Gmail mati = **503** (bukan 200) — lihat P2-3.

## 6. Hook kategorisasi otomatis — **PASS** (batas: provider live NOT RUN)

- Consent: `POST /v1/category-jobs` menolak tanpa `AI_CATEGORIZATION=GRANTED`
  (`handlers_category.go:89–93`); `EnqueueImported` (jalur Gmail) juga provider-free dan mengecek
  `autoCategoryConsentGranted` (`handlers_category_queue.go:338–342`).
- Batas: `CategoryDrainBatch=5`, `CategoryDrainMaxBatches=4`, `CategoryDrainInterval=2h`,
  fanout cache 50, `maxEnqueueIDs=50` (`handlers_category_queue.go:17–33`); tes
  `category_window_test.go` memastikan jendela 2 jam menghasilkan maksimum 4 batch × 5 merchant.
- Single-flight: lease per owner `acquireOwnerDrain` (`:114–125`) — drain paralel mengembalikan
  `not_due` tanpa provider call.
- Budget: gate ledger (`providerAttempt`, daily/monthly ceiling, attempts, `FitsInputBudget`) di
  `classifyGroupWith`; hasil `budget_exhausted` menghentikan window tanpa memboroskan sisa budget
  (bukti kode + tes paket `categoryllm`/`httpapi` lolos). Tidak ada provider live yang dipanggil
  selama audit → spend nyata **NOT RUN**.
- **P3-5 (residual, defense-in-depth):** `EnqueueImported`/`createCategoryJobs` tidak memeriksa
  jendela retensi; transaksi kedaluwarsa yang belum tersapu masih bisa diklasifikasikan (biaya
  provider, hasil dihapus saat sweep). Bukan kebocoran data; disarankan guard yang sama bila
  klasifikasi otomatis dianggap jalur baca.

## 7. Kejujuran dokumentasi — **PARTIAL**

| Dokumen | Klaim | Hasil audit |
|---|---|---|
| `docs/RETENTION_POLICY_IMPLEMENTATION.md` | window WIB inklusif, bulan batas parsial, 29 Feb clamp, tabel eligible, urutan hapus FK-safe, gate endpoint, envelope keys (`windowStart…months`), env var, known limits | **Akurat** dan cocok dengan kode/runtime; envelope keys di dokumen ini benar (berbeda dengan contract, lihat P2-2) |
| `docs/RETENTION_POLICY_IMPLEMENTATION.md:53` | "Direct resource reads (`GET /v1/transactions/{id}`, **attachments by id**) only enforce the lower bound" | **Overclaim** — lampiran by-id tidak memeriksa batas apa pun (P2-1); transaksi by-id benar |
| `docs/RETENTION_POLICY_IMPLEMENTATION.md:88` + `retention_worker.go:14–15` | "setiap run menulis baris audit"/"every tick writes one row" | **Overclaim saat gagal** — error path tidak menulis baris `FAILED` (P3-2) |
| `docs/RETENTION_POLICY_IMPLEMENTATION.md:118` | guard test mencakup "list/summary/analysis/import/**attachment** paths" | **Overclaim** — `internal/httpapi/retention_guard_test.go:63–124` tidak memuat kasus lampiran sama sekali (P2-1/P2-5) |
| `docs/API_CONTRACT.md:611–614` (§19) | envelope `retention` = `start`, `end`, `monthId`; guard berlaku untuk lampiran | **Drift** — runtime mengirim `windowStart/windowEnd/startMonthId/endMonthId/timezone/months` (P2-2); guard lampiran tidak lengkap (P2-1) |
| `docs/API_CONTRACT.md:584` (§18) | `GET /v1/gmail/status` saat disabled tetap `200 connected:false` | **Salah** — runtime `503 gmail_disabled` (`handlers_gmail.go:175–178`); callback/push/status lain sesuai (P2-3) |
| `docs/API_CONTRACT.md:233–234` (§5.1) | "rentang retensi (24 bulan)" | **Basi** — kebijakan satu tahun; kontradiksi dengan §19 (P2-4) |
| `docs/API_CONTRACT.md:413–420` (§10) | body/response lampiran memakai `displayName`,`mime` | **Drift** — runtime memakai `fileName`,`mimeType` (out-of-lane, pre-existing; P3-6) |
| `docs/evidence/INTEGRATION_BACKEND_2026-11-24.md` | fresh vet/test/build @ `efb03e8`, 19 migrasi, tabel smoke (401/200/403/302/503, no PII, log rute), gate NOT RUN (Console/E2E/CASA/sweep nyata/merge/deploy) | **Terbukti** — hasil fresh & smoke audit mereproduksi nilai yang diklaim; daftar NOT RUN jujur. Catatan kecil: waktu tes yang ditulis (mis. `httpapi 25.864s`) berbeda dari run audit (`31.134s`), wajar antar mesin; tidak ada klaim yang dibantah |
| `docs/evidence/GMAIL_API_2026-11-24.md:101` | unique index DELTA di "migrasi 019"; P2 leftovers didaftar eksplisit | **Akurat** — nomor migrasi benar; daftar P2 jujur |

Tidak ada bukti dokumen menyembunyikan kegagalan; yang ditemukan adalah overclaim/penyimpangan
teknis yang harus dikoreksi (bukan fabrikasi bukti).

---

## Temuan terprioritas

### P0 — tidak ada
### P1 — tidak ada

### P2

1. **Guard retensi lampiran tidak lengkap (read & write by id; list tanpa filter).**
   `internal/httpapi/handlers_attachments.go:80–107` (list hanya guard saat `transactionId`),
   `:119` (`getAttachment`), `:129` (`updateAttachment`), `:145` (`deleteAttachment`).
   Runtime: baris kedaluwarsa `GET` 200 / `PATCH` 200 / `DELETE` 204 / list tanpa filter memuatnya.
   Menyimpang dari `docs/API_CONTRACT.md:611–614` dan `RETENTION_POLICY_IMPLEMENTATION.md:53,118`.
2. **Envelope `retention` di contract §19 tidak cocok dengan implementasi.**
   `docs/API_CONTRACT.md:611–612` (`start`,`end`,`monthId`) vs
   `internal/httpapi/retention.go:33–46` (`windowStart`,`windowEnd`,`startMonthId`,`endMonthId`,
   `timezone`,`months`); diverifikasi dari body 400 nyata. Klien yang mengikuti contract akan
   membaca field `undefined`.
3. **Contract §18 mengklaim status Gmail disabled 200, implementasi 503.**
   `docs/API_CONTRACT.md:584` vs `internal/httpapi/handlers_gmail.go:175–178`; runtime
   `GET /v1/gmail/status` = `503 gmail_disabled`. Perilaku 503 konsisten untuk semua route Gmail;
   yang salah adalah klaim dokumen (atau perilaku harus dibedakan bila FE membutuhkan 200).
4. **Contract §5.1 masih menyebut retensi 24 bulan.**
   `docs/API_CONTRACT.md:233–234` vs kebijakan satu tahun (§19 + `internal/retention/policy.go`).
   Kontradiksi internal dokumen yang bisa menyesatkan FE/QA.
5. **Klavim dokumen retensi soal lampiran & tesnya overclaim.**
   `RETENTION_POLICY_IMPLEMENTATION.md:53` (lampiran by-id "enforce lower bound") dan `:118`
   (guard test mencakup attachment path) tidak benar; tidak ada satu pun tes lampiran di
   `retention_guard_test.go`. Ini memperkuat P2-1: jaminan tertulis tidak diuji.

### P3

1. `GET /v1/import-jobs/{id}` tidak dijaga retensi (`handlers_import.go:67–75`) — metadata saja.
2. Sweep gagal tidak menulis baris audit `FAILED`, padahal komentar/dokumen mengklaim setiap run
   menulis baris (`retention_worker.go:14–15,66–70`; `018.sql` status FAILED tidak terpakai).
3. Migrasi 019 memutasi state job DELTA open sebelum indeks unik (`019.sql:6–13`) — aman pada
   upgrade yang diuji (main tanpa tabel Gmail), tetapi bukan "purely additive".
4. `RetentionWorker.Stop()` menunggu sweep in-flight hingga 60 dtk sebelum HTTP drain
   (`retention_worker.go:30–34`; `main.go:159–164`) — risiko shutdown lambat; data aman karena
   sweep atomik/rollback.
5. `EnqueueImported`/`createCategoryJobs` tidak memeriksa jendela retensi sebelum klasifikasi
   (biaya provider untuk baris kedaluwarsa yang belum tersapu).
6. Body/response lampiran di `docs/API_CONTRACT.md:413–420` memakai `displayName`/`mime`,
   implementasi `fileName`/`mimeType` (out-of-lane, pre-existing).
7. Contract §19 menyiratkan semua "request baca/tulis di luar jendela" → 400 untuk transaksi
   create, sedangkan future-dated manual create sengaja diizinkan (201, `ValidateNotExpired`) dan
   didokumentasikan di `RETENTION_POLICY_IMPLEMENTATION.md` §2 — rumusan contract perlu dipersempit
   ke batas bawah.

---

## Belum dapat diverifikasi (audit limit)

- **Google Cloud Console, OAuth consent screen + test users, OAuth client, Gmail API, Pub/Sub
  topic/subscription + publisher grant, endpoint HTTPS, E2E akun pilot** — tidak ada kredensial
  dan tidak boleh menyentuh akun nyata.
- **CASA / verifikasi restricted scope Gmail untuk production** — proses eksternal.
- **Sweep `DELETE` nyata pada data UAT/production dan restore/rollback data nyata** — audit hanya
  memakai DB `/tmp`; tidak ada data owner yang dihapus.
- **Failure-injection sweep** (DB error di tengah delete) tidak dijalankan; jaminan atomik
  diverifikasi lewat kode + tes store, bukan injeksi runtime.
- **Sweep dataset besar (>60 dtk)** tidak diuji; hanya batas context 60 dtk yang terbaca dari kode.
- **Provider LLM live** (spend nyata, retry/reconcile token) — gate budget diverifikasi kode + tes,
  bukan panggilan provider nyata.
- **UI ringkas tahun berjalan Android / E2E lintas device** — di luar lane backend ini (audit
  Android/Gmail UI ada di `AUDIT_GMAIL_API_2026-11-24.md`, round sebelumnya).
- **Merge `integration/2026-11-24` → `main`, deployment, rotasi secret** — menunggu instruksi
  pemilik; tidak dilakukan.
- Uji `GET /v1/gmail/status` saat Gmail **enabled tapi belum terhubung** tidak dijalankan (butuh
  konfigurasi OAuth nyata); hanya jalur disabled yang diuji.

---

## Rekomendasi gate (untuk Lead/pemilik)

1. **Tidak ada blocker P0/P1** pada integrasi backend; regresi Gmail+retensi hijau dan jalur
   upgrade 16→19 aman.
2. Sebelum mengklaim "semua endpoint lampiran diproteksi retensi" atau memakai contract §19 untuk
   FE, perbaiki **P2-1** (guard lampiran) dan **P2-2** (keys envelope) — ini yang paling mungkin
   menimbulkan bug klien/regresi kebijakan. P2-3/P2-4/P2-5 adalah koreksi dokumen.
3. P3 boleh dicatat sebagai backlog; P3-2 (audit `FAILED`) layak diperbaiki saat observabilitas
   sweep dibutuhkan.
4. Merge/deploy tetap keputusan pemilik; audit ini tidak mengubah kode, branch, maupun remote.

---

## Lampiran bukti (perintah & hasil ringkas)

**A. Fresh verify (clone bersih `96fd74e`, kode = `efb03e8`), caches hangat:**

```text
$ cd /tmp/pam-retention-audit && git status --short   # bersih
$ ./scripts/go.sh vet ./...                           # bersih
$ ./scripts/go.sh test ./... -count=1
ok internal/auth 1.468s        ok internal/categoryllm 0.430s  ok internal/config 0.391s
ok internal/crypto 0.412s      ok internal/finance 0.383s      ok internal/gmail 4.128s
ok internal/httpapi 31.134s    ok internal/llm 0.552s          ok internal/retention 0.481s
ok internal/store 5.704s
$ ./scripts/go.sh build ./... && ./scripts/go.sh build -o bin/pam-api ./cmd/api  # ok
```

**B. Fresh DB:** `schema_migrations` `(19, 1, 19)`.

**C. Upgrade nyata:** binary `main` `327d876` → migrasi `(16,1,16)`, register user 201; binary baru
pada DB sama → migrasi `1..19`, health 200, login user lama ok, `PRAGMA foreign_key_check` = 0.

**D. Smoke retensi (isolated port 18137–18142, DB `/tmp`):** hasil guard/gap/sweep seperti tabel §2
dan §3; audit `retention_sweeps`:
`REPORT_ONLY/COMPLETED {"transactions":1,"attachments":1,"importJobs":1,...}`,
`DELETE/COMPLETED` (worker startup), `DELETE/COMPLETED` (manual, tx terhapus). Semua server uji
dimatikan; tidak ada proses uji yang tertinggal di port uji.

**E. Smoke hook kategori/consent/budget/single-flight:** tes paket `internal/httpapi`/`categoryllm`
lolos fresh; konstanta dan gate diverifikasi di kode (§6); provider live tidak dipanggil.

**F. Dokumen baru:** `docs/API_CONTRACT.md` §18–19 dan `INTEGRATION_BACKEND_2026-11-24.md`
dibaca penuh dan dibandingkan dengan kode/runtime — hasil di §7.
