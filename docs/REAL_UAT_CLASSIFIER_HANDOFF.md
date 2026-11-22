# REAL-UAT classifier handoff

Status: **implementation ready for owner UAT; full Android→Azure E2E and benchmark NOT RUN.**
No secret, key, token, merchant or transaction identity is stored here.

## APK and backend

- APK: `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app/app/build/outputs/apk/debug/app-debug.apk`
- Android endpoint: `http://10.0.2.2:18090`
- Local launcher (merges private env and enables classifier only for this test process):
  `python3 scripts/run_real_uat_with_classifier.py /tmp/pam-real-uat-api`
- Build binary first: `cd backend-go && ../scripts/go.sh build -o /tmp/pam-real-uat-api ./cmd/api`
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
