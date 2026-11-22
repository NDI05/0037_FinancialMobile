# Rencana sinkronisasi otomatis, batch kategori, dan status PAM

Tanggal keputusan: **23 November 2026**.
Status: **arah disetujui pemilik; PLANNED / acceptance belum dijalankan**.
Pekerjaan sesi PM ini hanya perencanaan dan draf revisi; tidak mengaktifkan scheduler,
mengubah runtime/env, atau merevisi SRS resmi. Jangan menganggap kode worker classifier
yang sedang dikerjakan SE sebagai implementasi sinkronisasi Gmail otomatis ini.

Draf konsolidasi SRS: [CR-005](../changes/CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md).
Baseline formal tetap SRS v0.5. Rencana ini menjadi rujukan aktif untuk scope baru;
penundaan background sync pada roadmap v0.3 adalah historis.

## 1. Keputusan dan batas scope

- Pengguna tidak perlu menekan Impor untuk pembaruan rutin setelah login, koneksi Gmail
  dan consent yang sesuai tersedia. Tombol pembaruan manual tetap sarana recovery opsional.
- Tahap awal mempertahankan **IMAP dan parser di Android**. Credential Gmail tidak pindah
  ke backend. Tidak menambah Google OAuth, GCP/Pub/Sub, atau hosting publik dalam scope ini.
- Target pemeriksaan background **setiap 30 menit**, ditambah pemeriksaan saat aplikasi
  masuk foreground jika data sudah cukup lama. WorkManager bersifat best effort, bukan
  jaminan realtime, jam pasti, atau tetap berjalan ketika perangkat mati/force-stop.
- Kategorisasi melalui antrean backend dengan target pemrosesan **setiap 2 jam**,
  maksimal **5 merchant unik berkonteks sama per request**. Impor/penyimpanan finansial
  tidak menunggu model. Rules, pemetaan terkonfirmasi, dan cache diperiksa lebih dahulu.
- Gunakan komponen status bersama di top bar dan panel rincian. Loading hanya muncul
  ketika pekerjaan benar-benar berjalan, bukan sepanjang menunggu jadwal berikutnya.
- Gmail Push/OAuth adalah opsi fase berikut yang memerlukan keputusan arsitektur,
  hosting, privasi dan consent tersendiri; belum termasuk rencana implementasi ini.

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
5. Satu pekerjaan per owner/mailbox: foreground, background, dan Impor manual melewati
   koordinasi yang sama. Tidak ada full scan, koneksi IMAP abadi/IDLE, atau service terus
   menyala sebagai cara mengakali jadwal Android pada tahap ini.
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
  Gmail/aggregate sebagai izin Azure. Hindari backlog tersembunyi dari data lama; target
  run otomatis adalah transaksi baru, reclassification histori tetap aksi eksplisit.
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
- [Gmail Push](https://developers.google.com/workspace/gmail/api/guides/push): opsi arsitektur lanjutan, tidak termasuk tahap IMAP ini.
