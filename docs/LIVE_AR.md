# Live camera assistance

The browser camera and native Android phone camera continuously track detected parts while periodically requesting fresh detections and recommendations. Open an inspection, open its camera, and tap **Start live assist**. **Pause live assist** stops analysis. Taking a photo pauses assistance and continues the existing evidence workflow.

The Android **Glasses** screen also accepts the continuous DAT glasses video stream and sends live feedback to speech and compatible glasses displays, including with the phone locked. See [Glasses setup and verification](GLASSES.md#continuous-assistance-2026-10-28). The browser hands-free view continues to display saved inspection photos.

The live view shows moving outlines, tracking status, local updates per second, and the age of AI guidance. Immediate on-device feedback identifies dark, blurred or rapidly changing views. AI guidance asks for a better view or recommends the next inspection check. Lost objects disappear rather than remaining attached to unrelated pixels.

## How it works

1. The camera preview runs independently of analysis. Every approximately 100 ms, the client reduces a frame to a 160-pixel grayscale image and tracks textured patches inside each detected box. Work runs in a browser worker or the Android CameraX analyzer executor.
2. At most every 4 seconds, a JPEG up to 640 pixels on its longest side goes to SAM 3.1. Detection outlines up to three concepts, two boxes each: the short noun phrases the latest guidance named (for example "error message" or "status LED"), then the parts the site record configures. Concept text never comes from the client.
3. A separate request, at most every 12 seconds, asks Muse Spark what is in view and what to do next. It is not limited to the site record's equipment: other devices, cables, indicator lights and error or status text on screens are in scope, and readable screen text is quoted word for word ("On screen: …"). The server rejects answers with links, over-long text, or focus terms that are not plain 1–4 word noun phrases. The live-only profile uses minimal reasoning. Neither request blocks local tracking.
4. Frame IDs and a bounded frame history align delayed detections with the current view. Missing frame history, occlusion, inconsistent motion and expired detections clear boxes. Guidance expires after 20 seconds or a substantial scene change.

These are continuous 2D image trackers with periodic AI inference that work across browsers, glasses streams and Android cameras. Android phones with Google Play Services for AR add the ARCore mode below. In 2D mode, rapid motion, weak texture, rotation and occlusion can lose a target until the next detection reacquires it. Model latency and detection accuracy vary with equipment, lighting, network and provider load. Live guidance can describe anything in view, but repair verification still uses only the site record's parts, a new photo, the technician's review and the approved equipment check.

## ARCore mode (Android phones)

When Google Play Services for AR is installed, **3D AR: On** runs live assistance on an ARCore session instead of CameraX. On a supported phone without it, **Get 3D AR** starts the Play Store install. Elsewhere (emulators without ARCore, unsupported phones, browsers, glasses) there is no 3D control and assistance stays 2D.

1. ARCore owns the camera and draws it full screen. Every 100 ms its CPU image is copied, made upright and fed to the same 2D tracker and quality checks; uploads use the same endpoints and limits.
2. For a frame that may be uploaded for detection, the app keeps ARCore's camera pose and projection at that instant and the depth it measured: the Depth API image on phones that support it, otherwise hit tests against the feature points and planes ARCore is tracking.
3. When SAM's boxes for that frame return, each box with a depth measurement inside it becomes an ARCore anchor at the unprojected point, with its physical width and height. A box with no measurement stays 2D and gets no distance.
4. Each frame projects the anchors through the current camera. A part followed by both keeps its live 2D box and shows the distance from the camera to its anchor (`≈ 42 cm`); an anchor without a live track is drawn fainter with "seen N s ago". Distance coaching ("Move about 18 cm closer…") appears only from those measured distances.
5. An anchor is removed when a later detection frame looks straight at it and does not find the part, when it goes 20 seconds without confirmation, or when ARCore stops tracking it. Two parts with the same concept stay two anchors.
6. The shutter hands the camera back to CameraX and takes the evidence photo at full resolution once its preview streams. Anchors are not kept across that switch.

The status pill reports ARCore's own state ("Live AR · ARCore tracking + depth", "AR paused · add light", …). If the session cannot start or the camera stays busy, the screen says so and continues in 2D.

## Guided tasks and free-form help (added October 28–29)

The task step runner (web `/m/t/:id/step/:stepId`, Android step screen) and free-form help (`/m/t/:id/live`, Android
`FreeformScreen`) use the same camera, tracker and limits through `POST /v1/tasks/{id}/live/detect` and `/live/guide`.
The live handler is shared with inspections; only the concept list and the guidance call differ.

- Detection outlines the current step's `lookFor` phrases first, then the focus phrases of the latest step guidance.
  Concept text still never comes from the client. Free-form help has no step, so only guidance focus phrases are used.
- Guidance comes from `StepCoach` with the goal, the accepted plan and the current step as context, instead of the site
  record. Inspections keep `LiveSceneService` and its prompt.
- Live coaching on a step is opt-in, as for inspections. The endpoints refuse a closed task or a planned task without a
  plan (`task_not_active`).
- On Android the step screen reuses `CameraScreen`, so ARCore runs there on a supported phone. Distance coaching still
  uses all tracked objects; it is not yet aimed at the step's main part.

Checked in the browser pane and on the emulator with genuine Muse and SAM calls
(`evidence/tasks/android-live-coaching.png`). Not yet run on a phone, so no ARCore anchor has been seen on a task step.

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
| Automated suites | 175 .NET tests, 23 web tests and 104 Android unit tests passed, including ARCore geometry tests (`ArSpatialTest`: unprojection round trip with ARCore-style matrices, distance equals the measured depth, no depth means no anchor, world-locking under camera motion, refutation and expiry); production web bundle and debug APK built |
| ARCore on the emulator | Not possible: the arm64 emulator has no Google Play Services for AR. The camera showed no 3D control and ran 2D assistance at 8 updates/second with genuine AI guidance (`android-2d-fallback-no-arcore.png`) |
| ARCore on a phone (Galaxy S23 Ultra, Android 16, Google Play Services for AR 1.57) | The app detected ARCore and offered **3D AR: On**. Live assist ran on an ARCore session: camera drawn full screen through ARCore, status "Live AR · ARCore tracking + depth", about 9 local updates/second, genuine SAM and Muse calls (SAM 0.4–1.6 s, Muse 1.5–2.6 s). Rotating to landscape kept ARCore running. The phone was aimed at desk monitors, so no fixture part was detected and **no anchor or distance was produced**; anchors and distance on real equipment, and the AR-to-CameraX shutter hand-off, remain untested on hardware. That run also showed live assist switching off on rotation, fixed afterwards and not yet re-run on the phone |

The provider timings are bounded observations, not throughput benchmarks. The browser test uses a synthetic moving camera source and recorded genuine detections. The phone-camera emulator check uses its virtual-room camera; the subsequent glasses check uses the SDK mock's HEVC lab clip and real providers. Neither establishes tracking accuracy on physical hardware. Physical Android cameras and glasses, the ARCore mode on an ARCore phone, camera rotation across devices, Bluetooth audio routing and thermal/battery behavior remain to be validated.

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
