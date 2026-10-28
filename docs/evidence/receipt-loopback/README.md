# Receipt bundle: LIVE_LOCAL_PROBE (loopback)

A genuine `LiveLocal` HTTP measurement of a `python3 -m http.server` running on this Mac's loopback (127.0.0.1:8765),
standing in for the owned lab endpoint. It proves the live code path end to end (fault measured as a 404 FAIL, repair
measured as 200, `VerifiedCompleteLive`, and `UNAVAILABLE` when the server is stopped). It is **not** a measurement of lab
hardware and is never presented as one. Photos are synthetic renders (`RecordedFixture`); the SAM call is genuine.
How it was produced: `docs/LIVE_PROBE_RUNBOOK.md`. Verify: `dotnet run --project tools/LensQi.FieldOps.VerifyReceipt -- docs/evidence/receipt-loopback`.
