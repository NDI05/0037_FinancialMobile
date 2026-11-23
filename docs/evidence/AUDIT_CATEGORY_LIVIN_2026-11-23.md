# Independent audit — category Livin reason fix (`8ad43f6`) — 2026-11-23

Auditor: `worker-sync-recovery` (independent of the author lane). Not committed; written at Lead's request.
Scope: commit `8ad43f6` (`origin/work/category-livin`) vs base `112ddb3` (`feat/c2-android`).
Environment: detached worktree `pam-wt-audit` @ `8ad43f6`; real UAT DB `pam-real-uat` inspected
read-only; real backend 18090 not restarted/touched; emulator-5554 owner session not touched.

## Verdict

**PASS WITH NOTES.** No blocking defect: the diagnosis is evidence-based (with one stated
limitation below), the Go guard ordering and privacy invariants hold, the Android mapping is safe,
the evidence is sanitized and its NOT RUN claims are honest, and the gates pass on re-run. Four
minor evidence-wording/test-coverage items should be fixed before merge (none changes behavior).

| Checklist item | Result |
|---|---|
| 1. Diagnosis validity | **PASS (with limitation).** DB confirms 47 owner rows, 5 `NEEDS_REVIEW`/`unsafe_merchant`, 0 owner cache, 0 owner provider attempts; log + pre-fix binary confirm the 22:27 baseline. Merchant-label *shape* stats are author-observed only (DB columns are sealed ciphertext) — see M4. |
| 2. Go logic | **PASS.** Reason classification is after eligibility/consent/personal-transfer and before `SanitizeMerchant`; every new path is `NEEDS_REVIEW` + `uncategorized`, 0 provider calls; cache/dedup/budget/5-merchant/lease flows unchanged; one `finishJob` per job. |
| 3. Android | **PASS.** Reason codes → Indonesian copy, unknown → generic, no raw token; detail notice is neutral; editable value preserved; USER-origin rows still excluded; no consent-gating change; no new sensitive display. |
| 4. Evidence | **PASS with notes.** Sanitized (scan clean), numbers match DB/log, NOT RUN/limited-UAT claims honest; minor citation/wording defects M1–M3. |
| 5. Diff hygiene | **PASS.** 14 files, +577/−3, no secrets, no SQL/DB writes, no artifacts, no backlog-drain or scope creep; single commit on base; remote hash matches. |
| 6. Gates | **PASS.** Go `test`/`vet`/`build` green; Android full suite 498 tests / 0 failed / 0 skipped, targeted transactions area 38 tests / 0 failed (incl. 5 new). |

## Independently verified facts

Read-only DB (`turso db shell pam-real-uat`, aggregates only, no raw values/ids printed):

- Real owner: **47** transactions, all `category_origin=IMPORTED`, all joined to builtin
  `uncategorized`, 0 `USER`. (DB has other synthetic owners; scoped via the owner holding the
  47 imported rows.)
- Owner `category_jobs`: exactly **5 × NEEDS_REVIEW / reason_code=unsafe_merchant**, created
  `2026-11-23T14:16:30.141207Z`–`14:16:32.231881Z`; **no** owner job created after `15:00Z`
  → the 22:27 WIB tap replayed the same 5 ids (no new jobs/charges).
- Owner `category_cache`: **0** rows (0 tied to the unsafe_merchant jobs).
- Owner `category_usage`: **0** rows → 0 owner provider attempts. Global `*` scope:
  2 attempts / $0.0002118 (matches evidence's unrelated synthetic owner).
- Owner `import_jobs`: 1 × COMPLETE `v1`, matched 47 / imported 47, finished
  `2026-11-22T09:04:55.439593Z`; today 4 × EMPTY (0 matched), consistent with "no re-probe possible".
- Backend log excerpt is **byte-identical** to `/private/tmp/pam-android-studio-uat.log`
  lines 140–143 (incl. correlation `req_c90c2f9b…`, status 200, 796 ms).
- Running `/tmp/pam-android-studio-uat-api` (PID 58125, mtime 21:12) contains `unsafe_merchant`
  but **not** `merchant_not_identifiable`/`merchant_missing` → UAT ran on the pre-fix binary and it
  was not restarted.

Code (read at `8ad43f6`):

- `backend-go/internal/httpapi/handlers_category.go:207` — `merchantReviewReason` runs after
  revision/`USER`/personal-transfer eligibility (`:187`–`:206`) and before
  `categoryllm.SanitizeMerchant` (`:214`). Empty label → `merchant_missing`, digits/separators only
  → `merchant_not_identifiable`, other sanitizer failures → `unsafe_merchant`; all
  `NEEDS_REVIEW` + `uncategorized`, none appended to `pending` → no provider/cache/budget call.
- `categoryllm.SanitizeMerchant` is **untouched** (`git diff 112ddb3 8ad43f6 -- internal/categoryllm/`
  is empty); >8-digit rejection still at `classifier.go:116`.
- Consent (403), 1..5-id cap, duplicate-tap replay, cache lookup, coalescing, budget gate,
  transient-retry/requeue and lease-token finishing are unchanged; the new branch returns the
  finished job once, so no double `finishJob`.
- New Go test `category_pipeline_test.go:802` asserts, per case (19-digit/short/separated ref,
  empty, unsafe), `NEEDS_REVIEW`, exact `reasonCode`, `uncategorized`, **0** provider calls and
  transaction category unchanged. Readable-label control still classifies via existing
  `BAKSO UJI` pipeline tests; isolated evidence shows only `KOPI UJI` reaching the stub.
- Android: `TransactionsViewModel.kt:603` dedups/sorts `reasonCode`/`errorCode`; `:616` numeric
  matcher mirrors the Go rules; `TransactionsScreen.kt:369` maps 5 known codes, everything else to
  `transactions_classify_review_generic` (no raw token reachable); detail sets
  `merchantIsReference` (`TransactionDetailViewModel.kt:192`) and shows a neutral notice
  (`TransactionDetailScreen.kt:303`); USER-origin exclusion (`TransactionsViewModel.kt:502`) and
  consent flow unchanged.

Evidence sanitation: no 12+ digit sequences, emails, owner ids/tokens, or screenshots in the docs
/assets; "withheld"/"shape only" statements are accurate.

## Findings (none blocking)

**M1 — Minor (evidence citation).** `docs/evidence/UAT_CATEGORY_LIVIN_2026-11-23.md:92` cites Go
test `TestUserCategoryNotOverwritten`, which does not exist. The USER-category-preservation
assertion actually lives in `TestCacheIgnoresPreMappingVersionRows`
(`backend-go/internal/httpapi/category_pipeline_test.go:735`, assertion at `:794`). Fix the
citation.

**M2 — Minor (evidence wording).** `UAT…md:32` and `owner_uat_baseline.json:45` name the
sanitizer rejection `digits_gt8`; no such code exists — `SanitizeMerchant` returns
`ErrInvalidInput` when digits > 8 (`backend-go/internal/categoryllm/classifier.go:92`–`:119`).
Describe the actual rule instead of an invented token.

**M3 — Minor (evidence precision).** `owner_uat_baseline.json:16` says `"classifier_origin": null`;
the DB column is `TEXT NOT NULL DEFAULT ''`, i.e. empty string for all 47 (semantics "no classifier
applied" is correct). If the value was read through the API, empty→null is fine; otherwise say
"empty".

**M4 — Note (verification limitation).** The owner merchant-shape stats (47/47 19-digit, 27
distinct digit sets, attributes empty, constant description) cannot be re-derived from
`pam-real-uat`: merchant/description fields are sealed (`transactions.sensitive_ciphertext`, no
plaintext merchant column). They are corroborated indirectly (5 jobs `unsafe_merchant`, >8-digit
rule, 0 cache/usage) but remain author-observed, not audited facts. Do not present them as
independently verified.

**N5 — Low (test coverage).** No unit test covers the detail path: `merchantIsReference` wiring
and the detail notice banner are untested (`ClassificationReviewReasonTest` covers only the
helpers/mapping). Add one small ViewModel/screen assertion for reference vs. real-name labels.

**N6 — Low (intentional behavior change, worth documenting).** The new gate routes *all*
numeric-only labels to manual review, including short ones (≤8 digits, e.g. "711") that previously
reached the provider. This matches the mandate (reference labels never leave the server) but is a
product tradeoff not stated in the evidence.

**N7 — Low (UX copy).** FAILED-job `errorCode`s (budget_exhausted, stale_job, provider_*, …) fall
back to "AI belum bisa menentukan kategori"; safe (no token leak) but under-explains operational
failures. Optional follow-up.

## Gates re-run (exact)

Backend (`pam-wt-audit/backend-go`, Go 1.27.4 via main checkout toolchain):

- `./scripts/go.sh test -count=1 ./...` → **PASS** (all packages ok; `internal/httpapi` 19.183 s)
- `./scripts/go.sh vet ./...` → **PASS** (exit 0)
- `./scripts/go.sh build ./...` → **PASS** (exit 0)
- `./scripts/go.sh test -count=1 -run 'TestReferenceMerchantNeedsReviewWithoutProviderCall' -v ./internal/httpapi/`
  → **PASS** (5/5 subtests, 0.97 s)

Android (`pam-wt-audit`, `.toolchain/.gradle-home/.robolectric-deps` symlinked to main checkout,
build serialized via `/tmp/pam_build_lock.sh`):

- `./scripts/build.sh :app:testDebugUnitTest --tests "com.pam.recap.ui.feature.transactions.*"`
  → **BUILD SUCCESSFUL**, 6 classes / 38 tests / 0 failed / 0 skipped
  (`ClassificationReviewReasonTest` 5/5).
- `./scripts/build.sh :app:testDebugUnitTest` → **BUILD SUCCESSFUL**; 69 result XML files,
  **tests=498, skipped=0, failures=0, errors=0** — matches `test_results.txt` exactly.

## NOT run / limitations of this audit

- No runtime verification on backend 18090 (pre-fix binary must not be restarted) and no APK
  install/tap on emulator-5554 (owner session untouched). Real-owner CLASSIFIED visibility and
  ≥95% precision remain **NOT RUN** — evidence is honest about this.
- No raw-email parser probe (no raw Livin emails available; Gmail-linked import jobs are EMPTY).
- No read of sealed owner merchant/description values (privacy); shape claims per M4.
- No merge/commit; branch left as-is at `8ad43f6` (remote hash verified equal).

## Residual risk

1. Until the Livin bill-payment template/parser provides a real merchant/biller name, owner rows
   stay `NEEDS_REVIEW` with manual selection — the fix explains, it does not make owner data
   classifiable.
2. The post-fix runtime path is proven on an isolated instance + tests, not on 18090; deploy/restart
   (with consent) still needed for the owner to see the new reasons live.
3. New backend reason codes require new Indonesian copy; the generic fallback prevents token leaks
   but would silently under-explain.
4. Numeric-only legitimate brand names become manual-review rows (N6).

## Artifacts / cleanup

- Audit worktree: `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-wt-audit`
  (detached @ `8ad43f6`, `local.properties` copied; untracked env symlinks `.toolchain`,
  `.gradle-home`, `.robolectric-deps` → main checkout). Lead may `git worktree remove --force`
  after removing those symlinks. No code was modified.
- This report is uncommitted in the main checkout (`pam-app`); main checkout otherwise clean.
