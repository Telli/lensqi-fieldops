---
name: fieldops-conventions
description: Architecture invariants, code layout, commit and ledger discipline for the LensQi FieldOps repository. Load before changing any C# in src/, adding an endpoint, event or test, or committing.
---

# FieldOps conventions

## Invariants (never violate; tests enforce most of them)
1. `InspectionSession` (Core) is the only thing that changes `InspectionStage`. Endpoints translate HTTP into session commands; models and probes produce typed inputs.
2. Model output is untrusted until a validator accepts it: `KnowledgeActionValidator` for grounded proposals, the session's `RecordAssessment` for visual assessments. Reject, never repair.
3. Diagnostics run only from a fixture-approved `diagnosticId` resolved by `DiagnosticDispatcher` through server configuration. No URL, host, command or credential from any request or model.
4. Provenance labels: photos = evidence; Muse/SAM = inferred; probes = measured with `DiagnosticSource`; packs = documented/site-configured. Simulated or replay never becomes `VerifiedCompleteLive`.
5. Events are append-only, sequential, server-timestamped, and carry the fixture digest. `InspectionState.Replay` must reproduce state; add a `[JsonDerivedType]` for every new event.
6. Everything judged is event-period work. No private LensIQ source is copied (PRD §4A).

## Layout
- `src/LensQi.FieldOps.Core` pure: contracts, `Inspection/` (events, state, session, policy, twin, RoiCrop), `Knowledge/` (packs, applicability, resolver, validator), `Receipts/`, `Diagnostics/` (registry contract). No provider or ASP.NET references.
- `src/LensQi.FieldOps.Meta` Muse (`Muse/`), grounding (`Knowledge/`), visual assessment (`Vision/`), SAM + sidecar (`Segmentation/`).
- `src/LensQi.FieldOps.Diagnostics` probes and dispatcher.
- `src/LensQi.FieldOps.Api` minimal API: `Features/<Area>/<Area>Endpoints.cs` with a static `Map...` method, `Infrastructure/` stores and loaders, `wwwroot/` browser UI, `Program.cs` wiring.
- `tools/` consoles: StageOneGate (Muse evidence), VerifyReceipt, Eval; `tools/sam-parser/` Python sidecar.
- `fixtures/` twin, packs, schemas, eval scenarios; `scripts/` Python validators, ledger and secret helpers; `docs/evidence/` recorded genuine calls.

## Adding things
- New endpoint: add to the area's `Map...`, wrap in `Guard`/`GuardAsync`, use `ApiResults.Conflict(code, msg)` with a stable snake_case code, read `Idempotency-Key` through `RequestId(http, purpose)`.
- New event: record in `InspectionEvents.cs` + `[JsonDerivedType]`, apply in `InspectionState.Apply`, emit only from a session command, add a replay assertion to a test.
- New config: options class with `SectionName`, bound in `Program.cs`, defaults in `appsettings.json`; secrets only via env or `.env`.
- JSON: `KnowledgeJson.Options` (camelCase, string enums, unknown members rejected) for domain documents; `JsonStringEnumConverter` is registered for HTTP.
- Tests: Core = pure xUnit; Api = `FieldOpsApiFactory` with `FakeVisionChat`, `FakeSegmenter`, `FakeCropper`, Simulated diagnostics; Meta = stub `HttpMessageHandler`. Target: every new behaviour has a positive and a negative test.

## Commit and ledger discipline
1. `dotnet test --nologo` green and `scripts/secret_scan.sh` PASS before every commit.
2. Commit message: imperative title with the task IDs in parentheses, body listing what and why, then the line `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` (keep the attribution line the session reminder specifies).
3. After each feature commit run `python scripts/ledger_add.py "<what>" "<test evidence>"`; it appends the row and commits the ledger.
4. Check off the task in `.kiro/specs/*/tasks.md` with a one-line "*(done date: what shipped)*" note.
5. `git push origin main` after the ledger commit. Never force-push.
