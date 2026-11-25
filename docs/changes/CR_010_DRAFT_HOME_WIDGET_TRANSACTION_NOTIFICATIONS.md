# CR-010 — Widget Ringkasan dan notifikasi transaksi baru

**Status:** Usulan untuk review pemilik, 25 November 2026 WIB. Acuan: SRS final PAM v0.7. Nomor kebutuhan di bawah bersifat sementara untuk menjaga keterlacakan.

## Tujuan dan kondisi saat ini

Widget Android membawa data kartu bank dari Ringkasan ke layar utama HP. Notifikasi memberi tahu pengguna ketika transaksi Livin baru sudah tersimpan. README PAM mendokumentasikan polling Gmail setiap 15 menit selama backend berjalan. Pemeriksaan sumber Android belum menemukan implementasi App Widget atau penerima Firebase Messaging. Dukungan multi-bank pada widget mengikuti ketersediaan bank di aplikasi.

## Kebutuhan fungsional usulan

| ID | Kebutuhan | Kriteria penerimaan |
|---|---|---|
| W-01 | Pengguna dapat menambahkan widget Android dan memilih satu bank per widget. Mandiri tersedia pada tahap awal. Saat multi-bank aktif, bank yang tersedia bisa dipilih dan beberapa widget dapat dipasang dengan pilihan berbeda. | Dua widget dengan pilihan berbeda tidak saling mengubah konfigurasi. Carousel tetap berada di Ringkasan. |
| W-02 | Widget menampilkan nama bank, bulan, jumlah transaksi tercatat dan waktu pembaruan bila diperlukan. Periode default mengikuti bulan berjalan WIB. | Jumlah sama dengan kartu Ringkasan untuk bank dan periode yang sama. Nol hanya muncul setelah server mengonfirmasi. Bulan baru tidak menampilkan hitungan bulan lalu dengan label baru. |
| W-03 | Tap widget membuka Ringkasan pada bank dan periode yang ditampilkan, setelah sesi diverifikasi. | Ada keadaan belum masuk, memuat, kosong, offline dengan data terakhir bertanggal, dan gagal tanpa data. Setelah logout atau pindah akun, widget dirender ulang tanpa angka akun sebelumnya. |
| N-01 | Pengguna dapat mengaktifkan atau menonaktifkan notifikasi transaksi baru dari Pengaturan. Default nonaktif. Minta izin sistem Android bila dibutuhkan setelah pengguna memilih aktifkan. | Izin ditolak atau channel dimatikan tidak menghambat sinkronisasi. UI memberi akses ke pengaturan sistem bila relevan. |
| N-02 | Notifikasi dipicu oleh transaksi Livin yang benar-benar baru dan tersimpan melalui sinkronisasi delta. Kelompokkan transaksi yang masuk pada satu siklus sinkronisasi. | Satu transaksi menghasilkan “Transaksi baru tercatat”. Beberapa transaksi menghasilkan “3 transaksi baru tercatat”. Backfill awal, impor ulang, pemulihan riwayat, retry, duplikat dan transaksi manual tidak memicu notifikasi baru. |
| N-03 | Tap notifikasi membuka detail bila hanya satu transaksi, atau daftar untuk kelompok terkait. | Aplikasi memeriksa sesi dan kepemilikan sebelum membuka data. Jika transaksi sudah tidak tersedia atau perangkat sedang memakai akun lain, arahkan ke daftar yang dapat diakses tanpa menampilkan data akun sebelumnya. |

## Privasi, pengiriman dan pembaruan

- Widget menggunakan snapshot agregat minimal per akun di penyimpanan privat aplikasi. Tampilkan data di launcher setelah pengguna memasang widget. Hapus snapshot saat logout, pindah akun, atau penghapusan akun. Tanpa sesi yang sah tampilkan “Masuk untuk melihat ringkasan”.
- Notifikasi layar kunci memakai teks umum tanpa nominal, merchant, email, atau nomor rekening. Batalkan notifikasi akun saat logout dan lepaskan keterkaitan perangkat dengan akun. Abaikan pesan tertunda yang tidak cocok dengan sesi dan pengaturan aktif.
- Opsi teknis: Jetpack Glance untuk widget. Refresh dari Ringkasan, saat ada sinyal perubahan dengan pekerjaan latar terjadwal yang dibatasi, atau saat membuka aplikasi. Gunakan satu snapshot bersama untuk widget yang memilih bank dan periode sama. Jadwal latar bersifat best effort.
- Rekomendasi notifikasi latar: backend Go menyimpan event secara durable bersama hasil impor, lalu mengirim melalui FCM ke perangkat yang terdaftar. Gunakan identitas event stabil dan deduplikasi di server serta perangkat. Payload hanya membawa penanda event buram dan teks umum bila diperlukan. Ambil detail melalui API PAM setelah otorisasi.
- Latensi dihitung sejak email diterima sampai transaksi tersimpan dan notifikasi tampil. Polling Gmail tetap berjalan sesuai jadwal server. FCM bekerja setelah penyimpanan selesai. Ukur latensi pada HP fisik ketika aplikasi terbuka, di latar, dan ditutup normal. Catat kondisi offline, izin ditolak, dan force-stop secara terpisah.
- FCM tercantum tanpa biaya layanan. Verifikasi konfigurasi proyek sebelum aktivasi. Rancangan ini memakai proses Go yang sudah berjalan dan tidak membutuhkan Cloud Functions atau Pub/Sub baru. Widget dan notifikasi tidak memicu permintaan AI.

## Review sebelum implementasi

Konfirmasi penggunaan satu bank per widget serta pengelompokan satu notifikasi per siklus sinkronisasi. Review mockup pada launcher terang dan gelap, izin Android, layar kunci dan navigasi dari notifikasi. Acceptance mencakup akun berganti, event yang dikirim ulang, backfill awal, impor delta saat aplikasi tertutup normal, refresh setelah pergantian bulan WIB, dan data offline. Implementasi dan UAT belum dilakukan.

Rujukan: [Pembaruan Glance](https://developer.android.com/develop/ui/compose/glance/glance-app-widget), [Izin notifikasi Android](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [Harga Firebase](https://firebase.google.com/pricing).
