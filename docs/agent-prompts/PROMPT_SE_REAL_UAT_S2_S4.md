# Prompt gabungan SE — REAL-UAT dan penyelesaian S2–S4

Gunakan prompt ini setelah pemilik menyelesaikan `docs/REAL_UAT_SETUP.md` dan menyerahkannya
kepadamu. Penyimpanan dokumen ini saja bukan instruksi untuk memakai akun nyata sebelum handoff.
Ini menggabungkan arahan resume 0541342 dan setup REAL-UAT yang sebelumnya belum dikirim.

Kamu Software Engineer PAM, workspace `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir`,
root Git `pam-app`, branch `feat/c2-android`. Baca HEAD/status aktual dan pertahankan WIP/commit PM.
Baca AGENTS.md, SRS/CR-003, `docs/agent-prompts/MANDAT_S2_S4.md` dan
`docs/agent-prompts/RESUME_0541342_CLOSE_CR003.md`. Seluruh requirement dan acceptance di dokumen
tersebut tetap mengikat; kerjakan sebagai satu penugasan berkelanjutan, bukan satu patch per turn.

## A. Siapkan lingkungan nyata dari konfigurasi privat yang tersedia

- File privat `/Users/user/.config/pam/real-uat.env` berisi APP_ENV=production, PORT=18090,
  invite/JWT/refresh/encryption secrets random persisten, serta Ollama lokal melalui adapter
  OpenAI-compatible. Jangan mencetak/commit isi file, mengganti kunci, atau memakai default dev.
- URL dan token DB ada pada `/Users/user/.config/pam/turso-real-uat.url` dan
  `/Users/user/.config/pam/turso-real-uat.token`. Validasi format secara privat lalu isi dua field
  kosong pada real-uat.env dengan parser/data API, bukan string interpolasi shell yang berisiko.
  Bila field sudah terisi benar, pertahankan. Tidak ada secret di args/log/report/artifact Git.
- Pastikan target database khusus REAL-UAT baru, engine kompatibel libSQL driver, token tepat
  dan migrasi dapat diterapkan. Jangan menggunakan database existing lain, QA reset DB atau
  menjalankan dua migration runner. Jangan provision layanan berbayar atau deploy publik.
- Siapkan launcher yang memuat env secara benar ke proses Go. Keberadaan backend/.env bukan
  bukti Go menggunakannya. Verifikasi config, migrations, restart, health/readiness dan DB
  persistence. Evidence hanya status/count/schema/version/run ID, tanpa URL/token/data privat.
- API lokal REAL-UAT port 18090, Android emulator `http://10.0.2.2:18090`; Ollama server lokal
  `http://127.0.0.1:11434/v1`, model installed `qwen3-vl:8b-instruct`, provider `openai` sebagai
  adapter, bukan provider `fake`. Jangan mengklaim kualitas/FR-37 PASS sebelum diuji. External
  model output invalid menjadi error yang sah; tidak ada fallback success statis.
- Buat build/source-set REAL-UAT beridentitas jelas dan terpisah dari QA untuk melindungi
  credential/data pemilik. Pastikan backend namespace/owner guards benar sebelum menautkan
  Gmail asli. Update APK dengan package/signature sama mempertahankan data; jangan uninstall/
  pm clear aplikasi asli. Bila perlu app ID berbeda, tampilkan sebagai app REAL-UAT terpisah.

## B. Tuntaskan CR-003 sebelum menyatakan alur nyata siap

Tutup seluruh temuan `RESUME_0541342_CLOSE_CR003.md`: race success/failure/post-connect/session
generation termasuk A→logout→A; reconnect lifecycle dengan hasil/retry tersimpan yang terlihat;
server-confirmed consent (ledger lokal bukan bukti ack); transport/folder/cache/cursor/label
owner+generation guards; vault namespace dan checked persistence. Pertahankan disconnected,
offline dan reauth yang jujur. Logout mempertahankan link untuk owner sama, explicit disconnect/
withdrawal/deletion menghapus owner terkait. Tidak ada credential atau error late masuk ke B.

Tuntaskan categoryConfidence/provenance/rule version/reason ke Go/UI, kategori custom, category
lookup error/recovery, reclassification UNCATEGORIZED non-USER dengan expected revision/409,
idempotensi dan USER override protection. Preserve amount/date/source/fingerprint/row count;
refresh totals/UI tanpa restart. Classifier whole-word dan strict parser yang sudah lulus tetap.

## C. Pengujian nyata dan QA yang dapat berjalan tanpa saling merusak

- SE menjalankan acceptance sintetis pada QA terpisah: deferred validation/search/fetch/switch,
  missing response/retry/restart, deletion/reset, owner isolation, 409 dan file/admin lifecycle.
  Tidak ada fault injection/sweep/delete/reset pada REAL-UAT atau akun Gmail asli.
- Setelah build REAL-UAT siap, beri satu handoff UI konkret kepada pemilik untuk register/login
  akun PAM dan menautkan Gmail asli dengan App Password langsung di UI jika belum tertaut. Tidak
  meminta password utama Gmail, secret via chat, atau mengekstrak vault. Jangan menyimpan credential
  Gmail plaintext di env. Sesi MCP Gmail PM bukan credential IMAP aplikasi.
- Sambil menunggu input autentikasi, teruskan tests/implementasi lain. Sesudah link siap, gunakan
  alur read-only Gmail dan consent aplikasi untuk menguji impor notifikasi Livin ke Turso REAL-UAT,
  list/detail/summary dan kategori; lakukan logout/login/restart yang non-destructive untuk
  membuktikan persistensi tautan. Jangan menyebut REAL-UAT PASS dari fixture atau API seed manual.
- Simpan data transaksi normal tersanitasi sesuai kontrak; raw mail/body/subject/sender/message
  ID/credential tidak masuk server/log/repo. Evidence sintetis dan UAT nyata dipisah; laporan nyata
  cukup counts/status dan assertion privat tersanitasi. Tidak mengirim merchant/notes ke model AI;
  model tetap menerima canonical aggregate sesuai consent dan schema FR-37 saja.
- Jika user input/akses provider/cloud menjadi blocker, sebutkan dependency tepat dan tetap
  kerjakan scope independen. Jangan menutup penugasan hanya karena satu UAT eksternal menunggu.

## D. Teruskan seluruh mandat S2–S4

Setelah irisan CR-003 koheren, lanjut import job-native/counts/revision/coverage, immutable
upload journal/resume dan fingerprint key server; pagination/filter/search >100; update/delete/
409; budget/analysis/freshness/deletion receipt UI; S3 transaksi/kategori/anggaran CRUDS dan
dynamic fields; S4 lampiran/admin/audit/25 aksi serta pengeluaran Room/SQLCipher dari varian
penilaian. R1/accessibility tetap acceptance. Jangan berhenti pada unit tests atau dokumentasi.

Setiap commit koheren **langsung push ke branch yang sama** dan verifikasi remote. Tidak perlu
meminta izin ulang untuk pekerjaan yang sudah dimandatkan. Tidak ada force/reset destruktif,
merge/deploy, pengiriman undangan/email atau biaya layanan. Pertahankan C1/F1–F4/gate A/C2A/R1/
Manrope/OFL dan QA proxy. Gunakan checkpoint lengkap agar dapat resume tanpa mengulang audit.

Jalankan tests relevan, Android unit/assemble/lint, Go fresh tests/race/vet/build bila berubah,
migration/interop bila terdampak. Perbaiki failure, jangan menonaktifkan checks atau memalsukan
PASS. Laporan final berisi commit/push/ref, acceptance CR-003 dan S2–S4/25 aksi, REAL-UAT versus
QA evidence, actual commands/results, limitations dan external NOT RUN. Tidak menjanjikan
bebas defect mutlak. Batas runtime nyata berarti checkpoint unfinished, bukan tugas selesai.

**Mulai bekerja setelah prompt ini diserahkan. Tuntaskan implementasi dan verifikasi, teruskan
seluruh scope independen, dan gunakan satu handoff autentikasi yang konkret saat APK siap.**
