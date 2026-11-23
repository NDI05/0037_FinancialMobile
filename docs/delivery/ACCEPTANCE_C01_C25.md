# Matriks acceptance 25 aksi (C01–C25) — status per 23 November 2026 (malam)

Matriks ini memetakan setiap aksi delivery S3 (C01–C15) dan S4 (C16–C25) ke bukti yang
benar-benar dijalankan, bukan ke daftar fitur yang direncanakan. Kosakata status:

- **PASS runtime** — dijalankan pada aplikasi + backend sungguhan, hasil dibaca silang dari
  server/log/database, dengan batas lingkungan yang dicatat.
- **PASS API/unit** — kontrak server dan/atau test otomatis lulus; UI perangkat belum diuji
  ulang untuk aksi itu pada sesi ini.
- **PARTIAL** — implementasi dan sebagian bukti ada, tetapi satu atau lebih kriteria
  acceptance belum dibuktikan.
- **NOT RUN** — belum ada bukti runtime; jangan dibaca sebagai lulus.

Angka tes fresh 23 Nov malam (HEAD integrasi `99cb3df`): Android **463 tests / 63 classes /
0 failure** (debug) dan **473 tests / 64 classes / 0 failure** (varian assessment),
`:app:lintDebug` **0 error, 115 warning, 1 hint**, `:app:lintAssessment` **0 error, 123 warning,
1 hint**, Go `test -count=1 ./...` **semua paket ok**, `go vet` bersih, `-race` store+httpapi ok.
UAT lampiran: [UAT_S4_ATTACHMENTS_2026-11-23.md](../evidence/UAT_S4_ATTACHMENTS_2026-11-23.md);
UAT admin: [UAT_S4_ADMIN_2026-11-23.md](../evidence/UAT_S4_ADMIN_2026-11-23.md);
UAT perangkat S3/S4: [UAT_S3_S4_DEVICE_2026-11-23.md](../evidence/UAT_S3_S4_DEVICE_2026-11-23.md).
Akun uji perangkat memakai data sintetis `e2e.accept.c.6f6f536f@pam-uat.test` (135 transaksi),
bukan data pemilik.

## Transaksi (C01–C05)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C01 Create manual, key stabil, retry satu record | PARTIAL | Kontrak POST server + test idempotensi/outbox; UI `Tambah transaksi` ada; percobaan perangkat mengirim `POST /v1/transactions` tetapi 400 karena nilai form lama ikut terpulihkan | Create manual lewat UI perangkat belum terbukti; replay create tetap API/unit-only |
| C02 Read list/detail/cursor/provenance | **PASS runtime** | Perangkat menampilkan 6 transaksi server + detail `KOPI UJI QRIS` lengkap dengan provenance (sumber, parser, keyakinan, waktu) | Halaman kedua/cursor belum diuji di perangkat (6 baris < limit) |
| C03 Update correction + revisi/409 | **PASS runtime** | Editor detail menyimpan `PATCH /v1/transactions/{id}` 200; state revisi naik (2 → 3); atribut dinamis round-trip `{"sumber":"kamera"}` dibaca ulang dari server | Summary invalidation dibuktikan unit/integration, tidak diukur ulang di perangkat |
| C04 Delete aktif/total, tanpa resurrect | **PASS runtime** | Perangkat: detail → Hapus → konfirmasi; `DELETE /v1/transactions/{id}` 204; November 85 → 84 baris, baris #025 hilang di UI dump dan API, tidak resurrect setelah reload | Untuk akun sintetis; guard replay/outage tetap diuji unit/API |
| C05 Search query/filter halaman berikutnya | **PASS runtime** | Perangkat: `q=UJIACC` halaman 1 "50 transaksi" + "Server memeriksa 51 transaksi"; "Muat lebih banyak" → halaman 2 "85 transaksi"/"Server memeriksa 86 transaksi"; ganti query mereset kursor (dump c05_p1/p2/s4 + log route-level) | Filter tanggal/kategori di perangkat tidak diulang; dataset 135 sintetis |

## Kategori (C06–C10)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C06 Create custom | PASS API/unit | Route/store kategori + screen `CategoryManagement` + editor atribut dinamis; test CRUD lulus | Belum dijalankan di perangkat pada sesi ini |
| C07 Read ID/code/label/kind/attributes | PASS API/unit | GET kategori oleh store/API, model + test | Sama |
| C08 Update terbaca ulang | PASS API/unit | PATCH kategori + revision CAS di store, test revision | Sama |
| C09 Delete built-in guard / used-category | PASS API/unit | Guard `builtin_category`, `category_in_use` di handler + test | Sama |
| C10 Search owner-scoped | **PASS runtime** | Server `GET /v1/categories?q=&kind=&limit=&cursor=` (`search{scanned,truncated}`, `nextCursor`) + UI; perangkat: 11 kategori → `mak` → "1 kategori" Makanan/food/Bawaan, `GET /v1/categories` 200 | Query persis hanya di dump (log backend route-level); decode `attributes:null` diperbaiki di commit `3a0ff3b` |

## Anggaran (C11–C15)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C11 Create plan/limits | PASS API/unit | PUT budget plan server + domain `ServerBudgetPlan`, test save | UI perangkat belum dijalankan |
| C12 Read month/gap/actual | **PASS runtime** | Perangkat menampilkan REALISASI VS RENCANA: Makanan Rp476,27 dari Rp25.000, Transportasi Rp396,88 dari Rp12.000, Belanja Rp494,08 dari Rp9.000, sisa bulan Rp3.815,78 (dump c12_c15_budget.xml) | Plan anggaran dibuat worker pada akun sintetis; bukan plan pemilik |
| C13 Update limits/attributes/revision | PASS API/unit | Editor atribut dinamis pada plan (worker S3) + PUT round-trip test | Sama |
| C14 Delete plan | PASS API/unit | DELETE plan server + test | Sama |
| C15 Search periode/metadata persistence | **PASS runtime** | Server `GET /v1/budget-plans?from=&to=&limit=&cursor=` (inklusif, newest-first, `nextCursor`); perangkat: "Muat riwayat rencana" → 200, kartu "1 rencana pada Desember 2025 sampai November 2026.", baris "November 2026 · revisi 0", "limit Rp46.000 · selisih Rp44.000" (totalLimitMinor/gapMinor) | Baru 1 plan tersimpan → indikator truncation belum terpicu; membuka plan dari riwayat belum ada |

## Lampiran (C16–C20)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C16 Create private copy + metadata | **PASS runtime** | `POST /v1/attachments 201`, panel menampilkan nama/tipe/ukuran, hint file tetap di perangkat | PDF uji 193 B buatan sendiri |
| C17 Read setelah restart | **PASS runtime** | Force-stop + buka ulang memuat metadata dari `GET /v1/attachments` | Unavailable di perangkat lain belum diuji |
| C18 Update display name/attributes + conflict | **PASS runtime** | Perangkat: rename via sheet → `PATCH /v1/attachments/{id}` 200, notice "Nama lampiran diperbarui.", baris menjadi bukti-rename-accept.pdf | Konflik revisi 409 di perangkat belum diulang (ditutup API/unit) |
| C19 Delete metadata/file cleanup | **PASS runtime** | Hapus di panel + `DELETE /v1/attachments`; baris hilang setelah reload | Retry kegagalan hapus belum diuji perangkat |
| C20 Search nama/context/owner | **PASS runtime** | Perangkat: submit `zzz` → "Tidak ada lampiran yang cocok dengan pencarian."; submit nama hasil rename → baris cocok tampil | Pencarian konteks/owner lain di perangkat belum dijalankan |

## Admin pengguna (C21–C25)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C21 Create terkontrol/invite; public register USER | **PASS runtime** | `pam-admin -promote` idempoten + audit `bootstrap_admin`; undangan `201` token tampil sekali; `POST /v1/accounts` dari undangan `201` peran sesuai undangan | Email undangan tidak benar-benar dikirim |
| C22 Read list/detail tanpa finance owner lain | **PASS runtime** | Daftar 4 akun, detail editor, tidak ada data keuangan di layar admin | Hanya 4 akun UAT |
| C23 Update role/status/revision/revocation/audit | **PASS runtime** | Edit nama 200, konflik 409 dengan draf utuh, reload revisi lalu simpan 200; audit `admin.account.update` | Perubahan peran belum dijalankan di perangkat (nama/status sudah) |
| C24 Delete/deactivate + last-admin guard + revoke | PARTIAL | Nonaktifkan akun lain 200 → `SUSPENDED`, seluruh sesi target dicabut; self-deactivate ditolak 400; last-admin guard ditutup test store/HTTP + trigger DB | Guard `last_admin` tidak dipicu ulang di perangkat |
| C25 Search query/role/status/cursor; USER ditolak | PARTIAL | Pencarian `andhika` → 1 baris; USER tanpa peran ditolak sebelum request admin; filter peran/status/cursor diuji API/VM; list default server kini mengecualikan DELETED (commit `daa5f36`) | Filter status di perangkat hanya diamati, cursor butuh >1 halaman; walk perangkat C24/C25 sengaja tidak dijalankan agar sesi admin lain tidak mencabut sesi bukti |

## Kesimpulan gate

- S3 (C01–C15): **lulus kecuali C01**. PASS runtime perangkat: C02, C03, C04, C05, C10, C12,
  C15 (+ C06–C09/C11/C13/C14 PASS API/unit). C01 tetap PARTIAL — jalur create terpanggil di
  perangkat tetapi tidak pernah mencapai 201 karena nilai form lama; jangan diklaim lulus.
- S4.1 (lampiran): C16/C17/C19 **PASS runtime** dan C18/C20 kini juga **PASS runtime**;
  sisa batas: konflik revisi dan retry kegagalan hapus tidak diulang di perangkat.
- S4.2 (admin): C21/C22/C23 **PASS runtime**; C24/C25 PARTIAL karena walk perangkat sengaja
  tidak dijalankan (promosi akun admin lain akan mencabut sesi bukti). Last-admin guard,
  filter status, dan cursor tetap PASS API/unit, bukan UAT perangkat.
- S4.4 (assessment variant): **lulus audit** — varian `assessment` bebas Room/SQLCipher/
  WorkManager (`assessmentRuntimeClasspath` 0 entri terlarang, APK assessment 0 string
  terlarang), 473 test assessment hijau; batas: foreground-only sync, tanpa
  `applicationIdSuffix` (tidak bisa coexist dengan debug), tanpa data finansial lokal.
- Tidak ada baris di matriks ini yang diberi PASS tanpa menyebut bukti dan batasnya.
