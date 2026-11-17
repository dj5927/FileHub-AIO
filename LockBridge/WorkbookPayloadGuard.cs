using System.IO.Compression;
using System.Security.Cryptography;

namespace NASMount.LockBridge;

/// <summary>
/// Fail closed BEFORE replacing an Office workbook with an empty or incomplete
/// temporary file. We inspect the replacement file, not the old destination.
/// This is a format sanity check, not a substitute for Excel's own parser.
/// </summary>
internal static class WorkbookPayloadGuard
{
    internal readonly record struct Payload(long Length, string Hash);
    private static readonly byte[] CompoundDocument =
        [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];

    internal static Payload Inspect(string source, string destination)
    {
        using var stream = new FileStream(source, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete);
        long size = stream.Length;
        if (size < 512)
            throw new InvalidDataException(
                $"Refusing to overwrite workbook with empty/truncated payload: {size} bytes");
        var header = new byte[8];
        if (stream.Read(header) != header.Length)
            throw new InvalidDataException("Workbook header was not readable");
        stream.Position = 0;
        bool cfb = header.AsSpan().SequenceEqual(CompoundDocument);
        bool zip = header[0] == (byte)'P' && header[1] == (byte)'K' &&
            header[2] == 3 && header[3] == 4;
        string ext = Path.GetExtension(destination).ToLowerInvariant();
        switch (ext)
        {
            case ".xls":
                if (!cfb) throw new InvalidDataException("Invalid Excel 97-2003 CFB header");
                if ((size & 511L) != 0)
                    throw new InvalidDataException("Incomplete compound-file sectors");
                break;
            case ".xlsx":
            case ".xlsm":
            case ".xlsb":
                if (zip)
                {
                    using (var archive = new ZipArchive(stream, ZipArchiveMode.Read, leaveOpen: true))
                    {
                        bool hasWorkbook = archive.Entries.Any(x =>
                            x.FullName.Equals("xl/workbook.xml", StringComparison.OrdinalIgnoreCase) ||
                            x.FullName.Equals("xl/workbook.bin", StringComparison.OrdinalIgnoreCase));
                        bool hasContentTypes = archive.Entries.Any(x =>
                            x.FullName.Equals("[Content_Types].xml", StringComparison.OrdinalIgnoreCase));
                        if (!hasWorkbook || !hasContentTypes)
                            throw new InvalidDataException("Excel ZIP container is incomplete");
                        // Read each entry in order to discover bad compressed data/CRC
                        // before replacing the last good workbook.
                        foreach (var entry in archive.Entries)
                        {
                            if (entry.FullName.EndsWith('/')) continue;
                            using var body = entry.Open();
                            body.CopyTo(Stream.Null);
                        }
                    }
                }
                else if (!cfb)
                    throw new InvalidDataException("Invalid Excel OOXML or encrypted CFB header");
                break;
            default:
                throw new InvalidOperationException("Unexpected workbook extension " + ext);
        }
        stream.Position = 0;
        return new Payload(size, Convert.ToHexString(SHA256.HashData(stream)));
    }
}
