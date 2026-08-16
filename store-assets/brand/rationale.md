# TerraWatch logo directions — rationale (Plan 5 Task 4)

Three original directions, evolved from the seeds in the plan, checked against `research.md`'s
findings. All three use only exact Calm Guardian hex values from
`core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/Tokens.kt` (Ink `#17222E`, Water
`#D9E9F4`, Safe `#2FA36B`). Renders: `logo-directions.png` (512px/48px, light/dark card, all 3
directions). This document is the design rationale only — **no direction has been applied to the
app**; that's the user's call at the gate this task stops at.

## Direction A — `direction-a.svg` — shield in a gapped ring

**One-liner:** The shipped mark, disciplined — same shield-in-a-ring DNA, but down to two colors
and one fewer shape, trading the old bolted-on accent dot for a single deliberate gap in the ring
itself.

**References / avoids:** References its own predecessor directly (this is the explicit
"evolve, don't copy blindly" seed) — same 108-unit safe-zone math, same ring-radius convention.
Avoids research.md's most crowded finding (red-badge-plus-seismograph-waveform, used by 4 of the 6
marks actually inspected) by construction, since it never touches that vocabulary at all; also
avoids the busyness those marks share by *removing* an element from the current shipped icon rather
than adding one.

**Monochrome/scalability:** Both shapes (ring-with-gap, shield) are simple flat silhouettes with no
gradient or fine internal detail — collapses to single-color exactly like the shipped
`ic_launcher_monochrome.xml` does today. Confirmed by rendering: holds up cleanly at a true 48px
raster (see comparison sheet) — the gap reads as clearly at small size as the shield does, which
was the specific bet this direction made (a gap can't turn to a muddy speck at small size the way
the old filled dot could). Ring recolored white→Ink versus the shipped mark specifically to raise
contrast against the pale Water-blue field, since the mark now leans on one fewer element to read
correctly.

## Direction B — `direction-b.svg` — concentric rings with a negative-space T

**One-liner:** Epicenter-ring convention, made ownable — the same "rings around a center" language
QuakeFeed's icon and real USGS/EMSC shake-contour maps both use, but built from Calm Guardian green
and ink instead of an alarm-orange gradient, with no waveform anywhere, and a "T" cut through the
rings as true negative space instead of an added letterform.

**References / avoids:** References the *broad* concentric-ring convention (grounded in real
shake-map cartography, not just QuakeFeed) but deliberately avoids QuakeFeed's specific execution
in every particular research.md flagged: no orange gradient, no red seismograph spike through the
center, no wordmark. The T device itself is not present in any of the 6 marks inspected.

**Monochrome/scalability:** This is the direction best suited to Android's monochrome/themed-icon
system in principle — the T is already true alpha (nothing drawn there) rather than a same-color
paint-over, so a themed-icon pass just recolors the three ring/disc shapes and the T stays exactly
as transparent as it already is. The honest caveat, visible directly in the rendered comparison
sheet and worth stating plainly rather than glossed over: at 512px the T reads clearly once you
read the negative space (it looks distinctly like a "T", or arguably closer to a stylized Greek
"phi" / capacitor glyph on first glance before the letterform lands); at a true 48px raster it
compresses further, to a cross-shaped notch in the rings rather than a crisp letterform. That's a
legible, distinctive small icon either way, but if this direction is picked, it deserves a real
device/launcher check before treating the "T" reading as guaranteed at every size — see the 48px
row on the comparison sheet and judge for yourself rather than taking this write-up's word for it.
Implementation note for later (not done here, out of scope for this task): the SVG punches the T
via a real `<mask>` element, which Android's VectorDrawable format doesn't fully support — a real
implementation would re-express the cutout as compound paths with `android:fillType="evenOdd"`.

## Direction C — `direction-c.svg` — a fault-step horizon

**One-liner:** The most abstract of the three and the only one with no circular badge framing at
all — a level horizon with one centered, symmetric fault-block step, reading as both "geological
fault" and "calm skyline" at once, and a literal picture of the app's own "Know the ground beneath
you" onboarding line.

**References / avoids:** References real fault-block ("horst/graben") diagrams from geology, which
is a step away from every researched competitor — none of the 5 marks actually inspected in
research.md use a horizon or landscape motif at all; they're uniformly circular badges. Explicitly
avoids the "cracked ground" cliché named in the brief: this is one clean line with exactly two
right-angle jogs (the ground moved, then settled level again), not chaotic multi-angle spiderweb
cracking.

**Monochrome/scalability:** The one direction where monochrome needs a genuinely different asset
rather than a simple recolor, and it's worth being direct about that rather than hand-waving it: a
naive single-color pass over the two fills would erase the sky/ground distinction entirely (both
regions become the same flat color, the horizon disappears). The themed-icon version of this
direction should carry *only* the Ink seam line with both fills dropped, degrading to a simple
line-icon of the fault-step rather than a silhouette of the full two-tone shape. At true 48px this
is, somewhat counter-intuitively, the strongest performer of the three — see the comparison sheet —
because the whole device is a single high-contrast step rather than a shape with internal detail
that can degrade.

## Cross-cutting notes

- All three share the exact same Water-blue field as the shipped icon's own background convention
  (continuity across "3 directions, same brand" rather than 3 unrelated logos), except where
  direction C's two-tone horizon composition makes that field the "sky" half of the mark itself.
- None of the three uses `MagStrong`/`MagMajor` orange/red anywhere. This was a deliberate reading
  of research.md's synthesis: alarm red is this category's single most crowded convention, and it's
  also not a "brand identity" color in this app's own token set today (`MagMajor` is reserved for
  the magnitude-severity system per `Tokens.kt`'s own kdoc) — keeping it out of the mark keeps that
  separation intact rather than accidentally blurring "this app's brand" with "this quake was
  severe."
- Real device/launcher verification (different OEM adaptive-icon mask shapes, actual themed-icon
  recoloring, real notification tray at true small size) has not happened for any direction — these
  are honest Pillow rasterizations of the hand-authored SVG geometry (see `research.md`'s renderer
  note for why Pillow, not a general SVG rasterizer), not a substitute for seeing them on a real
  Android device.
