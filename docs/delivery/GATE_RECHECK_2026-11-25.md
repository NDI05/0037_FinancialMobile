# Verifikasi ulang gate rilis — 25 November 2026 (WIB)

Dokumen ini mencatat verifikasi ulang pasca-merge dari `main` kedua repo, termasuk perbaikan
nominal impor email dan perubahan auth terakhir (registrasi terbuka + Google SSO langsung) yang
diputuskan pemilik pada hari yang sama. Baseline requirement tetap SRS v0.7/CR-008 dengan CR-009
sebagai perubahan cakupan auth. PASS hanya diberikan untuk perilaku yang benar-benar diuji pada
build/lingkungan yang disebut; sisanya PARTIAL atau NOT RUN.

## Snapshot repo dan build

| Item | Nilai |
|---|---|
| Android `main` | `0d8affa` == `origin/main`, worktree bersih; kode auth `a53f45e`, perbaikan mode daftar `8de8f75`, dokumentasi/skrip `651f807` + `0d8affa` (`0d8affa` docs-only) |
| Backend `main` | `1325d3d` == `origin/main`, worktree bersih; kode auth `6c941d9`, dokumentasi kontrak `1325d3d`; perbaikan nominal sebelumnya `d42d7c4`/`aa33fb8`/`68f1ba4`/`b6ce917` tetap ada |
| APK debug terpasang di perangkat fisik & emulator | **APK terbaru (fix pesan kategori):** 47.628.023 B sha256 `39f2cd6efc8d27e22007a08b9d349797019c8c0188ca6087f0b18347b75a4fbe` (commit `13b7a2a`), terpasang di Redmi 10 14:42 WIB; **APK dasar gate:** `app-debug.apk` 47.668.107 B sha256 `a0498c2904b7d129dc471c5e09a65e6f59f3ae7d96c180bd63a1901440da114d` (build dari `main` `8de8f75`), `adb install -r` di emulator-5556 dan perangkat fisik Xiaomi **Redmi 10** (model `21061119AG`, seri diredaksi) yang terhubung saat verifikasi; pemilik mengonfirmasi 25 Nov bahwa HP-nya adalah **Redmi 10 `21061119AG` (seri `105397ba0507`)** yang terhubung tersebut; catatan model `24069PC21G` dari ronde sebelumnya keliru; APK sebelumnya `fefd7041…` dipakai smoke emulator putaran pertama |
| Binary backend diverifikasi | `/tmp/pam-api-head` (dibangun dari `1325d3d`/kode `6c941d9`) sha256 `3878814bbb3c3c5ba21fbe2802ca375299f4b1346a813662590e118558cf7ffe`; binary UAT loopback aktif sha256 `cbd882ea86cc684e3beb6fc38981205b226d2e70c4114e9d61db512f7824abc7` (backup biner lama: `pam-api-loopback.bak-20261125-auth`) |
| CI backend | `6c941d9` **success**; `1325d3d` **success** (docs); commit sebelumnya (`d42d7c4`, `aa33fb8`, `68f1ba4`, `b6ce917`) juga success |
| CI Android | tidak ada workflow CI di repo Android (tetap seperti sebelumnya) |

## Kebersihan repo

| Item | Hasil |
|---|---|
| Branch sementara | `feat/c2-android` (Android) dan `integration/2026-11-24` (backend), keduanya sudah merged ke `main`, dihapus dari lokal dan `origin`; tersisa `main` (kedua repo); tidak ada branch `develop` (klaim lama keliru) dan tidak ada branch sementara lain |
| Worktree | Satu worktree per repo, bersih; tidak ada worktree sementara |
| Temporary artifact | Fixture/log DB uji nominal di `/tmp` dibersihkan; binary/DB UAT yang masih dirujuk bukti dipertahankan |
| Tautan dokumen | Pemeriksaan tautan relatif Markdown di `README.md` + `docs/**`: 8 tautan rusak ditemukan dan diperbaiki, hasil akhir **0 rusak** |

## Perubahan auth pada verifikasi ulang ini (owner decision 25 November 2026)

- `POST /v1/accounts` sekarang terbuka tanpa kode undangan; field `inviteCode`/`invitationToken`
  pensiun dan ditolak `400 invalid_request`.
- `POST /v1/auth/google` langsung login **dan** mendaftar: identitas Google dengan email
  terverifikasi membuka akun yang emailnya sama atau membuat akun `USER` baru; email tidak
  terverifikasi `403 google_email_unverified`; identitas bentrok `409 google_identity_conflict`.
- E2E biner baru pada DB sementara: register tanpa kode **201**, body kode undangan lama **400**,
  login **201**. Detail keputusan/keamanan: [CR-009](../changes/CR_009_OPEN_GOOGLE_AUTH.md).
- Dampak pada gate: langkah lama "isi kode undangan" di runbook G36 tidak berlaku lagi; SSO live
  tetap gate pemilik.

## Tabel gate

| Gate | Status | Bukti (build/lingkungan) | Batas |
|---|---|---|---|
| Integritas repo pasca-merge | **PASS** | Android `main`=`origin`=`0d8affa` bersih (kode auth `a53f45e`, fix mode daftar `8de8f75`); backend `main`=`origin`=`1325d3d` bersih (kode auth `6c941d9`); CI backend kedua commit **success**; tidak ada force-push; SRS DOCX/PDF tidak diubah | — |
| FR-67 — backfill awal menahan antrean kategori; setelah selesai batch dibatasi | **PASS (diuji ulang pada build auth)** | Runtime terisolasi dibangun dari HEAD `1325d3d` (`/tmp/pam-api-head`), fake Gmail + classifier stub port 18091/18191: `GATE_MONTHS 1/13`; enqueue `200 {"pending":4}`; drain `state:"not_due"`; **stub 0 panggilan** saat backfill belum lengkap; `BACKFILL_COMPLETE_AT 51`; sesudahnya tepat **4** panggilan (batch `[2,2,1,2]`), poll berikutnya `not_due`; `FINAL_TX_COUNT 4` | Uji runtime terisolasi, bukan HP pemilik; log `/tmp/pm25_fr67_e2e_authfix2.log` |
| NFR-15 — tanggal masa depan ditolak; entri manual hari ini WIB berhasil; retry identik menghasilkan satu transaksi | **PASS (diuji ulang pada build auth)** | Binary HEAD `1325d3d` pada backend terisolasi port 18094, DB `/tmp` (akun sintetis): future `2026-11-27T00:00:00+07:00` → **400** `outside_retention`; hari ini date-only → **201** `tx_77d0f32e…` (`eventAt 2026-11-24T17:00:00Z` = 25 Nov 00:00 WIB); retry identik → **200** id sama; list tepat **1** baris | Transaksi probe sintetis di DB sementara, bukan data pemilik |
| G14 — drill retensi/hapus/restore pada fixture DB terpisah | **PASS (fixture, diuji ulang pada build auth)** | Harness `/tmp/pam-g14/drill_new.py` (kontrak registrasi terbuka) memakai binary HEAD `1325d3d`, DB/port terisolasi (18091/18092): **59/59** skenario lulus termasuk restore, audit, eligible counts; laporan `/tmp/G14_DRILL_AUTH_2026-11-25.md`; binary sha256 `3878814bbb3c3c5ba21fbe2802ca375299f4b1346a813662590e118558cf7ffe` | Drill pada **data pemilik tetap NOT RUN** (butuh izin + salinan eksplisit) |
| G14 — drill pada data pemilik | **NOT RUN** | — | Hanya pemilik yang dapat memberi izin dan menyalin DB |
| G25 — impor email Livin live ke akun pilot | **PASS (bukti ronde sebelumnya)** | Evidence ronde live: backfill 12/12, 914 transaksi unik, 0 duplikat; rekonsiliasi 769 impor + 145 tersimpan = 914 (`docs/evidence/GMAIL_LIVE_IMPORT_2026-11-24.md`, `GMAIL_POLLING_UAT_PHONE_2026-11-24.md`) | Tidak ada email baru pada verifikasi ini; perilaku "email baru saat app tertutup" tetap G36 |
| Satuan nominal impor email (laporan pemilik 25 Nov) | **PASS (perbaikan + verifikasi)** | Commit `d42d7c4`: `go vet` PASS, `go test ./... -count=1` PASS 11 paket, tes parser/format + round-trip fixture + migrasi 022 PASS, E2E fake Gmail PASS (`2_500_000`/`999_000`/`12_345_678` minor utuh), DB UAT lokal: 915 baris EMAIL diskalakan sekali, 10 baris MANUAL utuh, `schema_migrations`=22, `/healthz`+`/readyz` 200. **Bukti UI APK** (emulator-5556, backend terisolasi, data sintetis): Transaksi menampilkan `Rp25.000`, `Rp9.990`, `Rp123.456,78`; Ringkasan KELUAR `Rp158.446,78` (jumlah tepat) | Diterapkan ke UAT lokal, bukan produksi; analisis lama perlu dibuat ulang; uji UI memakai data sintetis di emulator, bukan HP/data pemilik; detail + screenshot `docs/evidence/AMOUNT_UNIT_BUGFIX_2026-11-25.md` |
| Registrasi terbuka + Google SSO langsung (CR-009) | **PASS (otomatis + E2E); SSO live NOT RUN** | Go `6c941d9`: register tanpa kode **201**, body `inviteCode` **400**, login **201** (DB sementara); perbaikan mode daftar `8de8f75` (Welcome → "Buat akun" kini langsung mode daftar; diverifikasi emulator dua kali); seluruh tes Go/Android lulus (rincian [evidence](../evidence/AUTH_OPEN_SSO_2026-11-25.md)); smoke API build baru: categories 200 (11), create transaksi 201, list 200, 6 path pensiun **404** | Uji Google live dengan akun Google asli **belum** dijalankan; butuh Test user OAuth consent screen + akun pemilik di perangkat |
| Migrasi non-destruktif & rute pensiun | **PASS** | 23 file SQL = **22 versi** migrasi (001–021, dengan `003.triggers.sql` bagian dari versi 003, plus 022 aditif); `TestSchemaHasExactly21Migrations` PASS dengan asersi 22 versi; diff `migrations/db.go` tidak menghapus migrasi lama; 8 path modul pensiun tetap **404** semua method (`TestRetiredModulesAreNotRouted` PASS). Distribusi baris EMAIL pemilik pasca-022: `gmail-livin-v1`=914, `gmail-livin-v2`=1 (semua tercakup migrasi; tidak ada NULL/legacy yang terlewat di UAT) | Migrasi 022 adalah perbaikan data aditif, bukan penghapusan; distribusi versi parser adalah agregat UAT, bukan data pribadi |
| UX kategori saat classifier dimatikan | **PASS (kode + unit test, build `13b7a2a`)** | `503 category_unavailable` kini dipetakan ke aturan konfigurasi berpesan "Kategorisasi AI belum diaktifkan di server ini…", bukan `backend_503`; tes `BackendErrorsCategoryTest` lulus, seluruh unit test debug + build APK lulus | Belum diuji ulang di HP untuk kondisi 503 (perlu tekan tombol yang memicu enqueue) |
| G36 — Run literal Android Studio, login/logout/relogin Google, Lewati/Tautkan Gmail + consent, email Livin baru saat app tertutup | **PARTIAL** (perangkat fisik Redmi 10 **terkonfirmasi milik pemilik**, tidak terkunci; APK terbaru `39f2cd6e…` terpasang 14:42 WIB; **login Google live terverifikasi di backend**: POST rute SSO 200 (1257 ms) + sesi auth baru 14:37:16 WIB, app menampilkan Ringkasan) | Login/logout/relogin Google di HP fisik terbukti pada build 24 Nov; APK auth+fix terpasang dan `adb reverse tcp:18090` dipulihkan; app dibuka di perangkat fisik menampilkan Ringkasan tanpa crash (data tidak ditelusuri). HP pemilik adalah Redmi 10 yang terhubung; Run literal tetap milik pemilik (menekan **Run** di Android Studio). Smoke emulator lengkap PASS (lihat baris smoke) | **Run literal lewat tombol Android Studio, logout/relogin berurutan, onboarding Gmail akun uji, dan email Livin baru saat app tertutup NOT RUN** — bagian pemilik: jalankan Run di Android Studio, tambahkan email pemilik sebagai Test user OAuth consent screen, lalu uji alur Gmail. Runbook: `docs/delivery/OWNER_G36_RUNBOOK_2026-11-25.md`; readiness read-only **exit 0** pada pemeriksaan akhir 25 Nov ±14:35 WIB untuk perangkat fisik yang terhubung (Redmi 10): `/readyz` 200, SSO nonce, rute Gmail, client ID di APK `a0498c29…`, Play Services, paket + `adb reverse` semua OK |
| G24 — deployment/kesiapan hosting | **NOT RUN** | Catatan kesiapan: `docs/delivery/DEPLOYMENT_READINESS_NOTE_2026-11-25.md` (HTTPS, polling 15 menit, backup/restore, rollback) | Deploy hanya atas instruksi terpisah dari pemilik |
| G29/G37 — biaya GCP Rp0 | **OPEN / NOT RUN** | `gcloud` tidak tersedia di mesin ini (exit 127); audit read-only sebelumnya `docs/evidence/GCP_READONLY_RP0_2026-11-24.md` | Verifikasi billing hanya lewat konsol pemilik |
| Kesiapan deploy (dokumen, bukan deploy) | **PASS (dokumen)** | `docs/delivery/DEPLOYMENT_READINESS_NOTE_2026-11-25.md`: checklist HTTPS, polling, backup, restore, rollback, env wajib | Tidak ada deployment yang dijalankan |
| OI-27 pemetaan akademik (CR-008) / OI-28 provisioning admin | **OI-28 DIPUTUSKAN; OI-27 OPEN** | OI-28: pemilik memutuskan registrasi terbuka + Google SSO langsung (CR-009); jalur ADMIN tetap hanya `pam-admin -promote <email>`. OI-27: pemetaan D.2/D.5/D.6/D.9 butuh pedoman asli + keputusan pemilik; memo `docs/delivery/DECISION_MEMO_OI27_OI28_2026-11-25.md` diberi addendum | OI-27 tetap keputusan pemilik sebelum klaim akademik |
| Smoke emulator build final | **PASS (data sintetis + backend terisolasi)** | Emulator-5556, APK `a0498c29…` (commit `8de8f75`): Welcome → "Buat akun" langsung mode daftar ("Buat akun PAM.", field Nama tampilan, tombol "Daftar dengan Google") **dua kali deterministik**; "Masuk" tetap mode masuk ("Masuk dengan Google"); login email/password sintetis `201`; Ringkasan Nov 2026 net **Rp88.466,78**, masuk **Rp123.456,78**, keluar **Rp34.990**; Transaksi **3 transaksi** (Rp123.456,78 / Rp9.990 / Rp25.000); Analisis consent-aware empty state (404 ditangani); Pengaturan tanpa Anggaran/Lampiran/Administrasi (0 kemunculan di semua dump); tidak ada crash (`AndroidRuntime:E` kosong) | Backend/DB/akun sintetis di `/tmp` (fake LLM, Gmail/Google SSO off), bukan HP/data pemilik; bukan pengganti UAT HP fisik/G36; bukti `/tmp/w-ui-smoke2-*` |

## Tindakan yang benar-benar hanya bisa dilakukan pemilik

1. Buka kunci HP, lalu jalankan build final lewat tombol **Run** Android Studio (konfigurasi `app`,
   varian `debug`, HP fisik) untuk menutup G36 Run literal.
2. Di Google Cloud Console, tambahkan email Google pemilik sebagai **Test user** pada OAuth consent
   screen (masih status Testing), lalu login/daftar lewat tombol Google dan selesaikan onboarding
   Gmail (Lewati/Tautkan) + consent pada akun uji.
3. Kirim satu email Livin alami saat aplikasi tertutup, lalu pastikan tepat satu transaksi masuk
   (G36/G25).
4. Tentukan OI-27 (pemetaan akademik CR-008) sebelum klaim akademik; OI-28 sudah diputuskan lewat
   CR-009 (registrasi terbuka + SSO langsung).
5. Buka konsol GCP untuk memastikan tagihan Rp0 (G29/G37).
6. Beri izin eksplisit + salinan DB bila menginginkan drill G14 pada data pemilik, dan instruksi
   terpisah bila menginginkan deployment produksi.
