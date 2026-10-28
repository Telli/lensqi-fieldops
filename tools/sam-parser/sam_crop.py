#!/usr/bin/env python3
"""Internal sidecar: cut a padded ROI from the ORIGINAL image without resampling.

Usage: python sam_crop.py --image in.jpg --left L --top T --right R --bottom B --out crop.jpg
The box is half-open in original-image pixels (already padded and clamped by the caller). Prints JSON with the
actual crop rectangle and the output size. Original pixels are preserved 1:1 so fine label detail survives.
"""
from __future__ import annotations

import argparse
import json
import sys

from PIL import Image


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", required=True)
    ap.add_argument("--left", type=int, required=True)
    ap.add_argument("--top", type=int, required=True)
    ap.add_argument("--right", type=int, required=True)
    ap.add_argument("--bottom", type=int, required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    img = Image.open(a.image)
    img.load()
    w, h = img.size
    left, top = max(0, a.left), max(0, a.top)
    right, bottom = min(w, a.right), min(h, a.bottom)
    if right <= left or bottom <= top:
        print(json.dumps({"error": "empty crop", "imageWidth": w, "imageHeight": h}))
        return 2
    crop = img.crop((left, top, right, bottom))
    if crop.mode not in ("RGB", "L"):
        crop = crop.convert("RGB")
    crop.save(a.out, quality=95)
    print(json.dumps({"imageWidth": w, "imageHeight": h, "left": left, "top": top, "right": right, "bottom": bottom,
                      "cropWidth": crop.width, "cropHeight": crop.height, "scale": 1.0}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
