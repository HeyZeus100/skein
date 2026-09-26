#!/usr/bin/env bash
#
# skein-xtov.23.4 (DS4, docs/ux/DESIGN_SYSTEM.md §3) — builds Skein Sans and
# Skein Mono, the Latin subsets of IBM Plex that `:core:designsystem` bundles
# in `core/designsystem/src/main/res/font/`.
#
# Licence (OFL-1.1 §3, Reserved Font Name "Plex"): subsetting and instancing
# make a Modified Version, which may not use the reserved name, so every
# `name` record that says "Plex" is rewritten to "Skein Sans"/"Skein Mono"
# (or dropped: the Plex trademark line). The script fails if any "plex"
# survives. The copyright notice and licence text ship unchanged in
# `core/designsystem/src/main/assets/fonts/OFL.txt`; see NOTICE.
#
# Upstream (IBM's own GitHub releases, pinned by SHA-256 below):
#   @ibm/plex-sans-variable@0.2.0  plex-sans-variable.zip  (font version 3.000;
#       axes wght 100–700, wdth 85–100)
#   @ibm/plex-mono@2.5.0           ibm-plex-mono.zip       (font version 2.005)
#
# Output (subset, hinting dropped, glyph names dropped):
#   skein_sans.ttf         Plex Sans Var roman, wdth pinned to 100, wght kept 400–600
#   skein_sans_italic.ttf  Plex Sans Var italic, static instance wght 400
#   skein_mono.ttf         Plex Mono Regular
# Expected bytes (2026-09-26): 136600 / 91116 / 54440 = 282156 on disk,
# 125133 deflated (level 9, what the APK stores) against 561796 / 240079 for
# the four unmodified Plex Mono files they replace.
#   skein_sans.ttf         0070220cde6ebf1d6310750cde44fef37ce453e3504e2aeef35401eddcb8ddc5
#   skein_sans_italic.ttf  f2df03c6de8acba8ce4b7739ca39b2cde44bf5aaa255cb4c937b902413753bfc
#   skein_mono.ttf         d68f62574fd75891302123d6aee9ef0a1f754823ca8ddafe2a94cbb31c50a683
#
# Variable-axis spike (DESIGN_SYSTEM §3.1 "verify first"), 2026-09-26: the
# variable roman was loaded through Compose `Font(R.font.skein_sans, weight,
# variationSettings = FontVariation.Settings(FontVariation.weight(n)))` under
# Robolectric native graphics (SDK 34, the Roborazzi renderer) and measured:
# see RESULT below and `SkeinTypeRenderingTest` in :feature:shell, which keeps
# checking that 400/500/600 render distinctly. minSdk is 30, and Android has
# honoured `fvar` axes through `Typeface`/`Paint.setFontVariationSettings`
# since API 26, so devices take the same path.
#   RESULT: the axis is honoured, so the variable file ships (no static
#   instances). "Hamburgefonstiv 0123456789" at 100 px measured 1394 / 1411 /
#   1424 px at wght 400 / 500 / 600 — the font's own advances at those
#   instances (1397.4 / 1414.5 / 1425.9, before kerning). Without the axis all
#   three would be identical (no synthetic bold: each weight is declared).
#
# Subset (§3.1): Basic Latin, Latin-1 Supplement, Latin Extended-A/-B, Latin
# Extended Additional (Vietnamese), General Punctuation, € ₹ ™, − ≈ ≠ ≤ ≥,
# arrows U+2190–2199, plus Combining Diacritical Marks U+0300–036F so
# decomposed (NFD) Latin text — e.g. macOS file names — keeps its accents in
# the same face. Skein Mono also keeps Box Drawing and Block Elements
# U+2500–259F: code and logs (`tree` output, tables) must stay aligned, which a
# fallback face cannot do. No Greek/Cyrillic (owner decision DS-e): other
# scripts fall back to the platform's Noto. OpenType features kept:
# kern liga ccmp locl mark mkmk lnum frac sups subs zero (+ rvrn, which the
# variable font requires). Plex figures are tabular by default (all digits
# 600 units; checked below), so no `tnum` is needed.
#
# Tools (recorded 2026-09-26): Python 3.14.4, fontTools 4.66.0 (pinned below),
# curl, unzip. The venv lives under ./.agent-logs (git-ignored) unless
# SKEIN_FONTS_VENV says otherwise; nothing is installed globally. Output bytes
# are deterministic (SOURCE_DATE_EPOCH, no timestamp recalculation): re-running
# the script must leave `git status` clean for res/font.
#
# Usage: tools/fonts/build-skein-fonts.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/core/designsystem/src/main/res/font"
WORK="${SKEIN_FONTS_WORK:-$ROOT/.agent-logs/fonts-work}"
VENV="${SKEIN_FONTS_VENV:-$ROOT/.agent-logs/fonts-venv}"
FONTTOOLS_VERSION="4.66.0"

SANS_URL="https://github.com/IBM/plex/releases/download/%40ibm%2Fplex-sans-variable%400.2.0/plex-sans-variable.zip"
SANS_SHA256="f83825d527be6cd39c8971c932b9bf22688a3ad3e5ac6305b6143d02f52b87b6"
MONO_URL="https://github.com/IBM/plex/releases/download/%40ibm%2Fplex-mono%402.5.0/ibm-plex-mono.zip"
MONO_SHA256="6d23f01257663d8cc49a0d64c22ced630b79e0e2a0ac08a0da86e9a38bbc481c"

sha256() {
  if command -v shasum > /dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

fetch() {
  local url="$1" want="$2" dest="$3"
  [ -f "$dest" ] || curl -sSL --fail --max-time 300 -o "$dest" "$url"
  local got
  got="$(sha256 "$dest")"
  if [ "$got" != "$want" ]; then
    echo "SHA-256 mismatch for $url: got $got, want $want" >&2
    rm -f "$dest"
    exit 1
  fi
}

mkdir -p "$WORK" "$OUT"
fetch "$SANS_URL" "$SANS_SHA256" "$WORK/plex-sans-variable-0.2.0.zip"
fetch "$MONO_URL" "$MONO_SHA256" "$WORK/ibm-plex-mono-2.5.0.zip"

unzip -o -q -j "$WORK/plex-sans-variable-0.2.0.zip" \
  "fonts/complete/ttf/IBM Plex Sans Var-Roman.ttf" "fonts/complete/ttf/IBM Plex Sans Var-Italic.ttf" -d "$WORK"
unzip -o -q -j "$WORK/ibm-plex-mono-2.5.0.zip" "ibm-plex-mono/fonts/complete/ttf/IBMPlexMono-Regular.ttf" -d "$WORK"

[ -x "$VENV/bin/python" ] || python3 -m venv "$VENV"
"$VENV/bin/python" -c "import fontTools, sys; sys.exit(fontTools.version != '$FONTTOOLS_VERSION')" 2> /dev/null ||
  "$VENV/bin/pip" install --quiet --disable-pip-version-check "fonttools==$FONTTOOLS_VERSION"

export SOURCE_DATE_EPOCH=1790000000
"$VENV/bin/python" - "$WORK" "$OUT" << 'PY'
import io
import sys
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

work, out = sys.argv[1], sys.argv[2]

LATIN = (
    list(range(0x20, 0x7F)) + list(range(0xA0, 0x250)) + list(range(0x300, 0x370))
    + list(range(0x1E00, 0x1F00)) + list(range(0x2000, 0x2070))
    + [0x20AC, 0x20B9, 0x2122, 0x2212, 0x2248, 0x2260, 0x2264, 0x2265] + list(range(0x2190, 0x219A))
)
BOXES = list(range(0x2500, 0x25A0))
FEATURES = ["kern", "liga", "ccmp", "locl", "mark", "mkmk", "lnum", "frac", "sups", "subs", "zero", "rvrn"]


def build(src, dst, family, ps_family, style, unicodes, axes=None):
    font = TTFont(f"{work}/{src}", recalcTimestamp=False)
    if axes:
        # Round-trip through bytes: the subsetter trips over the instancer's in-memory gvar.
        buffer = io.BytesIO()
        instancer.instantiateVariableFont(font, axes).save(buffer)
        buffer.seek(0)
        font = TTFont(buffer, recalcTimestamp=False)
    options = subset.Options()
    options.layout_features = FEATURES
    options.hinting = False
    options.glyph_names = False
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=unicodes)
    subsetter.subset(font)
    rename(font, family, ps_family, style)
    check(font, dst)
    font.save(f"{out}/{dst}")


def rename(font, family, ps_family, style):
    """OFL §3: the Modified Version drops the Reserved Font Name "Plex"."""
    name = font["name"]
    name.removeNames(nameID=7)  # "IBM Plex® is a trademark of IBM Corp…"
    name.removeNames(nameID=16)
    name.removeNames(nameID=17)
    for record in name.names:
        text = record.toUnicode()
        for old, new in (
            ("IBM Plex Sans Var", family), ("IBMPlexSansVar", ps_family),
            ("IBM Plex Mono", family), ("IBMPlexMono", ps_family),
        ):
            text = text.replace(old, new)
        record.string = text
    ps_style = style.replace(" ", "")
    version = name.getDebugName(5)
    name.setName(family, 1, 3, 1, 0x409)
    name.setName(style, 2, 3, 1, 0x409)
    name.setName(f"Skein;{ps_family}-{ps_style};{version}", 3, 3, 1, 0x409)
    name.setName(f"{family} {style}", 4, 3, 1, 0x409)
    name.setName(f"{ps_family}-{ps_style}", 6, 3, 1, 0x409)
    if "fvar" in font:
        name.setName(ps_family, 25, 3, 1, 0x409)


def check(font, dst):
    leaks = [r.toUnicode() for r in font["name"].names if "plex" in r.toUnicode().lower()]
    assert not leaks, f"{dst}: Reserved Font Name survives in name table: {leaks}"
    cmap, hmtx = font.getBestCmap(), font["hmtx"]
    digits = {hmtx[cmap[ord(c)]][0] for c in "0123456789"}
    assert digits == {600}, f"{dst}: figures are not tabular: {digits}"
    hhea = font["hhea"]
    assert (font["head"].unitsPerEm, hhea.ascent, hhea.descent) == (1000, 1025, -275), f"{dst}: vertical metrics moved"


build("IBM Plex Sans Var-Roman.ttf", "skein_sans.ttf", "Skein Sans", "SkeinSans", "Regular", LATIN,
      axes={"wdth": 100, "wght": (400, 600)})
build("IBM Plex Sans Var-Italic.ttf", "skein_sans_italic.ttf", "Skein Sans", "SkeinSans", "Italic", LATIN,
      axes={"wdth": 100, "wght": 400})
build("IBMPlexMono-Regular.ttf", "skein_mono.ttf", "Skein Mono", "SkeinMono", "Regular", LATIN + BOXES)
PY

for f in skein_sans.ttf skein_sans_italic.ttf skein_mono.ttf; do
  printf '%-22s %7s bytes  sha256 %s\n' "$f" "$(wc -c < "$OUT/$f" | tr -d ' ')" "$(sha256 "$OUT/$f")"
done
