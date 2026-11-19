# PAM — AI Assisted Monthly Financial Recap

Android app that turns **Livin by Mandiri email notifications** into a deterministic monthly
cash-flow recap, lets the user correct categories, and — only with separate, explicit consent —
produces an evidence-linked analysis and a next-month budget suggestion.

Built to the specification in `PAM_SRS_v0.2_Gmail_Livin.pdf` (PAM-SRS-001) with the visual
language of the supplied reference design.
New to the code? Read [`docs/01_SCOPE_TRACEABILITY.md`](docs/01_SCOPE_TRACEABILITY.md) first —
it maps every requirement ID to where it is implemented.

---

## What it does

| Capability | Requirement |
|---|---|
| Google sign-in with an app session gate | FR-01, FR-05 |
| Separate Gmail read-only and AI consent, recorded with notice version and timestamp | FR-03, FR-04, FR-49 |
| Mailbox connection with six explicit states | FR-06…FR-10 |
| Livin QRIS notification parsing with an allow-listed sender/template registry | FR-11…FR-15 |
| Paginated, idempotent, resumable import with coverage reporting | FR-16…FR-22 |
| Category correction, private notes, internal transfers, linked refunds, manual income | FR-23…FR-27 |
| Deterministic monthly summary with drill-downs and explicit coverage labels | FR-28…FR-33 |
| Consent-gated analysis grounded in the computed metrics | FR-34…FR-41, FR-50 |
| Next-month budget envelope with a feasibility gap | FR-42…FR-45 |
| Disconnect, consent ledger, deletion with retention disclosure | FR-46…FR-49 |

**Deliberately absent** (SRS §1.4): bank APIs, account balances, ledger-completeness claims,
money movement, other banks or providers, investment/credit/tax advice, export, push reminders,
background sync. The app displays no balance and can move no money.

### The honesty rules the UI enforces

- Every report carries an **`DARI EMAIL`** badge. It is email-derived, never a bank statement.
- `Complete` means *every page of the configured Gmail query was processed* — not that every
  bank movement produced an email (BR-05).
- **Zero**, **unavailable**, **stale**, **partial** and **no matching email** are five distinct
  states with distinct copy (NFR-11). An empty query never renders as `Rp0` spending.
- A missing amount stays *unresolved*; it never becomes zero (FR-13).
- A percentage change against a zero or missing base renders **"Tidak berlaku"**, never
  infinity or a fabricated number (FR-31).
- Amounts are never rendered with a minus sign; direction is carried by the word, the glyph and
  the colour (design system §2).

---

## Build and run

The project is self-contained: it pins its own JDK, Gradle home and debug keystore, because the
machine-wide `~/.gradle` and `~/.android` are not writable in this environment.

```bash
cd pam-app
./scripts/build.sh :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./scripts/build.sh :app:testDebugUnitTest      # 100+ JVM tests
./scripts/build.sh :app:lintDebug              # Android lint
./scripts/build.sh :app:assembleRelease        # R8-minified release build
```

`./scripts/build.sh` is a thin wrapper that exports:
- `JAVA_HOME` → `.toolchain/jdk-21.0.13.1+2/Contents/Home` (Temurin 21; the Android Studio JBR on
  this machine cannot load Gradle's native platform library)
- `GRADLE_USER_HOME` → `.gradle-home` (pre-seeded dependency caches)
- `ANDROID_HOME` → `~/Library/Android/sdk`

`local.properties` points at the SDK. Build with any real Gradle install instead by calling
`./gradlew` with those three variables exported.

### Requirements

| Tool | Version |
|---|---|
| JDK | 21 (pinned in `.toolchain/`) |
| Gradle | 9.4.1 (wrapper) |
| AGP | 8.13.0 |
| Kotlin | 2.2.20 |
| Compose BOM | 2026.01.00 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Build tools | 36.0.0 |

---

## Running the app

`minSdk` is 26, so any API 26+ phone or emulator works.

On first launch the app opens on **Welcome**. Tap **Masuk dengan Google**. When
`PAM_GOOGLE_WEB_CLIENT_ID` is empty the app runs in **Demo mailbox mode** (OI-01) and signs in
to a local demo session instead of Google, then seeds a synthetic February 2026 Jakarta dataset
so every screen is populated.

The demo dataset is not decoration — it is asserted in `DemoDatasetTest` and contains the SRS
B5 worked example, so the recap you see is arithmetically checkable:

```
inflow Rp5.850.000 · outflow Rp2.870.650 · net Rp2.979.350
housing net Rp900.000 after a linked refund · transfer Rp200.000 kept out of classified
1 uncategorized row · 2 manual rows · 1 excluded non-IDR row
```

A walkthrough that shows everything: Welcome → Consent (grant both) → Overview → tap a
category → Transactions → open a row → change its category → Analysis → generate → Budget.

### Enabling real Google / Gmail

Real OAuth needs a Google Cloud project; it cannot be provisioned from the repo. When you have
one:

1. Create an **Android** OAuth client for package `com.pam.recap` and your signing SHA-1.
2. Enable the **Gmail API** and configure the consent screen with the
   `https://www.googleapis.com/auth/gmail.readonly` scope (a Google *restricted* scope — OI-04
   applies before any real-user release).
3. Build with the client id:
   ```bash
   ./scripts/build.sh :app:assembleDebug -PPAM_GOOGLE_WEB_CLIENT_ID=xxxx.apps.googleusercontent.com
   ```
   Without it, the DI graph binds `FixtureGmailSource`; with it, `GoogleGmailSource` is used.
   Both implement the same `GmailSource` port (NFR-26), so nothing else changes.

The optional analysis backend is behind the same posture:
`-PPAM_ANALYSIS_BACKEND_URL=https://…` switches `OnDeviceAnalysisEngine` for
`RemoteAnalysisEngine`. The device never holds an LLM key (NFR-14), and the payload is the
allow-listed aggregate projection only (FR-35).

---

## Backend

`backend/` is a Node 24 + TypeScript service (Express 5, `node:sqlite`, zero native deps) that
owns everything the device must not: Google ID-token verification, revocable sessions, consent
enforcement, aggregate storage, the LLM call with output validation, quotas, and deletion.

```bash
cd backend
npm install
npm start                     # http://127.0.0.1:8080 — fake LLM, local SQLite, no config needed
bash scripts/smoke.sh         # 13-step happy path against the running server
npm test                      # 83 contract tests
```

Then build the app pointed at it:

```bash
cd pam-app
./scripts/build.sh :app:assembleDebug -PPAM_BACKEND_BASE_URL=http://10.0.2.2:8080
```

`10.0.2.2` is how the Android emulator reaches the host's `localhost`. The device and the backend
must agree on the contract in [`docs/06_BACKEND_CONTRACT.md`](docs/06_BACKEND_CONTRACT.md); the
build emits the app's exact request body to `app/build/contract/analysis-request.json`, which can
be replayed against a live server with `curl` to prove compatibility without a device.

Without `PAM_BACKEND_BASE_URL` the app uses the deterministic on-device engine and pushes nothing
off the device — the offline demo path is unchanged.

### What the backend never receives

No email body, subject, sender, Gmail message id, OAuth token, card PAN, customer name, merchant
name, private note or transaction row. Only the allow-listed monthly aggregate on
`AiAggregatePayload` plus the metrics it cites. The service rejects any other field (FR-35,
NFR-19) and stores even that as AES-256-GCM ciphertext (NFR-16).

## Going from demo to real Google

The app ships in demo mode on purpose (OI-01): no Google Cloud project exists yet. Everything
needed to switch it on — the two OAuth clients, the consent screen with the restricted
`gmail.readonly` scope, the exact registered package names and SHA-1 fingerprints, and a
troubleshooting table of every `DEVELOPER_ERROR` you can hit — is in
[`docs/05_REAL_GOOGLE_SETUP.md`](docs/05_REAL_GOOGLE_SETUP.md).

## Architecture

Single Gradle module, Hilt, MVVM with unidirectional state. The dependency rule is
`ui → domain ← data`; `domain` imports no Android, Room, OkHttp or Compose.

```
com.pam.recap
├── core/      money (exact minor units) · time (Asia/Jakarta months) · result · security
├── domain/    models · repository interfaces · ports (GmailSource, AnalysisEngine) · use cases
├── data/      Room+SQLCipher · DataStore · Gmail sources · analysis engines · seeds
└── ui/        theme tokens · design-system components · navigation · feature screens
```

Key decisions, each traceable to a requirement:

- **Money is `Long` minor units.** No `Double`/`Float` touches a money path (BR-04). Rounding
  happens at display only.
- **Month membership comes from the parsed event timestamp in Asia/Jakarta**, never the email
  receipt time; the search window carries a 3-day ingestion buffer (BR-01).
- **Gross vs classified.** Transfers stay in gross cash flow and leave classified income and
  expense (BR-02). A linked refund reduces its category's net for the refund month (BR-03).
- **Idempotent import.** A per-user HMAC fingerprint over the message id and transaction fields
  is the dedupe key; a collision is flagged for review rather than merged (FR-18).
- **Encrypted at rest.** SQLCipher with a passphrase wrapped by an Android Keystore AES-GCM key,
  excluded from backup, `FLAG_SECURE` on the window (NFR-16).
- **Replaceable integrations.** Gmail and the analysis engine sit behind ports with a real and a
  deterministic implementation, so provider failure never blocks local review or arithmetic
  (NFR-26).
- **Untrusted provider text.** Model output and email content are data, never instructions;
  validation rejects invented amounts and instruction-like prose (NFR-13, FR-37).

---

## Testing

```bash
./scripts/build.sh :app:testDebugUnitTest
```

The suite is the specification, not decoration. Highlights:

| Test | What it proves |
|---|---|
| `CashFlowCalculatorTest` | The SRS B5 worked example, transfer exclusion, refund netting, non-IDR exclusion, zero-base percentage |
| `LivinQrisParserTest` | Supported fixture, failed payment rejected, missing amount never zero, attachment-only and unknown template excluded, injected instructions ignored |
| `ImportMonthUseCaseTest` | Pagination, retry, duplicate suppression, month boundaries |
| `FingerprintFactoryTest` | Determinism, per-user scoping, field sensitivity |
| `AiPayloadBuilderTest` | Forbidden-field scan: no body, subject, sender, id, token, PAN or merchant |
| `AnalysisValidatorTest`, `RequestAnalysisUseCaseTest` | Invented totals rejected, one regeneration max, no call without consent, mid-run withdrawal discarded |
| `JakartaMonthTest` | Half-open month boundaries in Asia/Jakarta |
| `IdrFormatterTest` | Indonesian grouping, decimals, no minus sign |
| `PlanningGapTest` | Income 5.000.000 − savings 1.000.000 − limits 4.500.000 ⇒ shortfall 500.000 |
| `DemoDatasetTest` | The demo numbers above |

### Design review harness

A device emulator cannot run in this sandbox, so screens are rendered on the JVM with
Roborazzi + Robolectric native graphics:

```bash
./scripts/build.sh :app:recordRoborazziDebug --tests "com.pam.recap.screenshot.ScreenScreenshotsTest"
open app/build/screenshots/
```

Every screen is captured against the demo dataset. This is how the layout defects found during
development were caught — including amounts being *clipped* by narrow tiles, which is how
`Rp5.850.000` once rendered as `Rp5.850.00`. Amounts step their type size down rather than clip.

---

## Localisation

The UI is Indonesian (SRS §1.4) with an English translation.
`res/values/` = Indonesian (the default, used for any unmatched locale), `res/values-en/` =
English. Both files must keep identical key sets. Category names, consent disclosure, coverage
wording, error copy and accessibility labels are all resources — there are no user-visible
string literals in Kotlin.

---

## Known limitations and open points

These come from the SRS's own open-issues list (Appendix C) and are recorded rather than hidden:

- **OI-01** A real Google Cloud project, OAuth client and test-user allowlist are required to
  leave demo mode. The code path exists and is gated by `PAM_GOOGLE_WEB_CLIENT_ID`.
- **OI-02 / OI-06** The Livin sender and subject allow-list reflects one manually reviewed QRIS
  payment notification. MIME/HTML variants and language changes are not exhaustively covered.
- **OI-03** Incoming-transfer templates are unverified, so income is manual-entry only (A4).
- **OI-04** `gmail.readonly` is a Google restricted scope. OAuth verification, approved use and
  any security assessment must be resolved before any real-user release.
- **OI-07** The LLM route is not chosen. The default engine is deterministic and on-device; a
  remote engine exists behind configuration.
- No backend is implemented in this release: aggregates, analyses and budget plans live in the
  encrypted local database. The app therefore makes no claim about multi-device sync.
- CSV/PDF export, reminders and background sync are explicitly deferred (OI-12).
