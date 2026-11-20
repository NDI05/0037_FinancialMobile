# Stack 100% Gratis

Tujuan: seluruh proyek berjalan tanpa biaya sepeser pun dan tanpa kartu kredit.

**Ringkasan: bisa, dan sebagian besar sudah begitu.** Yang perlu diubah hanya cara Anda
menjalankan LLM dan (opsional) di mana backend dijalankan. Kode aplikasi tidak berubah.

---

## 1. Koreksi dulu: Gmail API tidak butuh billing

Premis "harus bayar Rp500.000 untuk dapat $300" itu mencampur dua hal berbeda:

| Hal | Butuh kartu/bayar? |
|---|---|
| Membuat **project** Google Cloud | ❌ tidak |
| Mengaktifkan **Gmail API** | ❌ tidak — ada kuota gratis 1 miliar unit/hari |
| Membuat **OAuth client ID** (Android & Web) | ❌ tidak |
| Mengisi **OAuth consent screen** + test user | ❌ tidak |
| Program **Google Cloud Free Trial $300** | ✅ **ya** — ini yang minta verifikasi pembayaran |

Google Cloud **Free Trial** (kredit $300/90 hari) memang meminta metode pembayaran untuk
verifikasi identitas, dan itulah yang Anda temui. Tapi **Anda tidak perlu ikut program itu.**
Yang dibutuhkan PAM hanya project + Gmail API + OAuth client, dan ketiganya ada di jalur gratis.

Referensi: [panduan setup GCP tanpa billing untuk Gmail API](https://huggingface.co/spaces/MCP-1st-Birthday/HR-Assistant/blob/main/docs/mcp/gmail_mcp_gcp_setup.md),
[panduan komunitas yang menyebut billing dibutuhkan untuk free tier](https://github.com/odyssey4me/agent-skills/blob/main/docs/gcp-project-setup.md).

> Kedua sumber saling bertentangan soal ini — pengalaman lapangan berbeda-beda per negara dan
> per akun, dan Google memang mengubah kebijakannya dari waktu ke waktu. **Kalau konsol Anda
> menolak membuat OAuth client tanpa billing**, jangan paksakan: lewati Gmail (lihat §5) dan
> aplikasi tetap 100% gratis dan tetap memenuhi SRS lewat mesin analisis di perangkat.

---

## 2. Tabel biaya seluruh komponen

| Komponen | Pilihan gratis | Biaya |
|---|---|---|
| IDE & build | Android Studio, Gradle, JDK 21, Kotlin, Compose | Rp0 |
| Database aplikasi | Room + SQLCipher (lokal di HP) | Rp0 |
| Navigasi, DI, HTTP | Navigation Compose, Hilt, OkHttp | Rp0 |
| Screenshot test | Roborazzi + Robolectric | Rp0 |
| UI/UX | Plus Jakarta Sans (OFL), Material 3 | Rp0 |
| Backend runtime | Node.js 24 | Rp0 |
| Database backend | SQLite (`node:sqlite` bawaan) | Rp0 |
| Hosting backend | **laptop Anda sendiri** (§4) | Rp0 |
| **LLM (AI analysis)** | **Ollama lokal** / Groq / OpenRouter `:free` (§3) | Rp0 |
| Distribusi APK | sideload / share file | Rp0 |
| Google OAuth + Gmail API | jalur gratis (§1) | Rp0 |

Yang **tidak** Anda perlukan (dan jangan dibeli):

- ❌ Google Cloud Free Trial $300 — tidak dipakai
- ❌ Google Play Console **$25** sekali bayar — tidak perlu, cukup sideload APK
- ❌ API key OpenAI berbayar — digantikan Ollama/Groq
- ❌ Hosting berbayar (Railway/Render paid, VPS) — backend jalan di laptop
- ❌ Emulator cloud / device farm — emulator lokal gratis

---

## 3. LLM gratis: tiga pilihan

Semua di bawah ini **kompatibel dengan adapter OpenAI** yang sudah ada, jadi hanya perlu ubah
`.env`. Tidak ada perubahan kode.

### Opsi A — Ollama lokal (paling murah dan paling aman)

Gratis penuh, offline, tanpa akun, tanpa rate limit, data tidak keluar dari laptop. Ini yang
sudah saya uji dan terbukti bekerja.

```bash
# sekali saja
brew install ollama          # kalau belum ada
ollama pull qwen3-vl:8b-instruct   # ~6 GB

# jalankan backend menunjuk Ollama
cd pam-app/backend
LLM_PROVIDER=openai \
LLM_BASE_URL=http://127.0.0.1:11434/v1 \
LLM_MODEL=qwen3-vl:8b-instruct \
LLM_API_KEY= \
LLM_TIMEOUT_MS=120000 \
npm start
```

| Kelebihan | Kekurangan |
|---|---|
| Rp0 selamanya, tanpa limit | butuh RAM ~8 GB, disk ~6 GB |
| Data tidak pernah keluar | ~30 detik per analisis di mesin ini |
| Jalan tanpa internet | model 8B kadang kurang rapi mengikuti format JSON |

### Opsi B — Groq (gratis, tanpa kartu kredit)

Cepat, model besar, cukup daftar dengan email. Cocok kalau laptop Anda tidak kuat menjalankan model.

```bash
LLM_PROVIDER=openai
LLM_BASE_URL=https://api.groq.com/openai/v1
LLM_MODEL=llama-3.3-70b-versatile
LLM_API_KEY=<key dari console.groq.com>
```
Daftar: <https://console.groq.com> — [tanpa kartu kredit](https://github.com/vava-nessa/free-coding-models/blob/7b5ca613e373f5910657853a0370a7772f797eb3/website/src/content/docs/providers/groq.mdx).
Ada rate limit per menit/hari, cukup untuk demo tugas.

### Opsi C — OpenRouter model `:free`

Puluhan model gratis (`:free`), tanpa kartu kredit untuk model gratis.

```bash
LLM_PROVIDER=openai
LLM_BASE_URL=https://openrouter.ai/api/v1
LLM_MODEL=<nama-model>:free
LLM_API_KEY=<key dari openrouter.ai>
```
Daftar: <https://openrouter.ai/models?max_price=0> — [tanpa kartu kredit](https://benchlm.ai/free-tier/openrouter).

### Yang saya sarankan

**Opsi A untuk demo dan penilaian** (tidak bergantung internet, tidak mungkin kehabisan kuota di
tengah presentasi), dan **Opsi B** kalau Anda butuh kualitas keluaran lebih baik saat sidang dan
Wi-Fi kampus memadai. Simpan keduanya di `.env` sebagai komentar, tinggal tukar.

> Catatan penting: **`LLM_PROVIDER=fake` tetap default.** Tanpa `.env`, `npm start` langsung
> jalan dengan LLM deterministik. Jadi seluruh sistem bisa didemokan tanpa memilih LLM sama sekali.

---

## 4. Hosting backend: jalankan di laptop (gratis)

Backend adalah satu proses Node + satu file SQLite. Tidak perlu cloud.

```bash
cd pam-app/backend
npm install
npm start                 # http://127.0.0.1:8080
```

Hubungkan dari HP/emulator:

| Dari mana | Base URL |
|---|---|
| Emulator Android di laptop yang sama | `http://10.0.2.2:8080` |
| HP fisik, satu Wi-Fi dengan laptop | `http://<IP-LAN-laptop>:8080` |
| Backend publik sementara (opsional) | `cloudflared tunnel --url http://localhost:8080` |

Cari IP LAN: `ipconfig getifaddr en0` (macOS).

Build aplikasi menunjuk backend:

```bash
cd pam-app
./scripts/build.sh :app:assembleDebug -PPAM_BACKEND_BASE_URL=http://10.0.2.2:8080
```

Kalau Anda ingin backend tetap online saat laptop mati, alternatif gratis: **Turso** (SQLite 9 GB
gratis) atau **Cloudflare D1**, tapi itu menambah pekerjaan migrasi dan tidak diperlukan untuk
tugas ini. Backend lokal sudah memenuhi semua kewajiban SRS.

---

## 5. Kalau Gmail diblokir billing: tetap 100% gratis

Aplikasi dirancang supaya bagian Google bisa dimatikan tanpa kehilangan fungsionalitas inti:

| Dengan Google | Tanpa Google |
|---|---|
| Import email QRIS sungguhan | Demo mailbox (14 notifikasi contoh) |
| Analisis lewat backend + LLM | Analisis **di perangkat**, deterministik |
| Semua tetap jalan | Semua tetap jalan |

Tanpa `-PPAM_GOOGLE_WEB_CLIENT_ID` dan `-PPAM_BACKEND_BASE_URL`, aplikasi berjalan penuh secara
offline: demo mode, `OnDeviceAnalysisEngine`, penyimpanan terenkripsi lokal. **Nol rupiah, nol
akun, nol internet.**

Yang hilang hanya dua hal: import email asli, dan LLM sungguhan (diganti mesin deterministik yang
tetap menghasilkan observasi + sitasi metrik). Untuk demo akademik keduanya bisa dijelaskan
sebagai keterbatasan lingkungan, bukan kekurangan perangkat lunak.

---

## 6. Yang tetap gratis tapi perlu diperhatikan

| Batas | Dampak | Mitigasi |
|---|---|---|
| Model lokal 8B kadang gagal menghasilkan JSON valid | backend menjawab `422` setelah 1 regenerasi | pakai Groq, atau model lebih besar |
| Groq/OpenRouter punya rate limit gratis | analisis ditolak `429` | kuota di `.env` sudah membatasi; tunggu atau pakai Ollama |
| OAuth consent screen masih *Testing* | maks 100 test user | cukup untuk tugas; tidak perlu verifikasi |
| Restricted scope `gmail.readonly` | verifikasi hanya wajib untuk rilis publik | tetap di *Testing* |
| Laptop tidur = backend mati | aplikasi jatuh ke mesin di perangkat | itu perilaku yang benar (NFR-26) |

---

## 7. Kalau tetap harus bayar Rp500.000

Jangan bayar. Tanyakan dulu ke penyedia biaya kampus atau lab apakah punya **akun Google Cloud
Workspace institution**. Tapi untuk tugas ini jalur gratisnya lengkap: Ollama di laptop + Gmail
jalur gratis, atau tanpa Google sama sekali.
