# PAM — Design System

Derived from the supplied reference (`docs/design/reference-design.jpg`): three phone frames in
a deep violet-black environment with frosted glass cards, very large corner radii, a
violet→magenta→pink brand sweep, and warm amber bloom used sparingly as a second accent.

The reference is a course app. **We keep its visual language and drop its content**: no
hero photography of people, no "lessons/creator" semantics. Finance semantics replace them.

---

## 1. Non-negotiables of the look

1. **Dark only.** Canvas `#0B0711`. No light theme.
2. **Glass, not flat.** Cards are a white sheen at 4–10 % over ink plus a 1 dp top-lit border.
   Never a solid grey card.
3. **Huge radii.** Card 26–28 dp, hero 28 dp, sheet 34 dp, controls fully pill.
4. **Gradient is a budget, not a wash.** One brand gradient surface per screen maximum, plus
   radial "aurora" blooms behind content. Income/expense keep their own solid semantics.
5. **Numbers are the hero.** Amounts use display sizes with tight negative tracking.
6. **Quiet chrome.** Icons are 20–22 dp, line-style, white at 70 %, inside 44 dp circular wells.
7. **Breathing room.** 20 dp screen gutter, 16 dp inside cards, 12 dp between list rows.

---

## 2. Colour tokens

Implemented in `ui/theme/Color.kt`. Names below are the API; feature code must use them and
must never inline a `Color(0x…)` literal.

| Token | Hex | Use |
|---|---|---|
| `Ink` | `#0B0711` | App canvas |
| `InkElevated` | `#150E1F` | Sheets, secondary containers |
| `InkSurface` | `#1A1226` | Card base |
| `InkHigh` | `#241932` | Inset tracks, skeletons |
| `Violet` | `#C79BFF` | Primary accent, selected icon |
| `VioletDeep` | `#7B4DFF` | Gradient stop, primary container |
| `VioletSoft` | `#E0C8FF` | Text on gradient |
| `Magenta` | `#C36BF0` | Gradient mid stop |
| `Pink` | `#F5A9D0` | Secondary accent |
| `PinkSoft` | `#FFC7E4` | Secondary text |
| `Amber` | `#FFC08A` | Warm accent (schedule-like highlight) |
| `AmberDeep` | `#FF8A4C` | Warm bloom core |
| `IncomeMint` | `#7FE7B4` | CREDIT / income only |
| `ExpenseRose` | `#FFA2AC` | DEBIT / expense only |
| `WarningAmber` | `#FFC46B` | Partial / provisional / outdated |
| `DangerRed` | `#FF7B87` | Destructive, failed |
| `OnInk` | `#F6F1FF` | Primary text |
| `OnInkMuted` | `#AA9CBF` | Secondary text |
| `OnInkFaint` | `#6F6483` | Tertiary text, disabled |
| `GlassFill` / `GlassFillStrong` | white 6 % / 10 % | Card fills |
| `GlassBorder` / `GlassBorderStrong` | white 12 % / 20 % | Card borders |
| `TrackInset` | white 8 % | Progress tracks |

**Rule:** income is mint, expense is rose — always with an explicit sign or the words
"Masuk"/"Keluar", never colour alone (NFR-01 and SRS §4.1 text-distinguishable states).

---

## 3. Type scale

Plus Jakarta Sans variable font (`res/font/plus_jakarta_sans.ttf`, weights pinned by the
`pjs_*` font-family XMLs). Styles live in `ui/theme/Type.kt`.

| Style | Size/LH | Weight | Tracking | Use |
|---|---|---|---|---|
| `DisplayXl` | 40/44 | Bold | −0.04 em | One hero number per screen |
| `DisplayL` | 32/38 | Bold | −0.03 em | Screen titles, big totals |
| `DisplayM` | 26/32 | Bold | −0.02 em | Section heroes, budget gap |
| `HeadlineL` | 22/28 | SemiBold | −0.02 em | Card titles |
| `HeadlineM` | 19/25 | SemiBold | −0.015 em | Sub-section |
| `TitleL` | 17/23 | SemiBold | −0.01 em | List row primary |
| `TitleM` | 15/20 | SemiBold | 0 | Dense rows |
| `TitleS` | 13/18 | Medium | +0.01 em | Row meta |
| `BodyL` | 15/23 | Regular | +0.005 em | Paragraphs |
| `BodyM` | 13.5/20 | Regular | +0.005 em | Secondary paragraph |
| `BodyS` | 12/17 | Regular | +0.01 em | Captions, legal |
| `LabelL` | 14/18 | SemiBold | +0.005 em | Buttons |
| `LabelM` | 12/16 | Medium | +0.02 em | Chips |
| `LabelEyebrow` | 10.5/14 | SemiBold | **+0.16 em, UPPERCASE** | Section eyebrows |

Money uses `FontFeatureSettings("tnum")` via `TextStyle.copy(fontFeatureSettings = "tnum")`
in the shared `MoneyText` component so digits never jitter.

---

## 4. Shape & spacing

`ui/theme/Shape.kt` — `PamRadius.{xs 10, sm 14, md 20, lg 26, xl 32, hero 28, sheet 34, pill}`,
`PamSpacing.{xxs 4 … xxxl 40, gutter 20}`, `PamSize.{minTouch 48, iconButton 44, fab 56,
bottomBar 68, monthStripCell 52}`.

---

## 5. Gradients

`ui/theme/PamGradients.kt` — `brand`, `brandSoft`, `hero`, `glassSheen`, `glassEdge`,
`aurora()`, `warmBloom()`, `ringProgress()`, `income()`, `expense()`.

Usage map:
- **Welcome / Consent hero** → `hero` + `aurora()` behind the wordmark.
- **Overview balance hero** → `hero` + `warmBloom()` in the top-right corner (the reference's
  amber bloom behind "Schedule").
- **Category ring / progress rings** → `ringProgress()` over `TrackInset`.
- **Primary buttons** → `brand`.
- **Import progress** → `brandSoft`.

---

## 6. Component contract

All in `ui/components/`. Names are the API; screens compose these, they do not re-invent them.

| Component | Notes |
|---|---|
| `PamScaffold` | Edge-to-edge, canvas + optional aurora bloom + status bar padding |
| `GlassCard` | `Column` wrapper: `InkSurface` → `glassSheen` overlay, 1 dp `GlassBorder` border, radius param |
| `GlassTopBar` | Row: circular icon well, centred title, trailing well(s) |
| `CircularIconWell` | 44 dp circle, `GlassFill`, icon 20 dp at 80 % white |
| `PillButton` | Primary (brand gradient) / Secondary (glass) / Ghost. Min height 48 dp |
| `PillToggleGroup` | Segmented control matching "My Courses / Progress": inset dark track, white selected pill |
| `EyebrowText` | Uppercase `LabelEyebrow`, `OnInkFaint` |
| `SectionHeader` | Eyebrow + optional trailing action |
| `MoneyText` | Amount + optional currency prefix + tabular figures; `tone` = income/expense/neutral |
| `AmountPill` | Signed delta chip (`+`, `−`, `%`) |
| `ProgressRing` | Gradient arc + track; centre slot; `label` for accessibility |
| `CategoryChip` | Icon + name + amount, used in overview legend and filters |
| `TransactionRow` | Circular category glyph, title, timestamp, amount, optional confidence dot |
| `MonthStrip` | Horizontal day/week selector echoing the reference's Sun–Sat date row with the active day ringed |
| `MonthSelector` | `‹ January 4–10 ›` style centred month navigator with chevrons |
| `StatusChip` | `EMAIL-DERIVED`, `Partial`, `Provisional`, `Outdated`, `Manual` variants |
| `EmptyState` | Illustration-less: eyebrow, title, body, action |
| `ErrorState` | Title, message, retry; announced via `liveRegion` |
| `LoadingShimmer` | Skeleton blocks on `InkHigh` |
| `ConsentSwitchRow` | Title, body, switch + `Open in full` text action |
| `StatTile` | Eyebrow + big number + caption (used in 2-up grids) |
| `BottomNavBar` | Floating glass pill, 4 destinations + centre raised action |
| `SheetScaffold` | `ModalBottomSheet` wrapper with drag handle and 34 dp top radius |
| `AvatarStack` | Overlapping circles — reused for the mailbox/consent "who" chip |
| `SearchField` | Glass rounded search with trailing circular search button (reference bottom bar) |

Accessibility requirements on every component: min 48 dp touch target, `contentDescription`
on icon-only controls, `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on async
status text, and no information conveyed by colour alone.

---

## 7. Motion

- Screen enter: 260 ms `FastOutSlowInEasing` fade + 12 dp rise.
- Progress ring: `animateFloatAsState` 600 ms.
- Month change: cross-fade the hero totals 200 ms; the strip indicator slides.
- No looping decorative animation; shimmer only while genuinely loading.

---

## 8. Copy voice

Indonesian UI (SRS §1.4). Sentence case for body, UPPERCASE for eyebrows and status chips.
Honest, non-advisory phrasing:

- ✅ "Tidak ada email yang cocok ditemukan" — ❌ "Pengeluaran Anda nol"
- ✅ "Ringkasan dari email — bukan mutasi rekening" — ❌ "Saldo Anda"
- ✅ "Belum ada data untuk bulan ini" — ❌ "Rp0"
- Nothing ever claims completeness of bank activity, and nothing offers financial advice.

All strings live in `res/values/strings.xml` (and `values-en` for English). **Zero literals in
Compose.** Non-translatable technical values (metric keys, category codes) go to
`strings.xml` as `<string translatable="false">` only when they must be shown.
