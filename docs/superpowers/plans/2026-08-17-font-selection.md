# TerraWatch Font Selection: Research + Decision

**Status: IMPLEMENTED** (2026-08-17, same branch, later same day). Full implementation report:
`docs/qa/review-round-3/RESULTS.md` ("Review round 3 — Inter font rollout" section). Summary of
where reality diverged from this doc's own sketch, verified rather than assumed: (1) static Inter
instances were bundled, not the variable file — but the doc's own ~100–300 KB/instance estimate for
statics didn't hold (measured actual: ~400 KB each, ~1.22 MB combined, *larger* than the 856 KB
variable file); static was chosen anyway because Compose Multiplatform's variable-`wght`-axis support
turned out to have two open JetBrains bugs on this app's own non-Android compile targets (desktop/iOS
`#3127`, `wasmJs` `#4635`), verified via WebSearch, not assumed from this doc's own framing. (2) Font
files live in `core/ui/…/composeResources/`, not `composeApp/…` — `TerraTheme.kt` (Part 5 step 3's
consumer) lives in `core:ui`, and compose-resources' generated `Res` accessor doesn't cross module
boundaries the wrong way. (3) `Typography(defaultFontFamily = …)` (Part 5 step 3) does not exist on
this project's actually-resolved Material3 (`org.jetbrains.compose.material3:material3:1.8.2`,
confirmed via `javap` against the real resolved jar) — AndroidX's own version of that parameter landed
in `1.5.0-alpha19`, a revision line this CMP fork hasn't caught up to at this pin; worked around with
explicit per-role `.copy(fontFamily = …)` across all 15 roles instead, same net effect. (4) Part 5
item 4's undecided Bold-migration question resolved to decision (b) (sweep to SemiBold) — 28 real
call sites swept, not the "27 outside `TerraTheme.kt`" this doc counted, because the doc's own
"silently ships faux-bold on every magnitude badge" warning necessarily includes `TerraTheme.kt`'s own
titleLarge/headlineMedium roles (which style the magnitude hero), not just the scattered ad hoc
overrides.

**Type:** Research + decision doc only — no app code, Gradle, or device work performed (owned by other in-flight agents on this branch). **Source:** user request 2026-08-17 — "font issues: use ONE consistent font, research good user-friendly font." **Branch:** `feat/review-round-3`. **Method:** direct code read (`TerraTheme.kt`, `TabularFigures.kt`, `MagnitudeBadge.kt`) + repo-wide grep for `FontFamily`/`fontWeight`/`TextStyle`/`.tabularFigures()` across `core/` and `composeApp/`, direct read of 3 real device screenshots (`docs/qa/feed-visit-ux/`, `docs/qa/post-p5-tail/`), a read of this repo's own design-mockup history (`docs/design/mockups/ui-direction.html`), WebSearch across font-selection criteria and per-candidate license/OpenType-feature research, `gh api` byte-exact file-size checks against the `google/fonts` GitHub mirror, and a primary-source `WebFetch` of JetBrains' own Compose Multiplatform resources documentation.

---

## Executive Summary

- **Zero `FontFamily` anywhere in the codebase.** `TerraTheme.kt`'s `TerraTypography` is `Typography().let { base -> base.copy(titleLarge = …, headlineMedium = …) }` — only 2 of Material 3's 13 type-scale roles are touched, and only for `fontWeight`. Every role, including those two, inherits `FontFamily.Default` (the platform's own system font). This is not a one-off implementation slip: the winning "Calm Guardian" design-mockup direction (`docs/design/mockups/ui-direction.html`, panel B) never specified a font-family either — only the *losing* "Field Journal" direction (panel C) named one (a Georgia serif for numerals/headlines), and that direction wasn't adopted. TerraWatch has never had a deliberately chosen type family, from design through ship.
- **The visible "font issue" is not mixed typefaces — it's a flat, binary weight system standing in for real hierarchy.** `TerraTheme.kt`'s own kdoc claims bold is used only for magnitude numerals ("except the two styles used for magnitude numerals… which are bold"), but grep finds **27 additional hand-written `fontWeight = FontWeight.Bold` overrides** scattered across 9 files (badges, shields, cards, banners, four different screens), each applied ad hoc per call site with no shared name. There is no Medium/SemiBold step anywhere in the app — every piece of text is either Bold(700) or Regular(400), nothing in between.
- **Zero control over what actually renders.** Because no `FontFamily` is pinned, "the font" is whatever the OS resolves `FontFamily.Default` to. On stock Android that's Roboto — but the QA screenshots audited for this doc are captured on a OnePlus device (`OP9`/`OP9R` filenames; an OxygenOS icon-picker screenshot sits right next to them in `docs/qa/post-p5-tail/`), and OxygenOS — like Samsung One UI and Xiaomi MIUI — ships a user-facing system-font picker that can silently swap every app's default typeface with zero app-level override today.
- **The good news: this app already built tabular-numeral infrastructure, and it needs zero changes for a font swap.** `TabularFigures.kt`'s `.tabularFigures()` extension (`fontFeatureSettings = "tnum"`) is wired into 6 real call sites (`MagnitudeBadge`, `BarChart`, `DistributionBars`, `StatusShield`, `StatRow`, `DetailSheet`). It operates purely on `TextStyle` and is font-agnostic at the call site — the only requirement going forward is picking a bundled font that actually implements the OpenType `tnum` feature table, verified below.

---

## Part 1 — Current-State Diagnosis (code)

**`core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/TerraTheme.kt`** (lines 88–96):

```kotlin
// System-sans defaults from Typography() carry through everywhere except the two styles used for
// magnitude numerals (the big number on a quake card/pin callout), which are bold so the number
// - the single most important glanceable fact in this app - always reads as emphasized.
private val TerraTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    )
}
```

The comment's own framing ("except the two styles… which are bold") is contradicted by the rest of the codebase — bold shows up in far more than two places, just not through this file.

**Repo-wide grep results** (`core/`, `composeApp/`, build artifacts excluded):

| Search | Hits |
|---|---|
| `FontFamily` | **0** |
| `TextStyle(` (raw construction) | **0** |
| `Font(` (resource loading) | **0** |
| `composeResources` font directory | **does not exist** |
| `compose.components.resources` dependency in any `build.gradle.kts` | **0** |
| `fontWeight = FontWeight.Bold`, outside `TerraTheme.kt` | **27**, across `MagnitudeBadge.kt`, `StatusShield.kt` (×3), `QuakeCard.kt`, `StatRow.kt`, `TsunamiBanner.kt`, `InsightsScreen.kt`, `SettingsScreen.kt` (×4), `FeedSheet.kt` (×3, one conditional: `if (isLive) FontWeight.Bold else FontWeight.Normal`), `DetailSheet.kt`, `HistoryScreen.kt` (×2), `PaywallScreen.kt` (×3), `OnboardingScreen.kt` (×4) |
| `.tabularFigures()` call sites | **6**: `MagnitudeBadge.kt`, `BarChart.kt`, `DistributionBars.kt`, `StatusShield.kt`, `StatRow.kt`, `DetailSheet.kt` |
| `MaterialTheme.typography.*` usage (94 call sites total) | `bodyMedium` 21 · `bodyLarge` 19 · `bodySmall` 17 · `labelSmall` 13 · `titleMedium` 8 · `labelMedium` 5 · `headlineSmall` 4 · `labelLarge` 3 · `headlineMedium` 3 · `titleLarge` 1 |

Two things fall out of that table directly:

1. **69 of 94 real call sites (73%) use `body*`/`label*` roles** — small, dense, glanceable strings: timestamps, distances, depths, region names, stat labels. TerraWatch's actual text profile is overwhelmingly small-size/caption-adjacent, not headline-heavy.
2. **The 27 scattered `FontWeight.Bold` overrides are a symptom of a missing middle weight.** Whoever wrote each of those call sites needed "more emphasis than default body text" and reached for the same tool `TerraTheme.kt` reserves for magnitude numerals — because nothing else was on offer. A named Medium/SemiBold step would have given them a smaller lever than full Bold.

**Design history confirms this was never decided, not just never implemented.** `docs/design/mockups/ui-direction.html` presents three named directions (`h3` headings, grep-verified): *"A · Seismo Dark"*, *"B · Calm Guardian"*, *"C · Field Journal"*. `TerraTheme.kt`'s own kdoc ("Calm Guardian theme…") confirms **B** shipped. Only **C**'s CSS (`.pC .masthead h1`, `.pC .stat .n`, `.pC .magnum`) names a real font-family — `Georgia, "Times New Roman", serif` for headers and numerals, a deliberate editorial/field-journal identity that lost. **B**'s own CSS (15 `.pB` rules, grepped) never sets `font-family` at all — only sizes/colors/weights, several of which (`font-weight: 650`, `750`, `800`) are prototyping-only numbers that don't correspond to any real static font instance. So "no `FontFamily` in code" is a faithful carry-through of a mockup phase that itself never committed to a typeface — this doc is the first point in the project where that decision actually gets made.

**Platform breadth (context, not the primary finding):** `composeApp/build.gradle.kts` and `core/ui/build.gradle.kts` both declare `androidTarget(); jvm(); wasmJs { browser() }` — TerraWatch is genuinely KMP across three render backends, each of which resolves an unset `FontFamily.Default` differently (Android → device Roboto or an OEM substitute; desktop JVM → OS-dependent Skia fallback; Wasm → browser default). Per this project's own standing scope decision, only Android is currently verified/shipped — so cross-platform divergence is noted here as a latent, currently out-of-scope risk, not re-litigated as a primary finding.

## Part 2 — What the inconsistency actually looks like on screen

Three real device screenshots were read directly (all dark theme, all what filenames identify as a OnePlus 9/9R): `docs/qa/feed-visit-ux/commit3-op9-expanded-latest-first-plus-banner.png` (feed list), `docs/qa/feed-visit-ux/commit4-op9-detail-sheet-whatsapp-icon.png` (map + detail sheet), `docs/qa/post-p5-tail/darkmode-favorites-after-home.png` (map + feed list).

**There is no visible typeface clash in any of the three** — one consistent grotesque/system-sans renders throughout, with no serif intrusion or obviously substituted glyphs. So the complaint isn't "two fonts fighting." What's visible instead matches the code finding exactly: **a strict two-step weight system doing all the hierarchy work.** Region names ("FLORES SEA", "OFFSHORE VALPARAISO, CHILE", "SUMBA REGION, INDONESIA") render Bold + all-caps; the metadata line under each ("15 min ago · 20.0 km · 6,143 km away") renders Regular, sentence case. The detail sheet's stat trio repeats the identical pattern (bold "10.0 km" over a regular-weight uppercase "DEPTH" label). Banner cards do too ("All calm near you" bold / "Nothing within 100 km · 24 h" regular). It reads as clean specifically *because* it's simple — but it's also completely flat: every string is either shouting or whispering, with no way to express "slightly emphasized" other than reaching for the same Bold weight already spent on the single most important number on the screen (the magnitude badge), diluting what Bold is supposed to signal.

Worth stating plainly: the glyphs actually visible in these three captures are consistent with stock Roboto, a legible baseline on its own. The "font issue" here is structural — no pin, no real middle weight, no deliberate choice — not a rendering defect visible in these particular captures. The structural gap is what leaves the door open to the OEM font-swap risk named in the Executive Summary.

---

## Part 3 — Research: candidate fonts

Evaluated against the axes this task specified. Confidence is marked where a claim rests on secondary/marketing material rather than a primary OpenType spec I could independently verify.

| Candidate | Small-size legibility (12–14sp) | Tabular numerals | Weights available | License | Bundle size (measured, `google/fonts` mirror) | Pairing with the dial/gauge mark's geometry | Verdict |
|---|---|---|---|---|---|---|---|
| **Inter** | Purpose-built for UI screens at small sizes — tall x-height, open apertures; explicitly designed "for computer screens" | **Confirmed** explicit `tnum` OpenType feature. Default figures are *proportional* — `tnum` is a real, distinct alternate, so this call is load-bearing, not a no-op | 9 named weights, 100–900, incl. Medium(500)/SemiBold(600)/Bold(700), all in one variable file | OFL 1.1, fully open since 2020 (the earlier Apache-license carve-out for fallback glyphs was removed) | `Inter[opsz,wght].ttf` = **876,576 B (~856 KB)**, one file, every weight | Neutral grotesque — doesn't fight the mark's circularity, doesn't echo it either | **RECOMMENDED** |
| **Manrope** | Good — geometric sans; semi-condensed proportions worth a spot-check at 12sp, not a red flag on their own | **Confirmed** — "Tabular Figures" is an explicitly listed OpenType feature | 7 weights, Thin–ExtraBold, single `wght` axis | OFL 1.1 | `Manrope[wght].ttf` = **165,420 B (~161.5 KB)** — smallest full-range file among the well-evidenced candidates | Explicitly marketed as "geometric sans-serif" — the most literal echo of the dial mark's circular character | **RUNNER-UP** |
| IBM Plex Sans | Good general-purpose; a more "engineered/corporate" personality than a UI-first face | *Moderate confidence* — sources describe figures as tabular-friendly alongside Plex Mono, but I could not independently confirm an explicit `tnum` GSUB tag against a primary spec | 8 weights (Thin…Bold), across `wdth`+`wght` axes | OFL 1.1 | `IBMPlexSans[wdth,wght].ttf` = **537,244 B (~525 KB)** | Technical/corporate character; workable, not a strong "Calm Guardian" fit | Considered, not chosen |
| Figtree | Very good — "elevated x-height," designed by a UI/UX practitioner (Erik Kennedy) explicitly for interface use | *Moderate confidence* — tabular numbers claimed in the designer's own materials; smaller community track record to cross-check against | 7 weights, 300–900, single `wght` axis | OFL 1.1 | `Figtree[wght].ttf` = **62,712 B (~61 KB)** — smallest of every candidate, by a wide margin | Self-described "clean-yet-friendly geometric sans" — good, slightly warmer pairing than Manrope | Strong dark horse; newer/smaller ecosystem is the only real knock |
| DM Sans | Good, friendly low-contrast geometric | *Moderate confidence* — "numerous OpenType features such as tabular figures" per secondary sources | Variable 100–1000 + optical-size axis (very wide range) | OFL 1.1 | `DMSans[opsz,wght].ttf` = **240,164 B (~235 KB)** | Rounded/geometric, decent pairing | Solid all-rounder, no edge over Inter/Manrope |
| Space Grotesk | **Weaker** at true caption sizes — quirky, wider counters tuned for display/headline personality, not maximal small-size density | **Confirmed** explicit tabular figures (`tnum` + `lnum` combined) | Only **5** weights (Light/Regular/Medium/SemiBold/Bold) | OFL 1.1 | `SpaceGrotesk[wght].ttf` = **136,676 B (~133.5 KB)** | Strongest, most overt geometric personality of any candidate on paper | **Rejected as the app-wide face** — see below |
| Roboto (current default) / Roboto Flex | Fine, proven, zero effort | Classic Roboto's digits are tabular in practice with no proportional alternate commonly exercised in UI (best-available inference, not a pinned primary source); **Roboto Flex** (the newer variable mega-family) explicitly flipped its own default to *proportional* — a different, less-safe default than classic Roboto | Classic Roboto: standard static weights, already on-device. Roboto Flex: huge continuous axis range | OFL (Roboto was re-licensed OFL) | **Zero marginal bundle cost** — already resident on every Android device | Same neutral non-issue as Inter | **Rejected as "do nothing"** |

Space Grotesk detail: the user's own framing of this candidate ("headers only?") is exactly right per this research — its quirks (unusually wide counters, an idiosyncratic italic-leaning leg on some glyphs, a heritage as a proportional derivative of the monospace Space Mono) read as confident personality at display sizes and as friction at 12–14sp caption density. Introducing it as a second, headers-only face would also directly violate the user's explicit "ONE consistent font" instruction, so it's excluded outright rather than proposed as a secondary face.

Roboto/"do nothing" detail: zero bundle cost is real, but it is exactly the OEM-swappable default this diagnosis identifies as the actual root problem (Part 1) — it cannot satisfy "one consistent font" by definition, since "consistent" is precisely the property it lacks today.

---

## Part 4 — Recommendation

**Font: Inter. Weights to bundle: Regular (400), Medium (500), SemiBold (600).**

Why Inter over the runner-up:

1. **Best evidenced fit for this app's actual text profile.** 73% of TerraWatch's real `MaterialTheme.typography.*` call sites are `body*`/`label*` roles — small, dense, glanceable strings (Part 1). That is precisely the size/density regime Inter's own design brief targets more specifically than any other candidate's stated brief.
2. **The tabular-numeral work this app already shipped becomes meaningful, not just consistent.** Inter's default figures are proportional and `tnum` is a real, confirmed, distinct alternate — so `.tabularFigures()`'s 6 existing call sites (magnitude badges, bar chart, distribution bars, status shield, stat row, detail sheet) will, for the first time, be doing load-bearing work, rather than what is likely already a no-op against Roboto's naturally-tabular digits today.
3. **Medium(500) and SemiBold(600) are real named instances already inside the same OFL file** — no separate acquisition or licensing step, and headroom (e.g. a future Black/900 hero treatment) at zero extra bundle cost.
4. **Lowest ecosystem risk.** Inter is the most widely deployed font of this candidate set, and JetBrains' own official Compose Multiplatform resources documentation demonstrates bundling *this exact font* (`Inter_24pt_Regular` / `Inter_24pt_SemiBold`) as its canonical example — the wiring this doc recommends below has direct upstream precedent, not a novel setup.
5. **Appropriately neutral geometry for "Calm Guardian."** Inter doesn't fight the dial-gauge mark's circular geometry, but it doesn't need to loudly echo it either — a safety-adjacent, calm-branded app benefits more from disappearing-into-legibility than from a font with strong personality.

**Runner-up: Manrope.** Loses narrowly, not by a wide margin. Its "geometric sans-serif" identity is a more literal visual echo of the dial mark than Inter's neutral grotesque, it has confirmed tabular-figure support, it's OFL-licensed, and its variable file is far smaller (161.5 KB vs. 856 KB for Inter's full range). It loses specifically because Inter's whole design brief is small-size screen legibility — this app's dominant text profile — Inter carries broader tooling/ecosystem maturity, and Manrope's semi-condensed proportions are a small but real caution flag at 12sp that Inter doesn't share. A team that weights "distinctive brand personality" above "maximum small-size safety margin" would not be wrong to pick Manrope instead — this is a close call, not a rout.

---

## Part 5 — Implementation sketch (not executed — for whoever implements)

1. Add `implementation(compose.components.resources)` to `core/ui/build.gradle.kts`'s `commonMain.dependencies` block. This dependency does not exist anywhere in the repo today (confirmed via grep) — it's net-new build wiring, not a version bump.
2. Create `core/ui/src/commonMain/composeResources/font/` and add the Inter OFL font asset(s) plus `OFL.txt` (the license requires the license text to travel with the font; it does not require in-app attribution UI — though `SettingsScreen.kt` already has a static "ABOUT" attribution section that would be a natural, optional place to credit it).
   - **Simplest path** (fewest files; "variable fonts are supported on all platforms" per JetBrains' own docs): bundle the single `Inter[opsz,wght].ttf` variable file once, and declare three `Font()` entries against that *one* resource with three different `FontWeight`s — the same pattern as JetBrains' own official example, just 3 weights off one file instead of 2 separate static files.
   - **Alternative** (smaller effective footprint, matches JetBrains' literal doc example more closely, more moving parts): pre-extract 3 static instances via `fonttools varLib.instancer`, optionally glyph-subset to Latin + digits + punctuation (TerraWatch is English-only per every screenshot reviewed — no need for Inter's full Cyrillic/Greek/Vietnamese coverage), and bundle 3 small static TTFs instead.
3. In `TerraTheme.kt`, build one `FontFamily` from those `Font()` entries and pass it to `Typography()`'s own `defaultFontFamily` parameter — e.g. `Typography(defaultFontFamily = TerraFontFamily)` in place of the current bare `Typography()`. `defaultFontFamily` applies to all 13 type-scale roles in one place, which is the "one-place change" this task asked for; the existing 2-role Bold override (`titleLarge`/`headlineMedium`) layers on top unchanged.
4. **Decision this doc surfaces but does not make** (app code, out of scope here): the 27 scattered `FontWeight.Bold` call sites (Part 1) either (a) stay at Bold/700 — which then requires bundling a 4th weight (Bold/700) too, since asking Skia to draw weight 700 against a family that only actually contains 400/500/600 triggers synthetic/faux-bold rather than a true drawn glyph — or (b) get swept to `FontWeight.SemiBold`(600) in the same change that wires in the font, intentionally softening app-wide emphasis in a way that's consistent with "Calm Guardian" (this repo's own prior polish doc already cites UXmatters on exactly this: "bold fonts… compete for attention"). Whoever implements this must choose (a) or (b) explicitly — leaving it undecided silently ships faux-bold on every magnitude badge and headline the day the font lands.
5. **Verify before ship** (device work, someone else's lane per this task's scope): render a column of real digits with `.tabularFigures()` active against the actually-bundled Inter file and confirm equal advance widths. The feature existing in Inter generally doesn't guarantee it survived any subsetting/instancing step in step 2 above — cheap to check now, expensive to discover missing after ship.

---

## Part 6 — APK size impact

- **Primary path (single variable file):** ~856 KB raw addition (`Inter[opsz,wght].ttf`, 876,576 bytes measured directly from the `google/fonts` mirror). Covers the full opsz+wght design space, including all 9 named weights, though only 3 are declared — no marginal per-weight cost, since all three live in the one bundled file.
- **Alternative path (3 static, English-subset instances):** likely well under that — subsetted single-weight statics typically land far below the "~100–300 KB per weight, full glyph set" ballpark, but this doc did not produce and measure an actual subsetted build, so treat this as directional, not a committed number.
- **In context:** this repo's own git history already records real per-ABI APK sizes (arm64: 20.3 MB, armeabi-v7a: 16.3 MB — commit `342c4a5`). Even the least-optimized (single variable file) option is under 0.1% of installed size. Final installed-APK delta will typically be somewhat smaller still after Play's own on-device compression; this doc reports the raw, pre-compression, directly-measured file size rather than guessing a compression ratio.

---

## Concerns

- **Font-weight migration is explicitly undecided** (Part 5, item 4) — Bold-700-stays vs. sweep-to-SemiBold-600 must be a deliberate choice made during implementation, not a default that falls out silently.
- **IBM Plex Sans / Figtree / DM Sans's tabular-numeral support is sourced from secondary/marketing material**, not a primary OpenType feature-table spec I could independently verify (Inter, Manrope, and Space Grotesk's tnum support is confirmed at higher confidence). If either of the first three is ever reconsidered, verify directly (e.g. a `fonttools ttx` dump of the GSUB table) before trusting `.tabularFigures()` against it.
- **Static-instance file sizes for the implementation sketch's "alternative" path are estimated, not measured.** Actually extracting and subsetting, then re-measuring, is cheap and should happen before finalizing which of the two bundling paths ships.
- **The screenshot evidence in Part 2 is thin by design** — 3 images, 2 directories, all dark theme, all apparently the same OnePlus test device. That's enough to establish "the binary Bold/Regular pattern is real and visible," which is what this pass needed, but it is not a full visual audit across every screen/theme and shouldn't be read as one.
- **KMP scope:** this app targets `androidTarget()` + `jvm()` + `wasmJs()`, and an unset `FontFamily.Default` resolves differently on each. This research treated Android as primary, matching this project's own standing verification-scope decision — cross-platform default-font divergence on the other two targets is real but unmeasured here.

---

## Sources

**Fetched in full (WebFetch):**
- [Using multiplatform resources in your app — Kotlin Multiplatform Documentation](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html) — `composeResources/font` convention, the official `Font()`/`FontFamily` code sample (their own example bundles Inter), variable-font-on-all-platforms confirmation.

**WebSearch (snippet-level evidence):**
- "best UI fonts 2026 data-dense app legibility tabular numbers" — [madegooddesigns.com/best-fonts-for-apps](https://madegooddesigns.com/best-fonts-for-apps/), [madegooddesigns.com/best-fonts-for-dashboards](https://madegooddesigns.com/best-fonts-for-dashboards/)
- "Inter font tabular numbers OpenType tnum feature default Google Fonts license" — [rsms.me/inter](https://rsms.me/inter/), [Inter (typeface) — Wikipedia](https://en.wikipedia.org/wiki/Inter_(typeface)), [OpenType Feature: tnum](https://www.preusstype.com/techdata/otf_tnum.php)
- "IBM Plex Sans tabular figures numerals OFL license weights" — [IBM Plex Sans — Google Fonts](https://fonts.google.com/specimen/IBM+Plex+Sans), [github.com/IBM/plex](https://github.com/IBM/plex), [ibm.com/plex/specs](https://www.ibm.com/plex/specs/)
- "Manrope font tabular numbers OpenType features license weights" — [fontsarena.com/manrope-by-michael-sharanda](https://fontsarena.com/manrope-by-michael-sharanda/)
- "Figtree font Google Fonts tabular numbers license weights designer Erik Kennedy" — [erikdkennedy.com/projects/figtree](https://www.erikdkennedy.com/projects/figtree.html), [Erik Kennedy's own announcement, X/Twitter](https://x.com/erikdkennedy/status/1575135945359097864)
- "DM Sans font tabular numbers variable font weights license" — [DM Sans — Google Fonts](https://fonts.google.com/specimen/DM%2BSans), [DM Sans — Fontsource](https://fontsource.org/fonts/dm-sans)
- "Space Grotesk font tabular numbers numerals license use case" — [github.com/floriankarsten/space-grotesk](https://github.com/floriankarsten/space-grotesk/blob/master/README.md), [fonts.floriankarsten.com/space-grotesk](https://fonts.floriankarsten.com/space-grotesk)
- "Roboto Flex variable font Android weights tabular numbers file size" — [Roboto Flex now on Google Fonts — Material Design 3](https://m3.material.io/blog/roboto-flex), [9to5google.com Roboto Flex coverage](https://9to5google.com/2022/05/05/roboto-flex-font/)
- "Roboto default numerals tabular lining figures proportional Android system font" — [github.com/googlefonts/roboto-2 issue #71](https://github.com/googlefonts/roboto-2/issues/71)
- "Compose Multiplatform compose resources custom font Font() variable font support Skiko" — [What's new in Compose Multiplatform 1.8.2](https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html)
- "compose multiplatform variable font single file multiple FontWeight same Font() resource wght axis" — [Just your type: Variable fonts in Compose — Android Developers, Medium](https://medium.com/androiddevelopers/just-your-type-variable-fonts-in-compose-5bf63b357994), [compose-jb issue #1663](https://github.com/JetBrains/compose-jb/issues/1663)
- "Samsung One UI Xiaomi MIUI OnePlus OxygenOS change system font settings feature" — [How to change the font style on your Android phone — Android Police](https://www.androidpolice.com/change-font-android-phone/), [How to change the font on Android smartphones — XDA Developers](https://www.xda-developers.com/how-to-change-font-android/)

**Byte-exact file sizes (`gh api`, `google/fonts` GitHub mirror, `main` branch, read 2026-08-17):**
- `ofl/inter/Inter[opsz,wght].ttf` — 876,576 B
- `ofl/manrope/Manrope[wght].ttf` — 165,420 B
- `ofl/ibmplexsans/IBMPlexSans[wdth,wght].ttf` — 537,244 B
- `ofl/dmsans/DMSans[opsz,wght].ttf` — 240,164 B
- `ofl/figtree/Figtree[wght].ttf` — 62,712 B
- `ofl/spacegrotesk/SpaceGrotesk[wght].ttf` — 136,676 B
- (`rsms/inter` upstream repo checked too: only ships a full multi-format release ZIP (33.7 MB, v4.1) rather than individually-addressable static files via the GitHub Contents API, hence the `google/fonts` mirror was used as the citable byte-exact source instead.)

**TerraWatch repo files read directly:** `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/TerraTheme.kt`, `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/TabularFigures.kt`, `core/ui/src/jvmTest/kotlin/com/yugma/terrawatch/ui/theme/TabularFiguresTest.kt`, `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/components/MagnitudeBadge.kt`, `core/ui/build.gradle.kts`, `composeApp/build.gradle.kts`, root `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `docs/design/mockups/ui-direction.html`, `docs/superpowers/plans/2026-08-16-ui-polish-findings.md` (for house doc style/format and to check for prior font-specific findings — found none), `docs/superpowers/plans/2026-08-15-terrawatch-plan-5-polish.md` (Task 4 logo-research context).

**Device screenshots read directly:** `docs/qa/feed-visit-ux/commit3-op9-expanded-latest-first-plus-banner.png`, `docs/qa/feed-visit-ux/commit4-op9-detail-sheet-whatsapp-icon.png`, `docs/qa/post-p5-tail/darkmode-favorites-after-home.png`, `docs/qa/post-p5-tail/logo-01-launcher-grid.png` (TerraWatch app icon / dial-gauge mark), `docs/qa/post-p5-tail/logo-04-oxygenos-icons-page.png` (OxygenOS customization surface, supporting the OEM-font-swap finding).
