# UAT pemisahan backend Go → repo terpisah (parity gate) — 24 November 2026

Dokumen gate untuk pemisahan backend Go dari repo Android `pam-app` ke repo
`0037-Backend-Financial`, beserta serah-terima APK debug yang membaca data dari server yang
dijalankan **dari repo backend** (bukan dari source di repo Android). Ditulis oleh w-qa.

Aturan bukti: setiap status menyebut **perintah persis**, hash, dan hasil yang benar-benar
diamati di mesin ini pada 24 Nov 2026 13:18–13:50 WIB. Semua verifikasi memakai port terisolasi
dan DB file di `/tmp`; backend UAT pemilik (`:18090`), DB Turso pemilik, dan akun pemilik **tidak
disentuh**. Artefak mentah (dump UI, screenshot, log server) berada di
`/tmp/pam-split-run/artifacts/` dan `/tmp/pam-split-run/server.log` (tidak di-commit; sesi ini
hanya meng-commit dokumen ini).

## 1. Baseline & state saat verifikasi

### 1.1 Repo Android — `pam-app`

- Remote: `https://github.com/NDI05/0037_FinancialMobile.git`, branch `feat/c2-android`.
- HEAD saat verifikasi: **`b5ebf192bc49bc6bb1029f69f9105e755686c1df`** (2026-11-24 13:26:13 +0700,
  "chore(repo): drop stray Go binary and ignore build outputs"); `git ls-remote origin
  refs/heads/feat/c2-android` = hash yang sama.
- Riwayat pemisahan: `cd0f91a` (resume pemisahan) → `70d1535` (source `backend-go/`, fixture
  Node, `backend/` lama, docs/scripts keluar; **binary `backend-go/api` 19 MB ikut terbawa**) →
  `b5ebf19` (hapus binary tersebut + ignore `bin/`, `.DS_Store`).
- Tracked file: **769** (sebelum pemisahan 934). `git ls-files 'backend-go'` = **0**;
  direktori `backend-go/` tidak ada di checkout. Kode Android `app/src/main` tetap utuh.
- Apakah binary 19 MB masih ada di history? Ya, di commit `70d1535` (tidak di-rewrite karena
  larangan force-push). Ini **temuan historis, bukan blocker gate**: tree aktif bersih dan
  `.gitignore` menutup output build.

### 1.2 Repo backend Go — `0037-Backend-Financial`

- Remote: `https://github.com/NDI05/0037-Backend-Financial.git`, branch `main`.
- HEAD: **`327d8762b0c637a70f7e30f93ffbfd311b3a02ed`** (2026-11-24 13:17:58 +0700); hash remote
  sama.
- Tracked 103 file, 72 file `*.go` / 18.422 baris, 11 paket.
- **Catatan penting:** working tree lokal repo backend **dirty** dengan implementasi Gmail API
  yang belum di-commit (`internal/gmail/`, `internal/httpapi/handlers_gmail.go`,
  `internal/store/gmail.go`, `internal/store/migrations/017.sql`, serta perubahan
  `config`/`server`/`fingerprint`/`imports`/`main`). Verifikasi pada dokumen ini memakai
  **clone bersih dari remote HEAD `327d8762`**, sehingga implementasi Gmail API tersebut
  **tidak ikut diuji** (lihat P9).
- Toolchain: clone tidak membawa `.toolchain/`; perintah memakai
  `PAM_GOROOT=/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app/.toolchain/go`
  (go1.27.4 darwin/arm64).

### 1.3 Hasil ringkas

Semua item yang diminta Lead dieksekusi. PASS: P1–P7; PARTIAL: P8 (build/health PASS, restore DB
produksi NOT RUN); NOT RUN: P9 (Gmail API).

| ID | Butir | Status | Bukti ringkas |
|---|---|---|---|
| P1 | Dua URL + hash + sinkron remote | **PASS** | Android `b5ebf19…` = remote; backend `327d8762…` = remote |
| P2 | Clone bersih repo backend | **PASS** | `git clone` → HEAD `327d8762…`, 103 file, tree bersih |
| P3 | Go vet/build/test dari clone | **PASS** | vet/build/test rc=0; 11 paket, **167 test**, 0 skip, 0 fail |
| P4 | Tree Android tanpa source backend + build debug | **PASS** | `backend-go` 0 file; `:app:assembleDebug --rerun-tasks` rc=0; APK sha256 `ef183b75…` |
| P5 | Server dari repo backend, `/healthz`+`/readyz` | **PASS** | binary dari clone; health 200, ready 200, port 18095 (isolated) |
| P6 | Migrasi DB terisolasi | **PASS** | DB baru `/tmp/pam-split-run/db/pam.db`; `schema_migrations` **16 baris**, max version 16 |
| P7 | APK login + baca data dari server repo backend | **PASS** | login user sintetis; Ringkasan **Rp22.222,21 / 2 transaksi**; Transaksi 2 baris `Rp12.345,67` & `Rp9.876,54` |
| P8 | Rollback pasangan baseline | **PARTIAL** | server dari `git show cd0f91a:backend-go` health/ready 200 (DB 16 migrasi); APK `cd0f91a` build rc=0 sha256 `ef183b75…`. **Restore DB produksi/skenario rollback nyata NOT RUN** (butuh otorisasi pemilik) |
| P9 | Gmail API/Pub/Sub parity | **NOT RUN** | implementasi Gmail masih WIP uncommitted di repo backend; HEAD publik belum punya `internal/gmail/` maupun endpoint Gmail |

## 2. Metode & perintah persis

Semua perintah dijalankan dari shell dengan `PAM_GOROOT` seperti §1.2; Go memakai
`GOCACHE=pam-app/.toolchain/go-cache`, `GOMODCACHE=pam-app/.toolchain/go-mod`.

### P1 — URL, hash, sinkron remote

```
git -C pam-app remote get-url origin                # https://github.com/NDI05/0037_FinancialMobile.git
git -C pam-app rev-parse HEAD                        # b5ebf192bc49bc6bb1029f69f9105e755686c1df
git -C pam-app ls-remote origin refs/heads/feat/c2-android
git -C 0037-Backend-Financial remote get-url origin  # https://github.com/NDI05/0037-Backend-Financial.git
git -C 0037-Backend-Financial rev-parse HEAD         # 327d8762b0c637a70f7e30f93ffbfd311b3a02ed
git -C 0037-Backend-Financial ls-remote origin refs/heads/main
```

Hasil: keempat hash remote cocok dengan HEAD lokal (Android feat/c2-android & backend main).

### P2 — clone bersih

```
rm -rf /tmp/pam-split-clone
git clone https://github.com/NDI05/0037-Backend-Financial.git /tmp/pam-split-clone
git -C /tmp/pam-split-clone log -1 --format='%H %s'   # 327d8762… chore(repo): bootstrap standalone backend repository
git -C /tmp/pam-split-clone status --porcelain        # (kosong)
git -C /tmp/pam-split-clone ls-files | wc -l          # 103
```

### P3 — vet / build / test dari clone

```
cd /tmp/pam-split-clone
export PAM_GOROOT=/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app/.toolchain/go
./scripts/go.sh vet ./...        # rc=0
./scripts/go.sh build ./...      # rc=0
./scripts/go.sh test ./... -count=1 -json > /tmp/clone_test.json   # rc=0
```

Dihitung dari event `Action=="run"` unik: **11 paket, 167 fungsi test, 0 skip, 0 fail**.

### P4 — Android tanpa source backend + build debug

```
git -C pam-app ls-files 'backend-go' | wc -l     # 0
ls pam-app/backend-go                            # tidak ada
git -C pam-app rev-parse HEAD                    # b5ebf19…
cd pam-app
./scripts/build.sh :app:assembleDebug --rerun-tasks -P PAM_BACKEND_BASE_URL=http://127.0.0.1:18090
# BUILD SUCCESSFUL in 55s; 43 actionable tasks: 43 executed
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
# ef183b753f798bffed9f7e15100d9c0b6413c702f87008b28314f17c3e6f2653
grep PAM_BACKEND_BASE_URL app/build/generated/source/buildConfig/debug/com/pam/recap/BuildConfig.java
# = "http://127.0.0.1:18090"
```

APK yang sama kemudian di-install ulang ke emulator-5556 (`adb install -r`) dan tetap membaca
data server (lihat P7). Menarik: APK hasil build HEAD `b5ebf19` **byte-identik** dengan APK yang
dibangun terpisah dari baseline `cd0f91a` (sha256 sama) — commit pemisahan repo tidak mengubah
source aplikasi Android.

### P5 / P6 — server + DB dari clone (port terisolasi)

Catatan port: `:18090` dipakai backend UAT pemilik (`pam-azure`, PID 35599) dan `:18091` dipakai
proses backend lain (`pam-be-me`, PID 33035). Karena backend pemilik tidak boleh diganggu,
server uji dijalankan di **host port 18095**, lalu emulator diarahkan lewat
`adb reverse tcp:18090 tcp:18095` sehingga APK (base URL `http://127.0.0.1:18090`) tetap
mencapai server uji.

```
cd /tmp/pam-split-clone
./scripts/go.sh build -o /tmp/pam-split-run/pam-api ./cmd/api
rm -f /tmp/pam-split-run/db/pam.db*
PORT=18095 APP_ENV=development \
 DATABASE_URL=file:/tmp/pam-split-run/db/pam.db \
 JWT_SECRET=<dummy-lokal-lama-diredaksi> \
 JWT_ISSUER=pam-api JWT_AUDIENCE=pam-android ACCESS_TOKEN_TTL_SECONDS=900 \
 REGISTRATION_INVITE_CODE=pam-dev DATA_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
 DATA_KEY_VERSION=1 CATEGORY_LLM_ENABLED=false GMAIL_SYNC_ENABLED=false \
 /tmp/pam-split-run/pam-api
curl -s -w ' [%{http_code}]' http://127.0.0.1:18095/healthz   # {"status":"ok"} [200]
curl -s -w ' [%{http_code}]' http://127.0.0.1:18095/readyz    # {"status":"ready"} [200]
sqlite3 /tmp/pam-split-run/db/pam.db 'select count(*), max(version) from schema_migrations;'
# 16|16
```

Server mencatat `started` port 18095, migrasi berjalan otomatis pada DB baru (17 file SQL embed,
16 baris `schema_migrations`), dan berhenti bersih (`shutdown_requested`) saat dimatikan.
`ACCESS_TOKEN_TTL_SECONDS` wajib 60..900; `DATA_ENCRYPTION_KEY` base64 32 byte.

### P7 — seeding lewat API + login UI + baca data

```
# akun sintetis
POST /v1/accounts  {"email":"split.qa.133112@pam-uat.test","password":"split-qa-pass-2026",
                    "displayName":"Split QA","inviteCode":"pam-dev"}          → 201
POST /v1/sessions  {"email":…,"password":…}                                 → 201
# kategori + 2 transaksi (owner sintetis, BUKAN data owner asli)
POST /v1/categories {"code":"splitqa","label":"Split QA Makanan","kind":"EXPENSE",
                    "attributes":{"sumber":"split-uat"}}                    → 201 cat_42d30f68…
POST /v1/transactions (Idempotency-Key) amountMinor=1234567, eventAt=2026-11-10T03:15:00Z → 201 tx_2707387…
POST /v1/transactions (Idempotency-Key) amountMinor=987654,  eventAt=2026-11-18T05:40:00Z → 201 tx_23f51d5…
```

Di emulator: install APK P4 → `adb reverse tcp:18090 tcp:18095` → login akun sintetis lewat UI
(form "Masuk", email + kata sandi) → layar persetujuan → "Lanjut tanpa menghubungkan Gmail" →
Ringkasan → tab Transaksi.

Hasil UI (dump + screenshot di `/tmp/pam-split-run/artifacts/`):

- `09_ringkasan.{xml,png}`: ARUS KAS BERSIH **Rp22.222,21**, KELUAR Rp22.222,21,
  kategori "Split QA Makanan" **2 transaksi**, 0 belum berkategori — angka = 1.234.567 + 987.654
  minor dari server uji.
- `14_transaksi_fixed.{xml,png}` dan `15_transaksi_after_reinstall.{xml,png}`: tab Transaksi
  menampilkan **2 transaksi** — "Split QA Transaksi 1" Rp12.345,67 (10 Nov) dan
  "Split QA Transaksi 2" Rp9.876,54 (18 Nov), sumber "Manual".
- Log server uji mencatat request dari aplikasi: `/v1/sessions` 201, `/v1/consents` 201,
  `/v1/summaries/{monthId}` 200, `/v1/transactions` 200, `/v1/categories` 200 — tanpa satu pun
  request ke `:18090` backend pemilik.

### P8 — rollback ke pasangan baseline (scope build/health)

```
# a) server dari source backend-go pada commit baseline cd0f91a
mkdir -p /tmp/pam-base-backend
git -C pam-app archive cd0f91a2b06562ddf12e8fea1c4868e88de54007:backend-go \
  | tar -x -C /tmp/pam-base-backend          # 93 file
cd /tmp/pam-base-backend
GOROOT=$PAM_GOROOT GOCACHE=… GOMODCACHE=… go build -o pam-api ./cmd/api   # rc=0
# catatan: snapshot baseline belum punya scripts/go.sh, jadi memakai go langsung
PORT=18096 DATABASE_URL=file:/tmp/pam-base-run/db/pam.db … ./pam-api
curl /healthz → 200; /readyz → 200; schema_migrations 16 baris   # isolate, DB baru

# b) APK versi baseline cd0f91a
git -C pam-app worktree add --detach /tmp/pam-base-android cd0f91a…
cd /tmp/pam-base-android
./scripts/build.sh :app:assembleDebug -P PAM_BACKEND_BASE_URL=http://127.0.0.1:18090
# BUILD SUCCESSFUL in 1m15s; 21 executed
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
# ef183b753f798bffed9f7e15100d9c0b6413c702f87008b28314f17c3e6f2653  (sama dengan APK HEAD)
git ls-files 'backend-go' | wc -l   # 93 (source backend masih ada di pasangan baseline)
```

Kedua sisi pasangan baseline terbukti **masih dapat dibangun dan dijalankan**. Restore database
produksi / rollback data nyata **NOT RUN** — di luar scope izin (tidak menyentuh DB UAT pemilik)
dan menunggu instruksi pemilik.

### P9 — Gmail API/Pub/Sub

Belum dijalankan: HEAD publik repo backend (`327d8762`) **belum memuat implementasi Gmail**
(`internal/gmail/` tidak ada; `docs/GMAIL_API_CONTRACT.md` sudah ada tetapi endpoint belum).
Implementasi Gmail yang sedang dikerjakan masih ada di working tree lokal repo backend yang
**belum di-commit/push** (lihat §1.2). Uji parity Gmail menunggu commit+push tersebut, lalu
diulang dari clone bersih dengan `GMAIL_SYNC_ENABLED=true/false` dan tanpa kredensial pemilik.

## 3. Temuan

1. **Temuan data-shape attributes (ditemukan & diperbaiki saat uji).** Seed transaksi pertama
   memakai `attributes:{"tags":["split"]}` (array). Server menerima (201) dan Ringkasan tetap
   benar, tetapi tab Transaksi menampilkan **"Data tidak dapat dibaca."** — klien Android
   memetakan `attributes` server ke map string datar (`toStringAttributes`) sehingga nilai
   non-string dianggap kegagalan protokol (`ErrorKind.PARSING`). Setelah transaksi dibuat ulang
   dengan `attributes:{"sumber":"split-uat"}` (string datar), tab menampilkan 2 transaksi normal
   (bukti `10`→`11` gagal, `14`/`15` sukses). **Kontrak `API_CONTRACT.md` belum membatasi tipe
   nilai `attributes`** — rekomendasi: dokumentasikan "nilai atribut harus string" atau buat
   klien mentoleransi nilai non-string. Ini bukan regresi pemisahan repo, tetapi gap kontrak
   klien/server.
2. **Binary 19 MB sempat masuk history Android** (`backend-go/api` di commit `70d1535`),
   dihapus di `b5ebf19`. Tidak di-rewrite (larangan force-push); tree & build bersih.
3. **Clone bersih butuh `PAM_GOROOT` eksternal** karena repo backend belum membawa `.toolchain/`.
   CI workflow repo backend memasang Go sendiri; untuk run lokal dari checkout bersih, dokumentasi
   `README` sebaiknya menyebut variabel ini.
4. **Gmail API belum bisa digate** sampai implementasi WIP di repo backend di-commit+push.
5. **Banner "Ada transaksi yang belum diakui server"** tampil setelah login (status pembaruan
   menandai transaksi manual yang belum masuk siklus impor). Perilaku ini juga muncul pada sesi
   baseline sebelumnya; bukan efek pemisahan repo, tetapi tetap perlu keputusan PM apakah akan
   dirapikan sebelum serah terima.
6. **APK deterministik**: build HEAD `b5ebf19` menghasilkan sha256 sama dengan build baseline
   `cd0f91a` → bukti tidak ada perubahan source Android dalam commit pemisahan repo.

## 4. Batas pengujian & cleanup

- Tidak menyentuh `:18090` backend UAT pemilik (PID 35599 masih jalan setelah uji), DB Turso
  pemilik, maupun akun/data pemilik. Semua server uji memakai DB file `/tmp` dan sudah dimatikan
  (`shutdown_requested`); `adb reverse` sudah dicabut; worktree `/tmp/pam-base-android` dihapus.
- Gate ini tidak mengubah/menghapus kode, fixture interop, migrasi DB, atau dokumen lain. Hanya
  dokumen ini yang diubah dalam lane ini.
- Yang **belum** dibuktikan: (a) restore DB produksi/rollback data nyata (butuh otorisasi),
  (b) parity Gmail API/Pub/Sub (menunggu implementasi di-push), (c) APK login pada perangkat
  fisik/Android Studio Run—uji UI memakai emulator-5556 dengan APK debug hasil build yang sama,
  (d) konfigurasi provider Azure untuk kategori (uji ini `CATEGORY_LLM_ENABLED=false`, sesuai
  batas biaya).

## 5. Kesimpulan gate

Pemisahan repo **terbukti berfungsi end-to-end untuk jalur inti**: backend dapat di-clone bersih,
di-vet/build/test (167 test hijau), dijalankan sebagai server dengan DB terisolasi, dan APK debug
yang dibangun **tanpa source backend** dapat login serta membaca transaksi/kategori yang dibuat
lewat API dari server repo backend. Pasangan baseline masih dapat dibangun dan dijalankan.

Status gate: **P1–P7 PASS; P8 PARTIAL (build/health PASS, restore DB produksi NOT RUN);
P9 NOT RUN (menunggu implementasi Gmail di-push)**. Gate belum final sampai dua butir tersebut
selesai/diotorisasi.
