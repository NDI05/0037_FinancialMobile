# Review PM dan mandat resume — tutup CR-003 setelah 0541342

Snapshot review 22 November 2026: branch `feat/c2-android`, lokal/remote
`054134262dc1c0b227bbd977eaee8cd0669d7b4c`, worktree bersih sebelum dokumen PM.
Lanjutkan HEAD aktual, termasuk commit PM sesudah snapshot. Pertahankan WIP yang sah.
Baca `AGENTS.md`, CR-003, `RESUME_C68DC38_CR003_ACCEPTANCE.md` dan mandat S2–S4.

## Keputusan dan validasi PM

Checked vault save dan rejection hasil validasi sukses A→B sudah diperbaiki untuk kasus unit
yang ditambahkan. Reconnect use case sudah ada. **CR-003 tetap PARTIAL**: bukan bukti dua bug
owner selesai pada APK. Category provenance/reclassification dan uploader Go/UI belum selesai.

PM menjalankan ulang:

```bash
./scripts/build.sh :app:testDebugUnitTest \
  --tests '*GmailConnectPurgeTest' --tests '*AppPasswordVaultTest' \
  --tests '*SignOutUseCaseTest' --tests '*MerchantCategoryRulesTest'
```

**15 tests PASS**: GmailConnectPurge 4, vault 5, signout 2, classifier 4; XML nol failures/errors.
PM tidak mengulang full 258, Go/lint/assemble, emulator atau real-provider acceptance. Tidak
mengubah source aplikasi atau akun asli pada review ini. Temuan di bawah berasal dari source,
bukan insiden lintas akun yang telah diamati pada akun asli.

## Gap yang harus ditutup dalam implementasi berikutnya

1. **Race guard belum mencakup semua outcome/generation.** Di GmailConnectViewModel, cabang
   validator Failure masih langsung menulis actions.error tanpa owner guard. Setelah await
   connectGmail, error/success juga diterbitkan tanpa guard baru. OwnerId equality tidak
   membatalkan operasi A-lama setelah A→logout→login A. Test sukses A→B tidak menutup failure,
   A→logout, A→A generation baru, atau switch saat persistence/authorize/readback berlangsung.
   Terapkan session generation/cancellation + commit guard konsisten, termasuk cleanup dan
   error/label publication. Jangan hanya menambah beberapa perbandingan ownerId lagi.
2. **Reconnect belum lifecycle-complete.** Overview init membaca sessions.current sekali;
   runCatching mengabaikan Outcome dan dapat menelan cancellation. Reconnect tidak otomatis
   mengikuti perubahan sesi jika ViewModel/backstack bertahan, tidak menyediakan retry UI
   tersimpan saat offline lalu pulih, dan bukan state machine yang dapat diamati. Gunakan
   session-aware bounded/single-flight orchestration yang sesuai pola existing. Jangan membuat
   background import baru. Expose checking/connected/unavailable/reauth dengan hasil nyata;
   cancellation diteruskan dan stale result tidak mengubah owner baru.
3. **Klaim consent server perlu dikoreksi.** ConnectGmailUseCase saat ini membaca
   consents.latest dari ConsentRepositoryImpl→consentDao lokal. RecordConsentUseCase append
   GRANTED lokal sebelum server acknowledgement, dan failure ack tidak menghapus entry itu.
   Jadi local GRANTED bukan bukti server-confirmed. Bedakan pending/confirmed/withdrawn dan
   reconcile server sebelum auto-reconnect yang memerlukan grant. Uji failed ack, pending local
   grant, withdrawal saat authorize berjalan, serta offline. Jangan menyebut jalur ini sudah
   memeriksa server bila hanya membaca Room.
4. **Transport scope masih terbuka.** ImapGmailSource mengambil sesi/credential sebelum mutex,
   ensureFolder/currentStore dapat memakai handle/folder connected lama tanpa owner binding.
   Bind operation, handle, cache, cursor, label dan state ke owner+session generation+mailbox
   yang benar; recheck setelah antre dan sebelum I/O/publish. Routing connectionState masih
   fixture. Tuntaskan queued/deferred authorize/search/fetch dengan fake transport terkontrol.
5. **UI dan vault:** tambahkan jalur eksplisit reconnect credential tersimpan, sehingga failure
   sementara tidak memaksa password baru; jangan hanya berharap Overview selalu selesai lebih
   dahulu. Namespace backend+owner dan ownership binding mengikuti CR-003. Clear/disconnect/
   withdrawal/deletion tidak boleh menghapus owner lain atau memicu reconnect ulang. Legacy v1
   ambigu tetap satu reauth yang dijelaskan, bukan reuse global otomatis.
6. **Kategori harus dituntaskan sampai Go/UI dan row lama.** Tambahkan categoryConfidence,
   RULE/USER origin, rule version/reason yang asli dan terpisah dari parser confidence dengan
   kontrak/schema/migration/tests yang diperlukan. Category lookup failure adalah error/recovery,
   bukan map kosong→null. Reclassify UNCATEGORIZED non-USER yang layak lewat API owner-scoped,
   expected revision/409, idempotensi, tanpa mengubah amount/event/source/fingerprint/row count
   atau menimpa USER. Refresh category totals/list/detail/analysis setelah ack. Classifier word/
   phrase yang sudah lulus dipertahankan; tidak perlu mengulang perubahan keyword saja.

## Acceptance yang SE wajib jalankan sendiri

Siapkan QA app/AVD, transport fixture/fake, Go dan DB sintetis terpisah. Jangan menjadikan
permintaan owner menginstal/mencoba APK sebagai pengganti acceptance engineering.

- A link→logout→login A→restart tanpa input credential ulang; A→B→A; explicit disconnect/
  withdrawal/deletion; unavailable→retry tersimpan; credential revoked/corrupt; namespace switch.
- Deferred validation **success dan failure**, persistence dan authorize/search/fetch yang
  ditahan: switch A→B, A→signed-out, A→logout→A. Assert credential/connection/label/error/result
  tidak dipersistenkan atau diterbitkan oleh sesi lama. Sertakan mutex queue dan cache reuse.
- Pending/failed consent server acknowledgement tidak dianggap confirmed. Withdrawal selama
  reconnect mencegah late CONNECTED dan pekerjaan baru. Manual/read finance tetap independen.
- Fixture MIME makanan→classifier→uploader Android→Go→DB→GET list/detail/summary, category food
  terlihat tanpa restart. Reimport tidak duplikat, USER override bertahan, reclassification row
  lama bekerja, 409 tidak menimpa, total gross tetap benar dan distribusi kategori diperbarui.
- Upload failure/lost batch/finalize response→retry→restart/resume memakai immutable pending
  identity/payload, counts/revision/coverage server. Satu API seed manual yang tampil sesudah
  restart bukan bukti pipeline Android tersebut.

Gunakan transport palsu hanya dalam QA/harness yang jelas dan tandai fake/local versus real
Gmail. Kredensial/provider nyata bukan prasyarat regression dan acceptance fixture lokal.
Jangan menyimpan email/secret/financial rows nyata di repo atau melakukan QA destructive pada
akun/perangkat asli pemilik. Jika tool/environment benar-benar menghalangi test, sertakan
command/error dan lanjutkan pekerjaan independen, bukan sekadar menyebut acceptance NOT RUN.

## Build APK dan kelanjutan S2–S4

Untuk update APK dengan package/signature yang sama, gunakan **install update yang mempertahankan
data** (misalnya `adb install -r <apk>` pada perangkat QA yang dituju). Jangan uninstall/pm clear
akun asli untuk membuktikan persistensi logout: itu menghapus data/vault dan bukan kasus logout.
Jika package/signature berbeda, isolasi QA app/AVD; jangan memaksa dengan menghapus instalasi owner.
Restart aplikasi/API berbeda dari uninstall/reinstall. UAT owner dilakukan sesudah bukti sintetis
siap; jangan menutup run dengan instruksi agar owner menjalankan acceptance yang belum SE lakukan.

Setiap irisan koheren: implementasi→tests relevan→perbaiki failure→commit→**langsung push** ke
branch yang sama, verifikasi remote, lalu lanjut. Jangan meminta izin push ulang. Tidak ada
force/reset destruktif/merge/deploy. Pertahankan C1/F1–F4/gate A/strict parser/C2A/R1/font/OFL.

Sesudah CR-003, teruskan mandat S2–S4: import job-native/counts/fingerprint key server,
pagination >100, update/delete/conflict, budget/analysis/deletion UI, S3 CRUDS/dynamic fields,
S4 lampiran/admin/25 aksi/Room removal dan aksesibilitas R1. Jangan berhenti pada satu test baru
atau checkpoint. Batas runtime nyata dicatat sebagai unfinished, bukan hasil tiga sprint selesai.

Jalankan Android unit/assemble/lint; Go fresh tests/race/vet/build bila backend berubah serta
migration/interop bila terdampak. Update matriks dan laporkan actual commands/results, evidence
satu run lifecycle dan category E2E, commit/ref remote, sisa scope dan external NOT RUN. Hasil
historis bukan run baru. Jangan menonaktifkan assertions/checks atau memalsukan PASS.
