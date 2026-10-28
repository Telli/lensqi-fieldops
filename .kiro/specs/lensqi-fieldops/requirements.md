# LensQi FieldOps — Kiro Requirements

**Version:** 1.1  
**Status:** To implement (no runtime implementation included)  
**Theme:** Push the Frontier  
**References:** `../../../PRD.md` in the repository root, `design.md`, `tasks.md`.

## User stories

**US-01 (Technician):** As a network field technician, I want to document an observed installation discrepancy and receive an explicit request for missing evidence so I can investigate rather than guess.

**US-02 (Technician):** As a technician, I want a read-only diagnostic to verify whether the service endpoint works, even when the cable visually appears seated.

**US-03 (Supervisor):** As a reviewer, I want a bounded, auditable evidence receipt so I can determine which fixture checks were actually passed.

**US-04 (Developer/Judge):** As a reviewer of the public project, I want to reproduce the inspection using an openly licensed synthetic fixture without real customer data or private dependencies.

## R1 — Independent public build (P0)

1. WHEN the project is cloned cleanly, THE SYSTEM SHALL compile and run the demo without access to `Telli/LensIQ-Monorepo` or proprietary APIs.
2. WHEN the project is published, THE REPOSITORY SHALL include reproducible setup instructions, a sanitized environment template, clear third-party attribution, and a dated new-work ledger.
3. IF indie prior-code reuse permission is absent, THE IMPLEMENTATION SHALL not copy private pre-hackathon LensIQ source.
4. THE DEMO SHALL identify and showcase only event-built FieldOps functionality.

## R2 — Fixture selection and expected state (P0)

1. WHEN a technician starts an inspection, THE SYSTEM SHALL bind it to a declared fixture and revision.
2. IF an asset ID is not present in the signed-off fixture schema, THE SYSTEM SHALL reject the association.
3. THE SYSTEM SHALL record the fixture content digest and required checks with the inspection.
4. THE SYSTEM SHALL resolve probe targets by server-owned alias, never by an LLM-supplied URL or IP.

## R3 — Visual evidence capture (P0)

1. WHEN a capture occurs, THE CLIENT SHALL retain or securely upload the original image and its metadata while generating a bounded inference preview separately.
2. WHEN the photo is blurry, dark or occluded, THE AGENT SHALL be able to respond with a targeted evidence request or an inconclusive result.
3. THE SYSTEM SHALL distinguish phone, recorded fixture, glasses and mock source provenance in the inspection record.
4. WHEN the app is offline, THE CLIENT SHALL preserve collected evidence without presenting unfinished cloud analysis as a completed inspection.

## R4 — Meta reasoning and segmentation (P0)

1. WHEN a supported image is submitted, THE BACKEND SHALL invoke an actual Meta Muse Spark multimodal request and validate its typed response.
2. WHEN a segmentable object is nominated, THE BACKEND SHALL call SAM 3.1 using a concrete object prompt and produce a normalized box/mask result for the client.
3. IF SAM finds no match, THE SYSTEM SHALL emit a non-match result rather than manufacture a mask.
4. WHEN an invalid fixture ID or next-action enum appears in a model response, THE BACKEND SHALL refuse the action and log a bounded parsing failure.
5. THE MODEL SHALL NOT set the inspection completion state directly.
6. WHERE the model provides self-reported confidence, THE SYSTEM SHALL store it as an annotation, never as the final verification threshold.

## R5 — Active acquisition and inspectable ROI (P0)

1. WHEN important evidence is obscured or ambiguous, THE AGENT SHALL request a named component or camera view with a reason.
2. THE CLIENT SHALL render the applicable SAM ROI box or mask in original-image coordinates, handling rotation and letterboxing correctly.
3. WHEN a region is cropped, THE SYSTEM SHALL record the transform and preserve the original-image hash.
4. THE AGENT SHALL cap follow-up requests per inspection and become inconclusive rather than looping forever.

## R6 — Read-only diagnostics (P0)

1. WHEN the user requests corroboration, THE API SHALL run only fixture-approved read-only probes.
2. THE PROBE SHALL report `LiveLocal`, `Simulated` or `RecordedReplay` source, time, target alias, test definition and pass/fail/unavailable result.
3. IF a target is unknown, a redirect leaves the allowlist, or credentials are missing, THE PROBE SHALL fail closed.
4. THE SYSTEM SHALL NOT execute arbitrary model-generated shell commands, configuration changes or network targets.

## R7 — Epistemic state machine (P0)

1. WHEN evidence is incomplete, THE SESSION SHALL remain in `NeedsEvidence`, `FaultSuspected` or `Inconclusive`, not `FaultConfirmed` by default.
2. WHEN a technician claims repair, THE SYSTEM SHALL timestamp a repair event and invalidate all pre-claim verification checks for completion.
3. WHEN a fresh diagnostic still fails after a repair claim, THE SESSION SHALL become `VerificationFailed` with a reason.
4. WHEN evidence conflicts, THE SESSION SHALL retain the conflict and require further review.
5. THE SYSTEM SHALL apply state changes transactionally and with idempotent request identifiers.

## R8 — Deterministic dual-evidence verification (P0)

1. WHEN verification is requested, THE SERVER SHALL require a matching fixture revision, technician acknowledgement, human-reviewed visual checklist, post-repair image, fresh fixture-bound diagnostic and no unresolved contradiction.
2. IF any required evidence is stale, missing, synthetic-as-live or contradictory, THE SERVER SHALL deny live completion.
3. IF a simulated probe passes, THE SYSTEM SHALL display `VerifiedCompleteSimulated`, never `VerifiedCompleteLive`.
4. A passing connectivity probe SHALL verify only the defined target's connectivity check; it SHALL NOT imply port-level correctness on unmanaged switches.
5. THE TEST SUITE SHALL include a false-repair rejection, cross-fixture evidence rejection, stale evidence rejection and duplicate verification request.

## R9 — Evidence receipt (P0)

1. WHEN a bounded verification decision occurs, THE SYSTEM SHALL export JSON and printable HTML containing model/policy versions, asset and fixture versions, image/mask hashes, diagnostics, decisions and reasons.
2. THE RECEIPT VALIDATOR SHALL detect alterations to a included image, mask or manifest record.
3. THE RECEIPT SHALL disclaim that hashes establish integrity of the exported bundle, not independent authenticity of the observations.

## R10 — Wearables/hands-free (P1 only)

1. WHEN P0 is demonstrated reliably, THE APP MAY accept a labeled camera source through Meta's Wearables Device Access Toolkit and Mock Device Kit.
2. IF no supported glasses hardware is connected, THE P0 PHONE PATH SHALL remain usable without the SDK.
3. IF a mock glasses frame is shown, THE UI AND DEMO SHALL label it as simulated.

## Nonfunctional requirements

- Small bounded file sizes, validated media, limited Meta calls, timeouts, cancellation and useful errors.
- No secrets in Android APK, sample fixtures, screenshots, Git history or demo video.
- No automatic physical repair, energized equipment instructions, arbitrary network scan or real CMMS dispatch.
- Human-initiated capture; no silent background camera recording.
- Scoped verification status, not general electrical/structural/safety certification.

## Release gates

- P0: Live Muse + SAM calls; actual masks or honestly reported no-match; working evidence loop; fake-fix rejection; successful bounded verification; receipts and tests.
- P1: Wearables, voice or advanced visualization only after P0 end to end.
