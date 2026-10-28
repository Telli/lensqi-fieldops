# Review of the FieldOps v1.1 Kiro kit and PRD

**Reviewed:** October 28, 2026 (UTC), against `LensQi_FieldOps_v1.1_Kiro_Kit.zip` and `LensQi_FieldOps_PRD_v1.1.md` (the PRD inside the zip is byte-identical to the standalone file). Checked against the hackathon participant guide, the Meta Model API docs as they read today, and the code already in this repository (commits `f46e967` to `c5d0984`).

## 1. Verdict

The PRD is unusually honest and well-bounded for a hackathon document. The epistemic state machine, the fail-closed dual-proof gate, the live/simulated separation and the ten negative tests are exactly the shape a "verifiable agent" entry should have, and they match what Meta is teaching this week. The kit is build-ready in intent. It is not yet build-ready in detail: the fixture schema under-validates, the vocabulary drifts between PRD, design and the vendor-knowledge child spec, one model id is wrong, the SAM transport is different from the Muse transport, and the day plan is already a day behind. None of these are hard to fix. Section 3 lists them in priority order.

**Competition fit** is strong on theme and Stage One is already satisfied (genuine `muse-spark-1.3` calls recorded in `docs/evidence/`). The two things most likely to sink the entry are scope (a native Android app plus a SAM parser plus the full loop, solo, in seven days) and the "simple image analyzer" optics if the video does not open with the fake-fix rejection. Section 4 covers both.

## 2. What the kit gets right

- One fixture, one fault, one probe, one fake fix, one real fix. The cutlines are explicit and dated.
- `InspectionStage` is kept distinct from `VerificationOutcomeKind`. Simulated success can never be labelled live.
- Probe targets resolve only through server-owned aliases; the schema already rejects a URL in `targetAlias`.
- Hash receipts are described as bundle integrity, never as proof of authenticity. Judges who know security will notice and respect this.
- The reuse audit of the private LensIQ repository is specific and conservative.

## 3. Improvements, in priority order

### 3.1 Fix before building on it (today)

1. **SAM model id.** Design, tasks and PRD §16 write `sam-3-1`. The Model API models page lists `sam-3.1`. The Muse id is correct. One find-and-replace, but a wrong id would cost the Day 2 gate an hour of confusion.

2. **SAM uses a different transport than Muse.** SAM 3.1 answers on `POST /v1/responses` with `input_text` and `input_image` parts and returns special-token text in the `output_text` lane (boxes in pixel coordinates with inclusive `x2`/`y2`, masks as a compressed data payload). The Muse client built so far uses `/v1/chat/completions`. The design should say plainly that the Meta project has two adapters with two wire formats, and that the SAM adapter is streaming-first. Two unknowns to test before anything else on Day 2: whether `input_image` accepts a `data:` URL (the docs only show `https://` URLs), and whether `metadata.mask_encoding: "one_bit"` plus `include_confidence` work as documented.

3. **SAM parser decision.** The PRD proposes a TypeScript worker around `@meta-sam/parser`. The docs now also list `meta-sam-parser` on PyPI with no dependencies and Python 3.10+, which is already installed here. A Python sidecar is simpler to run from .NET than a Node one, and the repository already has a Python toolchain for the fixture validators. Recommendation: Python sidecar for Day 2, and treat a native C# decoder as a stretch validated against the sidecar as oracle. The grammar reference page needs to be read before any native attempt; the client-libraries page alone does not define the mask payload.

4. **Fixture schema under-validates.** `expected-twin.schema.json` accepts a connection whose `fromAssetId` does not exist, duplicate `assetId` and `diagnosticId` values, asset IDs with whitespace, and free-text `requirementId` values. Its `$id` is still `https://example.invalid/...`. Apply the same two-layer pattern the vendor-knowledge pack already uses: JSON Schema for shape, then a cross-reference invariants pass (asset references resolve, IDs unique and pattern-constrained, one probe per diagnostic id), run by both the Python validator and the .NET loader. The twelve rejections the schema does catch (URL alias, shell type, zero max age, oversized timeout, unknown fields) are good and should become test cases.

5. **Two sources of truth for the diagnostic allowlist.** The twin's `diagnostics[]` and the vendor-knowledge binding's `approvedDiagnostics` both say which probes an asset may run. Rule to add: the binding's list must be a subset of the twin's `diagnostics[].diagnosticId` for the same fixture, checked at load. Otherwise the two can drift and the weaker one wins.

### 3.2 Align vocabularies (this week)

6. **One next-action enum.** The design has `ProposedNextAction { RequestView, SegmentRegion, RunApprovedProbe, PrepareWorkOrder, RequestHumanReview, StopInconclusive }`. The PRD §9 example uses `nextAction: "request_another_view"`. The vendor-knowledge child spec defines its own `VendorGroundedNextAction { RequestView, RunApprovedDiagnostic, RequestAssetConfirmation, RequestHumanAction, Abstain }` with a mapping table because the parent was unavailable when it was written. Now that both exist, keep one. Recommendation: the parent enum is the wire vocabulary; the child's five values map as `RequestView → RequestView`, `RunApprovedDiagnostic → RunApprovedProbe`, `RequestAssetConfirmation → RequestHumanReview`, `RequestHumanAction → PrepareWorkOrder`, `Abstain → StopInconclusive`. `SegmentRegion` stays parent-only because the knowledge prompt never asks the model to drive SAM. This is a two-file change in Core plus the schema the composer emits.

7. **Observation status words.** PRD §7 uses `observed / suspected / unknown / conflicting`; design uses `Observed, Suspected, Unknown, Conflicting`; PRD §9 adds `assessment: "uncertain"`. Pick the enum form and drop `assessment` or define it.

8. **Outcome labels.** PRD §7 pseudocode returns `SimulatedVerified`; §17 names the kinds `VerifiedCompleteLive` and `VerifiedCompleteSimulated`. Use the §17 names everywhere, including for `RecordedReplay`, which the pseudocode currently folds into "simulated" without saying so.

9. **State table gaps.** `Created` can only go to `InitialScan`, yet R4 says a Meta timeout is a first-class inconclusive state. Add `Created → Inconclusive` (provider unavailable) and `InitialScan → NeedsEvidence` on image-quality failure, so the first photo failing does not need a hypothesis to exist.

### 3.3 Reconcile with the child spec and the code already written

10. **Reuse policy conflict.** PRD §4A, `COMPONENT_REUSE.md` and the kickoff prompt say no private source is copied until the indie reuse rule is clarified. The vendor-knowledge kit (in `docs/SOURCES_AND_RIGHTS.md`, revised yesterday on my recommendation) permits one labelled scaffolding import commit. These contradict each other, and the PRD's stance is the safer one. Nothing has been imported, and fresh equivalents of the useful utilities already exist in this repository. Recommendation: revert the child kit to the PRD rule, delete the import allowance, and record in `PRIOR_ART.md` that zero private source was copied. That is a cleaner story for judges than any labelled import.

11. **Repository layout.** PRD §9 lists only `.kiro/specs/lensqi-fieldops/` and `fixtures/telecom-lab/`. Add the child spec folder and `fixtures/vendor-knowledge/`, and add `LensQi.FieldOps.Diagnostics` to the solution since the PRD names it and the Core already defines `IDiagnosticRegistry` where the probe interface will attach.

12. **Templates that should now be real files.** The kit ships `HACKATHON_CONTRIBUTIONS.md`, `PRIOR_ART.md`, `SECURITY_AND_DATA_RIGHTS.md` and `DEMO_AND_EVALUATION.md` as templates. This repository has seven event-period commits and none of those files. Copy them in and fill the ledger now; retrofitting it on November 3 is how entries get disqualified on attribution.

13. **Predictions and change evidence.** The child spec's VK-R11 (pre-registered predictions) and VK-R12 (SAM before/after change metrics) do not appear in the parent. They do not need to change the parent's evaluator, which must ignore prediction outcomes by design, but FR-03 should gain a `segmentationPrompt` per `visualRequirements[]` entry (for example `yellow ethernet cable` for `yellow_cable_near_end_visible`) so change analysis has a declared target instead of inferring one from tags.

### 3.4 Smaller items

- `diagnosticMaxAgeSeconds: 60` means the probe must run inside the verify call or within a minute before it. Say that the verify endpoint triggers the probe itself; otherwise the demo will hit stale-diagnostic denials for the wrong reason.
- PRD §16 links the tool-calling cookbook, but the design uses structured output, not tool calls. Drop the link or state that tool calling is not used.
- The kit README still says it "does not create or publish any GitHub repository". The repository exists; update on merge.
- `.env.example` is listed in the layout but not in the kit. Add it with `MODEL_API_KEY=` and `Muse__ReasoningEffort=low`, since the first live run showed reasoning tokens exhausting `max_tokens`.

## 4. Competition fit

**Stage One (pass/fail).** Satisfied in substance: real `muse-spark-1.3` structured-output calls are recorded. The kit's own gate adds a real SAM call. That is Day 2, today, and nothing else should come before it.

**Stage Two (rubric, assumed: technical, innovation, impact, Meta use, demo).** Strengths are the ones already listed in section 2 plus the pre-registered predictions from the child spec, which turn "the model was right" into a measured rate. Weaknesses:

- **Scope.** The day plan allocated Day 1 to repo, fixture, contracts and the first Muse call. Day 1 actually went to the vendor-knowledge layer, the Muse client and the gate. The Core state machine, SAM, probes, verification and receipts are all still ahead, with seven days left and an Android app in P0. Recommendation: invoke the kit's own Oct 29 cutline now rather than later. Make a browser-based inspection UI the P0 surface and Android P1, labelled honestly as the kit already requires. Judges watch a video and clone a repo; they do not install an APK.
- **Optics.** Open the video with the fake-fix rejection, not with the manual lookup or the segmentation overlay. The participant guide lists "basic RAG" and "simple image analyzer" as anti-projects, and the first thirty seconds decide which bucket a tired judge puts you in.
- **Wearables.** Attractive to Meta judges, correctly P1. Do not touch it before November 1.

**Rules.** Public repo, new work only, rights-clean fixtures: all handled. The one open contradiction is the reuse policy in item 10; resolve it toward the PRD.

## 5. Suggested order for the next two days

1. Fix items 1, 2 and 4 in the kit files, merge the kit into this repository, copy the doc templates in and fill the contributions ledger with the existing seven commits.
2. SAM 3.1 spike: one real call through `/v1/responses` with a fixture photo, parsed by the Python sidecar, box drawn on the original image. Record the evidence file the same way the Muse gate does.
3. Core state machine and append-only events, with the ten negative tests from PRD §17 written first.
4. Simulated probe and the verify gate, then the fake-fix scenario end to end in the browser UI.
