using System.Text.Json;
using Json.Schema;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Api.Infrastructure.Knowledge;

public sealed record PackValidationResult(bool IsValid, IReadOnlyList<string> Errors, KnowledgePack? Pack)
{
    public static PackValidationResult Failure(IEnumerable<string> errors) => new(false, errors.ToList(), null);
}

public sealed record BindingValidationResult(bool IsValid, IReadOnlyList<string> Errors, FixtureKnowledgeBinding? Binding)
{
    public static BindingValidationResult Failure(IEnumerable<string> errors) => new(false, errors.ToList(), null);
}

/// <summary>
/// VK-T01: strict validation of pack and binding documents. Order: JSON Schema (structure, enums, patterns,
/// additionalProperties) → typed binding → cross-reference invariants shared with the Python validator.
/// A document failing any stage is rejected whole.
/// </summary>
public sealed class KnowledgePackValidator
{
    private static readonly EvaluationOptions SchemaOptions = new()
    {
        OutputFormat = OutputFormat.List,
        RequireFormatValidation = true,
    };

    private readonly JsonSchema _packSchema;
    private readonly JsonSchema _bindingSchema;
    private readonly Func<DateOnly> _today;

    public KnowledgePackValidator(string packSchemaPath, string bindingSchemaPath, Func<DateOnly>? today = null)
    {
        // Each validator builds into its own registry: the schemas declare $id values, and the library's
        // global registry refuses to register the same $id twice (e.g. one validator per test).
        var build = new BuildOptions { SchemaRegistry = new SchemaRegistry() };
        _packSchema = JsonSchema.FromFile(packSchemaPath, build);
        _bindingSchema = JsonSchema.FromFile(bindingSchemaPath, build);
        _today = today ?? (() => DateOnly.FromDateTime(DateTime.UtcNow));
    }

    public PackValidationResult ValidatePack(ReadOnlySpan<byte> bytes)
    {
        if (!TryParse(bytes, out var element, out var parseError))
            return PackValidationResult.Failure([parseError!]);

        var schemaErrors = Evaluate(_packSchema, element);
        if (schemaErrors.Count > 0)
            return PackValidationResult.Failure(schemaErrors);

        KnowledgePack pack;
        try
        {
            pack = element.Deserialize<KnowledgePack>(KnowledgeJson.Options)
                   ?? throw new JsonException("document is null");
        }
        catch (JsonException ex)
        {
            return PackValidationResult.Failure([$"binding: {ex.Message}"]);
        }

        var invariantErrors = KnowledgePackInvariants.Check(pack, _today());
        return invariantErrors.Count > 0
            ? PackValidationResult.Failure(invariantErrors)
            : new PackValidationResult(true, [], pack);
    }

    public BindingValidationResult ValidateBinding(ReadOnlySpan<byte> bytes, IKnowledgePackStore store)
    {
        if (!TryParse(bytes, out var element, out var parseError))
            return BindingValidationResult.Failure([parseError!]);

        var schemaErrors = Evaluate(_bindingSchema, element);
        if (schemaErrors.Count > 0)
            return BindingValidationResult.Failure(schemaErrors);

        FixtureKnowledgeBinding binding;
        try
        {
            binding = element.Deserialize<FixtureKnowledgeBinding>(KnowledgeJson.Options)
                      ?? throw new JsonException("document is null");
        }
        catch (JsonException ex)
        {
            return BindingValidationResult.Failure([$"binding: {ex.Message}"]);
        }

        var errors = FixtureBindingInvariants.Check(binding, store);
        return errors.Count > 0
            ? BindingValidationResult.Failure(errors)
            : new BindingValidationResult(true, [], binding);
    }

    private static bool TryParse(ReadOnlySpan<byte> bytes, out JsonElement element, out string? error)
    {
        try
        {
            using var doc = JsonDocument.Parse(bytes.ToArray(), new JsonDocumentOptions { CommentHandling = JsonCommentHandling.Disallow });
            element = doc.RootElement.Clone();
            error = null;
            return true;
        }
        catch (JsonException ex)
        {
            element = default;
            error = $"parse: {ex.Message}";
            return false;
        }
    }

    private static List<string> Evaluate(JsonSchema schema, JsonElement element)
    {
        var result = schema.Evaluate(element, SchemaOptions);
        if (result.IsValid) return [];
        var errors = new List<string>();
        foreach (var detail in (result.Details ?? []).Where(d => d.Errors is { Count: > 0 }))
        {
            foreach (KeyValuePair<string, string> kv in detail.Errors!)
                errors.Add($"{detail.InstanceLocation}: [{kv.Key}] {kv.Value}");
        }
        if (errors.Count == 0) errors.Add("schema: document is invalid");
        return errors.Distinct(StringComparer.Ordinal).OrderBy(e => e, StringComparer.Ordinal).ToList();
    }
}
