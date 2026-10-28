# LensQi FieldOps — Meta Global AI Developer Hackathon Specification Kit

**Status:** Planning/specifications only. This is **not** the working application and **does not** create or publish any GitHub repository.

**Product:** LensQi FieldOps  
**Core differentiator:** FieldProof Engine — evidence-driven physical inspection and deterministic outcome verification  
**Track/theme:** Indie Developer / Push the Frontier  
**Build window:** October 27–November 4, 2026  
**Private prior project:** `Telli/LensIQ-Monorepo` at `593f467372c7e88acb7a217242fb70b1b0c49525`, not incorporated into this kit.

## What's included

- `PRD.md` — full v1.1 PRD, including component reuse audit, trust and safety requirements, technical architecture, acceptance criteria, risk controls and schedule.
- `.kiro/specs/lensqi-fieldops/requirements.md` — numbered EARS-style requirements and testable acceptance conditions.
- `.kiro/specs/lensqi-fieldops/design.md` — module boundaries, contracts, state transitions, Meta adapter choices, demo fixture and security policy.
- `.kiro/specs/lensqi-fieldops/tasks.md` — execution sequence and hard cutlines for the hackathon.
- `fixtures/telecom-lab/expected-twin.json` — **synthetic** illustrative expected-state fixture; not an actual observed installation.
- `fixtures/telecom-lab/expected-twin.schema.json` — JSON Schema for fixture validation.
- `docs/COMPONENT_REUSE.md` — code reuse decisions and conditions.
- `docs/PRIOR_ART.md` — editable provenance template. Nothing from the private repo has been copied into this kit.
- `docs/HACKATHON_CONTRIBUTIONS.md` — contributor evidence log template.
- `docs/DEMO_AND_EVALUATION.md` — demo story and benchmark checklist.
- `docs/SECURITY_AND_DATA_RIGHTS.md` — public-source/credential and fixture audit.
- `codex-kickoff.md` — first implementation prompt.

## Decision record

1. Do **not** publish the private LensIQ repository or carry its Git history into the public entry.
2. For indie-track reuse, obtain organizer clarification. Until then, write fresh small utilities; use the private code only as reference/inspiration.
3. Highlight only event-built FieldOps capabilities in the competition demo; provide prior-work disclosure.
4. Treat verified results as bounded fixture checks, not safety/compliance certification.
5. Android P0; Meta glasses P1. The working P0 demo must not require the glasses SDK or original LensIQ app.

## Start

Read `PRD.md`, `requirements.md`, `design.md`, and `tasks.md`. Run `codex-kickoff.md` only in a **newly initialized and authorized** working repository, not in `Telli/LensIQ-Monorepo`.
