#!/usr/bin/env python3
"""Internal sidecar: measure image-quality signals for an uploaded photo (T6.1).

Usage:  python image_quality.py --image photo.jpg
Output: one JSON object with the decoded size, the variance of a 3x3 Laplacian on a grey copy downscaled to at most
        1024 px on the long side (low = blurrier), the mean grey level 0-255, and a 64-bit difference hash (dHash) as
        16 hex digits. Blur and luminance are signals only. The dHash feeds change evidence (VK-T26): Core compares
        Hamming distances; this script only measures. Never exposed over the network.
"""
from __future__ import annotations

import argparse
import json
import sys

import PIL
from PIL import Image, ImageFilter, ImageStat

VERSION = "image-quality/2 (pillow " + PIL.__version__ + ")"
MAX_SIDE = 1024
# Refuse to decode anything larger than the API's own pixel cap; Pillow's default only warns.
Image.MAX_IMAGE_PIXELS = 50_000_000


def dhash64(grey: Image.Image) -> str:
    """Difference hash: 9x8 grey thumbnail, one bit per horizontal neighbour comparison, row-major, MSB first."""
    small = grey.resize((9, 8), Image.Resampling.LANCZOS)
    px = list(small.tobytes())  # mode "L": one byte per pixel, row-major
    bits = 0
    for row in range(8):
        for col in range(8):
            bits = (bits << 1) | (1 if px[row * 9 + col] > px[row * 9 + col + 1] else 0)
    return f"{bits:016x}"


def measure(path: str) -> dict:
    img = Image.open(path)
    img.load()
    width, height = img.size
    grey = img.convert("L")
    scale = MAX_SIDE / max(width, height)
    if scale < 1:
        grey = grey.resize((max(1, round(width * scale)), max(1, round(height * scale))), Image.Resampling.BILINEAR)
    # Offset 128 keeps negative responses; extremes clip at 0/255, which only lowers the variance of very sharp edges.
    laplacian = grey.filter(ImageFilter.Kernel((3, 3), [0, 1, 0, 1, -4, 1, 0, 1, 0], scale=1, offset=128))
    return {
        "width": width,
        "height": height,
        "blurLaplacianVariance": round(ImageStat.Stat(laplacian).var[0], 3),
        "meanLuminance": round(ImageStat.Stat(grey).mean[0], 3),
        "dhash64": dhash64(img.convert("L")),
        "version": VERSION,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", required=True)
    a = ap.parse_args()
    try:
        print(json.dumps(measure(a.image)))
    except Exception as ex:  # decoding errors become a typed error, never a stack trace on stdout
        print(json.dumps({"error": type(ex).__name__ + ": " + str(ex)}))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
