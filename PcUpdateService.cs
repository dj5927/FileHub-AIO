using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http.Headers;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NASMount;

// The Windows updater has its own release namespace: pc-v1.3.x.
// Android v0.x releases are never considered. This can later move into a
// dedicated PC repository without changing the on-disk update protocol.
internal static class PcUpdateService
{
    internal const string ReleaseRepo = "dj5927/FileHub-AIO";
    internal const string PcTagPrefix = "pc-v";
    internal const string ReleaseApi =
        "https://api.github.com/repos/" + ReleaseRepo + "/releases";
    internal const long MaxDownloadBytes = 700L * 1024 * 1024;
    internal const long MaxUncompressedBytes = 2L * 1024 * 1024 * 1024;
    internal static readonly Version InstalledVersion =
        Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0);
    // One authoritative display version for title bar, tray and dialogs.
    // It comes from the running executable, not a manually updated UI label.
    internal static string DisplayVersion =>
        InstalledVersion.Build >= 0
            ? InstalledVersion.ToString(3) : InstalledVersion.ToString();
    internal static string UpdateRoot =>
        Path.Combine(AppStore.DirectoryPath, "updates");

    internal sealed record Candidate(
        Version Version, string Tag, string DownloadUrl, string Sha256,
        long Size, string Notes, string ReleasePage);
    internal sealed record Staged(Candidate Release, string Directory, string ZipPath);

    private static HttpClient NewClient(TimeSpan timeout)
    {
        var client = new HttpClient { Timeout = timeout };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("FileHubAIO-WindowsUpdater/1.3");
        client.DefaultRequestHeaders.Accept.Add(
            new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        client.DefaultRequestHeaders.TryAddWithoutValidation(
            "X-GitHub-Api-Version", "2022-11-28");
        return client;
    }

    internal static Candidate? ParseReleaseFeed(string json, Version current)
    {
        using var document = JsonDocument.Parse(json);
        if (document.RootElement.ValueKind != JsonValueKind.Array)
            throw new FormatException("GitHub release response is not a list");
        Candidate? newest = null;
        foreach (var release in document.RootElement.EnumerateArray())
        {
            if (release.TryGetProperty("draft", out var draft) && draft.GetBoolean())
                continue;
            if (release.TryGetProperty("prerelease", out var pre) && pre.GetBoolean())
                continue;
            if (!release.TryGetProperty("tag_name", out var tagNode)) continue;
            string tag = tagNode.GetString() ?? "";
            if (!tag.StartsWith(PcTagPrefix, StringComparison.Ordinal)) continue;
            if (!Version.TryParse(tag[PcTagPrefix.Length..], out var version) ||
                version <= current || (newest is not null && version <= newest.Version))
                continue;
            string expected = $"FileHub-AIO-PC-{version}-win-x64.zip";
            if (!release.TryGetProperty("assets", out var assets) ||
                assets.ValueKind != JsonValueKind.Array) continue;
            foreach (var asset in assets.EnumerateArray())
            {
                if (!string.Equals(asset.GetProperty("name").GetString(), expected,
                    StringComparison.Ordinal)) continue;
                string digest = asset.TryGetProperty("digest", out var digestNode) &&
                    digestNode.ValueKind == JsonValueKind.String
                        ? digestNode.GetString() ?? "" : "";
                // SHA-256 is mandatory. Do not install unauthenticated ZIPs
                // even if GitHub returned a valid HTTP 200 status.
                if (!Regex.IsMatch(digest, "^sha256:[a-fA-F0-9]{64}$")) continue;
                string url = asset.GetProperty("browser_download_url").GetString() ?? "";
                if (!Uri.TryCreate(url, UriKind.Absolute, out var parsed) ||
                    parsed.Scheme != "https" ||
                    !string.Equals(parsed.Host, "github.com", StringComparison.OrdinalIgnoreCase) ||
                    !parsed.AbsolutePath.StartsWith(
                        "/" + ReleaseRepo + "/releases/download/" + tag + "/",
                        StringComparison.Ordinal) ||
                    !parsed.AbsolutePath.EndsWith("/" + expected,
                        StringComparison.Ordinal)) continue;
                long size = asset.GetProperty("size").GetInt64();
                if (size <= 0 || size > MaxDownloadBytes) continue;
                string notes = release.TryGetProperty("body", out var noteNode) ?
                    noteNode.GetString() ?? "" : "";
                string page = release.TryGetProperty("html_url", out var pageNode) ?
                    pageNode.GetString() ?? "" : "";
                newest = new Candidate(version, tag, url, digest[7..].ToUpperInvariant(),
                    size, notes, page);
                break;
            }
        }
        return newest;
    }

    internal static async Task<Candidate?> FindUpdateAsync(
        CancellationToken cancellation = default)
    {
        using var client = NewClient(TimeSpan.FromSeconds(15));
        // Android may publish many v0.x releases between two PC releases.
        // Traverse bounded pages so the PC-only tag does not fall off the
        // latest page simply because APKs were published more recently.
        for (int page = 1; page <= 6; page++)
        {
            using var response = await client.GetAsync(
                ReleaseApi + "?per_page=100&page=" + page, cancellation);
            response.EnsureSuccessStatusCode();
            string json = await response.Content.ReadAsStringAsync(cancellation);
            if (json.Length > 10 * 1024 * 1024)
                throw new IOException("GitHub release feed exceeds safe size");
            Candidate? candidate = ParseReleaseFeed(json, InstalledVersion);
            if (candidate is not null) return candidate;
            using var document = JsonDocument.Parse(json);
            if (document.RootElement.GetArrayLength() < 100) return null;
        }
        return null;
    }

    internal static async Task<Staged> DownloadAndStageAsync(
        Candidate release, IProgress<(long, long)>? progress,
        CancellationToken cancellation)
    {
        string parent = Path.Combine(UpdateRoot, release.Tag);
        Directory.CreateDirectory(parent);
        string zipPath = Path.Combine(parent, "update.zip");
        string partial = zipPath + ".download";
        string stage = Path.Combine(parent, "package");
        if (Directory.Exists(stage)) Directory.Delete(stage, recursive: true);
        try
        {
            using var client = NewClient(TimeSpan.FromMinutes(20));
            using var response = await client.GetAsync(release.DownloadUrl,
                HttpCompletionOption.ResponseHeadersRead, cancellation);
            response.EnsureSuccessStatusCode();
            if (response.Content.Headers.ContentLength is long bytes &&
                (bytes != release.Size || bytes > MaxDownloadBytes))
                throw new IOException("Release asset size differs from GitHub metadata");
            await using var source = await response.Content.ReadAsStreamAsync(cancellation);
            long written = 0;
            string actual;
            await using (var destination = new FileStream(partial, FileMode.Create,
                FileAccess.Write, FileShare.None, 128 * 1024, useAsync: true))
            using (var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                byte[] buffer = new byte[128 * 1024];
                while (true)
                {
                    int got = await source.ReadAsync(buffer, cancellation);
                    if (got == 0) break;
                    written += got;
                    if (written > MaxDownloadBytes || written > release.Size)
                        throw new IOException("Update ZIP exceeded advertised download size");
                    sha.AppendData(buffer.AsSpan(0, got));
                    await destination.WriteAsync(buffer.AsMemory(0, got), cancellation);
                    progress?.Report((written, release.Size));
                }
                await destination.FlushAsync(cancellation);
                actual = Convert.ToHexString(sha.GetHashAndReset());
            }
            if (written != release.Size ||
                !string.Equals(actual, release.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new IOException("Update ZIP SHA-256 mismatch; install cancelled");
            File.Move(partial, zipPath, overwrite: true);
            Directory.CreateDirectory(stage);
            ExtractVerified(zipPath, stage);
            return new Staged(release, stage, zipPath);
        }
        catch
        {
            try { if (File.Exists(partial)) File.Delete(partial); } catch { }
            try { if (Directory.Exists(stage)) Directory.Delete(stage, true); } catch { }
            throw;
        }
    }

    internal static void ExtractVerified(string zipPath, string destination)
    {
        using var archive = ZipFile.OpenRead(zipPath);
        int entries = 0;
        long total = 0;
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in archive.Entries)
        {
            if (++entries > 3000)
                throw new InvalidDataException("Too many entries in update ZIP");
            string name = entry.FullName;
            string normalized = name.EndsWith('/') ? name[..^1] : name;
            if (normalized.Length == 0 || name.Length > 350 ||
                name.StartsWith('/') || name.Contains('\\') || name.Contains(':') ||
                normalized.Split('/').Any(x => x is "" or "." or ".."))
                throw new InvalidDataException("Unsafe ZIP path");
            int unixMode = (entry.ExternalAttributes >> 16) & 0xF000;
            if (unixMode == 0xA000)
                throw new InvalidDataException("Update ZIP contains symbolic links");
            if (!name.EndsWith('/'))
            {
                if (!paths.Add(name))
                    throw new InvalidDataException("Duplicate Windows filename in ZIP");
                total += entry.Length;
                if (total > MaxUncompressedBytes)
                    throw new InvalidDataException("Update ZIP extraction size exceeded");
            }
        }
        if (!paths.Contains("FileHubAIO.exe") ||
            !paths.Contains("tools/NASMountLockBridge.exe") ||
            !paths.Contains("tools/rclone.exe"))
            throw new InvalidDataException("Incomplete Windows update package");
        ZipFile.ExtractToDirectory(zipPath, destination, overwriteFiles: false);
    }

    internal static string CreateApplyPlan(Staged staged)
    {
        string install = Path.GetDirectoryName(Environment.ProcessPath ??
            throw new IOException("Missing current executable location"))!;
        string planPath = Path.Combine(UpdateRoot, staged.Release.Tag, "apply.json");
        var plan = new PcUpdateApply.Plan(Environment.ProcessId,
            install, staged.Directory, staged.Release.Version.ToString(), DateTime.UtcNow);
        File.WriteAllText(planPath, JsonSerializer.Serialize(plan));
        return planPath;
    }

    internal static void StartHelper(Staged staged)
    {
        string exe = Path.Combine(staged.Directory, "FileHubAIO.exe");
        if (!File.Exists(exe)) throw new IOException("Update helper exe not found");
        string plan = CreateApplyPlan(staged);
        // The mount job must die with the old GUI, but the updater helper
        // must remain alive long enough to replace it and launch the new exe.
        MountProcessLifetime.StartUpdaterOutsideMountJob(
            exe, staged.Directory, plan);
        AppStore.Log("PC_UPDATE_HELPER_STARTED " + staged.Release.Tag);
    }

    internal static void FeedPolicyTest()
    {
        string Build(string tag, string digest, bool prerelease = false)
        {
            string ver = tag[PcTagPrefix.Length..];
            string asset = $"FileHub-AIO-PC-{ver}-win-x64.zip";
            return JsonSerializer.Serialize(new {
                tag_name = tag, draft = false, prerelease,
                assets = new[] { new { name = asset,
                    digest, size = 1024L,
                    browser_download_url =
                        $"https://github.com/{ReleaseRepo}/releases/download/{tag}/{asset}" } },
                html_url = $"https://github.com/{ReleaseRepo}/releases/tag/{tag}",
                body = "QA"
            });
        }
        string okHash = "sha256:" + new string('a', 64);
        string list = "[" + Build("pc-v1.3.84", okHash) + "," +
            Build("pc-v1.3.85", okHash, true) + "," +
            Build("pc-v1.3.86", "sha256:INVALID") + "," +
            JsonSerializer.Serialize(new { tag_name = "v0.1.200",
                draft = false, prerelease = false }) + "]";
        Candidate? candidate = ParseReleaseFeed(list, new Version(1, 3, 83));
        if (candidate?.Tag != "pc-v1.3.84" ||
            ParseReleaseFeed(list, new Version(1, 3, 84)) is not null)
            throw new IOException("Windows updater mixed Android releases or accepted wrong digest");
        Console.WriteLine("PC_UPDATE_FEED_ANDROID_ISOLATION_AND_SHA256_PASS");
    }
}
