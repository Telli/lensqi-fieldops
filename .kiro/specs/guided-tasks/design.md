# LensQi — Guided Tasks
## Kiro Technical Design

**Spec ID:** `guided-tasks` · **Version 1.0** · October 28, 2026  
**Status:** Design written before implementation; most of it was built on October 28–29. §0 records where the code differs, from a review of `40ed417` on October 29. The sections after §0 describe the design as written.  
**Stack:** ASP.NET Core 10 minimal API · React web (`apps/web`) · Kotlin/Compose Android (`apps/android`) · Muse Spark 1.3 + SAM 3.1  
**References:** `requirements.md`, `tasks.md`, PRD v2.0 §4 (safety policy), §7 (state machines), §9 (architecture), §18 (task trust invariants); `../lensqi-fieldops/design.md` for the FieldProof inspection loop.

## 0. As built (code review, October 29, 2026)

Where the implementation differs from the design below. Tests passed on the reviewed commit: 211 .NET, 25 web, 107 Android unit tests; 214 .NET after the review fixes.

| Area | As built |
|---|---|
| Modes | `TaskMode` adds `Freeform` ("Just ask"): no plan, `InProgress` at once, questions and live coaching, ended with `Finish`. Planning, accepting and the FieldProof link are refused for it |
| Events | Twelve types: the eleven in §2.2 plus `TaskPhotoRecorded(evidenceId, sha256, width, height, purpose, stepId?)`. `PlanProposed` also records the model id, prompt and response digests and the manual photo ids |
| Session rules | Goal 3–300 characters, details up to 1000. Accepting starts the first step. Safety acknowledgement is required only when risk is above Low, a professional is required, or any step has a caution; the clients' start button is the acknowledgement. A `required` photo check is a prompt, never a block (since Oct 29). Finishing or skipping the last open step completes the task. Every event carries the `requestId` that appended it, so replayed requests survive a restart (since Oct 29) |
| Validator | As §2.4, plus: a hands-on step in a High-risk plan needs a caution; apostrophes are allowed in `lookFor`; `HasLink` catches `://` and bare `www.` addresses (since Oct 29; quoted screen text in live guidance may still show a bare address). A rejected plan is requested once more with the rejection reason, then the error is returned |
| Meta | `TaskPlanner` and `StepCoach` share a `MuseStructured` helper. There is no separate `StepChecker`: the check is `StepCoach.CheckAsync`. The scene prompt was **not** extracted from `LiveSceneService`; inspections keep their own prompt, and the live endpoints share one handler (`RunLive`) with per-session concept and guidance functions |
| Ask | The API appends a fixed referral when `RestrictedTopics` matches the **question** and the answer does not contain "professional". Restricted wording in the goal of the task does not trigger it, and unsafe answer content is not structurally rejected |
| API | Create takes `fixtureId` and `assetId` (not a `siteLink` object) and up to 3 manual photos; extra routes `POST /{id}/plan` (retry a failed plan), `/{id}/finish` (free-form), `/{id}/abandon`. `link-inspection` returns the existing inspection when already linked |
| Store | `TaskStore` mirrors `InspectionStore` (in-memory sessions, per-task lock, append-only JSONL, rehydrate on start); the shared JSONL helper was **not** extracted |
| UX (Oct 29) | One tap from the home goal box to a plan; one "I've read the safety notes · Start" button; Done never blocked by a suggested photo check; typing next to the mic everywhere; the speech-provider notice shown once, before first use |
| Web | Routes `/m` (Do it yourself, Just ask, Technician), `/m/tech` (technician home), `/m/new`, `/m/t/:id`, `/m/t/:id/step/:stepId`, `/m/t/:id/done`, `/m/t/:id/live` (free-form), `/ops/tasks`; `/m/scan-label` starts an inspection from a scanned asset label (since Nov 2). Each POST carries an `Idempotency-Key` kept until the server gives a final answer (`once` in `lib/api.ts`), named by what the command depends on: the step and a digest of the photo for a check, the question and frame for ask, and for inspection commands the claim, accepted photo, review answers and confirmation (`commandScope`). A retry replays the recorded outcome; a changed input is a new command (since Nov 2; earlier every POST minted a fresh key). An observation retried under its key with different photo bytes is refused with 409 `key_reused` rather than answered with the first photo (since Nov 3; before that the first photo was replayed). Model calls take an `AbortSignal` and the pages offer Cancel; the server ignores a cancel once the model has answered |
| Voice grammar | Same phrase table on web and Android: next, back, repeat, check (also "take a photo"), skip, mute, unmute. No "pause". The whole utterance must match; anything else is a question |
| Android | One `ui/task/TaskScreens.kt` and `TaskViewModels.kt`; `voice/Voice.kt` holds `Speaker`, `rememberVoiceInput` (on-device recognizer when available on Android 12+) and `parseCommand`. **Not built:** `SpokenFeedbackGate` on task speech, `distanceAdvice` aimed at the step's main `lookFor` (it uses all tracked objects), the `sessionId` / `StepCheckPhoto` queue generalization, the glasses rename |
| Review fixes | The three defects found on Oct 29 are fixed (GT-T31–T33 in `tasks.md`): `link-inspection` checks and creates under the task lock; a step-check retry with the same key reuses the saved photo and replays a recorded verdict; unexpected exceptions are logged and answered with a generic `internal` error. GT-T34–T37 (link check, persisted request ids, linked inspection outcome, UX blockers) followed the same day |

## 1. Architecture and code boundaries

```text
 Web /m task routes                      Android ui/task/*
 camera + LiveOverlay + 2D tracker       CameraScreen / LiveCameraSession / ARCore renderer
 voice.ts: useSpeak, usePushToTalk       voice/Speaker, voice/VoiceInput
 parseCommand (shared grammar)           CommandGrammar (shared grammar), CommandQueue(sessionId)
              │        text, frames, photos; never audio        │
              └────────────────────┬─────────────────────────────┘
                                   ▼
                    Api/Features/Tasks/TaskEndpoints.cs
           Idempotency-Key · ApiResults · guards · LiveVisionGate · ModelCallLimits
                 │                    │                       │
        Core/Tasks (pure)     Meta/Tasks                 Features/Inspections (unchanged)
        TaskSession           TaskPlanner                     │
        TaskEvents            StepCoach ◄── shared scene ──► LiveSceneService
        TaskPlanValidator     StepChecker   prompt/context    │
        RestrictedTopics          │                       Core/Inspection (unchanged)
                 │                ▼                       VerificationPolicyEvaluator, receipts
                 │      MuseSparkChatClient · SamSegmentationClient
                 ▼
   TaskStore (data/tasks/{id}.jsonl) ── shared JSONL append/replay ── InspectionStore
   EvidenceFileStore (photos under task id or inspection id)
```

**What is reused unchanged:** Muse and SAM clients; the live 2D tracker and ARCore anchors and distance; open-scene live guidance (`LiveSceneService`) for inspections; the append-only JSONL event log and evidence file store; camera screens, the glasses link and spoken-feedback gating (`SpokenFeedbackGate`); the inspection, verification and receipt engine.

**Trust separation:** Only `TaskSession` commands append task events, and only `InspectionSession` commands and `VerificationPolicyEvaluator` decide inspection outcomes. The model proposes plans, guidance, answers and step-check verdicts; the validator decides whether they are stored or shown; the user decides whether a step is done; FieldProof decides whether a technician repair is verified.

## 2. Core/Tasks (pure, no provider dependencies)

Lives in `src/LensQi.FieldOps.Core/Tasks/`, mirroring `Core/Inspection`.

### 2.1 Contracts

```csharp
public enum TaskMode { Diy, Technician }
public enum TaskPhase { Planned, InProgress, Completed, Abandoned }
public enum RiskLevel { Low, Moderate, High }
public enum CautionKind { Hot, Electrical, Lifting, Chemical, Height, Sharp, Pressure, Other }
public enum RestrictedCategory { None, GasLinesOrAppliances, MainsElectrical, EvHighVoltage, AirbagsSrs, Refrigerant, Structural }
public enum StepCheckVerdict { LooksDone, NotYet, CantTell }
public enum TaskPhotoPurpose { StepCheck, Manual, Question }

public sealed record Caution(CautionKind Kind, string Text);
public sealed record StepPhotoCheck(bool Required, string WhatDoneLooksLike);

public sealed record TaskStep(
    string Id, string Title, string Instruction, bool HandsOn,
    IReadOnlyList<string> LookFor,          // SAM noun phrases, at most 3
    IReadOnlyList<Caution> Cautions, IReadOnlyList<string> StopIf, StepPhotoCheck? Check);

public sealed record TaskPlan(
    string Title, string Summary, RiskLevel Risk, int EstimatedMinutes,
    RestrictedCategory Restricted, bool ProfessionalRequired, string? ProfessionalReason,
    IReadOnlyList<string> Tools, IReadOnlyList<string> Parts, IReadOnlyList<TaskStep> Steps,
    string SourceNote);                     // set by the server: "AI-written plan · check your owner's manual"

public sealed record SiteLink(string FixtureId, string AssetId);
```

`HandsOn` marks a step that asks the user to touch, loosen, fit or remove something; prepare-and-observe steps do not. It is model-declared and therefore checked conservatively (§2.3).

### 2.2 Events

Each event is a record with a `[JsonDerivedType]` discriminator on the task event base, a server timestamp, and a replay test.

| Event | Payload | Appended by |
|---|---|---|
| `TaskCreated` | `mode, goal, details, siteLink?` | `TaskSession.Create` |
| `PlanProposed` | `plan` (validated) | `ProposePlan` |
| `PlanAccepted` | `safetyAcknowledged` (must be `true`) | `AcceptPlan` |
| `StepStarted` | `stepId` | `StartStep` |
| `QuestionAnswered` | `stepId, question, answer, photo?` | `RecordAnswer` |
| `StepChecked` | `stepId, photoId, verdict, reason` | `RecordStepCheck` |
| `StepCompleted` / `StepSkipped` | `stepId` | `CompleteStep` / `SkipStep` |
| `TaskCompleted` / `TaskAbandoned` | — | `Complete` / `Abandon` |
| `InspectionLinked` | `inspectionId` | `LinkInspection` (technician tasks with a site link only) |

### 2.3 TaskSession aggregate

Copies the `InspectionSession` command pattern: each command takes a client request id, validates against current state, and either returns the events it appended or throws `TaskConflictException(code)`. A repeated request id returns the original events without appending. State is materialized by `TaskState.Replay(events)`.

| Phase | Allowed commands |
|---|---|
| (none) | `Create` → `TaskCreated`, then `ProposePlan` → `PlanProposed` → `Planned` |
| `Planned` | `AcceptPlan(true)` → `InProgress`; `Abandon` |
| `InProgress` | `StartStep`, `RecordAnswer`, `RecordStepCheck`, `CompleteStep`, `SkipStep`, `LinkInspection`, `Complete`, `Abandon` |
| `Completed`, `Abandoned` | none (`invalid_state`) |

Error codes: `invalid_state`, `unknown_step`, `unsafe_plan`, `invalid_plan`, `safety_not_acknowledged`, `duplicate_evidence`, `invalid_input`.

Rules: `AcceptPlan(false)` fails with `safety_not_acknowledged`; step commands need a step id from the accepted plan; going back is a new `StepStarted`, never a deletion; `RecordStepCheck` never changes step completion; `LinkInspection` fails for DIY tasks and tasks without a site link.

### 2.4 TaskPlanValidator and RestrictedTopics

`TaskPlanValidator.Validate(goal, details, plan)` returns the plan with the server `SourceNote`, or throws `invalid_plan` / `unsafe_plan`.

1. **Bounds:** 1–25 steps; title, summary, instruction, caution, stop-if, tool and part lengths bounded; at most 3 `lookFor` per step; unique step ids.
2. **No links:** no URL or scheme-like text anywhere in the plan.
3. **Concept phrases:** each `lookFor` matches the same 1–4 word noun-phrase rule as `ValidScene` in `LiveVisionEndpoints.cs`, so SAM gets a plain concept.
4. **Restricted categories:** `RestrictedTopics.Classify(goal + " " + details)` maps the user's words to a category with word-boundary patterns (for example "breaker panel", "airbag", "refrigerant", "load-bearing", "traction battery", "gas line"). The effective category is the model's label, or the words' category when the model said `None`. If the words name a category and the model's plan said `None`, the plan is rejected (`unsafe_plan`): the user's words decide as much as the model's label.
5. **Restricted plans:** every step must have `HandsOn = false`; `ProfessionalRequired` must be `true` with a reason. Any hands-on step → `unsafe_plan`.
6. **Source note:** overwritten with the server constant.

The same link, length and noun-phrase checks are applied by the Meta services to guidance, answers and step-check reasons before anything is returned.

## 3. Meta/Tasks

Lives in `src/LensQi.FieldOps.Meta/Tasks/`. All three use `MuseSparkChatClient` with strict JSON schemas (root object, every property required, `additionalProperties: false`), record model id, schema version, token usage and correlation id, and have fakes for tests.

### 3.1 TaskPlanner

- Input: mode, goal, details, up to N manual photos (as image parts), and for technician tasks with a site link, the asset's display name and kind from the fixture (not authoritative IDs to invent).
- Call: `muse-spark-1.3`, `reasoning_effort: low`, budget about 6000 tokens, `TaskPlan` schema without `SourceNote`.
- Prompt rules: write for the stated mode; include cautions and stop-if per step; say "check your owner's manual" for specifications; for restricted work, write prepare-and-observe steps and a referral only; never include links.
- Output: passed to `TaskPlanValidator`; a schema or validation failure is a typed error, never a stored plan.

### 3.2 StepCoach

- A generalized `LiveSceneService`. The scene prompt and context builder are extracted into a shared component; `LiveSceneService` keeps its inspection context (site record parts) and `StepCoach` supplies goal, accepted plan, current step and its `lookFor`.
- `GuideAsync(frame)` returns step-bound guidance and focus phrases (validated like `ValidScene`).
- `AskAsync(question, frame?)` returns a bounded answer within the current step; the prompt requests a referral for restricted work, and the API appends one for recognized restricted phrases when the answer lacks “professional”. This does not structurally reject unsafe answer content.
- Guidance outside the current step is dropped by the prompt and by a check that focus phrases and advice relate to the step.

### 3.3 StepChecker

- Input: step-check photo and the step's `WhatDoneLooksLike` (and title/instruction for context).
- Output: `{ verdict: LooksDone | NotYet | CantTell, reason }`, reason bounded and link-free. "Can't tell" is preferred over a guess when the photo does not show the relevant part.
- The verdict is stored as `StepChecked`; nothing else reads it for decisions.

## 4. API — `Features/Tasks/TaskEndpoints.cs`

Same patterns as `InspectionEndpoints`: `Idempotency-Key` on every POST, `ApiResults` problem responses with the codes above, request-size limits, per-IP rate limits, and `ModelCallLimits` around model calls.

| Endpoint | Body | Result |
|---|---|---|
| `POST /v1/tasks` | JSON `{mode, goal, details, siteLink?}` or multipart with `manual` photos | Task with plan (`201`), or `invalid_plan` / `unsafe_plan` / provider errors |
| `GET /v1/tasks` | `?mode=` | Recent tasks (summary) |
| `GET /v1/tasks/{id}` | — | Phase, plan, step states, latest step checks, linked inspection id and its outcome if any |
| `GET /v1/tasks/{id}/events` | — | Event log |
| `GET /v1/tasks/{id}/artifacts/{name}` | — | A stored task photo (only that task's) |
| `POST /v1/tasks/{id}/accept` | `{safetyAcknowledged: true}` | Task |
| `POST /v1/tasks/{id}/steps/{stepId}:start` / `:complete` / `:skip` | — | Task |
| `POST /v1/tasks/{id}/steps/{stepId}/check` | multipart `image` | `{verdict, reason, photoId}` |
| `POST /v1/tasks/{id}/ask` | multipart `question` + optional `image`, or JSON `{question}` | `{answer, stepId}` |
| `POST /v1/tasks/{id}/live/detect` | multipart `image`, `frameId` | Normalized boxes; concepts = current step `lookFor`, then guidance focus phrases |
| `POST /v1/tasks/{id}/live/guide` | multipart `image`, `frameId` | Step guidance and focus phrases |
| `POST /v1/tasks/{id}/link-inspection` | — | `{inspectionId}`; creates or links an `InspectionSession` for the task's site asset |

Live endpoints reuse `LiveVisionGate` (one in-flight request per client and session, cooldowns, global concurrency bound), the frame checks, and the client-side minimum intervals (4 s detection, 12 s guidance). Responses are `no-store`; live frames are never written to the evidence store.

Existing `/v1/inspections/*` routes are unchanged. `link-inspection` calls the same creation path as `POST /v1/inspections` with the task's fixture and asset, then appends `InspectionLinked`.

### Storage

- `TaskStore` writes `data/tasks/{id}.jsonl` with a per-id lock, append and rehydrate, using a small shared JSONL append-and-replay helper extracted from `InspectionStore` (no behaviour change for inspections).
- Photos go to `EvidenceFileStore` under the task id with SHA-256 digest and purpose; duplicate uploads by digest return `duplicate_evidence` or the existing id.

### Voice on the server

None. The API accepts text questions and goals only. Android prefers on-device recognition where available on Android 12+; system and browser speech services may process audio remotely. Only recognised text reaches LensQi.

## 5. Web (`apps/web`)

### Routes (`main.tsx`)

| Route | Page |
|---|---|
| `/m` | Mode home: "Do it yourself" goal box with mic; "Technician" (jobs, site records, verify a repair); recent tasks |
| `/m/new?mode=Technician` (anything else is Do it yourself) | Goal, details, manual photos, mic; technician site picker |
| `/m/t/:id` | Plan screen: AI-written label, risk, time, tools, parts, steps with cautions and stop-if, professional referral, safety acceptance |
| `/m/t/:id/step/:stepId` | Step runner |
| `/m/t/:id/done` | Summary; technician tasks with a site link continue to the existing verify and receipt flow |

Technician keeps `/m/start`, `/m/i/*` and `/ops/*`. The ops dashboard gains a Tasks list (P1) of technician tasks and their linked inspections.

### Library pieces

- `lib/tasks.ts`: API client, types matching §2.1, hooks `useTask(id)` and `useActTask(id)` (TanStack Query mutations with idempotency keys).
- `lib/voice.ts`:
  - `useSpeak()` lifted from `pages/m/glasses.tsx` (speechSynthesis with de-duplication and spacing).
  - `usePushToTalk()` over `webkitSpeechRecognition` / `SpeechRecognition`; returns `supported` and `notice`; the task screens always show a typed box next to the mic, and show the speech-provider notice until the mic is first used on that device.
  - `parseCommand(text)` → `next | back | repeat | takePhoto | checkStep | pause | null` with a small synonym table ("next step", "go back", "say that again", …). `null` means "send as a question".
- `lib/use-live-assist.ts` takes an endpoint base so it serves `/v1/tasks/{id}` and `/v1/inspections/{id}`.

### Step runner

Built from `scan.tsx`'s camera, `LiveOverlay`, `AiCard` and `Progress` (steps = plan steps): live camera with outlines; step title and instruction (spoken on open); cautions and stop-if always visible; push-to-talk and typed question; "Check this step" shutter with the suggested verdict card ("Suggestion: looks done — …") and Complete / Not yet buttons; Next and Back.

## 6. Android (`apps/android`)

### Screens and view model

- `ui/task/TaskHomeScreen` (mode picker and recent tasks), `NewTaskScreen`, `PlanScreen`, `StepScreen`, `TaskDoneScreen`; `TaskViewModel` with StateFlow.
- `FieldOpsNavHost` starts on the mode home; technician routes (`inspection/{id}`, review, receipt) stay as they are.

### Camera and AR

- `StepScreen` reuses `CameraScreen` / `LiveCameraSession` and the ARCore renderer unchanged, passing a task live-frame analyzer (task live endpoints) and the step prompt.
- `distanceAdvice(objects, targetConcept)` uses the step's main `lookFor`; distance appears only for anchors with measured depth. Without ARCore, the CameraX path tracks in 2D with no distance.
- The shutter hand-off from ARCore to CameraX takes the full-resolution step-check photo, as in inspections.

### Voice

- `voice/Speaker`: injectable wrapper over `TextToSpeech`, gated by the existing `SpokenFeedbackGate`.
- `voice/VoiceInput`: push-to-talk over `SpeechRecognizer`, preferring `createOnDeviceSpeechRecognizer` on Android 12+ where available; partial results shown on screen; returns recognized text only.
- `CommandGrammar`: the same rules as web `parseCommand`, with the same test cases run in both suites.
- Manifest gains `RECORD_AUDIO`; requested on first push-to-talk; refusal falls back to typed questions.

### Session generalization

- `CommandQueue` / `PendingCommand` / `SyncWorker`: rename `inspectionId` to `sessionId` (task or inspection), with a Room migration if the stored column changes, and add a `StepCheckPhoto` command that uploads to the task check endpoint with its idempotency key.
- `GlassesLiveService` / `GlassesLiveState`: same rename so glasses can serve tasks (P1).

## 7. Technician mode and FieldProof

- Technician tasks are ordinary tasks with `mode = Technician` and an optional `SiteLink` validated against the fixture catalog at creation.
- `link-inspection` creates or links an `InspectionSession`; the technician then uses the existing inspection screens (capture, investigate, diagnostics, repair claim, verify, receipt).
- The task detail exposes the linked inspection's stage and `VerificationOutcomeKind`; the done screen shows **Verified Complete (live)** or **(simulated)** only from that outcome.
- Step checks, answers and plans never enter the inspection's evidence or the verification policy.

## 8. Error handling and honesty

- Provider timeout, rate limit or outage: typed errors (`model_timeout`, `rate_limited`, provider-down), retry offered; no fallback plan is invented.
- SAM no-match: no outline; guidance may ask for a better view.
- ARCore not tracking or no depth: 2D outline, no distance, status pill states the ARCore state.
- Speech unavailable or refused: typed box.
- Labels: AI-written plan; "Suggestion" on verdicts; "Simulated" on simulated diagnostics; "Mock" on mock glasses.

## 9. Testing strategy

| Layer | Tests |
|---|---|
| Core | Replay for every event type; validator bounds, links, noun phrases; each restricted category; model omits restriction; restricted plan with a hands-on step; step transitions; safety acknowledgement; idempotent commands; DIY cannot link |
| API (fakes: `FakeVisionChat`, `FakeSegmenter`) | Create plan; rejected unsafe plan stores nothing; accept and step flow; ask; step check never completes a step; live detect uses step `lookFor`; technician inspection link and FieldProof outcome surfaced; artifact access scoped to task |
| Web (`npm --prefix apps/web test`) | `parseCommand` shared cases; task helpers; `use-live-assist` endpoint base |
| Android (`./gradlew :app:testDebugUnitTest`) | `CommandGrammar` shared cases; `TaskViewModel` state; queue migration and `StepCheckPhoto` |
| Genuine calls | Oil-change and IP-camera plans, a restricted goal, a step check, a voice question and SAM outlines on step targets, stored under `docs/evidence/tasks/` with model ids and digests |
| Device | Browser DIY flow at mobile size; S23 Ultra run: ARCore anchors on a real part (recorded honestly if none appear), spoken step, voice question, "next step" by voice, shutter hand-off |

Existing suites, `scripts/secret_scan.sh` and `scripts/clean_room_check.sh` must stay green.

## 10. Main risks and controls

- **Unsafe plan reaches a user:** server validator with restricted categories from the user's words; tests per category; genuine restricted-goal run.
- **Model drifts outside the step:** step-bound context, output checks, dropping off-step advice.
- **Step check mistaken for approval:** "Suggestion" wording, user-only completion, no link to FieldProof.
- **Breaking inspections during generalization:** shared helpers extracted without behaviour change; inspection tests and clean-room check stay in the loop; Android queue migration tested.
- **Voice differences between platforms:** one grammar table, both test suites; typed fallback everywhere.
