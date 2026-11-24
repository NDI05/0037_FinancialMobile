# Gmail live — impor Livin nyata lewat polling (ronde 6), 24 November 2026

Status: **G25 PASS (live, terbatas pada akun pilot owner)**; G36 (SSO Google + Run pemilik) dan
G29/G37 (GCP Rp0) tetap **NOT RUN / OPEN**. Dokumen ini hanya memuat **agregat**; tidak ada alamat
email, token, ID pesan Gmail, nominal, merchant, subjek, atau isi email di repo/chat.

## 1. Temuan pada UAT live pertama

Backend resmi owner berjalan di `127.0.0.1:18090` (`APP_ENV=uat-local`) dengan kredensial OAuth
nyata dan mode **polling**. Backfill setahun berjalan (12 bulan), tetapi **semua pesan Livin
dilewati** (`seen` bertambah, `imported=0`): 104 (Nov) + 136 (Okt) + 136 (Sep) + 24 (Agu) +
92 (Jul) + 50 (Jun) pesan, `skipped` sama dengan `seen`.

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
`-race` auth/gmail/httpapi/store **PASS**.

## 3. Bukti live (agregat, per 24 Nov 19:59 WIB)

Binary runtime resmi dibangun ulang dari `c98527b` dengan overlay launcher owner, lalu backend
dijalankan ulang memakai launcher yang sama (`~/.config/pam/uat-runtime/start.py`); DB dicadangkan
sebelum reset buku sinkronisasi (bukan data transaksi).

| Butir | Hasil |
|---|---|
| Koneksi Gmail | 1 koneksi `ACTIVE`, `sync_state=backfill` → impor berjalan |
| Backfill Nov 2026 | 54 dipindai, **40 transaksi diimpor**, 0 duplikat, 14 dilewati |
| Backfill Okt 2026 | 86 dipindai, **53 transaksi diimpor**, 0 duplikat, 33 dilewati |
| Backfill Sep 2026 | 136 dipindai, 0 baru, **79 duplikat**, 57 dilewati |
| Backfill Agu 2026 | 124 dipindai, **55 transaksi diimpor**, 0 duplikat, 69 dilewati |
| Backfill Jul 2026 | sedang berjalan saat snapshot (50 dipindai, **34 diimpor**) |
| Total transaksi unik | **327** baris, 327 `source_ref_hash` unik, 327 fingerprint unik (tidak ada duplikat) |
| Rate limit Google | 16 `gmail_provider_retry` (`rate_limited`) ditangani backoff provider; pass tetap berjalan tiap ±2 menit |
| Aplikasi | layar utama memanggil `/v1/summary` dan `/v1/transactions` dan menampilkan data tersimpan (tanpa error) |

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
