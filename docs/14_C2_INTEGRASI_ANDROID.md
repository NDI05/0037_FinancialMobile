# C2 Android — status integrasi server

Status: **irisan C2 berjalan, bukan C2 penuh**, 22 November 2026.

Branch: `feat/c2-android` (dibuat dari checkpoint C1 `3132ebb`).
Tujuan C2 adalah memindahkan sumber data v0.4 Android (refresh, import, transaksi,
ringkasan, anggaran, analisis, deletion) ke server. Dokumen ini memisahkan yang
sudah dibuktikan dari yang masih lokal/Room agar tidak diklaim selesai.

## Sudah berjalan

| Bagian | Bukti |
|---|---|
| Session access+refresh | `BackendSessionRepositoryImpl` menyimpan access, refresh, expiry, server owner/role; Keystore wrapping tetap |
| Single-flight refresh | Mutex + re-check token; dua caller paralel berbagi satu `/v1/sessions/refresh` |
| Rotasi atomik | Refresh+access ditulis bersama; generation counter membuang respons terlambat setelah logout |
| Refresh gagal | 401/403 membersihkan sesi; tidak ada retry loop; login/refresh tidak melalui client terautentikasi |
| REST client | `PamApiClient` dengan timeout/cancellation, error envelope, 401→renew→retry satu kali |
| DTO | summary/kategori, transaksi + page/cursor, manual create + Idempotency-Key, budget, analysis freshness, deletion receipt |
| Deletion server | UI meminta password akun + konfirmasi; `RequestDeletionUseCase` mengirim server request dan menerima SLA/receipt |

Tes Android baru: `BackendSessionRepositoryTest` (16), `PamApiClientTest` (6) dan
`RequestDeletionUseCaseTest` (3), `DeletionReceiptStoreTest` (1) dan `ServerFinanceRepositoryImplTest` (3). Total suite saat dokumen ini ditulis: **248 tes JVM**, 0 gagal.

## Perbaikan temuan C2-F1–F4

| Temuan | Perbaikan | Tes |
|---|---|---|
| F1 deletion sukses palsu | backend failure → Failure, tanpa local wipe/sign-out; 202 → server id/state + receipt terproteksi sebelum cleanup; `deviceOnly` eksplisit; password PAM menggantikan Gmail reauth | `RequestDeletionUseCaseTest` |
| F2 late login | generation login dicatat sebelum jaringan; response setelah clear/login baru ditolak | `a late login response cannot resurrect a cleared session` |
| F3 401 paralel | `renew` menerima rejected token; caller kedua memakai pasangan baru tanpa rotasi tambahan | `two rejections of the same token share one refresh rotation` |
| F4 receipt log | route/panic log disanitasi; test canary token | `TestReceiptTokenIsNeverWrittenToLogs` |

## Belum dipindahkan ke server

| Fitur v0.4 | Sumber saat ini | Sisa pekerjaan |
|---|---|---|
| Transaksi daftar/detail | **Server Go** | edit/delete belum remote; detail memberi status belum tersedia |
| Ringkasan | **Server Go** | priorMonth/recent dari server; tidak menghitung dari Room |
| Budget | Room lokal | DTO/limits ID/revision ke server dan konflik |
| Analisis | engine lokal/remote lama | dependency revision server, freshness STALE/HISTORICAL di UI |
| Import batch | Room + `BackendSyncClient` summary saja | upload kandidat/job/batch ke Go, resume dan finalisasi server |
| Kategori | Room lokal | ID kode server/custom category |
| Lampiran | lokal | tahap D (FR-59) |
| Admin | belum ada | tahap D (FR-65) |

C2A kini membuktikan manual create → list → detail → overview summary dari
server, termasuk restart API/aplikasi, clear app data, dua akun, dan unavailable.
Gate A menambahkan pemetaan custom/provenance, strict wire failure, refresh bus
owner+month, detail error propagation, dan pending-action durability. Import,
pagination, budget, analysis, edit/delete lanjutan dan admin/lampiran masih
tersisa; Room tetap untuk fitur yang belum dipindahkan.

## Emulator

C2A sudah dijalankan pada emulator dengan database server sintetis. Bukti
persistence/restart/clear-data/dua akun/unavailable ada di bagian Bukti C2A.
Import, budget, analysis, edit/delete remote dan admin/lampiran belum menjadi
bagian bukti UI ini.

## Batas

Cloud Turso, Gmail nyata, provider nyata, perangkat fisik dan backup/restore
tetap **NOT RUN**. Tidak ada deployment atau data nyata.


## Bukti C2A — 22 November 2026

- Database uji: `file:/tmp/pam-c2a.db`, server Go lokal; emulator memakai
  `http://10.0.2.2:8080`.
- Akun A `c2a2@example.test`; akun B `c2b@example.test` (sintetis).
- Manual: `Rp175.000`, 22 Nov 2026, kategori Makanan, keterangan `C2A`.
- DB server: satu baris `source=MANUAL`, `amount_minor=17500000`, `revision=0`
  sebelum transaksi berikutnya.
- UI: daftar menampilkan `1 transaksi`, detail menampilkan `Sumber: Manual`,
  `Versi parser: server-v1`; Ringkasan menampilkan `Rp175.000`,
  coverage `Sebagian`.
- Restart API dan force-stop/start app: angka tetap.
- `pm clear` + login A: baris/summary tetap dari server.
- Login B: summary `Kosong`, transaksi 0; DB hanya berisi owner A.
- API dimatikan: Ringkasan menampilkan “Tidak dapat terhubung…”, bukan nol/local.
- 248 tes Android PASS; assemble/lint dijalankan terpisah (lihat laporan).


## Gate A C2A (22 November 2026)

- A1: custom category total tidak lagi dibuang; `serverCode/serverLabel` dipertahankan
  dan label custom dirender. Provenance/parser/confidence/origin/note/refund/fingerprint
  dipetakan dari DTO; MANUAL tidak menampilkan parser palsu.
- A2: tanggal/enum/amount/currency/revision invalid menghasilkan `Outcome.Failure`
  parsing, bukan epoch/DEBIT/MANUAL rekaan.
- A3: `FinanceRefreshBus` menginvalidasi list/overview berdasarkan owner+bulan setelah
  acknowledgement; detail meneruskan error; overview tidak mengklaim comparison bila
  prior-month gagal.
- A4: `ManualPendingActionStore.save` mengembalikan hasil, memakai field Base64
  delimiter-safe, dan ViewModel tidak POST bila key gagal disimpan. Store test mencakup
  unicode/delimiter, restart-read, dan wrap failure.

## C2B1 — status

Consent: v0.4 `RecordConsentUseCase` memakai `recordConfirmed`; local GRANTED tidak
lagi dianggap bukti server. Offline/legacy tetap di belakang `isEnabled=false`.
Import job/batch/resume, fingerprint key dari server, dan pagination/filter server
belum diimplementasikan. C2B1 belum selesai.


## R1 visual candidate

Empat layar B01–B04 memakai token PAM Warm R1/Manrope. Detail capability notice
dan manual sticky CTA diterapkan. Lihat [`15_DESIGN_R1_HANDOFF.md`](15_DESIGN_R1_HANDOFF.md).
Dua verifikasi emulator gate C2A (refresh <5 detik dan retry respons hilang)
masih terbuka; C2B1 import/pagination belum selesai.
