#!/usr/bin/env python3
"""Validate sample vendor packs and the fixture binding against the normative schemas
and the cross-reference invariants that JSON Schema cannot express.

This checks spec fixture integrity only; it does not implement the FieldOps runtime,
contact vendor websites, make Meta API calls or interact with network devices.
The runtime loader (VK-T01, VK-T08) must enforce every rule below as well.
"""
from __future__ import annotations

import hashlib
import json
import sys
from datetime import date
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "fixtures" / "vendor-knowledge"
PACK_SCHEMA = json.loads((DATA / "knowledge-pack.schema.json").read_text(encoding="utf-8"))
BINDING_SCHEMA = json.loads((DATA / "fixture-binding.schema.json").read_text(encoding="utf-8"))
BINDING_FILE = "fixture-binding-example.json"
NON_PACK_FILES = {"knowledge-pack.schema.json", "fixture-binding.schema.json", BINDING_FILE}


def _schema_errors(schema: dict, instance: dict) -> None:
    errors = sorted(
        Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(instance),
        key=lambda error: tuple(str(part) for part in error.absolute_path),
    )
    if errors:
        details = [f"{list(e.absolute_path)}: {e.message}" for e in errors]
        raise ValueError("Schema errors: " + "; ".join(details))


def _unique(values: list[str], label: str) -> None:
    if len(values) != len(set(values)):
        raise ValueError(f"Duplicate {label}")


def validate_pack_data(pack: dict, today: date | None = None) -> None:
    """Raise ValueError on the first invariant a pack violates."""
    today = today or date.today()
    _schema_errors(PACK_SCHEMA, pack)

    document_ids = [d["documentId"] for d in pack["documents"]]
    fact_ids = [f["factId"] for f in pack["facts"]]
    cap_ids = [c["capabilityId"] for c in pack["capabilities"]]
    _unique(document_ids, "documentId")
    _unique(fact_ids, "factId")
    _unique(cap_ids, "capabilityId")

    for doc in pack["documents"]:
        if date.fromisoformat(doc["accessedOn"]) > today:
            raise ValueError(f"accessedOn in the future for {doc['documentId']}")

    docs = set(document_ids)
    pack_firmware = pack["identity"]["firmwareMajor"]
    for fact in pack["facts"]:
        if fact["sourceDocumentId"] not in docs:
            raise ValueError(f"Unknown document ID for {fact['factId']}")
        if pack["originKind"] == "VendorLinked" and fact["claimKind"] == "SiteConfigured":
            raise ValueError("Manufacturer pack cannot authorize site-specific configuration facts")
        required = fact["requiresFirmwareMajor"]
        if required is not None and required != pack_firmware:
            raise ValueError(
                f"{fact['factId']} requires firmware major {required!r} but the pack identity declares {pack_firmware!r}"
            )

    # Concrete contradiction (same fact key/scope, different asserted value) is invalid.
    fact_values: dict[tuple[str, str | None], str] = {}
    for fact in pack["facts"]:
        key = (fact["factKey"], fact["requiresFirmwareMajor"])
        old = fact_values.setdefault(key, fact["assertedValue"])
        if old != fact["assertedValue"]:
            raise ValueError(f"Contradictory factKey {fact['factKey']}")


def validate_pack(path: Path) -> dict:
    raw = path.read_bytes()
    pack = json.loads(raw.decode("utf-8"))
    validate_pack_data(pack)
    digest = hashlib.sha256(raw).hexdigest()
    print(
        f"PASS  {path.name}  ({len(pack['facts'])} facts, {len(pack['documents'])} source records, "
        f"sha256 {digest[:12]}…)"
    )
    return pack


def validate_binding_data(binding: dict, packs_by_id: dict[str, dict]) -> None:
    """Cross-check a fixture binding overlay against the installed packs."""
    _schema_errors(BINDING_SCHEMA, binding)
    vk = binding["vendorKnowledge"]
    pack = packs_by_id.get(vk["packId"])
    if pack is None:
        raise ValueError(f"Binding references unknown pack {vk['packId']!r}")
    identity = pack["identity"]
    if vk["expectedManufacturerId"] != identity["manufacturerId"]:
        raise ValueError("Binding expectedManufacturerId does not equal the pack identity")
    if vk["expectedProductCode"] != identity["productCode"]:
        raise ValueError("Binding expectedProductCode does not equal the pack identity")
    if vk["firmwareMajor"] is not None and identity["firmwareMajor"] is not None and vk["firmwareMajor"] != identity["firmwareMajor"]:
        raise ValueError("Binding firmwareMajor does not equal the pack identity")
    known_facts = {f["factId"] for f in pack["facts"]}
    missing = [fid for fid in vk["requiredKnowledgeFactIds"] if fid not in known_facts]
    if missing:
        raise ValueError(f"Binding requires fact IDs absent from pack {vk['packId']!r}: {missing}")


def validate_binding(path: Path, packs_by_id: dict[str, dict]) -> None:
    binding = json.loads(path.read_text(encoding="utf-8"))
    validate_binding_data(binding, packs_by_id)
    print(f"PASS  {path.name}  (binds {binding['assetId']} -> {binding['vendorKnowledge']['packId']})")


def main() -> int:
    packs_by_id: dict[str, dict] = {}
    for f in sorted(DATA.glob("*.json")):
        if f.name in NON_PACK_FILES:
            continue
        pack = validate_pack(f)
        if pack["packId"] in packs_by_id:
            raise ValueError(f"Two files declare packId {pack['packId']!r}")
        packs_by_id[pack["packId"]] = pack
    validate_binding(DATA / BINDING_FILE, packs_by_id)
    print("Sample knowledge packs and fixture binding validated. Runtime implementation remains pending.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as error:
        print(f"FAIL  {error}", file=sys.stderr)
        sys.exit(1)
