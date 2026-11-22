# Mandat SE — Azure LLM kategorisasi dan kelanjutan S2–S4

Lanjutkan dari HEAD aktual branch feat/c2-android, root Git pam-app. Baca AGENTS.md dan jaga
WIP/commit baru. Setiap commit langsung push ke branch yang sama; tidak force/reset/merge/
deploy app publik atau menghapus data nyata. Mandat S2–S4 dan perbaikan CR-003 tetap berlaku.

Pemilik memilih **Azure for Students**, bukan AWS. PM sudah login CLI, memeriksa Student
Enabled/spending limit On/region policy, menyiapkan Foundry resource/project dan deployment
`pam-category-gptoss120b` (gpt-oss-120b version1, Japan West, GlobalStandard). Satu request
lima fixture sintetis mendapat HTTP200 dan expected output. Ini bukan runtime category feature
atau benchmark akurasi. Tidak perlu provision ulang atau meminta key ke chat.

Sumber kebenaran baru:

- `docs/specifications/PAM_SRS_v0.5_Azure_LLM.docx` / PDF, full revision dari v0.4.
- `docs/changes/CR_004_AZURE_LLM_CATEGORIZATION.md` dan CR-003.
- `docs/AZURE_CLASSIFIER_SETUP.md` dan evidence smoke.
- Current contracts/architecture/living checkpoint dan mandat REAL-UAT/S2–S4 sebelumnya.

## 1. Provider/konfigurasi yang nyata

Konfigurasi privat `~/.config/pam/azure-classifier.env` adalah 0600; key backend-only. Jangan
mencetak/commit isinya, memindahkan secret ke APK, atau mengganti existing real-uat.env/encryption
keys. Flag CATEGORY_LLM_ENABLED masih false secara sengaja; env bukan implementasi budget/consent.

Implementasikan dedicated typed classifier adapter melalui Azure OpenAI-compatible v1. Pakai
deployment name sebagai model route dan pin prompt/model/taxonomy version. Jangan melebarkan
aggregate-analysis Provider.Analyze atau FR-35 agar dapat menerima merchant. Analisis agregat
tetap terpisah. Managed identity/Entra merupakan target hosted auth; private key dev boleh sesuai
setup. Jangan melakukan paid fallback, upgrade subscription, Marketplace purchase atau mengubah
allowed-region/spending policy untuk menutupi error.

## 2. Purpose, payload dan kontrak

Tambahkan consent `AI_CATEGORIZATION` yang independen dari `AI_PROCESSING` dan `GMAIL_READ`.
Audit schema CHECK constraints, backend handlers, DTO, Android consent UI/recovery dan notice
version. Local GRANTED atau consent lama aggregate-only tidak memberi izin classifier cloud.
Tolak/withdraw AI categorization tetap mengizinkan import/manual/read finance dan analisis yang
memiliki consent terpisah. Cancel/discard late output setelah withdrawal/deletion/owner switch.

Input provider dibangun server dari record owner, bukan body bebas client. Allowlist hanya
business merchant tersanitasi ≤120 chars, direction/method yang valid, built-in category codes
dan taxonomy version. Tidak ada nominal/tanggal, personal counterpart, raw email/source IDs,
PAN/account, secret, notes/attributes/custom labels. Fail sanitization menjadi NEEDS_REVIEW.
Business merchant proper name bukan bukti anonim; notice harus menjelaskan data tersebut ke Azure.

Output dibatasi categoryCode/needsReview/reasonCode dengan strict schema bila didukung, ditambah
validasi server semantic compatibility. Untrusted merchant tidak boleh menjadi prompt instruction,
tool call, kategori baru, perubahan amount/date/direction, refund atau internal-transfer fact.
Pisahkan category confidence/provenance dari parser confidence; tambahkan LLM origin melalui
migrasi/DTO/validators/UI tanpa memalsukan USER/RULE. Unknown/ambiguous boleh abstain.

## 3. Pipeline durable sampai UI

Import ke Go commit dahulu; model bukan prasyarat penyimpanan transaksi. Buat job PENDING/
RUNNING/CLASSIFIED/NEEDS_REVIEW/FAILED/CANCELLED owner/revision-scoped dengan stable operation
identity, payload hash, bounded retry dan restart/resume. USER overrides dan confirmed mapping
menang; owner-scoped merchant cache dengan policy/model/taxonomy versions mengurangi repeated
calls. Bounded local rules menjadi fallback, bukan sumber false-positive substring kos/air.

Apply hasil kategori hanya ke record/revision yang tepat; preserve fingerprint/row count/source
amount/time/direction dan USER correction. Handle 409/deleted record/late result secara jujur.
Reclassify row UNCATEGORIZED non-USER yang layak via authorized API, bukan bulk SQL pada data
pemilik. Perbarui dependency revision dan refresh list/detail/summary/analysis tanpa restart.

## 4. Budget dan data processor

Default pilot kandidat: concurrency≤2, timeout20s, satu retry, USD0.10/hari dan USD2/bulan.
Implementasikan atomic worst-case cost reservation, token/output caps, actual usage reconciliation
dan konservatif untuk unknown outcome. Verify current price per model/SKU; harga tidak terverifikasi
berarti cloud-disabled/fail-closed, bukan dianggap nol. Cache/retry tidak menghindari pencatatan
provider call yang mungkin sudah ditagih. Kuota milik subscription, bukan tiap akun PAM.

Spending limit Student On dipertahankan. Azure budget alerts bukan hard stop dan nilai env
budget belum otomatis ditegakkan. Jangan menyatakan sisa USD100 tanpa evidence billing. Azure
GlobalStandard bisa memproses di luar resource Japan West; jangan menjanjikan Indonesia-only
atau zero retention. Jangan aktifkan prompt-body invocation logs. Bukan izin mengirim data nyata
sebelum notice/purpose/minimization dan UAT gate ini siap.

## 5. Acceptance yang wajib dikerjakan

1. Tests purpose isolation, nested forbidden fields, malformed/schema-valid-but-wrong responses,
   injection, wrong owner, stale revision, USER override dan consent/deletion race.
2. Android fixture→Go import→Azure classifier→DB→GET list/detail/summary→UI, tanpa restart. Tunjukkan
   PENDING/NEEDS_REVIEW/provider-error states, reimport/no duplicate, cached merchant dan restart.
3. Kredit/quota/budget/401/403/429/timeout/response-loss dan cancelled job tidak menghapus atau
   menggandakan transaksi. Jangan melakukan fault injection pada akun/DB Gmail asli.
4. Siapkan benchmark human-labeled merchant-disjoint, representatif termasuk unknown/ambiguous.
   Laporkan per-class F1, auto-applied precision dan coverage/abstention, latensi/token/failure.
   Target ≥95% auto precision masih target, bukan hasil lima fixture. Confidence LLM bukan probabilitas.
5. Android unit/assemble/lint; Go tests/race/vet/build fresh; migration/interop bila terdampak.
   Preserve C1/F1–F4/gate A/parser strict/C2A/R1/OFL dan owner-safe Gmail persistence.

Sesudah irisan Azure koheren, commit/push dan lanjut seluruh backlog S2–S4: job-native import/
server counts/fingerprint key, pagination/search >100, update/delete/conflict, budget/analysis/
deletion UI, S3 CRUDS/dynamic fields, S4 lampiran/admin/25 aksi/Room removal dan accessibility.
Update contracts, traceability, CR-004 dan living checkpoint berdasarkan implementasi aktual.
Jangan berhenti setelah mengedit env, menulis provider wrapper atau screenshot saja. Laporan
akhir harus memisahkan cloud smoke, benchmark, Android E2E, historical tests dan external NOT RUN.
