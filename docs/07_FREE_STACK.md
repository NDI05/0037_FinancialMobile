# Stack gratis PAM

Diperbarui 21 November 2026 untuk rancangan v0.4. [Rancangan dan fase](10_MIGRASI_PEDOMAN.md) · [CR-002](11_SRS_CHANGE_v0.4.md)

| Kebutuhan | Pilihan |
|---|---|
| Cloud database | Turso Free, engine libSQL yang kompatibel dengan `@libsql/client` existing |
| API | Node 24/Express di laptop untuk development dan presentasi |
| Mobile | Kotlin/Jetpack Compose/Navigation Compose/MVVM |
| Mailbox | Gmail IMAP read-only melalui App Password dan vault Keystore existing |
| AI | Ollama lokal melalui backend existing, dengan consent dan validasi proyeksi agregat |
| File pengguna | Penyimpanan privat Android dengan metadata di Turso |

Database langganan ditargetkan Rp0 dengan plan Free. Batas layanan dan pemakaian akun harus diperiksa sebelum demo. Atur tetap pada Free serta hindari mengaktifkan overage berbayar. Rancangan menggunakan indeks, pagination, import batch, dan perhitungan berdasarkan revisi untuk mengendalikan pemakaian. [Harga resmi Turso](https://turso.tech/pricing)

Cloud database menyimpan data bisnis. Proses API masih membutuhkan laptop menyala dan koneksi internet ke Turso. Emulator memakai `http://10.0.2.2:8080` pada debug. HP USB dapat memakai `adb reverse tcp:8080 tcp:8080`. HTTPS diperlukan pada jalur publik dan release sesuai konfigurasi target. Penyedia hosting API 24 jam belum dipilih pada scope rancangan ini.

`DATABASE_URL` dan `DATABASE_AUTH_TOKEN` disetel hanya di lingkungan backend. APK memuat URL API. Gunakan database uji terpisah dari dataset existing, dan cocokkan engine libSQL dengan SDK. Tes libSQL `file:` tetap berguna untuk iterasi lokal, lalu acceptance cloud membuktikan query/migrasi/rollback pada layanan aktual. [SDK TypeScript](https://docs.turso.tech/sdk/ts/reference)

Rilis akademik v0.4 mengganti penyimpanan Room/SQLCipher dengan REST ke Turso. Saat gangguan Gmail atau AI, akses data server tetap berjalan selama API/database tersedia. Saat koneksi database putus, tampilkan unavailable dan pertahankan form yang belum terkirim. Pada v0.3 yang sedang berjalan, profil lokal, fixture, dan database perangkat masih ada sampai cutover.

Backup export terenkripsi harian dan restore drill termasuk rencana. Pemulihan harus mencakup kunci enkripsi dan invalidasi sesi lama. File lampiran hanya tersedia pada perangkat tempat file disimpan, yang perlu dijelaskan pada UI. Target database cloud tidak memberi jaminan backup lampiran perangkat.

## Verifikasi engine dan batas Free

Turso membedakan engine database baru dari **libSQL** yang dipakai `@libsql/client`. Project ini
menargetkan libSQL. Sebelum provisioning:

1. Buat database **uji** (bukan database demo/nyata) dan pastikan URL yang diberikan berawalan
   `libsql://` (bukan URL engine baru yang memerlukan SDK lain).
2. Jalankan `backend` dengan `DATABASE_URL` + `DATABASE_AUTH_TOKEN` dari environment; jangan pernah
   menaruh token di APK, repo, log, atau laporan.
3. Boot dua kali dan periksa `schema_migrations` 1–3; ulangi acceptance
   [`schemaV04.test.ts`](../backend/test/schemaV04.test.ts) pada database uji.
4. Jika dashboard hanya menawarkan engine baru dengan SDK berbeda, **jangan** mengganti dependency
   secara diam-diam. Laporkan dependency konkret dan tunda cloud acceptance; pekerjaan lokal tetap
   memakai `file:`.

Batas Free yang dipakai sebagai anggaran desain (periksa ulang sebelum demo): 100 database, total
penyimpanan 5 GB, 500 juta baris dibaca/bulan, 10 juta baris ditulis/bulan. Jangan mengaktifkan
overage berbayar. Dataset akademik + indeks + pagination + batch impor diharapkan jauh di bawah
batas ini, tetapi angka pemakaian aktual harus dicatat dari dashboard.

| Pemeriksaan | Status |
|---|---|
| Migrasi lokal `file:` + acceptance schema | PASS (lihat `docs/10` §9) |
| Koneksi Turso Cloud uji + migrasi + rollback | **NOT RUN** — belum ada kredensial uji |
| Backup/PITR/restore cloud | **NOT RUN** |
| Engine libSQL cocok dengan `@libsql/client` | **NOT RUN** — harus dibuktikan saat provisioning |

Backend API masih berjalan di laptop selama development/presentasi; hosting publik 24 jam belum
diputuskan dan tidak termasuk paket B.
