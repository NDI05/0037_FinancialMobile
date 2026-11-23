# Mandat tunggal SE — tuntaskan sisa Sprint 2, Sprint 3 dan Sprint 4 PAM

> **Cleanup berkelanjutan — 23 November 2026:** setiap penggantian implementasi mengikuti
> [kebijakan retirement](../maintenance/RETIREMENT_POLICY.md) dan AGENTS.md. Hapus kode/
> dependency yang terbukti obsolete, arsipkan dokumen historis bila diperlukan, perbarui
> referensi dan catat pengecualian. Backend Node tetap diaudit terhadap dependency interop
> serta keputusan cutover existing; bukan instruksi menghapus sekarang. SRS resmi dan
> WIP aktif dipertahankan. Cleanup menjadi bagian penutupan perubahan terkait.

> **Perencanaan tambahan pemilik, 23 November 2026:** baca
> [auto-sync/batch/status plan](../plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md) dan
> [CR-005 DRAF SRS](../changes/CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md).
> Scope baru masuk perencanaan perluasan S2 setelah gate classifier aktif; acceptance
> background/perangkat/visual mengikuti rencana. Jangan menyela WIP classifier untuk
> mengaktifkan scheduler dari penambahan dokumen ini. Tidak ada instruksi merevisi
> SRS resmi sekarang; seluruh DOCX/PDF SRS v0.5 tetap dipertahankan sampai pemilik
> meminta konsolidasi. Background sync deferred pada dokumen lama bukan status rencana
> terbaru, tetapi PLANNED juga bukan bukti implemented/PASS.

Pemilik meminta percepatan delivery. Kamu mendapat **satu penugasan untuk tiga sprint berurutan**, mencakup seluruh pekerjaan yang tersisa pada Sprint 2 dan seluruh Sprint 3–4. Lanjutkan melewati checkpoint internal sampai acceptance gabungan terpenuhi. Checkpoint dan commit menjadi alat menjaga correctness, bukan alasan mengakhiri penugasan setelah satu patch atau satu irisan kecil.

Target kualitas adalah tidak ada defect kritis/tinggi atau blocker yang diketahui dan belum diperbaiki pada scope yang diverifikasi. Klaim bebas kesalahan mutlak tidak dapat dibuktikan. Setiap kegagalan yang ditemukan harus diperbaiki, diuji ulang secara relevan, dan dicatat; jangan mengubah assertion/baseline, menonaktifkan lint atau menyebut NOT RUN sebagai PASS untuk mengejar status selesai.

## Arahan resume PM — checkpoint ceea72d

**Prioritas terbaru setelah `74eab38`:** pemilik memilih Azure for Students dan meminta kategori
LLM serta revisi SRS. Jalankan [PROMPT_SE_AZURE_CLASSIFICATION.md](PROMPT_SE_AZURE_CLASSIFICATION.md)
bersama mandat ini. [CR-004](../changes/CR_004_AZURE_LLM_CATEGORIZATION.md) dan SRS v0.5
menggantikan larangan merchant-to-model secara terbatas untuk dedicated classifier dengan
purpose baru; allowlist analisis agregat tetap ketat. Azure deployment/synthetic smoke PASS,
pipeline aplikasi/consent/budgets/benchmark belum. Seluruh backlog S2–S4 tetap berlaku.

**Arahan terbaru sesudah `0541342`:**
[`RESUME_0541342_CLOSE_CR003.md`](RESUME_0541342_CLOSE_CR003.md).
PM targeted 15 tests PASS; checked save dan successful-validation A→B sudah maju, tetapi failure/
generation races, reconnect lifecycle/consent confirmation, transport scope, category Go dan
reclassification belum selesai. SE wajib menjalankan acceptance QA sintetis sendiri, memakai
update APK yang mempertahankan data. Seluruh mandat S2–S4 tetap berlaku.

**Resume aktif sesudah `c68dc38`:** jalankan
[`RESUME_C68DC38_CR003_ACCEPTANCE.md`](RESUME_C68DC38_CR003_ACCEPTANCE.md).
Vault per owner/classifier word matching sudah ada; restore UI masih menuntut App Password
dan ada failure path pergantian sesi saat validasi yang harus ditutup. PM targeted 13 tests
PASS; acceptance emulator/category Go tetap terbuka. Arahan lama di bawah tetap konteks;
mandat S2–S4 belum selesai dan harus diteruskan setelah acceptance CR-003.

**Prioritas owner sesudah `2d279da`:** jalankan
[`RESUME_2D279DA_GMAIL_CATEGORY.md`](RESUME_2D279DA_GMAIL_CATEGORY.md). Pemilik meminta tautan
Gmail bertahan saat logout/login akun PAM yang sama dan kategori merchant QRIS diperbaiki.
[CR-003](../changes/CR_003_GMAIL_SESSION_CATEGORY.md) menggantikan clear-on-logout FR-54 lama;
owner-scoped vault/routing wajib sebelum mempertahankan credential. Review/probe terbaru:
[`PM_REVIEW_2D279DA_GMAIL_CATEGORY.md`](../reviews/PM_REVIEW_2D279DA_GMAIL_CATEGORY.md).
Scope S2–S4 tetap berlanjut. Instruksi checkpoint lebih lama di bawah adalah konteks historis.

**Resume terbaru sesudah worker `4c619f7`:** jalankan
[`RESUME_4C619F7_IMPORT_ACCEPTANCE.md`](RESUME_4C619F7_IMPORT_ACCEPTANCE.md), dengan
temuan/evidence pada [`PM_REVIEW_4C619F7.md`](../reviews/PM_REVIEW_4C619F7.md).
Split-date/time valid sudah lulus targeted test, tetapi invalid calendar/clock masih diterima.
Uploader awal belum menutup retry, durable job/batch identity, counts dan refresh server UI.
Mandat seluruh S2–S4 tetap berlaku; instruksi checkpoint lebih lama di bawah bersifat historis.

**Addendum aktif 22 November 2026:** baca [`RESUME_LIVIN_S2_S4.md`](RESUME_LIVIN_S2_S4.md).
PM menemukan tanggal/jam terpisah pada sampel Livin dan 48 skip `malformed_event_time`
di log emulator; import masih lokal sementara pembaca finance memakai Go. Prioritas baru:
regression MIME/parser → import server sampai UI, dengan gate C2A tetap wajib lalu teruskan
seluruh S2–S4. Addendum membedakan observasi mailbox/log dari acceptance end-to-end dan
memperbarui checkpoint sesudah `9571dc3`. Tidak ada klaim parser/import sudah diperbaiki.

PM telah memeriksa laporan gabungan dan repo. **Mandat tiga sprint belum tercapai.** R1 saat ini merupakan kandidat theme/presentasi; import/pagination/update/delete/budget/analysis dan hampir seluruh S3/S4 masih terbuka. Laporan ini menjadi checkpoint lanjutan, bukan akhir penugasan.

Lanjutkan dari kondisi aktif tanpa mengulang redesign/audit fondasi. Worker melaporkan refresh 2,53 detik, tetapi before/after satu record dan “revision 0” belum mengikat fresh mutation/key/UI baru. Bedakan revisi record dari `month_revisions`, gunakan baseline per-run dan rekonsiliasi bukti. Lost-create-response masih OPEN pada Android.

PM telah menyiapkan **proxy QA deterministik yang sudah terbukti terhadap Go**: `scripts/qa/committed_response_proxy.py`, verifier `scripts/qa/verify_committed_response_proxy.py`, dan runbook `docs/QA_C2A_COMMITTED_RESPONSE_LOSS.md`. Proxy menunggu upstream 201 lalu memutus downstream sebelum header. Bukti Go/proxy/Python client tersedia di `docs/evidence/`; itu bukan PASS Android. Gunakan tool tersebut dengan build/AVD/database sintetis terpisah untuk menutup gate UI, bukan kembali menambah hook ke produksi. UI boleh langsung sukses jika trace membuktikan retry otomatis dengan key/body sama dan satu row/revisi. UI error bukan syarat kelulusan; evidence drop/retry/DB yang menentukan.

Sesudah verifikasi yang benar, langsung kerjakan **fingerprint key + import job/batch/resume + pagination/filter server**, lalu teruskan seluruh sisa §4–6. C2B1 adalah milestone internal, bukan batas akhir tiga sprint. Tool QA dan dokumentasi baru juga bukan hasil akhir penugasan.

Pemilik berencana mengetik sendiri email asli untuk uji login. Gunakan password khusus PAM; Gmail IMAP memakai App Password, bukan password utama Gmail. Jangan meminta secret ke chat/source/log. Rencana login manual bukan izin memakai akun/perangkat asli untuk fault injection, `pm clear`, uninstall, deletion atau sweep. Semua QA destructive memakai akun/AVD/app/database sintetis terpisah; bukti login nyata tidak menggantikan acceptance reliabilitas/import/CRUDS.

R1 tidak perlu dipoles berulang-ulang sementara jalur data belum terintegrasi. Perbaiki visual yang memblokir penggunaan/aksesibilitas segera; catat deviasi lainnya untuk gate visual. PM melihat B01 masih memakai hero gradient biru dan B04 masih mempunyai komposisi legacy yang berbeda dari kontrak/sumber; owner visual UAT belum selesai. Jangan menyebutnya seluruh redesign final atau menggunakan Roborazzi sebagai pengganti emulator server E2E.

**Klarifikasi pemilik, 22 November 2026:** push sebelumnya memang diperintahkan pemilik. Penilaian PM bahwa push tersebut melanggar otorisasi dibatalkan. Pemilik menetapkan: **setiap commit langsung dipush ke branch kerja yang sama**. Instruksi ini menggantikan semua larangan push pada mandat/prompt PAM sebelumnya. Setelah commit lokal berhasil, segera jalankan push; jangan menunggu akhir sprint atau meminta izin push berulang. Pastikan push sukses dan catat hash/ref remote. Jika gagal, catat error, pertahankan commit dan tangani penyebabnya tanpa force-push atau membuang history. Merge/deploy tetap memerlukan instruksi tersendiri.

Worker telah mengklarifikasi alasan run sebelumnya berhenti: menutup turn setelah dokumentasi, bukan blocker cloud/Gmail/tool. Jangan mengulang aktivitas tersebut sebagai alasan jeda berikutnya. Gunakan checkpoint untuk terus mengimplementasikan backlog dalam sesi yang tersedia. Bila batas sesi/tool/context benar-benar terjadi kemudian, catat bukti dan langkah lanjut secara spesifik, tanpa mengarang penyebab atau menandai scope yang belum dikerjakan sebagai selesai.

## 1. Checkpoint dan rekonsiliasi pekerjaan aktif

- Workspace `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir`; root Git `pam-app`.
- Snapshot resume PM branch `feat/c2-android`, HEAD dan remote **`ceea72d`**, worktree bersih sebelum penambahan tool QA PM. Gate A `cd19ee5`, consent `0566824` dan direct-commit note `04bf384` dipertahankan. Periksa HEAD yang sebenarnya saat mulai; commit tool/dokumen PM yang lebih baru juga harus dipertahankan.
- Theme/font R1 yang sebelumnya belum di-commit sekarang tersimpan pada `ebc6dbf`/`d5a2bad` dengan handoff `3ed82cc`; living checkpoint `fb68397`. Pertahankan hasilnya dan perubahan lebih baru yang mungkin ada. Selesaikan operasi aktif ke titik konsisten; inventaris pemilik perubahan dan tes yang sedang berjalan. Jangan reset/clean/checkout paksa atau menimpa pekerjaan lain.
- Stack tetap Android Kotlin/Jetpack Compose/Navigation/MVVM/Hilt, backend Go dan Turso Cloud Free/libSQL. Node tetap referensi/interop selama dibutuhkan. Satu database hanya mempunyai satu migration runner aktif.
- Mandat ini menggantikan batas berhenti per paket pada prompt Sprint 2/3/4 sebelumnya. Requirement dan perbaikan yang sudah disepakati tetap berlaku. Sesudah gate satu tahap lulus, commit/checkpoint lalu **langsung kerjakan tahap berikutnya**.
- Kerjakan implementasi lokal, data sintetis dan DB uji terisolasi. Lindungi bukti sebelumnya seperti `/tmp/pam-c2a.db`. **Setiap commit langsung dipush ke branch kerja yang sama sesuai instruksi pemilik.** Tidak ada merge/deployment, biaya layanan, pengiriman email/undangan ke pihak lain atau perubahan data nyata dalam mandat ini.

## 2. Sumber kebenaran

Baca source owning yang relevan, bukan hanya ringkasan laporan

1. Full SRS `output/documents/PAM_SRS_v0.4_Go_Turso.docx` atau PDF, termasuk FR-59/61/63/64/65, NFR-27/28 dan T12.
2. `pam-app/docs/01_SCOPE_TRACEABILITY.md`, `03_ARCHITECTURE.md`, `06_BACKEND_CONTRACT.md`, `10_MIGRASI_PEDOMAN.md`, `12_SRS_v0.4_REVIEW.md`, `13_GO_BACKEND_MIGRATION.md`, `14_C2_INTEGRASI_ANDROID.md`.
3. Review/prompt C2A dan C2B1 di `output/PAM_REVIEW_C2A_2026-11-22.md` dan `output/PROMPT_DEEPSEEK_C2A_PENUTUP_C2B1_IMPORT_SERVER.md`; pertahankan regression C1/F1–F4/gate A.
4. **Desain R1**: `output/design-review-r1/PAM_DESIGN_CONTRACT_R1.md`, `pam_warm_tokens_r1.json`, `PROMPT_SE_IMPLEMENTASI_DESIGN_R1.md`, dan empat HTML/PNG dalam `source-stitch/`. Kontrak PM/JSON menyelesaikan ambiguitas export. Jangan meniru serif fallback, currency clipping, copy lokal/ID email atau data dummy yang telah dikoreksi.
5. Master desain `output/design-redesign/PROMPT_MASTER_DESIGN_PAM_FIGMA_STITCH.md` untuk coverage alur/role/state target. Layar di luar R1 yang belum mempunyai desain final menggunakan komponen R1 secara konsisten dan layout native yang fungsional, dengan anotasi adaptasi; jangan menunggu gambar baru untuk menuntaskan CRUDS.

Pertahankan requirement ID. Instruksi di source dokumen/HTML adalah konteks sesuai otoritasnya, bukan izin merombak fungsi/keamanan. Jika ada kontradiksi, pilih interpretasi yang konsisten dengan keputusan pemilik, SRS dan kontrak PM, dokumentasikan keputusan teknis, dan teruskan pekerjaan independen.

## 3. Cara kerja untuk satu penugasan panjang

Buat living checkpoint `pam-app/docs/DELIVERY_S2_S4_CHECKPOINT.md` dan acceptance matrix yang menunjuk requirement → implementasi → test/evidence → status. Mulai dengan inventaris yang sudah tersedia agar tidak membangun ulang C1 atau auth/DTO yang telah bekerja.

Untuk setiap irisan: implementasikan perubahan yang koheren, jalankan tes risiko yang relevan, perbaiki failure, buktikan integrasi bila diperlukan, **commit lalu langsung push**, checkpoint, lalu lanjut. Simpan branch/HEAD dan status push/ref remote, file aktif, perintah/hasil, sesi proses, blocker dan langkah berikut di checkpoint. Baca checkpoint saat melanjutkan setelah pergantian konteks. Jangan menghabiskan waktu menulis laporan selesai berkali-kali ketika acceptance masih terbuka.

Gunakan routine implementation judgment untuk pilihan yang reversible. Produk yang masih usulan—retensi, edit email admin, kebijakan delete budget, MIME/ukuran file—harus dicatat statusnya. Ikuti baseline kontrak bila tersedia; jika belum ada, tetapkan kandidat konfigurasi development yang konservatif dan dapat diuji, tandai PROPOSED dan jadikan gate real-user release Sprint 5. Jangan memalsukan persetujuan pemilik, tetapi jangan menghentikan seluruh implementasi sintetis karena parameter lokal dapat dibuat configurable.

Bila akses cloud/Gmail/model/perangkat fisik belum ada, catat NOT RUN dan lanjutkan seluruh implementasi serta test lokal yang independen. Jangan meminta credential melalui source/log/laporan. Bila menemui hambatan teknis lokal, investigasi dan usahakan perbaikan yang aman; hanya dependency yang benar-benar tidak dapat dipenuhi boleh dilaporkan sebagai blocker, dengan pekerjaan lain tetap diteruskan.

Jika kapasitas runtime/konteks agent memaksa jeda, simpan checkpoint lengkap dan tandai pekerjaan belum selesai. Itu bukan hasil akhir tiga sprint. Saat bisa melanjutkan, baca checkpoint dan teruskan tanpa mengulang audit dari nol.

## 4. Sprint 2 — tuntaskan integrasi server dan visual R1

### S2.1 Tutup gate C2A dengan bukti

Pertahankan custom category/provenance/wire validation yang sudah diperbaiki. Buktikan refresh list/overview sesudah save pada instance/navigation yang sama dalam <5 detik. Uji GET detail 404/unavailable/parsing yang berbeda, serta prior-month/recent failure yang tidak menjadi comparison/list kosong palsu.

Buktikan durable pending key sebelum POST, wrapping/write failure tanpa submit, restart/recovery, response hilang setelah server commit → retry menghasilkan satu row dan satu kenaikan revision, dua tap satu aksi, serta dua aksi identik yang memang berbeda menghasilkan dua record. Jangan memakai backend unit test saja untuk membuktikan lifecycle key Android. Gunakan fault injection yang terkontrol dengan DB/harness sintetis.

### S2.2 Terapkan visual R1 yang sudah diarahkan PM

Selesaikan theme/font/token work yang sedang aktif. Implementasikan B01 Overview, B02 Transactions, B03 Detail dan B04 Manual secara native Compose sesuai kontrak R1. Gunakan Manrope offline, semantic tokens dan komponen bersama; perbaiki currency Rp, copy, hierarchy dan constraints yang telah disebut PM.

Jangan hardcode dataset delapan transaksi pada akun live. Limit/progress/counts/refund/source harus memakai data server yang sah; field yang belum tersedia diberi state yang jujur. Screen lain yang terkena theme global tetap terbaca/usable. State loading/error/offline/partial/conflict/unsaved/busy/owner switch harus tetap benar.

Visual R1 diimplementasikan pada checkpoint yang koheren dengan gate correctness. Setelah lolos pemeriksaan relevan, langsung lanjut integrasi berikut; owner visual UAT tetap bukti terpisah dari keberhasilan engineering lokal.

### S2.3 Consent, fingerprint dan import server

Tuntaskan consent server-confirmed yang baru sebagian. Gmail dan AI independen; manual/read finance tidak mensyaratkan consent Gmail/AI. Local pending/recovery bukan bukti server GRANTED. Withdrawal segera memblokir pekerjaan baru yang relevan dan direkonsiliasi server.

Gunakan account fingerprint key server yang stabil, privat/terwrap dan owner-scoped. IMAP TLS/App Password/search/MIME/parser tetap di perangkat. Kandidat ke Go hanya data tersanitasi; raw MIME/body/subject/sender/message ID, PAN/credential/token tidak disimpan/dikirim/log sebagai kandidat.

Implementasikan remote job create/read, batches, finalisasi dan resume dari UI/use case. Stable batch key/body selama retry; counts/revision/coverage dari server; duplicate fingerprint tidak menimpa koreksi pengguna. Batas batch/body dan bulan Asia/Jakarta sesuai kontrak. UNRESOLVED/skipped memakai reason codes yang aman dan definisi counts yang konsisten.

Uji batch rollback, respons batch/finalisasi hilang, restart API/app, partial resume, reimport, consent withdrawal, credential revoked dan dua owner. Pertahankan regression tahap A pada onboarding, pilihan AI saat Gmail ditolak, HTML cell boundary, attachment count dan batas write MIME 1 MiB.

### S2.4 Pagination, transaksi update/delete dan konflik

Hubungkan query/filter/pagination server sampai UI. Cursor di-reset pada owner/month/query/filter berubah, late response tidak mengisi state baru. Dataset >100, timestamp sama dan search di halaman berikutnya wajib benar. Loaded count berbeda dari total bulan/hasil. Summary tetap data seluruh scope server.

Implementasikan koreksi kategori/note/attributes, transfer/refund link dan delete owner-scoped sesuai kontrak. Nominal/tanggal email tetap read-only. Update memakai If-Match/revisi; conflict 409 mempertahankan input dan meminta review state terbaru. Tidak ada fallback write ke Room atau overwrite diam-diam. Refresh/invalidate list/summary/analysis yang terdampak sesudah acknowledgement.

### S2.5 Budget, analysis dan lifecycle deletion UI

Pindahkan budget plan/limits/revision, summary dan analysis ke remote source. Analisis memakai canonical aggregate/dependency revision server; CURRENT/STALE/HISTORICAL serta coverage COMPLETE/PARTIAL/EMPTY/FAILED tampil berbeda. Pertahankan consent/cache/idempotency/output validation/kuota/biaya yang telah diperbaiki. Kegagalan AI/Gmail tidak membuat data finance yang masih tersedia ikut hilang.

Tuntaskan deletion akun PAM dengan password/explicit confirmation, server accepted/PENDING, local cleanup, receipt privat sesudah logout/restart dan server COMPLETED. Salah password/error jaringan tidak menyebabkan success/wipe palsu. Deletion tidak mensyaratkan Gmail. Uji lost response, retry/recovery, cleanup gagal, owner isolation dan last-admin guard.

### Gate Sprint 2

Emulator → Go lokal → DB sintetis harus membuktikan manual, import, list/detail/search/pagination/edit/delete, summary, budget, analysis freshness, refresh/logout/relogin dan deletion receipt. Buktikan restart, clear app data sintetis/login ulang, dua akun, unavailable, conflict dan no-duplicate retry. Visual R1 dan correctness diuji terpisah. Setelah gate lulus, checkpoint lalu **langsung masuk Sprint 3**.

## 5. Sprint 3 — tiga CRUDS lengkap dan field dinamis

Lengkapi seluruh jalur transaksi, kategori dan anggaran yang belum memenuhi Create/Read/Update/Delete/Search dari Compose hingga API dan persistence. Inventaris operasi Sprint 2 yang sudah lulus; gunakan ulang, bukan mengimplementasikan ulang.

- **Transaksi**: manual create, list/detail, koreksi yang diizinkan, soft delete dan search/filter/cursor; provenance/revision/custom category tetap utuh.
- **Kategori**: create/list/detail/update/delete/name search untuk kategori pengguna. Built-in terlindungi; reassign transaksi/limit atomik atau ditolak jelas. Identitas/kind/code tidak hilang karena enum lama.
- **Anggaran**: create/list/detail/update/delete/search berdasarkan periode/metadata yang didukung kontrak; expected income, savings, limits, gap dan revision benar. Jika list/search belum ada di API, lengkapi kontrak/endpoint minimum yang dibutuhkan requirement, bukan menaruhnya kembali di Room.
- **Dynamic fields**: transaksi, kategori dan budget plan mempunyai editor/readback yang tervalidasi. Maks. 10 pasangan, key ≤40 karakter, teks ≤200 karakter, encoded attributes ≤2 KB dan tipe sesuai kontrak. DTO mempertahankan tipe JSON. Validasi duplicate/invalid keys, overflow, corruption dan conflict, tanpa membocorkan attributes ke model AI.
- **UI**: gunakan tokens R1 dan reusable forms/rows/sheets/error states. Layar belum disediakan Stitch dibuat konsisten dan diberi catatan adaptasi. Search mempunyai results/no-results, edit punya unsaved/conflict, delete punya impact/confirmation yang akurat.

### Gate Sprint 3

Kelima belas aksi tiga fitur pertama pada matriks §8 dapat dijalankan lewat UI/API dan dibaca kembali setelah restart. Uji cross-owner, kategori custom/reassign, tanggal/amount/refund/transfer, dynamic field limits dan search pagination. Checkpoint lalu **langsung masuk Sprint 4**.

## 6. Sprint 4 — lampiran, admin dan pengeluaran Room

### S4.1 Lampiran/file privat dan metadata server

Implementasikan lengkap create/read/update/delete/search lampiran pada transaksi. Android system document picker, validasi tipe/size, copy ke penyimpanan privat owner, metadata Go dan relasi transaksi owner-scoped. Tidak menambah camera permission dari mockup. Byte file tidak diunggah/dijanjikan cloud sync; metadata server tidak membuktikan file tersedia di perangkat lain.

Pastikan copy→metadata dan delete metadata→file mempunyai cleanup/retry yang konsisten. Uji metadata gagal sesudah copy, copy gagal, respons hilang, delete file gagal, transaksi dihapus dan app crash/restart. File unavailable pada perangkat lain ditampilkan jujur dengan aksi yang sah, bukan tombol buka palsu. Hindari path traversal, unsafe filename dan akses lintas owner. Dynamic fields lampiran mengikuti batas yang sama dan dibaca ulang setelah restart.

Gunakan tipe/ukuran yang sudah ditetapkan kontrak. Jika belum final, buat batas konfigurasi kandidat development beserta keputusan PROPOSED dan tests over-limit/unsupported; jangan menganggap label 10 MB di mockup sebagai persetujuan kebijakan. Laporan release Sprint 5 tetap mencantumkan keputusan yang perlu disahkan.

### S4.2 Admin Go dan UI mobile

Lengkapi Go endpoint/service/persistence serta Compose UI untuk account create/invite, list/detail, update role/status/display name, deactivate/soft-delete dan search/filter/cursor. Public register selalu USER. Provisioning admin melalui mekanisme CLI/ops development yang terkontrol, idempoten dan diaudit; jangan membuka route bootstrap publik atau role escalation dari client.

Invitation terikat email/role, expiry, sekali pakai dan hanya hash disimpan sesuai kontrak. Token tampil sekali bila diperlukan di alur admin terkontrol. Tidak ada pengiriman email otomatis ke pihak lain. Nama/email pengguna dan secret tidak masuk log.

USER tidak melihat menu admin dan request admin tetap ditolak server. ADMIN mengelola akun tanpa membaca data keuangan owner lain. Role/status change/revocation mencabut akses/sesi sebagaimana kontrak. Last active admin dilindungi pada API/database/UI termasuk race. Email login read-only sebagai baseline kebijakan yang belum disahkan untuk diedit. Semua tindakan penting mempunyai audit record yang aman dan revision conflict handling.

### S4.3 Empat entitas dan lima CRUDS

Transaksi, Category, BudgetPlan dan AttachmentMetadata harus berelasi dengan owner/FK yang benar dan mempunyai dynamic fields yang benar-benar diinput, disimpan, dibaca, diedit lewat Compose. Lampiran dan admin menutup aksi #16–25 pada matriks. Jangan memenuhi UI dengan tombol no-op, placeholder success atau data statis.

### S4.4 Room/SQLCipher keluar dari varian penilaian

Selesaikan remote bindings/use cases serta file/preferences privat yang sah sebelum memutus jalur lama. Varian penilaian harus tidak memakai, membundel atau bergantung pada Room/SQLCipher sebagai database keuangan. Runtime flag yang tidak pernah memanggil DAO belum membuktikan dependency keluar.

Periksa Gradle dependency graph/source sets/manifest dan artefak APK yang relevan. Hilangkan dependency/import/initialization/database passphrase/DAO/entity/worker yang obsolete pada varian penilaian, sambil mempertahankan Keystore/token/receipt/file security yang masih diperlukan. Legacy boleh tetap hanya bila benar-benar terpisah dari varian penilaian dan dijelaskan; jangan menjadikan itu fallback diam-diam ketika API mati.

Migrasi/penghapusan data lokal pengguna nyata bukan scope mandat ini. Gunakan data sintetis dan dokumentasikan strategi transisi. Tests yang khusus implementasi Room yang telah dipensiunkan boleh direkonsiliasi dengan alasan serta replacement behavior tests; jangan menghapus security/finance regression untuk mengejar jumlah tes. Backend Node tetap dipertahankan sebagai referensi sampai keputusan pemulihan/final cutover terpisah.

### Gate Sprint 4

Seluruh 25 aksi §8 lulus, empat entitas/dynamic fields terbukti setelah restart, lampiran dapat dibuka ulang secara privat, metadata-only/unavailable state benar, admin/USER/last-admin/revocation teruji, dan varian penilaian bebas Room/SQLCipher sesuai audit dependency/APK. Setelah gate ini lulus, jalankan integrasi akhir §9 dan kirim laporan gabungan.

## 7. Invarian lintas tiga sprint

Owner berasal dari sesi server; role bukan pilihan client. Uang exact minor integer, waktu Asia/Jakarta, gross/classified/category net/refund/transfer mengikuti SRS. Custom category tidak hilang dan proyeksi model tetap memakai safe codes tanpa label/attributes bebas.

Auth generation/commit lock, single-flight termasuk 401, retry bounded, idempotency dan dependency invalidation tidak boleh mundur. Request lama sesudah logout/account switch/deletion tidak boleh menulis/menerbitkan state owner yang salah.

Tidak ada raw email/credential/token/receipt/path sensitif di log/artifact. Loading/empty/failed/unavailable/stale mempunyai state berbeda. Tidak ada fake success, sample balance, jumlah filter palsu dari halaman parsial, confidence/parser buatan atau file upload yang hanya terlihat bekerja.

R1 adalah empat layar referensi final untuk kandidat lokal. Screen lain mengikuti sistemnya dengan anotasi adaptasi; tidak mengklaim semua 37 desain telah mendapat owner visual UAT. Minimal 48 dp touch, font 200%, safe-area/IME, TalkBack, full IDR dan kontras yang dibuktikan tetap acceptance produk.

## 8. Matriks 25 aksi CRUDS wajib

Catat frame/route, endpoint, test/script, evidence UI/persistence dan hasil untuk setiap baris. Satu tes dapat mendukung beberapa baris bila assertion-nya memadai.

| ID | Fitur | Aksi | Bukti minimum |
|---|---|---|---|
| C01 | Transaksi | Create | manual ack, key stabil, retry satu record |
| C02 | Transaksi | Read | list/detail/cursor dari server, provenance lengkap |
| C03 | Transaksi | Update | allowed correction, revision/409, summary invalidation |
| C04 | Transaksi | Delete | hilang dari aktif/total, tidak resurrect saat replay |
| C05 | Transaksi | Search | query/filter benar termasuk halaman berikutnya |
| C06 | Kategori | Create | kategori custom tersimpan dan dapat dipilih |
| C07 | Kategori | Read | ID/code/label/kind/attributes utuh |
| C08 | Kategori | Update | perubahan terbaca ulang, revisi/semantik terjaga |
| C09 | Kategori | Delete | built-in guard, used-category reassign/protect atomik |
| C10 | Kategori | Search | nama/query owner-scoped, no-results benar |
| C11 | Anggaran | Create | plan/limits terkait kategori tersimpan |
| C12 | Anggaran | Read | month/list/detail/gap/actual benar |
| C13 | Anggaran | Update | limits/attributes/revision/conflict benar |
| C14 | Anggaran | Delete | sesuai kontrak kandidat, impact dijelaskan |
| C15 | Anggaran | Search | periode/metadata yang disepakati, persistence benar |
| C16 | Lampiran | Create | private copy + metadata owner/transaction konsisten |
| C17 | Lampiran | Read | buka ulang sesudah restart; unavailable pada device lain |
| C18 | Lampiran | Update | display name/attributes round-trip dan conflict |
| C19 | Lampiran | Delete | metadata/file cleanup/retry konsisten |
| C20 | Lampiran | Search | nama/context/owner, hasil dan file status benar |
| C21 | Admin pengguna | Create | controlled create/invite, public register tetap USER |
| C22 | Admin pengguna | Read | list/detail aman, tanpa finance owner lain |
| C23 | Admin pengguna | Update | role/status/version/revocation dan audit |
| C24 | Admin pengguna | Delete | deactivate/soft-delete, last-admin guard dan revoke |
| C25 | Admin pengguna | Search | query/role/status/cursor, USER ditolak |

## 9. Verifikasi gabungan dan penyelesaian

Gunakan akun A/B dan ADMIN sintetis, DB uji terpisah, provider AI fake/local, fixture mailbox dan file uji milik test. Buat script/runbook acceptance yang dapat dijalankan ulang tanpa data nyata atau credential eksternal. Jangan menjalankan destructive clear/delete pada dataset pemilik.

Wajib ada bukti actual

- Android emulator → Go → DB untuk alur utama serta 25 aksi, bukan hanya screenshot atau MockWebServer.
- Restart API/app, clear app data sintetis lalu login ulang, dua owner, session/role switch dan account deletion.
- Respons create/batch hilang, duplicate/reimport, restart/resume, invalid candidate dan rollback.
- Dataset >100, equal timestamps, search halaman berikutnya dan filter berubah saat request berjalan.
- 401/refresh/logout race, revision conflict, consent/deletion race saat proses berjalan, last-admin concurrency dan cross-owner/FK guards.
- Dynamic fields empat entitas, type/size bounds, read/edit sesudah restart; file MIME/size/path/owner/missing/copy/delete failures.
- API mati/form recovery, provider error/invalid output/quota, current/stale/historical dan coverage yang benar.
- R1 screenshots pada 360/393/412, font 100/200%, long content, keyboard/safe-area, serta smoke readability screen lain.
- Audit varian penilaian untuk Room/SQLCipher dan secret/log exposure.

Jalankan targeted checks selama implementasi. Pada checkpoint gate dan final, jalankan suite relevan yang menyeluruh: Android unit, assemble varian penilaian/debug yang diuji, lint; Go fresh tests/race/vet/build bila backend berubah; interop/migration tests ketika kompatibilitas tersentuh. Jangan mengulang suite berat tanpa alasan setelah hasilnya valid dan tidak ada perubahan terkait. Batch independent checks jika resource memungkinkan; emulator/migration/mutasi state tetap dikoordinasikan.

Lint/build harus nol error. Warning existing dicatat, regression baru yang berdampak diperbaiki. Jangan mematikan lint, menghapus assertion atau mengganti golden tanpa review visual untuk membuat status hijau. Jumlah tes bukan target; bukti perilaku yang menentukan.

Jika verification gagal, perbaiki akar masalah dan ulangi checks yang terdampak sebelum lanjut gate. Beri status PASS/FAIL/NOT RUN/PARTIAL secara jujur. Pada scope lokal wajib, NOT RUN/PARTIAL bukan gate lulus.

## 10. Batas Sprint 5 dan laporan akhir

Mandat tiga sprint selesai ketika gate Sprint 2/3/4 serta verifikasi gabungan lokal terpenuhi, 25 aksi terpetakan, varian penilaian memenuhi persyaratan penyimpanan dan tidak ada known blocker kritis/tinggi yang belum ditutup. Ini kandidat lengkap lokal, bukan klaim production/penilaian final tanpa Sprint 5.

Turso remote/backup/restore, Gmail/provider nyata, perangkat fisik, hosting/HTTPS publik, performance benchmark dan keputusan kebijakan produk real-user menjadi gate Sprint 5 bila akses/otorisasinya belum ada. Tidak memalsukan hasilnya untuk menyatakan seluruh proyek selesai. Jika database cloud uji sudah diotorisasi dan tersedia, pengujian yang aman boleh dikerjakan dan dibuktikan terpisah; jangan menyentuh database existing/nyata.

Kirim satu laporan gabungan yang memuat

1. Branch/HEAD terkini, kondisi pekerjaan awal/theme yang dipertahankan, rangkaian checkpoint, commit dan bukti push/ref remote.
2. Tabel Sprint 2/3/4 → deliverable → requirement → acceptance evidence → status.
3. Matriks 25 aksi, empat entitas/dynamic fields, API/source bindings, admin/file lifecycle dan audit Room removal.
4. Perintah/test results aktual, emulator runbook, screenshot paths dan assertions DB/API yang tersanitasi.
5. Perubahan schema/contract/design tokens/fonts/assets serta source→Compose mapping R1.
6. Defect yang ditemukan/diperbaiki dan regresi yang membuktikannya; known limitations dan hasil pemeriksaan akhir.
7. External NOT RUN dan usulan kebijakan yang masih perlu disahkan pada Sprint 5, dengan dependency spesifik.

Update README, scope traceability, API/architecture, migration/parity, docs 14 dan living checkpoint agar mencerminkan implementasi final. Jangan hanya menambahkan klaim PASS di dokumen tanpa evidence.

**Mulai sekarang dari checkpoint aktif. Tuntaskan sisa Sprint 2 → Sprint 3 → Sprint 4 dalam penugasan ini. Checkpoint antar tahap dicatat internal; lanjutkan pekerjaan sampai acceptance gabungan tercapai atau ada hambatan eksternal nyata yang telah diisolasi dan seluruh pekerjaan independen sudah dituntaskan.**
