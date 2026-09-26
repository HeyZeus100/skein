#!/usr/bin/env python3
"""WCAG 2.x contrast check for the Skein design-system colour tokens.

Source of truth for the values in docs/ux/DESIGN_SYSTEM.md §6. The Wave 2
`SkeinColorContrastTest` must mirror TOKENS and PAIRS (same hex, same pairs,
same thresholds) so the document and the shipped theme cannot drift.

Usage:  python3 docs/ux/tools/contrast.py            # markdown tables, exit 1 on any failure
        python3 docs/ux/tools/contrast.py --before   # also print today's measured problems

Pure stdlib. Relative luminance and ratio per WCAG 2.2 §1.4.3 / §1.4.11 (the
same formula as feature/shell/.../theme/WcagContrast.kt). A background written
"base+fg@alpha" is fg composited over base at that alpha (state layers,
selection, scrims), so the ratio is measured against the colour actually drawn.
"""

import sys

# Thresholds: TEXT = body text (AA 4.5:1), LARGE = text ≥ 18.66 dp bold / 24 dp
# regular (3:1), UI = component boundaries, focus rings, icons, graph marks
# (WCAG 1.4.11, 3:1), INFO = measured but exempt (disabled content, decoration).
TEXT, LARGE, UI, INFO = 4.5, 3.0, 3.0, 0.0

TOKENS = {
    "dark": {
        # Neutral ramp: OKLCH, hue 240, chroma 0.007; L 0.14 → 0.93.
        "surfaceContainerLowest": "#070A0C",
        "surface": "#0D1012",  # = background, surfaceDim
        "surfaceContainerLow": "#131719",
        "surfaceContainer": "#1A1D20",
        "surfaceContainerHigh": "#212527",
        "surfaceContainerHighest": "#2A2D30",
        "surfaceBright": "#323639",
        "outlineVariant": "#363A3D",
        "outline": "#7B8186",
        "onSurfaceVariant": "#B0B7BC",
        "onSurface": "#E4E8EB",
        "inverseSurface": "#DDE1E5",
        "inverseOnSurface": "#171B1F",
        "inversePrimary": "#0D6880",
        # Accent: restrained cyan (OKLCH 0.80 0.085 215).
        "primary": "#7ACCE0",
        "onPrimary": "#012630",
        "primaryContainer": "#113B47",
        "onPrimaryContainer": "#C4E9F2",
        # Secondary: cool neutral — selection, user messages, tonal controls.
        "secondary": "#ACBAC3",
        "onSecondary": "#172026",
        "secondaryContainer": "#2A343B",
        "onSecondaryContainer": "#DFE8ED",
        # Tertiary: violet, reserved for "made by AI" markers.
        "tertiary": "#BEB0EC",
        "onTertiary": "#261D42",
        "tertiaryContainer": "#37304C",
        "onTertiaryContainer": "#E2DDF7",
        "error": "#F29891",
        "onError": "#420F0D",
        "errorContainer": "#542523",
        "onErrorContainer": "#FBD8D4",
        "success": "#85CE9E",
        "onSuccess": "#0C2B17",
        "successContainer": "#1C3A27",
        "onSuccessContainer": "#CDEAD6",
        "warning": "#E9C67D",
        "onWarning": "#2E2206",
        "warningContainer": "#403419",
        "onWarningContainer": "#F1E3C7",
    },
    "light": {
        "surfaceContainerLowest": "#FFFFFF",
        "surface": "#F8FAFC",  # = background, surfaceBright
        "surfaceContainerLow": "#F1F4F6",
        "surfaceContainer": "#EBEEF0",
        "surfaceContainerHigh": "#E4E8EB",
        "surfaceContainerHighest": "#DDE1E5",
        "surfaceDim": "#D7DBDF",
        "outlineVariant": "#CED3D7",
        "outline": "#72787D",
        "onSurfaceVariant": "#4D5459",
        "onSurface": "#171B1F",
        "inverseSurface": "#2A2D30",
        "inverseOnSurface": "#EEF1F3",
        "inversePrimary": "#7ACCE0",
        "primary": "#0D6880",
        "onPrimary": "#FFFFFF",
        "primaryContainer": "#D1ECF3",
        "onPrimaryContainer": "#003444",
        "secondary": "#505D65",
        "onSecondary": "#FFFFFF",
        "secondaryContainer": "#D7E1E8",
        "onSecondaryContainer": "#1B2328",
        "tertiary": "#61489B",
        "onTertiary": "#FFFFFF",
        "tertiaryContainer": "#E9E4FE",
        "onTertiaryContainer": "#38255F",
        "error": "#B72D29",
        "onError": "#FFFFFF",
        "errorContainer": "#FFE1DE",
        "onErrorContainer": "#6B1E1B",
        "success": "#246E3A",
        "onSuccess": "#FFFFFF",
        "successContainer": "#D8F2DC",
        "onSuccessContainer": "#163F21",
        "warning": "#875814",
        "onWarning": "#FFFFFF",
        "warningContainer": "#FBE9C6",
        "onWarningContainer": "#54360B",
    },
}

# Component tokens are aliases of the palette above (DESIGN_SYSTEM.md §6.4).
ALIASES = {
    "dark": {
        "codeBlockContainer": "surfaceContainerLowest",
        "codeInlineContainer": "surfaceContainerHighest",
        "userMessageContainer": "secondaryContainer",
        "reasoningContainer": "surfaceContainerLow",
        "focusRing": "primary",
    },
    "light": {
        "codeBlockContainer": "surfaceContainer",
        "codeInlineContainer": "surfaceContainerHigh",
        "userMessageContainer": "secondaryContainer",
        "reasoningContainer": "surfaceContainerLow",
        "focusRing": "primary",
    },
}

# (foreground, background, threshold, where it is used)
PAIRS = [
    # Body and secondary text on every surface level it can land on.
    ("onSurface", "surface", TEXT, "Message and note body, titles"),
    ("onSurface", "surfaceContainerLowest", TEXT, "Text on the lowest container"),
    ("onSurface", "surfaceContainerLow", TEXT, "Drawer, sheets, list pane, reasoning lane"),
    ("onSurface", "surfaceContainer", TEXT, "Menus"),
    ("onSurface", "surfaceContainerHigh", TEXT, "Composer text, dialogs"),
    ("onSurface", "surfaceContainerHighest", TEXT, "Inline code, table header"),
    ("onSurfaceVariant", "surface", TEXT, "Metadata, subtitles, activity steps"),
    ("onSurfaceVariant", "surfaceContainerLow", TEXT, "History previews in drawer/list pane"),
    ("onSurfaceVariant", "surfaceContainer", TEXT, "Menu supporting text"),
    ("onSurfaceVariant", "surfaceContainerHigh", TEXT, "Composer placeholder, dialog body"),
    ("onSurfaceVariant", "surfaceContainerHighest", TEXT, "Muted text on the highest container"),
    # Accent.
    ("primary", "surface", TEXT, "Text buttons, links, selected-row accents"),
    ("primary", "surfaceContainerLow", TEXT, "Links in sheets and list pane"),
    ("primary", "surfaceContainerHigh", TEXT, "Text buttons in dialogs"),
    ("primary", "surfaceContainerHighest", TEXT, "Links on the highest container"),
    ("primary", "userMessageContainer", TEXT, "Links inside a user message"),
    ("onPrimary", "primary", TEXT, "Filled button label, Send/Stop icon"),
    ("onPrimaryContainer", "primaryContainer", TEXT, "Citation marker, accent tonal button"),
    ("onSecondaryContainer", "secondaryContainer", TEXT, "User message, selected nav item, tonal button"),
    ("onSurfaceVariant", "secondaryContainer", TEXT, "Metadata inside a selected row"),
    ("tertiary", "surface", TEXT, "“AI output” label"),
    ("onTertiaryContainer", "tertiaryContainer", TEXT, "AI-output badge"),
    # Status and destructive.
    ("error", "surface", TEXT, "Inline error text, field error"),
    ("error", "surfaceContainer", TEXT, "“Delete” menu item"),
    ("error", "surfaceContainerHigh", TEXT, "“Delete” dialog button"),
    ("onError", "error", TEXT, "Filled destructive button"),
    ("onErrorContainer", "errorContainer", TEXT, "Error card / banner"),
    ("success", "surface", TEXT, "Success text (“Ready”)"),
    ("onSuccessContainer", "successContainer", TEXT, "Success card"),
    ("warning", "surface", TEXT, "Warning text (“Running out of room”)"),
    ("onWarningContainer", "warningContainer", TEXT, "Warning card"),
    ("inverseOnSurface", "inverseSurface", TEXT, "Snackbar message"),
    ("inversePrimary", "inverseSurface", TEXT, "Snackbar action (Undo)"),
    # Component surfaces.
    ("onSurface", "codeBlockContainer", TEXT, "Code block text"),
    ("onSurfaceVariant", "codeBlockContainer", TEXT, "Code block language label"),
    ("onSurface", "codeInlineContainer", TEXT, "Inline code"),
    ("onSurfaceVariant", "reasoningContainer", TEXT, "Model's reasoning lane"),
    ("onSurface", "surface+primary@0.30", TEXT, "Text under selection highlight"),
    ("onSurface", "surface+onSurface@0.08", TEXT, "Hovered row"),
    ("onSurface", "surfaceContainerLow+onSurface@0.10", TEXT, "Pressed / focused row in drawer"),
    ("onSecondaryContainer", "secondaryContainer+onSecondaryContainer@0.10", TEXT, "Pressed selected row"),
    # Non-text UI (WCAG 1.4.11).
    ("outline", "surface", UI, "Text-field border, unchecked box, switch outline"),
    ("outline", "surfaceContainerHigh", UI, "Field border inside a dialog"),
    ("focusRing", "surface", UI, "Keyboard focus ring"),
    ("focusRing", "surfaceContainerLow", UI, "Focus ring in drawer / list pane"),
    ("focusRing", "surfaceContainerHigh", UI, "Focus ring in dialogs / composer"),
    ("primary", "surfaceContainerHigh", UI, "Send button against the composer"),
    ("primary", "surfaceContainerHighest", UI, "Progress indicator against its track"),
    ("primary", "surface", UI, "Running-step dot, switch on, graph note node"),
    ("secondary", "surface", UI, "Graph chat node"),
    ("tertiary", "surface", UI, "Graph AI-output node"),
    ("onSurfaceVariant", "surface", UI, "Graph file node, icons"),
    ("outline", "surface", UI, "Graph edges and tag nodes"),
    ("error", "surface", UI, "Error icon"),
    # Informational only.
    ("onSurface@0.38", "surface", INFO, "Disabled text (exempt; must carry a reason)"),
    ("outlineVariant", "surface", INFO, "Dividers (decorative)"),
    ("userMessageContainer", "surface", INFO, "User-message edge (grouping, not a control)"),
]

# Today's theme (feature/shell/.../SkeinColorHex.kt) and measured defects.
BEFORE = [
    ("#000000", "#0B0E11", TEXT, "dark", "Timeline titles in the Expanded pane: no Surface, so Text falls back to black (fold-inner/shell-landing-dark.png)"),
    ("#FFFFFF", "#F4F6F8", UI, "light", "Status-bar icons drawn white on the light theme (device 01-settings.png)"),
    ("#3A4552", "#0B0E11", UI, "dark", "OUTLINE used as control border (theme option, switch-off track)"),
    ("#3A4552", "#1B2029", UI, "dark", "OUTLINE on SURFACE_VARIANT"),
    ("#C3CBD3", "#F4F6F8", UI, "light", "OUTLINE used as control border"),
    ("#C3CBD3", "#FFFFFF", UI, "light", "OUTLINE on white surface"),
    ("#0E7FA3", "#F4F6F8", TEXT, "light", "PRIMARY text on BACKGROUND: “Close”, “Set default”, Settings section headers"),
    ("#0E7FA3", "#E7EBEF", TEXT, "light", "PRIMARY text on SURFACE_VARIANT"),
    ("#D7BA7D", "#2B2B2B", TEXT, "light", "Code span: passes, but a dark block inside the light theme (MarkdownStyle hard-codes it)"),
]


def _lum(hex_colour):
    def channel(v):
        c = v / 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (int(hex_colour[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def ratio(a, b):
    la, lb = _lum(a), _lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def _blend(fg, alpha, bg):
    f = [int(fg[i:i + 2], 16) for i in (1, 3, 5)]
    g = [int(bg[i:i + 2], 16) for i in (1, 3, 5)]
    return "#%02X%02X%02X" % tuple(round(alpha * x + (1 - alpha) * y) for x, y in zip(f, g))


def resolve(theme, name):
    """'token', 'token@alpha' (over surface) or 'base+token@alpha' → hex."""
    palette = {**TOKENS[theme], **{k: TOKENS[theme][v] for k, v in ALIASES[theme].items()}}
    if "+" in name:
        base, over = name.split("+")
        token, alpha = over.split("@")
        return _blend(palette[token], float(alpha), palette[base])
    if "@" in name:
        token, alpha = name.split("@")
        return _blend(palette[token], float(alpha), palette["surface"])
    return palette[name]


def _label(threshold):
    return {TEXT: "text 4.5", UI: "UI 3.0", INFO: "info"}.get(threshold, str(threshold))


def main():
    failures = 0
    for theme in ("dark", "light"):
        print(f"\n#### {theme.capitalize()} theme\n")
        print("| Foreground | Background | Hex (fg / bg) | Ratio | Needs | Result | Used for |")
        print("|---|---|---|---:|---|---|---|")
        for fg, bg, need, use in PAIRS:
            fh, bh = resolve(theme, fg), resolve(theme, bg)
            r = ratio(fh, bh)
            ok = r >= need
            failures += 0 if ok else 1
            result = "—" if need == INFO else ("pass" if ok else "**FAIL**")
            print(f"| `{fg}` | `{bg}` | {fh} / {bh} | {r:.2f} | {_label(need)} | {result} | {use} |")
    if "--before" in sys.argv:
        print("\n#### Today's theme — measured problems\n")
        print("| Foreground | Background | Theme | Ratio | Needs | Result | Where |")
        print("|---|---|---|---:|---|---|---|")
        for fh, bh, need, theme, where in BEFORE:
            r = ratio(fh, bh)
            print(f"| {fh} | {bh} | {theme} | {r:.2f} | {_label(need)} | {'pass' if r >= need else '**FAIL**'} | {where} |")
    print(f"\n{failures} failing pair(s) in the proposed palette.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
