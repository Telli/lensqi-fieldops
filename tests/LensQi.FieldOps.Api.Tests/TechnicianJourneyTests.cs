using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using LensQi.FieldOps.Core.Receipts;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Meta.Segmentation;

namespace LensQi.FieldOps.Tests;

/// <summary>One task and its linked inspection, through the unchanged public APIs. All perception is fake.</summary>
public class TechnicianJourneyTests
{
    [Fact]
    public async Task Guided_technician_task_rejects_fake_fix_then_issues_offline_verifiable_simulated_receipt()
    {
        var f = new FieldOpsApiFactory();
        var offline = Directory.CreateTempSubdirectory("fieldops-journey-offline-").FullName;
        try
        {
            using var c = f.CreateClient();
            var exchanges = new List<object>();
            async Task<JsonElement> Read(HttpResponseMessage response)
            {
                using (response)
                {
                    var raw = await response.Content.ReadAsStringAsync();
                    Assert.True(response.IsSuccessStatusCode, $"{response.RequestMessage?.RequestUri}: {raw}");
                    var value = JsonDocument.Parse(raw).RootElement.Clone();
                    exchanges.Add(new { path = response.RequestMessage?.RequestUri?.AbsolutePath, status = (int)response.StatusCode, response = value });
                    return value;
                }
            }
            async Task<JsonElement> Post(string path, object? body = null) =>
                await Read(body is null ? await c.PostAsync(path, null) : await c.PostAsJsonAsync(path, body));
            async Task<JsonElement> Upload(string path, byte salt)
            {
                using var form = new MultipartFormDataContent
                {
                    { new ByteArrayContent(TestImages.Png(640, 480, salt: salt)), "image", "synthetic.png" },
                    { new StringContent("RecordedFixture"), "source" },
                };
                return await Read(await c.PostAsync(path, form));
            }

            f.FakeChat.CannedResponse = JsonSerializer.Serialize(new
            {
                title = "Restore the lab switch link", summary = "Inspect and reseat the yellow cable, then verify the endpoint.",
                risk = "Low", estimatedMinutes = 5, restricted = "None", professionalRequired = false, professionalReason = (string?)null,
                tools = Array.Empty<string>(), parts = Array.Empty<string>(),
                steps = new[]
                {
                    new { title = "Locate the cable", instruction = "Identify the yellow cable at P2.", handsOn = false, lookFor = new[] { "yellow cable" }, cautions = Array.Empty<object>(), stopIf = Array.Empty<string>(), check = new { required = false, whatDoneLooksLike = "" } },
                    new { title = "Reseat the cable", instruction = "Reseat the yellow cable at P2.", handsOn = true, lookFor = new[] { "yellow cable" }, cautions = Array.Empty<object>(), stopIf = new[] { "the connector is damaged" }, check = new { required = false, whatDoneLooksLike = "" } },
                },
            });
            var created = await Post("/v1/tasks", new { mode = "Technician", goal = "Reseat the yellow cable at P2 and verify Node A", fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
            var taskId = created.GetProperty("task").GetProperty("taskId").GetString()!;
            var taskPath = $"/v1/tasks/{taskId}";
            await Post(taskPath + "/accept", new { safetyAcknowledged = true });
            var first = await Post(taskPath + "/steps/s1:complete");
            Assert.Equal("s2", first.GetProperty("currentStepId").GetString());
            var done = await Post(taskPath + "/steps/s2:complete");
            Assert.Equal("Completed", done.GetProperty("phase").GetString());
            Assert.All(done.GetProperty("plan").GetProperty("steps").EnumerateArray(), s => Assert.Equal("Done", s.GetProperty("status").GetString()));
            Assert.Equal(JsonValueKind.Null, done.GetProperty("inspectionId").ValueKind);

            var linked = await Post(taskPath + "/link-inspection");
            var id = linked.GetProperty("inspectionId").GetString()!;
            Assert.Equal(id, (await Post(taskPath + "/link-inspection")).GetProperty("inspectionId").GetString());
            var path = $"/v1/inspections/{id}";
            var state = await Read(await c.GetAsync(path));
            Assert.Equal("LAB-RACK-01", state.GetProperty("fixtureId").GetString());
            Assert.Equal("LAB-SW-01", state.GetProperty("assetId").GetString());

            await Post("/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = false });
            await Upload(path + "/observations", 1);
            MaskRecord Cable(int left) => new("0", 20, 20, left, 10, left + 20, 30, new RleMask([20, 20], CocoRle.Encode([0, 400])), 0.9);
            f.FakeSam.Queued.Enqueue([Cable(10)]);
            await Post(path + "/segment", new { concept = "yellow ethernet cable" });
            Assert.Equal("FaultConfirmed", (await Post(path + "/diagnostics/NODE-A-HEALTH:run")).GetProperty("state").GetProperty("stage").GetString());
            await Post(path + "/work-order", new { summary = "Restore the yellow cable link" });

            async Task Review(string photo)
            {
                foreach (var requirement in new[] { "switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured" })
                    await Post(path + "/checklist", new { requirementId = requirement, status = "Satisfied", photoEvidenceId = photo });
                await Post(path + "/acknowledge");
            }
            await Post(path + "/repair-claims", new { description = "Reseated the cable, endpoint still down" });
            f.FakeSam.Queued.Enqueue([Cable(200)]);
            var fake = await Upload(path + "/observations", 2);
            Assert.Equal("Changed", fake.GetProperty("changeAnalysis").GetProperty("verdict").GetString());
            await Review(fake.GetProperty("evidenceId").GetString()!);
            var denied = await Post(path + "/verify");
            Assert.Equal("Denied", denied.GetProperty("decision").GetProperty("result").GetString());
            Assert.Equal("VerificationFailed", denied.GetProperty("state").GetProperty("stage").GetString());

            await Post(path + "/repair-claims", new { description = "Restored the endpoint and reseated the cable" });
            await Post("/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = true });
            f.FakeSam.Queued.Enqueue([Cable(400)]);
            var fresh = await Upload(path + "/observations", 3);
            Assert.Equal("Changed", fresh.GetProperty("changeAnalysis").GetProperty("verdict").GetString());
            Assert.NotEqual(fake.GetProperty("evidenceId").GetString(), fresh.GetProperty("evidenceId").GetString());
            await Review(fresh.GetProperty("evidenceId").GetString()!);
            using var request = new HttpRequestMessage(HttpMethod.Post, path + "/verify");
            request.Headers.Add("Idempotency-Key", "journey-verify");
            var verified = await Read(await c.SendAsync(request));
            Assert.Equal("Verified", verified.GetProperty("decision").GetProperty("result").GetString());
            Assert.Equal("VerifiedCompleteSimulated", verified.GetProperty("decision").GetProperty("outcomeKind").GetString());
            using var repeat = new HttpRequestMessage(HttpMethod.Post, path + "/verify");
            repeat.Headers.Add("Idempotency-Key", "journey-verify");
            var replay = await Read(await c.SendAsync(repeat));
            Assert.True(replay.GetProperty("replayed").GetBoolean());
            Assert.Equal(verified.GetProperty("decision").ToString(), replay.GetProperty("decision").ToString());

            var events = await Read(await c.GetAsync(taskPath + "/events"));
            Assert.Single(events.EnumerateArray(), e => e.GetProperty("kind").GetString() == "InspectionLinked");
            var plan = events.EnumerateArray().Single(e => e.GetProperty("kind").GetString() == "PlanProposed");
            Assert.Equal("fake-muse", plan.GetProperty("modelId").GetString());
            Assert.Equal(64, plan.GetProperty("promptDigest").GetString()!.Length);
            Assert.Equal(64, plan.GetProperty("responseDigest").GetString()!.Length);
            var receipt = await Read(await c.GetAsync(path + "/receipt"));
            Assert.All(receipt.GetProperty("diagnostics").EnumerateArray(), d => Assert.Equal("Simulated", d.GetProperty("result").GetProperty("source").GetString()));
            var receiptStatus = await Read(await c.GetAsync(path + "/receipt/verify"));
            Assert.True(receiptStatus.GetProperty("isValid").GetBoolean());
            CopyDirectory(receiptStatus.GetProperty("bundle").GetString()!, offline);
            c.Dispose();
            f.Dispose(); // Verify the copy with the API stopped; the same verifier is used by the CLI.
            Assert.True(ReceiptVerifier.Verify(offline).IsValid);
            var artifact = Directory.GetFiles(Path.Combine(offline, "artifacts")).First();
            var original = File.ReadAllBytes(artifact);
            var altered = original.ToArray(); altered[^1] ^= 0xff; File.WriteAllBytes(artifact, altered);
            Assert.False(ReceiptVerifier.Verify(offline).IsValid);
            File.WriteAllBytes(artifact, original);
            Assert.True(ReceiptVerifier.Verify(offline).IsValid);

            // Opt-in export; regular tests never write to the repository. Destination must be new.
            if (Environment.GetEnvironmentVariable("FIELDOPS_JOURNEY_EVIDENCE") is { Length: > 0 } destination)
            {
                Assert.False(Directory.Exists(destination), "Evidence destination must not already exist");
                Directory.CreateDirectory(destination);
                CopyDirectory(offline, Path.Combine(destination, "receipt"));
                File.Copy(Path.Combine(f.DataDir, "tasks", taskId + ".jsonl"), Path.Combine(destination, "task-events.jsonl"));
                File.WriteAllText(Path.Combine(destination, "run.json"), JsonSerializer.Serialize(new
                {
                    recordedAtUtc = DateTimeOffset.UtcNow, provenance = "FAKE_MODELS_SIMULATED_DIAGNOSTICS",
                    taskId, inspectionId = id, servingPlanner = "fake-muse", segmentation = "FakeSegmenter (sam-3.1 wire label; no genuine call)",
                    outcome = "VerifiedCompleteSimulated", offlineReceiptValid = true, tamperedCopyRejected = true, exchanges,
                }, new JsonSerializerOptions { WriteIndented = true }));
                var manifest = Directory.GetFiles(destination, "*", SearchOption.AllDirectories).Order().Select(file => new
                {
                    path = Path.GetRelativePath(destination, file), sha256 = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file))),
                });
                File.WriteAllText(Path.Combine(destination, "files.json"), JsonSerializer.Serialize(manifest, new JsonSerializerOptions { WriteIndented = true }));
            }
        }
        finally
        {
            f.Dispose();
            Directory.Delete(f.DataDir, true);
            Directory.Delete(offline, true);
        }
    }

    private static void CopyDirectory(string source, string destination)
    {
        Directory.CreateDirectory(destination);
        foreach (var file in Directory.GetFiles(source, "*", SearchOption.AllDirectories))
        {
            var target = Path.Combine(destination, Path.GetRelativePath(source, file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(file, target);
        }
    }
}
