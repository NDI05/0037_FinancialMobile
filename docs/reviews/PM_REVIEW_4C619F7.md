# Review PM — 4c619f7: parser happy path lulus, import acceptance belum

Tanggal: 22 November 2026. Branch `feat/c2-android`; HEAD lokal dan remote
`4c619f7d68d21dd3a2d95fc985896f2aa45bfe13`, worktree bersih saat mulai review.
Review ini tidak mengubah aplikasi/backend atau data akun asli.

## Keputusan

- Split-date/time WIB valid **PASS untuk happy path yang diuji**, bukan seluruh validasi parser.
- Import server tetap **PARTIAL / belum diterima**; uploader dan satu fake-server test adalah
  kemajuan implementasi, bukan bukti lifecycle UI/API/database.
- Refresh 2,53 detik tetap observasi worker dengan acceptance PM PENDING REVIEW. Tidak ada
  evidence mutation baru pada checkpoint ini yang mengubah keputusan review sebelumnya.
- Lost-response Android tetap OPEN. Proxy QA standalone di `0d7ba18` tersedia dan sudah lulus
  backend smoke; narasi percobaan proxy lama tidak menggantikan penggunaan tool yang baru.
- S2 belum selesai; S3/S4 belum selesai. Tidak ada dasar untuk klaim kandidat tiga sprint lengkap.

## Temuan yang harus diperbaiki

### P1 — tanggal/jam invalid dinormalisasi menjadi waktu transaksi lain

`LivinQrisParser.kt:282` dan formatter pada `:336` memakai `DateTimeFormatter.ofPattern`
tanpa resolver STRICT. Probe reflection pada method split-date/time yang sudah dikompilasi
menghasilkan:

| Input sintetis | Hasil aktual | Expected |
|---|---|---|
| 19 Nov 2026, 09:15:20 WIB | 2026-11-19T02:15:20Z | parsed |
| 31 Nov 2026, 09:15:20 WIB | 2026-11-30T02:15:20Z | reject invalid calendar |
| 19 Nov 2026, 24:00 WIB | 2026-11-18T17:00:00Z | reject invalid clock |
| 19 Nov 2026, 09:15:20 WITA | null | reject unsupported timezone |

Probe adalah pemanggilan method parser aktual lewat JVM unit-test classpath, bukan E2E MIME
atau emulator. Hasilnya tetap menunjukkan nilai invalid berubah menjadi waktu finansial sah.
Gunakan validasi strict pada split dan combined datetime, lalu regression tests melalui boundary
MIME→parser dan public parse. Jangan mengganti expected output agar sesuai normalisasi tersebut.

### P1 — upload gagal setelah parse COMPLETE tidak mempunyai recovery UI yang benar

`ImportJobViewModel.kt:133` memanggil uploader hanya di `start`. `retry` pada `:115` hanya
memanggil `RetryImportUseCase`; uploader tidak dipanggil. `canRetry` pada `:40` mengikuti
job lokal, sehingga local COMPLETE + upload failure juga tidak menawarkan retry. Use case retry
malah langsung mengembalikan local COMPLETE. Pengguna dapat melihat error upload dengan journal
lokal sudah selesai dan tanpa aksi sah untuk meneruskan upload.

Pisahkan parse progress dan remote acknowledgement, persist pending remote state, dan pastikan
aksi retry/resume benar-benar mengirim ulang operasi yang tertunda serta dapat bekerja setelah
restart. Jangan menganggap local COMPLETE sebagai bukti remote complete.

### P1 — identitas job/batch belum durable; retry selalu membuat job server baru

`ServerImportUploader.kt:27` membuat job baru setiap invoke. Tidak ada mapping durable
owner/local intent→remote job ID, ack progress atau immutable batch snapshot. Batch key yang
mengandung local job ID/page saja belum membuktikan idempotensi: lookup backend scoped ke
owner+server job+batch key (`backend-go/internal/store/imports.go:147`). Respons create/batch/
finalize hilang dan process death belum mempunyai recovery yang terbukti.

### P1 — kandidat berasal dari semua email bulan tersebut; counts/reason/finalize tidak selaras

`ServerImportUploader.kt:33` membaca seluruh transaksi EMAIL bulan itu dari Room, bukan stream
kandidat job yang sedang diproses. Kandidat yang skipped/unresolved sudah tidak ikut tersampaikan.
Counters respons batch dihitung di variabel lokal tetapi tidak digunakan untuk UI/finalisasi.

Contoh yang dapat diturunkan dari source: job lokal COMPLETE dengan semua kandidat rejected dan
bulan tanpa row EMAIL menghasilkan nol batch, lalu finalize COMPLETE. Go menolak COMPLETE tanpa
pages/matched (`imports.go:287`). Jika job lokal EMPTY tetapi ada email lama dalam bulan itu,
uploader justru dapat mengirim row lama sebelum finalize EMPTY ditolak karena imported>0.
Ini analisis jalur kode, belum run E2E pada API.

Bangun stream kandidat parsed/rejected yang tersanitasi sesuai kontrak, pertahankan counts dan
reason codes untuk job yang tepat, dan gunakan hasil server untuk state UI/coverage. Jangan
mengubah all-rejected menjadi EMPTY jika itu menyembunyikan matched/skipped.

### P2 — category lookup failure disamarkan dan refresh setelah import belum terhubung

`ServerImportUploader.kt:31` mengubah kategori GET failure menjadi empty list; upload dapat
berjalan dengan categoryId null tanpa menjelaskan kegagalan lookup. Bedakan kategori memang
tidak dipetakan dari lookup failed. Pilihan fallback harus sesuai kontrak dan eksplisit.
Uploader/ViewModel juga tidak memanggil `FinanceRefreshBus`; jangan mengasumsikan invalidation
manual-save otomatis berlaku untuk import. Buktikan list/summary UI berubah setelah ack.

### P1 requirement gap — key server stabil dan counts UI server belum tersedia

DI masih mengikat `KeystoreHmacKeyProvider` yang memakai master random perangkat. Ini bukan
account fingerprint key server yang stabil setelah reinstall/perangkat lain. UI job masih
berasal dari `ImportRepository` lokal; hasil uploader hanya remote job ID yang tidak disimpan.
Dedupe lintas restart biasa tidak membuktikan reinstall, dan counts lokal bukan counts server.

## Validasi aktual PM

Perintah targeted:

```bash
./scripts/build.sh :app:testDebugUnitTest \
  --tests '*LivinMimeImportTest' --tests '*ServerImportUploaderTest'
```

**7 tests PASS**: enam `LivinMimeImportTest`, satu `ServerImportUploaderTest`, XML nol failures/
errors/skipped. Satu test uploader memakai FakeServer; tidak menguji wire Go atau emulator.
Probe JVM tambahan menghasilkan empat hasil tabel di atas. Evidence tersanitasi:
`docs/evidence/pm_4c619f7_parser_probe.json`.

PM tidak menjalankan ulang seluruh suite 250, Go/Node suite, lint/assemble, Gmail asli, atau
emulator import E2E pada review ini. Hasil worker 250 dan hasil Go 42 tetap dibedakan dari
targeted run PM. Tidak ada credential/raw email/real financial rows di evidence.

## Arahan lanjut

Jalankan `docs/agent-prompts/RESUME_4C619F7_IMPORT_ACCEPTANCE.md` bersama mandat S2–S4 dan
prompt Livin sebelumnya. Prioritaskan penutupan defect dan satu run fixture→Go→UI yang dapat
diulang. Selesaikan gate C2A menggunakan tool yang sudah tersedia, kemudian lanjut backlog
S2–S4. Jangan berhenti lagi dengan uploader unit test dan checkpoint dokumentasi saja.
