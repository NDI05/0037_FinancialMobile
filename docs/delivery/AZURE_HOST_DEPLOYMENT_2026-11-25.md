# Deploy Host Sementara Azure (Indonesia Central) — 25 November 2026

Status: **DEPLOYED (sementara, memakai credit Azure for Students) · Gmail AKTIF (redirect terdaftar 25 Nov) · G36 NOT RUN**

## Mengapa Azure, bukan Oracle

- Pemilik berhasil daftar Oracle Cloud Free Tier, tetapi pembuatan instance selalu gagal:
  `Out of capacity for shape VM.Standard.A1.Flex` (ARM) — dan percobaan `VM.Standard.E2.1.Micro`
  (AMD) juga `Out Of Capacity` di semua availability domain yang tersedia.
- Atas keputusan pemilik, backend sementara dipindah ke **Azure for Students** yang masih punya
  credit ±USD 100. Tidak ada uang tunai keluar; biaya diambil dari credit.

## Kendala yang ditemukan saat provisioning

- Policy subscription mahasiswa membatasi region: `sys.regionrestriction` hanya mengizinkan
  **malaysiawest, centralindia, eastasia, japanwest, indonesiacentral**. `southeastasia` ditolak
  (`RequestDisallowedByAzure`).
- `Standard_B1s` tidak tersedia (kapasitas) di japanwest, indonesiacentral, maupun malaysiawest.
- Ukuran yang lolos validasi dan tersedia: **`Standard_B2ats_v2`** (2 vCPU, 1 GiB, AMD burstable)
  di **Indonesia Central** — retail USD 0.0107/jam (±USD 7.81/bulan).

## Infrastruktur yang dibuat

| Komponen | Nilai |
|---|---|
| Resource group | `rg-pam-api-id` (Indonesia Central) |
| VM | `pam-api-vm`, Ubuntu 24.04.5 LTS, x86_64, `Standard_B2ats_v2` |
| Disk OS | 30 GB Standard HDD (hemat biaya) |
| Jaringan | NSG `pam-api-nsg`: TCP 22/80/443; public IP Standard statis |
| Swap | 1 GB (`/swapfile`, fstab) sebagai pengaman RAM 1 GiB |
| Runtime | systemd `pam-api` + Caddy HTTPS (Let's Encrypt) |
| Domain | `https://pam-70-153-86-163.sslip.io` |
| DB | Turso `pam-azure-uat` (DB terpisah dari demo Vercel & UAT lokal; 22 migrasi jalan, tabel terbentuk) |
| Secret | hanya di `/etc/pam/pam-api.env` (mode 600), tidak dicetak ke chat/commit |

## Konfigurasi AI (hemat token)

- `LLM_PROVIDER=azure`, `AZURE_CLASSIFIER_*` (endpoint/key/deployment/region/model dari env privat).
- Analisis: `LLM_MAX_OUTPUT_TOKENS=1200`, timeout 12 dtk, `DAILY_AI_CEILING_USD=0.25`.
- Kategorisasi: `CATEGORY_LLM_ENABLED=true`, plafon harian **USD 0.10**, bulanan **USD 2.00**,
  harga per 1K token diisi sehingga biaya terhitung dan pagar `daily_ceiling_reached` aktif.
- `GMAIL_ENABLED=true` setelah pemilik mendaftarkan redirect URI; polling 900 dtk, cooldown sync 60 dtk;
  `GMAIL_PAGE_SIZE=50`, `GMAIL_MAX_PAGES_PER_RUN=10` agar backfill tiga bulan selesai dalam beberapa menit.

## Bukti verifikasi (25 Nov 2026, WIB)

| Cek | Hasil |
|---|---|
| `GET /healthz` | **200** `{"status":"ok"}` |
| `GET /readyz` | **200** `{"status":"ready"}` |
| `POST /v1/auth/google` body kosong | **400** (rute ada, validasi jalan) |
| TLS | issuer Let's Encrypt, CN `pam-70-153-86-163.sslip.io`, valid 25 Nov 2026 – 23 Feb 2027 |
| Turso `pam-azure-uat` | tabel `transactions`, `analyses`, `gmail_connections`, `auth_sessions`, dll. terbentuk |
| APK debug diarahkan ke host | `PAM_BACKEND_BASE_URL=https://pam-70-153-86-163.sslip.io`, build `:app:assembleDebug` sukses, terpasang di HP fisik |
| Gmail aktif | `GMAIL_ENABLED=true`; `POST /v1/gmail/connect/start` tanpa auth **401** (rute siap), callback state invalid redirect ke `pam://gmail/connected?reason=state_invalid` |

### Uji Gmail dan kategorisasi AI di host (25 Nov 2026, 10:00–10:52 UTC)

| Cek | Hasil |
|---|---|
| Koneksi Gmail pemilik | 1 baris `gmail_connections` state `ACTIVE`; `sync_state` `idle` setelah backfill |
| Backfill tiga bulan (Sep–Nov 2026) | **876** transaksi `EMAIL` tersimpan (per 10:51 UTC), 0 MANUAL di DB host |
| Klasifikasi AI | Drain 22 job: **12 `CLASSIFIED`**, **19 `NEEDS_REVIEW`**, sisa antre; attempt Azure tercatat di `category_usage` |
| Polling server | `gmail_sync_pass` periodik `"backfills":1`/`"deltas":0` dengan `failures:0`; `last_sync_at` bergerak (10:51:34 UTC) |

Insiden dan perbaikan (commit backend `3e95727`, sudah di `main`+CI):

- Gejala: kategori AI tidak pernah berjalan; `sync_state` tertahan `backfill`; log
  `gmail_import_finalize_failed` dan `SQLite error: database is locked`.
- Sebab: Turso menolak penulis kedua (`SQLITE_BUSY`) saat `ImportBatch`/`FinalizeImportJob`
  berjalan bersamaan dengan penulis latar; job bulan gagal finalize, sehingga gate
  `categoryAwaitingInitialBackfill` menahan classifier terus-menerus.
- Perbaikan: retry backoff (4 percobaan) hanya untuk konflik tulis pada `ImportBatch` dan
  `FinalizeImportJob`, plus error finalize ikut dilog agar bisa dibedakan dari mismatch hitungan.
- Sesudah fix: finalize sukses, `sync_state=idle`, classifier berjalan dan transaksi mulai
  berkategori tanpa mengubah plafon biaya Azure (USD 0.10/hari, USD 2.00/bulan kategorisasi).
- Tindak lanjut antrean awal (commit backend `fed2547`): job dari backfill mewarisi jendela 2 jam,
  jadi classifier hanya bisa jalan 3 hari sekali. Kini saat bulan backfill terakhir selesai queue
  dibuka ulang (semua job PENDING dibuat due) dan jeda jendela dapat diatur lewat
  `CATEGORY_LLM_DRAIN_INTERVAL_SECONDS` (default tetap 2 jam; host UAT memakai **60 detik**).
  Sesudah deploy `fed2547`: antrean awal habis dalam ±20 menit. Hasil akhir per 11:55 UTC:
  **358 transaksi `CLASSIFIED`**, **515 `NEEDS_REVIEW`**, **0 job tersisa**, biaya **USD 0.0246**
  dari 205 attempt (masih di bawah plafon harian USD 0.10). `NEEDS_REVIEW` bukan kegagalan:
  label merchant ambigu atau transfer personal memang sengaja tidak dikategorikan otomatis
  (keputusan manual lewat aksi “Kategorikan dengan AI”/review), sesuai aturan produk agar tidak
  menebak kategori.

### CI/CD: deploy otomatis ke host (25 Nov 2026)

Pipeline ada di repo backend `.github/workflows/deploy-host.yml` dan berjalan setiap push ke
`main` (atau manual `workflow_dispatch`):

1. `go vet`, `go test ./...`, build `CGO_ENABLED=0 GOOS=linux GOARCH=amd64` (binary ±14 MB);
2. upload binary ke `/tmp/pam-api.new` lewat SSH;
3. cadangkan binary lama ke `/opt/pam-api/pam-api.prev`, pasang binary baru secara atomik,
   `systemctl restart pam-api`;
4. health check lokal `/readyz` (20×1 dtk); bila gagal, binary lama dipulihkan dan service
   di-restart otomatis (rollback), job gagal;
5. verifikasi publik `https://pam-70-153-86-163.sslip.io/healthz` + `/readyz` dari runner.

Rahasia repo yang dipakai: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PORT`, `DEPLOY_SSH_KEY`
(private key ed25519 khusus CI; public key-nya ditambahkan ke `~/.ssh/authorized_keys` user
`pam`). Uji nyata: run [45609200337](https://github.com/NDI05/0037-API-FinancialMobile/actions/runs/45609200337)
**success** 1m24s; sesudah deploy `/healthz` `uptimeSeconds:0`, `/readyz` 200, service `active`,
binary baru bertanggal 11:58 UTC. Deploy manual tetap bisa dilakukan bila perlu, tetapi jalur
utama sekarang push ke `main`.

### Aplikasi Android tanpa kabel USB

USB hanya dipakai untuk memasang APK debug dan melihat log. Build yang terpasang memakai
`PAM_BACKEND_BASE_URL=https://pam-70-153-86-163.sslip.io` (bukan `adb reverse` ke localhost),
jadi setelah kabel dicabut aplikasi tetap memuat Ringkasan/Transaksi dari host selama ada
internet. Bukti 25 Nov: seluruh `adb reverse` dihapus (`adb reverse --remove-all`), aplikasi
di-force-stop lalu dibuka ulang dan tetap memuat Ringkasan ("Dari email", arus kas, masuk/keluar)
dari host Azure. Sinkronisasi Gmail berjalan di server (polling 15 menit), jadi email baru tetap
diproses walau aplikasi tertutup dan HP tidak terhubung kabel.

### Cakupan data dari email Livin (audit 25 Nov 2026)

Audit kotak masuk (100 email Livin terakhir, 60 hari) menemukan hanya template transaksi
**keluar**: “Pembayaran Berhasil!” (QRIS), “Transfer dengan BI Fast Berhasil”, dan
“Tagihan Anda Sudah Dibayar”. **Tidak ada email “dana masuk/transfer masuk”** — Livin tidak
mengirim notifikasi email untuk uang masuk (notifikasinya hanya push/in-app), sehingga connector
Gmail memang tidak punya bahan untuk transaksi masuk. Ini batasan sumber data, bukan bug
kategorisasi AI.

Konsekuensinya: DB per 25 Nov berisi 77 transaksi November yang seluruhnya `DEBIT`; 42 transaksi
`CREDIT` yang ada berasal dari email Juli–Agustus 2026 (periode backfill lama), bukan November.
`import_skipped` 742 baris (662 `merchant_missing`, 67 `amount_missing`, 13 `outside_retention`)
sebagian besar adalah email non-transaksi (promo/statement/OTP) ditambah template
“Tagihan Anda Sudah Dibayar” yang tidak punya label `Penerima/Merchant`.

Jalur untuk uang masuk: (1) input **manual** di aplikasi (fitur sudah ada), (2) impor
mutasi/statement Livin (belum ada, perlu Fitur baru), atau (3) pembaca notifikasi Android
(butuh izin Notification Access). Opsi 2/3 menunggu keputusan pemilik.

### Operasional: polling, backup, rollback

- **Polling**: `GMAIL_MODE=polling`; delta tiap **900 dtk** dan cooldown sync manual **60 dtk**.
  Karena Gmail dibaca server, kondisi "aplikasi ditutup" tetap terpenuhi di sisi host.
- **TLS**: `caddy` memegang sertifikat Let's Encrypt dan memperbarui otomatis lewat systemd.
- **Restart/log**: `sudo systemctl restart pam-api`, `sudo journalctl -u pam-api -f`.
- **Backup DB**: Turso menyimpan backup otomatis; dump manual
  `turso db dump pam-azure-uat > pam-azure-uat-YYYYMMDD.sql` (simpan terenkripsi, jangan commit).
- **Restore**: buat DB baru dari dump (`turso db create … --from-dump …`) lalu arahkan
  `DATABASE_URL`/`DATABASE_AUTH_TOKEN` ke DB itu; service membaca env saat restart.
- **Rollback deploy**: lihat bagian [Rollback](#rollback) di bawah.

## Biaya dan pagar

- Estimasi: VM ±USD 7.81/bulan + disk 30 GB ±USD 1.5 + public IP statis ±USD 3.6 ≈ **USD 12–13/bulan**
  (dari credit USD 100; tidak menagih kartu selama credit tersedia).
- Budget alert baru `pam-host-monthly-15usd` (scope resource group, notifikasi 50/80/100% ke Owner).
- Budget Foundry lama `pam-foundry-monthly-10usd` tetap berlaku.
- Cara menghentikan biaya compute: `az vm deallocate -g rg-pam-api-id -n pam-api-vm`
  (disk & IP tetap ada; bisa `start` lagi). Hapus seluruh RG bila sudah tidak dipakai.

## Aksi pemilik yang masih diperlukan

1. ~~Daftarkan redirect URI Gmail~~ **SELESAI** (`https://pam-70-153-86-163.sslip.io/v1/gmail/connect/callback`, dikonfirmasi pemilik 25 Nov).
2. Uji **G36** dari **Run** Android Studio (local.properties menunjuk host Azure): login Google →
   Lewati → Tautkan Gmail + consent → force stop → email Livin alami → tunggu ±15 menit →
   laporkan jumlah/nominal transaksi.

## Rollback

- Nonaktifkan sementara: `az vm deallocate` (compute berhenti).
- Kembali ke UAT lokal: set `PAM_BACKEND_BASE_URL=http://127.0.0.1:18090` di `local.properties`,
  jalankan ulang runtime UAT lokal.
- Hapus total: `az group delete -n rg-pam-api-id --yes` dan `turso db destroy pam-azure-uat`.
