# Pointing at a part with your finger (Android)

A person points at something on the camera and asks "is this the test button?". The phone finds the fingertip on device,
works out which part it is on, shows that on screen, and sends the spot with the question.

**Status: first slice on a branch. Not yet run with a real hand on a real phone.** See [What has been tested](#what-has-been-tested).

## Is it worth building? Yes

### Which hand tracker

| Option | On device | Fits here? |
|---|---|---|
| **MediaPipe Hand Landmarker** (chosen) | Yes | 21 landmarks per hand, plus 3D estimates. Takes the bitmaps the camera code already makes. |
| ML Kit Pose | Yes | Full-body model. Has a rough index-finger point, and expects a body in view, not a hand at close range. |
| ARCore | Yes | Has no hand tracking on phones. |
| Muse Spark or SAM 3.1 | No | Would send extra frames off the phone, and takes seconds. Ruled out by the brief. |
| A custom fingertip model | Yes | Training and upkeep for no clear gain over MediaPipe. |

The MediaPipe lines in the app's logs come from ML Kit's text recognition, which carries a private copy inside
`libmlkit_google_ocr_pipeline.so`. It has no API the app could call, so nothing could be reused.

| Question | Answer |
|---|---|
| Licence | Apache 2.0 for the library (its POM) and for the model (its [model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Hand%20Tracking%20(Lite_Full)%20with%20Fairness%20Oct%202021.pdf)). Compatible with this project's AGPL-3.0. |
| Version | `tasks-vision` 0.10.35. The 1.x line adds a 21 MB `litertlm` LLM runtime and a larger native library, neither used here. |
| Size | Measured: 10.5 MB native code on arm64 plus the 7.8 MB model, about 18 MB on an S23 Ultra. The debug APK, which also carries the x86_64 library, grew about 32 MB. |
| Speed | Google publishes 17.12 ms on CPU and 12.27 ms on GPU on a Pixel 6. **Not yet measured on the S23 Ultra.** Cost here is capped: at most 10 looks a second with a hand around, 4 without. |
| Camera fit | Good. The CameraX analyzer already makes an upright bitmap, and the AR worker has the camera image. Both use the same coordinates as the part boxes. |

Limits the model card states, which matter for this work: it is **not meant for gloved hands or hands holding objects**, it is "not
intended for human life-critical decisions", and it is described as trained on limited data for experimental use. Its error
across 14 regions averages about 10% of palm size, ranging from 6.1% to 13%. Gloves are common in field work, so this slice says
in the app that it may miss them.

### How a fingertip maps onto what the app knows

- **Boxes, yes.** The task camera's live parts are SAM 3.1 boxes with labels. The fingertip is compared with those.
- **Masks, no.** Live responses carry no masks; they exist only on the inspection segment path. Choosing the smallest box
  under the tip gets most of the benefit: a button inside a panel resolves to the button.
- **2D tracking, with a real risk.** `LiveTracker` follows boxes by matching image patches, and a hand moving over a part can
  break that, so a part can vanish just as someone points at it. When that happens the question still carries the spot, and
  Muse sees the frame. This needs checking on the phone.
- **ARCore anchors, yes.** In AR the boxes are in view coordinates, so the fingertip goes through the same `ViewMap`. Anchored
  parts stay on screen while covered by the hand, so AR should do better here. A part only remembered for more than 10 s doesn't count.
- **Touching or about to touch: not possible from this.** The picture can't tell a finger on a button from one in front of it.
  The model's depth values are relative to the hand, and ARCore depth is too coarse and too slow for a fingertip. So the app
  says "pointing", never "touching", and says plainly that it can't tell.

## How it works

```
camera frame ─ HandDetector (MediaPipe, on the phone) ─ readPointer ─ PointingTracker ─ pointing state on screen
                                                         (is this a     (steadies it)         │
                                 part boxes ──────────── pointing       │                     ▼
                                                         shape?)        └── a person asks ─ question + note + that moment's frame
```

- **Pointing shape:** index straight, middle bent, and at least one of ring or little bent. A fist, an open hand, a thumbs-up
  and two fingers up don't count, so working hands and tools don't name parts by accident. Shape comes from the 3D landmarks,
  so a finger aimed away from the camera still counts.
- **Which part:** the part under the tip, or else the first one along the finger's direction within 1.5 finger lengths. Smallest box wins.
  Two side by side of about the same size stay undecided, and the screen says "between".
- **Steadying:** a finger must hold 250 ms to count. A dropout under 400 ms doesn't end it. Moving to another part must hold 300 ms.
  A jump over 15% of the frame starts again.
- **With a question:** the spot is kept for 8 s after the hand comes down, because a spoken question often finishes later. It
  goes with one question only, using the frame from the moment of pointing, not a later one where the hand has gone.

The question as sent:

```
Is this the TV stand? [Fingertip in the photo: 70% across, 52% down, on what the camera labelled "TV stand" (AI suggestion).]
```

**No server change was needed.** `/v1/tasks/{id}/ask` already takes a question and a frame, and the note fits the 500-character limit;
if it doesn't fit, the person's words go alone. Screens show the question in the person's own words, without the note.

## The rules it keeps

- **Nothing leaves the phone for this.** Detection is local. A frame kept for pointing stays in memory, is replaced about once a
  second, and leaves only if the person asks a question. The screen says so.
- **Labels stay marked as AI suggestions** on screen and in the note. Muse is free to disagree, and in testing it did.
- **Nothing here feeds the verification gate.** Only `TaskScreens` opts in. The inspection and evidence camera doesn't, and step
  check photos and the photo button never carry it. The person still decides when a step is done.
- **Off or harmless with no hand.** With no hand there is no state on screen, and the search slows to 4 looks a second. It can be
  turned off in Details. If the detector can't run, the camera works as before and Details says so.

### Restricted work

A pointing gesture can't turn referral-only guidance into hands-on instructions, for three reasons:

1. **It is off on professionals-only plans.** A finger reaching toward a breaker panel or a gas line is what those plans exist to
   avoid, so the app doesn't invite the gesture. Details says to keep hands back and tap an outline instead.
2. **The server guard still decides.** The note is part of the question, so `TaskSafety.Guard` reads it like the person's own words.
   On restricted work a hands-on answer is replaced by the referral whatever the note says.
3. **It can only add caution.** With no plan (Just ask), pointing at something labelled "breaker panel" puts those words in
   the question, which brings the referral in. `TaskSessionTests` covers both directions.

The note describes the picture and nothing else: no instruction, no claim of contact. A label can't break out of its quotes.

### If native code dies

A native crash can't be caught, and without a guard it would take down the camera on every visit. So a mark is written before the
first detection and cleared after it. If the mark is still there on the next visit, pointing stays off and Details says so; turning
the setting off and on tries again.

This was not hypothetical. On an Arm emulator on an Apple M4, the guest advertises SME but faults on it, and MediaPipe's CPU path
crashes with `SIGILL` on `rdsvl`. The S23 Ultra's chip doesn't advertise SME, so it should not be affected, but that is reasoning
until it runs there.

## What has been tested

| What | How | Result |
|---|---|---|
| Finger shape, part choice, steadying, no hand, low confidence, safety wording | `PointingTest`, positive and negative | Pass |
| Finger shape on **real model output** | Same model on a Mac, on MediaPipe's own photos; landmarks saved as a test fixture | Pointing up is pointing; thumbs-up, victory and fist aren't; a photo with no hand finds nothing |
| Referral can't be lifted, and can be added | `TaskSessionTests` | Pass |
| Full unit suite and `:app:assembleDebug` | Gradle | Pass |
| Ring, part outline, on-screen state, kept state then expiry | Emulator, with the recorded hand replayed in a throwaway build | Works: [named part](evidence/android/hand-pointing-replay-part.png) |
| Question and note reach real Muse | Same, against the real API | Server recorded the note above; Muse answered about that spot |
| Off on a real professionals-only plan | Same, with the replayed hand still feeding in | No state shown: [screen](evidence/android/hand-pointing-off-restricted.png) |
| Detector can't run | Emulator | No crash; Details says it is off |

**The emulator has never run the model.** No configuration on this Mac can: on the CPU it hits the SME fault, and on the GPU the
translator fails (ES 3.0 lacks compute shaders; ES 3.1 through SwiftShader fails in the delegate). The replay was a temporary patch
that fed recorded landmarks past the detector, and it was not committed. `HandDetectorPhotoTest` runs the real detector on real
photos, but skips itself on such an emulator.

## What needs the Galaxy S23 Ultra

First, over USB:

```bash
./gradlew :app:connectedDebugAndroidTest
```

This runs the real model on the phone. It uninstalls the app afterwards, so install again before the checks below.

1. **It runs at all.** Open Just ask: no crash, and Details shows "Finger pointing: On" without saying it couldn't run.
2. **Speed and heat.** Turn on diagnostics and read the `hand … ms, …/s` figure in Details, with camera assistance off, on, and on with
   spatial guidance. Watch for heat over 10 minutes.
3. **AR path.** Never exercised, because the emulator didn't enter AR. With spatial guidance on, is the ring on the fingertip?
   Also try the phone turned sideways.
4. **Real pointing poses.** With the phone in one hand and pointing with the other: across the picture, away from the camera, close up.
   Does it hold steady? Do a fist, an open hand and a hand holding a tool stay quiet?
5. **Does the part survive the hand?** Point at a detected part, with and without spatial guidance. This is the tracker risk above.
6. **Spoken question.** Point, say "is this the test button?", and lower the hand while speaking. Does the answer use the right spot?
7. **Hands and lighting.** Different skin tones, low light, gloves. Gloves are expected to be poor.
8. **Restricted.** On a professionals-only plan, confirm no state appears when pointing.

## Not in this slice

Glasses (hands are free there, so it may suit them best, but they use another frame source), the "Look" photo button, spoken cues for
the pointing state, touch detection, and masks.
