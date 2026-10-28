#!/usr/bin/env python3
"""Validate fixtures/telecom-lab/expected-twin.json against its schema and the cross-item invariants the schema
cannot express, then cross-check every vendor-knowledge fixture binding against the twin (parent T0.4, child VK-R02.7).

Fixture integrity only: no runtime, no network. The .NET ExpectedTwin loader must enforce the same rules.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
TWIN_DIR = ROOT / "fixtures" / "telecom-lab"
VK_DIR = ROOT / "fixtures" / "vendor-knowledge"
SCHEMA = json.loads((TWIN_DIR / "expected-twin.schema.json").read_text(encoding="utf-8"))


def _schema_errors(instance: dict) -> list[str]:
    errors = sorted(Draft202012Validator(SCHEMA, format_checker=FormatChecker()).iter_errors(instance),
                    key=lambda e: tuple(str(p) for p in e.absolute_path))
    return [f"{list(e.absolute_path)}: {e.message}" for e in errors]


def _unique(values, label, errors):
    seen = set()
    for v in values:
        if v in seen:
            errors.append(f"Duplicate {label} '{v}'")
        seen.add(v)


def validate_twin_data(twin: dict) -> list[str]:
    errors = _schema_errors(twin)
    if errors:
        return ["Schema: " + e for e in errors]

    asset_ids = [a["assetId"] for a in twin["assets"]]
    _unique(asset_ids, "assetId", errors)
    _unique([c["connectionId"] for c in twin["connections"]], "connectionId", errors)
    _unique([d["diagnosticId"] for d in twin["diagnostics"]], "diagnosticId", errors)
    _unique([d["targetAlias"] for d in twin["diagnostics"]], "targetAlias", errors)
    _unique([v["requirementId"] for v in twin["visualRequirements"]], "requirementId", errors)

    known = set(asset_ids)
    for c in twin["connections"]:
        for side in ("fromAssetId", "toAssetId"):
            if c[side] not in known:
                errors.append(f"Connection {c['connectionId']} {side} '{c[side]}' is not a declared asset")
        if c["fromAssetId"] == c["toAssetId"]:
            errors.append(f"Connection {c['connectionId']} connects an asset to itself")

    policy = twin["verificationPolicy"]
    if policy["verifiedLiveRequiresSource"] not in policy["allowedDiagnosticSources"]:
        errors.append("verifiedLiveRequiresSource must be one of allowedDiagnosticSources")
    if policy["requireFreshDiagnostic"] and not twin["diagnostics"]:
        errors.append("requireFreshDiagnostic is true but no diagnostics are declared")
    return errors


def validate_binding_against_twin(binding: dict, twin: dict) -> list[str]:
    errors = []
    if binding["fixtureId"] != twin["fixtureId"]:
        return []  # binding belongs to another fixture; nothing to cross-check here
    if binding["assetId"] not in {a["assetId"] for a in twin["assets"]}:
        errors.append(f"Binding asset '{binding['assetId']}' is not declared in fixture {twin['fixtureId']}")
    declared = {d["diagnosticId"] for d in twin["diagnostics"]}
    extra = [d for d in binding["approvedDiagnostics"] if d not in declared]
    if extra:
        errors.append(f"Binding approvedDiagnostics not declared by fixture {twin['fixtureId']}: {extra}")
    return errors


def main() -> int:
    raw = (TWIN_DIR / "expected-twin.json").read_bytes()
    twin = json.loads(raw)
    errors = validate_twin_data(twin)
    if errors:
        raise ValueError("\n".join(errors))
    print(f"PASS  expected-twin.json  ({len(twin['assets'])} assets, {len(twin['diagnostics'])} diagnostics, "
          f"sha256 {hashlib.sha256(raw).hexdigest()[:12]}…)")
    for path in sorted(VK_DIR.glob("fixture-binding*.json")):
        if path.name.endswith(".schema.json"):
            continue
        binding = json.loads(path.read_text(encoding="utf-8"))
        errs = validate_binding_against_twin(binding, twin)
        if errs:
            raise ValueError("\n".join(f"{path.name}: {e}" for e in errs))
        print(f"PASS  {path.name}  (approvedDiagnostics ⊆ {twin['fixtureId']} diagnostics)")
    print("Expected twin and bindings validated. Runtime loader (T0.4) must mirror these rules.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as error:
        print(f"FAIL  {error}", file=sys.stderr)
        sys.exit(1)
