# Menyiapkan Google sungguhan untuk PAM

Dokumen ini adalah daftar lengkap hal yang **harus Anda siapkan** sebelum aplikasi bisa keluar
dari demo mode dan membaca Gmail sungguhan. Semua langkah di sini dilakukan di akun Google Anda
sendiri; tidak ada satu pun yang bisa saya kerjakan dari sisi kode.

> Ringkasnya: **1 project Google Cloud, 2 OAuth client (Android + Web), 1 consent screen dengan
> scope `gmail.readonly`, dan 1 privacy policy URL.** Sisanya konfigurasi build.

> **Soal biaya:** langkah-langkah di dokumen ini **tidak memerlukan billing, kartu kredit, atau
> program Free Trial $300.** Yang meminta verifikasi pembayaran adalah *Free Trial*, dan itu tidak
> dipakai di sini. Lihat [`07_FREE_STACK.md`](07_FREE_STACK.md) untuk jalur 100% gratis.

---

## 0. Ringkasan yang paling sering bikin gagal

Empat hal ini yang paling sering membuat "Sign in with Google" gagal tanpa pesan jelas:

| Penyebab | Gejala |
|---|---|
| **Package name salah** — debug build memakai suffix `.debug` | `DEVELOPER_ERROR` (10) di `AuthorizationClient` |
| **SHA-1 belum didaftarkan** untuk package itu | `DEVELOPER_ERROR` (10) |
| **Memakai Web client ID sebagai Android client**, atau sebaliknya | `DEVELOPER_ERROR` atau ID token `aud` mismatch |
| **Akun belum masuk daftar test user** saat consent screen masih *Testing* | `access_denied` / "app not verified" |

PAM butuh **dua** OAuth client dengan peran berbeda, dan ini tidak bisa digabung:

| Client | Dipakai untuk | Butuh |
|---|---|---|
| **Android** | `AuthorizationClient` mengambil access token Gmail (`gmail.readonly`) | package name + SHA-1 |
| **Web** | `GetGoogleIdOption.serverClientId` (ID token login) **dan** `aud` yang diverifikasi backend | hanya client ID, tanpa secret di APK |

---

## 1. Yang harus Anda siapkan (checklist)

### A. Akun & aset
- [ ] Akun Google yang akan menjadi **pemilik project** (sebaiknya akun khusus, bukan akun
      pribadi yang berisi email bank sungguhan).
- [ ] Akun Gmail **uji** berisi notifikasi Livin by Mandiri, terpisah dari akun asli.
      Minimal satu notifikasi pembayaran QRIS yang berhasil — template inilah satu-satunya yang
      parser-nya sudah tervalidasi (OI-02). Tanpa ini, import akan selalu 0 hasil.
- [ ] **Privacy policy URL** yang publik (Google Docs/Notion yang di-*publish* cukup). Wajib
      untuk consent screen.
- [ ] (Opsional, untuk publikasi) URL homepage dan logo aplikasi.

### B. Google Cloud project
1. Buka <https://console.cloud.google.com/projectcreate> → buat project, misal `pam-recap`.
2. **APIs & Services → Library → Gmail API → Enable.**
   Hanya Gmail API yang perlu. Jangan aktifkan Drive/Calendar.

### C. OAuth consent screen
**APIs & Services → OAuth consent screen**

- User type: **External** (kecuali Anda punya Google Workspace, boleh Internal).
- App name: `PAM`, user support email, developer contact email.
- **Scopes** → Add or remove scopes → tambahkan manual:
  ```
  https://www.googleapis.com/auth/gmail.readonly
  ```
  Ini **restricted scope**. Google akan menandainya dan meminta justifikasi.
- Authorized domains: domain dari privacy policy Anda.
- **Publishing status**:

| Status | Cocok untuk | Konsekuensi |
|---|---|---|
| **Testing** | development, demo skripsi | Maks **100 test user**. Access token tetap bekerja normal. |
| **In production** | rilis ke pengguna nyata | Butuh **OAuth verification**, dan karena `gmail.readonly` restricted → bisa diminta **CASA Tier 2 security assessment** (berbayar, audit tahunan) |

> **Untuk keperluan tugas ini, tetap di *Testing* dan daftarkan akun uji Anda di
> *Test users*. Itu sah, legal, dan tidak butuh verifikasi.**

- [ ] Tambahkan akun Gmail uji di **Test users**.

### D. Dua OAuth client
**APIs & Services → Credentials → Create credentials**

**D1. Android client** (untuk Gmail access token)

Daftarkan **dua kali**, satu per package:

| Nama | Package name | SHA-1 |
|---|---|---|
| `PAM Android debug` | `com.pam.recap.debug` | `FE:38:71:AB:6A:DC:D2:E5:B3:68:96:DE:59:84:D3:08:5F:27:C6:30` |
| `PAM Android release` | `com.pam.recap` | SHA-1 keystore release Anda (lihat §3) |

> SHA-1 di atas adalah keystore debug yang **dipin di dalam project ini**
> (`app/keystore/debug.keystore`), bukan keystore debug bawaan Android Studio. Kalau Anda
> membangun dari mesin lain dengan keystore berbeda, daftarkan SHA-1 mesin itu juga.

**D2. Web client** (untuk ID token login + `aud` backend)

- Type: **Web application**. Nama: `PAM backend`.
- Tidak perlu menambahkan Authorized redirect URI untuk alur Android native.
- **Simpan Client ID-nya** — bentuknya `xxxxxxxx.apps.googleusercontent.com`. Inilah nilai
  `PAM_GOOGLE_WEB_CLIENT_ID` dan `GOOGLE_WEB_CLIENT_ID` di backend.

### E. Batas & kuota yang perlu Anda tahu
- Gmail API kuota per-user: 250 unit/detik; `users.messages.list` = 5 unit, `messages.get` = 5.
- Import 500 pesan ≈ 2.500 unit → aman, tapi tetap ada retry/backoff (NFR-05).
- Restricted scope review bisa memakan waktu berminggu-minggu. Jangan menunggu verifikasi untuk
  mulai development.

---

## 2. Angka yang harus cocok persis

`Application ID` di project ini **berbeda antara debug dan release**:

```
app/build.gradle.kts
  namespace      = "com.pam.recap"
  applicationId  = "com.pam.recap"
  debug          → applicationIdSuffix = ".debug"   →  com.pam.recap.debug
```

Jadi:

| Build | Package yang didaftarkan |
|---|---|
| `./scripts/build.sh :app:assembleDebug` | `com.pam.recap.debug` |
| `./scripts/build.sh :app:assembleRelease` | `com.pam.recap` |

Kalau Anda hanya mendaftarkan satu, build yang lain akan gagal dengan `DEVELOPER_ERROR`.

---

## 3. Mengambil SHA-1

**Keystore debug project ini** (sudah dihitung, tinggal pakai):

```bash
cd pam-app
JAVA_HOME=$PWD/.toolchain/jdk-21.0.13.1+2/Contents/Home \
  "$JAVA_HOME/bin/keytool" -list -v \
  -keystore app/keystore/debug.keystore -storepass android -alias androiddebugkey \
  | grep SHA1
# SHA1: FE:38:71:AB:6A:DC:D2:E5:B3:68:96:DE:59:84:D3:08:5F:27:C6:30
```

**Keystore release** (belum ada di project — lihat §4), setelah Anda membuatnya:

```bash
keytool -list -v -keystore pam-release.keystore -alias pam | grep SHA1
```

**Kalau nanti dipasang lewat Google Play**, Google Play App Signing akan mengganti sertifikat.
Ambil SHA-1 dari **Play Console → Setup → App signing**, bukan dari keystore lokal Anda.

---

## 4. Keystore release (perlu Anda buat & simpan)

Project ini hanya punya keystore debug. Untuk build release yang bisa dipasang:

```bash
cd pam-app
keytool -genkeypair -v \
  -keystore app/keystore/release.keystore \
  -alias pam -keyalg RSA -keysize 2048 -validity 10000
```

Lalu isi `app/keystore/release.properties` (jangan di-commit):

```properties
storeFile=release.keystore
storePassword=…
keyAlias=pam
keyPassword=…
```

> Saya belum membuatkan ini karena password-nya harus Anda pegang sendiri, bukan saya.
> Beri tahu saya kalau mau saya sambungkan `signingConfigs.release` ke file itu.

---

## 5. Menyalakan jalur Google di aplikasi

Setelah D2 selesai:

```bash
cd pam-app
./scripts/build.sh :app:assembleDebug \
  -PPAM_GOOGLE_WEB_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
```

Efeknya:
- `BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID` terisi → DI graph memilih **`GoogleGmailSource`**
  (OAuth + Gmail REST sungguhan) alih-alih `FixtureGmailSource`.
- `GetGoogleIdOption.serverClientId` memakai client ID itu untuk ID token.

Tanpa properti itu, aplikasi tetap berjalan di demo mode. Ini sengaja (OI-01).

---

## 6. Yang saya butuhkan dari Anda untuk backend

Cukup isi `.env` di `pam-backend/` (template `.env.example` sudah saya siapkan):

| Variabel | Dari mana | Rahasia? |
|---|---|---|
| `GOOGLE_WEB_CLIENT_ID` | D2 di atas | tidak |
| `LLM_API_KEY` | OpenAI **atau** OpenRouter | **ya** |
| `LLM_BASE_URL` | `https://api.openai.com/v1` atau `https://openrouter.ai/api/v1` | tidak |
| `LLM_MODEL` | mis. `gpt-4o-mini` / `openai/gpt-4o-mini` | tidak |
| `SESSION_PEPPER` | `openssl rand -hex 32` | **ya** |
| `DATA_ENCRYPTION_KEY` | `openssl rand -base64 32` | **ya** |
| `DAILY_AI_CEILING_USD` | mis. `1.00` | tidak |

Anda **tidak perlu** memberi saya API key apa pun. Saya membangun dan menguji backend dengan
LLM palsu (deterministik); Anda cukup mengisi key asli di `.env` saat mau memakai model asli.

---

## 7. Urutan pengerjaan yang saya sarankan

1. **B (enable Gmail API)** dan **C (consent screen + test user)** — 10 menit, tanpa ini tidak ada
   yang jalan.
2. **D1 debug client** dengan SHA-1 di atas → langsung bisa `assembleDebug` + login Gmail.
3. Isi `.env` backend → jalankan backend → uji analisis dari aplikasi.
4. **D2 Web client** → baru login ID token + verifikasi backend aktif.
5. **D1 release client** + keystore release → build release.

Langkah 1–2 sudah cukup untuk membuktikan import email sungguhan bekerja. Langkah 4 baru
dibutuhkan saat analisis dikirim ke backend.

---

## 8. Kalau macet: arti kode error

| Kode | Arti | Perbaikan |
|---|---|---|
| `10 DEVELOPER_ERROR` | package/SHA-1 tidak cocok, atau memakai client ID tipe salah | ulangi §D1, cek `applicationIdSuffix` |
| `12500` / `12501` | login dibatalkan user | bukan bug |
| `7 NETWORK_ERROR` | Play Services tidak tersedia (emulator tanpa Play Store) | pakai image *google_apis_playstore* atau HP asli |
| `access_denied` | akun bukan test user, atau consent screen belum diisi | §C |
| `invalid_grant` | token kedaluwarsa/dicabut | panggil ulang `authorize()` |
| Backend `401` | ID token `aud` ≠ `GOOGLE_WEB_CLIENT_ID` | samakan nilainya |
| Backend `403 consent_required` | consent AI belum tercatat di server | kirim `POST /v1/consents` lebih dulu |

---

## 9. Batasan yang tetap berlaku setelah semua ini siap

Supaya ekspektasinya benar, ini **tidak** akan berubah walaupun Google sudah aktif:

- Aplikasi tetap menampilkan **angka dari email**, bukan mutasi rekening. `Complete` hanya berarti
  "semua halaman query Gmail sudah diproses".
- **Tidak ada saldo**, tidak ada pergerakan uang, tidak ada API bank.
- Template **Transfer Masuk belum tervalidasi** (OI-03) → pemasukan tetap manual.
- Lampiran dan statement berpassword tidak diproses.
- Baru **satu mailbox** per pengguna, hanya Livin by Mandiri, hanya IDR.
