using LensQi.FieldOps.Api.Features.VendorKnowledge;
using LensQi.FieldOps.Api.Infrastructure.Knowledge;
using LensQi.FieldOps.Core.Diagnostics;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Muse;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);

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

// Server-owned probe registry (VK-R06.1). P0 registers only the simulated lab health probe.
builder.Services.AddSingleton<IDiagnosticRegistry>(new InMemoryDiagnosticRegistry([
    new DiagnosticRegistration("NODE-A-HEALTH", ObservationKind.EndpointReachability, "Approved lab node A HTTP health endpoint (simulated in P0)."),
]));

// Muse Spark client. The key comes only from server configuration or MODEL_API_KEY; it is never accepted from a request.
builder.Services.AddSingleton(sp =>
{
    var o = new MuseOptions();
    builder.Configuration.GetSection(MuseOptions.SectionName).Bind(o);
    o.ApiKey ??= Environment.GetEnvironmentVariable(MuseOptions.ApiKeyEnvironmentVariable);
    return o;
});
builder.Services.AddHttpClient("muse");
builder.Services.AddSingleton<IChatClient>(sp =>
    new MuseSparkChatClient(sp.GetRequiredService<IHttpClientFactory>().CreateClient("muse"), sp.GetRequiredService<MuseOptions>()));

var app = builder.Build();

// Fail closed at startup: an invalid pack or binding stops the process before any inspection can use it.
_ = app.Services.GetRequiredService<IKnowledgePackStore>();
_ = app.Services.GetRequiredService<IReadOnlyList<FixtureKnowledgeBinding>>();

app.MapGet("/health", (IKnowledgePackStore store) => Results.Ok(new { status = "ok", packs = store.Packs.Count }));
app.MapVendorKnowledge();

app.Run();

public partial class Program;
