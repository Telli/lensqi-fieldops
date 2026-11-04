using System.Buffers.Binary;

namespace LensQi.FieldOps.Meta.Voice;

/// <summary>Bounded mono PCM WAV accepted by Muse Voice. No decoder or shell process is needed.</summary>
public static class ClaimAudio
{
    public const int MaxSeconds = 60;
    public const int MaxBytes = 44 + 24_000 * 2 * MaxSeconds;

    public static bool IsSupported(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length < 44 || bytes.Length > MaxBytes || !bytes[..4].SequenceEqual("RIFF"u8) || !bytes.Slice(8, 4).SequenceEqual("WAVE"u8)) return false;
        if (BinaryPrimitives.ReadUInt32LittleEndian(bytes[4..]) != bytes.Length - 8) return false;
        var format = false;
        var data = false;
        uint rate = 0;
        uint dataBytes = 0;
        var offset = 12;
        for (; offset + 8 <= bytes.Length;)
        {
            var size = BinaryPrimitives.ReadUInt32LittleEndian(bytes[(offset + 4)..]);
            if (size > bytes.Length - offset - 8) return false;
            var chunk = bytes.Slice(offset + 8, (int)size);
            if (bytes.Slice(offset, 4).SequenceEqual("fmt "u8))
            {
                if (format || size < 16) return false;
                rate = BinaryPrimitives.ReadUInt32LittleEndian(chunk[4..]);
                format = BinaryPrimitives.ReadUInt16LittleEndian(chunk) == 1 && BinaryPrimitives.ReadUInt16LittleEndian(chunk[2..]) == 1
                    && rate is 16_000 or 24_000 && BinaryPrimitives.ReadUInt16LittleEndian(chunk[14..]) == 16
                    && BinaryPrimitives.ReadUInt32LittleEndian(chunk[8..]) == rate * 2 && BinaryPrimitives.ReadUInt16LittleEndian(chunk[12..]) == 2;
                if (!format) return false;
            }
            if (bytes.Slice(offset, 4).SequenceEqual("data"u8))
            {
                if (data || size == 0 || size % 2 != 0) return false;
                data = true;
                dataBytes = size;
            }
            offset += 8 + (int)size + (int)(size % 2);
            if (offset > bytes.Length) return false;
        }
        return offset == bytes.Length && format && data && dataBytes <= rate * 2 * MaxSeconds;
    }
}
