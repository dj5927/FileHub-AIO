// NASMount locking passthrough. Based on the official WinFsp passthrough-dotnet
// sample (separate GPLv3 component; original copyright retained in Passthrough.cs).
// This executable MUST NOT be used for production Office edits until two-client
// Office/WinFsp/rclone VFS tests, token refresh and crash recovery are validated.
using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Fsp;
using Fsp.Interop;
using passthrough;
using FspFileInfo = Fsp.Interop.FileInfo;

namespace NASMount.LockBridge;

internal static class Program
{
    public static int Main(string[] args)
    {
        try
        {
            if (args.Length == 2 && args[0] == "--webdav-tls-readonly-probe")
            {
                // A diagnostic for the exact user-supplied HTTPS endpoint.
                // Send only a credential-free HTTP OPTIONS, never WebDAV
                // MKCOL/PUT/DELETE/LOCK. Any HTTP status means TLS succeeded.
                if (!Uri.TryCreate(args[1], UriKind.Absolute, out Uri? url) ||
                    url.Scheme != Uri.UriSchemeHttps ||
                    url.UserInfo.Length > 0 || url.Host.Length == 0)
                    throw new ArgumentException("An HTTPS WebDAV URL is required");
                var probeProfile = new NasProfile
                {
                    Protocol = "WebDAV", Host = url.Host, Port = url.Port
                };
                using var http = WebDavTlsPolicy.CreateClient(probeProfile,
                    TimeSpan.FromSeconds(16), allowRedirect: false);
                using var response = http.Send(
                    new HttpRequestMessage(HttpMethod.Options, url));
                Console.WriteLine("WEBDAV_TLS_READONLY_PROBE_PASS " +
                    "host=" + url.Host + " port=" + url.Port +
                    " httpStatus=" + (int)response.StatusCode +
                    " noCredentials=true noWrites=true");
                return 0;
            }
            if (args.Length == 4 && args[0] == "--local-bridge-test")
            {
                if (!Uri.TryCreate(args[3], UriKind.Absolute, out var uri) ||
                    !uri.IsLoopback || uri.Scheme != "http" ||
                    args[1].Length != 3 || args[1][1] != ':' ||
                    args[2].Length != 2 || args[2][1] != ':')
                    throw new ArgumentException("Local bridge test requires loopback WebDAV and drive letters");
                var p = new NasProfile { Protocol = "WebDAV", Host = "127.0.0.1", Port = uri.Port };
                // Explicit, loopback-only integration mode: exercise the
                // exact production WebDAV cooperative marker path with Excel.
                // The normal lab continues using synthetic in-memory leases.
                bool visibleOwnerLab = Environment.GetEnvironmentVariable(
                    "NASMOUNT_LAB_REAL_VISIBLE_OWNER") == "1";
                p.WebDavUseHttp = true;
                int testRcPort = 0;
                bool haveRc = int.TryParse(
                    Environment.GetEnvironmentVariable("NASMOUNT_LAB_RC_PORT"),
                    out testRcPort);
                bool testUploadQueue = visibleOwnerLab && haveRc;
                string labRcUser = Environment.GetEnvironmentVariable("NASMOUNT_LAB_RC_USER") ?? "";
                string labRcPass = Environment.GetEnvironmentVariable("NASMOUNT_LAB_RC_PASS") ?? "";
                var labLeases = new LeaseCoordinator(p, "", TimeSpan.Zero,
                    uploadSafe: testUploadQueue
                        ? () => BridgeUploadGate.Check(testRcPort, labRcUser, labRcPass)
                        : () => true,
                    localLab: !visibleOwnerLab,
                    expediteAfterClose: testUploadQueue
                        ? path => BridgeUploadGate.ExpediteClosedWorkbook(
                            testRcPort, labRcUser, labRcPass, path) : null);
                using var labAtomic = new ExcelAtomicBridge(args[1], uri);
                var labBackend = new LockingPassthrough(args[1], labLeases, labAtomic);
                using var labFs = new FileSystemHost(labBackend);
                labFs.FileSystemName = "NTFS";
                int labMounted = labFs.Mount(args[2], null, true);
                if (labMounted < 0) throw new IOException("Local bridge mount failed " + labMounted);
                bool testPolling = haveRc &&
                    Environment.GetEnvironmentVariable("NASMOUNT_LAB_REMOTE_POLL") == "1";
                using var monitor = testPolling
                    ? new RemoteDirectoryChangeMonitor(labFs, p, "", args[1],
                        testRcPort, labRcUser, labRcPass,
                        labBackend.InvalidateExecutableMetadata)
                    : null;
                labBackend.DirectoryMonitor = monitor;
                Console.WriteLine("LOCAL_ATOMIC_BRIDGE_READY " + args[2]);
                Console.Out.Flush();
                Thread.Sleep(Timeout.Infinite);
                return 0;
            }
            if ((args.Length == 3 || args.Length == 4) && args[0] == "--private-backend-bridge-lab")
            {
                // Bounded lab-only bridge: caller mounts a local rclone
                // source to a private directory, then this exposes it on one
                // temporary network letter. No NAS credentials or config.
                string sourcePath = Path.GetFullPath(args[1]);
                string targetLetter = args[2].TrimEnd(':').ToUpperInvariant();
                if (targetLetter.Length != 1 || targetLetter[0] < 'D' || targetLetter[0] > 'Z' ||
                    !sourcePath.StartsWith(MountPresentation.BackendParent + "\\",
                        StringComparison.OrdinalIgnoreCase) ||
                    !Directory.Exists(sourcePath))
                    throw new ArgumentException("Unsafe local bridge laboratory arguments");
                var sample = new NasProfile { Protocol = "WebDAV", Host = "127.0.0.1",
                    Id = args.Length == 4 ? Guid.Parse(args[3]) : Guid.NewGuid(),
                    DisplayName = "윈텍", Letter = targetLetter };
                var labLeases = new LeaseCoordinator(sample, "", TimeSpan.Zero,
                    uploadSafe: () => true, localLab: true);
                using var lab = new FileSystemHost(new LockingPassthrough(sourcePath,
                    labLeases, null, MountPresentation.SafeLabel(sample.DisplayName)));
                lab.FileSystemName = "NTFS";
                lab.Prefix = MountPresentation.WinFspPrefix(sample);
                int result = lab.Mount(targetLetter + ":", null, true);
                if (result < 0) throw new IOException($"Local network bridge failed 0x{result:X8}");
                Console.WriteLine("PRIVATE_BRIDGE_LAB_READY " + targetLetter + ":");
                Console.Out.Flush();
                Thread.Sleep(30000);
                return 0;
            }
            if (args.Length == 1 && args[0] == "--network-presentation-test")
            {
                // Local-only WinFsp.Net smoke test. Does not contact NAS or
                // alter another application's existing drive mappings.
                string? free = Enumerable.Range(0, 23)
                    .Select(i => ((char)('Z' - i)).ToString())
                    .FirstOrDefault(x => !Environment.GetLogicalDrives().Any(d =>
                        d.StartsWith(x + ":", StringComparison.OrdinalIgnoreCase)));
                if (free is null) throw new IOException("No free local test drive letter");
                var sample = new NasProfile
                {
                    Id = Guid.NewGuid(), Protocol = "WebDAV", DisplayName = "윈텍",
                    Host = "127.0.0.1", Letter = free
                };
                string localSource = Path.Combine(AppContext.BaseDirectory, "network-qa-local-fixture");
                Directory.CreateDirectory(localSource);
                var coordinator = new LeaseCoordinator(sample, "", TimeSpan.Zero,
                    uploadSafe: () => true, localLab: true);
                using (var localFs = new FileSystemHost(new LockingPassthrough(localSource,
                    coordinator, null, MountPresentation.SafeLabel(sample.DisplayName))))
                {
                    localFs.FileSystemName = "NTFS";
                    localFs.Prefix = MountPresentation.WinFspPrefix(sample);
                    int mountStatus = localFs.Mount(free + ":", null, true);
                    if (mountStatus < 0)
                        throw new IOException($"WinFsp network mount failed 0x{mountStatus:X8}");
                    if (!localFs.CaseSensitiveSearch || !localFs.CasePreservedNames)
                        throw new IOException(
                            "WinFsp filename-case preserving flags were not set");
                    Thread.Sleep(300);
                    var previewDrive = new DriveInfo(free + ":\\");
                    if (previewDrive.DriveType != DriveType.Network)
                        throw new IOException($"Expected network drive, got {previewDrive.DriveType}");
                    if (previewDrive.VolumeLabel != "윈텍")
                        throw new IOException($"Expected label 윈텍, got '{previewDrive.VolumeLabel}'");
                    string[] entries = Directory.GetFileSystemEntries(free + ":\\");
                    Console.WriteLine("NETWORK_DIR_ENUM_PASS entries=" + entries.Length);
                    Console.WriteLine("NETWORK_DISK_SPACE total=" + previewDrive.TotalSize);
                    Console.WriteLine("NETWORK_PRESENTATION_TEST_PASS " + free + ": " +
                        previewDrive.DriveType + " " + previewDrive.VolumeLabel);
                }
                return 0;
            }
            if (args.Length == 3 && args[0] == "--atomic-lab-test")
            {
                if (!Uri.TryCreate(args[1], UriKind.Absolute, out var labUri) ||
                    !labUri.IsLoopback || labUri.Scheme != "http")
                    throw new ArgumentException("Atomic lab mode only permits loopback HTTP");
                using var client = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromMinutes(3) };
                using var labInput = File.OpenRead(args[2]);
                WebDavAtomicReplace.ReplaceAsync(client, labUri, "LabAtomic.xlsx", labInput)
                    .GetAwaiter().GetResult();
                Console.WriteLine("ATOMIC_WEBDAV_LAB_TEST_PASS");
                return 0;
            }
            if (args.Length == 1 && args[0] == "--filename-case-preservation-test")
            {
                string root = Path.Combine(Path.GetTempPath(),
                    "NASMountOfficeCase_" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(root);
                try
                {
                    File.WriteAllText(Path.Combine(root, "LabActual.xlsx"), "fixture");
                    File.WriteAllText(Path.Combine(root, "IntentionalUpper.XLSX"), "fixture");
                    using var caseAtomic = new ExcelAtomicBridge(root, new Uri("http://127.0.0.1:1/"));
                    const string backup = @"\1234ABCD.tmp";
                    string? existing = caseAtomic.CaptureOriginalCaseBeforeBackup(
                        @"\LABACTUAL.XLSX", backup);
                    if (existing != @"\LabActual.xlsx")
                        throw new IOException("Original workbook name casing was not observed");
                    caseAtomic.RecordSuccessfulBackupRename(
                        @"\LABACTUAL.XLSX", backup, existing);
                    string restored = caseAtomic.CasePreservedStagedDestination(
                        @"\87654321.tmp", @"\LABACTUAL.XLSX");
                    if (restored != @"\LabActual.xlsx")
                        throw new IOException("Excel replacement changed remote filename case");
                    caseAtomic.ForgetOriginalCase(restored);
                    if (caseAtomic.CasePreservedStagedDestination(
                            @"\1234ABCD.tmp", @"\NewCase.XLSX") != @"\NewCase.xlsx")
                        throw new IOException("New workbook extension is not lowercase");
                    string? intentionallyUpper = caseAtomic.CaptureOriginalCaseBeforeBackup(
                        @"\INTENTIONALUPPER.XLSX", @"\AAAABBBB.tmp");
                    caseAtomic.RecordSuccessfulBackupRename(
                        @"\INTENTIONALUPPER.XLSX", @"\AAAABBBB.tmp", intentionallyUpper);
                    if (caseAtomic.CasePreservedStagedDestination(
                            @"\BBBBCCCC.tmp", @"\INTENTIONALUPPER.XLSX") !=
                        @"\IntentionalUpper.XLSX")
                        throw new IOException("Intentional existing uppercase extension was changed");
                    if (caseAtomic.CasePreservedStagedDestination(
                            @"\normal.txt", @"\NORMAL.TXT") != @"\NORMAL.TXT")
                        throw new IOException("Unrelated non-Office rename was modified");
                    // Real Korean filenames must keep their exact remote
                    // spelling when Excel performs a variant .tmp backup.
                    File.WriteAllText(Path.Combine(root, "11월매출.xlsx"), "fixture");
                    string? koreanExisting = caseAtomic.CaptureOriginalCaseBeforeBackup(
                        @"\11월매출.XLSX", @"\ExcelBackup.tmp");
                    if (koreanExisting != @"\11월매출.xlsx")
                        throw new IOException("Korean workbook original case not captured");
                    caseAtomic.RecordSuccessfulBackupRename(
                        @"\11월매출.XLSX", @"\ExcelBackup.tmp", koreanExisting);
                    if (caseAtomic.CasePreservedStagedDestination(
                            @"\SavedWorkbook.tmp", @"\11월매출.XLSX") !=
                        @"\11월매출.xlsx")
                        throw new IOException("Korean .XLSX replacement not normalized");
                    caseAtomic.ForgetOriginalCase(@"\11월매출.xlsx");
                    if (caseAtomic.CasePreservedStagedDestination(
                            @"\AnotherTemp.tmp", @"\11월매출.XLSX") !=
                        @"\11월매출.xlsx")
                        throw new IOException("Existing .xlsx case fallback failed");
                    Console.WriteLine("OFFICE_FILENAME_CASE_PRESERVATION_POLICY_PASS");
                }
                finally { Directory.Delete(root, recursive: true); }
                return 0;
            }
            if (args.Length == 1 && args[0] == "--self-test")
            {
                Ptfs.ExecutableOpenPolicyTest();
                ExecutableSecurityMetadataCache.PolicyTest();
                MissingMuiLookupCache.PolicyTest();
                WebDavTlsPolicy.SelfTest();
                if (LockingPassthrough.LeaseFailureStatus(
                        new HttpRequestException("TLS certificate mismatch")) ==
                        LockingPassthrough.STATUS_SHARING_VIOLATION ||
                    LockingPassthrough.LeaseFailureStatus(
                        new CooperativeLocks.LockConflictException("proven lock")) !=
                        LockingPassthrough.STATUS_SHARING_VIOLATION)
                    throw new IOException(
                        "Lock conflict and WebDAV transport failure must have distinct statuses");
                Console.WriteLine("LOCKBRIDGE_LOCK_CONFLICT_VS_TLS_ERROR_POLICY_PASS");
                var plain = new NasProfile
                {
                    Protocol = "WebDAV", Host = "localhost", Port = 5000,
                    WebDavUseHttp = true
                };
                if (ExcelAtomicBridge.ProfileRoot(plain).Scheme != "http") return 3;
                plain.WebDavUseHttp = false;
                if (ExcelAtomicBridge.ProfileRoot(plain).Scheme != "https") return 4;
                if (!WriteIntent.IsDocument("\\test\\workbook.xlsx") ||
                    WriteIntent.IsDocument("\\test\\notes.txt") ||
                    WriteIntent.IsDocument("\\test\\~$workbook.xlsx") ||
                    !WriteIntent.IsWrite(2) || !WriteIntent.IsWrite(0x10000000u) ||
                    WriteIntent.IsWrite(1))
                    return 2;
                LocalWorkbookSaveNotificationGuard.PolicyTest();
                Console.WriteLine("LOCKBRIDGE_SELF_TEST_PASS");
                return 0;
            }
            if (args.Length == 1 && args[0] == "--release-retry-test")
            {
                // Offline: two transient remote UNLOCK failures then success.
                int releaseCalls = 0;
                var retryProfile = new NasProfile { Protocol = "WebDAV",
                    Host = "127.0.0.1", Letter = "Z" };
                var coordinator = new LeaseCoordinator(retryProfile, "",
                    TimeSpan.Zero, uploadSafe: () => true, localLab: true,
                    releaseTestHook: _ =>
                    {
                        if (Interlocked.Increment(ref releaseCalls) <= 2)
                            throw new IOException("Simulated transient connection loss");
                    });
                coordinator.AddHandle("11월매출.xlsx");
                coordinator.ReleaseHandle("11월매출.xlsx");
                var timeout = System.Diagnostics.Stopwatch.StartNew();
                while (!coordinator.CanShutdown &&
                    timeout.Elapsed < TimeSpan.FromSeconds(8))
                    Thread.Sleep(30);
                if (!coordinator.CanShutdown || releaseCalls != 3)
                    throw new IOException("Transient release failure stranded the lease: " +
                        releaseCalls);
                Console.WriteLine("LOCKBRIDGE_TRANSIENT_RELEASE_RETRY_PASS count=" +
                    releaseCalls);
                return 0;
            }
            if (args.Length == 1 && args[0] == "--workbook-guard-test")
            {
                string dir = Path.Combine(Path.GetTempPath(),
                    "NASMountWorkbookGuard_" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(dir);
                try
                {
                    string path = Path.Combine(dir, "staged.tmp");
                    void MustReject(byte[] bytes, string target)
                    {
                        File.WriteAllBytes(path, bytes);
                        bool rejected = false;
                        try { _ = WorkbookPayloadGuard.Inspect(path, target); }
                        catch (InvalidDataException) { rejected = true; }
                        if (!rejected) throw new IOException("Unsafe workbook accepted: " + target);
                    }
                    MustReject([], "test.xls");
                    MustReject(new byte[2048], "test.xls");
                    MustReject([], "test.xlsx");
                    MustReject(System.Text.Encoding.ASCII.GetBytes("PK\x03\x04"), "test.xlsx");
                    File.WriteAllBytes(path, [0xD0,0xCF,0x11,0xE0,0xA1,0xB1,0x1A,0xE1]);
                    MustReject(File.ReadAllBytes(path), "test.xlsx");
                    // A syntactically correct Excel .xlsx container passes.
                    using (var stream = File.Create(path))
                    using (var zip = new System.IO.Compression.ZipArchive(stream,
                               System.IO.Compression.ZipArchiveMode.Create))
                    {
                        foreach (var name in new[] {"[Content_Types].xml", "xl/workbook.xml"})
                        {
                            var entry = zip.CreateEntry(name, System.IO.Compression.CompressionLevel.NoCompression);
                            using var writer = new StreamWriter(entry.Open());
                            writer.Write("<root>" + new string('a', 1400) + "</root>");
                        }
                    }
                    var good = WorkbookPayloadGuard.Inspect(path, "test.xlsx");
                    if (good.Length <= 0 || good.Hash.Length != 64)
                        throw new IOException("Valid xlsx inspection failed");
                    byte[] validOle = new byte[2048];
                    byte[] oleHeader = [0xD0,0xCF,0x11,0xE0,0xA1,0xB1,0x1A,0xE1];
                    oleHeader.CopyTo(validOle, 0);
                    File.WriteAllBytes(path, validOle);
                    if (WorkbookPayloadGuard.Inspect(path, "test.xls").Length != 2048)
                        throw new IOException("Valid XLS header inspection failed");
                    Console.WriteLine("WORKBOOK_ZERO_BYTE_GUARD_TEST_PASS");
                }
                finally { Directory.Delete(dir, true); }
                return 0;
            }
            if (args.Length == 1 && args[0] == "--blocked-excel-rename-test")
            {
                string dir = Path.Combine(Path.GetTempPath(),
                    "NASMountBlockedRename_" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(dir);
                try
                {
                    byte[] originalBytes = new byte[2048];
                    new byte[] {0xD0,0xCF,0x11,0xE0,0xA1,0xB1,0x1A,0xE1}
                        .CopyTo(originalBytes, 0);
                    string original = Path.Combine(dir, "Original.xls");
                    string staged = Path.Combine(dir, "1234ABCD.tmp");
                    File.WriteAllBytes(original, originalBytes);
                    File.WriteAllBytes(staged, []);
                    var blockedRenameProfile = new NasProfile {
                        Protocol = "WebDAV", Host = "127.0.0.1", WebDavUseHttp = true
                    };
                    var coordinator = new LeaseCoordinator(blockedRenameProfile, "", TimeSpan.Zero,
                        uploadSafe: () => true, localLab: true);
                    using var blockedRenameAtomic = new ExcelAtomicBridge(dir,
                        new Uri("http://127.0.0.1:1/"));
                    var blockedRenameFs = new LockingPassthrough(dir, coordinator, blockedRenameAtomic);
                    int open = blockedRenameFs.Open(@"\1234ABCD.tmp", 0, 2, out var node,
                        out var desc, out var info, out var normalized);
                    if (open < 0 || desc is null)
                        throw new IOException("Could not open empty staged test file");
                    try
                    {
                        int result = blockedRenameFs.Rename(node, desc,
                            @"\1234ABCD.tmp", @"\Original.xls", true);
                        if (result >= 0)
                            throw new IOException("0-byte workbook replacement was accepted");
                        if (!File.ReadAllBytes(original).AsSpan().SequenceEqual(originalBytes))
                            throw new IOException("Original workbook changed on rejected rename");
                        if (!File.Exists(staged))
                            throw new IOException("Rejected temporary source was deleted");
                        Console.WriteLine("EXCEL_ZERO_BYTE_RENAME_BLOCKED_ORIGINAL_PRESERVED_PASS");
                    }
                    finally { blockedRenameFs.Close(node, desc); }
                }
                finally { Directory.Delete(dir, true); }
                return 0;
            }
            if (args.Length == 1 && args[0] == "--callback-lock-test")
            {
                // Local-only callback test: no WinFsp mount, no network
                // credentials, no NAS paths. Simulate Windows file-open flags.
                string root = Path.Combine(Path.GetTempPath(),
                    "NASMountBridgeLockCallback_" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(root);
                try
                {
                    File.WriteAllText(Path.Combine(root, "workbook.xlsx"), "local-test-only");
                    var p = new NasProfile { Protocol = "WebDAV",
                        Host = "127.0.0.1", WebDavUseHttp = true };
                    var callbackLeases = new LeaseCoordinator(p, "", TimeSpan.Zero,
                        uploadSafe: () => true, localLab: true);
                    var callbackFs = new LockingPassthrough(root, callbackLeases);
                    int status = callbackFs.Open(@"\workbook.xlsx", 0, 2, out var node,
                        out var handle, out var info, out var normalized);
                    if (status < 0 || handle is null)
                        throw new IOException("Write-open callback failed " + status);
                    if (callbackLeases.ActiveHandles != 1)
                        throw new IOException("Writable workbook open did not acquire a lease");
                    callbackFs.Close(node, handle);
                    if (!SpinWait.SpinUntil(() => callbackLeases.CanShutdown, 4000))
                        throw new IOException("Lease was not released on file close");
                    status = callbackFs.Open(@"\workbook.xlsx", 0, 1, out node,
                        out handle, out info, out normalized);
                    if (status < 0 || handle is null)
                        throw new IOException("Read-open callback failed " + status);
                    if (callbackLeases.ActiveHandles != 0)
                        throw new IOException("Read-only open unexpectedly acquired write lease");
                    callbackFs.Close(node, handle);
                    Console.WriteLine("LOCKBRIDGE_WRITE_CALLBACK_TEST_PASS");
                }
                finally
                {
                    Directory.Delete(root, recursive: true);
                }
                return 0;
            }
            if (args.Length == 1 && args[0] == "--owner-visibility-test")
            {
                // Exercise the real WinFsp callback wrappers with disposable
                // Windows Hidden+System files on a local temp directory.
                string root = Path.Combine(Path.GetTempPath(),
                    "NASMountOwnerVisibility_" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(root);
                try
                {
                    string owner = Path.Combine(root, "~$test.xlsx");
                    string hiddenOther = Path.Combine(root, "other.xlsx");
                    File.WriteAllText(owner, "office-owned-marker");
                    File.WriteAllText(hiddenOther, "other");
                    File.SetAttributes(owner, FileAttributes.Hidden | FileAttributes.System);
                    File.SetAttributes(hiddenOther, FileAttributes.Hidden);
                    var visibilityLeases = new LeaseCoordinator(new NasProfile
                    {
                        Protocol = "WebDAV", Host = "127.0.0.1"
                    }, "", TimeSpan.Zero, uploadSafe: () => true, localLab: true);
                    var callback = new LockingPassthrough(root, visibilityLeases);
                    if (callback.Open(@"\", 0, 1, out var node, out var handle,
                            out var info, out _) < 0)
                        throw new IOException("Could not enumerate local test folder");
                    try
                    {
                        object context = null;
                        bool sawOwner = false, sawNormalHidden = false;
                        for (int n = 0; n < 20; n++)
                        {
                            if (!callback.ReadDirectoryEntry(node, handle, null, null,
                                    ref context, out string filename, out var entry)) break;
                            uint hiddenBits = (uint)(FileAttributes.Hidden | FileAttributes.System);
                            if (filename == "~$test.xlsx")
                            {
                                if ((entry.FileAttributes & hiddenBits) != 0)
                                    throw new IOException("Office owner still hidden in WinFsp listing");
                                sawOwner = true;
                            }
                            if (filename == "other.xlsx")
                            {
                                if ((entry.FileAttributes & (uint)FileAttributes.Hidden) == 0)
                                    throw new IOException("Unrelated hidden files must remain hidden");
                                sawNormalHidden = true;
                            }
                        }
                        if (!sawOwner || !sawNormalHidden)
                            throw new IOException("WinFsp did not enumerate both test files");
                    }
                    finally { callback.Close(node, handle); }
                    if (callback.Open(@"\~$test.xlsx", 0, 1,
                            out node, out handle, out info, out _) < 0)
                        throw new IOException("Could not open visible Office owner");
                    try
                    {
                        uint hiddenBits = (uint)(FileAttributes.Hidden | FileAttributes.System);
                        if ((info.FileAttributes & hiddenBits) != 0)
                            throw new IOException("Office owner open result is hidden");
                        if (callback.GetFileInfo(node, handle, out info) < 0 ||
                            (info.FileAttributes & hiddenBits) != 0)
                            throw new IOException("Office owner handle metadata reverted to hidden");
                    }
                    finally { callback.Close(node, handle); }
                    byte[] sd = null;
                    if (callback.GetSecurityByName(@"\~$test.xlsx", out var fileAttributes, ref sd) < 0 ||
                        (fileAttributes & (uint)(FileAttributes.Hidden | FileAttributes.System)) != 0)
                        throw new IOException("Explorer GetSecurityByName still hides Office owner");
                    if ((File.GetAttributes(owner) & (FileAttributes.Hidden | FileAttributes.System)) !=
                        (FileAttributes.Hidden | FileAttributes.System))
                        throw new IOException("Real owner attributes changed; presentation must be read-only");
                    Console.WriteLine("LOCKBRIDGE_OWNER_VISIBLE_TEST_PASS");
                }
                finally { Directory.Delete(root, recursive: true); }
                return 0;
            }
            if (args.Length == 1 && args[0] == "--local-ftp-lease-test")
            {
                var testProfile = new NasProfile {
                    Protocol="FTP",Host="127.0.0.1",Port=21219,Share="",
                    Username="testuser",ExplicitTls=false,Letter="R"
                };
                var a = new LeaseCoordinator(testProfile,"testpass_local_only",TimeSpan.FromMilliseconds(400));
                var b = new LeaseCoordinator(testProfile,"testpass_local_only",TimeSpan.FromMilliseconds(400));
                string name=".nasmount_bridge_"+Guid.NewGuid().ToString("N")+".xlsx";
                a.AddHandle(name); a.AddHandle(name);
                bool denied=false;
                try { b.AddHandle(name); } catch (Exception) { denied=true; }
                if (!denied) throw new InvalidOperationException("Second client modified locked document");
                a.ReleaseHandle(name);
                if (a.ActiveHandles!=1) throw new InvalidOperationException("Lease released prematurely");
                a.ReleaseHandle(name);
                Thread.Sleep(1800);
                if (!a.CanShutdown) throw new InvalidOperationException("Lease not released after close");
                b.AddHandle(name); b.ReleaseHandle(name);
                Thread.Sleep(1800);
                if (!b.CanShutdown) throw new InvalidOperationException("Second lease not released");
                Console.WriteLine("TWO_CLIENT_FTP_LEASE_TEST_PASS");
                return 0;
            }
            string Arg(string key)
            {
                int at = Array.IndexOf(args, key);
                if (at < 0 || at >= args.Length - 1)
                    throw new ArgumentException("Missing " + key);
                return args[at + 1];
            }
            Guid id = Guid.Parse(Arg("--profile"));
            string source = Path.GetFullPath(Arg("--source"));
            string target = Arg("--target").Trim().ToUpperInvariant();
            if (target.Length != 2 || target[1] != ':' ||
                target[0] < 'D' || target[0] > 'Z')
                throw new ArgumentException("Invalid mount letter");
            // The private backend is a mountpoint directory, never a second
            // public drive letter. Validate BOTH its location and profile ID.
            var profile = AppStore.Load().Profiles.SingleOrDefault(x => x.Id == id)
                ?? throw new InvalidOperationException("Profile not found");
            if (!MountPresentation.IsOurBackendMountpoint(profile, source))
                throw new UnauthorizedAccessException("Unexpected private backend path");
            if (!Directory.Exists(source))
                throw new DirectoryNotFoundException("rclone backend is not mounted");

            string password = profile.IsAnonymous ? "" :
                Environment.GetEnvironmentVariable("NASMOUNT_BRIDGE_PASSWORD")
                ?? WindowsCredentials.Read(id)
                ?? throw new InvalidOperationException("Saved NAS password unavailable");
            Environment.SetEnvironmentVariable("NASMOUNT_BRIDGE_PASSWORD", null);
            int rcPort = int.Parse(Environment.GetEnvironmentVariable("NASMOUNT_RC_PORT")
                ?? throw new InvalidOperationException("Missing rclone RC port"));
            string rcUser = Environment.GetEnvironmentVariable("NASMOUNT_RC_USER")
                ?? throw new InvalidOperationException("Missing rclone RC username");
            string rcPass = Environment.GetEnvironmentVariable("NASMOUNT_RC_PASS")
                ?? throw new InvalidOperationException("Missing rclone RC password");
            Environment.SetEnvironmentVariable("NASMOUNT_RC_PASS", null);
            NativeWebDavLockClient? nativeClient = null;
            if (profile.UseNativeWebDavLocks)
            {
                string controlUrl = Environment.GetEnvironmentVariable(
                    "NASMOUNT_NATIVE_LOCK_CONTROL_URL") ??
                    throw new IOException("Native WebDAV gateway required");
                string secretToken = Environment.GetEnvironmentVariable(
                    "NASMOUNT_NATIVE_LOCK_CONTROL_SECRET") ??
                    throw new IOException("Native WebDAV gateway credential missing");
                nativeClient = new NativeWebDavLockClient(controlUrl, secretToken);
            }
            Environment.SetEnvironmentVariable(
                "NASMOUNT_NATIVE_LOCK_CONTROL_SECRET", null);
            var leases = new LeaseCoordinator(profile, password,
                uploadSafe: () => BridgeUploadGate.Check(rcPort, rcUser, rcPass),
                expediteAfterClose: path => BridgeUploadGate.ExpediteClosedWorkbook(
                    rcPort, rcUser, rcPass, path),
                nativeClient: nativeClient);
            using var atomic = profile.Protocol == "WebDAV"
                ? new ExcelAtomicBridge(source, ExcelAtomicBridge.ProfileRoot(profile),
                    profile.IsAnonymous ? null : profile.Username,
                    profile.IsAnonymous ? "" : password, (rcPort, rcUser, rcPass),
                    profile) : null;
            var passthrough = new LockingPassthrough(source, leases, atomic,
                MountPresentation.SafeLabel(profile.DisplayName));
            using var fs = new FileSystemHost(passthrough);
            fs.FileSystemName = "NTFS";
            // WinFsp.Net (network share), not WinFsp.Disk (local disk).
            // The first component is unique per profile and the second is the
            // user's display name, e.g. \\NASMount-12345678\Wintec.
            fs.Prefix = MountPresentation.WinFspPrefix(profile);
            fs.CasePreservedNames = true;
            fs.UnicodeOnDisk = true;
            int mounted = fs.Mount(target, null, true);
            if (mounted < 0)
                throw new IOException($"WinFsp lock bridge mount failed status=0x{mounted:X8}");
            using var directoryMonitor = profile.Protocol == "WebDAV"
                ? new RemoteDirectoryChangeMonitor(fs, profile, password,
                    source, rcPort, rcUser, rcPass,
                    passthrough.InvalidateExecutableMetadata)
                : null;
            passthrough.DirectoryMonitor = directoryMonitor;
            AppStore.Log("LOCKBRIDGE READY " + id + " " + target);
            Console.WriteLine("LOCKBRIDGE_READY " + target);
            Console.Out.Flush();

            // Graceful stop requests are sent by the manager. Never unmount
            // while file handles/leases are active.
            string pipeName = "NASMountBridge_" + id.ToString("N");
            while (true)
            {
                using var pipe = new NamedPipeServerStream(pipeName, PipeDirection.InOut,
                    1, PipeTransmissionMode.Byte, PipeOptions.None);
                pipe.WaitForConnection();
                using var reader = new StreamReader(pipe, leaveOpen: true);
                using var writer = new StreamWriter(pipe, leaveOpen: true) { AutoFlush = true };
                string command = reader.ReadLine() ?? "";
                if (command == "PING")
                {
                    writer.WriteLine("READY " + leases.ActiveHandles);
                    continue;
                }
                if (command == "QUIT")
                {
                    if (!leases.CanShutdown)
                    {
                        writer.WriteLine(leases.ActiveHandles > 0
                            ? "OPEN_HANDLES " + leases.ActiveHandles
                            : "UPLOAD_OR_LOCK_RELEASE_PENDING");
                        continue;
                    }
                    writer.WriteLine("OK");
                    break;
                }
                writer.WriteLine("UNKNOWN");
            }
            AppStore.Log("LOCKBRIDGE SAFE UNMOUNT " + id);
            return 0;
        }
        catch (Exception ex)
        {
            AppStore.Log("LOCKBRIDGE FAIL: " + ex);
            Console.Error.WriteLine(ex);
            return 1;
        }
    }
}

internal static class WriteIntent
{
    private static readonly HashSet<string> Office = new(StringComparer.OrdinalIgnoreCase)
    { ".xlsx", ".xlsm", ".xlsb", ".xls", ".docx", ".docm", ".doc", ".pptx",
      ".pptm", ".ppt", ".odt", ".ods", ".odp", ".hwp", ".hwpx" };

    internal static bool IsDocument(string path) =>
        Office.Contains(Path.GetExtension(path)) &&
        !Path.GetFileName(path).StartsWith("~$", StringComparison.Ordinal);

    internal static bool IsWrite(uint access) =>
        0 != (access & (0x2u | 0x4u | 0x10u | 0x10000u | 0x40000000u | 0x10000000u));
}

// Keep the original WinFsp passthrough implementation for all file operations
// except document opens/creates/renames/closes, which gain server-side leases.
internal sealed class LockingPassthrough : Ptfs
{
    // Only a proven lease conflict is an Excel "another user has it open"
    // situation. TLS, DNS, network and server errors are I/O failures,
    // NOT sharing violations.
    internal static int LeaseFailureStatus(Exception failure) =>
        failure is CooperativeLocks.LockConflictException
            ? STATUS_SHARING_VIOLATION : STATUS_UNEXPECTED_IO_ERROR;
    internal RemoteDirectoryChangeMonitor? DirectoryMonitor;
    private readonly LeaseCoordinator _coordinator;
    private readonly ExcelAtomicBridge? _atomic;
    private readonly string _displayLabel;
    private readonly Dictionary<object, string> _descriptors =
        new(ReferenceEqualityComparer.Instance);
    private readonly Dictionary<object, string> _ownerDescriptors =
        new(ReferenceEqualityComparer.Instance);
    private readonly Dictionary<object, string> _visibleOfficeOwners =
        new(ReferenceEqualityComparer.Instance);
    private readonly Dictionary<object, string> _executableReads =
        new(ReferenceEqualityComparer.Instance);
    private readonly ExecutableSecurityMetadataCache _exeMetadata = new();
    private readonly MissingMuiLookupCache _muiCache = new();
    private readonly object _sync = new();
    internal void InvalidateExecutableMetadata(string fileName) =>
        InvalidateBothExecutableCaches(fileName);

    private void InvalidateBothExecutableCaches(string name)
    {
        _exeMetadata.Invalidate(name);
        _muiCache.Invalidate(name);
    }
    internal LockingPassthrough(string source, LeaseCoordinator coordinator,
        ExcelAtomicBridge? atomic = null, string displayLabel = "NAS Mount") : base(source)
    {
        _coordinator = coordinator;
        _atomic = atomic;
        _displayLabel = displayLabel;
    }
    // Office creates the real ~$ file. Expose it visibly in NAS Mount without
    // changing the actual file attributes on the NAS or rclone backend.
    internal static bool IsOfficeOwnerFile(string name) =>
        Path.GetFileName(name).StartsWith("~$", StringComparison.OrdinalIgnoreCase) &&
        Path.GetExtension(name).ToLowerInvariant() is
            ".xlsx" or ".xlsm" or ".xlsb" or ".xls" or
            ".docx" or ".docm" or ".doc" or ".pptx" or ".pptm" or ".ppt";

    internal static uint VisibleOwnerAttributes(string name, uint attributes) =>
        IsOfficeOwnerFile(name)
            ? attributes & ~(uint)(System.IO.FileAttributes.Hidden | System.IO.FileAttributes.System)
            : attributes;

    public override int GetSecurityByName(string fileName, out uint fileAttributes,
        ref byte[] securityDescriptor)
    {
        bool mui = MissingMuiLookupCache.IsMui(fileName);
        string muiParent = mui ? (Path.GetDirectoryName(fileName) ?? "") : "";
        if (mui)
        {
            // The PE resource loader probes nonexistent locale directories
            // several times per EXE. Never send every .mui lookup across a
            // second WinFsp mount once the lower folder is proven absent.
            if (_muiCache.IsMissing(fileName) || _muiCache.IsMissing(muiParent))
            {
                fileAttributes = 0;
                return unchecked((int)0xC000003A); // STATUS_OBJECT_PATH_NOT_FOUND
            }
            if (muiParent.Length > 0 &&
                !_muiCache.IsKnownPresentParent(muiParent))
            {
                try
                {
                    var attrs = File.GetAttributes(ConcatPath(muiParent));
                    if ((attrs & FileAttributes.Directory) == 0)
                    {
                        _muiCache.Missing(muiParent);
                        fileAttributes = 0;
                        return unchecked((int)0xC000003A);
                    }
                    _muiCache.ParentExists(muiParent);
                }
                catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
                {
                    _muiCache.Missing(muiParent);
                    fileAttributes = 0;
                    return unchecked((int)0xC000003A);
                }
            }
        }
        bool executable = fileName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
            fileName.EndsWith(".dll", StringComparison.OrdinalIgnoreCase);
        bool needAcl = securityDescriptor is not null;
        if (executable && _exeMetadata.TryGet(fileName, needAcl,
                out fileAttributes, out var cachedAcl))
        {
            if (needAcl) securityDescriptor = cachedAcl!;
            return STATUS_SUCCESS;
        }
        long started = executable ? System.Diagnostics.Stopwatch.GetTimestamp() : 0;
        int status;
        try { status = base.GetSecurityByName(fileName, out fileAttributes, ref securityDescriptor); }
        catch (FileNotFoundException) when (mui)
        {
            _muiCache.Missing(fileName);
            fileAttributes = 0;
            return unchecked((int)0xC0000034); // STATUS_OBJECT_NAME_NOT_FOUND
        }
        catch (DirectoryNotFoundException) when (mui)
        {
            _muiCache.Missing(muiParent);
            fileAttributes = 0;
            return unchecked((int)0xC000003A);
        }
        if (executable && status >= 0)
            _exeMetadata.Store(fileName, fileAttributes, securityDescriptor);
        if (executable)
        {
            double ms = System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds;
            if (ms >= 300)
                AppStore.Log("EXECUTABLE_LOWER_SECURITY_METADATA_SLOW path=" + fileName +
                    " elapsedMs=" + ms.ToString("F1") + " status=" + status);
        }
        if (status >= 0) fileAttributes = VisibleOwnerAttributes(fileName, fileAttributes);
        return status;
    }

    public override int GetSecurity(object fileNode, object fileDesc,
        ref byte[] securityDescriptor)
    {
        string? name;
        lock (_sync)
            name = fileDesc is not null && _executableReads.TryGetValue(fileDesc, out var n)
                ? n : null;
        if (name is not null)
        {
            // A local EXE snapshot must expose the NAS ACL, not the ACL of
            // the local cache file. Name-based ACL lookups are coalesced.
            byte[] acl = Array.Empty<byte>();
            int result = GetSecurityByName(name, out _, ref acl);
            if (result >= 0) securityDescriptor = acl;
            return result;
        }
        return base.GetSecurity(fileNode, fileDesc, ref securityDescriptor);
    }

    public override bool ReadDirectoryEntry(object fileNode, object fileDesc,
        string pattern, string marker, ref object context,
        out string fileName, out FspFileInfo info)
    {
        if (context is null && fileDesc is Ptfs.FileDesc directory &&
            directory.DirInfo is not null)
            DirectoryMonitor?.ObserveDirectory(directory.DirInfo.FullName);
        bool found = base.ReadDirectoryEntry(fileNode, fileDesc, pattern, marker,
            ref context, out fileName, out info);
        if (found) info.FileAttributes = VisibleOwnerAttributes(fileName, info.FileAttributes);
        return found;
    }

    public override int GetFileInfo(object fileNode, object fileDesc, out FspFileInfo info)
    {
        int status = base.GetFileInfo(fileNode, fileDesc, out info);
        if (status < 0) return status;
        lock (_sync)
            if (fileDesc is not null && _visibleOfficeOwners.TryGetValue(fileDesc, out var name))
                info.FileAttributes = VisibleOwnerAttributes(name, info.FileAttributes);
        return status;
    }

    public override int GetVolumeInfo(out VolumeInfo volumeInfo)
    {
        int status = base.GetVolumeInfo(out volumeInfo);
        if (status >= 0) volumeInfo.SetVolumeLabel(_displayLabel);
        return status;
    }
    public override int Open(string fileName, uint createOptions, uint grantedAccess,
        out object fileNode, out object fileDesc, out FspFileInfo info, out string normalizedName)
    {
        bool executableRead = fileName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
            fileName.EndsWith(".dll", StringComparison.OrdinalIgnoreCase);
        long openedAt = executableRead ? System.Diagnostics.Stopwatch.GetTimestamp() : 0;
        if (fileName.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase) ||
            fileName.EndsWith(".xlsx", StringComparison.OrdinalIgnoreCase))
            AppStore.Log("TRACE OPEN " + fileName + " access=0x" + grantedAccess.ToString("X"));
        string key = WriteIntent.IsDocument(fileName) && WriteIntent.IsWrite(grantedAccess)
            ? fileName.TrimStart('\\').Replace('\\', '/') : "";
        bool leased = false;
        if (key.Length != 0)
        {
            try { _coordinator.AddHandle(key); leased = true; }
            catch (Exception ex)
            {
                AppStore.Log("LOCKBRIDGE OPEN DENIED " + key +
                    " kind=" + (ex is CooperativeLocks.LockConflictException
                        ? "LOCK_CONFLICT" : "REMOTE_IO_ERROR") +
                    " error=" + ex.Message);
                fileNode = fileDesc = null;
                info = default;
                normalizedName = null;
                return LeaseFailureStatus(ex);
            }
        }
        try
        {
            int status = base.Open(fileName, createOptions, grantedAccess,
                out fileNode, out fileDesc, out info, out normalizedName);
            if (executableRead)
                AppStore.Log("EXECUTABLE_VFS_OPEN_TIMING path=" + fileName +
                    " elapsedMs=" + System.Diagnostics.Stopwatch.GetElapsedTime(openedAt).TotalMilliseconds.ToString("F1") +
                    " status=" + status);
            if (status < 0)
            {
                if (leased) _coordinator.ReleaseHandle(key);
                return status;
            }
            if (leased) lock (_sync) _descriptors[fileDesc] = key;
            if (executableRead && fileDesc is not null)
                lock (_sync) _executableReads[fileDesc] = fileName;
            if (IsOfficeOwnerFile(fileName))
                lock (_sync) _visibleOfficeOwners[fileDesc] = fileName;
            info.FileAttributes = VisibleOwnerAttributes(fileName, info.FileAttributes);
            return status;
        }
        catch
        {
            if (leased) _coordinator.ReleaseHandle(key);
            throw;
        }
    }

    public override int Read(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, out uint transferred)
    {
        string? target;
        lock (_sync) _executableReads.TryGetValue(fileDesc, out target);
        if (target is null)
            return base.Read(fileNode, fileDesc, buffer, offset, length,
                out transferred);
        long started = System.Diagnostics.Stopwatch.GetTimestamp();
        try
        {
            int status = base.Read(fileNode, fileDesc, buffer, offset,
                length, out transferred);
            double ms = System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds;
            if (ms >= 500 || status < 0)
                AppStore.Log("EXECUTABLE_VFS_READ_TIMING path=" + target +
                    " offset=" + offset + " request=" + length +
                    " returned=" + transferred + " elapsedMs=" + ms.ToString("F1") +
                    " status=" + status);
            return status;
        }
        catch (Exception ex)
        {
            AppStore.Log("EXECUTABLE_VFS_READ_ERROR path=" + target +
                " elapsedMs=" + System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds.ToString("F1") +
                " type=" + ex.GetType().Name);
            throw;
        }
    }
    public override int Create(string fileName, uint createOptions, uint grantedAccess,
        uint fileAttributes, byte[] securityDescriptor, ulong allocationSize,
        out object fileNode, out object fileDesc, out FspFileInfo info, out string normalizedName)
    {
        InvalidateExecutableMetadata(fileName);
        AppStore.Log("TRACE CREATE " + fileName + " access=0x" + grantedAccess.ToString("X"));
        string key = WriteIntent.IsDocument(fileName) ?
            fileName.TrimStart('\\').Replace('\\', '/') : "";
        bool leased = false;
        if (key.Length != 0)
        {
            try { _coordinator.AddHandle(key); leased = true; }
            catch (Exception ex)
            {
                AppStore.Log("LOCKBRIDGE CREATE DENIED " + key +
                    " kind=" + (ex is CooperativeLocks.LockConflictException
                        ? "LOCK_CONFLICT" : "REMOTE_IO_ERROR") +
                    " error=" + ex.Message);
                fileNode = fileDesc = null;
                info = default;
                normalizedName = null;
                return LeaseFailureStatus(ex);
            }
        }
        try
        {
            int status = base.Create(fileName, createOptions, grantedAccess, fileAttributes,
                securityDescriptor, allocationSize, out fileNode, out fileDesc,
                out info, out normalizedName);
            if (status < 0 && leased) _coordinator.ReleaseHandle(key);
            else if (status >= 0)
            {
                if (leased) lock (_sync) _descriptors[fileDesc] = key;
                _atomic?.OnCreated(fileName);
                if (IsOfficeOwnerFile(fileName))
                    lock (_sync) _visibleOfficeOwners[fileDesc] = fileName;
                if (_atomic is not null && Path.GetFileName(fileName).StartsWith("~$", StringComparison.OrdinalIgnoreCase))
                {
                    lock (_sync) _ownerDescriptors[fileDesc] = fileName;
                    AppStore.Log("LOCKBRIDGE OFFICE_OWNER_CREATED " + fileName);
                }
            }
            if (status >= 0)
                info.FileAttributes = VisibleOwnerAttributes(fileName, info.FileAttributes);
            return status;
        }
        catch
        {
            if (leased) _coordinator.ReleaseHandle(key);
            throw;
        }
    }
    public override int Rename(object fileNode, object fileDesc, string fileName,
        string newFileName, bool replaceIfExists)
    {
        InvalidateExecutableMetadata(fileName);
        InvalidateExecutableMetadata(newFileName);
        AppStore.Log("TRACE RENAME " + fileName + " -> " + newFileName + " overwrite=" + replaceIfExists);
        if (Environment.GetEnvironmentVariable("NASMOUNT_FSP_TRACE") == "1")
            Console.Error.WriteLine("SAVELAB RENAME " + fileName + " -> " + newFileName +
                " overwrite=" + replaceIfExists);
        if (_atomic?.IsStagedWorkbookRename(fileName, newFileName) == true)
        {
            // The original may have been spelled LabActual.xlsx on the NAS
            // even if Excel requests a rename to LABACTUAL.XLSX. Restore the
            // captured spelling from the original-to-backup phase.
            string finalName = _atomic.CasePreservedStagedDestination(
                fileName, newFileName);
            if (!string.Equals(finalName, newFileName, StringComparison.Ordinal))
                AppStore.Log("OFFICE_FILENAME_CASE_PRESERVED " + newFileName +
                    " -> " + finalName);
            if (Environment.GetEnvironmentVariable("NASMOUNT_FSP_TRACE") == "1")
                Console.Error.WriteLine("SAVELAB PENDING_RENAME " + fileName + " -> " + finalName);
            string txnKey = finalName.TrimStart('\\').Replace('\\', '/');
            bool renameLease = false;
            try
            {
                _coordinator.AddHandle(txnKey);
                renameLease = true;
                // Excel has finished writing the staged workbook. Verify its
                // actual bytes before allowing any overwrite. The prior
                // direct WebDAV commit + rclone VFS writeback caused two
                // writers to overwrite the same original (0-byte incident).
                if (fileDesc is Ptfs.FileDesc descriptor)
                {
                    descriptor.Stream?.Flush(true);
                    descriptor.Stream?.Dispose();
                }
                var expected = _atomic.InspectStagedRename(finalName, fileName);
                // Excel itself has moved the old original to its backup TMP
                // through rclone. Never fake that backup rename as successful.
                // Preserve WinFsp's actual replace flag; do not force a second
                // writer or a local overwrite of a still-open workbook.
                int status = base.Rename(fileNode, fileDesc, fileName, finalName,
                    replaceIfExists);
                if (status < 0)
                {
                    AppStore.Log("EXCEL RCLONE RENAME FAILED " + txnKey + " status=" + status);
                    return status;
                }
                _atomic.MarkLowerRenameCompleted(finalName, fileName, expected);
                // The remote directory poller can otherwise report OUR OWN
                // Excel rename/upload to Windows as an external modification.
                // Save provenance is registered only after local integrity
                // validation; the poller must independently SHA-verify the
                // remote bytes before suppressing one matching notification.
                DirectoryMonitor?.RecordLocalWorkbookSave(finalName, expected);
                _atomic.ForgetOriginalCase(finalName);
                if (Environment.GetEnvironmentVariable("NASMOUNT_FSP_TRACE") == "1")
                    Console.Error.WriteLine("SAVELAB SINGLE_WRITER_RENAME_OK " + finalName);
                return STATUS_SUCCESS;
            }
            catch (Exception ex)
            {
                AppStore.Log("ATOMIC EXCEL SAVE FAILED " + txnKey + " " + ex);
                if (Environment.GetEnvironmentVariable("NASMOUNT_FSP_TRACE") == "1")
                    Console.Error.WriteLine("SAVELAB SINGLE_WRITER_RENAME_FAIL HR=0x" +
                        ex.HResult.ToString("X8") + " " + ex);
                return STATUS_UNEXPECTED_IO_ERROR;
            }
            finally { if (renameLease) _coordinator.ReleaseHandle(txnKey); }
        }
        // During Excel's OLD workbook -> backup.tmp rename the target is
        // not an Office file. Protect the SOURCE as well so its server-side
        // RFC4918 lock remains valid through the upload/copy/commit sequence.
        // The existing native lease waits for rclone's upload gate after Close.
        string sourceKey = WriteIntent.IsDocument(fileName) ?
            fileName.TrimStart('\\').Replace('\\', '/') : "";
        string key = WriteIntent.IsDocument(newFileName) ?
            newFileName.TrimStart('\\').Replace('\\', '/') : "";
        bool sourceLocked = false;
        if (sourceKey.Length > 0 &&
            !string.Equals(sourceKey, key, StringComparison.OrdinalIgnoreCase))
        {
            try { _coordinator.AddHandle(sourceKey); sourceLocked = true; }
            catch (Exception ex)
            {
                AppStore.Log("LOCKBRIDGE SOURCE RENAME DENIED " + sourceKey +
                    " kind=" + ex.GetType().Name);
                return LeaseFailureStatus(ex);
            }
        }
        if (key.Length != 0)
        {
            try { _coordinator.AddHandle(key); }
            catch (Exception ex)
            {
                if (sourceLocked) _coordinator.ReleaseHandle(sourceKey);
                AppStore.Log("LOCKBRIDGE RENAME DENIED " + key +
                    " kind=" + ex.GetType().Name);
                return LeaseFailureStatus(ex);
            }
        }
        // Capture the stored spelling while the existing file is present.
        // Record it only AFTER the ordinary rclone backup rename succeeds.
        string? originalCase = _atomic?.CaptureOriginalCaseBeforeBackup(
            fileName, newFileName);
        try
        {
            int status = base.Rename(fileNode, fileDesc, fileName, newFileName, replaceIfExists);
            if (status >= 0)
                _atomic?.RecordSuccessfulBackupRename(
                    fileName, newFileName, originalCase);
            if (Environment.GetEnvironmentVariable("NASMOUNT_FSP_TRACE") == "1")
                Console.Error.WriteLine("SAVELAB NORMAL_RENAME_STATUS " + status);
            return status;
        }
        finally
        {
            if (key.Length != 0) _coordinator.ReleaseHandle(key);
            if (sourceLocked) _coordinator.ReleaseHandle(sourceKey);
        }
    }
    public override void Close(object fileNode, object fileDesc)
    {
        string? key = null;
        string? owner = null;
        lock (_sync)
        {
            if (fileDesc is not null && _descriptors.Remove(fileDesc, out var value)) key = value;
            if (fileDesc is not null && _ownerDescriptors.Remove(fileDesc, out var ownerValue)) owner = ownerValue;
            if (fileDesc is not null) _visibleOfficeOwners.Remove(fileDesc);
            if (fileDesc is not null) _executableReads.Remove(fileDesc);
        }
        try { base.Close(fileNode, fileDesc); }
        finally
        {
            if (key is not null) _coordinator.ReleaseHandle(key);
            if (owner is not null)
                try { _atomic?.OwnerClosed(owner); }
                catch (Exception ex) { AppStore.Log("ATOMIC OWNER SYNC FAILED " + ex.Message); }
        }
    }

    public override int Write(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, bool writeToEndOfFile, bool constrainedIo,
        out uint transferred, out FspFileInfo info)
    {
        int status = base.Write(fileNode, fileDesc, buffer, offset, length,
            writeToEndOfFile, constrainedIo, out transferred, out info);
        if (status >= 0 && transferred > 0 && _atomic is not null)
        {
            string? owner = null;
            lock (_sync)
                if (fileDesc is not null && _ownerDescriptors.TryGetValue(fileDesc, out var name)) owner = name;
            if (owner is not null)
            {
                try
                {
                    if (fileDesc is Ptfs.FileDesc descriptor) descriptor.Stream?.Flush();
                    _atomic.OwnerClosed(owner);
                }
                catch (Exception ex) { AppStore.Log("ATOMIC OWNER IMMEDIATE SYNC FAILED " + ex); }
            }
        }
        return status;
    }

    public override void Cleanup(object fileNode, object fileDesc, string fileName, uint flags)
    {
        if ((flags & CleanupDelete) != 0) InvalidateExecutableMetadata(fileName);
        if ((flags & CleanupDelete) != 0) AppStore.Log("TRACE CLEANUP_DELETE " + fileName);
        // Single-writer rclone mode must not fake successful deletion of the
        // original. That previously left dirty virtual objects for a second
        // upload after the direct WebDAV commit.
        base.Cleanup(fileNode, fileDesc, fileName, flags);
        if (_atomic is not null && (flags & CleanupDelete) != 0 &&
            Path.GetFileName(fileName).StartsWith("~$", StringComparison.OrdinalIgnoreCase))
            try { _atomic.OwnerDeleted(fileName); }
            catch (Exception ex) { AppStore.Log("ATOMIC OWNER_DELETE FAILED " + ex.Message); }
    }
}

internal static class BridgeUploadGate
{
    // rclone RC vfs/queue-set-expiry promotes ONLY the workbook that has just
    // closed. It does not alter the global 15s write-back needed for Excel's
    // intermediate temporary files. Queue completion is still checked below.
    internal static bool ExpediteClosedWorkbook(int port, string user, string password,
        string relativePath)
    {
        try
        {
            using var client = new System.Net.Http.HttpClient {
                Timeout = TimeSpan.FromSeconds(3)
            };
            client.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Basic",
                    Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(
                        user + ":" + password)));
            using var queueResponse = client.PostAsync($"http://127.0.0.1:{port}/vfs/queue",
                new System.Net.Http.StringContent("{}")).GetAwaiter().GetResult();
            if (!queueResponse.IsSuccessStatusCode) return false;
            using var json = System.Text.Json.JsonDocument.Parse(
                queueResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult());
            if (!json.RootElement.TryGetProperty("queue", out var items) ||
                items.ValueKind != System.Text.Json.JsonValueKind.Array)
                return false;
            string target = relativePath.Replace('\\', '/').TrimStart('/');
            foreach (var item in items.EnumerateArray())
            {
                if (!item.TryGetProperty("name", out var name) ||
                    name.ValueKind != System.Text.Json.JsonValueKind.String ||
                    !string.Equals(name.GetString()?.Replace('\\', '/').TrimStart('/'),
                        target, StringComparison.OrdinalIgnoreCase))
                    continue;
                if (!item.TryGetProperty("id", out var id) ||
                    !id.TryGetInt64(out long handleId) || handleId <= 0)
                    return false;
                if (item.TryGetProperty("uploading", out var uploading) &&
                    uploading.ValueKind == System.Text.Json.JsonValueKind.True)
                    return true;
                string body = System.Text.Json.JsonSerializer.Serialize(
                    new { id = handleId, expiry = -1000000000 });
                using var response = client.PostAsync(
                    $"http://127.0.0.1:{port}/vfs/queue-set-expiry",
                    new System.Net.Http.StringContent(body,
                        System.Text.Encoding.UTF8, "application/json"))
                    .GetAwaiter().GetResult();
                if (!response.IsSuccessStatusCode) return false;
                AppStore.Log("LOCKBRIDGE CLOSED_FILE_UPLOAD_EXPEDITED " + target +
                    " queueId=" + handleId);
                return true;
            }
        }
        catch (Exception ex)
        {
            AppStore.Log("LOCKBRIDGE UPLOAD_EXPEDITE_SKIPPED " +
                relativePath + " " + ex.Message);
        }
        return false;
    }
    internal static bool Check(int port, string user, string password)
    {
        using var client = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(5) };
        client.DefaultRequestHeaders.Authorization =
            new System.Net.Http.Headers.AuthenticationHeaderValue("Basic",
                Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(user + ":" + password)));
        using var response = client.PostAsync($"http://127.0.0.1:{port}/vfs/stats",
            new System.Net.Http.StringContent("{}")).GetAwaiter().GetResult();
        if (!response.IsSuccessStatusCode) return false;
        using var json = System.Text.Json.JsonDocument.Parse(
            response.Content.ReadAsStringAsync().GetAwaiter().GetResult());
        if (!json.RootElement.TryGetProperty("diskCache", out var cache)) return false;
        foreach (var name in new[] { "uploadsQueued", "uploadsInProgress", "erroredFiles" })
            if (!cache.TryGetProperty(name, out var value) ||
                !value.TryGetInt32(out int count) || count != 0) return false;
        if (cache.TryGetProperty("outOfSpace", out var space) &&
            space.ValueKind == System.Text.Json.JsonValueKind.True) return false;
        return true;
    }
}

internal sealed class LeaseCoordinator
{
    private readonly Func<bool> _uploadSafe;
    private readonly Action<string>? _releaseTestHook;
    private readonly Func<string, bool>? _expediteAfterClose;
    private readonly bool _localLab;
    private readonly NativeWebDavLockClient? _nativeClient;
    private sealed class State
    {
        internal CooperativeLocks.Lease Lease;
        internal int Count;
        internal int Epoch;
    }
    private readonly Dictionary<string, State> _leases = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _gate = new();
    private readonly NasProfile _profile;
    private readonly string _password;
    private readonly TimeSpan _grace;
    internal LeaseCoordinator(NasProfile profile, string password, TimeSpan? grace = null,
        Func<bool>? uploadSafe = null, bool localLab = false,
        Func<string, bool>? expediteAfterClose = null,
        NativeWebDavLockClient? nativeClient = null,
        Action<string>? releaseTestHook = null)
    {
        _profile = profile;
        _password = password;
        // WebDAV has a 15-second write-back delay; recheck upload state
        // after a short safety interval rather than holding a fixed 120s.
        // Last editor handle close initiates release immediately. We still
        // fail closed until the write-back queue has fully drained.
        _grace = grace ?? TimeSpan.Zero;
        _uploadSafe = uploadSafe ?? (() => true);
        _localLab = localLab;
        _nativeClient = nativeClient;
        _expediteAfterClose = expediteAfterClose;
        _releaseTestHook = localLab ? releaseTestHook : null;
    }
    internal int ActiveHandles { get { lock (_gate) return _leases.Values.Sum(x => x.Count); } }
    internal bool CanShutdown { get { lock (_gate) return _leases.Count == 0; } }
    internal void AddHandle(string path)
    {
        lock (_gate)
        {
            if (!_leases.TryGetValue(path, out var state))
            {
                // Do not request native WebDAV LOCK while data writes are sent by
                // rclone: rclone does not attach the server's Lock-Token to PUTs.
                var lease = _localLab
                    ? new CooperativeLocks.Lease(path, Guid.NewGuid().ToString("N"), "local-lab", DateTimeOffset.UtcNow)
                    : _nativeClient is not null
                        ? new CooperativeLocks.Lease(path,
                            _nativeClient.Acquire(path), "native-webdav",
                            DateTimeOffset.UtcNow)
                        : CooperativeLocks.Acquire(_profile, _password, path,
                            useWebDavNativeLock: false);
                state = new State { Lease = lease };
                _leases.Add(path, state);
            }
            state.Count++;
            state.Epoch++;
        }
    }
    internal void ReleaseHandle(string path)
    {
        int epoch;
        lock (_gate)
        {
            if (!_leases.TryGetValue(path, out var state)) return;
            state.Count = Math.Max(0, state.Count - 1);
            epoch = ++state.Epoch;
        }
        // rclone's VFS may continue uploading after Close. Keep the lock for
        // Close-triggered release with a fail-closed upload-state gate.
        _ = Task.Run(async () =>
        {
            // The underlying rclone handle has been closed, but it may
            // enqueue the final upload a fraction of a second later.
            await Task.Delay(_grace + TimeSpan.FromMilliseconds(250));
            bool expedited = false;
            int safeChecks = 0;
            // Fail closed: never release a server lease while rclone is still
            // uploading, has queued transfers, reports errors, or is unreachable.
            while (true)
            {
                lock (_gate)
                {
                    if (!_leases.TryGetValue(path, out var waiting) ||
                        waiting.Count != 0 || waiting.Epoch != epoch) return;
                }
                if (!expedited && _expediteAfterClose is not null)
                {
                    try { expedited = _expediteAfterClose(path); }
                    catch (Exception ex)
                    {
                        AppStore.Log("LOCKBRIDGE EXPEDITE_WARNING " + path + " " + ex.Message);
                    }
                }
                bool safe;
                try { safe = _uploadSafe(); } catch { safe = false; }
                if (safe)
                {
                    // Avoid releasing a lock on a transient empty queue
                    // during the final WinFsp Close -> rclone enqueue step.
                    if (++safeChecks >= 2) break;
                    await Task.Delay(TimeSpan.FromMilliseconds(500));
                    continue;
                }
                safeChecks = 0;
                AppStore.Log("LOCKBRIDGE UPLOAD PENDING; retaining lease " + path);
                await Task.Delay(TimeSpan.FromMilliseconds(500));
            }
            // A single transient DELETE/UNLOCK failure previously stranded
            // the lease forever because this Task returned after one try.
            // Retry while the same closed-handle epoch remains current.
            // Never release while uploads are pending, or delete another
            // client's owner after the path has been reopened.
            int attempt = 0;
            while (true)
            {
                Exception? failure = null;
                lock (_gate)
                {
                    if (!_leases.TryGetValue(path, out var state) ||
                        state.Count != 0 || state.Epoch != epoch) return;
                    try
                    {
                        if (_localLab)
                            _releaseTestHook?.Invoke(path);
                        else
                        {
                            if (_nativeClient is not null)
                                _nativeClient.Release(path, state.Lease.Id);
                            else
                                CooperativeLocks.Release(_profile, _password, state.Lease);
                        }
                        _leases.Remove(path);
                        if (attempt > 0)
                            AppStore.Log("LOCKBRIDGE RELEASE RECOVERED " + path +
                                " attempts=" + (attempt + 1));
                        return;
                    }
                    catch (Exception ex) { failure = ex; }
                }
                attempt++;
                if (attempt <= 3 || attempt % 10 == 0)
                    AppStore.Log("LOCKBRIDGE RELEASE RETRY " + path +
                        " attempt=" + attempt + " error=" +
                        failure!.GetType().Name);
                await Task.Delay(_localLab ? TimeSpan.FromMilliseconds(80) :
                    TimeSpan.FromSeconds(Math.Min(30, 1 << Math.Min(attempt, 5))));
                // A network outage can recur while we are retrying UNLOCK.
                // Recheck remote upload safety; never release a write lock
                // just because a previous check succeeded.
                try { if (!_uploadSafe()) safeChecks = 0; }
                catch { safeChecks = 0; }
                while (safeChecks == 0)
                {
                    lock (_gate)
                    {
                        if (!_leases.TryGetValue(path, out var current) ||
                            current.Count != 0 || current.Epoch != epoch) return;
                    }
                    try { if (_uploadSafe()) ++safeChecks; }
                    catch { safeChecks = 0; }
                    if (safeChecks == 0)
                        await Task.Delay(TimeSpan.FromMilliseconds(650));
                }
            }
        });
    }
}
