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

**Terakhir diperbarui:** 21 November 2026 · branch `feat/go-backend` (paket B + slice Go)

| Area | Status |
|---|---|
| SRS v0.2 → v0.3 change record (CR-001, tanpa GCP) | ✅ selesai |
| Aplikasi Android — 13 destinasi, **241 test JVM** | ✅ jalan |
| Akun PAM (email + password, scrypt) menggantikan Google Sign-In | ✅ selesai |
| Onboarding: akun baru → Persetujuan, tolak Gmail → jalur manual | ✅ diperbaiki + tes |
| Gmail lewat **IMAP + App Password** | 🟡 MIME/HTML + fixture impor lulus; **Gmail nyata belum diverifikasi** |
| Impor Livin → baris transaksi → total bulanan | 🟡 fixture end-to-end lulus; Gmail nyata ⬜ |
| Backend Node (referensi/rollback) | ✅ **124 test** lulus, termasuk interop Go |
| **Backend Go (`backend-go/`)** | 🟡 C1 backend lokal PASS: auth/JWT, consent, finance CRUD, import batch, analysis canonical, deletion sweep; **42 test Go**, race/vet/build PASS |
| Android ↔ Go | 🟡 C2: refresh single-flight, REST client/DTO, deletion reauth; transaksi/import/summary/budget/analysis UI masih Room |
| **Kontrak v0.4 (SRS, API, data, UI, acceptance)** | ✅ draft review siap disahkan |
| **Migrasi libSQL `version: 3`** | ✅ teruji lokal `file:`; **cloud NOT RUN** |
| Implementasi v0.4 (JWT/refresh, CRUD server, admin, lampiran) | 🟡 C1 backend lokal; C2/D belum |
| LLM **Ollama lokal** (gratis, offline) | ✅ jalan |
| Fase 5 — backup harian + deploy | ⬜ belum |
| Fase 6 — APK release bertanda tangan | ⬜ belum |
| **Kepatuhan Pedoman Project Akhir 2026** | 🟡 kontrak v0.4 siap, implementasi belum |

**Selesai pada C1 lokal.** Temuan review ditutup: konfigurasi produksi menolak encryption key
development; create manual idempoten dengan key/hash/result atomik; analisis memakai payload
canonical server, validator FR-37, guard consent/deletion/revisi saat finalisasi; usage/biaya
tercatat dan ceiling harian; import batch idempoten; deletion sweep + revoke sesi. **42 tes Go**
lulus. **Selesai pada paket B + slice Go.** Kontrak v0.4 dikonsolidasikan: SRS review, API v0.4
(JWT/refresh, transaksi/kategori CRUDS, impor batch, ringkasan server, anggaran, lampiran, admin),
DDL/migrasi `version: 3` yang additive, ERD, perubahan UI, T01–T11 dan 25 pemeriksaan CRUDS.
Backend Go berjalan dengan `net/http`, `database/sql`, driver libSQL; password/AES-GCM dibuktikan
dua arah dengan fixture Node; migrasi 1–7 dan constraint diuji lokal. Android di emulator API 36
berhasil register → login → consent → Ringkasan terhadap Go.

**Selesai pada paket A (dipertahankan).** Gate onboarding, tombol **Lanjut tanpa menghubungkan
Gmail** (hanya Gmail yang dipaksa `DECLINED`; pilihan AI yang sudah dibuat dipertahankan), batas sel
HTML MIME, propagasi jumlah lampiran, dan fixture impor end-to-end.

**Belum selesai.** C2 baru mencakup session refresh, REST client/DTO, dan deletion reauth; UI
transaksi/import/summary/budget/analysis masih Room. Admin/lampiran (FR-65/FR-59) tetap tahap D.
Lihat [`14_C2_INTEGRASI_ANDROID.md`](docs/14_C2_INTEGRASI_ANDROID.md). Cloud/engine, Gmail
nyata, perangkat fisik, dan provider nyata tetap NOT RUN. Impor Gmail nyata belum diuji ulang.
Kredensial database **uji** Turso belum tersedia, jadi cloud acceptance dan restore drill **NOT
RUN**. Klaim performa Go belum dibuat tanpa benchmark.

**Bukti emulator API 36 (paket A, 21 November 2026):** profil lokal baru → Persetujuan tanpa
interaksi tambahan; tolak Gmail → Ringkasan; impor kotak masuk demo **28 cocok / 25 tersimpan / 3
dilewati**; total Februari berubah `Rp0` → `Rp3.896.150`; impor ulang **0 tersimpan / 25 duplikat**;
Back dan sign-out tidak memulihkan layar terlindungi.

### Kontrak v0.4 (paket B)

| Dokumen | Isi |
|---|---|
| [`12_SRS_v0.4_REVIEW.md`](docs/12_SRS_v0.4_REVIEW.md) | SRS review: scope, lokasi data, peran, FR-57–63, ERD, privasi, cutover, riwayat |
| [`06_BACKEND_CONTRACT.md`](docs/06_BACKEND_CONTRACT.md) | endpoint, request/response/error, JWT/refresh, atomisitas, pencarian, lampiran, admin |
| [`10_MIGRASI_PEDOMAN.md`](docs/10_MIGRASI_PEDOMAN.md) | DDL kandidat `version: 3`, acceptance lokal, cloud NOT RUN, rollback |
| [`01_SCOPE_TRACEABILITY.md`](docs/01_SCOPE_TRACEABILITY.md) | traceability, T01–T11, 25 CRUDS, rubrik D.1–D.10 |
| [`03_ARCHITECTURE.md`](docs/03_ARCHITECTURE.md) | target remote Go, perubahan UI, editor field dinamis, lampiran lokal |
| [`13_GO_BACKEND_MIGRATION.md`](docs/13_GO_BACKEND_MIGRATION.md) | toolchain, struktur Go, matriks parity, interop, auth transition, rollback |
| [`11_SRS_CHANGE_v0.4.md`](docs/11_SRS_CHANGE_v0.4.md) | CR-002, riwayat revisi, status sign-off |

**Sudah disetujui:** cloud database gratis dan **pergantian backend ke Go** (21 November 2026).
Implementasi Go dimulai; Node tetap rollback sampai parity diterima.

**Siap disahkan (belum disetujui):** transaksi/kategori/anggaran/metadata lampiran di Turso/libSQL;
mode offline terbatas dan kebutuhan internet; dua peran `USER`/`ADMIN` dengan admin terakhir
dilindungi; lampiran lokal + metadata server; cutover bertahap dan rollback dengan rekonsiliasi.

**Keputusan tersisa sebelum aktivasi data pengguna** (satu blok):

1. Sign-off paket v0.4 dan urutan cutover Go (Node tetap rollback sampai parity diterima).
2. Hosting API demo/24 jam + URL HTTPS (sekarang laptop).
3. Retensi data server setelah akun dihapus (usulan: 30 hari backup, data aktif segera).
4. Apakah rencana anggaran boleh dihapus hard (usulan) dan apakah admin boleh mengubah email.
5. Kredensial database Turso **uji** terpisah + konfirmasi engine `libsql://`.

### Kepatuhan pedoman

**Pembaruan rancangan 21 November 2026.** Pemilik menetapkan cloud database gratis sebagai arah project. Lead memilih **Turso Cloud Free/libSQL** yang sudah dipakai backend. Rancangan terbaru mengganti usulan MySQL di dokumen sebelumnya. Target berikutnya meliputi JWT/refresh, peran, CRUDS, file lokal dan penghapusan Room dari varian penilaian. Kode saat ini masih v0.3. [Rancangan cloud](docs/10_MIGRASI_PEDOMAN.md) · [CR-002](docs/11_SRS_CHANGE_v0.4.md)

Pedoman mata kuliah (`PEDOMAN PROJECT AKHIR PAM 2026`) menetapkan syarat yang menentukan nilai.
Kontrak v0.4 sudah menutup desainnya; implementasi belum:

| Butir | Syarat | Kondisi |
|---|---|---|
| D.1 | Access token **JWT** + **refresh token** | 🟡 kontrak + migrasi; C1 |
| D.2 | **Role-based UI**, minimal 1 peran | 🟡 USER/ADMIN dirancang; C1/D |
| D.3 | Navigasi Compose, alur awal benar | ✅ transisi akun baru dan back stack diperbaiki + tes |
| D.5 | 4 entitas + field dinamis | 🟡 kontrak; D |
| D.6 | **5 fitur CRUDS penuh** | 🟡 25 pemeriksaan didefinisikan; C2/D |
| D.8 | Database minimal MySQL; **Room dilarang** | 🟡 Turso/libSQL + migrasi lokal; Room keluar pada D |
| D.9 | **Simpan & baca file lokal** | 🟡 lifecycle lampiran dirancang; D |
| C.1 | Progress **mingguan** di README + push rutin | 🟡 progress dicatat per paket |

Rancangan migrasi: [`docs/10_MIGRASI_PEDOMAN.md`](docs/10_MIGRASI_PEDOMAN.md) ·
Perubahan SRS: [`docs/11_SRS_CHANGE_v0.4.md`](docs/11_SRS_CHANGE_v0.4.md) ·
SRS review: [`docs/12_SRS_v0.4_REVIEW.md`](docs/12_SRS_v0.4_REVIEW.md)

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

`backend-go/` is the target Go 1.27.4 service (`net/http`, `database/sql`, libSQL driver). The
existing `backend/` Node service remains the behavioural reference and rollback path during the
migration. The service owns everything the device must not: account authentication (scrypt),
revocable JWT sessions + rotated refresh tokens, consent enforcement, account-scoped finance data,
the LLM call with output validation, quotas, and deletion.

```bash
cd backend-go
../scripts/go.sh run ./cmd/api       # http://127.0.0.1:8080 — fake LLM, local libSQL file
../scripts/go.sh test ./...          # 42 Go tests, including Node interop fixtures
../scripts/go.sh test -race ./...

# Node reference/rollback only (separate database, never both runners on one file)
cd ../backend && npm install && npm test
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
[`docs/09_SRS_CHANGE_v0.3.md`](docs/09_SRS_CHANGE_v0.3.md). The v0.4 target moves transaction rows
from Room to Turso over REST; until the C1/C2 cutover, the app still runs the v0.3 local storage.
See [`docs/12_SRS_v0.4_REVIEW.md`](docs/12_SRS_v0.4_REVIEW.md).

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
├── data/      Room (v0.3, keluar pada D) · remote REST (target) · DataStore · Gmail · AI · seeds
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
cd backend && npm test                 # Node reference + interop fixtures
cd backend-go
../scripts/go.sh test ./...            # Go target
../scripts/go.sh test -race ./...      # concurrency
../scripts/go.sh vet ./... && ../scripts/go.sh build ./cmd/api
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
| `ConsentViewModelTest` | Untouched switch is not a decision; explicit decline records Gmail DECLINED and preserves an already-chosen AI grant |
| `ImapMimeTest` (bound) | An unknown-size message stops at the MIME byte bound instead of buffering first |
| `schemaV04.test.ts` (backend) | v0.4 DDL: cross-owner FK, per-owner fingerprint, amount/JSON checks, soft delete, category reassign, last admin, refresh rotation, index pagination |
| `goInterop.test.ts` (Node) + Go interop tests | Node verifies Go scrypt/AES fixture and Go verifies Node fixtures, with owner AAD binding |
| Go `httpapi` tests | register/login/refresh/replay/concurrency, consent, transaction CRUD, cross-owner isolation, budget/reassign, analysis allowlist/cache/deletion |
| Go `finance` test | SRS B5 worked example and unlinked credit rules |
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
