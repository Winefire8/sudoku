#!/usr/bin/env python3
"""
Renders the bundled demo screenshot(s) used by the in-app acceptance test.

The output mimics a phone Sudoku app: a board with perspective distortion, a header
bar, and anti-aliased digits — i.e. something the recogniser has to *find*, not a
clean bitmap.

Usage:
    python3 tools/make_sample.py                    # writes assets + docs image
    python3 tools/make_sample.py --puzzle-file p.txt

Requirements: pillow, numpy  (pip install pillow numpy)

The ground truth grid is printed to stdout and stored next to the image as
`sample1.grid.txt`, so you can check the overlay digits against it.
"""
from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

PUZZLE = (
    "530070000"
    "600195000"
    "098000060"
    "800060003"
    "400803001"
    "700020006"
    "060000280"
    "000419005"
    "000080079"
)

SOLUTION = (
    "534678912"
    "672195348"
    "198342567"
    "859761423"
    "426853791"
    "713924856"
    "961537284"
    "287419635"
    "345286179"
)

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "C:/Windows/Fonts/arialbd.ttf",
]


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    raise SystemExit(
        "No TrueType font found. Install one of: " + ", ".join(FONT_CANDIDATES)
    )


def dlt(src, dst) -> np.ndarray:
    """3x3 homography mapping src(x, y) -> dst(x, y)."""
    a, b = [], []
    for (x, y), (u, v) in zip(src, dst):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        b.append(u)
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        b.append(v)
    h = np.linalg.solve(np.array(a, float), np.array(b, float))
    return np.array([[h[0], h[1], h[2]], [h[3], h[4], h[5]], [h[6], h[7], 1.0]])


def parse_grid(text: str) -> list[int]:
    digits = [int(ch) for ch in text if ch.isdigit()]
    if len(digits) != 81:
        raise SystemExit(f"expected 81 digits, got {len(digits)}")
    return digits


def render(puzzle: list[int], width: int = 900, height: int = 1800, seed: int = 3) -> Image.Image:
    rng = np.random.default_rng(seed)

    dark = True
    bgc = (30, 32, 38) if dark else (245, 246, 250)
    board_bg = (42, 44, 52) if dark else (255, 255, 255)
    line = (232, 234, 240) if dark else (28, 28, 34)
    num = (242, 244, 250) if dark else (18, 18, 24)

    size = int(width * 0.86)
    x0 = (width - size) // 2
    y0 = (height - size) // 2 + int(height * 0.035)

    # Slight perspective: the board is a little wider at the bottom.
    quad = np.array(
        [
            [x0 + size * 0.012, y0],
            [x0 + size * 0.988, y0 + size * 0.006],
            [x0 + size, y0 + size],
            [x0, y0 + size * 0.995],
        ],
        float,
    )

    # Draw at the final size (scale 1): a higher-resolution canvas with 1-2 px hairlines
    # loses them to the perspective resampling, which would make the sample unrealistically
    # hard for the detector.
    scale = 1
    inner = size * scale
    off = 8
    flat = Image.new("RGB", (inner + 2 * off, inner + 2 * off), board_bg)
    dr = ImageDraw.Draw(flat)
    font = load_font(int(inner / 9 * 0.66))

    for r in range(9):
        for c in range(9):
            v = puzzle[r * 9 + c]
            if not v:
                continue
            text = str(v)
            box = dr.textbbox((0, 0), text, font=font)
            cx = off + inner / 18 + c * inner / 9
            cy = off + inner / 18 + r * inner / 9
            dr.text(
                (cx - (box[2] + box[0]) / 2, cy - (box[3] + box[1]) / 2),
                text,
                font=font,
                fill=num,
            )

    thick = max(3, int(inner / 9 * 0.075))
    for i in range(10):
        w = thick if i % 3 == 0 else max(2, thick // 3)
        pos = off + i * inner / 9
        dr.line([(pos, off), (pos, off + inner)], fill=line, width=w)
        dr.line([(off, pos), (off + inner, pos)], fill=line, width=w)
    # outer border
    dr.rectangle([off, off, off + inner, off + inner], outline=line, width=thick)

    src = [(off, off), (off + inner, off), (off + inner, off + inner), (off, off + inner)]
    h = dlt(src, [tuple(p) for p in quad])
    hinv = np.linalg.inv(h)
    coeffs = tuple(hinv[i, j] for i in range(3) for j in range(3) if not (i == 2 and j == 2))

    layer = flat.transform(
        (width, height), Image.PERSPECTIVE, coeffs, resample=Image.BICUBIC, fillcolor=board_bg
    )

    screen = Image.new("RGB", (width, height), bgc)
    sd = ImageDraw.Draw(screen)
    sd.rectangle([0, 0, width, int(height * 0.07)], fill=(58, 62, 70))
    sd.text(
        (int(width * 0.05), int(height * 0.025)),
        "Sudoku  ·  Hard",
        font=load_font(int(height * 0.022)),
        fill=(240, 240, 240),
    )
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).polygon([tuple(p) for p in quad], fill=255)
    screen.paste(layer, (0, 0), mask)

    # Subtle screen-like softness + noise so it is not a synthetic vector image.
    screen = screen.filter(ImageFilter.GaussianBlur(0.4))
    arr = np.array(screen).astype(np.int16)
    arr = np.clip(arr + rng.normal(0, 1.6, arr.shape), 0, 255).astype(np.uint8)
    return Image.fromarray(arr)


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    ap = argparse.ArgumentParser()
    ap.add_argument("--puzzle-file", help="text file with 81 digits (0 = empty)")
    ap.add_argument(
        "--assets",
        default=os.path.join(root, "app", "src", "main", "assets", "samples"),
    )
    ap.add_argument("--docs", default=os.path.join(root, "docs"))
    args = ap.parse_args()

    puzzle = parse_grid(PUZZLE)
    if args.puzzle_file:
        with open(args.puzzle_file) as fh:
            puzzle = parse_grid(fh.read())

    os.makedirs(args.assets, exist_ok=True)
    os.makedirs(args.docs, exist_ok=True)

    shot = render(puzzle)
    asset_path = os.path.join(args.assets, "sample1.png")
    # Palette-quantise: a 1080x2160 photo-like PNG is ~3.5 MB otherwise, and the
    # bundled asset should stay small. 96 colours is invisible at 1:1 on a phone.
    shot.quantize(colors=48, method=Image.MEDIANCUT, dither=Image.NONE).save(
        asset_path, optimize=True
    )
    shot.resize((450, 900), Image.LANCZOS).save(os.path.join(args.docs, "sample_sudoku.png"))

    lines = ["puzzle"]
    for r in range(9):
        lines.append(" ".join(str(v) for v in puzzle[r * 9:r * 9 + 9]))
    sol = parse_grid(SOLUTION)
    if sol != puzzle and all(
        puzzle[i] == 0 or puzzle[i] == sol[i] for i in range(81)
    ):
        lines += ["solution"]
        for r in range(9):
            lines.append(" ".join(str(v) for v in sol[r * 9:r * 9 + 9]))
    else:
        lines.append("# solution for the bundled puzzle not applicable")
    with open(os.path.join(args.assets, "sample1.grid.txt"), "w") as fh:
        fh.write("\n".join(lines) + "\n")

    print("wrote", asset_path)
    print("grid:", "".join(str(v) for v in puzzle))
    return 0


if __name__ == "__main__":
    sys.exit(main())
