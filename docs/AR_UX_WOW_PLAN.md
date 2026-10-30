# AR & UX "Wow Factor" Implementation Plan

## Non-Negotiable Epistemic & Architectural Invariants

1. **Server Owns All Decisions:** Clients never compute or invent an `InspectionStage`, `VerificationResult`, or `VisualChangeVerdict`. Every badge, gate, and banner derives from `GET /v1/inspections/{id}` or endpoint responses.
2. **Strict Provenance Labelling:**
   - Photos = `Evidence` (with origin: `Phone`, `Glasses`, `MockWearables`, `RecordedFixture`)
   - Muse / SAM = `AI outline · inferred` / `AI observation · suggestion` (never called a measurement)
   - Probes = `Measured` with explicit `LIVE` / `SIMULATED` / `REPLAY` badge (`VerifiedCompleteSimulated` never presented as live)
   - Expected Twin / Knowledge Packs = `Site record` / `Vendor documentation` (intent, not proof)
3. **Photo Identity & Spatial Provenance Discipline:** Every segmentation outline, hypothesis finding, and visual change verdict shown on a captured photo **must** verify `photoEvidenceId` matches that exact photo. In live camera mode, label whether tracking is `2D patch tracked` or `ARCore 3D world-anchored · depth measured` so spatial provenance is always explicit.
4. **Build Output Rule:** Never edit `src/LensQi.FieldOps.Api/wwwroot/` by hand; `npm --prefix apps/web run build` populates it.

---

## Phased Execution Plan

### Phase 1: Instrumented Live AR HUD, 60fps Box Interpolation & Quality Telemetry Bar
- **`apps/web/src/lib/use-live-assist.ts`**: Expose `telemetry: { brightness: number; sharpness: number; motion: number } | null` in `LiveFeedback` from the worker's frame metrics.
- **`apps/web/src/components/fo/live-overlay.tsx`**: Keep the primary `<rect x={...} y={...} width={...} height={...}>` as the first `<rect>` in each `<g key={o.id}>` inside `<svg aria-label="Live object tracking">` (preserving `scripts/live_ar_smoke.mjs`), add smooth CSS transitions (`transition-[x,y,width,height] duration-100 ease-out`), corner-bracket HUD `<path>` reticles, target-concept highlight (`targetConcept?: string`), and a sleek pill header tag.
- **`apps/web/src/pages/m/scan.tsx`**: Pass `targetConcept={targetConcept(fixture, prior)}` into `LiveOverlay`, and render a live 3-pill camera telemetry micro-meter (`Light · Focus · Stability`) when `assist.enabled && assist.telemetry` is active.

### Phase 2: High-Resolution "ROI Port Loupe" (Picture-in-Picture Crop Callout & Zoom)
- **`apps/web/src/lib/ar.ts`**: Extend `ArTag` with `cropFile?: string | null` populated from `SegmentationGeometry.crop?.file ?? null`.
- **`apps/web/src/components/fo/ar-frame.tsx`**: When an `ArTag` is selected (`active`), render an interactive **High-Res ROI Port Loupe** card alongside the finding detail whenever `active.cropFile` is available (using the inspection's artifact path derived from `src`), with a `1× / 2×` magnifier toggle and an animated leader-ring around the selected box on the overlay.

### Phase 3: Interactive "Before / After + AI Change Diff" Split-Curtain Scrubber
- **`apps/web/src/pages/m/verify.tsx` & `apps/web/src/pages/ops/inspections.tsx`**: Upgrade `VerifyHero` (and provide a reusable `BeforeAfterCompare` or integrated `Compare` mode) so when both `before` and `after` photos exist, users can toggle `Before`, `After`, or `Compare` (draggable split-curtain scrubber with keyboard-accessible range input) and view a deterministic change-evidence telemetry bar (`pHash Δ`, `Target IoU`, `Shift`).

### Phase 4: Interactive Expected Digital Twin Port-Map & Dual-Key Epistemic Gate
- **`apps/web/src/components/fo/expected-state.tsx` & `apps/web/src/pages/m/investigate.tsx`**: Add an interactive **Switch Port Topology Schematic** above the connection rows (preserving all existing text for tests) with cross-highlighting support (`selectedConcept` / `onSelectConcept`), and surface `ExpectedState` inside `Investigate`'s `"Equipment and documentation"` section.
- **`apps/web/src/pages/m/verify.tsx`**: Add a visual **Dual-Key Epistemic Gate** diagram above the verification `CheckRow`s showing `1. Visual + Human Review` and `2. Independent Telemetry Probe` converging into the `FieldProof Engine` outcome (highlighting the classic Fake-Fix denial where Visual passes but Telemetry fails).

### Phase 5: Native Android `ArOverlay` Parity & Live HUD Reticles
- **`apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/ArOverlay.kt`**: Add `assessmentForPhoto(...)` and require `change.photoEvidenceId == g.photoEvidenceId` and `g.photoEvidenceId in h.evidenceIds` in `arTags(...)`. Replace overlapping floating on-image text chips with numbered circular pins (`1`, `2`, ...) + bottom caption bar with `Hide outlines` / `Show outlines` toggle, selectable part pills, and High-Res ROI crop preview when `g.crop?.file` is available.
- **`apps/android/app/src/main/java/com/lensqi/fieldops/ui/inspection/ReviewScreen.kt`**: Filter assessment via `assessmentForPhoto`, prevent bottom sheet overlap by making the top review area scrollable (`Modifier.weight(1f).verticalScroll(...)`), and offer `"Capture the requested view"` directly when `target != null`.
- **`apps/android/app/src/main/java/com/lensqi/fieldops/ui/camera/CameraScreen.kt`**: Render corner-bracket HUD reticles around tracked live objects and display live quality telemetry pills (`Light · Focus · Stability`).

---

### Phase 6: Optional ARCore 3D World-Locked Spatial Anchors & Depth/Distance Coaching (Android)

#### 1. Architecture Overview
On ARCore-supported Android devices, bridge **SAM 3.1 2D detections** with **ARCore 6DoF Pose, Hit-Testing (`Frame.hitTest`), and Depth API (`Frame.acquireDepthImage16Bits`)** while preserving a zero-regression fallback to the existing `CameraX` + `LiveTracker` 2D pipeline when ARCore is unavailable, denied, or running on emulators/glasses.

```text
SAM 3.1 /live/detect (Normalized 2D Box [l, t, r, b] + frameId)
       │
       ▼
 Historical Pose Buffer (matches frameId -> ARCore Camera Pose & Intrinsics at capture instant)
       │
       ├──► 1. Raycast Box Centroid & Corners (Frame.hitTest / DepthPoint / Plane / InstantPlacementPoint)
       │
       ├──► 2. Create/Update World-Locked Anchor (com.google.ar.core.Anchor) per detected concept
       │
       └──► 3. Every 60fps Display Frame:
               - Project 3D Anchor Pose + physical extent into current Camera View/Projection Matrix
               - Compute Euclidean distance d = ||CameraPose.translation - AnchorPose.translation|| (meters/cm)
               - Emit metric coaching ("Move ~18 cm closer to Port P2" / "Hold 25–35 cm from faceplate")
               - Render 3D perspective-projected reticle & distance badge on Compose Canvas
```

#### 2. Gradle & Manifest Additions (`apps/android`)
- **`gradle/libs.versions.toml`**:
  ```toml
  arcore = "1.46.0"
  # under [libraries]
  google-ar-core = { module = "com.google.ar:core", version.ref = "arcore" }
  ```
- **`app/src/main/AndroidManifest.xml`**:
  ```xml
  <!-- Optional so non-ARCore devices, emulators, and glasses workflows always install and run -->
  <uses-feature android:name="android.hardware.camera.ar" android:required="false" />
  <meta-data android:name="com.google.ar.core" android:value="optional" />
  ```

#### 3. Core Components to Create under `com.lensqi.fieldops.arcore`
1. **`ArCoreCapabilityChecker.kt`**:
   - Wraps `ArCoreApk.getInstance().checkAvailability(context)`.
   - Returns `ArSupportState`: `SupportedReady`, `SupportedNeedsInstall`, or `Unsupported`.
   - Checks `session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)` to enable hardware/ToF or depth-from-motion metric measurement.
2. **`ArCoreFramePoseBuffer.kt`**:
   - Ring buffer (capacity 120 frames / ~12 seconds) storing `PoseSnapshot(frameId, timestampNs, cameraPose, viewMatrix, projectionMatrix, depthMetersAtCenter)` captured when each frame is sampled for SAM `/live/detect`.
   - Solves the **asynchronous inference lag problem**: when SAM returns a 2D bounding box 800–1400ms later for `frameId`, the anchor is raycast using the exact `cameraPose` from `frameId` rather than the current camera position!
3. **`SpatialAnchorTracker.kt`**:
   - Maintains active `Map<String, SpatialTrackedObject>` keyed by `concept` (`"network switch"`, `"yellow ethernet cable"`).
   - **Anchor Creation / Update:**
     - Converts normalized 2D box center `(cx, cy)` and box corners `(left, top, right, bottom)` using the historical `PoseSnapshot` and current `Frame.hitTest(px, py)` (falling back to Depth API 16-bit depth lookup at `(cx, cy)` + `Pose.makeTranslation(...)` along the unprojected camera ray if no plane/point cloud is hit yet).
     - Attaches a `com.google.ar.core.Anchor` so the marker stays world-locked in 3D space even if the user turns away and looks back, or walks closer/further.
   - **60fps Projection & Metric Distance:**
     - On each ARCore `Frame`, transforms each `Anchor.pose` into screen coordinates `(screenX, screenY,apparentScale)` via `projectionMatrix * viewMatrix * anchorPose`.
     - Calculates metric distance `distanceMeters = hypot(dx, dy, dz)` between `camera.pose` and `anchor.pose`.
     - Computes **Metric Distance Coaching**:
       - Ideal close-up inspection window: `0.20m .. 0.40m` (`20–40 cm`).
       - If `distanceMeters > 0.45f`: `"Move ${(distanceMeters - 0.30f) * 100).roundToInt()} cm closer to ${label}"`
       - If `distanceMeters < 0.15f`: `"Move back ~${((0.22f - distanceMeters) * 100).roundToInt()} cm for focus"`
       - If `0.20f..0.40f`: `"Optimal distance (${(distanceMeters * 100).roundToInt()} cm) · Hold steady & capture"`
4. **High-Resolution Evidence Capture Handoff (`SharedCamera` / CpuImage):**
   - Configures `Session.Feature.SHARED_CAMERA` (or selects highest-resolution `CameraConfig` with `ImageFormat.YUV_420_888` CPU image capture) so tapping the shutter captures a full-resolution JPEG while keeping the 3D spatial anchors alive, or pauses the ARCore session for `ImageCapture` and restores anchors on resume.
5. **UI Toggle & Provenance Badge in `CameraScreen.kt`**:
   - When `ArCoreCapabilityChecker` reports `SupportedReady`, display a `"3D Spatial AR"` toggle chip next to `"Live assistance"`.
   - Badge the HUD status explicitly as `"ARCore 3D world-anchored · depth measured"` (vs. `"Live 2D assistance · AI inferred"`) and render the live metric distance pill (`"32 cm · target locked"`) on each 3D-anchored reticle.

