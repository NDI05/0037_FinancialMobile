# Setup REAL-UAT sebelum menyerahkan prompt kepada SE

Disusun 22 November 2026. Turso CLI, adb/emulator dan Ollama sudah tersedia pada laptop.
Model lokal `qwen3-vl:8b-instruct` juga terpasang. Konfigurasi privat baru sudah disiapkan di
`/Users/user/.config/pam/real-uat.env` (folder 700, file 600), dengan kunci random yang persisten.
Nilai secret tidak dicetak atau masuk Git. DATABASE_URL/TOKEN masih kosong; SE akan mengisinya
dari dua file privat pada langkah 3. File ini bukan konfigurasi database lama.

## 1. Masuk ke Turso

Di Terminal:

```bash
turso auth login
```

Selesaikan login browser dengan akun Turso Anda. Tidak perlu mengirim login/token ke chat.

## 2. Buat database REAL-UAT baru

```bash
turso db create pam-real-uat --wait
```

Gunakan database baru untuk data UAT yang ingin dipertahankan. Jangan membuat salinan database
lama atau menghapus database yang sudah ada. Jika nama sudah digunakan, pilih nama baru seperti
`pam-real-uat-2`, lalu gunakan nama itu pada kedua perintah di bawah. Pertahankan plan gratis;
jika CLI meminta upgrade/biaya, berhenti pada provisioning itu dan catat pesan yang tampil.

Target backend saat ini libSQL. Pada CLI terpasang, flag `--tursodb` adalah pilihan engine lain;
perintah di atas tidak memakainya. SE tetap wajib memverifikasi kompatibilitas driver/engine
dan URL `libsql://` sebelum migrasi. URL saja bukan bukti semua fitur engine kompatibel.

## 3. Simpan URL dan token secara privat

```bash
umask 077
turso db show pam-real-uat --url > /Users/user/.config/pam/turso-real-uat.url
turso db tokens create pam-real-uat --expiration 30d > /Users/user/.config/pam/turso-real-uat.token
chmod 600 /Users/user/.config/pam/turso-real-uat.url /Users/user/.config/pam/turso-real-uat.token
```

Gunakan nama DB yang benar bila langkah 2 memakai alternatif. Token ini hanya untuk database
tersebut, bukan group/global. Jangan memakai read-only token karena migrasi dan CRUD memerlukan
write. Perintah mengarahkan secret ke file sehingga token tidak ditampilkan ke chat/terminal.

Cek keberadaan file tanpa membaca nilainya:

```bash
test -s /Users/user/.config/pam/turso-real-uat.url && echo "URL tersedia"
test -s /Users/user/.config/pam/turso-real-uat.token && echo "Token tersedia"
```

Dua pesan itu hanya membuktikan file tidak kosong; SE masih memvalidasi format, auth dan koneksi.
Jangan menjalankan ulang create token tanpa kebutuhan; token berumur 30 hari dapat kedaluwarsa.
Simpan file konfigurasi dan kunci enkripsi dengan aman, jangan generate ulang kunci setiap run.

## 4. Siapkan Gmail untuk satu kali penautan di aplikasi

Gmail yang berisi notifikasi Livin harus mempunyai 2-Step Verification aktif. Jika App Password
yang dipakai sebelumnya masih valid, gunakan yang sama; tidak perlu membuat ulang hanya karena
ada update APK. Jika belum punya, buat lewat https://myaccount.google.com/apppasswords dengan
nama PAM REAL-UAT, simpan di password manager Anda, dan masukkan langsung ke aplikasi nanti.

Jangan taruh App Password/password Gmail/password PAM di file env atau chat. Koneksi Gmail GPT/
MCP tidak otomatis memberi aplikasi PAM credential IMAP. Saat REAL-UAT app siap, pemilik perlu
registrasi/login PAM dan memasukkan App Password satu kali bila link belum tersedia di app itu.
Karena alur APK sedang diperbaiki, langkah UI ini memang baru dapat dilakukan setelah build siap.
Satu handoff autentikasi tidak menjadi alasan SE menghentikan implementasi/test independen.

## 5. Biarkan Ollama dan emulator siap

```bash
ollama list
adb devices
```

Pastikan model `qwen3-vl:8b-instruct` muncul dan emulator berstatus `device`. Buka Ollama bila
servernya belum berjalan. Model sudah ada; tidak perlu pull/download baru. Konfigurasi REAL-UAT
memakai adapter OpenAI-compatible pada `http://127.0.0.1:11434/v1`, provider config `openai`,
bukan provider `fake`. Kata `openai` di sini nama adapter; proses inference tetap Ollama lokal.
Kesesuaian output JSON/FR-37, latency dan integrasi analisis belum dinyatakan PASS; SE mengujinya.
Model nyata belum tentu menghasilkan output sah pada setiap request; error harus ditampilkan jujur.

## 6. Serahkan satu prompt setelah langkah 1–5 siap

Gunakan `docs/agent-prompts/PROMPT_SE_REAL_UAT_S2_S4.md`. Prompt ini menggabungkan arahan terakhir
yang belum dikirim; tidak perlu mengirim satu per satu prompt resume sebelumnya.

SE menyiapkan launcher env untuk Go (Go membaca environment proses, tidak otomatis membaca
backend/.env), port khusus 18090 dan Android REAL-UAT yang jelas terpisah dari QA. SE juga
menjalankan migrasi/health/readiness serta tests. Pemilik tidak perlu merakit command backend
atau membagikan secret ke chat. Database yang diprovision tetap kosong sampai SE menyiapkannya.

Kesiapan yang perlu disampaikan: database berhasil dibuat; file URL/token tersedia; Ollama/
emulator berjalan; App Password tersedia di password manager. Ini belum bukti koneksi cloud
atau bug CR-003 selesai. Setelah build siap ada satu input autentikasi langsung oleh pemilik;
selanjutnya SE melanjutkan implementasi/verifikasi tanpa meminta ulang izin untuk aksi yang
sudah masuk mandat. Pengujian destructive tetap pada QA sintetis terpisah, bukan REAL-UAT.

Referensi: [Turso create](https://docs.turso.tech/cli/db/create),
[Turso database token](https://docs.turso.tech/cli/db/tokens/create),
[Google App Password](https://support.google.com/accounts/answer/185833),
[Ollama OpenAI-compatible](https://docs.ollama.com/api/openai-compatibility).
