# LensQi FieldOps — Kiro Technical Design

**Version 1.1** · Fresh public source · Android + ASP.NET Core 10 · Model API Muse Spark 1.3 + SAM 3.1

## Architecture and code boundaries

```text
Phone CameraX / Recorded Fixture / optional Meta DAT
                    │
                Android Compose
      Capture / Reframe / Inspect / Verify
                    │ HTTPS, typed DTOs
           LensQi.FieldOps.Api
               │        │
     Inspection state   File evidence
               │        │
         LensQi.FieldOps.Core ───── Evidence verification (pure C#)
               │
      ┌────────┼────────────┐
      ▼        ▼            ▼
   Muse       SAM       IDiagnosticProbe
   adapter    adapter   LiveLocal | Simulated | Replay
      └────────┼────────────┘
               ▼
     Append-only receipt ledger
               ▼
       JSON/HTML + SHA-256
```

## Repo layout

- `apps/android/` — Kotlin/Jetpack Compose, CameraX, Coroutines, Retrofit, state machine view model. `com.lensqi.fieldops` package.
- `src/LensQi.FieldOps.Api/` — ASP.NET Core Minimal APIs, image validation, scoped artifact files, SQLite store.
- `src/LensQi.FieldOps.Core/` — typed fixture/state/evidence/check contracts and pure deterministic evaluator. **No provider dependency**.
- `src/LensQi.FieldOps.Meta/` — bounded Muse Spark structured response and SAM segmentation adapter.
- `src/LensQi.FieldOps.Diagnostics/` — fixture-allowlisted HTTP probe plus simulated/replay implementations.
- `tools/sam-parser/` — tiny internal Python sidecar using Meta's `meta-sam-parser` (PyPI, no dependencies). Emits typed JSON only; never externally exposed.
- `tests/` — pure state tests first, then API contract and fixture scenario tests.
- `fixtures/telecom-lab/` — original or synthetic test images, expected twin, validated schema and synthetic probe outputs.

## Trust separation

**Only deterministic Core code advances `InspectionStage`**. Muse returns hypotheses plus an enum indicating requested observation type. SAM returns pixels/boxes. Probe returns actual or simulated fixture measurements. The technician confirms physical work and reviews the visual checklist. Each producer owns its provenance fields; the model cannot invent them.

### Contract proposal

```csharp
public enum InspectionStage
{
    Created, InitialScan, NeedsEvidence, FaultSuspected,
    FaultConfirmed, AwaitingRepair, ReadyToVerify,
    VerificationFailed, VerifiedComplete, Inconclusive
}

public enum EvidenceOrigin { Phone, Glasses, RecordedFixture, MockWearables, Muse, Sam, LiveLocalProbe, SimulatedProbe, ReplayProbe, Technician }
public enum ObservationStatus { Observed, Suspected, Unknown, Conflicting }
// Single wire vocabulary for every model proposal, including the vendor-knowledge child spec's VendorGroundedProposal.
// SegmentRegion is offered only to the parent visual-assessment prompt; the knowledge prompt never drives SAM.
public enum ProposedNextAction { RequestView, SegmentRegion, RunApprovedProbe, PrepareWorkOrder, RequestHumanReview, StopInconclusive }

public sealed record Hypothesis(string FeatureId, ObservationStatus Status, string Reason, IReadOnlyList<string> EvidenceIds);
public sealed record VisualAssessment(string AssetId, IReadOnlyList<Hypothesis> Hypotheses, ProposedNextAction NextAction, string? RequestedView, string ModelId, string SchemaVersion);
public sealed record DiagnosticResult(string FixtureId, string DiagnosticId, string TargetAlias, bool? Passed, DateTimeOffset CompletedAt, string Source, string TraceId);
```

All `AssetId`, `FeatureId`, `DiagnosticId`, `NextAction` values are validated against the fixture and allowed enum. Model-generated floating confidence never selects `VerifiedComplete`.

### Visual media and ROI

1. Capture original full-resolution JPEG (phone frame). Record orientation and pixel dimensions.
2. Produce small downsampled analysis preview for initial Muse reasoning. **Do not discard original**.
3. Ask SAM for one concrete segmentable noun (`ethernet switch`, `yellow ethernet cable`). Its `sam-3.1` output is special-token geometry, not a PNG.
4. Decode with Meta's `meta-sam-parser` (Python sidecar; `@meta-sam/parser` is the TypeScript equivalent). Boxes arrive in media pixels with inclusive `x2`/`y2`; normalize to half-open original-image coordinates, decode masks to COCO RLE or a 0/1 raster, and reject malformed geometry or an `incomplete` outcome.
5. Crop a padded ROI from original resolution, record transform metadata, and pass both the whole scene and crop to Muse for targeted reasoning.
6. Draw overlays on Android Compose Canvas with letterbox/rotation corrections. Keep the underlying source image inspectable.

### Meta API

- Base: `https://api.meta.ai/v1`, bearer `MODEL_API_KEY`. **Two adapters, two wire formats:**
  - `muse-spark-1.3` on `POST chat/completions` with `response_format.json_schema` (`strict: true`, root object, every property required, `additionalProperties: false`, no `allOf`). Reasoning tokens count against `max_tokens`; send `reasoning_effort` (default `low`) and a budget of several thousand tokens, and record `completion_tokens_details.reasoning_tokens`. Implemented: `MuseSparkChatClient`.
  - `sam-3.1` on `POST responses` with `input_text` (one concrete noun) and `input_image` parts, streaming; output is special-token text in `output_text`, parsed by the sidecar. Day 2 unknowns to test first: whether `input_image` accepts a `data:` URL or needs a hosted URL, and whether `metadata.mask_encoding: "one_bit"` and `include_confidence` behave as documented.
  See <https://dev.meta.ai/docs/models>, <https://dev.meta.ai/docs/sam/overview>, <https://dev.meta.ai/docs/sam/client-libraries>.
- `MODEL_API_KEY` stored server-side only. Add spend/usage bounds and correlation IDs. No model credentials in the APK.
- Prefer typed interfaces `IVisualReasoner` and `ISegmenter` so invalid provider results are tested without paid calls.
- Treat invalid schema, model timeout, empty SAM matches, and token-stream parse errors as first-class inconclusive/error states.
- Muse Wonder and Muse Voice Transcribe are optional; Muse Code is a coding tool rather than a runtime inspection tool.

### State / evidence workflow

`Created -> InitialScan -> NeedsEvidence / FaultSuspected -> FaultConfirmed -> AwaitingRepair -> ReadyToVerify -> VerificationFailed / VerifiedComplete / Inconclusive`. `Created -> Inconclusive` is also allowed when the provider is unavailable or the first capture is unusable; no stage may be inferred from a model response alone.

At each transition append an immutable event with origin, server-created timestamp, fixture digest and related artifact IDs. An out-of-order operation should yield a typed conflict error; replayed client request IDs return the same result.

### Repair and verification

`ClaimRepair` emits a new repair timestamp and invalidates existing verification-complete candidates. `Verify` runs the fixture-approved probe itself unless a result newer than the repair claim and within `diagnosticMaxAgeSeconds` exists. `EvaluateProof` checks same fixture/version; human-reviewed non-conflicting visual checklist; fresh post-repair photo; allowed health check; diagnostic after repair; diagnostic age policy; and technician acknowledgement. Status is live or simulated depending on actual probe source. An unmanaged switch's HTTP health check does not certify a specific switch port.

### Read-only diagnostic safety

- `targetAlias` maps only to `services` configured by the server for the synthetic lab. Do not accept direct URL/IP from any user/model JSON.
- Reject redirects or verify every destination against allowlist; restrict address families and DNS resolution; cap response/timeout; do not execute shell scripts.
- P0 target: `lab-node-a` HTTP health endpoint on an owned, isolated network. `SimulatedProbe` and `RecordedReplayProbe` are selectable only with explicit labels.

### Persistence

Use SQLite and local evidence files for P0. Image artifacts are immutable by ID, with SHA-256. Session reads materialize state from append-only events. Receipts include a canonical manifest hash; verifier recalculates hashes. Add simple 7-day default retention/purge for local demo data.

### Wearables adapter (P1)

Define `IFrameSource`: `PhoneCameraFrameSource`, `RecordedFixtureFrameSource`, `MetaWearablesFrameSource`. Integrate Meta `mwdat-core`, `mwdat-camera`, `mwdat-mockdevice` via Android Wearables Device Access Toolkit **only after P0 passes**. Developer preview hardware/capability prerequisites are tested and documented. Do not claim physical in-lens spatial overlay without demonstrated support.

## Main risks and controls

- **Misread port labels:** request close-up; never infer exact port if unreadable.
- **Model returns plausible but wrong action:** validate schema and tool allowlist in C#.
- **Fake fix:** probe fails despite plausible photo; deny live completion.
- **Probe passes but wrong physical port:** restrict claim to endpoint reachability unless managed port telemetry supplied.
- **Private code exposure:** zero private repository dependency; written reuse permission for indie before copying any old source.
- **Receipt authenticity:** hashes detect edits to the provided bundle, not falsified recording.

## Developer test requirement

The pure verification core must pass full tests before any model/wearables work is treated as complete. Every provider integration must have a deterministic fake, but demo materials must identify mocked vs real evidence clearly.
