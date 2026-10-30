using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Inspection;

/// <summary>
/// VK-T25 (design.md §6a): Core keeps score, not the model. Ordinal equality on normalized values; a probe that measured
/// nothing makes the prediction Unscorable. The verification policy never reads the outcome.
/// </summary>
public static class PredictionScorer
{
    public static PredictionOutcome Score(string predictedValue, DiagnosticResult result) =>
        result.Passed is null || result.NormalizedValue is null ? PredictionOutcome.Unscorable
        : string.Equals(result.NormalizedValue, predictedValue, StringComparison.Ordinal) ? PredictionOutcome.Confirmed
        : PredictionOutcome.Falsified;
}
