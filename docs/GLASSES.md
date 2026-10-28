# Meta glasses (Wearables Device Access Toolkit) in the Android app

Hands-free live assistance and capture with Ray-Ban Meta glasses through Meta's Wearables Device Access Toolkit (DAT) 1.0.1
(`com.meta.wearable:mwdat-core`, `-camera`, `-inputs`, `-display`; `-mockdevice` in debug builds only).

**Status: integrated and exercised against Meta's Mock Device Kit on an Android emulator. Not tested on real glasses:
no hardware was available.**

## Continuous assistance (2026-10-28)

Open an inspection → **Glasses** → **Start glasses camera** → **Start live assist**. After the explicit Start action,
sampled glasses frames are sent to the shared live-analysis endpoints, and local tracking continues between detections.
The phone shows tracked boxes, guidance age and update rate. The wearer receives changing guidance through Android
text-to-speech using the selected media output (Bluetooth glasses when routed there); compatible Meta Ray-Ban Display
glasses also receive text guidance and the Capture control. Ordinary Ray-Ban Meta glasses have no visual overlay in the lens.

The glasses stream feeds a headless HEVC decoder, so inference does not depend on a visible phone preview. The analysis
decoder stays primed while the glasses camera is running: DAT 1.0.1 has no request-keyframe operation, and discarding that
decoder on pause made late starts/resumes wait indefinitely on the mock stream. RGB conversion, tracking and remote
requests run only while live assistance is enabled. Camera stop releases both decoders.

`GlassesLiveService` owns the analysis session, speech and display feedback. A `connectedDevice` foreground service and
a renewable, bounded partial wake lock keep it active with the phone locked. Its notification has **Mute/Unmute** and
**Stop camera** actions. Speech waits for guidance to settle for 1.5 seconds, spaces new utterances by at least 8 seconds,
and does not repeat unchanged advice. Removing the app from Recents stops the camera; there is no automatic restart.

**Pause live assist** stops frame analysis, cancels in-flight requests and clears tracking while retaining the visible
camera preview. Closing the glasses screen, stopping the camera or capturing a photo also ends assistance. Backgrounding
a preview without live assistance stops its camera. A paused/disconnected or stalled stream clears old boxes and guidance;
an unexplained 30-second frame outage ends the session with recovery instructions. A glasses-initiated pause can resume.

Frames remain ephemeral: no saved photos, events, stage changes, offline queue entries or automatic repair decisions.
Low-light/focus coaching helps before model guidance arrives; a current, validated recommendation takes priority over
those coarse full-frame heuristics. Both phone and browser clients wait the requested refresh interval after a successful
response, avoiding a race with the server's cooldown.

Verification: the SDK Mock Device Kit's HEVC lab video reached the real SAM/Muse endpoints; the cable was outlined and
tracked at approximately 6–7 local updates/second. New inference requests continued during a seven-second phone lock;
pause stopped requests, resume decoded frames, and inspection events remained unchanged. This is a synthetic video on
an emulator, not a physical-glasses accuracy or battery benchmark. Bluetooth playback has not been heard on real glasses.
Screenshots and the bounded check are in [live-glasses evidence](evidence/live-glasses/README.md).

The notification's Stop camera action was also exercised: it ended the foreground service and returned the UI to a
connected camera-ready state. Late display responses after teardown are ignored instead of producing a false error.

`./gradlew :app:testDebugUnitTest :app:assembleDebug` passed with 91 tests. A separate release build reached compilation
but its lint task crashed in AndroidX `NonNullableMutableLiveDataDetector` with a Kotlin analysis-API class/interface
incompatibility. No lint checks were disabled; release packaging needs that toolchain issue resolved.

## What it does

Inspection screen, **Use glasses** (initial observation and the post-repair photo), opens the `glasses` route:

- **Connection**: asks for Bluetooth, initialises DAT, then shows one status chip: Glasses unavailable / Not registered /
  Registering / No glasses connected / Glasses connected / Starting camera / Paused on glasses / Streaming.
  **Connect with Meta AI** calls `Wearables.startRegistration`, which hands over to the Meta AI app.
- **Camera**: a `DeviceSession` (auto device selector); once it reports STARTED the app adds the camera (504x896,
  24 fps, compressed HEVC) and decodes the stream with MediaCodec onto the preview. The camera permission is checked
  with `Wearables.checkPermissionStatus` and requested through `Wearables.RequestPermissionContract` (Meta AI).
- **Capture**: the phone shutter, the glasses' capture button / touchpad select (DAT Inputs), or the Capture button on a
  Meta Ray-Ban Display. `Stream.capturePhoto()` returns HEIC or a Bitmap; the app writes an upright full-resolution
  JPEG (EXIF orientation applied, quality 95). That JPEG is the original the offline queue hashes and uploads.
- **Instruction**: the inspection's camera prompt, with the stage as a status line. It is read aloud with Android
  TextToSpeech each time it changes (mute toggle). The audio goes to the phone's output, which is the glasses when they
  are the connected Bluetooth audio device. On a Meta Ray-Ban Display the prompt, status and a Capture button are also
  shown on the glasses via `mwdat-display`. On glasses without a display the screen says so and nothing is sent.
- **Evidence origin**: real glasses upload `source=Glasses`. The Mock Device Kit uploads `source=MockWearables` and is
  shown as "Mock glasses" (badge on the screen, the preview and the instruction, and on the photo in the inspection). The
  origin is fixed when the capture starts, so a mock photo is never relabelled. The API parses `source` into
  `EvidenceOrigin` (both values exist). Release builds have no Mock Device Kit (`src/release` stub), so they cannot
  produce MockWearables evidence.

Code: `apps/android/app/src/main/java/com/lensqi/fieldops/wearables/` (`GlassesSource`, `DatGlassesSource`,
`HevcPreview`, `GlassesPhotos`, `GlassesState`), `ui/glasses/` (`GlassesScreen`, `GlassesViewModel`),
`src/debug/.../MockGlassesImpl.kt` (Mock Device Kit) and `src/release/.../MockGlassesImpl.kt` (stub).

## Build changes

- `minSdk` 26 -> **31**. DAT 1.0.1 requires API 31.
- The DAT artifacts are built with Kotlin 2.2 metadata, so the toolchain moved: Kotlin 2.0.21 -> 2.2.21, KSP
  2.2.21-2.0.4, Hilt 2.52 -> 2.57.2, Room 2.6.1 -> 2.7.2 (Room 2.6 fails under KSP2). AGP 8.7.3, Gradle 8.11.1 and
  compileSdk 35 are unchanged.
- The debug APK is about 100 MB because DAT ships native libraries for all ABIs. ABI splits are not configured.
- Manifest: `BLUETOOTH`, `BLUETOOTH_CONNECT`, the `com.meta.wearable.mwdat.APPLICATION_ID` / `CLIENT_TOKEN`
  meta-data, and a `lensqi-fieldops://` VIEW intent filter on `MainActivity` so Meta AI can return to the app. Live assistance
  adds a non-exported `connectedDevice` foreground service, notification permission and a service-owned partial wake lock.

## Registering real glasses

1. Install the Meta AI app on the phone and pair the glasses there.
2. **Development**: in Meta AI, turn on Developer Mode for the glasses (it is per device and can reset after updates).
   In Developer Mode attestation is skipped. Meta's docs use `0` for both values.
   **Production**: create the app in the Wearables Developer Center and copy its application ID and client token.
3. Put them in the untracked `apps/android/local.properties` (never commit them):

   ```properties
   mwdat_application_id=0
   mwdat_client_token=0
   ```

   They are injected as manifest placeholders. The defaults are empty, and with empty values the SDK logs
   `TOKEN_NOT_CONFIGURED`.
4. Build, open an inspection, tap **Use glasses**, then **Connect with Meta AI** and approve LensQi FieldOps. Next,
   **Start glasses camera** and allow camera access when Meta AI asks. Tap **Start live assist** for continuous tracking
   and spoken guidance; select the glasses as the phone's media audio output.
5. The glasses' capture button needs the experimental DAT Inputs capability approved for the app in the Developer
   Center. If it is not approved, the screen says the glasses' button is unavailable and the phone shutter still works.

## Evidence policy for mock photos

Decided 2026-10-28: a `MockWearables` photo is treated like an imported (`RecordedFixture`) photo. It can be the
accepted post-repair photo and count toward verification, and it is labelled as mock everywhere it appears
(Android photo strip and review screen, web hands-free view; the receipt JSON keeps the origin on every photo event). The verification policy does not change for it: the fresh
equipment check is still required, and a simulated check still yields `VerifiedCompleteSimulated`, never live.

## What was verified (emulator-5554, API 36 arm64, Mock Device Kit, API at 10.0.2.2:5199)

- Real path without hardware: DAT initialises and registration reports *unavailable*. **Connect with Meta AI** returns
  `META_AI_NOT_INSTALLED`, and the screen shows that. (`docs/evidence/android/24-glasses-02-real-registration-no-meta-ai.png`)
- Mock Ray-Ban Meta: enabled from the glasses screen, it registers, connects and streams (chip "Streaming").
  - The phone-camera feed decodes live onto the preview (`24-glasses-03b-mock-streaming-phone-camera-feed.png`).
  - An HEVC clip of the lab render `c01-clear-p2.jpg` also decodes live (`24-glasses-03-mock-streaming-lab-clip.png`).
- Capture through the simulated **glasses capture button** (MockInputKit -> DAT Inputs event -> shutter) and through
  the phone shutter. Each photo was queued, uploaded, and recorded by the API with `origin: MockWearables`
  (`GET /v1/inspections/I-e87a1f4dbbf0`: three photos, all MockWearables). The inspection shows "Mock glasses" under
  each thumbnail (`24-glasses-05-photo-queued-mock-label.png`).
- Mock Meta Ray-Ban Display: the display attaches and the instruction, stage and Capture button render in the kit's
  display preview (`24-glasses-06-mock-display-hud.png`).
- Entry point and mock panel: `24-glasses-01-inspection-entry.png`, `24-glasses-04-mock-panel.png`.

Mock Device Kit behaviour found on the emulator:

- **The mock stream needs a feed.** With no feed the stream ends with a critical error.
- **With the phone camera as the feed, `capturePhoto()` returns a phone-camera still, not the image set with
  `setCapturedImage`.** With a video-clip feed it returns the chosen image. The screen says so.
- **A clip feed's first frame carries a container header ahead of the parameter sets.** `HevcPreview` re-frames each
  buffer as clean Annex-B before decoding.

## Not verified

Everything on real glasses is untested: registration in Meta AI, the camera permission flow, real HEVC stream
timing and keyframes, HEIC capture and its EXIF orientation, the capture button via Inputs, Bluetooth audio routing of
the spoken instruction, and the Ray-Ban Display HUD. Text-to-speech was triggered but not listened to on the emulator.

Implementation references: Android's [connected-device foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
and [MediaCodec flexible YUV output](https://developer.android.com/reference/android/media/MediaCodec).
