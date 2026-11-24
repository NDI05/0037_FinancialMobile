# RP0 — Audit Read-Only GCP (G29/G37) — 2026-11-25

- **Hasil:** **G29 = NOT RUN / OPEN**, **G37 = NOT RUN / OPEN** (bukan PASS)
- **Sifat sesi:** read-only. Tidak ada perubahan Billing, tidak ada pembuatan resource GCP, tidak ada kredensial yang dicetak, tidak ada write/commit ke repo mana pun.
- **Alasan utama OPEN:** sesi ini **tidak memiliki akses Billing/konsol GCP** sehingga klaim “Rp0 terverifikasi” tidak bisa dibuktikan. Audit kode/konfigurasi hanya membuktikan **tidak ada layanan GCP berbayar di jalur default**, bukan bahwa tagihan Rp0.

## 1. Batas akses yang terverifikasi

| Pemeriksaan | Hasil | Bukti |
| --- | --- | --- |
| `gcloud` | tidak tersedia (`command -v gcloud` exit 1) | tooling sesi |
| `gsutil` | tidak tersedia (exit 1) | tooling sesi |
| `bq` | tidak tersedia (exit 1) | tooling sesi |
| Kredensial GCP | `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_QUOTA_PROJECT`, `CLOUDSDK_CONFIG`, `GCP_PROJECT` semuanya **UNSET** (nama variabel saja yang diperiksa) | env sesi |
| Konfigurasi SDK | `~/.config/gcloud` **tidak ada** | filesystem |
| Sesi browser terautentikasi | tidak tersedia di sesi agent | batas sesi |

Konsekuensi: Billing API, quota Gmail, daftar resource, dan status Pub/Sub **tidak dapat dibaca**. Gate Rp0 tidak boleh ditutup berdasarkan audit ini.

## 2. Audit read-only: konfigurasi backend (commit basis yang dibaca)

Repository backend di head **`1d020bc`** (read-only; working tree bersih saat audit).

| Area | Temuan | Dampak biaya GCP |
| --- | --- | --- |
| `GMAIL_ENABLED` | default **false** (`internal/config/config.go`) | tidak ada traffic Gmail |
| `GMAIL_MODE` | default **polling**; `GMAIL_PUBSUB_TOPIC` default kosong dan **diabaikan** saat polling (warning eksplisit di config) | tidak ada Pub/Sub, tidak ada push endpoint |
| Mode `push` | opt-in; butuh `GMAIL_PUBSUB_TOPIC`, `GMAIL_PUSH_AUDIENCE`, `GMAIL_PUSH_SERVICE_ACCOUNT` | hanya jika pemilik mengaktifkan |
| Dependensi Go (`go.mod`) | `libsql-client-go` (Turso/libSQL, non-GCP), `modernc.org/sqlite`, `golang-jwt`, `x/crypto`; **tidak ada SDK Google Cloud** | tidak ada billing lewat SDK |
| LLM/classifier | provider default `fake`; Azure classifier opt-in via env `AZURE_*` | non-GCP |
| Retention/worker | default report-only; delete butuh env + token + konfirmasi manual | tanpa layanan GCP |

## 3. Audit read-only: repo dan artefak deploy

| Pemeriksaan | Hasil |
| --- | --- |
| Referensi `gcloud`/`gsutil`/`bq`/`cloudbuild` di `scripts/` backend & Android | **tidak ada** |
| `Dockerfile`, Terraform (`*.tf`), `cloudbuild*`, `app.yaml`, `.gcp.*` di repo backend | **tidak ada** |
| File service-account/key-like di repo (`*service*account*.json`, `*credentials*.json`, `gcloud*`) | **tidak ada** |
| Rencana deploy (`pam-app/docs/plans/MULTI_BANK_AND_GO_DEPLOYMENT_PLAN.md`) | Cloud Run default **dikeluarkan** dari target pilot; pilot memakai host biaya minimum atau UAT lokal loopback |
| Android (`pam-app`) | tidak ada Firebase/Crashlytics/SDK Google Cloud; hanya `androidx.credentials-play-services-auth` (library client-side, tidak menagih GCP) |
| Doc setup Gmail (`GMAIL_API_SETUP.md`, `GMAIL_OWNER_UAT_RP0.md`) | Pub/Sub hanya untuk mode push opt-in dan **tidak dibuat** untuk mode polling; project uji GCP masih **Billing tertaut** |

Catatan: Gmail API/OAuth adalah produk GCP dengan kuota gratis, tetapi tanpa akses Billing/kuota **pemakaian nyata tidak terukur** — karena itu G29 tetap OPEN.

## 4. Audit read-only: runtime

| Item | Hasil |
| --- | --- |
| Proses UAT lokal | port **127.0.0.1:18090** LISTENING (1 proses) |
| `GET /healthz` | HTTP 200 `{"status":"ok"}` |
| `GET /readyz` | HTTP 200 `{"status":"ready"}` |
| Data/endpoint terautentikasi | **tidak diakses** (menghindari data pemilik) |
| Env proses / file profil privat (`~/.config/pam/*`) | hanya keberadaan file yang diperiksa; isi/secret **tidak dibaca dan tidak dicetak** |

Probe runtime hanya health endpoint publik-loopback; tidak ada bukti resource GCP berbayar dari proses yang berjalan (konfigurasi UAT dipandu doc: polling, SQLite lokal, loopback, Azure/classifier nonaktif).

## 5. Status gate (jujur)

| Gate | Status | Alasan |
| --- | --- | --- |
| **G29 — target biaya GCP Rp0** | **NOT RUN / OPEN** | Billing masih tertaut; sesi tidak punya akses Billing/konsol untuk mengukur tagihan/kuota nyata. Audit kode = tidak ada layanan berbayar di jalur default (bukan bukti tagihan Rp0). |
| **G37 — gate SSO/Gmail live/Rp0 terpisah** | **NOT RUN / OPEN** | Billing belum dilepas, tidak ada pengukuran kuota nyata, dan tidak ada kredensial GCP di sesi ini; laporan SSO/Gmail/Rp0 tetap dipisah sesuai dokumen gate. |

## 6. Tindakan yang tersisa (untuk pemilik, bukan QA)

1. Buka konsol GCP → Billing: verifikasi project uji masih tertaut/tidak, catat status final.
2. Putuskan: lepas Billing dari project uji **atau** siapkan project baru tanpa Billing untuk bukti Rp0.
3. Verifikasi tidak ada resource berbayar (Pub/Sub topic/subscription, Cloud Run, VM, bucket) di project tersebut.
4. Catat project ID final di laporan gate (jangan tempel secret/test-user di chat/commit).
5. Setelah Billing beres **dan** UAT live Gmail lulus, barulah G29/G37 bisa dipertimbangkan PASS.

QA **tidak** mengubah Billing, tidak menghapus/membuat resource, dan tidak memakai kredensial apa pun.

## 7. Bukti perintah (exit code)

| Perintah | Exit code | Ringkasan |
| --- | --- | --- |
| `command -v gcloud` | 1 | tidak ditemukan |
| `command -v gsutil` | 1 | tidak ditemukan |
| `command -v bq` | 1 | tidak ditemukan |
| `ls ~/.config/gcloud` | non-zero | direktori tidak ada |
| `grep -E 'pubsub|cloud run|gcloud|billing|...' backend` | 0 (match docs only) | hanya referensi dokumentasi/pedoman, bukan implementasi |
| `grep -E 'gcloud|gsutil|bq|cloudbuild' scripts/` | 1 (0 match) | tidak ada pemanggilan CLI GCP |
| `find ... Dockerfile/*.tf/cloudbuild/credential-json` | 0 (0 hasil) | tidak ada artefak deploy GCP |
| `curl 127.0.0.1:18090/healthz` | 0 | HTTP 200 `ok` |
| `curl 127.0.0.1:18090/readyz` | 0 | HTTP 200 `ready` |
| `git status --porcelain` (backend) | 0 | 0 baris; HEAD `1d020bc` (tanpa perubahan dari QA) |
| `git status --porcelain` (pam-app) | 0 | 20 baris perubahan WIP worker lain; **tidak ada** perubahan dari sesi QA ini (hanya read) |

## 8. Kesimpulan

Audit read-only menunjukkan **jalur default PAM tidak memakai layanan GCP berbayar** (polling tanpa Pub/Sub, tanpa SDK GCP, tanpa artefak deploy GCP; DB Turso, classifier Azure opt-in, runtime UAT loopback). Namun **G29/G37 tetap NOT RUN / OPEN** karena (a) Billing masih tertaut menurut dokumen pemilik, dan (b) sesi ini tidak punya akses Billing/konsol untuk membuktikan tagihan Rp0 serta kuota Gmail nyata. Tidak ada perubahan Billing/resource GCP atau write repo yang dilakukan.
