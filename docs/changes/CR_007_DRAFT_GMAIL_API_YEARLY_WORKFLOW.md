# CR-007 — Draf revisi SRS untuk Google SSO, Gmail API, dan workflow setahun

Tanggal: **24 November 2026**. Revisi desain mencakup polling 15 menit dan onboarding Google SSO.
Status: **draf untuk konsolidasi SRS; BELUM diterapkan**. Full SRS resmi tetap v0.5; Revisi 24 November 2026 (ronde 6): Gmail live **PASS untuk akun pilot owner**; SSO live dan Rp0 tetap NOT RUN/OPEN.
file DOCX/PDF, requirement ID resmi, dan version history tidak berubah oleh CR ini.
Nomor versi berikutnya dan nomor FR/NFR final belum ditetapkan.

Dasar: [mandat PM Gmail API + workflow tahunan](../agent-prompts/PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW.md)
dan revisi kontrak server §9 per 24 November 2026
([GMAIL_API_CONTRACT](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/GMAIL_API_CONTRACT.md)).
Rancangan SSO dan kriteria uji ada di [rencana sinkronisasi §10](../plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md#10-google-sso-dan-onboarding-gmail).
Melengkapi [CR-003](CR_003_GMAIL_SESSION_CATEGORY.md) (koneksi email) dan
[CR-005](CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md) (auto-sync/kategori/status).
CR ini **menggantikan target transport IMAP/app-password** dan sekaligus
**menggantikan desain awal Gmail API + Pub/Sub push sebagai jalur utama** menjadi
**polling default 15 menit dengan push sebagai opt-in rollback**.

## 1. Alasan perubahan

Pemilik menetapkan **Masuk dengan Google** sebagai jalur utama. Sesudah masuk, pengguna
dapat menautkan Gmail untuk memulai impor Livin setahun dan pembaruan otomatis.
PAM memakai satu job kategorisasi logis untuk histori, menyimpan data dalam batas retensi setahun,
dan memakai Bahasa Indonesia ringkas. Mandat juga memisahkan backend Go ke repo
**0037 Backend Financial**.

Revisi desain 24 November 2026 menetapkan:

1. **Jalur utama adalah polling**, bukan Pub/Sub push. Worker backend membaca Gmail API secara
   periodik (default delta **900 detik / 15 menit**, backfill 12 bulan bertahap) selama proses
   backend hidup. Ini menghilangkan kebutuhan topik/subscription Pub/Sub, endpoint HTTPS publik,
   dan verifier OIDC pada jalur default — mengejar **target biaya GCP Rp0** untuk pemakaian pilot.
   Aplikasi Android boleh tertutup; yang harus tetap hidup adalah backend.
2. **Push + `users.watch` + Pub/Sub menjadi opt-in rollback**, bukan default. Tanpa
   `GMAIL_MODE=push` dan topik yang disiapkan, backend tidak memasang webhook, tidak memanggil
   `users.watch`, dan tidak menerima verifikasi OIDC. Jalur ini dipertahankan sebagai opsi latensi
   rendah bila pemilik nanti menyiapkan Pub/Sub.
3. **Sinkron manual masuk antrean Go durable**: tombol "Sinkronkan sekarang" hanya menulis
   permintaan (202 queued/coalesced), bukan impor sinkron; ada cooldown 60 detik dengan 429
   `sync_cooldown`, dedupe, dan permintaan lanjut setelah backend restart.
4. **Recovery `history.list` 404** memakai cursor baseline + replay history yang tersedia,
   termasuk pergantian bulan WIB, tanpa kehilangan atau menggandakan transaksi.
5. **Backfill 12 bulan mengalah pada delta**: halaman per batch dibatasi, delta diproses lebih
   dulu, bulan berjalan diprioritaskan.
6. **Consent Gmail dan consent AI terpisah** (Gmail baca, kategorisasi AI, analisis AI).
7. **Google SSO dan penautan Gmail berada dalam satu onboarding**. Login membuka sesi PAM.
   Akses baca Gmail diminta melalui langkah opsional sesudahnya. Pengguna yang melewati
   langkah tersebut tetap dapat mencatat transaksi manual. Akun PAM lama menautkan
   identitas Google dari sesi akun yang sudah dimiliki agar data dan aksesnya tetap utuh.

IMAP/app-password tidak lagi menjadi target: tidak dapat memenuhi impor server-side saat app
tertutup secara andal dan menambah permukaan kredensial di perangkat.

## 2. Calon requirement untuk konsolidasi nanti

| ID draf | Rumusan calon requirement | Trace bukti implementasi |
|---|---|---|
| DRAFT-SSO-01 | Android memulai **Masuk dengan Google** melalui Credential Manager. Go memvalidasi tanda tangan ID token, issuer, audience, expiry, dan nonce sebelum menerbitkan sesi PAM. Identitas disimpan sebagai pasangan issuer dan `sub`. | **IMPLEMENTED (fixture/unit)** `pam-app@e67380a`, backend `71013f5`: Credential Manager `GetGoogleIdOption` (picker selalu tampil, tanpa auto-select) + verifier Go RS256-only dengan JWKS cache/rotate, iss whitelist, aud = Web Client ID, skew exp/nbf/iat, dan nonce sekali pakai (consume-before-verify, replay ditolak). Tes: `internal/auth`, `internal/httpapi`, Android `GoogleSsoBackendTest`/Compose. UAT HP akun Google nyata **NOT RUN (owner)**. |
| DRAFT-SSO-02 | Akun lama menautkan identitas Google setelah autentikasi PAM. Penautan unik, atomik, dan mempertahankan owner, transaksi, role, serta koneksi Gmail. Email sama tidak memicu penggabungan. Pembuatan akun baru tetap tunduk pada undangan dan kebijakan akun yang berlaku. | **IMPLEMENTED (fixture/unit)** migrasi 021 `google_identities` PK(issuer,subject) + UNIQUE(owner_id) dengan FK cascade; `POST /v1/auth/google/link` idempoten, konflik lintas owner → 409, akun nonaktif → 403, penautan bersamaan owner sama tepat satu pemenang dan satu 409 (tanpa 500), email sama beda `sub` tidak merge; tes memastikan transaksi/koneksi Gmail owner tidak berubah. Login ulang akun lama + UAT HP akun Google nyata **NOT RUN (owner)**. |
| DRAFT-SSO-03 | Setelah SSO, tawarkan akses `gmail.readonly` kepada pengguna yang belum menautkan Gmail. Gmail boleh memakai akun sama atau berbeda. Consent Gmail terpisah dari identitas Google dan akses AI. Lewati, tolak, atau cabut Gmail tetap memungkinkan akses PAM dan transaksi manual. | **PARTIAL**: onboarding Gmail pasca-login + tombol Lewati per-owner (persisten DataStore), non-blocking, dan consent Gmail/AI terpisah sudah diimplementasi serta diuji unit/Compose; consent Google nyata, backfill setahun, dan pencabutan akses di HP akun nyata **NOT RUN (owner)**. Bukti polling FIXTURE tetap berlaku untuk lingkup fixture. |
| DRAFT-SSO-04 | Profil pilot memakai Google Sign-In langsung, Gmail API standar, dan backend Go yang tersedia. Gate Rp0 membutuhkan project tanpa Billing tertaut dan alur live yang lulus. | **PARTIAL**: Gmail live sudah **PASS untuk akun pilot owner** (polling 12/12 bulan, 914 transaksi unik, 0 duplikat; `GMAIL_LIVE_IMPORT_2026-11-24.md`); login Google nyata (**G36**) dan gate **Rp0** (Billing masih tertaut) tetap **NOT RUN/OPEN**. Kode tidak menambah Cloud Identity Platform/Pub/Sub/layanan berbayar dan desain default tetap polling. SSO, Gmail live, dan Rp0 dilaporkan sebagai gate terpisah. |
| DRAFT-GM-01 | Owner menautkan Gmail dari sesi PAM aktif; server membuat `state` acak sekali pakai (TTL ≤10 menit) terikat owner + PKCE S256; callback menukar code, menyimpan refresh token terenkripsi, dan selalu mengembalikan owner ke app lewat deep link `pam://gmail/connected` dengan kode alasan aman | Kontrak §1–2; E2E fake `normal/history_expired/rate_limit` (`docs/evidence/GMAIL_CONTRACT_E2E_2026-11-24.md`); UAT perangkat FIXTURE butir 2 (`docs/evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md`); tes `internal/gmail` |
| DRAFT-GM-02 | Hanya satu koneksi Gmail ACTIVE per owner; sambung ulang dengan akun lain mencabut token lama sebelum mengganti (dan menghentikan watch hanya bila mode push aktif); scope minimum `gmail.readonly`; status tidak pernah menampilkan token/email penuh | Kontrak §3; tes isolasi owner A/B; E2E status termask tanpa token/message id |
| DRAFT-GM-03 | **Default polling 15 menit**: worker memanggil `history.list` tiap `GMAIL_DELTA_INTERVAL_SECONDS=900` dan backfill bertahap; **tidak ada** `users.watch`, `users.stop`, webhook push, atau verifier OIDC pada mode default; delta berjalan **selama backend hidup**, aplikasi boleh tertutup | Default config backend (`GMAIL_MODE=polling`, delta 900 dtk); E2E: `watchCalls=0`, `stopCalls=0`, `POST /v1/internal/gmail/push` → 404 (82/82 PASS); UAT FIXTURE butir 7 (app force-stop, delta tersimpan) ; **live**: backfill 12/12 bulan pada mailbox nyata hanya lewat polling (tanpa Pub/Sub), `sync_state=idle` setelah selesai |
| DRAFT-GM-04 | Push + `users.watch` + Pub/Sub hanya **opt-in rollback**: aktif hanya bila `GMAIL_MODE=push` dan topik/audience/service account dikonfigurasi; event push diverifikasi OIDC, durable, dedupe; tanpa topik tidak ada webhook/verifier | Tes push (JWT valid/invalid/expired, replay, NEEDS_REAUTH) pada mode push; gate live Pub/Sub **NOT RUN (owner)** |
| DRAFT-GM-05 | "Sinkronkan sekarang" menulis permintaan ke **antrean job Go durable**: `202 {"queued":true,"coalesced":...}`; cooldown 60 detik → `429 {"code":"sync_cooldown","retryable":true,"retryAfterSeconds":N}`; permintaan aktif memicu `coalesced`; dedupe; job/permintaan lanjut setelah backend restart dan marker dibersihkan saat drain | Kontrak §9.3; E2E `manual sync 202 → 429`, restart lanjut tanpa duplikat; UAT FIXTURE butir 5–6 (202 + drain "Menunggu", 429 + hitungan mundur); perbaikan audit ronde 4 (`1290910`) |
| DRAFT-GM-06 | Backfill awal 12 bulan bergulir WIB diproses **bertahap** (halaman per run dibatasi), **mengalah pada delta**, bulan berjalan lebih dulu; checkpoint per batch; email lintas bulan dirutekan ke bulan transaksi (bukan bulan kedatangan); transaksi di luar jendela retensi ditolak eksplisit | `GMAIL_BACKFILL_MONTHS=12`, `GMAIL_MAX_PAGES_PER_RUN`; E2E `months_done=12/12`; UAT FIXTURE butir 3 (12/12, 27 transaksi sintetis) |
| DRAFT-GM-07 | Delta memakai `history.list` dengan cursor terenkripsi; **404 history kedaluwarsa** dipulihkan lewat baseline cursor baru + replay history yang masih tersedia (termasuk rollover bulan WIB), tanpa kehilangan transaksi baru dan tanpa duplikat | E2E skenario `history_expired` (404 → idle, `last_error_code=null`, delta masuk tepat sekali); tes cursor expiry |
| DRAFT-GM-08 | Identitas pesan Gmail tidak pernah disimpan mentah; dedupe memakai hash; tidak ada baris ganda meski email sama dibaca ulang saat recovery/restart | E2E: 31 transaksi unik, 0 duplikat setelah delta & restart; UAT FIXTURE butir 8–9 |
| DRAFT-GM-09 | Rate limit/429 provider tidak boleh memarkir owner di `FAILED` permanen atau meminta reauth; retry internal terbatas (maks 3, exponential) dan status tetap jujur (`last_error_code`, `last_attempt_at`, `next_sync_at`); Gmail nonaktif → `503 gmail_disabled`, belum tertaut → `409 gmail_not_connected`, grant dicabut → `409 gmail_needs_reauth` | E2E skenario `rate_limit` (bukan error permanen, `needs_reauth=false`); kontrak §9.3; tes config/handler |
| DRAFT-GM-10 | Status sinkron server diproyeksikan untuk UI: `connected`, email termask, `scope`, `sync_state` (`idle/backfill/delta/error`), `progress{phase,month,months_done,months_total,pages_done}`, `last_attempt_at`, `last_success_at`, `next_sync_at`, `sync_requested_at`, `last_error_code`, `needs_reauth`; `watch_expires_at` null/absen pada polling | Kontrak §3/§9.2; E2E pemeriksaan bentuk status; UAT FIXTURE butir 4 (panel "Sinkron server Gmail") |
| DRAFT-GM-11 | Consent **Gmail**, **AI kategorisasi**, dan **AI analisis** terpisah; menolak satu consent tidak mematikan yang lain; layar tautkan menjelaskan status server dengan jujur (tanpa CTA menyesatkan saat Gmail nonaktif) | Tes consent UI/backend; UAT FIXTURE profil fixture dengan consent AI nonaktif |
| DRAFT-GM-13 | Parser Livin harus menerima **templat produksi**, bukan hanya fixture: label boleh berada di baris sendiri (`Penerima` lalu nilai di baris berikutnya) dan tanggal boleh memakai nama/abreviasi bulan Indonesia (`Okt`, `Agu`, `Des`, …) dengan waktu terpisah (`pkl … WIB`/`WITA`/`WIT`) | Live ronde 6: backfill awal mengimpor 0 karena bentuk produksi; perbaikan backend `5df6be6` + `c98527b` dengan regresi `TestParseLivinStacked*`/`TestParseLivinIndonesianMonthAbbreviation`; setelah itu 914 transaksi unik, 0 duplikat. Audit ronde 7 menutup dua P1: baris field (`Nominal…`, `Tanggal…`, `No. Referensi…`, `pkl…`) tidak boleh menjadi merchant, dan angka bukti direkonsiliasi (769 impor run final + 145 baris tersimpan = 914); hardened di `e3b84b4` (WITA/WIT offset nyata, waktu lintas baris, alias `Nop`/`Peb`) dengan regresi baru; email promosi QRIS dicatat backlog non-blocking; parser baru ditandai `gmail-livin-v2` (914 baris live lama tetap `v1`, regenerasi butuh persetujuan owner). Audit ronde 2 menutup celah PAN/field-label pada jalur **stacked dan inline** (`e19477b`): baris `Merchant PAN`/`Customer PAN`/identifier ≥6 digit dan baris `Label: nilai` generik tidak lagi menjadi merchant, dengan regresi khusus Audit verifikasi ronde 3 menutup P3: baris referensi tanpa titik dua (`Ref`/`ID`/`Kartu` + nilai) tidak lagi menjadi merchant (`looseFieldPattern`) dan prosa ber-kolon tidak menjembatani waktu (`c69cf57`, regresi `TestParseLivinProseColonDoesNotBridgeTime`). |
| DRAFT-GM-12 | **Target biaya GCP Rp0** untuk pemakaian pilot: jalur default tidak memerlukan Pub/Sub topic/subscription, Cloud Run/endpoint publik, atau verifikasi OIDC; backfill/delta cukup memakai kuota Gmail API gratis yang relevan | Default config polling; gate **Rp0 OPEN**: Billing masih tertaut dan pemakaian nyata belum diukur — **NOT RUN (owner)** |
| DRAFT-RT-01 | Data keuangan disimpan dalam jendela bergulir satu tahun WIB; permintaan di luar jendela ditolak `400 outside_retention` dengan metadata jendela, bukan error server | migrasi 018 + guard backend; [RETENTION_POLICY_IMPLEMENTATION](https://github.com/NDI05/0037-Backend-Financial/blob/integration/2026-11-24/docs/RETENTION_POLICY_IMPLEMENTATION.md) |
| DRAFT-RT-02 | Sweep retensi default report-only (hanya audit agregat); penghapusan memerlukan flag `RETENTION_DELETE_ENABLED`, token internal, dan konfirmasi literal; budget plan dikecualikan | Tes store/httpapi; gate hapus data nyata **NOT RUN (owner)** |
| DRAFT-UI-01 | Navigasi bulan terbatas pada jendela retensi (panah depan mati di bulan berjalan, panah belakang di batas, label "sebagian"); status proses memakai satu ikon dan satu sheet dengan label ringkas Indonesia | `docs/evidence/RETENTION_UI_2026-11-24.md` |
| DRAFT-UI-02 | Sheet status memuat panel **"Sinkron server Gmail"**: state, progres bulan, percobaan/keberhasilan terakhir, jadwal berikutnya, tombol "Sinkronkan sekarang" dengan hitungan mundur saat cooldown; state tidak boleh tertahan di "Menghubungkan" | UAT FIXTURE butir 4–6 + aset `status_sheet_final.*`, `status_cooldown_429.*`; perbaikan audit ronde 4 `addc240` |
| DRAFT-UI-03 | Seluruh alur utama Bahasa Indonesia, TalkBack satu node per kontrol status, target sentuh ≥48 dp, layout benar pada font 200% di 360/393/412 dp | `RETENTION_UI_2026-11-24`; `UAT_ACCESSIBILITY_2026-11-24`; walk TalkBack final **NOT RUN** |
| DRAFT-OPS-01 | Source backend berdiri di repo terpisah dengan build/test/CI sendiri; Android tidak memuat source server; rollback ke pasangan baseline didokumentasikan | `docs/delivery/BACKEND_SPLIT_2026-11-24.md`; QA parity P1–P9 |
| DRAFT-OPS-02 | Profil UAT FIXTURE reproducible: provider `fake` hanya boleh aktif di `APP_ENV=development` + loopback allowlist + fixture; UAT Gmail live memakai kredensial owner di luar repo; tidak ada secret/PII di repo/log/laporan | Config guard backend; runbook UAT fixture (`0037-Backend-Financial` `067eac7`); UAT perangkat FIXTURE |

## 3. Perubahan UI utama

1. Pengaturan → **Tautkan Gmail lewat browser**, status tersambung (email masked, sinkron terakhir,
   jadwal berikutnya), tombol ganti akun dan putuskan berkonfirmasi. Tidak ada lagi instruksi
   app-password/Gmail App Password.
2. Ringkasan/Transaksi/Analisis dibatasi jendela retensi dengan label bulan batas "sebagian".
3. Status pembaruan: satu ikon + satu sheet; di dalamnya ada panel **"Sinkron server Gmail"**
   dengan state (`Menunggu`, `Mengambil riwayat`, `Memperbarui`, `Perlu perhatian`), progres
   `bulan selesai/total`, percobaan & keberhasilan terakhir, jadwal berikutnya, dan tombol
   "Sinkronkan sekarang". Saat cooldown, tombol menampilkan hitungan mundur "Tunggu N detik..."
   dan tidak mengirim permintaan. Menutup sheet tidak menghentikan job server.
4. Perbaikan audit ronde 4: state tidak lagi tertahan "Menghubungkan" setelah drain, sehingga
   panel mencerminkan proyeksi server (`idle` + `sync_requested_at=null`).
5. Istilah teknis (IMAP, Pub/Sub, OIDC, token) tidak tampil di UI utama.
6. Layar masuk mengutamakan **Masuk dengan Google**. Akun lama tetap punya akses masuk
   untuk migrasi identitas. Setelah login pertama, langkah **Tautkan Gmail untuk impor
   Livin** menawarkan **Lewati**. Bila akses Gmail kedaluwarsa, tampilkan **Tautkan ulang Gmail**.

## 4. Keputusan yang masih menunggu pemilik

1. Merge `feat/c2-android`/`integration/2026-11-24` ke `main` repo masing-masing dan deployment;
   saat ini hanya branch yang dipush.
2. Web OAuth client UAT, redirect loopback, dan test user Gmail sudah terbukti dipakai pada UAT
   Gmail live 24 November 2026. Masih perlu verifikasi package + SHA-1 Android OAuth client untuk
   **SSO live** (G36); lalu periksa ketentuan restricted scope dan assessment untuk produksi.
   Topik dan subscription Pub/Sub hanya diperlukan bila mode push opt-in dipakai.
3. Memverifikasi target biaya **Rp0** (Billing saat ini masih tertaut) dan mengukur kuota Gmail API
   pada akun pilot.
4. Rotasi client secret/service account dan pemasangan kredensial di lingkungan UAT.
5. Akun pilot Gmail live sudah dipakai pada UAT 24 November 2026 (izin data nyata dari pemilik);
   sisanya login Google nyata untuk SSO (G36) dan verifikasi Rp0 (G29/G37).
6. Keputusan menghentikan jalur app-password perangkat sepenuhnya setelah rollback teruji.

## 5. Batas draf dan status bukti

Status implementasi per 24 November 2026:

- **Google SSO dan onboarding Gmail terpadu**: implementasi selesai di fixture/unit
  (`pam-app@e67380a`, backend `71013f5`; lihat DRAFT-SSO-01..04). **Gmail live sudah PASS untuk
  akun pilot owner** (polling 12/12 bulan, 914 transaksi unik, 0 duplikat; perbaikan parser
  `5df6be6`/`c98527b`). UAT login **Google nyata (G36)** dan gate **Rp0 (G29/G37)** tetap
  **NOT RUN/OPEN**. Bukti polling FIXTURE di bawah berlaku untuk lingkup fixture saja.
- Android `feat/c2-android` HEAD `edd7570` (kode aplikasi tidak berubah sejak `e67380a`), verifikasi
  fresh ronde 7: unit debug **678 tes/88 suite/0 gagal/0 dilewati**, `lintDebug`
  **0 error/123 warning**, `assembleDebug` PASS (APK debug 47.992.939 byte, sha256 `5368c92f…`;
  build UAT memakai `local.properties` owner sehingga Web OAuth client ID masuk `BuildConfig`).
  Tes `GoogleSsoAccountSourceTest` diperbaiki agar mengikuti client ID yang benar-benar
  dikonfigurasi (sebelumnya mengasumsikan checkout tanpa client ID).
- Backend `integration/2026-11-24` HEAD `4244f4f`, verifikasi fresh ronde 7: `vet`/`build`/
  `test ./... -count=1` PASS (10 paket), `-race` auth/gmail/httpapi/store PASS; perbaikan
  akumulatif ronde 4–6 mencakup marker `sync_requested_at`, kontrak 429, fase kegagalan,
  `409 gmail_needs_reauth`, label/waktu stacked, dan bulan Indonesia.
- Kontrak polling §9 diverifikasi black-box dengan provider fake: **PASS=82, FAIL=0**
  (`docs/evidence/GMAIL_CONTRACT_E2E_2026-11-24.md`), termasuk backfill 12/12, sync 202→429,
  restart tanpa duplikat, recovery history 404, dan rate limit tidak permanen.
- UAT perangkat fisik FIXTURE: **PASS** alur terkontrol (`docs/evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md`),
  termasuk delta saat app ditutup, manual sync + cooldown, dan restart backend. Ini **bukan**
  bukti Gmail live.
- UAT Gmail live akun pilot owner: **PASS (G25)** — backfill 12/12 COMPLETE, 914 transaksi unik,
  0 baris duplikat, rate limit ditangani backoff; bukti agregat
  (`docs/evidence/GMAIL_LIVE_IMPORT_2026-11-24.md`).
- Matriks gate: **29 PASS, 4 PARTIAL, 5 NOT RUN** (38 gate), tanpa FAIL.

Gate yang **NOT RUN (owner)**: login Google nyata (**G36**), topik Pub/Sub nyata (hanya bila push
opt-in dipakai), Azure nyata, hapus/restore data nyata (G14), dan **GCP Rp0** (G29/G37; Billing
masih tertaut). Tombol **Run** Android Studio secara literal juga **NOT RUN** (instalasi UAT
memakai `adb install -r`); lihat penyimpangan di evidence UAT. Draf ini tidak menyatakan SRS
resmi berubah dan tidak mengklaim SSO live atau Rp0 PASS.
