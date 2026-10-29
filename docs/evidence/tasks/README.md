# Guided tasks — genuine model calls, 2026-10-28

All calls went to the Meta Model API (`muse-spark-1.3`) through a local API on the `guided-tasks` branch with an isolated data folder. Each file is the API's response as returned, including the prompt and response digests where the endpoint reports them.

| File | Request | Result |
| --- | --- | --- |
| `plan-oil.json` | DIY: "Change the engine oil in my 2025 Honda Civic" (1.5L turbo, socket set, drain pan) | 9 steps, Moderate risk, about 45 min. Cautions on every step (hot oil, jack stands), stop-if conditions, a required photo check on reinstalling the drain plug. Oil grade, capacity and torque are referred to the owner's manual, not invented. |
| `plan-cam.json` | DIY: "Mount a wired PoE IP camera above my garage door" (brick wall) | 9 steps, Moderate risk, ladder and masonry-drilling cautions, photo checks on the mount, cable and aim. |
| `plan-panel.json` | DIY: "Replace the breaker panel in my garage" | Restricted `MainsElectrical`, professional required, every step observe/prepare only (no hands-on step), ending with calling a licensed electrician. The server would have rejected any hands-on step. |
| `ask-oil.json` | Question during step 2 of the oil plan, no camera frame: "Where exactly should the jack stands go on this car?" | Says it cannot see the lift points, tells the user where to point the camera, defers the exact positions to the owner's manual. |
| `check-oil.json` | Step 2 photo check with an unrelated photo (the lab switch fixture image) | `CantTell`, naming what the photo actually shows. A suggestion only; the step stays open until the user marks it done. |

The first oil-change attempt before these was rejected by the plan validator (an outline phrase "owner's manual" with an apostrophe); the validator now accepts apostrophes and a rejected plan is re-requested once with the reason. No live-camera frames, voice recognition or phone runs are recorded here.

## Android on the emulator (2026-10-28)

The rebuilt app on the arm64 emulator (virtual-room camera, no ARCore), pointed at the same local API:

- `android-plan.png`: the IP-camera plan screen (AI-written label, risk, time, tools, parts, per-step cautions and stop-if conditions).
- `android-step-runner.png`: step 1 on the live camera with the instruction, cautions and the Back / Ask / Check / Done controls.
- `android-step-check.png`: a full-resolution check photo of the virtual room sent to the real step check: `CantTell`, naming that no garage door, brick wall or camera is visible.
- `android-live-coaching.png`: live coaching on step 2 with genuine SAM and Muse calls; the guidance stays within the step ("go to the PoE switch inside to switch it off…").

Voice input was not exercised (no microphone audio on the emulator); spoken output was not checked by ear. No phone run of the task flow yet.

## Technician task on the site record (2026-10-28)

`plan-technician.json`: Technician mode, "Reseat the yellow cable on the lab switch and confirm the link to Node A", on `LAB-RACK-01 / LAB-SW-01`. The genuine plan used the site record (port P2, yellow cable, Node A), Low risk, 7 steps with two hands-on. The steps were then skipped through the API (no work done) and the task was linked to FieldProof inspection `I-ebdf6b18bb3b` on the same asset; the ops dashboard's Guided tasks page lists it with that link. No verification was run on that inspection.

## Free-form help, "Just ask" (2026-10-29)

- `freeform-session.json`: a free-form session from the browser. A typed question about a router's red internet light and "No beacon heard" got a genuine answer (check the WAN cable, then an ISP outage, then point the camera at the router lights).
- `ask-freeform-gas.json`: "How do I disconnect the gas line behind my stove so I can move it?" in free-form help. The genuine answer declines to walk through it, says gas work needs a qualified professional and offers to help observe and document. The server would have appended the referral if the model had not.
- `android-freeform.png`: Android free-form help on the emulator. The photo button took a full-resolution photo, reduced it with its EXIF rotation applied, and the genuine answer describes the virtual room and its test pattern. (An earlier attempt before the rotation fix was described as sideways, and the answer did not appear until the screen reloaded the task; both fixed.)

## Continuous technician API journey (October 28 Pacific)

[Journey evidence and reproduction](technician-journey.md): plan accepted, both steps completed, linked inspection,
changed-photo fake fix denied, fresh evidence and passing simulated check produce `VerifiedCompleteSimulated`,
then offline receipt verification and tamper rejection. **Fake models and simulated diagnostics**, separate from the
genuine calls above. The full .NET suite passed 211 tests. No inspection gate or endpoint changes were needed.

## Open-model feasibility

[Feasibility record](open-model-feasibility.md): deferred until hackathon alias/access confirmation and a locally
configured Baseten credential. No Open integration, genuine Open call or bonus eligibility is claimed.
