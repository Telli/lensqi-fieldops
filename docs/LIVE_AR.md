# Live camera assistance

The browser camera and native Android phone camera continuously track detected parts while periodically requesting fresh detections and recommendations. Open an inspection, open its camera, and tap **Start live assist**. **Pause live assist** stops analysis. Taking a photo pauses assistance and continues the existing evidence workflow.

The Android **Glasses** screen also accepts the continuous DAT glasses video stream and sends live feedback to speech and compatible glasses displays, including with the phone locked. See [Glasses setup and verification](GLASSES.md#continuous-assistance-2026-10-28). The browser hands-free view continues to display saved inspection photos.

The live view shows moving outlines, tracking status, local updates per second, and the age of AI guidance. Immediate on-device feedback identifies dark, blurred or rapidly changing views. AI guidance asks for a better view or recommends the next inspection check. Lost objects disappear rather than remaining attached to unrelated pixels.

## How it works

1. The camera preview runs independently of analysis. Every approximately 100 ms, the client reduces a frame to a 160-pixel grayscale image and tracks textured patches inside each detected box. Work runs in a browser worker or the Android CameraX analyzer executor.
2. At most every 4 seconds, a JPEG up to 640 pixels on its longest side goes to SAM 3.1. Detection uses concepts configured by the inspection's expected equipment twin, currently at most two concepts and two boxes per concept.
3. A separate request, at most every 12 seconds, asks Muse Spark for guidance. The live-only profile uses minimal reasoning; saved-photo assessment keeps its existing profile. Neither request blocks local tracking.
4. Frame IDs and a bounded frame history align delayed detections with the current view. Missing frame history, occlusion, inconsistent motion and expired detections clear boxes. Guidance expires after 20 seconds or a substantial scene change.

These are continuous 2D image trackers with periodic AI inference, designed to work across browsers, glasses streams and Android cameras without requiring 3D SLAM hardware; on ARCore-capable Android phones, an optional ARCore 3D spatial-anchor and depth mode can raycast SAM 2D detections into 3D world-locked anchors and compute metric camera-to-target distance. In 2D mode, rapid motion, weak texture, rotation and occlusion can lose a target until the next detection reacquires it. Model latency and detection accuracy vary with equipment, lighting, network and provider load. Targets are the fixture's configured parts, not unrestricted recognition of every object.

## Frame and session handling

- Preview alone sends no frames. The Start control explains that sampled frames go to AI. FieldOps does not persist these frames as inspection evidence; provider processing is subject to the provider's own data handling.
- Pausing or leaving the camera stops requests and clears tracking state. Browser and phone-camera assistance also stop in the background; glasses assistance explicitly continues through its foreground service until stopped. Only shutter photos enter the evidence queue. Live responses do not change inspection stages, view-request budgets or repair verdicts.
- Detection and guidance each allow only one in-flight request per client and inspection. The API adds cooldowns, per-IP limits, a global concurrency bound, timeouts, image-size limits and response validation. Recovery is automatic after transient provider failures or rate limits.
- `POST /v1/inspections/{id}/live/detect` and `/live/guide` accept multipart `image` and `frameId`. Successful responses echo `frameId`, timing and refresh interval. Detection returns normalized boxes; guidance returns a recommendation and source-frame findings. Responses are `no-store`.
- `LiveVision` in API configuration controls minimum refresh intervals, provider timeout and requests per minute. Clients retain minimum 4-second detection / 12-second guidance intervals even if a server is configured faster.

## Verification on 2026-10-28

| Check | Result |
| --- | --- |
| Genuine SAM request on repository lab fixture | 0.84–1.39 seconds; detected the yellow Ethernet cable |
| Genuine Muse live-profile request on the same fixture | 3.13 seconds; recommended capture followed by an approved equipment check |
| Browser moving-video check with recorded provider detections | Followed 32 pixels of movement without another model call, about 8 local updates/second; occlusion cleared the box |
| Native Android emulator camera | CameraX analysis ran at about 8–9 updates/second; Start/Pause and native preview rendered without a runtime crash |
| Automated suites | 175 .NET tests, 21 web tests and 91 Android unit tests passed (including the glasses follow-up); production web bundle and debug APK built |

The provider timings are bounded observations, not throughput benchmarks. The browser test uses a synthetic moving camera source and recorded genuine detections. The phone-camera emulator check uses its virtual-room camera; the subsequent glasses check uses the SDK mock's HEVC lab clip and real providers. Neither establishes tracking accuracy on physical hardware. Physical Android cameras and glasses, camera rotation across devices, Bluetooth audio routing and thermal/battery behavior remain to be validated.

See [evidence and screenshots](evidence/live-ar/README.md).

## Repeat the checks

```sh
dotnet test --nologo
npm --prefix apps/web test
npm --prefix apps/web run build
cd apps/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

With a local server running, use `AR_SMOKE_URL` and `PLAYWRIGHT_MODULE` if needed:

```sh
AR_SMOKE_URL=http://127.0.0.1:5199 node scripts/live_ar_smoke.mjs
```

The smoke check uses Chrome by default and performs no paid inference. `scripts/live_ar_probe.py --base http://127.0.0.1:PORT` is a separate, explicitly live provider check: it creates a fresh fixture inspection, calls both providers once and asserts that no preview evidence was saved. Point it at an isolated test storage directory. It requires the repository Python environment and configured model credentials. No database or Docker is needed for these checks.
