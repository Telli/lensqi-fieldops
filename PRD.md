# LensQi FieldOps
## Product Requirements Document — Meta Global AI Developer Hackathon

**Version:** 1.1 — Hackathon scope, component reuse and public-repo architecture  
**Date:** October 27, 2026  
**Track / theme:** Indie Developer / Push the Frontier  
**Build period:** October 27–November 4, 2026  
**Submission:** November 4, 2026, 9:00 PM Eastern / 6:00 PM Pacific  
**Status:** Build-ready specifications; model calls, hardware, and benchmarks remain unimplemented/unverified until tested.  
**Repository strategy:** Public `Telli/lensqi-fieldops`, created October 28, 2026 (UTC) from the specification kits; existing `Telli/LensIQ-Monorepo` remains private.


**Brand hierarchy:** **LensQi** (visual intelligence platform) → **FieldOps** (field worker product) → **FieldProof Engine** (evidence and verification capability). **LensIQ** refers only to the existing private heritage/tourism repository; it is not the hackathon product name.

**Pre-existing private baseline:** `Telli/LensIQ-Monorepo` `main` at `593f467372c7e88acb7a217242fb70b1b0c49525` (2026-10-16). This is a provenance reference, **not** permission to redistribute code. The public repository's first commit is `f46e967` (specification kit); no private source has been copied.

**Competition rules assumption:** The guide requires public code and demonstrations limited to work built during the event; it explicitly discusses existing-code reuse for startups but is less clear for indie entrants. **Default to fresh implementation unless the organizer confirms permitted indie reuse.** Pre-existing code, if permitted, must be separately credited and excluded from claims of hackathon novelty.

---

## 1. Executive summary

**LensQi FieldOps** is a visual AI agent for field technicians that turns observations of physical equipment into **traceable, verified maintenance outcomes**. In the first demonstration, a technician inspects a small Ethernet-switch installation using an Android camera or a compatible Meta AI glasses feed. The agent compares visible equipment with a declared expected state, identifies ambiguity, requests a specific additional view, obtains read-only network diagnostics, and tracks the repair. The system refuses to mark a repair **Verified Complete** unless fresh, relevant visual and diagnostic evidence satisfies deterministic verification rules.

Its core innovation is an **epistemic state machine**: the agent explicitly represents *known*, *hypothesized*, *unknown*, and *contradictory* facts and chooses the next evidence-gathering action accordingly. The model proposes hypotheses and evidence requests; application code enforces state transitions, permissions, evidence freshness, and completion criteria. A memorable **fake-fix scenario** shows a cable that looks connected while an independent connectivity test still fails.

**Tagline:** *See the problem. Prove the solution.*

**Nine-day deliverable:** One reproducible telecom/IT-network inspection loop, a fresh public Android + ASP.NET Core implementation, real Meta API calls, a seeded fixture, a failure-and-recovery demo, an integrity-checkable evidence receipt, and a three-minute video.

**Longer-term vision:** Extend the **FieldProof Engine** to telecoms, energy, water, facilities, municipal assets, and construction; eventually share a reusable visual intelligence layer with the existing private LensIQ heritage product under the **LensQi** brand.

## 2. Problem, users, and jobs to be done

### Problem

Technicians often document asset condition with disconnected photos, notes, inventory spreadsheets, and network checks. AI image analyzers can describe a scene but may invent details when labels are small, cables occlude ports, or equipment is viewed obliquely. Existing work-order systems can record that someone pressed “complete” without proving the reported fault was resolved.

### Primary user and job

**Primary:** Telecom/IT field technician responsible for low-voltage network installations or service restoration. *When I inspect or repair an asset, help me collect enough evidence to distinguish a plausible fault from a confirmed fault, and demonstrate that the outcome was actually achieved.*

**Secondary:** Site supervisor or QA reviewer who needs to review a compact record of what was observed, tested, changed, and verified.

**Later users (not hackathon scope):** Solar technicians, municipal inspectors, water utility teams, facilities managers, and mobile infrastructure auditors.

### Principles

1. **Do not claim more than the evidence supports.** Unknown is a valid outcome.
2. **Active perception beats passive classification.** Request a useful second view when needed.
3. **Proposed action is not verified outcome.** Visual appearance and independent functional telemetry must be reconciled.
4. **Structured, inspectable evidence.** Each claim has an origin, timestamp, and status.
5. **Human-controlled physical work.** The agent never autonomously changes a real device configuration or instructs unsafe interventions.
6. **Offline/resilient interface.** Field capture and review may continue without a connection; cloud AI and live probes require connectivity.
7. **New hackathon work must be obvious.** A clean standalone repository, dated commits, and an honest demo showing only event-built functionality.

## 3. Hackathon success definition

A successful project must demonstrate this *entire closed loop* with actual Meta models:

1. **Observe:** Capture a wide-angle image of an identified test network switch.
2. **Recognize uncertainty:** Muse Spark identifies one or more plausible discrepancies, but marks occluded or tiny visual details as unknown.
3. **Request evidence:** FieldProof points to an inspectable ROI and asks for a targeted close-up. SAM 3.1 supplies a box/mask around a concrete object such as a network switch or yellow Ethernet cable.
4. **Corroborate:** The system compares visual observations against a versioned expected-state JSON fixture and a read-only network check.
5. **Reject a false repair:** The technician presses “Verify” when the near-end cable appears seated but the remote endpoint is still unreachable. Completion is denied, with a specific reason.
6. **Verify the real repair:** A fresh follow-up image plus passing read-only diagnostic establishes that the approved test condition is restored; a technician acknowledges the result.
7. **Produce evidence:** Export an integrity-checkable JSON/HTML FieldProof receipt linking before/after photos, visual annotations, tests, and model/tool versions.

Judges must be able to run a **clearly labeled simulated diagnostic fixture** without the physical equipment. A real local low-voltage network demo is preferred for the video, but simulations must never be represented as live measurement.

### Proposed pass/fail acceptance gates

- At least one real request to **Muse Spark** and one real request to **SAM 3.1** are visible in logs/trace with sensitive data removed.
- Three scripted flows work end-to-end: **genuine fault**, **ambiguous view**, and **fake fix rejected before successful re-verification**.
- An image alone never closes an inspection; a mocked or failed diagnostic never becomes an unlabeled “live-verified” result.
- Unknown asset IDs, stale photos, stale pre-repair diagnostics, and unapproved network targets cannot cause completion.
- A user can open the public code, reproduce the simulated demonstration, and validate the receipt hashes.

## 4. Scope boundaries

### P0 — Mandatory

- Fresh Android app (Kotlin, Jetpack Compose, CameraX) with camera capture, basic inspection status, ROI overlay, second-view request, repair claim, and results screen.
- ASP.NET Core 10 API using explicit typed contracts, state transitions, and SQLite storage for sessions and evidence metadata; local media in a controlled file store.
- Meta Model API integration: Muse Spark for multimodal reasoning/structured decisions; SAM 3.1 for image segmentation and masks.
- Deterministic expected-state asset catalog for **one** Ethernet switch fixture and **one** network endpoint.
- One independent read-only diagnostic: HTTP `/health` or ICMP against a fixed, explicitly allowlisted target. Managed-switch SNMP/LLDP is optional.
- Active second-view guidance and a cropped high-resolution ROI, without claiming accurate port-level recognition unless legible and independently supported.
- Fail-closed dual-evidence completion gate, timeline of evidence, technician confirmation, and a JSON + printable HTML receipt with SHA-256 evidence-manifest verification.
- Simulated diagnostic adapter, pre-recorded camera observations, unit tests, reproducible evaluation runner, README and demo video.

### P1 — Valuable only after P0 works

- Meta Wearables Device Access Toolkit capture source (real glasses when available, otherwise labeled Mock Device Kit).
- Hands-free push-to-talk, spoken evidence request, spoken inspection summary, audio feedback (Android speech/TTS or supported Meta toolkit features).
- Manual ROI selection, perspective correction when reliable, side-by-side image comparisons, additional fault class.
- A small web reviewer panel if time permits; not necessary for P0 because Android UI + HTML receipt suffice.

### P2 — Stretch / post-hackathon

- Muse Voice Transcribe and multilingual technician conversations; higher-quality voice loop.
- Managed-switch read-only SNMP/LLDP interface-state evidence for port-specific proof.
- Signed receipts with a managed signing key, optional PDF export, audit-store anchoring.
- Muse Image “proposed after” illustrations, unmistakably labeled **conceptual**, never verification evidence.
- Production CMMS/ERP integration, field-service work orders, post-visit fleet asset memory.

### Explicitly out of scope

- Automated electrical repair, software configuration writes, privileged shell access, public-network scanning, safety certification, compliance enforcement, automatic work-order dispatch to real third parties, arbitrary device/brand support, pixel-to-world geospatial coordinate claims, autonomous site-wide video surveillance, photorealistic 3D digital twins, and guarantee of sub-second model responses.


## 4A. Component reuse decision and competition gate (v1.1)

### Governing rule: reuse is technically useful but not automatically competition-eligible

- Keep `Telli/LensIQ-Monorepo` **private**, including all of its earlier commits, migrations, configuration, historical imagery, and commercial plans.
- Create a **new unrelated Git repository** with new commit history during the permitted event build window. Never create a public fork/mirror of the private repository.
- **No copying of pre-event private source into the hackathon repository until** (1) the indie-track rules are clarified, (2) rights to redistribute the selected code are established, (3) code and transitive assets pass secret/licensing review, and (4) provenance is recorded.
- Where clarification is unavailable, reimplement tiny infrastructure utilities from their **behavioral specification**, not by copying the source. Use standard Android/.NET libraries. Do not describe pre-existing code as newly authored hackathon work.
- Existing LensIQ code may remain **context and prior-project evidence**, separate from the hackathon demo.

### Inspected private modules and concrete extraction decisions

| Source path relative to the private LensIQ monorepo | Existing behavior | Decision for FieldOps | Competition / engineering notes |
|---|---|---|---|
| `LensIQ-Android/app/src/main/java/com/lensiq/android/service/SceneFrameSource.kt` | 18-line Hilt camera frame bridge; calls `LiveCameraService` JPEG helper | **Rebuild as interface + new provider** (`IFrameSource`, `PhoneCameraFrameSource`) | Simple behavior to reproduce; direct copying yields little benefit and pulls tourism dependencies |
| `.../service/live/LiveCameraService.kt` | 640 px, 50% quality compressed preview JPEG | **Adapt design only**; implement separate full-resolution evidence capture | Never send the downscaled preview as the only artifact when inspecting port labels; retain original pixel dimensions |
| `.../service/TtsService.kt` | Android offline-preferred TTS with playback status | **Conditional targeted copy/adaptation (P1)** | Useful for spoken instructions if the indie reuse rule and redistribution rights allow; otherwise reimplement with Android `TextToSpeech` |
| `.../service/PermissionHandler.kt` | Camera/mic/location permissions and tourism strings | **Rebuild (P0)** | FieldOps must not require GPS or mic for basic inspection; replace tourism-specific permission explanations |
| `.../data/remote/backend/BackendJson.kt` | Lenient `kotlinx.serialization` configuration | **Rebuild tiny equivalent (P0)** | Few lines; fresh contracts are easier to audit |
| `.../data/remote/backend/LensIqApiService.kt` | Large Retrofit interface tied to tourism endpoints, billing, auth | **Do not copy** | Define typed `FieldOpsApi` with only inspection/evidence/verify endpoints |
| `.../service/live/AudioCaptureService.kt` + `AdaptiveVad.kt` | PCM capture and adaptive VAD | **Conditional targeted reuse (P1)** | Nontrivial lifecycle/permission logic; add only after P0 works and permitted rights established |
| `.../service/ArCapabilityChecker.kt`, `ar/` | ARCore capability checks, anchors, depth and scene management | **Defer** | No world-locked overlay is required for the verified-inspection loop |
| `.../service/HeadingProvider.kt`, `LocationProvider.kt` | Geospatial sensors and live observations | **Defer** | Site/asset IDs, not approximate GPS location, identify the fixture in P0 |
| `.../service/SceneCandidateService.kt` | GPS/heading-ranked heritage landmarks | **Reject** | Semantics do not apply to equipment identification |
| `.../service/GroundingService.kt`, `engine/ConfidenceScoringEngine.kt` | Weighted confidence scores for historical explanations | **Reject** | Maintenance verification must use explicit evidence gates, not combined model confidence |
| `.../service/ExplainService.kt`, `TimeTravelService.kt` | Tourism story and follow-up logic | **Reject** | Product-specific business logic and prompts |
| `LensIq-backend/src/LensIq.Api/Shared/PromptValidation.cs`, `ApiError.cs` | Simple ASP.NET validation/error contract | **Reimplement or conditional utility reuse** | Low time savings; do not import unrelated entitlement/error variants |
| `LensIq-backend/src/LensIq.Api/Features/Ai/` | Gemini-backed tourism explain/time-travel endpoints | **Reject** | Fresh Muse/SAM adapters and inspection contracts are the judged contribution |
| Existing EF schema, curated place data and migrations | Tourism product persistence/content | **Reject** | SQLite-only inspection records in the public hackathon edition |

**Priority:** technical patterns worth reusing are `frame source abstraction`, `camera lifecycle`, `Android TTS`, `typed Retrofit transport`, `bounded retries`, and `coroutine cancellation`. Actual source reuse, if approved, should be limited to a handful of low-coupling utility classes; it does **not** justify copying the existing app shell or monorepo history.

### Required provenance for any approved copied file

Create `docs/PRIOR_ART.md` with original repository/path, upstream commit SHA, original creation date if known, copied-line origin, license/IP approval, adaptation description, and a declaration that inherited code is **excluded from judged new functionality**. Preserve original copyright/notice obligations, but **do not embed private source links as a runtime dependency**. If ownership or organizer permission is unresolved, do not copy.

## 5. Primary user journey (the three-minute hero demo)

**Fixture:** A clearly labeled Ethernet switch, a yellow patch cable, a test endpoint (laptop/Raspberry Pi), and a preconfigured health-check target.

**A. Scan (0:00–0:35)** — Technician selects fixture `LAB-SW-01` via an asset label/QR, captures a wide view; the app displays detected regions and expected connection diagram. QR identifies the fixture; do **not** claim the model independently recognized a serial number from a blurred image.

**B. Resolve uncertainty (0:35–1:15)** — A bundle obscures the target connector. Muse emits hypothesis/uncertainty and asks: “Move closer and photograph the yellow cable and the switch faceplate.” The app highlights a visual target. SAM's segmentation supplies the mask; the app computes the ROI and shows the crop.

**C. Confirm fault (1:15–1:45)** — New visual evidence supports “cable appears seated at this end,” but a read-only health probe reports the test endpoint unreachable. The agent records a discrepancy and requests technician investigation rather than asserting the precise hidden cause.

**D. Fake fix rejection (1:45–2:15)** — The technician indicates “repaired” without restoring the remote end. The app reruns the probe; visual appearance alone is insufficient. It displays **Verification Failed — endpoint still unreachable**.

**E. Successful repair (2:15–2:40)** — After authorized technician action, capture a fresh image and repeat the probe. Expected diagnostics pass, the visual checklist is resolved, and the technician acknowledges. Status becomes **Verified Complete (fixture checks)**.

**F. Explain engineering proof (2:40–3:00)** — Show state-transition evidence, SAM mask, actual Meta calls, and the downloadable FieldProof receipt. Clearly identify simulated versus live telemetry and work created during the event.

**Optional wrong-port variant:** On a **managed** switch with reliable per-port telemetry or manually verified port labels, place the patch lead in the wrong port and show an explicit expected-port mismatch. With an unmanaged switch, endpoint reachability **cannot** prove which port was used; do not claim off-by-one port detection from SAM alone.

## 6. Product requirements and acceptance criteria

### FR-01 — Asset fixture and expected twin (P0)

- User selects a known fixture; asset ID can be read from a QR code, chosen manually, or entered.
- Expected-state catalog defines labeled devices, components, ports, intended connections, and the approved diagnostic.
- The backend rejects unknown/unauthorized asset IDs; model outputs cannot create new authoritative IDs.
- Every inspection binds to a fixture version and its SHA-256 digest so later changes cannot silently rewrite the expectation.

**Acceptance:** A malformed fixture fails validation; selected asset and expected-state digest appear in the receipt.

### FR-02 — Visual capture and quality assessment (P0)

- Android captures JPEG images with timestamp, image size, device/capture source (`phone`, `glasses`, `mock`), and user consent.
- Original resolution is preserved locally when permitted. Backend generates downsampled inference variants and cropped ROIs.
- Quality checks flag blurred, very dark, or overexposed images. Such checks are quality signals, not evidence of authenticity.
- Camera source is explicit; mock/recorded images carry an unmistakable demo label.

**Acceptance:** Missing image or unacceptably poor quality yields a targeted recapture request, not an invented diagnosis.

### FR-03 — Meta segmentation + ROI reticle (P0)

- Muse or a fixed fixture rule nominates a concrete segmentable object (e.g., “network switch”, “yellow Ethernet cable”).
- SAM 3.1 segments that object in the photo and returns boxes/masks. On no match, show **No region detected**, never a synthetic mask disguised as real.
- Convert masks/boxes to original-image coordinates with documented transformations; Android draws a visible mask/box.
- Compute a padded crop from the most relevant region; if a valid quadrilateral is detected, optionally rectify perspective. Never claim calibrated distance from a 2D mask.
- Request a more useful angle using qualitative guidance (“move slightly left”, “show both cable ends”), not uncalibrated measurements (“six inches right”).

**Acceptance:** Segmentation overlays align correctly on portrait/landscape test images; cropping retains fine label pixels and preserves an original-to-crop transform.

### FR-04 — Muse hypothesis and uncertainty contract (P0)

- Muse Spark receives whole-frame context, candidate asset/expected state, selected ROI/crop, and a bounded evidence timeline.
- JSON-schema-constrained response returns candidate observations, specific uncertainty reasons, observed feature IDs, and the **next allowed action**.
- Model confidence, if present, is a *self-reported ranking aid*, not a calibrated probability. The system must not use an arbitrary `>= 0.85` number to make safety- or verification-critical decisions.
- Three distinct semantic results: `observed`, `suspected`, and `unknown`; contradictory observations are recorded as `conflicting`.
- The agent can ask for at most two additional captures per loop; beyond the limit, mark **Needs Human Review / Inconclusive**.

**Acceptance:** Occlusion and unreadable port labels produce `unknown` or `suspected` plus a relevant evidence request; mismatched JSON/unknown asset IDs fail validation.

### FR-05 — Evidence acquisition loop (P0)

- Agent requests an additional view with a purpose, target asset/region ID, suggested framing, and allowable response types.
- Technician explicitly triggers recapture, decline, or manual clarification. No background scanning is required.
- New evidence is immutable; previous evidence is not overwritten. Deduplicate accidental retries with client request IDs.

**Acceptance:** State cannot advance to confirmed fault while mandatory evidence is missing. Declining a request does not fabricate a successful outcome.

### FR-06 — Read-only diagnostic corroboration (P0)

- API exposes a fixed tool with one or more allowlisted diagnostic targets under the configured lab fixture.
- Production-style probe interface supports `LiveLocal`, `Simulated`, and `RecordedReplay`, with provenance in every result.
- Minimal P0 live check is HTTP health (or ICMP when supported) on a **user-controlled** target. Success can verify endpoint reachability, but does not prove correct port termination or cable health in general.
- Probe results include status, target ID, test definition, execution timestamp, duration, bounded output, and evidence ID.
- No model-generated URLs, IPs, commands or SNMP community strings can be executed; no unrestricted network scanner.

**Acceptance:** An unapproved target is rejected. Diagnostic failures result in non-completion, not presumed success. Simulator outputs are labeled and never passed off as real observations.

### FR-07 — Work-order and repair claim (P0)

- FieldProof creates a **local draft** describing suspected/confirmed issue, relevant evidence, and authorized technician checklist.
- Technician can claim work performed and indicate readiness to verify, but this is not equivalent to successful repair.
- Every repair claim produces an immutable timestamp/event and invalidates earlier “post-repair” verification evidence.

**Acceptance:** Tapping “Repaired” without fresh checks cannot complete the inspection.

### FR-08 — Dual-proof verification and anti-fake-fix behavior (P0)

- A deterministic evaluator verifies: correct asset/expected-state version, technician acknowledgement, required images present and after the repair claim, visual checklist status not conflicting, qualifying live diagnostic fresh and passing, same fixture/diagnostic target, and no newer contradictory evidence.
- If the visual record remains ambiguous, outcome is **Needs Human Review**, not Verified Complete.
- If probe fails, outcome is **Verification Failed** with a clear reason even when model describes a cable as visually seated.
- If user runs the simulator, UI explicitly states **Simulated Verified**; only truly measured evidence may be labeled **Live Verified**.
- A qualifying result means **the specified fixture checks passed**, never a universal safety or repair warranty.
- `POST /verify` runs the fixture-approved diagnostic itself (or accepts one no older than `diagnosticMaxAgeSeconds`), so a freshness denial reflects real staleness, not demo timing.

**Acceptance:** Automated tests exhaustively reject stale diagnostics, pre-repair images, cross-asset evidence, simulated-as-live status, visual contradiction, and unknown required checks.

### FR-09 — FieldProof evidence receipt (P0)

- Generate JSON plus printable HTML containing: fixture ID/version, capture/device provenance, image/mask/crop artifact references, model names, prompt/schema versions, hypotheses, request/response steps, diagnostic results, user actions, verification rule version, final outcome and reasons.
- Canonicalize manifest data before hashing; calculate SHA-256 for each binary artifact and an overall manifest digest. Provide a `verify-receipt` CLI that recomputes and compares digests.
- Store inspection events append-only at application level (never update prior events in place); link each event digest to the previous digest if useful.
- **Important:** Hashing detects accidental or subsequent alteration of a provided bundle but does **not** prove who captured the images or prevent a malicious author from replacing the entire bundle. Digital signatures using a protected trusted key are future work.

**Acceptance:** Deliberately modify one evidence image or event; the verifier detects the mismatch. Receipt distinguishes *live* from *simulated* evidence.

### FR-10 — Wearable/hands-free mode (P1)

- Provide a hands-free UI mode on Android: large capture/verify controls, limited overlays, spoken next steps, and push-to-talk or supported speech input.
- Abstract capture behind `IFrameSource` with `PhoneCameraFrameSource`, `RecordedFixtureFrameSource`, and optional `MetaWearablesFrameSource`.
- Use the official Wearables Device Access Toolkit and Mock Device Kit for the glasses pathway; mark simulated glasses sessions clearly. Display elements are optional and device-dependent.
- No promise of precise spatial anchors on glasses. No autonomous continuous recording.

**Acceptance:** An Android device can complete P0 without any glasses; if P1 ships, the same inspection state machine accepts one labeled mock/glasses frame source.

## 7. Epistemic state machine and evidence policy

### Session states

| State | Entry condition | Allowed progression |
|---|---|---|
| `Created` | Fixture selected, no image | `InitialScan`; `Inconclusive` when the model provider is unavailable or the first capture is unusable and the technician declines to retry |
| `InitialScan` | First observation received | `NeedsEvidence`, `FaultSuspected`, `FaultConfirmed`, `Inconclusive` |
| `NeedsEvidence` | Missing/ambiguous visual information | New capture → `InitialScan`/`FaultSuspected`; decline → `Inconclusive` |
| `FaultSuspected` | Model or visual data indicates possible discrepancy | More evidence or read-only diagnostic; may become `FaultConfirmed` or `Inconclusive` |
| `FaultConfirmed` | Defined discrepancy supported by required evidence, possibly visual + telemetry | `AwaitingRepair` |
| `AwaitingRepair` | Draft work order and technician claim pending | Repair claim → `ReadyToVerify` |
| `ReadyToVerify` | Technician claims work performed | Fresh capture + diagnostic → `VerificationFailed`, `NeedsEvidence`, or `VerifiedComplete` |
| `VerificationFailed` | Required check fails or contradicts claim | New repair claim / reinspection; never silent automatic completion |
| `VerifiedComplete` | Deterministic dual-proof gate passes with technician acknowledgement | Terminal for this inspection; later evidence opens a new inspection/revision |
| `Inconclusive` | Insufficient evidence, budget exhausted or probe unavailable | Review/abort; never represented as a fault or repair success |

**Not all faults need initial visual+telemetry agreement:** physical and telemetry evidence can conflict, in which case record discrepancy and request a targeted diagnostic or review. Do not force a false “FaultConfirmed” solely to move the demo forward.

### Evidence structure

Every evidence item has `id`, `inspectionId`, `assetId`, `kind` (`photo`, `mask`, `crop`, `model_observation`, `technician_confirmation`, `diagnostic`, `repair_claim`), `source` (`phone`, `glasses`, `mock`, `meta_model_api`, `live_lab_probe`, `simulator`), `observedAt`, `receivedAt`, `artifactSha256` when binary, `fixtureVersion`, and optionally `relatedEvidenceIds`. Model interpretation is **an annotation** of a photo, not a substitute for the photo itself. The app preserves `unknown`, `conflicting`, and `requiresReview` alongside proposed observations.

### Completion policy (illustrative pseudocode)

```csharp
VerificationDecision Evaluate(InspectionSession s, IReadOnlyList<Evidence> evidence)
{
    if (!s.TechnicianAcknowledged || s.RepairClaimedAt is null)
        return Deny("Repair has not been acknowledged");
    if (!ExpectedTwinIsLockedAndMatches(s))
        return Deny("Expected-state version mismatch");
    if (!HasFreshPostRepairImage(s, evidence))
        return Deny("A new post-repair image is required");
    if (!HasTechnicianReviewedRequiredVisualChecklist(s, evidence))
        return Review("Visual evidence not reviewed, incomplete or contradictory");
    var probe = LatestAuthorizedDiagnosticForFixture(s, evidence);
    if (probe is null || probe.ObservedAt <= s.RepairClaimedAt)
        return Deny("Fresh post-repair diagnostic is required");
    if (!probe.Passed)
        return Deny("Connectivity check still fails");
    // RecordedReplay is labelled Simulated for outcome purposes: neither is a live measurement.
    if (probe.Source != DiagnosticSource.LiveLocal)
        return VerifiedComplete(VerificationOutcomeKind.VerifiedCompleteSimulated, "Qualifying simulated or replayed fixture checks passed");
    return VerifiedComplete(VerificationOutcomeKind.VerifiedCompleteLive, "Specified live fixture checks passed");
}
```

This is an **example policy**, not a drop-in security library. Implement server-owned timestamps, authentication/authorization, fixture-bound probes, idempotency keys, transaction isolation/concurrency checks, time windows, and explicit evidence review state. Do **not** use uncalibrated model confidence thresholds as the decisive proof rule.

## 8. Example expected digital twin (small but explicit)

**Canonical v1.1 fixture:** `fixtures/telecom-lab/expected-twin.json` (synthetic). Its structure is validated by the accompanying JSON Schema.

```json
{
  "schemaVersion": "1.0",
  "fixtureId": "LAB-RACK-01",
  "fixtureRevision": 1,
  "fixtureDescription": "Synthetic demonstration network: one unmanaged lab switch and one owned HTTP health endpoint. The P2 port mapping is an EXPECTED value; the HTTP probe cannot verify which physical port is used.",
  "assets": [
    {
      "assetId": "LAB-SW-01",
      "kind": "ethernet_switch",
      "displayName": "Lab Switch 01",
      "visualLabels": [
        "SW01"
      ]
    },
    {
      "assetId": "LAB-NODE-A",
      "kind": "test_endpoint",
      "displayName": "Lab Node A",
      "visualLabels": [
        "NODE-A"
      ]
    }
  ],
  "connections": [
    {
      "connectionId": "LAB-CONN-A",
      "fromAssetId": "LAB-SW-01",
      "fromPortLabel": "P2",
      "toAssetId": "LAB-NODE-A",
      "cableLabel": "YELLOW-A",
      "expectedState": "connected"
    }
  ],
  "diagnostics": [
    {
      "diagnosticId": "NODE-A-HEALTH",
      "type": "http_health",
      "targetAlias": "lab-node-a",
      "expectedHttpStatus": 200,
      "timeoutMs": 3000,
      "claimVerified": "The approved lab endpoint returned HTTP 200 at test time (not physical port identity)."
    }
  ],
  "visualRequirements": [
    {
      "requirementId": "switch_chassis_visible",
      "mandatory": true,
      "segmentationPrompt": "network switch"
    },
    {
      "requirementId": "yellow_cable_near_end_visible",
      "mandatory": true,
      "segmentationPrompt": "yellow ethernet cable"
    },
    {
      "requirementId": "post_repair_view_captured",
      "mandatory": true
    }
  ],
  "verificationPolicy": {
    "requirePostRepairPhoto": true,
    "requireFreshDiagnostic": true,
    "requireTechnicianAcknowledgement": true,
    "requireHumanReviewedVisualChecklist": true,
    "diagnosticMaxAgeSeconds": 60,
    "allowedDiagnosticSources": [
      "LiveLocal",
      "Simulated",
      "RecordedReplay"
    ],
    "verifiedLiveRequiresSource": "LiveLocal"
  }
}
```

`segmentationPrompt` is the one concrete noun SAM is asked for when that requirement is checked; it also names the target region for the child spec's before/after change analysis (VK-R12). Beyond the JSON Schema, the loader enforces cross-item invariants: every connection endpoint names a declared asset, IDs are unique and pattern-constrained, and a vendor-knowledge binding's `approvedDiagnostics` must be a subset of this fixture's `diagnostics[].diagnosticId`.

**Important:** `targetAlias` resolves only through trusted server-side configuration, e.g. `lab-node-a` → an approved local test address. The model cannot supply a URL or host. `fromPortLabel: P2` is an **expected configuration fact**, not proof that a photo can accurately read P2. In P0 the verified outcome is endpoint health with visual checklist; precise port identity needs a managed switch or additional trusted evidence.

## 9. Technical architecture

```text
Android Compose app ────────────────────────────────────────┐
  CameraX / selected photo / optional DAT Mock Device Kit   │
  ROI overlay + push-to-talk + workflow controls             │
                                    HTTPS                    ▼
                           ASP.NET Core 10 API
                  Inspections | Evidence | Verification
                            SQLite / local media
                                   │
                 ┌─────────────────┼──────────────────┐
                 ▼                 ▼                  ▼
          Muse Spark 1.3        SAM 3.1         Read-only probe
          multimodal reasoning  masks/boxes     allowlisted fixture
          structured hypotheses    │                  │
                 │                crop                 │
                 └─────────────────┼──────────────────┘
                                   ▼
                       Evidence policy evaluator
                                   │
                        JSON / HTML hash receipt
```


### Public monorepo layout (v1.1)

```text
lensqi-fieldops/                     # New public repository; not a fork
├── apps/android/                    # New Kotlin/Compose app
│   └── .../fieldops/{capture,inspection,evidence,voice,wearables}/
├── src/
│   ├── LensQi.FieldOps.Api/          # ASP.NET Core 10 Minimal APIs
│   ├── LensQi.FieldOps.Core/         # Pure verification/state model
│   ├── LensQi.FieldOps.Meta/         # Muse and SAM adapters
│   └── LensQi.FieldOps.Diagnostics/  # Allowlisted probes + simulator
├── tools/sam-parser/                # Python sidecar around meta-sam-parser (PyPI); typed JSON out
├── tools/LensQi.FieldOps.StageOneGate/ # Genuine Meta-call evidence runner (docs/evidence/)
├── tests/                           # Core, API, Meta contract, integration
├── fixtures/telecom-lab/            # Synthetic/releasable expected twin + examples
├── fixtures/vendor-knowledge/       # Knowledge packs, schemas, fixture binding (child spec)
├── .kiro/specs/lensqi-fieldops/     # Parent requirements, design, tasks
├── .kiro/specs/vendor-knowledge-and-diagnostics/  # Child spec: packs, predictions, change evidence
├── docs/
│   ├── PRIOR_ART.md                 # Reuse permissions and provenance
│   ├── HACKATHON_CONTRIBUTIONS.md    # Newly built features, dates and commit refs
│   ├── SECURITY_AND_DATA_RIGHTS.md  # Key/asset/repo checks
│   └── DEMO_AND_EVALUATION.md        # Live vs replay and benchmark instructions
├── README.md
├── .env.example
└── LICENSE                         # Choose only after redistribution review
```

**Dependency boundary:** The public project must run from a clean checkout without cloning, importing, reaching into or authenticating against the private LensIQ repository. Use **`LensQi.FieldOps`** namespaces and `com.lensqi.fieldops` Android package. Project naming is a working decision, subject to later trademark checks.

**Module responsibilities:** Core owns transitions and proof gates with no AI dependencies; Meta owns provider DTOs, segmentation parsing and bounded requests; Diagnostics owns live/read-only vs simulated probe implementations; API coordinates state and artifact IO; Android captures evidence and displays guidance. No model output can assign authoritative IDs, issue arbitrary commands, or call the verification gate directly.

**SAM parsing decision:** Meta SAM 3.1 returns special-token mask/box text, not PNG masks. Meta publishes `@meta-sam/parser` (npm) and `meta-sam-parser` (PyPI, no dependencies, Python 3.10+). For this .NET-first stack the Python package is the preferred parser, run as a tiny internal sidecar that emits typed JSON (boxes in original-image pixels with half-open right/bottom, masks as COCO RLE, optional 0–1 confidence when `include_confidence` is requested). Model grammar parsing never enters the C# verification layer. A native C# decoder is a stretch goal validated against the sidecar as oracle; the grammar reference page must be read first, because the client-libraries page does not define the mask payload. SAM is called on `POST /v1/responses` with `input_text` and `input_image` parts, not on the Chat Completions endpoint Muse uses. In either implementation, validate empty matches, original-image coordinates, raster dimensions and malformed outputs. Do not make image masks a source of authoritative network topology.

### Backend

- .NET 10 minimal APIs, `Microsoft.Extensions.AI` for routine chat abstraction where supported; explicit Meta HTTP adapters for SAM and any model-specific features.
- Typed vertical slices: `Inspections`, `Vision`, `Diagnostics`, `Verification`, `Receipts`.
- EF Core + SQLite for quick reproducibility (swap to PostgreSQL later); image files under a scoped local evidence directory. Do not copy commercial LensIQ data or migrations.
- `HttpClientFactory`, cancellations, timeout policy, retry only safe requests, rate limits, max image dimensions/bytes, bounded model/tool-call budgets and telemetry.
- Store model ID, prompt/schema version, source-image digest and request correlation ID for reproducibility. Never log API keys or complete private image data to public telemetry.
- Use **a well-tested parser** for SAM's special-token mask encoding: Meta offers TypeScript `@meta-sam/parser` and Python `meta_sam_parser`; alternatively implement a narrowly scoped, fixture-tested C# decoder with strict bounds and explicit negative cases. Do not assume SAM returns ready-to-use JPEG or PNG masks.

### Android

- Kotlin, Jetpack Compose, CameraX, ViewModel/StateFlow, Retrofit or Ktor HTTP client, Android text-to-speech for P1.
- Screens: (1) Select Fixture, (2) Live/Selected Capture, (3) Investigation & Reticle, (4) Verify & Evidence Receipt.
- Overlay maps normalized mask/box dimensions onto displayed image after accounting for sensor rotation, crop, mirroring and letterboxing.
- Prefer single-frame user-triggered capture to an always-on stream. The reviewer can use prerecorded fixtures without camera permission.

### Model responsibilities

- **Muse Spark 1.3 (P0):** Observe a scene and selected high-resolution ROI, return candidate hypotheses and uncertainty, propose safe evidence-gathering tool calls and concise technician instructions.
- **SAM 3.1 (P0):** Segment one concrete concept per request (e.g., `ethernet switch`, `yellow ethernet cable`), return mask/box, supply target ROI for overlay and crop. It does not identify whether a cable is electrically connected or certify port numbering.
- **Muse Wonder (optional):** Escalate unusually difficult visual comparisons after a measured latency/cost check; not necessary to pass P0.
- **Muse Voice Transcribe (optional):** Transcribe explicit technician voice notes; Android local speech input may be sufficient for P1.
- **Muse Image (stretch):** Conceptual repair illustration only, labeled as generated and excluded from verification evidence.
- **Muse Code:** Development tool only, not an authorized runtime field agent.

### Candidate API endpoints

| Endpoint | Purpose |
|---|---|
| `GET /v1/fixtures` | Return approved lab fixtures / expected-state versions |
| `POST /v1/inspections` | Create inspection for known fixture |
| `POST /v1/inspections/{id}/observations` | Capture/upload JPEG with idempotency key and source metadata |
| `POST /v1/inspections/{id}/investigate` | Muse + SAM plan / evaluate / request next evidence |
| `GET /v1/inspections/{id}` | State, evidence timeline, outstanding requirements |
| `POST /v1/inspections/{id}/diagnostics/{diagnosticId}:run` | Execute allowlisted read-only probe |
| `POST /v1/inspections/{id}/repair-claims` | Technician asserts work performed (not proof) |
| `POST /v1/inspections/{id}/verify` | Run **server-owned** deterministic gate |
| `GET /v1/inspections/{id}/receipt` | Evidence manifest + JSON receipt |
| `GET /v1/inspections/{id}/receipt.html` | Printable human-readable receipt |

### Core model response contract (illustrative)

```json
{
  "assetId": "LAB-SW-01",
  "observations": [
    {
      "featureId": "yellow_cable_near_end",
      "status": "Unknown",
      "description": "Connector partially obscured",
      "evidenceIds": ["photo-initial-001"]
    }
  ],
  "requestedSegmentationConcept": "yellow ethernet cable",
  "nextAction": "RequestView",
  "evidenceRequest": {
    "targetComponentId": "yellow_cable_near_end",
    "reason": "Near-end connector is occluded",
    "technicianInstruction": "Move closer and show the switch faceplate and the yellow cable connector"
  }
}
```

Enum values are the PascalCase names from `design.md` (`ObservationStatus`, `ProposedNextAction`); snake_case variants are rejected. The same `ProposedNextAction` enum is the wire vocabulary for the vendor-knowledge child spec's proposals. Enforce exact enums, `additionalProperties: false`, explicit required keys, known asset/feature IDs, max text sizes and safe tool allowlists. Empty / malformed / ungrounded responses become `Inconclusive`, never verified evidence. Tool selection can be model-driven; tool execution and verification decisions remain server-owned.

## 10. Data, security, and privacy

- **Privacy:** Explicit camera capture, visible upload notice, local deletion, no covert recording. Avoid faces, badges, personal screens, client network credentials and proprietary configurations in public fixtures.
- **Permissions:** Android camera and audio permissions only when needed; optional glasses integration follows Meta registration and device access permissions.
- **Network safety:** Diagnostics cannot accept URLs/hosts from the model. Server resolves aliases from an operator-controlled allowlist; validate DNS and destination on every run, prevent SSRF/redirects, disable proxy surprises, and require user-authorized dedicated lab targets. No generic shell/execution tool.
- **Secrets:** All model keys and network credentials in environment or secret manager. No keys in APK, fixtures, demo footage or public logs; include sanitized `.env.example`.
- **Data rights:** Publish only original or appropriately licensed photos, asset diagrams and fixture descriptions. Do not publicize real-client topology or facility images without permission.
- **Retention:** Demo default: local inspection artifacts retained for seven days unless user removes them sooner. Provide deterministic purge command; production retention/deletion is a separate design decision.
- **Observability:** Structured trace IDs, duration/cost counters, provider errors without leaking frames, and an explicit `LiveLocal`/`Simulated`/`RecordedReplay` badge on results.
- **Evidence integrity:** SHA-256 manifest for consistency; don't market it as cryptographic proof of genuine repair, nonrepudiation or third-party attestation.
- **Safety:** Technician remains responsible for tools, energized equipment, and physical work. Avoid inspection claims that imply electrical certification, regulatory compliance or safety clearance.

## 11. Evaluation and benchmarks

Build a reproducible test set of **20–30 scenarios** with labeled expected outcome and source type, plus a small genuinely held-out subset. Recommended distribution:

| Scenario | Target examples |
|---|---:|
| Correctly identified fixture, clear scene | 5 |
| Occluded or blurry connector / need more evidence | 5 |
| Cable appears connected, diagnostic fails (“fake fix”) | 5 |
| Repair performed and fresh diagnostic passes | 5 |
| Wrong asset / unknown fixture / malicious or stale evidence | 5 |

If time permits, add off-axis/poor-lighting examples and wrong-port cases **only when independently instrumented**. Keep variants of the same capture in the same split to avoid inflated results.

**Compare:**

- Baseline A: single full-frame Muse response, no targeted follow-up image.
- Proposed B: Muse + SAM ROI + one requested second view + deterministic diagnostic gate.

**Measure:** asset identity accuracy, false/confident mismatches, requested-view usefulness, inconclusive rate, verified false-completion count, number of follow-up captures, real model/tool latency, estimated cost per session, and receipt-verifier result.

**Hackathon success targets (goals, not measured claims):**

- 3/3 primary demo scenarios reproducibly complete with documented inputs.
- **0 erroneous `VerifiedComplete` results** across deliberately invalid unit/fixture scenarios (a bounded test outcome, not statistical proof of production safety).
- Every completion has new post-repair photo, qualifying diagnostic, user acknowledgement, and valid evidence manifest.
- Real Muse and SAM invocations logged in a sanitized, reproducible run.
- Median time from upload to first guidance measured and reported; aim for ≤20 seconds on the configured setup, but do not promise a guaranteed latency.
- Tests include Meta timeout, empty SAM response, low-quality photo, model-invalid ID, probe outage, retry/replay, and concurrent verification attempt.

## 12. Delivery plan — October 27 to November 4

| Date | Build outcome | Go/no-go gate |
|---|---|---|
| **Oct 27** | Create clean public repo, seed fixture, typed contracts, first live Muse call | Muse returns schema-valid assessment on a real image |
| **Oct 28** | SAM segmentation, parser, Android mask preview, ROI crop | Correctly displayed mask + original crop in Android or repeatable test UI |
| **Oct 29** | State machine, evidence ledger, targeted second-view request | Ambiguous case triggers recapture and cannot advance prematurely |
| **Oct 30** | Allowlisted read-only diagnostic adapter, live/simulated fixture | Deterministic endpoint failure/success with clear provenance |
| **Oct 31** | Repair claim + fail-closed verification gate | “Fake fix” is reliably rejected |
| **Nov 1** | Successful repair verification + receipt/hashing | Complete end-to-end demo and tamper check |
| **Nov 2** | Android UX polish; optional glasses Mock Device Kit / voice | P0 stable before any P1 expansion |
| **Nov 3** | Evaluation, test matrix, 3-minute video, README | New-work demonstration and public repo fully reproducible |
| **Nov 4** | Freeze, backup, check URLs/rights, submit | Submission before 6 PM Pacific |

**Kill switches:** If SAM mask overlay is still unreliable on October 29, make SAM ROI verification a limited but genuine secondary panel, preserve the epistemic/diagnostic loop, and do not add wearables. If live diagnostic hardware is unavailable by October 30, execute the full simulation but label it clearly; seek a separate real read-only test for the video. If the full loop is not working on November 1, eliminate P1/P2 entirely.

### Daily technical priorities

1. P0 vertical slice first: photo → Muse → uncertainty → next evidence → diagnostic → gate → receipt.
2. Automated tests and fixture scripts alongside each stage.
3. No accounts, billing, CMMS integrations, mapping dashboard, or production deployment before the core demo is reliable.
4. Three-minute demo storyboard controls feature prioritization.

## 13. Public repository and hackathon attribution

Proposed repository: **`lensqi-fieldops`**, initialized from scratch during the official build window (confirm exact allowed start in official rules). Include source for the P0 demo, original or licensed fixture media, test fixture generator, README, architectural overview, sample `.env.example`, reproducible local setup, and a clear `docs/HACKATHON_CONTRIBUTIONS.md` that identifies new work and pre-existing inspiration. Use a consciously selected open-source license and verify dependency / asset redistribution rights.

Do **not** publish the private LensIQ monorepo, old Git history, production `.env` files, private roadmap, archived images, or unreviewed secrets. The existing LensIQ Android app may be referenced as the original product context, but the judged demo must spotlight only newly developed FieldProof functionality. If code reuse is desired, obtain organizer clarification for the indie track and document exact provenance.


### v1.1 provenance, licensing and public-repo publication checklist

The private reference baseline is `593f467372c7e88acb7a217242fb70b1b0c49525` (2026-10-16). This commit existed before the event and **must not be counted as a newly built feature**. Any approved reused source must be explicitly identified in `PRIOR_ART.md`, including a reviewer-readable code contribution ledger. Where possible, implement fresh adapters and reference prior designs only.

Before publishing the public repository, verify: (a) no Git objects/history from `Telli/LensIQ-Monorepo`; (b) no `.env` secrets, database passwords, signing keys, cloud credentials or internal addresses; (c) no licensed/private customer photographs or network configurations; (d) SBOM/dependency attribution and permissible redistribution; (e) chosen open-source license is consistent with any authorized reused source; (f) runnable setup needs no private backend; (g) the new-work diff is obvious in demo and README; (h) organizer's indie reuse interpretation is recorded, if received.

**Security flag from existing repository documentation:** its remediation notes say external credential rotation was skipped, and older developer guidance contains local database credentials. This is a reason to avoid copying historic source trees or publicizing old history—not evidence of a currently compromised service. Rotate any reused real credentials and use a dedicated hackathon key/fixture.

## 14. Known risks and mitigations

| Risk | Mitigation |
|---|---|
| Small port text is illegible | ROI crop + request another image; never guess a port ID |
| SAM mask absent or points to wrong object | No-match state; alternate prompt/manual region; no fake mask |
| Model confidently invents defect | Typed observations, known IDs, separate probe + explicit uncertainty |
| Probe passes through unintended path | Restrict to controlled fixture; state only what specific test confirms; add switch port telemetry later |
| Network probe leaks capability | Server-side alias allowlist, no arbitrary target input, no shell |
| Real glasses unavailable | Android-first, Mock Device Kit labeled; no headset claim without testing |
| Hashes falsely imply tamper-proof authenticity | Explain integrity limits; true signing/attestation deferred |
| Public-source requirement exposes IP | Fresh source-only repo with clean history and licensed fixtures |
| Meta API credit exhaustion or downtime | Fixed usage budget, local cached **replay for tests only**, graceful inconclusive on live failures |
| Demo over-scopes into a general inspection platform | One fixture, one fault loop, explicit P0 cutline |

## 15. Definition of done

- [ ] Public repository contains a fresh, independent implementation and shows the build-period contributions clearly.
- [ ] Real Muse Spark multimodal reasoning and SAM 3.1 image segmentation demonstrated.
- [ ] Android or evaluation UI shows original frame, ROI overlay and reason for a requested second view.
- [ ] One controlled lab fault can be corroborated with live read-only telemetry or a clearly marked simulator.
- [ ] A false-repair claim is **rejected** by the deterministic verifier.
- [ ] A subsequent actual repair passes the defined checklist using **fresh** evidence and acknowledgement.
- [ ] Receipt exports and hash verification work; altering evidence is detected.
- [ ] Tests/evaluation results are reported as measured, not assumed.
- [ ] Three-minute video only highlights hackathon-built features; source, rights and demo links are public and accessible.
- [ ] Build, run, and verification steps work from a clean checkout with no private LensIQ dependencies.

## 16. Authoritative implementation references

- Meta Model API overview and model IDs (`muse-spark-1.3`, `sam-3.1`): https://dev.meta.ai/docs/models
- Meta Model API overview: https://dev.meta.ai/docs/overview
- Muse Spark tool calling (reference only; this design uses structured output, not tool calls): https://dev.meta.ai/docs/cookbook/tool-function-calling
- Muse structured outputs: https://dev.meta.ai/docs/cookbook/structured-output
- SAM 3.1 segmentation: https://dev.meta.ai/docs/sam/overview
- SAM prompts/limitations: https://dev.meta.ai/docs/sam/segmenting
- SAM parser libraries: https://dev.meta.ai/docs/sam/client-libraries
- Wearables Android SDK (developer preview; Mock Device Kit; example SDK 1.0.1): https://github.com/facebook/meta-wearables-dat-android
- Hackathon participant guide: https://cerebralvalley.ai/e/meta-ai-developer-hackathon/details

**Scope statement:** This PRD is an engineering plan, not a claim that the proposed features have been implemented, field-tested, or certified. Verification demonstrates specified test outcomes for a controlled fixture, not general physical safety or repair correctness.

## 17. FieldProof Engine contract and trust invariants (v1.1)

The name **FieldProof Engine** denotes a bounded verification system, not a universal repair certificate.

### Typed boundary rules

- **Model observation:** Describes possible visual features, missing information and next evidence request. It cannot mark a port connected, a fault confirmed or work completed without relevant authoritative evidence.
- **Photo/mask/crop:** Immutable media artifact with original pixels or recorded transform. A mask is an interpretation of a region; a crop is not additional independent evidence.
- **Expected state:** Versioned operator-authored fixture. The model may refer only to declared asset IDs, port aliases and components; unknown IDs are rejected.
- **Diagnostic result:** Produced by an allowlisted tool with `LiveLocal`, `Simulated` or `RecordedReplay` source, fixture binding, timestamps and explicit `Passed`/`Failed`/`Unavailable`. Simulated output cannot be labeled live verification.
- **Repair event:** Human-declared action. It resets post-repair evidence freshness and cannot become a completion assertion on its own.
- **Proof decision:** Deterministic backend calculation with a versioned policy, pass/fail reasons, and receipt. Every completed inspection must have a human-reviewed visual checklist and qualifying post-repair diagnostic.

### Required negative tests

1. Visually plausible connection with a failed probe → **VerificationFailed**.
2. Passed pre-repair probe reused after a repair claim → **reject stale evidence**.
3. Correct health-check for the wrong fixture → **reject mismatched fixture**.
4. Mock/replay probe masquerading as live → **no `LiveVerified`**.
5. Missing or ambiguous SAM mask → **request view/manual review**, not fabricated mask.
6. Invalid model output/asset IDs → **schema error, no state advancement**.
7. Probe URL supplied by an LLM or request body → **deny**; resolve only allowlisted server-side aliases.
8. Duplicate `POST /verify` → **idempotent decision**, not duplicate receipt issuance or evidence mutation.
9. A readable cable image but no functional telemetry → **inconclusive**, not verified.
10. A passing endpoint check but unverified port mapping on an unmanaged switch → **only endpoint connectivity verified**, never the claimed port mapping.

### Distinguish the three kinds of success

- **`VerifiedCompleteLive`**: A `VerificationOutcomeKind` label on a `VerifiedComplete` session when a fresh live fixture-bound diagnostic and reviewed visual checklist satisfy the defined policy.
- **`VerifiedCompleteSimulated`**: A `VerificationOutcomeKind` label on a `VerifiedComplete` session when qualifying simulated tests pass; not evidence of a physical repair.
- **`InspectionInconclusive`**: An `Inconclusive` inspection stage when evidence is missing, contradictory, stale or unavailable.

Keep the workflow stage (`InspectionStage`) distinct from verification provenance (`VerificationOutcomeKind`). The demo receipt should show the exact scope and status. Avoid marketing the bundle as an immutable or independently authenticated real-world truth record: hash manifests detect changes to the issued bundle, not fabrication or wholesale substitution.

## 18. First implementation handoff and acceptance order (v1.1)

1. **Create public source with no private tree copied.** Add README, `.gitignore`, API/Android skeleton, `HACKATHON_CONTRIBUTIONS.md`, optional `PRIOR_ART.md`, fake/non-secret fixture and license decision. Record event-period start in UTC and commit SHA.
2. **Implement pure Core first.** Typed `InspectionSession`, `Evidence`, `ExpectedTwin`, `VerificationPolicy`, transitions, and denial reasons; test the ten negative cases without invoking any model.
3. **Implement one fixture and diagnostic.** A synthetic `expected-twin.json`, a separate fixture schema validator, `SimulatedDiagnosticProbe`, and guarded `LiveLocalDiagnosticProbe` with one server-owned target alias.
4. **Wire Muse to one endpoint.** A schema-validated `VisualAssessment` with `unknown` support, one allowable next-action enum and asset ID allowlist. Confirm real API request before expanding AI logic.
5. **Integrate SAM segmentation.** Use one concrete object noun per request; normalize mask/box geometry, retain original image dimensions, and add ROI crop/overlay tests.
6. **Wire Android capture and the closed-loop UX.** New P0 evidence-quality capture (not old 640 px preview) with four screens and source badges.
7. **Record the fake-fix and valid-fix scenarios.** Real physical test preferred; demo/replay path clearly marked. Use genuine model calls and never silently substitute simulator traces as physical readings.
8. **Export JSON/HTML evidence and verify integrity.** Verify altered artifact detection; record model versions, prompt version, policy version and tool source.
9. **Evaluate + publish evidence.** Small labeled set, failure cases, latencies and actual costs. Present only event-built features in the three-minute video; clearly distinguish prior LensIQ from new FieldOps.

**Halt condition:** No copying private source until indie organizer reuse permission and redistribution rights are documented. This does **not** block fresh implementations or work on the public FieldProof Engine.

## 19. Open decisions for the hackathon (v1.1)

| Decision | Default | Change trigger |
|---|---|---|
| Indie-track private-code reuse | Fresh implementation, no code copied | Written clarification plus rights approval |
| Repo visibility | New repo public | Competition rules change |
| Copyright/license | Choose after ownership/dependency audit | Rights approval; avoid premature blanket MIT over copied source |
| Database | SQLite and local artifact store | Persistence requirements exceed single-node demo |
| Test target | Owned isolated Ethernet lab fixture + allowlisted HTTP health | A live target is demonstrably available and controlled |
| Wearable | P1 mock input; Android P0 | Core closed loop verified by Nov 1 and compatible SDK works |
| Port-level claims | Not verified for unmanaged switch | Instrumented managed port telemetry or human-reviewed correct port labels |
| Spatial coordinates | No per-component world coordinates in P0 | Calibrated depth/pose with measured uncertainty |
| Historical LensIQ dependency | None | Never a prerequisite for this public repo |
| SAM decoder | Python `meta-sam-parser` sidecar emitting typed JSON | Native C# parser passes fidelity tests against the sidecar |
| P0 UI surface | **Decided Oct 28: browser inspection UI served by the API is the P0 surface** (`src/LensQi.FieldOps.Api/wwwroot`, labelled "browser P0 surface"); Android moves to P1 | Android P1 only after the full loop, evaluation and video are done |

**Submission boundary:** This PRD and the accompanying Kiro documents are planning artifacts. They do not constitute a working implementation or evidence that a live Meta API call, headset connection, hardware diagnostic or evaluation has passed.
