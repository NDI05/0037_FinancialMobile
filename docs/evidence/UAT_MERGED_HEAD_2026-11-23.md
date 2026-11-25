# UAT merged HEAD `feat/c2-android` — acceptance PM — 23 November 2026

Dokumen ini mencatat acceptance yang dijalankan **setelah merge** tiga lane gap (C01, S2.5,
C24/C25) ke `feat/c2-android`, memakai APK gabungan hasil build checkout utama. Tujuannya
memisahkan bukti **runtime merged head** dari bukti lane (yang sudah diaudit terpisah) dan
memberi dasar penutupan S2/S3/S4 tanpa mengklaim melebihi yang benar-benar dijalankan.

## 1. Titik integrasi dan artefak

| Item | Nilai |
|---|---|
| Branch | `feat/c2-android` |
| HEAD | `7337559` — `merge(analysis): wire server-mode S2.5 freshness, generation and evidence` (induk `aa61a7b` + `5fe623c`) |
| Remote | `origin/feat/c2-android == 7337559` (dipush, diverifikasi `git ls-remote`) |
| APK debug | `app/build/outputs/apk/debug/app-debug.apk`, 46.709.962 B, sha256 `6497a4066a32b00308fe7ee44291bcb713e039d54abd3909709a78c9b5e5db16` |
| APK assessment | `app/build/outputs/apk/assessment/app-assessment.apk`, 24.344.958 B, sha256 `fa0d224e7591400354184129c2096533c87cc4334dfa6218be22baa002d44fd7` |
| Build | `./gradlew :app:assembleDebug -PPAM_BACKEND_BASE_URL=http://127.0.0.1:18090` dan `:app:assembleAssessment` (lihat `/tmp/merged-gates.log`); string `http://127.0.0.1:18090` terverifikasi ada di `classes*.dex` APK debug |
| Backend UAT | `/tmp/pam-be-merged` pada `127.0.0.1:18090`, PID 9190, DB Turso sintetis `pam-real-uat-ndi05`, log `/tmp/pam-uat-18090-fixed.log` |
| Backend terisolasi (jalur analisis) | binary yang sama pada `127.0.0.1:18091`, DB file `/tmp/pam-gap-analysis/pam.db`, `LLM_PROVIDER=fake` (tidak ada panggilan LLM nyata), log `/tmp/pam-gap-analysis/backend.log` |
| Perangkat | emulator **5556** `Pixel_10_Pro_XL`; `adb reverse tcp:18090 tcp:18090` (UAT) lalu `tcp:18090 → tcp:18091` (isolated). Tidak memakai perangkat fisik `(serial diredaksi)`. |

Alasan dua backend: backend UAT `18090` tidak punya provider analisis yang berfungsi (POST
`/v1/analyses` → 502 setelah ±31 s), sehingga jalur generasi/kesegaran analisis diuji pada
binary backend yang sama di `18091` dengan provider fake dan DB terisolasi — persis lingkungan
yang dipakai lane S2.5 dan diaudit. Jalur create manual tetap dibuktikan pada UAT `18090`.

Gate regresi merged head (dijalankan sebelum acceptance perangkat, log `/tmp/merged-gates.log`):

| Gate | Hasil |
|---|---|
| `:app:testDebugUnitTest --rerun-tasks` | **493 test / 68 kelas, 0 gagal, 0 error, 0 skip** |
| `:app:testAssessmentUnitTest --rerun-tasks` | **503 test / 69 kelas, 0 gagal, 0 error, 0 skip** |
| `:app:lintDebug` | **0 error, 115 warning, 1 hint** |
| `:app:lintAssessment` | **0 error, 123 warning, 1 hint** |
| Go `test -count=1 ./...`, `vet`, `build`, `test -race ./internal/store ./internal/httpapi` | semua lulus |

## 2. C01 — create manual: PASS runtime (merged)

**2a. UAT `18090`, akun sintetis, lewat UI perangkat.**

- Transaksi diisi lewat form `Tambah transaksi` (nominal `14567800` minor, deskripsi
  `C01 merged 2101`, tanggal default 23 Nov 2026), IME ditutup dengan BACK sebelum simpan.
- Backend mencatat `POST /v1/transactions` **201** pada `2026-11-23T20:33:56.57742+07:00`
  (`correlationId req_f27db8bd3df48d193d1e79a029ec4b34`), diikuti `GET /v1/transactions` 200
  (`log_18090_c01.txt`).
- Ringkasan sesudah create (API, token sesi baru, `api_uat_summary_2026-11.json`):
  `currentRevision=93`, `transactionCount=90`, `netMinor=7661301005`,
  `grossOutflowMinor=116210580`, `freshness=CURRENT`. Lane C01 mencatat kondisi sebelum create
  pada akun yang sama: 89 baris / revisi 92 (`/tmp/gap-c01-evidence/api_verify_final.json`),
  jadi create merged ini menambah tepat **+1 transaksi dan revisi +1**.
- Baris hasil create terlihat di dump perangkat (`c01_after_save2.xml`: nilai `145678`,
  deskripsi `C01 merged 2101`, pesan "Setelah tersimpan, transaksi ini masuk ke ringkasan
  bulan November 2026.").

**2b. Backend terisolasi `18091`, APK merged yang sama.**

- Create manual lewat UI → `POST /v1/transactions` **201** pukul `20:46:29.734` WIB
  (`log_18091_analysis_cycle.txt`).
- Baris DB: `tx_471df9b67f550dbe783173d0f2740179 | DEBIT | MANUAL | 24681000 | 2026-11 |
  revision 0 | 2026-11-23T13:46:29.725738Z`; tepat **1** baris untuk nominal itu
  (`db_18091_c01_analysis.txt`). Revisi bulan naik **9 → 10**, jumlah transaksi ringkasan
  **7 → 8**.

Batas yang tetap berlaku: replay idempotency create (key sama, respons hilang) tetap
**unit/API-only**; tidak dipentaskan ulang di perangkat pada akhir sesi ini.

## 3. S2.5 — status analisis kedaluwarsa: PASS runtime (merged)

Semua langkah memakai APK debug merged di emulator 5556 terhadap backend `18091`; status
dibaca silang dari UI dump + log route + respons API.

1. **Analisis tersimpan CURRENT.** Layar Analisis menampilkan kartu `ANALISIS TERSIMPAN`,
   chip **"Tersedia"** untuk `inputRevision=9 == currentRevision=9`
   (`iso_analysis_current.xml`).
2. **Mutasi data lewat UI → STALE.** Create manual §2b menaikkan revisi 9 → 10. Saat layar
   Analisis dibuka, chip menjadi **"Kedaluwarsa"** dan banner:
   *"Analisis ini dibuat dari revisi 9, sedangkan data bulan ini sudah di revisi 10. Buat ulang
   analisis agar sesuai data terbaru."* (`iso_stale.xml`, juga tampak pada screenshot
   `merged_analysis_stale_after_reopen.png` yang diambil pada kondisi dependency-stale).
3. **Regenerasi dari UI → CURRENT.** Tap "Buat ulang analisis" → backend mencatat
   `POST /v1/analyses` **200** pukul `20:46:51.823` WIB; layar kembali menampilkan
   kartu `ANALISIS TERSIMPAN` + chip **"Tersedia"**, banner kedaluwarsa hilang
   (`iso_regenerated.xml`).
4. **Buka-tutup aplikasi → freshness server tetap otoritatif.** Force-stop + buka kembali:
   foreground sync aplikasi membuat dua `POST /v1/import-jobs` 201 pukul `20:47:13` (dua job
   state `EMPTY`, halaman 0) — lihat `log_18091_analysis_cycle.txt` dan
   `db_18091_c01_analysis.txt`. Job baru itu masuk ke dependency hash backend, coverage bulan
   berubah `PARTIAL → EMPTY`, sehingga `GET /v1/analyses/2026-11` mengembalikan
   `inputRevision=10, currentRevision=10, freshness=STALE` walau nomor revisi bulan sama
   (`api_analysis_2026-11.json`). UI menampilkan chip **"Kedaluwarsa"** dengan banner
   dependency: *"Analisis ini dibuat dari data yang sejak itu sudah berubah (revisi 10),
   meskipun nomor revisi bulan tetap…"* (`iso_reopen_current.xml`). Ini justru membuktikan
   aturan dependency-hash server dipatuhi UI (bukan hanya perbandingan revisi).
5. **Efek lanjutan yang dicatat jujur.** Karena coverage kini `EMPTY` (tidak ada mailbox Gmail
   terhubung di DB terisolasi), tap "Buat ulang analisis" berhenti dengan pesan
   **"Belum ada data untuk bulan ini."** — guard `MONTH_HAS_NO_DATA` yang memang dirancang
   (`ServerAnalysisRepositoryImpl.generate` menolak coverage `EMPTY`/`FAILED`). Dump
   `iso_regen_after_sync.xml`. Tidak ada `POST /v1/analyses` baru setelah itu (log).

Interpretasi: seluruh transisi **CURRENT → STALE → regenerate → CURRENT → dependency-STALE**
berjalan sesuai kontrak; tidak ada cacat pada jalur merged (decoding, chip, banner, tombol,
guard consent/coverage). Perilaku langkah 4–5 bersifat environment-driven: tanpa Gmail
terhubung, sinkronisasi foreground menulis import-job `EMPTY`, yang menaikkan dependency hash
dan menurunkan coverage. Catatan tindak lanjut **INFO** (bukan blocker, di luar scope merge
ini): pertimbangkan apakah job sync tanpa kandidat seharusnya mengubah dependency/coverage,
atau apakah backend sebaiknya mengabaikan job nol-halaman pada dependency hash. Ini keputusan
desain backend, bukan regresi UI Android.

## 4. C24/C25 — admin: PASS runtime (audited) + merged regression

Walk runtime C24/C25 dilakukan lane admin pada worktree terisolasi (backend `18092`, DB
`/tmp/pam-gap-admin/pam.db`) dan telah diaudit; lihat
[UAT_GAP_ADMIN_2026-11-23.md](UAT_GAP_ADMIN_2026-11-23.md) serta laporan audit
`AUDIT_REPORT_GAP_ADMIN.md`. Ringkasnya: nonaktifkan akun 200 → `SUSPENDED` + seluruh sesi
target dicabut, self-deactivate 400, guard admin-terakhir ditutup test store/HTTP + trigger DB,
pencarian `andhika` → 1 baris, USER ditolak sebelum request admin, list default mengecualikan
DELETED.

Pada merged head, kode lane admin ikut terverifikasi oleh gate regresi di §1 (debug 493 test,
assessment 503 test, 0 gagal; lint 0 error) dan tidak ada perubahan lanjutan pada modul admin
setelah audit. Walk perangkat last-admin/cursor **tidak diulang** pada merged head agar sesi
admin bukti tidak tercabut; batas ini tetap dicatat di matriks.

## 5. Kesiapan varian assessment (S4.4) — ringkas

- Artefak `app-assessment.apk` dibangun dari merged head: sha256 `fa0d224e…`, 24.344.958 B;
  `testAssessmentUnitTest` 503 test lulus; `lintAssessment` 0 error.
- Smoke perangkat: APK assessment dipasang (`install -r`) dan diluncurkan di emulator 5556
  terhadap backend `18091` (reverse `tcp:18090 → tcp:18091`). Layar Ringkasan termuat dari
  server — "November 2026", "Dari email Kosong", arus kas bersih `Rp331.935`, 8 transaksi,
  kategori pengeluaran dengan nominal — dan log backend mencatat `GET /v1/summaries`,
  `GET /v1/transactions`, `PATCH /v1/import-jobs`, `POST /v1/categories` pada 20:55:32 WIB
  (`assessment_smoke.xml/.png`). Artinya varian assessment benar-benar berjalan dan memakai
  backend yang sama, bukan sekadar artefak build.
- Batas by-design yang tetap berlaku: sinkronisasi **foreground-only** (tanpa
  WorkManager/background sync), tanpa `applicationIdSuffix` (tidak bisa coexist dengan build
  debug), dan tidak menyimpan data finansial lokal (server-backed + encrypted outbox).
- Varian ini adalah kandidat build penilaian, bukan build produksi; klaim readiness terbatas
  pada definisi di checkpoint, tanpa deployment.

## 6. Artefak mentah

Semua di `docs/evidence/assets/merged_head/` kecuali disebut lain.

| File | SHA-256 |
|---|---|
| `iso_analysis_current.xml` | `54be05c837b38acaa208e816e1734982ee644aa73322e6ada674d3488cd8eb9b` |
| `iso_stale.xml` | `4817c96cb1430bfe02168584f73fa3adc57c7cbcd034303c31149427a53d3a3b` |
| `iso_regenerated.xml` | `54be05c837b38acaa208e816e1734982ee644aa73322e6ada674d3488cd8eb9b` |
| `iso_reopen_current.xml` | `50addaf195a0b05fab65382fbabb2df46b344493e2307e680562d4f9ab888728` |
| `iso_regen_after_sync.xml` | `9dc27e6d4204b6305b2fda39113932a721c63d6cee1a1783d0d080ebff3ab0f3` |
| `merged_analysis_stale_after_reopen.png` | `88c4c3b74f2a138dbbf8b073b9aead55a16698c337eeb965310940f1703b79d5` |
| `c01_after_save2.xml` (UAT) | `f11c005db541f6454e8d83c41ba871d01fba78a71d5e5b1e6235a6868d25d5c8` |
| `c01_after_save3.xml` (UAT list) | `83d51865b28770c43be75c2ecc750c7eb48f4b1c40f9c74ea9a50919f1b35aac` |
| `api_uat_summary_2026-11.json` | `f73bb61668fe1ba6b11a1ea749628b28d1eb14b967fd74003e1483d99bc57aee` |
| `api_summary_2026-11.json` (18091) | `40d3b321c2f12a3a1ba11e47c638a2deada1ec2c69d474440d88f969d6555b9a` |
| `api_analysis_2026-11.json` (18091) | `5bb8c140a74134dd0e2fa9c2e761ae58645213da5115ec7b4c10ec16cad42708` |
| `log_18090_c01.txt` | `29db7ee4eb2d5a5d014d46128fb2eb25368ca72bc068d67e0daba9b85f5afbca` |
| `log_18091_analysis_cycle.txt` | `84d5fe4dde398fa4e5f74e981fe3967717a4410d3ef36014f0e721106b915784` |
| `db_18091_c01_analysis.txt` | `f374e1abb40bd798ab5ffaa5e117012d74dafd33953f1870d287065dc89d1e46` |
| `assessment_smoke.xml` | `66e6be4d0da892e71332d766added50db7d1ee211ad0ac7995915450adc4955c` |
| `assessment_smoke.png` | `91a513376a0fca1e3769a632908a8025396b9a735af43f47d3d84e902cd5170e` |

## 7. Kesimpulan acceptance merged head

- **C01** — PASS runtime (create via UI 201 di UAT + cross-check DB/isolated; delta ringkasan
  tepat). Batas: replay lost-response tetap unit/API.
- **S2.5** — PASS runtime (CURRENT, revision-STALE, regenerate→CURRENT, dependency-STALE pasca
  sync semuanya tampil beda dan sesuai kontrak server; guard coverage EMPTY bekerja).
- **C24/C25** — PASS runtime berdasarkan walk teraudit + regression merged; walk perangkat
  last-admin/cursor tidak diulang (batas dicatat).
- **S3/S4** — tidak ada regresi terbuka dari acceptance ini; gate unit/lint/Go hijau.
- **Sisa gap eksternal (S5)** tidak berubah: mailbox nyata + email baru, benchmark berlabel
  manusia, 36 email malformed, UAT pemilik di perangkat, hosting/backup-restore,
  keputusan merge/deploy.
