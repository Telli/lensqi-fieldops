---
name: fieldops-meta-models
description: Exact, verified wire details and traps for calling Meta Muse Spark and SAM 3.1 from this repository, and how to record genuine-call evidence. Load before touching MuseSparkChatClient, SamSegmentationClient, prompts, schemas or docs/evidence.
---

# Meta Model API in FieldOps (verified October 28, 2026)

Base `https://api.meta.ai/v1/`, header `Authorization: Bearer $MODEL_API_KEY`. Key comes from the environment or the gitignored `.env` (never a request). Costs are billed; keep calls bounded.

## Muse Spark (`muse-spark-1.3`) — `POST chat/completions`
- Implemented in `src/LensQi.FieldOps.Meta/Muse/MuseSparkChatClient.cs` as an `IChatClient`. Text and image parts (`image_url` with a data URL). Structured output via `response_format: {type: "json_schema", json_schema: {name, schema, strict: true}}`.
- **Strict schema rules:** root object; `additionalProperties: false` on every object; `required` lists every property; no `allOf`/`oneOf`; nullable as `"type": ["string","null"]`; `anyOf` allowed below the root. Build schemas as `JsonObject` (see `KnowledgeGroundingComposer.ProposalSchema`, `VisualAssessmentService.Schema`).
- **Reasoning tokens count against `max_tokens`.** Send `reasoning_effort` (`minimal|low|medium|high|xhigh|max`, default here `low`) and a budget of thousands (6000). With 1200 the content came back empty. Reasoning tokens are reported in `usage.completion_tokens_details.reasoning_tokens` and recorded in `UsageDetails.AdditionalCounts["reasoning_tokens"]`.
- Prompts: the model receives typed JSON context and the image; fact statements are declared untrusted quoted data. Never put credentials, URLs to probe, or parent action names the model should not use into the prompt.
- Validation after the call is mandatory: `GroundedProposalService` → `KnowledgeActionValidator`; `VisualAssessmentService` → `InspectionSession.RecordAssessment`. Both reject foreign asset IDs, unknown feature/fact IDs and unsupported actions.

## SAM 3.1 (`sam-3.1`) — `POST responses`
- Implemented in `src/LensQi.FieldOps.Meta/Segmentation/SamSegmentationClient.cs`. Body: `{model, input:[{type:"message", role:"user", content:[{type:"input_text", text:"<one noun phrase>"}, {type:"input_image", image_url:"data:image/jpeg;base64,..."}]}], stream:false, metadata:{mask_encoding:"one_bit", include_confidence:"true"}}` (`include_confidence` is the **string** `"true"`).
- Output is special-token text under `output[].content[].type == "output_text"`. Empty text = honest no-match. Never parse the grammar in C#: `PythonSidecar` runs `tools/sam-parser/sam_parse.py` (`meta-sam-parser`) and returns boxes (half-open pixels), masks (COCO RLE, box-local bounds, raster size equals bounds) and confidence. Usage reports `images_processed`, not tokens.
- The sidecar's Python resolves from `FIELDOPS_PYTHON` or a repo-root `.venv` with `tools/sam-parser/requirements.txt` installed.
- On synthetic renders: wide views match; close-ups returned no-match. Record whatever happens; do not tune prompts to force a match without saying so.

## Evidence discipline
- Every genuine call that matters for judging gets a record under `docs/evidence/` with model id, prompt digest, response digest, usage and the `LIVE_META` label (`tools/LensQi.FieldOps.StageOneGate`, `tools/sam-parser/sam_spike.py`, the eval runner).
- Run `scripts/secret_scan.sh` before committing anything under `docs/evidence/`.
- Never fabricate a mask, a model response or a measurement for a demo. Fakes live only in tests (`FakeVisionChat`, `FakeSegmenter`).
