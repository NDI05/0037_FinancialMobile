# PAM — AI Assisted Monthly Financial Recap

Android app that turns **Livin by Mandiri email notifications** into a deterministic monthly
cash-flow recap, lets the user correct categories, and — only with separate, explicit consent —
produces an evidence-linked analysis and a next-month budget suggestion.

Built to the specification in `PAM_SRS_v0.2_Gmail_Livin.pdf` (PAM-SRS-001) with the visual
language of the supplied reference design.
New to the code? Read [`docs/01_SCOPE_TRACEABILITY.md`](docs/01_SCOPE_TRACEABILITY.md) first —
it maps every requirement ID to where it is implemented.


---

## Progress

Bagian ini diperbarui setiap kali ada kemajuan. Kalau ragu apakah masih akurat, `git log` adalah
kebenarannya. Sisa README ini memakai bahasa Inggris sebagai dokumentasi teknis.

**Terakhir diperbarui:** 21 November 2026 · branch `fix/onboarding-import-stability` dari `c10ca67`

| Area | Status |
|---|---|
| SRS v0.2 → v0.3 change record (CR-001, tanpa GCP) | ✅ selesai |
| Aplikasi Android — 13 destinasi, 223 test JVM | ✅ jalan |
| Akun PAM (email + password, scrypt) menggantikan Google Sign-In | ✅ selesai |
| Onboarding: akun baru → Persetujuan, tolak Gmail → jalur manual | ✅ diperbaiki + tes |
| Gmail lewat **IMAP + App Password** (tanpa Google Cloud) | 🟡 MIME/HTML terbaca; impor fixture lulus; **Gmail nyata belum diverifikasi** |
| Impor Livin → baris transaksi → total bulanan | 🟡 fixture end-to-end lulus; Gmail nyata ⬜ |
| Backend Node + **Turso** (database cloud), 111 test | ✅ jalan |
| LLM **Ollama lokal** (gratis, offline) | ✅ jalan |
| Fase 5 — backup harian + deploy | ⬜ belum |
| Fase 6 — APK release bertanda tangan | ⬜ belum |
| **Kepatuhan Pedoman Project Akhir 2026** | 🟡 dianalisis, rancangan selesai, belum dieksekusi |

**Selesai pada tahap ini.** (1) Gate onboarding: profil baru langsung ke Persetujuan tanpa Back,
sesi berakhir tidak memulihkan layar terlindungi, dan Persetujuan tetap bisa dibuka lagi untuk
mengubah keputusan. (2) Tombol **Lanjut tanpa menghubungkan Gmail** mencatat penolakan secara
eksplisit. (3) Impor: batas sel tabel HTML kini dipertahankan, sehingga label tanpa titik dua dan
nilai di sel berikutnya terbaca; lampiran di dalam MIME tak bertipe ikut dihitung. Fixture impor
menghasilkan 25 baris dan total Februari, dan impor ulang menghasilkan 0 baris baru.

**Belum selesai.** Impor ke mailbox Gmail sungguhan belum diuji ulang pada kode ini. Pemilik perlu
menjalankan langkah di bagian *Real Gmail check* dan mengirim hasil yang sudah disanitasi. Bukti
historis "20 ditemukan, 0 tersimpan" berlaku untuk build sebelum perbaikan ini.

**Bukti emulator API 36 (21 November 2026):** profil lokal baru → Persetujuan tanpa interaksi
tambahan; tolak Gmail → Ringkasan; impor kotak masuk demo **28 cocok / 25 tersimpan / 3 dilewati**;
total Februari berubah `Rp0` → `Rp3.896.150`; impor ulang **0 tersimpan / 25 duplikat**; Back dan
sign-out tidak memulihkan layar terlindungi.

### Kepatuhan pedoman

**Pembaruan rancangan 21 November 2026.** Pemilik menetapkan cloud database gratis sebagai arah project. Lead memilih **Turso Cloud Free/libSQL** yang sudah dipakai backend. Rancangan terbaru mengganti usulan MySQL di dokumen sebelumnya. Target berikutnya meliputi JWT/refresh, peran, CRUDS, file lokal dan penghapusan Room dari varian penilaian. Kode saat ini masih v0.3. [Rancangan cloud](docs/10_MIGRASI_PEDOMAN.md) · [CR-002](docs/11_SRS_CHANGE_v0.4.md)

Pedoman mata kuliah (`PEDOMAN PROJECT AKHIR PAM 2026`) menetapkan syarat yang **bertentangan**
dengan SRS v0.3, dan pedoman yang menentukan nilai. Enam butir belum terpenuhi:

| Butir | Syarat | Kondisi |
|---|---|---|
| D.3 | Navigasi Compose, alur awal benar | ✅ transisi akun baru dan back stack diperbaiki + tes |
| D.8 | Database minimal MySQL; **Room dilarang** | ❌ masih Room + SQLCipher |
| D.1 | Access token **JWT** + **refresh token** | ❌ token opaque |
| D.2 | **Role-based UI**, minimal 1 peran | ❌ belum ada |
| D.9 | **Simpan & baca file lokal** | ❌ belum ada |
| D.6 | **5 fitur CRUDS penuh** | ⚠️ Delete belum ada |
| C.1 | Progress **mingguan** di README + push rutin | 🟡 dimulai hari ini |

Rancangan migrasi: [`docs/10_MIGRASI_PEDOMAN.md`](docs/10_MIGRASI_PEDOMAN.md) ·
Perubahan SRS: [`docs/11_SRS_CHANGE_v0.4.md`](docs/11_SRS_CHANGE_v0.4.md)

---

## What it does

| Capability | Requirement |
|---|---|
| PAM account sign-in (email + password, scrypt) with an app session gate | FR-01, FR-05, FR-51 |
| Separate mailbox and AI consent, recorded with notice version and timestamp | FR-03, FR-04, FR-49 |
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

On first launch the app opens on **Welcome**. With no backend configured, tap **Lanjut tanpa
akun** to run the offline demo: it creates a local profile and seeds a synthetic February 2026
Jakarta dataset so every screen is populated.

With a backend configured, **Buat akun** registers an email + password account (`pam-dev` is the
development invite code) and the sample rows are **never** seeded — a server account holds only
real data.

The demo dataset is not decoration — it is asserted in `DemoDatasetTest` and contains the SRS
B5 worked example, so the recap you see is arithmetically checkable:

```
inflow Rp5.850.000 · outflow Rp2.870.650 · net Rp2.979.350
housing net Rp900.000 after a linked refund · transfer Rp200.000 kept out of classified
1 uncategorized row · 2 manual rows · 1 excluded non-IDR row
```

A walkthrough that shows everything: Welcome → Consent (grant both) → Overview → tap a
category → Transactions → open a row → change its category → Analysis → generate → Budget.

### Connecting a real mailbox (App Password)

Gmail is read over **IMAP with an App Password** — no Google Cloud project, no OAuth client, no
verification review. The user creates a 16-character app password and pastes it into
**Pengaturan → Kelola kotak masuk**.

1. Enable **2-Step Verification** on the Google account (without it Google hides App Passwords).
2. Create one at <https://myaccount.google.com/apppasswords> and copy the 16 characters.
3. Paste it into the app. It is validated with a real IMAP login **before** being stored, then
   wrapped by an Android Keystore key and never sent to the backend.

The app reads INBOX read-only and issues no mutating command (FR-55). The credential is a
long-lived one that also permits sending mail — a risk the owner accepted in CR-001 (OI-13).

The optional analysis backend is behind the same posture:
`-PPAM_ANALYSIS_BACKEND_URL=https://…` switches `OnDeviceAnalysisEngine` for
`RemoteAnalysisEngine`. The device never holds an LLM key (NFR-14), and the payload is the
allow-listed aggregate projection only (FR-35).

---

## Backend

`backend/` is a Node 24 + TypeScript service (Express 5, `node:sqlite`, zero native deps) that
owns everything the device must not: account authentication (scrypt), revocable sessions, consent
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

## Going from demo to production

Demo mode exists so the app is fully usable with no account, no mailbox and no network. To leave
it: register a PAM account, connect a mailbox with an App Password, and point the build at the
backend. No Google Cloud project is involved anywhere.

The decisions that removed Google Cloud from the architecture (IMAP, local accounts, hybrid
storage, Turso) are recorded in [`docs/08_ROADMAP.md`](docs/08_ROADMAP.md) and
[`docs/09_SRS_CHANGE_v0.3.md`](docs/09_SRS_CHANGE_v0.3.md).

## Cost

The entire stack runs at **zero cost, with no credit card**: mail is read over IMAP with a free
App Password (no Cloud project, no OAuth verification), the LLM can be a local Ollama model with
no account at all, the cloud database is Turso's free tier, and the backend is a single Node
process that runs on a laptop. [`docs/07_FREE_STACK.md`](docs/07_FREE_STACK.md) has the per-component cost
table, the three free LLM presets and the fully-offline fallback.

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
| `LivinMimeImportTest` | Untyped MIME → HTML table (no colon, value in the next cell) → parsed row → monthly total; re-import dedupe; owner separation; malformed amount, failed payment and attachment-only add nothing |
| `ImapMimeTest` | Plain/HTML/attachment shapes, untyped root recovery, table-cell boundary |
| `ImportMonthUseCaseTest` | Pagination, retry (partial and complete), duplicate suppression, timeout, month boundaries |
| `GateRoutingTest` | New profile → Consent, saved decision → Overview, ended session never keeps a protected screen, Consent re-openable |
| `ConsentViewModelTest` | Untouched switch is not a decision; explicit decline records DECLINED for Gmail and AI |
| `FingerprintFactoryTest` | Determinism, per-user scoping, field sensitivity |
| `AiPayloadBuilderTest` | Forbidden-field scan: no body, subject, sender, id, token, PAN or merchant |
| `AnalysisValidatorTest`, `RequestAnalysisUseCaseTest` | Invented totals rejected, one regeneration max, no call without consent, mid-run withdrawal discarded |
| `JakartaMonthTest` | Half-open month boundaries in Asia/Jakarta |
| `IdrFormatterTest` | Indonesian grouping, decimals, no minus sign |
| `PlanningGapTest` | Income 5.000.000 − savings 1.000.000 − limits 4.500.000 ⇒ shortfall 500.000 |
| `DemoDatasetTest` | The demo numbers above |

### Real Gmail check (owner-run)

Fixture evidence does not prove a live mailbox. To close that gap on a **demo account**, run this
and report only sanitized results:

1. Build and install the debug APK, sign in, and grant Gmail consent.
2. Settings → *Kelola kotak masuk* → enter the demo Gmail address and its 16-character App
   Password → *Hubungkan kotak masuk*. (Never paste the password into a prompt, issue or commit.)
3. Open the month that has Livin notifications and press *Impor sekarang*.
4. Send back only: app commit, job state, and the four counters (`matched`, `saved`, `skipped`,
   `unresolved`), plus the skip **reason codes** shown on the result screen.
5. If it still fails, `adb logcat -d | grep -E "ImportMonth|ImapGmail"` contains integer-only
   diagnostics (`chars`, `plain`, `html`, `nom_start`, `nom_colon`, `next_len`). Send those numbers
   only — never the email body, sender address, message id, or any amount.

### Design review harness

Screens are also rendered on the JVM with Roborazzi + Robolectric native graphics, so layout can
be reviewed without a device:

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

- **OI-13** The IMAP App Password also permits sending mail and never expires on its own. It is
  stored Keystore-wrapped, never leaves the device, and is cleared on sign-out.
- **Parser coverage** Only the notification layouts exercised by fixtures are understood. The
  measured table layout (label without a colon, value in the next cell) is now covered and the
  import path is proven with anonymized MIME fixtures, but a live Gmail run is still required
  before claiming real-mailbox success.
- **OI-02 / OI-06** The Livin sender and subject allow-list reflects one manually reviewed QRIS
  payment notification. MIME/HTML variants and language changes are not exhaustively covered.
- **OI-03** Incoming-transfer templates are unverified, so income is manual-entry only (A4).
- **OI-07** The LLM route is not chosen. The default engine is deterministic and on-device; a
  remote engine exists behind configuration.
- Aggregates, analyses and budget plans live in the Turso cloud database; the app therefore can
  move between devices once signed in. Transaction rows stay local by design (FR-35).
- CSV/PDF export, reminders and background sync are explicitly deferred (OI-12).
