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

    /// <summary>Pixel size read from the file header (PNG IHDR or the first JPEG SOFn), never from what a client declares.
    /// JPEG sizes are pre-EXIF-rotation. Returns false when no header can be found.</summary>
    public static bool TryReadDimensions(ReadOnlySpan<byte> bytes, out int width, out int height)
    {
        width = height = 0;
        if (DetectImageType(bytes) == "image/png")
        {
            // Signature (8) + IHDR length (4) + "IHDR" (4) + width (4) + height (4), big-endian.
            if (bytes.Length < 24 || bytes[12] != (byte)'I' || bytes[13] != (byte)'H' || bytes[14] != (byte)'D' || bytes[15] != (byte)'R') return false;
            width = (int)System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(bytes[16..]);
            height = (int)System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(bytes[20..]);
            return width > 0 && height > 0;
        }
        if (DetectImageType(bytes) != "image/jpeg") return false;
        var i = 2;
        while (i + 4 <= bytes.Length)
        {
            if (bytes[i] != 0xFF) return false;
            var marker = bytes[i + 1];
            if (marker == 0xFF) { i++; continue; }                                   // fill byte
            if (marker is 0x01 or (>= 0xD0 and <= 0xD7)) { i += 2; continue; }        // standalone markers
            var length = (bytes[i + 2] << 8) | bytes[i + 3];
            if (length < 2) return false;
            // SOF0–SOF15 except DHT (C4), JPG (C8) and DAC (CC) carry the frame size.
            if (marker is >= 0xC0 and <= 0xCF and not (0xC4 or 0xC8 or 0xCC))
            {
                if (i + 9 > bytes.Length) return false;
                height = (bytes[i + 5] << 8) | bytes[i + 6];
                width = (bytes[i + 7] << 8) | bytes[i + 8];
                return width > 0 && height > 0;
            }
            if (marker == 0xDA) return false;                                       // scan data before any frame header
            i += 2 + length;
        }
        return false;
    }

    /// <summary>
    /// The EXIF orientation of a JPEG (1–8) from its first APP1 Exif segment, or 1 when there is none, the file is not a
    /// JPEG, or the segment cannot be read. Only the IFD0 Orientation tag (0x0112) is consulted.
    /// </summary>
    public static int ReadExifOrientation(ReadOnlySpan<byte> bytes)
    {
        if (DetectImageType(bytes) != "image/jpeg") return 1;
        var i = 2;
        while (i + 4 <= bytes.Length && bytes[i] == 0xFF)
        {
            var marker = bytes[i + 1];
            if (marker == 0xFF) { i++; continue; }
            if (marker is 0x01 or (>= 0xD0 and <= 0xD7)) { i += 2; continue; }
            if (marker is 0xDA or 0xD9) return 1;                                  // image data: no APP1 came first
            var length = (bytes[i + 2] << 8) | bytes[i + 3];
            if (length < 2 || i + 2 + length > bytes.Length) return 1;
            if (marker == 0xE1 && length >= 16 && bytes.Slice(i + 4, 6).SequenceEqual("Exif\0\0"u8))
                return OrientationFromTiff(bytes.Slice(i + 10, length - 8));
            i += 2 + length;
        }
        return 1;
    }

    private static int OrientationFromTiff(ReadOnlySpan<byte> tiff)
    {
        if (tiff.Length < 8) return 1;
        var little = tiff[0] == (byte)'I' && tiff[1] == (byte)'I';
        if (!little && !(tiff[0] == (byte)'M' && tiff[1] == (byte)'M')) return 1;
        var ifd = (int)U32(tiff, 4, little);
        if (ifd <= 0 || ifd + 2 > tiff.Length) return 1;
        var entries = U16(tiff, ifd, little);
        for (var e = 0; e < entries; e++)
        {
            var at = ifd + 2 + e * 12;
            if (at + 12 > tiff.Length) return 1;
            if (U16(tiff, at, little) != 0x0112) continue;
            var value = U16(tiff, at + 8, little);
            return value is >= 1 and <= 8 ? value : 1;
        }
        return 1;
    }

    private static uint U32(ReadOnlySpan<byte> b, int at, bool little) =>
        at + 4 <= b.Length ? (little ? System.Buffers.Binary.BinaryPrimitives.ReadUInt32LittleEndian(b[at..]) : System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(b[at..])) : 0;
    private static ushort U16(ReadOnlySpan<byte> b, int at, bool little) =>
        at + 2 <= b.Length ? (little ? System.Buffers.Binary.BinaryPrimitives.ReadUInt16LittleEndian(b[at..]) : System.Buffers.Binary.BinaryPrimitives.ReadUInt16BigEndian(b[at..])) : (ushort)0;

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
