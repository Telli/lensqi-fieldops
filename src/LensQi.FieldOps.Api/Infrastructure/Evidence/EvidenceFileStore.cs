using System.Security.Cryptography;

namespace LensQi.FieldOps.Api.Infrastructure.Evidence;

public sealed record StoredArtifact(string EvidenceId, string Sha256, string RelativePath, long Bytes, string MediaType, int Width, int Height);

/// <summary>Immutable artifact store: content-addressed files under data/evidence/{inspectionId}/. Never overwrites.</summary>
public sealed class EvidenceFileStore(string dataDirectory)
{
    public const long MaxImageBytes = 12 * 1024 * 1024;
    private readonly string _root = Path.Combine(dataDirectory, "evidence");
    public string DataDirectoryPath => dataDirectory;

    public static string? DetectImageType(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) return "image/jpeg";
        if (bytes.Length >= 8 && bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return "image/png";
        return null;
    }

    public StoredArtifact SaveImage(string inspectionId, string evidenceId, byte[] bytes, string mediaType, int width, int height)
    {
        var sha = Convert.ToHexStringLower(SHA256.HashData(bytes));
        var dir = Path.Combine(_root, inspectionId);
        Directory.CreateDirectory(dir);
        var ext = mediaType == "image/png" ? "png" : "jpg";
        var rel = Path.Combine("evidence", inspectionId, $"{evidenceId}-{sha[..12]}.{ext}");
        var full = Path.Combine(dataDirectory, rel);
        if (!File.Exists(full)) File.WriteAllBytes(full, bytes);
        return new StoredArtifact(evidenceId, sha, rel, bytes.LongLength, mediaType, width, height);
    }

    public byte[] Read(string relativePath) => File.ReadAllBytes(Path.Combine(dataDirectory, relativePath));
}
