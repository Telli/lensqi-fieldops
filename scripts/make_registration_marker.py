#!/usr/bin/env python3
"""Writes the LensQi registration marker (an ARCore Augmented Image) as a grayscale PNG.

The image is seeded, so running this again gives the same bytes. The app loads the copy in its assets; the copy in
docs/registration is the one to print (docs/registration/README.md). Standard library only.

    python3 scripts/make_registration_marker.py
"""
import random
import struct
import zlib
from pathlib import Path

SIZE = 1024
SEED = 20261031
ROOT = Path(__file__).resolve().parent.parent
OUTPUTS = [
    ROOT / "apps/android/app/src/main/assets/registration/lensqi-marker-v1.png",
    ROOT / "docs/registration/lensqi-marker-v1.png",
]


def draw() -> bytearray:
    rng = random.Random(SEED)
    px = bytearray([200]) * (SIZE * SIZE)

    def rect(x0, y0, x1, y1, v):
        x0, x1 = max(0, x0), min(SIZE, x1)
        for y in range(max(0, y0), min(SIZE, y1)):
            px[y * SIZE + x0:y * SIZE + x1] = bytes([v]) * (x1 - x0)

    def disc(cx, cy, r, v):
        for y in range(max(0, cy - r), min(SIZE, cy + r + 1)):
            dy = y - cy
            half = int((r * r - dy * dy) ** 0.5)
            x0, x1 = max(0, cx - half), min(SIZE, cx + half + 1)
            px[y * SIZE + x0:y * SIZE + x1] = bytes([v]) * (x1 - x0)

    # Many overlapping shapes of different sizes and strong contrast: corners and edges everywhere, nothing repeating.
    for _ in range(900):
        v = rng.choice([0, 0, 0, 255, 255, 255, 90, 160])
        if rng.random() < 0.55:
            w, h = rng.randint(6, 110), rng.randint(6, 110)
            x, y = rng.randint(-w // 2, SIZE), rng.randint(-h // 2, SIZE)
            rect(x, y, x + w, y + h, v)
        else:
            disc(rng.randint(0, SIZE), rng.randint(0, SIZE), rng.randint(4, 60), v)
    # A solid frame so the printed edge is easy to cut and measure.
    t = 16
    rect(0, 0, SIZE, t, 0); rect(0, SIZE - t, SIZE, SIZE, 0); rect(0, 0, t, SIZE, 0); rect(SIZE - t, 0, SIZE, SIZE, 0)
    return px


def png(px: bytearray) -> bytes:
    raw = b"".join(b"\x00" + bytes(px[y * SIZE:(y + 1) * SIZE]) for y in range(SIZE))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 0, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


if __name__ == "__main__":
    data = png(draw())
    for out in OUTPUTS:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)
        print(out.relative_to(ROOT), len(data), "bytes")
