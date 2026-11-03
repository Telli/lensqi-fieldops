"""Rotate a JPEG the way its EXIF orientation says, so the stored pixels are the ones every viewer shows.

The API stores what it receives; SAM and the crop tool read those bytes directly, while browsers and phones display a JPEG
turned by its orientation tag. This script writes the turned pixels with the tag cleared, so stored, segmented and
displayed pixels agree. Only JPEGs with an orientation other than 1 are sent here.
"""
import argparse
import json
import sys

from PIL import Image, ImageOps

VERSION = "auto-orient/1"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    try:
        img = Image.open(a.image)
        img.load()
        before = img.size
        turned = ImageOps.exif_transpose(img)
        if turned is None:
            turned = img
        if turned.mode not in ("RGB", "L"):
            turned = turned.convert("RGB")
        exif = turned.getexif()
        exif[0x0112] = 1
        turned.save(a.out, "JPEG", quality=95, exif=exif.tobytes())
        print(json.dumps({"version": VERSION, "beforeWidth": before[0], "beforeHeight": before[1], "width": turned.width, "height": turned.height}))
        return 0
    except Exception as ex:  # noqa: BLE001 - the caller keeps the original bytes on any failure
        print(json.dumps({"error": str(ex), "version": VERSION}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
