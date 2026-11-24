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

Angka tes fresh 23 Nov malam (HEAD integrasi **`7337559`**): Android **493 tests / 68 classes /
0 failure** (debug) dan **503 tests / 69 classes / 0 failure** (varian assessment),
`:app:lintDebug` **0 error, 115 warning, 1 hint**, `:app:lintAssessment` **0 error, 123 warning,
1 hint**, Go `test -count=1 ./...` **semua paket ok**, `go vet` bersih, `-race` store+httpapi ok.
APK merged head: debug sha256 `6497a4066a32b00308fe7ee44291bcb713e039d54abd3909709a78c9b5e5db16`,
assessment sha256 `fa0d224e7591400354184129c2096533c87cc4334dfa6218be22baa002d44fd7`.
UAT lampiran: [UAT_S4_ATTACHMENTS_2026-11-23.md](../evidence/UAT_S4_ATTACHMENTS_2026-11-23.md);
UAT admin: [UAT_S4_ADMIN_2026-11-23.md](../evidence/UAT_S4_ADMIN_2026-11-23.md);
UAT perangkat S3/S4: [UAT_S3_S4_DEVICE_2026-11-23.md](../evidence/UAT_S3_S4_DEVICE_2026-11-23.md).
Akun uji perangkat memakai data sintetis `e2e.accept.c.6f6f536f@pam-uat.test` (135 transaksi),
bukan data pemilik.

## Transaksi (C01–C05)

| Aksi | Status | Bukti | Batas |
|---|---|---|---|
| C01 Create manual, key stabil, retry satu record | **PASS runtime** | Merged head: create manual via UI perangkat → `POST /v1/transactions` **201** 23 Nov 20:33:56 WIB (`req_f27db8bd…`), baris `C01 merged 2101` tampil di dump; ringkasan UAT naik 92/89 → **93/90** (`netMinor 7661301005`). Cross-check backend terisolasi: `tx_471df9b6…` DEBIT MANUAL 24681000, revisi 9→10, tepat 1 baris. Bukti: [UAT_MERGED_HEAD_2026-11-23.md](../evidence/UAT_MERGED_HEAD_2026-11-23.md) §2 | Key stabil/outbox diuji unit; replay lost-response create tetap API/unit-only |
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
| C24 Delete/deactivate + last-admin guard + revoke | **PASS runtime** | Nonaktifkan akun lain 200 → `SUSPENDED` + seluruh sesi target dicabut; self-deactivate ditolak 400; last-admin guard ditutup test store/HTTP + trigger DB; walk teraudit `a512770` (`UAT_GAP_ADMIN_2026-11-23.md`), regresi merged head lulus | Guard `last_admin` tidak dipicu ulang di perangkat (sengaja, agar sesi admin bukti tidak tercabut) |
| C25 Search query/role/status/cursor; USER ditolak | **PASS runtime** | Pencarian `andhika` → 1 baris; USER tanpa peran ditolak sebelum request admin; filter peran/status/cursor diuji API/VM; list default server mengecualikan DELETED (commit `daa5f36`); walk teraudit `a512770`, regresi merged head lulus | Filter status di perangkat hanya diamati; cursor butuh >1 halaman; walk perangkat tidak diulang sengaja |

## Kesimpulan gate

- S3 (C01–C15): **lulus**. PASS runtime perangkat: C01 (merged head, create manual 201 + delta
  ringkasan tepat), C02, C03, C04, C05, C10, C12, C15 (+ C06–C09/C11/C13/C14 PASS API/unit).
  C01 dibuktikan ulang setelah merge pada checkout utama; replay lost-response tetap
  API/unit-only dan dicatat sebagai batas.
- S4.1 (lampiran): C16/C17/C19 **PASS runtime** dan C18/C20 kini juga **PASS runtime**;
  sisa batas: konflik revisi dan retry kegagalan hapus tidak diulang di perangkat.
- S4.2 (admin): **C21–C25 PASS runtime** — walk C21–C25 teraudit (`a512770`,
  `UAT_GAP_ADMIN_2026-11-23.md`) dan kode admin lolos regresi merged head. Batas: guard
  last-admin dan walk cursor tidak dipentaskan ulang di perangkat.
- S4.4 (assessment variant): **lulus audit** — varian `assessment` bebas Room/SQLCipher/
  WorkManager (`assessmentRuntimeClasspath` 0 entri terlarang, APK assessment 0 string
  terlarang), **503 test assessment** hijau di merged head, `lintAssessment` 0 error, APK
  sha256 `fa0d224e…`; batas: foreground-only sync, tanpa `applicationIdSuffix` (tidak bisa
  coexist dengan debug), tanpa data finansial lokal.
- Tidak ada baris di matriks ini yang diberi PASS tanpa menyebut bukti dan batasnya.

## Addendum 23 November 2026 (malam) — revamp UI sinkronisasi + alasan kategori

Perubahan ini bukan aksi baru C01–C25, tetapi merevisi permukaan UI/UX dan pesan alasan atas
aksi yang sudah ada. Karena itu status diberi label bukti sendiri, tidak menaikkan status
S3/S4 di atas tanpa bukti baru.

| Butir | Status | Bukti | Batas |
|---|---|---|---|
| Ringkasan tanpa banner "Tidak ada email Livin", CTA impor, kartu Sumber data, ikon gear | **PASS runtime (dua perangkat)** | APK merged `a310067`: grep dump UI 0 kemunculan; Pengaturan tetap via tab bawah. [UAT_MERGED_UI_CATEGORY_2026-11-23.md](../evidence/UAT_MERGED_UI_CATEGORY_2026-11-23.md) §2 | Keadaan bulan kosong hanya diverifikasi dengan data sintetis |
| Judul top app bar terpusat terhadap lebar layar | **PASS runtime (393 dp, 360 dp/font 200%)**; **PARTIAL** untuk back+judul panjang & 412 dp | Pusat glyph 540,0 px / 540,5 px vs pusat layar 540,0 px; baris back+judul panjang & 412 dp hanya uji Robolectric. Idem §3 | Tidak ada tangkapan perangkat untuk 412 dp dan judul sangat panjang |
| Ikon status 48 dp + label singkat fade 2–3 s + TalkBack | **PASS runtime** target 48 dp & `content-desc` dinamis; label live **PARTIAL**; TalkBack live **NOT RUN** | Bounds 126 px dan 144 px pada dua density; bingkai animasi live tidak tertangkap; TalkBack terhalang dialog izin notifikasi di perangkat sintetis. Idem §4 | Animasi & TalkBack dibuktikan uji unit/Robolectric, bukan live |
| Satu sheet "Status pembaruan"; buka sheet tanpa impor; satu job untuk satu tap; hasil sama setelah tutup/buka dan minimize/relaunch | **PASS runtime** | Log backend 18090: buka sheet 263→263, satu tap = satu `POST /v1/import-jobs` 201, tutup/buka dan minimize/relaunch menampilkan hasil sama tanpa request baru. Idem §5 | Bingkai running/progress live **PARTIAL** (job sintetis < 2 s); resume unggahan pending live **NOT RUN** |
| Kegagalan + "Coba lagi" di perangkat owner | **PASS runtime** (tampilan alasan & tombol) ; alur resume unggahan **NOT RUN** | Sheet owner idle: alasan gagal tampil, tombol sinkron; buka/tutup ulang identik, tanpa request baru | Tidak ada unggahan pending yang boleh dipicu ulang di data owner |
| Alasan kategori AI tampil di Android | **PASS runtime (owner, backend 18090 lama)** | `classified=0 needsReview=5 failed=0`; copy "Perlu tinjauan: nama merchant tidak aman dikirim ke AI…"; 0 panggilan Azure. [UAT_CATEGORY_LIVIN_2026-11-23.md](../evidence/UAT_CATEGORY_LIVIN_2026-11-23.md) §12 | Notis nomor referensi di detail **belum teramati** pada record owner; kategori AI terklasifikasi/precision ≥95% tetap **NOT RUN** |
| Route/layar impor lama pensiun | **PASS by construction + grep**; probe deep-link **NOT RUN** | Destination/shortcut/string impor hilang; tidak ada deep link yang pernah mengekspos route itu. [UAT_UI_REVAMP_2026-11-23.md](../evidence/UAT_UI_REVAMP_2026-11-23.md) §5 | Tidak ada percobaan runtime ke route lama |
| "Buat Analisis" sebagai gate terpisah (provider Ollama, bulan EMPTY-coverage + tx manual) | **PASS runtime (Azure, 24 Nov 2026)** | UAT owner `:18090`: `POST /v1/analyses` **200** (5,98 s, korelasi `req_79edd5e5…`), provider Azure 499 prompt + 188/215 completion token, lalu `GET /v1/analyses/2026-11` **200** `status=AVAILABLE`, `freshness=CURRENT`, `inputRevision=26` = revisi server. Log `/tmp/pam-azure-analysis-uat.log`; ringkasan tersimpan [UAT_PM_GO_2026-11-24.md](../evidence/UAT_PM_GO_2026-11-24.md) §F | Percobaan pertama sesi pagi 400 tidak lagi direproduksi setelah backend baru; bulan EMPTY-coverage tetap NOT RUN terpisah |

Gate merged head `a310067`: 509 tes debug / 519 tes assessment (0 gagal), `lintDebug` 0
error (112 warning), `lintAssessment` 0 error (120 warning), assemble debug+assessment PASS,
Go test/vet/build/`-race` PASS, APK debug sha256 `0e9169f2…`, assessment `dea936b8…`.
Regresi merge-only yang ditemukan auditor (7 `MissingTranslation`) diperbaiki di `a310067`
dan diverifikasi ulang.

## Addendum 24 November 2026 — bukti API/admin/assessment baru (w-qa)

Dokumen rekonsiliasi lengkap + rekomendasi gate:
[UAT_PM_QA_2026-11-24.md](../evidence/UAT_PM_QA_2026-11-24.md). Test JVM fresh 24 Nov pada
HEAD `6cc0f2a`: `:app:testDebugUnitTest` **531 tes / 74 suite, 0 failure** dan
`testAssessmentUnitTest` **541 tes / 75 suite, 0 failure** (angka 513/523 adalah snapshot
`4dacad7`, bukan HEAD, dan tidak dipakai sebagai klaim final). Rincian mentah:
`docs/evidence/assets/pm_qa_2026-11-24/{s3_api,s4_admin,ui}/` dan
[UAT_ASSESSMENT_2026-11-24.md](../evidence/UAT_ASSESSMENT_2026-11-24.md).
Backend: terisolasi `:18092` (DB sementara, akun sintetis), kecuali walk UI yang memakai UAT
`:18090` + akun sintetis baru `wqa.ui.*`. Tidak ada akun/data pemilik yang disentuh.

| Butir | Status bukti baru | Bukti | Batas |
|---|---|---|---|
| C06 create custom | **PASS API + UI** | POST `/v1/categories` 201 + kategori dipakai transaksi 201; dibuat dari UI (`qa24`, atribut `sumber=qa-uat`) + API readback 200 | — |
| C07 read | **PASS API** (+ UI) | GET kategori 200 `id/code/label/kind/attributes` | Idem |
| C08 update + 409 | **PASS API** (+ UI) | PATCH 200 readback + PATCH revisi basi 409 `revision_conflict` | Idem |
| C09 guard builtin/used | **PASS API + UI** | 403 `builtin_category`; 409 "category is still referenced" (juga dipicu di UAT `:18090`); reassign 204 + GET lama 404; **reassign final lewat UI 24 Nov** `qa24`→`food`, kategori `qa24` count 0 | — |
| C11 create plan/limits | **PASS API + UI** | PUT plan 200 limits/totalLimit/gap/attributes; **simpan rencana Nov 2026 dari UI 24 Nov** (`ui_split/19`–`21`), API revision 2 | — |
| C13 update + 409 | **PASS API** | PUT 200 limits/attributes baru; PUT basi 409 | UI update limit + konflik belum dijalankan 24 Nov |
| C14 delete plan | **PASS API + UI** | DELETE 204 → GET 404, plan lain tetap; **hapus rencana lewat dialog UI 24 Nov** (`ui_split/22`–`23`), API `plans:[]` | — |
| C02 cursor | **PASS API** | transaksi & kategori halaman 2 dengan id berbeda | UI halaman 2 belum diulang |
| Persistensi | PASS API | kategori/plan/transaksi tetap setelah restart; kolom DB terenkripsi | DB sementara |
| C18 konflik revisi | **PASS API** | PATCH stale 409, PATCH benar 200 | UI konflik perangkat ditunda (emulator dialihkan) |
| C19 hapus + retry | **PASS API** | DELETE rev salah 409, benar 204, retry 404 | Retry kegagalan di UI dicatat §2.5 dok. QA |
| C20 konteks/owner | **PASS API** | search `transactionId`+`q` 1 hit; owner lain GET 404 | UI konteks dicatat §2.5 dok. QA |
| C21 bootstrap/audit | PASS API | `pam-admin -promote` + audit `bootstrap_admin`, token lama stale | Email tetap tidak dikirim |
| C23 role change/revoke/audit | **PASS API + UI** | PATCH role 200; token lama 401 `session_stale`; refresh 401 `refresh_invalid`; audit `admin.account.update`; **promosi/demote akun sintetis `wqa.ui.b` lewat dialog UI 24 Nov** (`ui_split/27`–`34`) | Status SUSPENDED via UI belum diulang |
| C24 last-admin + race | **PASS API/DB** | race dua admin → tepat 1 sukses, sisi lain 409 `last_admin`; self-demote 409; self-deactivate 400; UPDATE SQL langsung diabort trigger `last_active_admin` | Guard tidak dipentaskan ulang di perangkat |
| C24 deactivate + revoke | **PASS API** | DELETE 200 SUSPENDED, sesi target 401, audit `admin.account.deactivate` | — |
| C25 filter/cursor/USER | **PASS API + PARTIAL UI (diperluas)** | role/status filter, cursor halaman 2, `q=`; USER token 403 `forbidden`; layar Admin menolak USER di UI; **filter peran ADMIN di UI menyaring 8→5 akun** 24 Nov (`ui_split/30`–`31`) | Cursor UI tidak ada (daftar UAT < page size); pencarian live UI tidak menyaring (temuan §2.5 dok. QA) |
| S4.4 assessment | **PASS audit** | assemble+test+deps RC=0; 523 tes 0 gagal; 0 dependensi/string Room/SQLCipher/WorkManager; APK tanpa `libsqlcipher.so` | Tidak koexist (applicationId sama); foreground-only; install NOT RUN |

Sesi UI perangkat 24 Nov terbagi dua (emulator-5556, akun sintetis `wqa.ui.a`, UAT `:18090`).
Sesi pagi menambah bukti UI **C06/C07/C08** (buat kategori `qa24` + atribut dari UI, readback
editor, banner konflik 409) dan **C25 USER-ditolak**. Sesi ulang build gabungan 11:07–11:35 WIB
menambah bukti UI **nav P0 PASS**, **C09 reassign final**, **C11/C14 rencana anggaran**, **C23
role-change akun sintetis**, dan **filter peran C25**. Sisa GAP bukti UI (bukan FAIL): C13
update/konflik, C18–C20, status SUSPENDED C23 via UI, guard last-admin C24 di perangkat, dan
regresi parser "Penerima" di UI (di-SKIP atas arahan Lead; diverifikasi Lead lewat API UAT).
Dokumentasi UI: `docs/evidence/assets/pm_qa_2026-11-24/ui/` dan `.../ui_split/` (sesi ulang).
**Serah terima emulator:** 5556 diserahkan ke w-ui sejak 09:31 WIB, lalu dikembalikan untuk sesi
ulang 11:07–11:35 WIB dan dilepas kembali setelah walk selesai.

Status gate di atas **tidak dinaikkan** oleh addendum ini; rekomendasi gate ada di dokumen QA
§4 dan keputusan tetap milik Lead/pemilik.

| Butir P0 24 Nov | Status | Bukti | Batas |
|---|---|---|---|
| Navigasi Transaksi satu sumber bulan + kembali ke Ringkasan | **PASS runtime (emulator gabungan)** | Sesi ulang 11:07–11:35 WIB: gejala (c) kembali dari daftar Transaksi ke Ringkasan **direproduksi sebelum** perbaikan lalu **PASS sesudah**; Home↔Transaksi dua putaran; "Lihat semua" konsisten; bulan aktif tampil. Lihat [UAT_PM_UI_2026-11-24.md](../evidence/UAT_PM_UI_2026-11-24.md) + `ui_split/01`–`17` | Varian final lewat **Run Android Studio** pemilik dan drill-down-after dengan data belum dijalankan; gejala (a) tab kosong tidak tereproduksi |
| Merchant Livin = nama `Penerima`, repair tanpa duplikat | **PASS API/unit + PASS runtime perangkat pemilik** | 49 baris November 2026 tanpa duplikat setelah repair 13 baris; label nama usaha, bukan PAN/referensi; 40 baris uncategorized di-enqueue, job `NEEDS_REVIEW` revisi 0 lama tidak memblokir, 8 baris terklasifikasi model (6 food/1 shopping/1 transport) biaya $0.00101235. Detail transaksi perangkat pemilik menampilkan field **Merchant** berisi nama usaha penerima (bukan PAN/referensi) dan daftar menampilkan kategori hasil Azure ("Saran AI"). [UAT_PM_GO_2026-11-24.md](../evidence/UAT_PM_GO_2026-11-24.md) §E, [UAT_PM_OWNER_DEVICE_2026-11-24.md](../evidence/UAT_PM_OWNER_DEVICE_2026-11-24.md) §2–3 | Dedupe re-import antar-perangkat belum terbukti karena klien belum mengirim `sourceRef` di jalur impor; Run Android Studio pemilik belum dijalankan |
