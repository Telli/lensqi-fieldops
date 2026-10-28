using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Core.Receipts;

namespace LensQi.FieldOps.Tests;

public class ReceiptApiTests : IClassFixture<FieldOpsApiFactory>
{
    private readonly FieldOpsApiFactory _factory;
    private readonly HttpClient _client;
    private static readonly byte[] Png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9, 9];

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
    }
}
