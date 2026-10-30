using System.Text.Json;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Tasks;

namespace LensQi.FieldOps.Api.Shared;

/// <summary>
/// Parts the phone measured with ARCore, sent beside a live frame or a question as the form field "measured" (a JSON array
/// of {part, distanceCm, heightAboveFloorCm}). Bounded and checked like model output: plain part names, plausible numbers.
/// </summary>
public static class MeasuredParts
{
    public const int Max = 4;

    /// <summary>The parts, an empty list when none were sent, or null when the field is malformed.</summary>
    public static IReadOnlyList<MeasuredPart>? Parse(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return [];
        if (json.Length > 2000) return null;
        List<MeasuredPart>? parts;
        try { parts = JsonSerializer.Deserialize<List<MeasuredPart>>(json, KnowledgeJson.Options); }
        catch (JsonException) { return null; }
        if (parts is null || parts.Count > Max) return null;
        return parts.All(p => p is not null && TaskPlanValidator.IsConcept(p.Part)
            && p.DistanceCm is null or (>= 1 and <= 2000) && p.HeightAboveFloorCm is null or (>= -100 and <= 1000))
            ? parts : null;
    }

    public static IResult Invalid() => ApiResults.BadRequest("invalid_measured",
        $"measured is a JSON array of up to {Max} parts: plain part names, distanceCm 1 to 2000, heightAboveFloorCm -100 to 1000");
}
