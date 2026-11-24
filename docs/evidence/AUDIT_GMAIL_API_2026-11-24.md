# Audit Independen — Integrasi Gmail API / Pub/Sub (Backend Go) + UI Gmail Android

Tanggal: **24 November 2026** (WIB). Auditor: tim audit independen (bukan implementer; **tidak
mengubah kode aplikasi**). Status gate: **PARTIAL** — implementasi inti terbukti dan verifikasi
fresh hijau, tetapi beberapa butir mandat belum terpenuhi dan seluruh gate live (Google Console,
OAuth/Pub/Sub nyata, perangkat) **NOT RUN**.

## 1. Objek dan metode

- Backend: `0037-Backend-Financial`, branch **`feat/gmail-api`**, HEAD **`9d388ae`**
  (`249dcb9` store, `273fd88` gmail client/parser/OAuth/OIDC, `0d124d4` service+worker+coordinator,
  `eae4903` httpapi, `4e3bafc` setup guide, `9d388ae` docs). Diff vs `origin/main` (`327d876`):
  **33 file, +6020/−12, 25 file baru**; satu-satunya migrasi baru `internal/store/migrations/017.sql`.
- Kontrak acuan: `docs/GMAIL_API_CONTRACT.md`; mandat:
  `pam-app/docs/agent-prompts/PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW.md` §4.
- Android: `pam-app`, HEAD **`b5f4905`** (lane `f1e3e16`/`7c4bfff`, merge `4e6946b`, doc
  `b5f4905`). File: `GmailLinkScreen/ViewModel`, `ServerGmailLinkRepositoryImpl`, `RemoteGmail`,
  `MainActivity` deep link, `AndroidManifest.xml`, string id/en.
- **Catatan kebersihan:** worktree backend **tidak bersih** saat audit (7 file WIP lane
  auto-kategorisasi: `cmd/api/main.go`, `internal/gmail/{service,worker}.go`,
  `internal/httpapi/{handlers_category_queue,handlers_import,server}.go`,
  `internal/store/imports.go`). WIP itu **tidak diaudit dan tidak disentuh**; seluruh temuan
  dinilai pada commit `9d388ae`, dan build/smoke memakai **clone bersih** `/tmp/pam-gmail-audit`
  (HEAD `9d388ae`, `git status` bersih).

## 2. Verifikasi fresh (bukan klaim dokumen)

```text
$ git clone <backend> /tmp/pam-gmail-audit && git checkout feat/gmail-api   # HEAD 9d388ae, clean
$ PAM_GOROOT=.../pam-app/.toolchain/go ./scripts/go.sh vet ./...            # bersih
$ ./scripts/go.sh test ./... -count=1
ok internal/auth 1.792s        ok internal/categoryllm 1.094s
ok internal/config 1.051s      ok internal/crypto 1.093s
ok internal/finance 1.090s     ok internal/gmail 1.453s
ok internal/httpapi 27.041s    ok internal/llm 1.038s
ok internal/store 2.543s
?  cmd/api, cmd/pam-admin, internal/analysis [no test files]
$ ./scripts/go.sh build ./... && ./scripts/go.sh build -o bin/pam-api ./cmd/api   # ok
```

- Go **1.27.4**. `grep -rhE '^func Test' internal cmd | wc -l` = **150** (klaim evidence
  backend cocok); `internal/gmail` = 13; gmail-specific `httpapi`+`store` = 8.
  Tidak ada `t.Skip`/`SkipNow`/`.Only(`.
- Smoke server terisolasi (port **18102**, DB `/tmp/pam-gmail-smoke.db`, `APP_ENV=development`):
  - `/readyz` → **200** `{"status":"ready"}`; migrasi **17** diterapkan pada DB kosong
    (`schema_migrations` count 17, max 17); tabel `gmail_connections`, `gmail_oauth_states`,
    `gmail_push_events`, `gmail_sync_jobs` + 5 indeks eksplisit terbentuk.
  - `GET /v1/gmail/status` tanpa token → **401**; `GET /v1/gmail/connect/callback` saat fitur
    nonaktif → **302** `Location: pam://gmail/connected?reason=gmail_disabled&status=error`;
    `POST /v1/internal/gmail/push` saat nonaktif → **503 gmail_disabled**.
  - Log request hanya memuat `route` (tanpa query/state/email/token) — tidak ada PII di log.
  - `GMAIL_ENABLED=true` tanpa kredensial → **exit 1**, pesan validasi eksplisit (fail-closed).
- Android (HEAD `b5f4905`, JDK Android Studio, `--offline`):
  `./gradlew :app:testDebugUnitTest --tests 'com.pam.recap.ui.feature.gmail.*'` →
  **BUILD SUCCESSFUL**, 26 tes gmail-link (`GmailLinkViewModelTest` 13 + `GmailLinkScreenTest` 13),
  **0 gagal, 0 skip**; +4 tes lama `GmailConnectPurgeTest` juga hijau.

## 3. Verdict per butir

### Backend (mandat §4 + kontrak)

| # | Butir | Verdict | Bukti ringkas |
| --- | --- | --- | --- |
| B1 | OAuth dari sesi aktif; state sekali pakai, TTL ≤10 mnt, PKCE S256, owner-bound | **PASS** | `service.go` Start 100–170, Callback 142–199; `gmail_oauth_states` unik `state_hash`, verifier sealed AES-GCM, `expires_at`; `crypto/rand` 32B; TTL di-cap 10 mnt (`Store.CreateGmailOAuthState`). Catatan P2-9 (RowsAffected). |
| B2 | Refresh token terenkripsi | **PASS** | `store.SaveGmailConnection` 169–222: ciphertext/iv/tag/key_version, AAD owner+purpose; tak ada kolom plaintext; tes `TestGmailConnectionSealsTokenAndEnforcesOneOwner`. |
| B3 | Satu koneksi ACTIVE per owner; 409; reconnect | **PARTIAL** | 409 `gmail_already_connected` (`handlers_gmail.go:53–57`); `owner_id UNIQUE`; replace via `ON CONFLICT(owner_id)`. Namun reconnect **tidak** stop watch / revoke token lama (P2-1) dan **tidak** memicu backfill akun baru (P1-2). |
| B4 | Scope `gmail.readonly` | **PASS** | Default & consent URL `gmail.readonly`; Callback menolak scope lain → `scope_missing`. Catatan P2-8 `GMAIL_SCOPE` configurable. |
| B5 | connect/status/disconnect | **PASS** | 5 rute kontrak; status masked (`account_email_masked`, scope, sync_state, last_sync_at, watch_expires_at, last_error_code); disconnect → stop watch → revoke → clear credential, data transaksi tetap. Smoke di §2. |
| B6 | Push OIDC + ACK idempoten, duplikat tidak dobel | **PASS** | `oidc.go`: RS256, `exp`, issuer `accounts.google.com`, audience, email service-account + `email_verified`; Gmail nonaktif/404 → 503/401. `event_key` unik, payload sealed, `ON CONFLICT DO NOTHING`; ACK 204 setelah durable; mailbox asing di-ACK (anti-enumerasi). |
| B7 | `users.watch` register/renew/stop | **PASS** | watch saat callback; renew bila <24 jam (`watchDue` 465–471, `UpdateGmailWatch` RowsAffected check); stop watch saat disconnect. |
| B8 | Backfill 12 bulan bergulir WIB, bulan berjalan dulu, checkpoint, batas halaman | **PASS** | `rollingMonths`/`monthWindow`/`monthOf` (WIB), `EnsureGmailBackfillJob` unik per owner+bulan (partial unique index), checkpoint per batch, `MaxPagesPerRun` cap, resume page token. |
| B9 | Pagination, **buffer penerimaan**, keanggotaan berdasarkan **timestamp transaksi** | **FAIL** | `monthQuery` memfilter dengan **tanggal email** `after:/before:` WIB (service.go 615–620); kandidat beda bulan dibuang jadi `UNRESOLVED/outside_period` (worker.go 282–304). Tidak ada buffer penerimaan/late-arrival; notifikasi beda bulan (mis. email 1 Des untuk transaksi 30 Nov) **tidak akan pernah masuk** pada impor awal → lihat **P1-1**. |
| B10 | Delta `history.list` + cursor advance setelah commit + recovery 404 | **PARTIAL** | `runDelta` (worker.go 63–121) checkpoint + advance cursor; 404 → `recoverHistory`. Namun recovery mengabaikan `Done`/batas halaman (P2-3) dan checkpoint memakai counter basi (P2-6). |
| B11 | Dedupe per owner+mailbox; **migrasi identitas IMAP lama** | **PARTIAL/FAIL** | Dedupe `g:<16 hex hash email>:<messageId>` + fingerprint, source ref disimpan **sealed + hash** ✓. **Tidak ada** verifikasi migrasi IMAP→Gmail, mode pembandingan tanpa mutasi, atau jalur koreksi baris (mandat §4.3) → **P1-3**. |
| B12 | Tidak ada raw email/PAN/source ID di DB/log/Git | **PASS** | `git grep -I -nE '(BEGIN .*PRIVATE KEY|refresh_token=|client_secret=|ya29\.|AIza|eyJhbGciOi)'` (file committed) kosong; email di-hash HMAC keyed & sealed; source ref sealed; log hanya kode/`route` (tanpa query); raw MIME hanya di memori. |
| B13 | Migrasi 017 tidak mengubah migrasi lama | **PASS** | `git diff --name-status origin/main..HEAD -- internal/store/migrations/` = `A 017.sql` saja; 017 hanya `CREATE TABLE/INDEX`; fresh DB migration 16→17 sukses (§2). |
| B14 | Tidak ada tes di-skip/dinonaktifkan | **PASS** | Pencarian `t.Skip`, `SkipNow`, `.Only(` kosong; 150 fungsi tes; suite fresh hijau. |
| B15 | Config opt-in aman untuk production | **PASS** | `GMAIL_ENABLED=false` default; `config.Load` validasi saat enabled (client id/secret, redirect HTTPS tanpa query/fragment, topic, audience, service account); fail-closed terverifikasi (§2). `.env.example`, README, `docs/GMAIL_API_SETUP.md` konsisten. |
| B16 | Kualitas: race/transaksi, indeks unik, error mapping, retry/backoff, log, validasi, kecukupan tes | **PARTIAL** | Lihat P2-2 s/d P2-7. |
| B17 | Tombol "Sinkronkan sekarang"/retry memakai koordinator yang sama | **FAIL/NOT RUN** | Tidak ada endpoint/trigger manual; hanya komentar `coordinator.go:10` dan klaim README/GMAIL_API_SETUP. Mandat §4.4 belum terpenuhi (P2-8). |
| B18 | Kejujuran dokumen evidence | **PARTIAL** | Status PARTIAL + Console/CASA/E2E **NOT RUN** jujur dan lengkap. Over-claim: reconnect "di-stop/di-revoke", jalur "Sinkronkan sekarang", hitungan "25 tes baru" (aktual 26). |

### Android (kontrak §1–§6)

| # | Butir | Verdict | Bukti ringkas |
| --- | --- | --- | --- |
| A1 | Konsistensi field kontrak | **PASS** | `RemoteGmail.kt` persis field kontrak + `@SerialName`; `toDomain` menoleransi timestamp rusak; 409/503 ditangani taxonomy. |
| A2 | Tanpa token/email penuh di klien | **PASS** | DTO hanya proyeksi; tak ada field token/code/state; tidak ada logging di file Gmail (grep `Log./print` kosong); tes menegaskan hanya email masked. |
| A3 | Deep link `pam://gmail/connected?status=&reason=`, replay/duplikat intent | **PARTIAL** | Manifest intent-filter `VIEW/BROWSABLE` scheme `pam` host `gmail` pathPrefix `/connected`; `MainActivity.onCreate`+`onNewIntent`; nav hanya saat gate Ready; banner + refresh. Namun intent tidak "dibersihkan"/di-dedup → banner dapat tampil ulang saat activity dibuat ulang/process restore; tidak ada tes replay (P2-10). |
| A4 | State backend-off jujur | **PARTIAL** | Build tanpa backend: `isEnabled=false`, layar menjelaskan (tes ada) ✓. Tetapi backend aktif dengan `GMAIL_ENABLED=false` → `503 gmail_disabled` dipetakan `AppError.Network("backend_503")` → copy "gangguan jaringan" + tombol connect tetap aktif (P2-11). |
| A5 | UI vs kontrak (masked, sync state, watch, disconnect 409) | **PASS** | `GmailLinkScreen` menampilkan masked + sync_state (idle/backfill/delta/error) + last_sync + watch_expires_at; dialog konfirmasi disconnect; status null = belum tertaut (bukan error). |
| A6 | Tidak ada regresi IMAP | **PASS** (dengan risiko P1-3) | `RoutingGmailSource` mempertahankan IMAP (app password) & fixture untuk build tanpa backend; tidak ada penghapusan jalur lama. Risiko: app password lama tetap diprioritaskan sehingga impor perangkat + impor server dapat berjalan bersamaan → P1-3. |
| A7 | String id/en parity | **PASS** | `values/strings.xml` (default Indonesia) vs `values-en`: 29 kunci `gmail_link_*` identik (diff kosong); tidak ada `values-in` (default sudah id). |
| A8 | Tes segar | **PASS** | 26 tes gmail-link hijau (13 VM + 13 screen), 0 gagal/0 skip di HEAD `b5f4905`; dokumen UI menyebut "25" → P3-1. |
| A9 | Walk perangkat/emulator + OAuth live | **NOT RUN** | Butuh backend `GMAIL_ENABLED=true`, OAuth client, akun pilot, emulator — tidak tersedia di audit ini. |

## 4. Temuan

### P0
Tidak ada.

### P1

**P1-1 — Impor awal tidak mengimplementasikan buffer penerimaan / keanggotaan berdasarkan
timestamp transaksi (mandat §4.1).**
`monthQuery` memakai tanggal email sebagai filter (`after:YYYY/MM/DD before:YYYY/MM/DD`, WIB)
(`internal/gmail/service.go:615–620`), lalu `buildCandidates` membuang kandidat yang bulan
transaksinya ≠ bulan job sebagai `UNRESOLVED/outside_period`
(`internal/gmail/worker.go:282–304`). Akibat: notifikasi Livin yang **email-nya masuk bulan
berikutnya** untuk transaksi akhir bulan sebelumnya (kasus umum notifikasi tertunda) tidak masuk
pada backfill 12 bulan — dan tidak akan pernah discan ulang karena query bulan lama tidak
memuatnya. Jalur delta (`importHistoryMessages`, worker.go 226–277) mengelompokkan per bulan
`eventAt`, jadi hanya email yang tiba **setelah** koneksi yang tertangkap. Tidak ada tes
buffer/late-arrival (pencarian `buffer|late|out_of_order` kosong). Dampak: kelengkapan data
keuangan satu tahun bisa kurang pada transaksi batas bulan.

**P1-2 — Reconnect ke mailbox lain tidak memicu impor awal dan tidak membersihkan watch/token
lama.**
`Start(reconnect=true)` hanya membuat state baru (`service.go:100–105`); `SaveGmailConnection`
mengganti baris koneksi tetapi **tidak** menyetel ulang/menghapus `gmail_sync_jobs`
(`internal/store/gmail.go:169–222`). Karena `CompletedGmailBackfillMonths` per owner (bukan per
mailbox, store/gmail.go:675–700), setelah ganti akun `backfillPending` bernilai false → **tidak ada
backfill 12 bulan untuk mailbox baru**; koneksi langsung idle/delta. Selain itu watch mailbox lama
tidak di-`stop` dan refresh token lama tidak di-revoke (hanya ditimpa), sehingga klaim evidence
"koneksi lama di-stop/di-revoke" tidak benar. Mandat §4.1/§4.5 (impor awal setelah consent; renew/
stop watch) tidak terpenuhi untuk jalur reconnect. Belum ada tes reconnect-ganti-mailbox.

**P1-3 — Migrasi/koeksistensi transaksi IMAP lama → Gmail API belum dibuktikan (mandat §4.3).**
Tidak ada verifikasi bahwa transaksi IMAP lama tidak terduplikasi oleh Gmail API: UID IMAP ≠
message ID Gmail; dedupe hanya per owner+mailbox dengan `source_ref` berbeda antar-jalur. Tidak ada
mode pembandingan-tanpa-mutasi, pemetaan identitas terverifikasi, jalur koreksi baris, atau
penanda cutover. Di Android, `RoutingGmailSource` tetap memprioritaskan IMAP bila app password
tersimpan; bila pengguna menautkan Gmail via server tanpa menghapus app password, impor server
(Gmail API) dan impor perangkat (IMAP) dapat berjalan bersamaan dan menghasilkan baris ganda
(amount/tanggal/merchant sama, source ref beda). Status: **NOT RUN/FAIL** — memerlukan keputusan
produk + uji migrasi.

### P2

- **P2-1 — Reconnect tidak stop/revoke koneksi lama** (bagian dari P1-2; `service.go:100–105`,
  `store/gmail.go:169–222`): watch lama tetap aktif di Google, token lama tetap valid, push untuk
  mailbox lama di-ACK sebagai unmapped.
- **P2-2 — `EnsureGmailDeltaJob` rawan duplikat** (`store/gmail.go:549–577`): SELECT-lalu-INSERT
  tanpa indeks unik parsial untuk `kind='DELTA'` (BACKFILL sudah unik). Pada multi-replica/worker
  paralel dapat terbentuk dua job delta untuk owner yang sama.
- **P2-3 — Recovery cursor 404 memajukan cursor setelah rescan terbatas**
  (`internal/gmail/worker.go:124–144`): `importMonthPages` dipanggil dengan `Done` diabaikan dan
  dibatasi `MaxPagesPerRun`; job tetap COMPLETE + cursor baru, sehingga sisa halaman bulan berjalan
  bisa terlewat (dedupe aman, tetapi gap data sampai event berikutnya).
- **P2-4 — Semantik event push lemah** (`service.go:400–411`, `452–463`): event ditandai
  `PROCESSED` segera setelah job delta diantrekan (belum tentu terproses); kegagalan refresh token
  transien menandai event `FAILED` permanen (tidak ada retry/attempt cap; hanya `PENDING` yang
  diproses). Event juga tidak pernah dipangkas.
- **P2-5 — Mapping error provider terlalu kasar & tanpa backoff** (`google.go:70–81`): semua 404
  (termasuk non-history) dianggap `history_expired`; 401/403 (termasuk kuota/rate limit 403)
  dianggap `ErrUnauthorized` → `NEEDS_REAUTH`. Tidak ada retry/backoff eksplisit (hanya tick
  berikutnya); `isTransientProviderError` adalah dead code.
- **P2-6 — Counter checkpoint delta basi** (`worker.go:~100–118`): `CheckpointGmailSyncJob`
  memakai `job.Seen/Imported/Duplicates/Skipped` yang tidak pernah diperbarui per halaman → metrik
  job salah.
- **P2-7 — Access token di-refresh setiap tick** (`service.go:380`, `accessToken` 478+): satu call
  token endpoint per owner per interval (default 60 dtk) tanpa cache → kuota/latensi berlebih.
- **P2-8 — Klaim "Sinkronkan sekarang" belum ada** (`README.md:74–75`,
  `docs/GMAIL_API_SETUP.md:97–98`, `coordinator.go:10`): tidak ada endpoint/trigger manual; mandat
  §4.4 menuntut tombol/retry memakai koordinator yang sama. `GMAIL_SCOPE` configurable tetapi
  Callback meng-hardcode `gmail.readonly` (`service.go:~170`) → salah konfigurasi memutus flow.
- **P2-9 — State OAuth tidak cek `RowsAffected`** (`store/gmail.go:152–156`): update
  `WHERE consumed_at IS NULL` tanpa memeriksa hasil. Replay sekuensial aman (dan replay konkuren
  tetap gagal di Google karena `code` sekali pakai), tetapi jaminan "sekali pakai" di layer store
  belum tegas; tidak ada tes konkuren.
- **P2-10 — Deep link bisa tampil ulang** (`MainActivity.kt:100–114`; `PamNavHost.kt:82–92`):
  intent callback tidak dibersihkan/di-dedup, banner dapat muncul lagi saat recreate/restore;
  tidak ada tes replay/duplikat intent.
- **P2-11 — Status "backend nonaktif" runtime tidak jujur** (`BackendErrors.kt:20–40`):
  `503 gmail_disabled` → `AppError.Network("backend_503")` (copy "gangguan jaringan") dan tombol
  connect tetap aktif; tidak ada pemetaan khusus `gmail_disabled`. Yang terbukti jujur hanyalah
  build tanpa backend (`isEnabled=false`).

### P3

- **P3-1 — Hitungan tes evidence UI** `GMAIL_UI_2026-11-24.md:41` menyebut "25 tes baru
  (13+13)" — aktual **26** (13+13); suite segar tetap hijau.
- **P3-2 — Reason code mentah ditampilkan** di banner (`gmail_link_failed` `%1$s`), mis.
  `state_invalid`/`mailbox_taken`; aman (tidak ada PII) tetapi belum ramah pengguna dan kontrak
  hanya memberi contoh (`mis.`) sementara server mengirim 11 kode.
- **P3-3 — Baris `gmail_oauth_states`/`gmail_push_events`** tidak pernah dipangkas (akumulasi
  data operasional, bukan PII).

## 5. Penilaian kejujuran dokumen evidence

- `0037-Backend-Financial/docs/evidence/GMAIL_API_2026-11-24.md`: status **PARTIAL** dan
  §Batas/NOT RUN (Console, CASA restricted scope, E2E akun pilot, rotasi secret) **jujur dan
  lengkap** — tidak ada klaim runtime live. Klaim statistik yang dicek cocok: 150 fungsi tes, 13
  gmail, 8 httpapi+store, vet/test/build hijau. Over-claim: reconnect "koneksi lama di-stop/
  di-revoke" (P1-2) dan "maks 5 halaman per tick" benar; "buffer" tidak diklaim di evidence
  (kekurangan ada pada implementasi vs mandat, bukan klaim palsu).
- `docs/GMAIL_UI_2026-11-24.md`: status **PARTIAL** + walk perangkat/OAuth live **NOT RUN** jujur;
  hitungan tes salah kecil (P3-1). Klaim "tanpa token/full email" terbukti di kode + tes.
- README/`GMAIL_API_SETUP.md`: konsisten soal env `GMAIL_*`/default nonaktif, tetapi mengklaim
  jalur "Sinkronkan sekarang" yang belum ada (P2-8).

## 6. Batas audit (belum dapat diverifikasi)

1. Google Cloud Console (project, consent screen, OAuth client, Pub/Sub topic/subscription,
   grant `roles/pubsub.publisher`) — **NOT RUN**.
2. OAuth live + Pub/Sub push nyata + verifikasi restricted scope/CASA — **NOT RUN**.
3. E2E akun pilot: connect → backfill setahun → email Livin baru → delta → kategorisasi — **NOT RUN**.
4. Walk perangkat/emulator UI (connect→consent→callback→status; replay deep link) — **NOT RUN**.
5. Turso/DB produksi jarak jauh, rotasi secret UAT, multi-replica/concurrency load — **NOT RUN**;
   uji migrasi dan smoke memakai SQLite lokal di `/tmp` (satu instance).
6. WIP lane auto-kategorisasi di worktree backend tidak diaudit (di luar `9d388ae`).
7. Audit ini **tidak memperbaiki** kode, tidak merge, tidak deploy; commit hanya dokumen audit ini.

## 7. Ringkasan

- **PASS**: encripsi kredensial, OIDC push + idempotensi ACK, watch renew/stop, backfill 12 bulan
  WIB dengan checkpoint, dedupe + identitas opaque, migrasi 017 additive, opt-in fail-closed,
  privasi (tanpa raw email/PAN/source ID), tidak ada tes di-skip; UI Android sesuai kontrak,
  tanpa token/email penuh, string id/en paritas, 26 tes gmail-link hijau.
- **PARTIAL**: reconnect (P1-2), recovery delta (P2-3), kualitas worker/race (P2-2/4/5/6/7),
  state backend-off runtime (P2-11), deep-link replay (P2-10).
- **FAIL/NOT RUN**: buffer penerimaan/keanggotaan timestamp (P1-1), migrasi IMAP→Gmail (P1-3),
  tombol "Sinkronkan sekarang" (P2-8), seluruh gate live.
