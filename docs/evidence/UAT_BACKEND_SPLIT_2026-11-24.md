# UAT pemisahan backend Go → repo terpisah (parity gate) — 24 November 2026

Dokumen ini adalah **checklist parity + baseline** untuk pemisahan backend Go dari repo Android
`pam-app` ke repo `0037-Backend-Financial`, dan migrasi Gmail ke Gmail API. Ditulis oleh w-qa.
Status: **persiapan/baseline**; verifikasi end-to-end **belum dijalankan** karena PM masih
membersihkan tree Android (menunggu sinyal "tree Android sudah bersih dari backend-go").

Aturan bukti: setiap klaim di bawah menyebut **perintah persis** dan hasil yang benar-benar
diamati pada 24 Nov 2026 13:18–13:21 WIB. PASS hanya bila perintah dijalankan ulang dari clone
bersih dan hasilnya cocok. Jangan menyalin status dari dokumen delivery S2–S4 lama.

## 1. Baseline tercatat (24 Nov 2026, 13:18–13:21 WIB)

### 1.1 Repo Android — `pam-app`

- Path: `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app`
- Remote: `https://github.com/NDI05/0037_FinancialMobile.git`
- Branch: `feat/c2-android`
- HEAD: `cd0f91a2b06562ddf12e8fea1c4868e88de54007` — `2026-11-24 13:11:35 +0700` —
  `docs: resume PAM delivery with separate backend repository`
- Working tree: **bersih** (`git status --porcelain` = 0 baris)
- Ukuran tree (`git ls-files`): **934 file**; rincian teratas: `docs/` 425, `app/` 318,
  **`backend-go/` 93**, `backend/` (Node lama) 70, `scripts/` 13, `fixtures/` 4.
- Kode Android yang relevan: **176 file `*.kt`** di `app/src/main`.
- `backend-go/` **masih ada** di tree Android saat baseline → inilah yang sedang dipindahkan PM.

### 1.2 Repo backend Go — `0037-Backend-Financial`

- Path: `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/0037-Backend-Financial`
- Remote: `https://github.com/NDI05/0037-Backend-Financial.git`
- Branch: `main`
- HEAD: `327d8762b0c637a70f7e30f93ffbfd311b3a02ed` — `2026-11-24 13:17:58 +0700` —
  `chore(repo): bootstrap standalone backend repository`
- Working tree: **bersih**.
- Ukuran tree (`git ls-files`): **103 file**, **72 file `*.go`**, **18.422 baris Go**;
  paket: `cmd/api`, `cmd/pam-admin`, `internal/{analysis,auth,categoryllm,config,crypto,finance,httpapi,llm,store}`.
- Tambahan khusus repo standalone dibanding salinan di Android: `.env.example`, `.gitignore`,
  `.github/workflows/ci.yml`, `docs/API_CONTRACT.md`, `docs/GMAIL_API_CONTRACT.md`,
  4 fixture `fixtures/interop/*.json`, `scripts/go.sh`.

### 1.3 Parity snapshot Android↔repo backend (saat baseline)

Dibandingkan dengan meng-ekstrak commit (bukan working tree):

```
git -C pam-app archive HEAD:backend-go | tar -x -C /tmp/parity_a
git -C 0037-Backend-Financial archive HEAD | tar -x -C /tmp/parity_g
diff -rq /tmp/parity_a /tmp/parity_g
```

Hasil: **72 file `.go` dan 18.422 baris di kedua sisi**; 93 path tracked sama. Perbedaan isi
hanya 3 hal yang memang disengaja:

| Path | Perbedaan | Sebab |
|---|---|---|
| `README.md` | isi berbeda | README repo standalone (Gmail API, Pub/Sub, retensi) vs README salinan lama |
| `internal/auth/interop_test.go` | path fixture `../../../fixtures/...` → `../../fixtures/...` | fixture dipindah ke dalam repo backend |
| `internal/crypto/interop_test.go` | idem untuk `node-sealed.json` | idem |

Tidak ada perbedaan logika/kode selain ketiga hal di atas pada saat baseline. Ini **titik acuan
parity** yang harus dibuktikan ulang setelah PM selesai memindahkan tree.

### 1.4 Toolchain

- Go: **go1.27.4 darwin/arm64** di `pam-app/.toolchain/go/bin/go` (repo backend **tidak**
  membawa `.toolchain/`; `scripts/go.sh` memakai urutan `PAM_GOROOT` → `./.toolchain/go` → PATH).
- Perintah test resmi repo backend:
  `PAM_GOROOT=<path>/pam-app/.toolchain/go ./scripts/go.sh test ./... -count=1`
  (`scripts/go.sh` mengekspor `GOCACHE`/`GOMODCACHE` ke `.toolchain/go-cache` dan `.toolchain/go-mod`).

### 1.5 Verifikasi Go dari repo standalone — **dijalankan sekarang (PASS)**

```
cd 0037-Backend-Financial
PAM_GOROOT=/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app/.toolchain/go \
  ./scripts/go.sh vet ./...            # rc=0
… ./scripts/go.sh build ./...          # rc=0
… ./scripts/go.sh test ./... -count=1 -json > /tmp/go_test.json   # rc=0
```

Ringkasan hasil test (dihitung dari 709 event JSON): **11 paket**, **167 fungsi test**,
**0 skip**, **0 gagal**, `rc=0`. Ini baseline "hijau" yang harus direproduksi dari **clone bersih**.

### 1.6 Server dari repo standalone — **dijalankan sekarang (PASS)**

Build & jalankan dari repo backend dengan DB file terisolasi (bukan DB owner):

```
./scripts/go.sh build -o /tmp/wqa-split-18093/pam-api ./cmd/api
PORT=18094 DATABASE_URL=file:/tmp/wqa-split-18093/pam.db \
JWT_SECRET=<32+ chars sintetis> JWT_ISSUER=pam JWT_AUDIENCE=pam-android \
ACCESS_TOKEN_TTL_SECONDS=900 REGISTRATION_INVITE_CODE=pam-dev \
DATA_ENCRYPTION_KEY=<base64 32 byte> DATA_KEY_VERSION=1 \
CATEGORY_LLM_ENABLED=false GMAIL_SYNC_ENABLED=false \
/tmp/wqa-split-18093/pam-api
```

Hasil teramati:

| Cek | Hasil |
|---|---|
| `GET /healthz` | **200** `{"status":"ok","uptimeSeconds":…}` |
| `GET /readyz` | **200** `{"status":"ready"}` |
| Migrasi DB | 17 file SQL di `internal/store/migrations`, **16 baris** di `schema_migrations` |
| Shutdown | log `shutdown_requested`, proses berhenti bersih |
| Log | ada WARN "development secrets are in use" (diharapkan untuk dev); tidak ada secret tercetak |

Catatan operasional: `ACCESS_TOKEN_TTL_SECONDS` divalidasi **60..900** (nilai 3600 membuat server
gagal start — sempat teramati dan sudah dikoreksi ke 900); port **18093 sedang dipakai proses
Python lain**, jadi baseline memakai **18094**. Migrasi ikut binary (embed), tidak butuh file
migrasi eksternal. Android di bawah ini tidak dijalankan karena PM masih mengubah tree.

## 2. Checklist parity gate (diisi setelah PM menyatakan tree Android bersih)

Semua langkah dijalankan berurutan; status per butir: **PASS / PARTIAL / FAIL / NOT RUN** +
bukti (perintah + output ringkas + hash + artefak). Artefak disimpan di
`docs/evidence/assets/pm_backend_split_2026-11-24/` **setelah** PM memberi izin
(sebelum itu hanya dokumen ini yang diubah). Redaksi token/JWT/secret sebelum menyimpan.

| ID | Butir | Metode / perintah persis | Kriteria PASS | Status |
|---|---|---|---|---|
| P1 | Dua URL + hash repo | `git -C pam-app remote -v; git -C pam-app rev-parse HEAD; git -C 0037-Backend-Financial remote -v; git -C 0037-Backend-Financial rev-parse HEAD`; `git push --dry-run`/`git ls-remote origin` untuk memastikan hash terkirim | Kedua remote sama dengan baseline §1; HEAD Android + HEAD backend tercatat; hash di remote = hash lokal | NOT RUN |
| P2 | Clone bersih repo backend | `git clone <url-backend> /tmp/pam-split-clone && git -C /tmp/pam-split-clone log -1 --format=%H`; bandingkan dengan §1.2 | Clone berhasil, HEAD = `327d8762…` (atau commit PM terbaru yang dilaporkan), working tree bersih | NOT RUN |
| P3 | Go vet/build/test dari clone bersih | `PAM_GOROOT=<pam-app>/.toolchain/go /tmp/pam-split-clone/scripts/go.sh vet ./...`; `… build ./...`; `… test ./... -count=1 -json` | rc=0 untuk ketiganya; **167 fungsi test**, 0 gagal, 0 skip; jumlah paket 11 (perubahan sah hanya bila dilaporkan PM) | NOT RUN |
| P4 | `backend-go/` hilang dari tree Android tanpa merusak build | `test ! -e pam-app/backend-go && echo absent`; `git -C pam-app ls-files 'backend-go' \| wc -l`; `./scripts/build.sh split-check -P PAM_BACKEND_BASE_URL=http://10.0.2.2:18094` (build debug) | `backend-go/` absen (0 file tracked); build debug **sukses**; tidak ada task Gradle/script yang menunjuk `backend-go` (grep `scripts/`, `settings.gradle.kts`, `app/build.gradle.kts`) | NOT RUN |
| P5 | Server Go dijalankan dari repo backend (bukan dari pam-app) | Jalankan P6 server dari `/tmp/pam-split-clone` dengan DB terisolasi; `curl -s -w '%{http_code}' /healthz /readyz` | `/healthz` 200, `/readyz` 200, log menunjukkan port yang dipakai dan tidak ada error migrasi | NOT RUN |
| P6 | Migrasi DB terisolasi | `rm -rf /tmp/pam-split-db && mkdir -p /tmp/pam-split-db`; jalankan server `DATABASE_URL=file:/tmp/pam-split-db/pam.db`; `sqlite3 …/pam.db 'select count(*), max(version) from schema_migrations'` | DB baru dibuat, migrasi 16 baris (atau sesuai laporan PM), server `readyz` 200; tidak menyentuh DB owner/UAT `:18090` | NOT RUN |
| P7 | APK debug login + baca data dari server repo backend | Install debug dari langkah P4 ke emulator-5556; login akun sintetis UAT; buka Ringkasan + Transaksi; verifikasi via logcat/correlationId bahwa request masuk ke server repo backend | Login sukses, Ringkasan/Transaksi menampilkan data yang sama seperti baseline (jumlah transaksi + angka ringkasan), log server repo backend mencatat request dengan correlationId; tidak ada request ke `:18090` | NOT RUN |
| P8 | Rollback ke pasangan baseline | Pasangan: Android `cd0f91a2…` (dengan `backend-go/` masih ada) + server dari `pam-app/backend-go` HEAD `cd0f91a2`; dokumentasikan langkah restore: checkout commit Android, build debug, jalankan server dari `pam-app/backend-go` pada port terpisah, ulangi smoke login+read | APK dari pasangan baseline login & membaca data dari server pasangan baseline; langkah restore tertulis dan bisa diulang | NOT RUN |
| P9 | Rekonsiliasi Gmail API (bila PM menyerahkan) | Ikuti `docs/GMAIL_API_CONTRACT.md` di repo backend: cek endpoint OAuth/Pub/Sub + `GMAIL_SYNC_ENABLED` mati/hidup; jangan pakai kredensial owner nyata | Endpoint + config terdokumentasi sinkron dengan kontrak; mode OFF tidak memanggil Google; tanpa secret di repo | NOT RUN |

### 2.1 Urutan eksekusi yang akan dipakai

1. P1–P2 (hash & clone bersih) → 2. P3 (vet/build/test) → 3. P6 (DB terisolasi) → 4. P5
(`/healthz` + `/readyz`) → 5. P4 (build Android tanpa source backend) → 6. P7 (login+read E2E) →
7. P8 (rollback) → 8. P9 (Gmail API, hanya bila diserahkan PM).

### 2.2 Batas & larangan selama verifikasi

- **Jangan** menyentuh backend UAT owner `:18090`, DB Turso owner, akun/data owner; verifikasi
  memakai port **>18090** (mis. 18094/18095) + DB file di `/tmp`.
- **Jangan** commit/push/checkout destruktif atau mengubah kode; lane ini hanya menguji.
  Perubahan file dibatasi pada dokumen ini sampai PM memberi sinyal tree bersih.
- **Jangan** menyalin secret/token/JWT mentah ke repo; redaksi `<redacted-token>`.
- Jangan menjalankan build Android penuh selama PM masih mengubah tree (baseline ini sengaja
  belum build).
- Emulator dipakai bergiliran; wajib lepas lock `/tmp/pam-emu5556.lock` setelah selesai.

## 3. Status gate ringkas (saat baseline, 24 Nov 13:21 WIB)

| Gate | Status | Keterangan |
|---|---|---|
| Baseline tercatat | **PASS** | HEAD Android `cd0f91a2…` + HEAD backend `327d8762…`, tree bersih, ukuran file, parity snapshot |
| Go standalone (vet/build/test) | **PASS (baseline)** | 11 paket / 167 test / 0 gagal dari repo backend saat baseline |
| Server standalone `/healthz` + `/readyz` | **PASS (baseline)** | 200/200, migrasi 16 baris, DB terisolasi, shutdown bersih |
| Backend pindah dari Android (P1–P4) | **NOT RUN** | menunggu PM menyatakan tree Android bersih |
| E2E APK ↔ server repo backend (P5–P7) | **NOT RUN** | butuh tree Android bersih + build debug |
| Rollback pasangan baseline (P8) | **NOT RUN** | disiapkan metodenya |
| Gmail API parity (P9) | **NOT RUN** | menunggu penyerahan PM |

Kesimpulan: **belum ada gate yang bisa dinaikkan**. Baseline membuktikan repo backend standalone
sudah bisa di-vet/build/test dan server-nya sehat dengan DB terisolasi; semua klaim parity
end-to-end masih **NOT RUN** sampai PM memberi sinyal dan verifikasi dijalankan ulang dari clone
bersih sesuai §2.
