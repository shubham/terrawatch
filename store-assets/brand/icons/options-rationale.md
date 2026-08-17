# TerraWatch settings-icon options — rationale

User feedback: "settings icon looks not good, use a new setting icon" — 5 variations below, plus
the shipped glyph carried over as a reference column, so the user can pick the one that reads as
most "modern" side by side. The my-location FAB glyph is untouched (out of scope for this pass).

**Update: user picked v4.** It has been applied to the app (`HomeScreen.kt`'s `SettingsGlyph`,
translated directly from `direction-v4-sliders-vertical.svg`'s 24x24 geometry) — see the v4
section below for the PICKED/SHIPPED status note. v1/v2/v3/v5 remain exploration-only and
untouched, same stop point `store-assets/brand/round2`/`round3`'s own logo-direction rationale
docs left their own work at.

## Correction to this task's own starting premise

The brief guessed the current icon might be a "generic gear." Checked the real code
(`HomeScreen.kt`'s `SettingsGlyph`, called from `SettingsGearChip`) and a real device screenshot
(`docs/qa/post-p5-tail/darkmode-favorites-after-home.png`) before designing anything: **it is not a
gear.** It's a hand-drawn "sliders/equalizer" glyph — three horizontal tracks, each with one offset
knob — and `SettingsGlyph`'s own kdoc already explains why a gear was rejected the first time: "a
recognizable gear needs teeth around its rim, which is a much fussier path to hand-draw reliably at
24dp than three lines + three circles." Flagging this explicitly rather than silently designing
around a premise that didn't hold up, same discipline `docs/qa/post-p5-tail/RESULTS.md`'s own Fix 3
used when its task brief's "dark map tiles" assumption also didn't survive reproduction.

One more real fact worth surfacing before the options below: `SettingsScreen.kt` (the screen this
icon actually opens) contains **two literal `Slider` controls** (search radius + minimum
magnitude). The shipped icon's "sliders" concept is therefore not an arbitrary equalizer motif — it
is, whether by design or accident, an accurate preview of the destination screen's real content.
That's a genuine point in favor of the sliders family (v3/v4) that the pure-gear variations (v1/v2)
don't have, and it's weighed honestly in the recommendation below rather than ignored.

## What's actually weak about the shipped glyph (measured, not guessed)

Computed directly from `SettingsGlyph`'s real math at its real 24dp call-site size (not eyeballed):

- **Knob diameter (7.2 units) is larger than the row-to-row gap (6.24 units).** The closest pair
  (row 2's knob to row 3's knob) clears center-to-center by only **0.82 units out of a 24-unit
  icon** — under a pixel of real breathing room once rendered at true 24dp with anti-aliasing (see
  the "24px true" rows on `settings-icon-options.png`, where this pair visibly reads as crowded).
- **The knob stagger (knobX at 0.32 / 0.65 / 0.44 of the icon width) is non-monotonic** — left,
  then right, then back toward center. It reads as a slightly random zigzag rather than an
  intentional rhythm once you look for it.
- **The knob itself is a two-color "hole-punch"**: a flat opaque circle in `knobFill`
  (`colorScheme.surface` — White in light theme, DuskCard in dark) topped with a stroked ring in
  `tint`. It only reads correctly if `knobFill` exactly matches whatever it's drawn over — every
  other hand-drawn glyph in this app (`StatusShield`'s `CheckGlyph`/`LocationPinGlyph`, all three
  `NavIcons.kt` tab icons, `MyLocationGlyph`) draws in a single `tint` color only. At true 24dp (see
  the render), this hole-punch is also the construction most likely to read as a smudge instead of
  a crisp ring once anti-aliasing softens its edges.

## The five variations

Geometry for all five lives in `direction-v1-m3-gear.svg` through `direction-v5-dot-gear.svg`
(24×24 viewBox, one shared Ink `#17222E` fill/stroke standing in for whatever `tint`/`onSurface`
the real call site would pass) — each file's own header comment carries the full per-variation
story; summarized here.

### v1 — M3 rounded-tooth gear (`direction-v1-m3-gear.svg`)

**One-liner:** the direct answer to the *original* reason this app avoided a gear — 8 teeth, each
one plain rotated rounded-rect, no bespoke tooth Path needed.

A stroked root ring (r7.4) plus 8 identical rounded rects (radial length 3.4, width 3.6, corner
radius 1.6 — close to fully rounded, the "generous rounding" the brief asked for) each placed with
one `rotate(45° × i)` around the icon center. Implementable in Compose as
`rotate(degrees) { drawRoundRect(...) }` in an 8-iteration loop — the exact fussiness `SettingsGlyph`'s
own kdoc objected to (a multi-point gear-tooth `Path`) genuinely does not apply to this
construction. The center hole is just the ring's own open interior — no separate element.

**24px honesty:** reads clearly as a friendly rounded gear at true 24dp in both themes (see the
sheet) — teeth stay individually distinct, hole stays open. One of the two clearest gear-family
reads at true size, alongside v2.

**Construction note, disclosed not hidden:** at this tooth-width-to-ring-thickness ratio, each
tooth meets the ring with a slight concave "waist" where the tooth's straight sides tangent the
ring's curve — visible if you look closely at the 96px row. A real, common characteristic of
rotate+rounded-rect gear constructions (not unique to this one), softenable by deepening the
tooth/ring overlap or thickening the ring if this direction gets picked — not chased further here,
same "worth revisiting if picked" allowance `round2-rationale.md`'s direction D left on its own
join detail.

### v2 — Minimal 6-tooth gear, large center hole (`direction-v2-minimal-gear.svg`)

**One-liner:** the same construction as v1, thinned out — fewer/bigger teeth, a noticeably bigger
open hole.

Same rotate+rounded-rect method, 6 teeth instead of 8, thinner ring (stroke 1.7 vs. v1's 2.0), each
tooth shorter (radial length 2.6 vs. v1's 3.4). Open interior radius ≈7.75 vs. v1's ≈6.4 — about
21% bigger in radius, roughly 47% more open area, computed directly from the two ring specs, not
eyeballed.

**24px honesty:** the clearest gear-family read of all three at true 24dp — arguably clearer than
v1, since fewer/bigger teeth survive pixelation more gracefully than more/smaller ones, and the
big hole stays unambiguous even blocky.

**Construction note:** the same tooth/ring "waist" v1 discloses is more visible here (fewer, fatter
teeth against a thinner ring makes the tangent pinch more prominent) — tried thickening the ring
from 1.4→1.7 units specifically to soften it (kept, visibly better) without giving up the "large
hole" ask.

### v3 — Sliders/faders, refined (`direction-v3-sliders-horizontal.svg`)

**One-liner:** same family as what's shipping today, refined rather than reinvented — wider row
gaps, a cleaner ascending knob rhythm, and a solid dot instead of a hole-punch.

Flagged honestly as a refinement, not a fresh idea (matching `round2-rationale.md`'s own "Direction
A — carried over, unchanged" precedent for calling this out plainly). Row gap widened to 5.8 units
against a smaller 4.8-unit knob diameter — a full 1.0-unit clearance, not the shipped glyph's
razor-thin 0.82. Knob stagger changed to a clean monotonic left-to-right ascending ramp (7.5 → 12.5
→ 17.0). Knob switched to one solid filled dot in `tint` — no second `knobFill` color to keep in
sync with whatever it's drawn over.

**24px honesty:** the cleanest true-24dp read of all six columns, CURRENT included — straight lines
and solid dots survive pixelation with the least ambiguity of anything on the sheet.

### v4 — Sliders/faders, vertical variant (`direction-v4-sliders-vertical.svg`) — PICKED / SHIPPED

**One-liner:** v3 rotated into a genuinely distinct silhouette — vertical tracks, a fresh diagonal
knob rhythm, same underlying fixes.

Same refinements as v3 (wide gaps, solid dots), laid out as 3 vertical tracks with a top-right-
rising diagonal knob ramp (17.3 → 11.5 → 5.7) rather than v3's horizontal ramp — a different
enough silhouette that it doesn't read as "v3, just rotated" at a glance.

**24px honesty:** equally clean at true 24dp as v3, for the same reason (straight lines + solid
dots).

**Status: PICKED / SHIPPED.** User picked v4 over v1/v2/v3/v5. Landed in `HomeScreen.kt`'s
`SettingsGlyph` (called from `SettingsGearChip`) — the glyph now draws these exact vertical
track/knob coordinates via `Canvas`/`drawLine`/`drawCircle`, single-`tint` throughout (the old
`knobFill`/hole-punch construction is gone, same single-tint convention this doc's own
"Cross-cutting notes" section called out as an advantage of all five options). Verified:
`:composeApp:jvmTest`, `compileDebugKotlinAndroid`, `assembleDebug` all green.
**Device-verification-pending** — real device `98bc1cd8` (OnePlus 9R) was not reachable from this
session (`adb devices` empty even after an `adb kill-server`/`start-server` cycle; no USB
enumeration visible via `system_profiler`/`ioreg` either), so the on-device zoom-crop screenshot
this project's own real-device-only convention calls for has not been captured yet — same
"device-verification-pending" disclosure `BannerAdSlot.android.kt`'s own kdoc already uses for an
identical not-connected-this-session gap. Highest-value next step once the device is reconnected.

### v5 — Dot-gear hybrid (`direction-v5-dot-gear.svg`)

**One-liner:** the softest option — a gear reduced to nothing but circles, no straight edges
anywhere.

One stroked ring (r6.6) plus 8 small filled dots (r1.6) at the same 8 angular positions v1's teeth
occupy, placed just outside the ring. Simplest of all five to hand-code: two `drawCircle` calls, one
of them in an 8-iteration loop of computed `cos`/`sin` offsets — no `rotate`, no rounded rect, no
`Path` at all.

**24px honesty:** the dots read as clear individual marks even at true 24dp in both themes, but the
ring itself is the thinnest-reading element on the whole sheet at true size — still a continuous,
unbroken circle, not broken into arcs, but visually airier/lower-contrast than v1/v2's thicker
rings or v3/v4's bold tracks. Legible, not broken; just the one variation whose overall visual
*weight* is honestly a step down from the other four at true size.

## Convention check (2 WebSearches this session, current as of 2026)

- **Google's Material Symbols** (the current, actively-maintained Material icon set,
  fonts.google.com/icons) ships "settings" (gear) and "tune" (sliders) as two distinct, equally
  first-class, current glyphs — with rounded/outlined/sharp style variants of each. Confirms
  neither concept is dated; both are live, current conventions, not one superseding the other.
- **Apple's own Settings app icon** has used a gear continuously through every visual era,
  including the 2025/2026 iOS 26 "Liquid Glass" system-icon overhaul (9to5Mac, "New iOS 26 icons:
  Here's how all the new app icons look" — Sept 2025) — that redesign changed the *rendering*
  (glass material, cross-platform unification across iOS/macOS/watchOS/visionOS), not the
  underlying gear concept. Separately, the gear's teeth were already rounded (and the icon
  refined to match macOS's System Preferences) as far back as iOS 6 (Logopedia's "Settings (iOS)"
  page) — the "rounded gear" look v1 uses is a decade-plus-old refinement of the same idea, not a
  new one, still current today.

Net: a rounded gear (v1) tracks the one system-level convention (iOS's own Settings icon) that has
never used anything else, rendered in today's rounder style. Sliders/"tune" (v3/v4, and the shipped
glyph) is an equally legitimate, currently-shipping Material convention, more often seen as an
in-app filter/preferences glyph than as an OS-level Settings icon specifically.

Sources: [Material Symbols and Icons – Google Fonts](https://fonts.google.com/icons) ·
[New iOS 26 icons – 9to5Mac](https://9to5mac.com/2025/09/15/new-ios-26-iphone-apps-icons-compared/) ·
[Settings (iOS) – Logopedia](https://logos.fandom.com/wiki/Settings_(iOS)) (fan-wiki tier source,
used only for the uncontroversial "rounded in iOS 6" historical detail, not for any judgment call).

## Modern-pick recommendation

Answering the narrow "which is most 'modern' per current Material/iOS convention" question
directly: **v1 (M3 rounded-tooth gear)**. It's the only variation that matches BOTH the one
platform-level icon convention that has never changed (iOS's own Settings gear, through its newest
2025/2026 redesign) and the current Material rounded-symbol aesthetic, while also being the
construction that specifically solves this codebase's own prior objection to gears (fussy
hand-drawing) via a trivial rotate-loop.

Honest caveat, not swept aside: v1/v2/v5 all give up the "the icon previews the actual Settings
screen's own two Sliders" consistency argument that v3/v4 (and the shipped glyph) have going for
them. If that content-echo matters more to the user than chasing the platform-gear convention,
**v4** is the strongest alternative pick — all of v3's measured fixes, a fresh enough silhouette,
and it keeps the sliders-preview-Sliders logic intact. Both are defensible; this is a genuine
trade-off, not a close call resolved by one factor alone.

## Cross-cutting notes

- **Single-tint robustness.** All five new variations draw in exactly one color (`tint`) with no
  second `knobFill`/background-matching color anywhere — simpler to implement and impossible to
  get out of sync with whatever surface it's drawn over, unlike the shipped glyph's hole-punch
  knobs. A real (if minor) engineering advantage of all five over the status quo, not just an
  aesthetic one.
- **Render method.** Pillow 11.3.0, 8x supersample + LANCZOS downsample for every smooth render —
  the same method already established in `store-assets/brand/round2`/`round3`'s own comparison
  sheets, for the same reason (no rsvg-convert/inkscape/cairosvg on this machine). The two "24px
  true" rows are the real 24×24 raster shown 4x larger with **nearest-neighbor only** (no
  smoothing) — the same "true raster, blown up honestly" convention `round3`'s own 48px rows used.
  Script kept in this session's scratchpad only, not committed — same precedent rounds 1–3 set.
- **Chip backgrounds are real, not invented.** Each swatch composites the real
  `MaterialTheme.colorScheme.surface` value at the real alpha (0.78) directly onto an actual cropped
  TerraWatch map screenshot (`docs/qa/post-p5-tail/darkmode-favorites-lightmode-home-spotcheck.png`),
  not a flat stand-in color — genuine translucency-over-map blending, matching
  `SettingsGearChip`'s real construction pixel-for-pixel. Per `RESULTS.md`'s own device-verified
  finding, the basemap is a fixed light style in *both* app themes, so the same map crop honestly
  underlies both the "light" and "dark glass chip" rows here, exactly as it does in the real app —
  the same map crop is simply resized to fill every cell rather than sourcing six independent map
  locations, a simplification worth naming rather than leaving implicit.
- **Not done this round, disclosed rather than silently skipped:** no Claude-Browser-pane
  cross-check of the raw SVGs against their own Pillow rasterization — tried it (a local static
  server for the icons directory, matching this session's own attempt), but both a fresh `file://`
  navigation and a `localhost` preview were blocked by the pane's own policy in this session, the
  same class of "browser pane unreachable" gap `round2-rationale.md` already recorded for its own
  round. Verification here rests on the Pillow renders alone (which did catch and fix one real
  issue — v2's tooth/ring seam, softened by thickening the ring from 1.4 to 1.7 units after seeing
  the first render — the same "rendered, looked, fixed" discipline that caught round 2's direction-G
  Pac-Man problem).
- **Out of scope, untouched:** the my-location FAB glyph (`MyLocationGlyph`), all app/Gradle code,
  and any device work — this task worked only inside the new `store-assets/brand/icons/` directory,
  per its own brief.
