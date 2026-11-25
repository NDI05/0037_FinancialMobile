# Azure untuk semua fitur AI + optimalisasi token — 25 November 2026

Keputusan pemilik (25 Nov 2026, via chat): **semua analisis AI boleh memakai Azure penuh**
(kredit Azure ±100 USD tersedia) dengan syarat **token tidak boros**. Dokumen ini mencatat
jalur Azure yang aktif, bukti nyata, dan semua pagar biaya/token yang menahan pemakaian.

## 1. Status jalur AI per lingkungan

| Lingkungan | Analisis (`POST /v1/analyses`) | Kategorisasi AI (`POST /v1/category-jobs`) | Catatan |
|---|---|---|---|
| **UAT lokal pemilik** (`127.0.0.1:18090`) | **Azure** — `LLM_PROVIDER=azure`, deployment Foundry `pam-category-gptoss120b` | **Azure aktif** — `CATEGORY_LLM_ENABLED=true`, provider/deployment sama | Diaktifkan 25 Nov 2026 malam atas keputusan pemilik; launcher `~/.config/pam/uat-runtime/start.py` (backup `start.py.bak-pre-azure-full-20261125`) memetakan endpoint/key/deployment dari profil privat `~/.config/pam/azure-classifier.env` ke `LLM_*` |
| **Vercel demo** (`pam-api-vercel.vercel.app`) | tetap `fake` | tetap nonaktif | **Sengaja tidak diberi key Azure**: demo publik, registrasi terbuka, tanpa rate-limit; menaruh key Azure di sana membuka penyalahgunaan kuota/kredit oleh akun sintetis |
| **Harness/tes isolasi** | stub `fake` (kecuali smoke Azure di bawah) | stub (kecuali smoke Azure di bawah) | Rp0, tanpa data pemilik |

Profil resmi repo tetap: `scripts/run_real_uat_with_classifier.py` + `real-uat.env` +
`azure-classifier.env`. Launcher itu sudah memetakan endpoint/deployment Azure yang sama untuk
kedua fitur saat `PAM_UAT_CLASSIFIER=1`; tanpa flag tersebut classifier mati dan nilai Azure
tidak masuk proses.

## 2. Bukti Azure nyata (dijalankan 25 Nov 2026, biaya riil kecil)

Binary dibangun dari backend `main` `5789f54` (bersih, == `origin/main`).

### 2.1 Analisis — `scripts/azure/analysis_smoke.py`

Instance throwaway (port acak, SQLite sekali-pakai, akun sintetis), profil Azure nyata:

| Bukti | Hasil |
|---|---|
| Consent gate | PASS — tanpa consent `AI_PROCESSING` → `403 consent_required`, 0 baris analisis |
| Panggilan Azure | PASS — `analysis-v1`, freshness `CURRENT`, 3 observations, 1 limitation, 2,64 dtk |
| Token & biaya | PASS — prompt **409**, completion **156**, total **565** token; biaya tercatat **$0,00015495** (± Rp2,5) |
| Label provider | PASS — `provider=azure-openai-compatible`, `model=pam-category-gptoss120b`, `regenerations=0` |
| Cache/replay | PASS — GET & POST ulang dengan `Idempotency-Key` sama mengembalikan hasil tersimpan; `providerCalls` tetap **1**, hanya 1 baris `analyses` |
| Freshness tanpa biaya | PASS — transaksi berubah → `STALE` tanpa panggilan provider baru; consent dicabut → `HISTORICAL` |
| Plafon harian | PASS — `DAILY_AI_CEILING_USD` sangat kecil → `429 daily_ceiling_reached` **sebelum** reservasi/panggilan provider |
| Log usage | PASS — satu baris `analysis provider usage` tersensor (token + correlationId) |

Bukti JSON: [`assets/pm_go_2026-11-25/azure_analysis_smoke_2026-11-25.json`](assets/pm_go_2026-11-25/azure_analysis_smoke_2026-11-25.json).

### 2.2 Kategorisasi — `scripts/azure/classification_smoke.py --synthetic-only`

Satu permintaan Azure berisi 5 merchant sintetis (tanpa data email/pemilik):

| Bukti | Hasil |
|---|---|
| HTTP/provider | PASS — **200**, model `pam-category-gptoss120b`, 2,30 dtk |
| Token | prompt **380**, completion **272** (reasoning 104), total **652** untuk 5 merchant |

Bukti JSON: [`assets/pm_go_2026-11-25/azure_classification_smoke_2026-11-25.json`](assets/pm_go_2026-11-25/azure_classification_smoke_2026-11-25.json).

## 3. Pagar token/biaya yang sudah ada (bukan janji, sudah di kode)

**Analisis**
- Hash cache: bulan + revisi + `PromptVersion` + provider/model + hash snapshot + consent +
  hash dependensi. Tap berulang / buka ulang mengembalikan hasil tersimpan, **nol token Azure**.
- Idempotency replay: `Idempotency-Key` sama tidak memanggil provider lagi.
- Snapshot dibangun di server (bukan kirim transaksi mentah); payload divalidasi.
- `LLM_MAX_OUTPUT_TOKENS` (UAT: 1200), `LLM_TIMEOUT_MS` 12000, budget input dicek sebelum call,
  `max_tokens` eksplisit, `reasoning_effort: low`, temperature 0,2, JSON schema ketat.
- `DAILY_AI_CEILING_USD=0,25` di UAT lokal; setiap call direservasi `MaxCallCostUSD` lebih dulu,
  ceiling habis → `429` tanpa panggilan provider.
- Konsen `AI_PROCESSING` dicek ulang setiap POST; tanpa consent tidak ada biaya.

**Kategorisasi**
- Hanya transaksi **baru/belum berkategori**; gate backfill FR-67 menahan antrean sampai bulan
  backfill selesai (tes: 0 panggilan selama gate, lalu tepat 4 panggilan).
- Aturan tanpa biaya: referensi 19 digit / angka pendek / label kosong / label tidak aman →
  `NEEDS_REVIEW` **tanpa** panggilan provider.
- Dedupe merchant dalam satu permintaan + cache hasil per owner (merchant sama tidak dibayar ulang).
- Batch maksimal 5 transaksi per permintaan; maksimal **4 panggilan provider per jendela 2 jam**
  (sisa tetap `PENDING`); fan-out cache maksimal 50 baris tanpa provider.
- Plafon `CATEGORY_LLM_DAILY_CEILING_USD=0,10` dan `CATEGORY_LLM_MONTHLY_CEILING_USD=2,00`;
  attempt cap 20/owner/hari dan 100/install/hari; harga prompt/completion wajib > 0 (fail-closed).
- Enable classifier **wajib** endpoint/key/prices valid — server menolak start bila kosong,
  sehingga tidak ada fallback diam-diam ke provider mahal.

## 4. Estimasi biaya terhadap kredit 100 USD

| Operasi | Token tipikal | Biaya terukur | Kredit 100 USD cukup untuk |
|---|---|---|---|
| Satu analisis bulanan (cache miss) | ±565 | $0,000155 | ±645.000 analisis (jauh di atas kebutuhan) |
| Satu batch klasifikasi 5 transaksi | ±652 | ±$0,0002–0,0003 | ±300.000+ batch |
| Plafon analisis lokal | — | maks $0,25/hari | $100 ≈ 400 hari plafon penuh |
| Plafon classifier | — | maks $0,10/hari & $2/bulan | $100 ≈ 50 bulan plafon penuh |

Plafon harian/bulanan adalah pembatas utama, bukan kredit. Pemakaian normal (satu akun, sync
bulanan) jauh di bawah plafon.

## 5. Cara mematikan lagi (rollback)

1. Kembalikan launcher: `cp ~/.config/pam/uat-runtime/start.py.bak-pre-azure-full-20261125 ~/.config/pam/uat-runtime/start.py`
2. Restart backend UAT (`python3 ~/.config/pam/uat-runtime/start.py`), cek `GET /readyz` = 200.
3. Jalur resmi repo: jalankan `scripts/run_real_uat_with_classifier.py` **tanpa**
   `PAM_UAT_CLASSIFIER=1` (classifier mati; `LLM_PROVIDER=fake` hanya jika env override).

Tidak ada perubahan kode backend pada aktivasi ini: seluruhnya konfigurasi runtime privat.
Kode backend tetap `5789f54` (== `origin/main`), Android tetap `733c0fa` (== `origin/main`).

## 6. Catatan risiko

- Kunci Azure tetap **hanya** di file privat mode 600 dan tidak pernah dicetak/di-commit.
- Vercel demo tidak memakai Azure (alasan di §1). Bila pemilik ingin demo memakai Azure,
  perlu rate-limit/allowlist lebih dulu agar kredit tidak bisa dikuras pihak lain.
- Angka biaya di atas adalah yang dilaporkan provider/server (bukan estimasi tebakan); harga
  per 1K token di profil privat sudah diisi sehingga perhitungan plafon memakai harga nyata.
