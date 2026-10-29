# LensQi — Guided Tasks
## Kiro Implementation Tasks

**Spec:** `guided-tasks` · `requirements.md` (GT-R01–GT-R13), `design.md`  
**Dependency:** The FieldProof inspection loop (`../lensqi-fieldops/tasks.md`) stays green throughout; no task here may weaken it.  
**Timebox:** October 29 to November 4, 2026 (submission 9 PM ET). Web (Oct 30) and Android (Oct 31) can run in parallel once the API contract is frozen at the end of Oct 29.  
**Status:** Boxes are checked only with a commit, passing tests, and evidence where a task asks for it. Work landed early on the `guided-tasks` branch (Oct 28); notes say what is partial.

## Day 0: Wed Oct 28 — Plan and docs

- [x] **GT-T00 — Product docs.** Rewrite `PRD.md` (v2.0, two modes, safety policy), add this spec, update the README summary. *(done Oct 28: PRD v2.0, this spec, README summary; status lines updated after the build)*
  - Acceptance: PRD, requirements, design and tasks agree with the approved plan; nothing is described as built beyond what exists today.

## Day 1: Thu Oct 29 — Core, safety, API contract

- [x] **GT-T01 — Core task contracts and events.** `Core/Tasks`: `TaskMode`, `TaskPhase`, `RiskLevel`, `CautionKind`, `RestrictedCategory`, `StepCheckVerdict`, `TaskPlan`, `TaskStep`, `SiteLink`; the eleven task events with `[JsonDerivedType]` (GT-R11.2). *(done Oct 28: c722a14; replay round trip in `TaskSessionTests`)*
  - Tests: replay round trip for every event type.
- [x] **GT-T02 — `TaskSession` aggregate.** Idempotent command pattern copied from `InspectionSession`; phase table and error codes from `design.md` §2.3 (GT-R04). *(done Oct 28: c722a14; 21 Core tests)*
  - Tests: step transitions, unknown step, out-of-order command, `AcceptPlan(false)`, duplicate request id, DIY cannot link, completed/abandoned reject commands.
- [x] **GT-T03 — `TaskPlanValidator` and `RestrictedTopics`.** Bounds, unique ids, no links, `ValidScene` noun-phrase rule for `lookFor`, restricted categories from the user's words and the model label, hands-on rejection, `professionalRequired`, server `SourceNote` (GT-R02.4, GT-R03). *(done Oct 28: c722a14, apostrophes allowed and one re-ask with the rejection reason in ba64285)*
  - Tests: one per restricted category; model omits restriction; restricted plan with one hands-on step; link in a caution; over-long instruction; four `lookFor` phrases.
- [x] **GT-T04 — `TaskStore` and shared JSONL helper.** Extract append-and-replay from `InspectionStore` without behaviour change; `data/tasks/{id}.jsonl` with per-id lock; task photos in `EvidenceFileStore` with digest and purpose (GT-R11). *(done Oct 28: `TaskStore` mirrors `InspectionStore`'s mechanics; the shared helper was not extracted; restart test passes)*
  - Tests: existing inspection store tests unchanged and passing; task rehydrate equals in-memory state.
- [x] **GT-T05 — `TaskPlanner`.** Muse strict schema, low reasoning, about 6000 tokens; manual photos as image parts; output through the validator (GT-R02). *(done Oct 28: genuine oil, IP-camera, restricted-panel and technician plans in `docs/evidence/tasks/`)*
  - Tests with `FakeVisionChat`: valid plan stored; invalid schema and unsafe plan store nothing.
- [x] **GT-T06 — `StepCoach` and shared scene prompt.** Extract the prompt and context builder from `LiveSceneService`; `GuideAsync` and `AskAsync` bound to the current step; restricted hands-on questions get a referral (GT-R06, GT-R08). *(done Oct 28: `StepCoach` guide and ask with its own prompt; `LiveSceneService` keeps its prompt, shared live handler only)*
  - Tests: inspection live guidance unchanged; answer with a link rejected; off-step advice dropped.
- [x] **GT-T07 — `StepChecker`.** Muse vision, `LooksDone` / `NotYet` / `CantTell` with bounded reason (GT-R09.2). *(done Oct 28: `StepCoach.CheckAsync`; genuine CantTell checks recorded)*
  - Tests: verdict recorded as `StepChecked`; step stays incomplete.
- [x] **GT-T08 — `TaskEndpoints`.** All `/v1/tasks` routes in `design.md` §4 with `Idempotency-Key`, `ApiResults`, limits; live detect/guide on `LiveVisionGate` with step `lookFor` first (GT-R01, GT-R05.2, GT-R05.6). *(done Oct 28: c722a14, 9 API tests)*
  - Tests with `FakeVisionChat` / `FakeSegmenter`: create plan; rejected unsafe plan; accept and steps; ask; step check; live detect concepts; artifact scoping.
- [x] **Gate (end of Oct 29):** `dotnet test --nologo` passes including all new Core and API tests; FieldProof tests unchanged; API contract frozen and recorded (`fixtures/api-recordings/` or an equivalent contract note) for web and Android. *(met Oct 28: 208 .NET tests; recorded responses in `docs/evidence/tasks/` serve as the contract)*

## Day 2: Fri Oct 30 — Web DIY flow

- [x] **GT-T09 — `lib/tasks.ts`.** Client, types, `useTask`, `useActTask` with idempotency keys. *(done Oct 28: ba64285)*
- [x] **GT-T10 — `lib/voice.ts`.** `useSpeak` lifted from `glasses.tsx`; `usePushToTalk` with typed fallback and the browser-service notice; `parseCommand` grammar (GT-R07). *(done Oct 28: ba64285; `voice.test.ts`)*
  - Tests: shared command cases (next, back, repeat, take photo, check step, pause, synonyms, non-commands).
- [x] **GT-T11 — Parameterize `use-live-assist.ts`.** Endpoint base for tasks and inspections. *(done Oct 28: ba64285)*
  - Tests: existing live-assist tests pass; task base hits `/v1/tasks/{id}/live/*`.
- [x] **GT-T12 — Mode home and goal entry.** `/m` and `/m/new?mode=diy|tech` with mic, details, manual photos, technician site picker (GT-R01.1). *(done Oct 28: ba64285)*
- [x] **GT-T13 — Plan screen.** `/m/t/:id`: AI-written label, risk, time, tools, parts, steps with cautions and stop-if, referral, not-a-certification note, safety acceptance (GT-R03.7, GT-R04.1). *(done Oct 28: ba64285; checked in the browser)*
- [x] **GT-T14 — Step runner and done screen.** `/m/t/:id/step/:stepId` from `scan.tsx` camera, `LiveOverlay`, `AiCard`, `Progress`; spoken instruction; push-to-talk and typed questions; commands; "Check this step" with suggestion card; Next/Back; `/m/t/:id/done` (GT-R05, GT-R07, GT-R09.3). *(done Oct 28: ba64285; browser: plan, step, Done, check card; camera and microphone not available in the browser pane)*
- [x] **Gate:** `npm --prefix apps/web test` and `npm --prefix apps/web run build` pass; DIY flow works at phone size in the browser with fakes, and with one genuine plan. *(met Oct 28: 25 web tests, build, genuine plans in the browser)*

## Day 3: Sat Oct 31 — Android task screens

- [ ] **GT-T15 — Task API and `TaskViewModel`.** Retrofit endpoints on the frozen contract; StateFlow state. *(partial Oct 28: endpoints and view models in 3d999b6; no view-model unit tests yet)*
  - Tests: view-model state for plan, accept, step, check, ask, errors.
- [x] **GT-T16 — Task screens.** `TaskHomeScreen`, `NewTaskScreen`, `PlanScreen`, `StepScreen`, `TaskDoneScreen`; `FieldOpsNavHost` starts on the mode home; technician routes unchanged (GT-R12.3). *(done Oct 28: 3d999b6)*
- [x] **GT-T17 — Step camera.** `StepScreen` on `CameraScreen` / `LiveCameraSession` and the ARCore renderer with a task live-frame analyzer; `distanceAdvice` on the step's main `lookFor`; full-resolution shutter hand-off (GT-R05.3, GT-R05.4). *(done Oct 28: 3d999b6 via `CameraScreen` slots; distance advice is not yet targeted at the step's main part; shutter hand-off untested on hardware)*
- [ ] **GT-T18 — Voice.** `voice/Speaker` over `TextToSpeech` with `SpokenFeedbackGate`; `voice/VoiceInput` push-to-talk over `SpeechRecognizer` (on-device where available on Android 12+, otherwise the system service); `CommandGrammar`; `RECORD_AUDIO` on first use with typed fallback (GT-R07). *(partial Oct 28: Speaker, push-to-talk and grammar with tests; `SpokenFeedbackGate` not wired, no typed fallback on Android, not exercised with a real microphone)*
  - Tests: the same command cases as web.
- [ ] **GT-T19 — Queue and glasses generalization.** `CommandQueue` / `PendingCommand` / `SyncWorker` to `sessionId` and `StepCheckPhoto`; `GlassesLiveService` / `GlassesLiveState` rename (GT-R09.5, GT-R12.1, GT-R12.2).
  - Tests: existing queue and sync tests pass; offline step-check photo syncs.
- [x] **Gate:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes; task flow runs on the emulator (2D, no ARCore there) with screenshots in `docs/evidence/android/`. *(met Oct 28: 107 unit tests, APK; emulator screenshots in `docs/evidence/tasks/`)*

## Day 4: Sun Nov 1 — Technician mode

- [x] **GT-T20 — Technician tasks and site link.** Technician goal entry with site picker; `SiteLink` validated at creation; install and repair plans (GT-R10.1). *(done Oct 28: site picker on web and Android; genuine site-aware plan recorded)*
- [ ] **GT-T21 — `link-inspection`.** Create or link an `InspectionSession` for the task's asset; `InspectionLinked`; task detail shows the inspection stage and outcome (GT-R10.2–GT-R10.4). *(partial Oct 28: link-inspection works and is tested; task detail does not yet show the inspection stage, and the API journey now reaches a FieldProof decision with fakes (see GT-T21 evidence below); no continuous hardware run)*
  - Tests: linked technician task reaches `VerifiedCompleteSimulated` through existing endpoints; unlinked task shows no verification label; DIY link rejected.
- [x] **GT-T22 — Home reorganization.** Web and Android home with both entries and recent tasks; technician jobs, site records and "verify a repair" reachable. *(done Oct 28: web and Android)*
- [x] **GT-T23 — Ops Tasks list (P1).** Technician tasks with their linked inspections in `/ops`. *(done Oct 28: 20d2a85, `/ops/tasks`)*
- [x] **Gate (API integration with fakes):** a technician task linked to `LAB-SW-01` goes plan → steps → linked inspection → fake fix denied → real fix verified (simulated, labelled) → receipt verifies. *(done Oct 28: `TechnicianJourneyTests`, 211 .NET tests passed; `docs/evidence/tasks/technician-journey.md`; no inspection Core, endpoints or policy changes. User-operated/hardware journey remains unvalidated.)*

## Day 5: Mon Nov 2 — Genuine-call evidence and phone run

- [ ] **GT-T24 — Genuine Muse and SAM calls.** Oil-change plan (2025 Civic), IP-camera mounting plan, one restricted goal showing a referral plan (and a rejected hands-on plan if the model produces one), one step check, one voice question, SAM outlines on step targets; stored under `docs/evidence/tasks/` with model ids and digests (GT-R13.3). *(partial Oct 28: plans, restricted referral, typed question, step checks and live coaching recorded; no spoken question and no SAM outline on a real step part yet)*
- [ ] **GT-T25 — S23 Ultra phone run.** DIY task with ARCore on a real part; spoken step; voice question answered from the camera view; "next step" by voice; shutter hand-off to a full-resolution step photo. Record what happened, including whether anchors and distance appeared; do not claim what did not.
- [ ] **GT-T26 — Docs match what ran.** Update `PRD.md` §1 status, `docs/LIVE_AR.md`, README, `docs/evidence/README.md` and `docs/HACKATHON_CONTRIBUTIONS.md` ledger rows; check this file's boxes only for completed work.
- [ ] **Gate:** every claim in README and PRD §1 points to evidence or a passing test.

## Days 6–7: Tue Nov 3 – Wed Nov 4 — Buffer, polish, submit

- [ ] **GT-T27 — Final checks.** `dotnet test --nologo`; `npm --prefix apps/web test` and `run build`; `./gradlew :app:testDebugUnitTest :app:assembleDebug`; `scripts/secret_scan.sh`; `scripts/clean_room_check.sh`.
- [ ] **GT-T28 — Demo video.** Storyboard from PRD §5: DIY goal, guided step with AR, voice question and command, step check, safety boundary, technician closer, engineering proof; simulated checks labelled.
- [ ] **GT-T29 — Submit.** Public repository and video accessible; submission before 9 PM ET / 6 PM Pacific on Nov 4.

## Added Oct 29: free-form help

- [x] **GT-T30 — Free-form help ("Just ask").** `TaskMode.Freeform`: no plan, in progress at once, questions and live coaching about anything in view, `Finish`; recognized restricted phrases trigger an appended referral when the answer lacks “professional”; this safeguard does not structurally reject unsafe free-form answers; web `/m/t/:id/live` and Android `FreeformScreen`; "Make a plan" opens a planned task with the last question as the goal. *(done Oct 29: Core and API tests; browser and emulator with genuine Muse answers, including the gas-line referral; `docs/evidence/tasks/`)*

## Cutlines

- If the contract is not frozen by end of Oct 29, Android ships the step runner only and the web flow is the guaranteed surface.
- If ARCore anchors do not appear on real parts in the phone run, demo 2D tracking and say no distance was shown.
- If speech recognition is unreliable on a device, keep spoken guidance and typed questions; drop voice commands from the video.
- If the technician link fails on Nov 1, demo the FieldProof loop on its own and do not claim the link.
- Never add a "verified" label outside FieldProof, and never relax a restricted category to make the demo work.

## Definition of done

- [ ] DIY task end to end on web and Android with genuine plans and SAM outlines
- [ ] Restricted plans with hands-on steps rejected in tests and in a genuine run
- [ ] Live AR step runner: ARCore distance only from measured depth; 2D elsewhere
- [ ] Spoken guidance, push-to-talk questions and voice commands; no audio sent to the LensQi server
- [ ] Step check suggests; the user decides
- [ ] Technician task linked to a site record reaches a FieldProof decision; receipt verifies
- [ ] Evidence under `docs/evidence/tasks/`; docs claim only what ran
