# Spatial AR implementation plan — LensQi FieldOps

Date: October 30, 2026. Planning baseline: repository HEAD a705f5c. Read-only source inspection plus the recorded
Galaxy S23 Ultra runs in `docs/evidence/tasks/s23-phone-run-{1,2,3}`. Not a new device validation. No application code
changed.

## Changes from the previous draft

The previous draft had the right principles. It missed several facts in the current source that change the order of
work or block a phase as written:

1. **The shutter destroys the AR session and every anchor.** In `CameraScreen.kt` the shutter sets `liveEnabled = false`.
   `LiveCameraSession` is `remember(liveEnabled)`, so `ArCameraFeed` is disposed, `ArLiveRenderer.close()` runs and
   `SpatialAnchors.clear()` detaches all anchors. The same happens on `ON_STOP`. `MainActivity` declares no
   `configChanges`, so a rotation also recreates the session. "Return to the confirmed component" after a capture, a
   retake or a resume is therefore impossible today. Phase 3 now starts with a decision about this.
2. **Floor height already gates a safety step.** `reachAdvice` (`ui/task/Reach.kt`) offers *Skip this step: not needed*
   for a ladder/stool step when `heightAboveFloorCm` is within reach. `measuredJson` also sends that height to the
   server as `measured`. The floor is the lowest upward-facing plane at least 30 cm below the camera, kept as a
   session-wide minimum that is never reset (`ArLiveRenderer.floorY`). This is a Phase 0 blocker, not a display issue.
3. **Real-device evidence already shows the identity and distance failures.** Phone run 2 showed one ceiling detector
   as three anchors (≈280, ≈320 and ≈385 cm) and the coaching "Move about 250 cm closer to the ceiling detector".
   That run is the regression case for Phase 0 and Phase 1.
4. **A single missed detection deletes an anchor.** `SpatialAnchors.seed` detaches any anchor that projects into the
   central 80% of a detection frame and is not detected again. A hand covering the part during a repair loses the
   target. Unconfirmed anchors also expire after 20 s. A confirmed target needs its own lifecycle.
5. **Anchors are replaced on every sighting and float in free space.** Each confirmation creates a new
   `Session.createAnchor` at an averaged, unprojected point with identity rotation and detaches the old one. Nothing is
   attached to a trackable through a hit result. Phase 4 also needs real orientation.
6. **Step changes keep old anchors.** One `LiveCameraSession` and one anchor set survive a step change. Only
   `targetConcept`/`preferredConcept` change, and `preferredConcept` is read when a late detection is applied rather
   than when its frame was captured.
7. **The FPS gate measured the wrong thing.** `LiveCameraState.fps` counts analysis ticks. They are sampled every
   100 ms, so the maximum is 10/s, and 9/s was observed. A 24 FPS target can only apply to GL rendering
   (`onDrawFrame`), which is not instrumented yet.
8. **The verify sequence must follow the server.** Android now derives its verify card from the server's next step
   (`domain/VerifyFlow.kt`: `ClaimRepair → CapturePhoto → WaitForAnalysis → ReviewChecklist → Acknowledge → Verify`).
   The draft omitted `ClaimRepair` and hard-coded an order. The plan now consumes `nextStep`.
9. **There are two capture flows.** Task-step checks (`TaskScreens.kt` → `vm.check(step.id, file)`) and inspection
   verification (`FieldOpsNavHost.kt` → verify cards and receipts) both use `CameraScreen`. All phone runs so far used
   the task flow with a smoke detector. The receipt demo uses the inspection flow. The milestone now names one flow.
10. **The MVP was over-scoped.** Appearance or region matching is now post-MVP. For the hackathon, the technician taps a
    box to confirm one target. Ambiguity always asks the technician and is never resolved by a heuristic.

## Objective and scope

Help a technician locate the correct component, keep its instruction associated with that physical component while
moving, and capture useful repair evidence. Ship Android first on the existing ARCore pipeline (ARCore 1.46). The
existing CameraX/2D experience must stay available whenever spatial tracking is unavailable. The server continues to
own every stage, verdict and receipt. The client never derives one from spatial state.

**Hackathon milestone (recommended, not a dated commitment):**

- **Flow:** the inspection hero flow (fake fix, then real fix, on the repository lab fixture) because verify cards,
  server decisions and receipts live there. Task-step AR stays as it is, except for the Phase 0 safety fixes, which
  apply to both flows.
- **Device:** Samsung Galaxy S23 Ultra (SM-S918U1, Android 16, Depth supported). This is the only phone with recorded
  ARCore runs.
- **Spatial scope:** session-local anchors, one technician-confirmed target, an off-screen return cue and guided evidence
  capture with re-acquisition after the shutter.

## Existing foundation

The source already provides:

- ARCore availability and install checks.
- Horizontal and vertical plane finding.
- Optional Depth API with a hit-test fallback.
- Image, view and world coordinate mapping.
- Per-detection world anchors with an estimated size.
- Distance to the anchor and an estimated height above the floor.
- "Seen N s ago" labels, 20 s anchor expiry and an off-screen count (a count, not a direction).
- Camera ownership handoff from ARCore to CameraX.
- 2D fallback.
- `ArSpatialTest` with 17 geometry and lifecycle tests.

Primary seams under `apps/android/app/src/main/java/com/lensqi/fieldops`:

| File | Role |
| --- | --- |
| `arcore/ArCore.kt` | Availability, session config, depth sampling, hit tests, floor heuristic, tracking label |
| `arcore/ArSpatial.kt` | Transforms, `SpatialAnchors` (association, refutation, expiry), `fuseTracks`, `distanceAdvice` |
| `arcore/ArRenderer.kt` | GL loop, capture pose/depth buffer (`captures`, 25 s), anchor seeding on the GL thread, `floorY` |
| `ui/camera/LiveCameraSession.kt` | 2D tracker, request scheduling, stale-response checks by `frameId`, advice text |
| `ui/camera/CameraScreen.kt` | ARCore/CameraX switch, overlays, shutter and the session lifetime (`remember(liveEnabled)`) |
| `ui/task/TaskScreens.kt`, `ui/task/Reach.kt` | Step target concept, reach advice, `measuredJson` |
| `ui/FieldOpsNavHost.kt`, `domain/VerifyFlow.kt`, `ui/inspection/*` | Inspection capture and verify cards from the server's next step |

These capabilities exist in source. They are not proof of field accuracy. On hardware, anchors on a real part were seen
once, in run 2, with duplicated identity. The ARCore-to-CameraX shutter handoff has not been exercised on a phone.

## Phase 0 — Safety fixes and a trustworthy baseline

Deliverables:

- **Floor and reach (blocker).** Stop offering *Skip this step: not needed* from an AR height, and stop sending
  `heightAboveFloorCm` as `measured`, until the floor is explicitly established. Establish the floor with a deliberate
  "tap the floor" confirmation, a hit test on that plane, and a reset when tracking resets. Until then, show height as
  "relative to the selected surface" or omit it. A ladder step is never removed on the strength of a heuristic plane.
- **Distance wording.** Replace the hard-coded 30 cm and 16 cm thresholds in `distanceAdvice` with a framing range taken
  from the task or view request. Without a range, do not coach a distance. Suppress "move closer" for parts above head
  height and for any distance over about 1.5 m, and say "estimated". Never infer safe clearance from camera distance.
  Label distances as *estimated from depth at the box centre*: the value is the median depth inside the box, not the
  component's surface.
- **Spatial states.** Use `acquiring`, `tracking`, `uncertain`, `lost` and `unavailable`. Track semantic identity
  confidence (detected, confirmed) separately from geometric tracking quality (ARCore state, depth source). Neither
  stands in for the other.
- **Generations.** Add a generation (asset, task/step, AR session) to every capture in `ArLiveRenderer.captures`. Drop a
  detection whose generation is no longer current. Read the preferred concept from the capture, not from current state.
  Clear the anchors and the 2D tracker on any asset, task or step change.
- **Instrumentation.** Count GL render FPS in `onDrawFrame` separately from analysis updates per second. Log anchor
  count, anchor creations and detaches, tracking-state transitions and the capture-to-detection latency.
- **Baseline.** On the S23 Ultra with the lab fixture, record acquisition time, anchor count per physical part, drift
  after a walk-around, behaviour on tracking loss, render FPS, analysis rate and thermal state over 10 minutes. Re-run
  the run-2 ceiling detector case as a regression.
- **Docs.** `LIVE_AR.md` still says distance coaching is not aimed at the step's part. The code passes `targetConcept`
  now. Correct it along with the floor change.

Gate:

- Tracking loss removes anchored labels and distance coaching in the next state update.
- A step or task switch shows none of the earlier labels and applies none of the earlier responses (unit-tested with a
  late detection).
- No skip offer comes from an unconfirmed floor.
- The 2D fallback still completes a capture.

## Phase 1 — Confirm and hold one target

User experience: start live assist, point at the fixture, tap a detected box, confirm "This is the {component}", and
see one label stay on it while the camera moves. The active instruction sits near the target without covering the
work area. The current lower-half panel layout already keeps the centre clear.

Implementation:

- **`SpatialTarget`, session-local.** Fields:
  - inspection or task and step identity, and the semantic component (concept)
  - identity source: AI-detected, technician-confirmed or configured
  - anchor handle and depth source
  - capture-frame timestamp and tracking state
  - last confirmation time
- **One anchor per confirmed target.** Create it from a hit result where one exists (`HitResult.createAnchor` on a
  plane, point or depth point) so it is attached to a trackable. Otherwise use the current free-space anchor. Keep that
  anchor. Later sightings update a smoothed offset and the size rather than detaching and recreating the anchor.
  Recreate it only when the measured position moves by more than the target's own size, and log that event.
- **Lifecycle of a confirmed target.** It differs from that of unconfirmed detections:
  - A missed detection, or a hand over the part, moves it to `uncertain` (faded, "not seen for N s"). It is not deleted.
  - After a configurable time, or on an ARCore tracking reset, it moves to `lost` and asks the technician to reconfirm.
  - Unconfirmed detections keep today's 20 s expiry.
  - A world anchor is a remembered position. It is not continuous proof that the part is still there.
- **Matching for the MVP.** A new detection updates the confirmed target only when all three hold: the concept matches,
  the target anchor projects inside the new box, and no second same-concept box also contains it. Two candidates means
  ask the technician. The broad distance tolerance in `samePart` (up to 15% of depth) stays for unconfirmed labels only.
- **Bounds.** Keep at most N anchors active, detach anything that has not been drawn for the expiry window, and show at
  most two labels (`partsToShow` already does this).

Gate: in 10 scripted runs on two adjacent identical parts of the fixture, the confirmed label never moves silently to
the other part. Every ambiguity produces a prompt, and a covered part goes to `uncertain`, not deleted.

## Phase 2 — Step callout and return cue

- A world-positioned label and callout for the confirmed target only. Other detections stay plain boxes.
- **Edge arrow.** Compute the direction from the camera-space vector (`ArCameraView.toCamera`), not the projected point,
  so a target behind the camera gets a "turn around" cue instead of a mirrored arrow. Today `offscreen()` returns only
  a count.
- Framing guidance uses the task's range and uncertainty-aware wording from Phase 0.
- A step transition invalidates the old callout. Reviewing an earlier step suspends coaching (`liveAllowed = !reviewing`
  already stops live assist). *Repeat* reads the step that is shown.
- Where several labels belong to one piece of equipment, place them relative to one equipment anchor so independently
  drifting anchors do not imply relative precision. This matters for Phase 4. For the MVP there is only one target.

Gate: turn away and back 10 times. Each time the callout returns to the confirmed part or asks for re-acquisition, and
it never points at the wrong part. Unsupported or degraded AR falls back to 2D without blocking the task.

## Phase 3 — Guided evidence capture

### Decision first: what happens to AR at the shutter

Today the shutter tears down ARCore and the target with it. Choose one approach before building this phase.

- **A. Re-acquire after capture (recommended for the MVP).** Keep the semantic target (concept, confirmation, the
  reference crop from the confirming frame) outside the AR session. After the full-resolution CameraX photo, restart AR
  and require the technician to reconfirm the target before any further spatial guidance. This is cheap and honest. It
  costs a few seconds per capture.
- **B. Shared camera spike (time-boxed, post-MVP).** ARCore's Shared Camera API lets a Camera2 `ImageReader` capture
  stills while the session continues. Check the still resolution and the Depth support on the S23 Ultra before
  committing. Resolution or quality limits that make the photo unfit as original evidence rule it out.
- **Ruled out:** using the ARCore CPU image (640 px upright in this app) as evidence.

Also make the AR session survive rotation and resume, or document that it does not. Today both recreate it.

### User experience

On the server's `CapturePhoto` step, guide the technician back to the confirmed component and show the framing range.
Overlay a ghost frame of the original evidence photo as a framing aid. Say on screen that matching the view is a
framing aid, not proof of repair. The technician presses the shutter.

### Implementation

- Drive every card from the server's next step (`ClaimRepair → CapturePhoto → WaitForAnalysis → ReviewChecklist →
  Acknowledge → Verify`), as `VerifyFlow.kt` does. AR only helps inside `CapturePhoto`.
- The original evidence is the full-resolution CameraX JPEG, uploaded unmodified through the existing queue. Annotated
  previews are separate. An overlay screenshot never becomes evidence.
- After capture, show a confirmation screen: "Is this the {component}?" on the actual still. Preview coordinates are not
  carried across cameras. Pose transfer comes only after calibration.
- **Spatial context.** Do not upload it in the MVP. If it is stored later (frame timestamp, session ID, target,
  coordinate convention, tracking state), that is an API, event and test change on the server, made under the repository
  conventions. Mark it unavailable when it cannot be tied to the shutter image, and never present a preview pose as the
  capture pose.
- An anchor, a distance or a live label never satisfies a checklist item, acknowledges for the technician or triggers
  verification.

Gate:

- The fake fix (unchanged photo or a failed diagnostic) still fails while AR shows the target confidently.
- Retake, tracking loss during capture, slow analysis, repeated shutter taps and process death between capture and
  upload all recover.
- Simulated measurements keep their SIMULATED label.

## Phase 4 — Registered component and port guidance (after the MVP)

- Identify the equipment instance by an asset code or a technician-confirmed asset record.
- Establish its pose with a known-size image target (ARCore Augmented Images with a physical width) or a deliberate
  manual three-point registration. A QR code that carries only an ID is not a calibrated spatial reference.
- Store component coordinates in an asset-local frame with model, revision and source metadata. Keep authored geometry
  separate from AI detections. This needs anchor orientation, which current anchors lack.
- Project callouts from the equipment frame. Revalidate when the equipment moves or tracking resets.
- Enable port-level arrows only after demonstrated error is below half the port pitch. Otherwise show a coarse equipment
  callout and ask the technician to identify the port.

Gate: test adjacent ports, similar models, wrong asset labels, registration drift and moved equipment. Never claim
port-level identification from a generic detection.

## Phase 5 — Optional later capabilities

Only after the single-device workflow is reliable:

- depth-aware occlusion
- technician-authored issue pins
- multi-component steps
- persistent annotations
- remote expert collaboration
- appearance-based re-identification

Cross-session persistence needs relocalisation. Session world coordinates cannot be reused after a restart. Evaluate
local marker registration first. Consider Cloud Anchors only if shared or persistent use justifies the service,
permissions, connectivity and retention costs.

Out of scope: room-wide mapping, navigation, cross-platform spatial parity, CAD reconstruction, cable routing,
precision metrology.

## Validation and acceptance

**Unit tests (extend `ArSpatialTest` and the task tests):**

- projection, rotation and crop
- behind-camera arrows from camera-space direction
- the confirmed-target lifecycle: occlusion goes to `uncertain`, not deleted; a tracking reset goes to `lost`
- two same-concept candidates produce an ambiguity prompt
- late detections from an old generation are dropped
- a step change clears state
- `reachAdvice` never offers a skip without a confirmed floor
- `measuredJson` excludes unconfirmed heights
- `distanceAdvice` stays silent without a task range or for out-of-reach parts

**Device tests on the S23 Ultra:**

- Depth on and Depth forced off (hit-test path)
- fallback with 3D AR off
- poor light, reflective surfaces and repeated identical parts
- a hand over the target and a moved target
- rotation, background and resume
- camera permission denied
- the shutter handoff and retake
- connectivity loss and process death after capture

If a second phone without Depth is available, add it. Otherwise say the non-Depth path was tested only by forcing
Depth off.

**MVP gates (targets to measure, not results):**

- Ten consecutive scripted runs with no silent target swap.
- Anchored guidance disappears in the next state update after tracking loss.
- At least 24 FPS GL render rate and at least 8 analysis updates per second, sustained for 10 minutes without a thermal
  throttling warning.
- The target label stays inside the component outline in annotated walk-around footage. Otherwise precision is
  downgraded to the coarse callout.
- The final spatial-error threshold is derived from component size and separation, not from an advertised centimetre
  figure.

Record genuine device video or screenshots with conditions in `docs/evidence/`, as the S23 runs do. An emulator build
cannot show spatial accuracy: the arm64 emulator has no ARCore. No Docker is needed.

## Delivery order

1. Phase 0 safety fixes (floor and reach, distance wording, generations), instrumentation and the S23 baseline.
2. Confirmed-target lifecycle and identity stability.
3. The shutter decision (A for the MVP), then guided capture driven by the server's next step.
4. Callout and return cue.
5. Device acceptance and demo rehearsal.
6. After the MVP: the shared-camera spike, registered port guidance, then persistent or shared AR.

Capture moves ahead of the return cue because the demo needs capture and the current shutter behaviour blocks it. The
return cue is polish on top.

**Status on 2026-10-31 (branch `spatial-ar-mvp`, unit tests and debug build only, not yet run on a phone):** phases 0–2
and phase 3 option A are in code: the before-photo overlay, the check on the still, capture help only on `CapturePhoto`,
the shutter timeout, retake and back, and a still that survives process death. Phase 4 is in code too. It uses one
printed Augmented Image marker (`docs/registration/`), confirmed by the technician as being on the inspection's asset.
Ports are marked by the technician in the marker's frame and stored on the phone with fixture, asset, model, revision
and source. The port is pointed at only after two checks under half the port spacing, and the checks are dropped on
movement, loss of tracking or a new mark. `docs/LIVE_AR.md` describes the behaviour. Open: the S23 baseline and
acceptance runs with screenshots in `docs/evidence/android/`, scoring the marker with `arcoreimg`, a framing range from
the server, AR across rotation, a marker per asset, server-side layouts, and phase 5.

**Demo:** open the lab fixture inspection. Confirm the component. Move the phone and show the label staying on the
part. Cover it with a hand and show it go `uncertain`, then recover. Turn away and follow the cue back. Run the fake fix:
the capture and the server verdict fail. Run the real fix: re-acquire after the shutter, capture, review the checklist,
acknowledge, verify, then show the measured result and the receipt.

## Technical references

- Anchors, including reusing anchors for nearby objects, attaching to trackables and detaching unused ones:
  https://developers.google.com/ar/develop/anchors
- Hit-test types and their geometry and precision assumptions: https://developers.google.com/ar/develop/java/hit-test/developer-guide
- Depth support check and fallback: https://developers.google.com/ar/develop/java/depth/developer-guide
- Shared camera access (option B): https://developers.google.com/ar/develop/java/camera-sharing
- Augmented Images, known-size targets (Phase 4): https://developers.google.com/ar/develop/augmented-images
