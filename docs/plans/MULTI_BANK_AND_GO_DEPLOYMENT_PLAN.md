# Rencana transaksi multi-bank dan deployment Go

Status 24 November 2026 **perencanaan**. Prototipe menunggu persetujuan desain. Implementasi bank baru dan deployment belum dimulai dalam revisi ini.

[Rancangan SRS v0.6](../specifications/PAM_SRS_v0.6_Multi_Bank.docx) · [Prototipe pemilih bank](../design/pam-rekening-multi-bank.html)

## Keputusan pemilik

| Aspek | Arah yang disepakati |
|---|---|
| Rilis awal | Mandiri lebih dulu. QRIS, transfer, dan pembayaran virtual account masuk target. Aktifkan setiap jenis setelah contoh notifikasi tervalidasi. |
| Tahap berikutnya | Bank Jago dan BRI setelah sumber dan cakupan tiap jenis terbukti. |
| Pemilih | Satu kartu per bank pada tahap awal. Beberapa rekening pada bank yang sama berkontribusi pada kartu bank yang sama. |
| Angka kartu | Jumlah transaksi unik yang tercatat pada bank dan periode terpilih. |
| Transfer antar-rekening sendiri | Keputusan pengaruhnya pada Ringkasan menunggu review alur. Pertahankan rumus baseline selama keputusan terbuka. |
| Biaya | GCP Rp0 untuk pilot dan hosting Go tanpa biaya tambahan. Host belum dipilih. |

## Sumber dan cakupan transaksi

| Bank | Yang diketahui | Gate sebelum auto-import |
|---|---|---|
| Mandiri Livin | SRS sebelumnya dan bukti fixture mendukung jalur impor Livin. Dokumen Mandiri menyebut bukti elektronik termasuk email. Cakupan template transfer masuk, transfer keluar, dan VA belum ditetapkan. | Sampel Gmail tersamarkan per jenis dan arah yang akan diaktifkan. Catat pengirim, subjek, struktur waktu, status, nominal, dan label merchant atau biller yang tersedia. Verifikasi kasus gagal dan duplikat. |
| Bank Jago | Ketentuan resmi mencantumkan email sebagai salah satu opsi notifikasi. Riwayat atau e-statement per Kantong tersedia di aplikasi Jago. | Pastikan notifikasi consumer pada akun pilot, jenis dan batas nominal yang dikirim, serta perbedaan antar-Kantong. Jangan memakai bukti notifikasi Jago bisnis untuk menyimpulkan dukungan consumer. |
| BRI | Halaman Notifikasi Transaksi BRImo yang diperiksa mendokumentasikan WhatsApp/SMS. | Buktikan jalur email consumer yang sesuai bila ada. Jika tidak ada, PM meminta keputusan sumber lain. Integrasi BRIAPI, WhatsApp, atau SMS memerlukan pembahasan terpisah. |

[Syarat Livin](https://www.bankmandiri.co.id/livin/syarat-dan-ketentuan/general-aplikasi-livin) · [Notifikasi Jago](https://www.jago.com/id/terms-and-conditions) · [Riwayat Jago per Kantong](https://www.jago.com/id/jago/support/faq/menggunakan-jago/kantong/download-e-statement-dan-riwayat-transaksi) · [Notifikasi BRImo](https://www.bri.co.id/web/guest/promo-detail/1924)

### Usulan desain untuk persetujuan

Ketuk nama bank pada kartu Ringkasan untuk membuka bottom sheet **Pilih bank**. Pilihan mempertahankan periode. Kartu, jumlah QRIS/transfer/VA, dan cuplikan transaksi memakai filter yang sama. Opsi **Semua bank** dan contoh tabel kosong bagian dari prototipe. Data riil tetap digolongkan **Belum diketahui** bila sumber tidak bisa ditetapkan. Pada tahap ini angka kartu memakai hitungan transaksi. Nama Jago dan BRI dalam prototipe adalah contoh tahap berikutnya.

Bank, metode transaksi, arah dana, dan kategori merupakan atribut berbeda. Bank terpilih menunjukkan sumber yang semestinya tercatat pada transaksi, sementara label tujuan, virtual account, atau merchant tidak mengubah bank sumber. Kunci deduplikasi tetap dinilai per owner dan identitas sumber. Tes wajib membedakan transaksi bernilai sama, konflik bukti, replay, dua owner, dan perubahan filter bank/periode. Konflik arah atau status masuk tinjauan.

Review aturan transfer milik sendiri menggunakan contoh dua sisi dari dua bank, hanya satu sisi, duplikat email, reversal, dan biaya transfer. Tetapkan pengaruh pada pemasukan, pengeluaran, dan laporan sebelum mengubah perhitungan. Jangan menebak kepemilikan dari nominal atau nama penerima. Sampel QA memakai data sintetis atau tersamarkan dengan izin pemilik.

[Material 3 ModalBottomSheet](https://developer.android.com/develop/ui/compose/components/bottom-sheets)

## Rencana deployment Go

Backend **0037-Backend-Financial** tetap terpisah dari Android. Timer polling saat ini berada dalam proses Go. Target hosting perlu membuat proses tetap hidup ketika aplikasi ditutup.

| Opsi | Cocok untuk | Batas biaya dan operasi |
|---|---|---|
| Mac dan HP via USB | UAT developer dari Android Studio, API loopback, OAuth callback debug | Tidak menambah tagihan hosting. Berhenti saat Mac tidur atau proses Go mati. |
| Host yang sudah tersedia | Kandidat pilot berkelanjutan, jika ada mesin Linux yang boleh digunakan terus dan URL HTTPS yang dapat dipakai tanpa biaya tambahan | Host, koneksi, dan hak pakai domain belum dikonfirmasi. Tidak ada pembelian atau provisioning dalam rencana ini. |
| OCI Always Free | Opsi evaluasi sebagai cadangan | Kapasitas host bergantung wilayah dan Oracle bisa mengambil kembali instance gratis yang idle. Periksa syarat akun dan apakah konfigurasi lengkap tetap gratis sebelum memilih. |
| Render Free atau Cloud Run default | Demo HTTP atau evaluasi pendek | Render Free tidur saat idle. Cloud Run default mengalokasikan CPU untuk request dan tidak menjamin timer terus berjalan. Keduanya dikeluarkan dari target pilot polling ini. |

### Urutan ketika host dan URL disetujui

1. Pin commit backend dan toolchain Go dari repositori backend. Jalankan CI Go, lalu bangun binary Linux `./cmd/api` atau image multi-stage untuk arsitektur host. Simpan build ID dan checksum.
2. Jalankan satu instance sebagai layanan dengan restart otomatis. Pakai konfigurasi production, Turso terpisah dari fixture, dan secret di luar Git. TLS valid pada reverse proxy. Android release menggunakan URL HTTPS stabil dan OAuth callback terdaftar harus cocok persis. UAT USB tetap terpisah.
3. Dari staging, uji `/healthz`, `/readyz`, masuk dari HP, restart worker, delta setelah app ditutup, dan dua owner. Tandai tes Google asli dan verifikasi Billing sebagai gate tersendiri. Pantau jeda polling, error, serta kuota tanpa mencatat isi email.
4. Sebelum cutover, buktikan backup dan restore database terisolasi. Pindai kompatibilitas migrasi dan rancang rollback binary serta database sesuai kontrak. Tentukan pemilik host, domain, backup, secret, dan jadwal pemeliharaan.
5. Jika tidak tersedia host dan URL yang memenuhi batas biaya, jalankan UAT lokal dan biarkan deployment 24/7 terbuka sampai pemilik memilih host. Tidak perlu menambah layanan GCP sebagai syarat UAT.

> **Keputusan pemilik 25 November 2026:** karena Vercel Functions membatasi proses latar, pemilik
> meminta deploy pindah ke host gratis. Kandidat: **OCI Always Free** (rekomendasi, proses 24/7),
> **Render Free** (cepat, tetapi tidur saat idle), **GCP e2-micro Always Free** (akun sudah ada,
> egress 1 GB/bulan). Artefak siap di repo backend (`Dockerfile`, `render.yaml`,
> `deploy/free-host/`); langkah pemilik + batas biaya ada di
> [runbook host gratis](../delivery/FREE_HOST_DEPLOYMENT_2026-11-25.md). Platform final menunggu
> akun/VM dibuat pemilik; deploy nyata belum dijalankan.

[Panduan container Go](https://docs.docker.com/guides/golang/) · [Batas Render Free](https://render.com/docs/free) · [Cloud Run dan CPU untuk pekerjaan latar](https://docs.cloud.google.com/run/docs/configuring/billing-settings) · [Oracle Always Free](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
