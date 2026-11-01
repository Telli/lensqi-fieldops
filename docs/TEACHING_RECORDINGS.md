# Teaching-session recorder

Android now has a **Teaching recordings** entry on Home and a **Record a demonstration of this task** entry on a task's plan. This is the first observation-data capture slice of Teach LensQi. It does not generate or publish reusable procedures, train a model, or control a robot.

## Use

1. Open Teaching recordings and choose Record a teaching session. Enter a goal, equipment/model/fixture identifier, and a demonstrator alias.
2. Confirm permission to record the people and workspace. Training permission is a separate, optional choice, off by default.
3. Start recording. The rear camera writes a clean, silent MP4 with no app overlays. Keep hands, tools and contact points visible. A tripod or another person holding the phone is useful.
4. Mark a task step where an attached plan exists. Select an action type and object, then mark its start/end. Add observations, mistakes and recoveries as typed notes or push-to-talk dictation. Dictation saves recognized text only; Android's recognition service may process audio remotely. Recording itself does not open the microphone.
5. Stop and review the video. Set a human-assessed outcome (Succeeded, Failed, Uncertain), append corrections, and mark the video/annotations reviewed. Failed attempts are useful demonstrations and are retained.
6. Export a ZIP through Android's document picker. Nothing uploads automatically. Review for private information before sharing. Delete individual recordings from the library when no longer needed.

Step markers and human outcomes do not complete task steps or establish a FieldProof outcome. Task context is read-only. An attached AI plan keeps its source note; server event snapshots preserve their timestamps and diagnostic modes. Those events may predate the demonstration and must not be used as newly measured outcomes of the video.

## Limits and lifecycle

- CameraX targets HD, with a supported-quality fallback. Exported dimensions and frame timestamps come from the finalized video, not the requested quality/frame rate.
- Each session stops at 10 minutes or 256 MB. New capture requires 300 MB free and less than 768 MB of existing local teaching data. Export/delete old sessions to reclaim space.
- Backgrounding or closing the camera stops recording; an interruption is labelled Interrupted. Camera errors or unreadable video are Failed. Process death retains the partial files and marks an unfinished record Interrupted on next launch. An unfinalized MP4 may not be playable/exportable; it can be deleted explicitly.
- Training eligibility requires training consent, review after the latest annotation/correction, and a Complete recording. Interrupted video can be exported if finalized, but is ineligible. Eligibility is a local workflow label, not proof of data rights or research suitability.
- App-private storage survives relaunch but is removed by uninstall. Exports remain wherever the user saved them.

## ZIP v1

| File | Contents |
| --- | --- |
| `dataset.json` | Format/version, session, review and training labels, SHA-256 inventory, timing limitations and unavailable sensors |
| `session.json` | Goal, equipment, demonstrator alias, consent/version, device, source task ID, recording state and video quality summary |
| `video.mp4` | Original camera recording; no audio or overlays |
| `frames.csv` | Frame index, actual MP4 presentation timestamp in microseconds, time relative to first frame |
| `timeline.jsonl` | Sequential annotations and recording clock samples; original annotations remain after corrections |
| `context.json` | Optional task snapshot and server events fetched after capture; unavailable offline snapshots are not invented |

Capture events use `elapsedRealtimeNanos`, a monotonic device clock. CameraX start/status/finalize callbacks pair receipt times with the reported recording duration. `videoOffsetEstimateNs` extrapolates from the most recent pair. **Callback latency is unmeasured; this is approximate alignment, not a sensor-synchronized trajectory.** Consumers should retain the raw clock pairs and actual MP4 timestamps. Post-capture outcomes, reviews and corrections have wall time and null capture time, so rebooting does not fabricate video timing. A correction references the original event sequence. Its text supersedes the original text; for ActionStart it also supplies replacement actionType and objectName. If several corrections reference one event, the latest sequence wins; the original event and all corrections remain in the timeline.

Video quality reports dimensions, rotation, frame count, median/max presentation gaps and gaps larger than both three times the median and 100 ms. These metrics identify timing gaps; they do not establish sharpness, visibility, demonstration correctness or accurate hand tracking.

Validate an export without extracting it:

```sh
python3 scripts/validate_teaching_export.py /path/to/lensqi-teaching-session.zip
```

The validator checks the file allowlist, hashes, session/consent consistency, sequential event IDs, monotonic capture clock, frame index and training-label derivation. It does not decode the MP4 or validate the physical action. Use a media decoder for further video checks.

## Research boundary and next experiment

This is an observation dataset, not a LeRobot action dataset. It has no robot actions, joint states, hand pose, calibrated camera trajectories, depth or force/torque. Camera motion is not hand motion. Instrumented capture and a chosen robot embodiment are needed before training physical execution.

Collect a narrow bench task with multiple demonstrators, variations, mistakes and recoveries. Review temporal labels against the footage. Split evaluation by demonstrator/equipment, and compare step recognition or mistake detection with and without this data. No dataset-quality improvement or robot-training result is claimed by this implementation.

Reference formats and methods: [LeRobot datasets](https://huggingface.co/docs/lerobot/lerobot-dataset-v3), [EgoDex](https://machinelearning.apple.com/research/egodex-learning-dexterous-manipulation), [UMI](https://umi-gripper.github.io/).

## Verification

See [emulator evidence, test results, and release-tooling verification](evidence/android/teaching/README.md).
