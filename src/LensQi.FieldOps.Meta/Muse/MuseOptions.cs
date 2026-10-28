namespace LensQi.FieldOps.Meta.Muse;

public sealed class MuseOptions
{
    public const string SectionName = "Muse";
    public const string ApiKeyEnvironmentVariable = "MODEL_API_KEY";

    /// <summary>Meta Model API base. Must end with '/' so relative paths append instead of replacing the last segment.</summary>
    public string BaseUrl { get; set; } = "https://api.meta.ai/v1/";
    public string? ApiKey { get; set; }
    public string Model { get; set; } = "muse-spark-1.3";
    public double Temperature { get; set; } = 0.0;
    public int MaxOutputTokens { get; set; } = 1200;
    public int TimeoutSeconds { get; set; } = 60;
}
