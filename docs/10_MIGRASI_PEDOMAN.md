# Rancangan kepatuhan PAM dengan Turso Free

Diperbarui 21 November 2026. Menggantikan rancangan MySQL pada 20 November.

Pemilik telah mengarahkan penggunaan cloud database gratis dan menjelaskan bahwa MySQL adalah baseline minimum dalam pedoman. Pilihan lead adalah **Turso Cloud Free dengan engine libSQL**. Backend saat ini sudah menggunakan `@libsql/client`, migrasi berurutan, dan enkripsi payload. Rancangan ini memakai integrasi tersebut untuk seluruh data bisnis melalui REST. Larangan Room pada pedoman D.8 tetap berlaku.

Dokumen ini adalah rancangan target. Kode pada `fix/onboarding-import-stability` / `fe3a183` masih menjalankan v0.3. Lokasi transaksi di server, penghentian operasi offline penuh, dua peran, dan lima CRUDS berikut merupakan keputusan desain yang diajukan untuk v0.4. Persetujuan cloud gratis telah diterima. Sign-off keseluruhan CR-002 belum tercatat.

## 1. Pilihan layanan dan biaya

| Komponen | Rancangan |
|---|---|
| Database demo | Turso Cloud Free, engine libSQL yang kompatibel dengan driver existing |
| Backend | **Go 1.27.4 + `net/http` + `database/sql`** (`backend-go/`), berjalan di laptop saat pengembangan dan presentasi |
| Android | Kotlin + Compose + Navigation Compose + MVVM/Hilt, mengakses REST backend |
| Model | Ollama lokal melalui adapter yang sudah tersedia, dengan analisis deterministik saat layanan model gagal sesuai kontrak |
| File | Foto/PDF dipilih pengguna dan disalin ke penyimpanan privat Android, metadata disimpan di database |
| Biaya target | Rp0 untuk langganan layanan pada penggunaan dalam kuota Free |

Halaman resmi Turso saat diperiksa menyediakan 100 database, penyimpanan total 5 GB, 500 juta baris dibaca/bulan, dan 10 juta baris ditulis/bulan pada Free. Kapasitas ini merupakan batas layanan, dengan kecukupan untuk dataset akademik sebagai estimasi desain. Periksa plan dan pemakaian akun sebelum demo. [Turso pricing](https://turso.tech/pricing)

Backend hidup selama laptop hidup. Emulator mengakses host melalui `10.0.2.2`, HP USB dapat memakai `adb reverse`, dan akses jarak jauh memakai URL HTTPS backend yang disiapkan saat diperlukan. Kebutuhan backend publik 24 jam memerlukan keputusan hosting tersendiri. Penggunaan cloud database dapat dibuktikan meskipun proses API berjalan di laptop.

## 2. Alur data target

```mermaid
flowchart LR
    Gmail[Gmail IMAP read-only] --> Parser[Parser di Android]
    Parser --> App[Compose / ViewModel / Use case]
    App -->|REST + JWT| API[Backend Go]
    API -->|libSQL + token server| DB[(Turso Cloud Free)]
    App --> Files[File privat per pemilik]
    API --> Projection[Proyeksi agregat yang diizinkan]
    Projection --> LLM[Ollama / adapter model]
```

Token Turso dan kunci penandatangan JWT berada pada backend. Android menyimpan refresh token terbungkus Keystore dan kredensial IMAP pada vault yang sudah ada. Penerbitan JWT/refresh adalah tanggung jawab aplikasi backend. Token database memiliki tujuan berbeda dari sesi pengguna.

Email mentah dibaca sementara di perangkat. Baris hasil parsing dan transaksi manual menjadi data server dalam target v0.4. Proyeksi untuk AI tetap hanya agregat yang telah disetujui. Merchant, catatan pribadi, metadata bebas, dan label kategori buatan pengguna dikecualikan dari payload model. Pemberitahuan privasi harus menyebut penyimpanan transaksi server-side sebelum aktivasi pada data nyata.

## 3. Skema logis dan relasi

Gunakan tabel yang sudah ada dan migrasi baru yang berurutan. Tabel berikut adalah target logis, dengan detail DDL ditulis worker setelah meninjau schema existing.

| Entitas | Field dan relasi inti | Tampilan/field dinamis |
|---|---|---|
| users | owner_id, email, password hash/salt, display_name, role, status, auth_version, revision | Profil dan administrasi |
| categories | id, owner_id, code, label (sealed), kind, color, attributes (sealed), deleted_at | Editor kategori dengan atribut tambahan |
| transactions | id, owner_id, category_id, direction, amount_minor, currency, event_at_ms, month_id, fingerprint, source, sensitive payload (sealed), revision, deleted_at | Daftar/detail/editor transaksi |
| budget_plans | id, owner_id, month_id, expected_income_minor, planned_savings_minor, attributes (sealed), revision | Daftar/detail/editor anggaran; delete hard |
| budget_limits | owner_id, plan_id, category_id, amount_minor | Batas per kategori pada rencana |
| attachments | id, owner_id, transaction_id, device_ref, local_file_id, mime, size_bytes, sensitive payload (sealed), revision, deleted_at | Lampiran transaksi dan pencarian nama |
| refresh_tokens | id, owner_id, session_id, family_id, token_hash, issued_at, expires_at, revoked_at, replaced_by_id | Internal auth |
| auth_sessions | id, owner_id, family_id, created_at, last_seen_at, expires_at, revoked_at/reason | Referensi `sid` JWT |
| account_keys | owner_id, key_version, fingerprint sealed | Kunci HMAC fingerprint |
| import_jobs | id, owner_id, month_id, state, counters_json, started_at/finished_at, error_code | Kemajuan dan hasil impor |
| summaries, analyses, consents, deletion_requests | Perluasan tabel existing dengan referensi dan revisi yang sesuai | Ringkasan, analisis, izin, penghapusan |

Empat entitas utama untuk D.5 adalah kategori, transaksi, rencana anggaran, dan metadata lampiran. Setiap entitas memiliki form dinamis yang dapat ditulis, dibaca ulang, diedit, dan tampil di UI. Atribut yang dapat memuat data pribadi disegel sebagai JSON AES-GCM; JSON non-sensitif (counter impor, audit admin) disimpan sebagai TEXT dengan `CHECK(json_valid(...))`. Batasi ukuran, jumlah, dan tipe atribut dalam kontrak.

Gunakan UUID/string stabil untuk ID. Owner berasal dari sesi terverifikasi. Relasi kategori, transaksi, rencana/batas, dan lampiran harus memeriksa kesamaan owner, termasuk lewat composite foreign key ketika sesuai. Pertahankan `UNIQUE(owner_id, fingerprint)`, indeks pencarian berbasis owner, dan mata uang dalam unit minor integer. Periksa integer aman saat serialisasi TypeScript serta tolak overflow.

Sediakan indeks `(owner_id, month_id)` atau `(owner_id, event_at)` sesuai kebutuhan, pagination, serta revisi deterministik setelah perubahan data. Foreign key dan rollback harus dibuktikan juga pada engine cloud yang digunakan.

Desain pencarian perlu mengakomodasi enkripsi. Pertahankan AES-GCM untuk payload agregat/analisis/rencana. Untuk merchant, catatan, dan atribut sensitif baru, gunakan helper enkripsi existing. Kolom untuk filter terstruktur seperti owner, tanggal, arah, dan kategori dapat diindeks. Pencarian teks sensitif dilakukan backend setelah dekripsi pada rentang bulan yang dibatasi, dengan pagination hasil yang benar. Kontrak harus menjelaskan rentang pencarian. Jangan menerapkan SQL LIKE pada ciphertext.

## 4. Lima fitur CRUDS dan file lokal

| Fitur | Create | Read | Update | Delete | Search |
|---|---|---|---|---|---|
| Transaksi | Manual/impor | Daftar/detail | Kategori, catatan, atribut | Hapus satuan | Deskripsi pada rentang terpilih |
| Kategori | Buat kategori | Daftar/detail | Label, warna, atribut | Hapus kategori pengguna | Nama |
| Anggaran | Buat rencana bulanan | Daftar/detail | Nominal, batas, atribut | Hapus rencana | Bulan/nama |
| Lampiran | Pilih dan salin file | Buka ulang | Nama/atribut | Hapus file dan metadata | Nama |
| Pengguna admin | Buat/invite dari UI admin | Daftar/detail | Nama, peran/status | Soft delete dengan revoke sesi | Email/nama |

Soft delete mengecualikan transaksi, kategori, lampiran dan pengguna dari daftar aktif/total, menyimpan aturan retensi, dan bisa diperlihatkan lewat UI. Rencana anggaran dihapus hard (tidak memengaruhi arus kas) setelah konfirmasi. Penghapusan kategori yang direferensikan memerlukan pemilihan kategori pengganti yang atomik atau penolakan yang jelas. Admin mengelola akun. Akses finance tetap owner-scoped. Lindungi admin aktif terakhir dan larang eskalasi peran dari endpoint profil biasa.

File dibaca lewat pemilih dokumen dan disalin ke direktori privat yang terikat owner. Server menyimpan referensi lokal abstrak dan metadata yang diperlukan. Pada perangkat lain, metadata dapat muncul dengan status file belum tersedia. Uji kegagalan simpan metadata setelah copy file, kegagalan hapus file, restart, sign-out berganti pemilik, dan penghapusan transaksi. Cleanup/retry harus menjaga konsistensi. [Android SAF](https://developer.android.com/guide/topics/providers/document-provider)

## 5. Auth, impor, dan sumber data

JWT access berumur maksimum 15 menit, dengan subject, issuer, audience, role, expiry, dan referensi sesi. Refresh token disimpan hashed di server dan diputar secara atomik. Backend memeriksa status akun/sesi serta akses peran agar logout, penghapusan akun, atau perubahan role berlaku sesuai kontrak. Klien memakai satu refresh terkoordinasi untuk request paralel dan retry paling banyak sekali per request. Replay refresh ditangani pada keluarga sesi yang bersangkutan.

Setiap batch impor divalidasi dan disimpan atomik. Gunakan fingerprint per pemilik yang stabil selama masa hidup akun, sehingga reinstall/perangkat lain tidak menciptakan duplikasi ketika data mulai terpusat. Putuskan lifecycle kunci fingerprint sebelum menjanjikan migrasi/perangkat baru. Setelah transaksi berubah, perbarui revisi dan tandai ringkasan/analisis lama sesuai dependensinya. Tampilan menggunakan hasil deterministik dari dataset server, dengan pemeriksaan angka terhadap calculator yang sudah diuji.

Room/SQLCipher dihapus dari jalur aktif, dependensi dan skema varian penilaian pada akhir cutover. File preferensi kecil/Keystore menyimpan konfigurasi dan sesi. Keadaan tampilan terakhir dapat bertahan di memori selama proses hidup. CRUD dan pemuatan setelah restart memerlukan backend dan internet ke Turso. Gangguan Gmail atau AI tetap memungkinkan akses data server selama backend/database tersedia. Gangguan database menghasilkan state unavailable dengan pesan yang jelas.

## 6. Pengembangan, migrasi, dan pemulihan

- Jalankan tes cepat memakai `file:`/`:memory:` libSQL lokal. Sebelum klaim cloud PASS, jalankan acceptance pada database Turso uji terpisah dan engine libSQL yang kompatibel dengan `@libsql/client`. Produk engine Turso baru memakai SDK berbeda, sehingga verifikasi engine target sebelum provisioning. [TypeScript SDK](https://docs.turso.tech/sdk/ts/reference)
- Pertahankan migrasi existing sebagai riwayat. Tambahkan versi baru dan uji boot dua kali. Jangan memakai destructive migration lama sebagai pola untuk upgrade berikutnya.
- Inventaris database Room/Turso sebelum cutover. Seed sintetis untuk v0.4 berjalan pada database terisolasi. Data lama tetap tersedia pada baseline lama selama fase review. Pemindahan atau penghapusan data nyata mengikuti keputusan pemilik atas lokasi data dan retensi.
- Rollback aplikasi ke v0.3 membuka dataset lama. Transaksi yang baru ditulis ke v0.4 perlu export/reconciliation tersendiri agar rollback mempertahankan data tersebut. Uji migrasi, backup, dan pemulihan sebelum memilih cutover.
- Turso Free menyediakan PITR dalam 24 jam terakhir. Siapkan pula export terenkripsi harian, simpan kunci di luar dump, dan uji restore pada database terpisah. Catat jumlah record dan hasil dekripsi, termasuk invalidasi sesi yang telah dicabut setelah waktu backup. Retensi backup mengikuti kebijakan penghapusan. [PITR](https://docs.turso.tech/features/point-in-time-recovery) · [Dump/restore](https://docs.turso.tech/cli/db/shell)

## 7. Urutan worker berikutnya

| Paket | Hasil yang diminta |
|---|---|
| B — kontrak | SRS v0.4 dan kontrak UI/API/data/privasi yang konsisten, DDL calon migrasi, penerimaan D.1–D.10 |
| C1 — backend | Migrasi libSQL additive, JWT/refresh, peran, CRUDS transaksi/kategori, impor idempoten, ringkasan owner-scoped |
| C2 — Android | Auth dan repository remote untuk irisan register → consent → transaksi → ringkasan pada Compose |
| D — kelengkapan | Anggaran, lampiran, pengguna admin, field dinamis, penghapusan Room/SQLCipher, cutover |
| E — pembuktian | Lima CRUDS lewat UI, dua pengguna dan admin, restart/persistensi cloud, backup/restore, demo dan SRS konsolidasi |

Tahap A memiliki implementasi pada `fe3a183`. Laporan worker tahap A mencatat 223 tes Android dan
111 backend. Setelah paket B + C1 Go: **225 Android**, **124 Node** (termasuk 2 tes interop), dan
**40 Go**. Impor fixture emulator tahap A: 28 cocok / 25 tersimpan / 3 dilewati, impor kedua 25
duplikat. Gmail nyata dan perangkat fisik masih perlu diuji. Bukti cloud v0.4 harus merekam API, database target, dan pembacaan ulang setelah restart aplikasi, dengan kredensial disembunyikan.

## 8. Skema fisik v0.4 (review draft)

Sumber migrasi untuk database baru adalah **Go** (`backend-go/internal/store/migrations/001–007.sql`
dan `003.triggers.sql`), dijalankan oleh `internal/store/db.go`. SQL 001–002 disalin dari riwayat
Node yang sudah diterapkan; 003 adalah v0.4 yang sudah diuji Node; 004 menambah idempotensi HTTP,
snapshot hash analisis dan counter usage; 005 membangun ulang `analyses` agar laporan historis
lintas siklus consent dapat disimpan tanpa unique cache lama. Go memakai splitter yang
mempertahankan blok `CREATE TRIGGER ... BEGIN ... END`. Node tetap sebagai referensi dan tidak boleh
menjalankan migrasi pada database yang sama secara bersamaan.

### 8.1 ERD

```mermaid
erDiagram
  users ||--o{ auth_sessions : owns
  users ||--|| account_keys : holds
  auth_sessions ||--o{ refresh_tokens : rotates
  users ||--o{ categories : owns
  users ||--o{ transactions : owns
  categories ||--o{ transactions : classifies
  transactions ||--o{ attachments : has
  users ||--o{ budget_plans : owns
  budget_plans ||--o{ budget_limits : limits
  categories ||--o{ budget_limits : caps
  users ||--o{ import_jobs : runs
  import_jobs ||--o{ import_batches : batches
  import_jobs ||--o{ import_skipped : skips
  users ||--o{ month_revisions : revisions
  users ||--o{ admin_invitations : creates
  users ||--o{ admin_audit : actor
```

### 8.2 Tabel target

| Tabel | Kunci/indeks penting | Catatan |
|---|---|---|
| `users` (extend) | `role`, `status`, `auth_version`, `revision`, `updated_at` | default `USER`/`ACTIVE`; admin terakhir dijaga trigger |
| `account_keys` | PK `owner_id` | kunci HMAC fingerprint tersegel |
| `auth_sessions` | PK `id`; index `(owner_id, revoked_at)`, `family_id` | `sid` di JWT |
| `refresh_tokens` | UNIQUE `token_hash`; index `(owner_id, family_id)` | rotasi + `replaced_by_id` |
| `categories` | UNIQUE `(owner_id, code)`, `(owner_id, id)`; index aktif | label/atribut tersegel |
| `transactions` | UNIQUE `(owner_id, fingerprint)`, `(owner_id, id)`; index bulan | composite FK ke kategori/refund |
| `month_revisions` | PK `(owner_id, month_id)` | revisi dependen |
| `budget_plans` (extend) | `revision`, nominal integer; UNIQUE `(owner_id, id)` | payload lama tetap untuk v0.3 |
| `budget_limits` | PK `(owner_id, plan_id, category_id)` | FK plan + kategori |
| `attachments` | UNIQUE `(owner_id, transaction_id, local_file_id)` | metadata; file tidak di server |
| `import_jobs` | UNIQUE `(owner_id, id)`; index `(owner_id, month_id)` | counters JSON tervalidasi |
| `import_batches` | UNIQUE `(owner_id, job_id, batch_key)` | replay batch |
| `import_skipped` | FK job | hanya reason code |
| `admin_invitations` | UNIQUE `token_hash` | satu-kali, role terbatas |
| `admin_audit` | index target/waktu | metadata JSON tervalidasi |

### 8.3 Aturan integritas yang diuji lokal

- Composite foreign key `(owner_id, category_id)` / `(owner_id, transaction_id)` menolak referensi
  lintas pemilik.
- `UNIQUE(owner_id, fingerprint)` mengizinkan fingerprint sama pada dua owner, menolak duplikat
  pada owner yang sama.
- `amount_minor` dibatasi `> 0` dan `<= 1_000_000_000_000_000` (di bawah
  `Number.MAX_SAFE_INTEGER`); nilai 0/negatif/terlalu besar ditolak.
- Kolom `counters_json`/`metadata_json` memakai `CHECK(json_valid(...))`.
- Soft delete mengeluarkan baris dari daftar/total aktif tetapi mempertahankan baris.
- Reassign kategori + soft delete berjalan dalam satu transaksi.
- Trigger menolak demosi/penghapusan ADMIN aktif terakhir.
- Rotasi refresh: insert replacement lebih dulu, lalu revoke lama dengan `replaced_by_id`;
  replay mencabut seluruh keluarga.
- Query daftar memakai `idx_transactions_owner_month` dan urutan `(event_at_ms DESC, id)`.

### 8.4 Risiko engine

- `PRAGMA` tulis tetap dilarang di Turso; status migrasi tetap di `schema_migrations`.
- `json_valid` dan trigger harus diverifikasi pada engine libSQL cloud, bukan hanya `file:`.
- Tipe integer JavaScript: API wajib memakai `Number.isSafeInteger` dan batas DDL; BigInt tidak
  dikirim sebagai JSON number.
- Migrasi additive tidak menghapus `sessions` v0.3; C1 memakai `auth_sessions` dan menghapus tabel
  lama pada cutover.
- Rollback v0.4 → v0.3 tidak otomatis. Transaksi v0.4 perlu export/rekonsiliasi sebelum APK lama
  dibuka kembali.

## 9. Status bukti paket B

| Pemeriksaan | Status | Bukti |
|---|---|---|
| Migrasi lokal `file:` dari kosong | PASS | `schema_migrations` 1–3, boot kedua no-op (4 tes migrasi) |
| Upgrade v0.3 → v0.4 pada database lokal | PASS | baris `users` lama tetap ada; default `USER/ACTIVE`; tabel v0.4 dibuat |
| FK lintas owner, fingerprint, amount, JSON | PASS | `schemaV04.test.ts` (11 tes) |
| Soft delete, reassign kategori, admin terakhir, rotasi refresh, index pagination, account key | PASS | `schemaV04.test.ts` |
| Backend Node (referensi) | PASS | `npm run typecheck`, `npm run build`, **124 tes lulus, 0 gagal** |
| Backend Go (target) | PASS lokal | **40 tes**, `go test -race`, `go vet`, `go build ./cmd/api` |
| Go: migrasi 1–7, upgrade, trigger, scrypt/AES interop | PASS | `backend-go` 40 tes; `go test -race`, `go vet`, `go build` |
| Turso Cloud aktual + engine libSQL | **NOT RUN** | belum ada kredensial database uji; jangan memakai database existing |
| Backup/PITR/restore cloud | **NOT RUN** | menunggu kredensial/otorisasi |
| Migrasi pada database berisi data nyata | **NOT RUN** | butuh keputusan pemilik |

Perintah lokal:

```bash
cd backend
npm run typecheck && npm run build
npm test
```

Cloud acceptance (setelah kredensial uji tersedia dan engine dicocokkan dengan SDK):

1. Buat database Turso **uji** terpisah dengan plan Free; simpan URL bertipe `libsql://` dan token
   hanya di environment backend. Jangan memakai database demo/nyata.
2. Jalankan `backend` dengan `DATABASE_URL`/`DATABASE_AUTH_TOKEN` uji, lalu ulangi
   `migrate`/boot dua kali dan periksa `schema_migrations` 1–3.
3. Jalankan acceptance `schemaV04.test.ts` terhadap database uji (butuh harness cloud terpisah;
   saat ini hanya `file:`). Catat hasil PASS/FAIL dan hapus database uji setelahnya.
4. Uji PITR/export-restore ke database terpisah, lalu buktikan: `schema_migrations` ikut restore,
   `account_keys` dapat dibuka dengan kunci dari luar dump, semua sesi/refresh dicabut setelah
   restore, dan data terenkripsi terbaca. Catat jumlah record sebelum/sesudah.
5. Uji retensi: deletion request menghapus data aktif; backup kedaluwarsa ≤30 hari; file lampiran
   lokal tidak ada di backup server.
