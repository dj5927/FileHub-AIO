using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace NASMount;

/// <summary>
/// Explicit, isolated real-account smoke test. Never runs automatically or
/// changes existing user files. Creates exactly one disposable Drive folder,
/// verifies native API I/O, and trashes the folder in finally.
/// OAuth credentials, file IDs and names are never written to stdout/logs.
/// </summary>
internal static class GoogleDirectLiveLab
{
    internal static async Task RunAsync(Guid profileId,
        CancellationToken cancel = default)
    {
        var profile = AppStore.Load().Profiles.SingleOrDefault(p =>
            p.Id == profileId && p.Protocol == "GoogleDrive") ??
            throw new ArgumentException("A saved Google Drive profile is required");
        if (!GoogleDirectCredentialStore.HasNative(profile))
            throw new IOException(
                "Google native Chrome authorization not available; live test refused");
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        var oauth = GoogleDirectOAuthSession.ImportCustomProfile(http, profile);
        var api = new GoogleDirectApiClient(http, oauth.AccessTokenAsync,
            cacheTtl: TimeSpan.FromSeconds(2), maxRetries: 0);
        string folderName = "FileHub-Direct-Smoke-" +
            Guid.NewGuid().ToString("N");
        string? folderId = null;
        var total = Stopwatch.StartNew();
        bool cleaned = false;
        try
        {
            var timer = Stopwatch.StartNew();
            _ = await api.ListChildrenAsync("root", cancel);
            Console.WriteLine("LIVE_DIRECT_ROOT_LIST_OK elapsedMs=" +
                timer.ElapsedMilliseconds);
            timer.Restart();
            var folder = await api.CreateFolderAsync("root", folderName, cancel);
            folderId = folder.Id;
            Console.WriteLine("LIVE_DIRECT_FOLDER_CREATE_OK elapsedMs=" +
                timer.ElapsedMilliseconds);
            byte[] payload = Encoding.UTF8.GetBytes(
                "FileHub native Google Drive disposable probe " + folderName);
            using var input = new MemoryStream(payload, writable: false);
            timer.Restart();
            var uploaded = await api.UploadNewAsync(folder.Id,
                "native-smoke.txt", input, cancel);
            Console.WriteLine("LIVE_DIRECT_UPLOAD_OK elapsedMs=" +
                timer.ElapsedMilliseconds);
            timer.Restart();
            var readBack = await api.ReadRangeAsync(uploaded.Id, 0,
                payload.Length, cancel);
            if (!CryptographicOperations.FixedTimeEquals(
                SHA256.HashData(payload), SHA256.HashData(readBack)))
                throw new IOException("Native Google read-back integrity mismatch");
            Console.WriteLine("LIVE_DIRECT_READ_SHA256_OK elapsedMs=" +
                timer.ElapsedMilliseconds);
            timer.Restart();
            var renamed = await api.RenameAsync(uploaded.Id,
                "native-smoke-renamed.txt", folder.Id, cancel);
            var verified = await api.GetFileAsync(uploaded.Id, cancel);
            if (renamed.Id != uploaded.Id || verified?.Name !=
                "native-smoke-renamed.txt")
                throw new IOException("Native Google rename did not persist");
            Console.WriteLine("LIVE_DIRECT_RENAME_OK elapsedMs=" +
                timer.ElapsedMilliseconds);
        }
        finally
        {
            // Only the isolated folder created by THIS run may be trashed.
            // If Google failed after accepting creation but before replying,
            // resolve by our unique name instead of touching other files.
            try
            {
                if (folderId is null)
                {
                    api.InvalidateFolder("root");
                    folderId = (await api.ListChildrenAsync("root",
                        CancellationToken.None)).FirstOrDefault(x =>
                        x.IsFolder && x.Name == folderName)?.Id;
                }
                if (folderId is not null)
                {
                    await api.TrashAsync(folderId, "root", null,
                        CancellationToken.None);
                    Console.WriteLine("LIVE_DIRECT_TEST_FOLDER_TRASHED_OK");
                    cleaned = true;
                }
            }
            catch (Exception ex) when (ex is IOException or
                OperationCanceledException or HttpRequestException)
            {
                Console.Error.WriteLine("LIVE_DIRECT_TEST_FOLDER_CLEANUP_UNCONFIRMED category=" +
                    ex.GetType().Name);
            }
        }
        if (!cleaned)
            throw new IOException(
                "Native Google live smoke completed but test-folder cleanup could not be verified");
        Console.WriteLine("LIVE_DIRECT_API_SMOKE_PASS totalMs=" +
            total.ElapsedMilliseconds);
    }
}
