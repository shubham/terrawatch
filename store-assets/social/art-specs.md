# TerraWatch — social launch kit: art specs (Plan 5 Task 6, phase 1)

Exact production specs for phase 2 art (avatars, banners, IG posts). **Nothing here is produced yet —
this is the spec only.** Blocked on the Task 4 logo-direction pick (`store-assets/brand/direction-{a,b,c}.svg`,
user gate not yet resolved); every spec below is written to be direction-agnostic so that once a
direction is picked, phase 2 is mechanical — plug the winning mark into these exact canvases, no
re-deriving layout decisions.

All dimensions below were checked this session (WebSearch, current as of 2026), not assumed from
memory — cited per section.

## Shared palette (applies regardless of which direction wins)

Exact hex, `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/Tokens.kt` — same values
`store-assets/brand/rationale.md` confirms all 3 directions already use, and the same ones
`screenshots-framed/`'s own pipeline sources from:

| Token | Hex | Use |
|---|---|---|
| Ink | `#17222E` | wordmark text, ring/shield strokes (direction A/B), the seam line (direction C) |
| Water | `#D9E9F4` | field/background — matches `feature-graphic.png`'s existing convention |
| Safe | `#2FA36B` | accent (direction B's rings), kicker text (matches `screenshots-framed`'s "TERRAWATCH" kicker) |
| Canvas | `#F6FAF9` | light-card alternative background |
| DuskCanvas | `#10161D` | dark-card / bezel background (matches `screenshots-framed`'s device bezel) |
| DuskCard | `#1A222C` | inset bezel panel |

None of the three directions uses `MagStrong`/`MagMajor` red — deliberate (rationale.md), so no red
appears in any spec below either.

## YouTube

**Channel banner ("channel art"):** 2560×1440 px canvas. Safe area (guaranteed visible on every
device — mobile shows only this strip, desktop widens to 2560×423, TV shows the full canvas):
**1546×423 px, centered.**

Centering math (shown, not eyeballed): x-offset = (2560−1546)/2 = **507px exactly**; y-offset =
(1440−423)/2 = **508.5px** (not a whole pixel — round to 508 or 509, either is inside any reasonable
margin). Safe area therefore spans **x:[507, 2053], y:[508, 931]**. Put the mark + "TerraWatch"
wordmark centered inside that box; the full 2560×1440 canvas can carry a full-bleed Water-blue field
or a subtle extension of the mark's own background treatment behind it.

**Channel profile picture:** 800×800 px upload (displayed as a circle at 98×98; shrinks to as small
as **32×32 in comment threads** — smaller than the 48px raster already tested in
`store-assets/brand/logo-directions.png`. Whichever direction is picked, do one extra visual gut-check
at 32px specifically before finalizing this upload, since that's a smaller real-world size than
anything rendered in the existing comparison sheet).

**Video thumbnail** (bonus — not explicitly in this task's brief, but same "phase 2, logo-gated"
bucket, cheap to spec now): 1280×720 px, JPG/PNG/GIF, <2MB. Reuse the caption-strip + bezel visual
language from `scripts/frame-screenshots.py`'s existing output, cropped to 16:9 instead of the
framed-screenshot's 1080×1920 portrait canvas.

## Instagram

**Profile picture:** 320×320 px upload, displayed as a **circle** — keep the mark centered inside an
inscribed circle (roughly the central 90% diameter), since corner content in a square upload is
cropped away by the circular mask.

**Feed posts:** 1080×1080 px (1:1). **Spec tension worth naming rather than glossing over**: the
existing framed screenshots (`store-assets/screenshots-framed/*.png`) are 1080×1920 (portrait,
matching a phone screen) — not square. Fitting the *whole* framed image into a 1080×1080 square by
scaling to fit height means: scale factor = 1080/1920 = **0.5625**, scaled width = 1080×0.5625 =
**607.5px**, leaving **~236px pillarbox bars on each side** (Water or Ink fill, matching the
existing canvas convention) — the phone screenshot ends up fairly small and letterboxed in the middle
of the post.
- **Recommended**: re-run a square-native variant of `scripts/frame-screenshots.py`'s design language
  (same kicker + headline + bezel treatment, just re-proportioned so the caption strip + bezel
  together fill 1080×1080 instead of 1080×1920) — same inputs, same crop logic, different target
  canvas. This keeps the phone screenshot large and legible instead of a small pillarboxed rectangle.
- **Accepted v1 shortcut, if phase 2 is time-constrained**: pillarbox the existing 1080×1920 asset
  onto a 1080×1080 Water-blue canvas as computed above. Disclosed here as a known simplification
  (matching this branch's own established pattern of naming accepted shortcuts rather than silently
  shipping them), not the first choice.

## Threads

Shares Instagram's underlying account/profile picture (same 320×320 asset, no separate export needed)
per the plan brief's own "Threads (reuse IG)" — confirmed consistent with Threads' real identity model
(tied to the Instagram/Meta account graph) during this session's handle research.

## X (Twitter)

**Profile picture:** 400×400 px upload, displayed as a **circle** (same inscribed-circle-safe-zone
rule as Instagram's 320×320).

**Header ("banner") image:** 1500×500 px (3:1). Some devices crop roughly **60px off the top and
bottom** — safe vertical band is therefore **y:[60, 440] (380px tall)**, full width; keep the mark
and any wordmark inside that vertical band, ideally horizontally centered too since profile-picture
overlap eats the bottom-left corner on most X layouts.

## Avatar export summary (one master mark, sized per platform)

Once a direction is picked, one high-res master render (the same source used for
`store-assets/icon-1024.png`'s treatment) can be downscaled to every size below — no separate design
work per platform, purely mechanical export:

| Platform | Size | Shape shown as |
|---|---|---|
| YouTube channel banner | 2560×1440 (safe area 1546×423, centered) | rectangle |
| YouTube profile picture | 800×800 | circle |
| Instagram profile picture | 320×320 | circle |
| Threads profile picture | 320×320 (= Instagram's, reused) | circle |
| X profile picture | 400×400 | circle |
| X header | 1500×500 (safe band y:[60,440]) | rectangle |
| Instagram feed post | 1080×1080 | square |
| *(existing, for reference)* Play Store icon | 1024×1024 master / 512×512 upload | adaptive-icon-masked |

## What goes where

**Avatars/banners (all platforms):** the winning `direction-{a,b,c}.svg` mark (user's pick, still
open) + the Calm Guardian palette table above. No monochrome variant needed for social profile
pictures (unlike the Android launcher icon, which needs the themed-icon pass per `rationale.md`) —
platforms don't theme user avatars, so the full-color mark renders as-is everywhere.

**Framed screenshot → social post mapping** (already-captioned assets in
`store-assets/screenshots-framed/`, matching `copy.md`'s own per-post captions 1:1 so nothing here
needs re-deriving at execution time):

| Framed asset | Headline (already baked into the image) | Used for |
|---|---|---|
| `01-home-map.png` | "Live quakes worldwide" | IG/Threads launch post 1, X thread tweet 1/5 |
| `06-notification.png` | "Honest alerts" | IG/Threads launch post 2, X thread tweet 4/5 |
| `04-settings-radius-ring.png` | "Nearby, defined by you" | IG/Threads launch post 3, X thread tweet 5/5 |
| `02-detail-sheet.png` | "Every quake, the full picture" | link-in-bio page feature block (spare — not used in the 3 launch posts or thread) |
| `03-insights.png` | "Trends at a glance" | link-in-bio page feature block (spare) |
| `05-history.png` | "The full quake archive" | link-in-bio page feature block (spare) |

All 6 predate this branch's Plan 5 UI (my-location FAB, favorite chips, feed live-reveal chip) per
`screenshots-framed/README.md`'s own disclosed limitation — still the best captures that exist today.
If a fresh device pass captures any of the new UI before phase 2 executes, prefer swapping in updated
captures over these; the framing pipeline (`scripts/frame-screenshots.py`) re-runs cleanly on new
inputs with zero code change.
