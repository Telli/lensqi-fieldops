# LensQi FieldOps — Vendor Knowledge & Diagnostic Adapters
## Kiro Requirements Specification

**Spec ID:** `vendor-knowledge-and-diagnostics`  
**Version:** 1.2 · October 27, 2026 (see §8 for history)  
**Status:** Ready for implementation; functionality not yet claimed as shipped  
**Parent:** LensQi FieldOps / FieldProof Engine PRD v1.1  
**Hackathon:** Meta Global AI Developer Hackathon · *Push the Frontier* · public indie-track entry

## 1. Objective

Add **model-specific, citation-backed technical knowledge** to FieldProof's closed-loop inspection workflow. A technician should identify a known asset, receive applicable vendor guidance, gather the missing visual or diagnostic evidence, and verify a bounded maintenance outcome. Manuals guide the investigation; they **do not constitute evidence that a repair succeeded**.

This is not a free-form manual chatbot. The innovation is a link between *applicable documentation → approved inspection instruction → physical evidence → independent result → auditable receipt*.

### Prior requirements that remain authoritative

The new feature **extends** R1–R10 in `.kiro/specs/lensqi-fieldops/requirements.md` (asset fixture, visual evidence, Muse/SAM, evidence state machine, read-only diagnostics, dual-proof verification, receipts). It MUST NOT weaken the existing `VerifiedComplete` guard or add general network-device control. The parent specification and PRD take precedence if a new item conflicts with those safety invariants.

## 2. Scope and priorities

| Priority | Included | Excluded |
|---|---|---|
| **P0: Hackathon** | Versioned JSON vendor knowledge pack; exactly matched manufacturer/model; source references; fixed fact/step lookup; structured Muse grounding; asset-bound HTTP health/replay diagnostic; pre-registered predictions scored against measured results; SAM 3.1 change evidence on the cited target region; visible citations and evidence-receipt provenance; one real manufacturer example + a synthetic, reproducible lab pack | Open web crawling, automatic PDF extraction, vector database, arbitrary diagnostics, real vendor login, live CMMS writes |
| **P1: Only after FieldProof core passes** | Opt-in RouterOS v7 read-only REST adapter on owned test equipment; captured hardware/firmware inventory; hands-free spoken citations; offline cached packs | Production customer onboarding |
| **P2: Product roadmap** | Permission-aware PDF/Office ingest; diagrams and tables; pgvector/Timescale hybrid retrieval; multiple vendors and hardware revisions; firmware advisories; version diffing; vendor support portals; CMMS/ITSM | Hackathon-critical path |

The demo MUST remain reproducible without vendor hardware using an explicitly labeled `Simulated` or `RecordedReplay` probe. `VerifiedCompleteLive` requires real qualifying evidence; simulation cannot silently become live verification.

## 3. User stories

**US-VK-01 — Technician:** As a technician, I want FieldOps to match an asset to the correct vendor/model and explain what the relevant manual says, with citations, so I can avoid generic advice.

**US-VK-02 — Technician:** As a technician, I want the agent to identify missing evidence and request a safe next observation or approved test, so it doesn't invent a diagnosis from an ambiguous image.

**US-VK-03 — Supervisor:** As a reviewer, I want a repair receipt to record exactly which documentation and diagnostics informed the decision, so I can audit claims without confusing inference with measurement.

**US-VK-04 — Developer/judge:** As a reviewer of the public project, I want to run the full workflow with synthetic photos and a fake probe, so private documents and real equipment are unnecessary.

**US-VK-05 — Product operator:** As an operator, I want firmware/model incompatibilities, unavailable documentation, and vendor-API errors to fail safely and visibly rather than fall back to plausible but unverified advice.

**US-VK-06 — Judge/reviewer:** As a judge, I want the agent to commit to a falsifiable prediction before each measurement and to see whether the physical world agreed, so I can tell grounded reasoning from plausible narration.

## 4. Functional acceptance requirements (EARS-style)

### VK-R01 — Asset identity and applicability (P0)

1. WHEN an inspection starts, THE SYSTEM SHALL resolve an asset through an **explicitly selected or validated** asset ID from the versioned `ExpectedTwin` fixture, not from a free-text LLM guess.
2. WHEN a vendor pack is selected, THE SYSTEM SHALL require exact normalized manufacturer and model/product-code equality and verify the declared supported firmware-major and known hardware-revision constraints.
3. IF the asset's firmware is unknown and a fact requires a firmware version, THE SYSTEM SHALL mark that fact `ApplicabilityUnverified` and SHALL NOT use it to authorize a diagnostic or model-specific corrective instruction.
4. IF the model code is not a match, THE SYSTEM SHALL return `ModelMismatch` and SHALL NOT substitute a similar-looking model.
5. THE SYSTEM SHALL distinguish `ManufacturerSpecification` from `SiteExpectedState`: a vendor's generic port layout SHALL NOT establish the customer's specific cable or topology mapping.

### VK-R02 — Versioned knowledge packs and provenance (P0)

1. WHEN a JSON knowledge pack loads, THE SYSTEM SHALL validate it against `knowledge-pack.schema.json` and reject missing IDs, duplicate fact IDs, unresolved document references, unknown fields, invalid URLs, unsupported schema versions, `SiteConfigured` claims inside a `VendorLinked` pack, facts whose `requiresFirmwareMajor` differs from the pack's declared `identity.firmwareMajor`, and `accessedOn` dates in the future. The shipped `scripts/validate_vendor_packs.py` is the reference implementation of these rules.
2. WHEN loaded, THE SYSTEM SHALL compute and retain SHA-256 of the **actual pack file bytes**, its pack ID, declared version, and loading timestamp.
3. EVERY claim represented as vendor-documented SHALL reference an existing document ID plus a section/page/heading locator; claims without this SHALL be `Unverified` rather than assigned a fabricated citation.
4. THE SYSTEM SHALL expose source title, publisher, official URL, document version where known, and recorded retrieval date; a link does not by itself prove that the document remains current.
5. THE PUBLIC REPOSITORY SHALL contain only original/synthetic fixture assets, short original paraphrases of facts, and source links. It SHALL NOT redistribute full manuals or proprietary schematics without appropriate rights.
6. THE SYSTEM SHALL load knowledge packs only from a server-controlled directory or embedded resource. Packs or pack fragments supplied by the Android client, the model, or any request body SHALL be rejected with `pack_not_trusted`.
7. WHEN a site fixture binding references a pack, THE SYSTEM SHALL validate the binding against `fixture-binding.schema.json` and reject it at load if the pack is not installed, its identity fields differ from the pack's `identity`, or any `requiredKnowledgeFactIds` entry is absent from that pack.

### VK-R03 — Deterministic, bounded retrieval (P0)

1. WHEN Muse requires vendor context, THE KNOWLEDGE SERVICE SHALL retrieve a bounded subset (default at most 10, server-configured) of applicable fact IDs by model, capability/issue tag, and fixture context; it SHALL NOT retrieve the whole internet or arbitrary private files.
2. WHEN two facts contradict each other for the same model and applicable scope, THE SYSTEM SHALL surface `KnowledgeConflict`, preserve both source references, and request review rather than choose an answer silently.
3. IF no applicable facts exist, THE SYSTEM SHALL return `NoApplicableKnowledge` and may continue collecting generic visual evidence; it SHALL NOT produce model-specific instructions.
4. THE RETRIEVAL RESPONSE SHALL include fact IDs, citation IDs, applicability verdicts and source metadata in a typed structure, independently of the LLM's prose. Facts excluded as `Unverified` or `Inapplicable` SHALL be listed with their reason so the UI can ask for firmware or asset confirmation instead of silently dropping them.

### VK-R04 — Muse reasoning bounded to retrieved sources (P0)

1. WHEN an inspection hypothesis is generated, THE BACKEND SHALL pass Muse Spark only the relevant, applicable fact cards, confirmed site configuration and observation references.
2. THE MODEL OUTPUT SHALL include `usedFactIds`, `hypothesis`, `requestedVisualEvidence`, `proposedDiagnosticId` and a `nextAction` drawn only from the `VendorGroundedNextAction` set this spec defines (`RequestView`, `RunApprovedDiagnostic`, `RequestAssetConfirmation`, `RequestHumanAction`, `Abstain`; see `design.md` §5). THE SERVER SHALL map that value onto the parent FieldProof action allowlist for the current stage through a fixed code table and SHALL reject any proposal whose action has no permitted mapping with `action_not_allowed`. THE SERVER SHALL reject nonexistent fact IDs, an `assetId` other than the inspection's bound asset, unapproved procedure/probe IDs, and unauthorized actions. EVERY rejection SHALL be persisted as a `KnowledgeProposalRejected` event naming the offending IDs, so audits can show the boundary working.
3. WHEN documentation contains instructions that resemble prompts, network commands, credentials, or system messages, THE SYSTEM SHALL treat them as untrusted source content, not directives to the agent runtime.
4. WHEN a source backs only a specification (e.g. interface names) but not a diagnosis, THE UI SHALL distinguish the manufacturer's fact from the model's inference and from a measured diagnostic result.
5. THE MODEL SHALL NOT set `FaultConfirmed`, `VerifiedCompleteLive`, or `VerifiedCompleteSimulated` independently of FieldProof policy evaluation.

### VK-R05 — Safe knowledge-directed technician steps (P0)

1. WHEN a relevant fact suggests an observation, THE SYSTEM SHALL generate an evidence request that specifies a target component, reason, required evidence type and **safe qualitative** capture instructions (e.g. "show the labeled port clearly").
2. WHEN a procedure requires a physical change, THE SYSTEM SHALL mark it `HumanActionRequired` and SHALL NOT execute it as a software tool.
3. IF documentation advises potentially hazardous physical work, THE SYSTEM SHALL require qualified-technician review and SHALL NOT provide unsupported energized-equipment procedures.
4. WHEN SAM 3.1 returns a relevant mask/box, THE UI MAY highlight it; IF no object is found, THE UI SHALL show the no-match state and continue with a manual target selection or new view.

### VK-R06 — Diagnostic capability registry and probe execution (P0)

1. WHEN FieldOps proposes a diagnostic, THE BACKEND SHALL check that its `diagnosticId` appears in both the server-owned fixture's allowed diagnostics AND the configured probe registry.
2. THE REQUEST SHALL accept only the fixture-bound `diagnosticId`, not arbitrary network URLs, IP addresses, commands, interface paths or credentials supplied by Muse or the Android client.
3. WHEN a diagnostic runs, THE SYSTEM SHALL record `diagnosticId`, asset ID, expected-twin revision, source kind (`LiveLocal`, `Simulated`, `RecordedReplay`, or `VendorReadOnly`), start/end times, raw-result digest, normalized observation and exact claim verified.
4. IF a live target redirects, resolves outside the configured allowlist, loses certificate validation, or times out, THE PROBE SHALL fail closed and retain an error, never substitute a simulated pass.
5. WHEN a probe returns `running=true` for a router interface, THE SYSTEM SHALL treat it as **link state only**, not proof of correct port mapping or end-to-end application health.
6. THE DIAGNOSTIC SERVICE SHALL NOT execute general RouterOS console commands, arbitrary `POST`, SSH, shell scripts, configuration writes or network scans.
7. A knowledge-pack fact or capability that names a diagnostic ID (for example `LAB-HEALTH-CHECK-01`) is a recommendation only. Execution authority SHALL come solely from the fixture's `approvedDiagnostics` and the server probe registry.

### VK-R07 — Optional MikroTik RouterOS adapter (P1)

1. IF an operator explicitly enables the MikroTik adapter for an owned test router, THE SYSTEM SHALL connect over validated HTTPS using a dedicated read-only RouterOS user and a server-configured trusted endpoint.
2. THE ADAPTER SHALL be limited to a constant allowlist of GET resources (initially `GET /rest/interface/{configuredInterface}` with a fixed allowed property list).
3. IF the RouterOS version, interface name, device identity or certificate trust fails verification, THE ADAPTER SHALL return `Unavailable` or `UnsupportedVersion` without weakening repair verification.
4. THE ADAPTER SHALL never infer that an unmanaged-switch port maps to a RouterOS interface unless the site's asset inventory explicitly establishes that correspondence.

### VK-R08 — FieldProof state-machine integration (P0)

1. WHEN an inspection has `requiredKnowledgeFactIds` in its fixture policy, THE VERIFICATION ENGINE SHALL confirm the cited facts were applicable to the bound asset/revision when the associated evidence or technician action was assessed.
2. IF source applicability is unknown or contradicted for a required step, THE SESSION SHALL remain `NeedsEvidence` or `Inconclusive` pending review; it SHALL NOT be marked verified on manual text alone.
3. WHEN a technician records a repair claim, THE SYSTEM SHALL invalidate pre-repair diagnostics for completing that claim, as already required by FieldProof v1.1.
4. WHEN post-repair visual evidence appears acceptable but a required independent diagnostic fails, THE SYSTEM SHALL set `VerificationFailed` with the relevant diagnostic finding and documentation cited only where applicable.
5. IF required visual and approved diagnostics both pass, THE STATE MACHINE MAY advance under the existing scoped human-acknowledgement rules; vendor documentation SHALL NEVER replace these gates.

### VK-R09 — Evidence receipts and user interface (P0)

1. WHEN a documentation-grounded suggestion is shown, THE ANDROID CLIENT SHALL display a citation affordance (document title and locator, with link when available) and a visible `Documented`, `Site configured`, `Inferred`, or `Measured` distinction. Links the client opens SHALL come only from the server-resolved citation list, never from model prose.
2. WHEN verification is attempted, THE EVIDENCE RECEIPT SHALL include the pack SHA-256, pack version, cited fact IDs, source references, applicable device identity, diagnostic IDs, their provenance, and policy result.
3. IF a cited pack or diagnostic payload changes after receipt generation, THE RECEIPT VERIFIER SHALL detect an inconsistent bundle hash; it SHALL NOT claim cryptographic authentication of the physical event.
4. WHEN an equipment model cannot be confirmed, THE UI SHALL say so and request asset confirmation rather than invent an applicable manual.

### VK-R10 — Reproducibility, accessibility and failure handling (P0)

1. WHEN cloned in a clean environment, THE PUBLIC REPOSITORY SHALL run the synthetic vendor pack + simulated diagnostic workflow without vendor accounts, credentials, physical network equipment, private PDFs, or the original LensIQ monorepo.
2. WHEN unavailable offline, THE APP SHALL show locally packaged knowledge with a `Cached`/`Synthetic` label as appropriate and SHALL NOT claim a fresh vendor check or live diagnostic.
3. WHEN a vendor source changes or is inaccessible, THE SYSTEM SHALL retain the originally used pack digest and recorded accessed date; it SHALL NOT silently rewrite earlier receipts.
4. THE UI SHALL support readable source names and basic text guidance without mandatory glasses or audio hardware.
5. THE ADDITION SHALL NOT increase the bounded model/tool-call loop or allow the agent to bypass an inconclusive outcome.

### VK-R11 — Pre-registered predictions (P0)

1. WHEN a proposal's `nextAction` is `RunApprovedDiagnostic`, THE MODEL OUTPUT SHALL include a `prediction` with the same `diagnosticId`, a `predictedValue` drawn only from the closed value set the probe registry declares for that diagnostic's observation kind, and the `basedOnFactIds` it relies on (a subset of `usedFactIds`). A proposal without a prediction, or with a value outside the closed set, SHALL be rejected with `prediction_missing` or `prediction_value_invalid` and no dispatch.
2. THE SYSTEM SHALL persist the prediction as a `PredictionRegistered` event, with its digest and timestamp, BEFORE the diagnostic is dispatched. A prediction arriving after a result exists for that dispatch SHALL be rejected with `prediction_late`, and a probe result SHALL NOT be disclosed to the model before registration.
3. WHEN the diagnostic completes, CORE SHALL compare `predictedValue` with the normalized measured value deterministically and record `PredictionScored` with outcome `Confirmed`, `Falsified` or `Unscorable` (probe unavailable or errored). THE MODEL SHALL NOT score, revise or withdraw its own prediction.
4. Prediction outcomes are a calibration record, not a verification gate. A `Confirmed` prediction SHALL NOT advance state by itself and a `Falsified` prediction SHALL NOT by itself set `VerificationFailed`; the measured result drives the existing gates. Both outcomes SHALL be visible in the UI and the receipt.
5. THE RECEIPT SHALL include a `predictions` block (prediction, measurement, outcome, cited fact IDs, registration and measurement timestamps) and the per-inspection tally (for example 3 of 4 confirmed), labeled as model calibration, not evidence.
6. THE UI SHALL show the prediction as `Inferred` and the result as `Measured`, using the same provenance labels as all other content.

### VK-R12 — Segmentation-based change evidence (P0)

1. WHEN a technician submits post-repair visual evidence for a claim whose cited fact targets a component (for example a port), THE BACKEND SHALL run SAM 3.1 on the pre-repair reference image and the post-repair image with the same target prompt and SHALL record both masks or boxes with digests. The Android client SHALL NOT supply masks as evidence.
2. CORE SHALL compute deterministic change metrics without the model: a perceptual-hash distance between the two full images, mask overlap (IoU) for the target region, and, where both masks exist, the component's position relative to the target. Threshold values SHALL be server configuration, versioned, and recorded in the receipt.
3. IF the post-repair image's perceptual hash is within the duplicate threshold of any earlier image in the inspection, THE SYSTEM SHALL record `DuplicateImage` and SHALL NOT accept it as post-repair evidence or call SAM for it.
4. IF the cited target region shows no detectable change, THE SYSTEM SHALL record `NoVisualChange`; the post-repair visual gate for that claim is not satisfied, the session stays `NeedsEvidence`, and the UI SHALL request a new view or a corrected claim.
5. IF SAM returns no mask for the target in either image, THE SYSTEM SHALL record `Unscorable`, fall back to the existing manual target selection, and count it as neither change nor no-change; the parent human-reviewed visual gate applies unchanged.
6. A `Changed` verdict is evidence that *something in the cited region changed*, labeled `Measured (visual)`. It is never evidence that the repair is correct and SHALL NOT substitute for the required diagnostic.
7. Change analysis SHALL add at most two SAM calls per repair claim, SHALL run server-side, and SHALL fit within the parent's bounded tool-call budget.

## 5. Negative-case acceptance matrix

| Test | Required outcome |
|---|---|
| Wrong model or similar-looking model | `ModelMismatch`; no model-specific advice |
| Firmware unknown but version-sensitive fact | `ApplicabilityUnverified`; request confirmation |
| Missing manual locator / fabricated fact ID | Fact not cited or accepted |
| Two applicable facts in conflict | `KnowledgeConflict`, no silent winner |
| Manual contains `ignore instructions` prompt injection | Treated as data, tool permissions unchanged |
| Model requests disallowed diagnostic or URL | Reject; no network traffic |
| Port looks connected; endpoint unreachable | `VerificationFailed` |
| Live probe unavailable; simulated result is passing | Explicit simulated provenance, no `VerifiedCompleteLive` |
| Stale pre-repair diagnostic | Cannot close repair |
| Changed pack JSON or evidence file after receipt | Integrity verification fails |
| No hardware attached | Fully reproducible synthetic workflow |
| Manufacturer matches but hardware revision not in the pack's list | `HardwareRevisionMismatch`; no model-specific advice |
| Pack fact requires a firmware major different from the pack identity | Pack rejected at load |
| `SiteConfigured` fact inside a `VendorLinked` pack | Pack rejected at load |
| Client, model or request body supplies a pack or fragment | `pack_not_trusted`; server packs only |
| Muse proposal names a different `assetId` than the inspection | Rejected; `KnowledgeProposalRejected` recorded |
| Muse proposes `RunApprovedDiagnostic` with no `proposedDiagnosticId`, or `RequestHumanAction` with no `HumanActionRequired` fact cited | `action_not_allowed`; no approximation to a nearer action |
| `RunApprovedDiagnostic` proposal without a prediction, or predicting a value outside the closed set | `prediction_missing` / `prediction_value_invalid`; no dispatch |
| Prediction submitted after the probe result exists | `prediction_late`; not scored |
| Prediction falsified by the measurement | `PredictionScored: Falsified` recorded and shown; state driven by the measurement alone |
| Post-repair photo is the pre-repair photo re-submitted | `DuplicateImage`; not accepted as evidence; no SAM call |
| Post-repair photo shows no change in the cited port region | `NoVisualChange`; session stays `NeedsEvidence` |
| SAM finds no target in either image | `Unscorable`; manual target selection; no change claim either way |
| Fixture binding cites a fact ID absent from its pack, or identity differs from pack | Binding rejected at load |

## 6. P0 release gate / definition of done

- [ ] At least one **real manufacturer-linked knowledge pack** (MikroTik RB760iGS RouterOS 7, no copied manual pages) validates against the schema.
- [ ] A fully synthetic, correctly labeled knowledge pack drives the repeatable lab demonstration.
- [ ] Model/revision binding, citations, knowledge applicability and retrieval have passing unit tests.
- [ ] A genuine Muse call can use retrieved fact IDs and produce a grounded proposed next step; fabricated IDs fail validation.
- [ ] A fake-fix with a passing-looking photo but failing independent diagnostic is **not verified**.
- [ ] Every `RunApprovedDiagnostic` proposal in the demo carries a registered prediction; receipts show at least one `Confirmed` and one `Falsified` outcome, and no outcome changed a state transition.
- [ ] A re-submitted or unchanged post-repair photo is rejected by change analysis before any state advance.
- [ ] The inspection receipt includes pack IDs/digests, fact sources, test results and evidence hashes.
- [ ] Standalone public build with no dependence on private code or vendor credentials.

## 7. Timebox

**Target incremental engineering budget: 11–19 focused hours after FieldProof's core inspection loop works (8–14 for VK-R01–R10, 3–5 for VK-R11–R12).** P0 requires no live RouterOS account or custom vector index. If the core dual-proof demo is not working, implement only a curated pack + cited fact display, then return to the baseline state machine and Meta model integration. Detailed tasks and cutlines are in `tasks.md`.

## 8. Revision history

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-27 | Initial specification. |
| 1.1 | 2026-10-27 | Aligned claim kinds and proposal field names with the schema and design; added server-only pack trust (VK-R02.6), binding validation (VK-R02.7), excluded-fact reporting (VK-R03.4), rejection events (VK-R04.2), fact-is-not-authorization (VK-R06.7), `Site configured` label and link provenance (VK-R09.1); self-contained `VendorGroundedNextAction` contract with a fixed mapping onto the parent allowlist (VK-R04.2); seven new negative cases. Rationale in `docs/SPEC_REVIEW_2026-10-27.md`. |
| 1.2 | 2026-10-27 | Added VK-R11 pre-registered predictions and VK-R12 segmentation-based change evidence (both P0), US-VK-06, six negative cases, two release-gate items; timebox raised to 11–19 h. Rationale in `docs/HACKATHON_FIT_ASSESSMENT.md` §4. |
