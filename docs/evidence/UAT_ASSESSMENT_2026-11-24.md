# UAT S4.4 — Audit Varian `assessment` (24 November 2026)

Status: **PASS (audit statis + build/test), install-verify NOT RUN**
Owner: w-qa · Backend: tidak terlibat (varian offline/foreground-only) · Repo HEAD: `4dacad7` (`feat/c2-android`)

## 1. Tujuan
Membuktikan klaim matriks S4.4: varian `assessment` dapat di-build dan diuji, **tanpa** Room/SQLCipher/WorkManager, dengan batasan foreground-only — memakai bukti baru (bukan mengutip hasil lama).

## 2. Perintah yang dijalankan (terisolasi, serial `/tmp/pam-build.lock`)
```
./scripts/build.sh :app:assembleAssessment
./scripts/build.sh :app:testAssessmentUnitTest --rerun-tasks
./scripts/build.sh :app:dependencies --configuration assessmentRuntimeClasspath
```
Artefak:
- Log build+test+deps: `docs/evidence/assets/pm_qa_2026-11-24/assessment_build_test_2026-11-24.log`
- Graf dependensi: `docs/evidence/assets/pm_qa_2026-11-24/assessment_runtime_classpath_2026-11-24.txt`
- Hasil tes: `app/build/test-results/testAssessmentUnitTest/*.xml`
- APK: `app/build/outputs/apk/assessment/app-assessment.apk` (24.343.538 byte, 24 Nov 2026 08:37)

## 3. Hasil
| Cek | Hasil |
|---|---|
| `assembleAssessment` | **RC=0** (BUILD SUCCESSFUL) |
| `testAssessmentUnitTest --rerun-tasks` | **RC=0**; **523 tes, 0 failure, 0 error, 0 skipped**, 72 suite (termasuk `AssessmentVariantTest` 4 tes & `AssessmentDurabilityTest` 9 tes) |
| `dependencies assessmentRuntimeClasspath` | **RC=0**; grep `androidx.room`, `sqlcipher`, `androidx.work`/`work-runtime` = **0 baris** |
| APK badging | `package name='com.pam.recap.debug' versionName='1.0.0-debug' targetSdkVersion=36` |
| Native lib assessment | hanya `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so` — **tidak ada `libsqlcipher.so`** |
| Native lib debug (pembanding) | `libsqlcipher.so` untuk arm64-v8a/armeabi-v7a/x86/x86_64 (mis. 5.797.528 byte arm64) |
| Dex strings assessment vs debug | `androidx.room`: **0** vs 847 · `RoomDatabase`: **0** vs 192 · `net.zetetic`: **0** vs 63 · `sqlcipher`: **0** vs 56 · `androidx.work`: **0** vs 863 · `WorkManager`: **0** vs 104 · `WorkManagerImpl`: **0** vs 29 |

Catatan interpretasi: string `Landroid/database/sqlite/SQLiteDatabase;` di dex assessment berasal dari **framework Android** (`android.database.sqlite`) via core-ktx, **bukan** SQLCipher/net.zetetic/Room. Tidak ada provider inisialisasi WorkManager yang aktif di assessment (string `androidx.work` = 0).

## 4. Batasan yang harus dicatat di matriks
- **assessment tidak dapat berdampingan dengan debug**: `applicationId` sama (`com.pam.recap.debug`, tanpa suffix) → `adb install` assessment akan **mengganti** app debug terpasang. Karena itu varian ini **tidak dipasang** ke emulator UAT (menghindari merusak sesi/instalasi debug yang dipakai walk-through); bukti install di perangkat = **NOT RUN (by design)**.
- Varian assessment = **foreground-only**: tidak ada WorkManager → tidak ada sinkronisasi/pekerjaan background, tidak ada Room/SQLCipher → tidak ada DB lokal terenkripsi. Data akun lokal memakai DataStore/berkas, bukan Room.
- Audit ini statis/dependensi + unit test; bukan pengukuran runtime memori/APK startup.

## 5. Reproduksi (jika diminta install-verify)
```
# build dengan backend UAT (opsional, assessment tidak butuh)
PAM_BACKEND_BASE_URL=http://10.0.2.2:18090 ./scripts/build.sh :app:assembleAssessment
adb -s <serial> install -r app/build/outputs/apk/assessment/app-assessment.apk   # MENIMPA debug
# setelah selesai, kembalikan build debug:
PAM_BACKEND_BASE_URL=http://10.0.2.2:18090 ./scripts/build.sh :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```
Data aplikasi tidak terhapus oleh `install -r`, tetapi sesi login lama bisa menjadi stale setelah berganti backend/varian.

## 6. Kesimpulan
Klaim S4.4 “build & test varian assessment tanpa Room/SQLCipher/WorkManager” **terbukti ulang 24 Nov 2026** dengan RC build/test/deps 0, 523 tes lulus, dan audit APK/dex/native-lib yang menunjukkan ketiadaan tiga dependensi tersebut. Satu batasan operasional wajib tercantum: **assessment tidak koexist dengan debug** dan varian ini **tidak di-install** pada device UAT (NOT RUN by design).
