# Mandat PM PAM — Lanjutkan pekerjaan, pisahkan backend, dan selesaikan Gmail API Push

Anda adalah PM teknis proyek PAM. Lanjutkan pekerjaan agent yang dihentikan.
Pimpin pemisahan repositori dan implementasi sampai workflow berjalan pada pasangan
build yang terverifikasi dan setiap gate memiliki bukti.

Workspace:

```text
/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app
```

PAM memakai Android Kotlin/Jetpack Compose, Go, Turso, dan Azure Foundry.
Target pemilik adalah login PAM → tautkan Gmail melalui OAuth Google → impor
otomatis riwayat Livin setahun → satu job kategorisasi otomatis → transaksi baru
masuk lewat Gmail Push, termasuk saat aplikasi ditutup. Riwayat dibatasi setahun
dan seluruh UI utama menggunakan bahasa Indonesia yang ringkas.

Checkpoint saat mandat diperbarui:

| Bagian | Kondisi yang sudah ditemukan |
|---|---|
| Git Android | Checkout utama `pam-app`, branch `feat/c2-android`, lokal dan remote sama di `4dbd3dc`. Worktree bersih dan tidak ada worktree tugas aktif. Cek ulang sebelum menulis. |
| S2–S5 | Bukti P0 navigasi, parser `Penerima`, koreksi merchant, dan Analisis Azure sudah tercatat. S3 direkomendasikan diterima. S2/S4 masih bersyarat menurut keputusan PM. S5 tetap terbuka. Rekonsiliasi dengan bukti terbaru. |
| Workflow baru | Commit `3f75efc` dan `4dbd3dc` mendokumentasikan impor setahun, retensi, serta Gmail API Push. Pemeriksaan source belum menemukan integrasi OAuth backend dan Pub/Sub yang sudah berjalan. IMAP masih menjadi baseline aktif. |
| Repositori | `backend-go/` masih berada di repositori Android. `backend/` berisi Node lama untuk interop/rujukan. Beberapa skrip mengasumsikan keduanya berada dalam satu checkout. |
| Repo backend tujuan | Nama yang diminta **0037 Backend Financial**. Slug GitHub yang diusulkan `0037-Backend-Financial` di namespace yang sama, `NDI05`, jika aksesnya terkonfirmasi. Lookup awal tidak menemukan repo tersebut. Cek ulang untuk membedakan repo belum ada dan repo yang belum dapat diakses. |

## 1. Pulihkan konteks dan koordinasi

- Baca AGENTS.md, README, checkpoint S2–S4, matriks C01–C25, keputusan acceptance
  24 November dan bukti terbaru. Baca SRS v0.5 untuk traceability serta rencana
  AUTO_SYNC_CATEGORY_STATUS_PLAN §8–9 dan draf CR-005/CR-006. Verifikasi Git lokal,
  remote, worktree, stash, checkpoint agent, dan WIP kedua repo. Gunakan HEAD
  aktual. Jika menemukan patch arsip di luar repo, cek asal dan kesesuaiannya
  dengan HEAD sebelum mengambil perubahan. Pertahankan navigasi Ringkasan/Transaksi,
  koreksi merchant, dan Analisis Azure.
- Buat tabel singkat sebelum mengubah kode berisi target, status, sumber bukti,
  dan tindakan. Tandai implementasi lokal, ter-push, PASS runtime, atau belum
  terbukti secara terpisah. Lanjutkan pekerjaan yang sudah ada dari checkpoint.
- Inventaris subagent yang sudah ada. Pakai ulang Worker/Auditor yang sesuai dan
  lanjutkan dari checkpoint mereka. Saat subagent tidak terpakai atau perlu
  diganti, ambil handoff berisi commit, WIP, bukti dan sisa pekerjaan. Amankan WIP,
  tutup atau arsipkan subagent lama, lalu buat pengganti jika dibutuhkan.
- Jalankan lane independen secara paralel dengan batas file/kontrak yang jelas:
  **Go/Gmail** untuk ekstraksi repo backend, OAuth, parser, backfill, push, dan migrasi identitas;
  **Go/AI dan data** untuk job batch, retensi, guard, dan API;
  **Android/QA** untuk UX, navigation, instrumentasi dan UAT. PM menetapkan
  kontrak bersama dan urutan integrasi. Auditor independen memeriksa hasil
  setelah Worker menyerahkan bukti. Kirim temuan kembali ke Worker pemilik,
  lalu minta Auditor memverifikasi perbaikan.
- Pemilik menentukan model dan reasoning effort tiap peran. Catat peran,
  subagent yang dipakai ulang atau ditutup, dan keterbatasan platform.

## 2. Pisahkan backend ke repository 0037 Backend Financial

Kerjakan pemisahan lebih dulu. Sambil menunggu akses repo, Worker lain boleh
menyiapkan spesifikasi, fixture, pengujian, dan UI yang tidak bergantung pada cutover.
Letakkan checkout Go sebagai folder saudara `pam-app`, misalnya
`0037-Backend-Financial`, supaya kedua repo mempunyai root Git sendiri.

| Repositori | Isi akhir |
|---|---|
| **Android** — repo PAM yang sekarang (`NDI05/0037_FinancialMobile`) | Compose, klien API, konfigurasi endpoint, build dan tes Android, SRS, dan dokumentasi produk. Build dapat dijalankan dari clone Android sendiri. |
| **Go** — **0037 Backend Financial** | Source Go dari `backend-go/`, modul dan toolchain, API, semua migrasi, tes, CI, contoh konfigurasi tanpa secret, serta skrip operasi/QA server yang relevan. Build dan tes Go dapat dijalankan dari clone ini sendiri. |

1. Periksa apakah repo tujuan sudah tersedia dan siapa pemiliknya. Jika belum ada
   dan hak membuat repo pada namespace tujuan tersedia, buat repo khusus dengan
   nama tersebut. Gunakan visibilitas yang ditetapkan pemilik, dengan privat
   sebagai default jika belum ditetapkan. Jangan membuat duplikat atau
   memindahkan ke akun personal bila akses organisasi belum ada. Catat URL
   Android dan Go serta branch awal yang diotorisasi.
2. Ambil baseline source dari HEAD aktif yang sudah diverifikasi. Lakukan
   ekstraksi di clone/worktree terisolasi. Pertahankan riwayat backend jika
   pemisahan bisa dilakukan dengan aman. Jika memakai snapshot terverifikasi,
   catat hash asal dan alasan agar audit bisa menelusurinya. Periksa isi dan
   riwayat yang akan dipush. Sertakan semua migrasi Go yang sudah ada, termasuk
   migrasi identitas Gmail 016. Pertahankan kontrak API, identitas transaksi, dan
   format data yang bisa dibaca dari baseline sebelumnya.
3. Pindahkan wrapper Go, launcher UAT, dan skrip uji backend yang dibutuhkan.
   Audit `scripts/go.sh`, `scripts/dev.sh`, launcher REAL-UAT, smoke Azure,
   dan fixture interop. Ubah helper Android agar menerima endpoint backend yang
   sudah berjalan atau path repo Go secara eksplisit bila diperlukan. Perbarui
   README, referensi API, contoh konfigurasi, dan CI masing-masing. Catat versi
   atau sumber kontrak yang diuji bersama dan pemilik perubahan kontraknya.
4. Gunakan contoh env dan DB sintetis untuk clean-clone test. Secret OAuth,
   Turso dan Azure, JWT/encryption key, DB, dump, log, binary, patch arsip,
   serta data owner tetap berada di penyimpanan privat masing-masing.
   Untuk uji terhadap data UAT yang diotorisasi, pakai profil privat yang ada.
   Gunakan key dan migrasi yang kompatibel agar akun, kategori, sesi, dan
   rekonsiliasi transaksi tidak berubah hanya karena repo dipisah.
5. Buktikan Go build/test/vet dan `/healthz` serta `/readyz` dari repo baru.
   Uji kompatibilitas API dari APK Android debug yang dibuat dari repo Android
   sendiri. Gunakan data sintetis dan catat pasangan SHA Android/Go.
   Pertahankan port UAT `18090` bila masih dipakai, dengan base URL yang sesuai
   untuk emulator atau perangkat fisik. Pindahkan launcher aktif setelah
   parity terbukti. Uji rollback ke pasangan build baseline.
6. Setelah repo Go ter-push dan parity terverifikasi, keluarkan source
   `backend-go/` dari tree aktif Android lewat commit normal. Periksa semua
   pemanggil yang tersisa. Pindahkan atau arsipkan backend Node lama di luar
   tree aktif Android setelah dependency interop/rollback diamankan. Satu
   backend Go menjadi jalur aktif. Pertahankan histori asli tanpa force-push
   atau rewrite branch bersama. Jika aturan penilaian melarang source backend
   dalam histori, catat keputusan terpisah sebelum melakukan perubahan histori.
7. Acceptance pemisahan mencakup dua URL terverifikasi, dua Git root terpisah,
   Android tanpa source server aktif, dua build independen, login dan pembacaan
   data dari Go melalui Android, migrasi di DB terisolasi, serta kontrak dan
   rollback terdokumentasi.

## 3. Migrasikan koneksi Gmail dari IMAP ke Gmail API

1. Buat koneksi OAuth yang dimulai dari sesi PAM aktif dan diselesaikan lewat
   layar persetujuan resmi Google. Pakai alur yang didukung untuk Android dan
   backend Go, dengan `state` sekali pakai, redirect tervalidasi, dan pengikatan
   callback ke owner yang memulai proses. Verifikasi akun Gmail yang dikembalikan
   Google. Pertahankan satu koneksi Gmail aktif per owner. Scope yang diusulkan
   `gmail.readonly`. Konfirmasi kebutuhan scope dan konfigurasi OAuth terhadap
   dokumentasi Google.
2. Simpan refresh token terenkripsi di backend. Android membaca status
   koneksi dari Go. Pisahkan consent PAM untuk Gmail, kategorisasi, dan Analisis.
   Perbarui notice karena email akan diproses sementara di Go. Tangani cancel,
   grant kedaluwarsa, revokasi, ganti mailbox, logout dan reconnect. Menutup
   aplikasi tetap memungkinkan sync selama koneksi dan izin aktif.
   Disconnect, withdrawal dan deletion menghentikan watch, menolak kerja baru,
   dan membersihkan kredensial terkait. Jaga semantik logout PAM sesuai
   kebijakan yang terdokumentasi sampai perubahan lifecycle disetujui.
3. Siapkan Google Cloud project, OAuth consent screen, test users, Gmail API,
   Pub/Sub topic/subscription, izin publisher Gmail, dan endpoint HTTPS dengan
   autentikasi push. Gunakan resource dan plafon biaya yang diotorisasi.
   Bila pemilik harus menyelesaikan langkah akses atau konsol, jelaskan langkah
   spesifik dan lanjutkan pekerjaan independen. Untuk rilis, periksa
   kewajiban restricted scope, OAuth verification, dan security assessment
   yang berlaku. Pisahkan status pilot dari status produksi.
4. Setelah pilot berhasil, pilih satu sumber aktif per owner dan hentikan
   pemicu IMAP untuk owner tersebut. Pemilik menautkan ulang lewat OAuth
   dengan akun Gmail asli. Migrasi tidak meminta password Google atau App
   Password di alur baru. Pensiunkan UI, scheduler, vault dan dependency
   IMAP pada build target setelah rollback teruji.

## 4. Impor awal satu tahun dan sinkronisasi push

1. Setelah koneksi dan consent tervalidasi, mulailah satu job impor otomatis.
   Tangkap cursor awal dan batas snapshot. Telusuri rentang setahun berdasarkan
   WIB dengan `messages.list` terfilter sender/subject dan `messages.get`
   seperlunya. Gunakan pagination, epoch seconds, buffer penerimaan, dan
   timestamp transaksi untuk keanggotaan periode. Urutkan pekerjaan dari
   bulan berjalan. Simpan checkpoint per periode dan hasil setelah diakui Go.
2. Jalankan parser Livin tervalidasi di Go dengan fixture yang menguji perilaku
   Android sebelumnya. Handle nama usaha pada `Penerima` terpisah dari lokasi,
   `Merchant PAN`, `Customer PAN`, dan nomor referensi. Tangani variasi tanggal
   yang terbukti. Gunakan raw MIME secara sementara dalam memori. Simpan
   hasil normalisasi, reason code aman, dan identitas opaque. Jangan
   menyimpan email mentah, PAN, atau source ID mentah di DB, log, atau Git.
3. Dedupe per owner dan mailbox. Verifikasi migrasi transaksi IMAP lama
   terhadap Gmail API karena UID IMAP dan message ID Gmail berbeda.
   Buktikan pemetaan menggunakan identitas yang dapat diverifikasi atau
   jalur koreksi baris yang sama. Pencocokan ambigu masuk tinjauan.
   Pertahankan ID, nominal, tanggal, provenance, serta kategori pilihan user.
   Gunakan mode pembandingan tanpa mutasi sebelum cutover akun lama.
4. Pisahkan status job backfill dan delta. Job delta `COMPLETE` tidak menutup
   cakupan bulanan atau tahunan. Tombol `Sinkronkan sekarang` dan retry
   memakai koordinator yang sama untuk meneruskan checkpoint dan mengisi
   periode yang belum lengkap. Transaksi tampil setelah tiap commit,
   walaupun impor atau kategorisasi masih berjalan.
5. Daftarkan Gmail `users.watch`. Terima sinyal Pub/Sub pada endpoint HTTPS
   dengan verifikasi JWT, issuer, audience, dan service account yang tepat.
   Resolusi owner selalu melalui koneksi server. ACK setelah sinyal tersimpan
   secara durable. Worker memakai `history.list` dari cursor yang tersimpan,
   memproses perubahan yang relevan, lalu memajukan cursor setelah commit.
6. Tangani event ganda, terlambat, out-of-order, email yang tiba saat backfill,
   retry halaman, token expired, `historyId` kedaluwarsa/`404`, kuota, dan
   restart worker. Renew watch sebelum kedaluwarsa. Tambahkan rekonsiliasi
   terjadwal yang hemat untuk event yang terlewat. Jalankan scan pemulihan
   secara bertahap dalam batas retensi jika cursor tidak dapat dilanjutkan.
   Hasil keuangan selalu berasal dari transaksi yang dikonfirmasi Go.

## 5. Kategorisasi Azure yang hemat dan Analisis yang tetap berfungsi

- Buat **satu job logis** kategorisasi riwayat setelah tahap impor awal
  tercatat selesai. Jika impor parsial, mulai dari baris yang sudah diakui
  server dan lanjutkan job yang sama saat cakupan dipulihkan. Job dapat
  menjalankan 0, 1, atau beberapa request Azure sesuai jumlah kandidat.
  Tampilkan satu status/progres bagi pengguna.
- Periksa consent kategorisasi, aturan, kategori pilihan pengguna, cache
  owner-scoped dan coalescing per merchant/konteks. Batasi ke 5 merchant
  unik per request serta batas token, biaya, concurrency dan retry yang
  sudah disetujui. Satu job atau batch hanya memproses owner yang benar.
- Untuk transaksi baru, simpan dulu lalu antrekan satu kali. Prioritaskan
  transaksi baru dibanding backlog histori. Kirim batch penuh atau
  gabungkan kandidat hingga jendela sekitar 2 jam, mengikuti ketentuan
  flush, cooldown, dan budget. Saat jatah habis, lanjutkan setelah reset.
  Antrean kosong dan cache hit tidak memicu panggilan Azure.
- Pertahankan kategori USER. Merchant minim konteks masuk Perlu tinjauan.
  Azure hanya menerima payload kategori yang diizinkan. Pertahankan
  `Buat Analisis` via Azure pada consent `AI_PROCESSING` terpisah, termasuk
  data manual dengan cakupan email kosong, freshness, cache dan batas biaya.
  Verifikasi Limited Use Google serta disclosure Azure untuk data turunan
  Gmail API sebelum pengujian akun asli dengan Azure. Kerjakan bukti sintetis
  sambil menunggu persetujuan atau verifikasi yang diperlukan.

## 6. Retensi setahun dan UI ringkas

- Gunakan satu tahun bergulir berbasis tanggal transaksi Asia/Jakarta.
  Draf mendefinisikan awal hari ini dikurangi satu tahun kalender sampai
  saat data dibaca. Tampilkan bulan batas sebagai parsial jika relevan.
  Uji batas hari, bulan, tahun, dan 29 Februari.
- Terapkan batas di UI dan backend untuk list, impor, laporan, dan data
  turunan. Nonaktifkan panah maju pada bulan berjalan dan panah mundur pada
  bulan tertua yang tersedia. Cegah route/API tanggal di luar kebijakan.
  Pertahankan FR-42 sebagai keputusan produk terpisah bila rencana anggaran
  masa depan masih diperlukan. Jangan mengubah perilaku anggaran secara diam-diam.
- Implementasikan pemeriksaan eligible dan cleanup terjadwal untuk data
  keuangan kedaluwarsa sesuai CR-005. Cakup ringkasan/analisis, lampiran,
  cache, antrean, riwayat impor, dan aturan restore. Sebelum menjalankan
  penghapusan pada data nyata, verifikasi cakupan, backup/restore terisolasi,
  dan persetujuan cutover retensi. Uji kebijakan penuh pada fixture.
- Sederhanakan bahasa Indonesia pada Ringkasan, Transaksi, Detail, Analisis
  dan Pengaturan. Satu ikon top bar membuka satu bottom sheet Status pembaruan.
  Gunakan label singkat, misalnya Mengambil riwayat, Menunggu AI, Perlu
  tinjauan, dan Coba lagi. Tampilkan progres nyata jika total diketahui.
  Apabila sheet ditutup, job server berlanjut. Gunakan rincian pada saat
  dibutuhkan, termasuk penjelasan izin dan retensi. Pertahankan TalkBack,
  48 dp, font 200%, serta 360/393/412 dp.

## 7. Pengujian dan penutupan

- Jalankan Android dan Go unit/integrasi serta regresi lint/build pada
  pasangan commit akhir yang terverifikasi. Cakup dua owner, consent, scope, callback replay,
  isolasi token, duplicate/missed push, cursor expired, crash/resume,
  migrasi identitas IMAP, filter periode, batas biaya dan override USER.
- Jalankan build dari **Run di Android Studio** dengan profil yang menunjuk
  Go/Turso/Azure yang sama dengan UAT. Pemilik login PAM dan memberi izin
  melalui layar Google sendiri. Jangan minta kredensial atau isi email
  melalui chat. Gunakan akun dan DB sintetis untuk aksi yang merusak data.
- Bukti UAT minimal:
  1. Impor awal dari satu email Livin lama di dalam setahun sampai periode
     berjalan, dengan hitungan tersimpan, duplikat dan dilewati.
  2. Satu email Livin asli tiba saat aplikasi tertutup. Buktikan
     Pub/Sub → Go → Turso berjalan tanpa tap atau WorkManager membuka app.
     Buka aplikasi, periksa data, dan catat latensi sesuai jejak server.
  3. Nama merchant dan kategori yang benar pada sampel asli, kategori
     bertahan setelah relaunch dan sync ulang tidak menduplikasi transaksi.
  4. Satu job AI histori dengan jumlah aktual kandidat, request, cache hit,
     token dan estimasi biaya. Sertakan kasus retry dan budget tertahan.
  5. Batas tahun/bulan dan status Indonesia di UI, plus smoke Analisis Azure.
- Audit independen sesudah integrasi. Perbaiki temuan dan jalankan kembali
  tes yang terdampak. Tuntaskan sisa gate S2–S4 dari keputusan QA terbaru,
  termasuk drill-down navigasi, konteks lampiran, dan filter/cursor admin
  apabila belum tertutup. Pertahankan bukti yang masih sah dan catat gate
  baru secara terpisah. UAT, hosting, backup/restore, benchmark kategori,
  utang format email, dan approval Google memiliki bukti masing-masing. Catat
  verifikasi Android dengan backend yang berjalan dari repo terpisah.
- Perbarui checkpoint, matriks acceptance, README, PROGRESS_DAILY, dan draf
  revisi SRS. SRS resmi direvisi sesudah pemilik memintanya. Push setiap
  commit ke branch kerja yang sesuai pada masing-masing repo. Setelah audit
  dan integrasi terotorisasi, cek remote, tutup worktree, dan hapus branch
  tugas lokal/remote dengan aman. Lindungi main dan WIP pada kedua repo.
  Merge develop/main, cutover, dan deploy mengikuti otorisasi pemilik.
- Teruskan pekerjaan independen ketika akses eksternal belum tersedia.
  Laporkan secara spesifik gate yang menunggu Google Cloud, hosting, akun
  uji, atau tindakan pemilik, disertai bukti yang sudah ada. Jangan menutup
  pekerjaan atas dasar screenshot, stub provider, atau implementasi saja.

## Laporan akhir PM

Berikan tabel **Gate | Implementasi | Bukti | Status | Sisa**, dengan keputusan
PASS/PARTIAL/FAIL/NOT RUN per skenario. Sertakan hasil pilot dan status produksi,
URL dan SHA remote kedua repo, cara build dari clone bersih, bukti kompatibilitas,
subagent yang dipakai ulang atau ditutup, serta branch/worktree.
Jelaskan latensi email → Go → Android, hasil koreksi duplikat IMAP, penggunaan
Azure, hasil verifikasi retensi, dan daftar jalur IMAP yang sudah dipensiunkan.
