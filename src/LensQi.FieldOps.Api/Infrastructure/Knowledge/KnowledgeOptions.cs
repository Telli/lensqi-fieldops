namespace LensQi.FieldOps.Api.Infrastructure.Knowledge;

public sealed class KnowledgeOptions
{
    public const string SectionName = "Knowledge";

    /// <summary>Server-controlled directory holding packs, schemas and bindings. Relative paths are resolved by
    /// walking up from the content root, so `dotnet run --project src/...` finds the repository fixtures.</summary>
    public string PackDirectory { get; set; } = "fixtures/vendor-knowledge";
    public string PackSchemaFile { get; set; } = "knowledge-pack.schema.json";
    public string BindingSchemaFile { get; set; } = "fixture-binding.schema.json";
    public string BindingFilePattern { get; set; } = "fixture-binding*.json";
    public int MaxFacts { get; set; } = 10;
}

public static class KnowledgePaths
{
    public static string ResolveDirectory(string contentRoot, string configured)
    {
        if (Path.IsPathRooted(configured)) return configured;
        var dir = new DirectoryInfo(contentRoot);
        for (var i = 0; dir is not null && i < 8; i++, dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, configured);
            if (Directory.Exists(candidate)) return candidate;
        }
        throw new DirectoryNotFoundException($"Knowledge pack directory '{configured}' not found above '{contentRoot}'.");
    }
}
