#!/usr/bin/env python3
"""
Build installer/app.ico from assets/azry.png.

Composites the white-on-transparent Azry mark onto a dark rounded square so it
stays visible against light Windows surfaces (File Explorer, Programs and
Features) as well as dark ones (taskbar). Emits a multi-size ICO containing
16/24/32/48/64/128/256 entries.
"""

from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "assets" / "azry.png"
DST = ROOT / "installer" / "app.ico"

# Match the splash background (LauncherSplash.BG = 0x1E1F22) for visual unity.
BG_RGB = (0x1E, 0x1F, 0x22, 255)
LOGO_FRACTION = 0.68          # logo occupies ~68% of the icon square
CORNER_RADIUS_FRAC = 1 / 6.0  # 256-px master gets a 42-px corner radius

SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]


def build_master(size: int = 256) -> Image.Image:
    src = Image.open(SRC).convert("RGBA")

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    radius = max(2, int(size * CORNER_RADIUS_FRAC))

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size, size), radius=radius, fill=255)
    fill = Image.new("RGBA", (size, size), BG_RGB)
    canvas.paste(fill, (0, 0), mask)

    inner = int(size * LOGO_FRACTION)
    logo = src.resize((inner, inner), Image.LANCZOS)
    pad = (size - inner) // 2
    canvas.alpha_composite(logo, (pad, pad))

    return canvas


def main() -> None:
    if not SRC.is_file():
        raise SystemExit(f"source not found: {SRC}")

    DST.parent.mkdir(parents=True, exist_ok=True)
    master = build_master(256)
    master.save(DST, format="ICO", sizes=SIZES)
    print(f"wrote {DST.relative_to(ROOT)} ({DST.stat().st_size} bytes, {len(SIZES)} sizes)")


if __name__ == "__main__":
    main()
