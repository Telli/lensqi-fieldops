# LensQi product pitch and feature review

Reviewed October 28, 2026, Pacific time, against the local `guided-tasks` branch at `83132dd`. Audience: hackathon judges and prospective pilot partners. This review compares implementation, tests and recorded evidence; it is not a fresh runtime or hardware validation.

**LensQi helps people do hands-on technical work with camera-guided instructions, and gives technicians an evidence trail for the checks that matter.**

*See the task. Do it right. Prove it when it matters.*

**Pitch script — approximately 90 seconds**

You're halfway through installing a camera. The instructions don't match what you're looking at. Which cable is this? What should you check next? And when you're finished, how do you know the equipment actually works?

LensQi brings guidance into the job itself.

Start with a goal, such as mounting an IP camera, and LensQi builds a step-by-step plan with tools, cautions and conditions for stopping. Or open “Just ask,” point the camera and ask about what you see. Meta's Muse Spark provides contextual guidance; SAM 3.1 outlines relevant parts. Photo checks can suggest whether a step looks done, while the person stays in control.

For technicians, LensQi adds FieldProof. In its inspection workflow, a repair needs fresh photo evidence, human review and a passing approved equipment check before it can be marked verified. A convincing picture alone cannot close the job. The result is a receipt with an event history and artifact hashes that can be checked offline.

In our recorded evaluation of 25 synthetic scenarios with simulated diagnostics, the full verification loop produced zero false completions. A single-look visual baseline would have accepted four of five fake-fix cases.

The prototype runs on web and Android, with genuine model calls recorded. Our next milestone is validating the complete experience on real equipment with a pilot partner.

LensQi: see the task, do it right, prove it when it matters.

**Current feature review**

| Capability | What exists | Pitch boundary |
| --- | --- | --- |
| DIY guided tasks | Goal entry, AI plans, tools and parts, risk labels, cautions, stop conditions, step progress; genuine oil-change and camera-installation plans recorded | Plans are AI-written guidance. Physical completion of these jobs has not been demonstrated. |
| “Just ask” | Immediate camera help without a plan, questions with images, live coaching, entry into a planned task; browser and emulator evidence | Recorded questions include a router issue and an emulator camera image. Avoid claiming universal troubleshooting accuracy. |
| Visual guidance | Muse guidance, SAM segmentation and 2D tracking; Android ARCore implementation | Real-equipment anchors and measured distance have not been demonstrated. |
| Step photo checks | `LooksDone`, `NotYet`, `CantTell`; user controls completion | A photo-check suggestion never means verified work. Recorded task checks demonstrate uncertainty on unrelated images. |
| Voice | Spoken guidance, push-to-talk and command parsing implemented | Real-microphone task use remains unvalidated. On-device recognition is conditional; browser/system providers may process audio. |
| Plan safety controls | Server validator rejects hands-on steps for recognized restricted categories; genuine breaker-panel referral plan recorded | Classification uses configured phrases and model labels. Free-form answers receive a referral safeguard, not the same structural rejection as plans. No universal safety guarantee. |
| Technician context | Site-aware plans, task-to-inspection linking, asset-bound knowledge and citations, ops task list | Knowledge scope is partial. The continuous API journey now passes with fake models and simulated checks; a user-operated hardware journey remains unvalidated. |
| FieldProof | Separate inspection loop with photo-change checks, approved diagnostics, human checklist, provenance labels and verification gate | Demonstrated on a controlled switch fixture with synthetic scenes and simulated diagnostics; a live HTTP loopback run is separately recorded. |
| Receipts | Artifact hashes, append-only event trail and offline verification | Detects alterations within the bundle; does not authenticate the photographer or prove the physical repair universally. |
| Mobile and wearables | Web and Android; offline inspection capture/sync; glasses integration exercised with Mock Device Kit | Task-photo offline queue and task glasses support remain unfinished; no real-glasses validation. |

**Positioning and product priorities**

Lead with a familiar job and the question “What do I do next?” Close with the more distinctive question “What evidence says this worked?” DIY makes the product easy to understand; FieldProof gives the demonstration its strongest technical distinction.

For an initial pilot, focus on bounded network and camera installation work with explicit site records and approved checks. This is a proposed starting market, supported by the existing fixture and workflow, not a claim of customer demand or traction. Measure successful task completion, repeat visits, supervisor review time and incorrect closeouts before claiming operational savings.

The continuous technician API journey now covers plan, guided steps, linked inspection, failed repair attempt, accepted evidence, passing simulated check and intact receipt with fake models. The next validation is a user-operated journey with real-device camera and voice behavior. AR distance and glasses can remain secondary until demonstrated on hardware.

The README and PRD now distinguish platform speech services from on-device recognition and explain the weaker free-form referral safeguard. The demo storyboard includes guided tasks; the web UI discloses speech-provider audio processing.

**Three-minute demo outline**

| Time | Show | Message |
| --- | --- | --- |
| 0:00–0:20 | A recorded FieldProof attempt where the picture looks plausible but the simulated equipment check fails | Appearance alone cannot establish completion. Label synthetic images and simulated diagnostics on screen. |
| 0:20–0:45 | “Just ask” and a contextual camera question | Help can begin before the user knows how to describe the job. Use recorded evidence with its label if live capture is unavailable. |
| 0:45–1:20 | IP-camera goal, generated plan, cautions and step runner | Show concrete guidance and a photo-check suggestion that leaves the decision with the user. |
| 1:20–1:40 | Breaker-panel goal and referral-only plan | Demonstrate the restricted-plan boundary already supported by evidence. |
| 1:40–2:30 | Clearly transition to the separate FieldProof inspection: rejected duplicate photo, failed check, then passing simulated check with fresh accepted evidence and human review | Guidance and verification serve different roles. Label the linked API journey as fake-model integration evidence; do not imply a physical task was demonstrated. |
| 2:30–2:50 | Receipt and offline verifier; operations view | The reviewable record includes what was observed, checked and decided. |
| 2:50–3:00 | Evaluation result and closing line | “25 synthetic scenarios, simulated diagnostics, zero false completions in this evaluation.” |

**Evidence and implementation references**

- [Current scope and remaining requirements](../PRD.md), [guided-task implementation status](../.kiro/specs/guided-tasks/tasks.md), and [vendor-knowledge status](../.kiro/specs/vendor-knowledge-and-diagnostics/tasks.md).
- [Genuine task calls and browser/emulator evidence](evidence/tasks/README.md), including free-form help recorded under a UTC October 29 heading.
- [Recorded evaluation](evaluation/eval-20261028T061752Z.md): 25 scenarios, four held out; 25 deterministic expectations passed; zero full-loop false completions; 12 of 12 issued receipts verified. The single-look baseline uses an observed-parts proxy, not a deployed competing product.
- [Live loopback evidence](evidence/receipt-loopback/README.md), [AR status](LIVE_AR.md), and [existing inspection storyboard](DEMO_STORYBOARD.md).
- [Task endpoint behavior](../src/LensQi.FieldOps.Api/Features/Tasks/TaskEndpoints.cs), [plan validator](../src/LensQi.FieldOps.Core/Tasks/TaskPlanValidator.cs), [task API tests](../tests/LensQi.FieldOps.Api.Tests/TaskApiTests.cs), and [task state tests](../tests/LensQi.FieldOps.Core.Tests/TaskSessionTests.cs).
- [Web speech implementation](../apps/web/src/lib/voice.ts) and [Android speech implementation](../apps/android/app/src/main/java/com/lensqi/fieldops/voice/Voice.kt).
