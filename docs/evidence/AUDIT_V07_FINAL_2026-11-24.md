# AUDIT FINAL INDEPENDEN — SRS v0.7 (CR-008)

- **Auditor:** auditor2 (independen, read-only)
- **Tanggal:** 24 November 2026, sesi audit ~23:0x–23:3x WIB
- **Android:** `pam-app`, branch `feat/c2-android`, HEAD **`a629c41fe62494d9a176714bf3667917058cc618`**
  (`origin/feat/c2-android` = HEAD; `main` = `origin/main` = `c10ca67e57b6e5fbe8e9c419adc870318e2958da`, tidak tersentuh)
- **Backend:** `0037-Backend-Financial`, branch `integration/2026-11-24`, HEAD **`dd5bc88c6d9c85970e700f7444b89449f19e01e2`** (= origin, clean)
- **Metode:** baca kode + tes + dokumen di working tree, jalankan gate build/test independen (tanpa fix/commit/checkout). Tidak me-restart backend/port 18090, tidak memakai HP fisik `(serial diredaksi)`, tidak menyentuh data owner/live.

## Ringkasan Verdict

| Area | Verdict | Catatan |
|---|---|---|
| Regresi `b56e28f..a629c41` + delta backend `dd5bc88` | **PASS (dengan 2 P2)** | Tidak ada regresi P0/P1; 6 tes baru hijau; refresh hanya page-1, terjaga bulan, tanpa loop. Dua temuan P2 baru (privasi copy catatan, identitas retry legacy). |
| Isolasi lintas akun/owner (review kode) | **PASS** | Semua handler memakai `info.OwnerID` dari sesi; tidak ada parameter owner override; tes isolasi owner ada. |
| Modul pensiun (API 404 semua method, UI mati, data/tabel tetap) | **PASS** | Tes backend `Retired` lulus; tidak ada rute/entri UI; migrasi 001/003 & rebuild 015 mempertahankan baris. |
| Bukti vs build (Android gate, lint, APK, backend suite) | **PASS / catatan** | Angka tes/lint direproduksi persis; APK tidak byte-reproducible (sudah didokumentasikan sebagai fingerprint build). |
| Closure P2/P3 audit sebelumnya (`/tmp/AUDIT_V07_ANDROID.md`) | **PASS dengan 1 deferral** | P2-2/P2-3/P3-1/P3-2 FIXED; P2-1 (bank) resmi didefer ke keputusan produk; P3-3 sebagian (dokumen kanonik masih basi). |
| Kejujuran dokumen gate (G14/G24/G29/G36/G37, SSO) | **PASS untuk status** | G14/G24 NOT RUN, G29/G37 NOT RUN/OPEN, G36 PARTIAL — tidak dilonggarkan. Namun identitas build di dokumen gate kanonik basi (P3). |
| **Overall** | **PASS teknis; belum "final rilis"** | P0=0, P1=0, **P2=2, P3=3**; G24/G14/G29/G36-run/37 tetap milik owner. |

## 1. Regresi `b56e28f..a629c41` dan delta backend

Diff Android `b56e28f..a629c41` = 10 file (+337/−6): 6 file produksi/tes + README + `docs/PROGRESS_DAILY.md` + CR-008. Tidak ada perubahan Room/skema, manifest, API, atau konfigurasi build.

- `a629c41` alur simpan manual:
  - `ManualEntryScreen.kt` → `onSaved: (savedMonthId: String?) -> Unit`;
  - `ManualEntryViewModel.kt:175-181` menghitung `monthId` baris tersimpan, mengisi `savedMonthId`, lalu publish;
  - `PamNavHost.kt` menyimpan hasil satu-kali di `SavedStateHandle` (`manualSaveMonthId`) dan menghapusnya setelah dipakai;
  - `TransactionsScreen.kt:77-82` `LaunchedEffect(savedMonthId)` → `refreshAfterSave(monthId)` + `onSavedMonthHandled()`;
  - `TransactionsViewModel.kt:471-474` `refreshAfterSave` = no-op bila bulan aktif ≠ bulan hasil simpan, lalu `refreshTick += 1` (memuat page-1; halaman/filter lain tidak diubah).
  - Risiko duplikasi/loop: **tidak ada**. Item page-1 diganti (bukan append), key hasil dihapus setelah sekali pakai, dan bulan/owner lain no-op. 6 tes baru mengunci: 5 di `TransactionsManualRefreshTest.kt` (event simpan, hasil navigasi, bulan lain no-op, bulan simpan lain no-op, owner lain no-op) + 1 `ManualSaveResultNavigationTest.kt`.
- `b56e28f` catatan/tanggal manual: sudah diuji; lihat temuan baru P2 di §6.
- Backend `dd5bc88`: hanya `docs/API_CONTRACT.md` §13 + `internal/analysis/snapshot.go` (`ParseClientPayload` menerima enum coverage `COMPLETE|PARTIAL|EMPTY|FAILED|UNKNOWN`, nilai lain 400) + tes. Tidak ada perubahan skema/migrasi. 21 migrasi bernomor (001–021; 22 file SQL termasuk `003.triggers.sql`), tidak ada migrasi baru pada HEAD.
- Room v1→v2 non-destruktif tidak berubah di range ini; `fallbackToDestructiveMigration` tidak dipakai (`DatabaseModule.kt:46`, `Migrations.kt:19`).

## 2. Isolasi lintas akun/owner (review kode)

- `internal/httpapi/handlers_data.go` seluruh handler memakai `info.OwnerID` hasil auth sesi (baris 42, 56, 100, 135, 148, 165, 177, 269, 316, 335, 357, 365, 380, 397, 448, 452, 470, 498, 598, 615, 651); `handlers_analysis.go` 65–186; `gmailOwner` = `authFrom(r).OwnerID`.
- `internal/analysis/snapshot.go:76,101` menerima `ownerID` eksplisit dari sesi; store selalu `WHERE owner_id = ?` (`internal/store/summary.go:131,144,176,225`; `internal/store/analysis.go:57,69,77,130`).
- Tidak ditemukan parameter query/body `ownerId`/`owner_id` yang bisa menimpa sesi (grep).
- Tes isolasi owner ada dan hijau: `internal/httpapi/contract_slice1_test.go:213,326,407-414`, `search_contract_test.go:13`, `category_pipeline_test.go:355,465-468`, `gmail_category_test.go:173,192`.
- **PASS.** Tidak menguji data owner asli; murni kode + tes.

## 3. Modul pensiun CR-008

- **API 404 semua method:** `internal/httpapi/retired_modules_test.go` `TestRetiredModulesAreNotRouted` mengecek GET/POST/PUT/PATCH/DELETE untuk `/v1/admin/invitations`, `/v1/admin/accounts`, `/v1/admin/accounts/owner-legacy`, `/v1/admin/audit`, `/v1/attachments`, `/v1/attachments/att_legacy`, `/v1/budget-plans`, `/v1/budget-plans/2026-11` — 404 baik dengan token valid maupun tanpa token (membuktikan rute tidak terdaftar, bukan 401). **Lulus** pada run independen.
- **Data/tabel tetap:** `TestRetiredModuleDataIsRetained` menyisipkan baris ke `budget_plans`, `admin_invitations`, `admin_audit`, `attachments`, memastikan API 404, lalu baris tetap ada. **Lulus**.
- Migrasi: tabel pensiun dibuat di `001`/`003`; `015.sql` memang `DROP TABLE attachments` tetapi langsung `INSERT ... attachments_v2` (mempertahankan baris dengan hash/path placeholder) lalu rename — **bukan penghapusan data**. Tidak ada `DROP` tabel pensiun setelah retire.
- Dokumen backend `docs/API_CONTRACT.md` §9 (baris 390–395) dan §12 (baris 472) mencatat API pensiun 404 tetapi tabel/baris legacy dipertahankan.
- **UI Android tidak punya rute/entri:** `PamDestinations.kt` hanya 8 destinasi produk; `AndroidManifest.xml` hanya `MainActivity` + deep link Gmail; grep `Admin|Attachment|BudgetPlan` di `app/src/main/java` tidak menemukan entri UI (hanya komentar parser).
- **PASS.**

## 4. Bukti vs build (independen, bukan klaim)

Gate Android dijalankan ulang di HEAD `a629c41` dengan lock build:

```
JAVA_HOME=pam-app/.toolchain/jdk-21.0.13.1+2/Contents/Home
/tmp/pam_build_lock.sh ./scripts/build.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks
→ BUILD SUCCESSFUL in 2m 46s, 65 tasks executed, EXIT 0
```

| Artefak | Klaim dokumen (a629c41) | Hasil independen | Verdict |
|---|---|---|---|
| `testDebugUnitTest` | 608 tes / 87 suite / 0 gagal | **608 / 87 / 0 gagal / 0 error / 0 skip** | Cocok |
| `lintDebug` | 0 error / 117 warning / 1 hint | **0 error / 117 warning / 1 hint** | Cocok |
| `assembleDebug` | APK 48.819.325 B sha256 `e7a1f4af…` | APK **47.628.107 B** sha256 **`dcac3b78…`** | Tidak byte-identik (non-determinisme build; dokumen memang menyebut "fingerprint build") |

Catatan: sebelum rebuild, artefak APK di workspace memang **48.819.325 B = `e7a1f4af…`**, cocok dengan klaim; rebuild `--rerun-tasks` menghasilkan byte berbeda (dex/zip ordering). Jadi klaim APK bukan palsu, tetapi **tidak dapat diverifikasi ulang secara byte** oleh auditor ketiga — hanya tree/angka tes/lint yang reproducible.

Backend (read-only):

```
PAM_GOROOT=pam-app/.toolchain/go ./scripts/go.sh test ./... -count=1
→ exit 0; 11 paket `ok` (analysis, auth, categoryllm, config, crypto, finance, gmail, httpapi 24.9s, llm, retention, store)
PAM_GOROOT=... ./scripts/go.sh test ./internal/httpapi -run 'Retired' -count=1 -v → PASS
```

Log: `/tmp/v07_final_android_gate.log`, `/tmp/v07_final_backend_tests.log`. Backend tidak di-restart; DB live tidak disentuh.

## 5. Status closure temuan audit sebelumnya (`/tmp/AUDIT_V07_ANDROID.md`, P2=3/P3=3)

| Temuan lama | Status | Bukti |
|---|---|---|
| P2-1 FR-32 filter bank tidak diimplementasikan | **TIDAK (didefer resmi)** | CR-008 §"Catatan FR-32" (baris ~25–31): bank terblokir keputusan produk, tidak ada dimensi bank di model/kontrak; bukan cacat implementasi. Tetap OPEN sebagai keputusan produk. |
| P2-2 catatan opsional FR-26 tidak ada di form manual | **FIXED** | `b56e28f`: field catatan + counter, cap 500, ikut identitas retry; tes `ManualEntryViewModelTest.kt` (note tersimpan/opsional/cap) + `ManualPendingActionStoreTest`. |
| P2-3 tanggal manual tidak dibatasi NFR-15 | **FIXED (client-side)** | `ManualDateRange.kt` (min = hari ini − 1 tahun, max = hari ini; clamp leap-day), panah berhenti, simpan nonaktif + copy id/en; tes `ManualDateRangeTest`/`ManualEntryViewModelTest`. Server tetap toleran tanggal masa depan (kontrak lama) — didokumentasikan, bukan regresi. |
| P3-1 sisa error/string anggaran mati | **FIXED** | `PLAN_VALUE_NEGATIVE`/`error_plan_value_negative` tidak ada lagi (grep kosong). |
| P3-2 copy "Hubungi admin PAM" pasca-pensiunan admin | **FIXED** | `values/strings.xml:433` & `values-en/strings.xml:410` = "Akun ini tidak aktif… hubungi dukungan PAM". |
| P3-3 klaim APK byte-exact tidak reproducible + drift README | **PARTIAL** | Lane row README + `PROGRESS_DAILY.md` kini memakai fingerprint dan gate 608/87 final; tetapi blok snapshot README + FINAL_GATES kanonik masih basi (lihat P3 di §6). |

## 6. Temuan baru

### P0 (0) / P1 (0)

Tidak ada temuan P0/P1.

### P2-1 — Copy privasi catatan menyatakan "hanya di perangkat", padahal catatan dikirim & disimpan di backend

- Bukti UI: `app/src/main/res/values/strings.xml:128` ("Tetap di perangkat: … catatan pribadi …"), `:230`, `:266` ("Hanya tersimpan di perangkat"); `values-en/strings.xml:117,214,250` ("Stays on device… private notes", "Stored on this device only").
- Bukti kode: `TransactionUseCases.kt:82` mengirim `note` ke server (`server.createManual(..., note = ...)`); backend menerima `handlers_data.go:218` (`transactionCreate.Note`), menyimpannya terenkripsi sebagai field sensitif dan mengembalikannya (`handlers_data.go:420`); `store/transactions.go:23`.
- Bukti UAT final (working tree, belum commit) bahkan mencatat `GET /v1/transactions` memuat `note=catatan-final-v07` dan DB ciphertext (`docs/evidence/FINAL_UAT_SRS_V07_2026-11-24.md:33`) — jadi klaim "hanya di perangkat" salah.
- Dampak: pernyataan privasi/consent menyesatkan; user bisa menulis data sensitif dengan asumsi salah. Tidak ada kebocoran ke pihak ketiga (catatan tidak masuk payload analisis AI), jadi bukan P1.
- Rekomendasi: ubah copy menjadi akurat (mis. "Tersimpan terenkripsi di akun Anda; tidak dikirim ke analisis AI"), atau buat catatan benar-benar lokal. Perbaiki id & en sekaligus; tambahkan cek konsistensi copy di tes/screenshot agar tidak regresi.

### P2-2 — Identitas retry pending-action legacy tidak kompatibel → risiko baris manual ganda saat upgrade

- Versi sebelum `b56e28f` menyimpan identitas retry 5 field: `direction|amount|description|date|category` (`git show 1fc23ac:.../ManualEntryViewModel.kt:127-130`).
- Versi sekarang 6 field dengan `note` disisipkan: `direction|amount|description|note|date|category` (`ManualEntryViewModel.kt:175-181`).
- `ManualPendingActionStore.read` memang menerima payload 7 field lama dan mengisi `note=""` (`ManualPendingActionStore.kt:39-60`), tetapi identitas string yang tersimpan tetap format lama. Karena `save()` membandingkan `existing?.identity == identity` (baris 179-183), payload lama **tidak pernah cocok** dengan identitas baru (yang berisi `note` kosong) → dibuat UUID baru → idempotency key baru.
- Skenario dampak: pending action tersimpan pra-upgrade; POST sebenarnya sudah sukses di server tetapi respons hilang/crash; setelah upgrade user retry → key baru → **baris manual duplikat**.
- Tes yang ada hanya memverifikasi field payload lama terbaca (`ManualPendingActionStoreTest.kt:41-65`), bukan kontinuitas identitas end-to-end; komentar tes mengklaim "retry identity must survive the upgrade" padahal jalur simpan tidak memenuhi itu.
- Rekomendasi: saat payload 7 field, bandingkan juga dengan identitas legacy 5-field (atau simpan versi format pada payload); tambah tes: payload legacy + POST pertama gagal-setelah-sukses → retry memakai key lama. Risiko hanya pada jalur upgrade + respons hilang; dapat diterima sebagai known issue rilis hanya bila dicatat eksplisit.

### P3-1 — Dokumen gate kanonik masih memakai pasangan build lama (`51985f3`/`00bb515`, 678/88)

- `docs/delivery/FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md:54-58` (tabel "Pasangan build") dan baris 121–122 (G19/G20) masih menulis HEAD Android `51985f3`, backend `00bb515`, 678 tes/88 suite, lint 123 warning, APK 47.992.939/`5368c92f…` sebagai "kode final".
- `README.md:13-23` dan blok status `README.md:326+` juga masih snapshot `51985f3`/`00bb515` 678/88.
- `docs/delivery/OWNER_ACTION_CHECKLIST_2026-11-24.md:4` masih menyebut backend `00bb515` dan Android `8824fff`.
- Fakta final: Android `a629c41` (608/87, lint 0/117/1, APK fingerprint baru), backend `dd5bc88` (11 paket tes PASS). Status gate sendiri tidak dilonggarkan (G14/G24 NOT RUN, G29/G37 NOT RUN/OPEN, G36 PARTIAL), tetapi dokumen bernama "final gate" tidak mengidentifikasi build final → risiko lampiran SRS mengutip artefak lama.
- Rekomendasi: perbarui tabel pasangan build + G19/G20 + blok snapshot README/OWNER ke `a629c41`/`dd5bc88` dan angka fresh di laporan ini; pertahankan status owner apa adanya.

### P3-2 — Bukti final belum masuk git (untracked) dan bertentangan dengan dokumen tracked yang masih "NOT RUN"

- Saat audit, working tree Android berisi file **untracked** (bukan bagian HEAD): `docs/evidence/FINAL_UAT_SRS_V07_2026-11-24.md`, `AUDIT_V07_ANDROID_2026-11-24.md`, `AUDIT_V07_BACKEND_2026-11-24.md`, `G14_DELETION_RESTORE_DRILL_2026-11-24.md`, `GCP_READONLY_RP0_2026-11-24.md`, `docs/delivery/G24_READINESS_DRAFT_2026-11-24.md`, `docs/evidence/assets/final_v07/` (+ `.pm_tmp/` screenshot).
- Dokumen tracked (`README.md:97`, `docs/PROGRESS_DAILY.md:463-467`) masih menyatakan verifikasi UI simpan→daftar **NOT RUN**, sedangkan FINAL_UAT untracked mengklaim PASS di HP fisik. Keduanya bisa benar pada waktunya masing-masing, tetapi deliverable HEAD tidak memuat bukti PASS itu.
- Jika bukti ini dipakai untuk acceptance/SRS, **commit dulu** dan rekonsiliasi README/PROGRESS; jika tidak, hapus/beri label draf agar tidak ada dua kebenaran.
- Catatan: file-file ini tidak dibuat/diubah oleh auditor; auditor tetap read-only.

### P3-3 — Residu dokumen modul pensiun di kontrak backend

- `docs/API_CONTRACT.md:439` (§14 batas) masih mencantumkan "Lampiran | 10 MB, 10/transaksi" sebagai batas aktif, padahal API lampiran pensiun (§9 baris 390–395, §12 baris 472). Kosmetik, tidak menyesatkan implementasi (tidak ada rute), tetapi sebaiknya diberi label legacy/CR-008.

## 7. Kejujuran dokumen gate & bukti SSO

Status pada dokumen kanonik **tidak dilonggarkan** (diverifikasi baris per baris):

| Gate | Status dokumen | Verdict audit |
|---|---|---|
| G14 hapus/restore data nyata | **NOT RUN (owner)** | Jujur; drill fixture sintetis (untracked) juga eksplisit menyebut bukan data owner. |
| G24 merge/deploy/SRS resmi | **NOT RUN (owner)** | Jujur; draf kesiapan (untracked) berstatus NOT RUN. |
| G29/G37 GCP Rp0 | **NOT RUN / OPEN (owner)** | Jujur; dokumen GCP read-only menyatakan tidak ada akses billing dan menolak menutup gate. |
| G36 SSO HP + Run literal | **PARTIAL (owner)** | Login Google live 21:49–21:50 terbukti; Run literal/onboarding/email saat app tertutup diakui NOT RUN. Tidak ada klaim PASS. |
| G37 gate terpisah | **NOT RUN / OPEN** | Jujur. |

Tidak ditemukan klaim PASS tanpa bukti untuk gate owner di dokumen tracked. `FINAL_UAT` untracked juga konsisten menandai gate owner NOT RUN.

## 8. Residual risk & batasan audit

1. **APK tidak byte-reproducible** (sudah didokumentasikan): auditor hanya bisa memverifikasi hash artefak yang ada sebelum rebuild, bukan menghasilkan ulang byte identik.
2. **Tidak ada pengujian live/HP oleh auditor** (sesuai batas tugas): klaim UAT fisik tidak diverifikasi ulang; hanya dibaca. Bukti UAT final ada di working tree, belum commit (P3-2).
3. **P2-1 & P2-2** adalah temuan copy/privacy dan edge-case upgrade; tidak memblokir gate teknis tetapi sebaiknya diperbaiki sebelum rilis ke owner.
4. Dokumen gate kanonik basi (P3-1) dapat menyebabkan salah kutip artefak saat penyusunan SRS/lampiran.
5. Working tree Android tidak 100% bersih (untracked evidence/screenshot dan `.pm_tmp/`); tidak memengaruhi build, tetapi harus diputuskan sebelum merge.

## 9. Rekomendasi rilis

- **Merge/teknis:** **siap** — gate Android & backend hijau pada HEAD final (`a629c41` + `dd5bc88`), modul pensiun aman (API 404, UI mati, data/tabel dipertahankan), tidak ada regresi P0/P1, isolasi owner kuat, closure P2/P3 audit sebelumnya tercapai (kecuali deferral bank yang sah).
- **Rilis final / SRS resmi:** **belum** — G14/G24/G29/G36-run-literal/G37 tetap NOT RUN/PARTIAL/OPEN milik owner; jangan menaikkan status.
- **Sebelum rilis/merge, wajib/kuat disarankan:**
  1. Perbaiki copy privasi catatan (P2-1, id+en) — perubahan kecil, dampak kepercayaan.
  2. Selesaikan/mitigasi identitas retry legacy (P2-2) atau catat known issue upgrade.
  3. Commit bukti final (UAT/AUDIT_V07/G14/GCP/G24) dan rekonsiliasi README/PROGRESS/FINAL_GATES/OWNER_ACTION ke build final `a629c41`/`dd5bc88` (P3-1/P3-2).
  4. G29/G14/G24/G36/G37 tetap diserahkan ke pemilik dengan status apa adanya.

## Lampiran — perintah & log

- Android gate: `/tmp/v07_final_android_gate.log` (BUILD SUCCESSFUL, 65 tasks, exit 0).
- Backend: `/tmp/v07_final_backend_tests.log` (11 paket ok, exit 0).
- Test report: `app/build/test-results/testDebugUnitTest/*.xml` = 87 suite/608 tes/0 gagal/0 error/0 skip.
- Lint: `app/build/reports/lint-results-debug.txt` = 0 errors, 117 warnings, 1 hint.
- APK rebuild: `app/build/outputs/apk/debug/app-debug.apk` 47.628.107 B, sha256 `dcac3b78f7ef5084afcc97817ca0d846a0ae684d40875c5adedb1123dc268116` (fingerprint; bukan pengganti klaim artefak UAT).
