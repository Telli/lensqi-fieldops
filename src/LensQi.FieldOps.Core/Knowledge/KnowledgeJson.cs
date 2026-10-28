using System.Text.Json;
using System.Text.Json.Serialization;

namespace LensQi.FieldOps.Core.Knowledge;

/// <summary>Single JSON configuration for pack and binding documents: camelCase, string enums, no unknown members.</summary>
public static class KnowledgeJson
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.General)
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        Converters = { new JsonStringEnumConverter(allowIntegerValues: false) },
        ReadCommentHandling = JsonCommentHandling.Disallow,
        AllowTrailingCommas = false,
    };
}
