# LensQi — Guided Tasks for DIY Users and Technicians
## Kiro Requirements Specification

**Spec ID:** `guided-tasks`  
**Version:** 1.0 · October 28, 2026  
**Status:** Mostly implemented on October 28–29; see §7 for each requirement's state from the October 29 code review. Requirements stay the target where the code falls short.  
**Parent:** LensQi PRD v2.0 (`../../../PRD.md`), `design.md`, `tasks.md`  
**Related specs:** `../lensqi-fieldops/` (FieldProof inspection loop, the technician closer), `../vendor-knowledge-and-diagnostics/` (cited knowledge for site records)  
**Hackathon:** Meta Global AI Developer Hackathon · *Push the Frontier* · public indie-track entry

## 1. Objective

Re-centre the product on a **guided task**: the user states a goal, Muse Spark writes a labelled plan, live AR and voice walk the user through each step, and an optional photo check after a step suggests whether it looks done. Two modes share this core:

- **DIY mode** for everyday technical users (for example "change the oil in my 2025 Civic", "mount an IP camera").
- **Technician mode** for installs, repairs and on-site help. A technician task can be linked to a site record, which gives it the fixture-approved equipment check and the FieldProof verified receipt.

### Prior requirements that remain authoritative

R1–R10 in `.kiro/specs/lensqi-fieldops/requirements.md` and VK-R01 onward in `.kiro/specs/vendor-knowledge-and-diagnostics/requirements.md` remain in force for inspections. This spec MUST NOT weaken the `VerifiedComplete` gate, the fixture allowlist, or the diagnostic rules. It replaces only two v1.1 positions: the non-goal "arbitrary device/brand support" and the principle that the agent never instructs hands-on work. Both are replaced by the safety policy in GT-R03. If an item here conflicts with a FieldProof safety invariant, the FieldProof invariant wins.

## 2. Scope and priorities

| Priority | Included | Excluded |
|---|---|---|
| **P0: Hackathon** | Task core and event log; AI-written plans with safety validation; restricted categories; web and Android step runner with live AR; spoken guidance, push-to-talk questions, voice commands; step photo check; technician link to a site-record inspection; fakes, tests and genuine-call evidence | Server-side speech recognition; hands-on steps for restricted work; any "verified" label outside FieldProof |
| **P1: After P0 works** | Glasses for tasks through the existing glasses live service; ops dashboard Tasks list; vendor-knowledge citations in technician plans | Real-glasses claims without hardware |
| **P2: Roadmap** | Muse Voice Transcribe; multilingual guidance; household/fleet asset history; conceptual "done looks like" images | Hackathon-critical path |

## 3. User stories

**US-GT-01 — DIY user:** As an everyday technical user, I want to say what I am trying to do and get a step-by-step plan for my exact situation, with tools, parts, time, risks and when to stop, so I can decide whether to do it myself.

**US-GT-02 — DIY user:** As a user with my hands full, I want each step spoken and the parts I need outlined in the camera view, and I want to ask questions and move between steps by voice.

**US-GT-03 — DIY user:** As a user unsure whether I did a step right, I want to photograph my work and get a suggestion, while I stay in charge of moving on.

**US-GT-04 — Technician:** As a technician, I want the same guided steps for installs and repairs, and when the job is on a site record, I want to finish with the approved equipment check and a FieldProof receipt.

**US-GT-05 — Safety reviewer:** As a reviewer, I want dangerous categories of work to be refused at the server, not just discouraged in a prompt, so a model mistake cannot hand a user airbag or gas-line steps.

**US-GT-06 — Developer/judge:** As a reviewer of the public project, I want to run the task flow with fakes and see genuine-call evidence labelled with model ids and digests, so I can tell what really ran.

## 4. Functional requirements (EARS-style)

### GT-R01 — Modes and task creation (P0)

1. WHEN the app opens, THE CLIENT SHALL show a mode home with a "Do it yourself" entry (goal box with a mic button) and a "Technician" entry (jobs, site records, verify a repair), and SHALL list recent tasks under each.
2. WHEN a user submits a goal, THE API SHALL create a task with `mode` (`Diy` or `Technician`), `goal` (1–200 characters), optional `details` (bounded) and up to a configured number of manual photos, and SHALL append `TaskCreated`.
3. WHERE a technician task names a site link, THE API SHALL accept only a fixture ID and asset ID present in the site-record catalog, and SHALL reject unknown IDs with no task created.
4. IF the goal comes from speech, THE CLIENT SHALL send only the recognised text; THE API SHALL NOT accept audio.
5. THE API SHALL accept an `Idempotency-Key` on task creation and SHALL return the original task for a repeated key.

### GT-R02 — AI-written plan (P0)

1. WHEN a task is created, THE TASK PLANNER SHALL request a plan from Muse Spark with a strict JSON schema, low reasoning effort and a bounded token budget, sending the goal, details and any manual photos.
2. THE PLAN SHALL contain a title, summary, risk level (`Low`, `Moderate`, `High`), time estimate, restricted category, `professionalRequired` with a reason when true, tools, parts, and 1–25 steps.
3. EACH STEP SHALL contain an id, title, instruction, hands-on flag, at most three `lookFor` noun phrases, cautions (each with a kind: hot, electrical, lifting, chemical, height, sharp, pressure, other), stop-if conditions, and an optional photo check with "what done looks like".
4. THE SERVER SHALL set the plan's `sourceNote` to "AI-written plan · check your owner's manual"; IF the model output contains its own source note, THE SERVER SHALL ignore it.
5. WHEN the plan passes validation, THE SYSTEM SHALL append `PlanProposed(plan)`; IF it fails, THE SYSTEM SHALL store no plan and SHALL return a typed error (`invalid_plan` or `unsafe_plan`).
6. WHERE manual photos are attached, THE PLAN SHALL NOT be presented as quoting the manual unless the step text is grounded in a shown photo; THE UI SHALL keep the AI-written label in all cases.

### GT-R03 — Server-enforced safety policy (P0)

1. THE TASK PLAN VALIDATOR SHALL reject plans with missing required fields, text or list lengths over the configured bounds, duplicate step ids, any link or URL, or `lookFor` values that are not plain 1–4 word noun phrases (the rules the live-guidance validator already applies).
2. THE SYSTEM SHALL recognise these restricted categories: gas lines or gas appliances; main electrical panel or live mains wiring; EV high-voltage systems; airbags/SRS; refrigerant; structural or load-bearing work.
3. WHEN the user's goal or details name restricted work, THE VALIDATOR SHALL treat the task as restricted regardless of the model's label; IF the model's plan does not declare that restriction, THE VALIDATOR SHALL reject it.
4. WHILE a plan is restricted, THE VALIDATOR SHALL accept only prepare-and-observe steps and SHALL require `professionalRequired = true` with a referral reason; IF any step is hands-on, THE VALIDATOR SHALL reject the plan with `unsafe_plan`.
5. THE CLIENT SHALL NOT be able to lower or clear a task's restricted category or risk level.
6. THE SAME OUTPUT CHECKS (no links, bounded text, plain noun-phrase outline names) SHALL apply to live guidance, spoken answers and step-check reasons.
7. THE PLAN SCREEN SHALL state that the plan is not a safety certification, code compliance or manufacturer approval.

### GT-R04 — Plan acceptance and step flow (P0)

1. WHEN the user starts a task, THE CLIENT SHALL require acceptance of the plan's safety notes, and THE API SHALL append `PlanAccepted(safetyAcknowledged: true)`.
2. WHERE the plan's risk is above Low, it requires a professional, or any step has a caution, THE API SHALL reject step commands before `PlanAccepted` with `safetyAcknowledged = true` (`safety_not_acknowledged`); a Low-risk plan with none of these can start without it. THE CLIENT SHALL make the start button itself the acknowledgement, with the safety notes directly above it.
3. WHEN the user starts, completes or skips a step, THE API SHALL append `StepStarted`, `StepCompleted` or `StepSkipped` for a step id in the accepted plan; IF the step id is unknown, THE API SHALL reject it with `unknown_step`.
4. WHEN the user goes back to an earlier step, THE SYSTEM SHALL keep all prior events and SHALL record a new `StepStarted`.
5. WHEN the user finishes or stops, THE API SHALL append `TaskCompleted` or `TaskAbandoned`; no further step commands SHALL be accepted.
6. THE SYSTEM SHALL apply every command idempotently by `Idempotency-Key`, SHALL return a typed conflict (`invalid_state`) for an out-of-order command, and SHALL reproduce identical state by replaying the event log.

### GT-R05 — Live scan with AR (P0)

1. WHEN the step runner starts live assist, THE CLIENT SHALL reuse the existing live camera, on-device 2D tracker, frame sampling and minimum intervals.
2. WHEN live detection runs for a task, THE API SHALL outline the current step's `lookFor` phrases first, then focus phrases from the latest step guidance, and SHALL NOT accept concept text from the client.
3. WHERE the Android phone supports ARCore, THE CLIENT SHALL use the existing ARCore renderer, SHALL create anchors only where ARCore measured depth inside a box, and SHALL derive distance coaching from the step's main `lookFor`.
4. WHERE ARCore is unavailable (browsers, unsupported phones, glasses), THE CLIENT SHALL track in 2D and SHALL NOT show a distance.
5. WHEN SAM finds no match, THE CLIENT SHALL show no outline for that phrase and SHALL NOT draw a synthetic one.
6. THE TASK LIVE ENDPOINTS SHALL reuse the existing live-vision gate, frame checks, rate limits and model-call limits, and SHALL NOT store live frames as evidence.

### GT-R06 — Step guidance (P0)

1. WHEN live guidance is requested for a task, THE STEP COACH SHALL build its context from the goal, the accepted plan and the current step, not from a site record.
2. THE STEP COACH SHALL keep guidance inside the current step; IF the model suggests work outside the plan, THE SYSTEM SHALL drop it.
3. THE STEP COACH AND THE INSPECTION LIVE SERVICE SHALL share one prompt and context builder; inspection live assist SHALL keep its current behaviour.

### GT-R07 — Voice (P0)

1. WHEN a step opens or step guidance changes, THE CLIENT SHALL speak it through text-to-speech, gated so unchanged advice is not repeated and utterances are spaced.
2. WHEN the user holds or taps push-to-talk, THE CLIENT SHALL use Android `SpeechRecognizer` (preferring the on-device recognizer where available on Android 12+, otherwise the system service) or the browser Web Speech API; these services may process audio remotely. THE CLIENT SHALL open the microphone only while push-to-talk is active.
3. WHERE speech recognition is unavailable, THE CLIENT SHALL offer a typed question box instead.
4. WHEN recognised text matches the command grammar (next, back, repeat, take photo, check step, pause), THE CLIENT SHALL perform that action and confirm it on screen; OTHERWISE THE CLIENT SHALL send the text as a question.
5. THE COMMAND GRAMMAR SHALL be the same on web and Android and SHALL be covered by the same test cases on both.
6. THE LENSQI SERVER SHALL NOT receive or store audio. WHERE a browser's speech service sends audio to the browser vendor, THE CLIENT SHALL disclose it before first use.
7. WHEN the Android client first uses push-to-talk, IT SHALL request `RECORD_AUDIO`; IF refused, IT SHALL fall back to typed questions.

### GT-R08 — Questions within the step (P0)

1. WHEN a question arrives at `/ask` with optional frame, THE STEP COACH SHALL answer from the goal, the accepted plan, the current step and the frame, and THE API SHALL append `QuestionAnswered(stepId, question, answer, photo?)`.
2. IF an answer fails the output checks in GT-R03.6, THE API SHALL reject it and SHALL NOT record it as answered.
3. WHEN a question asks for hands-on work in a restricted category, THE ANSWER SHALL be a referral and SHALL NOT contain hands-on instructions. **Implementation gap:** this is a target behavior; the current prompt, output checks and appended-referral safeguard do not structurally enforce the absence of hands-on instructions.
4. WHEN a question frame is sent, THE SYSTEM SHALL store it under the task id with a digest and purpose `Question`.

### GT-R09 — Step photo check (P0)

1. WHEN the user takes a step-check photo, THE CLIENT SHALL capture it at full resolution and THE API SHALL store it under the task id with a digest.
2. THE STEP CHECKER SHALL compare the photo with the step's "what done looks like" using Muse vision and SHALL return `LooksDone`, `NotYet` or `CantTell` with a bounded reason; THE API SHALL append `StepChecked(stepId, photoId, verdict, reason)`.
3. THE UI SHALL present the verdict as a suggestion; ONLY the user's complete or skip command SHALL change the step. A step whose plan marks the check `required` SHALL get a prompt to take one, never a block: the user can mark it done without a photo.
4. A step check on a technician task SHALL NOT produce, feed or display a FieldProof decision.
5. WHILE the Android client is offline, IT SHALL queue step-check photos as `StepCheckPhoto` commands and sync them when the network returns.

### GT-R10 — Technician mode and site-record link (P0)

1. THE TECHNICIAN MODE SHALL offer the same plan, step, live AR and voice features as DIY mode for install, repair and on-site tasks.
2. WHEN a technician task with a site link calls `link-inspection`, THE API SHALL create or link an `InspectionSession` for that fixture and asset and SHALL append `InspectionLinked(inspectionId)`.
3. THE EXISTING `/v1/inspections/*` ROUTES, VERIFICATION GATE AND RECEIPT SHALL remain unchanged and SHALL be the only source of a verified outcome.
4. WHEN a linked inspection reaches a FieldProof decision, THE TASK DONE SCREEN SHALL show that decision with its live or simulated label; WHEN no inspection is linked, IT SHALL show no verification label.
5. DIY tasks SHALL NOT be linkable to inspections.

### GT-R11 — Task record and storage (P0)

1. THE SYSTEM SHALL store each task as an append-only JSONL event log (`data/tasks/{id}.jsonl`) with a per-id lock, sharing append-and-replay code with the inspection store.
2. EVERY TASK EVENT SHALL carry a server timestamp and SHALL have a registered polymorphic type and a replay test.
3. TASK PHOTOS SHALL be stored in the evidence file store under the task id with a SHA-256 digest and a purpose (`Manual`, `StepCheck`, `Question`).
4. `GET /v1/tasks/{id}/events` SHALL return the event log; `GET /v1/tasks/{id}/artifacts/{name}` SHALL return only artifacts belonging to that task.

### GT-R12 — Android generalization (P0)

1. THE ANDROID COMMAND QUEUE, PENDING COMMANDS AND SYNC WORKER SHALL address a session id that is either a task or an inspection, without changing inspection behaviour.
2. THE GLASSES LIVE SERVICE SHALL accept a task session id so glasses can serve tasks (P1 use).
3. THE NAVIGATION HOST SHALL start on the mode home; technician routes for inspections, review and receipt SHALL remain.

### GT-R13 — Honest labelling and evidence (P0)

1. THE UI SHALL show the AI-written label on every plan, "suggestion" on every step-check verdict, "simulated" on every simulated diagnostic, and "mock" on every mock glasses source.
2. THE UI AND DOCUMENTS SHALL use "verified" only for FieldProof decisions.
3. GENUINE-CALL EVIDENCE SHALL be recorded under `docs/evidence/tasks/` with model ids, request digests and what device or browser ran it; documents SHALL claim only what that evidence shows.
4. EVERY MODEL CALL SHALL have a deterministic fake used by automated tests; tests SHALL NOT be presented as genuine calls.

## 5. Nonfunctional requirements

- Bounded text, image size and photo counts on every endpoint; timeouts, cancellation and typed errors (`invalid_plan`, `unsafe_plan`, `invalid_state`, `unknown_step`, `safety_not_acknowledged`, `duplicate_evidence`, `invalid_input`, plus the existing `model_timeout`, `rate_limited` and provider-down codes).
- Plan creation within the model-call budget (about 6000 tokens, low reasoning); median plan and step-check latency measured and reported, not promised.
- No audio leaves the device to LensQi; no live frames stored as evidence; no secrets in clients, fixtures or logs.
- Human-initiated capture and push-to-talk only; no background recording.
- The phone-size web flow and Android screens remain usable one-handed with large controls.

## 6. Release gates

- **Contract gate (Oct 29):** Core task tests (replay, validator, restricted categories, step transitions, idempotency) and API tests with fakes pass; contract frozen.
- **Web gate (Oct 30):** DIY flow end to end at phone size with fakes and one genuine plan.
- **Android gate (Oct 31):** Command-grammar and view-model tests pass; debug APK builds; task flow runs on the emulator.
- **Technician gate (Nov 1):** A technician task linked to `LAB-SW-01` reaches a FieldProof decision through the existing endpoints.
- **Evidence gate (Nov 2):** Genuine oil-change and IP-camera plans, a step check, a voice question and SAM outlines recorded; S23 Ultra phone run recorded with what did and did not happen (including whether ARCore anchors appeared).
- **Always:** the FieldProof loop, clean-room check and existing tests stay green.

## 7. Implementation status (code review, October 29, 2026)

Reviewed commit `044c906` on the `guided-tasks` branch; 211 .NET, 25 web and 107 Android unit tests passed. Updated after the Oct 29 fixes (GT-T31–T37): 220 .NET, 25 web, 107 Android. "Met" means implemented and covered by tests or recorded evidence; it does not mean validated on a phone or with a real microphone. Differences from `design.md` are listed in its §0.

| Requirement | State | Gaps |
|---|---|---|
| GT-R01 Modes and creation | Met | Goal limit is 3–300 characters (not 1–200). A third mode, free-form help, was added |
| GT-R02 AI-written plan | Met | — |
| GT-R03 Safety policy | Met for plans | GT-R03.1 met Oct 29 (bare `www.` addresses too). GT-R03.6 applies length and link checks to guidance, answers and reasons; the "stay inside the step" rule is prompt-only |
| GT-R04 Plan acceptance and steps | Met | GT-R04.2 rewritten Oct 29 to the built rule. GT-R04.6: request ids are persisted on events since Oct 29; clients still send a fresh key per request, so only server-side retries replay |
| GT-R05 Live scan with AR | Partly met | Step `lookFor` is sent first. GT-R05.3: Android distance advice is not aimed at the step's main part. No phone run of the task step runner; no ARCore anchor recorded on a real part |
| GT-R06 Step guidance | Partly met | GT-R06.3: the prompt is not shared with `LiveSceneService`; inspections keep their own |
| GT-R07 Voice | Partly met | GT-R07.1: Android task speech is not gated by `SpokenFeedbackGate`. GT-R07.3 met Oct 29 (typing next to the mic on both clients). GT-R07.4: grammar is next, back, repeat, check, skip, mute, unmute (no pause). Not exercised with a real microphone |
| GT-R08 Questions | Partly met | GT-R08.3 is not structurally enforced: the server appends a referral only when the question matches a restricted phrase |
| GT-R09 Step photo check | Partly met | GT-R09.3: since Oct 29 a `required` check is a prompt, not a block. GT-R09.5: the Android offline queue does not carry step-check photos |
| GT-R10 Technician link | Partly met | Linked journey to `VerifiedCompleteSimulated` passes with fakes (`TechnicianJourneyTests`). GT-R10.4 met Oct 29 on the web done screen and Android summary |
| GT-R11 Task record and storage | Met | No shared JSONL helper with the inspection store (mechanics are duplicated) |
| GT-R12 Android generalization | Partly met | GT-R12.3 met. GT-R12.1 and GT-R12.2 (queue and glasses `sessionId`) not started |
| GT-R13 Honest labelling | Met in docs and UI reviewed | GT-R13.3: no genuine spoken question or SAM outline on a real step part recorded yet |

