# Equipment registration marker

`lensqi-marker-v1.png` is the marker the Android app uses to register a piece of equipment in AR (phase 4 of
[SPATIAL_AR_PLAN.md](../SPATIAL_AR_PLAN.md)). ARCore tracks it as an Augmented Image, which gives the equipment a pose
with orientation. A QR code that carries only an ID can't do this.

`scripts/make_registration_marker.py` generates the file from a fixed seed. The app's copy in
`apps/android/app/src/main/assets/registration/` is identical. If you regenerate the marker, both copies change, and
markers already printed stop matching.

## Print it

1. Print the PNG so the square, including its black frame, is **80 mm wide**. Turn off "fit to page" and set the size
   by hand.
2. Measure the print with a ruler. ARCore takes 80 mm as the true width, so a print of another size puts every
   distance and every marked port off by the same ratio. Reprint it if it isn't within about 1 mm.
3. Use matte paper, keep it flat, and stick it where it can't slide. A good place is the equipment's front panel, near
   the ports. If the sticker moves, every port marked against it is wrong.

## Use it in the app

Registration appears on the inspection camera with **3D AR** on, when the inspection's asset is in the fixture.

1. **Find the marker.** Point the phone at it until the screen says "Marker found · is it on {asset}?". Answer only after
   you've read the asset label on the equipment. **No** refuses registration for this AR session. **Ask again** undoes
   that.
2. **Mark ports.** Tap **Mark ports**, choose a port (the step's port, from the fixture's connection record, and the
   ports either side of it), and tap that port in the picture. Mark at least one neighbour, so LensQi knows how far apart
   the ports are. Each mark is stored in the marker's frame, together with the fixture, asset, model, fixture revision
   and "Marked by the technician on this phone". It is kept only on this phone.
3. **Check.** Tap **Check** and tap where the step's port really is. LensQi measures the distance from your mark.

The screen points at the port (a ring and "Port P2") only when all of these hold. Otherwise it points at the equipment
and asks you to find the port by its label.

- The asset is confirmed.
- The marker is tracked, in view or at its last seen position.
- The port and a neighbour are marked for this model.
- Two checks in this AR session were both under half the port spacing.

Checks no longer count after any of these:

- the marker moves more than 2 cm or turns more than 5°
- tracking is lost
- a port is marked again
- a new AR session starts, for example after the shutter

Nothing here is evidence. Marks, checks and the port ring are never uploaded, and they never count toward the
checklist, the acknowledgement or verification. Identifying a port comes only from your marks and checks against this
marker. A generic AI detection never identifies a port.

## Known limits

- **One marker at a time.** There is one marker image, and the app expects one marker in view. To register several
  units in one place, give each its own marker. That needs a marker per asset, which isn't built.
- **Movement out of view isn't seen.** If the equipment moves while the marker is out of view, the app can't tell until
  the marker is seen again.
- **Marker quality isn't scored.** The marker hasn't been scored with ARCore's `arcoreimg eval-img` tool, which
  recommends a score of at least 75. Its detection range and tracking quality on the S23 Ultra are untested.
- **Session start is slower.** The image database is built when the AR session starts, which adds a little to start-up.
  A database built in advance with `arcoreimg` would avoid that.
