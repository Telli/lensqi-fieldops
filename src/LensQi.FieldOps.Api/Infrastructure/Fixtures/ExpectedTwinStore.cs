using System.Security.Cryptography;
using System.Text.Json;
using Json.Schema;
using LensQi.FieldOps.Api.Infrastructure.Knowledge;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Api.Infrastructure.Fixtures;

public sealed class FixturesOptions
{
    public const string SectionName = "Fixtures";
    public string TwinDirectory { get; set; } = "fixtures/telecom-lab";
    public string TwinFile { get; set; } = "expected-twin.json";
    public string TwinSchemaFile { get; set; } = "expected-twin.schema.json";
}

/// <summary>T0.4: loads the expected twin with JSON Schema + invariants and binds the SHA-256 of the exact bytes (FR-01).</summary>
public sealed class ExpectedTwinStore
{
    private readonly Dictionary<string, LoadedExpectedTwin> _byId;

    private ExpectedTwinStore(IReadOnlyList<LoadedExpectedTwin> twins)
    {
        Twins = twins;
        _byId = twins.ToDictionary(t => t.Twin.FixtureId, StringComparer.Ordinal);
    }

    public IReadOnlyList<LoadedExpectedTwin> Twins { get; }
    public LoadedExpectedTwin? Find(string fixtureId) => _byId.GetValueOrDefault(fixtureId);

    public static ExpectedTwinStore Load(string directory, FixturesOptions options)
    {
        var schema = JsonSchema.FromFile(Path.Combine(directory, options.TwinSchemaFile), new BuildOptions { SchemaRegistry = new SchemaRegistry() });
        var path = Path.Combine(directory, options.TwinFile);
        var bytes = File.ReadAllBytes(path);
        using var doc = JsonDocument.Parse(bytes);
        var eval = schema.Evaluate(doc.RootElement, new EvaluationOptions { OutputFormat = OutputFormat.List, RequireFormatValidation = true });
        if (!eval.IsValid)
        {
            var errors = (eval.Details ?? []).Where(d => d.Errors is { Count: > 0 })
                .SelectMany(d => d.Errors!.Select(kv => $"{d.InstanceLocation}: [{kv.Key}] {kv.Value}")).ToList();
            throw new KnowledgePackLoadException($"Expected twin '{options.TwinFile}' failed schema validation", errors);
        }
        var twin = doc.RootElement.Deserialize<ExpectedTwin>(KnowledgeJson.Options)
                   ?? throw new KnowledgePackLoadException("Expected twin is empty", []);
        var invariants = ExpectedTwinInvariants.Check(twin);
        if (invariants.Count > 0)
            throw new KnowledgePackLoadException($"Expected twin '{options.TwinFile}' failed invariants", invariants);
        var loaded = new LoadedExpectedTwin(twin, Convert.ToHexStringLower(SHA256.HashData(bytes)), options.TwinFile);
        return new ExpectedTwinStore([loaded]);
    }
}
