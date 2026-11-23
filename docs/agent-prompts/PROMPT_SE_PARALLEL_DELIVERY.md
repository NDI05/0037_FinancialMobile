# Mandat SE — delivery paralel dengan sub-agent

Pemilik secara eksplisit meminta penggunaan beberapa sub-agent bersamaan untuk mempercepat
penutupan PAM. Ini perubahan cara eksekusi, bukan penambahan scope produk atau penurunan gate.

## 1. Peran, workspace dan baseline

Kamu adalah **SE utama / koordinator integrasi**. Delegasikan pekerjaan implementasi kepada
sub-agent nyata melalui fasilitas agentic yang tersedia. Jangan hanya menuliskan rencana
pembagian; mulai worker untuk pekerjaan independen, pantau hasilnya, lalu integrasikan.

Repository utama:
`/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app`

Branch integrasi aktif: `feat/c2-android`. Checkpoint source terakhir `ce56d9c`, review PM
dan dokumentasi `0a0c917`; periksa HEAD/status terbaru sebelum bekerja. Ada WIP terjemahan
`app/src/main/res/values-en/strings.xml` pada pemeriksaan PM. Jangan menimpa atau men-stage
WIP pihak lain. Lanjutkan perbaikan aktif, bukan reset ke hash baseline.

Baca AGENTS.md, mandat penutupan sebelumnya, `docs/agent-prompts/MANDAT_S2_S4.md`,
`docs/plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md`, serta
`docs/reviews/PM_REVIEW_CE56D9C_SYNC_RECOVERY.md`.
SRS resmi tetap v0.5; jangan merevisi/memindahkan DOCX/PDF. CR-005 tetap draf.

## 2. Susunan tim awal

Gunakan **3 sub-agent implementasi + 1 koordinator** bila kapasitas mendukung. Periksa
limit host terlebih dahulu; batasi worker menurut slot aktual, CPU/RAM dan kesiapan kontrak.
Jangan membuat worker menganggur untuk terlihat paralel, atau spawn turunan tanpa koordinasi.

| Peran | Tugas pertama | Batas ownership |
|---|---|---|
| Worker A — sync/recovery | Tutup P1 pending-upload lintas run, journal durable, partial/multi-bulan dan regression | Android sync/import use cases, cursor/journal/DAO/repository dan tests khusus yang disepakati |
| Worker B — Go/API | Pagination/filter/search >100, kontrak update/delete/conflict, budget/analysis/freshness; kemudian kategori/anggaran dan S4 backend | `backend-go/`, tests backend dan usulan kontrak per slice |
| Worker C — Android UI | S2.4 pagination/filter/search dan mutation UI; kemudian budget/analysis, CRUD kategori/anggaran, lampiran/admin sesuai API | Screens/ViewModels feature yang ditugaskan dan tests UI/domain terkait |
| Koordinator | API agreement, file bersama, integrasi, review, E2E, acceptance matrix dan dokumentasi harian | Kontrak canonical, DTO/ports/DI/navigation/resources/build bersama, README/checkpoint/progres |

Worker A bekerja independen dari B/C; backend dan Android tidak perlu menunggu P1 sync
untuk mulai mengerjakan pagination dan kontrak. Tidak semua pekerjaan harus bersamaan:
dependent integration, migrasi, emulator dan mutasi database wajib dikoordinasikan.

Setelah A menutup P1 dan patch diterima, alihkan slotnya ke QA integrasi atau slice S3/S4
yang independen. Bila A membantu backend/lampiran, alihkan ownership secara eksplisit agar
tidak berbenturan dengan B. Koordinator tetap mengawal penerimaan, bukan menyerahkan
tanggung jawab integrasi kepada worker terakhir.

## 3. Isolasi kerja dan kontrak

- Buat worktree dan branch lokal berbeda per worker dari baseline koheren, misalnya
  `work/se-sync-recovery`, `work/se-go-delivery`, `work/se-android-delivery`. Tempatkan
  worktree di direktori sibling; jangan memasukkan nested checkout ke Git repository utama.
- Jangan mengarahkan beberapa worker mengedit checkout yang sama. Jika platform sub-agent
  hanya berbagi filesystem, siapkan worktree lewat tooling dan pastikan cwd setiap worker.
- Koordinator membuat tabel ownership path/feature, dependency dan acceptance sebelum
  dispatch. File yang sama hanya memiliki satu penulis pada satu waktu. Worktree mengurangi
  risiko overwrite, bukan menghilangkan konflik integrasi.
- File bersama seperti PamApiClient, ServerFinanceRepository, DI, navigation, strings,
  Gradle/catalog, README/checkpoint dan API contract dipegang koordinator. Worker mengirim
  kebutuhan patch yang konkret; transfer ownership per slice jika koordinator mendelegasikan.
- Worker B dan koordinator menetapkan endpoint/query/cursor, DTO, error/status/revision,
  owner/consent serta fixture wire untuk satu slice sebelum C mengikat UI. Jangan menyetujui
  seluruh proyek lewat dokumen panjang sebelum mulai implementasi.
- C dapat membangun interaksi/UI dengan fake sesuai kontrak yang sama sambil B mengerjakan
  backend. Fake dibatasi pada test/demo berlabel; acceptance final memakai Go/persistence.
- Migrasi diberi nomor secara terpusat berdasarkan HEAD terbaru; jangan membiarkan dua
  worker menerbitkan nomor sama atau mengubah migrasi yang sudah diterapkan.
- Setiap worker menjalankan check sesuai perubahan, commit lalu langsung push branch
  kerja worker yang sama. Tidak force-push. Koordinator mengintegrasikan commit koheren
  dengan cherry-pick ke `feat/c2-android`, lalu langsung push setelah setiap commit integrasi.
  Ini tidak memberi izin merge ke develop/main atau deploy. Jangan cherry-pick di atas WIP
  yang akan tertimpa; koordinasikan checkpoint/WIP tanpa reset/stash diam-diam.
- Jangan memindahkan seluruh branch/copy folder untuk mengatasi konflik. Tinjau konflik,
  pertahankan behavior dan tests dari kedua slice, lalu jalankan pemeriksaan interop.
- Setelah sukses, jangan langsung menghapus branch/worktree yang masih berisi WIP atau
  evidence unik. Ikuti retirement policy dan pertahankan referensi commit.

## 4. Tiket minimum Worker A — P1 yang dibuktikan PM

Kasus: local commit run pertama berhasil, upload gagal; trigger berikut membuat job baru,
dedupe mempertahankan transaksi/importJobId lama, uploader memilih snapshot job baru kosong,
cursor maju meskipun server belum menerima transaksi awal. Probe PM: expected 1, actual 0.

Implementasikan pending run/snapshot+ack per bulan yang durable dan recovery otomatis sebelum
menutup rentang delta berikutnya. Jangan mengandalkan pengguna menekan Impor. Jangan kembali
ke upload seluruh bulan, memindahkan importJobId semua duplikat, atau mengganti key/body retry.

Acceptance: real import use case + uploader dengan mailbox/server fixture; gagal sebelum ack,
response hilang setelah commit, restart, partial multi-bulan, finalisasi gagal dan email baru
selagi upload pending. Assertions harus membuktikan seluruh kandidat akhirnya ada di server
tepat sekali, snapshot/key/body stabil, cursor tidak melompati pekerjaan. Uji jurnal durable,
owner lifecycle, dan pertahankan delta/no-old-body-fetch. Serahkan patch plus regression,
bukan perubahan status dokumen saja.

## 5. Tiket minimum B/C — tutup backlog fitur, bukan lane baru saja

### S2.4

- Pagination cursor/filter/search server dataset >100 dan equal timestamps.
- Query pada halaman berikutnya, reset saat owner/month/filter/query berubah, late-response
  guard, loaded count versus total server dan load-more/error/retry yang jujur.
- Allowed update/delete, revision/409, unsaved/form recovery dan invalidation antarhalaman.

### S2.5

- Budget plan/limits server, income/savings/gap, CRUD dan revision.
- Analysis canonical, freshness CURRENT/STALE/HISTORICAL, coverage dan consent yang benar.
- Deletion request/receipt/PENDING/COMPLETED, local cleanup dan recovery lintas restart.

### S3/S4 berikutnya tanpa menunggu mandat baru

- C01–C15: CRUDS transaksi, kategori dan anggaran dari Compose sampai persistence.
- Dynamic fields empat entitas sesuai batas/type kontrak.
- C16–C20: lampiran lokal privat + metadata server, CRUDS, unavailable/copy/delete recovery.
- C21–C25: admin/invite/accounts CRUDS, role/status/revocation, audit, last-admin guard.
- Cutover varian penilaian bebas Room/SQLCipher setelah replacement/journal yang sah siap;
  jangan menghapus jalur yang masih diperlukan A sebelum pengganti teruji.
- R1/accessibility, owner isolation dan server validation tetap wajib. Tidak mengirim
  undangan nyata atau membaca finance owner lain melalui peran admin.

Acceptance detail mengikuti mandat existing; jangan menghilangkan baris C01–C25 untuk
mempercepat laporan. Koordinator menempatkan tiket per slice yang cukup kecil untuk cepat
diintegrasikan dan cukup lengkap untuk diuji end-to-end.

## 6. Resource dan verifikasi bersama

- Batasi build Gradle berat dan emulator menjadi satu pemilik pada satu waktu di host ini.
  Gradle home/toolchain cache bersama perlu koordinasi; worktree bukan jaminan bebas lock.
  Static edits, backend checks terisolasi dan unit test ringan boleh paralel sesuai kapasitas.
- Port, database sintetis, mailbox fixture dan output evidence setiap worker harus terpisah.
  Jangan menjalankan migrasi dua runner pada database yang sama atau mematikan UAT pihak lain.
- QA/E2E gabungan dijalankan pada hasil integrasi, bukan menghitung PASS beberapa branch
  independen sebagai satu aplikasi lulus. Verifikasi APK/backend build sesuai commit integrasi.
- Gunakan stub/fake clock untuk race/scheduler/fault injection. Tidak menambah panggilan
  Azure hanya karena jumlah worker bertambah. Semua manual/auto/recovery tetap berbagi
  gate runtime USD0,10/hari, USD2/bulan, attempts/token/concurrency sesuai kontrak.
- Credential hanya pada konfigurasi privat yang relevan, tidak dimasukkan ke prompt worker,
  log atau handoff. Tidak ada schema/data destructive test pada database pemilik.
- Satu worker selesai → koordinator review + integrasi + targeted checks. Setelah paket
  lintas lapisan siap, jalankan emulator/API/DB readback dan update acceptance. Ulangi suite
  menyeluruh pada gate akhir atau jika perubahan/failure baru menuntutnya.

## 7. Handoff worker dan transparansi

Setiap sub-agent mengembalikan:
1. Ticket/acceptance yang ditangani dan status aktual.
2. Branch, worktree, HEAD lokal/remote dan commit yang telah dipush.
3. Daftar file/perubahan dan dependency yang diperlukan koordinator.
4. Perintah test, hasil fresh, evidence tersanitasi dan batas cakupan.
5. Known failures/blocker, WIP dan langkah integrasi berikutnya.

Worker tidak menulis README/checkpoint bersama secara bersamaan. Koordinator menghimpun
hasil, memperbarui baris tanggal WIB pada README/PROGRESS_DAILY, checkpoint dan matriks
acceptance. Bedakan DISPATCHED/RUNNING/IMPLEMENTED/INTEGRATED/VERIFIED/BLOCKED; worker done
belum berarti verified. Catat cleanup menurut retirement policy. Jangan memberi PASS hanya
karena patch ter-push atau unit test worker hijau.

## 8. Definisi selesai dan persistensi

Mandat penutupan sebelumnya tetap berlaku: seluruh acceptance wajib S2–S4, C01–C25 serta
AS/BC/UX/OBS terkait harus dibuktikan, tanpa known defect kritis/tinggi pada scope lokal.
Benchmark berlabel manusia, mailbox/perangkat nyata, backup/restore, hosting/availability
dan owner UAT yang belum dapat dijalankan dicatat sebagai gate eksternal spesifik, bukan
alasan menghentikan pagination/CRUD/admin yang tidak bergantung padanya.

Jangan berhenti ketika worker A selesai sementara B/C masih mempunyai pekerjaan yang sah.
Lanjutkan penugasan, integrasi dan QA sampai scope tertutup atau ada blocker nyata. Jangan
menyebut seluruh proyek selesai bila gate wajib masih terbuka. Tidak mengubah SRS sekarang.

Jika fasilitas sub-agent tidak tersedia, nyatakan keterbatasan aktual; jangan berpura-pura
spawn agent atau menyebut beberapa proses shell sebagai sub-agent. Tetap lanjutkan pekerjaan
yang dapat dikerjakan, dengan prioritas dan checkpoint yang jelas.

**Mulai sekarang:** inventaris WIP → siapkan worktree/ownership → dispatch A/B/C yang
independen → koordinator mengunci kontrak slice pertama dan mengintegrasikan hasil bertahap.
