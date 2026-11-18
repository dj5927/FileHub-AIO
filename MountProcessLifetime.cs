using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace NASMount;

/// <summary>
/// Windows kernel lifetime ownership. Before creating a single mount, put
/// this GUI in a private Job Object configured with KILL_ON_JOB_CLOSE.
/// rclone and LockBridge (and any child they create) inherit the job.
/// A GUI crash, Task Manager End task or TerminateProcess closes the GUI's
/// private job handle, causing Windows to stop the whole owned job.
///
/// Normal shutdown remains graceful. Abrupt termination can lose dirty
/// rclone VFS writes, but the cache is never intentionally erased.
/// Do not use this for update helpers, which must outlive the GUI.
/// </summary>
internal static class MountProcessLifetime
{
    private const int JobObjectExtendedLimitInformation = 9;
    private const uint JobObjectLimitBreakawayOk = 0x00000800;
    private const uint JobObjectLimitKillOnJobClose = 0x00002000;
    private const uint CreateBreakawayFromJob = 0x01000000;
    private const uint CreateNoWindow = 0x08000000;

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimitInformation
    {
        internal long PerProcessUserTimeLimit;
        internal long PerJobUserTimeLimit;
        internal uint LimitFlags;
        internal UIntPtr MinimumWorkingSetSize;
        internal UIntPtr MaximumWorkingSetSize;
        internal uint ActiveProcessLimit;
        internal UIntPtr Affinity;
        internal uint PriorityClass;
        internal uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        internal ulong ReadOperationCount;
        internal ulong WriteOperationCount;
        internal ulong OtherOperationCount;
        internal ulong ReadTransferCount;
        internal ulong WriteTransferCount;
        internal ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimitInformation
    {
        internal BasicLimitInformation Basic;
        internal IoCounters Io;
        internal UIntPtr ProcessMemoryLimit;
        internal UIntPtr JobMemoryLimit;
        internal UIntPtr PeakProcessMemoryUsed;
        internal UIntPtr PeakJobMemoryUsed;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo
    {
        internal uint Size;
        internal string? Reserved;
        internal string? Desktop;
        internal string? Title;
        internal uint X;
        internal uint Y;
        internal uint XSize;
        internal uint YSize;
        internal uint XCountChars;
        internal uint YCountChars;
        internal uint FillAttribute;
        internal uint Flags;
        internal ushort ShowWindow;
        internal ushort Reserved2Size;
        internal IntPtr Reserved2;
        internal IntPtr StdInput;
        internal IntPtr StdOutput;
        internal IntPtr StdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation
    {
        internal IntPtr Process;
        internal IntPtr Thread;
        internal uint ProcessId;
        internal uint ThreadId;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateJobObject(
        IntPtr securityAttributes, string? name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(
        SafeFileHandle job, int informationClass,
        ref ExtendedLimitInformation information, uint size);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(
        SafeFileHandle job, IntPtr process);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateProcess(
        string applicationName, StringBuilder commandLine,
        IntPtr processAttributes, IntPtr threadAttributes,
        [MarshalAs(UnmanagedType.Bool)] bool inheritHandles,
        uint creationFlags, IntPtr environment, string workingDirectory,
        ref StartupInfo startupInfo, out ProcessInformation processInformation);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    // This handle must remain held for the GUI's ENTIRE lifetime. It is not
    // inheritable, so abruptly closing the GUI closes the final job handle.
    private static SafeFileHandle? ownedJob;
    internal static bool IsActive => ownedJob is { IsInvalid: false, IsClosed: false };

    private static SafeFileHandle CreateConfiguredJob()
    {
        SafeFileHandle job = CreateJobObject(IntPtr.Zero, null);
        if (job.IsInvalid)
            throw new Win32Exception(Marshal.GetLastWin32Error(),
                "Cannot create FileHub mount lifetime job");
        try
        {
            var limits = new ExtendedLimitInformation();
            limits.Basic.LimitFlags =
                JobObjectLimitKillOnJobClose | JobObjectLimitBreakawayOk;
            if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation,
                    ref limits, (uint)Marshal.SizeOf<ExtendedLimitInformation>()))
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Cannot configure FileHub mount lifetime job");
            return job;
        }
        catch { job.Dispose(); throw; }
    }

    internal static void AttachGuiBeforeMounts()
    {
        if (IsActive) return;
        SafeFileHandle job = CreateConfiguredJob();
        try
        {
            if (!AssignProcessToJobObject(job, GetCurrentProcess()))
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Cannot attach FileHub GUI to mount lifetime job");
            ownedJob = job;
            AppStore.Log("MOUNT_LIFETIME_KERNEL_JOB_ACTIVE " +
                "killOwnedMountsOnGuiExit=true updaterBreakaway=true");
        }
        catch { job.Dispose(); throw; }
    }

    // Command-line quoting is required because updates are stored under the
    // user's Windows profile (which may contain spaces/Unicode characters).
    private static string QuoteArgument(string argument)
    {
        var output = new StringBuilder("\"");
        int slashes = 0;
        foreach (char ch in argument)
        {
            if (ch == '\\') { slashes++; continue; }
            if (ch == '"')
            {
                output.Append('\\', slashes * 2 + 1);
                output.Append('"');
            }
            else
            {
                output.Append('\\', slashes);
                output.Append(ch);
            }
            slashes = 0;
        }
        output.Append('\\', slashes * 2);
        return output.Append('"').ToString();
    }

    /// <summary>
    /// The verified updater must remain running after its old GUI exits.
    /// Start it via CREATE_BREAKAWAY_FROM_JOB, never Process.Start(), which
    /// would silently put it in the mount job and kill it on GUI exit.
    /// </summary>
    internal static void StartUpdaterOutsideMountJob(
        string executable, string workingDirectory, string applyPlan)
    {
        if (!IsActive)
            throw new InvalidOperationException("FileHub mount job is not active");
        var startup = new StartupInfo { Size = (uint)Marshal.SizeOf<StartupInfo>() };
        var command = new StringBuilder(QuoteArgument(executable) +
            " --apply-pc-update " + QuoteArgument(applyPlan));
        if (!CreateProcess(executable, command, IntPtr.Zero, IntPtr.Zero,
                false, CreateBreakawayFromJob | CreateNoWindow, IntPtr.Zero,
                workingDirectory, ref startup, out var created))
            throw new Win32Exception(Marshal.GetLastWin32Error(),
                "Updater must start outside the FileHub mount job");
        CloseHandle(created.Thread);
        CloseHandle(created.Process);
    }

    internal static void PolicyTest()
    {
        string expected = QuoteArgument(@"C:\Program Files\FileHub AIO\FileHubAIO.exe");
        if (expected != "\"C:\\Program Files\\FileHub AIO\\FileHubAIO.exe\"" ||
            (JobObjectLimitKillOnJobClose & JobObjectLimitBreakawayOk) != 0)
            throw new InvalidOperationException("Mount lifetime job policy corrupted");

        // Isolated test: assign ONLY a disposable test process, then close
        // the job handle and prove that the kernel terminates that child.
        using var job = CreateConfiguredJob();
        string self = Environment.ProcessPath ??
            throw new InvalidOperationException("Missing executable path");
        using var child = new Process
        {
            StartInfo = new ProcessStartInfo(self)
            {
                UseShellExecute = false,
                CreateNoWindow = true
            }
        };
        child.StartInfo.ArgumentList.Add("--emergency-child-wait");
        child.Start();
        try
        {
            if (!AssignProcessToJobObject(job, child.Handle))
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Cannot assign test process to mount lifetime job");
            job.Dispose(); // OS must terminate ONLY this test child.
            if (!child.WaitForExit(4000))
                throw new InvalidOperationException("Job close left the test child alive");
            Console.WriteLine("MOUNT_JOB_KILL_ON_CLOSE_PASS");
        }
        finally
        {
            if (!child.HasExited) child.Kill();
        }
    }
}
