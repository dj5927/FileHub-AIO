using System.Net;

namespace NASMount.LockBridge;

/// <summary>
/// Two independent FileHub clients competing for the same WebDAV workbook.
/// Uses the actual cooperative server-side MKCOL/owner marker, not a local
/// mock lease. Only touches a unique loopback test folder.
/// </summary>
internal static class DirectWebDavLeaseLab
{
    internal static void AssertOfficeEditBlocked(Uri server, string relativeName)
    {
        if (!server.IsLoopback || server.Scheme != Uri.UriSchemeHttp)
            throw new ArgumentException("Office edit probe only runs on isolated loopback");
        string normalized = DirectWebDavReadOnlyBackend.Normalize(relativeName)
            .TrimStart('/');
        if (!WriteIntent.IsDocument(normalized))
            throw new ArgumentException("Office lock probe requires a document path");
        var profile = new NasProfile {
            Protocol = "WebDAV", Host = server.Host, Port = server.Port,
            Share = "", WebDavUseHttp = true, IsAnonymous = true
        };
        var rival = new LeaseCoordinator(profile, "", TimeSpan.Zero);
        bool acquired = false;
        try { rival.AddHandle(normalized); acquired = true; }
        catch (CooperativeLocks.LockConflictException)
        {
            Console.WriteLine("DIRECT_WEBDAV_LIVE_EXCEL_SECOND_PC_EDIT_BLOCKED_PASS");
            return;
        }
        finally { if (acquired) rival.ReleaseHandle(normalized); }
        throw new IOException("Second FileHub editor acquired live Excel document lock");
    }

    internal static void Run(Uri server)
    {
        if (!server.IsLoopback || server.Scheme != "http")
            throw new ArgumentException("Lease laboratory must use loopback HTTP");
        string folder = "FileHubLeaseLab_" + Guid.NewGuid().ToString("N");
        string filename = folder + "/11wol.xlsx";
        var profile = new NasProfile {
            Protocol = "WebDAV", Host = server.Host, Port = server.Port,
            Share = "", WebDavUseHttp = true, IsAnonymous = true
        };
        using var backend = new DirectWebDavReadOnlyBackend(server);
        string scratch = Path.Combine(Path.GetTempPath(),
            "FileHubLeaseScratch_" + Guid.NewGuid().ToString("N"));
        using var writes = new DirectWebDavWriteStore(server, backend, scratch);
        writes.RequireExclusiveLockDirectory();
        writes.MakeDirectory(folder);
        var first = new LeaseCoordinator(profile, "", TimeSpan.Zero);
        var second = new LeaseCoordinator(profile, "", TimeSpan.Zero);
        bool firstOwned = false;
        bool secondOwned = false;
        try
        {
            first.AddHandle(filename);
            firstOwned = true;
            Console.WriteLine("DIRECT_WEBDAV_LEASE_FIRST_CLIENT_ACQUIRED_PASS");
            bool conflict = false;
            try { second.AddHandle(filename); secondOwned = true; }
            catch (CooperativeLocks.LockConflictException) { conflict = true; }
            if (!conflict)
                throw new IOException("Second FileHub client took existing document lock");
            Console.WriteLine("DIRECT_WEBDAV_LEASE_SECOND_CLIENT_CONFLICT_PASS");
            first.ReleaseHandle(filename);
            firstOwned = false;
            bool reacquired = false;
            for (int i = 0; i < 24; i++)
            {
                Thread.Sleep(250);
                try { second.AddHandle(filename); secondOwned = true; reacquired = true; break; }
                catch (CooperativeLocks.LockConflictException) { }
                // A mock/NAS WebDAV server can acknowledge DELETE of the
                // lock folder before its MKCOL namespace cache observes
                // the removal. During that short window MKCOL returns
                // 405 while owner.json already returns 404. Keep this
                // *test-only* retry bounded; production Acquire still
                // fails closed and never ignores an unknown lock owner.
                catch (IOException ex) when (
                    ex.Message.Contains("HTTP 405", StringComparison.Ordinal)) { }
            }
            if (!reacquired)
                throw new IOException("Cooperative lock did not release safely");
            Console.WriteLine("DIRECT_WEBDAV_LEASE_RELEASE_REACQUIRE_PASS");
            second.ReleaseHandle(filename);
            secondOwned = false;
            Thread.Sleep(1500);
        }
        finally
        {
            if (firstOwned) first.ReleaseHandle(filename);
            if (secondOwned) second.ReleaseHandle(filename);
            using var http = new HttpClient();
            using var response = http.DeleteAsync(writes.Remote(folder)).GetAwaiter().GetResult();
            if (!response.IsSuccessStatusCode && response.StatusCode != HttpStatusCode.NotFound)
                throw new IOException("Lab folder cleanup HTTP " + (int)response.StatusCode);
        }
        Console.WriteLine("DIRECT_WEBDAV_COOPERATIVE_TWO_CLIENT_LAB_PASS");
    }
}
