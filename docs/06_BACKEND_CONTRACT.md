# PAM Backend — API Contract (v1)

Sumber kewajiban: SRS §4.3 (software interfaces), FR-02/03/34–41/47–50, NFR-14/16/17/18/26,
Appendix B2. Dokumen ini adalah kontrak yang harus dipenuhi implementasi.

**Lokasi:** `pam-app/backend/` (satu repo dengan aplikasi, tapi *bukan* modul Gradle).

---

## 1. Keputusan arsitektur (menutup OI-07, OI-08)

| Aspek | Keputusan | Alasan |
|---|---|---|
| Runtime | **Node.js 24 + TypeScript** | `node:sqlite` bawaan → tanpa dependensi native, satu perintah jalan |
| HTTP | **Express 5** | mapan, middleware ringkas |
| DB | **SQLite** (default dev/skala kecil), mudah dipindah ke Postgres | satu file, nol infra untuk demo |
| Auth | **Opaque session token** hasil tukar Google ID token | bisa dicabut saat sign out → memenuhi FR-05 |
| LLM | **Adapter OpenAI-compatible** (`LLM_BASE_URL`) | satu adapter melayani OpenAI *dan* OpenRouter → OI-07 selesai |
| Enkripsi | **AES-256-GCM per-field** untuk payload agregat & analisis | memenuhi NFR-16 tanpa bergantung disk hosting |
| Log | JSON terstruktur, tanpa isi payload | NFR-18 |

### Yang TIDAK dilakukan backend
Tidak menyimpan email, subject, sender, message id, token Google, PAN, nama merchant, catatan
pribadi, atau baris transaksi. Hanya agregat bulanan yang disetujui (DR-05, FR-35).

---

## 2. Autentikasi

### 2.1 Tukar ID token → session

```
POST /v1/sessions
Content-Type: application/json
{ "idToken": "<Google ID token dari Credential Manager>" }
```

Verifikasi (semua wajib):
- signature terhadap Google JWKS (`https://www.googleapis.com/oauth2/v3/certs`, cache 1 jam)
- `iss` ∈ {`accounts.google.com`, `https://accounts.google.com`}
- `aud` == `GOOGLE_WEB_CLIENT_ID`
- `exp` belum lewat, `iat` tidak di masa depan (toleransi 60 dtk)
- `sub` ada dan tidak kosong

Respons `201`:
```json
{ "accessToken": "<opaque 32-byte base64url>", "expiresAt": "2026-12-01T10:00:00Z", "displayName": "Andhika" }
```

- Session disimpan sebagai `sha256(accessToken + SESSION_PEPPER)`; token asli tidak pernah disimpan.
- Masa berlaku 30 hari, bisa dicabut.

### 2.2 Header wajib

```
Authorization: Bearer <accessToken>
```

`401 unauthorized` bila tidak ada/kedaluwarsa/dicabut. Sign out:
```
DELETE /v1/sessions      → 204, session dicabut seketika (FR-05)
```

---

## 3. Bentuk error (SRS §4.3)

Semua error:
```json
{ "code": "consent_required", "message": "…", "retryable": false, "correlationId": "…" }
```

| HTTP | `code` | Kapan |
|---|---|---|
| 400 | `invalid_request` | body gagal skema |
| 401 | `unauthorized` | token tidak valid |
| 403 | `consent_required` | consent AI belum GRANTED (FR-34) |
| 403 | `forbidden` | objek milik user lain (FR-02) |
| 404 | `not_found` | — |
| 409 | `conflict` | idempotency key bentrok dengan body berbeda |
| 422 | `invalid_analysis_output` | keluaran LLM gagal validasi (FR-37) |
| 429 | `rate_limited` / `daily_ceiling_reached` | FR-50 |
| 502 | `provider_unavailable` | LLM gagal/timeout |
| 500 | `internal_error` | — |

`correlationId` selalu dikembalikan dan selalu dicatat di log — tanpa body.

---

## 4. Endpoint

| Method | Path | Kewajiban |
|---|---|---|
| GET | `/healthz` | liveness, tanpa auth |
| POST | `/v1/sessions` | FR-01, FR-05 |
| DELETE | `/v1/sessions` | FR-05 |
| GET | `/v1/me` | FR-49 (status consent + waktu terakhir) |
| POST | `/v1/consents` | FR-03 (purpose, noticeVersion, decision) |
| GET | `/v1/consents` | FR-03/FR-49 (ledger) |
| PUT | `/v1/summaries/{monthId}` | FR-33 (simpan agregat + `inputRevision`) |
| GET | `/v1/summaries/{monthId}` | FR-02 |
| POST | `/v1/analyses` | FR-34…FR-41, FR-50 |
| GET | `/v1/analyses/{monthId}` | FR-40 |
| PUT | `/v1/budget-plans/{monthId}` | FR-42…FR-45 |
| GET | `/v1/budget-plans/{monthId}` | FR-45 |
| POST | `/v1/deletion-requests` | FR-47/FR-48 |
| GET | `/v1/deletion-requests/{id}` | FR-48 |

`monthId` = `YYYY-MM` (Asia/Jakarta).

### 4.1 `POST /v1/analyses` — endpoint inti

Request:
```json
{
  "monthId": "2026-02",
  "inputRevision": 7,
  "payload": { …AiAggregatePayload persis seperti domain/model/AiPayload.kt… }
}
```
Header `Idempotency-Key` wajib.

Alur server:
1. cek session → `ownerId`
2. cek consent `AI_PROCESSING` == GRANTED → selain itu `403 consent_required` (FR-34)
3. cek payload **hanya** berisi field pada allowlist `AiAggregatePayload.ALLOWED_FIELDS` →
   selain itu `400` (FR-35, NFR-19)
4. cek kuota: rate limit per user + daily ceiling (FR-50)
5. cek cache: `(ownerId, monthId, inputRevision, promptVersion, provider, model)` sudah ada →
   kembalikan yang tersimpan tanpa memanggil provider (FR-40)
6. panggil LLM dengan prompt berversi, `response_format=json_object`, max token dibatasi
7. validasi keluaran (FR-36/FR-37):
   - JSON valid & sesuai skema
   - setiap `metricKeys` ∈ `MetricKeys.all` + `category.<CODE>`
   - **setiap angka rupiah di dalam teks harus ada di payload**, kecuali ditandai usulan anggaran
   - tidak boleh mengandung instruksi/tool-call → tolak
   - gagal → **maksimal 1 regenerasi**, lalu `422`
8. simpan & balas

Respons `200`:
```json
{
  "promptVersion": "analysis-v1",
  "observations": [{ "text": "…", "metricKeys": ["summary.grossOutflow"] }],
  "suggestions": [{ "text": "…", "metricKeys": ["category.FOOD"], "categoryCode": "FOOD", "suggestedAmountMinor": 90000000 }],
  "limitations": ["…"],
  "promptTokens": 812,
  "completionTokens": 240
}
```
Bentuk ini **harus identik** dengan `RemoteAnalysisResponse` di
`app/src/main/java/com/pam/recap/data/ai/RemoteAnalysisEngine.kt`.

### 4.2 Consent
```json
POST /v1/consents
{ "purpose": "GMAIL_READ" | "AI_PROCESSING", "decision": "GRANTED" | "DECLINED" | "WITHDRAWN", "noticeVersion": "v1" }
```
Append-only. `WITHDRAWN` pada `AI_PROCESSING` langsung memblokir analisis berikutnya (FR-04/FR-41).

### 4.3 Deletion (FR-47/FR-48)
`POST /v1/deletion-requests` → `202 { "id", "state": "PENDING", "slaDays": 7 }`.
Worker menghapus agregat aktif ≤7 hari; backup kedaluwarsa ≤30 hari (NFR-15).
Selama ada request aktif, semua sync/analisis untuk owner itu ditolak `409 deletion_in_progress`.

---

## 5. Batas & kuota (FR-50)

| Batas | Default env |
|---|---|
| Analisis per user per jam | `RATE_LIMIT_ANALYSES_PER_HOUR=10` |
| Analisis per user per hari | `RATE_LIMIT_ANALYSES_PER_DAY=40` |
| Max output token | `LLM_MAX_OUTPUT_TOKENS=800` |
| Ceiling biaya AI harian (semua user) | `DAILY_AI_CEILING_USD=1.00` |
| Timeout panggilan LLM | `LLM_TIMEOUT_MS=45000` |

Melebihi batas ditolak **sebelum** provider dipanggil.

---

## 6. Penyimpanan

Tabel: `users`, `sessions`, `consents`, `summaries`, `analyses`, `budget_plans`,
`deletion_requests`, `usage_counters`.

- Payload agregat, hasil analisis dan rencana anggaran disimpan sebagai ciphertext
  AES-256-GCM (`DATA_ENCRYPTION_KEY`), dengan `keyVersion` agar bisa dirotasi.
- Setiap tabel transaksional membawa `owner_id`; **setiap** query memfilter `owner_id`
  (FR-02, NFR-17).
- Backup: `sqlite3 .backup` terjadwal; `DATA_ENCRYPTION_KEY` disimpan terpisah dari file DB.

---

## 7. Observability (NFR-18)

Log satu baris JSON per request:
```json
{ "ts":"…", "correlationId":"…", "method":"POST", "path":"/v1/analyses", "status":200, "ms":812, "ownerRef":"u_ab12…", "retry":0 }
```
`ownerRef` = hash pendek, bukan `sub` mentah. **Tidak ada** payload, nominal, kode kategori,
email, atau token di log.

---

## 8. Yang diuji

- verifikasi ID token: `aud` salah, `exp` lewat, `iss` salah, signature invalid → 401
- session: dibuat, dipakai, dicabut, token acak tidak diterima
- isolasi dua user: user B tidak bisa membaca objek user A → 403/404 (FR-02)
- consent gate: tanpa consent → 403; `WITHDRAWN` memblokir (FR-34/FR-04)
- allowlist payload: field terlarang (`subject`, `body`, `messageId`, `token`, `pan`) → 400
- LLM: keluaran valid diterima; JSON rusak, angka karangan, prompt-injection → ditolak; regenerasi ≤1
- cache: revision sama → provider tidak dipanggil dua kali (FR-40)
- kuota: melewati batas → 429 tanpa memanggil provider (FR-50)
- enkripsi: ciphertext di DB ≠ plaintext; dekripsi benar
- deletion: request aktif memblokir analisis, data terhapus
- log: tidak mengandung PII (scan)
