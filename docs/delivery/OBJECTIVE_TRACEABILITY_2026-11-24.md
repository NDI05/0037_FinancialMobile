# Traceability Objective → Bukti → Gate (24 Nov 2026)

Dokumen ini memetakan setiap butir mandat PM ke implementasi, bukti uji, dan gate yang
bersangkutan. Status gate lengkap ada di
[`FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md`](FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md);
angka final: **29 PASS / 5 PARTIAL / 4 NOT RUN**.

## Backend Go (mode polling Rp0)

| Butir mandat | Implementasi | Bukti | Gate |
|---|---|---|---|
| Polling tanpa Pub/Sub | worker Gmail berbasis loop polling; tidak ada registrasi Pub/Sub | `docs/GMAIL_API_SETUP.md` §mode polling; harness §9 (tanpa topic/watch) | G26, G27 |
| Callback loopback HTTP **opt-in** | `GMAIL_ALLOW_LOOPBACK_REDIRECT=true` hanya menerima host `127.0.0.1` + path callback saat `APP_ENV` loopback | `internal/config/config.go` (validasi redirect), harness kontrak | G5, G26 |
| Delta 15 menit per owner | `GMAIL_DELTA_INTERVAL_SECONDS=900` (default), `SyncCooldown` 60 s | `internal/config/config.go`, `config_test.go`; log live `gmail_sync_pass` | G9, G26 |
| Backfill bertahap bergiliran | rotasi bulan + batas per pass, resume dari cursor | `internal/gmail/worker.go`; evidence §3 (12/12 bulan) | G8, G25 |
| "Sinkronkan sekarang" queue + cooldown/coalesce/restart-safe | `POST /v1/gmail/sync` → 202, job tunggal di-coalesce, state disimpan di DB | Harness §9; **UAT HP 24 Nov 21:05–21:15** (202, UI progress, failures=0) | G25, G26, G28 |
| Recovery history 404 | cursor patokan + rentang retensi + timestamp Unix, token recovery bertanda `recover:v1:` | `internal/gmail/worker.go` (recovery token, resume page) | G9, G26 |
| Token reuse / prefilter metadata / dedupe sebelum fetch | `source_ref_hash` sealed, batch dedupe, prefilter label metadata | `internal/store/imports.go`, evidence §3 (914 unik, 0 duplikat) | G10, G25 |
| Rate limit & 403/429 vs invalid_grant | klasifikasi retryable vs re-auth, backoff; 16 `gmail_provider_retry` ditangani | evidence §3 baris "Rate limit Google" | G9, G26 |
| Parser & consent tetap | parser Livin v2 (guard PAN/field/referensi), scope `gmail.readonly` terpisah, consent AI terpisah | `e3b84b4`, `e19477b`, `c69cf57`; audit ronde 2–3 | G22, G25 |

## Android

| Butir mandat | Implementasi | Bukti | Gate |
|---|---|---|---|
| Bottom sheet status server | sheet "Status pembaruan": status, percobaan terakhir, terakhir berhasil, jadwal berikutnya | UAT HP 24 Nov (snapshot UI) | G16, G28 |
| Tombol "Sinkronkan sekarang" | tombol di sheet → `POST /v1/gmail/sync`, state "Sedang sinkron" | UAT HP 24 Nov (202 → progress → idle) | G25, G28 |
| Progress / percobaan / berhasil / tautkan ulang | status job, pesan "Perlu perhatian", alur tautkan ulang | G34 evidence, UAT HP | G28, G34 |
| Endpoint 18090 via `adb reverse` | `adb reverse tcp:18090` + base URL debug | preflight UAT exit 0 | G21, G28 |
| SSO Google via Credential Manager | bottom sheet/entry Google, nonce + link akun lama | unit/HTTP SSO + fixture perangkat; contract harness = bukti Gmail, bukan UI SSO | G30–G35 |

## Profil privat, panduan, dan dokumen

| Butir mandat | Status |
|---|---|
| Profil privat mode 600 di luar repo (`GMAIL_CLIENT_ID/SECRET` owner) | **Selesai** — `~/.config/pam/*` mode 700/600, tidak ter-track git |
| Panduan test user + Web OAuth client | `docs/GOOGLE_SSO_OWNER_SETUP.md` (repo Android), `docs/GMAIL_OWNER_UAT_RP0.md` (repo backend `0037-Backend-Financial`) |
| README / PROGRESS_DAILY / matriks gate / panduan UAT / CR-007 | Diperbarui tiap ronde; CR-007 mencakup push→polling 15 m dan syarat backend hidup |
| Audit independen → kirim temuan → uji + audit ulang | Tiga audit read-only (ronde 7, 2, 3); temuan P1 PAN ditutup `e19477b`/`c69cf57` dan diverifikasi ulang |
| Push tiap commit, integrasi branch worker, hapus branch, `main` utuh | Semua commit dipush; tidak ada branch worker tersisa; `main` backend `327d876`, Android `c10ca67` |

## Gate yang masih menunggu pemilik

| Gate | Status | Aksi |
|---|---|---|
| **G36** UAT SSO HP fisik + Run literal Android Studio | PARTIAL | Login Google nyata + sesi PAM + binding identitas **sudah terbukti** di HP fisik 24 Nov 21:49–21:50 WIB ([bukti agregat](../evidence/SSO_LIVE_PHONE_2026-11-24.md)). Sisa: tekan **Run** literal konfigurasi `app` (build sekarang dari `adb install`, `lastUpdateTime` 19:28:04), lalu uji onboarding Gmail tautkan/skip |
| **G29 / G37** GCP Rp0 | OPEN | Lepas Billing dari project pilot, verifikasi tidak ada resource berbayar; `gcloud` tidak tersedia di host sehingga tidak bisa diperiksa agen |
| **G24** Merge/deploy/SRS resmi | NOT RUN | Keputusan pemilik (branch integrasi siap) |
| **G14** Hapus data nyata (retensi) | NOT RUN | Keputusan pemilik atas data live 914 baris |
