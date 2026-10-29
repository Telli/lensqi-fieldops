# Demo storyboard: guided tasks and FieldProof

The main video introduces guided tasks, then the FieldProof inspection workflow. Genuine model recordings and
fake-model integration evidence must be labelled separately. Synthetic equipment images and simulated diagnostics
stay visibly labelled. Do not present the API journey test as footage of a person completing physical work.

## Current three-minute story

| Time | Scene | What to show and say |
| --- | --- | --- |
| 0:00–0:15 | The problem | A plausible cable photo with a failed simulated equipment check: “Looking done is not enough.” |
| 0:15–0:40 | DIY plan | Create “Mount a wired PoE IP camera above my garage door”; show AI-written label, tools, cautions and stop conditions. Use the genuine recorded plan if not making a live call. |
| 0:40–1:10 | Guided step | Show the step runner, instruction and camera. Ask a typed question; explain that speech is implemented but real-microphone task use remains unvalidated. Only show SAM outlines actually returned. |
| 1:10–1:30 | Step photo check | Show the recorded unrelated-photo `CantTell` result, then that the user still controls progression. A suggestion is not verification. |
| 1:30–1:45 | Safety boundary | Show the genuine breaker-panel referral plan. Explain that recognized restricted plans cannot contain hands-on steps; free-form referrals are a weaker safeguard. |
| 1:45–2:00 | Technician handoff | Show site `LAB-RACK-01`, asset `LAB-SW-01`, and the linked inspection. The automated API journey is fake-model integration evidence; label it as such. A continuous user-operated hardware demonstration is still pending. |
| 2:00–2:35 | FieldProof | Use the inspection sequence below: reject reused evidence; reject a changed photo with a failing simulated check; accept fresh evidence plus human review and a passing simulated check. Keep the SIMULATED badge visible. |
| 2:35–2:50 | Receipt | Show the offline verifier passing, then rejecting a one-byte change in a separate copy. |
| 2:50–3:00 | Measured close | “25 synthetic scenarios with simulated diagnostics; zero false completions in that evaluation.” Close with the repository and tagline. |

The inspection-only shot list below is a reference for recording the FieldProof segment, not an additional three
minutes. Rehearse the current task navigation; older inspection screenshots predate the new mode home. “Just ask”
is an optional replacement for part of the DIY plan segment if time permits.

## Setup (before recording)

| Item | How |
|---|---|
| API, Simulated mode, clean data | `dotnet run --project src/LensQi.FieldOps.Api --no-launch-profile --urls http://127.0.0.1:5199 --Storage:DataDirectory data/demo` with `MODEL_API_KEY` in `.env` |
| Technician app | Chrome at 1280×800, <http://127.0.0.1:5199/m>: the app renders inside a phone frame on a desktop. Or a real phone on the same network (debug build of the Android app, or the web app). |
| Operations dashboard | <http://127.0.0.1:5199/ops> in a second window |
| Images (pick with the gallery button on the camera screen) | `fixtures/telecom-lab/eval/images/`: `a01-labels-occluded.jpg`, `c01-clear-p2.jpg`, `c03-clear-rotated.jpg`, `r03-repaired-rotated-post.jpg` (imported photos are labelled "imported", never "camera") |
| Android clips | Recorded separately from the emulator with `adb shell screenrecord`; see the Android segment |
| Terminal | 120×30, large font, in the repository root |

Record each scene as its own clip, so the edit never depends on one perfect take. The reference stills for every scene
are `docs/evidence/ui-v2/01–09`.

## Inspection recording reference (older three-minute cut)

| Time | Scene | Exact actions | What the screen shows |
|---|---|---|---|
| 0:00–0:12 | **Cold open: the fake fix is refused** | Clip from scene 5. | Two check rows (visual ✓, equipment check ✕) and the red **Verification failed** card: "The equipment still fails its check after the repair." **SIMULATED** in the top bar. Caption: "The cable looks fine. The endpoint is still down. Not verified." |
| 0:12–0:25 | **Start** | Mode home → **Technician** → **Start an inspection** → **Lab Switch 01**. | Greeting, live counts, the asset list. Caption: "The asset comes from its label; the AI never guesses a serial." |
| 0:25–0:55 | **Uncertainty, then a requested view** | Camera opens → gallery → `a01-labels-occluded.jpg`. Wait for *Saving photo ✓ Observing ✓ Outlining the part ✓*. **Continue investigation**: step 3 asks for a closer view → **Capture another view** → gallery → `c01-clear-p2.jpg`. | The AI outline of the yellow cable on the photo; "Yellow cable near end · AI hypothesis" with the reason; "Next: Move closer and show the switch faceplate…". Caption: "Genuine Muse Spark and SAM 3.1 calls. The AI asks instead of guessing." (`ui-v2/02`, `03`) |
| 0:55–1:15 | **The fault is measured** | Investigation step 5 → **Open approved checks** → **Demo controls** (endpoint off) → **Run approved check**. | **Fail — endpoint unreachable**, Source *Sim*, "What a pass proves: …HTTP 200 at test time (not physical port identity)". Caption: "Only checks the site approved. Documentation guides; it never proves a repair." |
| 1:15–1:50 | **Two fake fixes** | Back → Repair: plan "Reseat the yellow cable in port 2" → **Save** → "Reseated the yellow cable in port 2" → **Repair completed — verify**. (a) **Take post-repair photo** → `c01-clear-p2.jpg` again → **Photo not accepted · Same photo**. (b) **Take another photo** → `c03-clear-rotated.jpg` → **Change detected** → **Continue to verification** → **Looks right** ×3 → **I reviewed this evidence myself** → **Run verification**. | (a) "This is the same picture as an earlier one." (`ui-v2/04`) (b) Visual ✓, Equipment check ✕, **Verification failed** (`ui-v2/05`). Caption: "A changed photo is necessary, never sufficient." |
| 1:50–2:15 | **The real fix** | **Review equipment check** → Demo controls (endpoint on) → back → "Restored the endpoint and reseated the cable" → **Fixed it again — verify** → **Take post-repair photo** → `r03-repaired-rotated-post.jpg` → **Looks right** ×3 → confirm → **Run verification**. | Both checks ✓, **Verified complete**, "SIMULATED · The equipment check was simulated, not a live measurement." Attempts ✕ ✓. (`ui-v2/06`) |
| 2:15–2:35 | **Android (20 s)** | Emulator clips: airplane mode on → capture → "Awaiting upload · sha …"; airplane mode off → the photo appears; the verified banner and `receipt.html` in the app. | Caption: "Android client on the emulator: offline capture, hash on device, automatic sync. Same server decision." Corner label **Android emulator**. |
| 2:35–2:45 | **A live measurement (10 s)** | Open `docs/evidence/receipt-loopback/receipt.html` (or `receipt-live/` if the owner's LAN run exists). | Label **LIVE_LOCAL_PROBE (loopback)**: "a real HTTP probe of a local server standing in for the lab endpoint". `VerifiedCompleteLive` appears only here. |
| 2:45–3:00 | **Proof you can check** | **View FieldProof receipt** → before/after photos, decisions ✕ ✓, **Check now** → "Intact". Cut to the ops dashboard overview, then a terminal: `verify-receipt` PASS and a one-byte flip FAIL. | Receipt (`ui-v2/07`), dashboard (`ui-v2/08`). Caption: "25 labelled scenarios: 0 false completions with the gate; a single model look would have accepted 4 of 5 fake fixes." Repository URL. |

## Words to use and to avoid

- Say "the endpoint answered HTTP 200 at test time", never "the cable is fixed" or "the port is correct". The switch is
  unmanaged and the probe cannot see port identity.
- Say "simulated endpoint" every time the main story probes. Never show `VerifiedCompleteLive` outside the live segment.
- Say "the model proposes; code decides". Never say the model verified anything.
- The images are renders; no photograph of real equipment is shown unless the owner's real-photo set exists, and
  then it is captioned as such.

## Cutlines

| If this is missing on the day | Do this |
|---|---|
| Android clips | Drop 2:15–2:35 and give the time to the fake-fix scene. Never show a mock-up. |
| Owner's LAN run | Use the loopback bundle with its loopback label; never relabel it as lab hardware. |
| Muse or SAM down while recording | Re-record the scene; never splice recorded responses into a "live" shot. If it persists, show the recorded evidence files named as recordings. |
| Over three minutes | Shorten 0:12–0:25 first, then the documentation beat in 0:55–1:15. |

## Rehearsal check

Before recording, run `scripts/clean_room_check.sh`; it replays the same fake-fix and real-fix sequence over HTTP from a
fresh clone and must print `RESULT: PASS`.
