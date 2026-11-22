# Review PM — 2d279da, persistensi Gmail dan kategori QRIS

22 November 2026. HEAD lokal/remote awal `2d279dac9eb94122c6044795f82e2c3e1d3f0b03`, branch
`feat/c2-android`, worktree bersih. Pemeriksaan source, mailbox read-only dan probes sintetis;
tidak ada patch aplikasi/backend atau mutasi data asli pada review ini.

## 1. Logout: perilaku kode lama, kebutuhan pemilik berubah

`SignOutUseCase` di `domain/usecase/AccountUseCases.kt` memanggil `appPasswordVault.clear()`
dan `gmailConnection.deleteAll(ownerId)`. Interface vault dan SRS v0.3 memang mengharuskan
clear-on-signout. Itu menjelaskan mengapa koneksi hilang setelah logout.

Pemilik sekarang meminta tautan bertahan. CR-003 mencatat perubahan FR-05/06/54 sebagai arahan
langsung pemilik, bukan pertanyaan persetujuan baru. Jangan menghapus pemanggilan clear saja:
`AppPasswordVaultImpl` saat ini satu slot global `wrapped_app_password_v1`, dan
`RoutingGmailSource.active` memilih IMAP hanya dari `vault.hasCredential()` tanpa owner.
Preservasi credential global tanpa owner binding akan memungkinkan akun lain memakai mailbox
yang sama. Harus diubah bersama scoped vault, routing/session guard, restore metadata dan tests.

## 2. Mailbox: merchant tersedia; kategori/MCC eksplisit tidak ditemukan pada sampel

PM mencari sender Livin yang sudah diotorisasi dan membaca dua email QRIS terbaru. Sampel
menyebut merchant yang mengindikasikan makanan/minuman, tetapi tidak mempunyai field kategori/
MCC eksplisit. Detail pribadi, merchant asli, nominal, body dan message ID tidak masuk repo.

Kategori Makanan merupakan inferensi yang masuk akal dari nama usaha, bukan bank-supplied
category atau bukti barang spesifik yang dibeli. QRIS adalah petunjuk metode pembayaran pada
sampel, sehingga semua transaksi QRIS tidak boleh otomatis dimasukkan ke Makanan.

## 3. Classifier: keyword kurang lengkap dan substring terlalu luas

Probe reflection pada `MerchantCategoryRules.classify` compiled JVM, seluruh input sintetis:

| Merchant sintetis | Kategori aktual | Confidence aktual | Temuan |
|---|---|---|---|
| BAKSO UJI | UNCATEGORIZED | 0.35 | tidak ada aturan bakso |
| MIE AYAM UJI | UNCATEGORIZED | 0.35 | tidak ada frasa mie ayam |
| COFFEE UJI | UNCATEGORIZED | 0.35 | alias coffee belum tercakup |
| KOPI UJI | FOOD | 0.9 | kata kopi sudah didukung |
| TOKO KOSMETIK UJI | HOUSING | 0.9 | substring kos salah cocok |
| CHAIR STORE UJI | UTILITIES | 0.9 | substring air salah cocok |

Rule memakai `haystack.contains(keyword)` dan first match menang. Menambah beberapa keyword
saja tidak menutup false positives ini. Gunakan normalisasi dan bounded word/phrase matching,
prioritas yang dapat dijelaskan, unknown state dan koreksi user. Evidence:
`docs/evidence/pm_2d279da_category_probe.json`.

## 4. Round-trip dan transaksi lama harus ikut ditangani

- Uploader memetakan local enum code ke server category ID; kegagalan categories GET masih
  berubah menjadi empty list, lalu categoryId=null. Jangan menyamarkan kegagalan lookup.
- Import Go menyimpan category origin IMPORTED; port kandidat belum membawa confidence/origin
  kategori yang terpisah. Read mapper saat ini mengisi categoryConfidence dari parserConfidence.
  Provenance classifier perlu dipisahkan dari keberhasilan parser, dengan kontrak/migrasi yang sah.
- `ON CONFLICT(owner_id,fingerprint) DO NOTHING` mempertahankan row lama. Reimport setelah rule
  diperbaiki tidak dengan sendirinya mengubah UNCATEGORIZED yang sudah tersimpan. Gunakan
  jalur category correction/reclassification dengan revision dan USER-override protection.
- Setelah category update, list/detail dan category totals/analysis harus invalidate. Gross cash
  flow tidak berubah; distribusi category/classified totals dapat berubah sesuai semantik SRS.

## 5. Validasi dan status checkpoint

Targeted run PM:

```bash
./scripts/build.sh :app:testDebugUnitTest \
  --tests '*LivinMimeImportTest' --tests '*SignOutUseCaseTest' \
  --tests '*ServerImportUploaderTest'
```

**9 tests PASS** (7 MIME, 1 signout, 1 uploader), XML nol failures/errors/skipped. Signout test
lama hanya membuktikan session revoke/clear, bukan requirement baru persistensi. Probe kategori
di atas adalah observasi perilaku aktual, bukan PASS acceptance klasifikasi yang baru.

Trace `c2a_android_lost_response_gate2.jsonl` sudah diperiksa: 201 committed/drop-before-headers
lalu 200 forwarded dengan hash key/body/result sama, sesuai laporan worker. DB/UI assertions
tetap bukti yang dilaporkan worker; PM tidak mengulang emulator gate. Refresh 2,53 detik masih
observasi historis yang belum ditambah fresh-mutation evidence pada review ini. Tidak mengulang
full suite 251, Go 42, lint/assemble atau cloud/provider/device acceptance.

Import subset API→DB→UI yang dilaporkan memakai restart dan bukan satu run Android fixture→
uploader→Go→UI, sehingga tidak menutup gate tersebut. Journal a217e02 adalah kemajuan, tetapi
snapshot payload/key/cursor, counts job-native dan failure+restart masih perlu dibuktikan.
S2 tetap PARTIAL, S3/S4 belum selesai. Dua temuan owner sekarang menjadi prioritas implementasi
bersama penutupan integrasi, bukan alasan mengulang fondasi atau menghapus backlog S2–S4.
