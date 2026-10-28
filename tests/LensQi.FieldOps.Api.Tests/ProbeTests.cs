using System.Net;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Diagnostics;

namespace LensQi.FieldOps.Tests;

public class ProbeTests
{
    private sealed class Stub(Func<HttpRequestMessage, HttpResponseMessage> f) : HttpMessageHandler
    {
        public int Calls;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage r, CancellationToken ct) { Calls++; return Task.FromResult(f(r)); }
    }

    private static readonly TwinDiagnostic Diag = new("NODE-A-HEALTH", "http_health", "lab-node-a", 200, 2000, "The approved lab endpoint returned HTTP 200 at test time (not physical port identity).");
    private static ProbeRequest Req(Uri? url) => new("LAB-RACK-01", Diag, url is null ? null : new ResolvedTarget("lab-node-a", url), "t1");
    private static LiveLocalHttpHealthProbe Live(Stub stub, int max = 65536) => new(new HttpClient(stub), new DiagnosticsOptions { MaxResponseBytes = max }, TimeProvider.System);

    [Fact]
    public async Task Live_probe_passes_on_expected_status_and_fails_on_other_status()
    {
        var ok = Live(new Stub(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("ok") }));
        var r = await ok.RunAsync(Req(new Uri("http://127.0.0.1:9/health")), CancellationToken.None);
        Assert.True(r.Passed); Assert.Equal(DiagnosticSource.LiveLocal, r.Source); Assert.Equal("Reachable", r.NormalizedValue); Assert.NotNull(r.RawDigest);

        var bad = Live(new Stub(_ => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable)));
        var f = await bad.RunAsync(Req(new Uri("http://127.0.0.1:9/health")), CancellationToken.None);
        Assert.False(f.Passed); Assert.Equal("Unreachable", f.NormalizedValue);
    }

    [Fact]
    public async Task Live_probe_fails_closed_on_redirect_exception_oversize_and_bad_url()
    {
        var redirect = Live(new Stub(_ => new HttpResponseMessage(HttpStatusCode.Found) { Headers = { Location = new Uri("http://evil.example/") } }));
        Assert.Null((await redirect.RunAsync(Req(new Uri("http://127.0.0.1:9/health")), CancellationToken.None)).Passed);

        var boom = Live(new Stub(_ => throw new HttpRequestException("connection refused")));
        Assert.Null((await boom.RunAsync(Req(new Uri("http://127.0.0.1:9/health")), CancellationToken.None)).Passed);

        var big = Live(new Stub(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(new byte[100_000]) }), max: 1024);
        Assert.Null((await big.RunAsync(Req(new Uri("http://127.0.0.1:9/health")), CancellationToken.None)).Passed);

        var stub = new Stub(_ => new HttpResponseMessage(HttpStatusCode.OK));
        var noTarget = await Live(stub).RunAsync(Req(null), CancellationToken.None);
        Assert.Null(noTarget.Passed); Assert.Equal(0, stub.Calls);
        Assert.False(LiveLocalHttpHealthProbe.IsAcceptable(new Uri("ftp://127.0.0.1/x")));
        Assert.False(LiveLocalHttpHealthProbe.IsAcceptable(new Uri("http://user:pw@127.0.0.1/x")));
    }

    [Fact]
    public async Task Dispatcher_resolves_only_fixture_diagnostics_and_configured_aliases()
    {
        var twin = InspectionTestKitTwin.Load();
        var sim = new SimulatedProbe(new InMemorySimulationState(), TimeProvider.System);
        var d = new DiagnosticDispatcher(new DiagnosticsOptions { Mode = DiagnosticSource.Simulated }, [sim]);
        var r = await d.RunAsync(twin.Twin, "NODE-A-HEALTH", "t", CancellationToken.None);
        Assert.Equal(DiagnosticSource.Simulated, r.Source); Assert.False(r.Passed);

        Assert.Equal(InspectionErrorCodes.ProbeNotAllowlisted, (await Assert.ThrowsAsync<InspectionConflictException>(() => d.RunAsync(twin.Twin, "http://10.0.0.1/health", "t", CancellationToken.None))).Code);

        var live = new DiagnosticDispatcher(new DiagnosticsOptions { Mode = DiagnosticSource.LiveLocal }, [Live(new Stub(_ => new HttpResponseMessage(HttpStatusCode.OK)))]);
        Assert.Equal(InspectionErrorCodes.ProbeNotAllowlisted, (await Assert.ThrowsAsync<InspectionConflictException>(() => live.RunAsync(twin.Twin, "NODE-A-HEALTH", "t", CancellationToken.None))).Code); // alias not configured

        var replay = new DiagnosticDispatcher(new DiagnosticsOptions { Mode = DiagnosticSource.RecordedReplay, ReplayDirectory = Path.Combine(RepoPaths.FixturesDir, "..", "telecom-lab", "replay") },
            [new RecordedReplayProbe(new DiagnosticsOptions { ReplayDirectory = Path.Combine(RepoPaths.FixturesDir, "..", "telecom-lab", "replay") }, TimeProvider.System)]);
        var seq = new List<bool?>();
        for (var i = 0; i < 4; i++) seq.Add((await replay.RunAsync(twin.Twin, "NODE-A-HEALTH", "t", CancellationToken.None)).Passed);
        Assert.Equal([false, false, true, null], seq); // exhausted recording → Unavailable
    }
}

internal static class InspectionTestKitTwin
{
    public static LoadedExpectedTwin Load()
    {
        var path = Path.Combine(RepoPaths.FixturesDir, "..", "telecom-lab", "expected-twin.json");
        var bytes = File.ReadAllBytes(path);
        var twin = System.Text.Json.JsonSerializer.Deserialize<ExpectedTwin>(bytes, LensQi.FieldOps.Core.Knowledge.KnowledgeJson.Options)!;
        return new LoadedExpectedTwin(twin, Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes)), "expected-twin.json");
    }
}
