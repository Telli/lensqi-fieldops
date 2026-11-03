using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LensQi.FieldOps.Api.Shared;
using LensQi.FieldOps.Core.Inspection;
using LensQi.FieldOps.Core.Knowledge;
using LensQi.FieldOps.Core.Tasks;

namespace LensQi.FieldOps.Api.Features.Tasks;

internal static class TaskPackage
{
    public static IResult Export(TaskState job, InspectionState? inspection, string root, LensQi.FieldOps.Api.Features.Receipts.ReceiptService receipts)
    {
        var package = new FileStream(Path.Combine(Path.GetTempPath(), Path.GetRandomFileName()), FileMode.CreateNew,
            FileAccess.ReadWrite, FileShare.None, 81920, FileOptions.DeleteOnClose);
        try
        {
            using (var zip = new ZipArchive(package, ZipArchiveMode.Create, true))
            {
                var html = new StringBuilder("<!doctype html><meta charset=utf-8><title>Work package</title>");
                void Line(string tag, string? value) => html.Append('<').Append(tag).Append('>').Append(WebUtility.HtmlEncode(value)).Append("</").Append(tag).Append('>');
                Line("h1", job.DisplayTitle ?? job.Goal);
                Line("p", $"{job.TaskId} · {job.Phase} · {job.UpdatedAt:u}");
                if (job.Goal != TaskSession.FreeformGoal) Line("p", job.Goal);
                Line("p", job.Details);
                if (job.Site is { } site) Line("p", $"Equipment: {site.FixtureId} / {site.AssetId}");
                Line("h2", "Verification scope");
                Line("p", inspection is null ? "No linked verification record." : $"{inspection.InspectionId} · {inspection.Stage} · {inspection.LastDecision?.OutcomeKind}");
                if (inspection?.LastDecision is { } decision)
                {
                    Line("p", $"Inspection decision: {decision.Result}");
                    foreach (var claim in decision.VerifiedClaims) Line("p", claim);
                    foreach (var reason in decision.Reasons) Line("p", reason);
                }
                // A download reports the record; it never issues a receipt. The package carries the receipt last issued, named by
                // version, and says when the inspection has moved on since, so the reader knows to issue a new one.
                if (inspection is not null && receipts.IssuedExport(inspection.InspectionId) is { } export)
                {
                    foreach (var path in export.Paths)
                        zip.CreateEntryFromFile(Path.Combine(export.Directory, path), "fieldproof/" + path);
                    var version = export.Issue.Number == 0 ? "FieldProof receipt" : $"FieldProof receipt v{export.Issue.Number}";
                    Line("p", $"{version}, issued {export.Issue.IssuedAt:u}" + (export.Covers ? "" : " · the inspection has recorded more since; issue a new receipt for the latest record"));
                    html.Append("<p><a href='fieldproof/receipt.html'>FieldProof inspection receipt and evidence</a></p>");
                }
                else if (inspection is not null) Line("p", "No FieldProof receipt has been issued for this inspection yet.");
                foreach (var probe in inspection?.Diagnostics ?? [])
                    Line("p", $"{probe.Result.DiagnosticId}: {probe.Result.Source} · {probe.Result.NormalizedValue} · {probe.Result.CompletedAt:u}");
                Line("p", "Task progress and AI suggestions do not certify completion of a repair.");
                foreach (var step in job.Plan?.Steps ?? [])
                {
                    var status = job.CompletedStepIds.Contains(step.Id) ? "Done" : job.SkippedStepIds.Contains(step.Id) ? "Skipped" : job.CurrentStepId == step.Id ? "Active" : "Todo";
                    Line("h2", $"{step.Title} · {status}"); Line("p", step.Instruction);
                    foreach (var fact in step.SourceFacts ?? []) Line("p", $"{fact.Statement} — {fact.Citation.DocumentTitle}, {fact.Citation.SourceLocator}");
                    foreach (var page in step.ReferencePhotoIds ?? []) Line("p", $"Supporting photo: {page}");
                    if (job.SkipReasons.TryGetValue(step.Id, out var skip)) Line("p", $"Skipped: {skip.Text}");
                    if (job.Checks.LastOrDefault(c => c.StepId == step.Id) is { } check) Line("p", $"AI check: {check.Verdict} · {check.Reason}");
                }
                if (job.Handoff is { } source)
                {
                    Line("h2", $"From Just ask · {source.SourceTaskId}"); Line("p", source.EquipmentDetails);
                    foreach (var a in source.Answers) { Line("p", a.Question); Line("p", a.Answer); }
                }
                foreach (var a in job.Answers) { Line("h3", a.Question); Line("p", a.Answer); }
                Line("h2", "Job photos");
                foreach (var p in job.Photos)
                {
                    var dir = Path.Combine(root, "evidence", job.TaskId);
                    var file = Directory.Exists(dir) ? Directory.EnumerateFiles(dir, p.EvidenceId + "-*").FirstOrDefault() : null;
                    if (file is null) throw new InvalidDataException("A job photo is missing");
                    using var input = File.OpenRead(file);
                    if (!string.Equals(Convert.ToHexStringLower(SHA256.HashData(input)), p.Sha256, StringComparison.Ordinal))
                        throw new InvalidDataException("A job photo failed digest verification");
                    input.Position = 0;
                    var name = "photos/" + Path.GetFileName(file);
                    using (var entry = zip.CreateEntry(name, CompressionLevel.NoCompression).Open()) input.CopyTo(entry);
                    html.Append("<a href=\"").Append(WebUtility.HtmlEncode(name)).Append("\">").Append(WebUtility.HtmlEncode(p.EvidenceId)).Append("</a>");
                    Line("p", $"{p.Purpose} · {p.Origin} · {p.RecordedAt:u} · SHA-256 {p.Sha256}");
                }
                using (var writer = new StreamWriter(zip.CreateEntry("summary.html").Open())) writer.Write(html);
                using var json = zip.CreateEntry("job.json").Open();
                JsonSerializer.Serialize(json, new { job, verification = inspection?.LastDecision }, KnowledgeJson.Options);
            }
            package.Position = 0;
            return Results.File(package, "application/zip", $"{job.TaskId}-work-package.zip");
        }
        catch (InvalidDataException ex) { package.Dispose(); return ApiResults.Conflict("invalid_evidence", ex.Message); }
        catch { package.Dispose(); throw; }
    }
}
