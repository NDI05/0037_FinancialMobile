# UAT akhir SRS v0.7 di HP fisik — 24 November 2026

Status: **PASS untuk alur yang benar-benar dicoba** pada pasangan build di bawah. Dokumen ini
membedakan tegas: (a) fitur yang masih berjalan, (b) tiga modul yang dipensiunkan dari jalur aktif,
(c) gate yang tetap milik pemilik (Run literal, onboarding Gmail/consent, email baru saat aplikasi
tertutup, Rp0/billing, drill hapus-restore data pemilik, merge/deploy/SRS).

## 1. Pasangan build yang diuji

| Komponen | Nilai |
|---|---|
| Android | repo `pam-app`, branch `feat/c2-android`, HEAD **`a629c41`** (`8eae86e` docs P2/P3, `b56e28f` catatan+tanggal manual, `a629c41` refresh daftar setelah simpan manual) |
| Backend | repo `0037-Backend-Financial`, branch `integration/2026-11-24`, HEAD **`dd5bc88`** |
| Varian Android | `debug`, package `com.pam.recap.debug` |
| APK diuji | `app/build/outputs/apk/debug/app-debug.apk`, **48.819.325 byte**, sha256 `e7a1f4afcb40261c65ada103963d9c56fa5bb261a7a2018341f5142acf82dde4` (fingerprint build; byte tidak deterministik antar-build) |
| Instalasi | `adb -s 105397ba0507 install -r app/build/outputs/apk/debug/app-debug.apk` → `Success` (**bukan** tombol Run Android Studio) |
| Perangkat | HP fisik `105397ba0507`, portrait 1080×2400 @440 dpi, font scale 1.0 |
| Backend runtime | `~/.config/pam/uat-runtime/pam-api-loopback` (rebuild dari `dd5bc88`, sha256 `3bdcca47…`), `127.0.0.1:18090`, `adb reverse tcp:18090 tcp:18090`, `/readyz` = ready |
| Basis data | `~/.config/pam/pam-gmail-uat.db` (akun UAT sintetis `owner-uat@pam.test`; data pilot owner tidak diubah) |
| Profil provider | Gmail nyata (`GMAIL_PROVIDER=google`), kategori LLM **nonaktif** (`CATEGORY_LLM_ENABLED=false`), `LLM_PROVIDER=fake` untuk Analisis (env UAT; bukan Azure berbayar) |

## 2. Fitur yang masih berjalan (hasil UAT)

### 2.1 Entri manual (FR-26) — termasuk catatan opsional dan batas tanggal NFR-15

| Langkah | Hasil |
|---|---|
| Transaksi → Tambah transaksi | Form "ENTRI MANUAL" tampil: Masuk/Keluar, Nominal, Keterangan, **Catatan (opsional)** dengan penghitung `0/500 karakter`, info "Setelah tersimpan, transaksi ini masuk ke ringkasan bulan November 2026." |
| Isi Nominal `3000`, Keterangan `UjiNoteFinal`, Catatan `catatan-final-v07` | Penghitung berubah `17/500 karakter`; tidak ada field lampiran/foto/berkas apa pun — form selesai tanpa file |
| Gulir form | Baris **Tanggal** (`24 Nov 2026`, chevron Sebelumnya/Berikutnya) dan kartu **KATEGORI** (chips) terlihat setelah di-scroll. Catatan: pada viewport 1080×2400 kedua kontrol berada di bawah fold, tetapi form dapat di-scroll — bukan cacat (sempat dicurigai hilang) |
| Simpan | `POST /v1/transactions` → **201**; kembali ke daftar Transaksi |
| Daftar setelah simpan (fix `a629c41`) | Baris langsung tampil **tanpa pindah bulan**: `Selasa, 24 Nov` / `UjiNoteFinal` / `12:00 · Keluar · Belum berkategori` / badge **Manual** / `Rp3.000`; tidak perlu refresh manual |
| Persistensi catatan | `GET /v1/transactions?month=2026-11` memuat `description=UjiNoteFinal`, **`note=catatan-final-v07`**, `source=MANUAL`, `categoryOrigin=USER`; DB menyimpan catatan sebagai ciphertext (57 byte, bukan plaintext) |
| Ringkasan | KELUAR naik sesuai nominal (sempat terlihat `Rp82.443,73` saat baris uji ada) dan kembali `Rp32.443,73` setelah baris uji dihapus |
| Cleanup | Baris uji dihapus via `DELETE /v1/transactions/{id}` → **204**; daftar/ringkasan kembali konsisten |

Reproduksi regresi daftar basi sebelum fix: simpan manual lalu daftar tetap mulai `Senin, 23 Nov`
(baris 24 Nov tidak muncul) sampai bulan digeser Nov→Okt→Nov — terulang 2×; setelah `a629c41`
baris langsung muncul pada percobaan yang sama (dump `assets/final_v07/ui_note_saved.xml`).

### 2.2 Impor Gmail, Ringkasan, koreksi kategori, Analisis (consent terpisah)

| Fitur | Hasil pada build UAT |
|---|---|
| Impor Gmail/polling | Status server Gmail: `13 dari 13` bulan selesai, retensi November 2025–November 2026, ringkasan "Sebagian" setelah cek, transaksi tersimpan tetap 914 baris unik (tidak ada duplikat pada login ulang) |
| Ringkasan | Ringkasan November 2026 termuat (MASUK/KELUAR/arus kas bersih, komposisi, transaksi terbaru) |
| Koreksi kategori | Diuji pada lane sebelumnya (`b56e28f`/`8eae86e`): `PATCH /v1/transactions/{id}` dengan `categoryId` mengubah kategori dan `categoryOrigin`; `""` menghapus kategori (perilaku arah null yang terdokumentasi di audit) |
| Analisis opsional (consent terpisah) | Buat analisis konsisten: `POST /v1/analyses` → **200**, layar menampilkan **ANALISIS TERSIMPAN**, Observasi ("Arus kas keluar bulan ini tercatat pada ringkasan."), "1 dari 1", **BATASAN** copy, "Buat ulang analisis"; tab Saran **tidak** menampilkan saran anggaran (modul anggaran pensiun). Fitur ini di balik persetujuan "Analisis AI" terpisah di Pengaturan |

### 2.3 Ringkasan → Transaksi (FR-32) dan navigasi kembali

| Langkah | Hasil |
|---|---|
| Ringkasan → ketuk kartu **KELUAR** | Membuka daftar Transaksi berisi hanya baris arah **Keluar** (`00:00 · Keluar · …`), periode November 2026 |
| Back | Kembali ke **Ringkasan** (bottom-nav/back konsisten); tidak ada duplikasi halaman |

Dimensi `bank` pada drill-down masih **terblokir keputusan produk**: kontrak tidak memiliki dimensi
bank, parameter diterima tetapi tidak dipakai (CR-008 catatan FR-32; bukan cacat baru).

### 2.4 Sheet Status pembaruan (FR-79/FR-80)

| Langkah | Hasil |
|---|---|
| Dari Transaksi/Ringkasan ketuk ikon status | Sheet "Status pembaruan" terbuka; hanya `GET /v1/gmail/status` di log — **tidak** ada `POST /v1/gmail/sync`, jadi membuka sheet tidak memulai sinkron |
| Isi sheet | Data transaksi (periode November 2026, riwayat November 2025–November 2026, catatan bulan parsial), Sinkron server Gmail status **Menunggu**, `13 dari 13`, **Percobaan terakhir** dan **Terakhir berhasil sinkron** sebagai dua baris terpisah (`10 menit lalu`), "Sinkron berikutnya sekitar 23:14", tombol **Sinkronkan sekarang**, Kategori "Tidak ada kategori tertunda", bagian "Perlu perhatian" |
| Menutup sheet | Proses server tidak dihentikan (worker backend tetap jalan; perilaku ini juga dibuktikan di lane sebelumnya) |

`POST /v1/category-jobs/enqueue` → **503** pada sesi ini adalah konfigurasi runtime UAT
(`CATEGORY_LLM_ENABLED=false`), bukan regresi produk.

### 2.5 Login Google (G36, sub-butir login/logout/relogin)

Diuji pada **build final `a629c41`** hasil `adb install -r` (bukan Run literal):

| Langkah | Bukti |
|---|---|
| Pengaturan → Keluar | `DELETE /v1/sessions` → **204**; layar login tampil |
| Masuk → **Masuk dengan Google** | `GET` nonce SSO → **200**, picker Credential Manager tampil ("Continue as …"), `POST /v1/auth/google` → **200** (rute SSO tercatat `{unknown}` di middleware), `GET /v1/me` → **200** |
| Setelah login | Ringkasan November termuat; status Gmail `GET` 200; transaksi/kategori/summary dimuat ulang; **914 baris tetap unik** (tidak ada auto-merge/duplikasi) |

## 3. Tiga modul yang dipensiunkan — tidak lagi dapat diakses

| Modul | UI build final | API build final (`dd5bc88`, token valid) | Data/migrasi |
|---|---|---|---|
| Anggaran/rencana tabungan/limit per kategori/saran AI sebagai anggaran | Tidak ada entri anggaran di Pengaturan/navigasi; tab Saran Analisis kosong dari saran anggaran | `/v1/budget-plans`, `/v1/budget-plans/current`, `/v1/budgets` GET/POST → **404** semua | Tabel/baris/migrasi anggaran **tetap**; tidak ada penghapusan |
| Lampiran/foto/PDF/kamera/OCR transaksi | Tidak ada field/layar lampiran; form manual selesai tanpa file | `/v1/attachments`, `/v1/attachments/{id}` → **404** semua method | File/baris lampiran lama **tidak dihapus** |
| Administrasi pengguna/admin/invitation/role | Tidak ada dashboard admin/undangan; Pengaturan hanya akun sendiri; satu peran `USER` | `/v1/admin/accounts`, `/v1/admin/invitations`, `/v1/admin/users` → **404** semua method | Tabel peran/invitation dan riwayat migrasi **tetap** |

Kontrol positif: `/v1/me` dan `/v1/gmail/status` tetap **200** dengan token yang sama; UI tetap
menampilkan akun sendiri, sesi, dan tautan Google. Detail matriks 404 ada di
`docs/evidence/FINAL_RUNTIME_SMOKE_2026-11-24.md` dan laporan audit v0.7.

## 4. Gate yang tetap milik pemilik (tidak diklaim PASS)

| Gate | Status pada putaran ini | Alasan/bukti terakhir |
|---|---|---|
| G14 drill hapus/restore data nyata | **NOT RUN (owner)** | Drill fixture DB terpisah **PASS** (60/60, termasuk restore); salinan DB pemilik + izin + drill hapus belum ada. Tidak ada operasi hapus terhadap DB aktif |
| G24 merge `main`/deploy/revisi SRS resmi | **NOT RUN (owner)** | Menunggu keputusan owner atas CR-005/006/007/008 dan persetujuan SRS v0.7 |
| G29/G37 target biaya GCP Rp0 | **NOT RUN / OPEN (owner)** | Tidak ada akses read-only GCP/billing dari sesi ini; Billing belum diverifikasi dilepas |
| G36 Run literal Android Studio | **NOT RUN (owner)** | Instalasi dilakukan via `adb install -r`; tombol Run literal belum dijalankan pada build ini |
| G36 onboarding Gmail (Lewati/Tautkan + consent) | **NOT RUN (owner)** | Tidak dijalankan pada sesi ini; koneksi pilot sudah tertaut sejak G25 dan tidak diputus |
| G36 email Livin baru saat aplikasi tertutup | **NOT RUN (owner)** | Tidak ada email Livin baru yang tiba secara alami pada jendela uji; polling berjalan tanpa kegagalan |
| Aksesibilitas lanjutan (TalkBack, lebar 393/412 dp) | **PARTIAL** | Bukti lama tetap berlaku; walk manual di build ini tidak diulang |

## 5. Artefak mentah

- `docs/evidence/assets/final_v07/ui_note_saved.xml` — daftar setelah simpan manual (baris 24 Nov + badge Manual)
- `docs/evidence/assets/final_v07/ui_fix1.xml` — form entri manual terisi (catatan + penghitung)
- `docs/evidence/assets/final_v07/ui_sheet_final3.xml` — sheet Status pembaruan (dua timestamp terpisah)
- `docs/evidence/assets/final_v07/ui_fr32c.xml` — drill-down kartu KELUAR (hanya baris Keluar)
- `docs/evidence/assets/final_v07/ui_set2.xml` — Pengaturan tanpa entri admin/anggaran/lampiran
- `docs/evidence/assets/final_v07/ui_afterlogin2.xml` — Ringkasan setelah logout→login Google

Semua dump disanitasi: hanya email akun UAT sintetis; tidak ada token, nonce, `JWT_SECRET`,
email pribadi, nomor/ID Gmail, atau nominal data pilot pemilik.
