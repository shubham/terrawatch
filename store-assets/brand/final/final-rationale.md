# TerraWatch logo — FINAL mark rationale (Plan 5 Task 4 phase 2 / Task 6 phase 2)

## 2026-08-16 evening — user override: brighter dot wins over token-sync

**This section supersedes the dot-color decision below it. Read this first.** The original
decision (next section down) was to use the *current* `WarnInk` token (`#7A5B19`) instead of
G4's own stale `#B08A2E`, specifically to stay consistent with `Tokens.kt`'s "these hex values
are LAW" convention. The user has now reviewed both side by side and explicitly chosen the
**brighter `#B08A2E`** instead — direct instruction: the mark's contact dot becomes G4's
original color "everywhere the AMBER dot appears."

This is a deliberate reversal of the token-sync argument below, not a discovery that the
original reasoning was wrong. Both tensions are true at once and both stay documented:

- The token-sync argument (below) is still technically sound: `#7A5B19` is what `WarnInk`
  actually is today, and `#B08A2E` is a value the app's real design-token source of truth has
  moved away from for a real accessibility reason (WCAG AA on `RevisionBadge`).
- The user's aesthetic call overrides it anyway: `#B08A2E` reads as a stronger, more legible
  "pop" for the mark's one warm accent, which is exactly the quality `final-preview.png`'s own
  48px crop flagged as diminished under `#7A5B19` ("does not 'pop' quite as much as the
  brighter hex the user actually viewed when making the pick").
- **Consequence, flagged rather than hidden:** the brand mark's contact dot (`terrawatch-mark.svg`,
  `ic_launcher_foreground.xml`, and every raster derived from them) now uses a hardcoded
  `#B08A2E` that is *not* sourced from `core/ui`'s `Tokens.kt` and does not track `WarnInk` if
  that token ever moves again. This is the opposite of the "one consistent token" principle
  the section below argues for — accepted here as an explicit, user-directed exception scoped
  to this one asset, not a silent regression. `RevisionBadge` and every other `WarnInk` consumer
  in the running app are unaffected; only the brand mark's dot diverges.
- Monochrome (`terrawatch-mark-monochrome.svg`, `ic_launcher_monochrome.xml`) and the
  notification glyph (`ic_stat_terrawatch.xml`) are untouched by this override — both are
  single-color/alpha-only by spec (see their own sections below), so there is no hex in either
  for this change to touch.

Regenerated this pass: `terrawatch-mark.svg`, `ic_launcher_foreground.xml`, all 10
legacy `mipmap-*dpi/ic_launcher*.png` rasters, `store-assets/icon-1024.png`,
`store-assets/feature-graphic.png`, and 6 of the 9 `store-assets/social/art/` files (the 3
`ig-post-*` files are screenshots pillarboxed onto a canvas and were confirmed — not assumed —
to contain zero dot-colored pixels; the notification glyph they show is the unaffected
monochrome glyph, not the mark). Method: colorimetric in-place recolor (locate the dot's
existing anti-aliased region, re-project its already-baked blend weights against the new hex)
rather than a from-scratch re-render — chosen because this machine still has no SVG rasterizer
(rsvg-convert/cairosvg/inkscape/resvg all re-checked absent) and, for the composited assets
(feature graphic's wordmark/tagline/decorative dots, the avatar crops, channel-art centering),
the original compositing script/exact metrics aren't in this repo to re-derive faithfully. This
approach reproduces the exact original anti-aliasing profile and is scoped, provably, to only
the dot's own pixels — verified per file by diffing the touched bounding box against the
geometrically-expected dot location before writing anything.

User verdict: **G2's geometry** (`store-assets/brand/round3/direction-g2.svg` — "dial + cardinal
ticks") **with G4's color treatment** (`store-assets/brand/round3/direction-g4.svg` — "epicenter
accent dot") applied to the contact dot only. This document records exactly how the two were
merged, one correction made against both source files' own stated hex, and the resulting
hex-budget/monochrome/notification decisions that follow from it.

Output: `terrawatch-mark.svg` (full color, final) and `terrawatch-mark-monochrome.svg` (single-color
alpha-only, for themed icons and as the notification-glyph source geometry).

## Merge decision

G2 and G4 are both one-change variations of the same round-2 winner (`round2/direction-g.svg`), so
"merging" them is additive, not a from-scratch redesign:

- **From G2, unchanged:** the Ink disc (r27), the four Water@0.6-opacity cardinal hairline ticks
  (r23→r26, stroke 1.5, round cap), and the Safe sweep needle (unchanged angle/length/color all the
  way back to `direction-g.svg`). G2's own round3-rationale.md entry calls it "the strongest
  performer of the five [G1–G5] at 48px, and it isn't close" — the ticks are the one device kept
  from this round's exploration, so they're kept exactly as designed, not reinterpreted.
- **From G4, applied on top:** the contact dot's recolor (Water → a warm accent) and resize
  (r4.5 → r5.25). Disc, ticks, and needle are G4's own explicit "otherwise unchanged from G".
- **Not carried in from G1/G3/G5:** those three were not part of the user's pick, so none of their
  changes (G1's bolder needle/bigger dot tuning, G3's arc trail, G5's shield-shoulder disc reshape)
  appear here. The final mark is a strict G2+G4 merge, nothing else folded in.

Because G2 and G4 never touch the same element (G2 touches ticks only, G4 touches the dot only),
this merge has no real conflict to arbitrate — every element's source direction is unambiguous.

## Superseded: 2026-08-15 token-sync decision (kept for the record — see top of file for what shipped)

**The color conclusion in this section is no longer what ships.** It's kept verbatim below
because the reasoning is still accurate as *of the day it was written*, and the "2026-08-16
evening" section at the top of this file is a deliberate, disclosed override of it, not a
correction of an error — both are worth a reader's attention, not just the newest one.

### Correction: the dot's exact hex (read before assuming this is a typo)

`direction-g4.svg`'s own comments and `round3-rationale.md` both cite the dot's color as
**`#B08A2E`**, sourced (their own words) from "`core/ui/.../Tokens.kt` (TerraColors), grepped fresh
this session." That was true when G4 was authored. It is no longer true.

Re-grepping `Tokens.kt` fresh for this task turned up
`docs/superpowers/plans/2026-08-16-ui-polish-findings.md` (dated the same day as this task) and a
same-day `Tokens.kt` change: `WarnInk` was **darkened from `#B08A2E` to `#7A5B19`** to fix a real
WCAG AA failure — `RevisionBadge`'s fixed `WarnInk`-on-`WarnBg` pair measured 2.91:1 (failing the
4.5:1 floor for that `labelSmall` text), and `#7A5B19` clears it at 5.69:1 (`ContrastTest`). This
same constant also feeds `TerraTheme.kt`'s dark `errorContainer`/`onErrorContainer` — the fix is
already live everywhere else `WarnInk` is used in the app. `Tokens.kt`'s own header comment states
"these hex values are LAW... every screen sources color from here" — not a casual convention, the
single source of truth this whole codebase already treats as binding.

**Final mark uses the current `#7A5B19`, not G4 file's stale `#B08A2E`.** Reasoning: G4's entire
argument for choosing WarnInk over MagStrong/MagMajor was "reuse the app's own real token, not an
invented hex" — honoring that argument means following the token to where it now actually points,
not preserving a value it has since moved away from. Using the stale, brighter `#B08A2E` would
silently reintroduce the exact shade the app just deliberately darkened elsewhere for a real
accessibility reason, on the one asset (the brand mark) that would then be the *only* remaining
place in the app still using the old value — the opposite of "one consistent token."

**Honest consequence, flagged rather than hidden:** the user picked G4 by looking at
`logo-round3.png`, which was rendered with the old, brighter `#B08A2E`. `#7A5B19` is a visibly more
muted, olive-leaning amber — confirmed by direct comparison in this task's own renders
(`final-preview.png`'s 48px crops): the dot still reads clearly as "the one warm element" against
the cool disc/needle, but it does not "pop" quite as much as the brighter hex the user actually
viewed when making the pick. G4's own rationale already flagged that *using WarnInk at all* "deserves
an explicit design-system-owner sign-off before shipping, not a silent assumption" — this hex
substitution is a second, narrower instance of the same kind of call, made here because the
alternative (a stale, now-inconsistent value) is worse, not because the tension is fully resolved.
Surfaced explicitly in this task's own report back to the user for exactly that reason.

## Hex budget

Final mark uses **4 distinct hexes**: Water (background, ticks-at-0.6), Ink (disc), Safe (needle),
and `#B08A2E` for the dot (**as of the 2026-08-16 override, above** — this was `WarnInk`/`#7A5B19`
from 2026-08-15 through that override; see that section for why the dot no longer tracks the
token). This matches G4's own already-disclosed departure from the 3-hex budget (Ink/Water/Safe)
every other direction across all three rounds held to — inheriting G4's dot inherits G4's own
already-accepted trade-off, not a new one. (G2 alone, before this merge, stayed inside the 3-hex
budget — the 4th hex enters specifically because G4's dot was picked on top of it.)

## Monochrome variant

`terrawatch-mark-monochrome.svg`: same four shapes, single color (opaque white on transparent),
ticks keep their 0.6 alpha (reads as a proportionally fainter tint after the OS's own re-tint,
preserving the "ticks are quieter than the needle" relationship), disc/needle/dot fully opaque —
matching the precedent the pre-existing `ic_launcher_monochrome.xml` already set for the previous
shield design. All four shapes are flat fills/strokes with no gradients — collapses cleanly, no
further "cutout" rework needed (round3-rationale.md had flagged this as an outstanding TODO for
G2/G3 specifically; this file resolves it for the final mark by construction).

## Notification-icon derivative — a bigger deviation, reasoned through, not just simplified

Task instruction: "monochrome variant simplified: dial+needle+dot, ticks optional if muddy." Ticks
are dropped (uncontroversial — this task's own permission, and at 24dp they'd be far below the
48px floor round3-rationale.md verified for them). The harder question is the disc: a **literal**
transcription (filled disc + solid-white needle + solid-white dot, all the same color, since
Android notification icons render as a single flat alpha-only silhouette — hue is discarded
entirely) was rendered once to check rather than assumed: the needle and dot are **completely
indistinguishable from the disc** — not a resolution/legibility problem that a bigger canvas would
fix, a categorical one, since there is no second visual channel left once color is stripped and all
three shapes share one alpha value.

Fix used: the notification glyph instead reuses this app's own **already-shipped, already-proven**
ring convention (the previous `ic_stat_terrawatch.xml`'s hollow ring — "the radius ring") as the
"dial," with the needle and dot as solid shapes inside the hollow interior for contrast. Rendered
and visually confirmed at true 24px before deciding (both candidates rendered — see this task's own
report). Geometry (ring unchanged: centerline r7, stroke 3dp, center 12,12): needle from (12,12) to
(16.26,10.0), stroke 1.2dp; dot center (8.82,13.84), r1.1dp — both scaled from the master mark's own
needle-angle/dot-angle ratios against the ring's inner edge (r5.5) as the analog safe radius, same
clearance discipline (never touching the ring) the master mark uses for its own ticks against the
disc rim.

## Safe-zone confirmation (computed, not assumed)

Adaptive-icon safe zone: 66dp/108 canvas, i.e. r33 from center. Every element's max extent from
center (54,54):

| Element | Max extent from center | Margin under r33 |
|---|---|---|
| Disc | 27 (its own radius) | 6 |
| Ticks | 26 (outer end) | 7 |
| Needle (incl. round-cap tip) | ≈23 + 2.75 = 25.75 | ≈7.25 |
| Dot (incl. radius) | ≈18.01 + 5.25 = 23.26 | ≈9.74 |

All four comfortably clear the safe zone — the disc itself (the largest element) already carried a
6-unit margin in G2/G4's own round-3 design, unchanged here.

## What did NOT change

Background layer (`ic_launcher_background.xml`) needed no geometry change at all: it was already a
plain full-bleed Water rect, and the new mark's own background is the same Water hex at the same
full-bleed treatment — confirmed matching, not just left alone by omission. Its header comment was
updated to point at this design instead of the old shield, since the shape/color truly are
unchanged and future readers shouldn't have to cross-reference a superseded design to understand a
file that still matches it exactly.
