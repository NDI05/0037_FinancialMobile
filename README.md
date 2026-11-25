# PAM — Personal Finance Android App

PAM mengubah notifikasi email **Livin by Mandiri** dan transaksi manual menjadi
rekap arus kas bulanan. Pengguna dapat meninjau kategori, meminta bantuan kategorisasi
Azure, dan mengakses analisis sesuai consent masing-masing. Perencanaan anggaran pensiun menurut CR-008.

**Status:** dalam pengembangan · **Sprint aktif:** S2 / PARTIAL
**Pembaruan dokumentasi:** 25 November 2026 · **Branch kerja:** `feat/c2-android`
**SRS resmi:** v0.5 · **Release/merge/deploy:** belum dinyatakan selesai

> README ini adalah pintu masuk proyek. Riwayat pekerjaan, hasil tes, dan gate yang belum
> lulus dibedakan; commit atau jumlah tes tidak otomatis membuktikan fitur selesai.
> **Snapshot gate Gmail/retensi 24 November 2026 (historis):** Android HEAD `51985f3` (kode app `e67380a`;
> fresh **678 tes/88 suite/0 gagal**, lint **0 error/123 warning/1 hint**, APK debug
> **47.992.939 byte**, sha256 `5368c92f…` — build UAT ronde 7 dengan Web OAuth client ID owner),
> backend `integration/2026-11-24` HEAD `00bb515` (kode `c69cf57`; vet/build/test/race PASS),
> kontrak polling §9 **QA E2E 82/82 PASS** diulang pada `00bb515` (fake provider) dan **UAT
> perangkat FIXTURE PASS**. **Gmail live (G25) PASS untuk akun pilot owner** — data 914 transaksi
> dihasilkan runtime build `c98527b` (parser `5df6be6`/`c98527b`; 0 duplikat). **Login Google nyata
> di HP (G36) sudah terbukti** 24 Nov 21:49–21:50 WIB: sesi PAM terbit, identitas terikat, 914
> transaksi tetap unik; sisa G36 = tombol **Run literal** (`lastUpdateTime` tetap 19:28:04) dan
> onboarding Gmail, jadi G36 **PARTIAL**. **GCP Rp0 (G29/G37)** tetap **OPEN**. Snapshot APK lama (`7efc1803…`, `8ce4763c…`,
> `41584bae…`) hanya riwayat; artefak gate final mengikuti snapshot SRS v0.7 di bawah (`ef34261b…`).
> **Snapshot gate SRS v0.7 (24 November 2026):** Android HEAD **`b885555`** (fresh `--rerun-tasks` **613 tes/88 suite/0 gagal**, lint **0 error/117 warning/1 hint**, APK debug **47.628.443 byte** sha256 `ef34261b…` = fingerprint build; `a629c41` 608/87 APK `e7a1f4af…` adalah build UAT awal), backend HEAD **`dd5bc88`** (+ dokumen `1da82ec`; vet/build/test PASS). UAT HP fisik via `adb install -r` membuktikan entri manual+catatan, pensiun tiga modul (UI mati, API 404 semua method, data/migrasi tetap), Analisis tersimpan, FR-32, sheet status, dan logout→login Google; G14 data pemilik/G24/G29/G37/Run literal/onboarding Gmail/email saat app tertutup tetap **NOT RUN/OPEN**. [Gate akhir v0.7](docs/delivery/FINAL_GATES_SRS_V07_2026-11-24.md) · [UAT final](docs/evidence/FINAL_UAT_SRS_V07_2026-11-24.md) · [audit final](docs/evidence/AUDIT_V07_FINAL_2026-11-24.md).
> **Snapshot verifikasi ulang 25 November 2026:** Android `main` **`733c0fa`** (== `origin`, worktree bersih; **621 tes debug** lulus; APK terpasang `39f2cd6e…` 47.628.023 B, APK Vercel `f95f8dea…` 48.837.129 B belum dipasang); backend `main` **`5789f54`** (== `origin`; vet/build/11 paket tes lulus; migrasi **22**; CI `45573339049` success). **FR-67 PASS** (gate bertahan sampai `months_done=13`, lalu tepat 4 panggilan provider, sisa PENDING, poll berikut `not_due`), **NFR-15 PASS** (future 400, hari ini 201, retry identik 200 id sama, cleanup 204), **G14 fixture PASS 59/59**, smoke emulator terbuka tanpa crash. **Bug nominal impor email dari laporan pemilik diperbaiki** (parser rupiah→minor, fixture jujur, migrasi 022 memperbaiki 915 baris EMAIL pada DB UAT lokal; MANUAL utuh). **G36 PARTIAL** (Run literal dikonfirmasi pemilik; sisa onboarding Gmail + email Livin saat app tertutup), **G24 PARTIAL (demo Vercel; produksi NOT RUN)**, G29/G37/OI-27/OI-28 dan deploy produksi tetap terbuka (opsi OI-27/OI-28: [memo keputusan](docs/delivery/DECISION_MEMO_OI27_OI28_2026-11-25.md); langkah G36 untuk pemilik: [runbook G36](docs/delivery/OWNER_G36_RUNBOOK_2026-11-25.md)). **Azure penuh + optimalisasi token** diaktifkan di UAT lokal atas keputusan pemilik 25 Nov (analisis + kategorisasi; demo Vercel sengaja tetap fake): [bukti](docs/evidence/AZURE_FULL_OPTIMIZATION_2026-11-25.md). Bukti: [recheck gate](docs/delivery/GATE_RECHECK_2026-11-25.md) · [bugfix nominal](docs/evidence/AMOUNT_UNIT_BUGFIX_2026-11-25.md) · [kesiapan deploy](docs/delivery/DEPLOYMENT_READINESS_NOTE_2026-11-25.md).
> Rincian gate Gmail/retensi: [laporan gate akhir](docs/delivery/FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md).
> Snapshot lama `ce56d9c` + [review recovery PM](docs/reviews/PM_REVIEW_CE56D9C_SYNC_RECOVERY.md)
> tetap menjadi riwayat. Hasil TERLAPOR SE dan WIP yang belum diverifikasi tidak dihitung
> sebagai acceptance penuh.

## Daftar isi

1. [Ringkasan dan batas produk](#1-ringkasan-dan-batas-produk)
2. [Status sprint dan fitur](#2-status-sprint-dan-fitur)
3. [Progres mingguan (Weekly Progress)](#3-progres-mingguan-weekly-progress)
4. [Progres harian](#4-progres-harian)
5. [Arsitektur dan struktur repository](#5-arsitektur-dan-struktur-repository)
6. [Menjalankan proyek lokal](#6-menjalankan-proyek-lokal)
7. [Pengujian dan bukti](#7-pengujian-dan-bukti)
8. [Konfigurasi, data, dan biaya](#8-konfigurasi-data-dan-biaya)
9. [Dokumentasi dan cara berkontribusi](#9-dokumentasi-dan-cara-berkontribusi)

## 1. Ringkasan dan batas produk

| Kebutuhan | Perilaku yang dituju |
|---|---|
| Pencatatan | Impor notifikasi Livin yang didukung parser, serta transaksi manual |
| Rekap | Perhitungan IDR deterministik, kategori, refund dan transfer internal |
| Kategori | Koreksi pengguna dipertahankan; saran AI mempunyai provenance dan status review |
| Pembaruan | Auto-sync Gmail dan antrean kategori terpisah, dengan status yang dapat diperiksa |
| Analisis | Analisis berbasis agregat; integrasi UI mengikuti gate delivery. Rencana anggaran **pensiun** menurut CR-008 dan bukan lagi perilaku produk |

PAM bukan aplikasi bank: tidak menampilkan saldo rekening terverifikasi, memindahkan uang,
atau menjamin semua transaksi bank tercakup oleh email. `COMPLETE` merujuk pada pemrosesan
scope impor, bukan kelengkapan mutasi rekening. Empty, partial, failed, stale dan angka nol
harus tampil sebagai keadaan berbeda.

## 2. Status sprint dan fitur

### Posisi delivery

| Sprint | Cakupan | Status terakhir |
|---|---|---|
| S1 / fondasi | Android awal, auth dan baseline backend | Baseline tersedia; tidak sama dengan kelulusan proyek |
| **S2** | Integrasi Android–Go, impor, classifier, auto-sync, status UI | **PARTIAL — aktif** |
| S3 | CRUDS transaksi/kategori dan dynamic fields (anggaran **pensiun**, CR-008) | Belum lulus gate; sebagian backend tersedia |
| S4 | Visual dan cutover varian penilaian (lampiran & admin **pensiun**, CR-008) | Belum lulus gate |
| S5 | Verifikasi operasional, benchmark, perangkat dan owner UAT | Gate akhir masih terbuka |

Rincian bukti per gate dan sisa pekerjaan pemilik ada di [laporan gate akhir](docs/delivery/FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md) (38 gate: 29 PASS, 5 PARTIAL, 4 NOT RUN). Langkah penutupan gate tersisa untuk pemilik: [checklist aksi pemilik](docs/delivery/OWNER_ACTION_CHECKLIST_2026-11-24.md).

### Implementasi versus acceptance

| Area | Yang sudah tersedia | Yang masih perlu dibuktikan/diselesaikan |
|---|---|---|
| Auth dan data server | Go, JWT/refresh, transaksi/list/detail/summary, consent dan koreksi kategori | Acceptance gabungan, seluruh CRUDS dan lifecycle sesuai mandat |
| Gmail/Livin | **Gmail API dengan polling default 15 menit** (tanpa Pub/Sub, tanpa `users.watch`): OAuth dari sesi PAM, refresh token terenkripsi, backfill 12 bulan WIB bertahap yang mengalah pada delta, delta `history.list` + recovery cursor 404, antrean sync manual durable (202/429/409/503), dedupe identitas IMAP→Gmail; push + `users.watch` + Pub/Sub hanya **opt-in rollback**; jalur IMAP lama disimpan sebagai rollback | **Gmail live (G25) PASS** untuk akun pilot owner; **GCP Rp0 (G29/G37) OPEN** (Billing masih tertaut) dan kuota/biaya nyata, restricted scope/CASA tetap milik owner. Bukti terkontrol: [QA E2E 82/82](docs/evidence/GMAIL_CONTRACT_E2E_2026-11-24.md) dan [UAT perangkat FIXTURE](docs/evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md) |
| Azure classifier | Adapter, ledger/budget, cache, lease/recovery, caseId mapping dan USER override | Benchmark akurasi berlabel manusia; gate integrasi auto-queue |
| Auto-sync / batch | Worker Gmail memproses delta tiap **15 menit** selama backend hidup (app boleh tertutup) + backfill bertahap, satu job kategorisasi logis (5 merchant/permintaan, maks 4 batch/2 jam), antrean target 2 jam | Latensi email→Go→Android pada **Gmail nyata** belum terukur; pada fixture, delta saat app ditutup terbukti (G28). Due window server sudah diuji |
| Status UI | Satu sheet status ringkas (Bahasa Indonesia) dengan panel **"Sinkron server Gmail"** (state, progres bulan, percobaan/keberhasilan terakhir, jadwal berikutnya, tombol "Sinkronkan sekarang" + hitungan mundur saat cooldown), label job sederhana, rentang retensi setahun + label bulan batas, lampu Gmail "belum tertaut/aktif", UI utama 360–412 dp; font 200% + 360 dp diuji di APK final | Walk TalkBack manual dan lebar 393/412 dp di APK final belum diulang; state tidak lagi tertahan "Menghubungkan" (`addc240`), tetapi teks sisa status perangkat lama di panel "Perlu perhatian" masih dicatat sebagai temuan kecil |
| Kelengkapan penilaian | Kontrak 25 aksi dan empat entitas tersedia | S3/S4 (lampiran/admin **pensiun**, CR-008), dynamic fields dan audit Room/SQLCipher |

Temuan auto-sync tidak membuka kembali empat defect classifier lama yang sudah ditutup
pada `746b598`. Rincian implementasi/evidence: [living checkpoint](docs/DELIVERY_S2_S4_CHECKPOINT.md).
Dokumen itu memuat snapshot historis; baca bagian bertanggal terbaru dan keputusan review,
bukan hanya tabel atau hasil tes lama.

## 3. Progres mingguan (Weekly Progress)

> **Direkonstruksi dari riwayat Git** — minggu dihitung Senin–Minggu, tanggal **WIB**. Status tiap
> poin dibedakan: *diimplementasikan*, *lulus uji*, *parsial*, atau *direncanakan*. Commit backend
> ditautkan ke [repo backend](https://github.com/NDI05/0037-Backend-Financial) dan disebut asalnya.
> Detail per tanggal, bukti, dan gate yang masih terbuka tetap ada di
> [Progres harian](#4-progres-harian) dan [log harian lengkap](docs/PROGRESS_DAILY.md).

### 🗓️ Minggu 1 (16–22 November 2026)

Repository Android dimulai Kamis 19 November 2026; minggu ini berisi fondasi klien dan integrasi
awal ke backend.

- **Fondasi proyek, primitif uang/bulan Jakarta, dan kontrak domain** ([52c5d8f](https://github.com/NDI05/0037_FinancialMobile/commit/52c5d8f), [f3685d2](https://github.com/NDI05/0037_FinancialMobile/commit/f3685d2), [0bf8a38](https://github.com/NDI05/0037_FinancialMobile/commit/0bf8a38)). Build Gradle tervalidasi, uang dalam minor unit, bulan memakai Asia/Jakarta, dan seluruh port Gmail/Analisis ditetapkan sebagai interface. *Status: diimplementasikan; unit test primitif lulus.*
- **Room + SQLCipher skema v1 dan repositori lokal** ([5c728d6](https://github.com/NDI05/0037_FinancialMobile/commit/5c728d6), [5f66d8f](https://github.com/NDI05/0037_FinancialMobile/commit/5f66d8f)). Tabel transaksi, kategori, consent, impor, dan analisis disiapkan beserta DAO/konverter; enkripsi database aktif. *Status: diimplementasikan.*
- **Parser Livin QRIS + pipeline impor fixture** ([c30f826](https://github.com/NDI05/0037_FinancialMobile/commit/c30f826), [1232307](https://github.com/NDI05/0037_FinancialMobile/commit/1232307), [3e56bf0](https://github.com/NDI05/0037_FinancialMobile/commit/3e56bf0)). Registry template, parser ketat, fingerprint, dan mesin impor diuji terhadap fixture mailbox; email ditolak tetap dihitung sebagai match, bukan bulan kosong. *Status: lulus uji fixture.*
- **Desain Compose, navigasi type-safe, dan layar produk** ([d5c4b5e](https://github.com/NDI05/0037_FinancialMobile/commit/d5c4b5e), [2353a30](https://github.com/NDI05/0037_FinancialMobile/commit/2353a30), [ea9bea2](https://github.com/NDI05/0037_FinancialMobile/commit/ea9bea2), [cce0821](https://github.com/NDI05/0037_FinancialMobile/commit/cce0821), [e3fce16](https://github.com/NDI05/0037_FinancialMobile/commit/e3fce16)). Komponen desain, tujuan navigasi type-safe, gerbang sesi, serta layar Ringkasan/Transaksi/Detail/Entri manual/Analisis/Pengaturan; screenshot JVM dipakai untuk review desain. *Status: diimplementasikan; render JVM lulus.*
- **Antarmuka Bahasa Indonesia dan pemolesan C2** ([aab721b](https://github.com/NDI05/0037_FinancialMobile/commit/aab721b), [ff9fc9a](https://github.com/NDI05/0037_FinancialMobile/commit/ff9fc9a)). Seluruh string utama diterjemahkan sesuai SRS §1.4; temuan review C2 ditutup. *Status: lulus uji render.*
- **Klien backend Go + kontrak wire** ([19ed1c8](https://github.com/NDI05/0037_FinancialMobile/commit/19ed1c8), [e6f315f](https://github.com/NDI05/0037_FinancialMobile/commit/e6f315f), [756b186](https://github.com/NDI05/0037_FinancialMobile/commit/756b186)). Aplikasi terhubung ke API PAM; kontrak diverifikasi dengan MockWebServer dan body analisis replayable dipublikasikan. *Status: lulus uji kontrak.*
- **Transaksi manual dan daftar/detail/Ringkasan dari server** ([2a2ecd7](https://github.com/NDI05/0037_FinancialMobile/commit/2a2ecd7), [a21dc14](https://github.com/NDI05/0037_FinancialMobile/commit/a21dc14)). C2A memindahkan sumber data layar utama ke API Go, termasuk create manual, paging, dan ringkasan; gate mapping/refresh/retry ditutup di [cd19ee5](https://github.com/NDI05/0037_FinancialMobile/commit/cd19ee5). *Status: lulus uji; bukti server-backed.*
- **Consent v0.4 dengan acknowledgement server** ([0566824](https://github.com/NDI05/0037_FinancialMobile/commit/0566824)). Consent baru dianggap berlaku setelah server mengonfirmasi. *Status: lulus uji.*
- **Parser Livin tanggal/waktu ketat, unggah impor ke job Go, dan retry tanpa duplikat** ([fe8c4f4](https://github.com/NDI05/0037_FinancialMobile/commit/fe8c4f4), [72a9cf4](https://github.com/NDI05/0037_FinancialMobile/commit/72a9cf4), [a217e02](https://github.com/NDI05/0037_FinancialMobile/commit/a217e02)). Tanggal/waktu gabungan dipisah dengan benar, tanggal mustahil ditolak, impor Livin diunggah ke API job/batch, dan identitas job server dipersistenkan agar retry tidak menggandakan baris. *Status: lulus uji parser + lost-response.*
- **Batas klasifikasi Azure, consent AI_CATEGORIZATION terpisah, dan job kategori durable** ([8947881](https://github.com/NDI05/0037_FinancialMobile/commit/8947881), [5d9f93e](https://github.com/NDI05/0037_FinancialMobile/commit/5d9f93e), [0d80b50](https://github.com/NDI05/0037_FinancialMobile/commit/0d80b50)). Batas adapter bertipe, sakelar consent mandiri, aksi klasifikasi/reklasifikasi di Android, serta cache dan apply owner-scoped di backend. *Status: diimplementasikan; smoke Azure dan gate integrasi saat itu masih parsial.*
- **Gmail tetap tertaut saat logout biasa dan guard pindah akun** ([a5b4656](https://github.com/NDI05/0037_FinancialMobile/commit/a5b4656), [99151a1](https://github.com/NDI05/0037_FinancialMobile/commit/99151a1)). Kredensial owner-scoped tidak ikut terhapus oleh logout biasa; reconnect dan race pindah akun ditangani. *Status: lulus uji.*
- **SRS v0.5, provisioning Azure, dan bukti impor Gmail nyata pertama** ([c9d4a57](https://github.com/NDI05/0037_FinancialMobile/commit/c9d4a57), [74eab38](https://github.com/NDI05/0037_FinancialMobile/commit/74eab38)). Revisi SRS dan runtime Azure disiapkan; impor Gmail nyata, readback Go, dan siklus reconnect dicatat sebagai bukti awal. *Status: diimplementasikan; acceptance final belum.*

### 🗓️ Minggu 2 (23–29 November 2026)

Minggu berjalan dengan perubahan hingga **Rabu 25 November 2026**. Fokus: integrasi S2–S4,
Gmail API/SSO, pensiun modul sesuai SRS v0.7, dan UAT perangkat final.

- **Integrasi S2/S3/S4 dan acceptance merged-head** ([99cb3df](https://github.com/NDI05/0037_FinancialMobile/commit/99cb3df), [a512770](https://github.com/NDI05/0037_FinancialMobile/commit/a512770), [aa61a7b](https://github.com/NDI05/0037_FinancialMobile/commit/aa61a7b), [7337559](https://github.com/NDI05/0037_FinancialMobile/commit/7337559), [c999285](https://github.com/NDI05/0037_FinancialMobile/commit/c999285)). Paket recovery P1, bukti create manual di perangkat, wiring analisis server + guard consent, dan matriks acceptance merged; termasuk jalur admin/lampiran C18/C19/C23–C25 yang **kemudian dipensiunkan** oleh CR-008 ([897e751](https://github.com/NDI05/0037_FinancialMobile/commit/897e751), [cf7afc9](https://github.com/NDI05/0037_FinancialMobile/commit/cf7afc9)). *Status: lulus uji saat itu; modul terkait kini keluar dari cakupan aktif.*
- **Revamp UI sinkronisasi dan Ringkasan** ([1362c11](https://github.com/NDI05/0037_FinancialMobile/commit/1362c11), [9aeea28](https://github.com/NDI05/0037_FinancialMobile/commit/9aeea28)). Satu sheet status tanpa memicu impor saat dibuka, Ringkasan lebih ringkas, judul terpusat, ikon status 48 dp; i18n pasca-merge dirapikan. *Status: lulus uji + walk perangkat saat itu.*
- **Parser Livin memilih `Penerima` dan repair label merchant** ([02ee6df](https://github.com/NDI05/0037_FinancialMobile/commit/02ee6df), [b5ee7ae](https://github.com/NDI05/0037_FinancialMobile/commit/b5ee7ae), [276b7e1](https://github.com/NDI05/0037_FinancialMobile/commit/276b7e1)); backend pendukung [89b3203](https://github.com/NDI05/0037-Backend-Financial/commit/89b3203), [0688345](https://github.com/NDI05/0037-Backend-Financial/commit/0688345) (repo backend). Nama usaha dari `Penerima` diprioritaskan di atas `Merchant PAN`, baris tersimpan diperbaiki satu kali secara fail-closed, dan kategori pilihan pengguna tetap dipertahankan. *Status: lulus uji + bukti data owner.*
- **Pemisahan repository backend Go** ([70d1535](https://github.com/NDI05/0037_FinancialMobile/commit/70d1535)). Source Go diekstrak dari `pam-app` path `backend-go/`; repo [0037-Backend-Financial](https://github.com/NDI05/0037-Backend-Financial) berdiri sendiri mulai commit [327d876](https://github.com/NDI05/0037-Backend-Financial/commit/327d876) (24 Nov), dengan **32 commit pre-split** yang dapat diverifikasi. *Status: selesai; E2E lintas repo diverifikasi.*
- **Gmail OAuth lewat browser + retensi UI setahun** ([f1e3e16](https://github.com/NDI05/0037_FinancialMobile/commit/f1e3e16), [5eb25e3](https://github.com/NDI05/0037_FinancialMobile/commit/5eb25e3)). Layar Tautkan Gmail memakai flow server + deep link callback, status masked, dan putuskan dengan konfirmasi; navigator bulan dibatasi jendela retensi bergulir dengan label "sebagian" di bulan tertua. *Status: lulus uji; walk perangkat lintas lane dicatat di log harian.*
- **Google SSO di klien Android** ([5cb14d1](https://github.com/NDI05/0037_FinancialMobile/commit/5cb14d1)). Login "Masuk dengan Google" lewat Credential Manager, penautan akun lama, onboarding Gmail "Lewati", dan penanganan not-linked/inactive/invalid-token/conflict. *Status: lulus uji; login/logout/relogin nyata di HP terbukti 24 Nov (G36 PARTIAL — Run literal/onboarding Gmail belum).*
- **SRS v0.7: pensiun anggaran, lampiran, administrasi + migrasi non-destruktif** ([1acc835](https://github.com/NDI05/0037_FinancialMobile/commit/1acc835), [d7c6bca](https://github.com/NDI05/0037_FinancialMobile/commit/d7c6bca)); backend ([1d020bc](https://github.com/NDI05/0037-Backend-Financial/commit/1d020bc), [76b2664](https://github.com/NDI05/0037-Backend-Financial/commit/76b2664)). Rute Java/UI modul pensiun dihapus dari jalur aktif, rute API-nya 404 untuk semua method, sedangkan tabel, baris, file, dan riwayat migrasi tetap dipertahankan (Room v1→v2 non-destruktif; baris anggaran legacy tetap terbaca). *Status: lulus uji + bukti 404 matrix; data lama utuh.*
- **Entri manual: catatan opsional + batas tanggal NFR-15** ([b56e28f](https://github.com/NDI05/0037_FinancialMobile/commit/b56e28f)). Catatan 0–500 karakter ikut identitas retry; tanggal manual dibatasi lantai jendela retensi dan maksimum hari ini WIB; copy id/en dan validasi diperbarui. *Status: lulus uji + UAT perangkat.*
- **Refresh daftar Transaksi setelah simpan manual** ([a629c41](https://github.com/NDI05/0037_FinancialMobile/commit/a629c41)). Form mempublikasikan bulan baris tersimpan lewat `SavedStateHandle`; daftar memuat ulang halaman 1 tanpa mengubah filter/pencarian, sehingga baris baru langsung tampil. *Status: lulus uji; bug terulang dua kali sebelum perbaikan, lalu terverifikasi pada UAT perangkat setelah `a629c41`.*
- **Penutupan P2 audit final** ([b885555](https://github.com/NDI05/0037_FinancialMobile/commit/b885555)). Copy privasi catatan id/en tidak lagi mengklaim "hanya di perangkat" (catatan tersimpan di server akun, terenkripsi saat disimpan); identitas retry pending legacy 5-field dibuat kompatibel dengan format 6-field sehingga tidak memicu UUID/baris ganda saat upgrade. *Status: lulus uji (613 tes/88 suite) + UAT copy di perangkat.*
- **UAT akhir SRS v0.7 di HP fisik dan dokumentasi gate** ([3aebaa1](https://github.com/NDI05/0037_FinancialMobile/commit/3aebaa1), [ddcc60c](https://github.com/NDI05/0037_FinancialMobile/commit/ddcc60c)). APK final (`b885555`, sha256 `ef34261b…`) dipasang via `adb install -r`; terbukti entri manual+catatan, tiga modul pensiun tidak terekspos, FR-32, sheet status, Analisis tersimpan, dan logout→login Google. Tombol **Run** literal Android Studio, onboarding Gmail, dan sync email baru saat app tertutup tetap **NOT RUN/OPEN**. Bukti: [UAT final](docs/evidence/FINAL_UAT_SRS_V07_2026-11-24.md), [gate akhir v0.7](docs/delivery/FINAL_GATES_SRS_V07_2026-11-24.md). *Status: lulus uji teknis; gate pemilik terbuka.*

- **Penyelarasan tanggal transaksi dengan SRS final**. Entri baru dengan tanggal saja dikirim pada awal hari WIB dan timestamp yang dipakai untuk retry dipertahankan. Backend menolak penambahan transaksi dengan tanggal masa depan. Debug 614 tes dan assessment 623 tes lulus dengan lint 0 error pada keduanya.
- **Perbaikan nominal impor email + verifikasi ulang gate 25 Nov** (backend [`d42d7c4`](https://github.com/NDI05/0037-Backend-Financial/commit/d42d7c4), dokumentasi [`aa33fb8`](https://github.com/NDI05/0037-Backend-Financial/commit/aa33fb8)). Laporan pemilik "angka nol kurang" terbukti berasal dari parser Livin yang mengembalikan rupiah utuh padahal kontrak memakai minor IDR (1/100 rupiah); fixture fake ikut menutupi bug. Parser `gmail-livin-v3` kini mengembalikan minor eksak, fixture mencetak rupiah+sen, dan migrasi 022 memperbaiki 915 baris `EMAIL` pada DB UAT lokal sekali jalan (baris `MANUAL` utuh, revisi bulan naik). Diverifikasi `go vet` + 11 paket tes + E2E fake Gmail PASS, plus bukti UI APK di emulator (backend terisolasi, data sintetis) menampilkan `Rp25.000`/`Rp9.990`/`Rp123.456,78` dan Ringkasan `Rp158.446,78`; FR-67, NFR-15, G14 fixture 59/59, CI backend, dan smoke emulator dicatat ulang. *Status: perbaikan PASS di UAT lokal; G36/G24/G29/G37 dan deploy produksi tetap terbuka.* Bukti: [recheck gate](docs/delivery/GATE_RECHECK_2026-11-25.md), [bugfix nominal](docs/evidence/AMOUNT_UNIT_BUGFIX_2026-11-25.md), [kesiapan deploy](docs/delivery/DEPLOYMENT_READINESS_NOTE_2026-11-25.md).

- **Verifikasi ulang gate pada build auth + penutupan dokumentasi 25 November** ([651f807](https://github.com/NDI05/0037_FinancialMobile/commit/651f807), fix mode daftar [8de8f75](https://github.com/NDI05/0037_FinancialMobile/commit/8de8f75), backend [1325d3d](https://github.com/NDI05/0037-Backend-Financial/commit/1325d3d)). FR-67 diuji ulang pada biner HEAD: saat backfill belum lengkap **0 panggilan provider**, setelah selesai tepat 4 panggilan batch `[2,2,1,2]`; NFR-15 diuji ulang: masa depan 400, hari ini 201, retry identik 200 id sama, satu baris; G14 drill fixture DB terpisah **59/59 PASS**; smoke API build baru: registrasi terbuka 201, kategori 200 (11), transaksi 201/list 200, 6 rute pensiun (`budget-plans`, `attachments`, `admin/*`) **404**; smoke emulator APK fix: Welcome → "Buat akun" langsung mode daftar (dua kali), Ringkasan net Rp88.466,78, Transaksi 3 baris, tanpa entri Anggaran/Lampiran/Administrasi, tanpa crash; CI backend dua commit **success**. G36 tetap PARTIAL (Run literal + Test user Google), G29/G37 tetap OPEN, OI-28 diputuskan lewat CR-009. Bukti: [recheck gate](docs/delivery/GATE_RECHECK_2026-11-25.md), [CR-009](docs/changes/CR_009_OPEN_GOOGLE_AUTH.md). *Status: gate build auth PASS untuk yang diuji; UAT HP/deploy tetap milik pemilik.*

## 4. Progres harian

Tanggal memakai **Asia/Jakarta (WIB)**. Tabel berikut direkonstruksi dari Git/checkpoint;
bukan klaim setiap gate telah lulus pada tanggal tersebut.

| Tanggal | Fokus dan hasil utama | Verifikasi / batas bukti | Sisa utama |
|---|---|---|---|
| 19 Nov 2026 | Fondasi Compose/domain, backend awal dan kontrak klien | Riwayat source/test awal tersedia; bukan run ulang saat ini | Integrasi live dan arsitektur berikutnya |
| 20 Nov 2026 | Jalur IMAP/App Password, MIME, perbaikan analisis dan kajian pedoman | Perbaikan/parser fixture tercatat di Git | Integrasi nyata dan migrasi kepatuhan |
| 21 Nov 2026 | Onboarding, kontrak v0.4, migrasi Go dan baseline C1 | Backend lokal/interop dan emulator subset terdokumentasi | C2 Android dan kelengkapan CRUDS |
| 22 Nov 2026 | Parser strict, impor Go, consent, SRS v0.5, setup/runtime Azure | Unit/integration dan smoke berkembang per checkpoint; bukan benchmark | Runtime recovery dan Android acceptance saat itu |
| **23 Nov 2026** | Hari integrasi S2/S3/S4 di `feat/c2-android`: paket P1 recovery, Android acceptance, varian assessment (`99cb3df`), lalu penutupan gap S2–S4 malam: admin C24/C25 (`a512770`), bukti C01 create manual + identitas lost-response (`aa61a7b`), wiring analisis server S2.5 + guard consent (`7337559`); semua paket lewat audit independen; branch tugas/worktree dibersihkan; dokumen merged-head acceptance ditambahkan.; profil UAT Go + Azure + Ollama disamakan dengan Run Android Studio dan aturan agent diperbarui Lanjutan malam: revamp UI sinkronisasi (Ringkasan bersih, judul terpusat, ikon status 48 dp, satu sheet tanpa impor saat dibuka, hasil sama setelah tutup/buka dan minimize) digabung (`9aeea28`) bersama lane kategori (`dda4084`), lalu perbaikan i18n pasca-merge (`a310067`); bukti perangkat merged di [UAT_MERGED_UI_CATEGORY](docs/evidence/UAT_MERGED_UI_CATEGORY_2026-11-23.md), draf CR_006, matriks/checkpoint diberi addendum.| Android **493 tests/68 class/0 failure** debug + **503 tests/69 class/0 failure** assessment, lint **0 error/115 warning** debug & **0 error/123 warning** assessment, Go test/vet/build/race ok; merged head `7337559`: C01 create UI `POST /v1/transactions` 201 + ringkasan 92/89→93/90, S2.5 CURRENT→STALE→regenerate→CURRENT→dependency-STALE di perangkat, C24/C25 walk teraudit + regresi; APK debug sha256 `6497a406…`, assessment `fa0d224e…`.; build debug sukses, BuildConfig 10.0.2.2:18090, readiness emulator HTTP 200, probe sintetis Ollama HTTP 200 Sebelum fix, lint merged gagal 7 MissingTranslation; setelah `a310067`: debug **509 tests/0 failure**, assessment **519/0**, lint debug **0 error/112 warning**, lint assessment **0 error/120 warning**, assemble debug+assessment PASS (sha256 `0e9169f2…`/`dea936b8…`), Go test/vet/build/race PASS.| **S3 lulus (C01 PASS runtime)**; **S4.1/S4.2/S4.4 lulus** (batas perangkat C24/C25 dan foreground-only assessment dicatat); **S2.5 PASS runtime** (catatan INFO dependency-churn sync); temuan LOW audit tetap follow-up; gate eksternal S5 (mailbox nyata, benchmark, hosting/backup, UAT pemilik, merge/deploy).; smoke UI dari Run Android Studio dan panggilan Azure pada sesi penyelarasan belum dijalankan; **revisi UI sinkronisasi PASS runtime sebagian besar** — bingkai running/progress live, fade label live, TalkBack live, 412 dp+judul panjang, notis nomor referensi detail owner, resume unggahan pending live = PARTIAL/NOT RUN; **alasan kategori tampil PASS runtime** (`classified=0 needsReview=5 failed=0`, 0 panggilan Azure); **kategori AI terklasifikasi pada data owner, precision ≥95%, Buat Analisis via Ollama dengan bulan EMPTY-coverage = NOT RUN**; audit merged awal **FAIL** (lint) lalu diperbaiki.| `e3eeb17`, `94e2f37`, `99cb3df`, `a512770`, `aa61a7b`, `7337559`, plus commit lane di log harian, `9aeea28`, `dda4084`, `a310067`|
| **25 Nov 2026** | FR-67 dan NFR-15 diselaraskan dengan SRS v0.7 final; pesan UX kategori 503 diperbaiki; **API Go di-deploy ke Vercel Hobby** atas permintaan pemilik | Debug **621 tes** lulus, kedua build dan lint lulus; Go vet/build/test lulus. Vercel: Go Framework Preset, DB Turso baru `pam-vercel` migrasi 22/22, smoke health/ready/register/login/SSO nonce/transaksi/summary **200–201**, proteksi deployment dimatikan untuk akses HP; bukti [VERCEL_DEPLOYMENT](docs/evidence/VERCEL_DEPLOYMENT_2026-11-25.md). Azure penuh di UAT lokal (analisis + kategorisasi, token guard + plafon): [AZURE_FULL_OPTIMIZATION](docs/evidence/AZURE_FULL_OPTIMIZATION_2026-11-25.md) | Gmail otomatis tidak dijamin di Vercel Functions (polling in-process; Cron Hobby 1×/hari) dan Gmail nonaktif sampai redirect URI HTTPS didaftarkan; UAT lewat tombol Run, biaya cloud, dan merge/deploy produksi tetap terbuka |
| **24 Nov 2026** | P0 nav satu-sumber-bulan + parser Livin `Penerima` + repair label merchant; Analisis UAT beralih ke Azure Foundry; walk lampiran (konflik/retry) dan admin (pencarian live/filter status/SUSPENDED) menutup gap UI C18/C19/C23/C25<br>Draf workflow baru: impor awal setahun, antrean AI hemat, retensi, dan UI Indonesia ringkas.<br>Gmail API OAuth + Pub/Sub ditetapkan sebagai target pengganti IMAP dan [mandat PM](docs/agent-prompts/PROMPT_PM_GMAIL_API_YEARLY_WORKFLOW.md) disusun.<br>Mandat lanjutan ditambah pemisahan backend ke repo 0037 Backend Financial.<br>Google SSO dan onboarding Gmail masuk draf CR-007; implementasi SSO + penautan akun + onboarding Gmail selesai di fixture/unit (`pam-app@e67380a`, backend `71013f5`), sedangkan UAT akun Google nyata dan gate live **NOT RUN** (menunggu input owner: Web Client ID, SHA-1 client Android terdaftar, akun uji). Bukti fixture perangkat (24 Nov): sheet onboarding Gmail + tombol Lewati tampil, sesi tetap aktif, skip persisten setelah restart; kontrak Gmail polling diulang di integrasi (`138b5c3`) **PASS=82**; pemeriksaan kesiapan read-only `scripts/verify/uat_readiness.sh` ditambahkan untuk prasyarat Run. [Rancangan](docs/plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md#10-google-sso-dan-onboarding-gmail).SRS v0.6 dan prototipe multi-bank siap review. Mandiri lebih dulu, angka kartu berupa jumlah transaksi, dan hosting Go ditargetkan tanpa biaya tambahan. [Rencana](docs/plans/MULTI_BANK_AND_GO_DEPLOYMENT_PLAN.md).<br>[CR-008](docs/changes/CR_008_DRAFT_PRODUCT_SCOPE.md) mencatat keputusan pemilik untuk satu peran USER. Anggaran, lampiran transaksi, dan administrasi keluar dari cakupan produk. Verifikasi pedoman asli dan konsolidasi SRS masih terbuka.<br>Revisi [SRS v0.7](docs/specifications/PAM_SRS_v0.7_Personal_Finance_Draft.pdf) mengonsolidasikan CR-008 dengan satu peran USER dan dua modul CRUDS produk. Word dan PDF 53 halaman lolos pemeriksaan render, daftar isi, diagram, dan ID requirement. Pemetaan D.5/D.6/D.9 dan pendaftaran akun baru masih terbuka. | Test debug 536/74 dan assessment 546/75 lulus (termasuk audit aksesibilitas 3 layar); lint 0 error/112 warning; UAT owner: sync satu job, kategori Azure, detail merchant nama usaha, Buat Analisis 200 tersimpan; bukti UI `ui_split/36`–`38` dan `40`–`42` | Menunggu **Run Android Studio** pemilik untuk varian final dan drill-down-after (S2), sisa bukti UI S4 (C20 konteks, C24 guard, cursor C25), S5 (merge/deploy, backup, benchmark, mailbox produksi); parity repo split: **P1–P7 PASS** (clone bersih backend 167 tes, APK tanpa source server, E2E login/read 2 transaksi, DB 16 migrasi), **P8 PARTIAL** (restore DB/rollback nyata NOT RUN), **P9 NOT RUN** (Gmail API WIP); audit split PASS dengan temuan doc<br>Workflow baru masih draf dan menunggu implementasi serta UAT.<br>Pemisahan backend dimulai: repo backend `NDI05/0037-Backend-Financial` dibuat (privat), 32 commit riwayat backend diekstrak, bootstrap standalone + CI + kontrak Gmail API dipush (`327d876`); source Go, fixture interop, dan Node lama keluar dari tree Android; verifikasi E2E menunggu.<br>Gmail UI OAuth (branch `feat/gmail-ui`, commit `f1e3e16`): layar Tautkan Gmail via browser, deep link callback, status masked dan putuskan berkonfirmasi; 26 tes baru hijau (13 ViewModel + 13 Compose); build penuh/lint/emulator NOT RUN<br>Retensi UI (branch `feat/retention-ui`, commit `5eb25e3`): jendela setahun berjalan di Ringkasan/Transaksi/Analisis — panah depan mati di bulan berjalan, panah belakang mati di bulan batas, label bulan batas "sebagian", `monthId` di luar jendela di-clamp; sheet status memakai label ringkas "Mengambil riwayat"/"Menunggu AI"/"Perlu tinjauan"/"Coba lagi" dan menampilkan rentang retensi; TalkBack satu node + target 48 dp + font 200% di 360/393/412 dp. `:app:testDebugUnitTest` **562 tes/77 suite/0 gagal** (fresh, termasuk `RetentionWindowTest`, `MonthSelectorTest`, `UpdateStatusLabelsTest`); walk perangkat/lint lane **NOT RUN**.<br>Tindak lanjut audit Gmail UI (`305956d`): deep link `pam://gmail/connected` di-dedup sekali (gate `action|data` + `SavedStateHandle`) sehingga recreate/replay tidak menggandakan banner/refresh; `503 gmail_disabled` dipetakan ke state khusus "server belum mengaktifkan Gmail" (bukan gangguan jaringan, CTA connect/disconnect disembunyikan); hitungan tes dikoreksi; 51 tes Gmail/API hijau (fresh). Build penuh/lint/emulator tetap NOT RUN. <br>**Server sync UI Gmail (24 Nov, worktree `pam-wt-gmail-server-ui`, branch `work/gmail-server-status`):** sheet "Status pembaruan" menampilkan status sinkron server Gmail saat akun tertaut — state (Mengambil riwayat/Menunggu/Menghubungkan), progres bulan/halaman, percobaan & keberhasilan terakhir, satu tombol "Sinkronkan sekarang" yang hanya meng-enqueue `POST /v1/gmail/sync` (202 queued/coalesced, 429 `sync_cooldown` + `retryAfterSeconds` → pesan tunggu, 409 `gmail_not_connected`, 503 `gmail_disabled`), CTA "Tautkan ulang Gmail" saat `needs_reauth`, dan fallback sheet device-sync lama utuh bila Gmail tidak tertaut; membuka sheet hanya membaca status + polling selama sheet terbuka, menutup sheet tidak membatalkan job server. `GET /v1/gmail/status` lama tetap terbaca (field §9 opsional). Tes baru: VM 12, API+repo 15, Compose sheet 9 (termasuk target 48 dp di font 200%/360 dp). Gate fresh: `:app:testDebugUnitTest` **633 tes/83 suite/0 gagal**, `:app:lintDebug` **0 error/120 warning**, `:app:assembleDebug` lulus (APK debug 46,1 MB); emulator, TalkBack manual, dan UAT perangkat **NOT RUN** di lane ini. Commit `7ec2c28` dipush ke `origin work/gmail-server-status`. Setelah audit ronde 4 (`758d6df`): status tidak lagi nyangkut "Menghubungkan" — state diturunkan dari `sync_state`/progress dan `sync_requested_at` hanya hint lokal ≤20 dtk yang langsung dibersihkan oleh status berikutnya (marker basi dari server lama diabaikan), dan cooldown 429 memakai `retryAfterSeconds` camelCase sesuai §9.3 (snake_case lama tetap terbaca). Tes +6 (VM 3, sheet 2, API 1); gate fresh: **639 tes/83 suite/0 gagal**, lint **0 error/120 warning**, `:app:assembleDebug` lulus (APK 46,4 MB). **UAT perangkat fisik FIXTURE** (HP fisik (serial diredaksi), backend `1290910`, `adb reverse`): backfill 12/12, 27 transaksi, manual sync 202 + drain, cooldown 429 "Tunggu 57 detik", delta saat app ditutup 26→27 tanpa duplikat, restart backend tetap 27, rekonsiliasi 5 baris/Rp20.530,01 — bukti [UAT FIXTURE](docs/evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md) (tombol Run Android Studio literal NOT RUN). **Audit ronde 5**: MAJOR/P0 nol, MINOR F2 (hint "Menghubungkan" lengket saat GET status gagal) ditutup di `7f218ed` + tes; gate final **640 tes/83 suite/0 gagal**, lint **0 error/120 warning**, APK **46.433.080 byte** sha256 `7efc1803…` (dibangun 17:28, smoke ulang tap → 202 → drain). Gate Gmail live/Rp0 tetap **NOT RUN/OPEN**.  <br>**Google SSO Android (worktree `pam-wt-sso-android`, branch `work/sso-android`, commit `5cb14d1`):** login "Masuk dengan Google" + penautan untuk akun lama + onboarding Gmail "Lewati" per owner. BuildConfig `PAM_GOOGLE_SSO_CLIENT_ID` (default kosong => fitur tersembunyi, build tetap lulus); Credential Manager di balik gateway yang bisa difake; ID token tidak pernah di-log; cancel idle; salinan khusus not-linked/inactive/invalid-token/conflict. Gate fresh: **678 tes/88 suite/0 gagal** (640 tes lama + 38 tes SSO), lint **0 error/123 warning/1 hint**, `:app:assembleDebug` lulus (APK 47.992.939 byte). SHA-1 debug `FE:38:71:…:C6:30` (`com.pam.recap.debug`). Langkah owner: [GOOGLE_SSO_OWNER_SETUP](docs/GOOGLE_SSO_OWNER_SETUP.md). UAT SSO perangkat nyata **NOT RUN**.  <br>Audit SSO ronde 1 (P2 Android) ditindaklanjuti: fallback error Network memakai kode HTTP nyata + 2 tes regresi, doc `POST /v1/auth/google` 200 & picker akun selalu tampil, fixture screenshot jadi `Pengguna Uji`/`@example.test`. Gate fresh: **678 tes/88 suite/0 gagal**, lint **0 error/123 warning**, APK 47.992.939 B. Ronde 6–7: **Gmail live PASS untuk akun pilot owner** (backfill 12/12, 914 transaksi unik, 0 duplikat; parser produksi diperbaiki `5df6be6`/`c98527b`), gate teknis diverifikasi ulang (Android 678 tes/0 gagal, lint 0 error; Go vet/build/test/race PASS) dan tes SSO client-ID diperbaiki; SSO live (G36) + Rp0 (G29/G37) tetap NOT RUN/OPEN. Audit independen ronde 7: P0 nol, dua P1 ditutup (baris field tidak lagi menjadi merchant; angka live direkonsiliasi 769 impor + 145 tersimpan = 914) lewat backend `e3b84b4` dengan regresi baru; gate tetap 29 PASS / 4 PARTIAL / 5 NOT RUN. Audit ulang ronde 2 menemukan celah PAN/field-label sebagai merchant; ditutup backend `e19477b` (guard kedua jalur label + regresi PAN, zona baris terpisah, join 3 baris) dengan gate vet/build/test/race PASS. Audit verifikasi ronde 3: tanpa P0/P1; residual baris ref tanpa titik dua + prosa ber-kolon penjembatan waktu ditutup di `c69cf57` dengan regresi baru. UAT HP: tombol Sinkronkan sekarang → `POST /v1/gmail/sync` 202, UI progress→idle, `gmail_sync_pass failures=0`, 914 baris tetap.; **SSO live HP terbukti** 21:49–21:50 WIB (sesi PAM + binding identitas + 0 duplikat; G36 PARTIAL, sisa Run literal/onboarding) <br>**Lane SE Android SRS v0.7 (`feat/c2-android`, `b56e28f`):** pensiun administrasi pengguna/lampiran transaksi/anggaran (Room v1→v2, migrasi non-destruktif: tabel anggaran legacy tetap tersimpan), FR-32 kartu Masuk/Keluar membuka Transaksi terfilter direction+bulan dengan back ke Ringkasan, FR-79/80 tes lifetime sheet + percobaan/sukses terpisah, NFR-15 picker jendela setahun. Tindak lanjut audit independen (24 Nov, P0/P1 nol): form manual kini punya catatan opsional (FR-26, ikut identitas retry), tanggal manual dibatasi NFR-15 (maks hari ini WIB, min lantai jendela, simpan dinonaktifkan + copy id/en), sisa error anggaran mati dihapus, copy akun nonaktif tidak lagi menyebut admin, dan filter bank FR-32 dicatat sebagai keputusan produk terblokir di CR-008. Gate fresh: **602 tes/85 suite/0 gagal**, lint **0 error/117 warning/1 hint**, APK 48.819.325 B sha256 `3c5f6b70…` (fingerprint build, bukan determinisme byte); UAT emulator **NOT RUN**. **Refresh daftar Transaksi setelah simpan manual (FR-26 follow-up, `feat/c2-android`):** temuan PM pada `b56e28f` — simpan manual 24 Nov 2026 tidak muncul di halaman 1 daftar sampai bulan digeser, padahal Ringkasan sudah ter-update. Form manual kini mempublikasikan bulan baris tersimpan sebagai hasil navigasi (`SavedStateHandle` `manualSaveMonthId`) dan `TransactionsScreen` memanggil `refreshAfterSave(monthId)` untuk memuat ulang halaman 1 tanpa mengubah filter bulan/hari/kategori/pencarian; jalur `FinanceRefreshBus` tetap dipertahankan untuk sunting/hapus/impor. Kontrak API, backend, Room, dan sumber data lain tidak berubah. Regresi baru 6 tes (`TransactionsManualRefreshTest` 5, `ManualSaveResultNavigationTest` 1). Gate fresh: **608 tes/87 suite/0 gagal**, lint **0 error/117 warning/1 hint**, APK debug **48.819.325** byte sha256 `e7a1f4af…` (fingerprint build); smoke emulator app terbuka, verifikasi UI simpan→daftar **NOT RUN** karena sesi server akun emulator berakhir. <br>**Gate akhir SRS v0.7 PM (24 Nov, `a629c41` + backend `dd5bc88`):** UAT di HP fisik (serial diredaksi) (APK 48.819.325 B sha256 `e7a1f4af…`, `adb install -r`) membuktikan entri manual lengkap dengan catatan opsional (tersimpan `note=catatan-final-v07`, ciphertext 57 B) dan daftar Transaksi langsung memuat baris baru tanpa pindah bulan; tiga modul pensiun (anggaran, lampiran, administrasi) tidak lagi punya entri UI dan rute API-nya **404** dengan token valid; Analisis tersimpan/terbaca (backend `dd5bc88` allowlist coverage), FR-32 kartu KELUAR→Transaksi arah Keluar→back Ringkasan, sheet Status pembaruan hanya GET saat dibuka dan memisahkan percobaan vs keberhasilan, serta logout→login Google di build final (`DELETE /v1/sessions` 204 → `POST /v1/auth/google` 200 → `/v1/me` 200) dengan 914 baris tetap unik. Gate teknis: Android **608 tes/87 suite/0 gagal**, lint **0 error/117 warning/1 hint**; backend vet/build/test PASS. Bukti: [UAT akhir SRS v0.7](docs/evidence/FINAL_UAT_SRS_V07_2026-11-24.md) dan [gate akhir v0.7](docs/delivery/FINAL_GATES_SRS_V07_2026-11-24.md). G14 data pemilik, G24, G29/G37, Run literal, onboarding/consent Gmail, dan email baru saat app tertutup tetap **NOT RUN/OPEN**. |

**[Buka log harian lengkap, commit, bukti, dan template pembaruan →](docs/PROGRESS_DAILY.md)**

Tidak ada persentase selesai tanpa denominator acceptance. Pekerjaan WIP tidak disamakan
dengan commit ter-push; smoke provider, unit test dan Android E2E dicatat terpisah.

## 5. Arsitektur dan struktur repository

```mermaid
flowchart LR
    Owner[Pemilik] -->|login PAM| Android[Android Kotlin / Compose]
    Android -->|REST + sesi JWT| Go[Backend Go]
    Android -->|connect/start, buka browser| Google[Google OAuth 2.0]
    Google -->|callback code+state| Go
    Go -->|polling 15 menit: history.list, get| Gmail[Gmail API readonly]
    PubSub[Cloud Pub/Sub] -.->|opt-in rollback: push + OIDC JWT| Go
    Go -->|deep link pam://gmail/connected| Android
    Go --> DB[(Turso / libSQL)]
    Go -->|Merchant tersanitasi + consent kategorisasi| Azure[Microsoft Foundry]
    Go -->|Agregat + consent analisis| Azure
```

Jalur utama memakai **Gmail API mode polling**: pemilik menautkan Gmail lewat browser dari sesi
PAM, refresh token disimpan terenkripsi di server, dan worker backend membaca delta tiap **15
menit** serta backfill 12 bulan bertahap **selama backend hidup** (aplikasi boleh tertutup).
Email diproses di server dan tidak disimpan mentah (tidak ada isi email, PAN, atau ID pesan di
DB/log/Git). Push + `users.watch` + Pub/Sub hanya **opt-in rollback** dan tidak aktif tanpa
konfigurasi topik. Jalur IMAP lama tetap dikompilasi sebagai rollback sampai pemilik menyetujui
cutover, tetapi entri UI-nya sudah dihapus dari Pengaturan. Transaksi server-mode disimpan melalui
Go; merchant bisnis minimal hanya dikirim ke classifier dengan purpose yang sesuai. Payload
analisis agregat tidak diperlebar menjadi raw transaksi/email.

```text
pam-app/                 repo Android (klien)
├── app/                 Android, Compose UI, domain, data dan tests
├── scripts/             Wrapper build, QA, dokumen dan utilitas
├── docs/
│   ├── PROGRESS_DAILY.md Log harian dan aturan pencatatan
│   ├── specifications/  SRS resmi
│   ├── plans/           Rencana delivery
│   ├── changes/         Change records dan draf kebutuhan
│   ├── evidence/        Bukti tersanitasi yang dilacak
│   ├── maintenance/     Aturan cleanup/retirement
│   └── archive/         Dokumen historis, bukan runbook aktif
└── AGENTS.md            Instruksi kerja dan commit/push
```

**Backend yang dijalankan untuk PAM adalah Go di repositori terpisah**
[`0037-Backend-Financial`](https://github.com/NDI05/0037-Backend-Financial) (folder saudara
`../0037-Backend-Financial` saat pengembangan). Server source tidak lagi berada di repo
Android ini; build Android dari clone repo ini sendiri. Backend Node lama diarsipkan di luar
tree (lihat [kebijakan retirement](docs/maintenance/RETIREMENT_POLICY.md)). Room/SQLCipher
masih bagian transisi yang perlu dikeluarkan dari varian penilaian sesuai gate S4.

## 6. Menjalankan proyek lokal

### Prasyarat

- Android SDK; minimum perangkat API 26, compile/target SDK 36.
- JDK 21 untuk Android; Go 1.27.4 di repo backend terpisah (`../0037-Backend-Financial`).
- Wrapper existing menggunakan `.toolchain/`, `.gradle-home/` dan SDK lokal;
  lokasi alternatif didukung melalui konfigurasi wrapper.

Semua perintah berikut dimulai dari root repository `pam-app`.

### A. Backend development terisolasi

```bash
cd ../0037-Backend-Financial
PAM_GOROOT=../pam-app/.toolchain/go \
APP_ENV=development PORT=8080 \
  DATABASE_URL=file:./data/pam-readme-dev.db \
  LLM_PROVIDER=fake CATEGORY_LLM_ENABLED=false \
  ./scripts/go.sh run ./cmd/api
```

Perintah ini memakai database development lokal dan classifier nonaktif. Tidak menggunakan
database UAT pemilik atau membuat panggilan Azure. Registrasi publik tidak memerlukan kode
undangan sejak 25 November 2026. Konfigurasi rinci: README repo backend dan
[kontrak API](docs/06_BACKEND_CONTRACT.md).

### B. Android Studio dan UAT pemilik

Buka direktori `pam-app` di Android Studio. Gunakan JDK 21 sebagai Gradle JDK, pilih
varian `debug`, konfigurasi Run `app`, dan emulator. Tambahkan atau perbarui pengaturan
ini di `local.properties` tanpa menghapus `sdk.dir`:

```properties
PAM_BACKEND_BASE_URL=http://10.0.2.2:18090
```

Gradle membaca `local.properties` saat build. Opsi `-P` dan nilai dari `gradle.properties`
mendahuluinya. Gunakan alamat yang sama saat membuat APK pembanding. `10.0.2.2`
menghubungkan emulator ke host. Perangkat fisik perlu endpoint yang dapat dijangkau atau
alamat loopback yang cocok dengan `adb reverse`.

Jika backend UAT belum berjalan, dari root repo jalankan:

```bash
(cd ../0037-Backend-Financial && PAM_GOROOT=../pam-app/.toolchain/go \
  ./scripts/go.sh build -o /tmp/pam-android-studio-uat-api ./cmd/api)
python3 scripts/run_real_uat_with_classifier.py /tmp/pam-android-studio-uat-api
```

Launcher membaca profil privat di `~/.config/pam/` untuk Turso dan Azure. Untuk profil UAT
pemilik, gunakan `LLM_PROVIDER=azure` di `real-uat.env`. Launcher memakai deployment Azure
yang sudah tersedia di `azure-classifier.env` untuk Analisis. Pastikan port profilnya
`18090` dan `/readyz` merespons dari emulator. Readiness mengecek database.
Uji tombol **Kategorikan dengan AI** serta **Buat Analisis** secara terpisah di UI Android
Studio karena keduanya punya payload, consent, dan batas pemakaian masing-masing. Untuk build CLI UAT,
jalankan `./scripts/build.sh :app:assembleDebug` dengan konfigurasi lokal yang sama.

Profil UAT Analisis membatasi biaya aplikasi di USD0,25 per hari, timeout per panggilan
12 detik, dan maksimal 1.200 token keluaran. Batas classifier tetap terpisah.

Setelah menekan **Run**, masuk ke akun, pilih consent, lalu **Tautkan Gmail lewat browser**
(OAuth Gmail API; tidak ada App Password). Untuk UAT fixture di backend development, backend
disiapkan dengan provider `fake` dan aplikasi tetap memakai alur OAuth yang sama. Fixture/demo
harus berlabel; keberhasilan demo tidak membuktikan data server atau mailbox nyata.

### C. UAT Azure

Ikuti [setup Azure](docs/AZURE_CLASSIFIER_SETUP.md) dan instruksi pemilik/mandat aktif.
Launcher `scripts/run_real_uat_with_classifier.py` **mengaktifkan classifier**; jangan
memakainya sebagai startup development biasa. Pengujian fault injection memakai fixture
terisolasi. README ini tidak mengotorisasi perubahan cloud atau aktivasi pada data nyata.

## 7. Pengujian dan bukti

### Android

```bash
./scripts/build.sh :app:testDebugUnitTest
./scripts/build.sh :app:assembleDebug :app:lintDebug
```

### Go (repo backend `../0037-Backend-Financial`)

```bash
cd ../0037-Backend-Financial
PAM_GOROOT=../pam-app/.toolchain/go ./scripts/go.sh test -race -count=1 ./...
PAM_GOROOT=../pam-app/.toolchain/go ./scripts/go.sh vet ./...
PAM_GOROOT=../pam-app/.toolchain/go ./scripts/go.sh build ./...
```

| Jenis bukti | Yang dapat dibuktikan | Bukan bukti untuk |
|---|---|---|
| Unit/regression | Cabang perilaku dan kasus gagal yang diasert | Seluruh UX/runtime nyata |
| Integration | API/persistence/race dalam lingkungan uji | Semua kondisi cloud/perangkat |
| Smoke Azure | Request/response provider tertentu | Akurasi tinggi pada transaksi umum |
| Emulator E2E | Satu alur UI sampai persistence yang diamati | Alur lain atau seluruh sprint |
| Screenshot/layout | State visual yang dirender | Provider call, jadwal background atau TalkBack manual penuh |
| Owner UAT/benchmark | Penerimaan/evaluasi sesuai skenario dan dataset | Klaim bebas defect mutlak |

Evidence yang dilacak: `docs/evidence/` dan [checkpoint](docs/DELIVERY_S2_S4_CHECKPOINT.md).
Screenshot/status E2E berada pada direktori build lokal sesuai checkpoint, bukan selalu
tersedia di clone baru. `scripts/qa/e2e_update_status.py` menguji cold-start/status panel;
itu **belum** bukti lengkap email baru → delta → server → UI.

Bukti kontrak polling 24 November 2026:
- `scripts/qa/gmail/contract_e2e.py` (+ `README.md`) — harness black-box provider fake,
  **PASS=82, FAIL=0**: backfill 12/12, sync `202`→`429 sync_cooldown`, restart tanpa duplikat,
  recovery `history.list` 404, rate limit tidak permanen, `watch/stop=0`, push 404. Hasil:
  [GMAIL_CONTRACT_E2E](docs/evidence/GMAIL_CONTRACT_E2E_2026-11-24.md).
- UAT perangkat fisik FIXTURE (HP fisik (serial diredaksi), `adb reverse 18090`): backfill 12/12,
  manual sync + drain, cooldown 429 + hitungan mundur UI, delta saat app ditutup, restart
  backend tanpa duplikat. Hasil: [GMAIL_POLLING_UAT_PHONE](docs/evidence/GMAIL_POLLING_UAT_PHONE_2026-11-24.md).
  Ini bukan bukti live; Gmail live sekarang **PASS** pada akun pilot owner lewat
  [GMAIL_LIVE_IMPORT](docs/evidence/GMAIL_LIVE_IMPORT_2026-11-24.md), sedangkan login Google nyata
  di HP sudah terbukti (G36 **PARTIAL**; Run literal + onboarding Gmail belum) —
  [SSO_LIVE_PHONE](docs/evidence/SSO_LIVE_PHONE_2026-11-24.md).

## 8. Konfigurasi, data, dan biaya

- Consent `GMAIL_READ`, `AI_CATEGORIZATION`, dan `AI_PROCESSING` independen. Tautan Gmail
  memakai OAuth browser; refresh token hanya disimpan terenkripsi di backend (tidak di APK).
  Logout tidak otomatis menghapus tautan akun yang sama; disconnect/deletion dan owner
  isolation mengikuti lifecycle yang berlaku.
- **Target biaya GCP Rp0:** mode polling default tidak membutuhkan Pub/Sub topic/subscription,
  Cloud Run/endpoint publik, maupun verifier OIDC; backfill/delta memakai kuota Gmail API.
  Gate **Rp0 masih OPEN** karena Billing GCP masih tertaut dan pemakaian nyata belum diukur
  (owner).
- Token Turso, key Azure dan secret backend tidak berada di APK/Git/log. Jangan menyalin
  isi `.env` atau konfigurasi privat ke laporan.
- Total arus kas exact minor integer; bulan transaksi memakai Asia/Jakarta.
- USER override dipertahankan. Saran AI bukan keputusan pengguna atau probabilitas akurasi.
- Transaksi tetap tersimpan ketika kategorisasi gagal/budget habis; backend unavailable
  tidak boleh ditampilkan sebagai data kosong atau saldo nol.

| Kontrol classifier | Batas pilot yang dimandatkan |
|---|---|
| Budget runtime global | USD0,10/hari dan USD2/bulan |
| Outbound attempts | 20/owner/hari; 100/backend installation/hari, termasuk retry |
| Ukuran request | Maksimal 5 merchant unik; cap input/completion sesuai kontrak |
| Konkurensi dan retry | Maksimal 2 bersamaan; timeout 20 detik; maksimal 1 retry transient |
| Cloud budget Foundry | USD10/bulan sebagai lapisan luar; dukungan blocking GPT-OSS belum dibuktikan |

Azure menggunakan token berbayar dan kredit Student terbatas; jangan menyebut seluruh stack
selalu gratis. Biaya estimasi token berbeda dari tagihan Azure. Budget dan request cap juga
berbeda dari jaminan jadwal batch; integrasi worker/jadwal masih dalam review.
[Catatan cloud budget](docs/AZURE_MONTHLY_BUDGET_HANDOFF.md) ·
[Verifikasi harga](docs/evidence/azure_price_verification_2026-11-22.md).

## 9. Dokumentasi dan cara berkontribusi

| Dokumen | Kegunaan |
|---|---|
| [Progres harian](docs/PROGRESS_DAILY.md) | Hasil per tanggal, bukti, gate terbuka dan template update |
| [Living checkpoint](docs/DELIVERY_S2_S4_CHECKPOINT.md) | Handoff implementasi dan snapshot pengujian |
| [Mandat S2–S4](docs/agent-prompts/MANDAT_S2_S4.md) | Scope dan matriks 25 aksi CRUDS |
| [Mandat sub-agent paralel](docs/agent-prompts/PROMPT_SE_PARALLEL_DELIVERY.md) | Tiga worker, worktree terpisah, ownership dan gate integrasi |
| [Rencana auto-sync/status](docs/plans/AUTO_SYNC_CATEGORY_STATUS_PLAN.md) | AS/BC/UX/OBS acceptance dan dependency |
| [Draf CR-005](docs/changes/CR_005_DRAFT_AUTO_SYNC_BATCH_STATUS.md) | Perubahan untuk revisi SRS nanti; bukan SRS resmi baru |
| [SRS v0.7 DOCX](docs/specifications/PAM_SRS_v0.7_Personal_Finance_Draft.docx) / [PDF](docs/specifications/PAM_SRS_v0.7_Personal_Finance_Draft.pdf) | Baseline final yang dikumpulkan pemilik. Pemetaan penerimaan akademik masih terbuka. |
| [SRS v0.6 DOCX](docs/specifications/PAM_SRS_v0.6_Multi_Bank.docx) / [PDF](docs/specifications/PAM_SRS_v0.6_Multi_Bank.pdf) | Draf sebelumnya. Google SSO, Gmail polling, perluasan multi-bank, dan rencana hosting Go. |
| [Draf CR-008](docs/changes/CR_008_DRAFT_PRODUCT_SCOPE.md) | Keputusan pemilik untuk satu peran USER. Anggaran, lampiran transaksi, dan administrasi keluar dari target produk. Pemetaan akademik ditinjau sebelum revisi SRS disahkan. |
| [Rencana multi-bank dan deployment](docs/plans/MULTI_BANK_AND_GO_DEPLOYMENT_PLAN.md) / [Prototipe interaktif](docs/design/pam-rekening-multi-bank.html) | Persetujuan desain, contoh email, dan host tanpa biaya tambahan masih terbuka |
| [SRS v0.5 DOCX](docs/specifications/PAM_SRS_v0.5_Azure_LLM.docx) / [PDF](docs/specifications/PAM_SRS_v0.5_Azure_LLM.pdf) | Baseline sebelum revisi v0.6 |
| [Traceability](docs/01_SCOPE_TRACEABILITY.md) / [Arsitektur](docs/03_ARCHITECTURE.md) / [API](docs/06_BACKEND_CONTRACT.md) | Requirement, data flow dan wire contract; perhatikan tanggal snapshot |
| [Cleanup/retirement](docs/maintenance/RETIREMENT_POLICY.md) | Menghapus obsolete dan mengarsipkan histori dengan aman |
| [Arsip README sebelumnya](docs/archive/README_BEFORE_RESTRUCTURE_2026-11-23.md) | Riwayat dokumentasi, bukan petunjuk runtime terbaru |

Baca [AGENTS.md](AGENTS.md) sebelum bekerja. Setiap perubahan koheren di-commit lalu
langsung di-push ke branch kerja yang sama. Merge/deploy memerlukan instruksi terpisah.
Perbarui baris tanggal hari ini pada log harian dengan commit, bukti dan gate tersisa;
jangan menulis PASS hanya karena source ditambahkan atau build berhasil.

**Status gate 24 November 2026 (HEAD `51985f3`; backend `00bb515`):** lane Gmail polling +
sinkron server ditutup dengan bukti terkontrol; Gmail live pilot (G25) sudah PASS dan login Google
nyata di HP sudah terbukti (G36 PARTIAL; Run literal + onboarding Gmail belum), sedangkan
Rp0 (G29/G37) tetap terbuka.
- Android fresh (ronde 7–18): `testDebugUnitTest` **678 tes/88 suite/0 gagal**, `lintDebug`
  **0 error/123 warning/1 hint**, `assembleDebug` PASS, APK debug **47.992.939 byte** sha256
  `5368c92f…`; commit kode `e67380a`, tes SSO `4eeffd6`/`35a72ef`, HEAD dokumen `51985f3`.
- Backend fresh: `vet`/`build`/`test ./... -count=1` PASS (10 paket), `-race` auth/gmail/httpapi/store
  PASS, **21 migrasi**; kode `c69cf57` (marker `sync_requested_at`, kontrak
  `429 {"code":"sync_cooldown",...}`, fase kegagalan, `409 gmail_needs_reauth`, penutupan P1/P2/P3
  audit) + tes cooldown `00bb515` (HEAD).
- Kontrak polling §9: **QA E2E 82/82 PASS** (fake provider, port terisolasi) diulang pada
  `c69cf57`/`00bb515`, dan **UAT perangkat FIXTURE PASS** (backfill 12/12, delta saat app ditutup,
  sync 202→429, restart tanpa duplikat). Gate tally: **38 gate — 29 PASS, 5 PARTIAL, 4 NOT RUN**.
- S2 tetap **PARTIAL kondisional** (menunggu Run Android Studio pemilik untuk varian final +
  drill-down-after); S3 PASS sesuai bukti lama; S4 PARTIAL (gap C20/C24/C25 di perangkat).
- **PARTIAL (owner):** G36 SSO live HP — login Google nyata + sesi PAM + binding identitas sudah
  terbukti di HP (21:49–21:50 WIB), sisa tombol **Run literal** + onboarding Gmail + email baru saat
  app tertutup. **NOT RUN (owner):** Pub/Sub nyata (hanya bila push opt-in dipakai), Azure nyata,
  dan **GCP Rp0** (**OPEN**: Billing masih tertaut). Gmail live (G25) sekarang PASS untuk akun pilot
  owner; bukan bukti untuk akun lain. Tombol **Run Android Studio secara literal masih NOT RUN**
  (UAT dipasang via `adb install -r`; lihat penyimpangan di evidence UAT).
- Keputusan per gate: [FINAL_GATES_GMAIL_YEARLY_WORKFLOW](docs/delivery/FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md)
  dan [GATE_DECISIONS_S2_S4](docs/delivery/GATE_DECISIONS_S2_S4_2026-11-24.md). Gate terbuka:
  correctness auto-sync lanjutan, drill-down-after nav dengan data, dedupe re-import
  antar-perangkat, benchmark berlabel manusia, coverage parser, perangkat/background,
  backup/restore, availability, serta merge/deploy (butuh instruksi pemilik).
