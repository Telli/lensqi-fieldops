using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using Microsoft.Extensions.AI;

namespace LensQi.FieldOps.Meta.Vision;

public sealed record VisualAssessmentOutcome(VisualAssessment? Assessment, string RawResponse, string ModelId, string PromptDigest, string ResponseDigest, UsageDetails? Usage, string? Error);

/// <summary>
/// FR-04: one bounded Muse call on the latest photo. The model receives the typed fixture context (asset, declared
/// features, view budget) and the image, and must answer with the strict VisualAssessment schema. Feature IDs are the
/// fixture's visual requirement IDs; anything else is rejected by the Core session, not repaired here.
/// </summary>
public sealed class VisualAssessmentService(IChatClient chat)
{
    public const string SchemaVersion = "visual-assessment/1.1";

    public static readonly Lazy<JsonElement> Schema = new(() =>
    {
        var statuses = new JsonArray(Enum.GetNames<ObservationStatus>().Select(n => (JsonNode)n!).ToArray());
        var actions = new JsonArray(Enum.GetNames<ProposedNextAction>().Select(n => (JsonNode)n!).ToArray());
        var schema = new JsonObject
        {
            ["type"] = "object", ["additionalProperties"] = false,
            ["properties"] = new JsonObject
            {
                ["assetId"] = new JsonObject { ["type"] = "string" },
                ["hypotheses"] = new JsonObject
                {
                    ["type"] = "array",
                    ["items"] = new JsonObject
                    {
                        ["type"] = "object", ["additionalProperties"] = false,
                        ["properties"] = new JsonObject
                        {
                            ["featureId"] = new JsonObject { ["type"] = "string" },
                            ["status"] = new JsonObject { ["type"] = "string", ["enum"] = statuses },
                            ["reason"] = new JsonObject { ["type"] = "string" },
                            ["evidenceIds"] = new JsonObject { ["type"] = "array", ["items"] = new JsonObject { ["type"] = "string" } },
                        },
                        ["required"] = new JsonArray("featureId", "status", "reason", "evidenceIds"),
                    },
                },
                ["nextAction"] = new JsonObject { ["type"] = "string", ["enum"] = actions },
                ["requestedView"] = new JsonObject { ["type"] = new JsonArray("string", "null") },
                ["requestedSegmentationConcept"] = new JsonObject { ["type"] = new JsonArray("string", "null") },
            },
            ["required"] = new JsonArray("assetId", "hypotheses", "nextAction", "requestedView", "requestedSegmentationConcept"),
        };
        return JsonSerializer.SerializeToElement(schema);
    });

    public const string SystemPrompt = """
        You are the visual-assessment assistant inside LensQi FieldOps. You look at ONE photo of a known fixture and
        report what is observed, suspected, unknown or conflicting about the declared features. You never decide whether
        a fault exists or a repair succeeded; code and measured diagnostics do that.

        Rules:
        1. assetId must equal context.assetId. featureId values must come from context.features. evidenceIds must come
           from context.photoEvidenceIds. Never invent IDs.
        2. Mark a feature Unknown when it is occluded, blurred, too small or outside the frame. Do not guess port
           numbers or label text you cannot read.
        3. nextAction: RequestView when a specific additional view would resolve an Unknown (set requestedView to a safe,
           qualitative instruction such as "move closer and show the switch faceplate and the yellow cable connector");
           SegmentRegion when a concrete object should be localized (set requestedSegmentationConcept to one short noun
           phrase, e.g. "yellow ethernet cable"); RunApprovedProbe when the visual record is as good as it will get and a
           read-only diagnostic should corroborate; RequestHumanReview when evidence conflicts; StopInconclusive when the
           photo is unusable. Never PrepareWorkOrder from a single photo.
        4. Respect context.viewRequestsRemaining: if it is 0, do not choose RequestView.
        5. Never instruct the technician to touch, unplug, power or reconfigure anything.
        6. Output exactly one JSON object matching the schema. No prose.
        """;

    public async Task<VisualAssessmentOutcome> AssessAsync(InspectionState state, ExpectedTwin twin, byte[] image, string mediaType, string photoEvidenceId, CancellationToken ct)
    {
        var asset = twin.FindAsset(state.AssetId);
        var context = new
        {
            assetId = state.AssetId,
            assetDisplayName = asset?.DisplayName,
            assetKind = asset?.Kind,
            visualLabels = asset?.VisualLabels,
            expectedConnections = twin.Connections.Where(c => c.FromAssetId == state.AssetId || c.ToAssetId == state.AssetId)
                .Select(c => new { c.ConnectionId, c.FromPortLabel, c.CableLabel, c.ExpectedState, note = "expected configuration, not proof of what the photo shows" }),
            features = twin.VisualRequirements.Select(v => new { featureId = v.RequirementId, mandatory = v.Mandatory, segmentationPrompt = v.SegmentationPrompt }),
            photoEvidenceIds = state.Photos.Select(p => p.EvidenceId),
            currentPhotoEvidenceId = photoEvidenceId,
            stage = state.Stage.ToString(),
            viewRequestsRemaining = Math.Max(0, InspectionSession.MaxAdditionalViews - state.ViewRequests),
        };
        var contextJson = JsonSerializer.Serialize(context, new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true });
        var messages = new List<ChatMessage>
        {
            new(ChatRole.System, SystemPrompt),
            new(ChatRole.User, [new TextContent("FIXTURE CONTEXT (typed JSON, authoritative):\n" + contextJson), new DataContent(image, mediaType), new TextContent("Assess this photo.")]),
        };
        var options = new ChatOptions { Temperature = 0, ResponseFormat = ChatResponseFormat.ForJsonSchema(Schema.Value, "VisualAssessment") };
        var promptDigest = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(SystemPrompt + "\n" + contextJson)));

        ChatResponse response;
        try
        {
            response = await chat.GetResponseAsync(messages, options, ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or InvalidOperationException)
        {
            return new VisualAssessmentOutcome(null, "", "unknown", promptDigest, "", null, $"provider_unavailable: {ex.Message}");
        }
        var raw = response.Text ?? "";
        var responseDigest = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(raw)));
        var modelId = response.ModelId ?? "unknown";
        try
        {
            var wire = JsonSerializer.Deserialize<WireAssessment>(raw, KnowledgeJson.Options);
            if (wire is null) return new VisualAssessmentOutcome(null, raw, modelId, promptDigest, responseDigest, response.Usage, "empty");
            var assessment = new VisualAssessment(wire.AssetId, wire.Hypotheses, wire.NextAction, wire.RequestedView, wire.RequestedSegmentationConcept, modelId, SchemaVersion);
            return new VisualAssessmentOutcome(assessment, raw, modelId, promptDigest, responseDigest, response.Usage, null);
        }
        catch (JsonException ex)
        {
            return new VisualAssessmentOutcome(null, raw, modelId, promptDigest, responseDigest, response.Usage, $"invalid_model_output: {ex.Message}");
        }
    }

    private sealed record WireAssessment(string AssetId, IReadOnlyList<Hypothesis> Hypotheses, ProposedNextAction NextAction, string? RequestedView, string? RequestedSegmentationConcept);
}
