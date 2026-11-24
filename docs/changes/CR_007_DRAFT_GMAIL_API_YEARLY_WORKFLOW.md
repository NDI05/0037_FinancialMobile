# CR-007 — DRAF revisi SRS: Gmail API, impor setahun, retensi, kategorisasi otomatis

Tanggal: **24 November 2026**.
Status: **draf untuk konsolidasi SRS; BELUM diterapkan**. Full SRS resmi tetap v0.5;
file DOCX/PDF, requirement ID resmi, dan version history tidak berubah oleh CR ini.
Nomor versi berikutnya dan nomor FR/NFR final belum ditetapkan.

Dasar: [mandat PM Gmail API + workflow tahunan](../agent-prompts/PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW.md).
Melengkapi [CR-003](CR_003_GMAIL_SESSION_CATEGORY.md) (koneksi email) dan
[CR-005](CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md) (auto-sync/kategori/status).
CR ini **menggantikan target transport IMAP/app-password** pada bagian yang bertentangan:
sumber email produksi menjadi Gmail API OAuth + Pub/Sub push.

## 1. Alasan perubahan

Pemilik menetapkan target: login → tautkan Gmail lewat OAuth Google → impor otomatis riwayat
Livin setahun → satu job kategorisasi otomatis → transaksi baru masuk via Gmail Push termasuk
saat app tertutup → retensi setahun → seluruh UI utama Bahasa Indonesia ringkas. Mandat juga
memisahkan backend Go ke repo **0037 Backend Financial** agar Android tidak lagi memuat source
server. IMAP/app-password tidak dapat memenuhi push saat app tertutup secara andal dan menambah
permukaan kredensial (app password) di perangkat.

## 2. Calon requirement untuk konsolidasi nanti

| ID draf | Rumusan calon requirement | Trace bukti implementasi |
|---|---|---|
| DRAFT-GM-01 | Owner menautkan Gmail dari sesi PAM aktif; server membuat `state` acak sekali pakai (TTL ≤10 menit) terikat owner + PKCE S256; callback menukar code, menyimpan refresh token terenkripsi, dan selalu mengembalikan owner ke app lewat deep link dengan kode alasan aman | `feat/gmail-api`; `docs/evidence/GMAIL_API_2026-11-24.md`; audit `AUDIT_GMAIL_API_2026-11-24.md` |
| DRAFT-GM-02 | Hanya satu koneksi Gmail ACTIVE per owner; sambung ulang dengan akun lain menghentikan watch dan mencabut token lama sebelum mengganti; scope minimum `gmail.readonly`; status tidak pernah menampilkan token/email penuh | sama; tes isolasi owner + reconnect di backend |
| DRAFT-GM-03 | Transaksi baru diterima lewat Pub/Sub push yang diverifikasi JWT OIDC; event durable di-ACK 2xx, duplikat tidak menghasilkan baris ganda, mailbox tak dikenal di-ACK tanpa data | `internal/gmail/oidc.go`, `gmail_push_events`; tes push |
| DRAFT-GM-04 | `users.watch` diperbarui sebelum kedaluwarsa dan dihentikan saat disconnect; kegagalan auth memicu `NEEDS_REAUTH` yang menghentikan auto-sync sampai owner menyambung ulang | worker renew/stop; tes |
| DRAFT-GM-05 | Backfill awal mencakup 12 bulan bergulir WIB; bulan berjalan diproses lebih dulu; checkpoint per batch; email lintas bulan dirutekan ke bulan transaksi (bukan bulan kedatangan) dan transaksi di luar jendela retensi ditolak eksplisit | worker backfill; tes email lintas bulan |
| DRAFT-GM-06 | Delta memakai `history.list`; cursor disimpan terenkripsi dan pulih dari kedaluwarsa tanpa kehilangan transaksi; replay tidak menggandakan | worker delta; tes cursor expiry |
| DRAFT-GM-07 | Identitas pesan Gmail tidak pernah disimpan mentah; dedupe memakai hash dan merge dengan baris lama hasil IMAP berdasarkan kunci alami agar tidak terjadi dobel impor | dedupe + cross-source merge; gate migrasi IMAP |
| DRAFT-GM-08 | Setelah impor server-side, kandidat eligible otomatis masuk antrean kategorisasi; drain dibatasi 5 merchant/request, maksimal 4 batch per jendela, target 2 jam, hormati consent/budget/lease, dan antrean kosong tidak memanggil provider | jembatan kategorisasi; tes auto-category |
| DRAFT-RT-01 | Data keuangan disimpan dalam jendela bergulir satu tahun WIB; permintaan di luar jendela ditolak `400 outside_retention` dengan metadata jendela, bukan error server | migrasi 018 + guard backend |
| DRAFT-RT-02 | Sweep retensi default report-only (hanya audit agregat); penghapusan memerlukan flag `RETENTION_DELETE_ENABLED`, token internal, dan konfirmasi literal; budget plan dikecualikan | `RETENTION_POLICY_IMPLEMENTATION.md`; tes store/httpapi |
| DRAFT-UI-01 | Navigasi bulan terbatas pada jendela retensi (panah depan mati di bulan berjalan, panah belakang di batas, label "sebagian"); status proses memakai satu ikon dan satu sheet dengan label ringkas Indonesia | `docs/evidence/RETENTION_UI_2026-11-24.md` |
| DRAFT-UI-02 | Layar tautkan Gmail memisahkan consent Gmail/AI kategorisasi/AI analisis, menampilkan status masked, dan saat backend belum mengaktifkan Gmail menampilkan penjelasan jujur tanpa CTA menyesatkan | `docs/evidence/GMAIL_UI_2026-11-24.md`; 26+ tes UI |
| DRAFT-UI-03 | Seluruh alur utama Bahasa Indonesia, TalkBack satu node per kontrol status, target sentuh ≥48 dp, layout benar pada font 200% di 360/393/412 dp | `RETENTION_UI_2026-11-24`; `UAT_ACCESSIBILITY_2026-11-24` |
| DRAFT-OPS-01 | Source backend berdiri di repo terpisah dengan build/test/CI sendiri; Android tidak memuat source server; rollback ke pasangan baseline didokumentasikan | `docs/delivery/BACKEND_SPLIT_2026-11-24.md`; QA parity P1–P9 |

## 3. Perubahan UI utama

1. Pengaturan → **Tautkan Gmail lewat browser**, status tersambung (email masked, sinkron terakhir,
   kedaluwarsa watch), tombol ganti akun dan putuskan berkonfirmasi.
2. Ringkasan/Transaksi/Analisis dibatasi jendela retensi dengan label bulan batas "sebagian".
3. Status pembaruan: satu ikon + satu sheet; label "Mengambil riwayat", "Menunggu AI",
   "Perlu tinjauan", "Coba lagi"; sheet menampilkan rentang retensi; menutup sheet tidak
   menghentikan job server.
4. Istilah teknis (IMAP, Pub/Sub, token) tidak tampil di UI utama.

## 4. Keputusan yang masih menunggu pemilik

1. Merge `feat/gmail-api`/`feat/retention`/`integration/2026-11-24` ke `main` repo backend dan
   deployment; saat ini hanya branch yang dipush.
2. Pembuatan project/OAuth consent screen/test users/OAuth client Google, enable Gmail API,
   Pub/Sub topic + subscription + publisher grant, endpoint HTTPS, dan verifikasi restricted
   scope untuk produksi (CASA).
3. Rotasi client secret/service account dan pemasangan kredensial di lingkungan UAT.
4. Akun pilot untuk E2E live dan izin UAT pada data nyata.
5. Keputusan menghentikan jalur app-password perangkat sepenuhnya setelah Gmail API aktif.

## 5. Batas draf

Status implementasi per 24 November 2026: kode dan tes otomatis PASS pada pasangan branch;
gate live (Console, OAuth nyata, Pub/Sub nyata, E2E akun pilot, verifikasi restricted scope,
restore/rollback data nyata, deploy) **NOT RUN** dan menunggu pemilik. Draf ini tidak menyatakan
SRS resmi berubah.
