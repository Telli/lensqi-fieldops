#!/usr/bin/env python3
"""Record the API contract the Android client codes against (Track B).

Drives the hero flow (fake fix denied, then real fix verified) against a RUNNING API in Simulated diagnostics mode and
writes every response body to fixtures/api-recordings/NN-name.json. investigate and segment make genuine Muse Spark and
SAM 3.1 calls; diagnostics are SIMULATED_PROBE. Re-run after any API change and commit the diff.

Usage: python3 -I scripts/record_api.py [--base http://127.0.0.1:5199]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "fixtures" / "api-recordings"
IMAGES = ROOT / "fixtures" / "telecom-lab" / "eval" / "images"


class Recorder:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.n = 0
        self.index: list[dict] = []

    def call(self, name: str, method: str, path: str, body: dict | None = None, *, data: bytes | None = None,
             content_type: str | None = None, expect: int = 200, label: str = "") -> object:
        headers = {"Accept": "application/json"}
        if body is not None:
            data, content_type = json.dumps(body).encode(), "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        if method == "POST":
            headers["Idempotency-Key"] = str(uuid.uuid4())
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        if status != expect:
            raise SystemExit(f"{method} {path}: expected {expect}, got {status}: {raw[:400]!r}")
        self.n += 1
        file = f"{self.n:02d}-{name}.json"
        # Local absolute paths (receipt bundle locations) are machine-specific; keep recordings portable.
        text = raw.decode("utf-8").replace(str(ROOT), "<repo>").replace(str(pathlib.Path.home()), "<home>") if raw else ""
        parsed = json.loads(text) if text else None
        (OUT / file).write_text(json.dumps(parsed, indent=2, ensure_ascii=False) + "\n")
        self.index.append({"file": file, "method": method, "path": path, "status": status, "label": label})
        return parsed

    def upload(self, name: str, inspection: str, image: pathlib.Path, expect: int = 200) -> dict:
        boundary = "----fieldops" + uuid.uuid4().hex
        parts = [
            f'--{boundary}\r\nContent-Disposition: form-data; name="source"\r\n\r\nPhone\r\n'.encode(),
            f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="{image.name}"\r\nContent-Type: image/jpeg\r\n\r\n'.encode(),
            image.read_bytes(), f"\r\n--{boundary}--\r\n".encode(),
        ]
        return self.call(name, "POST", f"/v1/inspections/{inspection}/observations", data=b"".join(parts),
                         content_type=f"multipart/form-data; boundary={boundary}", expect=expect, label="photo evidence")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:5199")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("[0-9][0-9]-*.json"):
        old.unlink()
    r = Recorder(a.base)

    health = r.call("health", "GET", "/health")
    if health["diagnosticsMode"] != "Simulated":
        raise SystemExit("recordings require Diagnostics:Mode=Simulated")
    fixtures = r.call("fixtures", "GET", "/v1/fixtures")
    mandatory = [v["requirementId"] for v in fixtures[0]["visualRequirements"] if v["mandatory"]]
    created = r.call("inspection-created", "POST", "/v1/inspections", {"fixtureId": "LAB-RACK-01", "assetId": "LAB-SW-01"}, expect=201)
    iid = created["inspectionId"]
    p = f"/v1/inspections/{iid}"

    r.call("simulation-set-down", "POST", "/v1/simulation/diagnostics/NODE-A-HEALTH", {"passed": False}, label="SIMULATED_PROBE")
    r.upload("observation-initial", iid, IMAGES / "f02-wrong-port-down.jpg")
    r.call("investigate", "POST", p + "/investigate", label="LIVE_META muse")
    r.call("segment", "POST", p + "/segment", {"concept": "yellow ethernet cable"}, label="LIVE_META sam")
    r.call("evidence-request", "POST", p + "/evidence-requests", {"note": "Show the yellow cable connector at port 2 up close"}, label="reviewer request")
    r.call("knowledge", "GET", p + "/knowledge", label="documented")
    r.call("diagnostic-run-down", "POST", p + "/diagnostics/NODE-A-HEALTH:run", label="SIMULATED_PROBE")
    r.call("work-order", "POST", p + "/work-order", {"summary": "Move the yellow cable to port 2"})
    claim1 = r.call("repair-claim-1", "POST", p + "/repair-claims", {"description": "Moved the yellow cable to port 2"})
    # change evidence (VK-T26): the pre-repair photo re-submitted is a duplicate; no SAM call, back to NeedsEvidence
    dup = r.upload("observation-duplicate", iid, IMAGES / "f02-wrong-port-down.jpg")
    r.call("error-checklist-rejected", "POST", p + "/checklist", {"requirementId": mandatory[0], "status": "Satisfied", "photoEvidenceId": dup["evidenceId"]}, expect=409)
    # a visibly changed scene (cable now in P2) while the endpoint is still down: Changed, then denied by the diagnostic
    fake = r.upload("observation-post-claim-1", iid, IMAGES / "f02-wrong-port-down-post.jpg")
    r.call("change-analysis", "GET", f"{p}/claims/{claim1['claimId']}/change-analysis", label="measured (visual)")
    for req in mandatory:
        r.call(f"checklist-1-{req}", "POST", p + "/checklist", {"requirementId": req, "status": "Satisfied", "photoEvidenceId": fake["evidenceId"]})
    r.call("acknowledge-1", "POST", p + "/acknowledge")
    r.call("verify-denied", "POST", p + "/verify", label="SIMULATED_PROBE gate")

    r.call("simulation-set-up", "POST", "/v1/simulation/diagnostics/NODE-A-HEALTH", {"passed": True}, label="SIMULATED_PROBE")
    r.call("repair-claim-2", "POST", p + "/repair-claims", {"description": "Reseated the cable in port 2 and re-tested"})
    real = r.upload("observation-post-claim-2", iid, IMAGES / "c03-clear-rotated.jpg")
    for req in mandatory:
        r.call(f"checklist-2-{req}", "POST", p + "/checklist", {"requirementId": req, "status": "Satisfied", "photoEvidenceId": real["evidenceId"]})
    r.call("acknowledge-2", "POST", p + "/acknowledge")
    r.call("verify-verified", "POST", p + "/verify", label="SIMULATED_PROBE gate")
    r.call("inspection", "GET", p)
    r.call("events", "GET", p + "/events")
    r.call("artifacts", "GET", p + "/artifacts")
    r.call("receipt", "GET", p + "/receipt")
    r.call("receipt-verify", "GET", p + "/receipt/verify")
    r.call("inspections", "GET", "/v1/inspections")

    # Error shapes the client must surface verbatim.
    r.call("error-verify-closed", "POST", p + "/verify", expect=409)
    r.call("error-not-found", "GET", "/v1/inspections/I-doesnotexist", expect=404)
    other = r.call("inspection-created-2", "POST", "/v1/inspections", {"fixtureId": "LAB-RACK-01", "assetId": "LAB-SW-01"}, expect=201)
    r.call("error-image-too-small", "POST", f"/v1/inspections/{other['inspectionId']}/observations",
           data=_tiny_png_form(), content_type="multipart/form-data; boundary=tiny", expect=400)

    (OUT / "index.json").write_text(json.dumps({"base": "recorded against a local API, Simulated diagnostics",
                                                "calls": r.index}, indent=2) + "\n")
    print(f"recorded {r.n} responses into {OUT.relative_to(ROOT)}")
    return 0


def _tiny_png_form() -> bytes:
    png = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13]) + b"IHDR" + (16).to_bytes(4, "big") * 2 + bytes(9)
    return (b'--tiny\r\nContent-Disposition: form-data; name="image"; filename="tiny.png"\r\nContent-Type: image/png\r\n\r\n'
            + png + b"\r\n--tiny--\r\n")


if __name__ == "__main__":
    raise SystemExit(main())
