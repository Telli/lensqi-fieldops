#!/usr/bin/env python3
"""Negative cases for the expected-twin fixture format and the binding-to-twin cross-check (not runtime tests)."""
from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_expected_twin import TWIN_DIR, VK_DIR, validate_binding_against_twin, validate_twin_data  # noqa: E402

twin = json.loads((TWIN_DIR / "expected-twin.json").read_text(encoding="utf-8"))
binding = json.loads((VK_DIR / "fixture-binding-example.json").read_text(encoding="utf-8"))
failures: list[str] = []


def reject(change, label, fn=validate_twin_data, base=twin, extra=None):
    value = copy.deepcopy(base)
    change(value)
    errors = fn(value, extra) if extra is not None else fn(value)
    if errors:
        print(f"PASS negative: {label}  [{errors[0][:70]}]")
    else:
        failures.append(label)
        print(f"FAIL negative: {label} unexpectedly passed")


reject(lambda t: t["connections"][0].update(fromAssetId="GHOST"), "connection from undeclared asset")
reject(lambda t: t["connections"][0].update(toAssetId="LAB-SW-01"), "connection to itself")
reject(lambda t: t["assets"].append(dict(t["assets"][0])), "duplicate assetId")
reject(lambda t: t["diagnostics"].append(dict(t["diagnostics"][0])), "duplicate diagnosticId")
reject(lambda t: t["visualRequirements"].append(dict(t["visualRequirements"][0])), "duplicate requirementId")
reject(lambda t: t["assets"][0].update(assetId="LAB SW 01"), "assetId with whitespace")
reject(lambda t: t["visualRequirements"][0].update(requirementId="switch chassis visible"), "requirementId with spaces")
reject(lambda t: t["diagnostics"][0].update(targetAlias="http://10.0.0.1/health"), "targetAlias is a URL")
reject(lambda t: t["diagnostics"][0].update(targetAlias="10.0.0.1"), "targetAlias is an address")
reject(lambda t: t["diagnostics"][0].update(type="shell"), "diagnostic type shell")
reject(lambda t: t["verificationPolicy"].update(verifiedLiveRequiresSource="Simulated"), "live requires simulated")
reject(lambda t: t["verificationPolicy"]["allowedDiagnosticSources"].append("Fabricated"), "unknown diagnostic source")
reject(lambda t: t["verificationPolicy"].update(diagnosticMaxAgeSeconds=0), "zero diagnostic max age")
reject(lambda t: t.update(targetHost="10.0.0.1"), "unknown root field")
reject(lambda t: t["diagnostics"][0].update(url="http://x"), "unknown field inside diagnostic")
reject(lambda t: t.update(assets=[]), "empty assets")
reject(lambda t: t["verificationPolicy"]["allowedDiagnosticSources"].remove("LiveLocal"), "live source not in allowed list")

reject(lambda b: b["approvedDiagnostics"].append("RTR-01-INTERFACE"), "binding approves a diagnostic the fixture does not declare",
       fn=validate_binding_against_twin, base=binding, extra=twin)
reject(lambda b: b.update(assetId="LAB-SW-99"), "binding names an asset the fixture does not declare",
       fn=validate_binding_against_twin, base=binding, extra=twin)

if failures:
    print(f"\n{len(failures)} negative case(s) FAILED: {failures}", file=sys.stderr)
    sys.exit(1)
print("\nAll expected-twin negative tests passed. These do not test the FieldProof runtime.")
