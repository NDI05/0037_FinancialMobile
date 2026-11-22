# Setup Azure — PAM merchant classifier

Status 22 November 2026: **cloud deployment + synthetic smoke PASS; integrasi runtime PAM belum**.
Provider dikoreksi pemilik dari AWS ke Azure for Students. Full SRS v0.5 dan CR-004 menjadi
target requirement; existing Go aggregate-analysis adapter tidak otomatis menjadi classifier.

## Resource yang sudah disiapkan

| Komponen | Nilai |
|---|---|
| Subscription offer | Azure for Students, Enabled, spending limit On |
| Resource group | rg-pam-ai-student |
| Foundry resource | pam-ai-student-74eab38, AIServices S0 |
| Region resource | japanwest, diizinkan policy subscription |
| Foundry project | pam-classification |
| Deployment | pam-category-gptoss120b |
| Model | gpt-oss-120b, format OpenAI-OSS, version 1 |
| Deployment SKU/capacity | GlobalStandard / 10 |
| Endpoint route | HTTPS OpenAI-compatible `/openai/v1/chat/completions` |
| Application enable flag | CATEGORY_LLM_ENABLED=false |

Japan West adalah resource location, bukan pembatasan seluruh lokasi inference GlobalStandard.
Resource bestaande project lain tidak diubah. Tidak memakai VM, managed compute, PTU atau
Marketplace purchase. Student credit terbatas dan bukan jaminan sisa USD100 masih tersedia;
saldo/expiry dan actual credit allocation diperiksa melalui Azure Sponsorships/Cost Management.

## Konfigurasi privat

`/Users/user/.config/pam/azure-classifier.env` permission 0600 memuat endpoint, deployment,
model/version/region/SKU dan key Azure. Jangan cat/echo/source ke log, commit, screenshot key,
atau memasukkan key ke Gradle/APK. File ini tidak mengubah existing `real-uat.env`/database keys.

Variabel classifier memakai namespace terpisah:

```text
CATEGORY_LLM_ENABLED=false
CATEGORY_LLM_PROVIDER=azure
AZURE_CLASSIFIER_BASE_URL=<private HTTPS endpoint ending /openai/v1>
AZURE_CLASSIFIER_DEPLOYMENT=pam-category-gptoss120b
AZURE_CLASSIFIER_MODEL=gpt-oss-120b
AZURE_CLASSIFIER_MODEL_VERSION=1
AZURE_CLASSIFIER_REGION=japanwest
AZURE_CLASSIFIER_DEPLOYMENT_TYPE=GlobalStandard
AZURE_CLASSIFIER_API_KEY=<private backend-only secret>
CATEGORY_LLM_DAILY_CEILING_USD=0.10
CATEGORY_LLM_MONTHLY_CEILING_USD=2.00
```

Budget variables adalah konfigurasi kandidat untuk implementasi SE; belum ditegakkan oleh Go.
Flag tetap false sampai adapter, endpoint/job, consent, minimization, budgets dan UI lulus.
Production hosting menggunakan managed identity/Entra credential flow bila tersedia; jangan
menganggap token Azure CLI sementara sebagai token runtime permanen.

## Ulangi smoke sintetis secara eksplisit

```bash
python3 scripts/azure/classification_smoke.py --synthetic-only \
  --evidence /tmp/pam-azure-smoke-UNIQUE.json
```

Ganti UNIQUE tiap run. Tool menolak overwrite dan hanya memakai lima fixture hardcoded; tidak
membaca email/transaction DB dan tidak mempunyai opsi raw-mail input. Satu request cloud nyata
mengonsumsi token/kredit. Jangan menjalankan berulang tanpa alasan. Hasil awal di
`docs/evidence/azure_classification_synthetic_smoke.json`.

Smoke membuktikan HTTP200, schema validation dan contoh food/retail/unknown. Ia tidak membuktikan
accuracy umum, billing balance, production consent, app budgets atau Android→Go→Azure→DB→UI.

## Jika perlu memeriksa resource lagi

```bash
az account show --query '{name:name,state:state}' --output json
az cognitiveservices account deployment show \
  --name pam-ai-student-74eab38 --resource-group rg-pam-ai-student \
  --deployment-name pam-category-gptoss120b \
  --query '{state:properties.provisioningState,model:properties.model,sku:sku}' --output json
```

Verifikasi subscription yang benar tanpa mencetak token/key. Jangan mengubah allowed-region
policy, spending limit, nama/endpoint resource atau model version di belakang consent pengguna.
429 mengikuti Retry-After; 401/403 diperbaiki pada akses/config, bukan retry tanpa batas.

## Handoff implementasi

Jalankan [prompt SE Azure](agent-prompts/PROMPT_SE_AZURE_CLASSIFICATION.md) bersama mandat
S2–S4. Tujuannya pipeline kategori typed dan purpose-scoped; jangan menambahkan merchant ke
`llm.Provider.Analyze` atau payload agregat lama. Cloud setup ini tidak mengubah APK aktif.
