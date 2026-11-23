# UAT kategori Livin — alasan kategori tidak terbaca di Android — 2026-11-23

Lane: `worker-backend-gaps` · Branch: `work/category-livin` (dari `feat/c2-android` @ `112ddb3`)
Worktree: `pam-wt-cat` · Backend UAT nyata: **127.0.0.1:18090** (binary pra-perbaikan, *tidak*
di-restart selama UAT) + DB Turso nyata `pam-real-uat` · Perangkat: emulator `emulator-5554`,
akun owner role ADMIN, data nyata (tidak ada perubahan data lewat SQL).

Mandat: diagnosa sampai kategori terbaca di Android untuk email transaksi Livin — tanpa
mengarang nama merchant/kategori, tanpa hardcode merchant, tanpa melemahkan sanitasi privasi.

## 1. Ringkasan hasil

| Aspek | Status |
|---|---|
| Diagnosa akar masalah | **PASS** — bukti Level DB + API + kontrak |
| Perbaikan lapisan yang tepat (Go reason + Android tampilan) | **PASS** — unit + API runtime terisolasi |
| Alasan kegagalan tampil di Android (bukan sekadar counter) | **PASS** — mapping alasan + banner daftar/detail + tes |
| Kategori CLASSIFIED terlihat di data owner nyata | **NOT RUN** — data owner tidak punya nama merchant yang bisa diklasifikasi; backend 18090 tidak boleh di-restart |
| Presisi otomatis ≥95% pada data owner | **NOT RUN / UAT terbatas** — tidak ada sampel berlabel kebenaran (ground truth) |
| Tidak ada kategori palsu / nomor referensi terkirim ke Azure | **PASS** — 0 panggilan provider untuk label referensi; angka tetap di server |

Kesimpulan jujur: **pipeline-nya bekerja dan sekarang menjelaskan alasannya**, tetapi pada data
owner nyata tidak ada satu pun baris yang dapat diklasifikasi otomatis, sehingga klaim
"kategori berhasil terbaca untuk transaksi Livin owner" belum bisa dibuktikan dan **tidak
diklaim PASS**.

## 2. Akar masalah (root cause)

| # | Lapis | Gejala | Bukti | Perbaikan |
|---|---|---|---|---|
| R1 | Data/parser (data-limited) | Email Livin jenis tagihan menaruh **nomor referensi/biller** di slot "Merchant"; 47/47 baris owner `merchantLabel` berupa **19 digit angka**, 27 digit-set berbeda. Tidak ada nama merchant manusia di DB (`description` hanya teks template "Pembayaran", `attributes` kosong). | Inspeksi read-only kolom tersegel (hanya bentuk/panjang/jumlah, nilai mentah tidak dicatat); import job COMPLETE 2026-11-22 matched/imported 47, template v1; job import hari ini EMPTY (0 matched) sehingga email sumber tidak bisa diprobe ulang. | Tidak ada perubahan parser karena **tidak dapat dibuktikan** email sumber memuat nama merchant (probe email mentah **NOT RUN**, Gmail link saat ini tidak menghasilkan email). Tidak mengarang nama. |
| R2 | Go — sanitasi merchant | `categoryllm.SanitizeMerchant` menolak label >8 digit (`digits_gt8`), dan handler memetakan **semua** kegagalan sanitasi ke satu kode generik `unsafe_merchant`. | 5 job owner `NEEDS_REVIEW` + `reason_code=unsafe_merchant`; 0 baris cache; 0 percobaan provider owner. | `handlers_category.go`: klasifikasi alasan spesifik — `merchant_missing` (label kosong), `merchant_not_identifiable` (hanya angka/pemisah), `unsafe_merchant` (kasus sanitasi lain). Semua tetap `NEEDS_REVIEW` + `uncategorized`, **0 panggilan provider**. Aturan sanitasi digit **tidak** dilonggarkan. |
| R3 | Android — tampilan | Layar Transaksi hanya menampilkan counter `classified=0 needsReview=5 failed=0`; alasan dari API (`reasonCode`) dibuang. Detail menampilkan nomor referensi di kolom "Merchant" seolah nama merchant. | `TransactionsViewModel` hanya membangun string status; `ServerCategoryJob.reasonCode` sudah ada di kontrak tetapi tidak dipakai UI. | Mapping kode → kalimat Indonesia di banner daftar (`transactions_classify_review_*`) dan catatan netral di detail (`detail_merchant_reference_notice`); nilai mentah tetap bisa diedit, kode teknis tidak pernah ditampilkan. |

## 3. Baseline owner (pra-perbaikan) vs sesudah perbaikan

Baseline diambil lewat aplikasi nyata: satu ketukan "Kategorikan dengan AI" pada 2026-11-23
22:27 WIB.

| Metrik | Baseline (owner, app) | Sesudah perbaikan |
|---|---|---|
| `POST /v1/category-jobs` | 200 (correlation `req_c90c2f9b...`) | Tidak dijalankan ulang di owner (binary 18090 pra-perbaikan tidak boleh di-restart) |
| Banner UI | `Hasil kategorisasi: classified=0 needsReview=5 failed=0` | Di build baru: banner yang sama + **alasan spesifik** per kode (mis. "Perlu tinjauan: email hanya memuat nomor referensi, bukan nama merchant…") |
| Baris transaksi | tetap "Belum berkategori" | tetap "Belum berkategori" (tanpa sampel nama merchant, tidak mungkin CLASSIFIED) |
| `category_jobs` owner | 5 NEEDS_REVIEW `unsafe_merchant` (dibuat 14:16Z) | Kode baru akan menulis `merchant_not_identifiable` untuk label yang sama; **tidak diverifikasi di 18090**, hanya di instance terisolasi |
| `category_cache` owner | 0 baris | 0 baris (perilaku tidak berubah) |
| Percobaan provider owner | 0 | 0 |
| Panggilan Azure | 0 untuk owner (ledger global hanya 2 percobaan milik akun uji lain, $0.0002118) | 0 pada verifikasi terisolasi (stub lokal, bukan Azure) |

**Status baris tabel di atas:** kolom "sesudah perbaikan" untuk owner adalah **NOT RUN**;
bukti runtime perbaikan berasal dari instance backend terisolasi (bagian 4) dan ditandai
sebagai bukti non-owner.

Bukti replay/duplikasi: 5 job owner semuanya `created_at` 14:16:30–14:16:32Z. Ketukan 22:27
hanya me-replay 5 id transaksi yang sama — **tidak ada job baru, tidak ada tagihan ganda**.
Rincian tersanitasi: `assets/category_livin_2026-11-23/owner_uat_baseline.json`.

## 4. Bukti runtime perbaikan (instance terisolasi, non-owner)

Binary worktree pasca-perbaikan dijalankan di **127.0.0.1:18092** dengan DB SQLite terisolasi
dan provider kategori **stub lokal** (kompatibel Azure, tanpa jaringan/Azure/biaya). Akun
sintetis + consent `AI_CATEGORIZATION` lewat endpoint publik, lalu 4 transaksi sintetis
diklasifikasi dalam satu request:

| Kasus (sintetis) | Bentuk label | State job | reasonCode | Kategori | Panggilan provider |
|---|---|---|---|---|---|
| Referensi 19 digit | angka | NEEDS_REVIEW | `merchant_not_identifiable` | `uncategorized` | 0 |
| Angka pendek 5 digit | angka | NEEDS_REVIEW | `merchant_not_identifiable` | `uncategorized` | 0 |
| Label kosong | "" | NEEDS_REVIEW | `merchant_missing` | `uncategorized` | 0 |
| Kontrol nama terbaca | teks | CLASSIFIED | `stub` (respons stub) | `food` | 1 |

Stub hanya menerima **satu** merchant, yaitu kontrol `KOPI UJI` — label referensi/kosong tidak
pernah meninggalkan server. Artinya: perbaikan alasan tidak mengubah guard privasi, dan jalur
klasifikasi normal tetap berfungsi. JSON: `assets/category_livin_2026-11-23/isolated_pipeline_verification.json`,
log stub: `assets/category_livin_2026-11-23/isolated_provider_stub_hits.log`.

Catatan: ini bukti **pipeline & kontrak**, bukan akurasi model dan bukan data owner.

## 5. Perubahan Android (dan mengapa cukup di lapisan tampilan)

- `TransactionsViewModel.kt`: `classificationReviewReasons` di state; `reviewReasonCodes(jobs)`
  mengambil `reasonCode`/`errorCode` unik-terurut untuk job NEEDS_REVIEW/FAILED;
  `isReferenceMerchantLabel()` mendeteksi label angka/pemisah.
- `TransactionsScreen.kt`: banner alasan per kode lewat `classificationReasonRes(code)`; kode
  tak dikenal jatuh ke salinan generik ("Perlu tinjauan…"), jadi penambahan kontrak baru tidak
  pernah menampilkan token teknis atau crash.
- `TransactionDetailViewModel.kt`/`TransactionDetailScreen.kt`: `merchantIsReference` + catatan
  "Nilai ini adalah nomor referensi dari email, bukan nama merchant. Pilih kategori secara
  manual." Nilai mentah tetap tampil dan dapat diedit; tidak ada nama merchant palsu.
- String Indonesia baru di `res/values/strings.xml` (7 kunci).
- Kategori pilihan user tidak disentuh: filter klasifikasi Android tetap mengecualikan
  `categoryOrigin == USER`, dan tes Go `TestUserCategoryNotOverwritten` tetap lulus.

## 6. Guard yang dipertahankan (tidak berubah)

| Guard | Bukti |
|---|---|
| Consent sebelum klasifikasi | Consent 201 dibuat eksplisit di verifikasi; UI tetap memakai alur consent yang ada |
| Sanitasi merchant (digit >8, `@`, `://`, CR/LF, >120 rune) | Tidak diubah; label referensi tetap ditolak, 0 panggilan provider |
| Nomor referensi tidak dikirim ke Azure | Stub hanya menerima nama kontrol; 0 merchant referensi terkirim |
| Cache per-owner | 0 baris cache owner; tidak ada cache lintas owner |
| Dedup / replay job | 5 job lama di-replay, bukan duplikat; tanpa charge ganda |
| Budget & ≤5 merchant/request | Tidak diubah; UI tetap `take(5)`; ceiling tidak disentuh |
| Tidak ada tulis SQL / drain backlog / hardcode merchant | Semua inspeksi DB read-only; tidak ada baris yang disisipi SQL |

## 7. Metrik & kejujuran cakupan

- **Presisi otomatis:** `numerator/denominator = N/A` — tidak ada label kebenaran (ground
  truth) merchant owner dan tidak ada baris yang bisa diklasifikasi. **Tidak diklaim ≥95%.**
- **Cakupan pada data owner:** 0/47 baris memiliki nama merchant yang dapat diklasifikasi
  (47/47 hanya nomor referensi). Artinya *coverage* klasifikasi otomatis owner = **0%** dan itu
  memang perilaku yang benar untuk data ini, bukan bug yang bisa dipaksa.
- **Cakupan penjelasan alasan (sintetis, non-owner):** 3/3 kasus tidak-eligible mendapat alasan
  spesifik yang bisa ditampilkan (100% explanation coverage), ditambah 1 kontrol CLASSIFIED.

## 8. Gate & jumlah tes

| Gate | Hasil |
|---|---|
| `go test -count=1 ./...` (backend-go) | **PASS** — semua paket (internal/httpapi 17.0s) |
| `go vet ./...` | **PASS** |
| `go build ./...` | **PASS** |
| `:app:testDebugUnitTest` | **PASS** — 498 tes, 0 gagal, 0 skip (+5 tes baru) |

Regresi Go baru: `TestReferenceMerchantNeedsReviewWithoutProviderCall` (19 digit, angka pendek,
referensi berpemisah, kosong, label tidak aman → alasan tepat, 0 panggilan provider, kategori
tidak berubah). Regresi Android baru: `ClassificationReviewReasonTest` (dedup/urut alasan,
kode tak dikenal → generik, deteksi label referensi vs nama asli).

Rincian: `assets/category_livin_2026-11-23/test_results.txt`.
Catatan lingkungan: worktree memerlukan symlink gitignored `.robolectric-deps` ke checkout utama
agar Robolectric offline bisa jalan (bukan bagian commit).

## 9. Yang TIDAK dijalankan (NOT RUN) — eksplisit

1. **Probe email mentah Livin** untuk mencari nama merchant yang mungkin dibuang parser: **NOT
   RUN** — tidak ada salinan email mentah di repo/server (kolom tersegel hanya menyimpan label
   hasil parser), dan job import hari ini EMPTY (Gmail link tidak menghasilkan email). Karena itu
   klaim "parser membuang nama merchant" **tidak** dibuat; statusnya data-limited.
2. **Re-import / reklasifikasi data owner**: **NOT RUN** — Gmail link tidak menghasilkan email
   baru; tanpa itu tidak ada data baru untuk diuji.
3. **Verifikasi runtime perbaikan pada backend 18090**: **NOT RUN** — binary UAT nyata pra-fix
   tidak boleh di-restart selama UAT; bukti runtime perbaikan memakai instance terisolasi.
4. **CLASSIFIED terlihat di daftar/detail data owner** dan **presisi otomatis ≥95%**: **NOT RUN**
   — tidak ada sampel nama merchant yang bisa diklasifikasi dan tidak ada label kebenaran.
5. **Ketukan app tambahan** setelah perbaikan: **NOT RUN** — build APK baru tidak dipasang di
   emulator 5554 pada sesi ini; bukti Android berupa unit test + perubahan UI yang terkompilasi.

## 10. Risiko & batasan tersisa

- Selama email Livin jenis tagihan hanya membawa nomor referensi, transaksi tersebut akan tetap
  `NEEDS_REVIEW`; keputusan produk berikutnya (mis. menambah label merchant dari pengirim/nama
  biller pada template baru) memerlukan data email mentah yang belum tersedia.
- Label alasan `merchant_not_identifiable` hanya diverifikasi lewat instance terisolasi + unit
  test; verifikasi pada backend UAT nyata menunggu izin restart/deploy.
- Alasan generik sengaja dipakai untuk kode kontrak baru agar UI tidak pernah menampilkan token
  teknis; bila backend menambah kode alasan, salinan Indonesia perlu ditambahkan.

## 11. Artefak bukti

Semua di `docs/evidence/assets/category_livin_2026-11-23/`:

- `owner_uat_baseline.json` — angka baseline owner (tanpa nilai merchant mentah)
- `isolated_pipeline_verification.json` — hasil E2E instance terisolasi
- `isolated_provider_stub_hits.log` — merchant yang benar-benar dikirim ke provider stub
- `uat_backend_log_excerpt.txt` — baris log request UAT 22:26–22:27 (tanpa data sensitif)
- `test_results.txt` — ringkasan gate Go/Android
