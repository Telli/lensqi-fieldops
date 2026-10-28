#!/usr/bin/env python3
"""Negative regression checks for the sample pack and binding formats (not app runtime tests).

Each case mirrors a rejection the requirements (VK-R02.1, VK-R08.1) and tasks (VK-T01, VK-T08)
demand from the runtime loader. Run from any directory:  python scripts/test_sample_pack_constraints.py
"""
from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_vendor_packs import DATA, validate_binding_data, validate_pack_data  # noqa: E402

base = json.loads((DATA / "mikrotik-hex-s-rb760igs.routeros7.json").read_text(encoding="utf-8"))
synthetic = json.loads((DATA / "lab-switch-01.synthetic.json").read_text(encoding="utf-8"))
binding = json.loads((DATA / "fixture-binding-example.json").read_text(encoding="utf-8"))
packs_by_id = {base["packId"]: base, synthetic["packId"]: synthetic}

failures: list[str] = []


def should_reject_pack(change, label: str, source: dict = base) -> None:
    value = copy.deepcopy(source)
    change(value)
    try:
        validate_pack_data(value)
    except ValueError as error:
        print(f"PASS negative: {label}  [{str(error)[:70]}]")
    else:
        failures.append(label)
        print(f"FAIL negative: {label} unexpectedly passed validation")


def should_reject_binding(change, label: str) -> None:
    value = copy.deepcopy(binding)
    change(value)
    try:
        validate_binding_data(value, packs_by_id)
    except ValueError as error:
        print(f"PASS negative: {label}  [{str(error)[:70]}]")
    else:
        failures.append(label)
        print(f"FAIL negative: {label} unexpectedly passed validation")


# Schema-level rejections
should_reject_pack(lambda v: v.update(schemaVersion="0.9"), "unsupported schema version")
should_reject_pack(lambda v: v["facts"][0].pop("sourceLocator"), "missing locator")
should_reject_pack(lambda v: v["documents"][0].update(url="http://unsafe.example"), "non-HTTPS vendor URL")
should_reject_pack(lambda v: v.update(arbitraryCommands=["POST /rest/reboot"]), "undeclared command injection field")
should_reject_pack(lambda v: v["facts"][0].update(safetyClass="RunShellCommand"), "unsafe safety classification")
should_reject_pack(lambda v: v["facts"][0].update(claimKind="SiteConfigured"), "SiteConfigured fact inside VendorLinked pack")
should_reject_pack(lambda v: v["facts"][0].update(factId='fact "one" two'), "factId with whitespace and quotes")
should_reject_pack(lambda v: v["capabilities"][0].update(readOnly=False), "capability declared writable")
should_reject_pack(lambda v: v["facts"][0].update(statement="x" * 481), "statement long enough to be copied manual text")

# Cross-reference rejections (validator / runtime loader, not expressible in JSON Schema)
should_reject_pack(lambda v: v["facts"].append(copy.deepcopy(v["facts"][0])), "duplicate factId")
should_reject_pack(lambda v: v["facts"][0].update(sourceDocumentId="NOT-A-DOCUMENT"), "unresolved document reference")
should_reject_pack(lambda v: v["facts"][1].update(requiresFirmwareMajor="6"), "fact firmware scope differs from pack identity")
should_reject_pack(lambda v: v["documents"][0].update(accessedOn="2099-01-01"), "accessedOn in the future")
should_reject_pack(
    lambda v: v["facts"].append({**copy.deepcopy(v["facts"][0]), "factId": "MK-PRODUCT-PORTS-ALT", "assertedValue": "4"}),
    "contradictory assertedValue for same factKey",
)

# Fixture binding rejections
should_reject_binding(lambda b: b["vendorKnowledge"].update(packId="mikrotik-hex-s-rb760igs.routeros7"), "binding synthetic asset to the MikroTik pack")
should_reject_binding(lambda b: b["vendorKnowledge"]["requiredKnowledgeFactIds"].append("MK-REST-GET-INTERFACE"), "binding requires fact from another pack")
should_reject_binding(lambda b: b["vendorKnowledge"].update(expectedProductCode="LAB-8P-SYNTH-V2"), "binding product code differs from pack")
should_reject_binding(lambda b: b.update(approvedDiagnostics=["https://10.0.0.1/rest/interface"]), "binding diagnostic entry is a URL")
should_reject_binding(lambda b: b.update(targetHost="10.0.0.1"), "binding carries a network target")

if failures:
    print(f"\n{len(failures)} negative case(s) FAILED: {failures}", file=sys.stderr)
    sys.exit(1)
print("\nAll negative fixture tests passed. These do not test the FieldProof runtime.")
