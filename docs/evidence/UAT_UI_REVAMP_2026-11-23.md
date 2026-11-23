# UAT UI Revamp — Ringkasan, top bar, status sinkronisasi (2026-11-23)

Branch: `work/ui-summary-sheet` (from `feat/c2-android` @ `112ddb3`).
Device: `emulator-5556` (Medium_Phone_S25, Android 14) with the debug build installed and signed in
with a synthetic UAT account. Live backend at `http://127.0.0.1:18090` (`/readyz` = ready).
Screenshots/dumps: `docs/evidence/assets/ui_revamp_2026-11-23/`.

Status legend: **PASS** = observed on device and/or unit test; **PARTIAL** = one half observed, the
other half NOT RUN or covered only by Robolectric/unit test; **NOT RUN** = not observed, reason
given. No row is claimed without a matching artefact.

## 1. Ringkasan: banner, import CTA, "Sumber data" card, gear removed

| Item | Status | Evidence |
| --- | --- | --- |
| Banner "Tidak ada email Livin yang cocok…" removed | PASS | baseline `baseline_ringkasan_pre_revamp.xml` (still shows the banner + "Impor sekarang") vs `ringkasan_new_393dp.xml` (no banner, no CTA) |
| "Impor sekarang" CTA removed | PASS | same dumps; string resources `overview_import_now`/`overview_import_eyebrow` removed and `grep` returns 0 references |
| Bottom "Sumber data" card + import button removed | PASS | `ringkasan_new_393dp.xml` has no such node; the sheet is now the single entry point |
| Top-bar gear removed (Settings via bottom nav) | PASS | `ringkasan_new_393dp.xml` has no gear `content-desc`; Settings remains a bottom-nav tab |
| Honest month-empty copy kept | PASS | `EmptyMonthCard` still renders the genuine empty state when coverage says the month has no matched mail; it is not reachable on this UAT account (month has data) — logic covered by `UpdateStatusMapperTest`/overview tests |

## 2. Top app bar centring

Title is measured against the full screen width, with the back well and trailing actions drawn on
top (no weighted `Row`), so chrome never moves the title.

| Case | Status | Evidence |
| --- | --- | --- |
| 360 dp (density 480) | PASS | `ringkasan_360dp.png/.xml`; title glyph ink 411–670 px, centre 540.5 px vs screen centre 540 px (Δ 0.17 dp) |
| 393 dp (density 440) | PASS | `ringkasan_new_393dp.png/.xml`; glyph centre 540.5 px (Δ 0.18 dp) |
| 412 dp (density 420) | PASS | `ringkasan_412dp.png/.xml`; glyph centre 540.0 px (Δ 0 dp) |
| 200 % font at 360 dp | PASS | `ringkasan_360dp_font200.png/.xml`; one-line title, glyph ink 325–757 px, centre ≈ 541 px vs 540 px |
| Back button + long title do not move the centre | PARTIAL | Robolectric test `UpdateStatusTopBarTest.the title centre does not move when back and trailing controls appear` asserts the bare and chromed centres both equal the root centre; no device page with a back button was captured before the time-box expired |

## 3. Status icon and transient label

| Item | Status | Evidence |
| --- | --- | --- |
| Icon tappable, ≥ 48 dp, opens the "Status pembaruan" sheet | PASS | dumps show the status action node (144 px @ 2.727 = 52.8 dp) with `content-desc` "Buka status pembaruan: …"; tapping it opens the sheet (`status_sheet_idle_393dp.xml`) |
| Label appears on status change, holds, fades; icon stays | PARTIAL | `UpdateStatusPillTest` drives the compose clock (fade in 220 ms, hold 2.5 s, fade out 420 ms) and the pill remains; on-device timing of a sub-second job was not frame-capturable (see rows below) |
| Recomposition does not replay the animation | PASS | `UpdateStatusPillTest.the transient word appears once and a recomposition does not replay it` — after 4 s the label is gone and an unrelated state change does not bring it back |
| TalkBack content description carries the full status after the visual fade | PARTIAL | `content-desc` strings are present in every device dump (e.g. "Buka status pembaruan: Perlu perhatian. Ada hal yang perlu perhatian.") and the Robolectric test asserts the description survives the fade; a live TalkBack announcement is **NOT RUN** — TalkBack was enabled on the emulator but its first-run notification-permission dialog blocked interaction (`talkback_blocked_permission_dialog.png/.xml`) |

## 4. Single "Status pembaruan" sheet (extends `UpdateStatusSheet`)

| Item | Status | Evidence |
| --- | --- | --- |
| Sheet opens showing only status; no import/Azure call | PASS | `status_sheet_idle_393dp.xml` — period, last attempt, last success, trigger; no spinner and no new job on open |
| Period shown for the current month | PASS | "Periode: November 2026" in every sheet dump, including after process recreation |
| "Sinkronkan sekarang" in "Data transaksi" | PASS | `status_sheet_idle_393dp.xml` |
| Manual/auto/repeated taps share one single flight | PARTIAL | PASS in `SyncSessionCoordinatorTest` (repeat taps, state outlives caller). On device the flight finished in < 1 s, so a live "disabled while running" frame is **NOT RUN** |
| Real progress bar when total known, indeterminate when unknown | PARTIAL | Roborazzi `16-sheet-running-progress.png` / `17-sheet-running-unknown-total.png` + mapper tests; live device progress frame **NOT RUN** (job < 1 s) |
| Close sheet / navigate away → job continues at app scope | PASS | device: closed/reopened after a sync and the same result is shown with no new flight (`status_sheet_reopened_393dp.xml`); coordinator lives in `@Singleton` scope (`SyncSessionCoordinatorTest.state outlives the caller`) |
| Reopen shows the same progress/result | PASS | `status_sheet_reopened_393dp.xml` matches the post-run dump; no restart and no additional job |
| Minimised (Home) → relaunch keeps the same result | PASS | `status_sheet_minimized_relaunch_393dp.xml` |
| Result counts: matched/saved/duplicate/skipped/needs-review | PASS | post-run dump shows "Email cocok pada impor ini: 0" and "Tersimpan 0, duplikat 0, dilewati 0, belum terbaca 0." (always rendered, zero explained) |
| Import metrics separate from AI category queue | PASS | the Kategori section shows "Tidak ada kategori tertunda." independently of the import counters |
| Last attempt vs last success | PASS | post-run dump: "Percobaan terakhir: baru saja", "Terakhir berhasil sinkron: belum pernah" (backend mailbox still partial, so no success yet — honest state) |
| Zero/partial import explained | PASS | attention copy "Rentang email belum selesai diproses. Data yang sudah masuk tetap aman…" in `ringkasan_new_393dp.xml` and the sheet |
| Failure reason + "Coba lagi" | PASS | offline capture `status_sheet_offline_failure_393dp.png/.xml`: "Tidak dapat terhubung. Periksa koneksi Anda lalu coba lagi." + "Coba lagi" |
| Retry resumes and does not duplicate the job | PASS | reconnected, tapped "Coba lagi": error and pending-upload lines clear, trigger returns to "Sinkronkan sekarang", the same result/counters are shown (`status_sheet_retry_recovered_393dp.xml`); no second job row appears |
| Unacked upload shown as pending | PASS | offline dump: "Tersimpan di perangkat, belum diakui server." |
| Pending upload uses upload-resume recovery, no full restart | PARTIAL | PASS in `SyncSessionCoordinatorTest.pending upload resumes the upload path`; a live upload-resume was **NOT RUN** because no locally saved-but-unacked upload existed on this UAT account |
| Conflict is informational, not a failure | PARTIAL | Roborazzi `19-sheet-conflict.png` + mapper/coordinator tests; live conflict not reproducible on demand (**NOT RUN**) |

## 5. Retirement of the old import-job UI

| Item | Status | Evidence |
| --- | --- | --- |
| `ui/feature/importjob/` deleted | PASS | directory removed from `app/src/main/java/com/pam/recap/ui/feature/` |
| `PamDestination.ImportJob` and nav entries removed | PASS | `grep -rn "ImportJobScreen\|PamDestination.ImportJob\|feature.importjob" app/src/main app/src/test` → 0 |
| No dead string resources | PASS | 27 retired `import_*`/`overview_import_*` strings deleted from `values/strings.xml` + `values-en/strings.xml`; `grep` → 0 references |
| Retired route unreachable | PASS (by construction) + deep link **NOT RUN** | no nav destination or shortcut references it; an explicit `am start` deep-link probe was skipped because no activity/deep link ever exposed that route |

## 6. Build gates (fresh, final tree)

| Gate | Result |
| --- | --- |
| `:app:testDebugUnitTest` | PASS — 503 tests, 0 failures, 0 errors (`app/build/test-results/testDebugUnitTest/`) |
| `:app:testAssessmentUnitTest` | PASS — 513 tests, 0 failures, 0 errors (`app/build/test-results/testAssessmentUnitTest/`) |
| `:app:lintDebug` | PASS — 0 errors after adding the English translations for the new status strings (109 pre-existing warnings unchanged) |
| `:app:assembleDebug` | PASS |

### 6.1 Refresh pasca-merge (HEAD `a310067`)

Angka di tabel §6 adalah angka **lane** pada `1362c11` (sebelum string kategori ada). Setelah
kedua lane digabung, `:app:lintDebug`/`:app:lintAssessment` sempat **gagal** dengan 7
`MissingTranslation` karena string kategori hanya berbahasa Indonesia; perbaikan `a310067`
menambahkan 7 string Inggris. Gate merged-head yang fresh:

| Gate merged `a310067` | Hasil |
| --- | --- |
| `:app:testDebugUnitTest --rerun-tasks` | PASS — 509 tes, 0 gagal, 0 skip |
| `:app:testAssessmentUnitTest --rerun-tasks` | PASS — 519 tes, 0 gagal, 0 skip |
| `:app:lintDebug --rerun-tasks` | PASS — 0 error, 112 warning, 1 hint |
| `:app:lintAssessment --rerun-tasks` | PASS — 0 error, 120 warning, 1 hint |
| `:app:assembleDebug` / `:app:assembleAssessment` | PASS (sha256 debug `0e9169f2…`, assessment `dea936b8…`) |

UAT perangkat pada APK merged (Ringkasan bersih, judul terpusat 393 dp + 360 dp font 200%,
ikon status 48 dp, sheet tunggal tanpa impor saat dibuka, satu job impor per tap, hasil sama
setelah tutup/buka dan minimize/relaunch, alasan kategori tampil) ada di
[UAT_MERGED_UI_CATEGORY_2026-11-23.md](UAT_MERGED_UI_CATEGORY_2026-11-23.md).

## 7. Caveats

- The UAT backend mailbox/demo connection is still partial, so a full "complete with no new
  transactions" run and a real AI categorisation drain could not be produced end-to-end on this
  device; the corresponding states are covered by unit/Roborazzi tests and labelled PARTIAL above.
- The assessment build (`testAssessmentUnitTest`) has the foreground-only sync limit; the sheet's
  app-scoped flight does not change that WorkManager constraint. Not a regression.
- dp matrix was captured by `wm density` overrides on the same 1080×1920 emulator; font matrix used
  `settings put system font_scale 2.0` at the narrowest width, then the device was restored
  (density 420, font 1.0).
- The transient label is intentionally hidden at font scale ≥ 1.5 while TalkBack still receives the
  full status description (design decision, covered by `UpdateStatusPillTest`).
