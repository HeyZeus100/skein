#!/usr/bin/env python3
"""Regenerate Skein's launcher icon resources from the owner's artwork.

skein-t9h2: the app previously shipped with the Android placeholder icon
(`@android:drawable/sym_def_app_icon`). This script derives a real
adaptive icon (foreground + monochrome layers, background colour) plus
legacy `mipmap-mdpi`..`mipmap-xxxhdpi` PNGs from the single source image
at docs/branding/skein-icon-app-logo.png.

Deterministic by construction: the script performs pure, seed-free
resize/composite/mask operations against one fixed input file, so the
same script + same Pillow version + same source bytes always produce
byte-identical PNGs. The generated files are committed to the repo (like
any other Android resource) rather than produced at build time, so
`tools/rb/*` reproducible-build verification never has to re-run this
script or worry about its determinism.

Usage:
    python3 tools/icons/generate_launcher_icons.py

Requires: Pillow (checked at import time; this Mac has 12.2.0).
"""

from __future__ import annotations

import pathlib

from PIL import Image, ImageDraw

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = REPO_ROOT / "docs" / "branding" / "skein-icon-app-logo.png"
RES = REPO_ROOT / "app" / "src" / "main" / "res"

# Sampled from the artwork's own fully-opaque fill (bd note skein-t9h2):
# the swirl sits on a pure-black backdrop, so black is "a solid background
# colour taken from the artwork" per the bead's deliverable. Kept in sync
# with app/src/main/res/values/colors.xml's ic_launcher_background.
BACKGROUND_COLOR = (0, 0, 0, 255)

# Android adaptive icon spec: 108dp full canvas, 66dp safe zone that
# survives every launcher mask (circle, squircle, rounded square, ...).
ADAPTIVE_CANVAS_DP = 108
SAFE_ZONE_DP = 66
SAFE_ZONE_RATIO = SAFE_ZONE_DP / ADAPTIVE_CANVAS_DP

# Legacy (pre-API-26) launcher icon base size.
LEGACY_BASE_DP = 48

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def save_png(image: Image.Image, path: pathlib.Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # optimize=True picks the smallest deterministic zlib encoding for a
    # given Pillow/zlib pair; no tEXt/time metadata is written, so repeat
    # runs on unchanged input produce byte-identical files.
    image.save(path, format="PNG", optimize=True)


def art_scaled_to(source: Image.Image, safe_px: int) -> Image.Image:
    return source.resize((safe_px, safe_px), Image.LANCZOS)


def luma_channel(rgba: Image.Image) -> Image.Image:
    """The artwork is a grayscale swirl (R==G==B, up to resize rounding);
    collapsing it to a single luminance channel before saving is a lossless
    (to the eye) way to roughly halve every PNG's byte count versus keeping
    three redundant RGB channels -- this is what keeps the APK delta inside
    the bead's 200KB budget (bd note skein-t9h2: RGBA versions came in
    around 240KB, well over budget)."""
    return rgba.convert("L")


def make_foreground(source: Image.Image, canvas_size: int) -> Image.Image:
    """Adaptive-icon foreground layer: artwork inset to the 66dp safe zone
    on an otherwise fully transparent 108dp canvas. Returned as RGBA so
    callers can still pull its alpha channel out (monochrome layer); it is
    flattened to LA (luma + alpha) only at save time."""
    safe_px = round(canvas_size * SAFE_ZONE_RATIO)
    art = art_scaled_to(source, safe_px)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    offset = (canvas_size - safe_px) // 2
    canvas.paste(art, (offset, offset), art)
    return canvas


def foreground_to_la(foreground: Image.Image) -> Image.Image:
    return Image.merge("LA", (luma_channel(foreground), foreground.split()[3]))


def make_monochrome_la(foreground: Image.Image) -> Image.Image:
    """Android 13+ themed-icon layer: flat white silhouette (the launcher
    tints it) carrying the foreground's own alpha -- saved as LA, since the
    luminance value itself is thrown away by the system tint anyway."""
    alpha = foreground.split()[3]
    white = Image.new("L", foreground.size, 255)
    return Image.merge("LA", (white, alpha))


def make_legacy_opaque_l(source: Image.Image, size: int) -> Image.Image:
    """Pre-API-26 square launcher icon: background + foreground baked
    together (legacy launchers never apply an adaptive mask themselves),
    saved as flat grayscale with no alpha channel -- the whole canvas is
    already fully opaque, so an alpha channel would be pure waste."""
    baked = Image.new("RGBA", (size, size), BACKGROUND_COLOR)
    safe_px = round(size * SAFE_ZONE_RATIO)
    art = art_scaled_to(source, safe_px)
    offset = (size - safe_px) // 2
    baked.paste(art, (offset, offset), art)
    return luma_channel(baked)


def make_legacy_round_la(legacy_l: Image.Image) -> Image.Image:
    """Round counterpart of make_legacy_opaque_l: the same baked square,
    clipped to a circle (legacy round icons ship pre-masked) -- this one
    does need an alpha channel, for the area outside the circle."""
    size = legacy_l.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return Image.merge("LA", (legacy_l, mask))


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")

    for density, scale in DENSITIES.items():
        mipmap_dir = RES / f"mipmap-{density}"

        adaptive_size = round(ADAPTIVE_CANVAS_DP * scale)
        foreground = make_foreground(source, adaptive_size)
        save_png(foreground_to_la(foreground), mipmap_dir / "ic_launcher_foreground.png")
        save_png(make_monochrome_la(foreground), mipmap_dir / "ic_launcher_monochrome.png")

        legacy_size = round(LEGACY_BASE_DP * scale)
        legacy_l = make_legacy_opaque_l(source, legacy_size)
        save_png(legacy_l, mipmap_dir / "ic_launcher.png")
        save_png(make_legacy_round_la(legacy_l), mipmap_dir / "ic_launcher_round.png")

    print(f"Generated launcher icons for densities: {', '.join(DENSITIES)}")


if __name__ == "__main__":
    main()
