# REAL-UAT classifier handoff

Status: **implementation ready for owner UAT; full Android→Azure E2E and benchmark NOT RUN.**
No secret, key, token, merchant or transaction identity is stored here.

## APK and backend

- APK: `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app/app/build/outputs/apk/debug/app-debug.apk`
- Android endpoint: `http://10.0.2.2:18090`
- Local launcher (merges private env and enables classifier only for this test process):
  `python3 scripts/run_real_uat_with_classifier.py /tmp/pam-real-uat-api`
- Build binary first: `cd ../0037-Backend-Financial && ./scripts/go.sh build -o /tmp/pam-real-uat-api ./cmd/api`
- Private flag in `~/.config/pam/azure-classifier.env` remains `CATEGORY_LLM_ENABLED=false`; the
  launcher explicitly enables it for the local REAL-UAT process. Stop it to disable cloud calls.
- Prices used for reservations: Azure gpt-oss-120b GlobalStandard USD0.15/1M input and
  USD0.60/1M output (sources: [Azure pricing comparison](https://www.azurespeed.com/AzureAiModelPricing/Compare?models=openai-gpt-oss-120-b&profile=Global%20Standard),
  [model availability](https://modelavailability.com/models/openai/gpt-oss-120b)); caps are
  app-side, not Azure budget alerts.

## Owner steps

1. Login PAM REAL-UAT; open Consent and enable **Kategorisasi merchant Azure** separately
   (notice v2). Declining keeps import/manual/summary usable.
2. Open Transactions. The **Kategorikan dengan AI** button submits at most 5 visible eligible
   uncategorized rows per tap.
3. Expected states: CLASSIFIED updates category; NEEDS_REVIEW leaves uncategorized; FAILED/budget
   shows an error/status. A USER correction is never overwritten.
4. Stop the classifier process to stop cloud calls; financial data remains.

## Evidence

- Typed adapter strict tests and handler tests pass; Azure synthetic 5-case adapter probe returned
  BAKSO/COFFEE→food, cosmetic→shopping, chair→needs review, unknown→needs review.
- Local backend E2E with real Azure: BAKSO UJI transaction → CLASSIFIED food; readback
  `categoryCode=food`; cache hit produced no second provider call; USER override was not eligible.
- Official `classification_smoke.py` still fails output validation (`missing_or_duplicate_case`)
  on a fresh run; that is not reused as PASS. The adapter contract is the path tested above.
- Not run: Android UI → backend → Azure, 20 labeled merchant benchmark, restart/lease recovery,
  actual token/cost reconciliation, real-user UAT.

## Update after review defects — 22 November 2026

- Root cause of smoke/adapter divergence: named case IDs in smoke vs index in adapter,
  Bearer vs Azure `api-key`, loose json_object schema, and undetected truncation. Adapter now uses
  stable `c0..c4`, strict schema, `api-key`, reasoning-low, `finish_reason=length` rejection and
  input-token estimation before HTTP.
- Fresh synthetic Azure smoke: **PASS**, HTTP 200, 5 cases, elapsed 4.019s,
  usage 380 prompt / 289 completion / 121 reasoning tokens. Evidence:
  `docs/evidence/azure_classification_synthetic_smoke_latest.json`.
- Backend local E2E: BAKSO UJI transaction → category job `CLASSIFIED` → server readback
  `categoryCode=food`, `categoryOrigin=LLM`, rule/prompt/model provenance. Cache hit and USER
  override made no additional provider call. One earlier provider-timeout call was settled
  conservatively; combined recorded cost in the local test DB: **USD0,0009216** (not an Azure
  invoice).
- Added global monthly reservation, reservation IDs settled against original buckets, measured
  vs estimated/unknown usage, per-owner/installation attempts, direction-aware category allowlist,
  `OTHER` method unless QRIS is verifiable, get-or-create/atomic job claim, LLM provenance columns
  and deletion cleanup.
- Android list can read LLM origin and shows `Makanan`; full Android-triggered Azure E2E, durable
  worker recovery, coalescing, stale-lease recovery, and benchmark remain open. The two-hour
  window did not complete a clean Android tap→provider→UI run after the final mapper fix.
