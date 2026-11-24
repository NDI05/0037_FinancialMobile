# CR-005 — DRAF revisi SRS: auto-sync, batch kategori, status proses

Tanggal: **23 November 2026**.
Status: **arah fitur disetujui pemilik; draf konsolidasi SRS, BELUM diterapkan**.

Addendum 24 November 2026 mencatat workflow yang diminta pemilik untuk impor awal
setahun, kategorisasi otomatis, dan retensi data. Addendum ini mengarahkan revisi
berikutnya. Status di dokumen ini merujuk pada draf dan tidak menilai ulang fitur
auto-sync yang sudah dibuktikan pada checkpoint implementasi.

Instruksi pemilik: masukkan ke perencanaan dan draf revisi untuk nanti;
**jangan memperbarui SRS sekarang**. Full SRS resmi tetap v0.5; file DOCX/PDF,
requirement IDs dan version history resminya tidak berubah oleh CR ini.
Nomor versi SRS berikut dan nomor FR/NFR/test final belum ditetapkan.

Rencana delivery dan acceptance terperinci:
[AUTO_SYNC_CATEGORY_STATUS_PLAN.md](../plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md).

## 1. Alasan perubahan

Impor yang bergantung pada tap pengguna membuat transaksi baru belum terlihat sampai
aksi manual dilakukan. Pengguna menyetujui pemeriksaan otomatis yang hemat resource,
dengan kategorisasi Azure terpisah dan dijadwalkan per batch. Pengguna juga meminta
indikator top bar agar loading, pending, selesai, partial dan error dapat dimengerti.

“Otomatis” pada tahap ini bukan realtime dalam detik. Android dapat menunda background
work; UI wajib menjelaskan freshness dan status terakhir yang benar-benar berhasil.

## 2. Calon requirement untuk konsolidasi nanti

Kode DRAFT di bawah hanya identitas draf; bukan FR/NFR resmi yang sudah berlaku.

| ID draf | Rumusan calon requirement | Trace acceptance rencana |
|---|---|---|
| DRAFT-AS-01 | Sistem memulai pemeriksaan incremental otomatis untuk owner login dengan koneksi/consent Gmail valid, tanpa tap Impor rutin | AS-01/02 |
| DRAFT-AS-02 | Background sync menargetkan 30 menit, serta foreground freshness check yang didebounce; jadwal best effort mengikuti OS/jaringan | AS-03/05 |
| DRAFT-AS-03 | Sync memakai cursor/journal privat, fingerprint, dan acknowledgement server untuk replay/recovery tanpa pesan hilang atau transaksi ganda | AS-02/04 |
| DRAFT-AS-04 | Logout/owner switch/disconnect/withdrawal/deletion membatalkan pekerjaan relevan dan menolak late result; credential persistence mengikuti CR-003 | AS-06 |
| DRAFT-BC-01 | Financial commit tidak menunggu LLM; hanya kandidat eligible dengan consent kategorisasi dan auto-processing notice yang sah masuk antrean otomatis | AS-01, BC-01 |
| DRAFT-BC-02 | Backend memproses antrean target 2 jam, maksimal 5 merchant unik/context per request, dengan cache/rules/coalescing sebelum provider | BC-01/02 |
| DRAFT-BC-03 | Partial batch dapat menunggu run berikutnya; kandidat max age 4 jam saat layanan sehat, dalam batas budget; backlog/status tetap terlihat | BC-01/02 |
| DRAFT-BC-04 | Manual dan otomatis berbagi durable cost/attempt/token gate; antrean kosong tidak memanggil Azure dan batas tercapai tidak menghapus transaksi | BC-02/03 |
| DRAFT-UX-01 | Halaman utama menampilkan indikator status ringkas di top bar yang membuka panel status tanpa memicu sinkronisasi/kategorisasi | UX-01/02 |
| DRAFT-UX-02 | Panel memisahkan sync transaksi dan kategori, last attempt/last success, counts, antrean, partial/skipped, errors dan recovery yang aman | UX-01/02 |
| DRAFT-UX-03 | Loading hanya untuk pekerjaan aktif, progress numerik hanya dengan denominator sah, data existing tetap terlihat saat refresh | UX-01/02 |
| DRAFT-NFR-01 | Background work bounded/single-flight, incremental fetch, network constraints/backoff, tidak memegang koneksi kontinu untuk polling | AS-02/03/05, OBS-01 |
| DRAFT-NFR-02 | Indikator mendukung minimal 48 dp touch target, TalkBack, font 200%, 360/393/412 dp dan reduced motion; warna bukan satu-satunya pembeda | UX-03 |

## 3. Impact map untuk revisi SRS berikut

- Scope: ubah background sync dari deferred menjadi auto-sync bertahap. Addendum §7
  menetapkan Gmail API dengan OAuth dan Pub/Sub sebagai target pengganti IMAP.
- Use case impor: tambah foreground/background trigger, first sync versus delta,
  owner/mailbox coordination, retry/recovery dan catch-up.
- Arsitektur/sequence: Android WorkManager → IMAP/parser → journal/upload Go;
  financial commit → eligible category queue → backend scheduled worker → Azure.
- Model data: owner/mailbox cursor privat, sync attempts/last success, durable queues,
  next eligible run, leases, partial/error reasons dan accounting existing.
- Privasi/retensi: pastikan operational UID/UIDVALIDITY cursor lokal kompatibel dengan
  larangan penyimpanan source message IDs. Jangan mengizinkan ID/email/credential masuk
  transaksi, backend, logs atau analytics secara implisit. Putuskan retensi/purge metadata.
- Consent: perjelas auto-processing dan pilihan menonaktifkan kategorisasi otomatis;
  Gmail read, classifier dan aggregate processing tetap purpose berbeda. Grant lama
  yang hanya menjelaskan tap manual tidak dianggap persetujuan auto-processing baru.
- Biaya: pertahankan batas runtime USD0,10/hari dan USD2/bulan, 20 attempts/owner/hari,
  100/installation/hari dan guard lain; cloud USD10/bulan tetap lapisan luar.
- UI/wire: state durable dan status per pipeline, text/copy yang tidak mengklaim realtime,
  lokasi top bar/panel, skeleton/loading/empty/partial/offline/error/review/budget states.
- Verification/traceability: masukkan AS/BC/UX/OBS acceptance plan ke test suite dan
  requirement map resmi hanya saat konsolidasi SRS diminta nanti.
- Diagram/version history formal dan referensi lintas dokumen diperbarui bersama pada
  revisi berikutnya; jangan membiarkan nomor FR/test baru terbit hanya dari draf ini.

## 4. Parameter yang masih perlu finalisasi engineering/UAT

| Parameter | Kandidat/keputusan saat ini |
|---|---|
| Background Gmail check | Target 30 menit, best effort; disetujui sebagai arah rencana |
| Category drain | Target 2 jam, maksimal 5 merchant unik/request |
| Foreground cooldown | Kandidat 5 menit; perlu pengujian |
| Partial batch age | Kandidat 4 jam saat sehat dan budget tersedia; perlu pengujian |
| Status UI | Ikon ringkas + bottom sheet; copy/icon detail kandidat sampai visual UAT |
| Auto-sync opt-in/notice | Arah otomatis disetujui; disclosure, lifecycle dan default migrasi harus ditentukan |
| Cursor storage/retention | Review kompatibilitas privasi dan recovery sebelum implementasi |
| Backend 24/7/perangkat | Dependency operasional release; belum izin hosting/deploy baru |
| SRS version/requirement IDs | Ditentukan ketika pemilik meminta revisi resmi |

## 5. Batas klaim dan langkah nanti

Rencana ini tidak membuktikan scheduler aktif, email diimpor otomatis, 80 transaksi
selalu cukup 16 requests, baterai hemat sekian persen, atau kategori pasti akurat.
Tidak merevisi SRS v0.5, mengubah env/flag/limit, membuat resource cloud, maupun
mengaktifkan layanan. P0 classifier aktif tetap diselesaikan lebih dahulu.

Saat pemilik meminta revisi SRS: baca baseline lengkap, finalisasi parameter berdasarkan
hasil engineering/UAT, tetapkan requirement IDs, konsolidasikan semua impact dan diagrams,
render/validasi dokumen lengkap, kemudian cocokkan traceability dengan implementation.

## 6. Addendum 24 November 2026 — impor awal, antrean AI, dan retensi setahun

| Area | SRS v0.5, rencana, dan kode saat ini | Calon perubahan SRS |
|---|---|---|
| Impor awal setelah Gmail terhubung | FR-06/54 mengatur koneksi tervalidasi, FR-11/12/16–20 dan BR-01/05 mengatur impor per bulan dan hasilnya. Auto-sync saat ini memakai pencarian incremental. Tanpa cursor awal, overlap pemulihan 6 jam tidak menjangkau satu tahun. Jalur bulanan dapat melewati pencarian jika job terakhir bulan itu `COMPLETE`, termasuk job incremental. | **DRAFT-AS-05** — Setelah koneksi Gmail valid dan `GMAIL_READ` tersedia, jadwalkan satu impor awal otomatis untuk rentang setahun. Simpan cakupan dan checkpoint per bulan. Keberhasilan delta tidak mengisi status cakupan impor awal atau impor bulanan. |
| Kategorisasi setelah impor awal | FR-66–71 mensyaratkan consent khusus, input terbatas, aturan/cache, keamanan, dan pengendalian biaya. Implementasi saat ini membatasi 5 merchant unik per request dan menerapkan batas token. Rencana awal menunda klasifikasi backlog lama. | **DRAFT-BC-05** — Setelah tahap impor awal selesai atau hasil parsial akhirnya tercatat, jalankan satu job kategori otomatis untuk transaksi eligible yang telah dikonfirmasi server, dengan notice dan consent `AI_CATEGORIZATION` yang mencakup pemrosesan histori. Perluas job yang sama ketika data impor parsial berhasil dipulihkan. Satu job dapat terdiri dari nol, satu, atau beberapa request provider. |
| Email baru dan batch rutin | DRAFT-AS-02 menargetkan cek foreground dan pemeriksaan background 30 menit best effort. DRAFT-BC-02/03 menargetkan antrean sekitar 2 jam dengan flush sisa berdasarkan usia saat sehat. | **DRAFT-AS-06** — Setelah cursor awal dicatat, sinkronisasi delta mengambil email baru tanpa tap dan memprioritaskan transaksi baru ketika ada backlog histori. **DRAFT-BC-06** — Commit transaksi segera. Gabungkan kandidat uncached per owner dan konteks, kirim batch saat memenuhi batas kandidat/token atau ketika jendela tunggu jatuh tempo. |
| Retensi dan periode | NFR-15 mengusulkan 24 bulan sejak impor, dengan kebijakan deletion akun dan backup terpisah. BR-01 memakai waktu transaksi WIB. Tombol bulan di beberapa ViewModel masih memanggil `next()` tanpa batas bulan saat ini. | **DRAFT-RET-01** — Simpan dan sajikan data keuangan dalam jendela setahun bergulir berdasarkan waktu transaksi WIB. Hentikan akses data di luar jendela dan hapus dengan cleanup terjadwal. **DRAFT-RET-02** — Pemilih periode riwayat berhenti pada bulan berjalan dan bulan tertua yang masih tercakup. Terapkan batas yang sama pada UI, query, impor, dan API. |

### Batas periode dan dampak retensi

- Usulan definisi setahun ialah awal hari WIB pada tanggal hari ini dikurangi satu tahun
  kalender sampai saat pengambilan data. Pada 24 November 2026, riwayat dimulai 24 November
  2025 pukul 00.00 WIB. Bulan tertua mungkin hanya sebagian, dan pemilih bisa mencakup 13
  nama bulan. Gunakan `eventAt` untuk menentukan kelayakan, dengan buffer tanggal penerimaan
  email mengikuti BR-01. Validasi pergantian bulan/tahun serta batas 29 Februari.
- Retensi diusulkan berlaku untuk data keuangan yang diimpor maupun dicatat manual.
  Tentukan treatment anggaran periode lama, ringkasan/analisis turunan, metadata lampiran
  dan file lokal yang terhubung, cache, antrean, serta backup. Hasil agregat ikut dihitung
  ulang pada periode yang terpotong. Restore dan sinkronisasi ulang tidak boleh
  memunculkan kembali data yang sudah di luar jendela. Consent, sesi, dan audit akun
  mengikuti kebijakan masing-masing. Retensi tahunan dan penghapusan akun tetap dua alur.
- **Benturan FR-42** — SRS mengizinkan rencana anggaran masa depan. Draft membatasi layar
  riwayat dan laporan pada masa lalu serta bulan berjalan. Putuskan pada revisi resmi
  apakah editor perencanaan anggaran tetap punya pengecualian atau FR-42 ikut diubah.
- Sebelum mengaktifkan pembersihan data, finalisasi pemberitahuan, cakupan cleanup data
  lama, jadwal penghapusan, perlakuan backup, dan bukti pemulihan pada DB terisolasi.
  Addendum ini mendeskripsikan kebijakan yang diinginkan dan kasus uji.

Detail orkestrasi, batas biaya, dan acceptance ada di
[rencana delivery terbaru](../plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md#8-addendum-24-november-2026--workflow-setahun).
Usulan bahasa dan batas pemilih bulan pada UI ada di
[CR-006](CR_006_DRAFT_SYNC_UI_CATEGORY_REVIEW.md).

## 7. Addendum 24 November 2026 — Gmail API menggantikan IMAP

Pemilik memilih **Gmail API dengan OAuth read-only dan Gmail Push melalui Cloud Pub/Sub**.
Transport dan parser yang ada tetap menjadi baseline WIP sampai kesiapan cutover dibuktikan.
Rencana implementasi ada di
[§9 rencana sinkronisasi](../plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md#9-addendum-migrasi-imap-ke-gmail-api).

| ID draf | Calon perubahan | Dampak SRS v0.5 |
|---|---|---|
| DRAFT-GAPI-01 | Setelah login PAM, owner memberi izin Gmail melalui layar OAuth Google. Go mengaitkan satu mailbox terverifikasi ke owner. Simpan token OAuth dalam penyimpanan server terenkripsi. UI aktif memakai alur OAuth. | FR-06/09/10, FR-54/55 dan NFR-14. FR-08 historis tetap menjadi catatan perubahan. Koneksi IMAP lama perlu otorisasi baru untuk pindah. |
| DRAFT-GAPI-02 | Go menjalankan backfill 1 tahun dengan Gmail API. Raw MIME diproses sementara dalam memori Go untuk ekstraksi, lalu disimpan hanya hasil normalisasi. Pertahankan import bertahap, checkpoint, buffer penerimaan, dan tanggal transaksi WIB. | FR-11/12/16–20, BR-01/05, DR-03/05 dan trust boundary arsitektur. Diperlukan disclosure baru karena pemrosesan email berpindah ke backend. |
| DRAFT-GAPI-03 | Gmail `watch` dan Cloud Pub/Sub memicu delta owner-scoped melalui `history.list`. Status cakupan membedakan backfill, delta, dan retry. Penyimpanan tetap dapat berjalan saat aplikasi ditutup selama izin, koneksi akun, dan backend tersedia. | DRAFT-AS-02 historis menjadi fallback sebelum cutover. Tambahkan renewal watch, pemulihan cursor, penjadwalan rekonsiliasi, dan keputusan lifecycle logout. |
| DRAFT-GAPI-04 | Migrasi harus mencocokkan identitas transaksi lama yang dibuat lewat IMAP dengan kandidat Gmail API tanpa membuat duplikat atau menimpa koreksi pengguna. Hentikan watch dan invalidasi kerja pada disconnect, withdrawal, atau deletion. | FR-05/18/25/67, DR-01/02 dan acceptance account lifecycle. Perlakuan sesi PAM yang kedaluwarsa dibedakan dari logout eksplisit dan pencabutan izin Gmail. |
| DRAFT-GAPI-05 | Sinkronisasi push hanya dinyatakan tersedia setelah pilot membuktikan pengiriman event nyata, pengisian histori, isolasi owner, dan pemulihan. Hosting, kuota/biaya, OAuth verification, serta keamanan Google dievaluasi sesuai jenis rilis. | NFR-17/18/19/22/26, gate real-data readiness dan revisi panduan UAT. Consent `GMAIL_READ`, `AI_CATEGORIZATION`, dan `AI_PROCESSING` tetap terpisah. |

Draf ini merencanakan perubahan lokasi pemrosesan dan siklus hidup kredensial. Versi SRS
resmi, notice baru, serta migrasi akses akun nyata mengikuti proses persetujuan pemilik.
