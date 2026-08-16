# TerraWatch logo directions — round 2 rationale (Plan 5 Task 4)

User verdict on round 1: **direction A** (shield in a gapped ring) liked; **B** (concentric rings +
negative-space T) and **C** (fault-step horizon) rejected. Signal taken from the pick: a shield/
protective container plus geometric minimalism won; literal seismic metaphors (rings-as-epicenter,
fault-line-as-horizon) lost. `research.md`'s round-1 competitive findings still stand unchanged (re-
checked against each direction below, not re-fetched) — this round designs 4 **new** directions in
A's liked territory without cloning A itself, each varying both the container (what shape holds the
mark) and the earthquake tie (what, if anything, nods at seismic activity).

All four use only exact Calm Guardian hex values from
`core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/Tokens.kt` (grepped fresh this session):
Ink `#17222E`, Water `#D9E9F4`, Safe `#2FA36B` — the same three-hex budget round 1 held to, no
`MagStrong`/`MagMajor` orange/red anywhere (same reasoning as round 1: red is both the category's
single most crowded convention per `research.md` and this app's own reserved magnitude-severity
color, not a brand-identity color). Renders: `logo-round2.png`, 3340×2194px, 5 columns (A carried
over, then D/E/F/G) × 4 rows (light canvas/512px, light canvas/48px true raster, dark canvas/512px,
dark canvas/48px true raster). No direction has been applied to the app — this document is design
rationale only, same stop point round 1 left this task at.

## Direction A — carried over, unchanged

Not re-derived or redesigned — same `direction-a.svg` geometry from round 1 (shield in a gapped
ring, 2 colors, Ink ring recolored for contrast). Included as column 1 on `logo-round2.png` purely so
the user can compare the four new directions against the one they already liked, side by side, at
the same sizes/canvases. Full rationale for A is round 1's own `rationale.md` — not repeated here.

## Direction D — `direction-d.svg` — shield outline, one stroke displacement

**One-liner:** The shield itself carries the quake now — one unbroken stroked outline, no ring, no
fill, with a single honest jog where the line remembers moving.

**Seismic tie:** The earthquake reference is folded directly into the shield's own contour: the
outline is stroked as one continuous closed path, and on the right side only (about 60% of the way
down), the line steps out 4 units and back — a small, asymmetric stair-step displacement, not a
zigzag or spike. Asymmetric on purpose: an actual fault displacement moves one side, not both
symmetrically, so a mirrored pair of steps would have been the less honest choice.

**Why not derivative:** Varies A's own container (A = filled shield inside a separate stroked ring;
D = one stroked outline, no ring, no fill — a materially different construction, not a recolor) while
reusing A's/the shipped mark's actual shoulder/apex coordinates, so the family resemblance is real
continuity, not coincidence. Against `research.md`: none of the 6 marks actually inspected use an
outline-only shield, and the step is a single right-angle displacement, not the repeating zigzag
seismograph/ECG spike that 4 of those 6 marks use as their dominant device — the same "clean step, not
chaotic cracking" distinction round 1's direction C drew against the "cracked ground" cliché.

**48px honesty:** The shield silhouette itself holds up well at a true 48px raster — the pointed
base and rounded shoulders both read clearly. The displacement step is the part to be honest about:
it compresses to a small nub/tab on the right shoulder rather than a crisp two-corner step (see the
48px row on `logo-round2.png`) — legible as "something is there, deliberately," but a viewer would
need to already know to look for a step to read it as one rather than just an asymmetric bump. Round
joins (chosen to match the Calm Guardian family's soft, non-alarmist rounding elsewhere) soften the
corners further at small size; a sharper miter join was considered and rejected for consistency with
the rest of the set, but is worth revisiting if D is picked and the step needs to read more precisely
at launcher size.

## Direction E — `direction-e.svg` — globe tile: meridian arc + quake dot + one thin ring

**One-liner:** The world as a friendly rounded-square tile — one meridian curve, one dot, one halo:
a place worth watching, not a blast radius.

**Seismic tie:** A single quake dot with exactly one thin ring around it — a location marker closer
to a map-pin halo than an "epicenter blast," deliberately not a stack of concentric rings (that was
round 1's rejected direction B, and also QuakeFeed's own device per `research.md`). The meridian arc
alongside it is the "globe/earth" reference: one curve, not a lat/long grid.

**Why not derivative:** Container changes from A's circle to a rounded-square badge — a different
silhouette, not a recolor. `research.md` flags exactly one globe reference in the category
(Earthquake Network: a wireframe grid-sphere with a red seismograph spike through the middle) — this
is its near-opposite: one arc (not a grid), no red, no waveform, and square rather than circular in
the first place. The single-ring-around-a-dot device is genuine whitespace per `research.md`'s own
finding that the brief's assumed "red pins" cliché did not actually appear in any of the 5 marks
inspected.

**48px honesty:** This is the direction to be most candid about. At 512px it reads as intended
(tile + curve + marked location), but it also plausibly reads as an abstract camera-lens/aperture
icon before the "globe" context lands — an honest ambiguity, not a fatal flaw, but worth naming
rather than glossing over (the same spirit as round 1's disclosure that direction B's negative-space
T could first read as a Greek phi). At a true 48px raster the dot and its thin ring compress toward
each other — the ~3-unit gap between them at full size is roughly 1.4px at 48px, so "dot with a halo"
softens toward "dot with a faint fuzzy edge" (see the 48px row). The tile shape and the arc both
still read cleanly at 48px; the ring specifically is the element under the most size pressure.

## Direction F — `direction-f.svg` — bold T, one stepped crossbar

**One-liner:** TerraWatch, spelled in one letter — a bold geometric T whose crossbar took a single
clean step and kept going.

**Seismic tie:** The crossbar is not one straight bar — its right ~30% steps down by 6 units, a
single right-angle displacement, while the stem and the rest of the crossbar stay perfectly regular.
Same "one honest geometric discontinuity in an otherwise disciplined shape" principle as D, applied
to typography instead of a shield.

**Why not derivative:** The one direction that drops the shield container entirely rather than
evolving it — a deliberate brand-first bet (a letterform is the most reusable of the four outside the
Android launcher specifically: profile pictures, favicons, watermarks). None of the 6 marks actually
inspected in `research.md` use a single bold geometric letterform as their icon — LastQuake's
"CSEM/EMSC" is a stacked institutional wordmark, not a monogram glyph, and USGS's mark is a wordmark
beside a ribbon shape. A single "T" was chosen over a "TW" monogram (the brief offered either)
because a second counter/glyph is exactly the kind of extra small-gap detail that degrades first at
48dp.

**48px honesty:** The strongest 48px performer of the four, and it isn't close — confirmed by the
render, not assumed going in. The T reads perfectly clearly at 48px, and — better than the raw math
suggested going in (a straight 6-of-108-unit step is roughly 2.7px at 48px, which looked marginal on
paper) — the step in the crossbar is still visibly a step, not just noise, because the bar's own
straight edges give the eye a clean baseline to compare it against. No caveat needed here that isn't
also true at 512px.

## Direction G — `direction-g.svg` — dial: one sweep needle, one contact dot

**One-liner:** A quiet dial, one clean needle — TerraWatch keeps watch, so you don't have to stare
at the screen.

**Seismic tie:** Deliberately the loosest literal tie of the four, and that's the point: a
monitoring/"watch" metaphor (a dial with a needle and a marked contact point) rather than a
seismograph. No waveform, no rings-as-epicenter, no red — just one needle and one dot on a dark
scope face, echoing "watch" in the app's own name rather than picturing an earthquake at all.

**Why not derivative — including a real mistake caught and fixed:** Worth recording honestly rather
than presenting only the clean final answer: the first pass drew the "sweep" as a ~45° filled pie
wedge (a classic radar-scope beam) with the contact dot placed to one side. Rendered at both 512px
and a true 48px raster, it read unmistakably as a Pac-Man / cartoon face (wide wedge = mouth, dot =
eye) — confirmed by actually looking at the rendered PNGs, not a hypothetical worry. Fixed by
narrowing the sweep to a thin needle (a clock-hand line, not a wide angle that can read as a mouth)
and moving the dot to sit roughly diametrically opposite the needle rather than diagonally offset
from it, which is what let the two shapes triangulate into a face in the first place. Re-rendered
and re-checked: reads as a dial/gauge now, not a face. Against `research.md`: none of the 6 marks
inspected use a needle/dial device; the closest adjacent convention is literal weather-radar apps'
rotating sweep-over-a-map, which this avoids (no map, no color-coded intensity gradient, flat
2-position needle + dot only).

**48px honesty:** Reads cleanly as "dark disc, one diagonal mark, one small dot" at 48px — legible
and uncluttered, no face problem at small size either (re-confirmed after the fix, not just at
512px). The specific "dial/monitoring" reading is the part that needs the app's own name/context to
land fully; taken as a pure silhouette with zero context, "diagonal stroke + dot on a dark circle" is
recognizably a considered icon but not unambiguously "TerraWatch" the way F's letterform or A's
shield are. That's an honest trade-off for the calmest, least literal of the four, not a rendering
defect.

## Cross-cutting notes

- **One discontinuity, not zero and not many.** D and F both apply the same discipline: an otherwise
  perfectly regular geometric shape (shield outline, letterform) carries exactly one deliberate
  right-angle displacement as its entire earthquake reference. E and G take a softer version of the
  same idea (one dot+ring, one needle+dot) rather than a line displacement. None of the four stacks
  two or more devices into one mark — the specific busyness `research.md` flagged in 4 of 6
  competitor icons (globe+waveform, volcano+waveform, badge+waveform+continents) has no foothold in
  any of these.
- **Container variety was the actual brief, and it's real here:** an outline shield (D), a rounded-
  square tile (E), a letterform with no container at all (F), and a filled disc (G) — four distinct
  silhouettes, chosen so the comparison sheet shows genuinely different shapes, not four recolors of
  the same circle.
- **Water-blue field kept on all four** (`#D9E9F4`, full 108×108 bleed), matching A/the shipped mark's
  own background convention — continuity across "5 things on one sheet, one brand," not five
  unrelated logos.
- **Monochrome:** all four are flat shapes/strokes with no gradients — D, F, G collapse to a single
  color exactly like the shipped `ic_launcher_monochrome.xml` does today. E's arc/dot/ring are
  painted as same-color paint-over rather than a true alpha cutout (documented in the SVG's own
  comment) — a real adaptive-icon implementation would want that re-expressed as a true cutout
  (compound path, `evenOdd` fill), same deferred note round 1 left on direction B's T-cutout.
- **Not done this round, disclosed rather than silently skipped:** no real Android device/launcher
  verification (OEM mask shapes, actual monochrome recoloring, real notification tray) for any of the
  five directions on the sheet — same honest gap round 1 left. The Claude Browser pane, which round 1
  used to cross-check every SVG directly against its own Pillow rasterization, was not reachable this
  session (navigation to a local SVG file returned "Browser pane gone, gate off, or tab cap reached");
  verification here rests on the Pillow renders alone (both the individual per-direction test renders
  and the final composed sheet), which is what caught and fixed direction G's Pac-Man problem, but a
  browser-pane cross-check did not happen and is worth doing before any direction is picked.
