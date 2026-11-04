# Cerebral Valley submission draft

Status: entered in the open portal form; not submitted.

Team: LensQi FieldOps

Track: Indie Developer Track

Problem statement: Push the Frontier

## Project Description

LensQi FieldOps helps technicians and DIY users do hands-on technical work with camera-guided instructions, then gives technicians an evidence trail for the checks that matter. See the task. Do it right. Prove it when it matters.

The problem: manuals rarely match the exact view in front of a worker, and a convincing after-photo cannot establish that equipment actually works. LensQi connects practical guidance to a separate, evidence-based repair-verification workflow.

Start with a goal, such as installing a PoE camera, and LensQi creates a step-by-step plan with tools, cautions and stop conditions. Or open "Just ask," point the camera and ask about what you see. Meta Muse Spark supplies contextual guidance and photo-check suggestions; SAM 3.1 localizes relevant parts. The person controls step completion. Browser and Android clients support the workflow, with saved jobs, supported offline capture/update queues, and an operations dashboard for review and handover.

For site-bound repairs, FieldProof adds a verification loop. The technician confirms the asset, consults applicable vendor/site facts with citations, records a repair claim, supplies fresh evidence and reviews the required checklist. Muse can propose a diagnostic outcome before an approved check runs; the system records whether the prediction agrees with the measurement. SAM-based change analysis and image checks flag duplicate or unchanged evidence. A deterministic policy combines the evidence, human acknowledgement, required knowledge confirmations and a fresh approved diagnostic. A failed measurement overrides a plausible-looking photo. The receipt preserves the event history and artifact hashes for offline integrity checking.

This is our Push the Frontier contribution: a visual assistant whose proposals can be tested against independent evidence, with an auditable decision about what was actually verified. In a recorded evaluation of 25 synthetic switch scenarios with simulated diagnostics and genuine Muse Spark/SAM calls, the full loop produced zero false completions and all 12 issued receipts passed integrity verification. A single-look visual proxy would have accepted four of five fake-fix scenarios. These are controlled evaluation results, not field accuracy claims.

The prototype uses .NET 10, React/TypeScript and Kotlin/Jetpack Compose. Recorded evidence includes browser/emulator workflows and selected Samsung Galaxy S23 Ultra runs. A separate phone verification run used a real HTTP loopback check against a local test server, not physical equipment. Real-equipment pilot validation is the next milestone. Event-period implementation and prior design inputs are documented in docs/HACKATHON_CONTRIBUTIONS.md and docs/PRIOR_ART.md; pre-existing design materials are excluded from novelty claims.

## Public GitHub Repository

https://github.com/Telli/lensqi-fieldops

## Describe your use of Meta products

LensQi uses genuine Meta Model API calls for both reasoning and visual segmentation.

Muse Spark (muse-spark-1.3) powers goal-to-plan generation, camera questions, live coaching, step-photo suggestions and structured inspection assessments. In FieldProof it receives the asset/site context and applicable vendor facts, returns cited proposals and can register a predicted diagnostic result before the approved check runs. Strict JSON-schema outputs are validated against the bound asset, allowed facts and supported actions before application code accepts them.

SAM 3.1 (sam-3.1) localizes relevant equipment and components on original images. Its parsed masks and boxes support visual outlines, crops and before/after change analysis. Duplicate-photo and change checks help determine whether a new repair claim has usable new evidence. No-match and unscorable results remain explicit rather than being replaced with invented geometry.

The model adapters run on the .NET server; API credentials stay server-side. Recorded genuine calls include model IDs, usage, prompt/response digests and evidence labels. The evaluation records 25 synthetic scenarios using genuine Muse Spark and SAM calls; the diagnostic results in that evaluation are explicitly simulated.

The Android app also includes an optional Meta Wearables Device Access Toolkit integration for glasses capture and live assistance. That path was exercised with Meta's Mock Device Kit; physical Meta glasses have not been validated. Speech uses browser/Android speech services. The submitted implementation's model use is Muse Spark and SAM 3.1, with verification decisions made by deterministic application policy.

## Why Meta models matter

Meta's models connect the worker's visual scene to an actionable, testable workflow. Muse Spark combines the user's goal, camera image, current step and asset-specific knowledge to explain what to do next, ask for a better view when evidence is insufficient, and produce structured plans or cited hypotheses. SAM 3.1 supplies spatial evidence: where a component is in the image and how the relevant region compares across captures.

That pairing enables the core loop: observe, propose, gather evidence, measure and decide. Muse provides semantic reasoning; SAM makes visual regions available to deterministic image and change checks. Approved diagnostics and human review then determine whether the evidence supports the repair claim. Model uncertainty is visible, and the model cannot independently mark a repair verified.

Both models are integral to the implemented experience and have recorded genuine-call evidence. The controlled evaluation illustrates why this separation matters: plausible visual observations sometimes accepted fake fixes, while the complete verification policy held or denied them. The opportunity is a practical multimodal assistant for technical work whose outputs can be challenged and audited.

## Partner feedback

The submitted implementation uses Meta Model API directly. Baseten/Muse Spark Open was explored for feasibility, but no authenticated partner-hosted inference call or deployment was completed. We therefore have no hands-on feedback on Fireworks, Modal or Baseten to report and claim no partner-hosted integration.

## Remaining

- Demo video URL
- Optional X post URL
- Personal API/tool experience survey answers
- Marketing permission checkbox
- Final submission
