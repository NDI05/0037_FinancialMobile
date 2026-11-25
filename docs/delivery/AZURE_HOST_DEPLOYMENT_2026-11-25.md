# Deploy Host Sementara Azure (Indonesia Central) — 25 November 2026

Status: **DEPLOYED (sementara, memakai credit Azure for Students) · Gmail NOT RUN · G36 belum ditutup**

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
- `GMAIL_ENABLED=false` sampai redirect URI didaftarkan pemilik → tidak ada polling yang bisa
  memicu panggilan AI berulang saat belum siap.

## Bukti verifikasi (25 Nov 2026, WIB)

| Cek | Hasil |
|---|---|
| `GET /healthz` | **200** `{"status":"ok"}` |
| `GET /readyz` | **200** `{"status":"ready"}` |
| `POST /v1/auth/google` body kosong | **400** (rute ada, validasi jalan) |
| TLS | issuer Let's Encrypt, CN `pam-70-153-86-163.sslip.io`, valid 25 Nov 2026 – 23 Feb 2027 |
| Turso `pam-azure-uat` | tabel `transactions`, `analyses`, `gmail_connections`, `auth_sessions`, dll. terbentuk |
| APK debug diarahkan ke host | `PAM_BACKEND_BASE_URL=https://pam-70-153-86-163.sslip.io`, build `:app:assembleDebug` sukses, terpasang di HP fisik |

## Biaya dan pagar

- Estimasi: VM ±USD 7.81/bulan + disk 30 GB ±USD 1.5 + public IP statis ±USD 3.6 ≈ **USD 12–13/bulan**
  (dari credit USD 100; tidak menagih kartu selama credit tersedia).
- Budget alert baru `pam-host-monthly-15usd` (scope resource group, notifikasi 50/80/100% ke Owner).
- Budget Foundry lama `pam-foundry-monthly-10usd` tetap berlaku.
- Cara menghentikan biaya compute: `az vm deallocate -g rg-pam-api-id -n pam-api-vm`
  (disk & IP tetap ada; bisa `start` lagi). Hapus seluruh RG bila sudah tidak dipakai.

## Aksi pemilik yang masih diperlukan

1. **Daftarkan redirect URI Gmail** di Google Cloud Console → OAuth client (tipe Web) yang dipakai
   `GMAIL_CLIENT_ID`:
   `https://pam-70-153-86-163.sslip.io/v1/gmail/connect/callback`
2. Balas "redirect sudah didaftarkan" → agent menyalakan `GMAIL_ENABLED=true`, restart service,
   lalu pemilik menjalankan **Run** dari Android Studio (local.properties sudah menunjuk host Azure)
   dan menguji **G36**: login Google → Lewati → Tautkan Gmail + consent → force stop → email Livin
   alami → tunggu ±15 menit → laporkan jumlah/nominal transaksi.

## Rollback

- Nonaktifkan sementara: `az vm deallocate` (compute berhenti).
- Kembali ke UAT lokal: set `PAM_BACKEND_BASE_URL=http://127.0.0.1:18090` di `local.properties`,
  jalankan ulang runtime UAT lokal.
- Hapus total: `az group delete -n rg-pam-api-id --yes` dan `turso db destroy pam-azure-uat`.
