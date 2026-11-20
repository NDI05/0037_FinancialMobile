# SRS Change Record — v0.2 → v0.3

Document ID: PAM-SRS-001
Change ID: **CR-001**
Tanggal: 20 November 2026
Alasan: proyek harus berjalan **tanpa Google Cloud Platform** dan memakai **database cloud** untuk
sisi server.
Status: **disetujui owner** (D1–D6 di [`08_ROADMAP.md`](08_ROADMAP.md)).

Prosedur ini mengikuti §6.1 dan §6.4 dokumen SRS: setiap perubahan wajib menyebut requirement ID
yang terkena, dampak pengguna, konsekuensi integrasi/keamanan, dan pembaruan acceptance.

---

## 1. Ringkasan perubahan

| Jenis | Jumlah |
|---|---|
| Requirement diubah | 8 (FR-06, FR-07, FR-10, FR-11, FR-16, FR-18, NFR-03, NFR-14) |
| Requirement **dihapus** | 1 (FR-08) |
| Requirement **baru** | 6 (FR-51…FR-56) |
| Data rule diubah | 1 (DR-02) |
| Requirement **tidak berubah** dan tetap mengikat | FR-35, NFR-16, NFR-22, DR-05, BR-01…BR-06 |

---

## 2. Requirement yang diubah

### FR-06 — asosiasi mailbox
| | |
|---|---|
| **Lama** | "The system shall associate a Gmail mailbox with a user only after Google's OAuth authorization succeeds; entering an email address alone shall never establish mailbox access." |
| **Baru** | "The system shall associate a Gmail mailbox with a user only after a successful authenticated IMAP login using the user's own app password; entering an email address alone shall never establish mailbox access." |
| **Dampak** | tidak ada perubahan perilaku yang terlihat pengguna. |

### FR-07 — status koneksi
| | |
|---|---|
| **Lama** | Enam state dipetakan ke kondisi OAuth. |
| **Baru** | Enam state yang sama, dipetakan ke kondisi IMAP: `AUTHENTICATIONFAILED` ⇒ `REAUTHORIZATION_REQUIRED`; DNS/TLS/timeout ⇒ `UNAVAILABLE`; login OK ⇒ `CONNECTED`. |
| **Dampak** | teks bantuan berubah: "kata sandi aplikasi dicabut" menggantikan "izin OAuth dicabut". |

### FR-10 — konfigurasi tidak lengkap
| | |
|---|---|
| **Lama** | memblokir retrieval bila OAuth Google belum lengkap. |
| **Baru** | memblokir retrieval bila app password belum tersimpan atau gagal divalidasi. |
| **Berlaku** | **Fase 3 saja.** Selama Fase 2 mailbox masih fixture (`FixtureGmailSource`), tidak ada retrieval nyata yang perlu diblokir. |

### FR-11 — query sumber
| | |
|---|---|
| **Lama** | "query Gmail only for the configured Livin sender and approved notification subjects". |
| **Baru** | "…query the mailbox over IMAP with the allow-listed sender and subject criteria (Gmail `X-GM-RAW`), so that an email display name or body mention alone shall not make a message eligible." |
| **Dampak** | tetap; `X-GM-RAW` mempertahankan ekspresi pencarian yang setara. |

### FR-16 — pagination
| | |
|---|---|
| **Lama** | pagination Gmail API + ingestion buffer. |
| **Baru** | "…fetch matching messages in bounded UID batches with an ingestion buffer, then determine month membership from the parsed transaction timestamp in Asia/Jakarta rather than the received time." |

### FR-18 — deduplikasi
| | |
|---|---|
| **Lama** | hash Gmail message ID + fields. |
| **Baru** | "…per-user hash over the mailbox's account-wide message identifier (`X-GM-MSGID`) and transaction fields…" |
| **Dampak** | lebih stabil: `X-GM-MSGID` unik se-akun dan tidak berubah saat pesan dipindah folder. |

### NFR-03 — transport
| | |
|---|---|
| **Lama** | HTTPS + TLS 1.2 untuk Google API. |
| **Baru** | "…IMAP over TLS 1.2+ on port 993 with certificate and hostname verification; no cleartext IMAP." |

### NFR-14 — kredensial
| | |
|---|---|
| **Lama** | "Gmail access tokens shall be short-lived, held in memory where possible…" |
| **Baru** | "The Gmail app password is a long-lived credential. It shall be wrapped with an Android Keystore AES-GCM key, never written in plaintext, never transmitted to the PAM backend, and never written to logs, analytics or crash reports. It shall be cleared on sign out and on disconnect." |

---

## 3. Requirement yang dihapus

### FR-08 — DIHAPUS
> "The Android client shall obtain short-lived Gmail API access through Google's authorization flow,
> request only `gmail.readonly`, and never collect Google passwords or embed an OAuth client secret."

**Alasan:** berbenturan langsung dengan D1. App password **adalah** kredensial Google.
Digantikan FR-54 dan NFR-14 versi baru.

**Konsekuensi keamanan yang diterima owner (OI-13):**
- Least-privilege hilang: kredensial memberi akses **baca dan kirim (SMTP)**, bukan read-only.
- Kredensial berumur panjang; tidak kedaluwarsa otomatis, hanya dicabut manual oleh pengguna.
- Mitigasi: hanya perintah IMAP baca yang dipakai, `Folder.READ_ONLY`, vault Keystore,
  `FLAG_SECURE`, tidak pernah ke backend, dihapus saat sign out.
- Peringatan Google sendiri: *"app passwords aren't recommended"*.

---

## 3a. Pemetaan FR-08 → FR-54

FR-54 **adalah pengganti langsung FR-08**, bukan requirement tambahan yang tidak berkaitan:

| Aspek FR-08 yang dihapus | Digantikan oleh |
|---|---|
| "obtain short-lived Gmail API access through Google's authorization flow" | FR-54 (app password + validasi IMAP) |
| "request only `gmail.readonly`" | FR-55 (buka mailbox read-only, tanpa perintah mutasi) — **tapi jaminan least-privilege tingkat kredensial tidak bisa dipulihkan**, lihat OI-13 |
| "never embed an OAuth client secret" | tidak lagi relevan; tidak ada client secret |
| "never collect Google passwords" | **dibatalkan** oleh D1 |

## 4. Requirement baru

| ID | Requirement |
|---|---|
| **FR-51** | The system shall authenticate the user with a PAM account (email + password) hashed using a memory-hard KDF (scrypt, N=2^15, r=8, p=1, 16-byte salt). Passwords shall be 8–200 characters. Registration shall be invite-only, so no separate registration throttle is required. Password, salt and hash shall never be logged. |
| **FR-52** | The system shall allow the application to operate fully without a backend account, using a local profile and a local session, so offline use is preserved. |
| **FR-53** | The system shall rate-limit failed account-login attempts to 5 per 15 minutes per email+address-class, where address-class is IPv4 `/24` and IPv6 `/64` behind a trusted proxy header, and shall not disclose whether an email exists. Both real and unknown emails shall take the same time to reject. |
| **FR-54** | The system shall obtain mailbox credentials only from a user-entered Gmail app password, validate them with an IMAP login before storing, wrap them with an Android Keystore key, and clear them on sign out or disconnect. |
| **FR-55** | The system shall open the mailbox read-only and issue no IMAP command that mutates message or folder state. |
| **FR-56** | The system shall store consented monthly aggregates and reports in a managed cloud database, encrypted at rest and at field level, while transaction-level rows remain on the device. **Pending Fase 4** — Fase 2 belum menyentuh storage. |

---

## 5. Data rule yang diubah

### DR-02
| | |
|---|---|
| **Lama** | fingerprint dari Gmail message ID; sumber "Gmail body/header". |
| **Baru** | fingerprint dari HMAC(`X-GM-MSGID` + fields); sumber "IMAP body". Larangan menyimpan raw identifier tetap: `X-GM-MSGID` **tidak** disimpan, hanya turunan HMAC-nya. Kunci HMAC per-pengguna dibangkitkan sekali, dibungkus kunci Android Keystore (pola yang sama dengan passphrase SQLCipher), dan dihapus saat workspace dihapus. Rotasi kunci mengubah fingerprint sehingga baris lama tidak lagi cocok — karena itu rotasi dilarang tanpa migrasi ulang. |
| **Tidak berubah** | larangan menyimpan body, header penuh, PAN, terminal ID, lampiran. |

---

## 6. Yang **tidak** berubah (dan tetap mengikat)

Ini penting: alasan memilih opsi Hybrid adalah supaya requirement privasi tetap utuh.

| ID | Masih berlaku |
|---|---|
| FR-35 / NFR-19 | backend tidak menerima body, subject, sender, message id, token, PAN, nama merchant, atau baris transaksi |
| NFR-16 | database transaksi lokal terenkripsi, kunci di Keystore, dikecualikan dari backup |
| NFR-22 | dashboard tetap terbaca dari data lokal saat penyedia gagal |
| DR-05 | hanya agregat bulanan yang disetujui berada di backend |
| BR-01…BR-06 | aritmetika dan aturan bulan tidak berubah |
| FR-28…FR-33 | ringkasan deterministik tidak berubah |
| FR-34…FR-41, FR-50 | gerbang consent dan validasi analisis tidak berubah |

---

## 7. Dampak pengguna

| Sebelum | Sesudah |
|---|---|
| "Masuk dengan Google" | daftar/masuk dengan email + password |
| "Hubungkan Gmail" (persetujuan Google) | tempel **App Password** 16 karakter dari akun Google |
| tidak bisa dipakai tanpa internet setelah login | tetap bisa (tidak berubah) |
| — | pengguna wajib mengaktifkan 2FA di akun Google sebelum bisa membuat App Password |

**Panduan pengguna wajib diperbarui:** cara membuat App Password
(Akun Google → Keamanan → Verifikasi 2 Langkah → Sandi aplikasi), dan cara mencabutnya.

---

## 8. Pembaruan acceptance

| Suite | Perubahan |
|---|---|
| **T01** Identity & consent | tambah: registrasi/login/logout, scrypt, rate limit, akun lokal tanpa backend |
| **T02** Google authorization | **diganti** menjadi *IMAP authorization*: login valid, app password salah, app password dicabut, TLS wajib, kredensial tidak ada di log/prefs. **Fase 2 hanya menutup kasus "tidak ada kredensial"; empat kasus IMAP lainnya menunggu Fase 3.** |
| **T03** Livin source validation | fixture yang sama; ditambah kasus "app password hanya-baca" dan X-GM-RAW search |
| **T04** Import integrity | sumber pesan via IMAP; `X-GM-MSGID` HMAC; batching UID |
| **T08** Privacy & operations | ditambah: isi database cloud tidak memuat plaintext transaksi |

AC01, AC02, AC03 harus ditulis ulang mengikuti alur baru.

---

## 9. Open issues baru

| ID | Isu | Owner | Gate |
|---|---|---|---|
| **OI-13** | Risiko App Password (akses kirim, kredensial panjang) **diterima**; mitigasi FR-54/FR-55 wajib diverifikasi | owner + security reviewer | G2 |
| **OI-14** | Google dapat menghentikan App Password kapan saja; rencana keluar: kembali ke OAuth bila perlu | Android lead | G3 |
| **OI-15** | Turso free tier: kuota, backup, dan jalur pindah ke Postgres. "Portabel" berarti **dialek SQL tetap kompatibel** dan hanya driver klien yang diganti di balik lapisan store; migrasi ke Postgres bukan sekadar tukar driver karena tipe dan klausa `ON CONFLICT` berbeda | backend lead | G2 |
| **OI-16** | Pengguna tanpa 2FA tidak bisa memakai fitur import email; harus dijelaskan di panduan | owner | G2 |

---

## 10. Dampak traceability

- Matrix §6.3: baris T02 diganti judul dan cakupannya.
- Appendix C: OI-13…OI-16 ditambahkan; OI-01 (GCP), OI-04 (restricted scope), OI-06 (MIME) di-**tutup** atau disesuaikan karena OAuth tidak lagi dipakai.
- `docs/05_REAL_GOOGLE_SETUP.md` menjadi **usang**; digantikan panduan App Password.

---

## 10a. Amandemen setelah implementasi Fase 2

Delapan klarifikasi ditambahkan setelah Fase 2 dikerjakan, semuanya ditemukan oleh SE saat
menerjemahkan change record ini menjadi kode:

1. FR-10 ditandai berlaku Fase 3 saja.
2. T02 menyatakan empat kasus IMAP ditunda ke Fase 3.
3. FR-53 mendefinisikan address-class (IPv4 `/24`, IPv6 `/64`) dan kesetaraan waktu respons.
4. FR-51 menambahkan kebijakan panjang password dan sifat invite-only registrasi.
5. DR-02 menetapkan siklus hidup kunci HMAC fingerprint.
6. FR-56 ditandai *pending Fase 4*.
7. OI-15 menjelaskan arti portabilitas dan mengapa pindah ke Postgres bukan tukar driver.
8. Pemetaan eksplisit FR-08 → FR-54/FR-55 ditambahkan di §3a.

## 11. Sign-off

| Peran | Nama | Keputusan |
|---|---|---|
| Owner | Andhika Pratama | disetujui (D1–D6) |
| Lead Developer | — | implementasi sesuai `08_ROADMAP.md` |

Versi dokumen SRS dinaikkan ke **v0.3 draft**; perubahan ini menjadi baseline baru setelah
Fase 1 selesai.
