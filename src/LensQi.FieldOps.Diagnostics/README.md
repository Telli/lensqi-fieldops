# LensQi.FieldOps.Diagnostics

Fixture-allowlisted read-only probes: `LiveLocalHttpHealthProbe` (server-owned alias resolution, no redirects,
bounded response), `SimulatedProbe` and `RecordedReplayProbe`, each stamping its `DiagnosticSource`.
Implementation lands with parent task T3.1 to T3.3. This project must never accept a URL, host or command
from a request body or a model.
