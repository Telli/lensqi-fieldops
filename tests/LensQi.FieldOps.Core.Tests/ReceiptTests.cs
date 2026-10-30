using System.Text.Json;
using System.Text.Json.Nodes;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Receipts;

namespace LensQi.FieldOps.Tests;

public class ReceiptTests
{
    [Fact]
    public void Canonical_json_is_order_independent_and_whitespace_free()
    {
        var a = JsonNode.Parse("""{"b":[{"y":1,"x":2}],"a":"v","c":{"z":null,"m":1.5}}""");
        var b = JsonNode.Parse("""{ "c" : { "m" : 1.5 , "z" : null }, "a" : "v", "b" : [ { "x" : 2, "y" : 1 } ] }""");
        Assert.Equal(CanonicalJson.Serialize(a), CanonicalJson.Serialize(b));
        Assert.Equal("""{"a":"v","b":[{"x":2,"y":1}],"c":{"m":1.5,"z":null}}""", CanonicalJson.Serialize(a));
        Assert.Equal(CanonicalJson.Sha256(CanonicalJson.Serialize(a)), CanonicalJson.Sha256(CanonicalJson.Serialize(b)));
    }

    private static (EvidenceReceipt Receipt, string Dir) IssueBundle(bool verified = true)
    {
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();
        k.Tick(); k.RecordProbe(k.Probe(verified));
        k.Verify();
        var dir = Directory.CreateTempSubdirectory("receipt-").FullName;
        Directory.CreateDirectory(Path.Combine(dir, "artifacts"));
        var artifacts = new List<ReceiptArtifact>();
        foreach (var p in k.Session.State.Photos)
        {
            var bytes = System.Text.Encoding.UTF8.GetBytes("image-" + p.EvidenceId);
            var name = p.EvidenceId + ".jpg";
            File.WriteAllBytes(Path.Combine(dir, "artifacts", name), bytes);
            artifacts.Add(new ReceiptArtifact(p.EvidenceId, "photo", "artifacts/" + name, Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes)), bytes.Length, "image/jpeg", "Phone", p.ObservedAt));
        }
        var receipt = ReceiptBuilder.Build(k.Session, artifacts, null, InspectionTestKit.T0.AddMinutes(30), "visual-assessment/1.1");
        File.WriteAllText(Path.Combine(dir, "receipt.json"), JsonSerializer.Serialize(receipt, new JsonSerializerOptions(KnowledgeJson.Options) { WriteIndented = true }));
        File.WriteAllText(Path.Combine(dir, "receipt.html"), ReceiptHtml.Render(receipt));
        return (receipt, dir);
    }

    [Fact]
    public void Issued_bundle_verifies_and_manifest_is_deterministic()
    {
        var (receipt, dir) = IssueBundle();
        Assert.Equal(64, receipt.ManifestSha256!.Length);
        Assert.Equal(receipt.ManifestSha256, receipt.WithManifest().ManifestSha256); // recomputing yields the same digest
        var v = ReceiptVerifier.Verify(dir);
        Assert.True(v.IsValid, string.Join("; ", v.Failures));
        Assert.Contains(v.Checks, c => c.StartsWith("manifest digest matches"));
        Assert.Contains(v.Checks, c => c.Contains("events contiguous"));
        Assert.Equal("VerifiedCompleteSimulated", receipt.Inspection.OutcomeKind);
        Assert.Contains("SIMULATED", File.ReadAllText(Path.Combine(dir, "receipt.html")));
        Assert.Contains(receipt.Disclaimers, d => d.Contains("not prove who captured"));
    }

    [Fact]
    public void Altering_one_artifact_byte_is_detected()
    {
        var (_, dir) = IssueBundle();
        var file = Directory.EnumerateFiles(Path.Combine(dir, "artifacts")).First();
        var bytes = File.ReadAllBytes(file); bytes[0] ^= 0x01; File.WriteAllBytes(file, bytes);
        var v = ReceiptVerifier.Verify(dir);
        Assert.False(v.IsValid);
        Assert.Contains(v.Failures, f => f.Contains("file was altered"));
        Assert.Contains(v.Checks, c => c.StartsWith("manifest digest matches")); // the JSON itself is untouched
    }

    [Fact]
    public void Editing_an_event_or_the_decision_in_the_receipt_is_detected()
    {
        var (_, dir) = IssueBundle(verified: false);
        var path = Path.Combine(dir, "receipt.json");
        var root = JsonNode.Parse(File.ReadAllText(path))!.AsObject();
        root["decision"]!["result"] = "Verified";                 // forge the outcome
        root["inspection"]!["outcomeKind"] = "VerifiedCompleteLive";
        File.WriteAllText(path, root.ToJsonString());
        var v = ReceiptVerifier.Verify(dir);
        Assert.False(v.IsValid);
        Assert.Contains(v.Failures, f => f.Contains("manifest digest mismatch"));

        var (_, dir2) = IssueBundle();
        var p2 = Path.Combine(dir2, "receipt.json");
        var r2 = JsonNode.Parse(File.ReadAllText(p2))!.AsObject();
        r2["events"]!.AsArray().RemoveAt(3);                      // drop an event from the trail
        File.WriteAllText(p2, r2.ToJsonString());
        var v2 = ReceiptVerifier.Verify(dir2);
        Assert.False(v2.IsValid);
        Assert.Contains(v2.Failures, f => f.Contains("sequence gap"));
    }

    [Fact]
    public void Receipt_shows_predictions_with_a_tally_a_change_verdict_per_claim_and_refused_proposals()
    {
        static VendorGroundedProposal Probe() => new("LAB-SW-01", ["LAB-HEALTH-CHECK-01"], null, null, "NODE-A-HEALTH",
            ProposedNextAction.RunApprovedProbe, new Prediction("NODE-A-HEALTH", "Reachable", ["LAB-HEALTH-CHECK-01"], null));
        var k = new InspectionTestKit();
        k.ReachRepairClaim(); k.PostRepairEvidence();
        k.Session.RecordKnowledgeProposal("kp1", "prop-1", "fake-muse", "p", "r", Probe(), null, [], "pred-1", k.Now);
        k.Session.RecordKnowledgeProposal("kp2", "prop-2", "fake-muse", "p", "r", null, "RunApprovedProbe",
            [new(ProposalErrorCodes.ProbeNotAllowlisted, "reboot is not approved", ["POST /rest/system/reboot"])], "pred-2", k.Now);
        k.Tick(); k.RecordProbe(k.Probe(false)); k.Verify();
        k.Tick(); k.Session.ClaimRepair("c2", "claim-2", "Moved the cable to P2", k.Now);
        k.Session.RecordKnowledgeProposal("kp3", "prop-3", "fake-muse", "p", "r", Probe(), null, [], "pred-3", k.Now);
        k.PostRepairEvidence();
        k.Tick(); k.RecordProbe(k.Probe(true)); k.Verify();

        var receipt = ReceiptBuilder.Build(k.Session, [], null, k.Now, "visual-assessment/1.1");
        Assert.Equal("fieldproof-receipt/1.2", receipt.ReceiptVersion);
        Assert.Equal(new ReceiptPredictionTally(1, 1, 0, 0), receipt.Predictions.Tally);
        Assert.Equal(["Falsified", "Confirmed"], receipt.Predictions.Items.Select(p => p.Outcome));
        Assert.Equal(["Reachable", "Reachable"], receipt.Predictions.Items.Select(p => p.PredictedValue));
        Assert.Equal(["Unreachable", "Reachable"], receipt.Predictions.Items.Select(p => p.MeasuredValue));
        Assert.Equal("prop-1", receipt.Predictions.Items[0].ProposalId);

        Assert.Equal(2, receipt.VisualChange.Claims.Count);                // one verdict per repair claim
        Assert.All(receipt.VisualChange.Claims, c => Assert.Equal("Unscorable", c.Verdict));
        Assert.Equal("Moved the cable to P2", receipt.VisualChange.Claims[1].Description);
        Assert.Equal(2, receipt.VisualChange.Tally["Unscorable"]);

        Assert.Equal(2, receipt.Proposals.Accepted);
        var refused = Assert.Single(receipt.Proposals.Rejected);
        Assert.Equal((ProposalErrorCodes.ProbeNotAllowlisted, "RunApprovedProbe"), (Assert.Single(refused.Codes), refused.NextAction));

        var html = ReceiptHtml.Render(receipt);
        Assert.Contains("1 confirmed · 1 falsified", html);
        Assert.Contains("1 rejected", html);
        Assert.Contains("Moved the cable to P2", html);
        Assert.Equal(receipt.ManifestSha256, receipt.WithManifest().ManifestSha256);
    }
}
