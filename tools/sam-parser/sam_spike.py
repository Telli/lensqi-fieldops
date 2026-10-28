#!/usr/bin/env python3
"""Day 2 spike: one genuine sam-3.1 call per prompt on a fixture image, parsed by the sidecar, boxes and masks drawn
on the ORIGINAL image, evidence recorded under docs/evidence/. No diagnostic runs; no device is contacted.

    python tools/sam-parser/sam_spike.py --image fixtures/telecom-lab/images/lab-sw-01-initial-synthetic.jpg \
        --prompt "yellow ethernet cable" --prompt "network switch"
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import meta_sam_parser as sam  # noqa: E402
from sam_parse import parse_text  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
API = "https://api.meta.ai/v1/responses"


def api_key() -> str:
    key = os.environ.get("MODEL_API_KEY")
    if not key and (ROOT / ".env").exists():
        for line in (ROOT / ".env").read_text().splitlines():
            if line.strip().startswith("MODEL_API_KEY="):
                key = line.split("=", 1)[1].strip().strip('"')
    if not key:
        sys.exit("MODEL_API_KEY is not set (env or .env).")
    return key


def call_sam(key: str, image_bytes: bytes, mime: str, prompt: str) -> tuple[dict, float]:
    body = {
        "model": "sam-3.1",
        "input": [{"type": "message", "role": "user", "content": [
            {"type": "input_text", "text": prompt},
            {"type": "input_image", "image_url": f"data:{mime};base64," + base64.b64encode(image_bytes).decode()},
        ]}],
        "stream": False,
        "metadata": {"mask_encoding": "one_bit", "include_confidence": "true"},
    }
    req = urllib.request.Request(API, data=json.dumps(body).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=120) as resp:
        payload = json.loads(resp.read())
    return payload, time.perf_counter() - t0


def output_text(payload: dict) -> str:
    parts = []
    for item in payload.get("output", []):
        for c in item.get("content", []) or []:
            if c.get("type") == "output_text":
                parts.append(c.get("text", ""))
    return "".join(parts)


def draw(img: Image.Image, parsed: dict, colour: tuple[int, int, int]) -> Image.Image:
    out = img.convert("RGBA")
    overlay = Image.new("RGBA", out.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    for rec in parsed["records"]:
        if rec["kind"] == "box":
            b = rec["box"]
            d.rectangle([b["left"], b["top"], b["right"] - 1, b["bottom"] - 1], outline=colour + (255,), width=5)
            label = f"{rec['objectId']} {rec['confidence']:.2f}" if rec.get("confidence") is not None else rec["objectId"]
            d.text((b["left"] + 6, max(0, b["top"] - 18)), label, fill=colour + (255,))
    return Image.alpha_composite(out, overlay).convert("RGB")


def paint_masks(img: Image.Image, raw: str, colour: tuple[int, int, int]) -> Image.Image:
    parser = sam.image_segmentation_format().create_parser()
    parser.push(raw)
    result = parser.finish(sam.CompletedOutcome()).result
    out = img.convert("RGBA")
    for r in result.records:
        if getattr(r, "kind", "") != "mask":
            continue
        raster = sam.decode_mask_to_raster(r.mask)
        w, h = r.mask.width, r.mask.height
        tile = Image.frombytes("L", (w, h), bytes(255 if v else 0 for v in raster))
        tint = Image.new("RGBA", (w, h), colour + (110,))
        bw, bh = int(round(r.bounds.right - r.bounds.left)), int(round(r.bounds.bottom - r.bounds.top))
        if (w, h) != (bw, bh) and bw > 0 and bh > 0:
            tile = tile.resize((bw, bh), Image.NEAREST); tint = tint.resize((bw, bh))
        out.paste(tint, (int(round(r.bounds.left)), int(round(r.bounds.top))), tile)
    return out.convert("RGB")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", required=True)
    ap.add_argument("--prompt", action="append", required=True)
    ap.add_argument("--out-dir", default=str(ROOT / "docs" / "evidence"))
    a = ap.parse_args()

    key = api_key()
    image_path = Path(a.image)
    image_bytes = image_path.read_bytes()
    mime = "image/jpeg" if image_path.suffix.lower() in (".jpg", ".jpeg") else "image/png"
    img = Image.open(image_path).convert("RGB")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = Path(a.out_dir); out_dir.mkdir(parents=True, exist_ok=True)
    colours = [(255, 80, 80), (80, 200, 255), (120, 255, 120)]
    annotated = img.copy()
    runs = []
    for i, prompt in enumerate(a.prompt):
        payload, secs = call_sam(key, image_bytes, mime, prompt)
        raw = output_text(payload)
        parsed = parse_text(raw)
        boxes = [r for r in parsed["records"] if r["kind"] == "box"]
        masks = [r for r in parsed["records"] if r["kind"] == "mask"]
        print(f"== prompt '{prompt}': {len(boxes)} box(es), {len(masks)} mask(s), {secs:.1f}s, outcome={parsed['outcome']}")
        for b in boxes:
            print(f"   {b['objectId']}: box={b['box']} confidence={b.get('confidence')}")
        if raw:
            annotated = paint_masks(annotated, raw, colours[i % 3])
        annotated = draw(annotated, parsed, colours[i % 3])
        runs.append({
            "prompt": prompt, "model": payload.get("model", "sam-3.1"), "responseId": payload.get("id"),
            "status": payload.get("status"), "elapsedSeconds": round(secs, 2),
            "usage": payload.get("usage"),
            "rawOutputText": raw, "parsed": parsed, "noMatch": len(boxes) == 0,
        })
    overlay_path = out_dir / f"sam-spike-{stamp}-overlay.jpg"
    annotated.save(overlay_path, quality=85)
    evidence = {
        "kind": "sam-spike", "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
        "apiBase": API, "model": "sam-3.1", "requestMetadata": {"mask_encoding": "one_bit", "include_confidence": "true"},
        "imageTransport": "data URL (base64) in input_image.image_url",
        "image": {"path": str(image_path.relative_to(ROOT)) if image_path.is_relative_to(ROOT) else str(image_path),
                  "sha256": hashlib.sha256(image_bytes).hexdigest(), "width": img.width, "height": img.height,
                  "source": "synthetic", "note": "Rendered with Pillow; not a photograph of real equipment."},
        "runs": runs, "overlay": overlay_path.name,
        "label": "LIVE_META",
        "note": "Genuine Meta Model API calls. Boxes/masks are model interpretations of a synthetic image; they are not "
                "evidence of equipment state and never authorize a diagnostic.",
    }
    json_path = out_dir / f"sam-spike-{stamp}.json"
    json_path.write_text(json.dumps(evidence, indent=2))
    print(f"Evidence: {json_path.relative_to(ROOT)}  overlay: {overlay_path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
