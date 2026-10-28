# LensQi FieldOps — Vendor Knowledge & Diagnostic Adapters

**Kiro-style implementation add-on** for the LensQi FieldOps / FieldProof Engine v1.1 public hackathon project. Prepared October 27, 2026. **These are specifications and example data, not an implemented app feature.**

## What it adds

- **Vendor Knowledge Packs:** exact manufacturer/product-code applicability, firmware and revision limitations, short paraphrased fact cards, source URLs, section locators, version/digest provenance.
- **Grounded AI troubleshooting:** Muse Spark receives only applicable curated source facts and site-specific expected state, and proposes **approved** evidence requests or diagnostics.
- **Safe diagnostics:** existing fixture-bound HTTP health/simulation probe plus an **optional, future** MikroTik RouterOS v7 read-only REST GET adapter.
- **Predict, then measure:** before any approved diagnostic runs, Muse must pre-register a falsifiable prediction from a closed value set; Core scores it against the measurement. SAM 3.1 segments the cited region before and after the repair so Core can reject re-submitted or unchanged photos without trusting prose.
- **Traceable verification:** parent FieldProof engine owns state transitions; documentation and link status never replace post-repair visual evidence, independent diagnostics or technician acknowledgement.

## Files to copy into the new public repository

```text
.kiro/specs/vendor-knowledge-and-diagnostics/
  requirements.md             # EARS-style stories, mandatory cases, acceptance criteria
  design.md                   # architecture, C# contracts, API proposal, policy boundaries
  tasks.md                    # dependency-ordered P0/P1/P2 coding checklist
fixtures/vendor-knowledge/
  knowledge-pack.schema.json  # normative v1 JSON Schema for packs
  fixture-binding.schema.json # normative v1 JSON Schema for the site fixture overlay
  lab-switch-01.synthetic.json
  mikrotik-hex-s-rb760igs.routeros7.json
  fixture-binding-example.json
scripts/
  validate_vendor_packs.py          # validator: schemas + cross-reference invariants + binding
  test_sample_pack_constraints.py   # 19 negative cases the runtime loader must also reject
  requirements.txt                  # Python jsonschema dependency
docs/
  CODEX_VENDOR_KNOWLEDGE.md
  SOURCES_AND_RIGHTS.md
  INTEGRATION_NOTES.md
  SPEC_REVIEW_2026-10-27.md   # review findings, fixes applied, suggested features
  HACKATHON_FIT_ASSESSMENT.md # fit against the participant guide, frontier features, demo sequence
  LENSIQ_REUSE_ASSESSMENT.md  # what may be imported from the private monorepo and how to label it
```

Place this **alongside** the existing `.kiro/specs/lensqi-fieldops/` from v1.1; don't replace the parent specs or import LensIQ domain code (one labeled infrastructure-scaffolding import commit is permitted; see `docs/LENSIQ_REUSE_ASSESSMENT.md` and `docs/SOURCES_AND_RIGHTS.md`). If the new public repo is not yet present, this pack can be unpacked into the newly initialized repo after the permitted hackathon start.

## Build and test

```bash
dotnet test --nologo
```

```bash
dotnet run --project src/LensQi.FieldOps.Api
```

The API loads every pack under `fixtures/vendor-knowledge/` at startup and refuses to start if any pack or binding is invalid. `GET /api/vendors/knowledge-packs` lists installed packs with their byte-exact SHA-256. Implemented so far: VK-T01–VK-T04 (schema loader, digests, exact applicability, bounded resolution); see `tasks.md` for the live checklist.

## Start here

1. Read `.kiro/specs/vendor-knowledge-and-diagnostics/requirements.md`.
2. Read `.kiro/specs/vendor-knowledge-and-diagnostics/design.md` and confirm types against the currently implemented FieldProof code.
3. Implement tasks in `tasks.md` in order, starting with schema+asset applicability and ending with live device adapters **only if** the core demo is already sound.
4. Optionally validate example JSON:

```bash
python -m pip install -r scripts/requirements.txt
python scripts/validate_vendor_packs.py
python scripts/test_sample_pack_constraints.py
```

## Scope of the two example packs

- `lab-switch-01.synthetic.json` is **original synthetic test material**, designed to match the fictional `LAB-RACK-01` fixture from the FieldOps v1.1 Kiro kit. Its source URL is null and its claims are not about real hardware.
- `mikrotik-hex-s-rb760igs.routeros7.json` is a **vendor-linked reference** using public, official MikroTik URLs and concise paraphrases. It **does not** enable a live RouterOS device connection; real model/firmware and administrative permissions must be confirmed independently.

## Non-negotiable claims

- `running=true` on an interface is a specific measured link-state report, not proof of correct cabling or application reachability.
- Documentation citations are not evidence that physical equipment was repaired.
- A JSON/SHA-256 receipt detects tampering **within its included artifact bundle**, not fabrication of the original observation.
- Never treat simulated/replay data as `VerifiedCompleteLive`.
- Model API calls and hardware actions have **not** been executed to create these specifications.

## Integration strategy

P0 is a compact extension: one knowledge resolver, one versioned schema/pack registry, a few typed fields on an inspection event, and citation display in the existing Android screen/receipt. A separate database or multi-agent runtime is unnecessary. Keep the main false-repair verification demonstration on schedule for November 4.
