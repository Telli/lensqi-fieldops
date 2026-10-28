# Codex Kickoff — LensQi FieldOps (Day 1)

You are implementing the **new public** Meta Global AI Developer Hackathon project **LensQi FieldOps** in a FRESH repository. Read `PRD.md`, `.kiro/specs/lensqi-fieldops/requirements.md`, `design.md`, `tasks.md`, `docs/COMPONENT_REUSE.md`, `docs/SECURITY_AND_DATA_RIGHTS.md`, and `docs/PRIOR_ART.md` first.

## Non-negotiable boundaries

1. **Do not modify, fork, clone, copy or import code from `Telli/LensIQ-Monorepo`.** It is an unrelated private pre-event project. Indie-track copying permission is unresolved; treat existing implementation only as product knowledge unless the owner later provides written approval and provenance documentation.
2. All competition-feature code must be authored in this new repository during the official build window. Record new commits and test results in `docs/HACKATHON_CONTRIBUTIONS.md`.
3. No fake live model/probe calls, no fabricated SAM masks or hardware claims. Local simulations must be labeled and remain separate from live verification.
4. Preserve the proprietary heritage product; this repository must have zero private dependencies or past Git objects.
5. No broad platform scope: one lab fixture, one visual investigation, one read-only probe, one fake repair and one successful fixture verification.

## Day 1 concrete implementation

A. Inspect current directory and confirm it is a fresh approved working tree, not the private repo. If not, do not write files and report the conflict. Do not automatically create an external GitHub repository.

B. Create `.NET 10` solution projects `LensQi.FieldOps.Api`, `.Core`, `.Meta`, `.Diagnostics` and test projects. Add empty Android Gradle/Compose module skeleton only after backend P0 contracts compile.

C. Add fixture loading and JSON Schema validation for `fixtures/telecom-lab/expected-twin.json`. Validate IDs, version hash, connections, and server-owned diagnostic aliases.

D. Implement pure C# `InspectionSession`, `Evidence`, `InspectionStage`, `RepairClaim`, `DiagnosticResult` and deterministic `VerificationPolicy` with explicit failure reasons. Add unit tests for fake fix, stale probe, wrong fixture, simulation-not-live, and duplicate requests.

E. Implement Meta Model API adapter with server-only `MODEL_API_KEY`, a *real* `muse-spark-1.3` multimodal request on a legitimate test image, JSON-schema validation and sanitized logging. Do not hardcode example responses in the live path.

F. Create an initial `POST /v1/inspections` and `POST /v1/inspections/{id}/observations` that use the pure Core and read-only stored fixture. Wire remaining endpoints after P0 foundation is green.

G. Add `README.md` commands, `.env.example`, safe `.gitignore`, test commands and progress notes. Record the actual commit SHA and exact newly built functionality.

## Completion response

Report actual files changed, commands run, passed/failed tests, verified live Model API results, current blockers, and next tasks. Never claim a provider/hardware/live fixture check passed unless you executed it. The work is not complete until the fake-fix proof gate is deterministic and tested.
