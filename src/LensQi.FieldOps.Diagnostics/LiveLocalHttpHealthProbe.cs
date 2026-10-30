using System.Security.Cryptography;
using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Diagnostics;

/// <summary>
/// The one live probe in P0: an HTTP health check against a server-resolved target. Fail-closed rules (PRD FR-06,
/// child VK-R06.4): GET only, no redirects (a 3xx is Unavailable), http/https only, no userinfo, bounded timeout from the
/// fixture, bounded response size, and any exception yields Passed = null. The URL comes from DiagnosticsOptions.Targets
/// keyed by the fixture alias; nothing in a request body or model output reaches this class.
/// </summary>
public sealed class LiveLocalHttpHealthProbe(HttpClient http, DiagnosticsOptions options, TimeProvider clock) : IDiagnosticProbe
{
    public DiagnosticSource Source => DiagnosticSource.LiveLocal;

    public async Task<DiagnosticResult> RunAsync(ProbeRequest request, CancellationToken cancellationToken)
    {
        var d = request.Diagnostic;
        bool? passed = null;
        string? digest = null;
        int? answered = null;
        if (request.Target is { } target && IsAcceptable(target.Url))
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            cts.CancelAfter(TimeSpan.FromMilliseconds(Math.Clamp(d.TimeoutMs, 100, 30_000)));
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Get, target.Url);
                using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, cts.Token).ConfigureAwait(false);
                var status = (int)resp.StatusCode;
                answered = status;
                if (status is >= 300 and < 400)
                {
                    passed = null; // redirect leaves the allowlist: fail closed
                }
                else
                {
                    var body = await ReadBounded(resp, options.MaxResponseBytes, cts.Token).ConfigureAwait(false);
                    digest = Convert.ToHexStringLower(SHA256.HashData(body));
                    passed = status == d.ExpectedHttpStatus;
                }
            }
            catch (Exception) when (!cancellationToken.IsCancellationRequested)
            {
                passed = null; // timeout, DNS, TLS, connection refused: Unavailable, never a presumed pass
                answered = null; // an oversize body is not an answer either
            }
        }
        return new DiagnosticResult(request.FixtureId, d.DiagnosticId, d.TargetAlias, passed, clock.GetUtcNow(), Source, request.TraceId,
            ProbeNormalization.Normalize(passed), digest, answered);
    }

    public static bool IsAcceptable(Uri url) =>
        url.IsAbsoluteUri && (url.Scheme == Uri.UriSchemeHttp || url.Scheme == Uri.UriSchemeHttps) && string.IsNullOrEmpty(url.UserInfo);

    private static async Task<byte[]> ReadBounded(HttpResponseMessage resp, int max, CancellationToken ct)
    {
        await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = new MemoryStream();
        var buffer = new byte[8192];
        int read;
        while ((read = await stream.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
        {
            if (ms.Length + read > max) throw new InvalidOperationException("Response exceeds the bounded size");
            ms.Write(buffer, 0, read);
        }
        return ms.ToArray();
    }

    /// <summary>Creates the handler the probe must use: redirects disabled, no proxy surprises.</summary>
    public static HttpClient CreateClient() => new(new SocketsHttpHandler { AllowAutoRedirect = false, UseProxy = false, PooledConnectionLifetime = TimeSpan.FromMinutes(2) });
}
