# Rancangan Migrasi ke Pedoman Project Akhir 2026

Status: **rancangan**, belum ada kode yang diubah.
Sumber: `PEDOMAN PROJECT AKHIR PAM 2026.pdf` (6 halaman).
Dokumen pendamping: [`11_SRS_CHANGE_v0.4.md`](11_SRS_CHANGE_v0.4.md).

---

## 1. Masalah yang harus diselesaikan

Pedoman menilai **kelengkapan materi ajar** (auth JWT, role, CRUDS, database relasional, file
storage). SRS yang kita pakai menilai **privasi finansial** (transaksi tidak keluar perangkat,
token opaque, tanpa peran). Dua arah ini bertabrakan di tiga titik, dan pedoman yang menentukan
nilai.

| # | Butir pedoman | Kondisi project sekarang | Akibat bila dibiarkan |
|---|---|---|---|
| D.8 | "Database minimal MySQL… **Room dilarang**" | Room + SQLCipher | tidak memenuhi syarat wajib |
| D.1 | "Access Token (**JWT**), **Refresh Token**" | token opaque, tanpa refresh | tidak memenuhi syarat wajib |
| D.2 | "Role-based UI… minimal 1 level peran" | tidak ada peran | tidak memenuhi syarat wajib |
| D.9 | "Simpan dan baca file lokal" | tidak ada | tidak memenuhi syarat wajib |
| D.6 | "minimal 5 fitur **CRUDS penuh**" | Delete belum ada | tidak memenuhi syarat wajib |
| C.1 | "update progress **setiap minggu** ke README" | baru dimulai | 10% nilai berisiko |
| C.1/F.3 | "**sekali push project jadi** → nilai progress 0" | 81 commit dipush sekaligus | 10% nilai berisiko |

---

## 2. Keputusan desain

| Topik | Keputusan | Alasan |
|---|---|---|
| **Database** | **MySQL** (lokal via Homebrew untuk dev, cloud MySQL untuk demo) | pedoman menyebut MySQL sebagai baseline; Postgres/Cloud boleh, tapi MySQL paling aman saat ditanya |
| **Room/SQLCipher** | **dibuang seluruhnya** | dilarang eksplisit |
| **Auth** | **JWT access** (15 menit) + **refresh token** (30 hari, opaque, dirotasi) | pedoman D.1 |
| **Peran** | 2 level: `USER` dan `ADMIN` | D.2; ADMIN memberi layar tambahan + CRUDS pengguna |
| **File storage** | lampiran bukti transaksi, disimpan di direktori file aplikasi | D.9; sekaligus menjadi fitur CRUDS ke-4 |
| **Transaksi** | pindah ke MySQL, diakses lewat REST | konsekuensi D.8 |
| **Lapisan UI** | **tidak berubah** | repository di `domain` sudah berupa interface, jadi sumber data bisa ditukar tanpa menyentuh layar |
| **Parser IMAP** | tetap di perangkat, hasilnya dikirim ke server | email mentah tetap tidak disimpan; hanya baris hasil parsing yang dikirim |

> Catatan jujur: keputusan "transaksi ke cloud" **membatalkan** arsitektur hybrid yang saya
> pertahankan sebelumnya. Klaim privasi di SRS harus dicabut (lihat dokumen 11).

---

## 3. Arsitektur target

```
┌──────────────── Android (Compose) ────────────────┐
│  ui/ ──► domain/ ──► data/remote (OkHttp + JWT)   │
│                            │                      │
│                            ├── files/ (lampiran)   │  ← file lokal (D.9)
│                            ├── imap/  (parser)     │
│                            └── token/ (Keystore)   │
└────────────────────────────┼──────────────────────┘
                             │ REST + JSON  (D.7)
                             ▼
┌──────────────── Backend (Node 24 + TypeScript) ───┐
│  routes/ ──► domain/ ──► store/ (mysql2)          │
│  auth/ JWT + refresh · roles · fs metadata        │
└────────────────────────────┼──────────────────────┘
                             ▼
                        MySQL (D.8)
```

**Tidak ada Room. Tidak ada SQLCipher.** File lampiran tetap lokal karena pedoman justru
mewajibkannya.

---

## 4. Skema MySQL

```
users ──┬── refresh_tokens
        ├── categories ──┐
        ├── transactions ┼── categories
        │      └── attachments
        ├── budget_plans ── budget_limits ── categories
        ├── summaries
        ├── analyses
        ├── consents
        └── deletion_requests
```

| Tabel | Kolom inti | Keterangan |
|---|---|---|
| `users` | id, email, password_hash, **role** ENUM('USER','ADMIN'), display_name, is_active | D.2 |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked_at, replaced_by | rotasi D.1 |
| `categories` | id, user_id, code, label, kind, color, **attributes JSON**, deleted_at | field dinamis D.5 |
| `transactions` | id, user_id, category_id, direction, amount_minor, currency, event_at, description, note, source, status, fingerprint, is_internal_transfer, **metadata JSON**, deleted_at | field dinamis D.5 |
| `budget_plans` / `budget_limits` | plan per bulan, limit per kategori | relasi |
| `attachments` | id, user_id, transaction_id, file_name, mime, size_bytes, **local_path**, checksum | file lokal, metadata server |
| `summaries`, `analyses`, `consents`, `deletion_requests`, `login_attempts`, `usage_counters` | dipertahankan | sudah ada |

**Field dinamis (D.5)** diwujudkan sebagai kolom `JSON` pada `transactions.metadata` dan
`categories.attributes`, dengan editor "Field tambahan" (key–value) di UI.

---

## 5. Auth: JWT + refresh

| Langkah | Detail |
|---|---|
| Register | email + password (scrypt), peran awal `USER`; pengguna pertama ditetapkan `ADMIN` |
| Login | → `accessToken` (JWT, 15 menit, berisi `sub`, `role`) + `refreshToken` (opaque 32 byte) |
| Simpan | access token **di memori**; refresh token **KeyStore-wrapped** di perangkat |
| Pakai | `Authorization: Bearer <jwt>` pada setiap request |
| Kedaluwarsa | OkHttp `Authenticator` memanggil `POST /v1/auth/refresh` lalu mengulang **sekali** |
| Rotasi | setiap refresh menerbitkan token baru dan mencabut yang lama (`replaced_by`) |
| Logout | refresh token dicabut di server + dihapus dari perangkat |
| Deteksi ulang pakai | refresh token yang sudah dicabut ⇒ cabut seluruh sesi pengguna itu (indikasi pencurian) |

Endpoint baru: `POST /v1/auth/register`, `POST /v1/auth/login`, `POST /v1/auth/refresh`,
`POST /v1/auth/logout`, `GET /v1/auth/me`.

---

## 6. Peran dan UI

| Peran | Melihat | Tidak melihat |
|---|---|---|
| `USER` | Ringkasan, Transaksi, Analisis, Anggaran, Pengaturan | menu Administrasi |
| `ADMIN` | semua di atas + **Administrasi**: daftar pengguna (CRUDS), ringkasan sistem | — |

Pembatasan **bukan hanya di UI**: setiap route admin memeriksa `role` dari JWT dan menjawab `403`
bila bukan `ADMIN`.

---

## 7. Lima fitur CRUDS penuh (D.6)

| # | Fitur | Create | Read | Update | Delete | Search |
|---|---|---|---|---|---|---|
| 1 | **Transaksi** | manual + impor email | daftar + detail | kategori, catatan, field tambahan | hapus (soft delete) | keterangan |
| 2 | **Kategori** | kategori buatan pengguna | daftar + detail | label, warna, atribut | hapus (kategori default dilindungi) | nama |
| 3 | **Rencana Anggaran** | rencana per bulan | daftar + detail | nominal & batas | hapus rencana | bulan |
| 4 | **Lampiran** | pilih file → simpan lokal | buka/tampilkan | ganti nama | hapus file + metadata | nama file |
| 5 | **Pengguna** (ADMIN) | dibuat lewat register | daftar | nama & peran | nonaktifkan | email |

Semua entitas di atas tersimpan di MySQL dan ditampilkan di UI — memenuhi D.5 (≥4 entitas
saling terhubung) dan D.6 (≥5 CRUDS penuh).

---

## 8. File storage (D.9)

- Lampiran disimpan di `context.filesDir/attachments/<uuid>` (lokal, bukan Room).
- Metadata (nama, mime, ukuran, checksum) disimpan di MySQL agar bisa dicari dan ditampilkan.
- Alur: pilih berkas → simpan lokal → catat metadata → tampilkan di detail transaksi → bisa
  dibuka, diganti nama, dihapus.
- Batas ukuran dan tipe berkas divalidasi; tidak ada berkas yang dikirim ke server.

---

## 9. Dampak per lapisan

| Lapisan | Yang berubah |
|---|---|
| `ui/theme`, `ui/components` | **tidak berubah** — nilai UI/UX tetap |
| `ui/feature/*` | tambah layar: Administrasi, editor Kategori, Lampiran; tambah aksi hapus |
| `ui/navigation` | tambah rute admin/kategori/lampiran |
| `domain/repository` | interface dipertahankan, ditambah untuk kategori, lampiran, admin |
| `domain/usecase` | tambah CRUDS use case |
| `data/local` (Room) | **dihapus** |
| `data/remote` | baru: sumber data REST untuk semua entitas |
| `data/token` | baru: penyimpanan JWT/refresh |
| `data/files` | baru: penyimpanan lampiran |
| `backend` | MySQL, JWT, role, endpoint CRUDS, upload metadata |

Keuntungan: interface repository sudah ada, jadi **layar tidak perlu ditulis ulang** — yang
berganti hanya implementasi di baliknya.

---

## 10. Rencana fase

| Fase | Isi | Kriteria selesai |
|---|---|---|
| **0** | Dokumen ini + SRS v0.4 + README progress | disetujui owner |
| **1** | Skema MySQL + migrasi store backend | semua test backend hijau di MySQL; data lama (Turso) tidak diimpor |
| **2** | Endpoint CRUDS transaksi + kategori + pagination/search | teruji lewat test + smoke |
| **3** | JWT + refresh token (server & klien), hapus token opaque | 401→refresh→retry terbukti |
| **4** | Repository remote di aplikasi menggantikan Room (UI tidak berubah) | layar yang ada tetap berjalan |
| **5** | Peran + layar Administrasi + pembatasan 403 | ADMIN melihat, USER ditolak |
| **6** | Lampiran (file lokal + metadata) + CRUDS penuh | berkas tersimpan, terbaca, terhapus |
| **7** | Hapus Room + SQLCipher dari build; bersihkan dokumen | `grep -r "androidx.room"` ≈ 0 |
| **8** | SRS v0.4 final + README + push rutin | disetujui owner |

Estimasi: **10–14 sesi kerja**. Fase 1–3 tidak menyentuh aplikasi; aplikasi tetap bisa dipakai
sampai Fase 4 dimulai.

---

## 11. Risiko

| Risiko | Dampak | Mitigasi |
|---|---|---|
| Kehilangan mode offline | demo butuh internet | demo memakai MySQL lokal + tunnel; nyatakan keterbatasan di SRS |
| Migrasi besar sekaligus | aplikasi rusak di tengah | tiap fase hijau + commit kecil |
| Klaim privasi jadi tidak benar | nilai SRS −50% | SRS v0.4 mencabut klaim "transaksi tidak keluar perangkat" secara eksplisit |
| Test lama (203) banyak yang patah | regresi | test domain dipertahankan; test Room diganti test remote |
| Riwayat push terlihat "sekali jadi" | 10% nilai | commit + push **mingguan** mulai sekarang (dokumen ini adalah push rutin pertama) |
| Data lama di Turso | data demo hilang | memang data uji; tidak dimigrasikan |

---

## 12. Yang dibuang

| Dibuang | Diganti | Catatan |
|---|---|---|
| Room + SQLCipher | MySQL via REST | D.8 |
| Token opaque | JWT + refresh | D.1 |
| Anti-offline (NFR-22) | — | harus dicabut di SRS |
| Klaim "transaksi tidak pernah keluar perangkat" | pernyataan jujur: transaksi tersimpan di server | wajib, agar project selaras SRS |

---

## 13. Keputusan yang perlu persetujuan owner

1. **MySQL** (rekomendasi) — atau tetap Turso dan menjawab "Cloud Database diperbolehkan"?
2. Lampiran file: **bukti transaksi (foto/PDF)** — atau format lain?
3. Peran kedua: **ADMIN** dengan CRUDS pengguna — atau peran lain yang lebih sesuai tema?
4. Fase 1–3 dulu (backend, aplikasi tetap jalan), atau ada prioritas lain?
