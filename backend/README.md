# PAM Backend

Backend untuk aplikasi PAM: menerima **agregat bulanan saja** (tanpa email, tanpa merchant, tanpa
catatan pribadi), menghasilkan analisis lewat adaptor LLM, menyimpan rencana anggaran, dan mencatat
persetujuan serta penghapusan data.

Kontrak yang mengikat: [`../docs/06_BACKEND_CONTRACT.md`](../docs/06_BACKEND_CONTRACT.md).
Backend ini **bukan** modul Gradle — `settings.gradle.kts` hanya berisi `:app`.

## Prasyarat

- Node.js **24** (memakai `node:sqlite` bawaan, jadi tidak ada dependensi native)
- npm 11

## Cara jalan

```bash
cd pam-app/backend
npm install
npm start                 # http://127.0.0.1:8080
curl localhost:8080/healthz
```

Tanpa `.env` pun jalan: SQLite lokal di `./data/pam.db`, provider LLM **palsu** (deterministik),
dan token sesi dev `dev:<sub>:<nama>` supaya seluruh alur bisa dicoba tanpa Google.

Uji seluruh happy path dari terminal lain:

```bash
bash scripts/smoke.sh
```

Skrip itu membuat sesi, mencatat consent AI, menyimpan agregat, meminta analisis dua kali (yang
kedua harus kena cache), menyimpan rencana anggaran, lalu membuat permintaan penghapusan.

## Cara isi `.env`

```bash
cp .env.example .env
```

| Variabel | Kapan wajib | Catatan |
|---|---|---|
| `GOOGLE_WEB_CLIENT_ID` | produksi | client ID Web dari `docs/05_REAL_GOOGLE_SETUP.md`; juga menjadi `aud` yang diverifikasi |
| `SESSION_PEPPER` | produksi | `openssl rand -hex 32` |
| `DATA_ENCRYPTION_KEY` | produksi | `openssl rand -base64 32` (32 byte AES-256-GCM), simpan terpisah dari file DB |
| `INTERNAL_SWEEP_TOKEN` | produksi | `openssl rand -hex 24`; tanpa ini `POST /v1/internal/sweep` ditutup (404) |
| `LLM_API_KEY` | `LLM_PROVIDER=openai` | key OpenAI atau OpenRouter |
| `LLM_BASE_URL` | `LLM_PROVIDER=openai` | `https://api.openai.com/v1` atau `https://openrouter.ai/api/v1` |
| `LLM_MODEL` | `LLM_PROVIDER=openai` | mis. `gpt-4o-mini` / `openai/gpt-4o-mini` |
| `DAILY_AI_CEILING_USD` | tidak | default `1.00` untuk semua pengguna per hari |

`NODE_ENV=production` menolak nilai dev: pepper pendek, kunci enkripsi bukan 32 byte, token sweep
kosong, `AUTH_DEV_FAKE_TOKENS=true`, atau client ID yang masih placeholder. Pesannya menyebut
variabel mana yang harus diperbaiki.

## Opsi LLM gratis

Backend ini tidak butuh API key berbayar. Tiga preset yang didukung:

| Opsi | Biaya | Konfigurasi |
|---|---|---|
| **Ollama** (offline) | gratis penuh, tanpa akun | `LLM_PROVIDER=openai`, `LLM_BASE_URL=http://127.0.0.1:11434/v1`, `LLM_MODEL=qwen3-vl:8b-instruct`, `LLM_API_KEY=` (kosong) |
| **Groq** | gratis, tanpa kartu kredit | `LLM_BASE_URL=https://api.groq.com/openai/v1`, `LLM_MODEL=llama-3.3-70b-versatile`, `LLM_API_KEY=<key Groq>` |
| **OpenRouter free** | gratis untuk model `:free` | `LLM_BASE_URL=https://openrouter.ai/api/v1`, `LLM_MODEL=<vendor>/<model>:free`, `LLM_API_KEY=<key OpenRouter>` |

Default tetap `LLM_PROVIDER=fake` (deterministik, tanpa jaringan) supaya `npm start` dan `npm test`
jalan tanpa apa pun.

Ollama, sekali saja:

```bash
ollama pull qwen3-vl:8b-instruct
```

lalu di `.env`:

```bash
LLM_PROVIDER=openai
LLM_BASE_URL=http://127.0.0.1:11434/v1
LLM_MODEL=qwen3-vl:8b-instruct
LLM_API_KEY=
LLM_TIMEOUT_MS=120000
```

Runtime lokal tidak memerlukan header Authorization (backend mengirimkannya hanya bila
`LLM_API_KEY` terisi), dan `NODE_ENV=production` menolak key kosong hanya untuk host remote.

Catatan model lokal:

- Model *thinking* (mis. `gemma4:e2b-it-q4_K_M`) menghabiskan `max_tokens` di kanal `reasoning`
  sehingga `content` bisa kosong (`finish_reason=length`) dan permintaan berakhir `502
  provider_unavailable`. Naikkan `LLM_MAX_OUTPUT_TOKENS` (mis. 2048) atau pilih model *instruct*.
- `qwen3-vl:8b-instruct` lolos validasi apa adanya dengan `LLM_MAX_OUTPUT_TOKENS=800`.
- Bila model kecil mengembalikan JSON rusak, backend sudah meregenerasi satu kali lalu menjawab
  `422 invalid_analysis_output`; turunkan `temperature` atau ganti model bila sering terjadi.

## Cara tes

```bash
npm run typecheck     # 0 error
npm test              # node:test + tsx, tanpa jaringan
npm run build         # dist/ untuk Docker
```

Yang diuji: verifikasi ID token Google (aud/exp/iss/signature), sesi dan pencabutannya, isolasi
antar pengguna, gerbang consent, allowlist payload, penolakan keluaran LLM (JSON rusak, angka
karangan, prompt-injection) dengan regenerasi maksimal satu kali, cache FR-40, kuota, enkripsi
at-rest, penghapusan, dan log bebas PII.

## Cara deploy

```bash
docker build -t pam-backend .
docker run -d --name pam-backend -p 8080:8080 \
  -e NODE_ENV=production \
  -e GOOGLE_WEB_CLIENT_ID=... \
  -e SESSION_PEPPER=... \
  -e DATA_ENCRYPTION_KEY=... \
  -e INTERNAL_SWEEP_TOKEN=... \
  -v pam-data:/data \
  pam-backend
```

atau `docker compose up --build` (memakai `.env` bila ada; image berjalan sebagai pengguna non-root
dan menyimpan DB di volume `/data`).

Backup: `sqlite3 /data/pam.db ".backup '/backup/pam-$(date +%F).db'"` terjadwal. Simpan
`DATA_ENCRYPTION_KEY` **terpisah** dari file backup — tanpa kunci itu isi DB tidak bisa dibuka, dan
sebaliknya.

## Struktur

```
src/
  config.ts            env + validasi produksi (fail fast)
  app.ts               factory Express (semua dependensi di-inject)
  server.ts            bootstrap: config → DB → sweep → HTTP
  auth/                verifikasi ID token Google, sesi opaque, owner id turunan
  store/               migrasi + seluruh query (selalu owner_id)
  domain/              skema payload, prompt analysis-v1, validasi keluaran, kuota
  llm/                 AnalysisProvider: fake (default) dan OpenAI/OpenRouter
  routes/              satu file per resource pada kontrak §4
  http/, logger.ts     error shape, korelasi, log JSON tanpa isi payload
```

## Catatan keamanan

- Tidak ada email, subject, sender, message id, token Google, PAN, nama merchant, catatan, atau
  baris transaksi yang diterima maupun disimpan (FR-35).
- Payload agregat, hasil analisis, dan rencana anggaran disimpan sebagai ciphertext AES-256-GCM
  per baris; AAD mengikat ciphertext ke pemilik dan tujuannya.
- Setiap query memfilter `owner_id`; objek milik pengguna lain menjawab 404 (FR-02/NFR-17).
- Log satu baris JSON per request: `correlationId`, method, path, status, ms, `ownerRef` (hash
  pendek), retry — tanpa payload, nominal, kode kategori, email, atau token (NFR-18).
