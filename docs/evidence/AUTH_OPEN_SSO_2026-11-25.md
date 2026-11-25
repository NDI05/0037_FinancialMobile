# Bukti — registrasi terbuka dan Google SSO langsung (25 November 2026, WIB)

Keputusan: [CR-009](../changes/CR_009_OPEN_GOOGLE_AUTH.md). Lingkungan: macOS, Go 1.27.4
(`.toolchain/go`), JDK 21 proyek, backend UAT loopback `127.0.0.1:18090` (lokal, bukan produksi).

## Perubahan yang diverifikasi

| Repo | Commit | Isi |
|---|---|---|
| pam-app | `a53f45e` | UI/API tanpa kode undangan; tombol Google mode masuk & daftar |
| 0037-Backend-Financial | `6c941d9` | registrasi terbuka; SSO login+daftar langsung; config undangan dihapus |

## Hasil uji otomatis

- Go: `gofmt -l` bersih, `./scripts/go.sh vet ./...` exit 0, `./scripts/go.sh test ./... -count=1`
  seluruh paket lulus (httpapi 23,3 dtk; store 2,4 dtk; gmail 2,8 dtk).
- Android: `testDebugUnitTest` **617/617** lulus, `testAssessmentUnitTest` **626/626** lulus,
  `lintDebug` 0 error, `assembleDebug` sukses.
- Cakupan tes Go baru: provisioning akun Google baru (USER, sesi valid, login password mustahil),
  auto-link akun email yang sudah ada, email tidak terverifikasi ditolak tanpa akun/tautan,
  login berulang idempoten, konflik identitas 409, akun inaktif 403, balapan concurrent →
  1 akun/1 tautan, registrasi terbuka 201, field undangan pensiun 400.
- Cakupan tes Android baru: registrasi tanpa kode undangan, body `/v1/accounts` tanpa `inviteCode`,
  SSO di mode daftar berhasil, tombol “Daftar dengan Google” tampil, 409 tetap memetakan error.

## Uji end-to-end build baru (database terisolasi)

Backend biner baru dijalankan pada port 18093 dengan database sementara (bukan DB UAT pemilik):

- `POST /v1/accounts` `{email,password,displayName}` → **201**.
- Body lama berisi `inviteCode` → **400** (`invalid_request`; decoder ketat).
- `POST /v1/sessions` akun baru → **201**.

## UAT loopback pemilik

- Biner baru dipasang di `~/.config/pam/uat-runtime/pam-api-loopback` (backup:
  `pam-api-loopback.bak-20261125-auth`); proses lama dihentikan lalu `start.py` dijalankan ulang.
- `/healthz` 200, `/readyz` 200; probe `POST /v1/accounts` email tidak valid → 400 dan body dengan
  `inviteCode` → 400, membuktikan biner baru aktif (biner lama menjawab 403 `invalid_invite`).
- APK debug baru saat itu dipasang di perangkat fisik yang terhubung (seri diredaksi, `adb reverse tcp:18090` aktif); APK terbaru `39f2cd6e…` (commit `13b7a2a`) dipasang 25 Nov 14:42 WIB. Pemilik mengonfirmasi 25 Nov bahwa HP-nya adalah **Redmi 10 `21061119AG` (seri diredaksi)**; APK hasil fix `a0498c29…` dipasang di HP tersebut dan emulator-5556. Catatan model `24069PC21G` pada ronde sebelumnya keliru.

## Status verifikasi (jujur)

- **Google SSO live sudah teruji login** dengan akun Google asli di HP pemilik 25 Nov ±14:37 WIB.
  Rantai bukti backend: `DELETE /v1/sessions` **204** 14:37:08 WIB (logout) → `GET
  /v1/auth/google/nonce` **200** 14:37:10 → nonce terpakai `07:37:15Z` → `POST /v1/auth/google`
  **200** 1257 ms 14:37:16 → `google_identities.last_login_at` + `auth_sessions` baru owner
  identik `07:37:16.467436Z` → `GET /v1/me` **200**, app memuat Ringkasan.
  **Belum:** rekaman layar, login dengan akun Google kedua, dan login/registrasi akun baru lewat UI
  (registrasi live hanya diuji di DB sementara).
- Registrasi tidak dilakukan ke database UAT pemilik (sengaja; tidak membuat akun sintetis di data
  pemilik). Uji end-to-end memakai database sementara.
- SRS resmi DOCX/PDF tidak diubah.

## Addendum: temuan mode daftar dan perbaikannya

- Smoke emulator putaran pertama menemukan **Welcome → "Buat akun" membuka mode MASUK**: navigasi
  type-safe menaruh enum `AccountMode` di `SavedStateHandle`, sedangkan `AccountViewModel` hanya
  membaca `String`, lalu jatuh ke `SIGN_IN` tanpa error.
- Perbaikan `8de8f75` menerima enum maupun nama enum + tes regresi di `AccountSsoViewModelTest`.
- Diverifikasi ulang di emulator-5556 (APK sha256 `a0498c29…`, `pm clear`): "Buat akun" langsung
  mode daftar **dua kali deterministik** (headline "Buat akun PAM.", field Nama tampilan, tombol
  "Daftar dengan Google"), "Masuk" tetap mode masuk ("Masuk dengan Google"), login sintetis 201,
  Ringkasan net **Rp88.466,78** / masuk **Rp123.456,78** / keluar **Rp34.990**, Transaksi 3 baris,
  tanpa entri Anggaran/Lampiran/Administrasi, `AndroidRuntime:E` kosong. Bukti `/tmp/w-ui-smoke2-*`.
- APK hasil fix juga dipasang di perangkat fisik Redmi 10 (model `21061119AG`, seri diredaksi) yang
  terhubung; app membuka Ringkasan tanpa crash, data tidak ditelusuri.
- **Login Google live di HP pemilik terverifikasi backend 25 Nov ±14:37 WIB:** POST ke rute SSO
  (rute POST yang tidak terdaftar di logger request) membalas **200 dalam 1257 ms** dan tepat pada
  detik yang sama dibuat `auth_session` baru untuk akun pemilik; layar HP menampilkan Ringkasan.
  Ini bukti token Google asli diterima, bukan fixture. Logout/relogin berurutan dan login dengan
  akun Google kedua belum dicatat.
- **Run literal G36 dikonfirmasi pemilik** (chat 25 Nov): tombol **Run** Android Studio ditekan pada
  HP pemilik **Redmi 10 `21061119AG`**; app aktif dan memuat Ringkasan.
- **Tetap NOT RUN:** onboarding Gmail akun uji (Lewati + Tautkan + consent) dan email Livin alami
  saat app tertutup.
