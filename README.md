# LensQi FieldOps

**See the problem. Prove the solution.** A visual AI agent for field technicians that turns observations of physical
equipment into traceable, verified maintenance outcomes. Meta Global AI Developer Hackathon 2026, Indie track, Push the Frontier.

The agent (Muse Spark 1.3) proposes hypotheses, evidence requests and pre-registered predictions; SAM 3.1 localizes the
cited region; code owns every state transition and the fail-closed dual-proof verification gate. A repair is **Verified
Complete** only when fresh post-repair visual evidence and an independent read-only diagnostic agree and a technician
acknowledges. Documentation guides the investigation; it is never evidence that a repair succeeded.

**Status (2026-10-28):** specifications complete; vendor-knowledge layer and Muse client implemented with 68 passing tests;
Stage One gate passed with genuine Meta model calls (`docs/evidence/`). Inspection state machine, SAM, probes,
verification and receipts are in progress per `.kiro/specs/lensqi-fieldops/tasks.md`.

## Read first

| Document | Purpose |
|---|---|
| [PRD.md](PRD.md) | Product requirements v1.1: scope, state machine, trust invariants, schedule |
| [.kiro/specs/lensqi-fieldops/](.kiro/specs/lensqi-fieldops/) | Parent spec: requirements, design, day-by-day tasks |
| [.kiro/specs/vendor-knowledge-and-diagnostics/](.kiro/specs/vendor-knowledge-and-diagnostics/) | Child spec: vendor knowledge packs, grounded proposals, predictions, change evidence |
| [docs/HACKATHON_CONTRIBUTIONS.md](docs/HACKATHON_CONTRIBUTIONS.md) | Event-period work ledger, commit by commit |
| [docs/PRIOR_ART.md](docs/PRIOR_ART.md) | Provenance: no private source copied; third-party inputs |
| [docs/DEMO_AND_EVALUATION.md](docs/DEMO_AND_EVALUATION.md) | Three-minute demo story and benchmark plan |
| [docs/evidence/](docs/evidence/) | Recorded genuine Meta model calls with digests |

## Build and test

```bash
dotnet test --nologo
```

```bash
dotnet run --project src/LensQi.FieldOps.Api
```

The API loads every knowledge pack under `fixtures/vendor-knowledge/` at startup and refuses to start if any pack or
binding is invalid. `GET /api/vendors/knowledge-packs` lists installed packs with byte-exact SHA-256.

Fixture validators (Python 3.10+):

```bash
python -m pip install -r scripts/requirements.txt
```

```bash
python scripts/validate_expected_twin.py && python scripts/validate_vendor_packs.py
```

```bash
python scripts/test_expected_twin_constraints.py && python scripts/test_sample_pack_constraints.py
```

## Meta model calls

Copy `.env.example` to `.env` and set `MODEL_API_KEY` (the file is gitignored; the key is never accepted from a request).
Then run the Stage One gate, which makes two genuine `muse-spark-1.3` structured-output calls grounded in the synthetic
pack, validates them server-side, and writes an evidence record:

```bash
dotnet run --project tools/LensQi.FieldOps.StageOneGate
```

## SAM 3.1 spike

Genuine `sam-3.1` calls on a synthetic fixture photo, parsed by the Python sidecar, with boxes and masks drawn on the original image (`docs/evidence/sam-spike-*`):

```bash
python -m pip install -r tools/sam-parser/requirements.txt
```

```bash
python tools/sam-parser/sam_spike.py --image fixtures/telecom-lab/images/lab-sw-01-initial-synthetic.jpg --prompt "yellow ethernet cable" --prompt "network switch"
```

## Layout

```text
src/LensQi.FieldOps.Core         pure contracts, applicability, resolver, proposal validation (no provider deps)
src/LensQi.FieldOps.Api          minimal API, pack loader, startup validation
src/LensQi.FieldOps.Meta         MuseSparkChatClient, grounding composer, proposal service (SAM adapter next)
src/LensQi.FieldOps.Diagnostics  allowlisted read-only probes (in progress)
tools/LensQi.FieldOps.StageOneGate   genuine-call evidence runner (Muse)
tools/sam-parser                 SAM output parser sidecar and spike (Python, meta-sam-parser)
fixtures/telecom-lab             expected twin + schema (synthetic LAB-RACK-01)
fixtures/vendor-knowledge        knowledge packs, schemas, fixture binding
scripts/                         fixture validators and negative tests
```

## Non-negotiable claims

- `running=true` on an interface or HTTP 200 from an endpoint is a specific measured result, not proof of correct cabling.
- Documentation citations and model predictions are not evidence that physical equipment was repaired.
- A SHA-256 receipt detects tampering within its bundle, not fabrication of the original observation.
- Simulated or replayed data is never labelled `VerifiedCompleteLive`.

## Provenance

New public repository created during the event. No source from the private `Telli/LensIQ-Monorepo` is copied
(PRD §4A, `docs/SOURCES_AND_RIGHTS.md`, `docs/COMPONENT_REUSE.md`). Licensed under AGPL-3.0-only.
