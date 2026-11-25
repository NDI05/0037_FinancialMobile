# Perbaikan nominal impor email — 25 November 2026

**Laporan pemilik:** transaksi yang diambil dari email Livin menampilkan nominal dengan angka nol
kurang (100x lebih kecil). Entri manual tidak terpengaruh.

**Perbaikan:** backend `0037-Backend-Financial` commit
[`d42d7c4`](https://github.com/NDI05/0037-Backend-Financial/commit/d42d7c4)
(`fix(gmail): parse Livin amounts as minor units and repair imported rows`), dokumentasi
[`aa33fb8`](https://github.com/NDI05/0037-Backend-Financial/commit/aa33fb8).

## Penyebab

1. Parser notifikasi Livin (`internal/gmail/livin.go`) mengembalikan rupiah utuh: `"Rp 25.000"`
   menjadi `25000`.
2. Kontrak API, entri manual Android, dan formatter Android memakai **minor IDR (1/100 rupiah)**;
   Android membagi 100 saat menampilkan. Jadi baris hasil impor email tersimpan 100x terlalu kecil.
3. Fixture Gmail palsu mencetak nilai minor seolah rupiah, sehingga tes lama dan UAT sintetis
   sama-sama tidak menangkap kesalahan ini.

## Perbaikan

- Parser `gmail-livin-v3` mengembalikan minor unit dan mempertahankan sen yang tercetak
  (`"Rp 25.000,50"` → `2_500_050`).
- Fixture fake mencetak rupiah mayor + sen, dan tes round-trip memaksa parser mengembalikan nilai
  minor yang dideklarasikan.
- Migrasi **022** memperbaiki data lama sekali jalan: hanya baris `source=EMAIL` dari parser
  `gmail-livin-v1/v2` yang dikalikan 100; baris `MANUAL` tidak disentuh; revisi bulan dinaikkan agar
  cache ringkasan tidak menyajikan total lama.
- Tidak ada perubahan kode Android: APK yang sama membaca minor unit dari server dan sudah membagi
  100 saat menampilkan. Setelah server diperbaiki, buka ulang/refesh daftar Transaksi dan Ringkasan.

## Verifikasi

| Bukti | Hasil |
|---|---|
| `go vet ./...` pada `d42d7c4` | PASS |
| `go test ./... -count=1` pada `d42d7c4` | PASS 11 paket |
| Tes parser/format produksi + round-trip fixture | PASS (`25.000`→`2_500_000`, `1.250.000`→`125_000_000`, `25.000,50`→`2_500_050`, `9.990`→`999_000`) |
| Tes migrasi 022 | PASS (v1/v2 ×100, MANUAL & v3 utuh, revisi bulan naik) |
| E2E terisolasi fake Gmail (DB `/tmp`, port 18093) | PASS — fixture `2_500_000`/`999_000`/`12_345_678` minor dikembalikan API persis sama |
| DB UAT lokal pemilik setelah migrasi | 915 baris EMAIL diskalakan sekali; 10 baris MANUAL tetap; `schema_migrations`=22; health/ready endpoint 200 |

**Batas bukti:** diterapkan ke runtime UAT lokal (`127.0.0.1:18090`), bukan deployment produksi.
Belum ada email asli yang dibaca/dikutip pada verifikasi ini. Analisis lama yang sudah tersimpan
berisi snapshot nominal lama dan perlu dibuat ulang. Bukti email Livin baru masuk saat aplikasi
tertutup (G36) tetap tugas pemilik.

Detail teknis dan angka agregat: backend
[`docs/evidence/GMAIL_AMOUNT_UNIT_FIX_2026-11-25.md`](https://github.com/NDI05/0037-Backend-Financial/blob/main/docs/evidence/GMAIL_AMOUNT_UNIT_FIX_2026-11-25.md).

## Bukti UI pada APK (emulator, data sintetis)

Untuk membuktikan perbaikan sampai ke layar Android, APK final (`0cf7faf`, sha256 `5a2f71a1…`)
diuji pada emulator-5556 dengan **backend terisolasi** `d42d7c4` (port 18091, DB `/tmp`, fake Gmail,
akun sintetis `emu-amount@example.test`) dan `adb reverse tcp:18090 tcp:18091`. Fixture mengirim
tiga nominal minor: `2_500_000` (Rp25.000), `999_000` (Rp9.990), `12_345_678` (Rp123.456,78).

Hasil yang terlihat di layar:

| Layar | yang tampil |
|---|---|
| Transaksi | `Rp25.000`, `Rp9.990`, `Rp123.456,78` — tiga baris, 3 transaksi |
| Ringkasan | KELUAR `Rp158.446,78` (= 25.000 + 9.990 + 123.456,78), arus kas bersih `Rp158.446,78`, "3 belum berkategori" |

Screenshot (hanya data sintetis): [Transaksi](../evidence/assets/amount_fix_emulator_2026-11-25/transaksi_dengan_nominal_benar.png)
dan [Ringkasan](../evidence/assets/amount_fix_emulator_2026-11-25/ringkasan_dengan_nominal_benar.png).

Smoke pada sesi yang sama: layar **Analisis** terbuka tanpa crash, sheet **Status pembaruan** hanya
memanggil `GET /v1/gmail/status` (tanpa memicu impor) dan menampilkan status bulan 13/13, layar
**Pengaturan** tidak memuat entri Anggaran/Lampiran/Administrasi. Setelah uji, `adb reverse`
dikembalikan ke backend UAT pemilik dan data aplikasi emulator dibersihkan.

Batas: emulator bukan HP pemilik; provider Gmail adalah fake dan datanya sintetis; bukti email Livin
alami saat aplikasi tertutup (G36) tetap tugas pemilik.

Catatan batas teknis yang diketahui (dari audit independen, bukan blocker rilis lokal): format `25,5`
dibaca `25.500` minor karena koma hanya dianggap desimal bila dua digit — Livin selalu mencetak dua
digit desimal sehingga risiko rendah; cabang `ON CONFLICT` pada `month_revisions` dan idempotensi
buka-ulang migrasi belum diuji langsung, melainkan lewat runner migrasi sekali-jalan.
