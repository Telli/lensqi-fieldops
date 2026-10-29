# Muse Spark Open feasibility

Review date: October 28, 2026 (Pacific). The owner will apply for access and add the credential later. Status: deferred pending confirmation; no Open call or integration claimed.

- Baseten's [official model page](https://www.baseten.co/library/muse-spark-open/) documents image input and the OpenAI-compatible chat completions endpoint at `https://inference.baseten.co/v1`, using `meta-models/muse-spark-open`.
- The requested hackathon alias `meta-models/muse-spark-open-1026` was not independently confirmed. Do not substitute the public alias silently.
- The [organizer details page](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/details) returned only a loading shell to the research tool. Bonus requirements could not be verified from that response.
- No Baseten credential was configured in the environment or the workspace's untracked `.env` when checked. No secret values were printed, and no paid probe was made.
- An organizer reference and locally configured credential are needed before the requested image-plus-strict-schema genuine-call check can confirm feasibility. This record is not evidence of eligibility or provider compatibility with our exact schema.

Implementation boundary after confirmation: extract only `StepCoach.CheckAsync` into a checker dependency; retain the hosted client for all other consumers. Explicit provider configuration overrides automatic selection. Preserve schema and bounded-reason validation. Fall back once on provider unavailability or timeout, never on caller cancellation; reject malformed output. Persist attempted provider, serving model, prompt/response digests and sanitized fallback reason in backward-compatible task events. Test provider selection, fallback, both-provider failure, cancellation, schema rejection, event replay and that a suggestion never completes a step. Genuine-call evidence must identify the actual serving model; hosted fallback does not satisfy the Open-call requirement.

Credit resilience applies only to step checks: plans, coaching and inspection assessments would still require hosted Meta availability.
