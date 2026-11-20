using System.Security.Cryptography;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Non-destructive rescue of an interrupted native Google upload stage.
/// Export never contacts Google, deletes a journal, or overwrites a file.
/// This is intentionally separate from the rclone account path.
/// </summary>
internal static class GoogleDirectRecovery
{
    internal static string DirectoryFor(Guid profileId) => Path.Combine(
        AppStore.DirectoryPath, "google-direct-stages", profileId.ToString("N"));

    internal static int PendingCount(Guid profileId)
    {
        string folder = DirectoryFor(profileId);
        return Directory.Exists(folder)
            ? Directory.EnumerateFiles(folder, "*.json").Take(1001).Count()
            : 0;
    }

    internal static int Export(Guid profileId, string outputDirectory)
    {
        if (profileId == Guid.Empty || string.IsNullOrWhiteSpace(outputDirectory))
            throw new ArgumentException("Google recovery requires profile and destination");
        string root = DirectoryFor(profileId);
        if (!Directory.Exists(root)) return 0;
        Directory.CreateDirectory(outputDirectory);
        int exported = 0;
        foreach (string manifestPath in Directory.EnumerateFiles(root, "*.json"))
        {
            string id = Path.GetFileNameWithoutExtension(manifestPath);
            if (id.Length != 32 || !Guid.TryParseExact(id, "N", out _))
                continue; // Ignore unexpected paths, never follow journal data paths.
            string contentPath = Path.Combine(root, id + ".data");
            if (!File.Exists(contentPath)) continue;
            using var manifest = JsonDocument.Parse(File.ReadAllText(manifestPath));
            if (!manifest.RootElement.TryGetProperty("Name", out var property) ||
                property.ValueKind != JsonValueKind.String)
                continue;
            string requested = property.GetString() ?? "";
            string name = Path.GetFileName(requested.Replace('\\', '/'));
            var invalid = Path.GetInvalidFileNameChars();
            name = new string(name.Select(ch => invalid.Contains(ch) ? '_' : ch)
                .ToArray()).Trim().TrimEnd('.');
            if (name.Length == 0 || name is "." or "..") name = "recovery.bin";
            string outputName = Path.GetFileNameWithoutExtension(name) +
                " (FileHub recovery " + id[..8] + ")" + Path.GetExtension(name);
            string target = Path.Combine(outputDirectory, outputName);
            // Build a complete, fsynced temporary copy before publishing the
            // recovered name. A disk-full error must not leave a partial
            // file pretending to be a finished recovery export.
            string temp = Path.Combine(outputDirectory,
                ".filehub-recovery-" + Guid.NewGuid().ToString("N") + ".tmp");
            try
            {
                if (File.Exists(target))
                    throw new IOException("Recovery destination exists; refusing overwrite");
                using (var source = new FileStream(contentPath, FileMode.Open,
                    FileAccess.Read, FileShare.Read))
                using (var destination = new FileStream(temp, FileMode.CreateNew,
                    FileAccess.Write, FileShare.None, 81920, FileOptions.WriteThrough))
                {
                    source.CopyTo(destination);
                    destination.Flush(flushToDisk: true);
                }
                File.Move(temp, target); // Never overwrites an existing export.
            }
            finally { if (File.Exists(temp)) File.Delete(temp); }
            exported++;
        }
        return exported;
    }

    internal static void PolicyTest()
    {
        Guid profile = Guid.NewGuid();
        string folder = DirectoryFor(profile);
        string output = Path.Combine(Path.GetTempPath(),
            "FileHubGoogleRecoveryExport_" + Guid.NewGuid().ToString("N"));
        string id = Guid.NewGuid().ToString("N");
        try
        {
            Directory.CreateDirectory(folder);
            byte[] payload = "UNCLOSED EXCEL DATA"u8.ToArray();
            File.WriteAllBytes(Path.Combine(folder, id + ".data"), payload);
            File.WriteAllText(Path.Combine(folder, id + ".json"),
                JsonSerializer.Serialize(new
                {
                    Name = "report.xlsx", Status = "existing-recovery-required",
                    Size = payload.Length,
                    Md5Hex = Convert.ToHexString(MD5.HashData(payload)).ToLowerInvariant()
                }));
            if (PendingCount(profile) != 1 ||
                RemoteMount.GetIntegrityAlert(profile) is null ||
                Export(profile, output) != 1)
                throw new IOException("Google recovery journal was not discoverable");
            string restored = Directory.GetFiles(output).Single();
            if (!File.ReadAllBytes(restored).SequenceEqual(payload) ||
                !File.ReadAllBytes(Path.Combine(folder, id + ".data"))
                    .SequenceEqual(payload))
                throw new IOException("Google recovery export changed original bytes");
            bool refused = false;
            try
            {
                Export(profile, output);
            }
            catch (IOException) { refused = true; }
            if (!refused || !File.ReadAllBytes(restored).SequenceEqual(payload))
                throw new IOException("Recovery export overwrote an existing backup");
            Console.WriteLine("GOOGLE_DIRECT_CRASH_JOURNAL_EXPORT_NO_OVERWRITE_PASS");
        }
        finally
        {
            if (Directory.Exists(folder)) Directory.Delete(folder, true);
            if (Directory.Exists(output)) Directory.Delete(output, true);
        }
    }
}
