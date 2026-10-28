using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace LensQi.FieldOps.Core.Receipts;

public sealed record ReceiptVerification(bool IsValid, IReadOnlyList<string> Checks, IReadOnlyList<string> Failures);

/// <summary>
/// Recomputes every digest in an issued bundle (T5.3). Works on the raw JSON so it does not depend on the receipt
/// record shape staying identical: the manifest digest is the SHA-256 of the canonical JSON with manifestSha256 null,
/// and every artifact's file must hash to the recorded value. Also checks the event sequence is contiguous.
/// </summary>
public static class ReceiptVerifier
{
    public static ReceiptVerification Verify(string bundleDirectory)
    {
        var checks = new List<string>();
        var failures = new List<string>();
        var receiptPath = Path.Combine(bundleDirectory, "receipt.json");
        if (!File.Exists(receiptPath))
            return new ReceiptVerification(false, checks, [$"receipt.json not found in {bundleDirectory}"]);

        JsonObject root;
        try { root = JsonNode.Parse(File.ReadAllBytes(receiptPath))!.AsObject(); }
        catch (Exception ex) { return new ReceiptVerification(false, checks, [$"receipt.json is not valid JSON: {ex.Message}"]); }

        var recorded = root["manifestSha256"]?.GetValue<string>();
        var copy = root.DeepClone().AsObject();
        copy["manifestSha256"] = null;
        var computed = CanonicalJson.Sha256(CanonicalJson.Serialize(copy));
        if (string.Equals(recorded, computed, StringComparison.Ordinal)) checks.Add($"manifest digest matches ({computed[..12]}…)");
        else failures.Add($"manifest digest mismatch: recorded {recorded?[..12]}…, computed {computed[..12]}… (receipt content was altered)");

        var artifacts = root["artifacts"]?.AsArray() ?? [];
        foreach (var a in artifacts)
        {
            var id = a!["evidenceId"]?.GetValue<string>() ?? "?";
            var rel = a["path"]?.GetValue<string>() ?? "";
            var expected = a["sha256"]?.GetValue<string>() ?? "";
            var full = Path.Combine(bundleDirectory, rel);
            if (!File.Exists(full)) { failures.Add($"artifact {id}: file '{rel}' missing"); continue; }
            var actual = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(full)));
            if (actual == expected) checks.Add($"artifact {id} matches ({actual[..12]}…)");
            else failures.Add($"artifact {id}: '{rel}' hashes to {actual[..12]}…, receipt says {expected[..12]}… (file was altered)");
        }
        checks.Add($"{artifacts.Count} artifact(s) listed");

        var events = root["events"]?.AsArray() ?? [];
        long expectedSeq = 1;
        foreach (var e in events)
        {
            var seq = e!["sequence"]?.GetValue<long>() ?? -1;
            if (seq != expectedSeq) { failures.Add($"event sequence gap: expected {expectedSeq}, found {seq}"); break; }
            expectedSeq++;
        }
        if (events.Count > 0 && expectedSeq == events.Count + 1) checks.Add($"{events.Count} events contiguous");

        var htmlPath = Path.Combine(bundleDirectory, "receipt.html");
        if (File.Exists(htmlPath) && recorded is not null)
        {
            if (File.ReadAllText(htmlPath).Contains(recorded, StringComparison.Ordinal)) checks.Add("receipt.html carries the manifest digest");
            else failures.Add("receipt.html does not carry the recorded manifest digest");
        }
        return new ReceiptVerification(failures.Count == 0, checks, failures);
    }
}
