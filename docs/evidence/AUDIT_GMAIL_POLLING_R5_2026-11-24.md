# AUDIT RONDE 5 — re-audit perbaikan ronde 4 + bukti UAT baru (Gmail polling v2)

Tanggal: 2026-11-24 ~17:19–17:28 WIB (UTC+7). Auditor: `auditor` (independen).
Mode: **read-only** — tidak ada perubahan kode, commit, migrasi, force-push, atau menyentuh server/proses lane lain.

## 0. Target audit dan drift HEAD

| Repo | Branch | HEAD saat pesan Lead | HEAD saat laporan | Perubahan selama audit |
|---|---|---|---|---|
| `0037-Backend-Financial` | `integration/2026-11-24` | `1290910` | `12ce301` | 1 commit dokumen (`README.md` saja, +42/−14) |
| `pam-app` | `feat/c2-android` | `fc509a0` | `3e34436` | 2 commit dokumen (`README.md`, `CR_007…`, `FINAL_GATES…`), **tanpa perubahan kode** |

`git diff --stat 1290910..12ce301` = `README.md`; `git diff --stat fc509a0..3e34436` = 3 file dokumen. Karena semua gerbang yang saya jalankan ulang di HEAD sekarang menghasilkan kode yang identik, temuan kode di bawah valid untuk kedua pasangan HEAD. Semua verifikasi segar dilakukan pada HEAD terbaru; angka test/freshness mengacu ke HEAD terbaru dan SHA target Lead dicatat bila berbeda.

Catatan worktree saat audit selesai: backend bersih; Android menyisakan WIP dokumen milik lane lain (`CR_007_DRAFT_…`, `AUTO_SYNC_CATEGORY_STATUS_PLAN.md` belum di-commit). Tidak saya sentuh (read-only).

## 1. VERDICT

**Tidak ada temuan MAJOR/P0 yang tersisa. Siap merge ke target integrasi (bukan izin merge; pemilik memutuskan).**

Kedua MAJOR ronde 4 terbukti tertutup di kode + test kedua repo, dan sweep regresi (202/coalescing, 409/503, disconnect, needs_reauth, delta terjadwal vs manual, restart, dedupe) tidak menemukan regresi fungsional. Tersisa **3 MINOR dan 3 NIT** yang tidak memblokir merge: satu bug UI edge-case (label "Menghubungkan" masih bisa lengket bila status GET terus gagal), satu mismatch komentar/param cutoff, dan tiga soal kejujuran/kelengkapan bukti (ukuran-versi APK, provenance QA E2E, mapping 409 baru di Android).

## 2. KLAIM RONDE 4 — TERBUKTI TERTUTUP

### MAJOR-1 (marker `sync_requested_at` tidak pernah dibersihkan → "Menghubungkan" permanen)
- Backend: `internal/store/gmail.go:484-494` `ClearGmailSyncRequest`; dipanggil saat job DELTA selesai (`internal/gmail/worker.go:128`), replay setelah recovery selesai (`:336`), dan terminal `needs_reauth` (`:360`, `failJob`). Permintaan manual ditolak lebih awal bila grant dicabut: `internal/gmail/service.go:438-440` mengembalikan `ErrNeedsReauth` sebelum menulis marker/antrean; handler memetakan `409 gmail_needs_reauth` (`internal/httpapi/handlers_gmail.go:242-246`).
- Test: `internal/gmail/polling_test.go:161-197` (drain → `SyncRequestedAt` nol, `sync_state=idle`, bukan needs_reauth), `TestManualSyncRejectedWhenNeedsReauth` (tidak ada marker/antrean), test HTTP membuang job lalu memastikan `sync_requested_at == nil`, dan `TestGmailSyncRejectedWhenNeedsReauth`.
- Android: state panel tidak lagi diturunkan dari marker server. `GmailServerSyncUi.requestHintAt` hanya diisi dari ack 202 (`UpdateStatusViewModel.kt:149`), dibersihkan pada respons status pertama (`:239`), dan `connecting(now)` = `!running && !needsReauth && syncState != ERROR && hint != null && now-hint <= 20s` (`GmailServerSyncUi.kt:73-78`). Test: `UpdateStatusServerSyncTest` ("a stale sync_requested_at marker never pins the connecting label", "after a 202 an idle status with the marker still present settles on waiting", "a fresh request hint renders connecting only until the status supersedes it") + `UpdateStatusSheetServerSyncTest` ("an idle projection without a hint says waiting, never connecting"). Artefak test 17:09 memuat test-test ini.
- Kesimpulan: **tertutup untuk jalur normal**; sisa edge-case status-GET gagal = F2 (MINOR), bukan regresi MAJOR-1 lama (dulu lengket bahkan saat backend sehat).

### MAJOR-2 (kontrak 429 fase failure)
- Handler: `internal/httpapi/handlers_gmail.go` kini mengirim `{"code":"sync_cooldown","retryable":true,"retryAfterSeconds":N}` (camelCase, tanpa snake_case). Test HTTP menolak keberadaan `retry_after_seconds` dan memastikan `retryable==true`, `retryAfterSeconds>0`.
- Fase: `recordFailure(ctx, connection, err, phase)`; token error memilih `phase="backfill"` bila hanya backfill due (`internal/gmail/service.go`), `runDelta` → `"delta"`, `runBackfill`/`failJob(BACKFILL)` → `"backfill"`; watch tetap `"delta"`. Test `TestBackfillFailureKeepsBackfillPhase` (429 provider, `sync_state=="backfill"`, `LastErrorCode` terisi, bukan needs_reauth).
- Android: `RemoteGmailSyncError` memakai `retryAfterSeconds` camelCase + `@JsonNames("retry_after_seconds")` sebagai toleransi klien lama; test `PamApiClientTest` ("gmail sync 429 carries the server cooldown", "gmail sync 429 also tolerates a legacy snake_case retry delay").
- Config mati `TransientRetryBackoff` dihapus (`service.go`); docs diselaraskan (`GMAIL_API_CONTRACT.md` §4/§9, `GMAIL_API_SETUP.md` butir manual sync, `GMAIL_OWNER_UAT_RP0.md` §3a prosedur UAT FIXTURE).
- Kesimpulan: **tertutup**.

## 3. TEMUAN (MINOR/NIT) — tidak memblokir merge, perbaiki sebelum/segera setelah merge

### F1 (MINOR) — parameter `cutoff` tidak melindungi request yang datang di tengah run; komentar menjanjikan proteksi yang tidak ada
- Lokasi: komentar `internal/store/gmail.go:484-486` ("The cutoff protects a request that arrived after this run started"); pemanggil `internal/gmail/worker.go:128`, `:336`, `:360` semuanya `ClearGmailSyncRequest(ctx, ownerID, s.Now(), s.Now())` — `cutoff == waktu selesai`.
- Dampak: request yang diterima saat run berjalan (`sync_requested_at` < waktu selesai) ikut dibersihkan sebelum "dilayani"; hanya request setelah SQL clear yang bertahan. Tidak ada kehilangan data (delta terjadwal 15 menit menyusul) dan UI baru mengabaikan marker, tetapi klaim keamanan cutoff di komentar/desain **tidak benar** dan tidak ada test yang mengunci semantik ini (`grep cutoff --include=*_test.go` → tidak ada; tidak ada test `ClearGmailSyncRequest` di `internal/store/gmail_test.go`).
- Fix minimal: tangkap `startedAt := s.Now()` sebelum job dijalankan dan pakai sebagai `cutoff` (bukan waktu selesai); tambah test store: marker sebelum run → terhapus, marker selama run → bertahan, marker setelah selesai → bertahan. Bila perilaku "coalesce ke job yang sedang berjalan" memang disengaja, hapus parameter `cutoff` dan perbaiki komentar.

### F2 (MINOR) — "Menghubungkan" masih bisa lengket bila status GET terus gagal setelah 202
- Lokasi: `app/.../UpdateStatusSheet.kt:216` (`val now = state.observedAt ?: fallbackNow`; `now` = `observedAt` beku saat status sukses terakhir), `GmailServerSyncUi.kt:73-92` (`CONNECTING_HINT` 20 dtk), `UpdateStatusViewModel.kt:185-187` (cabang gagal `copy(loading=false, error=...)` tidak menghapus `requestHintAt`).
- Dampak: setelah tap 202 (hint diisi), bila `GET /v1/gmail/status` gagal terus, `observedAt` tidak maju dan selalu < hint, sehingga `connecting()` **selalu true** — label "Menghubungkan" bertahan selama kegagalan berlangsung (bisa lintas buka/tutup sheet), padahal backend tidak terjangkau. Klaim KDoc "elapsed-time cap keeps a stale ack from lingering if that response never arrives" tidak tercapai karena cap dibandingkan dengan `observedAt` beku (secara efektif dead code di produksi). Ini bukan regresi MAJOR-1 (tidak terjadi saat backend sehat), tetapi melanggar syarat "UI tidak mungkin menampilkan Menghubungkan permanen" secara literal.
- Fix minimal: di cabang gagal `refreshServerSyncNow`, tambahkan `requestHintAt = null` (satu baris) + test: ack 202 → status gagal berulang → `connecting(now)` false dan error tampil. Alternatif: pakai jam live (bukan `observedAt`) untuk `connecting`.

### F3 (NIT) — marker tidak dibersihkan saat disconnect/reconnect dan pada recovery-expiry kedua
- Lokasi: `internal/store/gmail.go:508-522` (`DisconnectGmail` tidak mereset `sync_requested_at`), `SaveGmailConnection` upsert `:205-260` (tidak mereset marker), `internal/gmail/worker.go:297-309` (replay yang kembali `history_expired` menutup job tanpa clear marker).
- Dampak: `GET /v1/gmail/status` dapat menampilkan `sync_requested_at` non-null saat idle setelah reconnect / hingga ±15 menit setelah recovery-expiry kedua, bertentangan dengan kalimat kontrak baru ("hanya terisi selama job … belum selesai"). UI baru mengabaikannya; klien lama (yang memakai marker) bisa lengket lagi.
- Fix minimal: set `sync_requested_at = NULL` pada `DisconnectGmail` dan pada upsert koneksi baru; panggil `ClearGmailSyncRequest` pada cabang replay-expiry.

### F4 (MINOR) — bukti UAT tidak konsisten soal identitas APK (ukuran & waktu build)
- Klaim: `pam-app/docs/evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md:13` dan `docs/delivery/FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md` (G19/pasangan build) — "APK debug **46.433.025 byte**, dibangun 17:11 WIB".
- Fakta artefak saat audit: `pam-app/app/build/outputs/apk/debug/app-debug.apk` = **46.433.080 byte**, mtime **17:08:19**, sha256 `93b69cc0e5506d54f3f11c1ecf05f9b683b11526e7add0459de0d6e04730d071`. Ukuran 46.433.025 justru milik APK worktree lain `pam-wt-gmail-server-ui/app/build/outputs/apk/debug/app-debug.apk` (17:03). Commit kode final `addc240` bertimestamp 17:07:14, jadi build 17:08 di `pam-app` masuk akal; angka di dokumen berasal dari artefak lama/lain.
- Dampak: identitas artefak UAT tidak dapat direproduksi dari dokumen (bukan bukti palsu — semua klaim fungsional didukung screenshot, tetapi provenance tidak ketat). Gate "audit independen" sebaiknya tidak lolos dengan angka artefak yang tidak cocok.
- Fix minimal: perbarui dokumen UAT + G19 dengan path APK, sha256, ukuran, dan waktu build sebenarnya (atau rebuild + install ulang lalu ulangi screenshot kunci); sebutkan hasil `shasum -a 256`.

### F5 (MINOR) — bukti QA E2E di repo masih milik backend `5d76c7f`; klaim "diulang di 1290910" belum terdokumentasi, dan harness tidak meng-assert field perbaikan ronde 4
- `docs/evidence/GMAIL_CONTRACT_E2E_2026-11-24.md` menulis backend yang diuji `5d76c7f` dan harness di branch `work/gmail-qa-harness`. Gate doc G27 mengklaim run ulang di `1290910` (PASS=82) — run itu memang ada di artefak `/tmp/gmail-qa-final-1290910.json` (HEAD 1290910, 82 PASS, 17:20 WIB), dan auditor mengulang independen di HEAD terbaru `12ce301`: **PASS=82, FAIL=0** (`/tmp/gmail-qa-1290910-full.json`, 82/82 PASS). Jadi klaim benar, tetapi evidence in-repo tidak mencatat run kedua.
- Celah cakupan: `scripts/qa/gmail/contract_e2e.py` hanya memeriksa status+`code` untuk 429 (baris ~725); tidak meng-assert `retryable`/`retryAfterSeconds`, pembersihan `sync_requested_at`, atau `409 gmail_needs_reauth`. Perbaikan ronde 4 diverifikasi oleh unit test backend, bukan oleh gate E2E.
- Fix minimal: tambahkan catatan run `1290910`/`12ce301` (SHA, tanggal, PASS=82, path artefak) ke evidence doc; tambah assertion 429 body + marker clearing di harness (atau nyatakan eksplisit bahwa cakupan itu milik unit test).

### F6 (MINOR) — Android belum memetakan `409 gmail_needs_reauth` ke CTA reconnect
- Lokasi: `app/src/main/java/com/pam/recap/data/backend/PamApiClient.kt:654-672` (`gmailSyncError` hanya mengenal `gmail_not_connected` dan `gmail_disabled`; 409 kode lain jatuh ke `backendError`); `AppError.kt:122-124` tidak punya rule `gmail_needs_reauth`.
- Dampak: bila grant dicabut tepat setelah status poll terakhir, tap "Sinkronkan sekarang" menerima 409 `gmail_needs_reauth` dan ditampilkan sebagai error generik, bukan CTA "Tautkan ulang Gmail". Status poll berikutnya (5 dtk) akan memperbaiki state. Kontrak §9.3 mengharapkan CTA.
- Fix minimal: tambah `AppErrorRules.GMAIL_NEEDS_REAUTH`, map ke `AppError.Validation`, tangani di `applySyncFailure` sebagai `needsReauth = true`, + test.

### F7 (NIT) — provenance audit ronde 5 belum ada di repo
- `FINAL_GATES…` G22 sudah menyatakan "re-audit temuan ronde 4 tanpa P0 tersisa" padahal laporan ini baru diterbitkan dan (sesuai mandat) berada di `/tmp`, bukan `docs/evidence/`. Isi klaim konsisten dengan hasil audit ini (tidak ada P0/MAJOR), tetapi dokumen gate sebaiknya merujuk laporan ronde 5 (path/isi) agar jejak audit lengkap — terutama karena gate G22 berstatus PASS.

### F8 (NIT) — identifier perangkat di dokumen bukti
- `GMAIL_POLLING_UAT_PHONE_2026-11-24.md` memuat model + serial perangkat (`21061119AG`, `(serial diredaksi)`) dan path profil privat `~/.config/pam/…` (tanpa nilai secret). Bukan data pribadi pemilik dan wajar untuk traceability UAT, tetapi bila dokumen dibagikan keluar, serial perangkat sebaiknya disamarkan.

## 4. SWEEP REGRESI (tidak ada regresi fungsional)

| Area | Bukti | Hasil |
|---|---|---|
| 202 / coalescing | `TestManualSyncCooldownThenCoalesces` (+extended assert marker/idle), handler tests, E2E `normal` sync #1 202 queued, #2 429; Android test "a coalesced ack is reported as coalesced, not queued" | PASS |
| 429 cooldown body + UI | handler test camelCase+retryable+retryAfterSeconds>0 tanpa snake_case; `PamApiClientTest` 2 test; UAT `status_cooldown_429.xml` = "Tunggu 57 detik" | PASS |
| 409/503 | handler tests (not_connected/needs_reauth/disabled); Android hides/disables section; CTA needs_reauth test | PASS (F6 = race mapping) |
| Disconnect | perilaku tak berubah; docs §4 dikoreksi untuk mode polling; marker cleanup = F3 | PASS (NIT) |
| needs_reauth | `RequestSync` menolak sebelum antre, job terminal + marker clear, handler 409, UI CTA; test backend+Android | PASS |
| Delta terjadwal vs manual | `RunOnce`: job delta terbuka menang atas cadence 15 mnt; clear marker pada completion; backfill bergilir setelah delta | PASS |
| Restart continuity | E2E `normal` restart (connection+state, unique 31, dedupe 0); UAT butir 9 (count 27 tetap, `sync_state=idle`, marker null) | PASS |
| Dedupe | E2E 31 unik/0 duplikat di `normal`, `history_expired`; UAT Rp9.990 muncul sekali | PASS |
| Migrasi | tidak ada migrasi baru (001–020) | PASS |

## 5. VERIFIKASI GERBANG (segar, oleh auditor)

Backend (`0037-Backend-Financial` @ `12ce301`, kode identik `1290910`; toolchain warm `pam-app/.toolchain/go` via `./scripts/go.sh`):
- `go vet ./...` → OK; `go build ./...` → OK.
- `go test ./... -count=1` → seluruh paket `ok` (termasuk `internal/gmail`, `internal/httpapi`, `internal/store`); tidak ada FAIL.
- `go test -race ./internal/gmail ./internal/store ./internal/httpapi -count=1` → OK (22,8 s / 30,8 s / 240,3 s).
- Subset yang diminta Lead (`./internal/gmail ./internal/httpapi ./internal/store -count=1`) → OK.
- QA E2E harness `scripts/qa/gmail/contract_e2e.py` (port terisolasi 18099, fake provider): **PASS=82, FAIL=0, exit 0** (head `12ce301`, 17:22 WIB). Run independen lain pada `1290910` juga PASS=82 (artefak `/tmp/gmail-qa-final-1290910.json`, 17:20 WIB). Catatan: percobaan pertama auditor sempat gagal `connection refused` karena berjalan bersamaan dengan run harness lain di port preferensi sama; pengulangan terisolasi PASS — bukan regresi backend.
- Binary UAT `/tmp/pam-gmail-uat/bin/pam-api`: `go version -m` menunjukkan `mod … v0.0.0-20261124100713-1290910038a2` (commit `1290910`), mtime 17:12:28 → provenance backend UAT **terverifikasi**.

Android (`pam-app` @ `3e34436`, kode identik `fc509a0`; build berat tidak dijalankan ulang sesuai instruksi):
- Unit: 639 tes / 83 suite / 0 failure / 0 error (artefak `app/build/test-results/testDebugUnitTest`, 17:09) — termasuk test baru ronde 4.
- Lint: 120 warning + 1 hint, **0 error** (artefak `app/build/reports/lint-results-debug.xml`).
- APK debug: 46.433.080 byte, 17:08:19, sha256 `93b69cc0…` — **tidak sama** dengan angka di dokumen (F4).
- Tidak ada perubahan kode setelah `fc509a0`.

## 6. VERIFIKASI KEJUJURAN BUKTI

| Klaim | Verifikasi auditor | Status |
|---|---|---|
| FIXTURE/fake provider eksplisit, bukan Gmail live | UAT doc §header + §4, gate G25/G29 NOT RUN; QA evidence header "not owner UAT, not real Google" | Jujur |
| Gmail live / consent Google / Pub/Sub / GCP Rp0 | dinyatakan **NOT RUN (owner)** / OPEN di gate G25 & G29 | Jujur (tidak di-PASS-kan) |
| 27 transaksi, delta 17:15:34, count 26→27 | tidak ada dump DB/log runtime di repo; narasi UAT. Didukung UI: 5 baris November (Rp9.990 sekali) + "5 belum berkategori" + total Rp20.530,01 | Sebagian dapat diverifikasi; angka transisi murni naratif |
| Rekonsiliasi 5 baris / Rp20.530,01 | `ringkasan_after_delta2.xml`: "Rp20.530,01", "5 belum berkategori", "Rp9.990" satu kali; `transactions_after_delta2.xml`: 5 baris November | Terverifikasi |
| Cooldown 59/57 dtk | `status_cooldown_429.xml`: "Tunggu 57 detik" (body 59 = narasi; UI poll 2 dtk lebih lambat, konsisten) | Terverifikasi (UI) |
| Panel status final "Menunggu", 12/12, jadwal 17:16 | `status_sheet_final.xml` memuat ketiganya; `status_sheet_after_delta.xml` sengaja menyimpan bug "Menghubungkan" build pra-fix sebagai pembanding | Terverifikasi |
| APK 46.433.025 / 17:11 | artefak aktual 46.433.080 / 17:08:19 (F4) | **Tidak cocok** |
| QA E2E PASS=82 | run auditor segar PASS=82 (`12ce301`); run `1290910` PASS=82; namun evidence in-repo hanya mencatat `5d76c7f` (F5) | Terverifikasi, provenance in-repo perlu dilengkapi |
| Backend UAT `1290910` | `go version -m` binary = pseudo-version `…1290910038a2` | Terverifikasi |
| Tanpa secret/email nyata/token/nominal nyata | XML dump: tidak ada `@`, token, serial, nomor; akun `uat.pm@pam.test` sintetis, mask `p***@pam.test`; nominal fixture sintetis | Jujur |

## 7. SISA NOT RUN / OPEN (bukan tanggung jawab auditor/lane, hanya pemilik)

- Gmail live E2E: consent Google nyata, OAuth Web client/secret, test users, restricted-scope/CASA, kuota & latensi Gmail nyata (G25).
- GCP Rp0 / billing masih tertaut; ukur pemakaian nyata (G29) — status OPEN.
- Push opt-in Pub/Sub: topik, service account grant, endpoint HTTPS (tidak diperlukan untuk default polling).
- TalkBack manual + lebar 393/412 dp di APK final (G18 PARTIAL); drill rollback DB (G3 PARTIAL); proteksi branch `main` backend (G4 PARTIAL); retire penuh jalur IMAP (G17 PARTIAL).
- Drill hapus/restore retensi pada data nyata (G14) dan keputusan merge/deploy/SRS resmi (G24).
- Tombol **Run** Android Studio secara literal (instalasi UAT dilakukan via `adb install -r`; UI assets mendukung build yang diuji, tetapi bukan bukti klik Run).

## 8. REKOMENDASI URUTAN

1. Sebelum merge: perbaiki F2 (satu baris + test) dan F4 (angka APK di dokumen) — keduanya menyentuh klaim gate/UI yang diaudit.
2. Sebelum gate pemilik: F1 (semantik cutoff/test), F5 (update evidence QA + assertion), F6 (mapping 409 CTA), F3 (cleanup marker disconnect/reconnect).
3. Opsional/kerapian: F7 (rujuk laporan V3 di gate G22), F8 (samarkan serial perangkat bila dokumen dibagikan).

## 9. PERNYATAAN

Audit ini independen dan read-only. Tidak ada file repo yang saya ubah, tidak ada commit, tidak ada push/force-push, dan tidak ada data pribadi pemilik yang disalin ke laporan ini. Semua angka berasal dari artefak di dua repo, artefak `/tmp` hasil run QA, dan perintah yang saya jalankan sendiri pada 2026-11-24 ~17:19–17:28 WIB.
