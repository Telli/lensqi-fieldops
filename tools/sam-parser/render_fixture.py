#!/usr/bin/env python3
"""Render synthetic, rights-clean LAB-SW-01 scenes for the evaluation set.

    python render_fixture.py --scenarios fixtures/telecom-lab/eval/scenarios.json --out fixtures/telecom-lab/eval/images

Each scenario's "render" block (the first photo) and optional "postRender" block (the photo after the repair
claim, written as <id>-post.jpg) control: port (1-8 or null for no cable), occludeLabels, brightness (0.2-1.6),
blur (px), rotate (degrees), chassisLabel, closeup (crop to the port row), glare. Deterministic for a given seed.
Every output is a Pillow render, never a photograph; the evaluation report says so.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont


def font(size):
    try:
        return ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", size)
    except Exception:
        return ImageFont.load_default()


def render(r: dict, seed: int = 7) -> Image.Image:
    random.seed(seed)
    W, H = 1600, 1000
    img = Image.new("RGB", (W, H), (28, 30, 34))
    d = ImageDraw.Draw(img)
    for x in (60, W - 60):
        d.rectangle([x - 18, 0, x + 18, H], fill=(52, 54, 58))
        for y in range(20, H, 44):
            d.ellipse([x - 6, y, x + 6, y + 12], fill=(18, 18, 20))
    for _ in range(4000):
        x, y = random.randrange(W), random.randrange(H); c = random.randint(-8, 8)
        d.point((x, y), fill=(28 + c, 30 + c, 34 + c))
    cx0, cy0, cx1, cy1 = 160, 380, 1440, 620
    d.rounded_rectangle([cx0, cy0, cx1, cy1], radius=18, fill=(92, 96, 102), outline=(140, 144, 150), width=4)
    d.rectangle([cx0 + 20, cy0 + 18, cx1 - 20, cy0 + 40], fill=(70, 74, 80))
    d.text((cx0 + 28, cy0 + 46), r.get("chassisLabel", "SW01"), fill=(230, 230, 230), font=font(30))
    d.text((cx1 - 200, cy0 + 46), r.get("assetLabel", "LAB-SW-01"), fill=(200, 200, 200), font=font(22))
    ports = []
    px = cx0 + 300
    for i in range(8):
        x0, y0 = px + i * 110, cy0 + 120; x1, y1 = x0 + 78, y0 + 70
        d.rectangle([x0, y0, x1, y1], fill=(16, 16, 18), outline=(30, 30, 32), width=3)
        d.rectangle([x0 + 24, y0, x1 - 24, y0 + 14], fill=(40, 40, 44))
        for k in range(8):
            d.line([x0 + 10 + k * 8, y0 + 20, x0 + 10 + k * 8, y0 + 32], fill=(200, 170, 60), width=3)
        d.ellipse([x0 + 4, y0 - 16, x0 + 12, y0 - 8], fill=(40, 90, 40))
        d.text((x0 + 22, y1 + 8), f"P{i + 1}", fill=(235, 235, 235), font=font(22))
        ports.append((x0, y0, x1, y1))
    port = r.get("port", 2)
    if port:
        x0, y0, x1, y1 = ports[port - 1]
        plug = (x0 + 12, y0 + 6, x1 - 12, y1 + 26)
        seated = r.get("seated", True)
        if not seated:
            plug = (plug[0], plug[1] + 30, plug[2], plug[3] + 30)
        d.rectangle(plug, fill=(214, 214, 214), outline=(150, 150, 150), width=2)
        d.rectangle([plug[0] + 8, plug[3] - 10, plug[2] - 8, plug[3] + 22], fill=(236, 196, 30))
        pts = [((plug[0] + plug[2]) // 2, plug[3] + 20)]
        for t in range(1, 40):
            pts.append((pts[0][0] - t * 14 + (t * t) // 6, pts[0][1] + t * 9))
        d.line(pts, fill=(236, 196, 30), width=26)
        d.line(pts, fill=(255, 220, 70), width=10)
    d.line([(cx0 + 60, cy1 + 10), (cx0 - 40, H - 40)], fill=(110, 110, 115), width=20)
    if r.get("occludeLabels"):
        # a black cable tie / velcro strap across the label row, hiding P1-P4
        d.rectangle([px - 20, cy0 + 195, px + 4 * 110 + 20, cy0 + 235], fill=(12, 12, 12))
        d.rectangle([px - 20, cy0 + 205, px + 4 * 110 + 20, cy0 + 212], fill=(40, 40, 40))
    if r.get("glare"):
        g = Image.new("RGB", (W, H), (0, 0, 0)); gd = ImageDraw.Draw(g)
        gd.ellipse([px - 150, cy0 - 80, px + 420, cy0 + 300], fill=(120, 120, 110))
        g = g.filter(ImageFilter.GaussianBlur(70))
        img = Image.blend(img, Image.eval(g, lambda v: min(255, v)), 0.0)
        from PIL import ImageChops
        img = ImageChops.add(img, g)
        d = ImageDraw.Draw(img)
    if r.get("closeup"):
        img = img.crop((px - 60, cy0 + 60, px + 4 * 110 + 60, cy0 + 260)).resize((1600, 1000), Image.LANCZOS)
    if r.get("rotate"):
        img = img.rotate(r["rotate"], resample=Image.BICUBIC, expand=False, fillcolor=(28, 30, 34))
    if r.get("brightness", 1.0) != 1.0:
        img = ImageEnhance.Brightness(img).enhance(r["brightness"])
    img = img.filter(ImageFilter.GaussianBlur(max(0.6, r.get("blur", 0.6))))
    return img


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    scenarios = json.loads(Path(a.scenarios).read_text())["scenarios"]
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for s in scenarios:
        if "render" not in s:
            continue
        img = render(s["render"])
        path = out / f"{s['id']}.jpg"
        img.save(path, quality=90)
        manifest[s["id"]] = hashlib.sha256(path.read_bytes()).hexdigest()
        if "postRender" in s:  # the photo taken after the repair claim (change evidence, VK-T26)
            post = out / f"{s['id']}-post.jpg"
            render(s["postRender"]).save(post, quality=90)
            manifest[s["id"] + "-post"] = hashlib.sha256(post.read_bytes()).hexdigest()
    (out / "manifest.json").write_text(json.dumps({"source": "synthetic (Pillow render)", "images": manifest}, indent=2) + "\n")
    print(f"rendered {len(manifest)} images into {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
