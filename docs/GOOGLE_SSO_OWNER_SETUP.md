# Setup Google SSO untuk pemilik — PAM Android

Dokumen ini adalah langkah milik **pemilik** untuk mengaktifkan tombol "Masuk dengan Google" dan
kartu "Tautkan Google" di aplikasi PAM. Tidak ada secret, client ID asli, atau data akun yang
disimpan di repo: nilai diisi lewat build property dan environment server.

Status implementasi Android: **IMPLEMENTED (fixture/unit)** di branch `feat/c2-android`
(commit `e67380a`). UAT perangkat nyata dengan akun Google betulan **NOT RUN** — menunggu langkah
1–5 di bawah dikerjakan pemilik. Tanpa client ID, fitur otomatis tersembunyi dan aplikasi berjalan
seperti sebelumnya (login email/PAM tetap normal).

Kontrak backend yang dipakai Android (lihat task SSO): `GET /v1/auth/google/nonce` (anonim),
`POST /v1/auth/google` (anonim), `POST /v1/auth/google/link` (Bearer), dan flag opsional
`googleLinked` pada `GET /v1/me`. Saat SSO dimatikan server menjawab `503 google_sso_disabled`.

## 1. Identitas aplikasi untuk Google Cloud Console

Nilai yang **wajib** dipakai saat membuat OAuth client Android (jangan menebak):

| Item | Nilai |
|---|---|
| Package name (debug) | `com.pam.recap.debug` |
| SHA-1 fingerprint keystore debug | `FE:38:71:AB:6A:DC:D2:E5:B3:68:96:DE:59:84:D3:08:5F:27:C6:30` |

SHA-1 dihitung dari keystore yang benar-benar dipakai build debug repo ini
(`app/keystore/debug.keystore`, alias `androiddebugkey`). Perintah verifikasi ulang:

```bash
# keytool dari JDK bawaan Android Studio (jbr) atau JDK 21 mana pun.
"$JAVA_HOME/bin/keytool" -list -v \
  -keystore app/keystore/debug.keystore \
  -alias androiddebugkey \
  -storepass android -keypass android \
  | grep -E "Alias name|SHA1:"
```

Kalau build memakai keystore lain (misalnya keystore release untuk uji instalasi produksi),
SHA-1 harus dihitung dari keystore itu dan client ID Android tambahan didaftarkan. Untuk build
`release`, package-nya `com.pam.recap` (tanpa suffix `.debug`).

## 2. Buat OAuth client di Google Cloud Console

Pakai project Google Cloud yang sudah ada untuk PAM. **Jangan** mengaktifkan Cloud Identity
Platform, Pub/Sub berbayar, atau layanan berbayar lain untuk langkah ini; SSO hanya butuh OAuth
consent screen + dua OAuth client.

1. **OAuth consent screen**: tipe *External*, status *Testing* sudah cukup untuk UAT. Tambahkan
   akun Google penguji ke daftar *Test users* (langkah 5).
2. **Create credentials → OAuth client ID → Android**:
   - Package name: `com.pam.recap.debug`
   - SHA-1: nilai pada tabel di atas
   - Simpan; client ID Android ini **tidak** diisi ke Android/build property. Ia hanya dipakai
     Google untuk memvalidasi aplikasi yang meminta kredensial.
3. **Create credentials → OAuth client ID → Web application**:
   - Beri nama misalnya `PAM backend SSO`.
   - Client ID **Web** inilah yang dipakai sebagai *audience*: dipasang di Android sebagai
     `PAM_GOOGLE_SSO_CLIENT_ID` **dan** diverifikasi backend.
   - Client secret Web **tidak** dipakai Android. Jangan pernah menaruhnya di repo, APK, atau
     dokumen ini.

Catat hanya **Web client ID** (formatnya berakhiran `.apps.googleusercontent.com`). Jangan
menempelkannya di chat/laporan publik; simpan di password manager pemilik.

## 3. Aktifkan di build Android

Client ID dibaca berurutan: `-P` Gradle → `gradle.properties` → `local.properties` → variabel
environment `PAM_GOOGLE_SSO_CLIENT_ID`. Gunakan `local.properties` (sudah gitignored) supaya build
lokal pemilik tidak mengubah repo:

```properties
# local.properties
PAM_GOOGLE_SSO_CLIENT_ID=<WEB_CLIENT_ID>.apps.googleusercontent.com
```

Atau sekali jalan:

```bash
PAM_JDK_HOME=$HOME/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home \
  ./scripts/build.sh :app:assembleDebug \
  -PPAM_GOOGLE_SSO_CLIENT_ID=<WEB_CLIENT_ID>.apps.googleusercontent.com
```

Alternatif CI/env tanpa menyentuh file:

```bash
PAM_GOOGLE_SSO_CLIENT_ID=<WEB_CLIENT_ID>.apps.googleusercontent.com ./scripts/build.sh :app:assembleDebug
```

Bentuk `BuildConfig` yang dihasilkan: `PAM_GOOGLE_SSO_CLIENT_ID` berisi string di atas dan
`PAM_GOOGLE_SSO_ENABLED` bernilai `true` bila string tidak kosong. Dengan nilai kosong
(default repo), tombol Google dan kartu penautan tidak dirender sama sekali.

Setelah build, cek efektif tanpa membuka source:

```bash
unzip -p app/build/outputs/apk/debug/app-debug.apk classes.dex | strings | grep -c "<WEB_CLIENT_ID_prefix>"
```

Hanya untuk memastikan nilainya masuk; client ID bukan secret, tetapi tetap jangan
dipublikasikan.

## 4. Aktifkan di backend (prasyarat tombol muncul berfungsi)

Backend harus memverifikasi ID token Google terhadap audience Web client ID yang sama, lalu
menerbitkan sesi PAM seperti login email/password. Nama variabel environment persisnya mengikuti
README repo `0037-Backend-Financial` (lane backend); yang Android butuhkan hanyalah perilaku
kontrak:

- `GET /v1/auth/google/nonce` → `200 {"nonce","expiresAt","expiresInSeconds"}` saat aktif,
  `503 {"code":"google_sso_disabled"}` saat nonaktif.
- `POST /v1/auth/google` → `200` sesi yang identik dengan `POST /v1/sessions` + `googleLinked:true`.
- `POST /v1/auth/google/link` → `200 {"linked":true,"googleLinked":true}` untuk akun yang sudah
  login email/PAM; `409 google_identity_conflict` / `409 google_already_linked` bila bentrok.
- `GET /v1/me` boleh menyertakan `googleLinked` (opsional; Android juga menyimpan flag lokal
  per owner).

Verifikasi cepat dari mesin backend:

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$PAM_BACKEND_BASE_URL/v1/auth/google/nonce"
# 200 = aktif, 503 = masih mati (google_sso_disabled)
```

Pemilik memegang keputusan menyalakan/mematikan flag ini; mematikan flag tidak merusak akun yang
sudah tertaut — login email/PAM tetap bisa dipakai.

## 5. Tambahkan akun Google penguji

1. Di OAuth consent screen, daftarkan alamat Gmail penguji sebagai *Test user*.
2. Untuk alur **masuk**: akun Google penguji harus sudah dikaitkan ke akun PAM di server
   (biasanya lewat lane backend/admin: pemetaan `google_identity` → `ownerId`). Bila belum
   terkait, aplikasi menampilkan pesan "Akun PAM belum tertaut ke Google. Masuk dengan email PAM
   lalu tautkan Google." — itu perilaku yang benar, bukan error.
3. Untuk alur **penautan**: login dulu dengan email/PAM, buka **Akun**, tekan **Tautkan Google**,
   pilih akun Google penguji. Kartu berubah menjadi "Google tertaut".
4. Emulator: pastikan emulator punya Google Play Services dan akun Google penguji ditambahkan di
   **Settings → Accounts**. Backend untuk emulator memakai
   `PAM_BACKEND_BASE_URL=http://10.0.2.2:18090` di `local.properties` (lihat README).

## 6. Checklist UAT pemilik (belum dijalankan)

| # | Langkah | Hasil yang diharapkan |
|---|---|---|
| 1 | Build debug dengan `PAM_GOOGLE_SSO_CLIENT_ID` terisi, buka layar Akun | Tombol "Masuk dengan Google" tampil di atas form email; form tetap terlihat |
| 2 | Tekan tombol Google, pilih akun penguji yang sudah tertaut | Masuk ke aplikasi; sesi PAM tersimpan; tidak ada ID token di log |
| 3 | Buka dialog Google lalu batalkan | Tetap di layar Akun, tanpa banner error |
| 4 | Login email/PAM pada akun yang belum tertaut, tekan Tautkan Google | Kartu berubah "Google tertaut"; data/transaksi/Gmail tidak berubah |
| 5 | Ulangi langkah 2 setelah login SSO | Picker akun Google **selalu** tampil (by design: `autoSelect=false`, tidak ada pemilihan senyap); pilih akun yang sama, lalu masuk tanpa kata sandi. Akun yang sudah pernah dipakai muncul di daftar |
| 6 | Build tanpa client ID, buka layar Akun | Tombol Google tidak ada; login email/PAM tetap normal |
| 7 | Akun tanpa Gmail tertaut setelah login | Sheet "Tautkan Gmail untuk impor Livin" muncul; "Lewati" diingat per owner |
| 8 | Login akun owner lain di perangkat yang sama | Sheet onboarding muncul lagi untuk owner tersebut |

Hasil aktual: **NOT RUN** per 24 November 2026 — butuh client ID, SHA-1 terdaftar, backend SSO
aktif, dan akun penguji milik pemilik.

## 7. Kalau ada masalah

- **Tombol Google tidak muncul**: `PAM_GOOGLE_SSO_ENABLED` false (string kosong) atau
  `GoogleSignInGateway.isAvailable` false. Cek `local.properties`/`-P` lalu rebuild bersih.
- **"Google Play Services tidak tersedia"**: emulator tanpa Play Services atau akun belum
  ditambahkan.
- **"Login Google belum bisa dipakai"** (`google_invalid_token`): audience/klien tidak cocok atau
  nonce kedaluwarsa; ulangi sekali, lalu cocokkan Web client ID backend vs build.
- **"Akun PAM belum tertaut ke Google"** (`google_identity_not_linked`): akun belum dipetakan di
  server — lanjutkan dengan email/PAM lalu tautkan.
- **"Akun ini tidak aktif"** (`account_inactive`): status akun di server SUSPENDED/INACTIVE;
  hubungi admin.
- **"Google sudah tertaut ke akun lain"** (`google_identity_conflict`): identitas Google itu sudah
  dipakai owner lain; sesi PAM saat ini tidak berubah.
- **`503 google_sso_disabled`**: flag backend masih mati.

## 8. Batas dan keamanan

- Aplikasi tidak pernah memegang client secret, tidak menulis ID token ke log/crash/analytics,
  dan tidak mengirim token ke selain backend PAM.
- Nonce diterbitkan backend, sekali pakai, dan diminta ulang setiap percobaan.
- Fitur ini tidak memerlukan layanan Google berbayar. Jangan menautkan billing atau menyalakan
  Pub/Sub/Identity Platform untuk aktivasi SSO.
- Mematikan SSO (kosongkan build property dan/atau flag backend) mengembalikan aplikasi ke alur
  email/PAM tanpa perubahan data.
