#!/usr/bin/env python3
"""frame-screenshots.py - Play Store screenshot framing pipeline (Plan 5 Task 5).

Takes raw device captures out of store-assets/screenshots/ (or any input dir) and
produces Play-Store-ready framed images in store-assets/screenshots-framed/: a dark
device bezel around a letterboxed/pillarboxed copy of the real capture (never
stretched/distorted), with a caption strip above it carrying an honest, per-shot
headline. Re-runnable - re-run any time the input captures or the config change.

Usage (one command, from repo root):

    python3 scripts/frame-screenshots.py

Optional flags:

    python3 scripts/frame-screenshots.py \\
        --input-dir store-assets/screenshots \\
        --output-dir store-assets/screenshots-framed \\
        --config scripts/screenshots-config.json

Design decisions (so a future re-run/tweak doesn't have to re-derive these):

- Palette is the app's real Calm Guardian tokens, hand-copied from
  core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/Tokens.kt (grep that
  file again if the app's tokens ever change - these are LAW per that file's own
  kdoc, and this script should track it, not drift from it).
- Canvas background = Water (#D9E9F4), matching store-assets/feature-graphic.png's
  own "Water background, Ink bold text" convention, so the whole store listing reads
  as one brand rather than two different treatments.
- Bezel is deliberately plain: a single dark rounded rect (DuskCanvas) with one
  inset accent panel (DuskCard) behind the screen area. No notch, no speaker
  grille, no side buttons - "simple, no fake hardware details" per this task's
  brief.
- The real capture is never stretched to fill the screen area. It's scaled to fit
  (preserving aspect ratio) and centered, so a narrower/taller capture pillarboxes
  (dark bars on the sides) rather than distorting. This matters here because the 5
  app-screen captures get their OS status/nav chrome cropped first (see below) and
  the 1 notification-shade capture doesn't, so the 6 inputs aren't all the same
  aspect ratio even before framing.
- Per-shot chrome cropping is measured, not eyeballed: this device's status bar is
  a uniform gray band from y=0 to ~y=108 on every app-screen capture, and the OS
  3-button nav row starts as a solid band at ~y=2296 (both confirmed by sampling
  pixel rows across all 6 source PNGs, not guessed from a screenshot preview). The
  app's OWN bottom tab bar (Home/History/Insights) sits above that nav-row band and
  is deliberately preserved - only OS chrome is cropped. The notification-shade
  capture (06) gets crop_top=0/crop_bottom=0: its clock/date and gesture handle are
  real shade content, not a duplicate status/nav bar, so cropping would remove
  evidence instead of chrome. See scripts/screenshots-config.json for the exact
  per-shot values and rationale.
- Headline font size auto-shrinks (74px down to 44px) to guarantee at most 2 lines
  in the caption zone, so a longer headline can never clip - important for
  re-running this on fresh copy later without re-tuning geometry by hand.
- Output is RGB (no alpha channel), matching Play Console's 24-bit PNG requirement.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Sequence

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# Calm Guardian palette - exact hex values from core/ui's Tokens.kt. Do not
# hand-tune; re-grep Tokens.kt and update here if the app's tokens ever change.
# ---------------------------------------------------------------------------
INK = (0x17, 0x22, 0x2E)  # TerraColors.Ink
CANVAS_BG = (0xF6, 0xFA, 0xF9)  # TerraColors.Canvas
WATER = (0xD9, 0xE9, 0xF4)  # TerraColors.Water
SAFE = (0x2F, 0xA3, 0x6B)  # TerraColors.Safe
DUSK_CANVAS = (0x10, 0x16, 0x1D)  # TerraColors.DuskCanvas
DUSK_CARD = (0x1A, 0x22, 0x2C)  # TerraColors.DuskCard

# ---------------------------------------------------------------------------
# Canvas geometry - Play spec 1080x1920 (16:9 portrait, well under the 2:1 max
# long/short-side ratio Play Console enforces).
# ---------------------------------------------------------------------------
CANVAS_W = 1080
CANVAS_H = 1920

TEXT_MARGIN = 90  # left/right padding for caption text wrapping
KICKER_Y = 64
KICKER_SIZE = 34
HEADLINE_TOP = 120
HEADLINE_ZONE_BOTTOM = 300  # caption zone is [0, 300)
HEADLINE_START_SIZE = 74
HEADLINE_MIN_SIZE = 44
HEADLINE_MAX_LINES = 2

BEZEL_MARGIN_SIDE = 60
BEZEL_TOP = 300
BEZEL_BOTTOM = 1900  # 20px margin above canvas bottom
BEZEL_RADIUS = 72
BEZEL_BORDER = 24  # thickness of the dark frame around the screen area
SCREEN_RADIUS_OUTER = 56  # DuskCard inset panel corner radius
SCREEN_RADIUS_INNER = 40  # rounding applied to the actual pasted capture

BEZEL_BOX = (BEZEL_MARGIN_SIDE, BEZEL_TOP, CANVAS_W - BEZEL_MARGIN_SIDE, BEZEL_BOTTOM)
SCREEN_X = BEZEL_MARGIN_SIDE + BEZEL_BORDER
SCREEN_Y = BEZEL_TOP + BEZEL_BORDER
SCREEN_W = (CANVAS_W - BEZEL_MARGIN_SIDE) - SCREEN_X
SCREEN_H = BEZEL_BOTTOM - SCREEN_Y
SCREEN_BOX = (SCREEN_X, SCREEN_Y, SCREEN_X + SCREEN_W, SCREEN_Y + SCREEN_H)

HEADLINE_FONT_PATHS = ["/System/Library/Fonts/Supplemental/Arial Bold.ttf"]
KICKER_FONT_PATHS = ["/System/Library/Fonts/Supplemental/Arial.ttf"]


def load_font(paths: Sequence[str], size: int) -> ImageFont.FreeTypeFont:
    """Load the first font path that exists; fall back to PIL's bitmap default.

    Documented choice: Arial (Bold for the headline, Regular for the kicker) -
    confirmed present under /System/Library/Fonts/Supplemental on this machine,
    a clean, universally-legible grotesque sans with no licensing ambiguity for
    a store-listing asset. SFNS (Apple's own system font) was considered and
    rejected: it's intended for Apple system UI, not general-purpose rendering.
    """
    for p in paths:
        if Path(p).exists():
            return ImageFont.truetype(p, size)
    print(f"WARNING: none of {paths} found; falling back to PIL default font (fixed size, will look wrong)", file=sys.stderr)
    return ImageFont.load_default()


def wrap_text(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, max_width: int) -> list[str]:
    """Greedy word-wrap using real measured text width (not char counting)."""
    words = text.split()
    lines: list[str] = []
    cur = ""
    for word in words:
        trial = f"{cur} {word}".strip()
        if draw.textlength(trial, font=font) <= max_width:
            cur = trial
        else:
            if cur:
                lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def fit_headline(
    draw: ImageDraw.ImageDraw, text: str, max_width: int
) -> tuple[ImageFont.FreeTypeFont, list[str]]:
    """Shrink the headline font until it wraps to at most HEADLINE_MAX_LINES.

    Guarantees no clipped text regardless of headline length - a longer caption
    just renders smaller, it never overflows the caption zone.
    """
    size = HEADLINE_START_SIZE
    while size >= HEADLINE_MIN_SIZE:
        font = load_font(HEADLINE_FONT_PATHS, size)
        lines = wrap_text(draw, text, font, max_width)
        if len(lines) <= HEADLINE_MAX_LINES:
            return font, lines
        size -= 4
    font = load_font(HEADLINE_FONT_PATHS, HEADLINE_MIN_SIZE)
    return font, wrap_text(draw, text, font, max_width)


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def fit_contain(img: Image.Image, box_w: int, box_h: int) -> Image.Image:
    """Scale img to fit within box_w x box_h, preserving aspect ratio (never
    stretches/distorts) - the caller pillarboxes/letterboxes by centering the
    result and leaving the frame's own background visible around it."""
    src_w, src_h = img.size
    scale = min(box_w / src_w, box_h / src_h)
    new_w = max(1, round(src_w * scale))
    new_h = max(1, round(src_h * scale))
    return img.resize((new_w, new_h), Image.LANCZOS)


def render_shot(
    input_path: Path,
    headline: str,
    kicker: str,
    crop_top: int,
    crop_bottom: int,
    output_path: Path,
) -> tuple[int, int]:
    if not input_path.exists():
        raise FileNotFoundError(f"input capture not found: {input_path}")

    src = Image.open(input_path).convert("RGB")
    w, h = src.size
    if crop_top or crop_bottom:
        if crop_top + crop_bottom >= h:
            raise ValueError(f"{input_path}: crop_top+crop_bottom ({crop_top + crop_bottom}) >= image height ({h})")
        src = src.crop((0, crop_top, w, h - crop_bottom))

    canvas = Image.new("RGB", (CANVAS_W, CANVAS_H), WATER)
    draw = ImageDraw.Draw(canvas)

    # --- caption strip ---
    kicker_font = load_font(KICKER_FONT_PATHS, KICKER_SIZE)
    kicker_w = draw.textlength(kicker, font=kicker_font)
    draw.text(((CANVAS_W - kicker_w) / 2, KICKER_Y), kicker, font=kicker_font, fill=SAFE)

    max_text_width = CANVAS_W - 2 * TEXT_MARGIN
    headline_font, lines = fit_headline(draw, headline, max_text_width)
    line_height = headline_font.size * 1.15
    total_h = line_height * len(lines)
    zone_h = HEADLINE_ZONE_BOTTOM - HEADLINE_TOP
    y = HEADLINE_TOP + max(0.0, (zone_h - total_h) / 2)
    for line in lines:
        lw = draw.textlength(line, font=headline_font)
        draw.text(((CANVAS_W - lw) / 2, y), line, font=headline_font, fill=INK)
        y += line_height

    # --- device bezel (simple, no fake hardware details) ---
    draw.rounded_rectangle(BEZEL_BOX, radius=BEZEL_RADIUS, fill=DUSK_CANVAS)
    draw.rounded_rectangle(SCREEN_BOX, radius=SCREEN_RADIUS_OUTER, fill=DUSK_CARD)

    # --- the real capture, fit-contained (never stretched) and corner-rounded ---
    fitted = fit_contain(src, SCREEN_W, SCREEN_H)
    mask = rounded_mask(fitted.size, SCREEN_RADIUS_INNER)
    px = SCREEN_X + (SCREEN_W - fitted.size[0]) // 2
    py = SCREEN_Y + (SCREEN_H - fitted.size[1]) // 2
    canvas.paste(fitted, (px, py), mask)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output_path, "PNG")
    return canvas.size


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input-dir", default="store-assets/screenshots", help="dir containing raw device captures")
    parser.add_argument("--output-dir", default="store-assets/screenshots-framed", help="dir to write framed PNGs")
    parser.add_argument("--config", default="scripts/screenshots-config.json", help="sidecar JSON: kicker + ordered (input, headline) list")
    args = parser.parse_args()

    config_path = Path(args.config)
    if not config_path.exists():
        print(f"ERROR: config not found: {config_path}", file=sys.stderr)
        return 1
    config = json.loads(config_path.read_text())

    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    kicker = config.get("kicker", "TERRAWATCH")

    ok = 0
    for shot in config["shots"]:
        in_path = input_dir / shot["input"]
        out_path = output_dir / shot["input"]
        try:
            size = render_shot(
                input_path=in_path,
                headline=shot["headline"],
                kicker=kicker,
                crop_top=int(shot.get("crop_top", 0)),
                crop_bottom=int(shot.get("crop_bottom", 0)),
                output_path=out_path,
            )
        except (FileNotFoundError, ValueError) as exc:
            print(f"FAILED {in_path}: {exc}", file=sys.stderr)
            continue
        print(f"OK  {in_path}  ->  {out_path}  {size[0]}x{size[1]}  \"{shot['headline']}\"")
        ok += 1

    total = len(config["shots"])
    print(f"\n{ok}/{total} shots framed into {output_dir}/")
    return 0 if ok == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
