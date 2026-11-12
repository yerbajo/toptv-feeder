#!/usr/bin/env python3
"""Draws the project artwork from one geometry: icon, logo, banner.

    python3 tools/make-artwork.py

The mark is a television whose picture is a ranked list, rank 1 in the accent
colour. Android TV shows android:banner (320x180) instead of the square icon, so
the banner carries the wordmark next to the mark and nothing else: at the size a
launcher draws it, a subtitle is unreadable.
"""
import pathlib
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
ICON = ROOT / "docs" / "img" / "icon.png"
LOGO = ROOT / "docs" / "img" / "logo.png"
LOGO_SVG = ROOT / "docs" / "img" / "logo.svg"
BANNER_DOCS = ROOT / "docs" / "img" / "tv-banner.png"
BANNER_APP = ROOT / "app" / "res" / "drawable-xhdpi" / "tv_banner.png"

BG = "#14161D"
SCREEN = "#0B0D12"
BODY = "#1F2430"
ACCENT = "#FFB020"
RANK2 = "#E8EAF0"
RANK3 = "#868FA3"
STAND = "#2E3440"
BASE = "#3A4150"
WORD = "#F4F5F7"

FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def font(size):
    try:
        return ImageFont.truetype(FONT_BOLD, size)
    except OSError:
        return ImageFont.load_default()


def mark(draw, x, y, width):
    """The television, drawn at `width`; its height is width * 48 / 48."""
    unit = width / 48.0

    def box(left, top, right, bottom, colour):
        draw.rectangle([x + left * unit, y + top * unit,
                        x + right * unit - 1, y + bottom * unit - 1], fill=colour)

    box(6, 9, 42, 32, BODY)
    box(10, 13, 35, 17, ACCENT)
    box(10, 20, 27, 24, RANK2)
    box(10, 26, 21, 30, RANK3)
    box(22, 32, 27, 35, STAND)
    box(15, 36, 33, 39, BASE)


def icon():
    size = 512
    image = Image.new("RGB", (size, size), BG)
    draw = ImageDraw.Draw(image)
    mark(draw, 64, 112, 384)
    image.save(ICON)


def logo():
    width, height = 960, 240
    image = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(image)
    mark(draw, 40, 60, 150)
    text = font(96)
    draw.text((230, 120), "TV Feeder", font=text, fill=WORD, anchor="lm")
    image.save(LOGO)


def banner():
    """320x180, the size a launcher draws: mark on the left, wordmark on the right."""
    width, height = 320, 180
    image = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(image)
    mark(draw, 24, 60, 72)
    text = font(34)
    draw.text((112, 92), "TV Feeder", font=text, fill=WORD, anchor="lm")
    image.save(BANNER_DOCS)
    image.save(BANNER_APP)
    # Fail loudly rather than shipping a banner whose wordmark runs off the edge.
    right = draw.textbbox((112, 92), "TV Feeder", font=text, anchor="lm")[2]
    if right > width - 8:
        sys.exit(f"wordmark overflows the banner: right edge {right} > {width - 8}")


def svg():
    LOGO_SVG.write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="240" '
        'viewBox="0 0 960 240" role="img" aria-label="TV Feeder">\n'
        f'  <rect width="960" height="240" fill="{BG}"/>\n'
        '  <g transform="translate(40 60) scale(3.125)">\n'
        f'    <rect x="6" y="9" width="36" height="23" fill="{SCREEN}"/>\n'
        f'    <rect x="10" y="13" width="25" height="4" fill="{ACCENT}"/>\n'
        f'    <rect x="10" y="20" width="17" height="4" fill="{RANK2}"/>\n'
        f'    <rect x="10" y="26" width="11" height="4" fill="{RANK3}"/>\n'
        f'    <rect x="22" y="32" width="5" height="3" fill="{STAND}"/>\n'
        f'    <rect x="15" y="36" width="18" height="3" fill="{BASE}"/>\n'
        '  </g>\n'
        f'  <text x="230" y="140" font-family="DejaVu Sans, Verdana, sans-serif" '
        f'font-size="96" font-weight="bold" fill="{WORD}">TV Feeder</text>\n'
        '</svg>\n',
        encoding="utf-8",
    )


def main():
    icon()
    logo()
    banner()
    svg()
    for path in (ICON, LOGO, LOGO_SVG, BANNER_DOCS, BANNER_APP):
        print(f"{path.relative_to(ROOT)} {Image.open(path).size if path.suffix == '.png' else ''}".rstrip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
