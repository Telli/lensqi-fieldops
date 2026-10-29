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
