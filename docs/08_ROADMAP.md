# PAM — Roadmap Pasca-Keputusan (tanpa GCP, database cloud)

> **Rencana aktif tambahan — 23 November 2026:** pemilik menyetujui auto-sync IMAP target
> 30 menit, batch kategorisasi target 2 jam, dan indikator status top bar/panel.
> Lihat [rencana delivery](plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md) dan
> [CR-005 DRAF SRS](changes/CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md).
> Background sync yang disebut deferred di roadmap v0.3 di bawah kini masuk perencanaan;
> isi v0.3 lainnya adalah baseline historis, bukan gambaran stack/runtime terbaru.
> Implementasi baru PLANNED; **SRS resmi tidak direvisi sekarang** sesuai instruksi pemilik.

Status: **disetujui owner**, 20 November 2026.
Perubahan ini menggeser arsitektur dari OAuth Google ke IMAP, dan menambah database cloud
untuk sisi server. Alasan, konsekuensi dan persetujuan tercatat di
[`09_SRS_CHANGE_v0.3.md`](09_SRS_CHANGE_v0.3.md).

> **Status 21 November 2026.** Dokumen ini adalah roadmap v0.3 yang sudah berjalan. Rancangan v0.4
> (Turso/libSQL untuk transaksi server-side, JWT/refresh, peran, lima CRUDS, lampiran, cutover)
> ada di [`10_MIGRASI_PEDOMAN.md`](10_MIGRASI_PEDOMAN.md) dan
> [`11_SRS_CHANGE_v0.4.md`](11_SRS_CHANGE_v0.4.md). Paket kerja: B kontrak (selesai review) →
> C1 backend → C2 Android → D kelengkapan/cutover → E pembuktian.

---

## 1. Keputusan yang dikunci

| # | Keputusan | Alasan |
|---|---|---|
| D1 | Sumber email: **IMAP Gmail + App Password** (bukan OAuth) | lepas penuh dari GCP |
| D2 | Identitas: **akun lokal** (email + password, scrypt) — **Google Sign-In dihapus** | Google Sign-In juga butuh OAuth client dari GCP |
| D3 | Database: **Hybrid** — transaksi tetap lokal terenkripsi, agregat/analisis/consent/plan di **cloud** | mempertahankan FR-35, NFR-22, dan offline |
| D4 | Database cloud: **Turso** (SQLite/libSQL, 9 GB gratis, tidak pernah pause) | migrasi paling kecil dari `node:sqlite` |
| D5 | Risiko App Password (akses kirim) **diterima owner** | dicatat sebagai OI-13 |
| D6 | Biaya total tetap **Rp0** | tidak ada layanan berbayar |

---

## 2. Arsitektur target

```
┌──────────────── Android ────────────────┐        ┌──────── Backend (Node) ────────┐
│                                         │        │                                │
│  Profile lokal (email)                  │        │  /v1/accounts  (scrypt)        │
│  Session lokal (FR-01/FR-05)            │◄──────►│  /v1/sessions  (opaque token)  │
│                                         │  HTTPS │  /v1/consents                  │
│  AppPasswordVault ── Keystore AES-GCM   │        │  /v1/summaries   ──┐           │
│         │                               │        │  /v1/analyses      │           │
│         ▼                               │        │  /v1/budget-plans  ├──► Turso  │
│  ImapGmailSource ── IMAPS 993 ──────────┼───────►│  /v1/deletions     │   (cloud)  │
│         │                               │  Gmail │                    ─┘           │
│         ▼                               │        │  LLM: Ollama / Groq            │
│  Parser Livin (tidak berubah)           │        └────────────────────────────────┘
│         │                               │
│         ▼                               │
│  Room + SQLCipher (transaksi)  ← tetap lokal, sesuai FR-35/DR-05
└─────────────────────────────────────────┘
```

**Batas privasi tidak berubah:** baris transaksi, merchant, catatan pribadi dan kredensial email
**tidak pernah** meninggalkan perangkat. Yang ke cloud hanya agregat bulanan yang disetujui.

---

## 3. Fase

| Fase | Deliverable | Acceptance | Usaha |
|---|---|---|---|
| **1** | SRS v0.3 change record + `docs/09` | impact review lengkap, tidak ada requirement yatim | ✅ dokumen ini |
| **2** | Auth backend sendiri + akun lokal app | daftar/login/logout; scrypt; rate limit login; app tetap jalan tanpa backend | ±1 sesi |
| **3** | `ImapGmailSource` + `AppPasswordVault` | login IMAP nyata, `EXAMINE` read-only, `X-GM-RAW` search, MIME text, tanpa lampiran; kredensial Keystore-wrapped | ±2 sesi |
| **4** | Turso menggantikan SQLite | store layer async, semua 97 test hijau, ciphertext tetap utuh | ±2 sesi |
| **5** | Backup harian + deploy gratis | dump terjadwal, restore drill, RPO 24 jam (NFR-23) | ±1 sesi |
| **6** | Keystore release + APK bertanda tangan | `assembleRelease` ter-sign, install di perangkat | ±1 sesi |

Urutan wajib: 2 → 3 (keduanya menyentuh auth aplikasi), lalu 4 → 5.

---

## 4. Detail teknis per fase

### Fase 2 — Auth
- `POST /v1/accounts` (registrasi, butuh `REGISTRATION_INVITE_CODE`), `POST /v1/sessions`
  (email + password), `DELETE /v1/sessions`.
- Hash: **`node:crypto.scrypt`** (N=2^15, r=8, p=1, salt 16 B) — tanpa dependensi native.
- Rate limit login: 5 percobaan / 15 menit / IP+email, lalu backoff.
- Aplikasi: `LocalAccountSource` menggantikan `GoogleIdentitySource`.
  Tanpa backend, akun tetap lokal (DataStore) supaya offline utuh.
- Hapus: `androidx.credentials`, `googleid`, `play-services-auth`, `GoogleAuthBridge`,
  `ActivityHolder` (bila tak terpakai lagi), `PAM_GOOGLE_WEB_CLIENT_ID`.

### Fase 3 — IMAP
- Library: `com.sun.mail:android-mail:1.6.8` (+ `android-activation`).
- `imaps://imap.gmail.com:993`, `Folder.READ_ONLY` (`EXAMINE`), TLS wajib.
- Search: `X-GM-RAW` dengan allow-list sender/subject yang sudah ada di template registry.
- Identitas pesan: `X-GM-MSGID` → **HMAC per-user** jadi fingerprint (SRS melarang menyimpan ID mentah).
- Body: hanya `text/plain`/`text/html`, lampiran diabaikan (FR-14).
- Error → status koneksi FR-07 yang sudah ada (`AUTHENTICATIONFAILED` ⇒ `REAUTHORIZATION_REQUIRED`).
- Kredensial: `AppPasswordVault` — AES-GCM via Keystore, tidak pernah ke backend, dihapus saat sign out.

### Fase 4 — Turso
- Ganti `node:sqlite` (`DatabaseSync`, sinkron) dengan `@libsql/client` (async).
- Semua method store jadi `async`; route sudah `async`.
- Migrasi SQL hampir identik; tabel `usage_counters` dibuat ulang.
- Enkripsi per-field tetap di aplikasi backend, jadi Turso hanya menyimpan ciphertext.

---

## 5. Risiko

| Risiko | Dampak | Mitigasi |
|---|---|---|
| **App Password bisa kirim email** | kredensial lebih kuat dari yang dibutuhkan | hanya IMAP read-only dipakai; vault Keystore; `FLAG_SECURE`; dihapus saat sign out |
| Google mencabut dukungan App Password | jalur mati di masa depan | mapping error jelas; OI-14 |
| `X-GM-RAW` bukan standar IMAP | vendor lock ke Gmail | sudah terkunci ke Gmail by scope, tidak menambah |
| Turso free tier berubah | biaya mendadak | dump harian + restore drill; bisa pindah ke Neon |
| Regresi privasi saat Fase 4 | data bocor ke cloud | test `encryption.test.ts` diperluas: isi Turso tidak boleh memuat plaintext |
| Model lokal gagal JSON | 422 saat demo | fallback on-device sudah ada; Groq sebagai cadangan |

---

## 6. Rollback

| Fase | Cara kembali |
|---|---|
| 2 | bind `GoogleIdentitySource` lagi; tabel `accounts` diabaikan |
| 3 | bind `FixtureGmailSource` (dummy) atau pulihkan `GoogleGmailSource` dari git |
| 4 | `DATABASE_PATH` ke SQLite; store layer dipilih lewat config |

Setiap fase adalah commit terpisah sehingga rollback = revert satu commit.

---

## 7. Di luar cakupan

- Multi-device sync transaksi (opsi C ditunda).
- Export CSV/PDF, reminder, background sync (tetap deferred, OI-12).
- Login sosial apa pun.
- Aplikasi iOS/web.
