# Audit Independen Lane Android — SRS v0.7 (CR-008)

- **Auditor:** auditor2 (independen, read-only)
- **Tanggal:** 24 November 2026, 22:39–22:43 WIB
- **Repo:** `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app`
- **Branch / HEAD:** `feat/c2-android` / `1fc23ac` (`origin/feat/c2-android` = `1fc23ac`, working tree bersih)
- **`main`:** `c10ca67` = `origin/main`; tidak ada commit pada `main` di lane ini
- **Backend pasangan:** repo `0037-Backend-Financial`, branch `integration/2026-11-24`, HEAD `2e6fdea` (clean)
- **Range yang diaudit:** `a86bded..HEAD` (89 file, +1751/−8504): `ee80ca4` (admin), `ecdca26` (lampiran), `e7614bc` (anggaran + Room), `d701088` (FR-32), `7a219ed` (FR-79/80), `e4ff68c` (copy), `1acc835` (tes migrasi non-destruktif), `d7c6bca`/`1fc23ac` (docs)
- **Metode:** baca kode + tes, grep sendiri, jalankan gate build independen dengan lock serialisasi. Tidak melakukan fix/commit/checkout, tidak install ke HP, tidak restart backend/port 18090.

## Ringkasan Verdict

| Severity | Jumlah | Inti |
|---|---|---|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 3 | FR-32 bank scope tidak diimplementasikan; FR-26 catatan opsional tidak ada di form manual; tanggal manual tidak dibatasi NFR-15 (masa depan diterima backend) |
| P3 | 3 | sisa string/error anggaran mati; copy "Hubungi admin PAM" pasca-pensiunan admin; klaim artefak APK byte-exact tidak reproducible + drift dokumen README |

**Verdict:** tidak ditemukan P0/P1. Pensiun tiga modul, FR-32 (direction+bulan), FR-79/80, NFR-15, dan migrasi Room v1→v2 terbukti benar. Lane layak dilanjutkan ke UAT perangkat setelah tiga P2 diputuskan/diselesaikan. Gate worker yang berupa **jumlah tes dan lint terbukti persis**; klaim **ukuran/sha256 APK tidak terbukti** (tidak reproducible), dan klaim "form lengkap" serta "strings pensiun bersih" **overstated**.

## Hasil Gate Independen (dijalankan ulang, fresh)

Perintah (di bawah lock serialisasi, tanpa Gradle paralel):

```
/tmp/pam_build_lock.sh ./scripts/build.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks > /tmp/v07_android_gate.log 2>&1
```

- Exit code `0`, `BUILD SUCCESSFUL in 2m 28s`, task `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` semuanya dieksekusi.
- **Tes:** `83` suite (file XML), **`587` tes, `0` failure, `0` error, `0` skipped** — *persis* klaim worker.
- **Lint:** `0 errors, 115 warnings, 1 hint` (`app/build/reports/lint-results-debug.txt`) — *persis* klaim worker.
- **APK:** `app/build/outputs/apk/debug/app-debug.apk` = **47.610.267 byte**, sha256 **`0b03c797f5abda0a2355b8791782d9e33cf31d2d7fdaa39948b7352a4dad4598`**.
  Klaim worker `47.742.524` B / `4b852c377e2a3b4f52ea65e0c1e810f6967fc6f06024703dd7cf1328ef13a298` **tidak dapat direproduksi**; README juga mencantumkan angka lain (`47.610.363` B / `53b9e9e9…`). APK debug memang tidak byte-identik antar-build; angka ini hanya fingerprint, bukan bukti determinisme.
- Tes baru benar-benar tereksekusi (bukan skipped): `PamDatabaseMigrationTest` (1 tes), `TransactionsDirectionFilterTest` (4), `OverviewDrillDownNavigationTest` (3), `OverviewMonthWindowTest` (2), `RetentionWindowTest` (10), `UpdateStatusServerSyncTest` (18).
- **Varian/endpoint:** `:app:assembleDebug` = build type `debug` (standard source set, Room/SQLCipher); `git diff a86bded..HEAD -- app/build.gradle.kts gradle/libs.versions.toml` kosong. BuildConfig hasil build memakai `PAM_BACKEND_BASE_URL` dari `local.properties` lokal (`http://127.0.0.1:18090`, milik profil Run owner) — konfigurasi kode tidak berubah, hanya environment untracked.

## Temuan P2

### P2-1 — FR-32: scope bank tidak diimplementasikan (parameter diterima, tidak pernah dipakai)

- **Lokasi:** `app/src/main/java/com/pam/recap/ui/navigation/PamDestinations.kt:44-55`; `app/src/main/java/com/pam/recap/ui/feature/overview/OverviewScreen.kt:173,190`; `app/src/main/java/com/pam/recap/ui/feature/transactions/TransactionsViewModel.kt` (filter direction/bulan/kategori/hari/query saja); model `Transaction` tidak punya field bank.
- **Bukti:** KDoc route sendiri menyatakan: "`[bank]` is carried for the future bank filter; no bank field exists on transactions yet, so it is accepted but not applied" (`PamDestinations.kt:46-48`). Kartu Masuk/Keluar hanya mengirim `direction` + `monthId` (`OverviewScreen.kt:173,190`). `grep -rn "bank" app/src/main/java/com/pam/recap/domain/model/Transaction.kt` kosong. Pekerja juga menulis di `docs/PROGRESS_DAILY.md:387-392` bahwa param bank "disiapkan, belum diterapkan".
- **Dampak:** SRS v0.7 FR-32 masih mensyaratkan drill-down Kartu Masuk/Keluar "for owner, period **and bank filter**". Saat ini owner tidak dapat memfilter per bank dan data bank bahkan tidak dimodelkan; requirement belum terpenuhi (bukan regresi, tetapi gap acceptance).
- **Rekomendasi:** Putuskan dengan PM: (a) implementasikan field bank pada transaksi + filter server/client, atau (b) turunkan scope FR-32 secara formal (CR) agar klaim requirement traceability tidak menyesatkan. Jangan biarkan parameter "diterima tapi tidak dipakai" sebagai bukti FR-32 selesai.

### P2-2 — FR-26: catatan opsional tidak ada di form Tambah Transaksi manual

- **Lokasi:** `app/src/main/java/com/pam/recap/ui/feature/entry/ManualEntryViewModel.kt:30-44` (state tidak punya `note`; `canSave` hanya amount+description); `app/src/main/java/com/pam/recap/ui/feature/entry/ManualEntryScreen.kt:136-190` (field: arah, nominal, keterangan, tanggal, kategori — tanpa catatan).
- **Bukti:** `AddManualEntryUseCase` sudah mendukung `note: String? = null` dan meneruskannya ke server (`app/src/main/java/com/pam/recap/domain/usecase/TransactionUseCases.kt:46,82`), tetapi UI tidak pernah mengisinya (tidak ada `note` di state/screen; `grep -n "note" ManualEntryViewModel.kt` kosong). Catatan hanya bisa ditambahkan setelah tersimpan lewat edit detail.
- **Dampak:** FR-26 mensyaratkan owner "entering amount, transaction date, direction and the allowed category, description **and optional notes**". Form belum lengkap; klaim worker "form tetap lengkap" overstates. Bukan blocker data (catatan bisa diisi di detail), tetapi requirement belum terpenuhi pada titik entri.
- **Rekomendasi:** Tambahkan field catatan opsional (multiline, opsional) ke state + screen, teruskan ke `note`, sertakan di `ManualPendingActionStore` identity agar retry tidak menghilangkan catatan, dan tambah tes VM/Compose.

### P2-3 — FR-26/NFR-15: tanggal entri manual tidak dibatasi jendela retensi; tanggal masa depan diterima backend

- **Lokasi:** `app/src/main/java/com/pam/recap/ui/feature/entry/ManualEntryViewModel.kt:36,108-110` (`shiftDate` = `date.plusDays(days)`, tanpa clamp); `app/src/main/java/com/pam/recap/ui/feature/entry/ManualEntryScreen.kt:156-188` (dua panah tanggal selalu aktif); `ManualEntryViewModel.kt:42-43` (`canSave` tidak memeriksa tanggal).
- **Bukti:** `shiftDate` tak terbatas; tidak ada pemakaian `RetentionWindow` di VM/screen entri (`grep -rn "RetentionWindow\|canGo" app/src/main/java/com/pam/recap/ui/feature/entry` kosong). Server hanya memakai guard batas bawah `retainNotExpired` pada POST manual (`0037-Backend-Financial/internal/httpapi/handlers_data.go:253`) dan komentar guard menyatakan ia deliberate menjaga perilaku lama tanggal masa depan (`internal/httpapi/retention.go:71-80`). Akibatnya tanggal masa depan **diterima** server, sedangkan bulan masa depan tidak dapat dijangkau picker (`RetentionWindow.canGoLater`), sehingga baris tersebut tersimpan tetapi tidak terlihat di UI.
- **Dampak:** Melanggar NFR-15 ("window ... ends at the current instant, inclusive. Apply ... to financial date selection") dan FR-26 ("The date shall be in the NFR-15 window"). Tanggal lampau di luar window akan ditolak server 4xx tanpa copy khusus; tanggal masa depan menjadi data tersembunyi yang tidak bisa dijangkau dari picker (risiko integritas data/UX).
- **Rekomendasi:** Clamp tanggal ke `[window.oldest, today]` di VM, nonaktifkan panah di batas, validasi tanggal di `canSave`, dan tampilkan pesan khusus bila server menolak `outside_retention`. Jika perilaku "future-dated manual" memang dipertahankan, ubah kontrak/SRS secara eksplisit.

## Temuan P3

### P3-1 — Sisa modul anggaran: error rule + string mati di dua locale

- **Lokasi:** `app/src/main/java/com/pam/recap/core/result/AppError.kt:113` (`PLAN_VALUE_NEGATIVE`); `app/src/main/java/com/pam/recap/ui/util/AppErrorCopy.kt:31` (pemetaan ke `R.string.error_plan_value_negative`); `app/src/main/res/values/strings.xml:109`; `app/src/main/res/values-en/strings.xml:100`.
- **Bukti:** `grep -rn "PLAN_VALUE_NEGATIVE"` hanya menemukan definisi + mapping; tidak ada pemancar/error produksi yang memakai kode ini. String anggaran lain sudah dihapus (diff −122 baris), tetapi dua string ini tertinggal — bertentangan dengan klaim "strings pensiun dihapus".
- **Dampak:** Kode/string mati (bloat, bukti pensiun tidak bersih); tidak ada dampak runtime.
- **Rekomendasi:** Hapus `PLAN_VALUE_NEGATIVE`, mapping, dan string di `values` + `values-en`.

### P3-2 — Copy `error_account_inactive` masih mengarah ke admin yang sudah dipensiunkan

- **Lokasi:** `app/src/main/res/values/strings.xml:430` ("Hubungi admin PAM"), `app/src/main/res/values-en/strings.xml:407`.
- **Bukti:** CR-008/SRS v0.7 menetapkan satu peran USER; modul administrasi pensiun (tidak ada route/screen admin tersisa). Tidak ada alur kontak admin/operator di aplikasi.
- **Dampak:** Pesan error mengarahkan owner ke peran/alur yang tidak ada; membingungkan saat akun inactive.
- **Rekomendasi:** Ubah copy ke kanal dukungan/operator yang benar-benar tersedia (atau netral: "Akun ini tidak aktif. Hubungi dukungan PAM."), kedua locale.

### P3-3 — Bukti artefak APK tidak reproducible + drift dokumen gate

- **Lokasi/bukti:** `docs/PROGRESS_DAILY.md:402-408` mengklaim APK `47.742.524` B sha256 `4b852c37…` pada code commit `1acc835`; `README.md:97` (baris lane `feat/c2-android`) masih mencatat `e4ff68c` dengan **586 tes/82 suite**, APK `47.610.363` B sha `53b9e9e9…`; build independen auditor = `47.610.267` B sha `0b03c797…`. Branch HEAD sebenarnya `1fc23ac` (dua commit docs setelah `1acc835`).
- **Dampak:** Angka byte/sha APK bukan bukti yang dapat diverifikasi ulang (beda mesin/konfigurasi), dan README belum diperbarui ke gate final lane; pembaca bisa menyimpulkan dua hasil gate yang berbeda. Jumlah tes/lint tetap konsisten (587/83/0; 0/115/1) dan terbukti.
- **Rekomendasi:** Dokumentasikan bukti APK sebagai fingerprint build tertentu (sertakan waktu/toolchain/konfigurasi), bukan invariant; perbarui baris README lane ke `1acc835`/`1fc23ac` + angka final, dan tandai angka lama sebagai riwayat. Jangan memakai ukuran/sha APK sebagai satu-satunya gate.

## Fokus 1–8: Hasil Pemeriksaan

1. **Pensiun admin/lampiran/anggaran — LULUS.** Manifest hanya permission `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_BIOMETRIC`; satu activity exported `MainActivity`; satu deep link `pam://gmail/connected` (gate menolak scheme/host/path lain, `GmailLinkIntentGate.kt:37-43`); tidak ada FileProvider, intent filter admin/lampiran/anggaran, atau `shortcuts.xml` (hanya `res/xml/data_extraction_rules.xml`). `PamDestinations.kt` 15 destinasi tanpa route pensiun; `PamNavHost.kt` hanya 4 tab + stack; Settings hanya akun/consent/kategori/hapus/sign-out/Gmail. `grep` `Admin|Attachment|BudgetPlan|BudgetLimit|...` di `app/src` = NONE; endpoint `/v1/admin|/v1/attachments|/v1/budget-plans` = NONE. Sisa satu-satunya: P3-1 (string/error mati) dan P3-2 (copy admin).
2. **Form manual — P2-2/P2-3.** Nominal, tanggal, arah (toggle), kategori, keterangan ada; tanpa file; validasi nominal>0 + keterangan wajib; pesan error via `NoticeBanner`/`AppErrorCopy`. Catatan opsional tidak ada; tanggal tak dibatasi.
3. **FR-32 — LULUS untuk direction+bulan+back; gap bank (P2-1).** Masuk→`Direction.CREDIT`, Keluar→`Direction.DEBIT` (`OverviewScreen.kt:173,190`); route membawa `monthId`+`direction`; `Transactions` satu-satunya destinasi; server memfilter `direction` (`0037-Backend-Financial/internal/store/transactions.go:236-238`); local path `matches()` memfilter direction tanpa filter ganda; cursor scope menyertakan direction dan di-reset saat query baru; `openedAsDrillDown` memberi back ke Ringkasan (`PamNavHost.kt:186-200,420`). Total konsisten: kartu memakai `summary.grossInflow/grossOutflow`, dan backend menghitung gross inflow/outflow dari seluruh baris CREDIT/DEBIT (`internal/finance/calculator.go:80-102`) — sama dengan daftar ber-filter direction. Bulan batas di-clamp sama di summary dan list (`summary.go:47-57`, `transactions.go:211-219`). Bank tidak diterapkan (P2-1).
4. **FR-79/80 — LULUS.** Buka sheet hanya `GET /v1/gmail/status` + polling; `onSheetClosed` hanya membatalkan poll, bukan request (comment + tes `UpdateStatusServerSyncTest`: in-flight request survives close, manual sync application-scoped); tombol sync + progress di sheet yang sama; `last attempt` vs `last success` string/field terpisah (`UpdateStatusSheet.kt:101,106,223,228`); mapping QUEUED/COALESCED/COOLDOWN/409/503/re-auth diuji.
5. **NFR-15 — LULUS.** `RetentionWindow`: `oldest` = satu tahun kalender lalu, `newest` = bulan berjalan, `canGoLater` = `month < newest` (tidak bisa maju), `canGoEarlier` = `month > oldest`; jendela 13 label bila boundary parsial (konsisten dengan backend: bulan boundary parsial diterima, `0037-Backend-Financial/internal/retention/policy.go:100-118`); leap day 29 Feb → 28 Feb diuji (`RetentionWindowTest.kt:44-50`); arrow Overview/Transaksi memakai guard window; `monthId` route di luar window di-clamp.
6. **Migrasi Room v1→v2 — LULUS.** `Migrations.kt` `MIGRATION_1_2` kosong (tidak ada DROP/DELETE); `DatabaseModule.kt:46` hanya `.addMigrations(MIGRATION_1_2)`, tanpa `fallbackToDestructiveMigration`; `PamDatabase.kt version = 2`; `1.json` tidak berubah, `2.json` ditambahkan; `PamDatabaseMigrationTest` membangun DB v1 nyata dari `1.json` (createSql + identityHash), mengisi transaksi/kategori/settings/`budget_plans`/`budget_limits`, membuka Room v2, dan memverifikasi version=2 + semua baris utuh + DAO membaca ulang.
7. **Gate — lihat tabel di atas.** 587/83/0 dan lint 0/115/1 terbukti; APK tidak reproducible; varian debug/standard tidak berubah; `main` utuh.
8. **P0/P1/cross-account — nihil P0/P1.** Owner switch memicu hard reset di server path (`TransactionsViewModel.kt:300-333`, "An owner switch is a hard reset"); Overview `flatMapLatest` meng-emit loading saat owner/bulan berubah; `ActiveMonthStore` per owner. Tidak ditemukan jalur kebocoran data antar-akun yang dapat dibuktikan; tidak ada perubahan izin/permission baru.

## Klaim Worker: Terbukti / Tidak

| Klaim | Status |
|---|---|
| 587 tes / 83 suite / 0 gagal | **TERBUKTI** (identik, fresh `--rerun-tasks`) |
| Lint 0 error / 115 warning / 1 hint | **TERBUKTI** (identik) |
| APK 47.742.524 B sha256 `4b852c37…` | **TIDAK TERBUKTI** (build auditor 47.610.267 B / `0b03c797…`; APK tidak deterministik) |
| "Form manual tetap lengkap" | **SEBAGIAN** — field wajib ada, catatan opsional FR-26 tidak ada (P2-2) |
| "Strings/DI pensiun bersih" | **SEBAGIAN** — DI/nav/API bersih; 2 string + 1 rule anggaran mati tertinggal (P3-1) |
| FR-32 direction+bulan+back | **TERBUKTI** (kode + 7 tes terkait); bank scope belum (P2-1, diakui worker) |
| FR-79/80 lifetime sheet + timestamp terpisah | **TERBUKTI** (18 tes `UpdateStatusServerSyncTest` + Compose sheet) |
| NFR-15 picker setahun | **TERBUKTI** (10 tes `RetentionWindowTest`, 2 `OverviewMonthWindowTest`) |
| Migrasi non-destruktif + tes v1 nyata | **TERBUKTI** |
| UAT emulator/HP lane NOT RUN | Konsisten dengan dokumen; auditor tidak menjalankan UAT perangkat (read-only) |

## Catatan Keterbatasan

- Audit read-only: tidak install APK, tidak menjalankan emulator/HP, tidak menguji TalkBack manual, tidak menguji Gmail live/Azure.
- Backend tidak diubah; verifikasi backend terbatas pada pembacaan kode dan route yang sudah diverifikasi 404 di ronde sebelumnya.
- APK berbeda byte dari klaim worker sehingga tidak dapat dipakai untuk membandingkan isi biner; verifikasi perilaku dilakukan lewat kode + unit test, bukan decompile.
