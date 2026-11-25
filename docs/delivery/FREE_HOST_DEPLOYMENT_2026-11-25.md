# Deploy backend ke host gratis (alternatif Vercel) — 2026-11-25

Status: **PERSIAPAN (keputusan platform menunggu pemilik)**. Vercel tetap dipakai untuk demo;
host gratis ini untuk UAT berkelanjutan yang butuh proses hidup 24/7 (polling Gmail + worker).

## 1. Mengapa Vercel tidak cocok untuk UAT penuh

- Vercel menjalankan fungsi per-request; proses latar (polling Gmail tiap 15 menit) tidak dijamin hidup.
- Kuota Daemon 5 CPU-jam/bulan cepat habis untuk API yang selalu dipanggil.
- Cron Hobby hanya 1×/hari, sedangkan delta Gmail butuh interval 15 menit.
- Vercel kita sekarang = demo (`LLM_PROVIDER=fake`, `GMAIL_ENABLED=false`) dan memang bukan produksi.

## 2. Pilihan host gratis

| Opsi | Kelebihan | Batas / risiko | Verdict |
|---|---|---|---|
| **Oracle Cloud Always Free** (Ampere A1) | 4 OCPU/24 GB RAM, 200 GB disk, 10 TB egress/bulan, IP publik stabil, proses 24/7 tanpa tidur. | Perlu daftar akun + verifikasi kartu; kapasitas ARM di region rumah bisa penuh; Oracle dapat merebut instance *idle* (pemakaian CPU/network sangat rendah) menurut syarat Always Free. | **Rekomendasi utama** untuk UAT 24/7. |
| Render Free | Tanpa kartu, deploy lewat GitHub, HTTPS otomatis, 512 MB/0,1 CPU, 750 jam/bulan. | Tidur setelah ±15 menit idle; jam 750/bulan (24/7 = 720 jam) sehingga butuh ping keep-alive tiap 10 menit; 512 MB cukup untuk backend ini tetapi tidak lapang. | **Alternatif cepat/demo**; layak dicoba dulu karena hampir tanpa gesekan. |
| GCP e2-micro Always Free | Akun GCP sudah ada, proses 24/7, tanpa tidur. | Hanya region AS (`us-west1/central1/east1`), egress gratis 1 GB/bulan (risiko tagihan kecil bila lewat), butuh billing tertaut; gate G29/G37 jadi lebih rumit. | Cadangan bila Oracle tidak bisa dibuat. |
| Fly.io / Railway / Koyeb | DX bagus. | Tidak ada tier gratis berkelanjutan yang bisa diandalkan (Fly/Railway berbasis kredit/PAYG; Koyeb bisa tidur). | Tidak dipilih. |

Catatan: harga/kuota platform dapat berubah — konfirmasi di dashboard saat mendaftar.

## 3. Artefak yang sudah disiapkan (repo backend)

- `Dockerfile` multi-stage (build statis, user non-root, `/data` writable) — dipakai Render atau Docker VM.
- `.dockerignore`.
- `render.yaml` Blueprint: Docker, plan free, region Singapore, health check `/healthz`,
  `GMAIL_ENABLED=false` dan `LLM_PROVIDER=fake` secara default (Azure/Gmail dinyalakan sadar setelah verifikasi).
- `deploy/free-host/bootstrap.sh` + `pam-api.service` + `caddy.service` + `Caddyfile` untuk VM Ubuntu
  (Oracle/GCP): systemd, hardening dasar, Caddy TLS otomatis via domain `*.sslip.io`, firewall 22/80/443.

Status verifikasi: commit backend **`0b09664`** (CI `45574878205` **success**); image Docker
dibangun (`pam-api:freehost-test`) dan smoke container membuktikan `/healthz` + `/readyz` **200**
(DB SQLite sementara, provider fake). Deploy nyata tetap **NOT RUN** sampai akun/VM pemilik tersedia.

## 4. Langkah pemilik — Oracle Cloud (rekomendasi)

1. Daftar **oracle.com/cloud/free** (butuh verifikasi kartu; jangan pakai kartu kredit limit besar).
   Pilih **home region** terdekat (Singapore bila tersedia).
2. Buat VM: **Compute → Instances → Create instance**
   - Image: **Ubuntu 24.04**
   - Shape: **VM.Standard.A1.Flex** (Ampere ARM), 2 OCPU/12 GB sudah lebih dari cukup
     (maksimal Always Free 4 OCPU/24 GB; bila kapasitas penuh, coba 2 OCPU atau shape AMD
     `VM.Standard.E2.1.Micro`).
   - **Add SSH key**: paste isi public key di bawah (jangan kirim private key ke siapa pun).
   - Assign **public IPv4**.
3. Buka **Networking → Security List / NSG**: izinkan TCP **22, 80, 443** dari internet.
4. Kirim ke agent: **IP publik + username default (`ubuntu`)**. Agent lalu:
   - membuat database Turso terpisah untuk host ini,
   - mengisi env produksi di `/etc/pam/pam-api.env` (secret tidak dicetak),
   - build binary Linux ARM64, scp, jalankan `bootstrap.sh`,
   - verifikasi `https://<ip>.sslip.io/healthz` dan `/readyz`,
   - daftarkan redirect Gmail `https://<ip>.sslip.io/v1/gmail/connect/callback` (pemilik menambah di Google Console),
   - build APK debug dengan `PAM_BACKEND_BASE_URL` menunjuk host ini, lalu serahkan untuk Run.

Public key agent untuk VM (aman dibagikan):

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF40yKrde6A8MDr+VZkHfDJ+n5CvrUOD8wDlJRGcjlJp pam-free-host-deploy
```

## 5. Langkah pemilik — Render Free (alternatif cepat)

1. Render dashboard → **New → Blueprint** → hubungkan repo backend `0037-Backend-Financial`.
2. Render membaca `render.yaml`; isi env yang `sync: false` (secret) dari profil privat pemilik
   (`~/.config/pam/*.env`), tanpa mengirim isinya ke chat.
3. Setelah deploy, salin URL `https://pam-api-xxxx.onrender.com`, lalu:
   - tambah ke Google Console sebagai redirect URI: `.../v1/gmail/connect/callback`,
   - set env `GMAIL_REDIRECT_URI` di Render, redeploy.
4. Render Free tidur bila idle. Untuk uji singkat tidak masalah; untuk 24/7 perlu ping
   `GET /healthz` tiap 10 menit dari cron eksternal gratis. Ini **bukan** pengganti polling yang
   andal jangka panjang.

## 6. Keamanan, biaya, rollback

- **Azure tidak dinyalakan** di host publik pada awalnya (`LLM_PROVIDER=fake`,
  `CATEGORY_LLM_ENABLED=false`) karena registrasi terbuka + tanpa rate-limit dapat menyedot kredit.
  Setelah perlu, nyalakan dengan plafon harian kecil + budget alert Azure.
- **Gmail** dinyalakan sadar (`GMAIL_ENABLED=true`) setelah redirect URI terdaftar; OAuth token
  dienkripsi dengan `DATA_ENCRYPTION_KEY` yang hanya ada di env host.
- Database host terpisah dari fixture; backup sebelum migrasi; rollback binary VM = simpan binary
  lama + `systemctl restart`, Render = menu **Rollback** ke deploy sebelumnya.
- Tidak ada secret di repo/dokumen ini; nilai diisi lewat env host atau dashboard.

## 7. Gate yang terpengaruh

- **G24**: demo Vercel tetap PARTIAL; host gratis ini menjadi kandidat **produksi/UAT 24/7**
  (butuh commit, HTTPS, live polling, backup/rollback terverifikasi sebelum PASS).
- **G29/G37**: bila memilih GCP, gate Rp0 makin rumit; Oracle/Render tidak memakai GCP.
- **G36**: uji onboarding Gmail + email Livin alami tetap milik pemilik, bisa dilakukan di host baru.
