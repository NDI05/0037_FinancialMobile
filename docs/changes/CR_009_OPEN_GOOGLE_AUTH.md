# CR-009 — Registrasi terbuka tanpa kode undangan dan Google SSO untuk Login/Daftar

Keputusan pemilik, 25 November 2026 (WIB). Ini adalah perubahan cakupan produk yang menggantikan
bagian identitas/onboarding pada [CR-008](CR_008_DRAFT_PRODUCT_SCOPE.md) dan menutup sebagian
OI-28 di [memo keputusan](../delivery/DECISION_MEMO_OI27_OI28_2026-11-25.md).

## Keputusan pemilik

1. **Hapus registrasi dengan kode undangan.** Pendaftaran email + kata sandi menjadi terbuka; tidak
   ada `REGISTRATION_INVITE_CODE`, tidak ada field `inviteCode`, tidak ada `invitationToken` pada
   jalur publik. Setiap akun publik adalah `USER`.
2. **Google SSO menjadi jalur login sekaligus pendaftaran langsung.** Pengguna tidak perlu
   menautkan Google lebih dulu di Pengaturan: identitas Google terverifikasi langsung membuka akun
   yang emailnya sama, atau membuat akun `USER` baru bila email belum terdaftar.
3. Tombol **Tautkan Google** di layar Akun tetap ada untuk akun yang sudah login (idempoten), tetapi
   bukan lagi prasyarat untuk login/daftar.

## Perubahan kontrak

- `POST /v1/accounts` menerima `{email, password, displayName}`; `inviteCode`/`invitationToken`
  ditolak `400 invalid_request` oleh decoder ketat. Duplikat email `409 conflict`.
- `POST /v1/auth/google` (tanpa Bearer):
  - identitas tertaut → sesi seperti login biasa (`googleLinked: true`);
  - identitas belum tertaut + `email_verified=true` → tautkan ke akun pemilik email itu, atau
    provisioning akun `USER` baru; password acak yang tidak dapat dipakai login password;
  - `email_verified=false` → `403 google_email_unverified` (tidak ada akun/tautan);
  - email sudah dimiliki akun dengan identitas Google lain → `409 google_identity_conflict`.
  - `409 google_identity_not_linked` hanya tersisa untuk keadaan yatim (identitas ada, akun hilang).
- Konfigurasi `REGISTRATION_INVITE_CODE` dihapus dari backend (tidak ada field mati); env lama yang
  masih memuatnya diabaikan.
- Kontrak rinci: backend `docs/API_CONTRACT.md` §2.1/§2.6 dan `docs/GOOGLE_SSO_CONTRACT.md`.

## Keamanan dan risiko

- Registrasi publik berarti siapa pun yang dapat menjangkau API dapat membuat akun `USER`. Data
  tetap terisolasi per `owner_id`; tidak ada role dari body.
- Email Google yang tidak diverifikasi tidak dapat membuat atau mengklaim akun. Tautan identitas
  yang sudah ada tidak pernah dipindahkan; email sama dengan identitas berbeda = konflik.
- Akun Google-only memakai digest password acak; login password untuk email tersebut tidak dapat
  ditebak.
- Risiko residual: `POST /v1/accounts` belum punya rate limit khusus anti-spam. Untuk deployment
  publik ini perlu lapisan tambahan (rate limit per IP, captcha, atau allowlist) — dicatat sebagai
  item lanjutan, bukan bagian CR ini.
- Google OAuth consent screen yang masih status *Testing* hanya mengizinkan **Test user** yang
  didaftarkan pemilik di Google Cloud Console; konfigurasi tester adalah tindakan pemilik, bukan kode.

## Implementasi dan bukti

- Android `a53f45e`: hapus UI/state/API kode undangan; tombol Google tampil di mode masuk dan daftar
  dengan label “Daftar dengan Google”; 617 tes debug + 626 tes assessment lulus; lint 0 error.
- Go `6c941d9`: registrasi terbuka; `/v1/auth/google` login+daftar langsung; transactional
  find-or-create dengan retry race; tes HTTP/store lengkap; `go vet` + seluruh tes lulus.
- Bukti terperinci: [AUTH_OPEN_SSO_2026-11-25.md](../evidence/AUTH_OPEN_SSO_2026-11-25.md).

## Di luar cakupan

- SRS resmi DOCX/PDF tidak diubah; revisi konsolidasi tetap milik pemilik.
- Uji live dengan akun Google asli belum dijalankan (butuh akun pemilik + Test user consent screen).
- Deploy produksi tetap keputusan pemilik.
