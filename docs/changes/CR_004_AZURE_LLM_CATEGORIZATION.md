# CR-004 — kategorisasi LLM melalui Microsoft Azure for Students

Tanggal: 22 November 2026. Arah provider dan fitur diminta langsung pemilik; implementasi
aplikasi, benchmark dan release acceptance tetap pekerjaan yang harus dibuktikan.

Pemilik mengoreksi rencana AWS menjadi **Microsoft Azure for Students menggunakan akun kampus**.
Tidak ada resource atau credential AWS yang dibuat/dipakai. Credit bukan jaminan layanan gratis
selamanya: offer Student menyediakan USD100/12 bulan, dengan eligibility dan sisa saldo aktual
harus diperiksa. Subscription label tidak membuktikan saldo tepat USD100 masih utuh.

## Keputusan dan setup yang terverifikasi

- Azure CLI terpasang, login interaktif kampus selesai. Subscription yang dipilih bernama
  Azure for Students, Enabled, quotaId AzureForStudents_2018-01-01, spendingLimit On.
- Ada kebijakan allowed regions pada subscription. Jangan mengubah policy, menghapus spending
  limit atau upgrade Pay-As-You-Go untuk melewati pembatasan tanpa instruksi baru pemilik.
- Kuota GPT-5 mini ada di beberapa lokasi yang tidak diizinkan policy. Japan West diizinkan,
  dan model catalog/kuota menyediakan **gpt-oss-120b**, format OpenAI-OSS, version 1,
  GlobalStandard. Model ini termasuk model yang dijual langsung oleh Azure; bukan pembelian
  partner Marketplace. Student credit tidak mencakup Marketplace offers.
- Resource group `rg-pam-ai-student`, resource AIServices `pam-ai-student-74eab38`, project
  `pam-classification`, deployment `pam-category-gptoss120b`, version 1, capacity 10 berhasil
  dibuat khusus PAM. Resource lain yang sudah ada tidak diubah.
- Key/endpoint lengkap hanya ada di `~/.config/pam/azure-classifier.env`, permission 0600.
  Tidak ada key Azure pada Git/APK. File existing `real-uat.env` dan token database tidak diubah.
- `scripts/azure/classification_smoke.py --synthetic-only` menghasilkan HTTP200 dan lima contoh
  sesuai expected pada satu request. Evidence JSON mengandung input/output sintetis saja.
  Ini bukan benchmark holdout, bukan consent test, dan bukan Android→Go→Azure→DB→UI acceptance.
- Flag private `CATEGORY_LLM_ENABLED=false`: cloud endpoint siap, tetapi runtime PAM belum
  memiliki pipeline classifier yang memenuhi CR-004. Keberadaan env tidak mengaktifkan fitur.

## Requirement v0.5

SRS lengkap [DOCX](../specifications/PAM_SRS_v0.5_Azure_LLM.docx) /
[PDF](../specifications/PAM_SRS_v0.5_Azure_LLM.pdf) direvisi dari baseline v0.4, bukan diganti
dengan ringkasan. Seluruh requirement/diagram lama dipertahankan; target yang berubah diperbarui
dan FR-66…71, NFR-29/30 serta suite T13 ditambahkan. CR-003 logout/Gmail juga dikonsolidasikan.

1. **Purpose:** `AI_CATEGORIZATION` independen dari Gmail dan `AI_PROCESSING` analisis agregat.
   Consent lama aggregate-only bukan izin mengirim merchant. Manual/read finance tetap bekerja
   jika klasifikasi AI ditolak, dicabut atau error.
2. **Payload kategori:** hanya business merchant label tersanitasi ≤120 karakter, direction
   terverifikasi, method code bila tersedia, built-in category codes dan taxonomyVersion.
   Tidak mengirim raw email, sumber message ID, nominal/tanggal, account/card, secret, catatan,
   attributes, custom labels atau nama personal counterparty. Input tidak layak menjadi review.
3. **Output:** allow-listed categoryCode, needsReview dan bounded reasonCode; validasi strict
   dan semantic compatibility sebelum category update. Model tidak mengubah ledger money/date/
   direction/refund/transfer. USER override menang; prioritas cache/rules mengurangi repeated calls.
4. **Lifecycle:** financial import commit tidak menunggu LLM; job durable owner/revision-scoped,
   retry bounded/idempotent, pending/review/error state jelas, cancel/discard late result setelah
   withdrawal, logout/owner switch, deletion atau revision conflict. Reclassify row lama hanya
   non-USER yang layak. Invalidation summary/analysis sesudah update yang diakui.
5. **Privasi:** aggregate-analysis allowlist FR-35 tetap terpisah, tidak dilebarkan. Processor notice
   menyebut Microsoft Azure dan GlobalStandard: resource di Japan West bukan jaminan inference
   hanya di Jepang/Indonesia. Azure abuse monitoring/retention tidak diklaim selalu zero-retention.
6. **Biaya:** tidak ada Marketplace purchase, provisioned compute/PTU, subscription upgrade atau
   paid fallback otomatis. Kandidat pilot app budget USD0.10/hari dan USD2/bulan, concurrency≤2,
   request timeout20s, maksimal satu retry. Itu target kontrol aplikasi yang belum diterapkan,
   bukan batas keras yang sudah dipasang di Azure. Azure budget alert bukan hard stop. Pricing,
   actual token usage, credit balance/expiry dan atomic cost reservations harus diverifikasi.
7. **Kualitas:** proposed release target ≥95% precision pada auto-applied subset dengan coverage
   dilaporkan; evaluasi merchant-disjoint, label manusia, per-class F1, ambiguous/unknown cases,
   review rate, latency, provider failures. Self-rated confidence LLM tidak dianggap calibrated.
   Lima smoke examples tidak boleh disebut akurasi 100% transaksi PAM.

## Sumber resmi yang diperiksa

- [Azure for Students](https://learn.microsoft.com/azure/education-hub/about-azure-for-students)
- [Education FAQ dan pengecualian Marketplace](https://learn.microsoft.com/azure/education-hub/faq)
- [Models sold directly by Azure, termasuk gpt-oss](https://learn.microsoft.com/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure)
- [API OpenAI-compatible v1](https://learn.microsoft.com/azure/foundry/openai/api-version-lifecycle)
- [Data/privacy](https://learn.microsoft.com/azure/foundry/responsible-ai/openai/data-privacy)
- [Deployment type dan lokasi pemrosesan](https://learn.microsoft.com/azure/foundry/foundry-models/concepts/deployment-types)

Rincian operasional: [AZURE_CLASSIFIER_SETUP.md](../AZURE_CLASSIFIER_SETUP.md). Tidak ada klaim
seluruh S2–S4 atau cloud user-data acceptance selesai karena setup provider ini.
