# Provenance pasangan build final — 24 November 2026

Dokumen ini mengikat angka gate Android ke artefak build yang benar-benar ada, sehingga hash
APK di laporan/UAT dapat diverifikasi ulang dari tree yang sama.

## Perintah gate

```bash
JAVA_HOME=pam-app/.toolchain/jdk-21.0.13.1+2/Contents/Home \
./gradlew testDebugUnitTest testAssessmentUnitTest lintDebug lintAssessment \
  assembleDebug assembleAssessment --rerun-tasks
```

Log mentah build: `/tmp/pam-final-gates.log` (ephemeral, 272 baris, berakhir
`BUILD SUCCESSFUL in 3m 38s`). Angka tes di bawah dihitung dari XML hasil tes di tree build,
bukan dari narasi sesi.

## Angka dari artefak

| Artefak | Nilai |
|---|---|
| `app/build/test-results/testDebugUnitTest/*.xml` | **80 kelas, 601 tes, 0 gagal, 0 error** |
| `app/build/test-results/testAssessmentUnitTest/*.xml` | **81 kelas, 611 tes, 0 gagal, 0 error** |
| `app/build/reports/lint-results-debug.html` | 0 error, 116 warning |
| `app/build/reports/lint-results-assessment.html` | 0 error, 124 warning |
| `assembleDebug` / `assembleAssessment` | BUILD SUCCESSFUL (keduanya) |

## Hash APK

| APK | sha256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `e5cba1dc79e88df67e9d1c23281eb3f686459d5d622c27b0b1c9f0bba5e16436` |
| `app/build/outputs/apk/assessment/app-assessment.apk` | `8f3254c7c70f2714be50b557c677061ba87a0b79a146cd0ca31af4c29c72f4b3` |

APK ini dibangun dari kode `19e6860` (HEAD kode terakhir; commit sesudahnya hanya
dokumentasi/evidence). APK debug dengan hash di atas dipasang ulang ke emulator 5556 dan
menghasilkan dump/screenshot `ui_integrated/08` (Pengaturan tanpa entri IMAP).
