# Audit pemisahan repo backend Go — 24 November 2026 (WIB)

Status: **audit independen, read-only**. Tidak ada perubahan kode, tidak ada commit selain dokumen
ini, tidak ada restart backend UAT `:18090`, tidak ada perubahan data/akun owner. Satu server
verifikasi dijalankan dari **clone bersih** pada port terisolasi `18099` dengan DB `/tmp`, lalu
dimatikan. Kode Gmail API (lane w-go) **tidak diaudit sebagai final**: mayoritas masih WIP
untracked di repo backend; hanya dokumen kontraknya yang diperiksa konsistensinya.

Snapshot saat audit:

- `pam-app` (Android): HEAD `b5ebf19` (`chore(repo): drop stray Go binary and ignore build outputs`),
  branch `feat/c2-android`; `origin` = `https://github.com/NDI05/0037_FinancialMobile.git` (publik);
  remote `feat/c2-android` = `b5ebf19` (hash lokal = remote).
- `0037-Backend-Financial`: `main` = `327d876` (`chore(repo): bootstrap standalone backend
  repository`) = `origin/main`; `origin` = `https://github.com/NDI05/0037-Backend-Financial.git`
  (**privat**, diverifikasi `gh repo view --json isPrivate`). Repo kerja lane lain aktif di
  `feat/gmail-api`/`feat/retention` (WIP, tidak diaudit).

---

## 1. Verdict per target audit

| # | Butir | Verdict | Ringkas |
|---|---|---|---|
| 1 | Backend standalone (tanpa referensi path repo Android) | **PASS** | 103 file tracked, semuanya teks; grep `pam-app`/`../backend`/`/Users/`/`Gabut-Code` = 0; repo privat; README menyatakan origin ekstraksi |
| 2 | Build/test/vet dari clone bersih + `PAM_GOROOT` | **PASS** | `vet` rc=0, `build ./...` rc=0, `test ./... -count=1` 8 paket `ok` + 3 tanpa tes = **11 paket**, **167 entri tes (128 top-level + 39 subtest)**, 0 gagal |
| 3 | Server dari clone backend + DB terisolasi | **PASS** | `/healthz` 200 `{"status":"ok"}`, `/readyz` 200 `{"status":"ready"}`, `schema_migrations` **16 baris (max 16)**, port `18099`, DB `/tmp`; `:18090` tidak disentuh |
| 4 | CI workflow | **PASS** | `.github/workflows/ci.yml` = vet+test+build (Go 1.27.4); run `45339431811` `completed/success` 1m37s pada push `main` |
| 5 | Tidak ada secret/DB/dump/binary ter-commit (backend) | **PASS** | 103 file teks; 0 blob >500 KB di seluruh history; 0 pola secret (`BEGIN PRIVATE KEY`, `gho_`, `ghp_`, `AKIA`, JWT); `.env.example` hanya placeholder; tidak ada email owner di history |
| 6 | Riwayat ekstraksi masuk akal | **PASS** | 33 commit = **32 riwayat backend dipertahankan + 1 bootstrap**; 32/32 subject identik dengan path `backend-go/` di pam-app `cd0f91a`; `HEAD~1` = `0272c97`; parity isi 93 file: 0 hilang, 3 berubah sah, 10 file bootstrap baru |
| 7 | Tree Android tanpa `backend-go/`, `fixtures/interop/`, `backend/` | **PARTIAL** | Tracked = **0** untuk ketiganya dan build tidak rusak; namun README §6 masih menyuruh `cd backend-go` (perintah mati), 27 dokumen masih menyebut path lama tanpa penanda arsip, dan direktori kosong `backend-go/` masih ada di disk |
| 8 | Android build tanpa source server | **PASS** | `./scripts/build.sh :app:assembleDebug` → **BUILD SUCCESSFUL** 8s (43 tasks); tidak ada task Gradle/script yang menunjuk `backend-go` |
| 9 | Dampak binary 19 MB di `70d1535` | **PARTIAL (diterima, terdokumentasi)** | Blob `a1c9dc8…` 19.146.914 byte (Mach-O arm64) masih ada di history **dan sudah ter-push** ke remote publik; **tidak berisi** JWT/email/owner-id/secret; membocorkan 1.093 path absolut build (DWARF); +19 MB permanen; penghapusan butuh rewrite history (dilarang) |
| 10 | Kompatibilitas `git worktree` lain | **PASS** | Worktree `pam-app-retention` (di `b5ebf19`) & `0037-backend-retention` (WIP) berjalan normal; `pam-app-gmail-ui` di basis pra-split `7c4bfff` masih memuat 93 file `backend-go` (expected, branch lama, bukan kerusakan) |
| 11 | Klaim `docs/delivery/BACKEND_SPLIT_2026-11-24.md` | **PARTIAL** | Status **PARTIAL** jujur dan tidak ada PASS palsu; 2 baris sudah usang (build Android kini PASS; P1 kini PASS karena sudah dipush) dan beberapa tautan/dokumen backend patah |

**Tidak ada gate yang bisa dinaikkan menjadi PASS penuh**: bukti E2E Android ↔ server repo backend
dan rollback masih **NOT RUN** (sesuai dokumen, bukan over-claim).

---

## 2. Perintah dan bukti persis

### 2.1 Backend standalone & clone bersih

```bash
git -C 0037-Backend-Financial remote -v          # origin github.com/NDI05/0037-Backend-Financial.git
git -C 0037-Backend-Financial ls-remote origin   # main = 327d8762b0c637a70f7e30f93ffbfd311b3a02ed
git -C 0037-Backend-Financial rev-list --count HEAD   # 33
gh repo view NDI05/0037-Backend-Financial --json isPrivate   # {"isPrivate": true, defaultBranchRef main}
git grep -InE 'pam-app|\.\./backend|/Users/|Gabut-Code' HEAD   # 0 hasil
git ls-tree -r -l HEAD | sort -k4 -n | tail                   # file terbesar 61.615 byte (teks)
git rev-list --objects --all | git cat-file --batch-check=...  # 0 blob > 500 KB
```

Clone bersih + toolchain dari repo Android:

```bash
rm -rf /tmp/pam-backend-audit
git clone 0037-Backend-Financial /tmp/pam-backend-audit      # HEAD 327d876, hanya tracked files
cd /tmp/pam-backend-audit
PAM_GOROOT=<pam-app>/.toolchain/go ./scripts/go.sh build ./...        # rc=0
PAM_GOROOT=<pam-app>/.toolchain/go ./scripts/go.sh vet ./...          # rc=0
PAM_GOROOT=<pam-app>/.toolchain/go ./scripts/go.sh test ./... -count=1
# ok: auth, categoryllm, config, crypto, finance, httpapi (22.0s), llm, store
# no test files: cmd/api, cmd/pam-admin, internal/analysis  => 11 paket
```

Hitung tes (dari `-json`, bukan `-list`): **167 entri unik (128 top-level + 39 subtest), 0 event
`fail`**. Klaim dokumen "167 tes" benar bila dimaksud entri tes termasuk subtest; istilah "fungsi
test" sedikit menyesatkan → lihat temuan P2-5.

### 2.2 Server dari repo backend + migrasi (DB terisolasi)

```bash
rm -rf /tmp/pam-split-audit-db2 && mkdir -p /tmp/pam-split-audit-db2
PAM_GOROOT=<pam-app>/.toolchain/go APP_ENV=development PORT=18099 \
  DATABASE_URL=file:/tmp/pam-split-audit-db2/pam.db \
  LLM_PROVIDER=fake CATEGORY_LLM_ENABLED=false \
  ./scripts/go.sh run ./cmd/api
# log: {"msg":"started","port":18099,"env":"development"}
curl -s -w ' [%{http_code}]\n' http://127.0.0.1:18099/healthz   # {"status":"ok","uptimeSeconds":0} [200]
curl -s -w ' [%{http_code}]\n' http://127.0.0.1:18099/readyz    # {"status":"ready"} [200]
sqlite3 /tmp/pam-split-audit-db2/pam.db 'select count(*), max(version) from schema_migrations;'  # 16|16
# setelah verifikasi proses dimatikan; port 18099 kembali kosong; :18090 tidak pernah disentuh
```

### 2.3 Parity ekstraksi (Android `cd0f91a:backend-go` ↔ backend `HEAD`)

93 file asli path `backend-go/` dibandingkan hash blob per path:

- **0 hilang**, **0 berubah tanpa alasan**.
- 3 berubah (sah): `README.md` (ditulis ulang standalone), `internal/auth/interop_test.go` dan
  `internal/crypto/interop_test.go` (path fixture `fixtures/interop/`).
- 10 baru dari bootstrap: `.env.example`, `.github/workflows/ci.yml`, `.gitignore`,
  `docs/API_CONTRACT.md`, `docs/GMAIL_API_CONTRACT.md`, 4 fixture interop, `scripts/go.sh`.
- 32 subject commit path backend di pam-app **identik** dengan 32 commit ujung riwayat backend
  (`0272c97` dan ke bawah), urutan/isi sesuai `git subtree split` (hash wajar berbeda).

### 2.4 Android setelah pemisahan

```bash
git -C pam-app ls-files backend-go fixtures/interop backend | wc -l   # 0
git -C pam-app status --short                                        # hanya dokumen WIP lane lain
git grep -InE 'backend-go' -- '*.sh' '*.py' '*.kts' '*.yml' '*.yaml' '*.properties' '*.gradle'  # 0 hasil
./scripts/build.sh :app:assembleDebug                                # BUILD SUCCESSFUL in 8s
git -C pam-app worktree list
# pam-app b5ebf19 | pam-app-gmail-ui 7c4bfff (93 file backend-go, basis pra-split)
# pam-app-retention b5ebf19 (0 file backend-go)
```

`scripts/dev.sh` konsisten: `BACKEND_REPO="${PAM_BACKEND_REPO:-$ROOT/../0037-Backend-Financial}"`,
memvalidasi `$BACKEND_REPO/go.mod`, dan menyalakan `"$BACKEND_REPO/scripts/go.sh" run ./cmd/api`
dengan `PAM_GOROOT` dari toolchain repo Android. Tidak ada sisa referensi ke `pam-app/backend-go`.

### 2.5 Binary 19 MB (`70d1535`)

```bash
git show --stat 70d1535   # A backend-go/api  Bin 0 -> 19.146.914 bytes
git show --stat b5ebf19   # D backend-go/api + .gitignore 4 baris (bin/, *.exe)
git cat-file -t a1c9dc8303546ea40bb6bec3c8ecd3956965f709   # blob (masih reachable di history)
git cat-file -s a1c9dc8…                                   # 19146914
git cat-file blob a1c9dc8… | file -b -                     # Mach-O 64-bit executable arm64
# scan marker (nilai TIDAK dicetak): JWT 0, email 0, owner-id 0, PRIVATE KEY 0, gho_/ghp_ 0,
# akun sintetis 0, host Turso 0; libsql:// hanya konstanta skema (2x, panjang 9).
# 1.093 kemunculan path absolut build (/Users/<user>/…/pam-app/...) dari DWARF debug info.
```

Kesimpulan: **tidak ada kebocoran secret/data owner** pada blob; yang bocor adalah metadata build
(path absolut) dan ukuran repo. `.gitignore` baru (`bin/`, `*.exe`) sudah menutup kelas artefak ini;
`b5ebf19` sudah dipush sehingga blob tetap ada di remote publik.

---

## 3. Matriks klaim `docs/delivery/BACKEND_SPLIT_2026-11-24.md`

| Klaim dokumen | Kenyataan audit | Status |
|---|---|---|
| Status keseluruhan **PARTIAL**; source terpisah + build/test independen PASS; E2E/rollback **NOT RUN** | Sesuai; standalone, build/test/vet, server+DB dan CI terverifikasi PASS; E2E/rollback memang belum ada bukti | **PASS (akurat)** |
| Backend `main` `327d876`; Android `feat/c2-android` `cd0f91a` (saat itu) | `327d876` = remote `main`; Android kini `b5ebf19` = remote `feat/c2-android` (dokumen memakai snapshot awal, wajar) | **PASS (historis)** |
| "32 commit riwayat dipertahankan", ujung `0272c97` | 32 subject identik + 1 bootstrap; `HEAD~1` = `0272c97` | **PASS** |
| Bootstrap: fixture interop pindah, path tes diperbaiki, `scripts/go.sh` mandiri, `.env.example`, `.gitignore`, CI vet+test+build, README standalone, kontrak | Semua ada dan berfungsi; parity 3 file berubah + 10 file baru persis kelompok ini | **PASS** |
| `backend-go/**` (migrasi 001–016, classifier, admin CLI, smoke) pindah | 93 file; 17 berkas migrasi (001–016 + `003.triggers.sql`) | **PASS** |
| Node `backend/` dipindah luar tree ke `../archive/pam-node-backend-2026-11-24/`; riwayat tetap di `cd0f91a` (`git checkout cd0f91a -- backend/`) | Direktori arsip ada; `cd0f91a` memuat 70 file `backend/`; HEAD 0 file | **PASS** |
| `scripts/dev.sh` memakai `PAM_BACKEND_REPO` (default sibling) | Sesuai kode | **PASS** |
| Matriks: Go build/vet/test **PASS (baseline)** 11 paket/167 tes; server `/healthz`+`/readyz` **PASS** 16 migrasi DB terisolasi | Direproduksi independen dari clone bersih (lihat §2.1–2.2) | **PASS (terverifikasi ulang)** |
| Matriks: "Android build tanpa source server **PENDING** (build sedang dijalankan)" | Kini **PASS**: `assembleDebug` sukses setelah `b5ebf19` | **USANG (under-claim)** |
| Matriks: E2E APK login/read, rollback, cutover launcher **NOT RUN** | Belum ada bukti baru; audit ini juga tidak menjalankannya | **PASS (jujur)** |
| Evidence P1 "PARTIAL: remote Android `cd0f91a`, `70d1535` belum dipush" | Kini remote `feat/c2-android` = `b5ebf19` (seluruh rantai sudah dipush) | **USANG (under-claim)** |
| "README dan dokumen arsitektur/kontrak diperbarui" | Sebagian: README masih punya perintah Go lama; 27 dokumen masih menyebut `backend-go`; kontrak backend punya 5 tautan relatif patah | **PARTIAL** |

Tidak ditemukan klaim PASS tanpa bukti di dokumen ini. Dua baris **under-claim** (bukan over-claim)
karena kondisi sudah membaik setelah dokumen ditulis.

---

## 4. Temuan

Severity: P0 = blocker/keamanan; P1 = harus dibereskan sebelum klaim gate; P2 = perbaikan tertunda.

### P0 — tidak ada

Tidak ada secret/data owner di repo backend, tidak ada binary/DB/dump ter-commit di backend, dan
`main` backend sudah standalone serta lulus build/test/vet dari clone bersih.

### P1

| # | Temuan | Bukti | Status |
|---|---|---|---|
| P1-1 | **PII owner masih ada di history repo Android yang publik.** HEAD sudah bersih (redaksi `0436fe2`), dan tidak ada JWT mentah yang pernah ter-commit, tetapi email/owner-id owner tetap tersimpan di commit lama di repo **publik**. Ini **pre-existing**, bukan akibat commit pemisahan; tidak bisa dihapus tanpa rewrite history (dilarang kebijakan). Keputusan (repo privat / terima terdokumentasi) milik pemilik. | `git log --all -S'<email-owner>' -- docs` menemukan commit lama; `gh repo view NDI05/0037_FinancialMobile --json isPrivate` → `false`; HEAD bersih (`0436fe2`). Nilai PII tidak dicetak di laporan ini. | **VERIFIED** |

### P2

| # | Temuan | Bukti | Status |
|---|---|---|---|
| P2-1 | **README masih mengajarkan perintah Go yang mati**: `cd backend-go` lalu `../scripts/go.sh ...` — direktori `backend-go/` sudah tidak ada, sehingga blok ini gagal bila diikuti. Menyesatkan karena README adalah runbook aktif. | `README.md:210-216` | **VERIFIED** (baca file) |
| P2-2 | **27 dokumen tracked masih menyebut `backend-go`/path lama tanpa penanda arsip/historis** (kebijakan retirement meminta penanda). Contoh paling berpotensi menyesatkan: `docs/13_GO_BACKEND_MIGRATION.md:78` (fixture `fixtures/interop/` seolah ada di repo Android), `docs/05_REAL_GOOGLE_SETUP.md:209` (`pam-backend/.env`), `docs/06_BACKEND_CONTRACT.md:19` (Node `backend/` seolah masih di tree; kenyataannya diarsipkan luar repo), `.gitignore:33` (`backend/.dev-server.log` sisa). | `git grep -Il 'backend-go' -- docs/ \| wc -l` = 27 (`git grep -Il ... -- .` = 29 termasuk README/AGENTS) | **VERIFIED** |
| P2-3 | **Kontrak backend membawa 5 tautan relatif patah** karena dokumen rujukan (SRS, CR-004, `13_GO_BACKEND_MIGRATION`, `AZURE_CLASSIFIER_SETUP`, `10_MIGRASI_PEDOMAN`) tetap tinggal di repo Android. Kontrak dipindah tanpa dokumen rujukan → pembaca repo backend tidak bisa membuka rujukan. | `docs/API_CONTRACT.md` (cek `os.path.exists` untuk semua link relatif: 5 broken) | **VERIFIED** |
| P2-4 | `.env.example` merujuk `docs/GMAIL_API_SETUP.md` yang **tidak ada** di repo backend (yang ada `GMAIL_API_CONTRACT.md`). | `.env.example:32`; `ls docs/` = `API_CONTRACT.md`, `GMAIL_API_CONTRACT.md` | **VERIFIED** |
| P2-5 | Klaim metrik tes "167 fungsi test" ambigu: 167 = **entri tes termasuk 39 subtest**; fungsi test top-level = 128. Mohon pakai satu definisi konsisten (mis. "167 entri tes / 128 fungsi top-level") agar mudah direproduksi. | `go test ./... -json` di clone bersih: 167 entri unik, 128 top-level, 0 `fail` | **VERIFIED** |
| P2-6 | **Sisa direktori kosong `backend-go/`** (hanya `.DS_Store`, untracked, ignored) masih ada di working tree pam-app. Kriteria P4 di dokumen (`test ! -e pam-app/backend-go`) akan gagal walau tracked = 0; sebaiknya dihapus agar cek gate deterministik. | `find backend-go` → `backend-go/.DS_Store`; `git ls-files backend-go` = 0 | **VERIFIED** |
| P2-7 | **Dokumen delivery/evidence usang sebagian**: matriks delivery "Android build PENDING", P1 evidence "belum dipush", P4 "masih 1 file tracked `backend-go/api`" — kondisi sekarang sudah PASS/kosong. Perbarui agar gate review tidak memakai status lama. (Evidence doc saat audit juga sedang diedit lane lain dan belum di-commit.) | `docs/delivery/BACKEND_SPLIT_2026-11-24.md:44-49`; `docs/evidence/UAT_BACKEND_SPLIT_2026-11-24.md` (working tree modified) | **VERIFIED** |
| P2-8 | Binary 19 MB tetap di history publik dan tidak dapat dihapus tanpa rewrite (force-push dilarang). Dampak: +19 MB clone, 1.093 path absolut build bocor (tanpa secret/data). Mitigasi ke depan: build dengan `-trimpath`, jangan commit artefak (`bin/`, `*.exe` sudah di-ignore). | `git cat-file` blob `a1c9dc8…`; remote `feat/c2-android` = `b5ebf19` (blob reachable) | **VERIFIED** |
| P2-9 | Dua script QA publik memuat **password literal akun sintetis** dev (`scripts/azure/analysis_smoke.py:237,409`, `scripts/verify/category_isolated.py:204`). Risiko rendah (akun sintetis/backend terisolasi, bukan kredensial owner), tetapi sebaiknya diganti env placeholder agar repo publik bersih. | `git grep -nE '(token\|password) *= *"[A-Za-z0-9_-]{16,}"' -- scripts/` | **VERIFIED** |
| P2-10 | Keystore debug `app/keystore/debug.keystore` ter-track — kunci debug standar (bukan kunci rilis), praktik umum; dicatat sebagai reviewed-OK, bukan kebocoran. | `git ls-files app/keystore` | **VERIFIED** |

---

## 5. Yang belum dapat diverifikasi / batas audit

1. **E2E Android ↔ server repo backend** (APK debug login + baca data ke server dari clone backend,
   P7 di dokumen evidence): **NOT RUN**. Audit ini tidak memakai emulator untuk menghindari
   tabrakan dengan lane UI/Gmail yang sedang aktif; butuh satu run QA khusus.
2. **Rollback ke pasangan baseline** (P8) dan **cutover launcher UAT**: **NOT RUN** (belum
   diserahkan/dijadwalkan).
3. **Kode Gmail API/Pub/Sub**: masih WIP di repo backend (untracked: `internal/gmail/`,
   `handlers_gmail.go`, `store/gmail.go`, migrasi `017.sql`); sesuai instruksi Lead **tidak
   diaudit final**. Yang diperiksa hanya dokumen kontrak: `docs/GMAIL_API_CONTRACT.md` berlabel
   "draf PM/kontrak kerja" (jujur), tautan internal valid, selaras dengan README (Gmail API +
   Pub/Sub push); `.env.example` Gmail sesuai daftar env kontrak, kecuali rujukan setup yang
   patah (P2-4).
4. **Metode `git subtree split` di `/tmp/pam-split`**: tidak direproduksi oleh auditor; yang
   diverifikasi adalah hasilnya (parity isi + 32 subject identik + `HEAD~1 = 0272c97`), bukan
   log perintah PM.
5. **Riwayat CI lengkap**: hanya run terakhir (`45339431811`, success) yang diperiksa via
   `gh run list`; tidak semua push/PR masa lalu dan tidak ada run untuk branch selain `main`.
6. **Konten PNG/XML aset QA**: tidak didekode; tidak ada klaim PII visual dari audit ini.
7. **Kebijakan repo** (privat/publik, rewrite history) adalah keputusan pemilik/PM, bukan auditor.

---

## 6. Rekomendasi gate pemisahan (keputusan tetap milik Lead/pemilik)

| Gate | Rekomendasi | Alasan |
|---|---|---|
| Standalone backend (source/build/test/CI) | **PASS** | Clone bersih: vet/build/test rc=0, 11 paket/167 entri tes, 0 gagal; server sehat dengan DB baru 16 migrasi; CI run sukses; tanpa referensi repo Android; tanpa secret/binary/DB ter-commit |
| History & parity ekstraksi | **PASS** | 32/32 subject identik, 93 file parity, bootstrap terdokumentasi, author asli dipertahankan |
| Tree Android bersih | **PARTIAL** | Tracked 0 + build sukses, tetapi README §6 menyesatkan (P2-1), 27 dokumen belum ditandai (P2-2), sisa direktori kosong (P2-6) |
| Gate keseluruhan pemisahan | **PARTIAL — belum naikkan PASS** | E2E Android↔server dan rollback masih NOT RUN; perbaiki P2-1/P2-2/P2-7 sebelum review gate berikutnya |
| Merge/deploy | **tidak direkomendasikan auditor** | Di luar kewenangan; tidak ada instruksi owner |

Saran tindak lanjut minimal (tanpa mengubah kode produk):
1. Perbaiki blok "### Go" di README agar menunjuk repo sibling/`PAM_BACKEND_REPO` (P2-1).
2. Tandai 27 dokumen historis atau pindahkan ke `docs/archive/` sesuai kebijakan retirement (P2-2).
3. Salin/tandai dokumen rujukan kontrak backend atau ubah tautan menjadi URL repo Android (P2-3),
   dan perbaiki rujukan `.env.example` (P2-4).
4. Segarkan status delivery/evidence (P2-7) dan hapus direktori kosong `backend-go/` (P2-6).
5. Untuk build/artifact berikutnya: `-trimpath`, jangan commit binary; sisa blob 19 MB di history
   diterima dan dicatat (P2-8).
