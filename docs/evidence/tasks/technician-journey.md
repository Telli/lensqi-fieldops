# Continuous technician journey

Recorded October 28, 2026 (Pacific). **FAKE_MODELS_SIMULATED_DIAGNOSTICS**. No genuine model calls, physical repair,
phone run or live equipment measurement is claimed. The test uses the real ASP.NET API, task and inspection event
stores, FieldProof gate and receipt implementation, with fake Muse/SAM/image analysis and simulated diagnostics.

`TechnicianJourneyTests.Guided_technician_task_rejects_fake_fix_then_issues_offline_verifiable_simulated_receipt`
passed, along with all **211 .NET tests** (96 Core, 18 Meta, 97 API). The existing negative tests remain in that run.
The web suite passed **25 tests**, and the production web build passed after adding speech-provider disclosures.

## What the run proves

1. A Technician task bound to fixture `LAB-RACK-01`, asset `LAB-SW-01`, accepts its plan and completes both steps in
   order (no skips). Task completion itself has no inspection verification outcome.
2. Linking creates an inspection for the same fixture/asset. Repeating the link returns the same inspection and emits
   only one `InspectionLinked` event.
3. An initial failed simulated diagnostic establishes the fault. After a repair claim, distinct header-only test-image bytes and
   a moved fake segmentation produce `Changed`; the human checklist and acknowledgement are submitted. Verification
   still returns `Denied` / `VerificationFailed` because the diagnostic fails.
4. A second claim, fresh accepted photo, human review and passing simulated diagnostic produce
   `VerifiedCompleteSimulated`. Repeating verify with the same idempotency key returns the same decision.
5. All receipt diagnostics remain `Simulated`. The receipt is copied out, the API is stopped, and the offline verifier
   passes. A one-byte artifact change fails; restoring the original bytes passes again. The standalone verifier CLI
   also passed on the exported bundle.

No inspection Core, inspection endpoint or verification-policy edits were required. The fixture-approved checks
and normal gate semantics were used throughout. Image artifacts are header-only test fixtures, not renderable equipment photos. The fake planner's model ID and prompt/response digests are in the
task event log. Fake SAM uses the existing `sam-3.1` wire label; it is explicitly identified as a fake in the run record,
not genuine Meta evidence.

## Artifacts

- [HTTP run record](technician-journey-fakes/run.json)
- [Task event log](technician-journey-fakes/task-events.jsonl)
- [Receipt](technician-journey-fakes/receipt/receipt.json) and its bundled artifacts
- [Artifact SHA-256 manifest](technician-journey-fakes/files.json)
- [Full .NET test names and outcomes](technician-journey-test-results.json)

The receipt covers the inspection. The separate task event log and HTTP transcript connect that receipt to the
originating task; the receipt format itself has not been extended to bundle guided-task history.

## Reproduce without credentials or hardware

```bash
dotnet test tests/LensQi.FieldOps.Api.Tests --nologo --filter FullyQualifiedName~TechnicianJourneyTests
dotnet test --nologo
dotnet run --project tools/LensQi.FieldOps.VerifyReceipt -- docs/evidence/tasks/technician-journey-fakes/receipt
```

For a fresh export, set `FIELDOPS_JOURNEY_EVIDENCE` to a new, nonexistent directory before running the focused test.
Existing evidence directories are rejected instead of overwritten. Regular tests use unique temporary directories
and delete only their own data. No Docker or database server is used.

Remaining validation: a continuous user-operated technician flow with genuine models and real equipment. The API
journey does not establish ARCore anchor quality, microphone behavior or physical repair correctness.
