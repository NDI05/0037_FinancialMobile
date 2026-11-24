# Gmail live — impor Livin nyata lewat polling (ronde 6), 24 November 2026

Status: **G25 PASS (live, terbatas pada akun pilot owner)**; G36 (SSO Google + Run pemilik) dan
G29/G37 (GCP Rp0) tetap **NOT RUN / OPEN**. Dokumen ini hanya memuat **agregat**; tidak ada alamat
email, token, ID pesan Gmail, nominal, merchant, subjek, atau isi email di repo/chat.

## 1. Temuan pada UAT live pertama

Backend resmi owner berjalan di `127.0.0.1:18090` (`APP_ENV=uat-local`) dengan kredensial OAuth
nyata dan mode **polling**. Backfill setahun berjalan (12 bulan), tetapi **semua pesan Livin
dilewati** (`seen` bertambah, `imported=0`): 104 (Nov) + 136 (Okt) + 136 (Sep) + 24 (Agu) +
92 (Jul) + 50 (Jun) pesan, `skipped` sama dengan `seen`. Angka ini milik **pass pertama yang
gagal** (hanya 6 bulan yang sempat berjalan) dan tidak boleh dibandingkan langsung dengan angka
`dipindai` pada run final §3.

Penyebab ditelusuri **tanpa membaca isi email**: diagnostik sementara (di luar repo, dihapus
setelah dipakai) hanya mencetak *bentuk* baris (digit/huruf dimask) dan agregat statistik parser.

- Templat produksi menaruh label di **baris sendiri** dan nilai di baris berikutnya:
  `Penerima` (tanpa titik dua) lalu nama penerima. Parser hanya menerima bentuk fixture
  `Penerima: NAMA` satu baris → `merchant_missing`.
- Tanggal juga bertumpuk: `Tanggal 12 Okt 2026` + `pkl 09:30:45 WIB`. Parser hanya menerima
  `Tanggal: ...` satu baris dan hanya nama bulan Inggris → `event_time_missing`.
- Agregat 40 pesan Nov setelah perbaikan pertama: 31 `PARSED`; sisa `amount_missing`/
  `merchant_missing` berasal dari template promosi/notifikasi non-transaksi.

## 2. Perbaikan (backend `integration/2026-11-24`)

| Commit | Isi |
|---|---|
| `5df6be6` | `internal/gmail/livin.go`: menerima label **stacked** (`Penerima`/`Merchant` baris sendiri) dengan penjaga identifier/reference; menerima waktu **stacked** (`pkl ... WIB`) dengan penggabungan dua baris; regresi baru di `parser_test.go` |
| `c98527b` | Normalisasi nama/abreviasi bulan Indonesia (`Okt`, `Agu`, `Des`, `Agustus`, …) sebelum layout tanggal; regresi baru |

Gate backend setelah perbaikan: `go vet ./...` **PASS**, `go build ./...` **PASS**,
`go test ./... -count=1` **PASS** (10 paket; `internal/gmail` termasuk regresi stacked/Indonesian),
`-race` auth/gmail/httpapi/store **PASS**. Audit independen ronde 7 menemukan dua P1: baris field
bisa menjadi merchant dan angka bukti belum rekonsiliasi; keduanya ditutup di `e3b84b4` dan dokumen
ini. Perbaikan tambahan `e3b84b4` (audit ronde 7) dan `e19477b` (audit ronde 2): WITA/WIT
memakai offset sebenarnya, waktu tetap diambil walau ada baris field di antara tanggal dan waktu,
alias bulan `Nop`/`Peb`, pembulatan nominal, batas panjang label, dan **baris PAN/identifier tidak
boleh menjadi merchant** baik pada label stacked maupun inline. Runtime loopback dibangun ulang dari
`e19477b` (`vcs.modified=false`) **tanpa impor ulang**; bukti 914 di §3 tetap dihasilkan build
`c98527b` (20:16 WIB), sebelum commit hardening, dan hardening berlaku untuk impor berikutnya.

## 3. Bukti live (agregat, final 24 Nov 20:16 WIB — backfill 12/12 COMPLETE)

Binary runtime resmi dibangun ulang dari `c98527b` dengan overlay launcher owner, lalu backend
dijalankan ulang memakai launcher yang sama (`~/.config/pam/uat-runtime/start.py`); DB dicadangkan
sebelum reset buku sinkronisasi (bukan data transaksi).

| Butir | Hasil |
|---|---|
| Koneksi Gmail | 1 koneksi `ACTIVE`, `sync_state=idle` setelah **12/12 bulan backfill COMPLETE** |
| Backfill Nov 2026 | 54 dipindai, **40 transaksi diimpor**, 0 duplikat, 14 dilewati |
| Backfill Okt 2026 | 86 dipindai, **53 transaksi diimpor**, 0 duplikat, 33 dilewati |
| Backfill Sep 2026 | 136 dipindai, 0 baru, **79 duplikat**, 57 dilewati |
| Backfill Agu 2026 | 124 dipindai, **55 transaksi diimpor**, 0 duplikat, 69 dilewati |
| Backfill Jul 2026 | 92 dipindai, **55 transaksi diimpor**, 0 duplikat, 37 dilewati |
| Backfill Jun 2026 | 124 dipindai, **73 transaksi diimpor**, 0 duplikat, 51 dilewati |
| Backfill Mei 2026 | 118 dipindai, **52 transaksi diimpor**, 0 duplikat, 66 dilewati |
| Backfill Apr 2026 | 156 dipindai, **97 transaksi diimpor**, 0 duplikat, 59 dilewati |
| Backfill Mar 2026 | 127 dipindai, **69 transaksi diimpor**, 0 duplikat, 58 dilewati |
| Backfill Feb 2026 | 106 dipindai, **48 transaksi diimpor**, 0 duplikat, 58 dilewati |
| Backfill Jan 2026 | 118 dipindai, **83 transaksi diimpor**, 0 duplikat, 35 dilewati |
| Backfill Des 2025 | 170 dipindai, **144 transaksi diimpor**, 0 duplikat, 26 dilewati |
| Total transaksi unik | **914** baris; 914 `source_ref_hash` unik; **0 baris duplikat** |
| Rate limit Google | 16 `gmail_provider_retry` (`rate_limited`) ditangani backoff provider; pass tetap berjalan tiap ±2 menit tanpa kegagalan permanen |
| Aplikasi | layar utama memanggil `/v1/summary` dan `/v1/transactions` dan menampilkan data tersimpan (HTTP 200, tanpa error) |

**Rekonsiliasi angka.** Run final mengimpor **769** transaksi (jumlah kolom "diimpor" di atas).
Total tabel **914** karena **145 baris sudah tersimpan** dari backfill live **parsial** pada build
perbaikan parser (`5df6be6`/`c98527b`), sebelum buku sinkronisasi di-reset untuk run final: Nov 38,
Okt 28, Sep 79. Pass pertama di §1 (`imported=0`) terjadi **sebelum** perbaikan parser, jadi 145
baris ini bukan hasil pass tersebut; job parsial itu tidak lagi muncul sebagai impor pada run final. Baris lama itu tidak dihitung ulang sebagai impor
(duplikat), sehingga run final mencatat **79 duplikat pada Sep** — 79 baris Sep yang sama — dan
769 + 145 = **914**. Semua 914 memiliki `source_ref_hash` unik (0 baris duplikat). Counter
duplikat per bulan hanya menghitung baris yang **dipindai ulang oleh job run itu**: baris Nov 38
dan Okt 28 tidak dipindai ulang oleh job final, jadi kolom duplikat Nov/Okt tetap **0**; hanya Sep
yang dipindai ulang (79). Karena itu counter per bulan tidak boleh dijumlahkan sebagai total
duplikat global.

Sisa `skipped` adalah pesan yang bukan transaksi atau template lain (promosi/notifikasi tanpa
merchant/nominal), bukan kegagalan impor diam-diam; alasannya tercatat per pesan di
`import_skipped` dan tidak memuat data sensitif.

## 4. Batasan dan penyimpangan

- Angka di atas hanya agregat akun pilot owner; bukan bukti untuk akun lain.
- Billing GCP **belum dilepas** dan kuota Gmail nyata belum diukur biayanya → G29/G37 **OPEN**
  sampai owner memverifikasi tagihan.
- G36 (login Google nyata + tautkan akun lama + tombol **Run** Android Studio) **belum dijalankan**
  pemilik; bukti fixture/unit tidak menggantikannya.
- Backend dijalankan ulang oleh agen memakai launcher owner (bukan `Run` Android Studio); aplikasi
  di HP tidak berubah dan tetap terhubung lewat `adb reverse tcp:18090`.
- Diagnostik sementara dibuat di `cmd/uatdiag` lalu **dihapus**; `git status` bersih dan tidak ada
  artefak data owner yang masuk repo.
- 914 baris live tersimpan dengan `parser_version=gmail-livin-v1`. Setelah hardening audit
  (`e3b84b4`) parser baru diberi tag **`gmail-livin-v2`** sehingga impor berikutnya memakai
  aturan merchant/waktu yang diperketat. Regenerasi 914 baris lama (hapus lalu impor ulang) bersifat
  destruktif pada data owner dan **belum dilakukan**; perlu persetujuan pemilik. Baris lama tetap
  valid dari sisi nominal/tanggal/dedupe (`source_ref_hash` unik), hanya label merchant pada
  templat tertentu yang berpotensi kurang bersih.

## 4. Verifikasi tombol "Sinkronkan sekarang" dari HP fisik (24 Nov 2026, 21:05–21:15 WIB)

Aplikasi debug pada HP owner (`com.pam.recap.debug`, `1.0.0-debug`) sudah login dan tertaut Gmail,
jadi alur antrian bisa diuji langsung dari perangkat (bukan hanya curl/harness).

| Langkah | Bukti |
|---|---|
| Bottom sheet "Status pembaruan" | Menampilkan "Sinkron server Gmail", status **Menunggu**, "Bulan selesai: 12 dari 12", jadwal sinkron berikutnya, dan tombol **Sinkronkan sekarang** |
| Ketuk "Sinkronkan sekarang" (2×) | `POST /v1/gmail/sync` → **202 Accepted** (masuk antrian), sesuai desain queue + coalesce |
| Transisi UI | Menunggu → **Mengambil riwayat** → **Sedang sinkron** → kembali **Menunggu** dengan tombol aktif |
| Log server selama sinkron | `gmail_sync_pass` tiap menit: `connections=1, backfills=0, deltas=0/1, events=0, failures=0`; **0 WARN/ERROR** |
| Jumlah transaksi tersimpan | **914** sebelum dan sesudah kedua sinkron (idempoten, tanpa duplikat) |
| Cooldown/queue | Ketukan berulang tidak membuat job paralel; job tetap satu dan selesai kembali ke idle |

Catatan semantik cooldown (diverifikasi 24 Nov 21:15–21:19): penanda `sync_requested_at` dihapus
saat job manual yang dipicu selesai (`ClearGmailSyncRequest`), sehingga ketukan **setelah** sync
selesai kembali diterima (202) — ini perilaku desain, bukan pelanggaran throttle. Respons **429**
`sync_cooldown` hanya muncul bila permintaan datang saat penanda masih aktif (job belum selesai);
jalur itu tetap dicakup harness kontrak (G26), karena tombol UI dinonaktifkan ("Sedang sinkron")
selama job berjalan sehingga tidak bisa dipicu dari layar. Total 7 permintaan manual 21:00–21:19:
semua **202**, `failures=0`, tanpa WARN/ERROR, jumlah tetap **914**.

Catatan: notice "Kategori: Koneksi terputus" pada sheet adalah respons 503 lama
(`/v1/category-jobs`, 19:55–20:24 WIB, sebelum restart terakhir); setelah restart tidak ada 503
kategori baru. Bukti ini memperkuat G25 (Gmail live + tombol manual dari HP) tetapi **bukan**
G36 (SSO Google + Run literal dari Android Studio) maupun G29/G37 (GCP Rp0), yang tetap menunggu
pemilik.
