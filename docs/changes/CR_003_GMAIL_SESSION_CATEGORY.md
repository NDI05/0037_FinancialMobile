# CR-003 — tautan Gmail bertahan saat logout dan kategorisasi merchant

Tanggal keputusan: 22 November 2026. Status: **implementasi owner-scoped dan classifier sudah ada; acceptance emulator/round-trip
Go belum selesai**. Tidak menyatakan sign-off seluruh paket SRS v0.4.

Pemilik melaporkan dua masalah pada APK: login ulang PAM meminta penautan Gmail lagi, dan
pengeluaran Livin belum mempunyai kategori yang sesuai. Pemilik menyatakan logout seharusnya
tidak mengharuskan penautan ulang, serta meminta PM membaca sampel QRIS untuk analisis kategori.
Arahan ini mengubah kebijakan credential lifecycle lama FR-54. Tidak perlu meminta persetujuan
ulang untuk perilaku yang sudah diminta ini.

## FR-05/FR-06/FR-54 — lifecycle koneksi yang baru

1. Logout PAM mencabut/menghapus sesi PAM, menghentikan pekerjaan aktif dan menutup transport
   IMAP serta state memori. Tautan Gmail dan credential terenkripsi milik akun PAM tersebut
   tetap tersimpan privat di perangkat. Login kembali ke akun PAM yang sama tidak meminta
   App Password lagi bila credential masih valid dan consent Gmail server masih berlaku.
2. Vault dan metadata koneksi wajib scoped ke identitas akun PAM yang terautentikasi dan
   namespace backend yang relevan, bukan satu slot credential global atau alamat Gmail semata.
   Akun lain dan keadaan signed-out tidak boleh membaca/memakai credential atau mailbox label.
3. Login bukan bukti IMAP terhubung. Rekonsiliasi consent server dan credential milik owner,
   lalu revalidasi/reconnect transport. Network failure sementara menjadi unavailable/retry,
   bukan penghapusan credential atau CONNECTED palsu. Credential revoked/corrupt/Keystore hilang
   boleh memerlukan reauthorization. Jangan menjanjikan persistensi melewati uninstall/clear-data
   atau perangkat lain; App Password tetap tidak disinkronkan ke server.
4. Disconnect Gmail eksplisit, withdrawal Gmail dan penghapusan akun/workspace membersihkan
   credential/koneksi owner yang relevan sesuai kontrak. Tidak menghapus milik owner lain.
   Wrapped key bersama tidak boleh dihancurkan jika masih dibutuhkan data owner lain.
5. Migrasi vault global v1 hanya jika kepemilikan dapat dibuktikan. Jangan menganggap Gmail
   address pasti sama dengan akun PAM atau otomatis mengikat credential lama ke akun pertama
   yang login. Jika ownership tidak dapat dibuktikan, minta reauthorization satu kali dengan
   state yang jujur; pola logout/login selanjutnya harus mengikuti requirement baru.
6. Update interface, use cases, routing/source state, tests dan copy yang menganggap logout
   selalu menghapus App Password. Dokumen v0.3 tetap historical; aturan baru menggantikan bagian
   FR-54/notice lama tentang penghapusan credential pada logout biasa.

## FR-23/24/25 — klasifikasi yang dapat dijelaskan dan dikoreksi

PM membaca dua sampel QRIS terbaru melalui mailbox yang diotorisasi. Sampel memuat merchant,
tetapi tidak mempunyai field kategori/MCC eksplisit. Nama merchant mengindikasikan usaha makanan/
minuman; kesimpulan kategori adalah inferensi aplikasi, bukan kategori resmi dari email bank.
Nama/nominal/ID/isi sampel asli tidak dimasukkan ke change request atau fixture repo.

- Kategori ditentukan dari data terstruktur bila benar-benar tersedia, koreksi USER yang berlaku,
  mapping merchant milik owner bila ada, lalu aturan kata/frasa yang terversi. `QRIS` atau subjek
  `Pembayaran Berhasil!` saja tidak menentukan kategori; tidak semua QRIS adalah pembelian makanan.
- Nama sintetis seperti `BAKSO UJI`, `MIE AYAM UJI`, `COFFEE UJI` dapat menghasilkan kandidat
  `food`/Makanan berdasarkan merchant. Normalisasi Unicode/case/whitespace/punctuation harus
  konsisten dan pencocokan mempunyai batas kata/frasa. `kosmetik` bukan `kos`, `chair` bukan `air`.
- Confidence kategori terpisah dari confidence parser; origin/rule version/reason dicatat sesuai
  kontrak. Hasil tidak pasti tetap UNCATEGORIZED atau diberi tanda perlu ditinjau; jangan
  mengarang presisi confidence atau menyatakan bank mengirim kategori bila tidak ada field itu.
- Category ID/code/label/kind/provenance harus utuh dari classifier→Go→readback UI/summary.
  Kegagalan lookup kategori bukan hasil sah categoryId=null. Custom category tetap didukung.
- Aturan baru tidak otomatis memperbaiki row lama karena import dedupe tidak menimpa transaksi.
  Sediakan jalur reclassification owner-scoped untuk row lama UNCATEGORIZED/non-USER yang layak,
  dengan revision/conflict handling, invalidasi summary/analysis dan idempotensi. Kategori USER
  tidak ditimpa. Amount/event time/source/fingerprint dan jumlah transaksi tidak berubah.
- Klasifikasi baseline dilakukan di perangkat; tidak menambah pengiriman merchant/body/notes
  ke model eksternal. Consent AI dan kontrak aggregate-only tetap berlaku.

## Acceptance

Logout/login owner A + restart tidak meminta penautan lagi; owner B tidak melihat/memakai koneksi
A; signed-out tidak mengakses mailbox; disconnect/withdrawal/deletion berlaku pada owner tepat;
expired auth, network failure, corrupted vault dan legacy migration menunjukkan state yang benar.

Fixture MIME merchant makanan menghasilkan category food yang tersimpan di Go dan tampil di
list/detail/summary tanpa restart. Reimport tidak menduplikasi, USER override bertahan, dan
reclassification sintetis mengubah hanya kategori/revisi/dependency yang relevan. Negative
substring/unknown-merchant tests harus ada. UAT akun asli terpisah dari fault injection/QA data.

Arahan implementasi: `docs/agent-prompts/RESUME_2D279DA_GMAIL_CATEGORY.md`.
