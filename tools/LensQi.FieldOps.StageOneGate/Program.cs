// Stage One gate: one genuine Muse Spark structured-output call, grounded only in the synthetic pack,
// validated by the same server rules the API uses. Writes a judge-readable evidence record and exits non-zero
// if the model's proposal is rejected. Never runs a diagnostic or touches a device.

using System.Text.Json;
using LensQi.FieldOps.Api.Infrastructure.Knowledge;
using LensQi.FieldOps.Core.Diagnostics;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Meta.Knowledge;
using LensQi.FieldOps.Meta.Muse;

var repoRoot = FindRepoRoot();
var apiKey = ResolveApiKey(repoRoot);
if (apiKey is null)
{
    Console.Error.WriteLine($"MODEL_API_KEY is not set. Export it, or put MODEL_API_KEY=... in {Path.Combine(repoRoot, ".env")} (gitignored).");
    return 3;
}

var options = new KnowledgeOptions();
var dir = Path.Combine(repoRoot, options.PackDirectory);
var validator = new KnowledgePackValidator(Path.Combine(dir, options.PackSchemaFile), Path.Combine(dir, options.BindingSchemaFile));
var store = FileKnowledgePackStore.Load(dir, options, validator);
var binding = FixtureBindingLoader.Load(dir, options, validator, store).Single(b => b.AssetId == "LAB-SW-01");
var registry = new InMemoryDiagnosticRegistry([
    new DiagnosticRegistration("NODE-A-HEALTH", ObservationKind.EndpointReachability, "Approved lab node A HTTP health endpoint (simulated in P0)."),
]);

var asset = binding.ToAssetIdentity();
var resolution = new KnowledgeResolver(store).Resolve(binding, asset, []);
Console.WriteLine($"Resolved {resolution.Facts.Count} applicable fact(s) from {resolution.PackId} ({resolution.PackSha256![..12]}…)");

var muse = new MuseOptions { ApiKey = apiKey, Model = Environment.GetEnvironmentVariable("MUSE_MODEL") ?? "muse-spark-1.3" };
using var http = new HttpClient();
var chat = new MuseSparkChatClient(http, muse);
var service = new GroundedProposalService(chat, registry);

var scenarios = new (string Name, Observation Observation)[]
{
    ("occluded-port-labels", new Observation(
        "Technician photographed the front of LAB-SW-01 in LAB-RACK-01. A single yellow patch cable is plugged into one of the lower " +
        "connectors, but the printed labels P1, P2 and P3 are partly hidden behind a cable tie, so the occupied port cannot be read. " +
        "No repair has been performed yet.")),
    ("repair-claimed-labels-visible", new Observation(
        "Technician reports moving the yellow patch cable and submits a close-up: labels P1, P2 and P3 are fully legible and the yellow " +
        "cable is seated in the connector labelled P2. The technician claims the repair is complete and asks for verification.")),
};

var records = new List<object>();
var allAccepted = true;
foreach (var (name, observation) in scenarios)
{
    Console.WriteLine();
    Console.WriteLine($"== Scenario: {name}");
    var result = await service.ProposeAsync(binding, asset, resolution, observation);
    allAccepted &= result.Accepted;
    Console.WriteLine($"model={result.ModelId} accepted={result.Accepted} tokens(in/out/reasoning)={result.Usage?.InputTokenCount}/{result.Usage?.OutputTokenCount}/{result.Usage?.AdditionalCounts?.GetValueOrDefault("reasoning_tokens")}");
    Console.WriteLine(result.RawResponse);
    foreach (var r in result.Validation.Rejections)
        Console.WriteLine($"REJECTED {r.Code}: {r.Message} [{string.Join(", ", r.OffendingIds)}]");
    records.Add(new
    {
        scenario = name,
        observation = observation.Description,
        modelId = result.ModelId,
        promptSha256 = result.PromptDigest,
        responseSha256 = result.ResponseDigest,
        usage = result.Usage is null ? null : new { input = result.Usage.InputTokenCount, output = result.Usage.OutputTokenCount, reasoning = result.Usage.AdditionalCounts?.GetValueOrDefault("reasoning_tokens") },
        accepted = result.Accepted,
        proposal = result.Proposal,
        rejections = result.Validation.Rejections,
        parseError = result.ParseError,
        rawResponse = result.RawResponse,
    });
}

var evidenceDir = Path.Combine(repoRoot, "docs", "evidence");
Directory.CreateDirectory(evidenceDir);
var stamp = DateTimeOffset.UtcNow.ToString("yyyyMMdd'T'HHmmss'Z'");
var evidencePath = Path.Combine(evidenceDir, $"stage-one-gate-{stamp}.json");
var evidence = new
{
    kind = "stage-one-gate",
    recordedAtUtc = DateTimeOffset.UtcNow,
    apiBase = muse.BaseUrl,
    model = muse.Model,
    reasoningEffort = muse.ReasoningEffort,
    maxOutputTokens = muse.MaxOutputTokens,
    pack = new { resolution.PackId, resolution.PackSha256, resolution.PackRevision },
    binding = new { binding.FixtureId, binding.AssetId, binding.ApprovedDiagnostics },
    applicableFactIds = resolution.Facts.Select(f => f.FactId),
    systemPromptSha256 = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(KnowledgeGroundingComposer.SystemPrompt))),
    scenarios = records,
    allAccepted,
    note = "Genuine Meta Model API call. No diagnostic was executed and no device was contacted. Proposals are model output validated by KnowledgeActionValidator; they are not evidence of equipment state.",
};
File.WriteAllText(evidencePath, JsonSerializer.Serialize(evidence, new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true }));
Console.WriteLine();
Console.WriteLine($"Evidence written: {Path.GetRelativePath(repoRoot, evidencePath)}");
Console.WriteLine(allAccepted ? "STAGE ONE GATE: PASS" : "STAGE ONE GATE: model proposal(s) rejected by server validation");
return allAccepted ? 0 : 2;

static string FindRepoRoot()
{
    var dir = new DirectoryInfo(AppContext.BaseDirectory);
    for (var i = 0; dir is not null && i < 10; i++, dir = dir.Parent)
        if (Directory.Exists(Path.Combine(dir.FullName, "fixtures", "vendor-knowledge"))) return dir.FullName;
    throw new DirectoryNotFoundException("Run from inside the repository.");
}

static string? ResolveApiKey(string repoRoot)
{
    var fromEnv = Environment.GetEnvironmentVariable(MuseOptions.ApiKeyEnvironmentVariable);
    if (!string.IsNullOrWhiteSpace(fromEnv)) return fromEnv.Trim();
    var envFile = Path.Combine(repoRoot, ".env");
    if (!File.Exists(envFile)) return null;
    foreach (var line in File.ReadLines(envFile))
    {
        var t = line.Trim();
        if (t.StartsWith(MuseOptions.ApiKeyEnvironmentVariable + "=", StringComparison.Ordinal))
            return t[(MuseOptions.ApiKeyEnvironmentVariable.Length + 1)..].Trim().Trim('"');
    }
    return null;
}
