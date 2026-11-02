# Mobile review fixes — emulator smoke, 2 November 2026

Debug APK installed and launched on the available `emulator-5554`. Native API ran on port 5201 with a new isolated filesystem data directory. Diagnostics were explicitly Simulated; no model calls or real hardware probes ran. Eight freeform sessions and one inspection were created through the API; the oldest session was stopped. One additional empty freeform session was opened during navigation; its camera assistance remained paused.

- `technician.png`: connected Technician page with persistent Start inspection and Assets entries.
- `assets.png`: Assets entry opens the existing asset browser with the seeded inspection.
- `history.png`: all-task filters and more than six retained sessions are reachable by scrolling.
- `stopped-history.png`: Stopped filter finds the oldest retained session.
- `stopped-record.png`: that record reopens as a read-only summary, without restarting the camera.

The configured emulator API address was restored to its original `http://10.0.2.2:5199/` after the smoke. ADB typing into Settings dropped characters, so restoration replaced only the equal-length API URL bytes in the stopped app’s preferences and verified all other bytes were preserved. No app data was cleared. This is a navigation/startup smoke, not a physical camera, microphone, ARCore, glasses, genuine-model or full hero-flow acceptance run. Automated tests separately exercise upload identity, offline review recovery, source grounding and verifiable receipt export.
