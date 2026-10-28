# Codex / Muse Code Implementation Kickoff Prompt

Paste this into the coding agent **from the root of the new public `lensqi-fieldops` repository**:

> You are implementing the additive **Vendor Knowledge & Diagnostic Adapters** Kiro spec for LensQi FieldOps.
>
> Read `.kiro/specs/lensqi-fieldops/{requirements,design,tasks}.md` and the parent PRD **first**. Then read `.kiro/specs/vendor-knowledge-and-diagnostics/{requirements,design,tasks}.md`. Inspect existing source interfaces before creating files; preserve the parent FieldProof state machine, `IDiagnosticProbe`, `ExpectedTwin`, receipt contracts, Android camera and Muse/SAM pathways.
>
> Before anything in this spec, confirm a genuine Muse Spark structured-output call and a genuine SAM 3.1 call already work from event-period code; that is the hackathon's Stage One gate. Then implement tasks VK-T01 through VK-T14, followed by VK-T24 through VK-T27 (pre-registered predictions and SAM before/after change evidence), incrementally. Start with immutable, schema-validated JSON packs and exact asset/revision matching, then bounded source resolution, Muse structured-output source validation, safe fixture-bound diagnostic reuse, receipt provenance, and a compact Android documentation drawer.
>
> Prioritize one synthetic end-to-end telecom test fixture. Keep the real MikroTik RB760iGS pack as a **vendor-linked reference only** unless an actual owned RouterOS v7 test router, trusted HTTPS certificate, read-only user, and specific approved interfaces are configured by the operator. VK-T15 through VK-T18 are optional after the main hackathon MVP succeeds.
>
> Rules: never let model-returned text choose URLs, network targets, shell commands, RouterOS paths, credentials, or final verification status. Facts are not physical repair evidence. Code from the private LensIQ monorepo may be imported **only** as infrastructure scaffolding (test fixtures, fake chat client, API error/result primitives, image upload validation, audit-row pattern, HTTP resilience wiring, CI workflows, anonymous JWT auth) in **one** first commit titled `Import pre-existing infrastructure scaffolding from LensIQ (not hackathon work)`, with a two-line origin header on every imported file and the commit SHA recorded in `docs/PRIOR_ART.md`. Everything domain-specific (knowledge resolver, applicability, proposal validation, predictions, change evidence, probes, state machine, receipts, Android inspection UI) is newly authored during the event. Never import Gemini, Places, billing, AR or confidence-scoring code; see `docs/LENSIQ_REUSE_ASSESSMENT.md` for the exact allow and deny lists. Do not fetch/copy whole vendor PDFs or redistribute vendor diagrams. All sample media must be original/licensed.
>
> Use C# ASP.NET Core 10 Minimal APIs, `.NET` typed contracts and Microsoft.Extensions.AI for compatible Muse calls; do not introduce a multi-agent framework, vector database or dynamic web crawling. The model's proposed `usedFactIds` and `proposedDiagnosticId` must be validated against the exact facts and allowed diagnostics for the inspected asset.
>
> For each task: inspect relevant existing code, implement the smallest change, add focused positive/negative tests, run tests, commit new event-period work with an informative message, and update `docs/HACKATHON_CONTRIBUTIONS.md` and `docs/PRIOR_ART.md` accurately. Nothing from the scaffolding import commit appears in the demo video or in `HACKATHON_CONTRIBUTIONS.md`. Never claim production-ready or live hardware functionality unless independently verified.
>
> Start now by reporting the existing FieldProof interfaces you found, any differences from the specification, and the concrete test you will use for VK-T01. Then implement VK-T01–VK-T04 and provide test results. Do not execute live device calls, change repository visibility, publish anything, or modify external systems without explicit user approval.

## Acceptance proof after the first implementation pass

- Schema validation emits a clear pass for two example packs and clear errors for malformed packs.
- `LAB-SW-01` resolves synthetic fact cards only; MikroTik fact cards cannot be applied to that fixture.
- `RB760iGS` with RouterOS major version 7 resolves applicable RouterOS facts, while unknown firmware marks version-specific facts as unverified.
- A model-generated `factId` absent from retrieved applicable facts fails validation.
- The existing fake-fix loop still rejects visual-pass/diagnostic-fail outcomes.

## Public repo note

The GitHub repository `Telli/lensqi-fieldops` was not found through the connected account when this add-on was prepared. These documents do **not** create, publish or modify a repository.
