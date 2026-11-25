# Memo keputusan OI-27 dan OI-28 — 25 November 2026 (WIB)

Status: **OI-28 DIPUTUSKAN PEMILIK 25 November 2026 (WIB); OI-27 tetap milik pemilik.**

## Addendum — keputusan pemilik untuk OI-28 (25 November 2026)

Pemilik memutuskan: (a) **hapus registrasi dengan kode undangan** — pendaftaran email+password
terbuka; (b) **Google SSO menjadi jalur login dan daftar langsung** tanpa perlu menautkan lebih
dulu di Pengaturan. Implementasi dan bukti: [CR-009](../changes/CR_009_OPEN_GOOGLE_AUTH.md) dan
[evidence](../evidence/AUTH_OPEN_SSO_2026-11-25.md). Keputusan ini menggantikan fakta dan opsi di
bawah; teks asli dipertahankan sebagai jejak keputusan. Dampak: OI-28 tinggal bagian ADMIN
(`pam-admin -promote <email>`) yang tidak berubah.

## OI-28 — pendaftaran akun dan provisioning ADMIN

Fakta yang terverifikasi dari kode (bukan asumsi):

- `POST /v1/accounts` tetap memerlukan `inviteCode` yang cocok dengan `REGISTRATION_INVITE_CODE`;
  tanpa kode yang benar respons `403 invalid_invite`. Tidak ada pendaftaran publik.
- Route undangan admin sudah pensiun (semua method `404`); tidak ada endpoint untuk membuat
  undangan baru.
- Satu-satunya jalur ADMIN yang benar-benar ada: `pam-admin -promote <email>` — idempoten,
  transaksional, dan menulis `admin_audit`. Dokumen menyebut `pam-admin bootstrap`, perintah itu
  tidak ada (temuan audit P2, murni dokumentasi/runbook).
- UI Android masih menampilkan “Buat akun” untuk alur undangan lama, sementara pembuatan undangan
  admin sudah pensiun; sampai keputusan diambil, tombol itu tidak menghasilkan akun tanpa kode
  undangan yang valid.

Opsi:

| Opsi | Isi | Dampak |
|---|---|---|
| **A. Pertahankan CLI + rapikan (disarankan untuk rilis)** | Perbaiki runbook menjadi `pam-admin -promote <email>`; dokumentasikan bahwa undangan admin pensiun; sembunyikan/beri keterangan “Buat akun” pada build produksi | Tidak menambah permukaan serangan; perubahan kecil; konsisten CR-008 |
| B. Sediakan undangan resmi lagi | Endpoint admin baru + UI undangan + tes + pembaruan kontrak | Perubahan lebih besar, butuh CR dan siklus uji baru |
| C. Aktifkan registrasi publik | Buka pendaftaran tanpa undangan | Dilarang CR-008; tidak disarankan |

## OI-27 — pemetaan akademik D.2/D.5/D.6/D.9

Fakta:

- Pedoman asli (PDF) belum ditemukan saat CR-008 dicatat; CR-008 menyatakan D.5, D.6, dan D.9 perlu
  dipetakan ulang dan tidak boleh diklaim tanpa pedoman + keputusan pemilik.
- Matriks lama memakai anggaran/lampiran sebagai dua dari empat entitas (D.5) dan tiga dari lima
  CRUDS (D.6); keduanya sudah pensiun di produk ini. D.9 (simpan/baca file lokal) juga menunggu
  keputusan bentuk aktivitas file lokal.
- SRS v0.7 tidak mengklaim pemenuhan butir-butir tersebut.

Opsi:

| Opsi | Isi | Dampak |
|---|---|---|
| **A. Tunda klaim + dokumentasikan gap (disarankan)** | Sebut D.2 terpenuhi lewat isolasi akun/autentikasi (bukti tes ada), dan nyatakan D.5/D.6/D.9 belum dipetakan sampai pedoman asli tersedia | Tidak mengarang fitur; klaim akademik jujur; menunggu pemilik |
| B. Owner menyediakan pedoman asli | Petakan ulang D.5/D.6/D.9 ke cakupan PAM, review bersama | Membutuhkan dokumen sumber dari pemilik |
| C. Klaim sebagian hanya D.2 | Gunakan bukti isolasi antar-akun yang sudah diuji | Aman bila hanya D.2 yang diklaim |

## Keputusan yang diminta dari pemilik

- [ ] OI-28: pilih A, B, atau C.
- [ ] OI-27: pilih A, B, atau C; bila B, lampirkan pedoman asli.
- [ ] Bila memilih OI-28 A: setujui perubahan runbook + label UI “Buat akun” pada build produksi.

Sampai keputusan diterima, gate OI-27/OI-28 tetap **OPEN**, provisioning ADMIN hanya lewat
`pam-admin -promote`, dan tidak ada klaim akademik baru.
