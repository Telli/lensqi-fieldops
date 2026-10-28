// FieldOps evaluation runner (PRD §11, T7.1/T7.2). Drives the running API over HTTP through every labelled scenario,
// making genuine Muse Spark and SAM 3.1 calls, and scores:
//   - deterministic expectations (hard pass/fail): decision, final stage, outcome kind, rejections
//   - model behaviour (reported, never asserted as truth): Muse feature statuses vs the allowed set, SAM match vs expected
//   - baseline A (single Muse look, no gate) vs proposed B (full loop with the deterministic gate): false completions
//   - latency, token usage, inconclusive/view-request rates, receipt verification
// Usage: dotnet run --project tools/LensQi.FieldOps.Eval -- [--base http://127.0.0.1:5199] [--only id,id] [--no-sam]

using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Core.Receipts;

var baseUrl = Arg("--base") ?? "http://127.0.0.1:5199";
var only = Arg("--only")?.Split(',', StringSplitOptions.RemoveEmptyEntries).ToHashSet() ?? [];
var noSam = args.Contains("--no-sam");
var root = FindRepoRoot();
var scenariosPath = Path.Combine(root, "fixtures", "telecom-lab", "eval", "scenarios.json");
var imagesDir = Path.Combine(root, "fixtures", "telecom-lab", "eval", "images");
var doc = JsonNode.Parse(File.ReadAllText(scenariosPath))!.AsObject();
var heldOut = doc["heldOut"]!.AsArray().Select(n => n!.GetValue<string>()).ToHashSet();
var scenarios = doc["scenarios"]!.AsArray().Select(n => n!.AsObject()).Where(s => only.Count == 0 || only.Contains(s["id"]!.GetValue<string>())).ToList();

using var http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(120) };
var health = await Get("/health");
Console.WriteLine($"API {baseUrl} · diagnostics mode {health["diagnosticsMode"]} · {scenarios.Count} scenario(s)");
if (health["diagnosticsMode"]!.GetValue<string>() != "Simulated") { Console.Error.WriteLine("Evaluation requires Diagnostics:Mode=Simulated"); return 2; }

var results = new List<JsonObject>();
var sw = Stopwatch.StartNew();
foreach (var s in scenarios)
{
    var id = s["id"]!.GetValue<string>();
    var r = new JsonObject { ["id"] = id, ["category"] = s["category"]!.GetValue<string>(), ["heldOut"] = heldOut.Contains(id), ["description"] = s["description"]!.GetValue<string>() };
    var t0 = Stopwatch.StartNew();
    try { await RunScenario(s, r); r["error"] = null; }
    catch (Exception ex) { r["error"] = ex.GetType().Name + ": " + ex.Message; r["deterministicPass"] = false; }
    r["elapsedSeconds"] = Math.Round(t0.Elapsed.TotalSeconds, 1);
    results.Add(r);
    Console.WriteLine($"{(r["deterministicPass"]?.GetValue<bool>() == true ? "PASS" : "FAIL")}  {id,-32} {r["elapsedSeconds"],5}s  {r["summary"]}");
}
sw.Stop();

var report = BuildReport(results, health, sw.Elapsed);
var outDir = Path.Combine(root, "docs", "evaluation"); Directory.CreateDirectory(outDir);
var stamp = DateTime.UtcNow.ToString("yyyyMMdd'T'HHmmss'Z'");
File.WriteAllText(Path.Combine(outDir, $"eval-{stamp}.json"), new JsonObject { ["report"] = report, ["scenarios"] = new JsonArray(results.Select(x => (JsonNode)x.DeepClone()).ToArray()) }.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
File.WriteAllText(Path.Combine(outDir, $"eval-{stamp}.md"), Markdown(report, results, stamp));
Console.WriteLine($"\nReport: docs/evaluation/eval-{stamp}.md");
return results.All(x => x["deterministicPass"]?.GetValue<bool>() == true) ? 0 : 1;

// ----------------------------------------------------------------------------------------------------------------------

async Task RunScenario(JsonObject s, JsonObject r)
{
    var flow = s["flow"]!.GetValue<string>();
    var expect = s["expect"]?.AsObject() ?? new JsonObject();
    var fixtureId = s["fixtureId"]!.GetValue<string>(); var assetId = s["assetId"]!.GetValue<string>();
    var fails = new List<string>();

    // create
    var create = await http.PostAsJsonAsync("/v1/inspections", new { fixtureId, assetId });
    var createBody = JsonNode.Parse(await create.Content.ReadAsStringAsync())!;
    if (flow == "createonly")
    {
        var code = createBody["code"]?.GetValue<string>();
        var want = expect["createRejectedWith"]!.GetValue<string>();
        r["createStatus"] = (int)create.StatusCode; r["createCode"] = code;
        r["deterministicPass"] = !create.IsSuccessStatusCode && code == want;
        r["summary"] = $"create -> {(int)create.StatusCode} {code}";
        return;
    }
    if (!create.IsSuccessStatusCode) throw new Exception("create failed: " + createBody);
    var id = createBody["inspectionId"]!.GetValue<string>();
    r["inspectionId"] = id;

    // simulated endpoint before the repair claim: down for fault flows, as labelled otherwise
    var upBefore = s["endpointUpBeforeRepair"]?.GetValue<bool>() ?? false;
    await Post("/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = upBefore });

    // photo
    var image = Path.Combine(imagesDir, id.Length > 0 ? s["id"]!.GetValue<string>() + ".jpg" : "");
    var photoId = await Upload(id, image);

    // Muse (baseline A = this single look)
    var inv = await Post($"/v1/inspections/{id}/investigate");
    var a = inv["assessment"]!.AsObject();
    r["muse"] = new JsonObject { ["modelId"] = inv["modelId"]?.GetValue<string>(), ["nextAction"] = a["nextAction"]?.GetValue<string>(), ["requestedView"] = a["requestedView"]?.GetValue<string>(),
        ["inputTokens"] = inv["usage"]?["inputTokenCount"]?.GetValue<long>(), ["outputTokens"] = inv["usage"]?["outputTokenCount"]?.GetValue<long>(),
        ["hypotheses"] = new JsonArray(a["hypotheses"]!.AsArray().Select(h => (JsonNode)new JsonObject { ["featureId"] = h!["featureId"]!.GetValue<string>(), ["status"] = h["status"]!.GetValue<string>() }).ToArray()) };
    var got = a["hypotheses"]!.AsArray().ToDictionary(h => h!["featureId"]!.GetValue<string>(), h => h!["status"]!.GetValue<string>());
    var expMuse = s["expectMuse"]?.AsObject();
    int musePass = 0, museTotal = 0;
    if (expMuse is not null)
        foreach (var kv in expMuse) { museTotal++; var allowed = kv.Value!.AsArray().Select(x => x!.GetValue<string>()).ToHashSet(); if (got.TryGetValue(kv.Key, out var st) && allowed.Contains(st)) musePass++; }
    r["museExpectedMatches"] = musePass; r["museExpectedTotal"] = museTotal;
    var cableObserved = got.TryGetValue("yellow_cable_near_end_visible", out var cs) && cs == "Observed";
    var chassisObserved = got.TryGetValue("switch_chassis_visible", out var ch) && ch == "Observed";
    r["baselineAWouldAccept"] = cableObserved && chassisObserved;         // single-look model says the connection looks fine
    r["viewRequestedOrUnknown"] = a["nextAction"]?.GetValue<string>() is "RequestView" or "SegmentRegion" || got.Values.Any(v => v is "Unknown" or "Suspected" or "Conflicting");
    if (expect["modelMustNotClaimAsset"]?.GetValue<bool>() == true) r["modelClaimedWrongAsset"] = chassisObserved;

    // SAM
    if (!noSam && s["samConcept"] is { } concept)
    {
        var seg = await Post($"/v1/inspections/{id}/segment", new { concept = concept.GetValue<string>() });
        var g = seg["segmentation"]!;
        r["sam"] = new JsonObject { ["matched"] = g["matched"]!.GetValue<bool>(), ["boxes"] = g["boxes"]!.AsArray().Count, ["elapsedSeconds"] = g["elapsedSeconds"]!.GetValue<double>(),
            ["confidence"] = g["boxes"]!.AsArray().FirstOrDefault()?["confidence"]?.GetValue<double>() };
        if (s["expectSamMatch"] is { } esm && esm.GetValueKind() != JsonValueKind.Null) r["samExpectedMatch"] = esm.GetValue<bool>();
    }

    // probe before any claim
    var probe = await Post($"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run");
    var stageAfterProbe = probe["state"]!["stage"]!.GetValue<string>();

    if (flow == "healthy")
    {
        var faultConfirmed = stageAfterProbe == "FaultConfirmed";
        if (faultConfirmed != (expect["faultConfirmed"]?.GetValue<bool>() ?? false)) fails.Add($"faultConfirmed={faultConfirmed}");
        var allowedStages = expect["finalStageIn"]!.AsArray().Select(x => x!.GetValue<string>()).ToHashSet();
        if (!allowedStages.Contains(stageAfterProbe)) fails.Add($"stage={stageAfterProbe}");
        r["finalStage"] = stageAfterProbe; r["decision"] = null;
        r["deterministicPass"] = fails.Count == 0;
        r["summary"] = $"stage {stageAfterProbe}; muse {musePass}/{museTotal}; A-accept={r["baselineAWouldAccept"]}" + (fails.Count > 0 ? " FAIL " + string.Join(",", fails) : "");
        return;
    }

    // fault flows: work order + claim
    if (stageAfterProbe != "FaultConfirmed") fails.Add($"expected FaultConfirmed after failing probe, got {stageAfterProbe}");
    await Post($"/v1/inspections/{id}/work-order", new { summary = "Reseat the yellow cable at P2" });
    await Post($"/v1/inspections/{id}/repair-claims", new { description = "Technician claims the repair" });
    var upAfter = s["endpointUpAfterRepair"]?.GetValue<bool>() ?? false;
    await Post("/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = upAfter });

    if (flow != "nophoto")
    {
        var after = await Upload(id, image);
        foreach (var kv in s["checklist"]!.AsObject())
            await Post($"/v1/inspections/{id}/checklist", new { requirementId = kv.Key, status = kv.Value!.GetValue<string>(), photoEvidenceId = after });
    }
    await Post($"/v1/inspections/{id}/acknowledge");
    var verify = await Post($"/v1/inspections/{id}/verify");
    var d = verify["decision"]!; var stateNode = verify["state"]!;
    var result = d["result"]!.GetValue<string>(); var stage = stateNode["stage"]!.GetValue<string>(); var kind = d["outcomeKind"]?.GetValue<string>();
    r["decision"] = result; r["finalStage"] = stage; r["outcomeKind"] = kind; r["reasons"] = new JsonArray(d["reasons"]!.AsArray().Select(x => (JsonNode)x!.GetValue<string>()).ToArray());
    if (expect["decision"]?.GetValue<string>() is { } ed && ed != result) fails.Add($"decision={result} want {ed}");
    if (expect["finalStage"]?.GetValue<string>() is { } es && es != stage) fails.Add($"stage={stage} want {es}");
    if (expect.ContainsKey("outcomeKind") && (expect["outcomeKind"]?.GetValue<string>()) != kind) fails.Add($"outcomeKind={kind}");
    if (expect["outcomeKindNot"]?.GetValue<string>() is { } okn && okn == kind) fails.Add($"outcomeKind must not be {okn}");
    if (expect["reasonContains"]?.GetValue<string>() is { } rc && !d["reasons"]!.AsArray().Any(x => x!.GetValue<string>().Contains(rc, StringComparison.OrdinalIgnoreCase))) fails.Add($"reason lacks '{rc}'");

    // receipt integrity for every verified/denied flow
    var rv = await Get($"/v1/inspections/{id}/receipt/verify");
    r["receiptValid"] = rv["isValid"]!.GetValue<bool>();
    if (!r["receiptValid"]!.GetValue<bool>()) fails.Add("receipt verify failed");

    // B false completion: verified while the endpoint is actually down
    r["proposedBFalseCompletion"] = result == "Verified" && !upAfter;
    r["deterministicPass"] = fails.Count == 0;
    r["summary"] = $"{result} {kind ?? ""} → {stage}; muse {musePass}/{museTotal}; A-accept={r["baselineAWouldAccept"]}" + (fails.Count > 0 ? " FAIL " + string.Join(",", fails) : "");
}

JsonObject BuildReport(List<JsonObject> rs, JsonNode health, TimeSpan total)
{
    int Count(Func<JsonObject, bool> f) => rs.Count(f);
    bool B(JsonObject x, string k) => x[k]?.GetValue<bool>() == true;
    var withMuse = rs.Where(x => x["museExpectedTotal"] is not null && x["museExpectedTotal"]!.GetValue<int>() > 0).ToList();
    var fakeFix = rs.Where(x => x["category"]!.GetValue<string>() == "fakefix").ToList();
    var ambiguous = rs.Where(x => x["category"]!.GetValue<string>() == "ambiguous").ToList();
    var samScored = rs.Where(x => x["samExpectedMatch"] is not null && x["sam"] is not null).ToList();
    var latencies = rs.Where(x => x["elapsedSeconds"] is not null).Select(x => x["elapsedSeconds"]!.GetValue<double>()).OrderBy(v => v).ToList();
    var inTok = rs.Sum(x => x["muse"]?["inputTokens"]?.GetValue<long>() ?? 0); var outTok = rs.Sum(x => x["muse"]?["outputTokens"]?.GetValue<long>() ?? 0);
    return new JsonObject
    {
        ["generatedAtUtc"] = DateTime.UtcNow.ToString("o"), ["totalSeconds"] = Math.Round(total.TotalSeconds, 1), ["diagnosticsMode"] = health["diagnosticsMode"]?.GetValue<string>(),
        ["scenarios"] = rs.Count, ["heldOut"] = Count(x => B(x, "heldOut")),
        ["deterministicPass"] = Count(x => B(x, "deterministicPass")), ["deterministicFail"] = Count(x => !B(x, "deterministicPass")),
        ["falseCompletions_proposedB"] = Count(x => B(x, "proposedBFalseCompletion")),
        ["falseCompletions_baselineA_onFakeFix"] = fakeFix.Count(x => B(x, "baselineAWouldAccept")), ["fakeFixScenarios"] = fakeFix.Count,
        ["museStatusWithinAllowed"] = withMuse.Sum(x => x["museExpectedMatches"]!.GetValue<int>()), ["museStatusTotal"] = withMuse.Sum(x => x["museExpectedTotal"]!.GetValue<int>()),
        ["ambiguousScenariosRequestingEvidence"] = ambiguous.Count(x => B(x, "viewRequestedOrUnknown")), ["ambiguousScenarios"] = ambiguous.Count,
        ["samAgreesWithLabel"] = samScored.Count(x => x["sam"]!["matched"]!.GetValue<bool>() == x["samExpectedMatch"]!.GetValue<bool>()), ["samScored"] = samScored.Count,
        ["wrongAssetClaimedByModel"] = Count(x => B(x, "modelClaimedWrongAsset")),
        ["receiptsVerified"] = Count(x => B(x, "receiptValid")), ["receiptsIssued"] = Count(x => x["receiptValid"] is not null),
        ["medianScenarioSeconds"] = latencies.Count == 0 ? 0 : latencies[latencies.Count / 2], ["museInputTokens"] = inTok, ["museOutputTokens"] = outTok,
        ["samImagesProcessed"] = Count(x => x["sam"] is not null),
    };
}

string Markdown(JsonObject rep, List<JsonObject> rs, string stamp)
{
    var sb = new StringBuilder();
    sb.AppendLine($"# FieldOps evaluation run {stamp}\n");
    sb.AppendLine("**Inputs:** 25 labelled scenarios over Pillow renders of a fictional switch (no photographs); diagnostics in **Simulated** mode; genuine `muse-spark-1.3` and `sam-3.1` calls through the running API. Model-behaviour numbers are reported, not asserted as ground truth. Deterministic expectations are hard pass/fail.\n");
    sb.AppendLine("## Headline\n");
    sb.AppendLine("| Metric | Value |\n|---|---:|");
    sb.AppendLine($"| Scenarios (held-out) | {rep["scenarios"]} ({rep["heldOut"]}) |");
    sb.AppendLine($"| Deterministic pass / fail | {rep["deterministicPass"]} / {rep["deterministicFail"]} |");
    sb.AppendLine($"| **False completions, proposed B (full loop + gate)** | **{rep["falseCompletions_proposedB"]}** |");
    sb.AppendLine($"| False completions, baseline A (single Muse look, no gate) on fake-fix scenarios | {rep["falseCompletions_baselineA_onFakeFix"]} / {rep["fakeFixScenarios"]} |");
    sb.AppendLine($"| Muse feature statuses within the labelled allowed set | {rep["museStatusWithinAllowed"]} / {rep["museStatusTotal"]} |");
    sb.AppendLine($"| Ambiguous scenes where Muse flagged uncertainty or asked for a view | {rep["ambiguousScenariosRequestingEvidence"]} / {rep["ambiguousScenarios"]} |");
    sb.AppendLine($"| SAM match agrees with label | {rep["samAgreesWithLabel"]} / {rep["samScored"]} |");
    sb.AppendLine($"| Wrong-asset scenes where Muse still marked the chassis Observed | {rep["wrongAssetClaimedByModel"]} |");
    sb.AppendLine($"| Receipts verified / issued | {rep["receiptsVerified"]} / {rep["receiptsIssued"]} |");
    sb.AppendLine($"| Median scenario wall time (s) | {rep["medianScenarioSeconds"]} |");
    sb.AppendLine($"| Muse tokens in / out | {rep["museInputTokens"]} / {rep["museOutputTokens"]} |");
    sb.AppendLine($"| SAM images processed | {rep["samImagesProcessed"]} |");
    sb.AppendLine($"| Total wall time (s) | {rep["totalSeconds"]} |\n");
    sb.AppendLine("Baseline A counts a scenario as \"accepted\" when the single model look marks both the chassis and the cable near end as Observed, i.e. what a plain image analyzer would report as fine. Proposed B counts a false completion only when the gate returns Verified while the simulated endpoint is down.\n");
    sb.AppendLine("## Per scenario\n");
    sb.AppendLine("| Scenario | Category | Held-out | Deterministic | Muse statuses | A accepts | SAM | Decision → stage | Outcome kind | Receipt | s |\n|---|---|:-:|:-:|:-:|:-:|:-:|---|---|:-:|--:|");
    foreach (var x in rs)
    {
        string Val(string k) => x[k]?.ToString() ?? "";
        var muse = x["museExpectedTotal"] is null ? "" : $"{x["museExpectedMatches"]}/{x["museExpectedTotal"]}";
        var sam = x["sam"] is null ? "" : (x["sam"]!["matched"]!.GetValue<bool>() ? $"match {x["sam"]!["confidence"]?.GetValue<double>():0.00}" : "no match") + (x["samExpectedMatch"] is null ? "" : (x["sam"]!["matched"]!.GetValue<bool>() == x["samExpectedMatch"]!.GetValue<bool>() ? " ✓" : " ✗"));
        var det = x["deterministicPass"]?.GetValue<bool>() == true ? "✓" : "✗";
        var decision = x["decision"] is null ? (x["createCode"] is not null ? $"create {Val("createStatus")} {Val("createCode")}" : $"— → {Val("finalStage")}") : $"{Val("decision")} → {Val("finalStage")}";
        sb.AppendLine($"| {Val("id")} | {Val("category")} | {(x["heldOut"]?.GetValue<bool>() == true ? "✓" : "")} | {det} | {muse} | {(x["baselineAWouldAccept"]?.GetValue<bool>() == true ? "yes" : (x["baselineAWouldAccept"] is null ? "" : "no"))} | {sam} | {decision} | {Val("outcomeKind")} | {(x["receiptValid"] is null ? "" : x["receiptValid"]!.GetValue<bool>() ? "✓" : "✗")} | {Val("elapsedSeconds")} |");
        if (x["error"] is not null && x["error"]!.GetValueKind() != JsonValueKind.Null) sb.AppendLine($"|  | error: {x["error"]} | | | | | | | | | |");
    }
    sb.AppendLine("\n## Scope\n\nSynthetic renders are easier than real photographs; these numbers bound the pipeline, not field recognition rates. The simulated probe stands in for the lab endpoint; no live measurement was made. Verification outcomes in this run are `VerifiedCompleteSimulated` by construction and are never labelled live.\n");
    return sb.ToString();
}

async Task<string> Upload(string id, string image)
{
    using var form = new MultipartFormDataContent();
    var bytes = File.ReadAllBytes(image);
    var content = new ByteArrayContent(bytes); content.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
    form.Add(content, "image", Path.GetFileName(image));
    form.Add(new StringContent("RecordedFixture"), "source");
    form.Add(new StringContent(DateTime.UtcNow.ToString("o")), "observedAt");
    var r = await http.PostAsync($"/v1/inspections/{id}/observations", form);
    var j = JsonNode.Parse(await r.Content.ReadAsStringAsync())!;
    if (!r.IsSuccessStatusCode) throw new Exception("upload failed: " + j);
    return j["evidenceId"]!.GetValue<string>();
}

async Task<JsonNode> Post(string url, object? body = null)
{
    var r = body is null ? await http.PostAsync(url, null) : await http.PostAsJsonAsync(url, body);
    var text = await r.Content.ReadAsStringAsync();
    var j = JsonNode.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text)!;
    if (!r.IsSuccessStatusCode) throw new Exception($"{url} -> {(int)r.StatusCode}: {j}");
    return j;
}
async Task<JsonNode> Get(string url) => JsonNode.Parse(await http.GetStringAsync(url))!;

string? Arg(string name) { var i = Array.IndexOf(args, name); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }
static string FindRepoRoot()
{
    var dir = new DirectoryInfo(AppContext.BaseDirectory);
    for (var i = 0; dir is not null && i < 10; i++, dir = dir.Parent)
        if (Directory.Exists(Path.Combine(dir.FullName, "fixtures", "telecom-lab"))) return dir.FullName;
    throw new DirectoryNotFoundException("run from inside the repository");
}
