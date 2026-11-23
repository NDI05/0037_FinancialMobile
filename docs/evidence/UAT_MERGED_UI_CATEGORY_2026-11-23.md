# UAT merged HEAD — revamp UI sinkronisasi + kategori Livin (23 November 2026)

Bukti PM atas hasil penggabungan dua lane (UI revamp `9aeea28`, kategori `dda4084`) plus
perbaikan i18n pasca-merge `a310067` di branch kerja `feat/c2-android`. Semua pengujian di
bawah dijalankan pada **APK hasil merged HEAD `a310067`**, bukan APK lane.

Konteks lingkungan yang dipakai (sama seperti UAT owner):

- Backend UAT Android Studio: port **18090**, PID 58125, **tidak di-restart** selama sesi;
  profil `LLM_PROVIDER=openai` + `LLM_BASE_URL=http://127.0.0.1:11434/v1`,
  `CATEGORY_LLM_PROVIDER=azure`, `AZURE_CLASSIFIER_MODEL=gpt-oss-120b`.
- `GET /readyz` dari emulator: **HTTP 200** (satu flap 503 sesaat tercatat di sesi ini,
  permintaan berikutnya 200).
- `BuildConfig.PAM_BACKEND_BASE_URL` pada APK final: **`http://10.0.2.2:18090`**
  (diverifikasi dari `app/build/generated/source/buildConfig/debug/com/pam/recap/BuildConfig.java`
  dan dari request yang benar-benar tiba di log backend 18090).
- Dua emulator: `emulator-5556` akun UAT sintetis (semua tangkapan layar di lampiran berasal
  dari perangkat ini), dan `emulator-5554` sesi owner (data sesi dipertahankan; **tidak ada
  tangkapan layar owner yang disimpan ke repo**, hanya angka agregat).
- APK uji: debug sha256 `0e9169f285e57c47c630780ebf9919609ddb56697b09ace3a6fb47cf4c022c94`,
  assessment sha256 `dea936b8f05ee1acab64d99137af8c91baba5e88dc2eab7f97b15c4011dad9e1`.

## 1. Gate build/test pada merged HEAD `a310067`

| Gate | Hasil | Catatan |
|---|---|---|
| `:app:testDebugUnitTest --rerun-tasks` | **PASS** 509 tes / 0 gagal / 0 skip (70 suite) | Percobaan pertama gagal 1 tes flaky `AutoCategoryUseCaseTest > concurrent callers cannot start two drains` (expected 1, was 2); kelas itu lulus **3/3** berdiri sendiri dan suite penuh lulus pada rerun. Dilaporkan apa adanya sebagai flake, bukan perbaikan kode. |
| `:app:testAssessmentUnitTest --rerun-tasks` | **PASS** 519 tes / 0 gagal / 0 skip (71 suite) | — |
| `:app:lintDebug --rerun-tasks` | **PASS** 0 error, 112 warning, 1 hint | Sebelum `a310067` gate ini **gagal** dengan 7 `MissingTranslation` (regresi merge-only). |
| `:app:lintAssessment --rerun-tasks` | **PASS** 0 error, 120 warning, 1 hint | Penyebab kegagalan sama dengan di atas, sudah diperbaiki. |
| `:app:assembleDebug` (`-PPAM_BACKEND_BASE_URL=http://10.0.2.2:18090`) | **PASS** | sha256 di atas |
| `:app:assembleAssessment` | **PASS** | sha256 di atas |
| Go `test -count=1 ./...` / `go vet` / `go build` | **PASS** | 7 paket, 0 gagal (dijalankan auditor pada `dda4084` + PM pada merged head) |
| Go `test -race -timeout 240s ./internal/store ./internal/httpapi` | **PASS** | store 19,8 s, httpapi 187,6 s; gate ini sempat perlu rerun ber-timeout setelah percobaan pertama digantung manual — dicatat sebagai catatan proses |

## 2. Ringkasan bersih (dua perangkat, APK merged)

- Tidak ada banner "Tidak ada email Livin…", CTA impor, kartu "Sumber data", maupun ikon
  gear di top bar. Grep dump UI: **0** kemunculan. Pengaturan tetap dapat dicapai dari tab
  navigasi bawah (diperiksa di perangkat).
- Keadaan bulan kosong yang jujur tetap dirender (bukan dihapus): "Dari email / Kosong",
  "Tidak berlaku", "0%", "terbesar" pada bulan tanpa data.
- Lampiran: [ringkasan.png](assets/merged_ui_category_2026-11-23/ringkasan.png),
  [ringkasan.xml](assets/merged_ui_category_2026-11-23/ringkasan.xml).

## 3. Judul top app bar terpusat

Diukur dari tangkapan layar dengan mendeteksi kolom piksel teks gelap pada baris judul,
bukan dari atribut layout:

| Konfigurasi | Pusat glyph | Pusat layar | Selisih |
|---|---|---|---|
| Ikhtisar, 393 dp (default 1080×1920 @420 dpi) | 540,0 px | 540,0 px | **0 px** |
| Ikhtisar, 360 dp + font 200% (density 480) | 540,5 px | 540,0 px | **±0,5 px** |

Bukti: [ringkasan.png](assets/merged_ui_category_2026-11-23/ringkasan.png),
[ringkasan_360dp_font200.png](assets/merged_ui_category_2026-11-23/ringkasan_360dp_font200.png),
[ringkasan_360dp_font200.xml](assets/merged_ui_category_2026-11-23/ringkasan_360dp_font200.xml).
Varian perangkat 412 dp dan baris "back + judul panjang" tetap **PARTIAL**: bukti 412 dp /
judul panjang hanya dari uji Robolectric (belum ada tangkapan perangkat pada sesi ini).

## 4. Ikon status top bar

- Target sentuh pada 393 dp: bounds `[912,84][1038,210]` = **126 px = 48 dp**; pada 360 dp /
  480 dpi: `[888,96][1032,240]` = **144 px = 48 dp**.
- `content-desc` dinamis: "Buka status pembaruan: Perlu perhatian. Ada hal yang perlu
  perhatian." → status tetap terbaca TalkBack dalam bentuk ringkas. Label visual fade-in
  tidak dapat diverifikasi dari dump/screenshot diam; **PARTIAL** (uji unit animasi lulus,
  bingkai animasi live tidak tertangkap).

## 5. Sheet "Status pembaruan" (satu sheet, tanpa impor saat dibuka)

Perangkat sintetis `emulator-5556`, APK merged:

| Langkah | Hasil | Bukti |
|---|---|---|
| Buka sheet dari ikon status | Jumlah baris log backend **263 → 263** (tidak ada request impor/Azure saat membuka) | [sheet_idle.png](assets/merged_ui_category_2026-11-23/sheet_idle.png), [sheet_idle.xml](assets/merged_ui_category_2026-11-23/sheet_idle.xml) |
| Keadaan idle | "Periode: November 2026", "Percobaan terakhir: 3 menit lalu", "Terakhir berhasil sinkron: belum pernah", tombol "Sinkronkan sekarang", bagian Data transaksi/Kategori/Perlu perhatian | idem |
| Tekan "Sinkronkan sekarang" | Tepat **satu** `POST /v1/import-jobs` **201** + polling `GET /v1/import-jobs/{id}` (tidak ada job ganda) | log backend 18090 baris 264–266 |
| Tutup sheet → buka lagi | Hasil percobaan yang **sama** tampil: "Percobaan terakhir: baru saja", "Email cocok pada impor ini: 0", "Tersimpan 0, duplikat 0, dilewati 0, belum terbaca 0", alasan partial "Rentang email belum selesai diproses…" | [sheet_reopened.png](assets/merged_ui_category_2026-11-23/sheet_reopened.png), [sheet_reopened.xml](assets/merged_ui_category_2026-11-23/sheet_reopened.xml) |
| Minimize (Home) → buka lagi aplikasi → buka sheet | Hasil sama ("baru saja", hitungan nol, alasan partial); tidak ada job baru, log tidak bertambah | [sheet_minimized_relaunch.png](assets/merged_ui_category_2026-11-23/sheet_minimized_relaunch.png), [sheet_minimized_relaunch.xml](assets/merged_ui_category_2026-11-23/sheet_minimized_relaunch.xml) |

Bingkai "sedang berjalan" tidak tertangkap karena job sintetis selesai < 2 detik; keadaan
running + progress bar sudah diverifikasi lewat uji unit/dump worker (`running` dengan stage
dan indikator indeterminate). **PARTIAL** untuk bingkai live.

Perangkat owner `emulator-5554` (tanpa menghapus data sesi):

- Membuka sheet: log backend **278 → 278** (nol request impor/Azure).
- Idle: periode November 2026, "Percobaan terakhir: baru saja", "Terakhir berhasil sinkron:
  belum pernah", "Tersimpan di perangkat, belum diakui server.", tombol sinkron, alasan gagal
  "Pembaruan terakhir gagal. Coba lagi nanti."
- Tutup → buka lagi: keadaan **identik**, tanpa request baru. FAILURE + tombol "Coba lagi"
  terverifikasi; alur resume unggahan pending secara live tetap **NOT RUN** (tidak ada
  unggahan pending yang bisa dipicu ulang tanpa menulis data owner).

## 6. Kategori Livin di perangkat owner (APK merged, backend 18090 lama)

Aksi "Kategorikan dengan AI" ditekan **satu kali** di perangkat owner:

- Hasil perangkat: **`classified=0 needsReview=5 failed=0`** (angka agregat; tidak ada nama
  merchant yang ditulis di dokumen ini).
- Alasan tampil di Android: "Perlu tinjauan: nama merchant tidak aman dikirim ke AI. Pilih
  kategori secara manual." — artinya Android sekarang menampilkan alasan dari API.
- Request baru di log backend: `POST /v1/category-jobs` + `GET /v1/transactions` saja;
  **0** panggilan provider Azure (grep `azure` seluruh log = 0). Ini konsisten dengan
  temuan lane: label merchant numerik ditolak sanitasi sebelum provider.
- Detail transaksi: chip "Perlu tinjauan" tampil; **notis nomor referensi tidak teramati**
  pada record yang dibuka (layout detail tidak merender label merchant numerik untuk record
  itu; logika notis tercakup uji unit). Bats verifikasi: **PARTIAL**.
- Setelah restart aplikasi: daftar 47 transaksi termuat ulang, keadaan "belum berkategori"
  tetap, tetapi banner alasan hasil kategorisasi **tidak** bertahan (ia state sesi aksi,
  bukan data tersimpan). Job NEEDS_REVIEW tetap di server. Bats: **PARTIAL**.
- Kategori terklasifikasi yang terlihat di daftar+detail dan bertahan restart **NOT RUN**:
  data owner tidak punya label merchant yang dapat diklasifikasi, jadi tidak ada kategori
  hasil AI untuk dibuktikan; ≥95% precision juga **NOT RUN** (tidak ada benchmark
  berlabel manusia).

## 7. "Buat Analisis" sebagai gate terpisah

Pada build UAT ini `PAM_ANALYSIS_BACKEND_URL` kosong, sehingga aplikasi memakai **mesin
analisis deterministik di perangkat** (layar menyatakan "Penyedia: mesin analisis
deterministik di perangkat (tanpa jaringan)") dan bulan uji menampilkan "Belum ada data untuk
bulan ini.". Jalur provider **Ollama lewat backend tidak diekspos build ini**.

Status: **NOT RUN** untuk kombinasi yang diminta (provider Ollama + bulan dengan cakupan
EMPTY yang berisi transaksi manual). Tidak ada klaim lulus.

## 8. Route impor lama

Diff merge `dda4084` vs `5451db9` hanya mengubah `strings.xml` (13 tambah / 27 hapus);
destination, layar, dan shortcut impor hilang dari tree dan grep repo bersih (diverifikasi
auditor). Uji probe deep-link ke route lama: **NOT RUN** (tidak ada deep link yang
mengarah ke sana; dibuktikan secara konstruksi + grep, bukan dengan percobaan runtime).

## 9. Kesimpulan bukti

**PASS runtime perangkat** pada APK merged: Ringkasan bersih; judul terpusat (393 dp & 360 dp
font 200%); ikon status 48 dp; sheet tunggal yang tidak memicu impor; satu job impor untuk
satu tap; hasil sama setelah tutup/buka dan minimize/relaunch; alasan kategori tampil di
Android; nol panggilan Azure untuk label yang tidak aman.

**PARTIAL**: bingkai running + progress live, fade label live, TalkBack live, notis nomor
referensi di detail owner, persistensi banner alasan setelah restart, baris back+judul
panjang di perangkat, resume unggahan pending live.

**NOT RUN**: kategori AI terklasifikasi dengan data owner (tidak ada sampel), precision
≥95% (tanpa benchmark berlabel), "Buat Analisis" via Ollama dengan bulan EMPTY-coverage,
probe deep-link route impor lama.
