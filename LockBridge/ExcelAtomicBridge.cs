using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.RegularExpressions;

namespace NASMount.LockBridge;

/// <summary>
/// Experimental Excel save transaction adapter for the WinFsp bridge.
/// Only intercepts delete-then-rename saves with a temp file created during
/// this process. All other filesystem operations use the normal passthrough.
/// </summary>
internal sealed class ExcelAtomicBridge : IDisposable
{
    private readonly string _lowerRoot;
    private readonly Uri _remoteRoot;
    private readonly HttpClient _http;
    private readonly (int port, string user, string password)? _rc;
    private readonly ConcurrentDictionary<string, DateTime> _newTemp = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, (string source, DateTime created)> _pending = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, byte> _ownedOwner = new(StringComparer.OrdinalIgnoreCase);
    // Excel can ask WinFsp to replace "LabActual.xlsx" via a temporary
    // workbook using "LABACTUAL.XLSX" as the final destination. Without
    // this short-lived snapshot the replacement changes the *remote* name,
    // not merely how Explorer displays it.
    private readonly ConcurrentDictionary<string, (string Original, DateTime SeenUtc)>
        _originalCase = new(StringComparer.OrdinalIgnoreCase);
    // Excel may stage as eight-character names with or without .tmp, or
    // descriptive .tmp names depending on version and save code path.
    private static readonly Regex TempName = new(
        "^[0-9a-fA-F]{8}(\\.tmp)?$", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static bool LooksLikeTemporary(string path)
    {
        string name = Path.GetFileName(Normalize(path));
        return TempName.IsMatch(name) ||
            (name.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase) &&
             !name.StartsWith("~$", StringComparison.OrdinalIgnoreCase));
    }

    internal ExcelAtomicBridge(string lowerRoot, Uri remoteRoot,
        string? basicUser = null, string? basicPassword = null,
        (int port, string user, string password)? rc = null)
    {
        _lowerRoot = lowerRoot.TrimEnd('\\');
        _remoteRoot = remoteRoot.AbsoluteUri.EndsWith('/') ? remoteRoot : new Uri(remoteRoot.AbsoluteUri + "/");
        _http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        if (basicUser is not null)
            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic",
                Convert.ToBase64String(Encoding.UTF8.GetBytes(basicUser + ":" + basicPassword)));
        _rc = rc;
    }

    internal static Uri ProfileRoot(NASMount.NasProfile p)
    {
        string path = string.Join("/", p.Share.Replace('\\','/').Split('/',
            StringSplitOptions.RemoveEmptyEntries).Select(Uri.EscapeDataString));
        return new Uri($"{(p.WebDavUseHttp ? "http" : "https")}://{p.Host}:{(p.Port > 0 ? p.Port : 5006)}/{(path.Length > 0 ? path + "/" : "")}");
    }

    private static string Normalize(string name) => name.TrimStart('\\').Replace('\\', '/');
    private string LowerPath(string name) => _lowerRoot + "\\" + Normalize(name).Replace('/', '\\');
    private Uri RemoteUri(string name) => new(_remoteRoot,
        string.Join("/", Normalize(name).Split('/').Select(Uri.EscapeDataString)));
    private static bool IsExcel(string name) =>
        Path.GetExtension(name).Equals(".xlsx", StringComparison.OrdinalIgnoreCase) ||
        Path.GetExtension(name).Equals(".xlsm", StringComparison.OrdinalIgnoreCase) ||
        Path.GetExtension(name).Equals(".xlsb", StringComparison.OrdinalIgnoreCase) ||
        Path.GetExtension(name).Equals(".xls", StringComparison.OrdinalIgnoreCase);
    private static bool IsOwner(string name) =>
        Path.GetFileName(name).StartsWith("~$", StringComparison.OrdinalIgnoreCase) && IsExcel(name);
    private static string Parent(string name) => Path.GetDirectoryName(Normalize(name).Replace('/', '\\')) ?? "";

    internal bool IsBackupRename(string original, string backup) =>
        IsExcel(original) && LooksLikeTemporary(backup) &&
        string.Equals(Parent(original), Parent(backup), StringComparison.OrdinalIgnoreCase);

    // Observe actual preserved file-name spelling BEFORE Excel moves the
    // existing workbook to its random backup. Never probe outside this
    // isolated backend root and never mutate remote data here.
    internal string? CaptureOriginalCaseBeforeBackup(string original, string backup)
    {
        if (!IsBackupRename(original, backup)) return null;
        string relative = Normalize(original);
        string basename = Path.GetFileName(relative);
        string folder = Path.GetDirectoryName(LowerPath(original))!;
        try
        {
            var matches = Directory.EnumerateFileSystemEntries(folder)
                .Select(Path.GetFileName)
                .Where(x => string.Equals(x, basename,
                    StringComparison.OrdinalIgnoreCase)).Take(2).ToArray();
            // On a truly case-sensitive backend two different files might
            // differ only by case. Never guess which one should be replaced.
            if (matches.Length != 1 || matches[0] is null) return null;
            string prefix = original[..(original.LastIndexOfAny(['\\', '/']) + 1)];
            return prefix + matches[0];
        }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
    }

    internal void RecordSuccessfulBackupRename(
        string original, string backup, string? observedName)
    {
        if (observedName is null || !IsBackupRename(original, backup)) return;
        _originalCase[Normalize(original)] = (observedName, DateTime.UtcNow);
        NASMount.AppStore.Log("OFFICE_ORIGINAL_FILENAME_CASE_CAPTURED " +
            Normalize(original) + " canonical=" + Normalize(observedName));
    }

    internal string CasePreservedStagedDestination(string source, string destination)
    {
        if (!IsStagedWorkbookRename(source, destination)) return destination;
        if (_originalCase.TryGetValue(Normalize(destination), out var saved) &&
            DateTime.UtcNow - saved.SeenUtc <= TimeSpan.FromMinutes(2))
            return saved.Original;
        // Some Office versions replace without the exact 8-hex backup
        // sequence. If the file still exists, prefer its actual preserved
        // spelling instead of trusting Excel's uppercased request.
        string? existing = ExistingCase(destination);
        if (existing is not null) return existing;

        // A brand-new workbook has no previous canonical name. Excel's
        // uppercased extension is not a user rename request; use the
        // conventional lowercase Office extension without modifying the
        // requested base name or any parent directory.
        string ext = Path.GetExtension(destination);
        return ext.Length == 0 ? destination :
            destination[..^ext.Length] + ext.ToLowerInvariant();
    }

    internal void ForgetOriginalCase(string destination) =>
        _originalCase.TryRemove(Normalize(destination), out _);

    internal bool UnsafeUntrackedOverwrite(string temp, string destination) =>
        TempName.IsMatch(Path.GetFileName(Normalize(temp))) && IsExcel(destination) &&
        File.Exists(LowerPath(destination)) && !IsPendingRename(destination, temp);

    /// <summary>
    /// New single-writer behavior: Excel performs the normal original-to-
    /// backup and temp-to-original renames through rclone only. Validate
    /// incoming Office temps before they can replace a workbook.
    /// </summary>
    internal bool IsStagedWorkbookRename(string source, string destination)
    {
        string key = Normalize(source);
        return LooksLikeTemporary(key) && IsExcel(destination) &&
            string.Equals(Parent(key), Parent(destination), StringComparison.OrdinalIgnoreCase);
    }

    private string? ExistingCase(string target)
    {
        try
        {
            string basename = Path.GetFileName(Normalize(target));
            var matches = Directory.EnumerateFileSystemEntries(
                    Path.GetDirectoryName(LowerPath(target))!)
                .Select(Path.GetFileName)
                .Where(name => string.Equals(name, basename,
                    StringComparison.OrdinalIgnoreCase))
                .Take(2).ToArray();
            if (matches.Length != 1 || matches[0] is null) return null;
            return target[..(target.LastIndexOfAny(['\\', '/']) + 1)] + matches[0];
        }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
    }

    internal void OnCreated(string name)
    {
        string key = Normalize(name);
        if (LooksLikeTemporary(key))
        {
            _newTemp[key] = DateTime.UtcNow;
            NASMount.AppStore.Log("TRACE TEMP TRACKED " + key);
        }
        if (IsOwner(name)) _ownedOwner[key] = 1;
    }

    internal bool DeferExcelDelete(string name)
    {
        if (!IsExcel(name) || IsOwner(name)) return false;
        NASMount.AppStore.Log("TRACE EXCEL DELETE PRECHECK " + name + " tracked=" + _newTemp.Count);
        string key = Normalize(name);
        string folder = Parent(key);
        foreach (var record in _newTemp)
        {
            if (DateTime.UtcNow - record.Value > TimeSpan.FromMinutes(2)) continue;
            if (!string.Equals(Parent(record.Key), folder, StringComparison.OrdinalIgnoreCase)) continue;
            if (!File.Exists(LowerPath(record.Key))) continue;
            _pending[key] = (record.Key, DateTime.UtcNow);
            NASMount.AppStore.Log("ATOMIC EXCEL ORIGINAL RETAINED pending=" + key + " source=" + record.Key);
            return true;
        }
        return false;
    }

    internal bool IsPendingRename(string original, string temp)
    {
        string target = Normalize(original);
        return _pending.TryGetValue(target, out var p) &&
            DateTime.UtcNow - p.created < TimeSpan.FromMinutes(2) &&
            string.Equals(p.source, Normalize(temp), StringComparison.OrdinalIgnoreCase);
    }

    // Real Excel renames the old workbook to a random backup temp before
    // renaming its staged replacement into the original name. Preserve the
    // server original by copying a local backup rather than renaming it.
    internal bool TryPrepareBackupRename(string original, string backup)
    {
        string oldKey = Normalize(original), backupKey = Normalize(backup);
        if (!IsExcel(original) || !TempName.IsMatch(Path.GetFileName(backupKey)) ||
            !string.Equals(Parent(oldKey), Parent(backupKey), StringComparison.OrdinalIgnoreCase))
            return false;
        var source = _newTemp
            .Where(entry => !string.Equals(entry.Key, backupKey, StringComparison.OrdinalIgnoreCase) &&
                string.Equals(Parent(entry.Key), Parent(oldKey), StringComparison.OrdinalIgnoreCase) &&
                DateTime.UtcNow - entry.Value < TimeSpan.FromMinutes(2) && File.Exists(LowerPath(entry.Key)))
            .OrderByDescending(entry => entry.Value)
            .FirstOrDefault();
        if (source.Key is null) return false;
        File.Copy(LowerPath(oldKey), LowerPath(backupKey), overwrite: true);
        _pending[oldKey] = (source.Key, DateTime.UtcNow);
        NASMount.AppStore.Log("ATOMIC EXCEL ORIGINAL PRESERVED on backup-rename " + oldKey +
            " backup=" + backupKey + " replacement=" + source.Key);
        return true;
    }

    /// <summary>
    /// Returns the staged document size/hash after verifying actual workbook
    /// bytes. This is read-only; it never touches the WebDAV server.
    /// </summary>
    internal WorkbookPayloadGuard.Payload InspectStagedRename(string original, string temp)
    {
        string source = Normalize(temp);
        var payload = WorkbookPayloadGuard.Inspect(LowerPath(source), original);
        NASMount.AppStore.Log("EXCEL STAGED VALIDATED path=" + source +
            " bytes=" + payload.Length + " sha256=" + payload.Hash);
        return payload;
    }

    /// <summary>
    /// Invoked only after the lower rclone filesystem successfully renamed the
    /// staged temp to the workbook. No second HTTP writer is used for the target.
    /// </summary>
    internal void MarkLowerRenameCompleted(string original, string temp,
        WorkbookPayloadGuard.Payload expected)
    {
        string target = Normalize(original);
        string source = Normalize(temp);
        var current = WorkbookPayloadGuard.Inspect(LowerPath(target), original);
        if (current.Length != expected.Length || current.Hash != expected.Hash)
            throw new IOException("Post-rename local workbook integrity mismatch " + target);
        _newTemp.TryRemove(source, out _);
        _pending.TryRemove(target, out _);
        NASMount.AppStore.Log("EXCEL SINGLE_WRITER_RCLONE_RENAME " +
            source + " -> " + target + " bytes=" + current.Length +
            " sha256=" + current.Hash +
            " (server upload pending; do not consider save durable yet)");
    }

    private void Forget(string path)
    {
        if (_rc is not { } rc) return;
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic",
                Convert.ToBase64String(Encoding.UTF8.GetBytes(rc.user + ":" + rc.password)));
            string body = System.Text.Json.JsonSerializer.Serialize(new { file = path });
            using var response = client.PostAsync($"http://127.0.0.1:{rc.port}/vfs/forget",
                new StringContent(body, Encoding.UTF8, "application/json")).GetAwaiter().GetResult();
            if (!response.IsSuccessStatusCode)
                NASMount.AppStore.Log("ATOMIC VFS FORGET HTTP=" + (int)response.StatusCode + " path=" + path);
        }
        catch (Exception ex) { NASMount.AppStore.Log("ATOMIC VFS FORGET FAILED " + path + " " + ex.Message); }
    }

    internal void OwnerClosed(string name)
    {
        string key = Normalize(name);
        if (!_ownedOwner.ContainsKey(key) || !File.Exists(LowerPath(key))) return;
        using var file = new FileStream(LowerPath(key), FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete);
        using var request = new HttpRequestMessage(HttpMethod.Put, RemoteUri(key)) { Content = new StreamContent(file) };
        using var response = _http.Send(request);
        response.EnsureSuccessStatusCode();
        NASMount.AppStore.Log("ATOMIC OFFICE OWNER VISIBLE " + key);
    }

    internal void OwnerDeleted(string name)
    {
        string key = Normalize(name);
        if (!_ownedOwner.TryRemove(key, out _)) return;
        using var response = _http.DeleteAsync(RemoteUri(key)).GetAwaiter().GetResult();
        if (!response.IsSuccessStatusCode && response.StatusCode != HttpStatusCode.NotFound)
            throw new IOException("Office owner remote delete failed HTTP " + (int)response.StatusCode);
        NASMount.AppStore.Log("ATOMIC OFFICE OWNER REMOVED " + key);
    }

    public void Dispose() => _http.Dispose();
}
