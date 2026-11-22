# Prompt SE — Gmail tetap tertaut setelah logout dan kategori QRIS yang benar

Lanjutkan pekerjaan PAM dari HEAD aktual branch `feat/c2-android` di
`/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app`.
Checkpoint worker sebelum review PM `2d279da`; jangan checkout mundur atau menimpa WIP/commit
baru. Baca `AGENTS.md`, `docs/changes/CR_003_GMAIL_SESSION_CATEGORY.md`,
`docs/reviews/PM_REVIEW_2D279DA_GMAIL_CATEGORY.md`, dan mandat/prompt S2–S4 sebelumnya.

Owner baru menguji APK dan meminta dua perbaikan konkret. **Kerjakan keduanya sekarang**, lalu
lanjutkan penutupan impor dan mandat S2–S4. Setiap commit koheren langsung push ke branch yang
sama tanpa meminta izin lagi; verifikasi remote. Tidak ada merge/deploy, force/reset destruktif,
pengiriman email, atau QA destructive pada akun asli. Pertahankan parser strict, auth/consent,
C1/F1–F4/gate A/C2A/R1/Manrope/OFL dan tool QA yang sudah ada.

## 1. Perubahan requirement yang sudah diminta owner: logout bukan melepas tautan Gmail

Kode sekarang memang menghapus App Password dan GmailConnection saat logout, mengikuti FR-54
lama. Owner meminta kebalikannya untuk logout biasa. CR-003 menggantikan kebijakan lama itu;
jangan meminta persetujuan ulang atau menjadikan dokumen lama sebagai blocker implementasi.

- Pisahkan close transport/session dari forget/disconnect mailbox. Logout PAM tetap revoke/
  clear PAM session, cancel active work dan membersihkan state memori; tautan/credential owner
  tetap terenkripsi di perangkat. Login owner yang sama + restart dapat reconnect tanpa input
  App Password lagi selama credential valid dan consent server masih berlaku.
- **Jangan hanya menghapus vault.clear pada SignOutUseCase.** Vault saat ini global satu slot,
  dan RoutingGmailSource tidak memeriksa owner. Ubah interface/implementasi/DI/callers agar
  credential dan koneksi scoped ke authenticated PAM account + backend namespace yang tepat.
  Jangan memakai alamat Gmail sebagai pengganti identity akun PAM. Signed-out/owner B tidak
  boleh mengakses credential, mailbox label, cached Store/Folder/search result milik A.
- Close/reset ImapGmailSource handle/cache/label pada logout/account switch; rebind hanya ke
  owner aktif yang sah. Perbaiki connectionState routing yang masih mengikuti fixture agar UI
  tidak mengklaim connected dari demo atau stale session. Tidak ada silent fixture di akun live.
- Setelah login, restore metadata owner lalu reconcile consent server dan revalidate/reconnect
  credential tersimpan. Tidak ada consent GRANTED buatan, background import sebelum consent,
  atau CONNECTED palsu. Network unavailable tidak menghapus credential; revoked/corrupt secret
  menampilkan reauthorization. Gmail/AI tetap independen, manual/read finance tetap dapat dipakai.
- Explicit Disconnect Gmail, withdrawal Gmail dan account/workspace deletion membersihkan hanya
  credential/koneksi owner terkait. Jangan menghancurkan key global yang masih dipakai owner lain.
  Simpan private Keystore-wrapped, hasil persistence checked dan ownership binding tervalidasi.
- Migrasi single-slot v1 hanya jika ownership dapat dibuktikan; jangan mengikat secret global
  ke akun pertama yang login atau mengasumsikan email PAM=Gmail. Jika tidak terbukti, reauth
  satu kali dengan state jelas; siklus logout/login berikutnya harus mempertahankan tautan.
- Update tests, notice/copy, current SRS/traceability/interface comments. Historical v0.3 tetap
  riwayat, bukan dihapus. Konsolidasikan DOCX/PDF SRS bila workflow dokumen tersedia; jangan
  mengklaim file tersebut sudah diperbarui jika hanya Markdown addendum yang berubah.

Acceptance sintetis: A link→logout→login A→restart tidak meminta link ulang; A→logout→login B
tidak melihat/memakai A; balik A tetap memakai milik A; B dapat mempunyai credential sendiri;
signed-out tidak melakukan mailbox I/O; disconnect/delete B tidak menghapus A; revoked/invalid/
offline/legacy ambiguous state benar. Uji source lifecycle dan persistence, bukan UI label saja.

## 2. Kategori QRIS: baca merchant, jangan menganggap QRIS sebagai kategori

PM sudah memeriksa sampel QRIS yang diminta owner. Field merchant tersedia dan mengindikasikan
makanan/minuman; tidak ditemukan kategori/MCC eksplisit pada sampel. Kategori food adalah
inferensi, bukan field kategori bank. Raw email/merchant asli/nominal/ID tidak boleh disalin ke
repo. Gunakan fixture sintetis `BAKSO UJI`, `MIE AYAM UJI`, `COFFEE UJI`.

- Periksa jalur parser→MerchantCategoryRules→transaction→server categoryId→GET→UI/summary.
  Rules sekarang tidak memiliki bakso/mie ayam/coffee, sehingga menghasilkan UNCATEGORIZED.
  Tambah cakupan merchant makanan/minuman secara data-driven, dengan normalization Unicode,
  case/whitespace/punctuation dan bounded word/phrase matching. Bukan satu special-case merchant
  owner atau aturan semua QRIS→FOOD.
- Perbaiki false positives `.contains`: `TOKO KOSMETIK UJI` sekarang menjadi HOUSING karena
  `kos`, dan `CHAIR STORE UJI` menjadi UTILITIES karena `air`. Tambah negative/collision tests,
  prioritas aturan yang dapat dijelaskan dan fallback UNCATEGORIZED/perlu ditinjau untuk ambigu.
  `kopi` valid tetap didukung; `kopiah` tidak boleh otomatis menjadi pembelian kopi.
- Koreksi kategori USER harus menang dan bertahan saat reimport/rule upgrade. Mapping merchant
  terkonfirmasi, jika digunakan, harus owner-scoped dan tidak mengubah data owner lain. Jangan
  menyimpulkan menu/barang yang dibeli dari nama merchant saja.
- Pisahkan parserConfidence dari categoryConfidence. Preserve category origin/rule version/
  reason sesuai kontrak dari classifier sampai server/readback. Uploader sekarang hanya membawa
  parser confidence, Go mengisi origin IMPORTED, dan mapper mengisi categoryConfidence dari
  parserConfidence; selesaikan gap ini dengan perubahan kontrak/schema/tests yang diperlukan.
  Jangan memalsukan RULE/USER atau menampilkan confidence parser sebagai kepastian kategori.
- Category lookup failure harus menjadi error/recovery yang jujur, bukan empty map→null category.
  Pastikan ID/code/label/kind dari server utuh termasuk custom category. Jangan mengubah category
  yang tidak dikenali menjadi Other Expense tanpa menyimpan identitas/provenance aslinya.
- **Perbaiki transaksi lama juga:** dedupe Go DO NOTHING tidak mereklasifikasi row yang sudah
  UNCATEGORIZED. Sediakan jalur category correction/reclassification via API owner-scoped,
  expected revision/409, untuk row non-USER yang layak. Uji dengan data sintetis, bukan bulk SQL
  atau mutasi data asli dari agent. Preserve amount/date/source/fingerprint/row count; USER
  override tidak ditimpa; rerun idempoten. Invalidate list/detail/summary/analysis setelah ack.
- Baseline classifier tetap di perangkat. Tidak menambah API model yang mengirim merchant,
  notes/body/raw email ke pihak luar: kontrak AI aggregate-only dan consent terpisah tetap berlaku.

Acceptance: fixture MIME merchant makanan→parser→classifier FOOD→Go category ID→GET list/detail/
summary terlihat sebagai Makanan pada instance aplikasi yang sama. Uji unknown/ambiguous merchant,
substring negatif, USER override, reimport tanpa duplikat, reclassification row lama, dan 409.
Totals gross sama sebelum/sesudah perubahan kategori; category/classified totals mengikuti SRS.

## 3. Integrasi dan checkpoint yang tetap wajib

- Parser strict pada `77993c9` dipertahankan. BI Fast tetap unsupported sampai body/status/arah
  terverifikasi. Jangan melemahkan checks untuk membuat semua 82 email tampak berhasil.
- Trace lost-response C2A `docs/evidence/c2a_android_lost_response_gate2.jsonl` sudah menunjukkan
  201/drop lalu 200/same key+body+result, sesuai laporan. Pertahankan gate itu dan evidence.
  Jangan mengulang dari nol atau kembali menambah hook produksi. Refresh historis 2,53 detik
  belum menggantikan bukti fresh mutation/list+summary jika bukti tambahan diminta review.
- Satu run API import manual yang baru tampil setelah restart bukan Android fixture→uploader→
  Go→UI acceptance. Setelah dua perbaikan owner, tuntaskan satu run fixture lokal yang memakai
  uploader Android sebenarnya, counts/revision/coverage server, response-loss/failure+retry+
  restart/resume dan refresh tanpa restart. Gmail asli bukan prasyarat fixture lokal.
- Journal `a217e02` hanya awal: payload/key/cursor immutable dan owner-scoped serta recovery
  create-job response loss, batch/finalize, all-rejected/empty/duplicates-only dan candidate job
  scope tetap harus dibuktikan sesuai prompt `RESUME_4C619F7_IMPORT_ACCEPTANCE.md`.
- Lanjut pagination/filter >100, fingerprint key server, update/delete/conflict, budget/analysis/
  deletion UI; S3 CRUDS+dynamic fields; S4 lampiran/admin/25 aksi/Room removal; R1 accessibility.
  Dua bug ini prioritas baru, bukan pembatalan penugasan S2–S4 atau izin berhenti setelah satu patch.

## 4. Verifikasi dan laporan

PM sudah menjalankan 9 tests targeted PASS dan enam probe classifier sintetis. Itu baseline
review, bukan acceptance fitur baru. Jalankan regression logout/vault/routing/account-switch,
MIME/classifier/roundtrip/reclassification dan import E2E; lalu Android unit/assemble/lint serta
Go tests/race/vet/build fresh bila backend berubah, migration/interop bila terdampak. Tidak
menyebut 251/42 historis sebagai run baru atau menonaktifkan checks untuk mengejar PASS.

Update living checkpoint, CR-003, SRS/traceability/contract yang terdampak. Laporan memuat
commit/push/ref remote, source changes, actual test/evidence, dua acceptance owner, status S2–S4,
limitations dan external NOT RUN. Pisahkan data sintetis dari UAT nyata; tidak meminta credential
ke chat/log atau mengoperasikan akun asli untuk destructive QA.

**Mulai implementasi sekarang. Selesaikan persistensi Gmail yang owner-safe dan kategori yang
round-trip ke Go/UI, kemudian teruskan seluruh pekerjaan independen S2–S4. Jangan menutup turn
hanya setelah membaca prompt, menambahkan kata bakso, atau menulis checkpoint dokumentasi.**
