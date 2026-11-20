# SRS Change Record — v0.3 → v0.4

Document ID: PAM-SRS-001
Change ID: **CR-002**
Tanggal: 20 November 2026
Alasan: project harus memenuhi `PEDOMAN PROJECT AKHIR PAM 2026`. Pedoman §B.9 menyatakan project
wajib **selaras dengan SRS**, jadi SRS yang menyesuaikan — bukan sebaliknya.
Status: **draf, menunggu persetujuan owner**.
Rancangan teknis: [`10_MIGRASI_PEDOMAN.md`](10_MIGRASI_PEDOMAN.md).

---

## 1. Ringkasan

| Jenis | Jumlah |
|---|---|
| Requirement **dicabut** | 3 (FR-35, NFR-22, NFR-16 sebagian) |
| Requirement **diubah** | 4 (FR-02, DR-02, DR-05, §4.3) |
| Requirement **baru** | 6 (FR-57…FR-62) |
| Perubahan sifat produk | **transaksi berpindah dari perangkat ke server** |

---

## 2. Perubahan paling besar: lokasi data transaksi

| | v0.3 | v0.4 |
|---|---|---|
| Baris transaksi | **di perangkat** (Room + SQLCipher) | **di MySQL, di server** |
| Alasannya | privasi: tidak keluar perangkat | pedoman D.8: **Room dilarang**, database minimal MySQL |
| Akses | lokal, offline | REST + JWT |

### Requirement yang dicabut

**FR-35 — DICABUT.** Bunyinya: *"The backend shall send only the required monthly totals… It shall
exclude email bodies, subjects, sender addresses, message IDs, OAuth tokens, account/card
identifiers, customer names and **transaction-level merchant descriptions**."*

Penghapusan berlaku untuk bagian **transaction-level merchant descriptions**. Bagian lain
**tetap berlaku** dan dipindahkan ke FR-60:
- email body, subject, sender, message ID, token, PAN, nama nasabah **tetap tidak boleh** dikirim
  ke penyedia LLM;
- yang berubah hanya siapa yang menyimpan baris transaksi (server PAM, bukan LLM).

**NFR-22 — DICABUT.** *"The dashboard shall remain readable from encrypted local data during
provider failures."* Tidak mungkin dipertahankan tanpa database lokal. Diganti NFR-27.

**NFR-16 — DIUBAH.** Tidak lagi mengatur database lokal (sudah tidak ada), tetapi tetap mengatur:
kredensial IMAP (Keystore), token refresh (Keystore), file lampiran, dan `FLAG_SECURE`.

---

## 3. Requirement baru

| ID | Requirement |
|---|---|
| **FR-57** | The system shall issue a short-lived **JWT access token** (≤15 minutes, containing subject and role) and an opaque **refresh token**, rotate the refresh token on every use, and revoke the previous one. Reuse of a revoked refresh token shall revoke the user's whole session chain. |
| **FR-58** | The system shall support at least two roles, `USER` and `ADMIN`. Authorisation shall be enforced on the server for every route, not only hidden in the UI; a role mismatch shall answer `403`. |
| **FR-59** | The system shall let a user attach a local file to a transaction: store the file in app-private storage, record its metadata server-side, display it, rename it, and delete it. File bytes shall never be uploaded. |
| **FR-60** | Transaction rows shall be stored server-side, owner-scoped, and reachable through REST create, read, update, delete and search. The backend shall not forward them to the LLM provider; only the aggregate projection defined in FR-56 may leave the backend. |
| **FR-61** | Four or more related entities shall be persisted and displayed, each supporting user-defined dynamic fields (transaction `metadata`, category `attributes`). |
| **FR-62** | The client shall refresh an expired access token transparently at most once per request and, on refresh failure, require the user to sign in again rather than losing unsaved work silently. |

---

## 4. Requirement yang diubah

| ID | Perubahan |
|---|---|
| **FR-02** | Tidak berubah maknanya (setiap akses di-scope pemilik), tetapi kini mencakup **semua baris transaksi** di MySQL, bukan hanya agregat. Isolasi antar-pengguna diuji untuk transaksi, kategori, lampiran, dan anggaran. |
| **DR-02** | Baris transaksi **disimpan di server**, tetap tanpa email body, header lengkap, PAN, terminal ID, atau lampiran. Fingerprint tetap opaque. |
| **DR-05** | Diganti: minimalisasi kini berlaku pada **email**, bukan pada transaksi. Email tetap diproses di perangkat dan tidak disimpan; transaksi disimpan di server milik pengguna. |
| **§4.3** | "imported transaction rows shall remain on-device" **dihapus**, diganti pernyataan bahwa baris transaksi disimpan di MySQL dengan scoping pemilik. |

---

## 5. Pernyataan privasi yang harus berubah

Ini bagian yang paling perlu kejujuran. Klaim berikut **tidak boleh** dipertahankan lagi:

| Klaim lama | Kenyataan v0.4 |
|---|---|
| "Raw email and transaction rows stay on the device" | email tetap di perangkat; **transaksi tidak lagi** |
| "The dashboard shall remain readable offline" | tidak lagi; dashboard butuh jaringan |
| "The backend never receives merchant descriptions" | backend **menyimpan** deskripsi transaksi milik pengguna |

**Yang tetap berlaku:**
- Email mentah, subjek, pengirim, isi lampiran email **tidak** disimpan di mana pun.
- Kredensial IMAP dan token refresh hanya di perangkat, dibungkus Keystore.
- Penyedia LLM tetap **hanya** menerima agregat bulanan (FR-60).
- Berkas lampiran pengguna tidak pernah diunggah.

---

## 6. Pembaruan acceptance

| Suite | Perubahan |
|---|---|
| **T01** Identity | JWT + refresh, rotasi, deteksi token dipakai ulang |
| **T02** Authorization | pengujian peran: `USER` ditolak `403` pada route admin, bukan hanya disembunyikan |
| **T04** Import integrity | impor kini menulis ke server; idempotensi dan duplikasi diuji lewat API |
| **T05** Summary correctness | tetap; sumber data berubah ke MySQL |
| **T08** Privacy | transaksi di server: uji scoping pemilik, uji isi database tidak memuat email mentah |
| **T11 (baru)** | CRUDS penuh untuk 5 fitur; file storage tersimpan, terbaca, terhapus; field dinamis tersimpan dan tampil |

---

## 7. Open issues baru

| ID | Isu | Owner | Gate |
|---|---|---|---|
| **OI-18** | Data transaksi kini di server: perlu enkripsi at-rest MySQL dan kebijakan retensi yang sesuai | owner + backend lead | G2 |
| **OI-19** | Mode offline hilang; demo membutuhkan jaringan dan database hidup | owner | G2 |
| **OI-20** | Pemilihan MySQL vs Cloud Database (Turso) untuk penilaian; rekomendasi MySQL | owner | G1 |
| **OI-21** | Riwayat push: pedoman memberi 0 pada "sekali push jadi"; perlu pola push rutin mulai sekarang | owner | G4 |

---

## 8. Dampak traceability

- Requirement yang dicabut ditandai `SUPERSEDED BY v0.4` di matriks §6.3, tidak dihapus dari
  riwayat.
- `docs/01_SCOPE_TRACEABILITY.md` perlu direvisi pada baris FR-35, NFR-16, NFR-22, DR-02, DR-05.
- `docs/03_ARCHITECTURE.md` (sudah usang sejak CR-001) digantikan sepenuhnya oleh
  `docs/10_MIGRASI_PEDOMAN.md` untuk arsitektur target.

---

## 9. Sign-off

| Peran | Nama | Keputusan |
|---|---|---|
| Owner | Andhika Pratama | **menunggu** |
| Lead Developer | — | menunggu persetujuan sebelum Fase 1 |

SRS dinaikkan ke **v0.4 draft**. CR-002 baru menjadi baseline setelah owner menyetujui empat
keputusan di `10_MIGRASI_PEDOMAN.md` §13.
