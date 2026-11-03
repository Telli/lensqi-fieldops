using LensQi.FieldOps.Api.Features.Inspections;
using LensQi.FieldOps.Api.Features.LiveVision;
using LensQi.FieldOps.Api.Features.Receipts;
using LensQi.FieldOps.Api.Features.Segmentation;
using LensQi.FieldOps.Api.Features.Simulation;
using LensQi.FieldOps.Api.Features.SitePacks;
using LensQi.FieldOps.Api.Features.Tasks;
using LensQi.FieldOps.Api.Infrastructure.Tasks;
using LensQi.FieldOps.Meta.Tasks;
using LensQi.FieldOps.Api.Features.VendorKnowledge;
using LensQi.FieldOps.Api.Infrastructure.Evidence;
using LensQi.FieldOps.Api.Infrastructure.Fixtures;
using LensQi.FieldOps.Api.Infrastructure.Hardening;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Diagnostics;
using LensQi.FieldOps.Meta.Knowledge;
using LensQi.FieldOps.Meta.Segmentation;
using LensQi.FieldOps.Meta.Vision;
using LensQi.FieldOps.Api.Infrastructure.Knowledge;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Diagnostics;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Muse;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);

// Enums travel as their names on the wire (ChecklistStatus, DiagnosticSource, InspectionStage, ...).
builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.Converters.Add(new System.Text.Json.Serialization.JsonStringEnumConverter(allowIntegerValues: false)));

builder.Services.Configure<KnowledgeOptions>(builder.Configuration.GetSection(KnowledgeOptions.SectionName));
builder.Services.AddSingleton(sp =>
{
    var options = sp.GetRequiredService<IOptions<KnowledgeOptions>>().Value;
    var dir = KnowledgePaths.ResolveDirectory(builder.Environment.ContentRootPath, options.PackDirectory);
    return new KnowledgePackValidator(Path.Combine(dir, options.PackSchemaFile), Path.Combine(dir, options.BindingSchemaFile));
});
builder.Services.AddSingleton<IKnowledgePackStore>(sp =>
{
    var options = sp.GetRequiredService<IOptions<KnowledgeOptions>>().Value;
    var dir = KnowledgePaths.ResolveDirectory(builder.Environment.ContentRootPath, options.PackDirectory);
    return FileKnowledgePackStore.Load(dir, options, sp.GetRequiredService<KnowledgePackValidator>(), TimeProvider.System);
});
builder.Services.AddSingleton<IReadOnlyList<FixtureKnowledgeBinding>>(sp =>
{
    var options = sp.GetRequiredService<IOptions<KnowledgeOptions>>().Value;
    var dir = KnowledgePaths.ResolveDirectory(builder.Environment.ContentRootPath, options.PackDirectory);
    return FixtureBindingLoader.Load(dir, options, sp.GetRequiredService<KnowledgePackValidator>(), sp.GetRequiredService<IKnowledgePackStore>());
});
builder.Services.AddSingleton(sp => new KnowledgeResolverOptions
{
    MaxFacts = sp.GetRequiredService<IOptions<KnowledgeOptions>>().Value.MaxFacts,
});
builder.Services.AddSingleton<IVendorKnowledgeResolver, KnowledgeResolver>();
builder.Services.AddSingleton<LensQi.FieldOps.Api.Infrastructure.Hardening.KeyedGate>();

// Server-owned probe registry (VK-R06.1). P0 registers only the simulated lab health probe.
builder.Services.AddSingleton<IDiagnosticRegistry>(new InMemoryDiagnosticRegistry([
    new DiagnosticRegistration("NODE-A-HEALTH", ObservationKind.EndpointReachability, "Approved lab node A HTTP health endpoint (simulated in P0)."),
]));

// Muse Spark client. The key comes only from server configuration or MODEL_API_KEY; it is never accepted from a request.
builder.Services.AddSingleton(sp =>
{
    var o = new MuseOptions();
    builder.Configuration.GetSection(MuseOptions.SectionName).Bind(o);
    o.ApiKey ??= Environment.GetEnvironmentVariable(MuseOptions.ApiKeyEnvironmentVariable) ?? ReadDotEnv(builder.Environment.ContentRootPath, MuseOptions.ApiKeyEnvironmentVariable);
    return o;
});
builder.Services.AddHttpClient("muse");
builder.Services.AddSingleton<IChatClient>(sp =>
    new MuseSparkChatClient(sp.GetRequiredService<IHttpClientFactory>().CreateClient("muse"), sp.GetRequiredService<MuseOptions>()));

// Expected twin (parent FR-01): schema + invariants + byte-exact digest; fail closed at startup.
builder.Services.Configure<FixturesOptions>(builder.Configuration.GetSection(FixturesOptions.SectionName));
builder.Services.AddSingleton(sp =>
{
    var o = sp.GetRequiredService<IOptions<FixturesOptions>>().Value;
    return ExpectedTwinStore.Load(KnowledgePaths.ResolveDirectory(builder.Environment.ContentRootPath, o.TwinDirectory), o);
});

// Storage: append-only JSONL event logs and content-addressed evidence files.
builder.Services.Configure<StorageOptions>(builder.Configuration.GetSection(StorageOptions.SectionName));
builder.Services.AddSingleton(sp =>
{
    var o = sp.GetRequiredService<IOptions<StorageOptions>>().Value;
    var dir = Path.IsPathRooted(o.DataDirectory) ? o.DataDirectory : Path.Combine(builder.Environment.ContentRootPath, o.DataDirectory);
    Directory.CreateDirectory(dir);
    var store = new InspectionStore(dir);
    // A restart keeps open inspections: every persisted log is replayed against the installed twin, fail closed per file.
    var skipped = store.LoadPersisted(sp.GetRequiredService<ExpectedTwinStore>().Find);
    var log = sp.GetRequiredService<ILoggerFactory>().CreateLogger("InspectionStore");
    log.LogInformation("Rehydrated {Count} inspection(s) from {Dir}", store.All.Count(), dir);
    foreach (var s in skipped) log.LogWarning("Left inspection log {File} on disk: {Reason}", s.File, s.Reason);
    return store;
});
builder.Services.AddSingleton(sp =>
{
    var o = sp.GetRequiredService<IOptions<StorageOptions>>().Value;
    var dir = Path.IsPathRooted(o.DataDirectory) ? o.DataDirectory : Path.Combine(builder.Environment.ContentRootPath, o.DataDirectory);
    var store = new TaskStore(dir);
    // Guided tasks survive a restart the same way inspections do: replayed from their logs, fail closed per file.
    var skipped = store.LoadPersisted();
    var log = sp.GetRequiredService<ILoggerFactory>().CreateLogger("TaskStore");
    log.LogInformation("Rehydrated {Count} task(s) from {Dir}", store.All.Count(), dir);
    foreach (var s in skipped) log.LogWarning("Left task log {File} on disk: {Reason}", s.File, s.Reason);
    return store;
});
builder.Services.AddSingleton(sp =>
{
    var o = sp.GetRequiredService<IOptions<StorageOptions>>().Value;
    var dir = Path.IsPathRooted(o.DataDirectory) ? o.DataDirectory : Path.Combine(builder.Environment.ContentRootPath, o.DataDirectory);
    return new EvidenceFileStore(dir);
});
builder.Services.AddSingleton(TimeProvider.System);

// Diagnostics (parent FR-06): mode and targets are server configuration only.
builder.Services.AddSingleton(sp =>
{
    var o = new DiagnosticsOptions();
    builder.Configuration.GetSection(DiagnosticsOptions.SectionName).Bind(o);
    if (o.ReplayDirectory is { } rd && !Path.IsPathRooted(rd)) o.ReplayDirectory = Path.Combine(builder.Environment.ContentRootPath, rd);
    return o;
});
builder.Services.AddSingleton<ISimulationState, InMemorySimulationState>();
builder.Services.AddSingleton<IDiagnosticProbe>(sp => new SimulatedProbe(sp.GetRequiredService<ISimulationState>(), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<IDiagnosticProbe>(sp => new RecordedReplayProbe(sp.GetRequiredService<DiagnosticsOptions>(), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<IDiagnosticProbe>(sp => new LiveLocalHttpHealthProbe(LiveLocalHttpHealthProbe.CreateClient(), sp.GetRequiredService<DiagnosticsOptions>(), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<DiagnosticDispatcher>();
builder.Services.AddSingleton<VisualAssessmentService>();
builder.Services.AddSingleton<LiveSceneService>();
builder.Services.AddSingleton<TaskPlanner>();
builder.Services.AddSingleton<StepCoach>();
builder.Services.AddSingleton<ReceiptService>();
builder.Services.AddSingleton<GroundedProposalService>();

// SAM 3.1 (parent FR-03): Responses API client + Python sidecar for the special-token grammar and the pixel crop.
builder.Services.AddSingleton(sp => { var o = new SamOptions(); builder.Configuration.GetSection(SamOptions.SectionName).Bind(o); return o; });
builder.Services.AddSingleton(sp =>
{
    var repoRoot = Path.GetDirectoryName(KnowledgePaths.ResolveDirectory(builder.Environment.ContentRootPath, "fixtures"))!;
    return new PythonSidecar(sp.GetRequiredService<SamOptions>(), repoRoot);
});
builder.Services.AddSingleton<ISamOutputParser>(sp => sp.GetRequiredService<PythonSidecar>());
builder.Services.AddSingleton<IRoiCropper>(sp => sp.GetRequiredService<PythonSidecar>());
builder.Services.AddSingleton<IImageAnalyzer>(sp => sp.GetRequiredService<PythonSidecar>());
builder.Services.AddSingleton<IImageOrienter>(sp => sp.GetRequiredService<PythonSidecar>());
builder.Services.AddHttpClient("sam");
builder.Services.AddSingleton(sp =>
{
    var o = new ChangeEvidenceOptions();
    builder.Configuration.GetSection(ChangeEvidenceOptions.SectionName).Bind(o);
    return o.ToThresholds();
});
builder.Services.AddSingleton<ChangeEvidenceService>();
builder.Services.AddSingleton<ISegmenter>(sp => new SamSegmentationClient(sp.GetRequiredService<IHttpClientFactory>().CreateClient("sam"), sp.GetRequiredService<SamOptions>(), sp.GetRequiredService<MuseOptions>(), sp.GetRequiredService<ISamOutputParser>()));

// T6.1: request timeouts and rate limits on the endpoints that spend Meta Model API calls.
builder.Services.AddModelCallLimits(builder.Configuration);
builder.Services.AddLiveVision(builder.Configuration);

var app = builder.Build();

// Fail closed at startup: an invalid pack or binding stops the process before any inspection can use it.
_ = app.Services.GetRequiredService<IKnowledgePackStore>();
_ = app.Services.GetRequiredService<IReadOnlyList<FixtureKnowledgeBinding>>();
_ = app.Services.GetRequiredService<ExpectedTwinStore>();
_ = app.Services.GetRequiredService<InspectionStore>();
_ = app.Services.GetRequiredService<TaskStore>();

// An unexpected exception is logged by the middleware; the client gets the error shape without its type or message.
app.UseExceptionHandler(error => error.Run(async context =>
{
    context.Response.StatusCode = StatusCodes.Status500InternalServerError;
    await context.Response.WriteAsJsonAsync(new ApiError("internal", "Unexpected server error"));
}));

app.UseModelCallLimits();

app.MapGet("/health", (IKnowledgePackStore store, ExpectedTwinStore twins, DiagnosticsOptions diag) => Results.Ok(new { status = "ok", packs = store.Packs.Count, fixtures = twins.Twins.Count, diagnosticsMode = diag.Mode.ToString() }));
app.MapVendorKnowledge();
app.MapInspections();
app.MapSimulation();
app.MapReceipts();
app.MapInspectionKnowledge();
app.MapSitePacks();
app.MapSegmentation();
app.MapLiveVision();
app.MapTasks();

// Technician app and operations dashboard: a single-page app built from apps/web into wwwroot (no Node at runtime).
app.UseDefaultFiles();
app.UseStaticFiles();
// Client-side routes (/m/..., /ops/...) load the app; API paths keep their JSON 404.
app.MapFallback(async ctx =>
{
    var path = ctx.Request.Path.Value ?? "";
    if (path.StartsWith("/v1", StringComparison.Ordinal) || path.StartsWith("/api", StringComparison.Ordinal) || path.StartsWith("/health", StringComparison.Ordinal) || Path.HasExtension(path))
    {
        ctx.Response.StatusCode = StatusCodes.Status404NotFound;
        await ctx.Response.WriteAsJsonAsync(LensQi.FieldOps.Api.Shared.ApiError.NotFound($"No route for {path}"));
        return;
    }
    var index = Path.Combine(app.Environment.WebRootPath ?? Path.Combine(app.Environment.ContentRootPath, "wwwroot"), "index.html");
    ctx.Response.ContentType = "text/html; charset=utf-8";
    await ctx.Response.SendFileAsync(index);
});

app.Run();

public partial class Program
{
    /// <summary>Developer convenience: read KEY=value from a gitignored .env at or above the content root. Never logged, never served.</summary>
    internal static string? ReadDotEnv(string start, string key)
    {
        var dir = new DirectoryInfo(start);
        for (var i = 0; dir is not null && i < 6; i++, dir = dir.Parent)
        {
            var path = Path.Combine(dir.FullName, ".env");
            if (!File.Exists(path)) continue;
            foreach (var line in File.ReadLines(path))
            {
                var l = line.Trim();
                if (l.StartsWith(key + "=", StringComparison.Ordinal))
                {
                    var v = l[(key.Length + 1)..].Trim().Trim('"');
                    return string.IsNullOrEmpty(v) ? null : v;
                }
            }
        }
        return null;
    }
}
