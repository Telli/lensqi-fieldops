using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Receipts;
using Microsoft.AspNetCore.Hosting;

namespace LensQi.FieldOps.Tests;

/// <summary>What a verified inspection and its receipt keep standing behind, over real HTTP.</summary>
public class VerificationIntegrityApiTests
{
    private static readonly string[] Requirements = ["switch_chassis_visible", "yellow_cable_near_end_visible", "post_repair_view_captured"];

    private static async Task<JsonElement> Json(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement.Clone();

    private static async Task<JsonElement> Post(HttpClient c, string url, object? body = null)
    {
        var r = body is null ? await c.PostAsync(url, null) : await c.PostAsJsonAsync(url, body);
        Assert.True(r.IsSuccessStatusCode, $"{url} -> {(int)r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await Json(r);
    }

    private static async Task<string> Upload(HttpClient c, string id, byte salt)
    {
        using var form = new MultipartFormDataContent { { new ByteArrayContent(TestImages.Png(640, 480, salt: salt)), "image", "f.png" }, { new StringContent("Phone"), "source" } };
        return (await Json(await c.PostAsync($"/v1/inspections/{id}/observations", form))).GetProperty("evidenceId").GetString()!;
    }

    private static Task<HttpResponseMessage> Review(HttpClient c, string id, string requirement, string status, string photo, string key)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, $"/v1/inspections/{id}/checklist") { Content = JsonContent.Create(new { requirementId = requirement, status, photoEvidenceId = photo }) };
        request.Headers.Add("Idempotency-Key", key);
        return c.SendAsync(request);
    }

    /// <summary>The normal journey up to and including the verify call, with the probe passing after the repair.</summary>
    private static async Task<(string Id, string Photo, JsonElement Verify)> Journey(HttpClient c, FieldOpsApiFactory f)
    {
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = false });
        var id = (await Json(await c.PostAsJsonAsync("/v1/inspections", new { fixtureId = "LAB-RACK-01", assetId = "LAB-SW-01" }))).GetProperty("inspectionId").GetString()!;
        var first = await Upload(c, id, 1);
        f.FakeChat.CannedResponse = JsonSerializer.Serialize(new
        {
            assetId = "LAB-SW-01",
            hypotheses = new[] { new { featureId = "yellow_cable_near_end_visible", status = "Suspected", reason = "may be unseated", evidenceIds = new[] { first } } },
            nextAction = "RunApprovedProbe", requestedView = (string?)null, requestedSegmentationConcept = (string?)null,
        });
        await Post(c, $"/v1/inspections/{id}/investigate");
        await Post(c, $"/v1/inspections/{id}/diagnostics/NODE-A-HEALTH:run");
        await Post(c, $"/v1/inspections/{id}/work-order", new { summary = "Reseat" });
        await Post(c, $"/v1/inspections/{id}/repair-claims", new { description = "Reseated" });
        await Post(c, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = true });
        var photo = await Upload(c, id, 3);
        foreach (var requirement in Requirements)
            Assert.True((await Review(c, id, requirement, "Satisfied", photo, "review-" + requirement)).IsSuccessStatusCode);
        await Post(c, $"/v1/inspections/{id}/acknowledge");
        return (id, photo, await Post(c, $"/v1/inspections/{id}/verify"));
    }

    [Fact]
    public async Task A_verified_inspection_refuses_a_late_review_and_its_receipt_does_not_change()
    {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient();
        var (id, photo, _) = await Journey(c, f);
        var manifest = (await Json(await c.GetAsync($"/v1/inspections/{id}/receipt"))).GetProperty("manifestSha256").GetString();

        var late = await Review(c, id, Requirements[1], "NotSatisfied", photo, "late-review");
        Assert.Equal(HttpStatusCode.Conflict, late.StatusCode);
        Assert.Equal(InspectionErrorCodes.InvalidState, (await Json(late)).GetProperty("code").GetString());

        var view = await Json(await c.GetAsync($"/v1/inspections/{id}"));
        Assert.Equal("VerifiedComplete", view.GetProperty("stage").GetString());
        Assert.True(view.GetProperty("technicianAcknowledged").GetBoolean());
        Assert.Equal(manifest, (await Json(await c.GetAsync($"/v1/inspections/{id}/receipt"))).GetProperty("manifestSha256").GetString());

        // A retry of the review the server already took still answers, so a client that lost the response is not told it failed.
        Assert.Equal(HttpStatusCode.OK, (await Review(c, id, Requirements[1], "Satisfied", photo, "review-" + Requirements[1])).StatusCode);
    }

    [Fact]
    public async Task Verification_is_held_while_required_facts_are_unconfirmed_and_completes_once_they_are()
    {
        // The installed pack now covers only hardware revision R2, and the site record names no revision.
        var packs = Directory.CreateTempSubdirectory("packs-").FullName;
        foreach (var file in Directory.EnumerateFiles(RepoPaths.FixturesDir)) File.Copy(file, Path.Combine(packs, Path.GetFileName(file)));
        var pack = Path.Combine(packs, "lab-switch-01.synthetic.json");
        File.WriteAllText(pack, File.ReadAllText(pack).Replace("\"hardwareRevisions\": []", "\"hardwareRevisions\": [\"R2\"]"));

        using var f = new FieldOpsApiFactory();
        string id;
        using (var changed = f.WithWebHostBuilder(b => b.UseSetting("Knowledge:PackDirectory", packs)))
        using (var c = changed.CreateClient())
        {
            (id, _, var held) = await Journey(c, f);
            var decision = held.GetProperty("decision");
            Assert.Equal("NeedsHumanReview", decision.GetProperty("result").GetString());
            Assert.Equal(JsonValueKind.Null, decision.GetProperty("outcomeKind").ValueKind);
            Assert.StartsWith(VerificationPolicyEvaluator.KnowledgeUnconfirmedPrefix, decision.GetProperty("reasons")[0].GetString());
            Assert.Equal("NeedsEvidence", held.GetProperty("state").GetProperty("stage").GetString());
        }

        // The site's documentation is put right; the claim reopens the way any sent-back claim does, with a new photo.
        using var fixedServer = FieldOpsApiFactory.Over(f.DataDir);
        using var again = fixedServer.CreateClient();
        await Post(again, "/v1/simulation/diagnostics/NODE-A-HEALTH", new { passed = true });
        var photo = await Upload(again, id, 5);
        foreach (var requirement in Requirements)
            Assert.True((await Review(again, id, requirement, "Satisfied", photo, "second-review-" + requirement)).IsSuccessStatusCode);
        await Post(again, $"/v1/inspections/{id}/acknowledge");
        var verified = await Post(again, $"/v1/inspections/{id}/verify");
        Assert.True(verified.GetProperty("decision").GetProperty("result").GetString() == "Verified", verified.GetProperty("decision").ToString());
        var basis = verified.GetProperty("decision").GetProperty("requiredKnowledge");
        Assert.Equal("lab-switch-01.synthetic", basis.GetProperty("packId").GetString());
        Assert.Equal(64, basis.GetProperty("packSha256").GetString()!.Length);
        Assert.Empty(basis.GetProperty("unconfirmedFactIds").EnumerateArray());
    }

    [Fact]
    public async Task An_edited_or_missing_page_fails_the_receipt_check_the_verifier_and_the_export_alike()
    {
        using var f = new FieldOpsApiFactory(); using var c = f.CreateClient();
        var (id, _, _) = await Journey(c, f);
        var first = await Json(await c.GetAsync($"/v1/inspections/{id}/receipt/verify"));
        Assert.True(first.GetProperty("isValid").GetBoolean(), first.ToString());
        var bundle = first.GetProperty("bundle").GetString()!;
        var html = Path.Combine(bundle, "receipt.html");
        var page = File.ReadAllText(html);

        async Task AssertRefused()
        {
            Assert.False((await Json(await c.GetAsync($"/v1/inspections/{id}/receipt/verify"))).GetProperty("isValid").GetBoolean());
            Assert.False(ReceiptVerifier.Verify(bundle).IsValid);
            Assert.Equal(HttpStatusCode.Conflict, (await c.GetAsync($"/v1/inspections/{id}/receipt.zip")).StatusCode);
        }

        File.WriteAllText(html, page.Replace("SIMULATED", "LIVE"));
        await AssertRefused();
        File.Delete(html);
        await AssertRefused();

        File.WriteAllText(html, page);
        Assert.True((await Json(await c.GetAsync($"/v1/inspections/{id}/receipt/verify"))).GetProperty("isValid").GetBoolean());
        Assert.True((await c.GetAsync($"/v1/inspections/{id}/receipt.zip")).IsSuccessStatusCode);
    }
}
