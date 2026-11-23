# UAT S3/S4 device evidence — Android acceptance (23 Nov 2026)

Owner: worker C (`work/se-android-acceptance`, worktree `pam-wt-accept`). Device: `emulator-5554`
only (API 36, 1080×2400, dynamic colour off). Backend: UAT Go API on `http://127.0.0.1:18090`
(`adb reverse tcp:18090`), contract merge `daa5f36`. Account: disposable synthetic owner
`e2e.accept.c.*@pam-uat.test` in the UAT DB (never the real owner). All raw dumps/log lines are in
`/tmp/accept-evidence/` on the build host.

APK actually installed for this run: `app/build/outputs/apk/debug/app-debug.apk`,
debug `1.0.0-debug`, base URL `http://127.0.0.1:18090`,
SHA-256 `cc6eef9534aafda9f911893348fcaf65b6336cff5d30738ee96e28f7aea18c7d`.
Code under test: `f56020c` + the `attributes:null` decoder fix in this commit. APK was rebuilt and
reinstalled after every code change; evidence below is from that APK.

## Verification gates

- `:app:lintDebug` (after the reviewer fixes): **0 errors**, 114 warnings, 1 hint
  (`app/build/reports/lint-results-debug.xml`). `values-en/strings.xml` now carries all
  `budget_history_*`, `category_more_hint` keys, so `MissingTranslation` is gone.
- Targeted JVM tests (`--rerun-tasks`): `PamApiClientTest`, `CategoryManagementViewModelTest`,
  `BudgetPlanServerTest` — **32 tests, 0 failures**; includes the new generation-guard tests
  (fast-vs-slow response, reload invalidates in-flight search), debounce burst (single request,
  null cursor) and the C15 `totalLimitMinor`/`gapMinor` parse assertion.
- Full Android suite on this branch HEAD (`3a0ff3b`): **462 tests / 63 classes / 0 failures**
  (23 Nov; the earlier `452` figure was a stale pre-fix run).
- Reviewer findings P3-1..P3-6 addressed in `f56020c`; P3-3 chose the truncation indicator over
  cursor append (documented in that commit) — the server cursor is still surfaced through
  `hasMore`, never silently dropped.

## Device closure evidence

### C01 manual create — PARTIAL (device), not closed
Form entry through the UI is reachable ("Tambah transaksi" → manual entry, dumps
`c01_form_filled.xml`). On save the app does call the server: four `POST /v1/transactions` were
observed at 17:29:27/28/30/41 (`/tmp/pam-uat-18090-fixed.log`), all **400 invalid_request** with no
detail in the allow-list log. The filled state came from restored instance state, so the request
carried a concatenated amount/description (`25000310003100` / `UJICUJIC01manUJIC01okUJual`), which
the server correctly rejected. A clean create attempt after an app restart did not send a POST
(typing into a freshly focused Compose field did not surface in the uiautomator tree before the
save tap). **Manual create + idempotent retry is therefore still not proven on device**; API/unit
coverage for C01/C03 remains as stated in the matrix. This is a test-harness/IME limitation on the
emulator, not a proven product defect — the request path and server validation are exercised.

### C04 delete — PASS (emulator)
Opened transaction `UJIACC 2026-11 #025` from the list, scrolled to the destructive panel
("Transaksi yang dihapus tidak bisa dikembalikan."), tapped "Hapus", confirmed
("Hapus transaksi ini?" → "Hapus"). Server: `DELETE /v1/transactions/{id}` → **204** at 17:31:43
(`c04_server_delete_log.txt`); the UI returned to the list. Reload/API check: November now has
**84 rows** instead of 85 and description `#025` is absent; the re-dumped list has zero `#025`
occurrences (`c04_after_delete_list.xml`) — no resurrection. Evidence: `c04_delete_action.xml`,
`c04_delete_dialog.xml`.

### C05 search + next page — PASS (emulator)
`q=UJIACC` on the seeded month. Dump-to-dump mapping:
- `c05_p1.xml`: page 1 shows "50 transaksi" and "Server memeriksa 51 transaksi."; no load-more label
  is exposed in this dump.
- `c05_s4.xml`: same page-1 counts **plus** the "Muat lebih banyak" control (this is the dump that
  contains the load-more affordance).
- `c05_p2.xml`: after tapping load-more, "85 transaksi" and "Server memeriksa 86 transaksi."
  (page 2 appended; no "Muat lebih banyak" in this dump).
- `c04_after_delete_list.xml`: the footer **"50 transaksi dimuat; masih ada halaman berikutnya."**
  appears here (it is the post-delete list dump, not `c05_p1.xml`).

The page-2 request used only the cursor from page 1. The prior-session query-change/cursor-reset
observation ("0 transaksi"/"Server memeriksa 85 transaksi.") is narrative from that session, not a
saved dump. Log evidence for the list is route-level only (the allow-list request log records
`GET /v1/transactions` without the query string/cursor); matching GETs were at 16:52:37, 16:53:36,
16:54:40.

### C10 category search — PASS (emulator, new live contract)
Category screen loads with "11 kategori" and the "Makanan" row present (`c10_load.xml`, written
17:27). Typing `mak` in "Cari kategori" narrows the grid to "1 kategori" with the
"Makanan / food / Bawaan" row (`c10_search.xml`, written 17:27, contains the query text `mak`).
Caveat on log precision: the backend allow-list request log records only the route
`GET /v1/categories` (no query string), so the exact `?limit=50&q=mak` URL is **not** citable
from the log — route-level 200s were observed around the dumps at 17:21:06/17:21:17/17:21:30/
17:21:57 and later at 17:29:29/17:29:40. The dump itself is the evidence that the query narrowed
the result to one row.

### C12 actual vs budget — PASS (emulator)
Budget screen (server mode, "Rencana server · revisi 0") renders the budget card and the
`REALISASI VS RENCANA` block: "Makanan Rp476,27 dari Rp25.000", "Transportasi Rp396,88 dari
Rp12.000", "Belanja Rp494,08 dari Rp9.000", "Sisa bulan ini Rp3.815,78", plus the server summary
freshness line ("Ringkasan server terkini."). Dumps `c12_c15_budget.xml`, `c15_history_loaded.xml`.
Note: server amounts are minor units in the contract, so the synthetic plan seeded with
`expectedIncomeMinor=12_000_000` renders as `Rp120.000`; the *relationship* (limit vs actual, gap,
remaining) is what C12 exercises and it is consistent with the stored plan.

### C15 plan period search — PASS (emulator, new live contract)
Tapping "Muat riwayat rencana" triggers the budget-plan history request; the allow-list log shows
a route-level `GET /v1/budget-plans` → **200** at 17:29:02 (`c15_server_log.txt`), and the exact
`from`/`to` query string is not recorded by that log. The rendered card states the period
explicitly: "1 rencana pada Desember 2025 sampai November 2026."
row "November 2026 · revisi 0", and — from this fix commit — "limit Rp46.000 · selisih Rp44.000"
(`totalLimitMinor`/`gapMinor` now mapped and rendered; `c15_history_loaded.xml`). The truncation
indicator is wired but correctly not triggered (1 stored plan). Opening a plan from history remains
a documented follow-up (button not added in this pass).

### C17 attachment metadata + read after restart — PASS (emulator)
Created one metadata attachment through the API on a disposable transaction
(`bukti-awal.pdf`, `application/pdf · 1 KB`, relative `storageRef`). Opening the transaction
detail shows "Lampiran", the file row and the metadata-only note
("Berkas ini tetap di perangkat; hanya metadata yang disinkronkan.") —
`c17_attachment_metadata.xml`. After `am force-stop` + relaunch, the same attachment and its
renamed name are still rendered (`c17_attachment_after_restart.xml`), proving read-after-restart.

### C18 attachment rename — PASS (emulator)
Renamed through the sheet (pencil → "Nama berkas" → "Simpan"). Server:
`PATCH /v1/attachments/{id}` → **200** at 17:34:03 (`c18_server_patch_log.txt`), notice
"Nama lampiran diperbarui.", row now `bukti-rename-accept.pdf` (`c18_attachment_renamed.xml`).
Revision CAS is exercised by the same request.

### C20 attachment name search — PASS (emulator)
Submitting `zzz` shows "Tidak ada lampiran yang cocok dengan pencarian."
(`c20_attachment_search_empty.xml`); submitting `rename` shows the row again
(`c20_attachment_search_match.xml`).

### C24 / C25 admin users — NOT RUN on device (API/unit coverage only)
No device walk for the admin list: promoting the disposable synthetic account to ADMIN and
re-logging in would invalidate the session used by the rest of this evidence. C24/C25 remain
covered by the handler/unit tests cited in `docs/evidence/UAT_S4_ADMIN_2026-11-23.md` and the
matrix; **do not count this as device PASS**. Last-admin guard and role/status/cursor filters are
API/unit-level only in this round.

## Gaps / deviations
- C01 (manual create + idempotent retry) not proven on device (emulator text-entry limitation
  described above); C04 delete path is proven, create retry is not.
- C24/C25 not device-walked in this round (see above).
- `open plan from history` for C15 is deferred (follow-up), and history truncation was not
  triggerable with one stored plan.
- Amount unit convention: UI renders server minor units through `Money` (hundredths), so seeded
  synthetic plan/transactions show values 100× larger in minor terms; this matches the app's
  existing server mapping and is not changed here.
- One ANR was observed on the emulator after a long scripted interaction burst
  (`Application Not Responding: com.pam.recap.debug`); the app recovered after force-stop/relaunch
  and all evidence above was re-captured afterwards. Treated as emulator load, not a reproducible
  product defect; noted for transparency.
