# LensQi FieldOps — Vendor Knowledge & Diagnostics
## Kiro Implementation Tasks

**Spec:** `vendor-knowledge-and-diagnostics`  
**Dependency:** Parent FieldProof v1.1 basic inspection loop and deterministic verification policy.  
**Timebox:** Target 11–19 focused engineering hours for P0 (8–14 for VK-T01–T14, 3–5 for VK-T24–T27), only after the main Meta/Muse/SAM + false-repair vertical slice is green. See *Cutlines* item 0 first.  
**Do not** move the November 4, 2026 hackathon submission deadline to accommodate this feature.

## P0-A — Asset-bound knowledge pack (suggested 2–3 hours)

- [x] **VK-T01 — Add normative schema and loaders.** *(done 2026-10-27: `KnowledgePackValidator`, `FileKnowledgePackStore`, 27 Api tests)* Place `knowledge-pack.schema.json` and the supplied sample packs under `fixtures/vendor-knowledge/`. Implement strict schema validation in `KnowledgePackValidator`; reject unknown schema versions, unknown fields, invalid HTTPS vendor source URLs, duplicate document/fact/capability IDs, unresolved document references, `SiteConfigured` facts inside `VendorLinked` packs, facts whose `requiresFirmwareMajor` differs from `identity.firmwareMajor`, and future `accessedOn` dates. Packs load only from the server-controlled pack directory; never from a request body.
  - Acceptance: valid synthetic/MikroTik examples load; intentionally corrupted JSON is rejected with field-specific error.
  - Tests: `VendorKnowledgePackValidationTests` — port the 19 cases in `scripts/test_sample_pack_constraints.py` as the minimum negative set.
- [x] **VK-T02 — Compute immutable pack identity.** *(done 2026-10-27: SHA-256 over exact bytes in `LoadedKnowledgePack`; pinned-digest test)* Hash exact JSON bytes with SHA-256, record `packId`, revision, digest, load timestamp and origin kind (`VendorLinked` or `Synthetic`); avoid in-place mutation after an inspection uses a pack.
  - Acceptance: changing one byte changes digest and previous receipt is still associated with original revision.
- [x] **VK-T03 — Implement exact asset applicability.** *(done 2026-10-27: `KnowledgeApplicability`, `KnowledgeConflictDetector`, 11 Core tests)* Add `AssetIdentity` and pure `KnowledgeApplicability` checking manufacturer ID, product code, firmware major and any required revision. Model matching must not use fuzzy string equivalence.
  - Acceptance: wrong model / unknown required firmware / incompatible firmware never returns an `Applicable` model-specific fact.
- [x] **VK-T04 — Build bounded fact resolution.** *(done 2026-10-27: `KnowledgeResolver`, `FixtureBindingInvariants`, `GET /api/vendors/knowledge-packs`, 8 Core tests)* Return at most 10 (configurable) tag-matched, model-applicable fact cards with server-composed source citations, split from site configuration, plus the excluded facts with `Unverified`/`Inapplicable` verdicts and reasons. Reject fabricated document references and `factKey` conflicts. Expose `GET /api/vendors/knowledge-packs` for installed pack metadata.
  - Acceptance: every `VendorDocumented` fact returned includes legitimate source title, URL and locator; no copied manual text embedded in public fixture.

**Gate A:** An inspection bound to synthetic `LAB-SW-01` produces a synthetic, cited explanation; it never selects the MikroTik pack for that fictional device. *(Passed 2026-10-27 at resolver level: `Synthetic_fixture_resolves_only_synthetic_facts_with_server_composed_citations`; the inspection endpoint itself is VK-T05+.)*

## P0-B — Grounded Muse evidence request (suggested 2–3 hours)

- [x] **VK-T05 — Compose safe knowledge context.** *(done 2026-10-28: `KnowledgeGroundingComposer`, strict proposal schema, `MuseSparkChatClient` over Chat Completions)* Extend the Muse Spark input with applicable fact IDs, source metadata, known site topology and allowed evidence/probe IDs. Mark document text as untrusted quoted data.
  - Acceptance: evidence request explains which approved fact or site rule motivated it; unknown firmware leads to a clarification request, not a vendor-specific procedure.
- [x] **VK-T06 — Validate model proposals.** *(done 2026-10-28: `KnowledgeActionValidator` with the §5 mapping rules and prediction checks; `GroundedProposalService`; event persistence waits for the inspection store)* Add `usedFactIds`, `proposedDiagnosticId` and (when `nextAction` is `RunApprovedProbe`) `prediction` to a typed structured-output contract. Reject IDs not present in the resolved applicable facts, diagnostics not present in the bound site twin, and proposals whose `assetId` differs from the inspection's asset. Persist a `KnowledgeProposalRejected` event for every rejection. Implement the static `ProposedNextAction` → parent action mapping table in Core exactly as tabulated in `design.md` §5; an action with no permitted mapping for the current stage, or one failing its extra validation, fails with `action_not_allowed`.
  - Acceptance: a Muse response asking for `POST /rest/system/reboot`, a made-up fact, a foreign asset ID, an invented target, or `RunApprovedProbe` without a diagnostic ID returns a validation failure without a network call, and the rejection appears in the inspection event log.
- [ ] **VK-T07 — Surface sources in Android.** Add a compact `KnowledgeSourcesSheet` to the inspection UI (source title, section, `Documented`/`Site configured`/`Inferred`/`Measured`, known revision/cached/synthetic status). Preserve existing visual evidence/ROI flow.
  - Acceptance: technician can tell whether guidance comes from a manufacturer, the site's approved wiring plan, the model, or a live probe.

**Gate B:** Genuine Muse calls use retrieved fact IDs and display grounded next-view guidance; error/simulated branches are labeled honestly.

## P0-C — Diagnostic binding and FieldProof integration (suggested 2–4 hours)

- [ ] **VK-T08 — Bind approved diagnostics to fixture.** Add `vendorKnowledge`/`requiredKnowledgeFactIds` to the existing expected-twin record (or a separate validated binding file); preserve the old twin schema if already stable. Validate the binding against `fixture-binding.schema.json` and cross-check pack ID, identity fields and required fact IDs at load (reject with `binding_invalid`). Diagnostics still resolve only through trusted server config.
  - Acceptance: knowledge packs contain no target IP, password, login or arbitrary command; an unknown `diagnosticId` fails with `probe_not_allowlisted`.
- [ ] **VK-T09 — Normalize results and constraints.** Reuse parent `IDiagnosticProbe` / synthetic + lab HTTP adapters; ensure source `LiveLocal`, `Simulated` or `RecordedReplay`, timestamp, target alias, raw digest and **specific claim verified** are preserved.
  - Acceptance: a successful endpoint health check verifies only endpoint reachability, never physical port identity.
- [ ] **VK-T10 — Add documentation gates to FieldProof evaluation.** Validate cited required facts have matching asset, pack digest and version; do **not** change the existing required visual, fresh diagnostic and human acknowledgement predicates.
  - Acceptance: wrong-model fact cannot close; false repair remains `VerificationFailed` even with vendor manual guidance.
- [ ] **VK-T11 — Include references in FieldProof receipt.** Add fact IDs/document locators, pack identity/digest, applicability verdict and probe evidence to JSON and printable HTML exports; include the `predictions` and `visualChange` blocks from P0-E when present. Extend integrity verifier to detect changed bundled pack bytes if included.
  - Acceptance: receipt separates vendor documentation, site expectation, model inference and measured verification, with clear origin labels.

**Gate C:** The full synthetic inspection—manual guidance → additional photo → apparent repair → failed diagnostic → valid repair → approved verification → receipt—reproduces with no physical hardware.

## P0-E — Predict, then measure (suggested 3–5 hours; P0, after Gate C)

- [ ] **VK-T24 — Add `Prediction` to the proposal contract.** Extend `VendorGroundedProposal` and the Muse structured-output schema; the probe registry declares a closed value set per observation kind (`design.md` §6a). Validate diagnostic-ID equality, closed-set membership and `basedOnFactIds ⊆ usedFactIds`.
  - Acceptance: a `RunApprovedProbe` proposal without a prediction, or predicting a value outside the set, is rejected with `prediction_missing`/`prediction_value_invalid` and no dispatch.
- [ ] **VK-T25 — Register before dispatch, score in Core.** Append `PredictionRegistered` before the dispatcher runs; `PredictionScorer` compares normalized values on `DiagnosticCompleted` and appends `PredictionScored`. The verification policy reads neither event.
  - Acceptance: the synthetic fake fix yields `Falsified`, the genuine repair yields `Confirmed`, and every state transition is byte-identical with predictions disabled.
- [ ] **VK-T26 — Visual change analysis.** Perceptual hash (Core) → two SAM 3.1 calls (Meta) → IoU and centroid-shift metrics (Core) → `VisualChangeAnalyzed`. Thresholds in server config with a version string.
  - Acceptance: re-submitting the pre-repair photo yields `DuplicateImage` with zero SAM calls; the unchanged-port fake fix yields `NoVisualChange`; the genuine repair yields `Changed`; a photo with no target yields `Unscorable` and manual fallback.
- [ ] **VK-T27 — Surface and record.** Prediction card (`Inferred` → `Measured`, outcome) and change verdict in the Android drawer or the web fallback; `predictions` and `visualChange` receipt blocks with the per-inspection tally.
  - Acceptance: the demo run's receipt shows at least one `Confirmed` and one `Falsified` prediction and a change verdict per repair claim.

**Gate E:** The demo video can show a falsified prediction and a rejected duplicate photo without any scripted shortcut.

## P0-D — Hardening and judging proof (suggested 2–4 hours)

- [ ] **VK-T12 — Implement negative-case suite.** Cover wrong model, wrong manufacturer, hardware revision mismatch, unknown firmware, conflict, missing document locator, prompt injection, fabricated fact ID, foreign asset ID in a proposal, disallowed diagnostic, client-supplied pack, binding with unknown fact ID, stale probe, visual-only pass, replay labeled as live, pack tampering, missing/late/invalid prediction, duplicate post-repair photo, and no-change post-repair photo.
  - Acceptance: no false `VerifiedCompleteLive` in scripted tests.
- [ ] **VK-T13 — Verify clean-room reproducibility.** Clone the fresh public repo, load only synthetic fixtures, run `.NET` tests, boot API and show Android or clearly labeled fallback demo with no private manuals or credentials.
  - Acceptance: README documents setup, model key via server secret, optional live calls and deterministic simulator mode.
- [ ] **VK-T14 — Record judged contribution.** Update `HACKATHON_CONTRIBUTIONS.md` (feature, date, event-period commit SHA, tests, public source), `PRIOR_ART.md` (copied-source register stays empty unless the indie reuse rule is clarified in writing), and attribution/source documents.
  - Acceptance: three-minute video highlights only hackathon-created functionality; source links are accessible and rights-cleared.

**Gate D:** FieldProof demo remains working; new knowledge feature increases grounding without making verification less conservative.

## P1 — Optional owned-router adapter (after all P0 gates)

- [ ] **VK-T15 — Add opt-in `RouterOsReadOnlyProbe`.** Only `GET /rest/interface/{configuredInterface}` over HTTPS; fixed interface in operator-approved fixture binding; verify certificate/identity; dedicated read/REST user.
- [ ] **VK-T16 — Validate RouterOS response.** Parse string `running` values and normalize `LinkReportedUp`/`LinkReportedDown`/`Unavailable` with model/firmware/version provenance. A link-up result does not imply end-to-end service.
- [ ] **VK-T17 — Integration fixture tests.** Mock redirect, invalid TLS, unauthorized status, bad interface, unknown fields, network failure and timeout; assert no POST/PUT/PATCH/DELETE or arbitrary path ever leaves adapter.
- [ ] **VK-T18 — Real owned-device smoke (only if hardware available).** Run against an explicit, isolated approved lab router; demonstrate only measured supported claims; write hardware/test source into the evidence receipt.

**P1 is optional and must not delay P0 or the submission.**

## P2 — After hackathon

- [ ] `VK-T19`: Permission-aware document ingestion pipeline (vendor manuals, PDFs, DOCX), layout and table extraction with citation spans and verified usage rights.
- [ ] `VK-T20`: PostgreSQL + pgvector/Timescale hybrid retrieval, document revision index and conflict detection with human review.
- [ ] `VK-T21`: Additional read-only vendor adapters behind capability-specific contracts; add integration tests for each manufacturer/version.
- [ ] `VK-T22`: Vendor bulletins/firmware advisory subscription; alert operators to outdated packs without rewriting historical receipts.
- [ ] `VK-T23`: ServiceNow/MaintainX/other CMMS approval-gated work orders, RBAC and multi-tenant document entitlements.

## Cutlines and critical path

0. **Stage One gate, non-negotiable.** *(Runner: `dotnet run --project tools/LensQi.FieldOps.StageOneGate` with `MODEL_API_KEY` set; writes `docs/evidence/stage-one-gate-*.json`. **Passed 2026-10-28 01:43 UTC** with `muse-spark-1.3`: two scenarios, both proposals accepted by `KnowledgeActionValidator`; see `docs/evidence/stage-one-gate-20261028T014314Z.json`.)* A genuine Muse Spark structured-output call and a genuine SAM 3.1 call, made from event-period code and recorded in event-period commits, come before any task in this file. A submission with no real Meta model call fails hackathon Stage One regardless of everything else here. If those calls are not working, stop all VK work and fix them first.
1. If the **main** FieldProof false-repair verification is not green: stop feature work after `VK-T04`; do not integrate a new vendor adapter and do not start P0-E.
2. If the Meta calls work but knowledge grounding is unstable: demo the grounded call on the synthetic pack only. Never present deterministic pack output as model output, and never present model output as a measurement.
3. If time is short after Gate C: implement `VK-T24`–`VK-T25` (predictions) before `VK-T26` (change analysis). Predictions are cheaper and carry the frontier story on their own; change analysis is the second independent check.
4. If there is no actual RB760iGS hardware: `VK-T15`–`VK-T18` remain unimplemented and the demo is explicitly synthetic; don't mock a hardware interaction as live.
5. If the documentation resolver fails: keep the validated pack+source display and prioritize the core state machine, SAM/Muse evidence loop and receipt.
6. No changes to the private LensIQ repository are required or permitted by this plan.

## Test commands (adapt to final solution paths)

```bash
# Backend unit/integration tests (solution root)
dotnet test --nologo

# Run the API locally; fails closed at startup on any invalid pack or binding
dotnet run --project src/LensQi.FieldOps.Api

# Compile Android client; use local documented Java/Gradle toolchain
cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest

# Validate shipped JSON examples and the fixture binding against their schemas
python scripts/validate_vendor_packs.py

# Negative regression cases for the pack and binding formats
python scripts/test_sample_pack_constraints.py
```

Both Python scripts ship in this kit under `scripts/` and run as-is; copy them with the fixtures. They check fixture integrity only and are not a substitute for the .NET tests above. If no Python runtime is desired, use a .NET JSON Schema validator, port the same cases, and document the equivalent command instead.
