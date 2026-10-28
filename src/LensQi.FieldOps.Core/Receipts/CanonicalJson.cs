using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Core.Knowledge;

namespace LensQi.FieldOps.Core.Receipts;

/// <summary>
/// Deterministic JSON for hashing (PRD FR-09 "canonicalize manifest data before hashing"): object keys sorted
/// ordinally at every level, no whitespace, camelCase names, enums as strings, invariant number formatting.
/// The same value always hashes the same regardless of property declaration order.
/// </summary>
public static class CanonicalJson
{
    private static readonly JsonSerializerOptions Compact = new(KnowledgeJson.Options) { WriteIndented = false };

    public static string Serialize(object value)
    {
        var node = JsonSerializer.SerializeToNode(value, KnowledgeJson.Options);
        return Sort(node)?.ToJsonString(Compact) ?? "null";
    }

    public static string Serialize(JsonNode? node) => Sort(node)?.ToJsonString(Compact) ?? "null";

    public static string Sha256(string canonical) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));

    public static JsonNode? Sort(JsonNode? node) => node switch
    {
        JsonObject o => new JsonObject(o.OrderBy(kv => kv.Key, StringComparer.Ordinal).Select(kv => KeyValuePair.Create(kv.Key, Sort(kv.Value)))),
        JsonArray a => new JsonArray(a.Select(Sort).ToArray()),
        null => null,
        _ => node.DeepClone(),
    };
}
