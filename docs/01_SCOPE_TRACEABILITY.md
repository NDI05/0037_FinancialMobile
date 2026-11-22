# PAM — Scope & Requirement Traceability v0.4 (review draft)

Status: **DRAFT REVIEW** 21 November 2026. Ini adalah interpretasi lead untuk target v0.4,
menggantikan pemetaan v0.3 yang masih menyebut OAuth/Google Sign-In dan Room sebagai runtime.
Sumber: SRS v0.2 + CR-001 (v0.3) + CR-002 review ([`11_SRS_CHANGE_v0.4.md`](11_SRS_CHANGE_v0.4.md),
[`12_SRS_v0.4_REVIEW.md`](12_SRS_v0.4_REVIEW.md)), Pedoman PAM 2026, dan review lead 21 Nov 2026.

Kolom **Status** berarti: `v0.3` = sudah ada di baseline `fe3a183` dan dipertahankan; `target` =
belum ada, akan dibangun C1/C2/D; `reuse` = endpoint/interface v0.3 dipakai ulang dengan perubahan
kontrak. Tidak ada status "selesai" untuk v0.4 sebelum kode dan cloud acceptance dijalankan.

---

## 1. Produk dan batas

PAM merekap notifikasi **Livin by Mandiri** dan transaksi manual menjadi ringkasan IDR deterministik,
lalu — hanya dengan consent terpisah — analisis berbasis bukti dan rencana anggaran. Bukan aplikasi
bank: tanpa saldo, transfer, OCR, API bank, atau klaim kelengkapan ledger.

**Target v0.4.** Transaksi/kategori/anggaran/metadata lampiran/import job disimpan server-side di
Turso/libSQL, diakses lewat REST dari layanan **Go** (`backend-go/`). Room/SQLCipher keluar dari
varian penilaian. Email mentah dan kredensial IMAP tetap di perangkat. AI hanya menerima proyeksi
agregat. Node `backend/` adalah referensi perilaku dan rollback.

---

## 2. Navigasi target

| Route | Screen | Requirement |
|---|---|---|
| `welcome` | Welcome + masuk/daftar | FR-01/FR-51 |
| `account` | Register/login backend | FR-51/57 |
| `consent` | Gmail & AI terpisah | FR-03/34 |
| `gmail_connect` | 6 state koneksi + App Password | FR-06…10/54 |
| **`overview`** | Ringkasan bulanan dari server | FR-28…33/60 |
| **`transactions`** | List/filter/search transaksi | FR-27/60 |
| `transaction_detail` | Detail, kategori, catatan, lampiran | FR-24/25/59 |
| `manual_entry` | Input manual | FR-26 |
| `import_job` | Progress + skip reasons | FR-19/21 |
| **`analysis`** | Analisis AI + consent | FR-34…41 |
| `budget_plan` | Rencana + batas + field dinamis | FR-42…45/61 |
| **`settings`** | Sesi, consent, mailbox, retensi | FR-46…49 |
| `consent_history` | Ledger consent | FR-03/49 |
| `data_deletion` | Reauth + hapus akun | FR-47/48 |
| **`admin_users`** | Daftar/cari/invite pengguna | FR-58/63 |
| `admin_user_detail` | Ubah role/status, soft delete | FR-58/63 |

Bottom bar tetap empat tab; aksi admin hanya muncul untuk `ADMIN`.

---

## 3. Functional requirement coverage

### 3.1 Identity, auth, consent

| ID | Requirement | Target/status |
|---|---|---|
| FR-01 | Sesi wajib sebelum data keuangan | JWT + gate; **reuse**, ubah token |
| FR-02 | Owner-scoped setiap akses; admin tidak membaca transaksi orang lain | **target** C1/C2 |
| FR-03 | Consent per purpose/version/decision/time | **reuse** |
| FR-04 | Withdraw AI memblokir analisis baru, laporan lama historis | **reuse** |
| FR-05 | Logout mencabut sesi/refresh, tanpa kebocoran lintas user | **target** C1 |
| FR-51 | Register/login PAM, scrypt, invite-only | **v0.3**, ditambah role USER |
| FR-53 | Rate limit login, tanpa oracle email | **reuse** |
| FR-57 | JWT access ≤15 menit + refresh opaque rotasi/replay | **target** C1 |
| FR-62 | Single-flight refresh, retry ≤1, recovery | **target** C2 |

### 3.2 Mailbox dan Livin

| ID | Requirement | Target/status |
|---|---|---|
| FR-06/07/10 | IMAP + 6 state, data lama tetap terbaca saat reauth | **v0.3** |
| FR-11–15 | Allowlist sender/subject, parser strict, gagal/attachment ditolak, alasan redacted | **v0.3** + perbaikan MIME Tahap A |
| FR-54/55 | App Password divalidasi sebelum disimpan, Keystore, read-only | **v0.3** |
| NFR-03/14 | TLS IMAP, tanpa kredensial di log/backend | **v0.3** |

### 3.3 Import dan transaksi

| ID | Requirement | Target/status |
|---|---|---|
| FR-16/17 | Pagination, buffer, membership event time Jakarta, field presisi | **v0.3** (lokal) → server batch |
| FR-18/DR-02 | Idempoten fingerprint HMAC per akun, collision flag | **v0.3 lokal** → kunci server C1 |
| FR-19/20/21 | Job state, empty≠failed, retry tanpa duplikasi | **v0.3** → server job/batch |
| FR-22 | Perubahan menandai ringkasan/analisis outdated | **v0.3** → `month_revisions` |
| FR-23–27 | Kategorisasi, manual, filter/search, provenance | **v0.3** → REST CRUDS |
| FR-60 | Transaksi server-side owner-scoped via REST | **target** C1/C2 |
| FR-61 | Empat entitas + field dinamis | **target** D |

### 3.4 Ringkasan, AI, anggaran

| ID | Requirement | Target/status |
|---|---|---|
| FR-28–33 | Total deterministik, drill-down, delta, revisi | **v0.3** calculator → server summary C1 |
| FR-34–41 | Consent, payload agregat, validasi, cache, withdraw | **reuse** backend |
| FR-35/NFR-19 | Allowlist payload; merchant/catatan/label bebas/atribut dikecualikan | **v0.3 backend**, diperluas C1 |
| FR-42–45 | Rencana, gap, limit, revision, nilai origin | **v0.3** → server budget C1 |
| FR-50 | Rate/ceiling AI | **reuse** |

### 3.5 Privasi, file, admin

| ID | Requirement | Target/status |
|---|---|---|
| FR-46–49 | Disconnect, deletion, status retensi, settings | **reuse** + server deletion |
| FR-52 | Mode v0.4 butuh backend/internet; fixture berlabel | **target** C2 |
| FR-56 | Turso/libSQL menyimpan data bisnis; kunci di backend | **target** C1 |
| FR-58 | USER/ADMIN, register→USER, provisioning terkontrol, last admin | **target** C1/D |
| FR-59 | File lokal per owner + metadata server | **target** D |
| FR-61 | Field dinamis empat entitas | **target** D |
| FR-63 | Lima CRUDS Compose | **target** C2/D |
| FR-64 | Kategori owner-scoped + reassign, kode aman untuk AI | **PASS backend**, UI C2/D |
| FR-65 | Admin create/invite/list/update/soft-delete/search, USER 403 | **target** D |
| DR-04/05 | Revisi dependen; minimisasi data server | **target** C1 |
| NFR-15/16/21/22/23 | Retensi, enkripsi, idempotensi, availability, backup/restore | **target** C1/D/E |

---

## 4. Business rules dan non-functional

BR-01…BR-06 tidak berubah (bulan Jakarta, gross/classified, refund netting, minor unit, coverage,
sitasi metrik). Tambahan operasional: amount `> 0` dan `≤ 1e15` minor; IDR-only di API v0.4.

| NFR | Target v0.4 |
|---|---|
| NFR-01/02 | Aksesibilitas dan format IDR tetap; state loading/error/offline baru diuji TalkBack/font 200% |
| NFR-03 | HTTPS API; IMAP TLS; tidak ada trust bypass |
| NFR-05 | Retry ≤2 dengan backoff untuk API; refresh di luar retry buta |
| NFR-06/07 | Target performa diukur pada dataset cloud yang ditentukan; angka dikunci sebelum test |
| NFR-10/11 | Tidak ada pergerakan uang; zero≠empty≠partial≠failed |
| NFR-13/19 | Teks provider/email = data; allowlist payload di backend |
| NFR-15/16 | Retensi 24 bulan; AES-GCM payload + field sensitif; Keystore untuk token/file key |
| NFR-17 | Setiap query memfilter owner; diuji dua akun |
| NFR-18 | Log tanpa body/email/nominal/merchant/token/path |
| NFR-21 | Impor atomik/idempoten per batch |
| NFR-22 | Gmail/AI gagal ≠ data server hilang; DB gagal = unavailable |
| NFR-23 | Backup/PITR + restore drill; sesi dicabut tetap dicabut; kunci terpisah |
| NFR-25 | UI tetap dapat dibaca pada font besar dan layar kecil |
| NFR-27 | Go: validasi request, timeout/body/cancellation, graceful shutdown, error eksplisit, log tanpa PII, migrasi/retry aman |
| NFR-28 | Migrasi Go: password/ciphertext/owner/migration re-entry/parity Android tetap; dibuktikan sebelum cutover |

---

## 5. Acceptance suites T01–T11

| Suite | Cakupan | Bukti wajib | Status target |
|---|---|---|---|
| T01 Identity & consent | FR-01–05, 34, 41, 49, 51, 57, 62; NFR-17 | register/login/JWT/refresh/logout, dua user, consent terpisah, role | target C1/C2 |
| T02 IMAP authorization | FR-06–10, 54, 55; NFR-03/05/14 | login valid/salah/dicabut, TLS, tanpa kredensial di log | v0.3 sebagian, Gmail nyata belum |
| T03 Livin source | FR-11–15; NFR-13; DR-05 | fixture supported/gagal/attachment/spoof/injection | v0.3 + fixture MIME |
| T04 Import integrity | FR-16–22; NFR-21; BR-01/05; DR-02/03 | pagination, boundary, retry, duplicate, batch atomik, fingerprint lintas reinstall | target C1 |
| T05 Summary correctness | FR-23–33; BR-02–04; DR-04 | total desimal, manual, refund, transfer, delta, revisi | target C1/C2 |
| T06 AI evaluation | FR-34–41, 50; NFR-09–13/19; BR-06 | consent, allowlist, schema, angka karangan, injection | reuse + audit payload |
| T07 Budget planning | FR-42–45, 61 | gap, limit, revision, field dinamis, delete | target C1/D |
| T08 Privacy & operations | FR-46–49; NFR-14–23; DR-01/05 | deletion, backup/restore, APK/log scan, retensi | target C1/D/E |
| T09 Mobile quality | NFR-01/02/06/07/25 | TalkBack, font 200%, loading/error/offline, timing | target C2/E |
| T10 Release audit | NFR-24/26; semua suite | traceability, APK/backend/SRS/engine cloud cocok, signed gate | target E |
| **T11 v0.4 CRUDS & files** | FR-58–64; D.5/D.6/D.9 | 25 aksi CRUDS, 4 entitas+dynamic fields, 2 user+admin, restart, file lokal | target D/E |
| **T12 Go migration** | NFR-27/28 | fixture Node↔Go password/AES, HTTP parity, migrasi re-entry/rollback, libSQL transactions, Android→Go smoke | lokal PASS; cloud NOT RUN |

---

## 6. Rubrik pedoman → bukti

| Butir | Syarat | Rencana bukti |
|---|---|---|
| D.1 | Login/register, JWT, refresh | T01, endpoint §2 06 |
| D.2 | Role-based UI/akses, min. 1 peran | USER/ADMIN, T01/T11 |
| D.3 | Navigation Compose multi-page | 16 destinasi + tes gate |
| D.4 | MVVM | ViewModel/use case/repository tetap; remote repository C2 |
| D.5 | 4 entitas terkait + field dinamis | transaksi/kategori/anggaran/lampiran; T11 |
| D.6 | 5 CRUDS penuh | §7, 25 checks |
| D.7 | REST + JSON | 06, dua user C1/C2 |
| D.8 | MySQL min./cloud; Room dilarang | Turso/libSQL; Room dihapus D |
| D.9 | Simpan/baca file lokal | lampiran private + metadata; T11 |
| D.10 | UI/UX Compose | T09, screenshot + perangkat |
| C.1/F.3 | Progress mingguan, ≥30 commit | README per minggu; commit bertahap |

---

## 7. Dua puluh lima pemeriksaan CRUDS

| # | Fitur | Aksi | Bukti yang harus terlihat |
|---|---|---|---|
| 1 | Transaksi | Create | Manual entry tersimpan server, muncul di daftar |
| 2 | Transaksi | Read | Detail memuat nominal/tanggal/kategori/provenance |
| 3 | Transaksi | Update | Ubah kategori/catatan/atribut, revisi naik |
| 4 | Transaksi | Delete | Soft delete hilang dari daftar dan total, baris retensi tetap |
| 5 | Transaksi | Search | Cari deskripsi pada bulan/rentang ≤31 hari, cursor benar |
| 6 | Kategori | Create | Kategori pengguna baru muncul di picker |
| 7 | Kategori | Read | Daftar/detail label, warna, atribut |
| 8 | Kategori | Update | Label/atribut berubah dan terbaca ulang |
| 9 | Kategori | Delete | Reassign transaksi/batas lalu hilang dari daftar aktif |
| 10 | Kategori | Search | Cari nama kategori milik owner |
| 11 | Anggaran | Create | Rencana bulan + limits tersimpan |
| 12 | Anggaran | Read | Detail + gap benar |
| 13 | Anggaran | Update | Nominal/limits/atribut berubah, revisi naik |
| 14 | Anggaran | Delete | Plan hilang; cashflow tidak berubah |
| 15 | Anggaran | Search | Cari bulan/rentang daftar rencana |
| 16 | Lampiran | Create | File disalin privat + metadata server |
| 17 | Lampiran | Read | Buka ulang file; perangkat lain `fileAvailable=false` |
| 18 | Lampiran | Update | Ganti nama/atribut metadata |
| 19 | Lampiran | Delete | Metadata tombstone + file lokal terhapus/retry |
| 20 | Lampiran | Search | Cari nama dalam transaksi/owner |
| 21 | Admin | Create/invite | Undangan satu-kali tampil di UI admin |
| 22 | Admin | Read | Daftar/detail akun (tanpa data keuangan) |
| 23 | Admin | Update | Nama/role/status; sesi dicabut; audit |
| 24 | Admin | Delete | Soft delete, keluar dari daftar, sesi dicabut |
| 25 | Admin | Search | Cari email/nama; last admin dilindungi |

---

## 8. Keputusan yang masih terbuka

1. Sign-off paket v0.4 (transaksi server-side, offline terbatas, dua peran, lampiran, cutover).
2. Hosting API publik 24 jam (sekarang laptop) dan URL HTTPS demo.
3. Retensi data server setelah akun dihapus (30 hari backup vs lebih pendek).
4. Apakah rencana anggaran boleh dihapus hard (usulan) atau harus soft delete bertombstone.
5. Apakah admin boleh mengubah email akun; usulan: tidak (email = identitas login).
6. Kredensial database Turso **uji** dan engine yang cocok; cloud acceptance belum dijalankan.

Sampai keputusan ini dicatat, implementasi C1 hanya boleh memakai dataset sintetis dan database uji
terisolasi. Tidak ada migrasi/penghapusan data nyata.

---

## 9. Rencana eksekusi dan exit criteria (C1–E)

### 9.1 Lingkungan

| Lingkungan | Isi | Status |
|---|---|---|
| Lokal backend | `file:` libSQL di direktori temporer; seed sintetis; tanpa data pengguna | tersedia |
| Android JVM | Robolectric/Compose test + MockWebServer | tersedia |
| Emulator API 36 | backend lokal + `adb reverse`; APK debug | tersedia |
| Turso Cloud **uji** | database terpisah, kredensial dari environment backend, engine `libsql://` | **NOT RUN** — belum ada kredensial |
| Gmail nyata | akun demo pemilik + App Password | **NOT RUN** — pemilik |
| Perangkat fisik | HP Android | **NOT RUN** |

Semua data development sintetis. Tidak ada database demo/nyata yang dipakai untuk migrasi atau
acceptance.

### 9.2 Exit criteria per paket

| Paket | Harus lulus |
|---|---|
| C1 backend | Migrasi 1–3 pada database uji; register/login/refresh/logout; dua user; CRUDS transaksi/kategori; batch impor idempoten; ringkasan/revisi; 401/403/409/503; log scan |
| C2 Android | Register → consent → transaksi → ringkasan lewat Compose; restart memuat dari REST; single-flight refresh; state loading/empty/error/unavailable; bukan Room |
| D | Anggaran, lampiran, admin, field dinamis; 25 aksi CRUDS; Room/SQLCipher keluar dari dependency/schema/runtime; file terbaca setelah restart |
| E | Dua user + admin; cloud persistence; gangguan jaringan; backup/restore; APK release; demo 15–30 menit; SRS/README/APK konsisten |

### 9.3 Perintah bukti

```bash
# Android
./scripts/build.sh :app:testDebugUnitTest --rerun
./scripts/build.sh :app:assembleDebug :app:lintDebug

# Backend lokal
cd backend && npm run typecheck && npm run build && npm test

# Cloud (setelah kredensial uji)
# DATABASE_URL=libsql://<db-uji> DATABASE_AUTH_TOKEN=... npm test
# lalu ulangi boot dua kali dan periksa schema_migrations 1–3
```

### 9.4 Aturan bukti

- Setiap klaim PASS menyebut perintah, jumlah tes, dan lingkungan (lokal/emulator/cloud/perangkat).
- Fixture tidak membuktikan Gmail nyata; `file:` tidak membuktikan Turso Cloud.
- Jangan menyalin email, token, kredensial, nominal pribadi, atau path file ke laporan/log/commit.
- Kegagalan yang belum diperbaiki dicatat sebagai FAIL/BLOCKED, bukan dihapus dari laporan.
