# Meta glasses (Wearables Device Access Toolkit) in the Android app

Hands-free capture with Ray-Ban Meta glasses through Meta's Wearables Device Access Toolkit (DAT) 1.0.1
(`com.meta.wearable:mwdat-core`, `-camera`, `-inputs`, `-display`; `-mockdevice` in debug builds only).

**Status: integrated and exercised against Meta's Mock Device Kit on an Android emulator. Not tested on real glasses:
no hardware was available.**

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
  meta-data, and a `lensqi-fieldops://` VIEW intent filter on `MainActivity` so Meta AI can return to the app. No
  foreground service: streaming runs only while the glasses screen is open.

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
   **Start glasses camera** and allow camera access when Meta AI asks.
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
