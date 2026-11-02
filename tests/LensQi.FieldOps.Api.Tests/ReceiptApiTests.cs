using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Api.Infrastructure.Inspections;
using LensQi.FieldOps.Core.Receipts;
using Microsoft.Extensions.DependencyInjection;

namespace LensQi.FieldOps.Tests;

public class ReceiptApiTests : IClassFixture<FieldOpsApiFactory>
{
    private readonly FieldOpsApiFactory _factory;
    private readonly HttpClient _client;
    private static readonly byte[] Png = TestImages.Png(800, 600);

    public ReceiptApiTests(FieldOpsApiFactory factory) { _factory = factory; _client = factory.CreateClient(); }

    [Fact]
    public async Task Receipt_endpoints_issue_a_verifiable_bundle_with_knowledge_block_and_tamper_detection()
    {
        var create = await _client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var id = JsonDocument.Parse(await create.Content.ReadAsStringAsync()).RootElement.GetProperty("inspectionId").GetString()!;
        using var form = new MultipartFormDataContent { { new ByteArrayContent(Png), "image", "f.png" } };
        Assert.True((await _client.PostAsync($"/v1/inspections/{id}/observations", form)).IsSuccessStatusCode);
        await _client.PostAsync($"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run", null);

        var json = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt")).RootElement;
        Assert.Equal(EvidenceReceipt.Version, json.GetProperty("receiptVersion").GetString());
        Assert.Equal(64, json.GetProperty("manifestSha256").GetString()!.Length);
        Assert.Equal(1, json.GetProperty("artifacts").GetArrayLength());
        Assert.Equal("lab-switch-01.synthetic", json.GetProperty("knowledge").GetProperty("packId").GetString());
        Assert.Equal(3, json.GetProperty("knowledge").GetProperty("citations").GetArrayLength());
        Assert.Equal("Simulated", json.GetProperty("diagnostics")[0].GetProperty("result").GetProperty("source").GetString());

        var html = await _client.GetStringAsync($"/v1/inspections/{id}/receipt.html");
        Assert.Contains("FieldProof evidence receipt", html);
        Assert.Contains("Documentation (guidance, not evidence)", html);

        var verify = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt/verify")).RootElement;
        Assert.True(verify.GetProperty("isValid").GetBoolean());
        var bundle = verify.GetProperty("bundle").GetString()!;

        // tamper with the copied artifact, then both the CLI logic and the endpoint must fail
        var art = Directory.EnumerateFiles(Path.Combine(bundle, "artifacts")).First();
        var bytes = File.ReadAllBytes(art); bytes[^1] ^= 0xFF; File.WriteAllBytes(art, bytes);
        var v = ReceiptVerifier.Verify(bundle);
        Assert.False(v.IsValid);
        Assert.Contains(v.Failures, f => f.Contains("file was altered"));
        var endpoint = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt/verify")).RootElement;
        Assert.False(endpoint.GetProperty("isValid").GetBoolean());
        Assert.Contains(endpoint.GetProperty("failures").EnumerateArray(), f => f.GetString()!.Contains("file was altered"));

        // Reissuing the receipt keeps the recorded digest, so the altered copy still fails rather than being re-certified.
        var reissued = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt")).RootElement;
        Assert.Equal(_factory.Services.GetRequiredService<InspectionStore>().Find(id)!.State.Photos[0].ArtifactSha256,
            reissued.GetProperty("artifacts")[0].GetProperty("sha256").GetString());
        Assert.False(ReceiptVerifier.Verify(bundle).IsValid);

        // Rewriting the manifest to match the altered file passes the bundle check but not the inspection record.
        var receiptPath = Path.Combine(bundle, "receipt.json");
        var node = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(receiptPath))!;
        node["artifacts"]![0]!["sha256"] = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes));
        node["manifestSha256"] = null;
        var digest = CanonicalJson.Sha256(CanonicalJson.Serialize(node.AsObject()));
        node["manifestSha256"] = digest;
        File.WriteAllText(receiptPath, node.ToJsonString());
        File.WriteAllText(Path.Combine(bundle, "receipt.html"), digest);
        Assert.True(ReceiptVerifier.Verify(bundle).IsValid);
        var forged = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt/verify")).RootElement;
        Assert.False(forged.GetProperty("isValid").GetBoolean());
        Assert.Contains(forged.GetProperty("failures").EnumerateArray(), f => f.GetString()!.Contains("differs from the inspection record"));
    }

    private async Task<JsonElement> Check(string id) => JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt/verify")).RootElement;

    [Fact]
    public async Task A_damaged_or_rewritten_receipt_fails_and_viewing_or_checking_it_never_repairs_it()
    {
        var create = await _client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var id = JsonDocument.Parse(await create.Content.ReadAsStringAsync()).RootElement.GetProperty("inspectionId").GetString()!;
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(800, 600, salt: 7)), "image", "f.png" } };
        Assert.True((await _client.PostAsync($"/v1/inspections/{id}/observations", form)).IsSuccessStatusCode);
        var first = await Check(id);
        Assert.True(first.GetProperty("isValid").GetBoolean());
        var bundle = first.GetProperty("bundle").GetString()!;
        var receiptPath = Path.Combine(bundle, "receipt.json");
        var issued = File.ReadAllText(receiptPath);
        var manifest = first.GetProperty("issued").GetProperty("manifestSha256").GetString();

        // Viewing the receipt again serves the issued one and leaves the bundle alone.
        var served = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt")).RootElement;
        Assert.Equal(manifest, served.GetProperty("manifestSha256").GetString());
        Assert.Equal(issued, File.ReadAllText(receiptPath));

        // A corrupt receipt.json stays corrupt and fails, however often it is viewed or checked.
        File.WriteAllText(receiptPath, "{");
        await _client.GetStringAsync($"/v1/inspections/{id}/receipt");
        await _client.GetStringAsync($"/v1/inspections/{id}/receipt.html");
        Assert.False((await Check(id)).GetProperty("isValid").GetBoolean());
        Assert.False((await Check(id)).GetProperty("isValid").GetBoolean());
        Assert.Equal("{", File.ReadAllText(receiptPath));

        // Events removed and the manifest recomputed: consistent on its own, but not the receipt the server issued.
        var node = System.Text.Json.Nodes.JsonNode.Parse(issued)!;
        node["events"]!.AsArray().RemoveAt(node["events"]!.AsArray().Count - 1);
        node["manifestSha256"] = null;
        var digest = CanonicalJson.Sha256(CanonicalJson.Serialize(node.AsObject()));
        node["manifestSha256"] = digest;
        File.WriteAllText(receiptPath, node.ToJsonString());
        File.WriteAllText(Path.Combine(bundle, "receipt.html"), digest);
        Assert.True(ReceiptVerifier.Verify(bundle).IsValid);
        var forged = await Check(id);
        Assert.False(forged.GetProperty("isValid").GetBoolean());
        Assert.Contains(forged.GetProperty("failures").EnumerateArray(), f => f.GetString()!.Contains("not the receipt issued"));

        // New events issue a new version; the issuance record keeps both.
        await _client.PostAsync($"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run", null);
        var next = await Check(id);
        Assert.True(next.GetProperty("isValid").GetBoolean());
        Assert.NotEqual(manifest, next.GetProperty("issued").GetProperty("manifestSha256").GetString());
        Assert.Equal(2, File.ReadAllLines(Path.Combine(_factory.DataDir, "receipts", id + ".issued.jsonl")).Length);
    }

    [Fact]
    public async Task Every_issued_version_stays_on_disk_and_an_earlier_one_is_still_checked_against_its_own_events()
    {
        var create = await _client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var id = JsonDocument.Parse(await create.Content.ReadAsStringAsync()).RootElement.GetProperty("inspectionId").GetString()!;
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(800, 600, salt: 13)), "image", "f.png" } };
        Assert.True((await _client.PostAsync($"/v1/inspections/{id}/observations", form)).IsSuccessStatusCode);
        var v1 = await Check(id);
        Assert.Equal(1, v1.GetProperty("issued").GetProperty("number").GetInt32());

        // A second photo issues version 2 in its own folder; version 1 is untouched and still valid for the events it covered.
        using var second = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(800, 600, salt: 14)), "image", "g.png" } };
        Assert.True((await _client.PostAsync($"/v1/inspections/{id}/observations", second)).IsSuccessStatusCode);
        var v2 = await Check(id);
        Assert.Equal(2, v2.GetProperty("issued").GetProperty("number").GetInt32());
        Assert.NotEqual(v1.GetProperty("bundle").GetString(), v2.GetProperty("bundle").GetString());
        var versions = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipts")).RootElement.GetProperty("versions");
        Assert.Equal([1, 2], versions.EnumerateArray().Select(v => v.GetProperty("number").GetInt32()));
        var earlier = await CheckVersion(id, 1);
        Assert.True(earlier.GetProperty("isValid").GetBoolean(), earlier.ToString());
        Assert.Equal(1, JsonDocument.Parse(File.ReadAllText(Path.Combine(earlier.GetProperty("bundle").GetString()!, "receipt.json"))).RootElement.GetProperty("artifacts").GetArrayLength());

        // Tampering with version 1 fails version 1 only; checking it never reissues or repairs it.
        var art = Directory.EnumerateFiles(Path.Combine(earlier.GetProperty("bundle").GetString()!, "artifacts")).Single();
        var bytes = File.ReadAllBytes(art); bytes[^1] ^= 0xFF; File.WriteAllBytes(art, bytes);
        Assert.False((await CheckVersion(id, 1)).GetProperty("isValid").GetBoolean());
        Assert.Equal(bytes, File.ReadAllBytes(art));
        Assert.True((await Check(id)).GetProperty("isValid").GetBoolean());
        Assert.Equal(2, File.ReadAllLines(Path.Combine(_factory.DataDir, "receipts", id + ".issued.jsonl")).Length);

        Assert.Equal(System.Net.HttpStatusCode.NotFound, (await _client.GetAsync($"/v1/inspections/{id}/receipt/verify?number=9")).StatusCode);
    }

    [Fact]
    public async Task A_receipt_issued_before_versions_is_version_0_and_the_next_issue_is_version_1()
    {
        var create = await _client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var id = JsonDocument.Parse(await create.Content.ReadAsStringAsync()).RootElement.GetProperty("inspectionId").GetString()!;
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(800, 600, salt: 15)), "image", "f.png" } };
        Assert.True((await _client.PostAsync($"/v1/inspections/{id}/observations", form)).IsSuccessStatusCode);
        Assert.True((await Check(id)).GetProperty("isValid").GetBoolean());

        // Rewrite the bundle as the old layout left it: files in the inspection's folder and a log of numberless lines, one
        // per reissue, of which only the last matches the folder (each reissue overwrote the one before).
        var root = Path.Combine(_factory.DataDir, "receipts", id);
        var v1 = Path.Combine(root, "v1");
        foreach (var f in Directory.EnumerateFiles(v1)) File.Move(f, Path.Combine(root, Path.GetFileName(f)));
        Directory.Move(Path.Combine(v1, "artifacts"), Path.Combine(root, "artifacts"));
        Directory.Delete(v1);
        var log = Path.Combine(_factory.DataDir, "receipts", id + ".issued.jsonl");
        var real = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllLines(log).Single())!.AsObject();
        real.Remove("number");
        var stale = real.DeepClone().AsObject();
        stale["manifestSha256"] = new string('0', 64);
        File.WriteAllLines(log, [stale.ToJsonString(), real.ToJsonString()]);

        var versions = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipts")).RootElement.GetProperty("versions");
        Assert.Equal([0], versions.EnumerateArray().Select(v => v.GetProperty("number").GetInt32()));
        var legacy = await CheckVersion(id, 0);
        Assert.True(legacy.GetProperty("isValid").GetBoolean(), legacy.ToString());
        Assert.Equal(root, legacy.GetProperty("bundle").GetString());

        using var second = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(800, 600, salt: 16)), "image", "g.png" } };
        Assert.True((await _client.PostAsync($"/v1/inspections/{id}/observations", second)).IsSuccessStatusCode);
        var next = await Check(id);
        Assert.Equal(1, next.GetProperty("issued").GetProperty("number").GetInt32());
        Assert.True(next.GetProperty("isValid").GetBoolean(), next.ToString());
        Assert.True((await CheckVersion(id, 0)).GetProperty("isValid").GetBoolean());
    }

    private async Task<JsonElement> CheckVersion(string id, int number) =>
        JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt/verify?number={number}")).RootElement;

    [Fact]
    public async Task Sam_mask_geometry_is_bundled_with_its_recorded_digest_and_an_altered_mask_fails()
    {
        var create = await _client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var id = JsonDocument.Parse(await create.Content.ReadAsStringAsync()).RootElement.GetProperty("inspectionId").GetString()!;
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(800, 600, salt: 11)), "image", "f.png" } };
        Assert.True((await _client.PostAsync($"/v1/inspections/{id}/observations", form)).IsSuccessStatusCode);
        _factory.FakeSam.Queued.Enqueue([new LensQi.FieldOps.Meta.Segmentation.MaskRecord("0", 20, 20, 10, 10, 30, 30,
            new LensQi.FieldOps.Meta.Segmentation.RleMask([20, 20], LensQi.FieldOps.Core.Inspection.CocoRle.Encode([0, 400])), 0.9)]);
        var seg = JsonDocument.Parse(await (await _client.PostAsJsonAsync($"/v1/inspections/{id}/segment", new { concept = "yellow ethernet cable" })).Content.ReadAsStringAsync()).RootElement;
        var segId = seg.GetProperty("segmentation").GetProperty("segmentationId").GetString()!;
        var recorded = _factory.Services.GetRequiredService<InspectionStore>().Find(id)!.Events.OfType<LensQi.FieldOps.Core.Inspection.SegmentationRecorded>().Single();
        Assert.Equal(Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(Path.Combine(_factory.DataDir, "evidence", id, segId + ".json")))), recorded.GeometrySha256);

        var check = await Check(id);
        Assert.True(check.GetProperty("isValid").GetBoolean());
        var receipt = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/receipt")).RootElement;
        var mask = receipt.GetProperty("artifacts").EnumerateArray().Single(a => a.GetProperty("kind").GetString() == "segmentation");
        Assert.Equal(segId, mask.GetProperty("evidenceId").GetString());
        var copy = Path.Combine(check.GetProperty("bundle").GetString()!, mask.GetProperty("path").GetString()!);
        Assert.True(File.Exists(copy));

        File.WriteAllText(copy, File.ReadAllText(copy).Replace("\"matched\": true", "\"matched\": false"));
        var altered = await Check(id);
        Assert.False(altered.GetProperty("isValid").GetBoolean());
        Assert.Contains(altered.GetProperty("failures").EnumerateArray(), f => f.GetString()!.Contains(segId));
    }

    [Fact]
    public async Task Downloaded_bundle_contains_original_evidence_and_verifies_after_extraction()
    {
        var create = await _client.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" });
        var id = JsonDocument.Parse(await create.Content.ReadAsStringAsync()).RootElement.GetProperty("inspectionId").GetString()!;
        using var form = new MultipartFormDataContent { { new ByteArrayContent(Png), "image", "original.png" } };
        Assert.True((await _client.PostAsync($"/v1/inspections/{id}/observations", form)).IsSuccessStatusCode);
        var knowledge = JsonDocument.Parse(await _client.GetStringAsync($"/v1/inspections/{id}/knowledge")).RootElement;
        Assert.Equal("Synthetic", knowledge.GetProperty("originKind").GetString());
        Assert.Equal(64, knowledge.GetProperty("packSha256").GetString()!.Length);
        var response = await _client.GetAsync($"/v1/inspections/{id}/receipt.zip");
        response.EnsureSuccessStatusCode();
        Assert.Equal("application/zip", response.Content.Headers.ContentType!.MediaType);
        var destination = Path.Combine(Path.GetTempPath(), "fieldproof-export-" + Guid.NewGuid().ToString("N"));
        try
        {
            using var stream = new MemoryStream(await response.Content.ReadAsByteArrayAsync());
            using var zip = new System.IO.Compression.ZipArchive(stream);
            System.IO.Compression.ZipFileExtensions.ExtractToDirectory(zip, destination);
            Assert.True(ReceiptVerifier.Verify(destination).IsValid);
            Assert.Equal(Png, File.ReadAllBytes(Directory.GetFiles(Path.Combine(destination, "artifacts")).Single()));
            var issued = await Check(id);
            File.WriteAllText(Path.Combine(issued.GetProperty("bundle").GetString()!, "receipt.json"), "{}");
            Assert.Equal(System.Net.HttpStatusCode.Conflict, (await _client.GetAsync($"/v1/inspections/{id}/receipt.zip")).StatusCode);
        }
        finally { if (Directory.Exists(destination)) Directory.Delete(destination, true); }
    }
}

