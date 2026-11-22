# PAM Backend — API Contract v0.4 (review draft)

Status: **DRAFT REVIEW**, 21 November 2026. Cloud database gratis disetujui pemilik; sign-off
paket v0.4 belum tercatat. Kontrak ini adalah target C1/C2, bukan deskripsi kode v0.3 yang sedang
berjalan. Sumber requirement: [`12_SRS_v0.4_REVIEW.md`](12_SRS_v0.4_REVIEW.md) FR-02/35/52/56–63,
DR-02/04/05, NFR-15/16/19/21/22/23.

**Implementasi target:** layanan Go di `backend-go/` (Go 1.27.4, `net/http`, `database/sql`,
driver libSQL remote + SQLite lokal untuk tes). `backend/` Node adalah referensi perilaku dan jalur
rollback; lihat [`13_GO_BACKEND_MIGRATION.md`](13_GO_BACKEND_MIGRATION.md). Migrasi SQL dimiliki
oleh Go untuk database baru.
**Base:** `https://<api>/v1` (lokal `http://127.0.0.1:8080`). JSON UTF-8. Waktu ISO-8601 UTC.
Bulan `YYYY-MM` dalam **Asia/Jakarta**. Nominal selalu **integer minor IDR** (1/100 rupiah).

---

## 1. Prinsip yang tidak boleh dilanggar

1. **Owner dari sesi.** `owner_id` tidak pernah diterima dari body/query. Setiap query memfilter
   owner; admin tidak dapat membaca data keuangan pengguna lain (FR-02).
2. **Atomisitas.** Operasi yang menyentuh lebih dari satu tabel berjalan dalam satu transaksi
   tulis libSQL. Gagal di tengah = tidak ada perubahan.
3. **Idempotensi.** Create manual dan batch impor memakai idempotency key per owner. Key sama +
   body sama mengembalikan hasil tersimpan; key sama + body berbeda → `409 idempotency_conflict`.
4. **Revisi.** Setiap perubahan transaksi menaikkan `month_revisions.revision`; ringkasan/analisis
   membandingkan `inputRevision`. Update memakai `If-Match` dan gagal `409 revision_conflict` bila
   versi berubah.
5. **Batas payload.** Body ≤256 KB, batch ≤100 kandidat, amount ≤`1_000_000_000_000_000` minor,
   `Number.isSafeInteger` wajib.
6. **Tanpa rahasia di log.** Tidak ada body, email, merchant, nominal, token, ciphertext, atau
   path file di log. `correlationId` selalu ada.

---

## 2. Auth: JWT access + refresh rotation

### 2.1 Register

```http
POST /v1/accounts
{ "email": "a@example.test", "password": "8..200 chars", "displayName": "A", "inviteCode": "..." }
```
`201 { userId, email, displayName, role: "USER" }`. Register publik selalu `USER`. Undangan admin
memakai `invitationToken` menggantikan `inviteCode` dan boleh menghasilkan `ADMIN` sesuai undangan.
Duplicate email → `409 conflict`; kode salah → `403 forbidden` (`invalid_invite`).
Server membuat `account_keys` (kunci fingerprint) dalam transaksi yang sama.

### 2.2 Login

```http
POST /v1/sessions
{ "email": "...", "password": "..." }
```
`201`:
```json
{
  "accessToken": "<JWT>",
  "accessExpiresAt": "2026-11-21T12:15:00Z",
  "refreshToken": "<opaque 32-byte base64url>",
  "refreshExpiresAt": "2026-12-21T12:00:00Z",
  "user": { "id": "u_...", "email": "...", "displayName": "A", "role": "USER" }
}
```
Access JWT (HS256, `JWT_SECRET` ≥32 byte):
`iss=pam-api`, `aud=pam-android`, `sub=<owner_id>`, `sid=<auth_sessions.id>`,
`role=USER|ADMIN`, `ver=<users.auth_version>`, `iat`, `exp` (≤15 menit), `jti`.

Setiap request terautentikasi memverifikasi signature, `iss`, `aud`, `exp`/`nbf` (toleransi 60 dtk),
lalu memuat `auth_sessions` dan `users`. Tolak `401 session_stale` bila sesi dicabut/kedaluwarsa,
`sub` tidak cocok, `ver` berbeda, status bukan `ACTIVE`, atau role klaim berbeda dari DB.
Perubahan role/status menaikkan `auth_version` dan mencabut sesi dalam transaksi yang sama.

Refresh token: 32 byte opaque; server hanya menyimpan `sha256(token + REFRESH_PEPPER)`; TTL 30 hari.

### 2.3 Refresh (rotasi atomik)

```http
POST /v1/sessions/refresh
{ "refreshToken": "..." }
```
`200` = pasangan access + refresh baru. Implementasi wajib dalam satu transaksi:
1. cari `refresh_tokens.token_hash`, belum revoked/expired;
2. **insert replacement lebih dulu** (karena `replaced_by_id` adalah foreign key);
3. revoke token lama (`revoked_reason='rotated'`, `replaced_by_id=<baru>`);
4. kembalikan pasangan baru.

Token lama yang dipakai lagi setelah rotasi = replay: revoke **seluruh keluarga** dan `401
refresh_reused`. Tidak ada grace period yang mengembalikan token baru yang sama.

### 2.4 Logout / revoke

```http
DELETE /v1/sessions      Authorization: Bearer <access>
```
`204`; revoke `auth_sessions` + semua `refresh_tokens` keluarga itu. Access token lama langsung
ditolak karena sesi dicabut (bukan menunggu TTL).

### 2.5 Klien

- Satu **single-flight refresh** untuk semua request paralel; tidak ada dua refresh bersamaan.
- Setiap request diulang **maksimal sekali** setelah refresh berhasil.
- Refresh yang gagal/ambigu tidak diulang buta. Klien menghapus token, mempertahankan isian form
  yang belum terkirim bila aman, dan masuk ke layar pemulihan sesi (FR-62).
- Access/refresh disimpan dengan Keystore; `Authorization` tidak pernah dicatat.

### 2.6 Provisioning admin

Tidak ada endpoint publik yang menaikkan role. Dua jalur terkontrol:
- CLI/ops `npm run provision:admin -- --email <email>` (idempoten, menulis audit); atau
- `POST /v1/admin/invitations` oleh ADMIN, token satu-kali ditampilkan **sekali** untuk dibagikan
  pemilik. Tidak ada pengiriman email otomatis.

---

## 3. Error, ownership dan forbidden

Semua error:
```json
{ "code": "revision_conflict", "message": "…", "retryable": false, "correlationId": "…" }
```

| HTTP | `code` | Kapan |
|---|---|---|
| 400 | `invalid_request`, `outside_retention` | skema/rentang tidak valid |
| 401 | `unauthorized`, `session_stale`, `refresh_invalid`, `refresh_reused` | auth |
| 403 | `forbidden`, `consent_required`, `builtin_category` | hak/consent |
| 404 | `not_found` | objek tidak ada **atau** milik owner lain |
| 409 | `conflict`, `idempotency_conflict`, `revision_conflict`, `category_in_use`, `last_admin`, `deletion_in_progress` | konflik |
| 413 | `payload_too_large` | body/batch/file metadata |
| 415 | `unsupported_media_type` | MIME lampiran |
| 422 | `validation_failed`, `invalid_analysis_output` | isi bisnis |
| 429 | `rate_limited`, `daily_ceiling_reached` | kuota |
| 502 | `provider_unavailable` | LLM |
| 503 | `database_unavailable` | DB tidak terjangkau — retryable |

Objek milik owner lain selalu `404` (bukan `403`) agar tidak menjadi oracle. `consent_required`
hanya untuk operasi AI/impor yang memerlukan consent.

---

## 4. Tabel endpoint

| Method | Path | Auth | Kewajiban |
|---|---|---|---|
| GET | `/healthz` | — | liveness |
| POST | `/v1/accounts` | — | FR-51/58 register |
| POST | `/v1/sessions` | — | FR-57 login |
| POST | `/v1/sessions/refresh` | refresh | FR-57 rotasi |
| DELETE | `/v1/sessions` | JWT | FR-05/57 logout |
| GET | `/v1/me` | JWT | profil, role, consent, sesi |
| GET | `/v1/fingerprint-key` | JWT | kunci HMAC per akun (DR-02) |
| GET/POST | `/v1/consents` | JWT | FR-03 ledger |
| GET | `/v1/transactions` | JWT | list/filter/search FR-27/60 |
| POST | `/v1/transactions` | JWT | create manual FR-60 |
| GET | `/v1/transactions/{id}` | JWT | detail owner |
| PATCH | `/v1/transactions/{id}` | JWT | update + `If-Match` |
| DELETE | `/v1/transactions/{id}` | JWT | soft delete + lampiran |
| GET | `/v1/categories` | JWT | list/search |
| POST | `/v1/categories` | JWT | create user category |
| GET | `/v1/categories/{id}` | JWT | detail |
| PATCH | `/v1/categories/{id}` | JWT | label/warna/atribut |
| DELETE | `/v1/categories/{id}?reassignTo=` | JWT | reassign + soft delete |
| GET | `/v1/budget-plans?from=&to=` | JWT | list bulan |
| GET | `/v1/budget-plans/{monthId}` | JWT | detail |
| PUT | `/v1/budget-plans/{monthId}` | JWT | upsert + `If-Match` |
| DELETE | `/v1/budget-plans/{monthId}` | JWT | hard delete |
| POST | `/v1/import-jobs` | JWT | buat job |
| GET | `/v1/import-jobs/{id}` | JWT | status job |
| POST | `/v1/import-jobs/{id}/batches` | JWT | batch atomik/idempoten |
| PATCH | `/v1/import-jobs/{id}` | JWT | finalisasi state |
| GET | `/v1/summaries/{monthId}` | JWT | ringkasan server-side |
| POST | `/v1/analyses` | JWT | FR-34…41/50 |
| GET | `/v1/analyses/{monthId}` | JWT | analisis tersimpan |
| GET/POST | `/v1/attachments` | JWT | list metadata / create metadata |
| PATCH/DELETE | `/v1/attachments/{id}` | JWT | rename/atribut / soft delete |
| GET | `/v1/admin/users` | ADMIN | list/search |
| GET | `/v1/admin/users/{id}` | ADMIN | detail akun |
| POST | `/v1/admin/invitations` | ADMIN | invite/create |
| PATCH | `/v1/admin/users/{id}` | ADMIN | nama/role/status + `If-Match` |
| DELETE | `/v1/admin/users/{id}` | ADMIN | soft delete + revoke |
| GET | `/v1/admin/audit?target=` | ADMIN | audit admin |
| POST/GET | `/v1/deletion-requests` | JWT | FR-47/48 |

`monthId` = `YYYY-MM` Asia/Jakarta.

---

## 5. Transaksi

### 5.1 Create

`Idempotency-Key` is **required** for manual create (1..200 printable characters). The server stores
the key, normalized request hash and result id in `http_idempotency` in the same transaction as the
row. Same key + same body → the original row with `200` and `Idempotency-Replayed: true`; same key +
different body → `409 idempotency_conflict`; a retry after soft delete returns the tombstone
(`deletedAt`) and never resurrects the row. Parallel writers are serialized by the unique key.

```http
POST /v1/transactions
Idempotency-Key: <uuid>
{
  "direction": "DEBIT",
  "amountMinor": 4500000,
  "currency": "IDR",
  "eventAt": "2026-02-03T01:12:44Z",
  "categoryId": "cat_...",
  "description": "QRIS",
  "merchantLabel": "Kopi …",
  "note": null,
  "attributes": { "tags": ["makan"] },
  "source": "MANUAL",
  "isInternalTransfer": false,
  "linkedRefundOf": null
}
```
Server menurunkan `monthId` dari `eventAt` di Asia/Jakarta, memvalidasi amount/IDR/rentang retensi
(24 bulan), lalu menyimpan field sensitif (`description`, `merchantLabel`, `note`, `attributes`)
sebagai satu JSON AES-GCM. Respons `201`:
`{ id, revision: 0, monthId, direction, amountMinor, currency, eventAt, categoryId, source,
  createdAt, updatedAt }`.

### 5.2 Read/list/search

`GET /v1/transactions?month=2026-02&categoryId=&direction=&source=&cursor=&limit=50&q=`
- `limit` default 50, max 100; `cursor` opaque = `(event_at_ms, id)`.
- Tanpa `q`: query terindeks `(owner_id, month_id, deleted_at, event_at_ms DESC, id)`.
- Dengan `q`: wajib `month` (atau `from`/`to` ≤31 hari); backend mendekripsi dan memindai maksimum
  2.000 baris, mengembalikan `items`, `nextCursor`, dan `search:{scanned,truncated}`. Tidak ada
  `LIKE` pada ciphertext.
- Soft-deleted tidak pernah muncul.

### 5.3 Update / delete

`PATCH` body `{ categoryId?, description?, merchantLabel?, note?, attributes?, isInternalTransfer?,
linkedRefundOf? }` + `If-Match: "<revision>"`. Kategori harus milik owner dan aktif. Perubahan
kategori mencatat `categoryOrigin=USER`. `409 revision_conflict` bila versi berubah.

`DELETE` soft-delete baris + metadata lampiran terkait dalam satu transaksi, menaikkan revisi
bulan. `204`. Fingerprint tetap reserved: impor ulang tidak menghidupkan kembali baris yang sengaja
dihapus (dihitung duplikat).

---

## 6. Kategori

`GET /v1/categories?q=&kind=&includeDeleted=false` — label didekripsi; kategori bawaan
(`isBuiltin=true`) tidak dapat dihapus.

`POST { code?, label, kind, color?, attributes? }` → `201`. `code` dibuat server bila kosong.
`PATCH { label?, color?, attributes?, kind? }` + `If-Match`. `DELETE ?reassignTo=<categoryId>`:
- tanpa `reassignTo` dan masih direferensikan → `409 category_in_use`;
- dengan `reassignTo` → pindahkan semua transaksi aktif + `budget_limits` lalu soft-delete kategori
  dalam satu transaksi;
- kategori bawaan → `403 builtin_category`.

Kategori pengguna boleh memiliki `attributes` key–value; label/atribut disegel dan tidak pernah
dikirim ke model AI.

---

## 7. Impor batch

### 7.1 Buat job

`POST /v1/import-jobs { "monthId": "2026-02", "templateVersion": "v1" }` → `201
{ id, state: "CREATED", ... }`.

### 7.2 Kirim batch

`POST /v1/import-jobs/{id}/batches`
```json
{
  "batchKey": "page-1",
  "pageOrdinal": 0,
  "candidates": [
    {
      "fingerprint": "32 hex",
      "status": "PARSED",
      "direction": "DEBIT",
      "amountMinor": 17500000,
      "eventAt": "2026-02-19T04:10:00Z",
      "categoryId": "cat_...",
      "parserVersion": "livin-bill-payment/v1",
      "confidence": 0.9,
      "description": "Pembayaran",
      "merchantLabel": "Indomaret",
      "attributes": {}
    },
    { "fingerprint": "32 hex", "status": "UNRESOLVED", "reason": "FAILED_PAYMENT" }
  ]
}
```
- ≤100 kandidat; `batchKey` unik per job.
- Dalam **satu transaksi**: validasi semua, insert transaksi baru dengan
  `INSERT ... ON CONFLICT(owner_id,fingerprint) DO NOTHING`, catat `import_skipped` (hanya reason
  code), update counter job, simpan `import_batches` + `request_hash`, naikkan `month_revisions`
  satu kali.
- Batch key sama + hash sama → `200` dengan counts tersimpan; hash berbeda → `409`.
- Kandidat `UNRESOLVED` tidak menyimpan nominal/merchant/body.
- Respons `200 { batchKey, matched, imported, duplicates, skipped, unresolved, inputRevision }`.

### 7.3 Finalisasi

`PATCH /v1/import-jobs/{id} { "state": "COMPLETE|PARTIAL|FAILED|EMPTY|PERMISSION_REQUIRED",
"errorCode": null }` → `200` job. Transisi state invalid → `409`. `matched` dihitung server dari
kandidat yang diterima; UI tidak boleh mengarang coverage. `COMPLETE` ditolak bila belum ada
halaman, bila `imported+duplicates+skipped != matched`, atau bila `matched=0` (harus `EMPTY`).
Batch key yang sama mengembalikan counts tersimpan bahkan setelah job difinalisasi; hash berbeda →
`409 idempotency_conflict`. Retry finalisasi dengan state akhir yang sama mengembalikan job yang
sudah ada (pemulihan respons hilang). Respons batch memuat `monthId`, `inputRevision`, counts dan
`jobState`.

Setiap kandidat `PARSED` harus berada di bulan `eventAt` yang sama dengan job menurut
Asia/Jakarta (batas UTC 17.00). Kandidat di luar bulan menolak **seluruh** batch sebelum record,
counter, idempotency atau revision ditulis. Fingerprint unik per owner: kandidat dari job bulan lain
dengan fingerprint sama dihitung `duplicates`, bukan baris baru.

### 7.4 Sweep internal

Deletion memerlukan password reauthentication (sekali pakai, tidak disimpan) dan konfirmasi literal
`DELETE_WORKSPACE`; body kosong/konfirmasi salah → `400`, password salah → `403`, admin aktif
terakhir → `409 last_admin`. Request yang dibuat segera mencabut seluruh sesi/refresh, sehingga
akses keuangan berikutnya `401`; status hanya dapat dibaca lewat `GET
/v1/deletion-receipts/{receiptToken}` yang mengembalikan state/timestamp tanpa data keuangan.
Write yang sudah melewati pemeriksaan awal diperiksa ulang di commit dan ditolak bila deletion
sudah menang.

Log server memakai template route tertutup (`/v1/deletion-receipts/{token}`), bukan path mentah;
token receipt tidak pernah menjadi bagian log/error/panic. `POST /v1/internal/sweep` dengan header `x-internal-token` (nilai dari `INTERNAL_SWEEP_TOKEN`).
Tanpa token konfigurasi → `404`; token salah → `401`. Sweep menghapus data finance/import/ringkasan/
analisis/attachment metadata/account key, mencabut sesi/refresh, menandai akun `DELETED`, dan
melindungi admin aktif terakhir.

---

## 8. Ringkasan

`GET /v1/summaries/{monthId}` menghitung dari transaksi aktif owner di server:
`grossInflowMinor`, `grossOutflowMinor`, `netMinor`, `classifiedIncomeMinor`,
`classifiedExpenseMinor`, `categoryTotals[{categoryId,code,label,kind,amountMinor,count}]`,
`transactionCount`, `coverage`, `inputRevision`, `emailDerived: true`, `generatedAt`.
Tidak ada upload ringkasan dari klien pada v0.4. Hasil boleh di-cache dengan kunci
`(owner,month,inputRevision,summaryVersion)`; cache tidak boleh menyajikan revisi lama.

`coverage` = `COMPLETE|PARTIAL|EMPTY|FAILED` dari import job terakhir + jumlah baris; `EMPTY` tidak
pernah berarti "tidak ada pengeluaran".

### 8.1 Analisis canonical dan freshness

Payload dari klien divalidasi sebagai envelope bertipe (unknown key, category code non-allowlist,
`transactionCount` hilang, dan nominal di luar batas ditolak), tetapi **angka yang dikirim klien
tidak dipakai**. Server membangun payload dari transaksi/kategori/anggaran aktif dan membandingkan
`inputRevision` dengan revisi bulan server; revisi basi → `409 revision_conflict` sebelum provider
dipanggil. Output provider divalidasi terhadap skema, metric key, `Rp` major→minor, angka minor
ambigu, instruksi, saran, dan jumlah observasi; maksimum satu regenerasi.

Snapshot menyimpan dependency hash yang mencakup revisi bulan target, priorMonth, state kategori,
budget plan/limits, dan import coverage. Finalisasi menghitung ulang token itu dalam transaksi
write; perubahan salah satu dependensi → `409`. Cache juga mensyaratkan token yang sama.
Withdrawal menandai report lama `HISTORICAL`.

Respons analisis (POST/GET/replay) memuat `monthId`, `inputRevision`, `currentRevision`,
`createdAt`, `status` (`AVAILABLE|HISTORICAL`) dan **`freshness`** (`CURRENT|STALE|HISTORICAL`).
`STALE` berarti laporan disimpan sebagai provenance tetapi dependensi server sudah berubah dan UI
tidak boleh menyebutnya terkini. Replay idempotency lama mengembalikan provenance dengan freshness
yang dihitung ulang, bukan otomatis `AVAILABLE/CURRENT`.

---

## 9. Anggaran

`GET /v1/budget-plans/{monthId}` → `{ monthId, expectedIncomeMinor, plannedSavingsMinor,
limits:[{categoryId,amountMinor}], attributes, revision, gapMinor }` (`gap = income − savings −
Σlimits`, integer minor).

`PUT /v1/budget-plans/{monthId}` + `If-Match` (`0` untuk create):
```json
{ "expectedIncomeMinor": 500000000, "plannedSavingsMinor": 100000000,
  "limits": [{ "categoryId": "cat_food", "amountMinor": 90000000 }],
  "attributes": { "catatan": "..." } }
```
Satu transaksi: upsert `budget_plans` (integer minor + sealed attributes), ganti seluruh
`budget_limits` (validasi kategori owner/aktif), naikkan `revision`. `DELETE` menghapus plan+limits
(hard) dan menulis audit; tidak mengubah total arus kas.

---

## 10. Lampiran metadata

Urutan klien: pilih file → salin ke direktori privat owner → `POST /v1/attachments` metadata. Bila
POST gagal, file hasil salin dihapus atau masuk retry queue.

`POST`:
```json
{ "transactionId": "tx_...", "localFileId": "lf_...", "deviceRef": "dev_...",
  "displayName": "nota.pdf", "mime": "application/pdf", "sizeBytes": 123456,
  "attributes": { "jenis": "nota" } }
```
`201 { id, revision, transactionId, localFileId, mime, sizeBytes, createdAt }`.
`(owner, transactionId, localFileId)` idempoten. Batas: 10 MB, MIME `image/jpeg|image/png|
image/webp|application/pdf`, ≤10 lampiran/transaksi, nama ≤120 char, atribut ≤2 KB JSON.

`GET ?transactionId=&q=&cursor=`; `PATCH {displayName?, attributes?}` + `If-Match`;
`DELETE` soft-delete metadata. Klien menghapus file lokal setelah server mengonfirmasi tombstone;
bila hapus file gagal, tombstone + retry queue mencegah metadata menggantung. Di perangkat lain,
metadata tampil dengan `fileAvailable=false` karena `deviceRef` berbeda. Menghapus transaksi
men-cascade soft-delete metadata; file lokal dibersihkan oleh klien.

Server **tidak** menyimpan/mengirim isi file dan tidak menyediakan download.

---

## 11. Admin

`GET /v1/admin/users?q=&role=&status=&cursor=&limit=` — default mengecualikan `DELETED`; email
boleh terlihat oleh ADMIN.

`POST /v1/admin/invitations { email, role: "USER"|"ADMIN" }` → `201
{ id, email, role, expiresAt, token }`; `token` ditampilkan sekali dan disimpan hanya hash-nya.
Undangan terikat email + role, sekali pakai, kedaluwarsa 7 hari.

`PATCH /v1/admin/users/{id}` + `If-Match: "<revision>"` body `{displayName?, role?, status?}`.
- Role/status berubah → naikkan `users.auth_version`, revoke semua sesi/refresh, tulis
  `admin_audit`, dalam satu transaksi.
- Menurunkan/menghapus ADMIN aktif terakhir → `409 last_admin` (juga dijaga trigger DB).
- ADMIN tidak boleh menaikkan dirinya sendiri lewat endpoint profil biasa.

`DELETE /v1/admin/users/{id}` = soft delete (`status=DELETED`), revoke sesi, audit. Data keuangan
tetap disimpan sampai retensi/penghapusan akun; tidak muncul di daftar aktif atau total pengguna.
Admin aktif terakhir tidak dapat dihapus.

---

## 12. Fingerprint per akun

`GET /v1/fingerprint-key` → `{ keyVersion, keyBase64 }` (hanya owner; access token valid).
Kunci 32 byte dibuat saat registrasi, disegel `AES-GCM` dengan AAD `fingerprint|owner|keyVersion`.
Klien menyimpannya di Keystore dan menghitung `HMAC-SHA256(key, "UIDVALIDITY:UID|amount|eventAt|
direction|recipient")`. Reinstall/perangkat baru mengambil kunci yang sama → fingerprint sama.
Rotasi kunci hanya dengan migrasi re-fingerprint; kunci dihapus saat akun dihapus.

---

## 13. Consent, analisis, deletion (reuse)

- `POST /v1/consents` tetap append-only; `WITHDRAWN` AI memblokir analisis berikutnya.
- `POST /v1/analyses` tetap memakai allowlist `AiAggregatePayload`; backend memeriksa consent,
  owner, revisi, dan payload sebelum provider. Tidak ada transaksi/merchant di payload.
- `POST /v1/deletion-requests { "password": "...", "confirmation": "DELETE_WORKSPACE" }` memblokir impor/analisis selama `PENDING`; worker menghapus data
  aktif dan metadata lampiran; backup kedaluwarsa ≤30 hari.

---

## 14. Batas dan observability

| Batas | Nilai |
|---|---|
| Access TTL | ≤15 menit |
| Refresh TTL | 30 hari |
| Body | 256 KB |
| Batch impor | 100 kandidat |
| List page | 50 default / 100 max |
| Search scan | 2.000 baris, rentang ≤31 hari |
| Amount | ≤1.000.000.000.000.000 minor |
| Lampiran | 10 MB, 10/transaksi |
| Analisis | rate + daily ceiling existing (FR-50) |

Reservasi biaya provider memeriksa biaya terpakai + reservasi outstanding + biaya panggilan
berikut. `LLM_MAX_INPUT_TOKENS` dan `LLM_MAX_OUTPUT_TOKENS` menjadi batas biaya per panggilan yang
dapat ditegakkan adapter; ini **soft ceiling** terhadap tagihan provider sebenarnya, bukan hard cap
global. Ceiling yang lebih kecil dari satu panggilan menolak sebelum provider. Usage provider
dicatat dengan context background terbatas; bila pencatatan gagal, respons error dan reservasi
konservatif tetap ada.

Konfigurasi wajib produksi: `DATA_ENCRYPTION_KEY` base64 32 byte yang bukan key development;
`JWT_SECRET`/`REFRESH_PEPPER` ≥32 byte bukan nilai development; `REGISTRATION_INVITE_CODE` bukan
`pam-dev`. Boot produksi gagal sebelum database dibuka bila salah satu tidak aman.
Konfigurasi auth (environment backend, tidak pernah di APK):
`JWT_SECRET` (≥32 byte), `JWT_ISSUER=pam-api`, `JWT_AUDIENCE=pam-android`,
`ACCESS_TOKEN_TTL_SECONDS=900`, `REFRESH_TOKEN_TTL_DAYS=30`, `REFRESH_PEPPER` (≥32 byte),
`DATA_ENCRYPTION_KEY` (existing). Ganti `JWT_SECRET`/`REFRESH_PEPPER` = semua sesi lama tidak valid
dan pengguna harus login ulang; rotasi `DATA_ENCRYPTION_KEY` memerlukan re-enkripsi bertahap.

Log JSON per request: `ts, correlationId, method, path, status, ms, ownerRef, retry`; tanpa body,
email, nominal, merchant, token, ciphertext atau path file. `ownerRef` = hash pendek.

---

## 15. Pemetaan skema

| Tabel | Peran |
|---|---|
| `users` | akun, role/status, `auth_version`, `revision` |
| `auth_sessions`, `refresh_tokens` | sesi JWT + rotasi refresh |
| `account_keys` | kunci fingerprint tersegel |
| `categories`, `transactions` | CRUDS owner-scoped + field dinamis |
| `budget_plans`, `budget_limits` | rencana + batas kategori |
| `attachments` | metadata lampiran (bukan file) |
| `import_jobs`, `import_batches`, `import_skipped` | impor atomik/idempoten |
| `month_revisions` | revisi dependen |
| `admin_invitations`, `admin_audit` | provisioning/jejak admin |
| `consents`, `summaries`, `analyses`, `deletion_requests`, `usage_counters` | existing |

DDL dan acceptance lokal ada di [`10_MIGRASI_PEDOMAN.md`](10_MIGRASI_PEDOMAN.md). Migrasi cloud
**NOT RUN** sampai kredensial database uji tersedia.

---

## 16. Yang harus lulus sebelum C1 dianggap selesai

1. Register/login/refresh/logout: JWT claims, TTL, revoke, rotation, replay family, role/status.
2. Dua owner: list/detail/update/delete lintas owner → 404; fingerprint sama di dua owner boleh.
3. CRUDS transaksi/kategori: `If-Match`, soft delete, reassign kategori, total berubah.
4. Impor: batch idempoten, retry tidak menduplikasi, atomic rollback, revisi naik sekali.
5. Ringkasan: angka cocok dengan calculator, `inputRevision` berubah setelah edit/hapus.
6. Anggaran: limits menggantikan seluruh set, gap benar, delete tidak mengubah cashflow.
7. Lampiran: metadata tanpa file di perangkat lain, cleanup/retry, delete transaksi.
8. Admin: invite/create, role/status, last-admin, revoke sesi, audit.
9. Shadow search: hanya rentang terbatas, pagination benar, ciphertext tidak di-`LIKE`.
10. Log/scan: tanpa PII; database cloud diuji terpisah dengan kredensial uji.

---

## 17. Backup, restore dan retensi (acceptance)

- Backup/export mencakup `schema_migrations`, `users`, `auth_sessions`, `refresh_tokens`,
  `account_keys`, kategori, transaksi, anggaran/limits, metadata lampiran, import job, consent,
  ringkasan/analisis dan deletion. Kunci enkripsi (`DATA_ENCRYPTION_KEY`) **tidak** disimpan di
  dump; kunci dikembalikan dari jalur terpisah.
- Restore ke database terpisah harus lolos `PRAGMA foreign_key_check` (lokal) dan pembacaan ulang
  data terenkripsi. Jumlah record dan hasil dekripsi dicatat.
- Setelah restore, **semua `auth_sessions`/`refresh_tokens` dicabut** dan pengguna login ulang.
  Ini mencegah sesi yang diterbitkan setelah titik backup tetap hidup pada keadaan lama.
- `account_keys` ikut backup; bila kunci data hilang, fingerprint tidak dapat dipulihkan dan impor
  berikutnya akan menduplikasi. Karena itu kunci data wajib disimpan terpisah dan diuji restore.
- Retensi: data aktif dihapus pada deletion request; backup kedaluwarsa ≤30 hari; file lampiran
  lokal dihapus klien dan tidak ada di backup server. PITR Free 24 jam adalah bantuan, bukan
  pengganti export harian.
