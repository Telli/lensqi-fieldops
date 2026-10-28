# Live probe runbook (T3.3, Track D)

The live probe is an HTTP health check of an endpoint **you own**. The API resolves the alias `lab-node-a` to a URL from
server configuration only; no request, model output or script names the host. A loopback rehearsal of exactly this
procedure is recorded in `docs/evidence/receipt-loopback/` and is labelled `LIVE_LOCAL_PROBE (loopback)`: a real HTTP
measurement of a server on 127.0.0.1, not a lab device.

## On the owned device (Pi, NAS, second laptop)

```bash
mkdir -p ~/fieldops-health && echo ok > ~/fieldops-health/health
python3 -m http.server 8765 --directory ~/fieldops-health
```

| Endpoint state | How | Probe result |
|---|---|---|
| fault | `mv ~/fieldops-health/health ~/fieldops-health/health.off` (404) | FAIL, measured → FaultConfirmed |
| repaired | `mv ~/fieldops-health/health.off ~/fieldops-health/health` (200) | PASS → VerifiedCompleteLive |
| unplugged | stop the server or pull the cable | UNAVAILABLE, never a pass |

## The two commands on this Mac

1. Start the API in LiveLocal mode on its own port and data directory, pointed at the device:

```bash
Diagnostics__Mode=LiveLocal Diagnostics__Targets__lab-node-a=http://<device-ip>:8765/health Storage__DataDirectory=data/live-lan dotnet run --project src/LensQi.FieldOps.Api --no-launch-profile --urls http://127.0.0.1:5202
```

2. Drive the flow; the script pauses for each physical step (break, repair, unplug, replug) and copies the receipt bundle:

```bash
python3 -I scripts/live_probe_flow.py --base http://127.0.0.1:5202 --out docs/evidence/receipt-live --label "LIVE_LOCAL_PROBE (LAN)"
```

Add `--pre photo-before.jpg --post photo-after.jpg` to use your own photographs of owned equipment (no serials, no client
networks); they are uploaded as `Phone`. Without them the synthetic renders are used and labelled `RecordedFixture`.

Then verify the bundle offline: `dotnet run --project tools/LensQi.FieldOps.VerifyReceipt -- docs/evidence/receipt-live`.
The script exits non-zero unless the decision is `Verified` with `VerifiedCompleteLive` and the bundle verifies.

## Loopback rehearsal (what was run on 2026-10-28)

The `fieldops-api-live-loopback` launch configuration (`.claude/launch.json`) runs step 1 against
`http://127.0.0.1:8765/health`, and step 2 ran unattended with `--fault-cmd`, `--repair-cmd`, `--unplug-cmd` and
`--replug-cmd` moving the health file and stopping/starting the local `http.server`. Result: fault measured as FAIL
(404) → FaultConfirmed; post-repair photo `Changed`; verify `Verified` / `VerifiedCompleteLive`; second inspection with
the server stopped → `UNAVAILABLE`; receipt bundle `RECEIPT INTEGRITY: PASS`.
