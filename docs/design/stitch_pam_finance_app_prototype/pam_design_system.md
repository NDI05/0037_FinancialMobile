---
name: "PAM — Pencatat Keuangan Pribadi"
description: "Sistem desain Android light-theme untuk PAM: ruang kosong lega, tipografi Manrope tegas dengan angka tabular, permukaan hangat, dan aksen lime fungsional."
---

## Overview

PAM adalah aplikasi pencatatan keuangan pribadi Android yang merangkum transaksi manual dan notifikasi email Livin' by Mandiri dari Gmail. Sistem desain ini mengambil prinsip ketenangan editorial: ruang kosong yang lega, hierarki angka yang tegas sebagai fokus utama, permukaan putih hangat tanpa gangguan dekoratif, serta aksen lime `#D9F449` yang hanya dipakai secara fungsional untuk menandai aksi utama atau sorotan aktif.

Target pengguna adalah individu di Indonesia yang ingin melihat ringkasan pengeluaran dan pemasukan secara cepat, jujur, dan tenang—tanpa iklan produk keuangan, saldo bank, atau fitur transfer.

## Colors

Palet PAM dibangun di atas tema terang hangat (*warm light theme*) dengan kontras tinggi (WCAG AA >= 4.5:1 untuk teks, >= 3:1 untuk komponen UI). Aksen lime `#D9F449` selalu dipasangkan dengan teks gelap `#171915`, tidak pernah dengan teks putih.

- **Canvas & Surface:** Latar utama `#FAFAF7` (*warm white*) dipadukan dengan kartu `#FFFFFF` dan kontainer abu hangat `#F4F4F1` / `#EEEEEB`.
- **Primary Ink:** `#171915` digunakan untuk teks utama, angka nominal, dan tombol aksi primer dengan teks putih `#FFFFFF`.
- **Accent Lime:** `#D9F449` dipakai hemat untuk tombol CTA primer tertentu, indikator aktif, atau penanda sorotan data, selalu dengan `on-accent` `#171915`.
- **Semantic:** Hijau `#006D30` untuk sukses/pemasukan positif, oranye hangat `#9B4500` untuk peringatan/perlu ditinjau, dan merah `#B91C1C` untuk gagal/hapus.

```yaml
colors:
  canvas: "#FAFAF7"
  accent: "#D9F449"
  on-accent: "#171915"
  light:
    primary: "#171915"
    on-primary: "#FFFFFF"
    primary-container: "#EEEEEB"
    on-primary-container: "#171915"
    secondary: "#576500"
    on-secondary: "#FFFFFF"
    secondary-container: "#D9F449"
    on-secondary-container: "#171915"
    surface: "#FFFFFF"
    on-surface: "#171915"
    surface-container-lowest: "#FFFFFF"
    surface-container-low: "#F4F4F1"
    surface-container: "#EEEEEB"
    surface-container-high: "#E8E8E5"
    on-surface-variant: "#454742"
    outline: "#767871"
    outline-variant: "#E2E3DB"
    success: "#006D30"
    on-success: "#FFFFFF"
    success-container: "#E6F6EC"
    on-success-container: "#00210A"
    warning: "#9B4500"
    on-warning: "#FFFFFF"
    warning-container: "#FFEDE5"
    on-warning-container: "#331200"
    error: "#B91C1C"
    on-error: "#FFFFFF"
    error-container: "#FFEDEA"
    on-error-container: "#93000B"
```

## Typography

PAM menggunakan **Manrope** secara konsisten untuk seluruh hierarki teks, dengan `fontFeature: "tnum"` (tabular numbers) pada semua angka nominal, tanggal, dan metrik agar kolom angka sejajar rapi saat dipindai.

- **Angka utama (`display-lg-mobile`):** 32px / 700, `tnum`, untuk total pengeluaran tercatat pada Ringkasan dan nominal detail transaksi.
- **Judul halaman (`title-lg`):** 18px / 700, dipusatkan terhadap lebar layar di top bar.
- **Label & aksi (`label-lg`, `label-md`, `label-sm`):** 14px, 12px, 11px dengan bobot 600 untuk tombol, chip filter, dan navigasi bawah.

```yaml
typography:
  display-lg:
    fontFamily: "Manrope"
    fontSize: 36px
    fontWeight: 700
    lineHeight: 44px
    letterSpacing: -0.75px
    fontFeature: "tnum"
  display-lg-mobile:
    fontFamily: "Manrope"
    fontSize: 32px
    fontWeight: 700
    lineHeight: 38px
    letterSpacing: -0.5px
    fontFeature: "tnum"
  headline-md:
    fontFamily: "Manrope"
    fontSize: 24px
    fontWeight: 700
    lineHeight: 30px
    letterSpacing: -0.3px
    fontFeature: "tnum"
  title-lg:
    fontFamily: "Manrope"
    fontSize: 18px
    fontWeight: 700
    lineHeight: 24px
    letterSpacing: -0.2px
  title-md:
    fontFamily: "Manrope"
    fontSize: 16px
    fontWeight: 600
    lineHeight: 22px
    letterSpacing: -0.1px
  body-md:
    fontFamily: "Manrope"
    fontSize: 14px
    fontWeight: 500
    lineHeight: 20px
    letterSpacing: 0px
  body-sm:
    fontFamily: "Manrope"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 18px
    letterSpacing: 0px
  label-lg:
    fontFamily: "Manrope"
    fontSize: 14px
    fontWeight: 600
    lineHeight: 20px
    letterSpacing: 0.1px
  label-md:
    fontFamily: "Manrope"
    fontSize: 12px
    fontWeight: 600
    lineHeight: 16px
    letterSpacing: 0.15px
  label-sm:
    fontFamily: "Manrope"
    fontSize: 11px
    fontWeight: 600
    lineHeight: 14px
    letterSpacing: 0.2px
```

## Layout & Spacing

Tata letak menggunakan grid fluida berbasis kelipatan **4 dp (4px)** dengan margin horizontal layar **16–20 dp** (`20px` standar pada 393 dp dan 412 dp; `16px` pada 360 dp). Area sentuh minimum untuk setiap elemen interaktif adalah **48 × 48 dp**.

- **360 dp (Varian sempit):** Margin layar `16px`. Kartu metrik pendamping atau chip filter membungkus ke baris berikutnya bila label memanjang, nominal utama tetap `32px` dengan `min-w-0` agar tidak terpotong.
- **393 dp (Frame utama):** Margin layar `20px`. Judul top bar selalu berada di tengah layar (*center-aligned* absolut terhadap lebar layar) meskipun ada tombol kembali di kiri atau ikon status di kanan.
- **412 dp (Varian lebar):** Margin layar `20px`, ruang napas ekstra pada kartu dan baris daftar transaksi.
- **Font 200% (Aksesibilitas):** Kontainer teks tidak pernah memakai tinggi tetap (`height` dikunci); gunakan `min-height` dan tata letak fleksibel (`flex-wrap` / kolom vertikal) agar judul, nominal, chip filter, dan tombol di form maupun bottom navigation mengalir tanpa tumpang tindih atau terpotong saat skala font sistem mencapai 200%.

```yaml
spacing:
  gutter-xs: 4px
  gutter-sm: 8px
  gutter-md: 12px
  gutter-lg: 16px
  page-margin: 20px
  section: 24px
  block: 32px
```

## Elevation & Depth

PAM menggunakan permukaan datar (*flat tonal layering*) tanpa bayangan berat. Hierarki kedalaman dibentuk melalui perbedaan warna permukaan (`#FAFAF7` canvas -> `#FFFFFF` kartu utama -> `#F4F4F1` kontainer sekunder) dan pemisah tipis `1px` (`outline-variant` `#E2E3DB`). Bottom sheet dan modal menggunakan latar `#FFFFFF` dengan scrim `#171915` opasitas rendah dan garis tepi atas yang jelas.

## Shapes

Sudut membulat berada di rentang **16–24 dp** sesuai fungsi:
- `md` (`12px`): Input field, chip kecil, penanda sumber.
- `lg` (`16px`): Tombol utama, baris item interaktif, kartu ringkas.
- `xl` (`20px`): Kartu metrik, kartu daftar transaksi, kartu kategori.
- `2xl` (`24px`): Bottom sheet, dialog, kartu hero ringkasan.
- `full` (`9999px`): Pill filter, badge status, indikator progres.

```yaml
rounded:
  sm: 8px
  DEFAULT: 12px
  md: 12px
  lg: 16px
  xl: 20px
  2xl: 24px
  full: 9999px
```

## Motion & Micro-interaction

Gerakan bersifat cepat, tenang, dan fungsional. Saat `prefers-reduced-motion` (Reduce Motion) aktif, semua transisi diganti perubahan status instan tanpa pergeseran posisi. Label status sementara pada ikon sinkronisasi top bar muncul dengan fade-in dan fade-out singkat, lalu menyisakan ikon saja tanpa menggeser judul yang berada di tengah layar.

```yaml
motion:
  state-change:
    duration: 120ms
    delay: 0ms
    timingFunction: ease-out
  status-label-fade:
    duration: 200ms
    delay: 0ms
    timingFunction: ease-in-out
  sheet-enter:
    duration: 250ms
    delay: 0ms
    timingFunction: cubic-bezier(0.2, 0, 0, 1)
  sheet-exit:
    duration: 180ms
    delay: 0ms
    timingFunction: cubic-bezier(0.3, 0, 1, 1)
```

## Voice & Content Strategy

- **Nada bicara:** Tenang, jujur, lugas, dan ringkas dalam Bahasa Indonesia (*sentence case*). Hindari jargon teknis, klaim berlebih, atau kalimat motivasi keuangan.
- **Aturan Nominal & Tanggal:** Format Rupiah Indonesia tanpa spasi setelah `Rp` dan titik sebagai pemisah ribuan: `Rp1.250.000`. Pemasukan ditandai `+Rp3.500.000` (hijau `success`) dan pengeluaran `-Rp48.000` atau `Rp48.000` sesuai konteks ringkasan. Tanggal memakai format singkat lokal, misalnya `25 Nov 2026` atau `Hari ini · 09:42`.
- **Ikon & TalkBack:** Gunakan **Material Symbols Outlined** bergaya garis seragam (`wght 400`). Setiap ikon tanpa teks (kembali, tutup, sinkronisasi) wajib memiliki atribut `aria-label` / `contentDescription` TalkBack yang jelas (misalnya `"Status pembaruan: siap disinkronkan"`, `"Kembali ke halaman sebelumnya"`).
- **Kejujuran Data:** Jika periode kosong atau sinkronisasi belum mencakup seluruh periode, nyatakan secara faktual (`"Belum ada transaksi tercatat pada periode ini"`), jangan menyimpulkan pengguna tidak memiliki pengeluaran.

## Daftar Layar & Peta Interaksi (Handoff Jetpack Compose)

### Alur Utama (Clickable Prototype)
1. **Masuk (`Auth`)** -> Tap `"Masuk dengan Google"` -> **Tautkan Gmail (`GmailLink`)** -> Tap `"Tautkan Gmail"` (otorisasi -> berhasil) atau `"Lewati"` -> **Ringkasan (`Summary`)**.
2. **Ringkasan (`Summary`)**:
   - Pemilih bulan di dekat judul (dibatasi cakupan 1 tahun; bulan berjalan menonaktifkan panah masa depan).
   - Tap kartu `"Pengeluaran tercatat"` -> **Transaksi (`Transactions`)** dengan filter periode & arah `Pengeluaran` aktif.
   - Tap kartu `"Pemasukan tercatat"` -> **Transaksi (`Transactions`)** dengan filter periode & arah `Pemasukan` aktif.
   - Tap `"Lihat semua"` -> **Transaksi (`Transactions`)** dengan filter periode yang sama.
   - Tap ikon status di kanan top bar -> **Bottom Sheet Status Pembaruan (`SyncSheet`)** (bisa ditutup saat sinkronisasi berjalan, progres tetap tersimpan saat dibuka kembali, mencegah aksi ganda).
3. **Transaksi (`Transactions`)**:
   - Pencarian, filter periode, arah (`Semua`, `Pengeluaran`, `Pemasukan`), sumber (`Semua`, `Manual`, `Livin`).
   - Tap baris transaksi -> **Detail Transaksi (`TransactionDetail`)** (impor: nominal & tanggal *read-only*, koreksi kategori & catatan; manual: edit penuh / hapus).
   - Tap CTA `"Tambah transaksi"` -> **Tambah Transaksi (`AddTransaction`)** (toggle `Pengeluaran`/`Pemasukan`, nominal, tanggal masa depan nonaktif, deskripsi, pemilih kategori yang sama dengan kelola kategori, catatan opsional, tombol `"Simpan transaksi"` aman dari keyboard).
4. **Analisis (`Analysis`)**:
   - Insight utama, grafik horizontal berlabel (terbaca tanpa warna), temuan ringkas. State: belum ada persetujuan AI, memproses, tersedia, data berubah (`"Perbarui analisis"`), gagal.
5. **Pengaturan (`Settings`) & Kelola Kategori (`Categories`)**:
   - Dikelompokkan per tugas: Akun Google, Koneksi Gmail (`Putuskan Gmail`), Izin AI (kategorisasi & analisis terpisah), Kategori (`Kelola kategori`), dan `"Keluar"` terpisah di bawah.

### Asumsi Desain untuk Persetujuan
- **Asumsi 1 (Arah pada daftar Transaksi):** Pengeluaran ditampilkan tanpa tanda minus di kartu ringkasan utama, namun di daftar Transaksi memakai tanda `-Rp...` (ink) dan `+Rp...` (hijau `success`) agar mudah dipindai.
- **Asumsi 2 (Koreksi kategori AI):** Transaksi dengan `"Saran AI"` menampilkan chip oranye hangat ringan; setelah pengguna memilih kategori, status berubah permanen menjadi kategori pilihan pengguna dan tidak ditimpa sinkronisasi berikutnya.
- **Asumsi 3 (Cakupan 1 tahun):** Pemilih bulan membatasi navigasi mundur maksimal 12 bulan dari bulan berjalan (Nov 2025 – Nov 2026).

## Components

Component implementations live as individual HTML sticker sheet files in
`components/`. Each file contains the canonical, styled HTML components with
`<!-- COMPONENT: name -->` comment markers. Read each file before use.

- **Button** → `@components/button.html` — `button-primary`, `button-icon`, `button-with-icon`
- **Card** → `@components/card.html` — `card-elevated`, `card-action`, `card-interactive`, `card-interactive-2`
- **Chip** → `@components/chip.html` — `chip-action`, `chip-input`, `chip-with-icon`
- **List** → `@components/list.html` — `list-single`, `list-two-line`, `list-leading-icon`, `list-trailing-action`, `list-interactive`, `list-interactive-2`
- **Modal** → `@components/modal.html` — `modal-standard`, `modal-alert`, `modal-drawer`

## Widget Layar Utama (Glance) & Notifikasi Transaksi Baru (Handoff)

### 1. Widget Layar Utama Android (`PAM Ringkasan`)
- **Ukuran & Token Visual:**
  - Ukuran `2×2` (min `144dp` tinggi) dan `4×2` (min `124dp` tinggi).
  - Permukaan tetap putih hangat (`canvas` `#FAFAF7`, border `outline-variant` `#E2E3DB`, radius `2xl` `24dp`) baik pada wallpaper terang maupun gelap agar tetap konsisten dan terbaca.
  - Prioritas visual pada jumlah transaksi (`display-lg-mobile` / `headline-md`, `tabular-nums`), diikuti nama bank (`Mandiri`) dan periode (`November 2026`). **Larangan:** tidak menampilkan saldo, nominal, nama merchant, nomor rekening, atau aksi pembayaran.
  - Satu bank per widget (kartu tetap, bukan carousel). Konsep tahap berikutnya: pemilih bank saat memasang widget (`Mandiri`, `Jago`, `BRI`).
- **5 Keadaan Komponen & Copy:**
  1. `Data tersedia`: `"Mandiri · November 2026 · 42 transaksi"`
  2. `Periode kosong terkonfirmasi`: `"0 transaksi tercatat"`
  3. `Offline (snapshot)`: Data terakhir + `"Diperbarui 10.15"`
  4. `Belum masuk`: `"Masuk untuk melihat ringkasan"`
  5. `Gagal memuat tanpa snapshot`: `"Ringkasan belum tersedia"`
- **Tujuan Tap & Aksesibilitas:**
  - Tap kartu widget -> membuka `Ringkasan` pada bank & periode yang sama (`?bank=mandiri&periode=2026-11`), atau `Masuk` bila belum masuk.
  - Label TalkBack: `"Mandiri, November 2026, 42 transaksi"`.
  - **Catatan Engineer (Glance):** Pemilih widget, bingkai launcher, dan penskalaan font sistem dikendalikan oleh host launcher Android; pastikan `Glance` layout memakai `wrapContent` / `minHeight` tanpa tinggi teks terkunci.

### 2. Notifikasi Transaksi Baru
- **Pengaturan (`Pengaturan`):** Toggle `"Notifikasi transaksi baru"` dengan subteks `"Beri tahu saya saat transaksi Livin baru tercatat."`.
  - Keadaan: `Nonaktif`, `Aktif`, dan `Izin sistem ditolak` (menampilkan `"Buka pengaturan sistem"`). Dialog izin sistem (`POST_NOTIFICATIONS` Android 13+) hanya dipicu setelah pengguna mengaktifkan toggle. Penolakan izin tidak memblokir sinkronisasi maupun daftar transaksi.
- **Format Notifikasi Native & Layar Kunci:**
  - Satu transaksi: judul `"Transaksi baru tercatat"`, isi `"Buka PAM untuk melihat detail"` -> membuka `Detail Transaksi`.
  - Beberapa transaksi (1 siklus): judul `"3 transaksi baru tercatat"`, isi `"Buka PAM untuk melihat transaksi"` -> membuka tab `Transaksi`.
  - Layar kunci memakai teks umum tanpa nominal, merchant, atau email. Tidak dikirim untuk backfill awal, transaksi manual, impor ulang, atau duplikat.
  - Fallback bila transaksi tidak tersedia: arahkan ke tab `Transaksi` dengan pesan singkat `"Transaksi tidak lagi tersedia. Menampilkan daftar terbaru."`.
  - **Catatan Engineer:** Tampilan panel notifikasi, layar kunci, dan dialog izin mengikuti rendering sistem Android OS.
