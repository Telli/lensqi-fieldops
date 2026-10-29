# LensQi
## Product Requirements Document — Meta Global AI Developer Hackathon

**Version:** 2.0 — Guided tasks for DIY users and technicians (re-architecture)  
**Date:** October 28, 2026 (status in §1 updated October 29, 2026)  
**Track / theme:** Indie Developer / Push the Frontier  
**Build period:** October 27–November 4, 2026  
**Submission:** November 4, 2026, 9:00 PM Eastern / 6:00 PM Pacific  
**Status:** Target product. Version 2.0 describes what the product is being rebuilt into. Only the items listed under "What exists today" in §1 have run; everything else in this document is a requirement, not a claim.  
**Repository strategy:** Public `Telli/lensqi-fieldops`, created October 28, 2026 (UTC) from the specification kits; existing `Telli/LensIQ-Monorepo` remains private.  
**Specs:** `.kiro/specs/guided-tasks/` (this re-architecture), `.kiro/specs/lensqi-fieldops/` (the FieldProof inspection loop, kept as the technician closer), `.kiro/specs/vendor-knowledge-and-diagnostics/` (cited vendor knowledge for site records).

**Brand hierarchy:** **LensQi** (visual intelligence platform) → **LensQi guided tasks** (the product: DIY mode and Technician mode) → **FieldProof Engine** (evidence and verification capability used by Technician mode). The repository and API keep the `LensQi.FieldOps` names. **LensIQ** refers only to the existing private heritage/tourism repository; it is not the hackathon product name.

**Pre-existing private baseline:** `Telli/LensIQ-Monorepo` `main` at `593f467372c7e88acb7a217242fb70b1b0c49525` (2026-10-16). This is a provenance reference, **not** permission to redistribute code. The public repository's first commit is `f46e967` (specification kit); no private source has been copied.

**Competition rules assumption:** The guide requires public code and demonstrations limited to work built during the event; it explicitly discusses existing-code reuse for startups but is less clear for indie entrants. **Default to fresh implementation unless the organizer confirms permitted indie reuse.** Pre-existing code, if permitted, must be separately credited and excluded from claims of hackathon novelty.

**Section numbering:** Sections §4A, §5, §7, §8, §9, §11, §16, §17 and §19 keep their v1.1 numbers and topics because code comments, tests and other documents cite them. FR-01 to FR-10 keep their v1.1 identifiers as the Technician-mode FieldProof requirements.

---

## 1. Executive summary

**LensQi** walks a person through a hands-on technical task with a live camera, AR outlines, AI-written steps and voice. It has two modes built on one guided-task core:

- **DIY mode** for everyday technical users. Example goals: *"Change the oil in my 2025 Civic"*, *"Mount an IP camera over the garage door"*.
- **Technician mode** for installs, repairs and on-site help. A technician task can be tied to a **site record**; that unlocks the fixture-approved equipment check and the **FieldProof verified receipt**, which stay the technician's closer.
- **Free-form help ("Just ask")** for spontaneous use: no goal or plan first. The camera opens at once; the user asks about whatever is in view (an error on a screen, an unfamiliar part, a quick fix) by voice or text, live coaching outlines what LensQi points at, and "Make a plan" turns a longer job into a guided task. Free-form answers use prompt instructions, output checks and a referral safeguard for recognized restricted phrases. This is weaker than the structural plan validator and does not block every unsafe answer.

**The guided task, in both modes:**

1. The user states a goal (typed or spoken) and may add details and photos of the owner's manual.
2. Muse Spark writes a plan: steps, tools and parts, time estimate, risk level, per-step cautions and "stop if" conditions. The plan is labelled **"AI-written plan · check your owner's manual"**, and the server rejects or restricts plans that break the safety policy (§4).
3. The user accepts the plan's safety notes and starts. Each step shows the live camera with SAM 3.1 outlines of the parts the step needs, ARCore anchors and measured distance on supported Android phones, and 2D tracking elsewhere. The instruction is spoken; the user can ask questions out loud about what the camera sees and move through steps by voice.
4. An optional photo check after a step *suggests* whether it looks done. The user decides. In Technician mode, verified completion still comes only from the FieldProof gate.

**Tagline:** *See the task. Do it right. Prove it when it matters.*

**Core innovation:** the model writes and coaches; application code decides. A deterministic plan validator enforces safety rules the prompt cannot override, every step and answer is an append-only event, and in Technician mode the existing epistemic state machine and dual-proof gate (§7, §17) refuse to call a repair verified on appearance alone. The memorable moments are a DIY plan that refuses to hand a user hands-on airbag steps, and the v1.1 fake-fix rejection: a cable that looks connected while an independent check still fails.

### What exists today (reviewed October 29, 2026)

Status from a code review of the `guided-tasks` branch at `044c906` and a fresh run of the automated suites on October 29: **211 .NET tests** (96 Core, 18 Meta, 97 API), **25 web tests** and **107 Android unit tests** passed. Three defects found in that review (GT-T31–T33) were then fixed, bringing the .NET suite to 214. Nothing on a physical phone or with a real microphone was re-run for this review.

| Capability | State |
|---|---|
| FieldProof inspection loop for one lab switch fixture (`LAB-SW-01`): Muse assessment, SAM outlines, fixture-approved diagnostic (simulated, replay and loopback live), fake fix denied, real fix verified, hashed receipt and offline verifier | Built and tested; evaluation `docs/evaluation/eval-20261028T061752Z.md` |
| Vendor knowledge packs with citations and predictions for the site record | Partly built (see the vendor-knowledge spec's task status) |
| Live camera assistance (web, Android CameraX, glasses stream): 2D tracking, periodic SAM detection, Muse live guidance about anything in view | Built; checked in the browser and on the emulator (`docs/LIVE_AR.md`) |
| Android ARCore mode: anchors from measured depth and camera-to-part distance | Built with geometry unit tests. First phone run on a Galaxy S23 Ultra ran live assist on an ARCore session with genuine model calls, but produced **no anchor or distance yet** (no fixture part in view). Anchors on real equipment and the AR-to-CameraX shutter hand-off are untested on hardware |
| Meta glasses through the Wearables Device Access Toolkit | Exercised against the Mock Device Kit only; no real glasses tested |
| Guided-task core: `TaskSession` with append-only JSONL events and replay, `TaskPlanValidator` with the restricted categories, `TaskPlanner` and `StepCoach` (guide, ask, step check), `/v1/tasks` API | **Built** (commits `c722a14`, `180b3f4`) with Core and API tests. Genuine Muse plans (oil change, IP camera, restricted breaker panel → referral only, a site-aware technician job), typed questions, step checks and live step coaching are recorded in `docs/evidence/tasks/` |
| Web task flow: three-entry home, goal entry with push-to-talk, plan screen with safety acknowledgement, step runner (live outlines, spoken instruction, questions, voice commands, photo check), done screen, free-form help, ops Guided tasks list | **Built** (`ba64285`, `20d2a85`, `180b3f4`); checked in the browser pane at phone size. Camera and microphone were not available in that pane |
| Android task flow: home, new task, plan, step runner on `CameraScreen` (CameraX or ARCore), free-form help, Speaker and push-to-talk | **Built** (`3d999b6`, `180b3f4`); checked on the arm64 emulator (2D, no ARCore). Not yet: a typed question box on the step runner, `SpokenFeedbackGate` on task speech, distance coaching aimed at the step's main part, the offline queue for step-check photos, glasses on tasks |
| Technician task linked to a site-record inspection | **Built**; `TechnicianJourneyTests` drives plan → steps → linked inspection → fake fix denied → `VerifiedCompleteSimulated` → offline receipt verification with fake models and simulated diagnostics (`docs/evidence/tasks/technician-journey.md`). The task screens do not yet show the linked inspection's stage; no user-operated run with genuine models |
| Free-form help ("Just ask") | **Built** (`180b3f4`); genuine answers on web and the emulator, including a gas-line referral. Its restricted-work safeguard is a phrase match plus an appended referral, not structural rejection |
| Voice | Spoken guidance and push-to-talk code on both clients with one command grammar; **not yet exercised with a real microphone** or checked by ear on a phone |

## 2. Problem, users, and jobs to be done

### Problem

People increasingly attempt technical jobs themselves using scattered videos, forum posts and manuals that do not match their exact model. They lose their place, miss a caution, or cannot tell whether a step is done. Technicians face the same problem on unfamiliar equipment, and work-order systems record that someone pressed "complete" without proving the fault was resolved. A general chatbot can write steps, but it does not see the work, does not keep the user inside a safe plan, and will happily describe dangerous work.

### Users and jobs

**DIY user (primary, new):** An everyday technical person: comfortable with tools, not a professional. *When I take on a job like an oil change or mounting a camera, give me a plan for my exact situation, show me where things are, keep me safe, and let me ask questions without putting the tools down.*

**Field technician (primary):** Installs, repairs and on-site help, including the telecom/IT technician from v1.1. *Guide me through an install or repair, and when the job is on a site record, help me collect enough evidence to show the outcome was actually achieved.*

**Site supervisor or QA reviewer (secondary):** Reviews a compact record of what was planned, observed, tested and verified.

**Developer or judge (secondary):** Reproduces the product from the public repository with synthetic fixtures and fakes, and can tell genuine model calls from simulations.

**Later users (not hackathon scope):** Solar technicians, municipal inspectors, water utility teams, facilities managers, mobile infrastructure auditors, and shared household or fleet maintenance records.

### Principles

1. **Do not claim more than the evidence supports.** Unknown is a valid outcome; "can't tell" is a valid photo-check verdict.
2. **Plans are AI-written and say so.** Every plan carries the label "AI-written plan · check your owner's manual", a risk level, cautions and stop conditions. Nothing in the product implies a plan is manufacturer-approved.
3. **Code enforces safety; the prompt only asks for it.** The server validates every plan and every answer. Restricted work never gets hands-on steps, whatever the model returns.
4. **Coach inside the plan.** Live guidance and spoken answers stay within the accepted plan's current step; they do not start new jobs.
5. **The user decides; FieldProof verifies.** A step photo check is a suggestion. Only the FieldProof gate in Technician mode marks anything verified.
6. **Active perception beats passive classification.** Ask for a better view, outline the part, measure distance only where ARCore measured it.
7. **Structured, inspectable record.** Each plan, step, question, check and decision is an append-only event with an origin and a server timestamp.
8. **Only recognised text reaches LensQi.** Android prefers on-device recognition where available on Android 12+; system fallback and browser recognition may send audio to their providers. Web offers a disclosed typed alternative; Android task typing remains unfinished.
9. **Honest labelling.** Simulated checks are labelled simulated, mock devices are labelled mock, and nothing is described as run, tested or verified until it has been.
10. **New hackathon work must be obvious.** A clean standalone repository, dated commits, and a demo showing only event-built functionality.

## 3. Hackathon success definition

A successful submission demonstrates, with genuine Meta model calls:

1. **DIY plan:** A spoken or typed goal ("change the oil in my 2025 Civic") produces a labelled AI-written plan with risk level, tools, parts, per-step cautions and stop-if conditions.
2. **Safety boundary:** A restricted goal (for example an airbag or gas-appliance job) produces a prepare-and-observe plan with a professional referral, and a model plan containing hands-on steps for it is rejected by the server.
3. **Guided step with live AR:** The step runner outlines the step's parts with SAM 3.1 on a live camera, with ARCore distance on a supported Android phone and 2D tracking elsewhere, and speaks the instruction.
4. **Voice:** A push-to-talk question about what the camera sees is answered within the current step; "next step" by voice advances the task.
5. **Step check:** A photo check returns a suggested verdict ("looks done", "not yet", "can't tell") with a reason; the user confirms or overrides it.
6. **Technician closer:** A technician task linked to the `LAB-SW-01` site record continues into the existing FieldProof loop: fake fix rejected, real fix verified, receipt verified. The simulated diagnostic is labelled simulated.

### Pass/fail acceptance gates

- Genuine Muse Spark calls for at least two DIY plans (oil change, IP camera), one step check and one voice question, and genuine SAM 3.1 calls on step targets, recorded under `docs/evidence/tasks/` with model ids and digests.
- The restricted-category rule rejects hands-on steps in automated tests and in at least one genuine-call run.
- A step photo check never marks a step complete by itself; a technician task never shows "verified" without a FieldProof decision.
- The FieldProof acceptance gates from v1.1 still pass: an image alone never closes an inspection; a mocked or failed diagnostic never becomes an unlabelled live-verified result; stale or cross-asset evidence cannot cause completion; receipts verify.
- A user can open the public code, reproduce the simulated demonstration with fakes, and validate receipt hashes.

## 4. Scope boundaries and safety policy

### Safety policy (needed because DIY means hands-on instructions)

v1.1 said the agent never instructs hands-on work. v2.0 replaces that rule with this policy, enforced on the server.

**Every plan carries:**

- The label **"AI-written plan · check your owner's manual"** (`sourceNote`, set by the server, never by the model).
- A **risk level**: low, moderate or high.
- **Per-step cautions** by kind: hot parts, electrical, lifting or jacking, chemicals, working at height, sharp parts, stored pressure, other.
- **"Stop if" conditions** per step (for example "stop if you smell fuel", "stop if the jack stand will not sit level").
- A **`professionalRequired`** flag with its reason.

**Restricted categories.** For goals in these categories the server allows only **prepare-and-observe** steps (gather information, locate, photograph, shut off at a user-accessible control where safe, call a professional) plus a referral to a professional. Any hands-on step in such a plan is rejected:

| Category | Examples |
|---|---|
| Gas lines or gas appliances | Gas pipe, valve, meter, gas stove, furnace, water heater or dryer connection |
| Main electrical panel or live mains wiring | Breaker panel, service panel, subpanel, meter base, working on live wires |
| EV high-voltage systems | Traction or hybrid battery, orange high-voltage cables |
| Airbags / SRS | Airbag modules, seatbelt pretensioners, clock spring |
| Refrigerant | A/C recharge, refrigerant lines |
| Structural or load-bearing work | Load-bearing walls, joists, structural beams, foundation |

The category is decided from the user's own words as well as the model's label: if the goal or details name restricted work and the model's plan does not flag it, the plan is rejected. Restriction cannot be lowered by the client.

**Every model answer** (plans, live guidance, spoken answers, step-check reasons) passes the same checks: no links, bounded text length, and outline names (`lookFor`, focus phrases) must be plain 1–4 word noun phrases. Live guidance and answers stay inside the accepted plan's current step.

**Step checks** only suggest a verdict ("looks done", "not yet", "can't tell") with a reason. The user decides whether a step is complete. In Technician mode, verified completion comes only from the FieldProof gate.

**Starting a task** requires accepting its safety notes (`PlanAccepted(safetyAcknowledged: true)`).

**What the policy does not do:** it is not a safety certification, it does not guarantee the plan is correct for the user's vehicle or building, and it does not replace the owner's manual or local codes. The UI says so on the plan screen.

### P0 — Mandatory

- Guided-task core: `TaskSession` aggregate with append-only, idempotent events; plan schema and `TaskPlanValidator` with the safety policy; `TaskStore` on the existing JSONL event log and evidence file store.
- Muse Spark task planner (goal + details + manual photos → plan), step coach (live guidance and spoken questions within the current step) and step checker (photo → suggested verdict).
- `/v1/tasks` API (§9) with the existing guard, error and `Idempotency-Key` patterns.
- Web DIY flow at phone size: mode home, goal entry with mic, plan screen, step runner with live camera and SAM outlines, speech, push-to-talk with typed fallback, voice commands, step check, done screen.
- Android task screens on the same contract: step runner on CameraX or ARCore, speaker, push-to-talk voice input, voice commands, offline queue for step-check photos.
- Technician mode: install and repair tasks, linking a task to a site-record inspection, and the existing FieldProof loop (FR-01 to FR-09) unchanged.
- Fakes for every model call, automated tests, genuine-call evidence, README and demo video.

### P1 — Valuable only after P0 works

- Glasses (Wearables Device Access Toolkit) for tasks: spoken step guidance and voice questions through the existing glasses live service, Mock Device Kit labelled.
- Ops dashboard Tasks list for technician tasks and their linked inspections.
- Vendor knowledge packs cited inside technician task plans for site-record equipment.
- Manual-photo grounding shown per step ("from your manual, page N" only when the photo shows it).

### P2 — Stretch / post-hackathon

- Muse Voice Transcribe as an alternative to client-managed speech recognition; multilingual guidance.
- Saved household or fleet asset history ("last oil change 5,000 miles ago").
- Managed-switch SNMP/LLDP evidence, signed receipts, PDF export (v1.1 P2 items).
- Muse Image "what done looks like" illustrations, unmistakably labelled **conceptual**, never step-check or verification evidence.
- Production CMMS/ERP integration and field-service work orders.

### Explicitly out of scope

- Hands-on instructions for restricted categories; any claim of safety certification, code compliance or manufacturer approval.
- Automated electrical repair, software configuration writes, privileged shell access, public-network scanning, automatic work-order dispatch to real third parties.
- Server-side speech recognition or audio upload; continuous background recording of audio or video.
- Treating a step photo check, a model answer or a plan as verification of anything.
- Autonomous site-wide video surveillance, photorealistic 3D digital twins, and guaranteed sub-second model responses.

v1.1's non-goal "arbitrary device/brand support" is removed: DIY plans cover whatever the user names, inside the safety policy. **FieldProof verification** remains limited to declared site-record fixtures and approved diagnostics.

## 4A. Component reuse decision and competition gate (v1.1, unchanged)

### Governing rule: reuse is technically useful but not automatically competition-eligible

- Keep `Telli/LensIQ-Monorepo` **private**, including all of its earlier commits, migrations, configuration, historical imagery, and commercial plans.
- Create a **new unrelated Git repository** with new commit history during the permitted event build window. Never create a public fork/mirror of the private repository.
- **No copying of pre-event private source into the hackathon repository until** (1) the indie-track rules are clarified, (2) rights to redistribute the selected code are established, (3) code and transitive assets pass secret/licensing review, and (4) provenance is recorded.
- Where clarification is unavailable, reimplement tiny infrastructure utilities from their **behavioral specification**, not by copying the source. Use standard Android/.NET libraries. Do not describe pre-existing code as newly authored hackathon work.
- Existing LensIQ code may remain **context and prior-project evidence**, separate from the hackathon demo.

### Module decisions relevant to v2.0

The module-by-module decisions for the private LensIQ code are recorded in `docs/COMPONENT_REUSE.md`. Those touching v2.0:

| Private module | Decision | v2.0 consequence |
|---|---|---|
| `TtsService.kt` (offline-preferred TTS) | Reimplement with Android `TextToSpeech` unless reuse is approved | The task `Speaker` is a fresh class gated by the existing `SpokenFeedbackGate` |
| `AudioCaptureService.kt` + `AdaptiveVad.kt` | Conditional P1 reuse only with rights | Not needed: push-to-talk uses Android `SpeechRecognizer` and the browser Web Speech API |
| `ArCapabilityChecker.kt`, `ar/` | Reimplemented as a clean FieldOps ARCore adapter | The step runner reuses the existing FieldOps ARCore renderer unchanged |
| `PermissionHandler.kt` | Rebuild | Microphone permission is requested only when the user first presses push-to-talk |
| Tourism explain/story services, landmark ranking, confidence scoring | Reject | Plans and step checks use explicit validation, not combined confidence |

### Required provenance for any approved copied file

Create `docs/PRIOR_ART.md` with original repository/path, upstream commit SHA, original creation date if known, copied-line origin, license/IP approval, adaptation description, and a declaration that inherited code is **excluded from judged new functionality**. Preserve original copyright/notice obligations, but **do not embed private source links as a runtime dependency**. If ownership or organizer permission is unresolved, do not copy.

## 5. Primary user journeys (the three-minute hero demo)

### Product shape

- **Home** shows three entries: **"Do it yourself"** (a goal box with a mic button), **"Just ask"** (straight to the camera for free-form help) and **"Technician"** (jobs, site records, "verify a repair"). Recent tasks appear under them.
- **Plan screen** before starting: steps, tools and parts, time estimate, risk level, safety warnings and "stop and get a pro if…" conditions, under the label "AI-written plan · check your owner's manual". Starting means accepting the safety notes.
- **Step screen**, one step at a time: live camera with AR outlines of the parts the step needs, ARCore distance where the phone has ARCore; the step instruction, spoken aloud; mic for questions about what the camera sees; voice commands; "Check this step" photo; Next and Back.
- **Done screen:** a summary of the task record. Technician tasks tied to a site record continue into the existing verify-and-receipt flow.

### Storyboard

**A. DIY goal (0:00–0:25)** — On the phone, the user taps the mic and says "change the oil in my 2025 Civic", adding a photo of the maintenance page of the owner's manual. The plan screen shows the AI-written label, moderate risk, tools (socket, drain pan, funnel), parts (oil, filter, crush washer), cautions (hot oil, lifting) and "stop if the car is not on level ground with stands".

**B. Guided step with AR (0:25–1:05)** — The user accepts the safety notes and starts. Step 3, "Locate the drain plug", is spoken. The live camera outlines the "oil drain plug" and "oil pan"; on the ARCore phone, the plug shows "≈ 38 cm" and coaching "move about 15 cm closer" appears only from measured depth. Without ARCore the outlines track in 2D and no distance is shown.

**C. Voice question and command (1:05–1:30)** — The user asks "is that the drain plug or the transmission plug?" The answer, limited to the current step and what the camera sees, is spoken. The user says "next step".

**D. Step check (1:30–1:50)** — "Check this step": the photo check suggests "not yet — the crush washer is still on the old plug" with a reason; the user decides.

**E. Safety boundary (1:50–2:10)** — A second goal, "replace the airbag in my steering wheel", produces a prepare-and-observe plan with a professional referral. A trace shows the server rejecting a model plan that contained hands-on steps.

**F. Technician closer (2:10–2:45)** — A technician task "restore the link to Lab Node A" is linked to site record `LAB-SW-01`. The task's steps guide the work; the fake fix is denied by the fixture-approved check, the real fix passes, and the receipt shows **Verified Complete (simulated)**, labelled as such.

**G. Engineering proof (2:45–3:00)** — Show the task event log, the plan validator's rejection, genuine Muse and SAM call records, and the FieldProof receipt verifier. Clearly identify simulated versus live checks and work created during the event.

## 6. Product requirements and acceptance criteria

### Guided-task requirements (v2.0)

#### GT-01 — Mode home and task creation (P0)

- Home offers DIY and Technician entries and lists recent tasks for each mode.
- A task is created from a mode, a goal (1–200 characters), optional details, and up to a bounded number of manual photos. Goal text can come from push-to-talk; the server only ever receives text.
- Technician tasks can optionally carry a site link (fixture and asset ID from the site-record catalog); unknown fixture or asset IDs are rejected.

**Acceptance:** Creating a task returns a task with a plan or a typed error; a site link with an unknown asset is rejected with no task created.

#### GT-02 — AI-written plan (P0)

- Muse Spark writes the plan under a strict JSON schema: title, summary, risk level, time estimate, restricted category, `professionalRequired` with reason, tools, parts, and steps (id, title, instruction, hands-on flag, up to three `lookFor` noun phrases, cautions, stop-if conditions, optional photo check with "what done looks like").
- Manual photos, when attached, are sent with the goal as grounding; the plan does not claim to quote a manual it was not shown.
- The server sets `sourceNote` to "AI-written plan · check your owner's manual"; the model cannot change it.

**Acceptance:** Invalid schema, empty steps, over-long text, links, or non-noun-phrase `lookFor` values fail validation and no plan is stored.

#### GT-03 — Server-enforced safety (P0)

- `TaskPlanValidator` applies the policy in §4: bounds, no links, plain noun phrases, cautions and stop-if present where required, restricted-category rule, `professionalRequired` set for restricted plans.
- Restricted classification uses both the model's label and the user's goal and details; a mismatch is rejected.
- Hands-on steps in a restricted plan are rejected (`unsafe_plan`).

**Acceptance:** Automated tests cover each restricted category, a model that omits the restriction label, and a restricted plan with one hands-on step; all are rejected.

#### GT-04 — Plan acceptance and step flow (P0)

- A task cannot start a step before `PlanAccepted(safetyAcknowledged: true)`. *(As built: acknowledgement is required when the plan's risk is above Low, a professional is required, or any step has a caution; a Low-risk plan with no cautions can start without it. Accepting starts the first step.)*
- Steps can be started, completed or skipped; Back returns to a previous step without deleting events. A task ends `Completed` or `Abandoned`.
- Every command is idempotent by `Idempotency-Key`; an out-of-order command returns a typed conflict.

**Acceptance:** Replaying the event log reproduces the same task state; a duplicate command returns the original result.

#### GT-05 — Live scan with AR in the step runner (P0)

- The step runner reuses the existing live camera, 2D tracker and frame limits. Detection outlines the current step's `lookFor` phrases first, then focus phrases from the latest guidance. Concept text never comes from the client.
- On Android phones with Google Play Services for AR, the existing ARCore renderer anchors outlined parts only where ARCore measured depth, and distance coaching uses the step's main `lookFor`. Elsewhere (browsers, phones without ARCore, glasses) tracking is 2D and no distance is shown.
- Live guidance comes from the step coach and stays inside the current step.

**Acceptance:** Detection requests for a task use the step's `lookFor`; no distance appears for a part without measured depth.

#### GT-06 — Voice (P0)

- **Speak guidance:** the step instruction and new guidance are spoken through text-to-speech, rate-limited by the existing spoken-feedback gate (no repeats of unchanged advice).
- **Questions out loud:** push-to-talk records speech on the device, converts it to text with Android `SpeechRecognizer` (on-device recognizer where available on Android 12+, otherwise the system service) or the browser Web Speech API, and sends the text plus an optional camera frame to `/ask`. Web offers a typed box. Android task typed fallback is still a requirement, not implemented.
- **Voice commands:** a shared grammar on web and Android recognises next, back, repeat, check (including "take a photo"), skip, mute and unmute; the whole utterance must be a command, and anything else is treated as a question. *(As built; "pause" from the original plan is not a command.)*
- No audio is sent to or stored by LensQi; browser/system recognition providers may process audio.

**Acceptance:** Command-grammar tests pass on both clients with the same cases; network traces show only text in `/ask`.

#### GT-07 — Questions within the step (P0)

- `/ask` answers from the goal, the accepted plan, the current step and the optional frame; answers pass the §4 output checks and are recorded as `QuestionAnswered`.
- A question asking for restricted hands-on work gets a referral, not instructions.

**Acceptance:** Answers longer than the bound or containing links are rejected; the question and answer appear in the task event log.

#### GT-08 — Step photo check (P0)

- "Check this step" uploads a full-resolution photo; Muse compares it with the step's "what done looks like" and returns `LooksDone`, `NotYet` or `CantTell` with a reason, recorded as `StepChecked`.
- The verdict is shown as a suggestion. Only the user's Complete or Skip changes the step.
- On Android, step-check photos queue offline and sync with the existing command queue.

**Acceptance:** A `LooksDone` verdict never completes a step by itself; a step check on a technician task never produces a FieldProof decision.

#### GT-09 — Technician mode and site-record link (P0)

- Technician tasks cover installs, repairs and on-site help with the same plan, step and voice features.
- `link-inspection` creates or links an `InspectionSession` for the task's site asset (`InspectionLinked`). The existing `/v1/inspections/*` flow, verification gate and receipt are unchanged.
- The task's done screen shows the FieldProof outcome only when one exists, with its live or simulated label.

**Acceptance:** A technician task linked to `LAB-SW-01` reaches a FieldProof decision through the existing endpoints; an unlinked task shows no verification label.

#### GT-10 — Honest labelling (P0)

- Plans carry the AI-written label; step checks say "suggestion"; simulated diagnostics say simulated; mock glasses say mock; ARCore distance appears only from measured depth.
- Recorded evidence states what actually ran: model ids, digests, device, and whether a check was simulated.

**Acceptance:** No screen or document uses "verified" for anything other than a FieldProof decision.

### Technician-mode FieldProof requirements (v1.1, unchanged)

FR-01 to FR-10 are the FieldProof inspection loop that Technician mode uses as its closer. Their full text is in `.kiro/specs/lensqi-fieldops/requirements.md` (R1–R10) and was in PRD v1.1; the identifiers are kept because code cites them.

| ID | Requirement | Acceptance |
|---|---|---|
| FR-01 | Asset fixture and expected twin: known fixtures only, versioned and digest-bound; model output cannot create authoritative IDs | Malformed fixture fails; asset and digest appear in the receipt |
| FR-02 | Visual capture and quality: full-resolution photo with source provenance (`phone`, `glasses`, `mock`); blur/dark/overexposure are quality signals | Poor capture yields a targeted recapture request, not a diagnosis |
| FR-03 | SAM 3.1 segmentation and ROI: one concrete noun per request; honest no-match; original-image coordinates; ARCore anchors only from measured depth, otherwise 2D and no distance | Overlays align; crops keep a transform |
| FR-04 | Muse hypothesis and uncertainty: `Observed`, `Suspected`, `Unknown`, `Conflicting`; at most two extra captures per loop | Occlusion produces unknown plus a request; invalid IDs fail |
| FR-05 | Evidence acquisition loop: explicit recapture, decline or clarification; immutable evidence; idempotent retries | Missing mandatory evidence blocks confirmation |
| FR-06 | Read-only diagnostics: fixture-allowlisted probes with `LiveLocal`, `Simulated` or `RecordedReplay` provenance; no model-supplied targets | Unapproved targets rejected; simulator labelled |
| FR-07 | Repair claim: a claim is an event, not success; it invalidates earlier post-repair evidence | "Repaired" without fresh checks cannot complete |
| FR-08 | Dual-proof verification: fresh post-repair photo, reviewed checklist, acknowledgement and fresh passing diagnostic; simulated passes say `VerifiedCompleteSimulated` | Stale, cross-asset, simulated-as-live and contradiction cases rejected |
| FR-09 | FieldProof receipt: canonical JSON + HTML, per-artifact SHA-256, offline verifier | One-byte change detected |
| FR-10 | Wearable/hands-free (P1): labelled glasses or mock source through `IFrameSource` | Phone path works without glasses |

## 7. State machines and evidence policy

### Task session (v2.0)

| Phase | Entry condition | Allowed progression |
|---|---|---|
| `Planned` | `TaskCreated` and `PlanProposed` with a validated plan | `PlanAccepted(safetyAcknowledged: true)` → `InProgress`; `TaskAbandoned` |
| `InProgress` | Plan accepted | `StepStarted`, `QuestionAnswered`, `StepChecked`, `StepCompleted`, `StepSkipped`, `InspectionLinked` (technician only); `TaskCompleted`; `TaskAbandoned` |
| `Completed` | User finishes the task | Terminal for the task; a linked inspection keeps its own state machine |
| `Abandoned` | User stops the task | Terminal |

A task's phase never implies verification. A step's completion is the user's decision recorded as `StepCompleted`; a `StepChecked` verdict is an annotation. Model output cannot append events directly; only `TaskSession` commands do.

### FieldProof inspection (v1.1, the technician closer)

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

**Not all faults need initial visual+telemetry agreement:** physical and telemetry evidence can conflict, in which case record the discrepancy and request a targeted diagnostic or review. Do not force a false `FaultConfirmed` to move the demo forward.

### Evidence structure

Every inspection evidence item has `id`, `inspectionId`, `assetId`, `kind` (`photo`, `mask`, `crop`, `model_observation`, `technician_confirmation`, `diagnostic`, `repair_claim`), `source` (`phone`, `glasses`, `mock`, `meta_model_api`, `live_lab_probe`, `simulator`), `observedAt`, `receivedAt`, `artifactSha256` when binary, `fixtureVersion`, and optionally `relatedEvidenceIds`. Task photos (manual, step check, question frame) are stored in the same evidence file store under the task id with a purpose and digest; they are never FieldProof evidence unless captured through a linked inspection. Model interpretation is **an annotation** of a photo, not a substitute for it.

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

This is an **example policy**; the implemented gate is `VerificationPolicyEvaluator` (`fieldproof-policy/1.2`, which adds change evidence on post-repair photos). Do **not** use uncalibrated model confidence thresholds as the decisive proof rule, and do not let a task step check feed this gate.

## 8. Site records and the expected twin

Technician tasks tied to a site record use the versioned expected twin. The canonical fixture is `fixtures/telecom-lab/expected-twin.json` (synthetic `LAB-RACK-01`: unmanaged lab switch `LAB-SW-01`, test endpoint `LAB-NODE-A`, yellow cable `YELLOW-A` expected on port P2, and the HTTP health diagnostic `NODE-A-HEALTH`), validated by its JSON Schema and by cross-item invariants in the loader.

- `segmentationPrompt` is the one concrete noun SAM is asked for when a visual requirement is checked; it also names the target region for change evidence (VK-R12).
- `targetAlias` resolves only through trusted server-side configuration. The model cannot supply a URL or host.
- `fromPortLabel: P2` is an **expected configuration fact**, not proof that a photo can read P2. Endpoint health verifies reachability, not port identity.
- A vendor-knowledge binding's `approvedDiagnostics` must be a subset of the fixture's diagnostics.

DIY tasks have no expected twin; their plans are AI-written and labelled as such.

## 9. Technical architecture

```text
Web (React, served by the API)          Android (Kotlin/Compose)
  /m mode home, task plan, step runner    TaskHome / NewTask / Plan / Step / Done
  live camera + 2D tracker                CameraX or ARCore + 2D tracker
  Web Speech push-to-talk, TTS            SpeechRecognizer, TextToSpeech
  command grammar                         command grammar, offline command queue
            │  text + frames + photos over HTTPS (no audio)  │
            └───────────────────────┬──────────────────────────┘
                                    ▼
                         ASP.NET Core 10 API
     /v1/tasks (plan, steps, ask, check, live)   /v1/inspections (FieldProof)
                │                                         │
     Core/Tasks: TaskSession, events,          Core/Inspection: state machine,
     TaskPlanValidator (safety policy)          verification gate, receipts
                │                                         │
     Meta/Tasks: TaskPlanner, StepCoach,       Meta: Muse assessment, SAM,
     StepChecker                                knowledge grounding
                └──────────────┬──────────────────────────┘
                               ▼
        Muse Spark 1.3 (chat/completions)    SAM 3.1 (responses)   Read-only probes
                               │
           TaskStore + InspectionStore (append-only JSONL), EvidenceFileStore
```

### Repository layout (v2.0 additions marked)

```text
lensqi-fieldops/
├── apps/android/                    # Kotlin/Compose; ui/task/* and voice/* are new
├── apps/web/                        # React; task routes, tasks.ts, voice.ts are new
├── src/
│   ├── LensQi.FieldOps.Api/          # Features/Tasks/TaskEndpoints.cs is new
│   ├── LensQi.FieldOps.Core/         # Tasks/ is new (pure, like Inspection/)
│   ├── LensQi.FieldOps.Meta/         # Tasks/ is new (TaskPlanner, StepCoach, StepChecker)
│   └── LensQi.FieldOps.Diagnostics/  # Allowlisted probes + simulator
├── tools/sam-parser/                # Python sidecar around meta-sam-parser
├── tools/LensQi.FieldOps.StageOneGate/, LensQi.FieldOps.Eval/, LensQi.FieldOps.VerifyReceipt/
├── tests/                           # Core, API, Meta contract, integration
├── fixtures/telecom-lab/, fixtures/vendor-knowledge/
├── .kiro/specs/guided-tasks/        # New: requirements, design, tasks
├── .kiro/specs/lensqi-fieldops/, .kiro/specs/vendor-knowledge-and-diagnostics/
└── docs/                            # evidence/tasks/ is new
```

**Dependency boundary:** The public project must run from a clean checkout without cloning, importing, reaching into or authenticating against the private LensIQ repository. Use `LensQi.FieldOps` namespaces and the `com.lensqi.fieldops` Android package.

**Module responsibilities:** Core owns task and inspection transitions, the plan validator and proof gates with no AI dependencies; Meta owns provider DTOs, prompts, schemas and bounded requests; Diagnostics owns probes; the API coordinates state, guards and artifact IO; clients capture, speak, listen and display. No model output can assign authoritative IDs, append events directly, or call the verification gate.

**SAM parsing decision (unchanged):** SAM 3.1 returns special-token mask/box text on `POST /v1/responses`; the Python `meta-sam-parser` sidecar emits typed JSON (half-open boxes in original-image pixels, COCO RLE masks, optional confidence). Model grammar parsing never enters the C# verification layer.

### Backend

- .NET 10 minimal APIs; explicit Meta HTTP adapters (`MuseSparkChatClient` with strict JSON schema, `SamSegmentationClient`).
- Vertical slices: `Tasks` (new), `Inspections`, `LiveVision`, `Segmentation`, `Receipts`, `Simulation`, `VendorKnowledge`.
- Append-only JSONL event logs per task and per inspection with a shared append-and-replay helper; evidence files immutable by id with SHA-256.
- `HttpClientFactory`, cancellations, timeouts, safe retries, rate limits, image limits, bounded model-call budgets (`ModelCallLimits`, `LiveVisionGate`).
- Store model id, prompt/schema version, source-image digest and correlation id. Never log API keys, frames or private image data.

### Web

- Routes: `/m` mode home, `/m/new?mode=diy|tech`, `/m/t/:id` plan, `/m/t/:id/step/:stepId` step runner, `/m/t/:id/done`. Technician keeps `/m/start`, `/m/i/*` and `/ops/*`.
- `use-live-assist.ts` is parameterized by endpoint base so it serves tasks and inspections.

### Android

- Kotlin, Jetpack Compose, CameraX and ARCore, ViewModel/StateFlow, Retrofit, Room command queue with WorkManager sync.
- Screens: `TaskHomeScreen`, `NewTaskScreen`, `PlanScreen`, `StepScreen`, `TaskDoneScreen`; technician routes (`inspection/{id}`, review, receipt) unchanged.
- Overlay maps normalized boxes onto the displayed image after rotation, crop, mirroring and letterboxing.
- `RECORD_AUDIO` is requested on first push-to-talk only.

### Model responsibilities

- **Muse Spark 1.3 (P0):** write task plans (strict schema, low reasoning), coach the current step and answer questions from a frame, suggest step-check verdicts, and in Technician mode assess inspection photos and propose evidence requests.
- **SAM 3.1 (P0):** outline one concrete concept per request (a step's `lookFor`, a guidance focus phrase, or a fixture's `segmentationPrompt`). It does not judge whether a step is done or certify anything.
- **Muse Voice Transcribe (P2):** not used in P0; speech recognition uses the client platform APIs, which may use a provider service.
- **Muse Image (stretch):** conceptual illustration only, labelled as generated.
- **Muse Code:** development tool only.

### Candidate API endpoints

| Endpoint | Purpose |
|---|---|
| `POST /v1/tasks` | Create a task from `{mode, goal, details, siteLink?}` plus optional multipart manual photos; plan it |
| `GET /v1/tasks`, `GET /v1/tasks/{id}` | Task list and detail (state, plan, steps, linked inspection) |
| `GET /v1/tasks/{id}/events`, `GET /v1/tasks/{id}/artifacts/{name}` | Event log and stored photos |
| `POST /v1/tasks/{id}/accept` | Accept the plan with `safetyAcknowledged: true` |
| `POST /v1/tasks/{id}/steps/{stepId}:start` / `:complete` / `:skip` | Step transitions |
| `POST /v1/tasks/{id}/steps/{stepId}/check` | Multipart step photo check → suggested verdict |
| `POST /v1/tasks/{id}/ask` | Question text plus optional frame → answer within the current step |
| `POST /v1/tasks/{id}/live/detect`, `/live/guide` | Live outlines (step `lookFor` first) and step-bound guidance |
| `POST /v1/tasks/{id}/link-inspection` | Create or link a FieldProof inspection for the task's site asset |
| `GET /v1/fixtures` | Approved site-record fixtures / expected-state versions |
| `POST /v1/inspections` and the `/v1/inspections/{id}/*` routes | The FieldProof loop, unchanged: observations, investigate, segment, live, diagnostics, repair claims, verify, receipt |

### Core model response contract (inspection, illustrative)

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

Enum values are the PascalCase names from the parent `design.md` (`ObservationStatus`, `ProposedNextAction`). The task plan contract is in `.kiro/specs/guided-tasks/design.md`. Enforce exact enums, `additionalProperties: false`, required keys, known IDs, max text sizes and safe allowlists. Empty, malformed or ungrounded responses become a typed error or `Inconclusive`, never stored as a plan or as verified evidence.

## 10. Data, security, and privacy

- **Camera:** explicit capture and an explicit Start for live assist; the Start control explains that sampled frames go to AI. Live frames are not stored as evidence; provider processing is subject to the provider's own data handling.
- **Microphone and speech:** the microphone opens only while push-to-talk is held or active; Android `SpeechRecognizer` prefers the on-device recognizer where available on Android 12+, otherwise the system service; web uses the Web Speech API. These services may send audio to their providers. Only the recognised text reaches the server. Browsers whose Web Speech implementation sends audio to the browser vendor's service are disclosed in the UI; the typed fallback avoids it. No audio is uploaded to or stored by LensQi.
- **Manual photos:** sent to Muse only to ground the plan; stored under the task id; users are told not to photograph personal documents.
- **Permissions:** camera and microphone only when needed; optional glasses integration follows Meta registration and device access permissions.
- **Network safety:** diagnostics cannot accept URLs or hosts from the model or request body; aliases resolve from an operator-controlled allowlist.
- **Secrets:** model keys in environment or secret manager only; no keys in the APK, fixtures, demo footage or logs.
- **Data rights:** publish only original or appropriately licensed photos and fixture descriptions; no copied manuals.
- **Retention:** demo default seven days for task and inspection artifacts with a deterministic purge.
- **Safety:** the user remains responsible for tools, vehicles, energized equipment and physical work. Plans are not safety certification, code compliance or manufacturer approval.
- **Evidence integrity:** SHA-256 manifests detect changes to an issued receipt bundle, not fabrication or wholesale substitution.

## 11. Evaluation and benchmarks

**FieldProof (kept):** the 25-scenario labelled evaluation (4 held out) over synthetic renders, baseline single-look versus full loop, false completions, change evidence and receipt verification (`tools/LensQi.FieldOps.Eval`, latest report `docs/evaluation/eval-20261028T061752Z.md`).

**Guided tasks (new):**

| Set | Target examples | Measure |
|---|---:|---|
| DIY goals across car, home network, mounting and appliance maintenance | 10 | Schema-valid plan rate, presence of cautions and stop-if per step, `lookFor` validity |
| Restricted goals, one or more per category, including indirect wording | 8 | Restricted classification, zero hands-on steps accepted |
| Step-check photos (done, not done, ambiguous) | 9 | Verdict agreement with labels; "can't tell" on ambiguous photos |
| Spoken questions on recorded frames | 6 | Answers within step, length and link checks pass |

**Targets (goals, not measured claims):** 0 restricted plans accepted with hands-on steps; 0 step checks that complete a step; 0 FieldProof false completions; median plan latency and step-check latency measured and reported.

## 12. Delivery plan — October 28 to November 4

| Date | Build outcome | Go/no-go gate |
|---|---|---|
| **Oct 28** | Approved re-architecture plan; PRD and guided-tasks spec written; Core task contracts started | Plan and docs agree |
| **Oct 29** | Core `TaskSession`, plan schema and validator, safety rules, `TaskStore`, task endpoints, `TaskPlanner` / `StepCoach` / `StepChecker`, all with fakes-based tests | API contract frozen at end of day |
| **Oct 30** | Web DIY flow end to end: mode home, goal entry, plan, step runner with live AR, speech, push-to-talk, commands, step check | DIY flow works at phone size with fakes and one genuine plan |
| **Oct 31** | Android task screens on the frozen contract: step runner on CameraX or ARCore, Speaker, VoiceInput, commands, queue and glasses generalization | Unit tests and debug APK; emulator run |
| **Nov 1** | Technician mode: install tasks, task-to-inspection link for FieldProof, home reorganization, ops Tasks list | Linked technician task reaches a FieldProof decision |
| **Nov 2** | Genuine-call evidence (oil-change and IP-camera plans, a step check, a voice question); phone run on the S23 Ultra; docs updated to match what ran | Evidence recorded under `docs/evidence/tasks/` |
| **Nov 3–4** | Buffer, demo polish, video, final checks, submit | Submission before 6 PM Pacific on Nov 4 |

Web (Oct 30) and Android (Oct 31) can run in parallel once the contract is frozen.

**Progress at October 29:** the Oct 29–Nov 1 build items landed early, on October 28 (backend, web, Android, technician link, ops list), plus free-form help. Open before Nov 2: the Android gaps listed in §1, showing a linked inspection's stage on the task screens, the offline step-photo queue, a genuine spoken question and SAM outlines on a real step part, and the S23 Ultra phone run of the task flow. Status per task: `.kiro/specs/guided-tasks/tasks.md`.

**Kill switches:** If the task API is not frozen by end of Oct 29, cut Android task screens to the step runner only and keep the web flow as the guaranteed surface. If ARCore anchors do not appear on real parts during the phone run, demo 2D tracking and state that distance was not shown. If voice recognition is unreliable on a device, keep spoken guidance and the typed question box, and drop voice commands from the video. If the technician link is not working on Nov 1, demo the existing FieldProof loop separately and do not claim the link. The FieldProof loop must stay green throughout; no task work may weaken it.

## 13. Public repository and hackathon attribution

Repository: **`lensqi-fieldops`**, initialized from scratch during the build window. Include source for the demo, original or licensed fixture media, README, architecture overview, `.env.example`, reproducible local setup, and `docs/HACKATHON_CONTRIBUTIONS.md` identifying new work commit by commit. Do **not** publish the private LensIQ monorepo, its history, production `.env` files, private roadmap or archived images. The pre-event baseline `593f467…` must not be counted as new work. Before publishing: no private Git objects, no secrets, no licensed or private photographs, dependency attribution checked, runnable setup without a private backend, and the new-work diff obvious in the demo and README.

## 14. Known risks and mitigations

| Risk | Mitigation |
|---|---|
| A plan gives wrong or dangerous instructions | AI-written label, owner's manual prompt, per-step cautions and stop-if, server-enforced restricted categories, risk level shown before start |
| Restricted work worded indirectly slips past classification | Classify the user's words as well as the model label; reject mismatches; evaluation set with indirect wording |
| User treats a step check as approval | Verdicts shown as suggestions; only the user completes steps; "verified" reserved for FieldProof |
| SAM finds no part for a step's `lookFor` | Honest "no outline" state; guidance asks for a better view; no synthetic outline |
| ARCore anchors not produced on real parts | Distance appears only from measured depth; 2D fallback; the phone run is a gate, not an assumption |
| Speech recognition unavailable or inaccurate | Typed fallback; commands confirmed on screen; unrecognised speech treated as a question |
| Model confidently invents a defect or a step result | Typed outputs, bounded text, "can't tell" verdict, FieldProof gate for technician outcomes |
| Re-architecture breaks the FieldProof loop | Inspection endpoints and Core unchanged; clean-room check and FieldProof tests stay in CI |
| Meta API credit exhaustion or downtime | Fakes for tests, fixed usage budget, graceful typed errors on live failures |
| Scope overrun before the deadline | Day-by-day gates and kill switches in §12 |

## 15. Definition of done

- [ ] DIY task from spoken or typed goal to done screen, on web at phone size and on Android, with genuine Muse plans and SAM outlines.
- [ ] Restricted-category plans rejected when they contain hands-on steps, in tests and in one genuine-call run.
- [ ] Step runner with live AR: SAM outlines of step parts; ARCore distance only from measured depth; 2D elsewhere.
- [ ] Voice: spoken guidance, push-to-talk question answered within the step, voice commands; no audio sent to the LensQi server.
- [ ] Step photo check suggests a verdict; the user decides.
- [ ] Technician task linked to a site record reaches a FieldProof decision; fake fix rejected; receipt verifies.
- [ ] Genuine-call evidence under `docs/evidence/tasks/` with model ids and digests; every claim matches what ran.
- [ ] Clean checkout builds and tests pass with no private dependencies; three-minute video shows only event-built features.

## 16. Authoritative implementation references

- Meta Model API overview and model IDs (`muse-spark-1.3`, `sam-3.1`): https://dev.meta.ai/docs/models
- Meta Model API overview: https://dev.meta.ai/docs/overview
- Muse structured outputs: https://dev.meta.ai/docs/cookbook/structured-output
- Muse Spark tool calling (reference only; this design uses structured output, not tool calls): https://dev.meta.ai/docs/cookbook/tool-function-calling
- SAM 3.1 segmentation: https://dev.meta.ai/docs/sam/overview
- SAM prompts/limitations: https://dev.meta.ai/docs/sam/segmenting
- SAM parser libraries: https://dev.meta.ai/docs/sam/client-libraries
- ARCore (Depth API, anchors, hit tests): https://developers.google.com/ar
- Android `SpeechRecognizer` (on-device recognizer, Android 12+): https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android `TextToSpeech`: https://developer.android.com/reference/android/speech/tts/TextToSpeech
- Web Speech API: https://developer.mozilla.org/docs/Web/API/Web_Speech_API
- Wearables Android SDK (developer preview; Mock Device Kit; SDK 1.0.1): https://github.com/facebook/meta-wearables-dat-android
- Hackathon participant guide: https://cerebralvalley.ai/e/meta-ai-developer-hackathon/details

**Scope statement:** This PRD is an engineering plan, not a claim that the proposed features have been implemented, field-tested, or certified. Plans are AI-written guidance, not manufacturer instructions; FieldProof verification demonstrates specified test outcomes for a controlled fixture, not general physical safety or repair correctness.

## 17. FieldProof Engine contract and trust invariants (v1.1, unchanged)

The name **FieldProof Engine** denotes a bounded verification system, not a universal repair certificate. In v2.0 it is reached only from Technician mode through a site-record inspection.

### Typed boundary rules

- **Model observation:** Describes possible visual features, missing information and next evidence request. It cannot mark a port connected, a fault confirmed or work completed without relevant authoritative evidence.
- **Photo/mask/crop:** Immutable media artifact with original pixels or recorded transform. A mask is an interpretation of a region; a crop is not additional independent evidence.
- **Expected state:** Versioned operator-authored fixture. The model may refer only to declared asset IDs, port aliases and components; unknown IDs are rejected.
- **Diagnostic result:** Produced by an allowlisted tool with `LiveLocal`, `Simulated` or `RecordedReplay` source, fixture binding, timestamps and explicit `Passed`/`Failed`/`Unavailable`. Simulated output cannot be labelled live verification.
- **Repair event:** Human-declared action. It resets post-repair evidence freshness and cannot become a completion assertion on its own.
- **Proof decision:** Deterministic backend calculation with a versioned policy, pass/fail reasons, and receipt. Every completed inspection must have a human-reviewed visual checklist and qualifying post-repair diagnostic.
- **Task step check (v2.0):** A suggestion attached to a task step. It is not inspection evidence and never reaches the proof decision.

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

Keep the workflow stage (`InspectionStage`) distinct from verification provenance (`VerificationOutcomeKind`). A task's `Completed` phase is neither.

## 18. Guided-task contract and trust invariants (v2.0)

### Typed boundary rules

- **Goal and details:** User text. Used to plan and to classify restricted work; never executed.
- **Plan:** Model-written, schema-validated, safety-validated, labelled by the server. Accepted only by the user, with safety acknowledgement.
- **Step instruction, live guidance and answers:** Model text bound to the current step, bounded, link-free; outline names are plain noun phrases.
- **Outline:** SAM geometry for a server-chosen phrase. An outline is where something probably is, not proof that a step is done.
- **Distance:** ARCore measurement from depth at the anchor; absent when ARCore measured nothing.
- **Step check:** Suggested verdict and reason. The user's complete or skip is the decision.
- **Voice:** Recognised text from the device. Commands are parsed on the device with a shared grammar; everything else is a question.

### Required negative tests

1. Restricted goal, model omits the restriction → **plan rejected**.
2. Restricted plan with any hands-on step → **plan rejected (`unsafe_plan`)**.
3. Plan with a link, over-long text or a non-noun-phrase `lookFor` → **plan rejected**.
4. Step started before safety acknowledgement → **`safety_not_acknowledged`**.
5. `LooksDone` step check → **step not completed**.
6. Duplicate command with the same `Idempotency-Key` → **same result, no duplicate event**.
7. Live detect on a task → **concepts come from the step's `lookFor`, never from the client**.
8. Answer containing a link or exceeding the bound → **rejected, not shown**.
9. Technician task without a linked inspection → **no verification label**.
10. Event-log replay → **identical task state**.

## 19. Open decisions for the hackathon (v2.0)

| Decision | Default | Change trigger |
|---|---|---|
| Product shape | **Decided Oct 28: two modes on one guided-task core; FieldProof kept as the technician closer** | — |
| DIY instruction source | **Decided Oct 28: AI-written plans, clearly labelled, with manual photos as optional grounding** | Licensed manufacturer data becomes available (post-hackathon) |
| Voice | **Decided Oct 28: speak guidance, push-to-talk questions, voice commands; client-platform speech recognition, on-device where available** | Recognition fails on the demo phone → use web typed questions; Android task typing remains unfinished |
| Restricted categories | Gas, mains electrical, EV high voltage, airbags/SRS, refrigerant, structural | Add categories when evaluation shows gaps; never remove one for the demo |
| Indie-track private-code reuse | Fresh implementation, no code copied | Written clarification plus rights approval |
| Repo visibility | New repo public | Competition rules change |
| Persistence | Append-only JSONL event logs and local evidence files | Persistence needs exceed a single-node demo |
| Test target (FieldProof) | Owned isolated lab fixture + allowlisted HTTP health; simulator labelled | A live target is demonstrably available and controlled |
| Wearable | P1; Mock Device Kit labelled | Real glasses available and tested |
| Spatial coordinates | ARCore anchors and distance only from measured depth on supported phones; 2D elsewhere | — |
| SAM decoder | Python `meta-sam-parser` sidecar emitting typed JSON | Native C# parser passes fidelity tests against the sidecar |
| P0 UI surface | **Browser app served by the API is the guaranteed surface; Android task screens are built in parallel on the frozen contract** and claimed only with evidence in `docs/evidence/android/` and `docs/evidence/tasks/` | If an Android gate slips, the video and README drop that Android claim; nothing is faked |

**Submission boundary:** This PRD and the accompanying Kiro documents are planning artifacts. They do not constitute a working implementation or evidence that a live Meta API call, headset connection, ARCore anchor, voice feature, hardware diagnostic or evaluation has passed beyond what §1 lists as existing today.
