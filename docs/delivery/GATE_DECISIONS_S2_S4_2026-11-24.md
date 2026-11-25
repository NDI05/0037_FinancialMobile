# Keputusan acceptance gate S2–S4 — PM pengganti, 24 November 2026

Dokumen ini adalah lembar keputusan PM untuk mandat "setiap gate S2–S4 punya keputusan
acceptance dan bukti yang bisa ditelusuri". Keputusan bersifat **kondisional di titik
serah-terima ke pemilik**; angka dan tautan diambil dari bukti yang sudah dijalankan, bukan
dari daftar fitur. Aturan bukti: sebelum/sesudah, penyamaran data pemilik, dan status
PASS / PARTIAL / FAIL / NOT RUN per skenario.

- HEAD deliverable: `968ad79` (branch `feat/c2-android`), remote `origin/feat/c2-android`
  sudah diverifikasi menunjuk commit yang sama; `main` di `c10ca67` tidak disentuh.
- Android fresh: `:app:testDebugUnitTest` **536 tes / 74 kelas / 0 gagal**, varian
  `assessment` **546 / 75 / 0 gagal**, `:app:lintDebug` **0 error / 112 warning**.
- Go fresh (tidak berubah ronde ini): `go test ./... -count=1` **8/8 paket, 127 tes
  top-level**, `go vet` bersih.
- Matriks per aksi: [ACCEPTANCE_C01_C25.md](ACCEPTANCE_C01_C25.md). Dokumen ini merangkum
  per gate, bukan menggantikan matriks.

## Tabel keputusan

| Sprint/Gate | Perubahan | Bukti | Status | Sisa |
|---|---|---|---|---|
| **S2 — Android↔Go inti (P0 24 Nov)** | Satu sumber bulan untuk Transaksi + tab stateless dan kembali ke Ringkasan (`0030a43`); parser Livin memprioritaskan `Penerima` (`02ee6df`); identitas email server-derived + migrasi 016 (`b5ee7ae`); repair label sekali-jalan fail-closed (`276b7e1`, `6cc0f2a`, `3b8a038`); redaksi PII (`0436fe2`) | [UAT_PM_OWNER_DEVICE_2026-11-24.md](../evidence/UAT_PM_OWNER_DEVICE_2026-11-24.md); [UAT_PM_QA_2026-11-24.md](../evidence/UAT_PM_QA_2026-11-24.md) §2.5; bukti API owner: 49 baris Nov tanpa duplikat, label merchant nama usaha tanpa PAN, 8 baris terklasifikasi model (6 Makanan/1 Belanja/1 Transportasi), biaya aktual `$0.00101235` | **PARTIAL (kondisional)** | Acceptance varian final di perangkat pemilik lewat **Run Android Studio** belum ada; bukti drill-down-after dengan data belum ada; klaim dedupe re-import via `sourceRef` dipersempit (klien belum mengirim `sourceRef` di jalur impor) |
| **S2.D — "Buat Analisis" lewat Azure dari UI** | Guard cakupan `EMPTY` diperbaiki; runtime Azure `AVAILABLE/CURRENT` | [UAT_PM_UI_2026-11-24.md](../evidence/UAT_PM_UI_2026-11-24.md) (PASS, UI "ANALISIS TERSIMPAN"); request owner `POST /v1/analyses` 200 | **PASS runtime** (UI pemilik) | Label penyedia di UI tetap generik — dicatat sebagai gap kosmetik, bukan kegagalan gate |
| **S3 — C01–C15 transaksi/kategori/anggaran** | Remote transaksi, kategori, anggaran; guard last-admin/kategori; update batas + konflik revisi | Matriks [ACCEPTANCE_C01_C25.md](ACCEPTANCE_C01_C25.md) baris C01–C15; [UAT_PM_QA_2026-11-24.md](../evidence/UAT_PM_QA_2026-11-24.md) §2.1/§2.6; C13 UI update + banner konflik (`be5b144`) | **PASS API/unit + PASS UI untuk aksi ber-UI yang diuji** | C02/C06–C08/C10/C12/C15 tidak dijalankan ulang 24 Nov (status 23 Nov dipertahankan); keputusan acceptance final tetap di pemilik |
| **S4 — C16–C25 lampiran/admin/assessment/aksesibilitas** | Lampiran metadata+revisi, admin pengguna, assessment varian, audit semantik aksesibilitas; perbaikan UI: konflik/retry lampiran, pencarian admin state lokal | Matriks C16–C25; [UAT_PM_QA_2026-11-24.md](../evidence/UAT_PM_QA_2026-11-24.md) §2.2–§2.4, §2.7 `ui_split/36`–`38`, §2.8 `ui_split/40`–`42`; [UAT_ASSESSMENT_2026-11-24.md](../evidence/UAT_ASSESSMENT_2026-11-24.md); [UAT_ACCESSIBILITY_2026-11-24.md](../evidence/UAT_ACCESSIBILITY_2026-11-24.md) | **PARTIAL** | C20 konteks/owner-scope di perangkat belum dijalankan; guard last-admin C24 di perangkat sengaja tidak dipicu (risiko memutus sesi bukti — API/DB sudah); cursor C25 lintas halaman hanya API/unit karena daftar UAT < page size; aksesibilitas manual (TalkBack/font scale/switch access) NOT RUN |
| **S5 — di luar gate ronde ini** | — | — | **NOT RUN / terbuka** | Merge/deploy, hosting + backup-restore, benchmark berlabel, mailbox produksi, cakupan email malformed |

## Audit penutupan per sprint

- **S2.** Semua commit P0 sudah di remote; bukti runtime ada di perangkat pemilik
  (sheet 0 request, satu tap = satu percobaan sync, auto-sync 1 job, kategori tampil,
  detail Merchant nama usaha) dan di emulator (nav P0). Yang tidak bisa ditutup PM tanpa
  pemilik: menjalankan varian final dari Android Studio dan drill-down-after dengan data.
  Karena itu S2 tetap **PARTIAL**, bukan PASS.
- **S3.** C13 (satu-satunya baris S3 yang masih PARTIAL saat checkpoint) sudah ditutup
  API+UI: update batas revisi 0→1, banner konflik revisi basi, pemulihan lewat *Muat data
  terbaru*. Baris S3 lain berstatus PASS dengan bukti 23/24 Nov dan tidak diklaim sebagai
  bukti baru.
- **S4.** Gap lampiran (konflik PATCH 409 + banner, retry DELETE 409 → reload → 204) dan
  gap admin (pencarian live menyaring 9→1 tanpa *Muat ulang*, filter status SUSPENDED 9→1,
  ubah status SUSPENDED lewat dialog) sudah dijalankan di perangkat dan diarsipkan. Temuan
  QA "pencarian admin tidak menyaring" **tereproduksi** sebagai teks teracak saat pemuatan
  dan diperbaiki (`4860808`). Gap yang tersisa terdaftar di tabel dan semuanya berisiko
  rendah atau tidak dapat dijalankan tanpa memutus sesi bukti.

## Bukti lingkungan (ringkas)

- Emulator `emulator-5556` (AVD project-local `pamui_api36`), backend UAT `127.0.0.1:18090`
  mode `production`, transport `adb reverse`; akun sintetis `wqa.*` dan `wqa.admin.1124`.
- Perangkat pemilik `(serial diredaksi)` (APK build `897e751`, terpasang 11:51) dipakai untuk UAT
  pemilik; data pemilik tidak pernah masuk repo (disamarkan/merah).
- Artefak QA: [assets/pm_qa_2026-11-24](../evidence/assets/pm_qa_2026-11-24/), manifest
  `SHA256SUMS`. Artefak audit: [UAT_AUDIT_2026-11-24.md](../evidence/UAT_AUDIT_2026-11-24.md).
- Repo bersih: hanya satu worktree (`pam-app`), tanpa worktree/branch kerja yatim; commit
  dibuat hanya oleh Lead; `main` tetap di `c10ca67`.

## Keputusan akhir (untuk pemilik)

1. **S3: terima** sebagai PASS API/unit + PASS UI untuk aksi ber-UI yang diuji, dengan
   daftar baris yang tidak dijalankan ulang dinyatakan apa adanya.
2. **S2: terima bersyarat** — sah sampai pemilik menjalankan varian final (Run Android
   Studio) dan mengonfirmasi drill-down-after; setelah itu S2 dapat dikunci PASS.
3. **S4: terima bersyarat** — sah dengan sisa bukti UI (C20 konteks, C24 perangkat, cursor
   C25) yang tidak mengubah kontrak API/keamanan; tidak ada temuan yang memaksa FAIL.
4. **S5: belum masuk acceptance** ronde ini dan harus dikerjakan sebelum rilis produksi.
