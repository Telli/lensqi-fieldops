# LensQi FieldOps — Vendor Knowledge & Diagnostics
## Kiro Technical Design

**Version:** 1.2 · Companion to `requirements.md` and `tasks.md`  
**Architecture:** C# / ASP.NET Core 10 / Microsoft.Extensions.AI, Kotlin/Compose, SQLite for hackathon; Meta Muse Spark + SAM 3.1 already specified in FieldProof v1.1.

## 1. Design decision record

| Decision | Choice | Reason |
|---|---|---|
| Knowledge retrieval in hackathon | Exact asset/model match + small **curated JSON fact cards** | Traceable, reproducible; not generic RAG |
| Vendor example | MikroTik **hEX S (RB760iGS)**, RouterOS 7 only for applicable facts | Official hardware specification and documented read-only REST API |
| Test fixture | Fictional `LAB-SW-01` synthetic switch pack + existing `LAB-RACK-01` twin | No vendor hardware required; no claims about real equipment |
| Vendor live adapter | P1, optional, disabled by default | Device access, certificate, user privileges and firmware must be validated |
| Existing tests/receipts | Extend `FieldProof Core` state/evidence events | No second workflow engine or bypassable verification |
| Document ingestion | **Link and short paraphrased facts** in P0 | License-safe, fast; no crawling or PDF parsing |
| Authorization | Fixture-bound diagnostic ID → operator-owned target alias | Avoid LLM-controlled SSRF or device writes |
| Persistence | Pack file checked at startup, immutable digest in session events | Simple, auditable versioning |
| Model accountability | Model must **pre-register a prediction** of the probe result; Core scores it after measurement | Turns narration into falsifiable hypotheses; hallucination becomes a measurable rate |
| SAM's role | Before/after segmentation of the cited region, Core computes change metrics | Gives SAM an evidentiary job; catches re-submitted or unchanged photos without trusting prose |

**Scope caveat:** The referenced private `Telli/LensIQ-Monorepo` is NOT a dependency. This extension is additive to the *planned* public `lensqi-fieldops` repository; create its own source files and event-period commits.

## 2. Logical architecture

```text
Selected Asset / QR / Known Test Fixture
            │
            ▼
       ExpectedTwin (site-owned)
       model / firmware / topology / required diagnostics
            │
      exact identity + revision match
            ▼
  VendorKnowledgeResolver ──────── Versioned JSON pack registry
       │                               │
       │ applicable fact cards         ├── Vendor docs URL + locator
       │ + source IDs                  └── SHA-256 / applicability
       ▼
  Muse Spark reasoning (untrusted candidate actions)
       │
       ▼
  Typed Hypothesis + requested evidence / diagnostic ID
  + pre-registered prediction of what the probe will report
       │
       ├────────► SAM 3.1 / technician camera ► visual evidence
       │             before/after masks ► Core change metrics (§6b)
       │
       └────────► Approved diagnostic dispatcher
                             ├── simulated / recorded replay (P0)
                             ├── local owned HTTP health (parent P0)
                             └── RouterOS GET adapter (P1, opt-in)
                                       │
                                       ▼
                                normalized measurements
                                + prediction scoring in Core (§6a)
                                         
                FieldProof Core verification policy
                (code, not model, owns state transitions)
                             │
                             ▼
                  Append-only evidence receipt
                cited facts + model/asset versions
                  photos + diagnostic provenance
```

### Dependency direction

- `Core` owns contracts, evidence rules, acceptance predicates and pack applicability; it references **no Meta or vendor HTTP SDK**.
- `Api` hosts endpoints, DI, pack loading, tenant/inspection authorization, logs, idempotency and storage.
- `Meta` consumes bounded applicable fact cards and returns typed proposed actions; it has **no direct network diagnostic transport**.
- `Diagnostics` owns tightly limited adapters and normalized measurements; it knows nothing about LLM prompt content.
- `Android` displays status, provenance and safe evidence instructions; cannot declare verification complete on its own.

## 3. Suggested file placement

```text
src/
  LensQi.FieldOps.Core/
    Knowledge/KnowledgeContracts.cs
    Knowledge/KnowledgeApplicability.cs
    Knowledge/KnowledgeResolver.cs
    Knowledge/KnowledgeConflictDetector.cs
    Verification/KnowledgeEvidencePolicy.cs
    Verification/PredictionScorer.cs
    Evidence/VisualChangeAnalyzer.cs               # perceptual hash, IoU, shift; no model
  LensQi.FieldOps.Api/
    Features/VendorKnowledge/VendorKnowledgeEndpoints.cs
    Infrastructure/Knowledge/FileKnowledgePackStore.cs
    Infrastructure/Knowledge/KnowledgePackValidator.cs
  LensQi.FieldOps.Meta/
    Knowledge/KnowledgeGroundingComposer.cs
    Knowledge/KnowledgeActionValidator.cs
    Segmentation/ChangeEvidenceComposer.cs         # two SAM 3.1 calls, before/after
  LensQi.FieldOps.Diagnostics/
    DiagnosticDispatcher.cs
    RouterOs/RouterOsReadOnlyProbe.cs             # P1 only
apps/android/.../fieldops/
    knowledge/KnowledgeSourcesSheet.kt
    knowledge/DocumentationStatus.kt
    inspection/InspectionViewModel.kt             # extend, do not replace
fixtures/vendor-knowledge/
    knowledge-pack.schema.json
    lab-switch-01.synthetic.json
    mikrotik-hex-s-rb760igs.routeros7.json
    fixture-binding.schema.json
    fixture-binding-example.json
```

Maintain the parent `ExpectedTwin`, `InspectionStage`, `EvidenceOrigin`, `IDiagnosticProbe` and receipt schema. Use additive fields and migrations only when needed; no separate `VendorInspectionSession` state machine.

## 4. Knowledge pack contract (v1)

The JSON Schema in `fixtures/vendor-knowledge/knowledge-pack.schema.json` is normative for file structure. A pack contains:

| Field | Purpose |
|---|---|
| `schemaVersion` | Exact supported schema version (`1.0`) |
| `packId`, `packRevision`, `originKind` | Stable identity and whether `VendorLinked` or `Synthetic` |
| `identity.manufacturerId`, `identity.productCode`, `identity.firmwareMajor` | Exact applicability key; product code is never inferred from model appearance alone |
| `documents[]` | Publisher/title/official URL/locator/`accessedOn` and rights mode `LinkOnly` or `OriginalSynthetic` |
| `facts[]` | `factKey` + `assertedValue` (machine-comparable), short paraphrase `statement`, `claimKind`, source document ID and locator, tags, firmware scope and `safetyClass` |
| `capabilities[]` | What *kind of read-only observation* a model supports; **not endpoints, credentials or URLs**. Informational grounding only: even when a fact's `assertedValue` names a diagnostic ID (as `LAB-HEALTH-CHECK-01` does), execution authority comes solely from the fixture's `approvedDiagnostics` and the server probe registry |

A fact card is structured as below (this is the exact record from the shipped MikroTik pack and validates against the schema; every listed field is required):

```json
{
  "factId": "MK-REST-GET-INTERFACE",
  "factKey": "routeros.rest.interface.read",
  "assertedValue": "GET:/rest/interface/{name}",
  "statement": "RouterOS v7 REST can read a named interface's current properties through an authenticated GET request.",
  "claimKind": "VendorDocumented",
  "sourceDocumentId": "MK-ROUTEROS-REST",
  "sourceLocator": "Read data",
  "tags": ["interface", "diagnostic", "link-state"],
  "safetyClass": "ReadOnlyObservation",
  "requiresFirmwareMajor": "7"
}
```

This is a **paraphrased factual card**, not a copied manual page. The `sourceDocumentId` maps to an explicit official URL. Source references in the output are composed from the resolved pack, not produced from arbitrary LLM strings.

### Pack integrity and versioning

- On load: validate JSON Schema, unique IDs, all source references, HTTPS official URLs (synthetic documents may use no external URL), known safety classes, duplicate capability IDs and sensible bounds.
- Compute `sha256(File.ReadAllBytes(path))` for the **exact** validated bytes. Store the digest on the inspection/receipt. A different whitespace encoding changes this hash, correctly reflecting changed artifact bytes.
- Immutable revisions: do not overwrite historical pack bytes used by receipts; keep old revisions addressable or copy an immutable pack artifact into the evidence bundle.
- `accessedOn` records when someone reviewed a URL; it is **not** proof of continuing availability or validity.
- If a source URL is unavailable: retain prior reference and show `SourceNotLiveVerified`, never invent a replacement.

### Applicability algorithm (pure function)

```text
Resolve(pack, asset):
  if asset manufacturer != pack manufacturer: ManufacturerMismatch
  if asset productCode != pack productCode: ModelMismatch
  if pack.hardwareRevisions is non-empty:          # empty list = pack does not restrict by revision
      if asset hardwareRev unknown: ApplicabilityUnverified
      if asset hardwareRev not in pack.hardwareRevisions: HardwareRevisionMismatch
  for each fact:
      if fact.requiresFirmwareMajor is null: fact Applicable
      else if asset.firmwareMajor unknown: fact Unverified  (reason: firmware_unknown)
      else if fact.requiresFirmwareMajor != asset.firmwareMajor: fact Inapplicable  (reason: firmware_mismatch)
      else fact Applicable
  if no fact is Applicable:
      ApplicabilityUnverified if any fact is Unverified (ask for firmware), else NoApplicableKnowledge
  if applicable facts conflict for same factKey: KnowledgeConflict
  return Applicable facts with pack metadata and source refs,
         plus Excluded facts with their verdict and reason (never silently dropped)
```

Use ordinal/case-normalized **exact** product codes, not fuzzy string similarity, for authoritative routing. A proposed AI identification may help the technician find a candidate, but a person or trusted inventory must confirm it.

### Conflict handling

P0 can use deterministic conflicts on duplicate `factKey`/same applicability scope with contradictory declared `assertedValue` values. Reject such packs at load when conflict is resolvable statically. For conflicts across packs/doc revisions, show `KnowledgeConflict` and require review. Natural-language contradiction detection with the model is P2 and cannot silently choose a winner.

## 5. C# contracts and service boundaries

These are **design sketches**; implementation should map to existing Core records to avoid duplicates.

```csharp
public enum KnowledgeResolutionStatus
{
    Applicable, NoPack, NoApplicableKnowledge, ManufacturerMismatch, ModelMismatch,
    HardwareRevisionMismatch, ApplicabilityUnverified,
    KnowledgeConflict, InvalidPack
}

// Mirrors the schema's claimKind exactly; pack authors choose one of these.
public enum KnowledgeClaimKind { VendorDocumented, SiteConfigured, EngineeringNote }

// Runtime provenance shown in UI and receipts. Inferred and Measured are never pack claim kinds.
public enum ProvenanceLabel { Documented, SiteConfigured, Inferred, Measured }

public enum FactApplicability { Applicable, Unverified, Inapplicable }
public enum KnowledgeSafetyClass { ObserveOnly, ReadOnlyObservation, HumanActionRequired }

public sealed record AssetIdentity(
    string AssetId, string ManufacturerId, string ProductCode,
    string? HardwareRevision, string? FirmwareMajor);

public sealed record KnowledgeCitation(
    string FactId, string PackId, string PackSha256,
    string DocumentId, string DocumentTitle, Uri? SourceUrl,
    string SourceLocator, DateOnly AccessedOn);

public sealed record ApplicableFact(
    string FactId, string FactKey, string AssertedValue, string Statement,
    KnowledgeClaimKind ClaimKind, IReadOnlyList<string> Tags,
    KnowledgeSafetyClass Safety, KnowledgeCitation Citation);

// A fact the resolver saw but did not return, with the machine-readable reason.
public sealed record ExcludedFact(string FactId, FactApplicability Verdict, string Reason);

public sealed record KnowledgeResolution(
    KnowledgeResolutionStatus Status,
    string? PackId, string? PackSha256, int? PackRevision,
    IReadOnlyList<ApplicableFact> Facts,
    IReadOnlyList<ExcludedFact> Excluded,
    IReadOnlyList<string> Warnings);

// Pure and synchronous: the pack store is in memory and the fixture binding selects the pack.
// The Api layer maps inspection → binding → asset and wraps this call.
public interface IVendorKnowledgeResolver
{
    KnowledgeResolution Resolve(
        FixtureKnowledgeBinding binding, AssetIdentity asset,
        IReadOnlyList<string> issueTags);
}
```

#### Muse structured output addition

Add optional fields to the existing `VisualAssessment` contract, validated in C#:

```csharp
// JSON field names: assetId, usedFactIds, hypothesis, requestedVisualEvidence, proposedDiagnosticId, nextAction, prediction.
// nextAction uses the PARENT enum ProposedNextAction (FieldOps v1.1 design.md) so there is one wire vocabulary.
// Knowledge proposals may carry every value except SegmentRegion; ProposedNextActions.KnowledgeAllowed lists them.
//   RequestView        — ask for a specific additional photo or angle (requestedVisualEvidence required)
//   RunApprovedProbe   — propose one fixture-approved diagnostic (proposedDiagnosticId + prediction required)
//   PrepareWorkOrder   — a HumanActionRequired fact applies; staged physical step the technician authorizes
//   RequestHumanReview — asset, firmware or documentation cannot be confirmed; a human or inventory decides
//   StopInconclusive   — documentation missing, inapplicable or conflicting; no model-specific advice

public sealed record VendorGroundedProposal(
    string AssetId,
    IReadOnlyList<string> UsedFactIds,
    string? Hypothesis,
    string? RequestedVisualEvidence,
    string? ProposedDiagnosticId,
    ProposedNextAction NextAction,
    Prediction? Prediction);   // required when NextAction == RunApprovedProbe (§6a)
```

**Per-action validation.** `KnowledgeActionValidator` applies these rules on top of the parent's stage rules; the stage machine (parent R7) decides whether the action is permitted in the current `InspectionStage`:

| `ProposedNextAction` | Parent effect | Knowledge-layer validation |
|---|---|---|
| `RequestView` | Request additional visual evidence | `requestedVisualEvidence` non-empty, qualitative only (VK-R05.1) |
| `RunApprovedProbe` | Dispatch a fixture-approved read-only probe | `proposedDiagnosticId` in fixture allowlist and probe registry; `prediction` present for the same ID with a value from the registry's closed set (§6a) |
| `PrepareWorkOrder` | Draft a technician-authorized physical step (parent FR-07) | At least one cited fact has `safetyClass = HumanActionRequired` |
| `RequestHumanReview` | Session stays `NeedsEvidence`; UI asks for asset or firmware confirmation (VK-R09.4) | Always allowed |
| `StopInconclusive` | Session stays `NeedsEvidence` or `Inconclusive`; no model-specific instruction | Always allowed |
| `SegmentRegion` | Parent visual-assessment prompt only | Always rejected for knowledge proposals; segmentation targets come from the fixture's `visualRequirements[].segmentationPrompt` |

Any failure is rejected with `action_not_allowed` and a `KnowledgeProposalRejected` event. The server never approximates a nearer action.

**Server validation:** `AssetId` must equal the inspection's bound asset. `UsedFactIds` must be a subset of `KnowledgeResolution.Facts`; IDs must belong to the same pack digest and model/revision. `ProposedDiagnosticId` must be in the *site fixture* allowlist and server registry, not in untrusted prose. If invalid, return `Inconclusive` or `NeedsEvidence` and append a `KnowledgeProposalRejected` event; never “repair” the action by guessing what the LLM meant. The `/knowledge/reason` endpoint takes an idempotency key and counts against the parent's bounded model-call budget per inspection (VK-R10.5); it never adds extra model turns.

**Prompt design:** The model receives (1) a typed observation and asset ID, (2) only applicable fact cards with IDs, (3) separate site expected-state rules, (4) allowed next actions and diagnostics, (5) explicit instruction to abstain on incompatible/missing documentation. Do not send whole manuals or raw credentials. Treat source text as potentially containing prompt injection.

## 6. Diagnostic adapters and authorization

Parent `IDiagnosticProbe` remains the single execution interface. P0 uses synthetic/replay and existing fixture allowlisted HTTP health. P1 adds a vendor probe without expanding its authority.

### Dispatch sequence

1. Client sends `inspectionId` + `diagnosticId` + idempotency key.
2. API looks up inspection and bound immutable expected twin.
3. Core confirms diagnostic belongs to that twin and is permitted in current inspection state.
4. `DiagnosticDispatcher` resolves server-side `targetAlias`, optional `interfaceName` and adapter through trusted configuration.
5. Adapter runs a fixed operation with bounded timeout and configured credentials; model/client never supplies a URL, IP or command.
6. Normalize result, tag source `LiveLocal`/`VendorReadOnly`/`Simulated`/`RecordedReplay`, hash raw bytes, record timing and `claimVerified`.
7. Verification engine checks freshness, provenance and specific required predicates.

### Verification outcome representation

The existing FieldProof parent design represents the inspection stage as `VerifiedComplete` and records the provenance of supporting probes. Treat terms such as **`VerifiedCompleteLive`** and **`VerifiedCompleteSimulated`** in acceptance criteria as **distinct result classifications** (e.g. `verificationMode` plus `InspectionStage`), not as a mandate to add duplicate inspection enum cases. Maintain one authoritative state machine and never infer `Live` from a simulated or replay source.

### P1 RouterOS-specific implementation

MikroTik RouterOS v7 supports **GET** `/rest/interface/{configuredInterface}`. Official docs demonstrate properties including `name`, `type`, `running` and `mtu`. The service uses a **read-only** RouterOS user (scoped with the documented read/REST policies), HTTPS with trusted certificate, and a static server-side interface allowlist. No generic REST client is exposed to the model.

```csharp
// Pseudocode; the interface value is resolved ONLY from trusted fixture binding.
GET https://{trustedRouterHost}/rest/interface/ether2
  ? .proplist=name,type,running,disabled
```

The actual HTTP client MUST use a hardcoded/validated path composition and escaped allowlisted name; the query field mask may be reduced to documented fields if the device responds differently. RouterOS returns JSON values as strings (e.g. `"running":"true"`); parsing is explicit. Request method is GET, redirect is disabled, host/TLS cert is pinned or trusted by deployment configuration, credentials are never logged, and target identity comes from trusted operator config.

**Verification meaning:** `running=true` establishes the router reported that interface as running at test time. It does **not** prove correct patch-panel port, cable termination at the other end, correct VLAN, or application connectivity. The existing HTTP health test remains separate when endpoint reachability is required.

The docs also support POST-based monitoring commands, but this feature deliberately does not implement POST to prevent expansion into general RouterOS execution. This is a design constraint, not a claim about what the vendor API can do.

### Fail-closed network policy

- Per-environment disabled-by-default vendor adapter switch; only test lab targets, not public or arbitrary IPs.
- Strict allowlist and egress network policy; no automatic scanning or host discovery.
- No redirects; HTTPS cert verification and fixed DNS/address identity. Reject URI userinfo and unsafe HTTP downgrade.
- Rate limit per inspection/target; timeouts, response-size limits, no unbounded retries.
- Real adapter credentials stored in local secret store/server environment, not sample JSON or Android.
- `Simulated` results remain marked as such through UI, diagnostic events and receipts.

## 6a. Pre-registered predictions

The scientific-method loop inside a maintenance ticket: the model must say what the probe will report before the probe runs, and Core keeps score.

```csharp
public enum PredictionOutcome { Confirmed, Falsified, Unscorable }

// Emitted by the model inside VendorGroundedProposal when NextAction == RunApprovedProbe.
public sealed record Prediction(
    string DiagnosticId,
    string PredictedValue,                  // must be in the registry's closed set for the observation kind
    IReadOnlyList<string> BasedOnFactIds,   // subset of UsedFactIds
    string? Rationale);                     // short untrusted prose; displayed, never parsed

public sealed record PredictionScore(
    string PredictionId, string DiagnosticId,
    string PredictedValue, string? MeasuredValue,
    PredictionOutcome Outcome,
    DateTimeOffset RegisteredAt, DateTimeOffset? MeasuredAt);
```

Closed value sets live in the probe registry, keyed by observation kind (the same enum as pack `capabilities[].observationKind`):

| Observation kind | Closed set |
|---|---|
| `EndpointReachability` | `Reachable`, `Unreachable` |
| `InterfaceLinkState` | `LinkReportedUp`, `LinkReportedDown` |
| `AssetInventory` | `MatchesExpected`, `DiffersFromExpected` |
| `Visual` | Not predictable through a probe; a proposal predicting it is rejected |

Sequence:

1. Proposal validation (§5) additionally checks `Prediction.DiagnosticId == ProposedDiagnosticId`, `PredictedValue` in the closed set, and `BasedOnFactIds ⊆ UsedFactIds`. Failure → `prediction_missing` or `prediction_value_invalid`, plus `KnowledgeProposalRejected`.
2. Core appends `PredictionRegistered` carrying a digest of the canonical prediction JSON. Only then may the dispatcher (§6) run. A probe result is never placed in a later model prompt for the same claim before registration.
3. On `DiagnosticCompleted`, `PredictionScorer` compares normalized values with ordinal equality. Probe `Unavailable` or error → `Unscorable`.
4. `PredictionScored` is appended and surfaced in the UI and the receipt `predictions` block. **Verification policy never reads it**: the measurement is the gate, the score is calibration.

Why Core scores and not the model: a model asked "were you right?" is grading itself. The comparison is a string equality on normalized values and belongs in code.

## 6b. Visual change evidence (SAM 3.1 before/after)

SAM already localizes the target for the technician. This gives it an evidentiary role: did the cited region actually change between the pre-repair reference and the post-repair photo?

```csharp
public enum VisualChangeVerdict { Changed, NoVisualChange, DuplicateImage, Unscorable }

public sealed record VisualChangeAnalysis(
    string ClaimId, string TargetPrompt,
    string BeforeImageDigest, string AfterImageDigest,
    string? BeforeMaskDigest, string? AfterMaskDigest,
    int PerceptualHashDistance,          // e.g. 64-bit dHash Hamming distance, computed in Core
    double? TargetRegionIoU,             // overlap of before/after target masks
    double? ComponentShiftNormalized,    // component centroid movement relative to target box, when both masks exist
    VisualChangeVerdict Verdict,
    string ThresholdsVersion);
```

Pipeline (server side; SAM calls in `ChangeEvidenceComposer`, metrics in Core's `VisualChangeAnalyzer`):

1. Technician submits a post-repair image for a claim whose cited fact names a target component (tag `port`, or the fixture names a target prompt).
2. Core computes the image digest and perceptual hash. If the hash is within `duplicateThreshold` of any earlier image in this inspection → `DuplicateImage`; stop, no SAM call.
3. Call SAM 3.1 twice with the same text or box prompt: once on the pre-repair reference image already in the inspection, once on the new image. Record mask digests. No mask on either → `Unscorable`; fall back to the parent's manual target selection.
4. Compute IoU of the target masks and, where a component mask exists (for example the cable), its centroid shift relative to the target box. Below `changeThreshold` on every metric → `NoVisualChange`; otherwise `Changed`.
5. Append `VisualChangeAnalyzed`. `NoVisualChange` and `DuplicateImage` leave the post-repair visual gate unsatisfied. `Changed` satisfies only the predicate "there is a visual change in the cited region" and feeds the parent's existing human-reviewed visual gate; the diagnostic gate is untouched.

Thresholds (`duplicateThreshold`, `changeThreshold`, minimum mask area) are server configuration carrying a `ThresholdsVersion` recorded in every analysis, so a receipt can be re-evaluated later under the same rules. Metrics in code decide the verdict; the model never sees them before it proposes.

Known limits, stated in the UI: lighting and camera angle can yield `Changed` on an unchanged scene. That is why `Changed` is a necessary condition, never a sufficient one. A technician may re-shoot a `NoVisualChange` result from a better angle; each attempt is recorded.

## 7. Site fixture binding (separate from vendor facts)

**Do not put customer topology in a vendor pack.** Add a site-owned binding to the existing fixture, for example:

```json
{
  "fixtureId": "LAB-RACK-01",
  "assetId": "LAB-SW-01",
  "vendorKnowledge": {
    "packId": "lab-switch-01.synthetic",
    "expectedManufacturerId": "LensQiLab-SYNTHETIC",
    "expectedProductCode": "LAB-8P-SYNTH",
    "firmwareMajor": null,
    "requiredKnowledgeFactIds": ["LAB-PORT-MAP-01", "LAB-HEALTH-CHECK-01"]
  },
  "approvedDiagnostics": ["NODE-A-HEALTH"]
}
```

The overlay is normative in `fixtures/vendor-knowledge/fixture-binding.schema.json`. At load the server cross-checks it against installed packs: the pack must exist, `expectedManufacturerId`/`expectedProductCode`/`firmwareMajor` must equal the pack `identity`, and every `requiredKnowledgeFactIds` entry must exist in that pack. A binding that fails any check is rejected before any inspection can use it (`scripts/validate_vendor_packs.py` demonstrates the same checks).

This addon **points only to the synthetic pack**, never the real MikroTik documentation. To use the MikroTik pack, a different fixture must bind an actual confirmed `RB760iGS` asset and firmware. For independent evaluation, the synthetic fixture and fake probe contain no proprietary information.

## 8. API surface (additive)

Follow existing `ApiError` and authentication conventions in FieldOps. Proposed endpoints:

| Method | Endpoint | Behavior |
|---|---|---|
| `GET` | `/api/vendors/knowledge-packs` | Enumerate server-installed pack IDs and metadata (no private files) |
| `GET` | `/api/inspections/{id}/knowledge` | Resolve applicable facts for this inspection; return typed status and citations |
| `POST` | `/api/inspections/{id}/knowledge/reason` | Ask Muse for a proposed evidence request using bounded applicable facts; enforce typed action validation |
| `POST` | `/api/inspections/{id}/diagnostics/{diagnosticId}/run` | Run **only** fixture-approved probe; existing route may be reused instead |
| `GET` | `/api/inspections/{id}/receipt` | Include applicable doc references, pack digest and diagnostic attestations in existing receipt |
| `GET` | `/api/inspections/{id}/predictions` | Registered predictions with their scores; read-only, Core-owned |
| `GET` | `/api/inspections/{id}/claims/{claimId}/change-analysis` | Latest `VisualChangeAnalysis` for a repair claim; the analysis itself runs automatically on evidence submission |

Example resolved-knowledge response:

```json
{
  "inspectionId": "I-1001",
  "status": "Applicable",
  "packId": "lab-switch-01.synthetic",
  "packSha256": "sha256-computed-by-server-not-a-hardcoded-example",
  "assetId": "LAB-SW-01",
  "facts": [
    {
      "factId": "LAB-PORT-MAP-01",
      "statement": "The approved lab fixture expects the yellow cable to connect to P2.",
      "sourceKind": "SiteConfigured",
      "citation": {
        "documentTitle": "Synthetic lab wiring plan",
        "sourceLocator": "Connections / LAB-CONN-A",
        "sourceUrl": null
      }
    }
  ]
}
```

### Errors

Return typed status or 4xx error with stable codes: `manufacturer_mismatch`, `model_mismatch`, `hardware_revision_mismatch`, `firmware_unknown`, `no_applicable_knowledge`, `knowledge_conflict`, `invalid_fact_reference`, `asset_mismatch`, `action_not_allowed`, `prediction_missing`, `prediction_value_invalid`, `prediction_late`, `duplicate_image`, `no_visual_change`, `pack_not_trusted`, `binding_invalid`, `probe_not_allowlisted`, `probe_unavailable`, `invalid_state`, `receipt_integrity_failure`. Do not collapse these into a generic successful response.

## 9. Android UX additions

**One compact knowledge drawer** on the inspection page:

- Equipment identity: `Confirmed model: LAB-8P-SYNTH` or `Model not confirmed`.
- Context note: `Synthetic lab plan` or `Vendor specification (link to official source)`.
- Sections: `What the documentation establishes`, `What is uncertain`, `What to inspect next`.
- Evidence card: source title + section label; optional openable HTTPS source link; visually distinguish `VendorDocumented`, `SiteConfigured`, `Inferred` and `Measured`.
- In repair verification: show documents as *guidance*, diagnostics as *measurements*. The verification banner is exclusively generated by Core.
- Offline: packaged synthetic facts can be shown with `Cached / Synthetic` label; no cloud reasoning or live probe claim while offline.

No full document viewer or rich PDF reader in P0.

## 10. Data and receipt extensions

Add append-only events without overwriting existing FieldProof history:

- `KnowledgePackResolved(inspectionId, assetId, packId, packDigest, status, timestamp)`
- `KnowledgeFactUsed(factId, documentId, sourceLocator, inspectionEventId, resolvedPackDigest)`
- `KnowledgeWarning(status, affectedFactIds, reason)`
- `KnowledgeProposalRejected(inspectionId, reason, offendingFactIds, offendingDiagnosticId, proposedAssetId, timestamp)` — one per rejected Muse proposal; makes the safety boundary auditable and countable in receipts
- `PredictionRegistered(inspectionId, predictionId, diagnosticId, predictedValue, basedOnFactIds, predictionDigest, timestamp)` — always before `DiagnosticRequested` for that dispatch
- `PredictionScored(predictionId, measuredValue, outcome, diagnosticEventId, timestamp)` — written by Core only
- `VisualChangeAnalyzed(claimId, beforeImageDigest, afterImageDigest, beforeMaskDigest, afterMaskDigest, metrics, verdict, thresholdsVersion, timestamp)`
- `DiagnosticRequested(diagnosticId, probeKind)`
- `DiagnosticCompleted(diagnosticId, sourceKind, measurement, rawDigest, observedAt, claimVerified)`

Receipt `knowledge` block: pack identity, revision, digest, applicability status, fact citations and source access dates. Receipt `diagnostics` block: probe/source, target alias, actual result and verification-bound claim. Receipt `predictions` block: each prediction with its measurement, outcome and cited fact IDs, plus the per-inspection tally, labeled model calibration. Receipt `visualChange` block: per-claim verdict, metrics, mask digests and thresholds version. Never include router passwords, personal data, arbitrary private URLs or giant raw manual content in public receipts. A receipt hash establishes **bundle integrity**, not hardware-event authenticity.

## 11. Tests and measurement

| Layer | Required tests |
|---|---|
| Schema | Valid official and synthetic packs load; duplicate IDs, invalid doc IDs, missing locators, unknown fields, `SiteConfigured` in `VendorLinked`, fact firmware scope ≠ pack identity fail; fixture binding cross-checks pass/fail as specified |
| Applicability | Manufacturer/model/firmware/revision mismatch and unknown firmware fail appropriately; case-normalization safe |
| Retrieval | Only applicable facts returned, cite IDs bound to pack, bounded result count, conflicts detected |
| Muse boundary | Fabricated citations, unapproved diagnostics, foreign `assetId`, prompt injection and invalid actions rejected; each rejection persisted as `KnowledgeProposalRejected` |
| Diagnostic dispatcher | Unknown alias denied, redirect denied, TLS/cert failure denied, timeout bounded, no POST, simulated labels persisted |
| RouterOS P1 | GET resource allowlist, string-to-bool parsing, read-only credentials, no mutation endpoints |
| State machine | Apparent visual fix + failing telemetry remains `VerificationFailed`; no stale pre-repair probe |
| Receipts | Pack digest + reference present; altered pack/diagnostic artifact detected; `predictions` and `visualChange` blocks present and hashed |
| Predictions | Missing, invalid-value and late predictions rejected; Core scores `Confirmed`/`Falsified`/`Unscorable`; outcomes never alter a state transition; probe result never enters a prompt before registration |
| Visual change | Re-submitted photo → `DuplicateImage` with zero SAM calls; unchanged region → `NoVisualChange`; no mask → `Unscorable` with manual fallback; `ThresholdsVersion` recorded; `Changed` alone never advances state |
| End to end | Synthetic fixture works without equipment or private repo; recorded real Meta calls only claimed when actually executed |

Suggested metrics: model/applicability mismatch rejection, cited-fact validity, fabricated-ID rejection, prediction accuracy (confirmed over scored), duplicate-image and no-change detection rates on the scripted fake fixes, fraction of needed follow-up views requested, diagnostic false-pass rate, verification correctness in fake-fix cases and source-lookup latency. State the sample size and what was mocked.

## 12. Sources and rights

- MikroTik hEX S product/specification: <https://mikrotik.com/product/hex_s> (product code RB760iGS, 5 Gigabit Ethernet ports and 1 SFP).
- MikroTik RouterOS REST API: <https://manual.mikrotik.com/docs/developer-guides/rest-api/> (v7 REST operation, read-only user setup, `GET /rest/interface/...`).
- Meta Model API: <https://dev.meta.ai/docs/overview>.
- SAM 3.1 segmentation: <https://dev.meta.ai/docs/media-segmentation>.

The `VendorLinked` pack contains short newly written paraphrases and links, not a vendor-supplied PDF or copied diagrams. The synthetic lab pack is original test content. No source website access is necessary at demo runtime.

## 13. Explicit boundaries

- No autonomous repairs, configuration writes, arbitrary shell commands or discovery scans.
- No uncontrolled agent RAG over the internet, no unsupported firmware-specific recommendations.
- No assumption that link-up, lit LED or HTTP 200 validates every fault condition.
- No fabricated manual citations, fake physical measurements, or promise of universal vendor coverage.
- No mixing manufacturer facts with site-authorized topology.
- No prediction outcome ever acts as a verification gate; the measurement decides.
- No `Changed` verdict is treated as repair correctness; it is a necessary condition only.
- No access to private LensIQ monorepo at runtime and no private source copied (PRD §4A); all code is event-period work.
