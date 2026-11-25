# UAT perangkat pemilik — 24 November 2026 (S2)

Bukti diambil langsung pada perangkat pemilik `(serial diredaksi)` dengan APK debug yang terpasang
pukul 11:08 WIB dari build `0030a43`. Rentang `0030a43..092276e` **tidak mengubah kode
Android** (`git diff --name-only 0030a43..HEAD | grep -v '^docs/'` hanya berisi backend Go),
jadi APK saat itu memuat kode Android final. Backend UAT `:18090` adalah binary baru yang
memuat perbaikan eligibility `6cc0f2a`/`3b8a038`; `adb reverse tcp:18090` aktif.

Pukul 11:51 WIB APK dipasang ulang di perangkat yang sama dari HEAD `9473064` (satu perubahan
non-P0: field pencarian admin tidak lagi dinonaktifkan selama pemuatan + unit test). Setelah
instal: aplikasi membuka Ringkasan dengan sesi pemilik tetap aktif, auto-sync berjalan dan
mengirim **satu** `POST /v1/import-jobs` 201 (11:51:32) tanpa tap manual — bukti tambahan
single-flight. Tidak ada perubahan pada alur P0 yang diuji di §1–§4.

Semua angka keuangan, nama merchant asli dan email pemilik **tidak disalin** ke dokumen ini.

## 1. Sheet status pembaruan — buka tanpa request, satu tap satu percobaan

- Sebelum sheet dibuka: log server 706 baris.
- Tap ikon status pada Ringkasan → sheet "Status pembaruan" tampil; setelah 3 detik log server
  **tetap 706 baris** (0 request saat membuka sheet). Isi sheet: periode November 2026,
  "Percobaan terakhir 9 menit lalu", "Tidak ada transaksi baru pada pemeriksaan terakhir",
  kategori "Tidak ada kategori tertunda".
- Satu tap "Sinkronkan sekarang" → label berubah menjadi **"Percobaan terakhir: baru saja"**
  tanpa ada tap kedua. Karena tidak ada email baru, sinkronisasi berhenti di pemindaian lokal
  (0 `POST /v1/import-jobs` baru) dan hanya memuat ulang ringkasan (`GET /v1/summaries` 200).
  Artinya satu tap = satu percobaan; tidak ada job ganda.
- Bukti satu tap = satu `POST /v1/import-jobs` 201 dengan email baru sudah dicatat pada
  23 November ([UAT_MERGED_UI_CATEGORY_2026-11-23.md](UAT_MERGED_UI_CATEGORY_2026-11-23.md) §5).

## 2. Daftar Transaksi — bulan aktif, kategori, saran AI

Dump UI perangkat (teks saja, nominal dihapus):

- Header "Transaksi", bulan **"November 2026"**, strip "Hari dengan transaksi · November 2026"
  dengan chip **Semua / Tempat tinggal / Makanan / Kategorikan dengan AI**.
- Ringkasan daftar "2 transaksi", "Server memeriksa 2 transaksi."
- Baris: `21:07 · Keluar · Transportasi` dan `09:45 · Keluar · Transportasi`, keduanya dengan
  label **"Saran AI"** — kategori hasil klasifikasi Azure tampil di UI perangkat pemilik, bukan
  lagi "Belum berkategori".

## 3. Detail transaksi — field Merchant berisi nama penerima

Detail baris pertama:

- "Sumber: **Email Livin**", "Versi parser: **livin-bill-payment/v1**", sidik jari awalan
  `eadd4f1d80`, "Asal kategori: **Saran Azure AI**".
- Setelah menggulir ke "UBAH DETAIL": label **"Merchant"** dengan nilai berupa **nama usaha
  penerima** (bukan `Merchant PAN`, `Customer PAN`, `No. Referensi`, atau `No. Ref. QRIS`;
  nilainya tidak disalin ke repo). Ini menutup bukti visual bahwa perbaikan parser `Penerima`
  + repair label benar-benar sampai ke layar pemilik.
- Catatan: kategori masih berstatus saran AI yang belum dikonfirmasi; pemilik dapat memilih
  kategori untuk mengonfirmasi lewat chip yang sama.

## 4. Analisis Azure dari UI pemilik

- `POST /v1/analyses` **200** (5,98 s, `req_79edd5e5…`), provider Azure 499 prompt token +
  188/215 completion token.
- `GET /v1/analyses/2026-11` **200**: `status=AVAILABLE`, `freshness=CURRENT`,
  `inputRevision=26` = revisi server.
- UI menampilkan **"ANALISIS TERSIMPAN"**, "Tersedia", tab Observasi/Saran, penanda "1 dari 3",
  dan tombol "Buat ulang analisis". Teks observasi tidak disalin ke repo karena memuat angka
  keuangan pemilik.

## Status

- Sheet sinkronisasi, daftar berkategori, detail Merchant, dan analisis Azure: **PASS runtime
  pada perangkat pemilik** (build final, backend final).
- **Run Android Studio oleh pemilik belum dijalankan**; APK dipasang lewat build/adb. Karena
  itu S2 tetap **PARTIAL** sampai pemilik menekan Run dan mengonfirmasi.
