# TerraWatch logo round 3 rationale (Plan 5 Task 4)

User verdict on round 2: **direction G** (dark scope disc, one sweep needle, one contact dot —
`round2/direction-g.svg`) picked. This round designs five variations that stay inside G's own "quiet
watch/monitoring dial" territory rather than proposing new unrelated directions — same disc, same
needle angle, same Calm Guardian 2-3 color discipline, each varying exactly one thing. All five keep
G's exact 108×108 viewBox, r27 disc (margin 6 under the r33 adaptive-icon safe zone), and — except
where a variation's whole point is to touch one of these — G's exact needle angle (-25deg, ~1-2
o'clock) and dot position (38.4,63, ~8 o'clock, diametrically clear of the needle).

Palette: only exact hex values from `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/Tokens.kt`
(grepped fresh this session) — Ink `#17222E`, Water `#D9E9F4`, Safe `#2FA36B`, plus (G4 only) WarnInk
`#B08A2E`. `MagStrong`/`MagMajor` orange/red stay untouched everywhere, same reasoning rounds 1 and 2
both documented: they're this app's reserved magnitude-severity colors, not brand-identity colors, and
alarm red/orange is `research.md`'s single most crowded competitor convention. Renders:
`logo-round3.png`, 3100×1834px, 6 columns (G original, carried over for comparison, then G1-G5) × 4
rows (light canvas/512px, light canvas/48px true raster, dark canvas/512px, dark canvas/48px true
raster) — same layout convention round 2 used. Render method: Pillow (11.3.0) hand-rasterization of
the exact SVG geometry at 8x supersample + LANCZOS downsample, the same method rounds 1-2 used and
for the same reason (no rsvg-convert/inkscape/cairosvg on this machine; `qlmanage -t` bakes opaque
white into transparent regions, disqualifying it) — script and per-direction individual test renders
kept in this session's scratchpad, not committed, same precedent as rounds 1-2. No direction has been
applied to the app; this document is design rationale only.

## Collision check (fresh this round)

Two WebSearches run this session, specifically because G1/G2/G3 push further into "dial/instrument"
and "motion trail" territory than G itself did, and that territory deserved its own fresh check rather
than reusing round 2's citations verbatim:

- **"radar app icon design"** — results are dominated by two devices: a rotating/scanning beam over a
  circular scope, and a location-pin inside a dashed circular outline (the GPS/tracking-scope
  convention); MyRadar's own icon (cited directly in the results) shows a position marker against a
  color-banded weather-intensity field. None of this is a single static thin needle + one static dot +
  (in G2) a few bare hairline ticks or (in G3) one faint 40-degree static trail — the family stays
  clear because it has no rotation, no color-graded intensity, and no map, exactly the distinction
  `direction-g.svg`'s own original comments already drew and that this round's variations preserve.
- **"monitoring app icon design dial gauge"** — results are dominated by classic speedometer/gauge
  iconography: dense 10-20-tick graduated scales, frequently with colored arc bands and numerals.
  G2's four bare cardinal hairlines (no numbers, no band, no dense scale) is deliberately the lightest
  possible version of that convention, short of it rather than a recolor of it.

Category-specific check (the six marks actually inspected in `research.md` — LastQuake, MyShake,
Earthquake Network, VolcanoDiscovery, QuakeFeed, USGS): none use a needle/dial device at all, a finding
already established in round 2 and unchanged by this round's variations, since none of G1-G5 add a
second device on top of the dial (G2's ticks and G3's trail are both refinements *of* the dial device,
not a second device stacked alongside it, so the "busy icon stacking 2-3 clichés" failure mode
`research.md` flags in 4 of 6 competitor marks has no foothold here either).

**Result: G-family stays clear of every researched mark and of the two adjacent conventions checked
this round.** Cited honestly rather than assumed — see the two result summaries above for what was
actually found, not a restatement of what was hoped for.

## G1 — `direction-g1.svg` — G refined

**One-liner:** The same dial, tuned — a bolder needle and a bigger dot, so the exact geometry the user
already picked reads more confidently at true launcher size.

**What changed:** Needle stroke 5.5→6.25 and length 23→21.5 units (shortened specifically so the
thicker round-cap tip still clears the r27 rim, by a wider margin than G's own — 2.37 units clear vs.
G's 1.25); dot radius 4.5→5.2. Disc, needle angle, dot position, and both colors are byte-for-byte
unchanged from `round2/direction-g.svg`.

**48px honesty:** Confirmed by direct comparison of the two renders (see `logo-round3.png`'s G vs. G1
columns): the needle and dot both read more solidly at 48px than G's own did, with no new legibility
cost anywhere. The most conservative of the five — no new risk to disclose.

## G2 — `direction-g2.svg` — dial + cardinal ticks

**One-liner:** Four quiet hairlines at 12/3/6/9 o'clock, and the dial suddenly reads as a calibrated
instrument instead of a plain disc with a needle.

**What changed:** Four radial hairline ticks (r23→r26, stroke-width 1.5, Water at stroke-opacity 0.6,
round cap) at the true cardinal angles, added before the needle/dot in document order. Disc, needle,
and dot are otherwise unchanged from G. Chose 4 ticks over 3: a 3-tick set is asymmetric and reads as
an accidental/incomplete render rather than a deliberate minimal scale; 4 reads immediately as "the
cardinal points, on purpose." Stays inside the 3-hex budget — the ticks reuse the Water hex at reduced
opacity rather than introducing a new color.

**Face-check:** Direction G's own history includes one real caught mistake (a wide pie-wedge + dot
that read as Pac-Man, fixed in round 2 — see `direction-g.svg`'s own comments). Checked this addition
against the same failure mode directly in the render: four symmetric cardinal ticks plus one off-axis
needle plus one off-axis dot reads as a compass/dial rose, not a face — confirmed by looking at the
actual render, not assumed from the geometry.

**48px honesty:** The strongest performer of the five at 48px, and it isn't close — confirmed by the
render. All four ticks stay individually visible as small light marks against the Ink disc even at a
true 48×48 raster, and they add a genuinely more "instrument" read with zero legibility cost. No
caveat needed here that isn't also true at 512px.

## G3 — `direction-g3.svg` — needle sweep with a faint arc trail

**One-liner:** The needle just swept into place — a single thin, faint arc trailing its tip implies
motion without a single moving part.

**What changed:** One stroked arc (radius 23 — exactly the needle's own length — spanning -65deg to
-25deg, i.e. 40 degrees, ending precisely at the needle's tip), stroke-width 2.5 (vs. the needle's
5.5), Safe green at stroke-opacity 0.32, drawn before the needle so the needle sits visually on top of
it. Disc, needle, and dot are otherwise unchanged from G.

**Why this isn't the radar cliché it could have been:** This exact file's own lineage already found
and fixed a related failure once — `direction-g.svg`'s first pass used a filled ~45deg pie wedge that
read as a cartoon mouth. G3's arc is the opposite construction on every axis that mattered there:
stroked, not filled; thin, not wide; faint (0.32 opacity), not solid; and a bounded 40-degree trail,
not the full rotating ring that literal radar-sweep apps use (confirmed via this round's WebSearch —
see Collision check above). No color gradient implying distance/intensity either, unlike QuakeFeed's
ripple-ring device (`research.md`).

**48px honesty:** The one variation most under size pressure, and worth being direct about it rather
than glossing over: at 512px the trail reads clearly as a graceful motion cue curling above the
needle. At a true 48px raster (see `logo-round3.png`'s G3 48px row) it compresses to a thin, slightly
darker-green squiggle right at the needle's tip — still visible on close inspection and distinguishable
from noise, but a viewer glancing at launcher size, without already knowing to look for it, would
likely register "something small near the tip" rather than consciously read "motion trail." Legible,
not broken, but the softest effect of the five — a real trade-off for staying faint enough to avoid
the radar cliché, not a rendering defect.

## G4 — `direction-g4.svg` — epicenter accent dot

**One-liner:** Everything stays calm except one dot — a single warm accent turns the contact point
into the mark's one focal pop.

**What changed:** The contact dot recolors Water→WarnInk (`#B08A2E`, amber/gold) and grows 4.5→5.25
radius. Disc and needle are otherwise unchanged from G.

**Color choice, argued rather than asserted:** The palette's only three genuinely warm hexes are
WarnInk `#B08A2E`, MagStrong `#F0663B`, and MagMajor `#C43A2F`. The latter two were deliberately ruled
out: both round 1's `rationale.md` and round 2's `round2-rationale.md` state explicitly that those two
are reserved for the app's magnitude-severity system, not brand identity — using either here would
blur exactly the line those documents protect, and would also walk straight into `research.md`'s single
most crowded competitor convention (alarm red/orange, used by 4 of 6 inspected marks). WarnInk is the
one remaining warm hex that is neither reserved nor the category cliché.

**Honest trade-off, flagged rather than hidden:** This is a real departure from the 3-hex budget
(Ink/Water/Safe) every other direction across all three rounds has held to — G4 uses four hexes. And
WarnInk itself already carries a "warning/caution" meaning elsewhere in the app's own design system
(`Tokens.kt` pairs it with `WarnBg` for warning banners) — using it as a neutral "focal accent" on a
brand mark sits in real tension with that existing meaning. If G4 is picked, that tension deserves an
explicit design-system-owner sign-off, not a silent assumption that "warm but not
MagStrong/MagMajor" fully resolves it.

**48px honesty:** Confirmed by the render — this is the variation where the one changed element
survives compression best, precisely because warm-vs-cool hue contrast holds up under
downsampling/antialiasing better than fine shape detail does. The amber dot reads clearly as a
deliberate accent at a true 48px raster, no caveat needed on the rendering itself — the caveat here is
entirely about the color-system trade-off above, not about legibility.

## G5 — `direction-g5.svg` — dial + shield-shoulder hint

**One-liner:** The dial's own dome quietly flattens and shoulders at the top — a nod to the user's
round-1 favorite (A's shield), folded into G without stacking a second shape on top of it.

**What changed:** Only the disc's own outline. The bottom/side ~290 degrees stay an exact r27 circle
(so the needle tip and dot, both on that untouched arc, keep identical rim clearance to G's own); the
top ~70 degrees (235deg-305deg, ±35deg off true 12 o'clock) is replaced by two mirrored cubic-bezier
curves rising from shoulder points *on the same r27 circle* — no added width — to a shared flat apex
2.4 units above the shoulders, with a genuinely horizontal tangent at the apex (a true flat-top moment,
not a disguised corner). This is the one variation that touches neither color nor the needle/dot — it
adds no shape and no hex, making it the cleanest of the five against the 3-hex budget, cleaner even
than G1's proportion tweaks.

**Why quiet rather than literal:** The brief was to marry G with A "quietly" — this deliberately does
not stamp A's actual shield silhouette onto the disc (that would stack two devices into one mark, the
exact busyness `research.md` flags in 4 of 6 competitor icons: globe+waveform, volcano+waveform). Only
the *character* of A's shoulder-then-flatter-apex profile is echoed, re-expressed at the disc's own
r27 scale — not a single coordinate is borrowed from `direction-a.svg` directly, since that shield
lives at a different absolute scale and center.

**48px honesty — the direction to be most candid about, per this task's own instruction not to force
it if it turns muddy:** Judged directly against the rendered PNGs, not assumed. At 512px (see
`logo-round3.png`'s G5 column against G's own for a direct comparison), the flattened top and
shoulders are genuinely visible once compared side-by-side against a true circle — it reads as a
deliberate soft silhouette, not an accident or a muddy blob; it does not, on its own, without that
side-by-side or without the app's own context, unambiguously read as "shield" the way A's actual shield
does — an honest ambiguity, not a failure, in the same spirit as round 2's disclosure that E's arc
could first read as a camera aperture. At a true 48px raster, the flattening compresses further: it is
still just barely perceptible on close inspection next to a plain circle, but a viewer with no
side-by-side reference would most likely perceive "a circle, possibly slightly imperfect" rather than
consciously registering a shield reference at all. **Verdict: this does not fail outright — it isn't
muddy or broken — but the "shield hint" reading depends on context/comparison more than any other
device in the G-family, and more than this task's "don't force it" bar was probably hoping to clear.**
Shipping it anyway as documented-weak rather than swapping it: the alternative considered (stamping
A's literal shield silhouette behind the dial) was rejected as the actual muddy option — two full
devices in one mark, which is a worse failure than "subtle to the point of near-invisibility." Between
"too quiet to read on its own" and "too busy to read cleanly," this round's honest judgment is that too
quiet is the smaller sin, but this is the one variation of the five most worth a real device/launcher
check, or a second, bolder attempt at the same idea, before it goes further.

## Cross-cutting notes

- **One change per variation.** G1 tunes proportions only; G2 adds ticks only; G3 adds a trail only;
  G4 recolors the dot only; G5 reshapes the disc only. None stacks two changes into one variation —
  deliberately, so the comparison sheet isolates exactly what each idea contributes rather than
  presenting five entangled redesigns.
- **Hex budget, tallied honestly:** G1/G2/G3/G5 all stay inside the exact 3-hex budget (Ink/Water/Safe)
  every direction across all three rounds has held to. G4 is the sole exception (4 hexes), flagged
  explicitly above rather than folded in quietly.
- **Monochrome-able:** all five collapse to flat shapes/strokes with no gradients, same as G itself —
  G2's ticks and G3's trail are both painted as same-color-at-reduced-opacity rather than a true alpha
  cutout (same deferred note rounds 1-2 left on other directions' cutouts/paint-overs); a real
  themed-icon implementation would want G2/G3's opacity effects re-expressed as true alpha, and G5's
  custom disc path re-expressed as a single compound path (it already is one in the SVG).
- **Not done this round, disclosed rather than silently skipped:** no real Android device/launcher
  verification for any of the six columns on the sheet — same honest gap rounds 1-2 both left. The
  Claude Browser pane was available this session (unlike round 2, where it was unreachable) but was
  used only as a live preview of the individual SVG files as they were authored, not as a systematic
  device/OEM-mask/themed-icon cross-check against the final rendered sheet — that real-device check
  remains outstanding for whichever variation is picked next, and is the specific follow-up G5's own
  honesty section above calls out most strongly.
