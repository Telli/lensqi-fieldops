using System.Security.Cryptography;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Api.Infrastructure.Knowledge;

public sealed class KnowledgePackLoadException(string message, IReadOnlyList<string> errors) : Exception(message)
{
    public IReadOnlyList<string> Errors { get; } = errors;
}

/// <summary>
/// VK-T01/VK-T02: loads every pack in the server-controlled directory at startup, computes SHA-256 over the exact
/// file bytes, and refuses to start on any invalid pack. The resulting list is immutable; packs are never reloaded
/// in place while the process runs, so a digest recorded on an inspection stays addressable.
/// </summary>
public sealed class FileKnowledgePackStore : IKnowledgePackStore
{
    private readonly Dictionary<string, LoadedKnowledgePack> _byId;

    private FileKnowledgePackStore(IReadOnlyList<LoadedKnowledgePack> packs)
    {
        Packs = packs;
        _byId = packs.ToDictionary(p => p.Pack.PackId, StringComparer.Ordinal);
    }

    public IReadOnlyList<LoadedKnowledgePack> Packs { get; }

    public LoadedKnowledgePack? Find(string packId) => _byId.GetValueOrDefault(packId);

    public static string ComputeSha256(ReadOnlySpan<byte> bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));

    public static bool IsPackFile(string fileName, KnowledgeOptions options) =>
        fileName.EndsWith(".json", StringComparison.OrdinalIgnoreCase)
        && !fileName.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)
        && !System.IO.Enumeration.FileSystemName.MatchesSimpleExpression(options.BindingFilePattern, fileName);

    public static FileKnowledgePackStore Load(string directory, KnowledgeOptions options, KnowledgePackValidator validator, TimeProvider? clock = null)
    {
        clock ??= TimeProvider.System;
        var errors = new List<string>();
        var loaded = new List<LoadedKnowledgePack>();
        var seen = new Dictionary<string, string>(StringComparer.Ordinal);

        foreach (var path in Directory.EnumerateFiles(directory, "*.json").OrderBy(p => p, StringComparer.Ordinal))
        {
            var name = Path.GetFileName(path);
            if (!IsPackFile(name, options)) continue;

            var bytes = File.ReadAllBytes(path);
            var result = validator.ValidatePack(bytes);
            if (!result.IsValid)
            {
                errors.AddRange(result.Errors.Select(e => $"{name}: {e}"));
                continue;
            }

            var pack = result.Pack!;
            if (seen.TryGetValue(pack.PackId, out var other))
            {
                errors.Add($"{name}: packId '{pack.PackId}' is also declared by {other}");
                continue;
            }
            seen[pack.PackId] = name;
            loaded.Add(new LoadedKnowledgePack(pack, ComputeSha256(bytes), name, clock.GetUtcNow()));
        }

        if (errors.Count > 0)
            throw new KnowledgePackLoadException($"{errors.Count} knowledge pack error(s); refusing to start with a partial pack set.", errors);

        return new FileKnowledgePackStore(loaded);
    }
}

/// <summary>Loads and cross-checks fixture binding overlays against the installed packs (VK-R02.7).</summary>
public static class FixtureBindingLoader
{
    public static IReadOnlyList<FixtureKnowledgeBinding> Load(string directory, KnowledgeOptions options, KnowledgePackValidator validator, IKnowledgePackStore store)
    {
        var errors = new List<string>();
        var bindings = new List<FixtureKnowledgeBinding>();
        foreach (var path in Directory.EnumerateFiles(directory, options.BindingFilePattern).OrderBy(p => p, StringComparer.Ordinal))
        {
            if (path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
            var result = validator.ValidateBinding(File.ReadAllBytes(path), store);
            if (result.IsValid) bindings.Add(result.Binding!);
            else errors.AddRange(result.Errors.Select(e => $"{Path.GetFileName(path)}: {e}"));
        }
        if (errors.Count > 0)
            throw new KnowledgePackLoadException($"{errors.Count} fixture binding error(s).", errors);
        return bindings;
    }
}
