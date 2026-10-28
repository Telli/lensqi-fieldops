# PRIOR ART — provenance register

**Project:** LensQi FieldOps  
**Status (2026-10-28):** No pre-hackathon private code copied. All source in this repository was authored during the event.

## Existing private project (not part of this public application)

- Repository: `Telli/LensIQ-Monorepo` (private, no license file; all rights reserved by the owner)
- Baseline: `593f467372c7e88acb7a217242fb70b1b0c49525` (Oct 16, 2026)
- Original app: historic landmark guide, Android/ASP.NET Core/Gemini, unrelated product logic
- Reuse permission from organizers for the indie track: **NOT RECEIVED** → default is fresh implementation (PRD §4A)
- Design influence only: the vertical-slice endpoint layout, the per-run local-PostgreSQL test fixture idea (not used yet),
  the `IChatClient` fake-with-prompt-capture pattern and the `ApiError` stable-code shape were reimplemented from behaviour
  with fresh code and fresh tests. See `docs/LENSIQ_REUSE_ASSESSMENT.md` for the full eligibility analysis.

## Copied source register

| New path | Original path | Original commit | License/owner clearance | Organizer approval | Adaptation/new commit | Judged as new? |
|---|---|---|---|---|---|---|
| _No entries_ | | | | | | |

**Rule:** add an entry before copying, never after judging. Copied lines are never claimed as hackathon contribution.

## Third-party inputs

| Component | License / source | Use | In demo? |
|---|---|---|---|
| Meta Model API (`muse-spark-1.3`, `sam-3.1`) | Meta developer terms | API calls only; key server-side | Yes |
| `JsonSchema.Net` 9.5.0 | MIT | Pack and binding schema validation (Api) | Yes |
| `Microsoft.Extensions.AI` 10.11.0, `Microsoft.Extensions.Options` 10.0.0 | MIT | Chat abstraction, options | Yes |
| `jsonschema` (PyPI) 4.x | MIT | Fixture validator scripts | Dev only |
| `meta-sam-parser` (PyPI) | Meta terms | Planned SAM output parsing sidecar | Planned |
| MikroTik product and RouterOS REST documentation | Vendor copyright; `LinkOnly` | URLs and short original paraphrases only; nothing redistributed | Yes |
| Meta Wearables SDK | Per Meta terms | Optional P1 | Optional |
| Test photographs | Original/synthetic, rights-cleared | Review before publication | Planned |

## Boundary statement for judges

FieldProof Engine (structured inspection, state machine, Meta perception orchestration, independent probe, false-repair
rejection, evidence receipt) and the vendor-knowledge layer (exact applicability, cited bounded retrieval, pre-registered
predictions, change evidence) are authored during October 27 to November 4, 2026, and only that work is highlighted as
competition functionality. Existing private LensIQ assets and features are out of scope and not present in this repository.
