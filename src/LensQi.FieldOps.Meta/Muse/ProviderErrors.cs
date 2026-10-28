using System.Text.RegularExpressions;

namespace LensQi.FieldOps.Meta.Muse;

/// <summary>
/// Builds the text of a provider error before it can reach an exception message, an API response or a logger (T6.1).
/// A provider that echoes the request would otherwise carry the photo back out as a base64 data URL, and a bearer
/// token must never appear either. Both are redacted, then the text is truncated.
/// </summary>
public static partial class ProviderErrors
{
    // The payload may be JSON-escaped ("\u002B" for '+', "\/" for '/'), so escaped base64 characters are consumed too.
    [GeneratedRegex(@"data:[\w.+-]+/[\w.+-]+;base64,(?:[A-Za-z0-9+/=_-]|\\u00[0-9A-Fa-f]{2}|\\/)+", RegexOptions.CultureInvariant)]
    private static partial Regex DataUrl();

    [GeneratedRegex(@"Bearer\s+[A-Za-z0-9._~+/=-]+", RegexOptions.CultureInvariant | RegexOptions.IgnoreCase)]
    private static partial Regex BearerToken();

    public static string Redact(string text, int max)
    {
        var s = BearerToken().Replace(DataUrl().Replace(text, "data:[redacted]"), "Bearer [redacted]");
        return s.Length <= max ? s : s[..max] + "…";
    }
}
