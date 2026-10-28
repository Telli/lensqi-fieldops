# LensQi FieldOps — Kiro Implementation Tasks

**Scope:** Only hackathon-generated functionality is judged. Do not modify or fork the private LensIQ repository.

## Re-baseline — Wed Oct 28 (UTC)

Day 1 actuals (commits `f46e967`..`c5d0984` in `Telli/lensqi-fieldops`): public repo created; .NET 10 solution with Core/Api/Meta/Diagnostics and tests; vendor-knowledge pack loader, digests, exact applicability and bounded resolver (child VK-T01–T04); `MuseSparkChatClient` and grounded-proposal validator (VK-T05–T06); **Stage One gate passed with genuine `muse-spark-1.3` structured-output calls** (`docs/evidence/`). Not started: Android, expected-twin loader, Core state machine, SAM, probes, verification, receipts. T0.2, T0.3 (backend half) and T0.5 are done; T0.4 and the Android half of T0.3 are open. Day 2 proceeds as written with SAM first. The P0 UI-surface decision (browser vs Android) is recorded in PRD §19.

## Day 1: Tue Oct 27 — Contract + real Muse

- [ ] T0.1 Record official indie prior-code reuse clarification and event start in `docs/HACKATHON_CONTRIBUTIONS.md` (or mark unresolved; do not copy private source).
- [ ] T0.2 Initialize clean public repo + copyright/license/data-rights review + `.gitignore` / `.env.example` / secret check.
- [ ] T0.3 Scaffold .NET 10 solution with Core/Api/Meta/Diagnostics and tests; Kotlin/Compose Android app with no private-source imports.
- [x] T0.4 Implement `ExpectedTwin` schema validation, cross-item invariants and version hash; load synthetic `LAB-RACK-01`. *(Done 2026-10-28: `ExpectedTwinStore` + `ExpectedTwinInvariants`; binding ⊆ diagnostics check is in the Python validator, .NET cross-check pending.)*
- [x] T0.5 Implement Model API `muse-spark-1.3` with strict typed response; log provider name, request ID and elapsed time without image/key leakage. *(Done 2026-10-28: text-grounded call passed; the first image request is folded into T1.3.)*
- [ ] **Gate:** successful real Muse call; invalid asset IDs refused.

## Day 2: Wed Oct 28 — Real SAM + ROI

- [x] T1.1 Integrate `sam-3.1` image segmentation with a short object noun prompt. *(Spike done 2026-10-28: genuine calls, `yellow ethernet cable` found at 0.73 confidence, `network switch` honest no-match; C# adapter next.)*
- [x] T1.2 Normalize special-token boxes/masks with Meta's `meta-sam-parser` in a small internal Python sidecar; portable JSON ROI response (half-open pixel boxes, COCO RLE masks, box-local bounds, confidence). *(Done 2026-10-28: `tools/sam-parser/sam_parse.py`; data-URL input and `one_bit` encoding confirmed.)*
- [ ] T1.3 Implement image original/preview capture and high-res ROI crop, with transform test fixtures.
- [ ] T1.4 Build minimal Android inspection screen showing image, ROI and next requested view.
- [ ] **Gate:** real SAM response and working mask/box display, with explicit no-match behavior.

## Day 3: Thu Oct 29 — Epistemic loop

- [x] T2.1 Implement Core state machine and append-only evidence metadata, including known/suspected/unknown/conflicting. *(Done 2026-10-28: `Core/Inspection/*`, 15 event types, `InspectionState.Replay`.)*
- [x] T2.2 Implement explicit `RequestAnotherView` with no more than two additional views. *(Done: third request → `Inconclusive`.)*
- [x] T2.3 Enforce allowed fixture IDs, evidence freshness, idempotent request IDs, typed bad-state responses. *(Done at Core level: `InspectionConflictException` codes; API mapping pending.)*
- [x] T2.4 Make ambiguous scene force recapture or inconclusive; cannot auto-advance. *(Done.)*
- [ ] **Gate:** targeted evidence request causes actual recapture; all state tests pass.

## Day 4: Fri Oct 30 — Diagnostics

- [x] T3.1 Implement allowlisted `IDiagnosticProbe` with HTTP health check, no arbitrary URL, strict local target resolution. *(Done 2026-10-28: `LiveLocalHttpHealthProbe`, redirects/oversize/exceptions fail closed; alias map is server config.)*
- [x] T3.2 Implement `SimulatedProbe` and `RecordedReplayProbe` with explicit provenance. *(Done; demo switch at `POST /v1/simulation/diagnostics/{id}`, only in Simulated mode.)*
- [x] T3.3 Validate failure/success against isolated fixture; document what the probe actually establishes. *(Done with the simulated fixture over HTTP; live owned endpoint still to be exercised.)*
- [ ] **Gate:** end-to-end probe flow, bounded failures, simulator visibly labeled.

## Day 5: Sat Oct 31 — Fake-fix checkpoint

- [x] T4.1 Implement repair-claim action, invalidating earlier verification evidence. *(Done in Core 2026-10-28.)*
- [x] T4.2 Implement deterministic dual-proof verification with human-reviewed visual checklist. *(Done in Core: `VerificationPolicyEvaluator` v1.1.)*
- [x] T4.3 Record **visual pass / telemetry fail** and show `VerificationFailed` with human-readable reason. *(Done over HTTP: `POST /verify` denies with the diagnostic reason; UI display pending.)*
- [x] T4.4 Add negative tests for stale, wrong fixture, unknown, simulated-as-live, and duplicate verify. *(Done: the ten PRD §17 cases plus view budget, freshness window, checklist review; 19 tests.)*
- [ ] **Gate:** cannot be tricked by an apparently plugged-in cable; no false live verification in scripted suite.

## Day 6: Sun Nov 1 — Completion + receipts

- [x] T5.1 Capture fresh post-repair frame and passing diagnostic; distinguish live vs simulated verification. *(Done at API level: `VerifiedCompleteSimulated` returned; receipts (T5.2/T5.3) pending.)*
- [ ] T5.2 Create JSON + printable HTML evidence manifest, content hashes and verification CLI.
- [ ] T5.3 Flip artifact bytes in tests and confirm receipt verification fails.
- [ ] **Gate:** full fault → failed fix → valid fix → receipt loop reliably reproduces.

## Day 7: Mon Nov 2 — Polish before wearable extras

- [ ] T6.1 Harden cancellation, timeouts, image size, quality/occlusion and provider-down behavior.
- [ ] T6.2 Improve Android flow/copy and add spoken guidance only if P0 remains green.
- [ ] T6.3 Optional Meta Device Access Toolkit + clearly labeled MockDevice input; no actual hardware claim without test.
- [ ] **Gate:** clean-install demo and core tests pass.

## Day 8: Tue Nov 3 — Evaluation/video

- [ ] T7.1 Run 20–30 labeled observations and a few independent held-out cases; measure one-shot vs active evidence workflow.
- [ ] T7.2 Record model IDs, genuine Meta calls, measured latency, inconclusive rates and false-completion count.
- [ ] T7.3 Record three-minute video: observe → ambiguous → request view → fake fix denied → fresh repair succeeds → receipt.
- [ ] T7.4 Update `HACKATHON_CONTRIBUTIONS.md` and `PRIOR_ART.md` including new-work commits.
- [ ] **Gate:** publicly runnable code, original/cleared fixture media, video links, reproducible demo.

## Day 9: Wed Nov 4 — Freeze and submit

- [ ] T8.1 Stop feature additions; code/secret/license scan; README from fresh checkout; sanitize screenshots/logs.
- [ ] T8.2 Check public repo/video live accessibility and accurate build-period attribution.
- [ ] T8.3 Submit via Cerebral Valley before 9 PM ET / 6 PM Pacific.

## Hard cutlines

- If real Muse or SAM calls are blocked on Oct 28, prioritize a minimal genuine Meta-powered vertical slice, not polished mock UI.
- If Android is unstable on Oct 29, permit a basic browser/evaluation UI for backup **but do not represent it as a working Android build**.
- If SAM ROI overlay remains unreliable on Oct 29, expose real output in a separated evidence panel, not a fake on-image mask.
- If the full dual-proof loop is not working on Nov 1, cancel all voice, glasses and P2 work.
- Do not publish or import private LensIQ source while indie reuse permission is unresolved.

## Definition of done

- [ ] Event-period public implementation with no private dependencies
- [ ] Real Muse + SAM traces
- [ ] Android P0/alternative transparently labeled
- [ ] Active evidence request + second image
- [ ] Fake fix rejected + actual bounded verification outcome
- [ ] Receipt generated and altered-artifact check detects tampering
- [ ] Labeled demo fixtures, metrics, runnable scripts, three-minute video
