using System.Diagnostics;
using System.Text;
using System.Text.Json;
using LensQi.FieldOps.Core.Inspection;

namespace LensQi.FieldOps.Meta.Segmentation;

/// <summary>Parsed JSON from tools/sam-parser/sam_parse.py.</summary>
public sealed record SidecarParse(IReadOnlyList<BoxRecord> Boxes, IReadOnlyList<MaskRecord> Masks, string ParserVersion, string RawSha256);

public interface ISamOutputParser
{
    Task<SidecarParse> ParseAsync(string rawOutputText, CancellationToken ct);
}

/// <summary>
/// Runs Meta's meta-sam-parser through a local Python process. Model grammar never enters C#; the sidecar emits typed JSON.
/// Also performs the pixel crop with Pillow so .NET needs no image codec. Never exposed over the network.
/// </summary>
public sealed class PythonSidecar(SamOptions options, string repoRoot) : ISamOutputParser, IRoiCropper, IImageQualityAnalyzer
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public string PythonExecutable { get; } = ResolvePython(options, repoRoot);
    public string ScriptDirectory { get; } = Path.Combine(repoRoot, options.SidecarDirectory);

    public static string ResolvePython(SamOptions options, string repoRoot)
    {
        var env = Environment.GetEnvironmentVariable("FIELDOPS_PYTHON");
        if (!string.IsNullOrWhiteSpace(env)) return env;
        foreach (var candidate in new[] { ".venv/bin/python", "venv/bin/python", ".venv/Scripts/python.exe" })
        {
            var p = Path.Combine(repoRoot, candidate);
            if (File.Exists(p)) return p;
        }
        return options.PythonExecutable;
    }

    public async Task<SidecarParse> ParseAsync(string rawOutputText, CancellationToken ct)
    {
        var (stdout, _) = await RunAsync(["-I", Path.Combine(ScriptDirectory, "sam_parse.py")], rawOutputText, ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(stdout);
        var root = doc.RootElement;
        var boxes = new List<BoxRecord>();
        var masks = new List<MaskRecord>();
        foreach (var r in root.GetProperty("records").EnumerateArray())
        {
            var kind = r.GetProperty("kind").GetString();
            var id = r.GetProperty("objectId").GetString() ?? "?";
            double? conf = r.TryGetProperty("confidence", out var c) && c.ValueKind == JsonValueKind.Number ? c.GetDouble() : null;
            if (kind == "box")
            {
                var b = r.GetProperty("box");
                boxes.Add(new BoxRecord(id, b.GetProperty("left").GetDouble(), b.GetProperty("top").GetDouble(), b.GetProperty("right").GetDouble(), b.GetProperty("bottom").GetDouble(), conf));
            }
            else if (kind == "mask")
            {
                var m = r.GetProperty("mask"); var bd = r.GetProperty("bounds"); var rle = m.GetProperty("rle");
                masks.Add(new MaskRecord(id, m.GetProperty("width").GetInt32(), m.GetProperty("height").GetInt32(),
                    bd.GetProperty("left").GetDouble(), bd.GetProperty("top").GetDouble(), bd.GetProperty("right").GetDouble(), bd.GetProperty("bottom").GetDouble(),
                    new RleMask(rle.GetProperty("size").EnumerateArray().Select(x => x.GetInt32()).ToList(), rle.GetProperty("counts").GetString() ?? ""), conf));
            }
        }
        var version = root.GetProperty("parser").GetProperty("version").GetString() ?? "unknown";
        return new SidecarParse(boxes, masks, "meta-sam-parser " + version, root.GetProperty("rawSha256").GetString() ?? "");
    }

    public async Task<CropResult> CropAsync(string sourcePath, string sourceEvidenceId, string sourceSha256, PixelBox box, string outputPath, CancellationToken ct)
    {
        var (stdout, _) = await RunAsync(["-I", Path.Combine(ScriptDirectory, "sam_crop.py"), "--image", sourcePath,
            "--left", box.Left.ToString(), "--top", box.Top.ToString(), "--right", box.Right.ToString(), "--bottom", box.Bottom.ToString(), "--out", outputPath], null, ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(stdout);
        var r = doc.RootElement;
        if (r.TryGetProperty("error", out var err)) throw new InvalidOperationException("crop: " + err.GetString());
        var actual = new PixelBox(r.GetProperty("left").GetInt32(), r.GetProperty("top").GetInt32(), r.GetProperty("right").GetInt32(), r.GetProperty("bottom").GetInt32());
        return new CropResult(outputPath, new CropTransform(sourceEvidenceId, sourceSha256, r.GetProperty("imageWidth").GetInt32(), r.GetProperty("imageHeight").GetInt32(), actual, 1.0));
    }

    public async Task<ImageQualitySignals?> AnalyzeAsync(string imagePath, CancellationToken ct)
    {
        try
        {
            var (stdout, _) = await RunAsync(["-I", Path.Combine(ScriptDirectory, "image_quality.py"), "--image", imagePath], null, ct).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(stdout);
            var r = doc.RootElement;
            return new ImageQualitySignals(r.GetProperty("blurLaplacianVariance").GetDouble(), r.GetProperty("meanLuminance").GetDouble(), r.GetProperty("version").GetString() ?? "image-quality/unknown");
        }
        catch (Exception ex) when (ex is InvalidOperationException or JsonException or KeyNotFoundException or System.ComponentModel.Win32Exception or TimeoutException)
        {
            return null;
        }
    }

    private async Task<(string Stdout, string Stderr)> RunAsync(IReadOnlyList<string> args, string? stdin, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(PythonExecutable) { RedirectStandardInput = stdin is not null, RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi) ?? throw new InvalidOperationException($"Could not start {PythonExecutable}");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(options.SidecarTimeoutSeconds));
        string stdout, stderr;
        try
        {
            if (stdin is not null)
            {
                await p.StandardInput.WriteAsync(stdin.AsMemory(), timeout.Token).ConfigureAwait(false);
                p.StandardInput.Close();
            }
            var stdoutTask = p.StandardOutput.ReadToEndAsync(timeout.Token);
            var stderrTask = p.StandardError.ReadToEndAsync(timeout.Token);
            await p.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
            stdout = await stdoutTask; stderr = await stderrTask;
        }
        catch (OperationCanceledException)
        {
            // Never leave an orphaned Python process behind a cancelled or timed-out request.
            try { p.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            if (ct.IsCancellationRequested) throw;
            throw new TimeoutException($"sidecar exceeded {options.SidecarTimeoutSeconds}s");
        }
        if (p.ExitCode != 0) throw new InvalidOperationException($"sidecar exited {p.ExitCode}: {stderr.Trim()}");
        return (stdout, stderr);
    }
}
