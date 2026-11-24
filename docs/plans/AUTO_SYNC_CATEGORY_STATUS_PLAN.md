# Rencana sinkronisasi otomatis, batch kategori, dan status PAM

Tanggal keputusan: **23 November 2026**.
Status **24 November 2026**: polling Gmail sudah diuji dengan fixture, termasuk pada
HP fisik. UAT Gmail live dan gate GCP Rp0 masih terbuka. Google SSO dan onboarding
Gmail terpadu disetujui untuk dirancang di §10. Implementasi dan UAT SSO masih direncanakan.

Draf konsolidasi SRS: [CR-005](../changes/CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md) dan
[CR-007](../changes/CR_007_DRAFT_GMAIL_API_YEARLY_WORKFLOW.md).
Baseline formal tetap SRS v0.5. Rencana ini menjadi rujukan aktif untuk scope baru;
penundaan background sync pada roadmap v0.3 adalah historis.

## 1. Keputusan dan batas scope

- Pengguna tidak perlu menekan Impor untuk pembaruan rutin setelah login, koneksi Gmail
  dan consent yang sesuai tersedia. Impor awal mencakup riwayat setahun sesuai addendum
  §8. Tombol pembaruan manual tetap sarana recovery opsional.
- Go menjalankan **polling Gmail API read-only setiap 15 menit** pada jalur utama
  selama backend hidup. Pub/Sub tersedia sebagai opt-in rollback sesuai §9 dan CR-007.
  Ketentuan IMAP, WorkManager, dan interval 30 menit dalam bagian historis mendokumentasikan
  baseline sebelum cutover.
- Kategorisasi rutin melalui antrean backend dengan target pemrosesan **sekitar 2 jam**,
  maksimal **5 merchant unik berkonteks sama per request**. Impor/penyimpanan finansial
  tidak menunggu model. Rules, pemetaan terkonfirmasi, dan cache diperiksa lebih dahulu.
- Gunakan komponen status bersama di top bar dan panel rincian. Loading hanya muncul
  ketika pekerjaan benar-benar berjalan, bukan sepanjang menunggu jadwal berikutnya.
- OAuth, kesiapan backend, dan kebijakan Google menjadi gate untuk pilot dan rilis
  sesuai §9–10. Profil pilot Rp0 memakai polling tanpa layanan GCP berbayar.

## 2. Dependency dan urutan delivery

| Paket | Penempatan | Hasil yang harus dibuktikan | Status awal |
|---|---|---|---|
| P0 | Gate classifier S2 existing | Settlement idempotent, biaya/token guard, lease/recovery, owner/consent/revision guards, coalescing, Android E2E sesuai mandat terbaru | Ikuti evidence SE aktual; tidak dinilai ulang oleh rencana ini |
| P1 | Perluasan S2, sesudah P0 | Incremental IMAP, cursor/journal durable, commit/upload aman, single-flight | PLANNED |
| P2 | Perluasan S2, sesudah P1 | Foreground freshness check dan WorkManager target 30 menit | PLANNED |
| P3 | Perluasan S2, sesudah P0/P1 | Enqueue otomatis kandidat eligible; scheduler kategori target 2 jam, bounded batch dan backlog | PLANNED |
| P4 | Perluasan S2, terintegrasi P1–P3 | Top bar status dan bottom sheet dengan sumber status nyata | PLANNED |
| P5 | Gate S2 + visual S4; perangkat/S5 | Acceptance sintetis, visual/accessibility; observasi perangkat/background/biaya | NOT RUN |

SE menyelesaikan mandat runtime aktif lebih dahulu. Rencana ini tidak menyuruh agent lain
menyela patch aktif, memulai scheduler, atau mengubah SRS sekarang. S3/S4 existing tidak
dihapus; penambahan scope perlu tercermin dalam estimasi berikutnya. Tidak menetapkan
tanggal selesai atau menyebut sprint selesai berdasarkan dokumen ini.

## 3. Incremental sync dan penghematan resource

1. First sync/backfill menggunakan alur bounded yang jelas. Run rutin mengambil delta,
   bukan memindai dan mengunduh seluruh bulan berulang kali. Tetap ambil email baru yang
   tanggal transaksinya masuk bulan sebelumnya; routing bulan berdasarkan parser sah.
2. Checkpoint per owner/mailbox mencakup UIDVALIDITY dan posisi pemrosesan UID yang aman.
   Rancangan penyimpanan cursor harus direview terhadap larangan source message ID pada
   SRS: operational cursor hanya privat di perangkat, bukan ID per-email pada transaksi,
   API, log atau analytics. Jika baseline tidak mengizinkan metadata itu, finalisasi
   rancangan privasi/migrasi sebelum implementasi; draf SRS belum mengubah izin tersebut.
3. Majukan posisi sesudah hasil tersimpan durable atau ada journal retry/skipped reason
   yang menjamin replay. Gagal fetch/parse/upload tidak boleh hilang karena high-water
   mark sudah maju. Raw MIME tidak disimpan sebagai journal.
4. Gunakan fingerprint dan idempotency upload server. Tangani UIDVALIDITY berubah,
   email datang terlambat, disconnect, restart, parser version/reprocess, partial batch,
   pagination dan recovery tanpa impor ganda. Backfill/reconciliation eksplisit dan bounded.
5. Satu pekerjaan per owner/mailbox: foreground, background, impor awal, dan Impor manual
   melewati koordinasi yang sama. Scan riwayat hanya untuk impor awal atau pemulihan cakupan.
   Sinkronisasi rutin menggunakan delta dan koneksi IMAP yang dibatasi durasi.
6. Worker memerlukan jaringan; lakukan backoff pada gangguan, bukan retry rapat. Jangan
   mensyaratkan Wi-Fi/charging pada semua sync kecil sehingga data rutin selalu tertunda.
   Backfill besar dapat memiliki kebijakan resource terpisah.
7. Logout membatalkan pekerjaan dan akses transport akun tersebut. Credential tetap
   owner-scoped untuk reconnect akun yang sama sesuai CR-003. Disconnect/withdrawal/
   deletion menginvalidasi pekerjaan terkait; late result tidak masuk ke owner baru.

Parameter kandidat teknis untuk validasi: foreground cooldown **5 menit**, initial delay
dan jitter scheduler yang mencegah herd. Nilai ini belum keputusan SLA final.

## 4. Antrean kategori dan batas biaya

- Setelah commit finansial server, enqueue kandidat eligible secara idempotent apabila
  AI_CATEGORIZATION masih GRANTED dan auto-categorization aktif. Jangan memakai consent
  Gmail/aggregate sebagai izin Azure. Run rutin menargetkan transaksi baru. Satu job
  klasifikasi riwayat saat impor awal mengikuti cakupan dan consent pada §8.
  Klasifikasi ulang histori sesudahnya tetap aksi eksplisit.
- Auto-categorization harus dijelaskan sebagai pemrosesan berkala tanpa tap; grant lama
  tidak diam-diam diubah menjadi opt-in otomatis jika notice sebelumnya hanya manual.
- Kelompokkan merchant/direction/method/taxonomy/model/prompt yang relevan per owner.
  USER override dan pemetaan terkonfirmasi menang; cache/coalescing mengurangi panggilan.
  Tidak menyamakan semua transaksi dengan QRIS dan tidak mengirim personal counterparty.
- Scheduler backend memeriksa antrean target setiap 2 jam, menghabiskan batch penuh
  dalam jatah yang tersedia. Sisa kecil dibawa ke run berikutnya; kandidat batas usia
  **4 jam** mengizinkan flush parsial saat layanan sehat, consent/budget tersedia.
  Ini target best effort yang perlu diuji, bukan janji selesai 4 jam pada error/offline.
- Tetap enforce batas pilot classifier global USD0,10/hari, USD2/bulan, owner 20 attempts/
  hari, installation 100 attempts/hari, input 1.024/completion 512 sesuai metode token
  yang tervalidasi, concurrency 2, timeout 20 detik, maksimal satu retry transient.
  Semua retries/automatic/manual requests berbagi gate/counter yang sama.
- Foundry USD10/bulan adalah lapisan luar, tidak menaikkan budget runtime. Jangan
  bergantung pada dukungan cost-control GPT-OSS yang belum dibuktikan.
- Jangan panggil provider pada antrean kosong, sekadar refresh/poll UI, atau untuk
  mengecek apakah sudah saatnya menjalankan batch. Pending/review/budget-exhausted
  tidak menghilangkan transaksi; beri status dan waktu reset yang sah.
- Contoh 80 merchant baru ≈16 requests hanya jika seluruh batch penuh. Flush kecil,
  token-driven split, retries dan distribusi waktu dapat menambah requests. Cap harian
  tetap berlaku; backlog dilanjutkan, bukan dinaikkan jatahnya secara otomatis.
- Backend perlu tersedia dan reachable agar jadwal kategori berjalan. Laptop lokal
  dengan port 18090 adalah lingkungan uji, bukan jaminan scheduler 24/7. Hosting/deploy
  dan endpoint perangkat nyata merupakan dependency release, bukan otorisasi deploy baru.

## 5. UX top bar dan panel status

Komponen bersama pada halaman utama (Ringkasan/Transaksi/Analisis/Pengaturan sesuai
layout R1), sebelum avatar bila ruang cukup. Satu indikator ringkas, bukan deretan
logo Gmail/Azure. Ikon dapat berubah sesuai proses; label singkat mencegah ambiguitas.
Ketuk membuka bottom sheet **Status pembaruan**, tanpa memicu pekerjaan baru.

| Keadaan nyata | Indikator/label usulan | Detail yang tersedia |
|---|---|---|
| Memeriksa mailbox | Spinner kecil · Memeriksa | Pemeriksaan email Livin baru |
| Mengambil/mem-parsing | Spinner · Mengambil | Progres nyata jika diketahui; jumlah diproses tanpa persen palsu |
| Upload belum diakui server | Spinner · Menyimpan | Bedakan local pending dari server committed |
| Sync server committed | Cloud centang · waktu | Terakhir berhasil sinkron; bukan klaim semua email terbaca |
| Kategori menunggu batch | Jam · jumlah menunggu | Jadwal perkiraan, umur antrean, alasan penundaan |
| Provider sedang berjalan | Spinner · Mengategorikan | Jumlah job/batch aktual |
| Offline/pause | Cloud terputus/pause · Offline/Dijeda | Data tersimpan tetap terlihat; alasan dan recovery |
| Auth/consent/error/partial | Peringatan · Perlu perhatian | Penyebab aman dan aksi yang tepat |
| Budget classifier habis | Jam/pause · Kategori ditunda | Transaksi sudah tersimpan; batas dan reset sah |

Panel memisahkan **Data transaksi**, **Kategori**, dan **Perlu perhatian**. Simpan last
attempt dan last successful server sync secara terpisah; waktu terakhir diperiksa
bukan waktu seluruh upload berhasil. Counts membedakan matched/saved/duplicate/skipped/
unresolved dan belum berkategori/perlu review. Angka dan status tidak hardcode.

Prioritas ringkasan: masalah yang perlu tindakan > aktivitas berjalan > antrean menunggu
> idle. Detail tetap menunjukkan dua pipeline jika berjalan bersamaan. Error kategori
tidak diberi label gagal mengambil Gmail. Warning parsial/parser tidak disembunyikan
oleh centang sukses upload; misalnya “Data diperbarui · 2 email perlu perhatian”.

Loading: spinner hanya active work; scheduled wait memakai ikon statis. Data lama tetap
terlihat saat refresh, first load dapat memakai skeleton lokal. Determinate progress
hanya bila denominator sah; total mailbox yang belum diketahui tidak diberi persen.
Tidak ada overlay blocking untuk background sync, spinner abadi, atau fake completion.

Pertahankan canvas/primary/Manrope R1. Ikon visual kandidat 20–24 dp dengan touch target
minimal 48 dp, teks/semantik TalkBack, kontras yang sesuai, dukungan 200% font dan
reduced motion. Jangan mengandalkan warna saja. Hindari status bergeser/terpotong pada
360/393/412 dp; varian ikon-only harus tetap punya label aksesibilitas dan panel detail.

Jadwal ditulis “sekitar”/“menunggu kondisi perangkat”, tidak countdown pasti. Status
berasal dari durable journal/worker/server job, bukan timer animasi. Pengambilan status
backend foreground harus bounded, backoff dan berhenti saat layar/background tidak
memerlukan polling; membuka panel tidak menambah request Azure.

## 6. Acceptance sebelum dinyatakan tersedia

| ID rencana | Bukti yang diperlukan |
|---|---|
| AS-01 | Email fixture baru → otomatis tersimpan server → UI tanpa tap Impor |
| AS-02 | Run ulang tanpa pesan baru: tidak fetch body lama, tidak create ulang, nol LLM calls |
| AS-03 | Foreground/background/manual overlap: satu eksekusi per owner/mailbox |
| AS-04 | Restart sebelum/sesudah commit/upload, UIDVALIDITY berubah, late email dan month rollover tidak kehilangan/menggandakan transaksi |
| AS-05 | Offline, backoff, Doze/battery saver, force-stop/reopen dan backend unavailable memberi status jujur dan catch-up |
| AS-06 | Logout/owner switch/disconnect/withdrawal/deletion mencegah late result dan cross-owner access |
| BC-01 | Jadwal 2 jam memakai fake clock; batch penuh/remainder/age flush/empty queue benar |
| BC-02 | Fixture 80 transaksi (merchant berulang dan unik): calls/usage/cache/retries/backlog sesuai batas tanpa klaim selalu 16 calls |
| BC-03 | Dua owner, manual+auto, concurrency/restart/race tidak melewati budget/attempt/token cap |
| UX-01 | Semua state top bar/panel, prioritas paralel, partial/skipped, server acknowledgement dan last success benar |
| UX-02 | Ketuk panel hanya membaca status; waiting tidak beranimasi; failure kategori tidak menutupi transaksi |
| UX-03 | 360/393/412 dp, font 200%, TalkBack, kontras, reduced motion, safe areas |
| OBS-01 | Catat durasi, wakeups/sync runs, bytes, fetched/parsed messages, calls/tokens/estimated cost dan backlog tanpa data pribadi |

Uji scheduler dengan fake clock/stub untuk percepatan; jangan menunggu berjam-jam atau
memboroskan provider. Uji destructive/fault injection hanya fixture/database terisolasi.
Ukur perangkat nyata pada gate S5; tidak menjanjikan persentase baterai, biaya atau
latensi tanpa pengukuran. S5 tetap memerlukan kesiapan backend/perangkat dan owner UAT.

## 7. Referensi riset UI dan platform

- [Android WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): interval periodic minimal 15 menit; eksekusi tidak presisi.
- [Progress Compose](https://developer.android.com/develop/ui/compose/components/progress): determinate/indeterminate sesuai bukti progres.
- [Android accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility): label dan target sentuh minimal 48 dp.
- [Hamina Sync Indicator](https://docs.hamina.com/hamina/onsite/site-surveys/syncronizing-surveys): ikon top bar untuk upload/download/done.
- [Genius Scan](https://help.geniusscan.com/exporting-and-sharing/export-destination-and-shortcuts/verify-if-your-documents-have-been-backed-up): indikator atas membuka detail status.
- [Dropbox](https://help.dropbox.com/sync/check-sync-status): status terpisah syncing/paused/up-to-date/issues.
- [Gmail Push](https://developers.google.com/workspace/gmail/api/guides/push): referensi arsitektur target di §9.

## 8. Addendum 24 November 2026 — workflow setahun

Status **draf delivery**. Gunakan batas bisnis di bagian ini bersama transport Gmail API
di §9. Gate S2–S4 yang sedang ditutup tetap mengikuti
[keputusan acceptance PM](../delivery/GATE_DECISIONS_S2_S4_2026-11-24.md). Masukkan
workflow baru ke tindak lanjut setelah baseline stabil, dan prioritaskan cacat penandaan
cakupan delta sebelum mengaktifkan impor tahunan. SRS v0.5 dijadikan rujukan untuk
mencatat dampak. Rumusan calon requirement ada di [CR-005](../changes/CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md)
dan [CR-006](../changes/CR_006_DRAFT_SYNC_UI_CATEGORY_REVIEW.md).

### Login, impor awal, dan rekap kategori

1. Setelah PAM menerima sesi owner, validasi koneksi Gmail dan izin `GMAIL_READ`.
   Jika impor awal belum selesai untuk koneksi tersebut, mulai satu job otomatis.
   Login ulang ke akun dan mailbox yang sama melanjutkan checkpoint yang tersimpan.
2. Tangkap batas waktu dan watermark awal. Baca notifikasi Livin yang diizinkan
   per bulan, utamakan bulan berjalan, cek waktu transaksi di WIB, dan unggah
   bertahap. Simpan rentang, halaman, hasil, dan konfirmasi Go sehingga pekerjaan bisa
   dilanjutkan setelah restart. Email yang tiba selama backfill masuk delta
   berikutnya. Proses riwayat dan delta berbagi koordinasi yang sama dan dapat
   bergantian di batas chunk agar transaksi baru tetap bisa diproses.
3. Tampilkan transaksi segera setelah tiap commit diakui Go. Tandai cakupan
   bulan berjalan atau bulan lama dengan status riil. Jika impor tertahan,
   lanjutkan dari checkpoint. `COMPLETE` pada delta hanya menutup rentang delta
   tersebut dan tidak boleh menutup kelengkapan satu bulan atau satu tahun. Jika
   status historis tidak punya mode/rentang, anggap cakupan bulanan belum terbukti.
   Tombol sinkronisasi dapat mengisi periode yang belum lengkap.
4. Setelah impor awal punya hasil akhir yang tercatat, sistem otomatis mengantrekan
   **satu job kategorisasi riwayat** apabila consent AI yang mencakup pemrosesan
   histori aktif. Jika selesai parsial, hanya kategorikan baris yang sudah diakui Go
   dan teruskan job yang sama saat data tambahan tersedia.
5. Go mengecualikan pilihan pengguna, memakai aturan dan cache, lalu menggabungkan
   kandidat per owner berdasarkan merchant dan konteks kategori. Azure hanya
   menerima kandidat yang masih memerlukan model. Satu job tahunan dipecah sesuai
   batas 5 merchant unik, batas input 1.024 token, kuota, dan plafon biaya. Satu
   panggilan cukup bila sisa kandidat muat. Nol panggilan dimungkinkan jika seluruh
   hasil tersedia dari aturan/cache. Status transaksi dan status AI tampil terpisah.
6. Baris Livin lama dengan label PAN memakai koreksi identitas terverifikasi yang
   sudah ada sebagai jalur rekonsiliasi. Verifikasi reimpor setelah koreksi secara
   khusus agar perubahan nama merchant tidak menciptakan baris baru.

### Transaksi baru dan penghematan request

- Sebelum cutover, baseline IMAP memakai pemeriksaan foreground dan WorkManager dengan
  target 30 menit untuk varian standar. Setelah cutover, Go mengambil delta dari Gmail
  setiap 15 menit pada mode polling. Ukur jeda dari email diterima sampai transaksi
  tersimpan, lalu sampai transaksi terlihat di aplikasi.
- Simpan setiap transaksi yang valid segera setelah diakui Go. Antrekan kandidat
  AI satu kali. Untuk draft ini, kirim kelompok yang sudah mencapai batas
  merchant/token jika anggaran tersedia, atau kumpulkan batch hingga jendela
  sekitar 2 jam jatuh tempo. Pertahankan target flush sisa berdasarkan usia seperti
  §4 dan ukur dampaknya. Prioritaskan transaksi baru di atas histori. Cooldown,
  single-flight, dan limiter tetap berlaku ketika antrean penuh.
- Contoh perhitungan: 80 transaksi dengan 11 kombinasi merchant/konteks yang
  berbeda dan belum ada di cache memerlukan paling sedikit 3 panggilan bila semuanya
  memenuhi syarat dan muat per batch. 80 kombinasi unik memerlukan paling sedikit 16
  panggilan dalam kondisi yang sama. Split token, timeout/retry, dan batch terpisah
  menurut waktu dapat mengubah jumlah aktual. Backlog dilanjutkan ketika jatah
  pulih, sementara transaksi tetap bisa dilihat dan dikoreksi.
- Saat aplikasi tertutup, backend tetap memeriksa `history.list` selama proses Go hidup.
  Ukur keterlambatan aktual dengan akun pilot. [Sinkronisasi Gmail](https://developers.google.com/workspace/gmail/api/guides/sync)

### Urutan kerja dan bukti penerimaan

| Urutan | Pekerjaan delivery | Bukti penerimaan |
|---|---|---|
| 1 | Simpan mode/rentang/konfirmasi per bulan. Perbaiki short-circuit scan bulanan. | Job delta `COMPLETE` tetap memicu pengisian riwayat bulan yang belum lengkap. Uji juga data lintas batas bulan dan akun yang punya job lama tanpa rentang. |
| 2 | Impor awal setahun, per bulan, dengan checkpoint dan delta yang memakai satu koordinator. | Fixture lintas periode, email pertama dan terakhir di dalam jendela, email di luar batas, retry setelah restart/timeout dan email baru selama backfill. Tidak ada kehilangan data atau transaksi ganda. |
| 3 | Jalankan satu job kategori histori, lanjutkan batch rutin hemat. | Uji 0 kandidat, 1 batch, >1 batch, merchant berulang, cache hit, consent dicabut, anggaran habis, serta hasil provider yang tiba setelah transaksi kedaluwarsa. Buktikan transaksi terlihat sebelum AI selesai dan ukur calls/token aktual. |
| 4 | Terapkan batas periode di UI dan API serta rancang cleanup retensi. | Uji kedua ujung periode, rollover WIB/leap year, query/API langsung, data lama dan hasil agregat, dan restore tanpa memunculkan data kedaluwarsa. Aktifkan cleanup setelah aturan retensi dan backup difinalisasi. |
| 5 | Sederhanakan UI Indonesia dan uji UAT dari Android Studio. | Pesan singkat, satu status sheet, filter periode, aksesibilitas, dan bukti dari satu perangkat. Setelah pengujian sintetis lulus, validasi sampel email asli dengan persetujuan pemilik. |

## 9. Addendum migrasi IMAP ke Gmail API

Android memulai dan menampilkan status tautan melalui OAuth Google. Go membaca notifikasi
Livin dari Gmail API, memprosesnya dari memori, dan menyimpan transaksi normalisasi di Turso.
Polling `history.list` tiap 15 menit menjadi jalur utama. Pada mode push opt-in, sinyal
Pub/Sub membawa cursor perubahan ke server. Android menampilkan data dari API Go.
Ukur waktu penyimpanan, waktu tampil di Android, dan waktu kategori selesai secara terpisah.

| Paket | Hasil yang harus dibangun | Bukti penerimaan |
|---|---|---|
| GAPI-1 | Kontrak koneksi Gmail OAuth server-side dengan scope hanya baca, consent baru dan status koneksi server. Validasi OAuth `state`, redirect dan binding terhadap sesi PAM. Simpan token dan penanda mailbox secara terenkripsi, dengan isolasi owner dan lingkungan. | Connect, cancel, code/state replay, akun berbeda, expiry, reconnect, revoke dan owner isolation. Persetujuan dicatat sebelum Go mengambil email. |
| GAPI-2 | Gunakan `messages.list` terfilter sender/subject dan rentang, pagination dan `messages.get` seperlunya. Ambil metadata dulu bila perlu menyaring kandidat. Validasi pengirim, bentuk pesan dan batas MIME di Go. Port perilaku parser dari fixture yang sudah ada, termasuk `Penerima` versus PAN. | Backfill setahun: akar range/metadata, kedua ujung jendela WIB, >1 halaman, resume, partial, duplicate, retry, malformed, email terlambat dan data terlihat setelah tiap commit. Hanya dokumen yang cocok diproses, tanpa fetch ulang massal ketika cursor sehat. |
| GAPI-3 | Worker polling memproses `history.list` dari checkpoint tersimpan. Backfill dan pemulihan cursor mempertahankan cakupan dalam retensi. Watch, renewal, dan webhook terautentikasi berlaku pada mode push opt-in. | Uji polling kosong, duplikat, retry halaman, kuota, cursor kedaluwarsa, dan kelanjutan setelah restart. Cursor maju sesudah data diproses dan disimpan. Uji missed push dan expiry watch hanya pada mode push. |
| GAPI-4 | Rekonsiliasi IMAP → Gmail API. Identitas Gmail API dan UID IMAP perlu strategi pemetaan terverifikasi. Jalankan mode bayangan tanpa mutasi saat membandingkan cakupan, lalu aktifkan satu sumber per owner. | Data lama tetap satu kali, kategori USER dan metadata bertahan. Akun lama menautkan ulang lewat OAuth. Ambiguitas ditahan untuk tinjauan. Rollback tidak mengeksekusi dua kolektor sekaligus atau memajukan cursor yang salah. |
| GAPI-5 | Sambungkan UI Indonesia, riwayat setahun, satu job kategori, retensi, dan status server sesuai §8 dan CR-006. | Run Android Studio dari build gabungan. Owner menautkan akun sendiri, satu email Livin baru tiba saat app tertutup, Go memproses tanpa tap, dan hasil tampil setelah app dibuka. Ukur latensi dan jumlah call/token Azure. |

### Aturan eksekusi dan migrasi

- Catat cursor awal sebelum backfill. Beri giliran ke delta saat backfill berjalan.
  Pembaruan cursor harus monoton dan tahan restart. Jangan menganggap `historyId`
  berurutan tanpa selisih.
- Pada mode push, autentikasi pesan Google dengan subscription berautentikasi, audience, issuer,
  dan identitas service account yang diharapkan. Resolusi owner berasal dari koneksi Gmail
  di server. Simpan sinyal yang sudah divalidasi sebelum memberi respons ACK. Worker
  menangani pengulangan secara idempotent. Cursor expired atau `404` memicu rekonsiliasi
  terkontrol dalam jendela retensi, dengan checkpoint dan dedup.
- Pada mode push, pembaruan watch berjalan terjadwal sebelum expiry. Rekonsiliasi ringan berkala
  menangani event yang terlambat atau hilang. Ukur dan laporkan jatah kuota, jumlah query,
  latency serta kegagalan. Batasi concurrency dan backoff saat Google atau penyedia
  database menolak request. Pencarian Gmail menggunakan epoch seconds dan buffer untuk WIB.
- Scope `gmail.readonly` termasuk restricted. Pastikan konfigurasi test/publik, OAuth
  consent screen, dan verifikasi serta assessment yang berlaku sebelum menjanjikan rilis.
  Topik Pub/Sub dan endpoint HTTPS publik diperlukan untuk mode push opt-in.
  Penyimpanan raw email, header, PAN, atau source ID mentah di log/Turso tidak diperlukan.
  Cursor operasional diproteksi dengan akses owner-scoped. Azure tetap memakai payload
  kategori atau analisis yang sudah diizinkan. Untuk hasil turunan Gmail API, verifikasi
  ketentuan Limited Use dan disclosure pemrosesan Azure sebelum pilot memakai data asli.
  Gunakan fixture bila transfer data tersebut belum disetujui.
- Disconnect, withdrawal dan deletion menghentikan watch pada mode push, menolak kerja baru, dan
  membersihkan kredensial terkait. Pertahankan aturan logout PAM dari baseline sampai
  keputusan revisi disahkan. Menutup app berbeda dari logout atau mencabut izin Gmail.
- Setelah UAT, audit rute UI, scheduler, vault App Password, konfigurasi dan dependency IMAP.
  Pensiunkan jalur lama pada build target setelah migration/rollback tervalidasi.
  Catat rollback dan pertahankan transaksi serta consent yang relevan selama cutover.

### Referensi resmi

- [Gmail API scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)
- [Google Workspace user data dan Limited Use](https://developers.google.com/workspace/workspace-api-user-data-developer-policy)
- [OAuth 2.0 Google](https://developers.google.com/identity/protocols/oauth2)
- [Gmail Push dan renewal watch](https://developers.google.com/workspace/gmail/api/guides/push)
- [Sinkronisasi Gmail dan pemulihan history](https://developers.google.com/workspace/gmail/api/guides/sync)
- [Rentang tanggal pencarian Gmail API](https://developers.google.com/workspace/gmail/api/guides/filtering)
- [Autentikasi Pub/Sub push](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions)

## 10. Google SSO dan onboarding Gmail

**Status 24 November 2026** — **implementasi selesai di fixture/unit** (`pam-app@e67380a`,
backend `71013f5`): Credential Manager, verifikasi ID token Go (RS256/JWKS,
issuer/audience/expiry/nonce sekali pakai), penautan unik-atomik, onboarding Gmail + Lewati.
UAT akun Google nyata, Gmail live, dan gate Rp0 **NOT RUN (owner)**; bukti polling FIXTURE
tetap berlaku untuk lingkup fixture. Lihat [CR-007](../changes/CR_007_DRAFT_GMAIL_API_YEARLY_WORKFLOW.md)
dan [matriks gate](../delivery/FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md).

1. **Masuk dengan Google** menjadi pilihan utama. Android memakai Credential Manager.
   Backend Go memvalidasi ID token dan membuat sesi PAM dengan mekanisme access/refresh
   token yang sudah ada. Untuk akun yang sudah ditautkan, backend menemukan `ownerId`
   melalui pasangan issuer dan `sub` Google yang tervalidasi.
2. **Tautkan Gmail untuk impor Livin** tampil setelah login bila Gmail belum tertaut.
   Pengguna bisa menyetujui scope `gmail.readonly` untuk akun yang sama atau akun Gmail lain.
   Opsi **Lewati** mempertahankan akses ke fitur manual. Backfill 12 bulan mulai setelah
   tautan dan consent Gmail berhasil. Consent kategori dan analisis AI tetap terpisah.
3. **Migrasi akun PAM lama**: pengguna masuk dengan email dan password PAM untuk
   menautkan identitas Google dari sesi miliknya. Go mempertahankan owner, role, transaksi,
   dan koneksi Gmail yang ada. Email yang sama tidak mengotorisasi penautan atau pembuatan
   akun duplikat. Identitas Google yang sudah terikat ke owner lain menghasilkan konflik.
   Pertahankan aturan undangan, status akun, dan pembatasan admin yang berlaku.
4. **Profil pilot Rp0**: gunakan Google Sign-In langsung, Gmail API dalam kuota standar,
   dan backend Go yang sudah tersedia. Hindari produk identitas terkelola dan infrastruktur
   GCP berbayar. Gate PASS membutuhkan project tanpa Billing tertaut dan UAT nyata.
   Pada OAuth External / Testing, refresh token Gmail dapat kedaluwarsa setelah 7 hari.
   Minta pengguna menautkan Gmail ulang sambil mempertahankan sesi dan fitur manual PAM.

| Pekerjaan | Kriteria penerimaan |
|---|---|
| Integrasi Credential Manager | Cocokkan package dan SHA-1 varian debug dengan OAuth Android client. Gunakan Web Client ID sebagai audience server. Client secret hanya disimpan di backend. Buktikan login dan batal login dari tombol Run Android Studio pada HP fisik. |
| Integrasi verifikasi Go | Validasi tanda tangan, issuer, audience, expiry, dan nonce sekali pakai. Penautan identitas unik dan atomik. Uji replay, audience salah, dua klaim bersamaan, akun nonaktif, dan konflik lintas owner. |
| Migrasi akun lama dan onboarding | Catat owner dan jumlah transaksi sebelum/sesudah penautan. Buktikan login ulang membuka owner yang sama tanpa akun ganda. Uji menolak, melewati, dan mencabut akses Gmail sambil tetap memakai PAM, lalu alur Gmail dengan consent hingga backfill dan delta tampil. |
| Pengujian biaya dan rilis | Pisahkan bukti fixture, SSO di HP fisik, Gmail live, dan GCP Rp0. Pastikan billing project pilot tidak tertaut. Verifikasi kebijakan scope restricted sebelum rilis publik. |

[Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg)
· [Otorisasi data pengguna](https://developer.android.com/identity/authorization)
· [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
