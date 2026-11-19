# PAM — Scope & Requirement Traceability

Source: `PAM_SRS_v0.2_Gmail_Livin.pdf` (Document ID PAM-SRS-001, v0.2 draft, 18 Nov 2026).
This file is the Lead's binding interpretation of that baseline for the Android deliverable.
Where the SRS leaves a decision open (Appendix C), the resolution is recorded below and must
be cited in code comments and in the final report — never silently absorbed.

---

## 1. Product in one line

PAM is a dark, glass-styled Android app that turns **Livin by Mandiri email notifications**
into a **deterministic monthly cash-flow recap**, lets the user correct categories, and then
— only with separate, explicit consent — produces an **evidence-linked AI analysis and next
month budget suggestion**.

PAM is **not** a bank app, has **no balance**, **no money movement**, and its totals are
**email-derived**, never a bank ledger.

---

## 2. Navigation baseline (SRS §4.1)

The SRS mandates four top-level destinations. The delivered graph:

| Route | Screen | SRS section |
|---|---|---|
| `welcome` | Welcome + Google sign-in + privacy notice | §4.1, FR-01 |
| `consent` | Gmail & AI consent, decided separately | FR-03, FR-34 |
| `gmail_connect` | Mailbox connection states + connect/disconnect | FR-06…FR-10, FR-49 |
| **`overview`** | Monthly recap, coverage, category split, drill-downs | FR-28…FR-33 |
| **`transactions`** | Month strip, list, filter, search | FR-27 |
| `transaction_detail/{id}` | Provenance, correction, note, transfer, refund link | FR-24, FR-25 |
| `manual_entry` | Manual income/expense, source = Manual | FR-26 |
| `import_job` | Import progress + matched/skipped/unresolved | FR-19, FR-21 |
| **`analysis`** | Provider disclosure, generate, observations, evidence | FR-34…FR-41, FR-50 |
| `budget_plan` | Envelope, limits, feasibility gap, save | FR-42…FR-45 |
| **`settings`** | Connection, consent history, sign out, delete | FR-46…FR-49 |
| `consent_history` | Consent ledger with purpose/version/timestamp | FR-03, FR-49 |
| `data_deletion` | Reauth + confirm + progress | FR-47, FR-48 |

Bottom bar = `overview`, `transactions`, `analysis`, `settings` (+ a raised **Import/Sync**
action). Letters of the SRS requirement "persistent EMAIL-DERIVED indicator" are honoured by a
chip in the Overview header and a badge on every report surface.

---

## 3. Functional requirement coverage

Legend: **impl** = implemented in app, **adapter** = implemented behind a port with a real and a
deterministic implementation, **n/a** = explicitly out of release-1 scope per SRS §1.4.

### 3.1 Identity & consent — §3.1
| ID | Requirement | Where |
|---|---|---|
| FR-01 | Auth session required before financial data | `SessionRepository`, `AuthGate` in nav |
| FR-02 | Owner-scoped access | `ownerId` on every entity + DAO predicates |
| FR-03 | Separate consent records (purpose, notice version, decision, ts) | `ConsentEntity`, `ConsentRepository` |
| FR-04 | Withdraw AI consent blocks new requests, marks old reports historical | `WithdrawAiConsentUseCase` |
| FR-05 | Sign out clears cache, no cross-user leakage | `SignOutUseCase` |

### 3.2 Google account & Gmail authorization — §3.2
| ID | Requirement | Where |
|---|---|---|
| FR-06 | Mailbox only after OAuth success | `GmailConnector.authorize()` |
| FR-07 | 6 explicit connection states | `GmailConnectionState` enum |
| FR-08 | `gmail.readonly` only, no secret in APK, no passwords | `GoogleGmailSource`, `AuthorizationClient` |
| FR-09 | Connection + transactions bound to one user | schema |
| FR-10 | Incomplete config ⇒ blocked with supportable status | `GmailConfigurationError` |

### 3.3 Livin source validation — §3.3
| ID | Requirement | Where |
|---|---|---|
| FR-11 | Query only allow-listed sender + subject; display name alone insufficient | `LivinQueryBuilder`, `LivinTemplateRegistry` |
| FR-12 | Extract date/time, amount, recipient, status, acquirer, fingerprint | `LivinQrisParser` |
| FR-13 | Reject missing/malformed amount, date, direction, status — never coerce 0 | `ParseResult.Unresolved(reason)` |
| FR-14 | Exclude marketing, merchant payouts, unverified templates, attachments | template allow-list |
| FR-15 | Field allow-list, ignore embedded instructions, redacted exclusion reason | `RedactedReason` enum |

### 3.4 Import — §3.4
| ID | Requirement | Where |
|---|---|---|
| FR-16 | Pagination + buffer, month membership from parsed event time in Asia/Jakarta | `ImportMonthUseCase` |
| FR-17 | Preserve exact amount/currency/ts/direction/description/fingerprint/parser version | `TransactionEntity` |
| FR-18 | Idempotent; per-user `HMAC(messageId + fields)`; flag collisions | `FingerprintFactory`, unique index |
| FR-19 | Job record with pages, counts, state (Complete/Partial/Failed/PermissionRequired) | `ImportJobEntity` |
| FR-20 | Empty ≠ failed; "no matching emails" never "no spending" | `ImportState.Empty`, UI copy |
| FR-21 | Retry incomplete pages without data loss; revoked ⇒ reconnect guidance | `RetryImportUseCase` |
| FR-22 | Correction/reversal templates reviewed, provenance kept, dependents outdated | `MarkSummariesOutdated` |

### 3.5 Categorization — §3.5
| ID | Requirement | Where |
|---|---|---|
| FR-23 | Income/expense/transfer/uncategorized + 8 expense categories | `Category` enum |
| FR-24 | Origin (rule/user), update time, confidence; low confidence stays reviewable | `CategoryOrigin` |
| FR-25 | Change category / note / mark transfer without touching amount, date, direction | `UpdateTransactionUseCase` |
| FR-26 | Manual income/expense marked `Manual`, never shown as imported | `TransactionSource` |
| FR-27 | Filter by date/category/direction + search descriptions; show provenance | `TransactionsFilter` |

### 3.6 Summary — §3.6
| ID | Requirement | Where |
|---|---|---|
| FR-28 | grossIn = ΣCREDIT, grossOut = ΣDEBIT, net = in − out, exact arithmetic | `ComputeMonthlySummary` |
| FR-29 | classified income/expense by BR-01…BR-06 | `CashFlowCalculator` |
| FR-30 | Month, mailbox label, all totals, category totals, counts, coverage | `MonthlySummary` |
| FR-31 | Prior-month delta; zero/missing denominator ⇒ "Not applicable" | `PercentChange.Na` |
| FR-32 | Every total drills down; partial/provisional/email-derived labels | `SummaryDrillDown` |
| FR-33 | Summary version + input revision traceability | `summary.inputRevision` |

### 3.7 AI analysis — §3.7
| ID | Requirement | Where |
|---|---|---|
| FR-34 | Verify consent + disclose provider, data categories, purpose pre-request | `AiConsentGate` |
| FR-35 | Aggregate-only payload; no bodies/subjects/senders/ids/tokens/PAN/merchants | `AiPayloadBuilder` + unit test with forbidden-field scan |
| FR-36 | Observations + evidence refs + suggestions + limitations; amounts validated | `AnalysisValidator` |
| FR-37 | Reject malformed/inconsistent; ≤1 regeneration; then "Analysis unavailable" | `AnalysisValidator`, `AnalysisJob` |
| FR-38 | Partial month ⇒ provisional; no records ⇒ no invented trend, no call | `AnalysisEligibility` |
| FR-39 | Store provider/model/prompt version/revision/ts/validation/usage | `AnalysisEntity` |
| FR-40 | Reuse saved analysis for unchanged revision+consent+config | `analysisCacheKey` |
| FR-41 | Consent revoked mid-flight ⇒ discard, never publish | `AnalysisJob` cancellation token |
| FR-50 | Per-user limits, output-token cap, daily ceiling checked pre-invocation | `AiBudgetGuard` |

### 3.8 Budget planning — §3.8 (Medium)
| ID | Requirement | Where |
|---|---|---|
| FR-42 | Expected income, planned savings, per-category limits; label historical averages | `BudgetPlanEditor` |
| FR-43 | gap = income − savings − Σlimits; explicit shortfall when negative | `PlanningGap` |
| FR-44 | AI values require review before activation; save never moves money | `BudgetPlan` |
| FR-45 | Plan month/revision/value origin; actual vs limit, transfers excluded | `BudgetComparison` |

### 3.9 Privacy — §3.9
| ID | Requirement | Where |
|---|---|---|
| FR-46 | Disconnect locally, clear token, revoke where supported, surface revoke failure | `DisconnectGmailUseCase` |
| FR-47 | Recent reauth + confirmation before deletion; sync blocked during deletion | `RequestDeletionUseCase` |
| FR-48 | Deletion status + retention explanation before confirm | `DeletionStatus` |
| FR-49 | View connection/consent/last-success times, never tokens | `SettingsScreen` |

---

## 4. Business rules (binding)

- **BR-01** Month = `[firstOfMonth 00:00 Asia/Jakarta, firstOfNextMonth 00:00 Asia/Jakarta)`.
  Search window = that interval widened by `INGESTION_BUFFER_DAYS = 3`. Membership is decided by
  **parsed event time**, never `internalDate`.
- **BR-02** `grossInflow` = Σ all POSTED CREDIT. `grossOutflow` = Σ all POSTED DEBIT.
  `classifiedIncome` = CREDIT where classification = Income. `classifiedExpense` = DEBIT where
  classification ∈ expense categories and not internal transfer.
  Internal transfers stay in gross, leave classified.
- **BR-03** Refund = CREDIT; may link to an expense category, reducing that category's **net**
  for the refund month. Unlinked credits are never netted against unrelated expenses.
- **BR-04** Exact base-10, two decimals. Internal representation = `Long` minor units (1/100 IDR).
  Rounding only at display. Non-IDR rows excluded from IDR totals and flagged.
- **BR-05** `Complete` = every page of the configured query processed. It never means
  "all bank transactions". Every report carries `EMAIL-DERIVED`.
- **BR-06** A suggestion must cite summary metric keys. With < 1 complete month, say so.

**Worked example (SRS B5) must be a unit test assertion:**
```
Salary          CREDIT  5,000,000.00  Income
Housing         DEBIT   1,000,000.00  Housing expense
Own transfer    DEBIT     200,000.00  Internal transfer
Housing refund  CREDIT    100,000.00  Housing refund (linked)
→ grossInflow 5,100,000.00 | grossOutflow 1,200,000.00 | net 3,900,000.00
→ classifiedIncome 5,000,000.00 | net housing expense 900,000.00
→ previous-month expense 0 ⇒ percentage change = "Not applicable"
```

---

## 5. Non-functional obligations that shape the code

| ID | Obligation | Implementation stance |
|---|---|---|
| NFR-01 | ≥48 dp targets, 200 % font scale, TalkBack, announced loading/error | `Modifier.minimumInteractiveComponentSize()`, `contentDescription`, `liveRegion` |
| NFR-02 | IDR display conventions; fingerprints opaque, no Gmail IDs | `IdrFormatter`, `FingerprintFactory` (HMAC, truncated) |
| NFR-03 | HTTPS only, no debug trust bypass | OkHttp default `CertificatePinner`-free but plain TLS, no `TrustManager` override |
| NFR-04 | Keep `internalDate` and event ts separately, normalize to Asia/Jakarta | two columns |
| NFR-05 | ≤2 retries with backoff, honour `Retry-After`, stop on 401 | `RetryPolicy` |
| NFR-06 | Dashboard ≤2 s p95 | cached Flow + `room` no N+1 |
| NFR-07 | 10k-row aggregation ≤2 s | SQL aggregate + in-memory fold on minor units |
| NFR-10/11 | No money movement; zero ≠ unavailable ≠ partial ≠ empty | state enums + dedicated empty copy |
| NFR-12 | Deterministic arithmetic, tested without LLM | pure `CashFlowCalculator` |
| NFR-13 | Provider text is untrusted data, never instructions | `SanitizedText`, prompt-injection corpus test |
| NFR-14 | No secrets in APK/logs | `BuildConfig` gate, no token logging |
| NFR-15 | 24-month local retention, delete removes immediately | `RetentionPolicy` |
| NFR-16 | Encrypted DB, Keystore key, no backup, obscured recents | SQLCipher + `KeystoreSecretStore` + `FLAG_SECURE` |
| NFR-18 | Logs carry no bodies/subjects/mailbox/token/PAN/merchant | `SafeLogger` allow-list |
| NFR-26 | Gmail + LLM replaceable behind versioned adapters | ports `GmailSource`, `AnalysisEngine` |

---

## 6. Decisions taken on open issues (Appendix C)

| OI | Decision for this build | Rationale |
|---|---|---|
| OI-01 | Real Google OAuth is **wired but gated** by `BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID`. Empty ⇒ app runs in **Demo mailbox mode** using bundled sanitized QRIS fixtures. | A Cloud project + SHA-1 cannot be provisioned from here. Code path is real, not stubbed. |
| OI-02 | Sender allow-list + subject patterns are data in `LivinTemplateRegistry`, versioned `v1`. | Configurable without code change; documents the observed template. |
| OI-03 | Incoming-transfer template unverified ⇒ **manual income only**, as the SRS mandates. | Matches A4. |
| OI-05 | Ingestion buffer 3 days, historical range 24 months. | Aligns NFR-15. |
| OI-07 | LLM is reached through `AnalysisEngine`. Default = `OnDeviceAnalysisEngine` (deterministic, evidence-bound). `RemoteAnalysisEngine` present and enabled only when a backend URL + consent exist. | Keeps FR-34…FR-41 semantics testable offline; NFR-26 satisfied. |
| OI-08 | Single-module app, Hilt, Room+SQLCipher, DataStore, Navigation Compose. No backend in this deliverable. | Scope of an academic release 1. |
| OI-10 | Accepted: one mailbox, Livin only, IDR, Indonesian UI, Asia/Jakarta, API 26+. | Per SRS. |
| OI-12 | Budget planning **included** (Medium). CSV/PDF export, reminders, background schedules **deferred**. | Medium feature is cheap here; deferrals stay out. |

**Out of scope, do not build:** bank APIs, balance, Ledger-completeness claims, Livin Merchant
payouts, password-protected statements, other banks/providers, transfers or payments,
investment/credit/tax advice, shared accounts, receipt scanning, product purchase, autonomous
execution, export, push reminders, background sync.
