# Gate akhir SRS v0.7 — cakupan produk, pensiun modul, dan regresi (24 November 2026)

Status keseluruhan: **PASS untuk perilaku yang diuji pada pasangan build di bawah**; gate
pemilik (Run literal, onboarding/consent Gmail, email baru saat app tertutup, Rp0/billing, drill
hapus-restore data nyata, merge `main`/deploy/revisi SRS) tetap **NOT RUN / OPEN** dan tidak
diklaim PASS. Bukti rinci di [FINAL_UAT_SRS_V07_2026-11-24.md](../evidence/FINAL_UAT_SRS_V07_2026-11-24.md).

## 1. Pasangan build yang diverifikasi

| Komponen | Nilai |
|---|---|
| Android | branch `feat/c2-android`, HEAD **`b885555`** (= origin; `a629c41` refresh daftar setelah simpan manual + `b885555` penutupan P2 audit final: copy privasi catatan dan identitas retry legacy) |
| Backend | branch `integration/2026-11-24`, HEAD **`dd5bc88`** (== `origin`), 21 migrasi tidak berubah |
| `main` | Android `main` **tidak disentuh**; backend `main` **tidak disentuh**; tanpa force-push |
| Varian Android | `debug` (`com.pam.recap.debug`), konfigurasi `app`, perangkat fisik `105397ba0507` (portrait 1080×2400, font 100%) |
| APK UAT | 48.819.325 byte sha256 `e7a1f4af…` (build `a629c41`, dipakai UAT awal) dan **47.628.443 byte sha256 `ef34261b…`** (build `b885555`, dipasang ulang via `adb install -r`); byte tidak deterministik — fingerprint build |
| Backend runtime | `~/.config/pam/uat-runtime/pam-api-loopback` rebuild dari `dd5bc88`, `127.0.0.1:18090` + `adb reverse`, `/readyz` ready; DB `~/.config/pam/pam-gmail-uat.db` (akun UAT sintetis) |
| Profil provider UAT | Gmail nyata (`google`); kategori LLM nonaktif (`CATEGORY_LLM_ENABLED=false` → enqueue 503 by config); Analisis `LLM_PROVIDER=fake` (bukan Azure berbayar) |

Gate teknis pada pasangan ini:

| Gate | Hasil |
|---|---|
| Android `:app:testDebugUnitTest` | **613 tes / 88 suite / 0 gagal** (fresh `--rerun-tasks` pada `b885555`; 608 tes pada `a629c41` + 5 tes retry identity) |
| Android `:app:lintDebug` | **0 error / 117 warning / 1 hint** |
| Android `:app:assembleDebug` | **BUILD SUCCESSFUL** (`b885555`); APK debug 47.628.443 byte, sha256 `ef34261b…` (fingerprint build; byte tidak deterministik antar-build) |
| Backend `./scripts/go.sh vet ./...` | **PASS** (`dd5bc88`, pm_go_vet.log) |
| Backend `./scripts/go.sh build ./...` | **PASS** |
| Backend `./scripts/go.sh test ./... -count=1` | **PASS**; 11 paket `ok`, termasuk `internal/httpapi` (23,5 dtk), `internal/gmail` (3,7 dtk), `internal/store`, `internal/retention`, `internal/analysis` |
| Go format (repo source) | Bersih; `gofmt -l` hanya menemukan file pustaka pihak ketiga di cache `.toolchain/go-mod` (bukan source repo) |

## 2. Requirement → hasil → bukti (SRS v0.7)

| Requirement / keputusan | Hasil | Bukti |
|---|---|---|
| FR-26 entri manual masih tersedia (pemasukan/pengeluaran; nominal, tanggal, kategori, keterangan) | **PASS** — form "ENTRI MANUAL" lengkap; simpan `POST /v1/transactions` 201; baris berbadge **Manual** di daftar | UAT §2.1; `assets/final_v07/ui_fix1.xml`, `ui_note_saved.xml` |
| Catatan opsional pada entri manual | **PASS** — field "Catatan (opsional)", penghitung 0/500, teks tersimpan dan terbaca (`note=catatan-final-v07`), ciphertext 57 byte di DB | UAT §2.1; API GET sesudah simpan |
| NFR-15 batas tanggal manual (tidak bisa melewati bulan berjalan/masa depan; tidak maju ke bulan berikutnya) | **PASS** — label tanggal `24 Nov 2026`, chevron Sebelumnya/Berikutnya dengan `ManualDateRange` (unit test 6 kasus) dan banner `dateOutOfWindow`; batas bulan berjalan diuji | `b56e28f`; `ManualDateRangeTest`, `ManualEntryViewModelTest` (8 tes) |
| Daftar Transaksi langsung memuat baris baru setelah simpan manual | **PASS** — fix `a629c41`; daftar menampilkan `Selasa, 24 Nov / UjiNoteFinal / Manual / Rp3.000` tanpa pindah bulan; refresh tetap satu kali, filter/bulan/pencarian tidak berubah | UAT §2.1; `TransactionsManualRefreshTest` (5 tes), `ManualSaveResultNavigationTest` (1 tes) |
| Impor Gmail tetap berjalan (polling, dedupe, retensi setahun) | **PASS (fungsional UAT)** — status `13 dari 13`, 914 baris unik setelah login ulang, tidak ada duplikat; G25 live pilot tetap PASS dari putaran sebelumnya | `docs/evidence/GMAIL_LIVE_IMPORT_2026-11-24.md`, `GMAIL_POLLING_UAT_PHONE_2026-11-24.md` |
| Ringkasan tetap berjalan | **PASS** — MASUK/KELUAR/arus kas bersih + komposisi; KELUAR berubah saat entri manual ditambah dan kembali setelah dihapus | UAT §2.1–2.3 |
| Koreksi kategori tetap berjalan | **PASS** — `PATCH /v1/transactions/{id}` mengubah kategori/origin; `""` mengosongkan (arah `null` terdokumentasi sebagai observasi, bukan regresi) | lane evidence + audit v0.7 Android |
| Analisis AI opsional dengan persetujuan terpisah tetap berjalan dan terbaca | **PASS** — `POST /v1/analyses` 200 setelah perbaikan allowlist coverage (`dd5bc88`); layar menampilkan ANALISIS TERSIMPAN, Observasi, BATASAN, "Buat ulang analisis"; tab Saran tidak berisi saran anggaran | `docs/API_CONTRACT.md` §13; `internal/analysis/snapshot_test.go`, `internal/httpapi/analysis_coverage_test.go`; UAT §2.2 |
| Pensiun modul anggaran (rencana, batas per kategori, saran AI→anggaran) | **PASS** — tidak ada entri UI; `/v1/budget-plans*`, `/v1/budgets` GET/POST → **404** semua method dengan token valid; tabel/baris/migrasi **tetap** | UAT §3; `retired_modules_test.go`; matriks 404 runtime `dd5bc88` |
| Pensiun modul lampiran transaksi (foto/PDF/kamera/OCR) | **PASS** — form manual selesai tanpa file; `/v1/attachments*` → **404**; file/baris lampiran lama tidak dihapus | UAT §3 |
| Pensiun administrasi pengguna (admin, undangan, kelola akun lain, ubah peran) | **PASS** — tidak ada layar/entri admin/invitation; `/v1/admin/accounts|invitations|users` → **404**; peran aplikasi `USER`; akun sendiri, sesi, dan tautan Google tetap ada | UAT §3; `retired_modules_test.go` |
| Kompatibilitas data & riwayat migrasi (tanpa hapus tabel/baris/file/migrasi) | **PASS** — 21 migrasi historis utuh dan non-destruktif; Room v1→v2 mempertahankan baris anggaran lama (agregat tetap) walau modul pensiun; drill fixture DB terpisah 60/60 (termasuk restore) | `1acc835`; `docs/evidence/UAT_AUDIT_2026-11-24.md`; `G14_DELETION_RESTORE_DRILL_2026-11-24.md` |
| OI-28 registrasi akun (pertahankan login akun tertaut & penautan akun PAM lama; jangan aktifkan pendaftaran publik/auto-create dari Google/email merge; dokumentasikan dampak pensiun undangan admin) | **PARTIAL (keputusan owner)** — login Google + sesi PAM + tautan akun lama tetap; backend **tidak** punya registrasi publik tanpa undangan (`POST /v1/accounts` butuh `inviteCode`); UI masih menampilkan "Buat akun" untuk alur undangan lama, sementara pembuatan undangan admin sudah pensiun → owner perlu memutuskan hapus alur undangan atau sediakan mekanisme undangan resmi | CR-008; `server.go`; UAT §2.5 |
| OI-27 pemetaan akademik (2 CRUD + 2 entitas field dinamis; jangan mengarang fitur atau klaim D.5/D.6/D.9 tanpa pedoman asli + keputusan owner) | **OPEN (owner)** — CR-008 mencatat pemetaan ulang D.2/D.5/D.6/D.9 butuh pedoman asli dan keputusan owner; tidak ada klaim PASS akademik baru | `docs/changes/CR_008_DRAFT_PRODUCT_SCOPE.md` |
| FR-32 Ringkasan → Transaksi (kartu Masuk/Keluar membuka transaksi pembentuknya) | **PASS** — kartu KELUAR membuka daftar hanya arah Keluar, periode November 2026; back kembali ke Ringkasan; satu tujuan Transaksi; dimensi `bank` tetap **terblokir keputusan owner** (CR-008) | UAT §2.3; `assets/final_v07/ui_fr32c.xml` |
| FR-79/FR-80 Status pembaruan + `Sinkronkan sekarang` | **PASS** — sheet berisi status, progress `13/13`, percobaan terakhir vs terakhir berhasil terpisah, sinkron berikutnya, tombol Sinkronkan; membuka sheet hanya GET status (tidak memicu sync); menutup sheet tidak menghentikan proses server | UAT §2.4; `assets/final_v07/ui_sheet_final3.xml` |
| Retensi rolling setahun WIB (termasuk bulan parsial tertua, leap day) | **PASS (backend)** — kebijakan `internal/retention/policy.go`, sweep REPORT_ONLY/DELETE, 21 hari uji lolos; klaim UI "November 2025 hanya tercakup sebagian"; `SweepRetention` diuji | `internal/retention` tests; audit backend v0.7 PASS |
| Klasifikasi kategori: satu job logis setelah backfill, batch maks 5 merchant unik/permintaan Azure, antrean 2 jam, batas cache/biaya, prioritas koreksi pengguna, pemetaan `caseId` | **PASS (backend)** — `MaxCandidates=5`, `CategoryDrainMaxProviderCalls=4`, sisa kembali PENDING, hasil terlambat dibuang; dibuktikan unit/integrasi provider fake | `internal/categoryllm`, `internal/httpapi` tests; audit backend v0.7 PASS |

## 3. Tabel gate pemilik G14/G24/G29/G36/G37

| Gate | Sub-butir | Status | Bukti terakhir / alasan |
|---|---|---|---|
| **G14** drill hapus/restore | Fixture DB terpisah | **PASS** | 60/60 pemeriksaan, termasuk restore; `G14_DELETION_RESTORE_DRILL_2026-11-24.md` |
| | Data pemilik nyata (salinan + izin + drill hapus) | **NOT RUN** | Butuh salinan DB pemilik dan izin eksplisit; tidak ada operasi hapus terhadap DB aktif |
| **G24** kesiapan rilis | Matriks requirement v0.7 + bukti | **PASS** (dokumen ini) | `FINAL_GATES_SRS_V07_2026-11-24.md`, UAT, audit |
| | Persetujuan owner atas CR-005/006/007/008 + SRS v0.7 | **NOT RUN** | Menunggu keputusan owner |
| | Merge `feat/c2-android`/`integration/2026-11-24` → `main`, deploy | **NOT RUN** | Butuh instruksi owner; `main` tidak disentuh |
| **G29/G37** Rp0 | Audit read-only GCP/billing | **NOT RUN / OPEN** | Tidak ada akses GCP/billing dari sesi ini; `GCP_READONLY_RP0_2026-11-24.md` |
| | Perubahan billing | **NOT RUN** | Hanya boleh atas instruksi owner; tidak ada perubahan |
| **G36** SSO di perangkat | Login/logout/relogin Google di build final | **PASS** (proven via ADB install, build `a629c41`) | UAT §2.5: `DELETE /v1/sessions` 204 → picker → `POST /v1/auth/google` 200 → `/v1/me` 200 → Ringkasan; 914 baris tetap unik |
| | Tombol **Run** literal Android Studio pada build final | **NOT RUN** | Instalasi `adb install -r`; konfigurasi `app`/varian `debug` sama, tetapi bukti Run literal belum ada |
| | Onboarding Gmail: **Lewati** dan **Tautkan Gmail** + consent pada akun uji | **NOT RUN** | Tidak dijalankan pada sesi ini; koneksi pilot tetap tertaut, akun uji tidak diputus |
| | Email Livin baru tersimpan saat aplikasi tertutup (polling, tepat 1 transaksi, tanpa tap Import) | **NOT RUN** | Tidak ada email Livin baru yang tiba secara alami pada jendela uji; polling tanpa kegagalan |
| | G29/G37 tetap gate terpisah | — | Lihat baris Rp0 di atas |

## 4. Audit independen, temuan, perbaikan, retest

| Putaran | Temuan | Perbaikan | Retest |
|---|---|---|---|
| Android v0.7 (`/tmp/AUDIT_V07_ANDROID.md`, disalin ke `docs/evidence/`) | P0=0, P1=0, **P2=3, P3=3** (provider-call cap, dokumentasi/tes, guard coverage EMPTY+manual, dll.) | `1d41f72` cap provider, `2e6fdea` dokumen+tes, `8eae86e` penutupan P2/P3 (walk UI + unit test) | **608 tes / 0 gagal**, lint 0 error; audit final putaran 2 (task-24) memverifikasi closure |
| Backend v0.7 (`/tmp/AUDIT_V07_BACKEND.md`) | **PASS** untuk pensiun modul, retensi, klasifikasi, dedupe | Perbaikan coverage EMPTY/FAILED `dd5bc88` | `internal/analysis` + `internal/httpapi` tests PASS; runtime `/v1/analyses` 200 |
| Audit final (P2 baru, `AUDIT_V07_FINAL_2026-11-24.md`) | P2-1 copy privasi catatan menyatakan "hanya di perangkat" padahal tersimpan server; P2-2 identitas retry legacy 5-field vs 6-field berisiko duplikat | `b885555`: copy id+en diperbaiki (termasuk `account_body_register`/`sign_in`); `ManualPendingActionStore.matchesDraft` membandingkan field draft dengan normalisasi note kosong | Gate fresh `b885555`: **613 tes/88 suite/0 gagal**, lint **0 error/117 warning/1 hint**; UAT perangkat copy baru + simpan manual diulang |
| UAT PM menemukan regresi daftar basi | Daftar Transaksi tidak memuat baris baru sampai pindah bulan | `a629c41` hand-off `SavedStateHandle` + refresh page-1 | UAT ulang di HP fisik: baris langsung muncul 2× (dengan/tanpa catatan) |
| UAT PM mencurigai tanggal hilang | Ternyata form manual dapat di-scroll; baris Tanggal/KATEGORI di bawah fold | Tidak ada perubahan (bukan cacat) | Gulir form menampilkan `Tanggal 24 Nov 2026` + chips kategori |

Laporan audit final independen putaran terakhir: `docs/evidence/AUDIT_V07_FINAL_2026-11-24.md` (P0=0, P1=0; P2=2 ditutup di `b885555`; P3 dokumen direkonsiliasi pada commit dokumen sesudahnya).

## 5. Git, artefak, dan kebersihan branch

- Android `feat/c2-android` HEAD `a629c41` == `origin/feat/c2-android`; backend `integration/2026-11-24` HEAD `dd5bc88` == `origin`.
- Branch worker lama (`work/*`, `feat/gmail-*`, `feat/retention-ui`, `fix/gmail-ui-audit`, dll.) sudah di-merge/di-cherry-pick dan **dihapus lokal+remote** pada putaran sebelumnya; saat ini tersisa `main`, `develop`, `feat/c2-android` (Android) dan `main`, `integration/2026-11-24` (backend). `main` tidak dihapus/diubah.
- Tidak ada force-push; tidak ada penghapusan migrasi/tabel/baris/file pengguna.
- Scratch `.pm_tmp/` dihapus sebelum penutupan.

## 6. Tindakan yang hanya boleh dilakukan pemilik

| Tindakan | Alasan | Bukti terakhir |
|---|---|---|
| Jalankan **Run** Android Studio pada build final (konfigurasi `app`, varian `debug`, HP fisik) untuk G36 Run literal | Gate meminta bukti Run literal, bukan ADB | UAT §2.5 (hanya ADB) |
| Onboarding Gmail: coba **Lewati** lalu **Tautkan Gmail** + consent di akun uji yang sesuai, tanpa memutus Gmail pilot | Consent Gmail harus lewat akun uji owner | koneksi pilot tetap; sub-gate NOT RUN |
| Kirim satu email Livin baru saat aplikasi tertutup, lalu verifikasi tepat 1 transaksi via polling tanpa tap Import (G36/G25) | Butuh email nyata; tidak boleh memalsukan data pilot | jendela uji tanpa email baru |
| Putuskan alur undangan/OI-28: hapus "Buat akun"/undangan lama atau sediakan mekanisme undangan resmi | Registrasi publik tidak boleh diaktifkan; undangan admin sudah pensiun | CR-008, UAT §3 |
| Putuskan FR-32 dimensi `bank` (aktifkan multi-bank atau tutup permanen) | Kontrak tidak punya dimensi bank; saat ini parameter diterima tapi tidak dipakai | CR-008 catatan FR-32 |
| Setujui CR-005/006/007/008 dan SRS v0.7; putuskan merge ke `main` + deploy (G24) | Perubahan SRS resmi dan merge/deploy butuh keputusan owner | dokumen ini §3 |
| Audit read-only GCP/billing Rp0 + konfirmasi target biaya (G29/G37); instruksikan perubahan billing bila perlu | Perubahan billing hanya atas instruksi owner | `GCP_READONLY_RP0_2026-11-24.md` |
| Drill hapus/restore pada salinan DB pemilik (G14 data nyata) | Butuh salinan DB + izin eksplisit; jangan terhadap DB aktif | drill fixture PASS 60/60 |
| Putuskan bentuk aktivitas file lokal jika D.9 tetap wajib untuk akademik (OI-27) | Pedoman asli belum ditemukan; jangan mengarang fitur | CR-008 |
