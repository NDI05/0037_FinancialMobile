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

Angka tes fresh 23 Nov malam: Android **452 tests / 63 classes / 0 failure**, `:app:lintDebug`
**0 error, 109 warning, 1 hint**, Go `test -count=1 ./...` **semua paket ok**, `go vet` bersih.
UAT lampiran: [UAT_S4_ATTACHMENTS_2026-11-23.md](../evidence/UAT_S4_ATTACHMENTS_2026-11-23.md);
UAT admin: [UAT_S4_ADMIN_2026-11-23.md](../evidence/UAT_S4_ADMIN_2026-11-23.md).

## Transaksi (C01–C05)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C01 Create manual, key stabil, retry satu record | PARTIAL | Kontrak POST server + test idempotensi/outbox; UI `Tambah transaksi` ada | Create manual lewat UI perangkat tidak diulang pada sesi ini; replay create hilang sudah diuji worker (gate2 JSONL) |
| C02 Read list/detail/cursor/provenance | **PASS runtime** | Perangkat menampilkan 6 transaksi server + detail `KOPI UJI QRIS` lengkap dengan provenance (sumber, parser, keyakinan, waktu) | Halaman kedua/cursor belum diuji di perangkat (6 baris < limit) |
| C03 Update correction + revisi/409 | **PASS runtime** | Editor detail menyimpan `PATCH /v1/transactions/{id}` 200; state revisi naik (2 → 3); atribut dinamis round-trip `{"sumber":"kamera"}` dibaca ulang dari server | Summary invalidation dibuktikan unit/integration, tidak diukur ulang di perangkat |
| C04 Delete aktif/total, tanpa resurrect | PARTIAL | Handler DELETE + guard replay ada; tombol hapus di detail ada | Penghapusan destruktif tidak dijalankan di UAT sesi ini |
| C05 Search query/filter halaman berikutnya | PARTIAL | UI pencarian server (`/v1/transactions` search envelope, `scanned`/`truncated`) dan test page/search lulus | Query lewat UI perangkat + halaman kedua belum dijalankan |

## Kategori (C06–C10)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C06 Create custom | PASS API/unit | Route/store kategori + screen `CategoryManagement` + editor atribut dinamis; test CRUD lulus | Belum dijalankan di perangkat pada sesi ini |
| C07 Read ID/code/label/kind/attributes | PASS API/unit | GET kategori oleh store/API, model + test | Sama |
| C08 Update terbaca ulang | PASS API/unit | PATCH kategori + revision CAS di store, test revision | Sama |
| C09 Delete built-in guard / used-category | PASS API/unit | Guard `builtin_category`, `category_in_use` di handler + test | Sama |
| C10 Search owner-scoped | PARTIAL | Server `q` untuk kategori belum ada di port (`GET /v1/categories` tanpa `q`); daftar owner-scoped lulus | Kriteria pencarian nama belum terpenuhi — gap kontrak dilaporkan worker |

## Anggaran (C11–C15)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C11 Create plan/limits | PASS API/unit | PUT budget plan server + domain `ServerBudgetPlan`, test save | UI perangkat belum dijalankan |
| C12 Read month/gap/actual | PARTIAL | GET plan + MonthlySummary; UI plan server memakai ringkasan bulanan | Perbandingan aktual-vs-limit dihitung ulang dari ringkasan; belum diuji di perangkat |
| C13 Update limits/attributes/revision | PASS API/unit | Editor atribut dinamis pada plan (worker S3) + PUT round-trip test | Sama |
| C14 Delete plan | PASS API/unit | DELETE plan server + test | Sama |
| C15 Search periode/metadata persistence | NOT RUN | — | Belum ada bukti; jangan diklaim |

## Lampiran (C16–C20)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C16 Create private copy + metadata | **PASS runtime** | `POST /v1/attachments 201`, panel menampilkan nama/tipe/ukuran, hint file tetap di perangkat | PDF uji 193 B buatan sendiri |
| C17 Read setelah restart | **PASS runtime** | Force-stop + buka ulang memuat metadata dari `GET /v1/attachments` | Unavailable di perangkat lain belum diuji |
| C18 Update display name/attributes + conflict | PARTIAL | Rename inline + CAS revisi, 28 test targeted hijau (worker) | Rename lewat UI perangkat belum diulang |
| C19 Delete metadata/file cleanup | **PASS runtime** | Hapus di panel + `DELETE /v1/attachments`; baris hilang setelah reload | Retry kegagalan hapus belum diuji perangkat |
| C20 Search nama/context/owner | PARTIAL | Search submit-driven + test lulus | Search lewat UI perangkat belum dijalankan |

## Admin pengguna (C21–C25)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C21 Create terkontrol/invite; public register USER | **PASS runtime** | `pam-admin -promote` idempoten + audit `bootstrap_admin`; undangan `201` token tampil sekali; `POST /v1/accounts` dari undangan `201` peran sesuai undangan | Email undangan tidak benar-benar dikirim |
| C22 Read list/detail tanpa finance owner lain | **PASS runtime** | Daftar 4 akun, detail editor, tidak ada data keuangan di layar admin | Hanya 4 akun UAT |
| C23 Update role/status/revision/revocation/audit | **PASS runtime** | Edit nama 200, konflik 409 dengan draf utuh, reload revisi lalu simpan 200; audit `admin.account.update` | Perubahan peran belum dijalankan di perangkat (nama/status sudah) |
| C24 Delete/deactivate + last-admin guard + revoke | PARTIAL | Nonaktifkan akun lain 200 → `SUSPENDED`, seluruh sesi target dicabut; self-deactivate ditolak 400; last-admin guard ditutup test store/HTTP + trigger DB | Guard `last_admin` tidak dipicu ulang di perangkat |
| C25 Search query/role/status/cursor; USER ditolak | PARTIAL | Pencarian `andhika` → 1 baris; USER tanpa peran ditolak sebelum request admin; filter peran/status/cursor diuji API/VM | Filter status di perangkat hanya diamati, cursor butuh >1 halaman |

## Kesimpulan gate

- S4.1 (lampiran): create/read/delete **PASS runtime**; update/search PARTIAL.
- S4.2 (admin): invite/read/update/deactivate **PASS runtime**; last-admin guard dan cursor
  masih bergantung pada test API/store, bukan UAT perangkat.
- S3 (C01–C15): belum memenuhi gate penuh. Yang paling dekat: C02/C03 runtime perangkat,
  C06–C09/C11/C13/C14 API/unit. Gap utama: C05 query perangkat, C10 search kategori,
  C12/C15 anggaran, C01/C04 destruktif.
- Tidak ada baris di matriks ini yang diberi PASS tanpa menyebut bukti dan batasnya.
