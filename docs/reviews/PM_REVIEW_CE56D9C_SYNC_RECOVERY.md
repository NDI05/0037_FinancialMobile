# Review PM ce56d9c — recovery upload lintas run

Tanggal: **23 November 2026**. Scope: review integrasi auto-sync; bukan audit seluruh proyek.
HEAD lokal saat pemeriksaan: `ce56d9c`. SRS resmi dan runtime tidak diubah.

## Hasil

- Perbaikan due window/server worker, shared manual coordinator, status partial/storage
  dan uploader multi-bulan ada pada source terbaru.
- PM mengulang `../scripts/go.sh test -race -count=1 ./internal/httpapi -run
  'Test(CategoryWindow|AutoCategory)'`: **PASS**, paket httpapi 12,092 detik.
- Android 311 tes, lint/build dan E2E status adalah **TERLAPOR SE**, tidak diulang sebagai
  suite fresh PM. E2E status bukan E2E kedatangan email lewat IMAP nyata.
- **P1 / OPEN:** retry otomatis setelah local commit + upload gagal dapat memajukan
  watermark tanpa pernah mengirim transaksi yang tertinggal ke backend.

## Reproduksi sintetis terisolasi

PM menggunakan fixture mailbox/parser/repository existing dari IncrementalSyncUseCaseTest,
uploader produksi ServerImportUploader dan fake server dengan importBatch pertama gagal.
Tidak ada akses Gmail, Turso atau Azure nyata. Journal Keystore tidak diaktifkan pada probe;
jurnal production diindeks per localJobId sehingga perlu regression lanjutan dengan jurnal
durable untuk membuktikan pemulihan setelah proses restart.

1. Deliver satu email fixture dan jalankan `IncrementalSyncUseCase.run(PERIODIC)`.
2. Local insert berhasil; paksa importBatch gagal sebelum server menerima kandidat.
3. Pastikan run FAILED, satu transaksi lokal tersimpan dan watermark belum maju.
4. Majukan fake clock 31 menit; jalankan trigger PERIODIC kembali tanpa aksi UI.
5. Import membuat job baru; fingerprint lama dianggap duplikat sehingga baris tetap
   mempunyai importJobId run pertama.
6. Uploader produksi membaca `listByImportJob(owner, newJob.id)`: kosong. Fake server
   mengakui finalisasi job EMPTY; sync mengembalikan Completed dan watermark maju.
7. Assertion kandidat yang diterima server = 1 gagal; actual = 0.

Hasil fresh:

```text
PMReviewImportRecoveryTest.pmRecoveryRetainsPendingRun: FAILED
cursor advanced but original pending transaction was not uploaded
expected:<1> but was:<0>
```

Perintah probe: `./scripts/build.sh :app:testDebugUnitTest --tests
com.pam.recap.domain.usecase.PMReviewImportRecoveryTest.pmRecoveryRetainsPendingRun`.
File tes sementara dibuat dari fixture existing, lalu dihapus setelah selesai; tidak ada
source aplikasi yang diubah. XML/report build lokal dapat ditimpa run berikutnya; pesan di
atas adalah rekaman observasi, bukan klaim fixture probe sudah menjadi regression permanen.

## Akar masalah dan referensi

- `domain/usecase/AutoSyncUseCases.kt`: run otomatis baru memanggil importMonth sebelum
  memulihkan upload run terdahulu; uploadPending hanya melewati cooldown, bukan resume.
- `domain/usecase/ServerImportUploader.kt`: kandidat dibatasi pada job.id run sekarang.
- `data/local/TransactionDao.kt`: INSERT OR IGNORE mempertahankan baris/importJobId lama.
- `data/security/ImportUploadJournal.kt`: key mencakup owner+localJobId; job baru tidak
  otomatis membaca journal job lama.

Menjaga cursor lama saja tidak menjamin recovery jika kandidat yang belum diakui server
tidak termasuk snapshot run baru. Jangan memperbaiki dengan kembali upload seluruh bulan
atau mengubah importJobId semua duplikat, karena itu merusak stable batch/body snapshot.

## Acceptance perbaikan

1. Identitas pending run/snapshot dan ack per bulan durable; otomatis resume pekerjaan
   belum diakui sebelum menutup rentang delta baru. Tidak memerlukan tap Impor.
2. Restart setelah local commit, gagal sebelum batch ack, response hilang setelah commit,
   partial multi-bulan dan gagal finalisasi: seluruh kandidat akhirnya ada di server tepat
   sekali, batch key/body tetap, cursor tidak melompati pekerjaan.
3. Fake mailbox + real import/uploader + fake server assertions memeriksa candidate IDs/
   fingerprints/counts server, bukan hanya jumlah local rows, uploads, atau Completed.
4. Jalankan regression dengan journal durable dan worker/coordinator yang sebenarnya;
   pertahankan USER override, owner isolation, budget dan delta tanpa full-month reupload.
5. Setelah P1 ditutup, langsung lanjut backlog S2.4/S2.5, S3 dan S4 sesuai mandat penutupan;
   tidak ada blocker credential untuk mengimplementasikan pagination/CRUD/anggaran/admin.

Empat temuan classifier lama tetap tertutup untuk scope regresinya. S2 masih **PARTIAL**;
penutupan lane auto-sync belum diterima karena P1 ini. Benchmark/perangkat/mailbox nyata
tetap gate terpisah. Tidak ada merge, deploy, penghapusan data atau perubahan credential.
