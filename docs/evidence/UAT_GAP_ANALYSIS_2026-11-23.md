# UAT gap S2.5 — Analisis kedaluwarsa (freshness analisis) — 2026-11-23

Lane: `worker-android-delivery` · Branch: `work/gap-analysis` (dari `0e76329` = `origin/feat/c2-android` HEAD)
Worktree: `pam-wt-analysis` · Backend: lokal, port **18091**, DB file `/tmp/pam-gap-analysis/pam.db`,
`LLM_PROVIDER=fake` · Perangkat: emulator **5556** (`Pixel_10_Pro_XL`), APK debug
`-PPAM_BACKEND_BASE_URL=http://127.0.0.1:18091` + `adb reverse tcp:18091 tcp:18091`.

## 1. Kriteria yang diuji (MANDAT_S2_S4.md §S2.5)

> Analisis memakai canonical aggregate/dependency revision server; CURRENT/STALE/HISTORICAL serta
> coverage COMPLETE/PARTIAL/EMPTY/FAILED tampil berbeda.

Fokus lane ini: **status analisis kedaluwarsa** pada layar Analisis (revisi kanonik → CURRENT vs
STALE vs HISTORICAL, dan pembeda terhadap *network error* serta *hasil belum tersedia*).

## 2. Trace kontrak: Go → wire → Android

| Lapis | Sumber | Perilaku |
|---|---|---|
| Go (otoritatif) | `backend-go/internal/httpapi/handlers_analysis.go` — `analysisJSON()` | `freshness` default `STALE`; `HISTORICAL` bila status `HISTORICAL`; `CURRENT` hanya bila `status=="AVAILABLE" && DependencyHash!="" && DependencyHash==currentDependencyHash && InputRevision==currentRevision`. `currentRevision` = revisi agregat live. |
| Go (summary) | `handlers_data.go` — `getSummary` | `currentRevision` = revisi live; `freshness` CURRENT/STALE/HISTORICAL dari perbandingan payload tersimpan. |
| Wire (bukti live) | `POST/GET /v1/analyses/{monthId}` (contract `daa5f36`) | `{monthId,inputRevision,currentRevision,freshness,status,promptVersion,observations,suggestions,limitations}` — lihat `docs/evidence/assets/s25/*.json`. |
| Android sebelum perbaikan | `RemoteAnalysis` + `PamApiClient.latestAnalysis()` ada, tetapi **tidak dipakai**; `AnalysisViewModel`/`AnalysisScreen` hanya memakai `Analysis.status` lokal. Server menyimpan `status=AVAILABLE` walau `freshness=STALE` → chip tetap "Tersedia". | **gap** |
| Android sesudah perbaikan | Mode backend: `AnalysisViewModel` mengambil summary bulan dari `ServerFinanceRepository.summary` **dan** analisis tersimpan dari `PamApiClient.latestAnalysis()` (`ServerAnalysisRepository.latest`), lalu merender chip dari `freshness` server (sumber utama); `Analysis.freshnessAgainst(summary)` hanya fallback saat freshness server `UNKNOWN`. `summary == null` → `UNKNOWN`/`UNAVAILABLE` (tidak lagi dipaksa CURRENT). Tombol buat/regenerate mengirim `POST /v1/analyses` dengan payload dari data server nyata, `inputRevision = summary.currentRevision`, idempotency key stabil. Mode lokal/offline tetap memakai pipeline lama. | **fixed (B-1, B-2, B-3)** |

## 3. Bukti API (backend lokal, data sintetis, provider fake)

**3a. Siklus awal (commit `94494ff`, bukti tetap berlaku).** Akun sintetis + 3 transaksi seed →
summary `inputRevision=3, currentRevision=3, freshness=CURRENT` (`assets/s25/summary-before.json`),
lalu `POST /v1/analyses` revisi 3 → `freshness=CURRENT, status=AVAILABLE`.

Mutasi transaksi ke-4 → summary `inputRevision=4, currentRevision=4` (`assets/s25/summary-after-mutation.json`).
`GET /v1/analyses/2026-11` **tanpa** membuat analisis baru:

```json
{"monthId":"2026-11","inputRevision":3,"currentRevision":4,"freshness":"STALE","status":"AVAILABLE"}
```

Ini persis gap Android: server tahu basi (`freshness=STALE`), tetapi `status` masih `AVAILABLE`.

**3b. Verifikasi ulang wiring baru (build ini, `assets/s25_remote/`).** Backend lokal :18091 yang sama,
token sesi segar, analisis tersimpan lama (hasil commit sebelumnya) dibaca mentah:

```json
// analysis-before.json — GET /v1/analyses/2026-11
{"monthId":"2026-11","inputRevision":4,"currentRevision":5,"freshness":"STALE","status":"AVAILABLE"}
```

Dua mutasi transaksi lewat `POST /v1/transactions` (idempotency key terpisah) menaikkan revisi
live 5 → 6 → 7 (`transaction-mutation.json`, `transaction-mutation-2.json`); summary live menjadi
`inputRevision=currentRevision=6` lalu `7` (`summary-2026-11-after-mutation*.json`).

Regenerasi dari UI (lihat §6) menghasilkan `POST /v1/analyses` 200 dan `GET` sesudahnya:

```json
// analysis-after-regenerate.json
{"monthId":"2026-11","inputRevision":5,"currentRevision":5,"freshness":"CURRENT","status":"AVAILABLE"}
// analysis-final.json (setelah regenerasi pada revisi 7)
{"monthId":"2026-11","inputRevision":7,"currentRevision":7,"freshness":"CURRENT","status":"AVAILABLE"}
```

Catatan: backend Go berjalan (`/tmp/pam-be-merged`) karena `go` tidak ada di PATH lane ini; respons live
diverifikasi sama dengan rule di source `handlers_analysis.go`/`handlers_data.go`. DB file lokal,
**bukan** Turso.

**3c. Cacat decoding yang ditemukan saat E2E (dan diperbaiki).** Backend menulis field list kosong
sebagai JSON `null` eksplisit (`"suggestions": null`). Decoder strict `PamJson` (non-nullable list)
gagal → `AppError.Parsing` pada GET ("Data tidak dapat dibaca.") dan `AppError.Network("backend_invalid_response")`
pada POST ("Tidak dapat terhubung") **walau server sudah menyimpan analisis baru**. Field list pada
`RemoteAnalysis` dan `RemoteAnalysisResponse` kini nullable + `orEmpty()`; regression test ditambahkan.
Temuan ini tidak akan terlihat tanpa E2E perangkat — bukti penting bahwa unit test saja tidak cukup.

## 4. Perbaikan Android (diff)

**Wiring server (B-1/B-2/B-3).**
- `data/repository/ServerAnalysisRepository.kt` (baru): kontrak `isEnabled`, `latest(ownerId, month)`
  (404 → `Success(null)`), `generate(ownerId, month)`.
- `data/backend/ServerAnalysisRepositoryImpl.kt` (baru): `latest()` dari `PamApiClient.latestAnalysis`;
  `generate()` menyusun payload **dari summary server bulan ini + summary bulan lalu + (opsional)
  budget plan**, `inputRevision = summary.currentRevision`, lalu `RemoteAnalysisEngine.analyze()`
  (POST `/v1/analyses`, idempotency key stabil, consent 403 → `AppError.PermissionRequired`).
  `ServerAnalysisPayload.assemble` + `RemoteAnalysis.toServer()` memetakan `freshness` server:
  CURRENT/HISTORICAL/STALE/else UNKNOWN → `STALE→OUTDATED`, `HISTORICAL→HISTORICAL`,
  `UNKNOWN→UNAVAILABLE`.
- `di/BackendModule.kt`: binding `ServerAnalysisRepository`.
- `data/backend/ServerAnalysisRepositoryImpl.kt` — **guard consent lokal (re-audit B-RE-3):**
  `generate()` membaca `ConsentRepository.latest(ownerId, AI_PROCESSING)` sebelum menyentuh jaringan;
  belum granted → `AppError.Validation(CONSENT_REQUIRED)`, withdrawn → `AI_CONSENT_WITHDRAWN` —
  sama dengan `RequestAnalysisUseCase`, tanpa POST. Tombol UI disabled dan 403 server tetap ada,
  tetapi repository tidak lagi bergantung pada keduanya untuk request yang punya efek samping.
- `ui/feature/analysis/AnalysisViewModel.kt`: mode backend (`serverAnalysis.isEnabled`) memakai
  `combine(consent, ui)` → fetch summary + analisis tersimpan tiap emisi; error summary/analisis dan
  error jaringan tetap terpisah dari "belum ada analisis"; `generate()` memakai
  `serverAnalysis.generate` + refetch (`refreshTick`). Mode lokal tidak berubah.
- `data/backend/ServerFinanceRepositoryImpl.kt`: mapping summary mengisi
  `currentRevision = maxOf(currentRevision, inputRevision)` (revisi live otoritatif,
  `canGenerate` dapat tercapai di mode server).
- `data/backend/PamApiClient.kt` + `data/ai/RemoteAnalysisEngine.kt`: field list nullable (JSON null);
  `domain/model/Summary.kt`: `MonthlySummary.currentRevision`, `SummaryFreshness.UNKNOWN`.
- `domain/model/Analysis.kt`: `freshnessAgainst(summary)` — HISTORICAL→HISTORICAL, OUTDATED→STALE,
  `summary == null` → **UNKNOWN** (B-3; dulu CURRENT), summary HISTORICAL/STALE/UNKNOWN diteruskan,
  bandingkan `inputRevision < summary.currentRevision`; `displayStatus`: UNKNOWN→UNAVAILABLE.
- `ui/feature/analysis/AnalysisScreen.kt`: banner memakai `summary.currentRevision`; bila revisi sama
  tetapi data berubah (dependency hash) → string `analysis_stale_dependency_body` (B-2);
  `ui/feature/budget/BudgetPlanScreen.kt`: `when` ekshaustif untuk `UNKNOWN`.
- `res/values/strings.xml` + `values-en/strings.xml`: `analysis_stale_dependency_body`.

**Test baru/diperbarui.**
- `domain/model/AnalysisFreshnessTest.kt`: summary null → UNKNOWN/UNAVAILABLE, stale dependency-hash
  dengan revisi sama, bump `currentRevision`.
- `data/backend/ServerAnalysisMappingTest.kt`: STALE→OUTDATED, dependency stale revisi sama,
  CURRENT→AVAILABLE, HISTORICAL, freshness tak dikenal→UNAVAILABLE, observasi/limitasi dipertahankan,
  **JSON `null` list tetap ter-decode**.
- `data/backend/ServerAnalysisPayloadTest.kt`: angka summary, bulan lalu, plan opsional.
- `data/ai/RemoteAnalysisEngineTest.kt`: respons 200 dengan `suggestions/limitations: null` tetap sukses.
- `data/backend/ServerAnalysisRepositoryConsentTest.kt` (baru, B-RE-3): consent absen → gagal lokal
  `CONSENT_REQUIRED` **tanpa** request engine/HTTP (`MockWebServer.requestCount == 0`); consent
  withdrawn → `AI_CONSENT_WITHDRAWN`; consent granted → engine tetap dipanggil dengan
  `inputRevision = currentRevision`.
- `ui/feature/analysis/AnalysisFreshnessScreenTest.kt`: summary null → chip "Tidak tersedia" tanpa
  "Tersedia"/"Kedaluwarsa"; banner dependency exact-text.
- `data/backend/DisabledServerAnalysisRepository.kt` (test double) + `ScreenScreenshotsTest` disesuaikan.

## 5. Gate (dijalankan via `/tmp/pam_build_lock.sh`)

| Gate | Perintah | Hasil |
|---|---|---|
| Unit suite penuh | `./scripts/build.sh --no-watch-fs :app:testDebugUnitTest --rerun-tasks` | **493 test / 68 kelas, 0 gagal, 0 error, 0 skip** (baseline lane `94494ff`: 476 test / 65 kelas; +14 test wiring server, +3 test guard consent B-RE-3) |
| Lint | `… :app:lintDebug` | **0 error, 115 warning, 1 hint** |
| APK | `… :app:assembleDebug -PPAM_BACKEND_BASE_URL=http://127.0.0.1:18091` | `app/build/outputs/apk/debug/app-debug.apk`, sha256 `fcfd55bf9c3ab0c1795cca3cf91a045b903ea744efd51a97f03916d5247988d3` (APK yang dipakai E2E §6, dibangun 19:59:02 sebelum guard consent B-RE-3; guard hanya menambah jalur gagal-lokal dan tidak mengubah wiring E2E) |

Catatan tooling: worktree ini tidak punya `.toolchain`/`.gradle-home`/`.robolectric-deps` miliknya;
dipakai milik checkout utama (`PAM_JDK_HOME`, `PAM_GRADLE_USER_HOME`, symlink `.robolectric-deps`,
untracked) — bukan bagian commit.

## 6. Bukti perangkat (emulator 5556), mode backend — end-to-end

APK debug dengan `PAM_BACKEND_BASE_URL=http://127.0.0.1:18091` dipasang ulang (`install -r`, data
login lama dipertahankan) + `adb reverse tcp:18091 tcp:18091`. Akun sintetis sama; backend lokal
`:18091` (healthz 200). Semua dump diambil dengan `uiautomator dump`, screenshot dengan `screencap`.

**Timeline aset dan APK (penting untuk membaca sitasi):** langkah 1–4 di bawah diambil pada
**19:56–19:57** dengan APK lama **pra-fix decode**; APK yang sudah memuat perbaikan nullable
(`suggestions:null`) baru dibangun **19:59:02** (sha256 `fcfd55bf…`) lalu dipasang. Jadi aset langkah
2 dan 4 **memang masih memuat banner "Tidak dapat terhubung…"**: keduanya adalah *state bug* yang
diakui dan diperbaiki pada §3c/butir 6, bukan bukti state bersih. Bukti state bersih pasca-fix adalah
aset langkah 5–6 (`s25-remote-current-7.xml/.png`, `s25-remote-reopen-current.xml/.png`), yang diambil
**20:00–20:01** setelah APK fix terpasang, dan bebas banner error.

1. **Analisis tersimpan terbaca dari server, chip STALE + banner nomor revisi.**
   Sebelum regenerasi, `GET /v1/summaries/2026-11` → `currentRevision=5` dan
   `GET /v1/analyses/2026-11` → `inputRevision=4`, `freshness=STALE`, `status=AVAILABLE`.
   Layar Analisis menampilkan kartu **"ANALISIS TERSIMPAN"**, chip **"Kedaluwarsa"**, banner
   *"Analisis ini dibuat dari revisi 4, sedangkan data bulan ini sudah di revisi 5. Buat ulang
   analisis agar sesuai data terbaru."* — bukan "Tersedia", bukan error, bukan "Belum ada analisis".
   Bukti: `assets/s25_remote/s25-remote-analysis-before.xml`, `s25-remote-stale-banner.png`.
2. **Siklus pra-fix (19:56): regenerasi mengubah chip menjadi "Tersedia", tetapi banner error masih
   muncul karena bug decode.** Tap "Buat ulang analisis" → log backend `POST /v1/analyses 200`
   (payload dari summary server, `inputRevision=5`), lalu `GET /v1/summaries` + `GET /v1/analyses`
   refetch. Chip berubah dari "Kedaluwarsa" menjadi **"Tersedia"**, banner kedaluwarsa hilang — tetapi
   UI **masih menampilkan banner "Tidak dapat terhubung…"** akibat `suggestions:null` (§3c). Ini
   sengaja didokumentasikan sebagai state bug, bukan klaim bersih.
   Bukti: `s25-remote-analysis-current.xml`, `s25-remote-current-chip.png` (keduanya memuat banner
   error, sesuai timeline); respons `assets/s25_remote/analysis-after-regenerate.json`
   (`inputRevision=currentRevision=5`, `freshness=CURRENT`).
3. **Mutasi transaksi → revisi naik → chip STALE lagi (5→6).**
   `POST /v1/transactions` 201 (`assets/s25_remote/transaction-mutation.json`), summary live
   `currentRevision=6`. Aplikasi di-restart dan dibuka ke Analisis → chip **"Kedaluwarsa"** + banner
   *"…revisi 5, … sudah di revisi 6…"*. Bukti: `s25-remote-analysis-stale-after-mutation.xml`,
   `s25-remote-stale-after-mutation.png`.
4. **Siklus pra-fix (19:57): regenerasi pada revisi 6 juga masih memuat banner error decode.**
   Chip "Tersedia" dan banner kedaluwarsa hilang, tetapi banner "Tidak dapat terhubung…" masih ada;
   ini state bug yang sama, bukan state bersih. Bukti: `s25-remote-analysis-current-after-mutation.xml`,
   `s25-remote-current-after-mutation.png` (keduanya memuat banner error, sesuai timeline).
5. **Siklus kedua pasca-fix (20:00): 6→7, chip CURRENT bersih, dan persistensi setelah
   buka-tutup.** APK fix (19:59:02) terpasang; data login dipertahankan. Mutasi kedua → summary
   `currentRevision=7` (`summary-2026-11-after-mutation-2.json`). Restart → chip "Kedaluwarsa" +
   banner 6→7 (`s25-remote-stale-6-7.xml/.png`). Regenerasi → chip **"Tersedia"**, **tanpa banner
   kedaluwarsa dan tanpa banner error** (`s25-remote-current-7.xml/.png`; dicek: tidak ada node
   "Tidak dapat terhubung"). Force-stop + buka kembali aplikasi → tetap **"Tersedia"**
   (`s25-remote-reopen-current.xml/.png`), dan `GET /v1/analyses/2026-11` akhir:
   `{"inputRevision":7,"currentRevision":7,"freshness":"CURRENT","status":"AVAILABLE"}`
   (`analysis-final.json`). Jadi state bersih + status CURRENT terbukti pada APK fix, tidak hanya
   sesaat setelah generate tetapi juga setelah refetch dan reopen.
6. **Cacat nyata yang ditemukan E2E lalu diperbaiki:** regenerasi pertama sempat menampilkan banner
   "Tidak dapat terhubung" **setelah** POST 200, karena respons memuat `"suggestions": null` dan
   decoder strict melempar exception yang dipetakan ke `AppError.Network` (§3c). Setelah perbaikan
   nullable + regression test, siklus 5–6 di atas diulang dan bersih (chip Tersedia, tanpa banner
   error). Tanpa E2E perangkat cacat ini tidak terdeteksi.

**Batas environment (jujur):** E2E offline/local-mode sesuai keputusan PM dibatalkan; jalur lokal
tetap dijaga unit test. Kasus `HISTORICAL` dan stale dependency-hash dengan revisi sama tidak
dipentaskan di perangkat (butuh data/status khusus) tetapi diuji unit. Consent 403 hanya diuji unit
(consent di perangkat sudah diberikan sejak awal). Guard consent lokal baru (B-RE-3) diuji unit
dengan MockWebServer (0 request saat consent absen); di perangkat tombol memang disabled saat consent
belum ada, sehingga jalur itu tidak dapat dipentaskan tanpa mencabut consent lebih dulu.

## 7. Gap / risiko yang tersisa

1. **Teks penyedia masih menulis "mesin analisis deterministik di perangkat (tanpa jaringan)"** pada
   mode server. Ini salinan statis lama dan sekarang menyesatkan (analisis justru dikirim ke backend).
   Tidak memengaruhi kebenaran status, tetapi perlu diperbaiki pada lane UI/copy berikutnya.
2. **Kasus `HISTORICAL`** belum dipertunjukkan di perangkat; aturan + render diuji unit/Compose.
3. **Dependency-hash STALE dengan revisi sama** (mis. perubahan kategori/budget/import tanpa kenaikan
   revisi) diuji unit; E2E perangkat untuk kasus itu belum ada.
4. **Idempotency:** yang diuji unit hanyalah **stabilitas key** (sama untuk owner+bulan+revisi, beda
   untuk input lain); replay/dedup sesungguhnya belum diuji unit maupun E2E — E2E hanya membuktikan
   `POST /v1/analyses` 200 dan refetch sesudahnya, bukan bahwa request key sama tidak membuat baris
   analisis duplikat.
5. Backend uji adalah binary berjalan (`/tmp/pam-be-merged`), bukan rebuild Go hari ini; DB file
   lokal, bukan Turso. Tidak ada perubahan backend di commit ini.
6. `RemoteAnalysisEngine` memetakan kegagalan decode ke `AppError.Network` (bukan Parsing); pesannya
   generik. Tidak mengubah perilaku yang diminta audit, tetapi copy error bisa lebih tepat.
7. Commit `94494ff` (aturan stale lokal) tetap ada di history; commit ini menambahkan wiring server di
   atasnya. Branch `work/gap-analysis`; tidak ada merge/deploy.
