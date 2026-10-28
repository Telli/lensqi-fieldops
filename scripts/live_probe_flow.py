#!/usr/bin/env python3
"""Drive the hero flow against an API running in Diagnostics:Mode=LiveLocal and keep the receipt bundle (T3.3, Track D).

The API owns the probe target (Diagnostics:Targets:lab-node-a); this script never names a URL to probe. The endpoint is a
plain HTTP health file served from a machine you own, for example:

    mkdir -p ~/fieldops-health && echo ok > ~/fieldops-health/health
    python3 -m http.server 8765 --directory ~/fieldops-health          # on the Pi / NAS / second laptop

  fault   = move the file away  → the server answers 404 → a measured fail (FaultConfirmed)
  repair  = put the file back   → 200 → a measured pass (VerifiedCompleteLive)
  unplug  = stop the server or pull the cable → Unavailable (never a pass, never a confirmed fault)

Usage:
  python3 -I scripts/live_probe_flow.py --base http://127.0.0.1:5202 --out docs/evidence/receipt-live
      [--fault-cmd "..."] [--repair-cmd "..."] [--unplug-cmd "..." --replug-cmd "..."] [--label "LIVE_LOCAL_PROBE (LAN)"]

Without the *-cmd options the script pauses and asks the operator to act. Photos default to the synthetic eval renders
(labelled RecordedFixture); pass --pre/--post to use your own photographs (labelled Phone).
"""
from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parent.parent
RENDERS = ROOT / "fixtures" / "telecom-lab" / "eval" / "images"


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")

    def call(self, method: str, path: str, body: dict | None = None, *, data: bytes | None = None, ctype: str | None = None, ok=(200, 201)):
        headers = {"Accept": "application/json"}
        if body is not None:
            data, ctype = json.dumps(body).encode(), "application/json"
        if ctype:
            headers["Content-Type"] = ctype
        if method == "POST":
            headers["Idempotency-Key"] = str(uuid.uuid4())
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=150) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        parsed = json.loads(raw) if raw else None
        if status not in ok:
            raise SystemExit(f"{method} {path} -> {status}: {parsed}")
        return parsed

    def upload(self, inspection: str, image: pathlib.Path, source: str) -> dict:
        b = "----fieldops" + uuid.uuid4().hex
        body = (f'--{b}\r\nContent-Disposition: form-data; name="source"\r\n\r\n{source}\r\n'
                f'--{b}\r\nContent-Disposition: form-data; name="observedAt"\r\n\r\n{datetime.now(timezone.utc).isoformat()}\r\n'
                f'--{b}\r\nContent-Disposition: form-data; name="image"; filename="{image.name}"\r\nContent-Type: image/jpeg\r\n\r\n').encode()
        body += image.read_bytes() + f"\r\n--{b}--\r\n".encode()
        return self.call("POST", f"/v1/inspections/{inspection}/observations", data=body, ctype=f"multipart/form-data; boundary={b}")


def act(what: str, cmd: str | None) -> None:
    if cmd:
        print(f"  → {what}: {cmd}")
        subprocess.run(cmd, shell=True, check=True)
    else:
        input(f"  → {what}, then press Enter… ")


def probe(api: Api, iid: str) -> dict:
    r = api.call("POST", f"/v1/inspections/{iid}/diagnostics/NODE-A-HEALTH:run")
    res = r["result"]
    verdict = {True: "PASS", False: "FAIL", None: "UNAVAILABLE"}[res["passed"]]
    print(f"  probe {res['diagnosticId']} [{res['source']}] {verdict} {res.get('normalizedValue')} raw sha256 {str(res.get('rawDigest'))[:16]}… → stage {r['state']['stage']}")
    return r


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://127.0.0.1:5202")
    ap.add_argument("--out", required=True, help="directory to copy the receipt bundle into")
    ap.add_argument("--pre", type=pathlib.Path, help="pre-repair photo (default: synthetic render, RecordedFixture)")
    ap.add_argument("--post", type=pathlib.Path, help="post-repair photo (default: synthetic render, RecordedFixture)")
    ap.add_argument("--fault-cmd"); ap.add_argument("--repair-cmd"); ap.add_argument("--unplug-cmd"); ap.add_argument("--replug-cmd")
    ap.add_argument("--label", default="LIVE_LOCAL_PROBE")
    a = ap.parse_args()

    api = Api(a.base)
    health = api.call("GET", "/health")
    if health["diagnosticsMode"] != "LiveLocal":
        raise SystemExit(f"API diagnostics mode is {health['diagnosticsMode']}; start it with Diagnostics__Mode=LiveLocal")
    own = a.pre is not None
    pre = a.pre or RENDERS / "f02-wrong-port-down.jpg"
    post = a.post or RENDERS / "f02-wrong-port-down-post.jpg"
    source = "Phone" if own else "RecordedFixture"
    log: list[str] = []

    print("1. Fault: the endpoint must answer with a non-200 status (a measured fail).")
    act("break the endpoint (move the health file away)", a.fault_cmd)
    iid = api.call("POST", "/v1/inspections", {"fixtureId": "LAB-RACK-01", "assetId": "LAB-SW-01"})["inspectionId"]
    print(f"  inspection {iid}")
    api.upload(iid, pre, source)
    seg = api.call("POST", f"/v1/inspections/{iid}/segment", {"concept": "yellow ethernet cable"})["segmentation"]
    print(f"  SAM '{seg['concept']}': matched={seg['matched']}")
    fault = probe(api, iid)
    if fault["state"]["stage"] != "FaultConfirmed":
        raise SystemExit("The probe did not measure a failure; is the health file really gone (404), not just unreachable?")
    api.call("POST", f"/v1/inspections/{iid}/work-order", {"summary": "Restore the lab endpoint and seat the yellow cable in P2"})
    api.call("POST", f"/v1/inspections/{iid}/repair-claims", {"description": "Restored the endpoint and moved the cable to P2"})

    print("2. Repair: the endpoint must answer 200 again.")
    act("repair the endpoint (put the health file back)", a.repair_cmd)
    up = api.upload(iid, post, source)
    change = up.get("changeAnalysis") or {}
    print(f"  post-repair photo {up['evidenceId']}: change evidence {change.get('verdict')} — {change.get('reason')}")
    accepted = up["state"].get("acceptedPostRepairPhotoId")
    if not accepted:
        raise SystemExit("Change evidence rejected the post-repair photo; capture a new view and run again.")
    for req in ["switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured"]:
        api.call("POST", f"/v1/inspections/{iid}/checklist", {"requirementId": req, "status": "Satisfied", "photoEvidenceId": accepted})
    api.call("POST", f"/v1/inspections/{iid}/acknowledge")
    v = api.call("POST", f"/v1/inspections/{iid}/verify")
    d = v["decision"]
    print(f"  verify: {d['result']} {d.get('outcomeKind')} — {'; '.join(d['reasons'])}")
    rv = api.call("GET", f"/v1/inspections/{iid}/receipt/verify")
    print(f"  receipt bundle verifies: {rv['isValid']} ({len(rv['checks'])} checks)")
    log += [f"inspection {iid}", f"fault probe: {fault['result']}", f"change evidence: {change}", f"decision: {d}", f"receipt valid: {rv['isValid']}"]

    unavailable = None
    print("3. Unavailable: with the endpoint unreachable the probe must say UNAVAILABLE, never pass.")
    act("unplug the endpoint (stop the server or pull the cable)", a.unplug_cmd)
    iid2 = api.call("POST", "/v1/inspections", {"fixtureId": "LAB-RACK-01", "assetId": "LAB-SW-01"})["inspectionId"]
    api.upload(iid2, pre, source)
    unavailable = probe(api, iid2)["result"]
    if unavailable["passed"] is not None:
        print("  WARNING: the probe did not report Unavailable")
    act("plug the endpoint back in", a.replug_cmd)
    log += [f"unavailable inspection {iid2}: {unavailable}"]

    out = pathlib.Path(a.out)
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(rv["bundle"], out)
    (out / "RUN.md").write_text(
        f"# {a.label} run {datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}\n\n"
        f"API {a.base} in LiveLocal mode; the probe target is server configuration (alias lab-node-a), not part of this log.\n"
        f"Photos: {pre.name} → {post.name} ({source}).\n\n" + "\n".join(f"- {line}" for line in log) + "\n")
    print(f"receipt bundle copied to {out} — verify it offline with: dotnet run --project tools/LensQi.FieldOps.VerifyReceipt -- {out}")
    return 0 if d["result"] == "Verified" and d.get("outcomeKind") == "VerifiedCompleteLive" and rv["isValid"] else 1


if __name__ == "__main__":
    sys.exit(main())
