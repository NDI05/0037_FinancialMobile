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

Tes Android baru: `BackendSessionRepositoryTest` (14) dan `PamApiClientTest` (6).
Total suite saat dokumen ini ditulis: **235 tes JVM**, 0 gagal.

## Belum dipindahkan ke server

| Fitur v0.4 | Sumber saat ini | Sisa pekerjaan |
|---|---|---|
| Transaksi daftar/detail/edit | Room lokal | repository remote + pagination/cursor/konflik revisi di UI |
| Ringkasan | `ComputeMonthlySummaryUseCase` lokal | computed summary server sebagai sumber utama; Room hanya cache |
| Budget | Room lokal | DTO/limits ID/revision ke server dan konflik |
| Analisis | engine lokal/remote lama | dependency revision server, freshness STALE/HISTORICAL di UI |
| Import batch | Room + `BackendSyncClient` summary saja | upload kandidat/job/batch ke Go, resume dan finalisasi server |
| Kategori | Room lokal | ID kode server/custom category |
| Lampiran | lokal | tahap D (FR-59) |
| Admin | belum ada | tahap D (FR-65) |

Karena itu v0.4 belum boleh disebut seluruhnya server-backed. UI fitur di atas
masih memakai Room; jalur legacy tidak dihapus pada C2 ini.

## Emulator

Belum ada bukti C2 baru di emulator pada checkpoint ini. Bukti emulator
register/login/consent/Ringkasan sebelumnya hanya membuktikan auth/navigation,
bukan sumber data server. Langkah berikutnya: build
`-PPAM_BACKEND_BASE_URL=http://10.0.2.2:8080`, seed database Go, lalu buktikan
transaksi/import/summary/budget/analysis/deletion lewat UI dan HTTP.

## Batas

Cloud Turso, Gmail nyata, provider nyata, perangkat fisik dan backup/restore
tetap **NOT RUN**. Tidak ada deployment atau data nyata.
