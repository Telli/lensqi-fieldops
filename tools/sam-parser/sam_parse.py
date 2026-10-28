#!/usr/bin/env python3
"""Internal sidecar: parse SAM 3.1 special-token output_text into typed JSON.

Usage:  python sam_parse.py < raw_output_text.txt
Output: one JSON object with outcome, records (boxes in half-open original-image pixels, masks as COCO RLE plus
        their box-local bounds), diagnostics and the SHA-256 of the raw text. Never exposed over the network.
"""
from __future__ import annotations

import hashlib
import json
import sys
from dataclasses import asdict

import meta_sam_parser as sam


def _version() -> str:
    try:
        from importlib.metadata import version
        return version("meta-sam-parser")
    except Exception:  # pragma: no cover
        return getattr(sam, "__version__", "unknown")


def parse_text(raw: str) -> dict:
    parser = sam.image_segmentation_format().create_parser()
    parser.push(raw)
    finish = parser.finish(sam.CompletedOutcome())
    result = finish.result
    records = []
    for r in result.records:
        kind = getattr(r, "kind", type(r).__name__)
        entry: dict = {"kind": kind, "order": r.order, "objectId": r.object_id}
        if kind == "box":
            entry["box"] = {"left": r.left, "top": r.top, "right": r.right, "bottom": r.bottom}
            entry["confidence"] = r.confidence
        elif kind == "mask":
            rle = sam.decode_mask_to_rle(r.mask)
            entry["bounds"] = asdict(r.bounds)
            entry["mask"] = {"encoding": r.mask.encoding, "width": r.mask.width, "height": r.mask.height,
                             "rle": {"size": list(rle.size), "counts": rle.counts}}
            entry["confidence"] = r.confidence
        elif kind == "text":
            entry["text"] = getattr(r, "text", None)
        records.append(entry)
    return {
        "parser": {"package": "meta-sam-parser", "version": _version()},
        "outcome": asdict(result.outcome) if hasattr(result.outcome, "__dataclass_fields__") else str(result.outcome),
        "revision": result.revision,
        "records": records,
        "diagnostics": [asdict(d) if hasattr(d, "__dataclass_fields__") else str(d) for d in result.diagnostics],
        "rawSha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
        "rawLength": len(raw),
    }


if __name__ == "__main__":
    print(json.dumps(parse_text(sys.stdin.read()), indent=2))
