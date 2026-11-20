using System.Security.Cryptography;
using System.Text.Json;

namespace NASMount.LockBridge;

/// <summary>
/// Durable local write stage for NEW Google Drive binary files only.
/// Existing content is never replaced here: Drive files.update does not
/// document an independently verified atomic content-update CAS contract.
/// Pre-generated Drive IDs prevent duplicate files after a lost reply.
/// </summary>
internal sealed class DirectGoogleWriteStage : IDisposable
{
    private sealed record Manifest(string Id, string ParentId, string Name,
        string? DriveId, string Status, long Size, string Md5Hex,
        DateTimeOffset Created);

    private readonly GoogleDirectApiClient _api;
    private readonly string _dataPath;
    private readonly string _manifestPath;
    private readonly string _parentId;
    private readonly string _name;
    private readonly DateTimeOffset _created = DateTimeOffset.UtcNow;
    private readonly object _gate = new();
    private string? _driveId;
    private bool _uploaded;
    private bool _discarded;
    private bool _closed;
    internal string LocalDataPath => _dataPath;
    internal bool Uploaded => _uploaded;
    internal bool Discarded => _discarded;
    internal FileStream Local { get; }
    internal long Length => Local.Length;

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

    private void SaveManifest(string status, string hash)
    {
        var manifest = new Manifest(Path.GetFileNameWithoutExtension(_dataPath),
            _parentId, _name, _driveId, status, Local.Length, hash, _created);
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
