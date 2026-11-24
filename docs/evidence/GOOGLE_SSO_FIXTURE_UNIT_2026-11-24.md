# BUKTI SSO FIXTURE/UNIT — Google Sign-In, Penautan Akun, Onboarding Gmail

- **Tanggal**: 24 November 2026 (WIB)
- **Sifat**: bukti **fixture/unit/HTTP**; **bukan** UAT akun Google nyata dan bukan gate live.
- **Pasangan build**: Android `e67380a` (`feat/c2-android`), backend `71013f5`
  (`integration/2026-11-24`); keduanya sudah di-push.

## 1. Gate otomatis (fresh pada commit di atas)

| Pemeriksaan | Hasil |
|---|---|
| Backend `vet ./...`, `build ./...` | PASS |
| Backend `test ./... -count=1` | PASS semua paket (auth, httpapi, store, gmail, dll.) |
| Backend `test -race ./internal/auth ./internal/httpapi ./internal/store` | PASS |
| Android `:app:testDebugUnitTest` | **678 tes / 88 suite / 0 gagal** (640 lama + 38 SSO) |
| Android `:app:lintDebug` | **0 error / 123 warning / 1 hint** |
| Android `:app:assembleDebug` | PASS; APK 47.992.939 byte, sha256 `1a4c8ddf…` |
| Migrasi | 21 (`001`–`021`), upgrade 020→021 diuji |

APK gate dibangun **tanpa** `PAM_GOOGLE_SSO_CLIENT_ID`; tombol SSO tersembunyi dan APK tidak
memuat string `googleusercontent.com`. Karena itu APK ini **bukan** bukti UI SSO pada perangkat.

## 2. Perilaku yang dibuktikan tes

- Verifier Go: RS256-only, `kid`/JWKS cache+rotasi, issuer whitelist, audience = client ID,
  skew exp/nbf/iat, nonce cocok; token salah/kedaluwarsa → 401.
- Nonce: TTL, konsumsi atomik sebelum verifikasi, replay ditolak.
- Login SSO menerbitkan sesi PAM normal (`accessToken`/refresh) + `googleLinked:true`; akun
  tidak dikenal → 409 `google_identity_not_linked` (tanpa auto-create).
- Link: idempoten, konflik lintas owner 409, sudah tertaut 409, nonaktif 403, race owner sama
  tepat satu pemenang + satu 409 (tanpa 500), email sama beda `sub` tidak merge, transaksi dan
  koneksi Gmail owner tidak berubah.
- Android: tombol utama "Masuk dengan Google" + fallback kata sandi, picker akun selalu tampil,
  ID token tidak masuk log/intent/state, onboarding Gmail non-blocking dengan tombol Lewati
  per-owner (DataStore), consent Gmail/AI terpisah.

## 3. Yang TIDAK dibuktikan di sini (NOT RUN / owner)

- Login Google nyata di HP (butuh Web Client ID + OAuth Android client terdaftar + akun uji).
- Link akun lama dan login ulang pada akun Google nyata.
- Consent `gmail.readonly` nyata, backfill setahun, cabut akses.
- Gate Rp0 setelah Billing dilepas.
- Tombol **Run** Android Studio literal.

Bukti polling FIXTURE Gmail (`GMAIL_POLLING_UAT_PHONE_2026-11-24.md`) tetap berlaku untuk
lingkup fixture dan tidak berubah oleh lane SSO.
