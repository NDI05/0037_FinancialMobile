# UAT lampiran S4.1 pada emulator — 23 November 2026

Bukti runtime untuk C16/C17/C19 (create, read setelah restart, delete) pada backend UAT
lokal dan emulator `emulator-5554`. Bukan bukti perangkat nyata, bukan bukti sinkronisasi
byte antar perangkat, dan bukan acceptance penuh S4 (C18/C20 dan admin C21–C25 dibahas di
[dokumen matriks](../delivery/ACCEPTANCE_C01_C25.md)).

## Lingkungan

| Komponen | Nilai |
|---|---|
| APK | `com.pam.recap.debug`, dibangun `./scripts/build.sh --no-watch-fs :app:assembleDebug -PPAM_BACKEND_BASE_URL=http://127.0.0.1:18090` |
| Backend | binary `/tmp/pam-uat-api-fixed` dari `backend-go` HEAD `7562609`, env `/Users/user/.config/pam/real-uat.env`, port 18090 |
| Database | Turso/libSQL UAT (bukan data perangkat lain); migrasi 015 diterapkan |
| Perangkat | AVD `emulator-5554` (sdk_gphone64_arm64), akun UAT tersimpan `andhikap877@gmail.com` |
| Berkas uji | `uat-kwitansi.pdf` 193 B `application/pdf` di `/sdcard/Download` (dibuat sendiri untuk uji) |

Perintah kunci: `adb -s emulator-5554 reverse tcp:18090 tcp:18090`; backend dijalankan dengan
`PORT=18090 /tmp/pam-uat-api-fixed`; log request di `/tmp/pam-uat-18090-fixed.log`.

## Skenario dan hasil

### 1. Create (C16) — PASS

1. Buka `Transaksi` → detail `KOPI UJI QRIS` → `Tambah lampiran`.
2. DocumentsUI → `Show roots` → `Downloads` → pilih `uat-kwitansi.pdf`.
3. Panel menampilkan `Lampiran tersimpan.` dan baris `uat-kwitansi.pdf`,
   `application/pdf · 193 B`, aksi hapus, serta hint
   `Berkas ini tetap di perangkat; hanya metadata yang disinkronkan.`
4. Log backend: `POST /v1/attachments` status `201`.

Bukti silang: baris yang sama muncul setelah `am force-stop` + buka ulang aplikasi, sehingga
metadata dibaca dari server, bukan hanya state memori (`GET /v1/attachments`).

### 2. Bug kontrak yang ditemukan dan diperbaiki

Percobaan pertama: POST 201 tetapi panel menampilkan `Lampiran belum bisa diproses.`.
Log allow-list baru (`adb logcat -s AttachmentVM`) menunjukkan `attachment_create_failed
code=PARSING`, artinya deserialisasi respons gagal walau server sudah commit.

Penyebab: server mengirim `"attributes": null` untuk metadata kosong, sedangkan DTO Android
mensyaratkan `Map<String,String>` non-null. Perbaikan pada `7562609`:

- `backend-go/internal/httpapi/handlers_attachments.go` selalu mengirim `{}` untuk map kosong;
- `RemoteAttachment.attributes` menjadi nullable dan dipetakan `.orEmpty()`, sehingga klien
  baru tetap kompatibel dengan server lama;
- `AttachmentViewModel` mencatat kode error allow-list (`attachment_import_failed`,
  `attachment_create_failed`, `attachment_delete_failed`, `attachment_load_failed`) tanpa
  body/PII, agar kegagalan lapangan dapat didiagnosis.

Tanpa perbaikan ini C16 gagal pada server nyata meskipun semua unit test hijau — bukti bahwa
fake-server test tidak cukup.

### 3. Read setelah restart (C17) — PASS untuk perangkat yang sama

Setelah force-stop dan buka ulang, panel tetap menampilkan dua baris lampiran yang dibuat
sebelumnya (dua baris sah: satu dari percobaan pertama yang sudah ter-commit di server sebelum
klien gagal parsing, satu dari percobaan kedua). Artinya metadata persisten dan owner-scoped.
Berkas byte tetap lokal; untuk perangkat lain panel hanya menampilkan metadata tanpa tombol
buka palsu — kondisi "file unavailable" ini belum diuji pada perangkat kedua.

### 4. Delete (C19) — PASS

1. Tap ikon hapus pada baris pertama → dialog `Hapus lampiran?` dengan penjelasan
   `Metadata lampiran dihapus dari server dan salinan lokal ikut dihapus.`
2. Konfirmasi → `DELETE /v1/attachments/{id}` status `204`.
3. Baris pertama hilang dari daftar, baris kedua tetap ada; tidak ada resurrect setelah
   reload karena daftar dibaca ulang dari server.

## Batas bukti

- UAT memakai AVD dan akun/database UAT, bukan akun/perangkat pemilik.
- Belum diuji pada perangkat fisik, layar 200%, TalkBack, atau dua perangkat berbeda.
- C18 (update nama/atribut) dan C20 (search lampiran) diverifikasi oleh unit/integration test
  pada irisan UI terkait, bukan skenario UAT ini.
- Byte file tidak diunggah ke cloud; klaim S4.1 tetap "metadata sinkron, byte privat lokal".
