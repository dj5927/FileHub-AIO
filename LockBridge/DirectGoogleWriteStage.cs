using System.Security.Cryptography;
using System.Text.Json;

namespace NASMount.LockBridge;

/// <summary>
/// Durable local stage for new files and explicitly gated existing-binary
/// pilot writes. Drive files.update has no demonstrated atomic content CAS:
/// preserve the previous binary revision and local recovery bytes.
/// </summary>
internal sealed class DirectGoogleWriteStage : IDisposable
{
    private sealed record Manifest(string Id, string ParentId, string Name,
        string? DriveId, string Status, long Size, string Md5Hex,
        DateTimeOffset Created, string? OriginalDriveId = null,
        string? OriginalRevision = null, string? OriginalMd5 = null);

    private readonly GoogleDirectApiClient _api;
    private readonly string _dataPath;
    private readonly string _manifestPath;
    private readonly string _parentId;
    private readonly string _name;
    private GoogleDirectApiClient.Item? _original;
    private readonly DateTimeOffset _created = DateTimeOffset.UtcNow;
    private readonly object _gate = new();
    private string? _driveId;
    private bool _uploaded;
    private bool _discarded;
    private bool _closed;
    private int _cleanupHandles;
    private int _closeHandles;
    internal string LocalDataPath => _dataPath;
    internal bool Uploaded => _uploaded;
    internal bool Discarded => _discarded;
    internal FileStream Local { get; }
    internal long Length => Local.Length;
    internal bool EditingExisting => _original is not null;

    // Multiple WinFsp handles may write and flush the same new file.
    // Upload only after final Cleanup; release local storage at final Close.
    internal void RegisterHandle()
    {
        lock (_gate)
        {
            if (_closed || _discarded)
                throw new IOException("Google write stage is no longer available");
            _cleanupHandles++;
            _closeHandles++;
        }
    }

    internal bool LastCleanup()
    {
        lock (_gate)
        {
            if (_cleanupHandles <= 0)
                throw new InvalidOperationException("Google stage cleanup mismatch");
            return --_cleanupHandles == 0;
        }
    }

    internal bool LastClose()
    {
        lock (_gate)
        {
            if (_closeHandles <= 0)
                throw new InvalidOperationException("Google stage close mismatch");
            return --_closeHandles == 0;
        }
    }

    internal DirectGoogleWriteStage(GoogleDirectApiClient api, string stageDirectory,
        string parentId, string name)
    {
        _api = api;
        _parentId = parentId;
        _name = name;
        Directory.CreateDirectory(stageDirectory);
        string uid = Guid.NewGuid().ToString("N");
        _dataPath = Path.Combine(stageDirectory, uid + ".data");
        _manifestPath = Path.Combine(stageDirectory, uid + ".json");
        Local = new FileStream(_dataPath, FileMode.CreateNew, FileAccess.ReadWrite,
            FileShare.Read, 81920, FileOptions.WriteThrough);
        SaveManifest("local", "");
    }

    internal DirectGoogleWriteStage(GoogleDirectApiClient api, string stageDirectory,
        string parentId, string name, GoogleDirectApiClient.Item original)
        : this(api, stageDirectory, parentId, name)
    {
        if (original.IsFolder || original.Size < 0 ||
            original.Size > 128L * 1024 * 1024 ||
            string.IsNullOrWhiteSpace(original.HeadRevisionId) ||
            string.IsNullOrWhiteSpace(original.Md5Checksum) ||
            original.MimeType.StartsWith("application/vnd.google-apps.",
                StringComparison.OrdinalIgnoreCase))
        {
            Discard();
            throw new IOException("Google existing file cannot be safely staged for editing");
        }
        _original = original;
        try
        {
            SaveManifest("original-download", "");
            long downloaded = 0;
            while (downloaded < original.Size)
            {
                int expected = (int)Math.Min(4 * 1024 * 1024,
                    original.Size - downloaded);
                var content = api.ReadRangeAsync(original.Id,
                    downloaded, expected).GetAwaiter().GetResult();
                if (content.Length != expected)
                    throw new IOException("Google existing file read was incomplete");
                Local.Write(content);
                downloaded += content.Length;
            }
            Local.Flush(flushToDisk: true);
            Local.Position = 0;
            string md5 = Convert.ToHexString(MD5.HashData(Local)).ToLowerInvariant();
            var latest = api.GetFileAsync(original.Id).GetAwaiter().GetResult();
            if (latest?.HeadRevisionId != original.HeadRevisionId ||
                !string.Equals(latest.Md5Checksum, original.Md5Checksum,
                    StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(md5, original.Md5Checksum,
                    StringComparison.OrdinalIgnoreCase))
                throw new IOException("Google file changed during staging; refusing edit");
            SaveManifest("original-verified", md5);
        }
        catch
        {
            Discard(); // No local edits yet; do not retain incomplete copy.
            throw;
        }
    }

    private void SaveManifest(string status, string hash)
    {
        var manifest = new Manifest(Path.GetFileNameWithoutExtension(_dataPath),
            _parentId, _name, _driveId, status, Local.Length, hash, _created,
            _original?.Id, _original?.HeadRevisionId, _original?.Md5Checksum);
        string temp = _manifestPath + ".next";
        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(manifest);
        using (var file = new FileStream(temp, FileMode.Create, FileAccess.Write,
            FileShare.None, 8192, FileOptions.WriteThrough))
        {
            file.Write(bytes);
            file.Flush(flushToDisk: true);
        }
        File.Move(temp, _manifestPath, overwrite: true);
    }

    internal int Read(long offset, byte[] buffer)
    {
        lock (_gate)
        {
            Local.Position = offset;
            return Local.Read(buffer);
        }
    }

    internal void Write(ulong offset, byte[] bytes, bool append)
    {
        lock (_gate)
        {
            if (_uploaded || _closed || _discarded)
                throw new IOException("Google new file is already committed or closed");
            Local.Position = append ? Local.Length : checked((long)offset);
            Local.Write(bytes);
            // The .data FileStream uses WriteThrough. Avoid a second
            // JSON rename+fsync for every small Windows write callback:
            // metadata is durably refreshed at Flush/Cleanup instead.
            // After a crash, recovery scans the actual .data file length.
        }
    }

    internal void SetLength(long size)
    {
        lock (_gate)
        {
            if (_uploaded || _closed || _discarded)
                throw new IOException("Google new file cannot change after upload");
            Local.SetLength(size);
            SaveManifest("local", "");
        }
    }

    internal void FlushLocal()
    {
        lock (_gate)
        {
            Local.Flush(flushToDisk: true);
            SaveManifest("local", "");
        }
    }

    internal void Commit()
    {
        lock (_gate)
        {
            if (_discarded || _closed)
                throw new IOException("Cannot commit discarded Google write");
            if (_uploaded) return;
            Local.Flush(flushToDisk: true);
            Local.Position = 0;
            string md5 = Convert.ToHexString(MD5.HashData(Local)).ToLowerInvariant();
            Local.Position = 0;
            if (_original is not null)
            {
                CommitExisting(md5);
                return;
            }
            if (string.IsNullOrEmpty(_driveId))
            {
                _driveId = _api.GenerateUploadIdAsync().GetAwaiter().GetResult();
                SaveManifest("allocated", md5);
            }
            _api.InvalidateFolder(_parentId);
            var children = _api.ListChildrenAsync(_parentId).GetAwaiter().GetResult();
            if (children.Any(item => item.Name.Equals(_name,
                StringComparison.OrdinalIgnoreCase) && item.Id != _driveId))
            {
                SaveManifest("conflict-name", md5);
                throw new IOException("Google Drive name conflict; local recovery retained");
            }
            var preexisting = _api.GetFileAsync(_driveId).GetAwaiter().GetResult();
            if (preexisting is not null)
            {
                Reconcile(preexisting, md5);
                return;
            }
            SaveManifest("uploading", md5);
            try
            {
                var file = _api.UploadNewAsync(_parentId, _name, Local,
                    generatedId: _driveId).GetAwaiter().GetResult();
                Reconcile(file, md5);
            }
            catch (Exception ex) when (ex is IOException or HttpRequestException
                or TaskCanceledException)
            {
                try
                {
                    var received = _api.GetFileAsync(_driveId).GetAwaiter().GetResult();
                    if (received is not null)
                    {
                        Reconcile(received, md5);
                        return;
                    }
                }
                catch (Exception inspect) when (inspect is IOException or
                    HttpRequestException or TaskCanceledException) { }
                SaveManifest("recovery-required", md5);
                throw new IOException(
                    "Google new-file upload unresolved; durable local recovery retained", ex);
            }
        }
    }

    private void CommitExisting(string expectedMd5)
    {
        var original = _original!;
        if (Local.Length == original.Size &&
            string.Equals(expectedMd5, original.Md5Checksum,
                StringComparison.OrdinalIgnoreCase))
        {
            _uploaded = true;
            SaveManifest("unchanged", expectedMd5);
            return;
        }
        SaveManifest("existing-edit-pending", expectedMd5);
        try
        {
            _api.PinCurrentBinaryRevisionAsync(original.Id,
                original.HeadRevisionId!, original.Md5Checksum!)
                .GetAwaiter().GetResult();
            SaveManifest("original-revision-pinned", expectedMd5);
            Local.Position = 0;
            var revised = _api.UploadExistingAsync(original.Id,
                original.Md5Checksum!, original.HeadRevisionId!, Local)
                .GetAwaiter().GetResult();
            var latest = _api.GetFileAsync(original.Id).GetAwaiter().GetResult();
            if (revised.Id != original.Id || latest is null ||
                latest.Size != Local.Length ||
                !string.Equals(latest.Md5Checksum, expectedMd5,
                    StringComparison.OrdinalIgnoreCase))
                throw new IOException("Google existing edit upload not verified");
            _api.InvalidateFolder(_parentId);
            _uploaded = true;
            SaveManifest("existing-committed", expectedMd5);
        }
        catch (Exception ex) when (ex is IOException or HttpRequestException
            or TaskCanceledException)
        {
            SaveManifest("existing-recovery-required", expectedMd5);
            throw new IOException(
                "Google existing file edit unconfirmed; local recovery retained", ex);
        }
    }

    private void Reconcile(GoogleDirectApiClient.Item actual, string expectedMd5)
    {
        if (actual.Id != _driveId || actual.Size != Local.Length ||
            string.IsNullOrEmpty(actual.Md5Checksum) ||
            !actual.Md5Checksum.Equals(expectedMd5,
                StringComparison.OrdinalIgnoreCase))
        {
            SaveManifest("conflict-recovery-required", expectedMd5);
            throw new IOException(
                "Google Drive content verification failed; local recovery retained");
        }
        _uploaded = true;
        SaveManifest("committed", expectedMd5);
    }

    internal void Discard()
    {
        lock (_gate)
        {
            if (_uploaded)
                throw new IOException("Cannot discard an already committed Drive file");
            _discarded = true;
            Dispose();
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_closed) return;
            _closed = true;
            Local.Dispose();
            if (_uploaded || _discarded)
            {
                try { File.Delete(_dataPath); } catch (IOException) { }
                try { File.Delete(_manifestPath); } catch (IOException) { }
            }
            // Failure keeps both content and JSON in the recovery folder.
        }
    }
}
