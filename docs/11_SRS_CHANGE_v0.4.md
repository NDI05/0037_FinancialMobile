# SRS Change Record v0.3 ke v0.4

PAM-SRS-001 · CR-002 · diperbarui 21 November 2026.

**Status** — rancangan arsitektur dan kebutuhan. Arahan cloud database gratis dari pemilik telah
diterima. Lead memilih Turso Cloud Free/libSQL berdasarkan integrasi existing. Pemilik menyetujui
**pergantian backend ke Go** pada 21 November 2026; implementasi target ada di `backend-go/`.
Sign-off keseluruhan v0.4 masih terbuka, termasuk lokasi transaksi, offline, retensi dan cutover data
nyata. [Rancangan teknis](10_MIGRASI_PEDOMAN.md)

## 1. Alasan perubahan

Pedoman mengizinkan cloud database menurut klarifikasi pemilik. D.8 tetap melarang Room. Target v0.4 memperluas backend Turso existing agar menyimpan entitas aplikasi, menambah JWT/refresh, hak akses, lima fitur CRUDS dan file lokal. V0.3 beserta CR-001 tetap menjadi baseline implementasi sampai cutover tervalidasi.

## 2. Requirement yang direvisi

| ID / bagian | Target v0.4 |
|---|---|
| FR-02 | Otorisasi berdasarkan pemilik untuk setiap query/read/write, list, search, import, attachment metadata, anggaran, dan deletion. Admin mengelola akun sesuai haknya. |
| FR-11–FR-15 | Matriks template mencakup format Livin yang dibuktikan lewat MIME/HTML. Pembayaran tagihan didukung hanya pada registry dan aturan validasi yang teruji. |
| FR-35 | Tetap berlaku untuk pengiriman ke penyedia AI. Hanya proyeksi agregat yang diizinkan dapat diteruskan, setelah consent. Merchant, catatan, label kategori bebas dan atribut dinamis dikecualikan. |
| FR-52 | Mode akademik v0.4 menggunakan sesi backend dan database cloud. Pemuatan setelah restart serta CRUD membutuhkan koneksi backend/database. Mode fixture diberi label untuk pengujian. |
| FR-56 | Turso Cloud/libSQL menyimpan transaksi, kategori, rencana/batas anggaran, metadata lampiran, import jobs dan data backend existing. Token database dan kunci enkripsi tetap di backend. |
| DR-02/DR-05 | Hasil parsing yang tervalidasi disimpan server-side dengan owner, fingerprint dan revisi. Email mentah, kredensial mailbox, ID pesan asli dan lampiran email tidak dipersistenkan di backend. |
| DR-04 | Revisi ringkasan, analisis, dan rencana mengacu pada dataset transaksi server yang konsisten. |
| NFR-06/07 | Uji performa daftar/ringkasan memakai data cloud dan dataset yang ditentukan. Target angka serta batas jaringan dikunci sebelum performance test. Calculator deterministik tetap diuji tersendiri. |
| NFR-15/16 | Retensi mencakup transaksi cloud, atribut, attachment metadata, dan backup. Pertahankan AES-GCM payload existing dan perluas perlindungan field sensitif sesuai kontrak pencarian. Vault IMAP, refresh token, serta file privat mendapat perlindungan perangkat. |
| NFR-19 | Allowlist payload AI berlaku di backend sebelum pemanggilan model, termasuk terhadap data kategori buatan pengguna. |
| NFR-21 | Impor idempoten dan commit atomik server, tidak kehilangan override saat retry, dengan fingerprint stabil per akun. |
| NFR-22 | Target availability backend tetap dievaluasi. Saat Gmail/AI gagal, data keuangan tetap terbaca jika backend/database dapat diakses. Gangguan backend/database menampilkan unavailable. |
| NFR-23 | Backup terenkripsi harian dan restore drill cloud, beserta prosedur pengembalian kunci dan pencabutan sesi. PITR Free 24 jam membantu pemulihan. |
| §1.4, §2, §4.3, Appendix B/C | Perbarui scope, aliran data, kontrak REST, diagram, notice privasi, isu dan acceptance sesuai batas data baru. |

FR-35 dan NFR-22 dipertahankan dengan cakupan yang dijelaskan di atas. Rancangan sebelumnya mencampur penyimpanan backend PAM dengan payload penyedia model dan menghapus sebagian target availability secara tidak sengaja. Penyebutan FR-56 sebagai definisi payload AI diganti rujukan FR-35/NFR-19 dan skema payload.

## 3. Requirement baru yang diajukan

| ID | Requirement |
|---|---|
| FR-57 | Backend menerbitkan JWT access maksimum 15 menit dan opaque refresh token, melakukan rotasi atomik, memeriksa replay, serta mencabut keluarga sesi sesuai kontrak. |
| FR-58 | Peran USER dan ADMIN ditegakkan di UI dan server. Register biasa menghasilkan USER. Provisioning admin terkontrol dan admin mengelola akun melalui akses terbatas. |
| FR-59 | Pengguna dapat memilih, menyimpan, membaca ulang, mengganti nama/atribut, mencari, dan menghapus file privat yang terkait transaksi. Server menyimpan metadata dan referensi lokal abstrak. |
| FR-60 | Transaksi disimpan pada Turso server-side melalui REST CRUDS, terikat owner. Setiap perubahan memelihara revisi dependennya. Data mentah transaksi tidak diteruskan ke model, mengikuti FR-35/NFR-19. |
| FR-61 | Minimal empat entitas terkait ditampilkan dan tersimpan. Kategori, transaksi, rencana anggaran dan metadata lampiran masing-masing menyediakan field dinamis yang tervalidasi dan dapat diisi serta dibaca ulang melalui UI. |
| FR-62 | Klien mengoordinasikan refresh serentak dan melakukan retry paling banyak sekali per request. Refresh gagal membawa pengguna ke pemulihan sesi sambil mempertahankan isian yang belum dikirim jika memungkinkan. |
| FR-63 | Aplikasi menyediakan lima fitur CRUDS lengkap melalui Compose untuk transaksi, kategori, anggaran, lampiran, dan pengguna admin. Search, create/invite admin dan delete wajib bisa didemokan. |

## 4. Acceptance dan status

T01 menguji register/login/JWT/refresh/logout, T02 owner dan peran, T04 impor retry/idempotensi server, T05 total/revisi, T07 CRUDS anggaran, T08 privasi/retensi/restore, T09 alur Compose dan gangguan jaringan. T11 menguji 25 aksi CRUDS, empat entitas dengan relasi dan field dinamis, serta file dibaca kembali setelah restart. T10 memeriksa kesesuaian SRS konsolidasi, APK, backend dan engine cloud aktual.

| Keputusan | Status |
|---|---|
| Cloud database gratis, MySQL sebagai minimum | Ditegaskan pemilik pada 21 November 2026 |
| Turso Free/libSQL | Pilihan desain lead untuk melanjutkan backend existing |
| Transaksi server-side, offline, dua peran, lampiran, CRUDS | Rancangan v0.4 yang harus tercermin pada sign-off sebelum aktivasi/cutover |
| Reset/migrasi data nyata, deployment, biaya berbayar | Belum ada otorisasi dari arahan rancangan ini |
| Tahap A | Worker menyelesaikan pada fe3a183. Gmail nyata masih perlu pengujian |

Dokumen SRS lengkap v0.2 belum dikonsolidasikan menjadi v0.4. Paket B menyiapkan versi review
beserta perubahan requirement dan acceptance; lihat §5. Tanggal/sign-off historis dipertahankan
sesuai fakta.

## 5. Konsolidasi paket B (review draft)

| Artefak | Isi | Status |
|---|---|---|
| [`12_SRS_v0.4_REVIEW.md`](12_SRS_v0.4_REVIEW.md) | SRS review v0.4: scope, data location, role, FR-57–63, ERD, privasi, cutover, riwayat | draft review |
| [`06_BACKEND_CONTRACT.md`](06_BACKEND_CONTRACT.md) | kontrak HTTP v0.4: JWT/refresh, transaksi/kategori, impor batch, ringkasan, anggaran, lampiran, admin, error, atomisitas | draft review |
| [`10_MIGRASI_PEDOMAN.md`](10_MIGRASI_PEDOMAN.md) §8–9 | DDL kandidat `version: 3`, acceptance lokal, cloud NOT RUN, cutover/rollback | migrasi lokal PASS |
| [`01_SCOPE_TRACEABILITY.md`](01_SCOPE_TRACEABILITY.md) | traceability FR/NFR/DR, T01–T11, 25 pemeriksaan CRUDS, rubrik D.1–D.10 | draft review |
| [`03_ARCHITECTURE.md`](03_ARCHITECTURE.md) §0 | target arsitektur remote, perubahan UI, lampiran lokal, state auth | draft review |

### 5.1 Klarifikasi requirement baru

- **FR-57**: access JWT ≤15 menit, refresh 30 hari; refresh dirotasi atomik dan replay mencabut
  keluarga sesi; sesi direferensikan oleh `sid` dan dapat dicabut seketika.
- **FR-58**: register publik selalu `USER`; `ADMIN` hanya provisioning terkontrol/undangan; admin
  aktif terakhir tidak dapat diturunkan/dihapus; perubahan role/status mencabut sesi.
- **FR-59**: server hanya menyimpan metadata lampiran (nama, MIME, ukuran, atribut, referensi lokal
  abstrak, `deviceRef`); file tetap di perangkat dan tidak disediakan untuk diunduh.
- **FR-60**: transaksi server-side; field sensitif (merchant, catatan, deskripsi, atribut) disegel
  AES-GCM; ringkasan dihitung server dari baris aktif dan `month_revisions`.
- **FR-61**: empat entitas dengan editor atribut key–value: transaksi, kategori, anggaran,
  metadata lampiran; batas 10 pasangan/2 KB per entitas.
- **FR-62**: satu refresh single-flight; retry request ≤1; kegagalan refresh → pemulihan sesi,
  bukan retry buta.
- **FR-63**: lima CRUDS (transaksi, kategori, anggaran, lampiran, admin); 25 pemeriksaan demo ada
  di `01_SCOPE_TRACEABILITY.md` §7.

### 5.2 Yang belum berubah

FR-35 dan NFR-19 tetap menjadi batas payload AI (bukan FR-56). FR-56 adalah lokasi penyimpanan
server. NFR-22 tetap menuntut data terbaca saat Gmail/AI gagal dan state unavailable saat
backend/database gagal. BR-01…BR-06 tidak berubah. Email mentah dan kredensial IMAP tetap lokal.

## 6. Riwayat revisi CR-002

| Tanggal | Revisi | Status |
|---|---|---|
| 20 Nov 2026 | Draf awal: MySQL, transaksi server-side, lampiran, role | draf |
| 21 Nov 2026 | Pemilik mengarahkan cloud database gratis; lead memilih Turso Free/libSQL; MySQL menjadi baseline minimum | arah cloud disetujui |
| 21 Nov 2026 | Paket A selesai (`fe3a183`): gate/consent/MIME/import fixture | 223 Android, 111 backend |
| 21 Nov 2026 | Paket B: konsolidasi SRS, kontrak API, DDL `version: 3`, acceptance lokal, ERD/UI/test plan | **review draft** |
| 21 Nov 2026 | Pemilik menyetujui backend **Go**; irisan register/login/consent/summary, JWT/refresh, CRUD inti dan interop Node↔Go | slicer berjalan, C2/D belum |

## 7. Sign-off paket v0.4

| Butir | Pemilik | Status |
|---|---|---|
| Arah cloud database gratis | Owner | disetujui 21 Nov 2026 |
| Turso Free/libSQL sebagai target | Lead | dipilih, menunggu sign-off paket |
| Pergantian backend ke Go | Owner | **disetujui 21 Nov 2026** |
| Transaksi server-side | Owner | **belum sign-off** |
| Offline terbatas + kebutuhan internet | Owner | **belum sign-off** |
| Dua peran USER/ADMIN + provisioning | Owner | **belum sign-off** |
| Lampiran lokal + metadata server | Owner | **belum sign-off** |
| Cutover/hapus data nyata | Owner | **belum diotorisasi** |
| Cloud acceptance (database uji) | Lead/backend | **NOT RUN** — tanpa kredensial |

Paket C1 tidak dimulai pada data pengguna sebelum butir di atas dicatat. Pekerjaan lokal pada
database sintetis boleh berlanjut.
