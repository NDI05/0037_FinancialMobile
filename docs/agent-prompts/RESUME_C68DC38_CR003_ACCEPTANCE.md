# Review PM dan prompt SE — tutup acceptance CR-003 setelah c68dc38

22 November 2026. Snapshot review: branch `feat/c2-android`, lokal/remote
`c68dc386844be36f0069ed54d931a2d2193ee66a`, worktree bersih sebelum dokumen PM.
Commit review berikutnya juga dipertahankan. Baca HEAD/status aktual, `AGENTS.md`, CR-003,
`RESUME_2D279DA_GMAIL_CATEGORY.md` dan mandat S2–S4. Jangan checkout mundur/reset WIP.

## Keputusan PM dan bukti

Classifier word/phrase dan persistensi credential per owner adalah kemajuan nyata.
CR-003 tetap **PARTIAL**, bukan acceptance selesai. Metadata/credential tersimpan belum
membuktikan login ulang bisa memakai Gmail tanpa mengisi App Password, dan classifier FOOD
belum membuktikan kategori tersimpan/readback dari Go atau memperbaiki transaksi lama.

PM menjalankan ulang:

```bash
./scripts/build.sh :app:testDebugUnitTest \
  --tests '*AppPasswordVaultTest' --tests '*RoutingGmailSourceTest' \
  --tests '*SignOutUseCaseTest' --tests '*MerchantCategoryRulesTest'
```

**13 tests PASS**: vault 5, routing 2, signout 2, classifier 4. XML nol failures/errors/skipped.
Ini run targeted PM, bukan pengulangan full suite 257/lint/assemble atau emulator acceptance.
Tidak ada patch produksi, akses secret, atau mutasi akun asli dalam review ini.

## Temuan kode yang wajib ditutup

### P1 — klaim Connect tanpa App Password belum didukung jalur UI

`GmailConnectViewModel.connect()` masih menolak `appPassword.isEmpty()` melalui
IMAP_CREDENTIALS_REQUIRED. `GmailConnectScreen` masih menampilkan form credential untuk
non-CONNECTED. Belum ada jalur reconnect tersimpan setelah login di LocalAccountSource.
`connectDemoMailbox()` bukan pengganti jalur restore akun live.

Koreksi laporan: credential dapat bertahan di vault, tetapi pernyataan bahwa tombol Connect
live sudah memakai credential tersimpan tanpa App Password belum sesuai kode. Jangan meminta
owner mengisi ulang sebagai solusi permanen atau menganggap satu reconnect legacy menutup ini.

### P1 — hasil validasi A dapat ditulis ke vault B saat sesi berubah

Di `GmailConnectViewModel.kt:115`, owner A ditangkap sebelum operasi. Setelah await
`validator.validate`, `:137` justru menyimpan credential ke `sessions.current().ownerId`,
lalu memanggil `connectGmail(ownerId)` dengan owner A yang lama. Jika sesi menjadi B selama
validasi, credential yang dimasukkan A dapat disimpan di slot B. Jika sesi sudah null,
`requireNotNull` dapat melempar. Cleanup `:142` juga memilih owner sesi saat itu, sehingga
dapat menghapus credential akun yang berbeda.

Ini failure path dari source, bukan insiden kebocoran yang diamati pada akun asli. Tulis test
deterministik deferred validation: start A→switch B/logout→complete validation. Tidak boleh
ada save/clear/connection/profile/label/error publication pada owner yang salah.

### P1 — owner-scoped vault belum mengikat seluruh transport dan operasi import

`ImapGmailSource.withFolder` membaca sesi/credential sebelum mutex, tetapi tidak memeriksa ulang
identitas/generation setelah antre dan sebelum menerbitkan hasil. `ensureFolder` mengembalikan
openFolder yang sudah terbuka tanpa owner check; `currentStore` memakai handle connected lama
tanpa memverifikasi owner/mailbox. `RoutingGmailSource.activeOwnerId` dapat menjadi owner B
sementara `imap.mailboxLabel()` masih berasal dari handle A. `connectionState()` tetap fixture.

Logout biasa menutup transport pada alur normal, tetapi itu belum membuktikan switch/race,
late response atau pending operation aman. Bind handle/folder/cache/search/cursor/label dan
operation context ke owner+session generation+mailbox credential version yang sesuai. Test
failure path tersebut dengan fake transport dan delayed operations, bukan email asli.

### Gap lain yang tetap terbuka

- Vault v2 baru scoped ownerId, belum namespace backend sebagaimana CR-003. Hasil commit()
  save/clear masih diabaikan; save dapat gagal tanpa failure yang dikembalikan caller.
- Category confidence/origin/rule version/reason belum round-trip; mapper mencampurkan parser
  confidence dengan category confidence. Lookup kategori gagal belum mempunyai recovery jelas.
- Reclassification UNCATEGORIZED lama dan proteksi koreksi USER belum ada. Reimport DO NOTHING
  tidak memperbaiki row lama. Import job-native/counts/recovery serta S2–S4 tetap belum selesai.

## Instruksi implementasi lanjutan — kerjakan sekarang

1. **Tutup race sebelum acceptance.** Capture owner/session generation sejak aksi dimulai;
   jalankan validasi/persistence/reconciliation dengan commit guard yang konsisten. Jangan
   memilih owner baru di tengah operasi. Hasil lama setelah switch/logout harus dibatalkan/
   diabaikan, bukan dipersistenkan atau diterbitkan ke akun baru. Pakai pendekatan generation/
   commit-lock yang sudah ada bila sesuai, bukan membangun sistem auth baru.
2. **Hubungkan restore setelah login/restart.** Jika credential owner tersedia dan consent
   Gmail server berlaku, restore/revalidate/reconnect tanpa input App Password baru. Deduplicate
   reconnect, batalkan pada switch/logout. Status checking/unavailable/reauthorization/connected
   harus sesuai hasil nyata; network sementara tidak menghapus secret atau memberi success palsu.
   Tidak ada auto import/background sync baru di luar scope. Explicit Disconnect/withdrawal/
   deletion tetap forget owner terkait dan tidak memicu reconnect lagi sampai user link ulang.
3. **Selesaikan vault dan source lifecycle.** Namespace backend+authenticated owner, wrapped
   ownership binding, checked persistence dan scoped cleanup; close/rebind transport/cache pada
   perubahan identity. Routing state/label berasal dari source aktif owner yang sah, bukan fixture
   atau cachedLabel owner lama. Jangan menerima global v1 secara otomatis; satu reauth legacy
   tetap boleh bila ownership tidak dapat dibuktikan, tanpa menghapus data akun asli dari agent.
4. **Tuntaskan kategori ke Go.** Preserve category ID/code/label/kind, RULE/USER origin, rule
   version/reason dan category confidence yang terpisah dari parser confidence. Sesuaikan
   DTO/contract/schema secara minimal dan aman, tests/migrasi jika perlu. Lookup failure harus
   error/retry yang jujur; custom category tidak menjadi enum fallback palsu. QRIS saja bukan FOOD.
5. **Tangani transaksi lama lewat API.** Reclassify row UNCATEGORIZED/non-USER yang layak dengan
   expected revision/409 dan idempotensi. Jangan overwrite USER, mengubah fingerprint/amount/
   event time/source, membuat transaksi baru, atau bulk SQL akun nyata. Invalidate list/detail/
   summary/analysis sesudah acknowledgement; gross cash flow tetap, distribusi kategori berubah
   sesuai semantik SRS. Pertahankan whole-word negative tests kos/air/kopi.

## Acceptance yang wajib dijalankan, bukan hanya ditulis

Gunakan QA app/AVD/accounts/DB sintetis terpisah. Untuk transport IMAP, gunakan fixture/fake
terkontrol pada varian QA atau harness lokal yang menjalankan orchestration aplikasi sebenarnya;
jangan berpura-pura fake validation adalah real Gmail. External UAT provider nyata dicatat terpisah.

- A link→logout→login A→restart: tanpa input credential ulang, tersambung setelah validasi nyata
  pada transport yang diuji. A→logout→login B→balik A: tidak ada label/credential/data tertukar.
- Deferred validate/connect/search/fetch dimulai A lalu switch/logout: tidak ada save/clear/
  mailbox I/O baru atau hasil late yang masuk ke B. Uji queued mutex dan reuse handle/cache.
- B disconnect/delete/withdraw tidak menghapus A. Signed-out tidak memakai mailbox. Offline,
  revoked/corrupt vault, Keystore unavailable dan persistence failure mempunyai state yang benar.
- Fixture MIME merchant FOOD→parser→classifier→uploader Android→Go→DB→GET list/detail/summary
  tampil Makanan tanpa restart. Uji reimport, USER override, reclassification row lama, revision
  conflict dan two-owner guards. Unit FakeServer saja bukan acceptance ini.
- Satu run import uploader sungguhan dengan immutable pending batch/payload, server counts/
  revision/coverage, lost-response/failure→retry→restart/resume, all-rejected/empty/duplicates-only.

Jangan clear-data/uninstall/revoke/delete atau fault-inject pada akun asli owner. Jika ada
dependency eksternal, isolasi dan selesaikan seluruh acceptance sintetis yang tidak bergantung
padanya. Catat fake/local versus real-provider dengan jujur.

## Sesudah CR-003: teruskan mandat S2–S4

Commit irisan koheren lalu **langsung push** branch yang sama; verifikasi remote tanpa meminta
izin ulang. Tidak ada merge/deploy/force/reset destruktif. Pertahankan C1/F1–F4/gate A/strict
parser/C2A/R1/Manrope/OFL serta tool QA yang sudah ada. Jangan membangun ulang fondasi.

Lanjut import job-native/fingerprint key server/pagination >100/update-delete-409/budget-analysis-
deletion UI, S3 CRUDS+dynamic fields, S4 lampiran/admin/25 aksi/Room removal dan aksesibilitas R1
sesuai mandat lengkap. CR-003 adalah milestone internal, bukan alasan menutup penugasan tiga sprint.

Jalankan tests relevan, Android unit/assemble/lint, dan Go fresh tests/race/vet/build bila backend
berubah; migration/interop bila terdampak. Jangan menyebut hasil lama sebagai run baru atau
menonaktifkan check. Update living matrix dan laporan dengan commit/ref remote, defect/evidence,
actual commands/results, acceptance CR-003, status S2–S4 serta external NOT RUN. Jangan mengulang
klaim Connect tanpa password sebelum jalur UI/source nyata dan tests membuktikannya.

**Implementasikan sekarang. Jangan berhenti setelah classifier/vault unit tests atau checkpoint
dokumentasi. Lanjutkan sampai acceptance gabungan terpenuhi atau batas runtime/blocker nyata
telah dicatat dengan seluruh pekerjaan independen yang tersedia dituntaskan.**
