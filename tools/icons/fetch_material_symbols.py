#!/usr/bin/env python3
"""Fetch Material Symbols SVGs and convert them to Android vector drawables.

Source: https://github.com/google/material-design-icons (Apache-2.0),
pinned to the commit SHA below so the icon set is reproducible. Style is
fixed at weight 400 / grade 0 / optical size 24 / fill 0 (the outlined
default), with a fill-1 companion for the handful of concepts that need a
"selected" variant (DESIGN_SYSTEM.md §9.1).

Usage:
    python3 tools/icons/fetch_material_symbols.py

Re-run whenever ICONS below changes; it always re-downloads and overwrites,
so it is safe to run repeatedly. No third-party dependencies — stdlib only
(urllib, re, textwrap).
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# Pin: google/material-design-icons @ this commit (checked 2026-09-26 via
# `curl -s https://api.github.com/repos/google/material-design-icons/commits/master`).
UPSTREAM_SHA = "bd8cb85bd4bad964fe6918f79665bb40c3a8efef"
RAW_BASE = f"https://raw.githubusercontent.com/google/material-design-icons/{UPSTREAM_SHA}/symbols/web"

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = REPO_ROOT / "core/designsystem/src/main/res/drawable"

# Every Material Symbols icon this SVG grid is drawn on (verified against
# several fetched icons): viewBox="0 -960 960 960" -> a 960x960 viewport
# whose origin is shifted up by 960. A vector <group android:translateY="960">
# reproduces that shift without needing to rewrite the path data.
VIEWPORT = 960

# (drawable name, Material Symbol name, fill1 variant?)
ICONS: list[tuple[str, str, bool]] = [
    ("ic_skein_menu", "menu", False),
    ("ic_skein_back", "arrow_back", False),
    ("ic_skein_close", "close", False),
    ("ic_skein_new_chat", "edit_square", False),
    ("ic_skein_chat", "chat_bubble", True),
    ("ic_skein_knowledge", "library_books", True),
    ("ic_skein_note", "article", False),
    ("ic_skein_new_note", "note_add", False),
    ("ic_skein_file", "draft", False),
    ("ic_skein_pdf", "picture_as_pdf", False),
    ("ic_skein_image", "image", False),
    ("ic_skein_import_file", "file_open", False),
    ("ic_skein_ai_output", "auto_awesome", False),
    ("ic_skein_graph", "hub", True),
    ("ic_skein_model", "memory", True),
    ("ic_skein_persona", "person", False),
    ("ic_skein_settings", "settings", True),
    ("ic_skein_search", "search", False),
    ("ic_skein_more", "more_vert", False),
    ("ic_skein_attach", "add", False),
    ("ic_skein_attach_file", "attach_file", False),
    ("ic_skein_send", "arrow_upward", False),
    ("ic_skein_stop", "stop", False),
    ("ic_skein_retry", "refresh", False),
    ("ic_skein_copy", "content_copy", False),
    ("ic_skein_check", "check", False),
    ("ic_skein_context", "layers", False),
    ("ic_skein_sources", "format_quote", False),
    ("ic_skein_activity_failed", "error", False),
    ("ic_skein_expand", "chevron_right", False),
    ("ic_skein_delete", "delete", False),
    ("ic_skein_rename", "edit", False),
    ("ic_skein_open", "arrow_forward", False),
    ("ic_skein_share", "share", False),
    ("ic_skein_pin", "keep", False),
    ("ic_skein_lock", "lock", False),
    ("ic_skein_keyboard", "keyboard", False),
    ("ic_skein_link", "link", False),
    ("ic_skein_link_off", "link_off", False),
    ("ic_skein_warning", "warning", False),
    ("ic_skein_info", "info", False),
    ("ic_skein_success", "check_circle", False),
    ("ic_skein_jump_to_latest", "arrow_downward", False),
]

PATH_RE = re.compile(r'<path\s+d="([^"]+)"\s*/>')

VECTOR_TEMPLATE = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{viewport}"
    android:viewportHeight="{viewport}">
    <group android:translateY="{viewport}">
        <path
            android:fillColor="#FF000000"
            android:pathData="{path_data}" />
    </group>
</vector>
"""


def fetch_svg(symbol: str, fill1: bool) -> str:
    suffix = "_fill1_24px.svg" if fill1 else "_24px.svg"
    url = f"{RAW_BASE}/{symbol}/materialsymbolsoutlined/{symbol}{suffix}"
    # Shell out to curl rather than urllib: this machine's Python has no
    # working local CA bundle, curl does. No new Python dependency either way.
    result = subprocess.run(["curl", "-fsSL", url], capture_output=True, check=True)
    return result.stdout.decode("utf-8")


def svg_to_vector_xml(svg: str) -> str:
    match = PATH_RE.search(svg)
    if not match:
        raise ValueError(f"no <path d=\"...\"/> found in SVG: {svg[:200]}")
    path_data = match.group(1)
    return VECTOR_TEMPLATE.format(viewport=VIEWPORT, path_data=path_data)


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    written = []
    for drawable_name, symbol, has_fill1 in ICONS:
        svg = fetch_svg(symbol, fill1=False)
        xml = svg_to_vector_xml(svg)
        out_path = OUT_DIR / f"{drawable_name}.xml"
        out_path.write_text(xml, encoding="utf-8")
        written.append(out_path)
        print(f"wrote {out_path.relative_to(REPO_ROOT)}  (Material Symbol: {symbol})")

        if has_fill1:
            svg_filled = fetch_svg(symbol, fill1=True)
            xml_filled = svg_to_vector_xml(svg_filled)
            out_path_filled = OUT_DIR / f"{drawable_name}_filled.xml"
            out_path_filled.write_text(xml_filled, encoding="utf-8")
            written.append(out_path_filled)
            print(f"wrote {out_path_filled.relative_to(REPO_ROOT)}  (Material Symbol: {symbol} fill1)")

    print(f"\n{len(written)} drawables written from google/material-design-icons@{UPSTREAM_SHA}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
