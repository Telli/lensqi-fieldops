# Application completeness and business-logic review — 3 November 2026

**Verdict: not feature complete against all stated requirements, and not ready for an unqualified correctness sign-off.** Most of the intended hackathon product is implemented across API, web and Android. The normal inspection journey passes, but this review found nine demonstrated defects or missing requirements: four P1, four P2 and one P3. Two additional concerns remain unproven.

The strongest issues are contradictory changes after verification, restricted-work safety bypasses, required vendor facts missing from the verification gate, and receipt HTML receiving an integrity pass after modification or deletion. Passing tests do not cover these counterexamples.

Reviewed commit: `8fcea1bbd42ae1d9656a8b299461bdc72ee79518`, branch `main`. Scope: application-wide review across behavior, security/data integrity, feature claims, architecture, performance and unused feature wiring. No application source changes, commits, pushes or fixes were made. Existing untracked `scratch/` was left untouched. This report and local reproduction/evidence records are the deliverables.

## Feature completeness

| Area | Assessment | What remains |
|---|---|---|
| FieldProof inspection, approved probes, fake-fix denial, labelled simulated/live results, receipts | Implemented; normal native API journey and clean checkout pass | Fix closed-state and receipt defects below; live provenance has a configuration-dependent flaw |
| DIY/technician plan, acceptance, steps, optional checks, summary, linked inspection | Implemented on web and Android | A continuous user-operated technician journey with genuine models remains unvalidated |
| Restricted-work protection | Implemented, but incomplete | Persist conversation restrictions and validate all instructional fields; deterministic counterexamples below |
| Vendor packs, applicability, binding, grounded advisor and predictions | Implemented | Required fact applicability does not gate completion: VK-R08 / VK-T10 remains incomplete |
| Voice, hands-free and tap-to-ask | Implemented | Latest hands-free changes need a real microphone/speaker run; audible output and next-step voice command are not documented as accepted |
| Live AR and measured distance | Implemented; S23 Run 2 recorded actual anchors/distances and an answered spoken question | Phone shutter step-check, later spatial/marker/held-target changes, and genuine SAM outlines on a real step part still need acceptance evidence |
| Offline task recovery, queued steps/photos, searchable history, cited pages and work packages | Implemented, with tests and prior browser/emulator evidence | Latest Android site-pack and OCR additions need device acceptance; this review did not rerun physical/offline-device scenarios |
| Task glasses and teaching recorder | Implemented with mock/emulator evidence | Real-glasses and physical teaching acceptance remain unvalidated |
| Seven-day server artifact retention | Missing relative to PRD §10 | Implement deterministic retention/purge or revise the requirement; browser cache expiry does not fulfill this |
| Production RBAC/tenancy, CMMS integration, PostgreSQL retrieval, RouterOS device adapter, Muse Spark Open | Deferred/optional or explicitly not integrated | Not treated as unexpected P0 bugs in this review |

The latest feature notes are more reliable than unchecked boxes alone. PRD §1 and parts of README still describe the October 29 state: Android task glasses/offline checks, linked outcomes, and real-phone anchors have since shipped or been demonstrated. Current safety code replaces detected unsafe answers; the older “append-only referral” description is also stale. The old status claim that required-fact binding is absent is wrong: **the binding exists; verification integration is missing**.

Evidence: [feature status](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/docs/FEATURE_STATUS.md), [latest implementation notes](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/docs/quality-pass/2026-11-03-open-scope.md), [S23 Run 2](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/docs/evidence/tasks/s23-phone-run-2/run.json), [vendor requirements](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.kiro/specs/vendor-knowledge-and-diagnostics/requirements.md:103).

## Verified findings, ranked

P1 = fix before claiming the affected core/safety contract is complete. P2 = material defect or missing requirement. P3 = low-impact UI inconsistency.

### P1 — Closed inspections accept contradictory checklist changes

**QP-df645eca798c** · [src/LensQi.FieldOps.Core/Inspection/InspectionSession.cs](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/src/LensQi.FieldOps.Core/Inspection/InspectionSession.cs:247) · demonstrated

ReviewChecklist has no closed-stage guard. ChecklistReviewed clears acknowledgement but leaves the verified decision and stage intact. ReceiptBuilder combines current checklist with LastDecision.

**Reproduction:** Verify a normal inspection, then submit a fresh NotSatisfied mandatory-checklist review against the accepted post-repair photo.

**Observed:** VerifiedComplete/Verified becomes ack=False and checklist=NotSatisfied; fresh policy evaluation is Denied. Replay and a newly built receipt preserve the contradictory Verified state.

**Recommended correction:** Refuse new closed-inspection mutations inside the idempotent action, preserving replay of previously accepted keys; review acknowledgement and other mutators for the same rule.

**Verification:** `dotnet run --project .quality-pass/full-review-20261103/bugs/Bugs.csproj --no-build --nologo`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/closed-checklist.json) includes watched contracts and callers.

### P1 — Restricted-work context disappears on follow-up questions

**QP-799a94fd77f3** · [src/LensQi.FieldOps.Core/Tasks/TaskPlanValidator.cs](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/src/LensQi.FieldOps.Core/Tasks/TaskPlanValidator.cs:152) · demonstrated

IsRestricted checks only the plan, goal/details, and current question. Recorded questions are ignored; a free-form session has a generic goal.

**Reproduction:** Record a breaker-panel question and referral in a free-form task, then guard a prohibited model answer to How do I open it?, or guard live guidance without a new question.

**Observed:** First question is referred, followup_restricted=False, and both follow-up answer and live guidance return the known prohibited hands-on text unchanged.

**Recommended correction:** Persist server-owned restricted context and apply it to subsequent answers and live guidance.

**Verification:** `dotnet run --project .quality-pass/full-review-20261103/security-harness --no-build --nologo`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/restricted-context.json) includes watched contracts and callers.

### P1 — Restricted plans bypass safety checking through spoken cautions

**QP-79ac84191e15** · [src/LensQi.FieldOps.Core/Tasks/TaskPlanValidator.cs](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/src/LensQi.FieldOps.Core/Tasks/TaskPlanValidator.cs:93) · demonstrated

Restricted-work checking covers Instruction and Title, but cautions get only length/link checks. The web step reader speaks cautions verbatim.

**Reproduction:** Validate a MainsElectrical plan requiring a professional with HandsOn=false, a safe observation instruction, and a caution containing a prohibited panel-cover removal instruction.

**Observed:** InstructsHandsOn(caution)=True but Validate accepts the restricted plan.

**Recommended correction:** Apply restricted-work validation to every instructional field shown or spoken, including cautions, stop conditions and check criteria.

**Verification:** `dotnet run --project .quality-pass/full-review-20261103/security-harness --no-build --nologo`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/unsafe-cautions.json) includes watched contracts and callers.

### P1 — Required vendor knowledge never gates verification

**QP-3bb77853b3ce** · [src/LensQi.FieldOps.Api/Features/Inspections/InspectionEndpoints.cs](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/src/LensQi.FieldOps.Api/Features/Inspections/InspectionEndpoints.cs:294) · demonstrated

The binding and resolver implement RequiredKnowledgeFactIds, but verify has no binding/resolver input and the policy accepts only inspection state, twin and time. VK-R08.1-2 requires applicability to gate verification.

**Reproduction:** Make an in-memory installed pack require revision R2 while its binding has no confirmed revision; resolve required facts, then perform a normal otherwise passing inspection.

**Observed:** Binding invariant errors=0; Knowledge=ApplicabilityUnverified with zero applicable facts and two required fact IDs; verification still returns VerifiedCompleteSimulated.

**Recommended correction:** Feed resolved required-fact prerequisites into the deterministic gate and persist the applicable pack identity/digest and decision context.

**Verification:** `dotnet run --project .quality-pass/full-review-20261103/architecture/Architecture.csproj --no-build --nologo`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/required-knowledge.json) includes watched contracts and callers.

### P2 — Receipt integrity verification accepts modified or missing HTML

**QP-4b814141220f** · [src/LensQi.FieldOps.Core/Receipts/ReceiptVerifier.cs](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/src/LensQi.FieldOps.Core/Receipts/ReceiptVerifier.cs:59) · demonstrated

HTML only has to contain the recorded manifest string; absent HTML is ignored. The API verify adds no rendering check, although ZIP export already validates rendering.

**Reproduction:** Copy an issued receipt bundle, modify its HTML while retaining the digest, then delete HTML; verify each version. The reviewer also reproduced through an isolated native API.

**Observed:** Before=True, after HTML tamper=True, after HTML deletion=True. API evidence: verify=True after tamper/deletion while ZIP returns 409.

**Recommended correction:** Require and validate the human-readable rendering consistently in CLI, API verification and exports, with explicit older-format compatibility.

**Verification:** `dotnet run --project .quality-pass/full-review-20261103/security-harness --no-build --nologo`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/receipt-html.json) includes watched contracts and callers.

### P2 — A valid no-fresh-diagnostic policy falsely reports live verification

**QP-55469b57a4a4** · [src/LensQi.FieldOps.Core/Inspection/VerificationPolicyEvaluator.cs](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/src/LensQi.FieldOps.Core/Inspection/VerificationPolicyEvaluator.cs:132) · demonstrated

weakestSource remains null when RequireFreshDiagnostic=false, then null is treated as proof of live verification. The shipped fixture uses true; this is a configuration-dependent defect.

**Reproduction:** Set only RequireFreshDiagnostic=false in an in-memory fixture, satisfy visual/ack prerequisites, record a failing Simulated diagnostic, then verify.

**Observed:** Configuration has zero invariant errors; VerifiedCompleteLive is returned despite the single diagnostic being failing and simulated.

**Recommended correction:** Require affirmative qualifying live evidence for a live outcome; reject configurations that weaken the mandatory dual-evidence contract if unsupported.

**Verification:** `dotnet run --project .quality-pass/full-review-20261103/bugs/Bugs.csproj --no-build --nologo`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/live-without-probe.json) includes watched contracts and callers.

### P2 — Step camera disclosure omits saved question frames

**QP-8446c3e928db** · [apps/web/src/pages/m/task/step.tsx](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/web/src/pages/m/task/step.tsx:237) · demonstrated

The UI says Only check photos are saved to the task, but ask captures the live camera frame and the API persists it as TaskPhotoPurpose.Question before calling the model.

**Reproduction:** With an active step camera and coaching off, type or speak a question without taking a check photo. Trace step ask to the task ask endpoint.

**Observed:** A question frame is retained even though the visible copy claims only check photos are saved. Confirmed by deterministic caller-to-persistence trace; no device run.

**Recommended correction:** Disclose saved question frames separately from unsaved continuous live-coaching frames.

**Verification:** `nl -ba apps/web/src/pages/m/task/step.tsx | sed -n '101,113p;233,237p'; nl -ba src/LensQi.FieldOps.Api/Features/Tasks/TaskEndpoints.cs | sed -n '283,299p'`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/photo-disclosure.json) includes watched contracts and callers.

### P3 — Stopped task summary offers an always-refused verification action

**QP-e6839e392a07** · [apps/web/src/pages/m/task/done.tsx](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/apps/web/src/pages/m/task/done.tsx:55) · demonstrated

The Verify with FieldProof action is gated by site/inspectionId, not phase; link-inspection explicitly refuses Abandoned tasks.

**Reproduction:** Open the summary route for a stopped technician task with a site and no linked inspection; select Verify with FieldProof.

**Observed:** The button renders enabled and every request returns invalid_state. Direct summary route is reachable; ordinary stopped-plan navigation does not prominently expose it.

**Recommended correction:** Hide the create-link action for Abandoned tasks while retaining access to an existing linked inspection.

**Verification:** `nl -ba apps/web/src/pages/m/task/done.tsx | sed -n '45,58p'; nl -ba src/LensQi.FieldOps.Api/Features/Tasks/TaskEndpoints.cs | sed -n '304,310p'`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/abandoned-verify.json) includes watched contracts and callers.

### P2 — The specified seven-day server artifact purge is not implemented

**QP-62255cc62bfa** · [src/LensQi.FieldOps.Api/Infrastructure/Evidence/EvidenceFileStore.cs](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/src/LensQi.FieldOps.Api/Infrastructure/Evidence/EvidenceFileStore.cs:108) · demonstrated

PRD line 533 requires a seven-day demo artifact retention policy and deterministic purge. The evidence store only writes artifacts; startup/stores/scripts have no retention job or age-based deletion. Browser cache pruning is a separate 14-day/50-job policy.

**Reproduction:** Inspect artifact writes, store registration and scripts; search server sources for retention/purge/age-based deletion.

**Observed:** Only unrelated safety-word and diagnostic-staleness hits occur. Saved task/inspection artifacts have no implemented expiry. Static missing-requirement finding; no seven-day clock-advance test was run.

**Recommended correction:** Implement an explicit retention lifecycle for source artifacts and receipt copies, or revise the product requirement before claiming completeness.

**Verification:** `rg -n -i 'retention|purge|AddDays\(-7|TimeSpan.FromDays\(7|older.than' src scripts --glob '!**/wwwroot/**' --glob '!**/obj/**' --glob '!**/bin/**'`. [Finding record](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings/retention.json) includes watched contracts and callers.

The safety reproductions deliberately supplied prohibited model output to the real validator/guard. They demonstrate failures of deterministic enforcement; they do **not** claim a genuine provider generated those outputs during this review. The conditional no-fresh-diagnostic defect is not triggered by the currently shipped fixture, which sets `requireFreshDiagnostic=true`.

## Checks performed

- **339 .NET tests passed:** Core 159, Meta 18, API 162; zero failed/skipped.
- **78 web tests passed**, typecheck and production build passed.
- **311 Android unit tests passed**, debug/release assembly and debug/release lint passed; zero failed/skipped unit tests. Gradle reused valid cached outputs where appropriate.
- Secret scan and test-report adapter passed.
- A temporary clean checkout of this exact HEAD passed validators, negative fixture tests, native API hero flow, failed-fix rejection, successful simulated verification, offline receipt checks, artifact tamper rejection, and byte-for-byte web build reproduction.
- The primary reviewer independently reran the Core business, safety/receipt and required-knowledge harnesses. The security reviewer also exercised altered/missing receipt HTML through an isolated native API.
- No Docker was used. The current application uses JSONL/files and Android local storage; it has no PostgreSQL test dependency, so no database was created or modified.

Gate output (verbatim summary):

```text
== summary
  PASS  4.761s  ['./gradlew', ':app:testDebugUnitTest', '--rerun', ':app:assembleDebug', '--console=plain']
  PASS  0.872s  ['./gradlew', ':app:assembleRelease', ':app:lintDebug', ':app:lintRelease', '--console=plain']
  PASS  13.535s  ['python3', 'scripts/test_dotnet_report.py', '{report_dir}']
  PASS  0.344s  ['bash', 'scripts/secret_scan.sh']
  PASS  0.076s  ['python3', 'scripts/test_report_adapter.py']
  PASS  1.212s  ['npm', 'test', '--', '--reporter=default', '--reporter=junit', '--outputFile.junit={report_dir}/web.xml']
  PASS  2.132s  ['npm', 'run', 'build']
== vs baseline: EQUIVALENT
```

Gate commit: `8fcea1bbd42ae1d9656a8b299461bdc72ee79518`; baseline commit: `9858ef933cc613b8cdf752b50112dcc92018216b`; configuration hash: `4af56cf8c2a60fc3501d5e0f137975ac860902d4bfdbed21009c5dd5bc34685a`. Comparison status is EQUIVALENT with no changed failure evidence or reduced test coverage.

Evidence: [structured gate results](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/logs/20261104-012947-29ed4fe2/results.json), [clean-checkout log](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/clean-room.log), [business reproduction output](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/bugs/verified-by-root.txt), [safety and receipt output](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/security-harness/verified-by-root.txt), [native receipt API output](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/security-harness/api-results.json), [required-knowledge output](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/architecture/verified-by-root.txt).

## Scope limits

The review followed Core state/policy contracts into API endpoints, storage, receipt construction, web callers, Android navigation/queues and existing tests. Independent reviews covered all six dimensions, but this was not a line-by-line proof of every file. Android utility exports and all screen branches, provider internals, deployment configuration, and every test helper were not exhaustively reviewed.

No physical phone, camera, microphone, real glasses or new browser interaction was exercised in this pass. Genuine Muse/SAM evaluation was explicitly skipped with `--no-meta`; historical genuine-call evidence was inspected. Production deployment, public repository/video accessibility, submission and remote CI status were not checked. No large-history or concurrency fault-injection performance run was performed. These limits remain even though builds and unit/integration tests pass.

## Suspected concerns — outside the execution plan

- **QP-ad4a5d66a6fc — Concurrent receipt construction may mix mutable event/state versions.** Receipt composition enumerates mutable session events outside the inspection command gate. A stress or controlled interleaving test is still required; no inconsistent receipt or exception reproduced.
- **QP-9db0e2754942 — Plan download failure may present server JSON instead of an actionable error.** Plan uses a raw download link; summary uses tasks.package to display API refusal. Export returns JSON 409 for corrupt/missing evidence. Browser behavior of the raw link under a 409 must be exercised before claiming a user-visible defect.

## Rejected preliminary claims

Two claims were rejected after checking current implementation:

- **QP-b382f505541a — Current restricted-answer protection only appends a referral.** Rejected: first_question_referred=True; current logic does replace unsafe detected text. The narrower confirmed gaps are context and unchecked fields.
- **QP-841de7ca4009 — RequiredKnowledgeFactIds binding support is absent.** Rejected: binding and retrieval support exist. The narrower demonstrated gap is integration with verification.

## Performance and unused-code results

No demonstrated performance defect or verified dead-feature deletion candidate was found. The fresh build emits one eager application JavaScript bundle of **626,571 bytes (179.48 kB gzip)**, CSS **67,427 bytes (10.98 kB gzip)**, a **3,286-byte** tracking worker, and a **12,222-byte** offline worker. Vite warns about the >500 kB JS chunk. Mobile and operations routes share it; route splitting is an optional measured-size improvement, not evidence of unacceptable load time.

The 40 ms live timer is internally throttled to at most 10 fps, limits model requests in flight, and tears down when hidden. Stores append only unpersisted events. Histories remain unpaginated/in-memory and should be load-tested before production scale, but no latency claim was demonstrated.

Both path and symbol/registration checks found callers for API feature mappers, web routes and Android scanner/site-pack/advisor/prediction/voice/teaching/glasses entry points. Debug/release mock-glasses files are intentional variants. No deletion is recommended.

## Correction plan

1. Fix the four P1 findings together with failing-then-passing regression coverage: preserve terminal inspection consistency, retain restricted conversational context, reject unsafe text across all instructional fields, and enforce required-knowledge prerequisites without weakening visual/probe/acknowledgement checks.
2. Make receipt HTML verification consistent across API, CLI and export; prevent a live label without affirmative live measurements. Preserve historical receipt compatibility explicitly.
3. Correct the question-frame disclosure and stopped-task action. Implement or explicitly revise the seven-day retention requirement, including its treatment of issued receipt copies.
4. Refresh feature-status documents from current code and evidence. Complete the missing physical-device acceptance scenarios before marking the relevant features Done.
5. Re-run the same native gates and clean-checkout demo after corrections. Investigate the two suspected concerns only with controlled reproductions. No architecture rewrite, broad refactor, dead-code sweep or optional adapter work is justified by this review.

Preserve Core ownership of state transitions, typed append-only events, server-owned diagnostic aliases, per-feature API registration, and the separation between knowledge, inferred suggestions and measured verification.

Saved review state: [state.json](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/state.json); per-finding records and reproduction sources live under [this review’s index](/Users/telli/Downloads/LensQi_FieldOps_Vendor_Knowledge_Kiro_Kit/.quality-pass/full-review-20261103/findings-index.json). No finding was approved for implementation or marked fixed.

This review is complete. Fixes are a separate phase: the [review skill](/Users/telli/.codex/plugins/cache/quality-pass-marketplace/quality-pass/1.1.0/skills/quality-pass-review/SKILL.md) directs: “Produce the ranked, evidence-backed report and stop. No code changes”. This matches the requested review-only scope.

Approve to continue with `quality-pass-execute`.
