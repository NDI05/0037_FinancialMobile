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
| Android sesudah perbaikan | `Analysis.freshnessAgainst(summary)` + `Analysis.displayStatus(summary)`; `AnalysisScreen` merender chip efektif + notice kedaluwarsa bernomor revisi. | **fixed** |

## 3. Bukti API (backend lokal, data sintetis, provider fake)

Akun sintetis + 3 transaksi seed → summary `inputRevision=3, currentRevision=3, freshness=CURRENT`
(`summary-before.json`), lalu `POST /v1/analyses` revisi 3 → `freshness=CURRENT, status=AVAILABLE`
(`analysis-created-1.json`).

Mutasi transaksi ke-4 (850.000) → summary `inputRevision=4, currentRevision=4, freshness=CURRENT`
(`summary-after-mutation.json`). `GET /v1/analyses/2026-11` tanpa membuat analisis baru:

```json
{"monthId":"2026-11","inputRevision":3,"currentRevision":4,"freshness":"STALE","status":"AVAILABLE"}
```
(`analysis-after-mutation.json`)

Ini persis gap Android: server tahu basi (`freshness=STALE`), tetapi `status` masih `AVAILABLE`.

Regenerasi pada revisi 4 → `inputRevision=4, currentRevision=4, freshness=CURRENT, status=AVAILABLE`
(`analysis-created-2.json`); `GET` ulang untuk mensimulasikan buka-layar-kembali → tetap `CURRENT`
(`analysis-refetched-2.json`, respons idempoten).

Catatan: angka di atas dari backend Go yang sudah berjalan (`/tmp/pam-be-merged`, build 16:35) karena
`go` tidak ada di PATH saat lane ini; respons live diverifikasi sama dengan rule di source
`handlers_analysis.go`. DB menggunakan file lokal, **bukan** Turso.

## 4. Perbaikan Android (diff)

- `app/src/main/java/com/pam/recap/domain/model/Analysis.kt` (+35): `freshnessAgainst(summary)` —
  STALE bila `isPublishable && inputRevision < summary.inputRevision`, bila summary sendiri STALE,
  atau analisis lokal OUTDATED; HISTORICAL bila status HISTORICAL atau summary HISTORICAL; selain itu
  CURRENT. `displayStatus(summary)` memetakan freshness → status chip (`OUTDATED`/`HISTORICAL`/status
  asli). Tidak menambah field wire baru — memakai revisi kanonik yang sudah ada
  (`summary.inputRevision` = `currentRevision` server).
- `app/src/main/java/com/pam/recap/ui/feature/analysis/AnalysisScreen.kt`: `AnalysisHeader` memakai
  status efektif; `AnalysisFreshnessHeader` menampilkan `StatusChip(Outdated)` + `NoticeBanner`
  bernomor revisi saat STALE. Pesan error jaringan tetap banner `AppError` terpisah; kondisi
  "belum ada analisis" tetap kartu kosong — ketiganya tidak saling tertukar.
- `app/src/main/res/values/strings.xml` + `values-en/strings.xml`: `analysis_stale_body`
  (ID: "Analisis ini dibuat dari revisi %1$d, sedangkan data bulan ini sudah di revisi %2$d …";
  EN analog).
- Test baru: `app/src/test/java/com/pam/recap/domain/model/AnalysisFreshnessTest.kt` (9 test aturan
  stale/current/historical/missing-summary/rejected) dan
  `app/src/test/java/com/pam/recap/ui/feature/analysis/AnalysisFreshnessScreenTest.kt` (4 test render
  Compose: chip + notice bernomor revisi, chip Tersedia tanpa notice, chip Riwayat untuk consent
  dicabut, summary null tidak dipaksa STALE).

## 5. Gate (dijalankan via `/tmp/pam_build_lock.sh`)

| Gate | Perintah | Hasil |
|---|---|---|
| Unit suite penuh | `./scripts/build.sh --no-watch-fs :app:testDebugUnitTest --rerun-tasks` | **476 test / 65 kelas, 0 gagal** (baseline `0e76329`: 463 test; +13 test lane ini) |
| Lint | `… :app:lintDebug` | **0 error, 115 warning, 1 hint** |
| APK | `… :app:assembleDebug -PPAM_BACKEND_BASE_URL=http://127.0.0.1:18091` | `app/build/outputs/apk/debug/app-debug.apk`, sha256 `e1cd80a2799a8ffb2475cb88ba1c3fc92661e6423f7a24030ab6257af7d46555` |

Catatan tooling: worktree ini tidak punya `.toolchain`/`.gradle-home`/`.robolectric-deps` miliknya;
dipakai milik checkout utama (`PAM_JDK_HOME`, `PAM_GRADLE_USER_HOME`, symlink `.robolectric-deps`,
untracked) — bukan bagian commit.

## 6. Bukti perangkat (emulator 5556)

Terbukti di perangkat, dengan akun sintetis yang sama dan backend lokal:

1. Login akun server sintetis berhasil (onboarding persetujuan → "Lanjut tanpa menghubungkan Gmail").
2. Overview membaca data server: 4 transaksi, arus kas bersih **Rp65.000** (masuk 90.000, keluar 25.000)
   — cocok dengan summary server `netMinor=6500000` pasca-mutasi (`summary-after-mutation.json`).
3. Layar Analisis menampilkan keadaan yang berbeda-beda, tidak tertukar:
   - *hasil belum tersedia*: kartu "Belum ada analisis" + CTA "Buat analisis";
   - *izin belum ada*: gate persetujuan AI ("Persetujuan analisis AI belum diberikan…"), tombol
     "Buka persetujuan";
   - *error jaringan*: banner "Tidak dapat terhubung. Periksa koneksi Anda lalu coba lagi." (diamati
     saat backend belum dijangkau sebelum `adb reverse`).
   Screenshot: `docs/evidence/assets/s25_analysis_empty_server_mode.png` (+ dump
   `s25_analysis_empty_server_mode.xml`).

**Keterbatasan perangkat (penting, jujur):** build yang diuji berjalan dengan backend aktif, sehingga
sumber data layar (Ringkasan/Transaksi) adalah server, sedangkan pipeline pembuatan analisis
(`ComputeMonthlySummaryUseCase` → `TransactionRepository` lokal/Room) masih membaca Room lokal yang
kosong (impor Gmail tidak tersedia di emulator dan consent email ditolak). Akibatnya tombol
"Buat analisis" nonaktif (`canGenerate = consent && summary.hasData`) dan analisis tidak bisa
dibuat dari UI di mode server — jalur *stale → regenerate → reopen* tidak dapat dipertunjukkan
end-to-end di perangkat pada environment ini. Ini gap lintas-fitur (mode server vs pipeline analisis
lokal), di luar perubahan S2.5, dan dicatat sebagai risiko di §7. Aturan stale + render banner
dibuktikan oleh unit/Compose test (13/13 lulus) dan kebenaran revisi kanonik oleh bukti API §3.

## 7. Gap / risiko yang tersisa

1. **Mode server vs analisis lokal**: bila aplikasi terhubung ke backend, layar analisis masih
   memakai Room lokal untuk compute-summary, sehingga analisis tidak dapat dibuat walau server punya
   data. Perlu wiring `ServerFinanceRepository` → pipeline analisis (atau sinkronisasi transaksi
   server ke Room) — di luar scope lane ini.
2. **Android belum membaca `freshness` server secara langsung**: `latestAnalysis()` masih unused;
   derivasi saat ini memakai revisi kanonik yang diproyeksikan ke `MonthlySummary.inputRevision`
   (dan status lokal). Bila nanti analisis diambil dari server, VM sebaiknya memakai
   `RemoteAnalysis.freshness`/`currentRevision` sebagai sumber utama dan summary hanya fallback.
   Tidak ada field/aturan baru yang dikarang; semua nilai berasal dari kontrak yang ada.
3. Uji perangkat memakai backend lokal berjalan (bukan hasil rebuild Go hari ini di worktree); bukti
   API tetap valid untuk kontrak karena respons live diverifikasi terhadap source handler.
