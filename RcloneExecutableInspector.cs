using System.ComponentModel;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Collections.Concurrent;
using System.Threading;

namespace NASMount;

/// <summary>
/// Safe preflight: checks only the local rclone binary, never NAS files or
/// cache. The bundled SHA-256 manifest distinguishes corrupted transfers from
/// protocol problems. The executable must also actually start on this PC.
/// </summary>
internal static class RcloneExecutableInspector
{
    // Every bundled backend has its own manifest. In particular the Google
    // nonblocking-About build is deliberately different from upstream rclone.
    // Never compare rclone-filehub-google.exe against rclone.sha256.
    internal static string ManifestFileName(string executable)
    {
        string name = Path.GetFileName(executable);
        if (name.Equals("rclone-filehub-google.exe",
                StringComparison.OrdinalIgnoreCase))
            return "rclone-filehub-google.sha256";
        if (name.Equals("rclone-terabox.exe",
                StringComparison.OrdinalIgnoreCase))
            return "rclone-terabox.sha256";
        return "rclone.sha256";
    }

    internal static void ManifestPolicyTest()
    {
        if (ManifestFileName("rclone.exe") != "rclone.sha256" ||
            ManifestFileName("rclone-terabox.exe") != "rclone-terabox.sha256" ||
            ManifestFileName("rclone-filehub-google.exe") !=
                "rclone-filehub-google.sha256" ||
            ManifestFileName("RCLONE-FILEHUB-GOOGLE.EXE") !=
                "rclone-filehub-google.sha256" ||
            ManifestFileName("rclone-filehub-google.exe") ==
                ManifestFileName("rclone.exe"))
            throw new InvalidOperationException(
                "Google patched backend was assigned the upstream rclone manifest");
        Console.WriteLine("RCLONE_THREE_ENGINE_DISTINCT_MANIFEST_POLICY_PASS");
    }

    internal static async Task<OperationResult> VerifyAsync(string executable)
    {
        if (!File.Exists(executable))
            return OperationResult.Fail("rclone.exe 파일이 없습니다: " + executable);
        // Coalesce SHA256 + version checks across concurrently started
        // profiles. Cache only a successful validation for the lifetime of
        // THIS NASMount process, keyed by file identity + manifest metadata.
        // Never trust a changed executable or manifest without rechecking.
        string key;
        try
        {
            var binary = new FileInfo(executable);
            string manifest = Path.Combine(binary.DirectoryName!,
                ManifestFileName(executable));
            var signature = new FileInfo(manifest);
            key = Path.GetFullPath(executable).ToUpperInvariant() + "|" +
                binary.Length + "|" + binary.LastWriteTimeUtc.Ticks + "|" +
                (signature.Exists ? signature.Length : -1) + "|" +
                (signature.Exists ? signature.LastWriteTimeUtc.Ticks : 0);
        }
        catch (Exception ex)
        {
            return OperationResult.Fail("rclone 파일 확인 실패: " + ex.Message);
        }
        var cached = Verified.GetOrAdd(key, _ =>
            new Lazy<Task<OperationResult>>(
                () => VerifyFreshAsync(executable),
                LazyThreadSafetyMode.ExecutionAndPublication));
        OperationResult result;
        try { result = await cached.Value.ConfigureAwait(false); }
        catch
        {
            Verified.TryRemove(key, out _);
            throw;
        }
        if (!result.Success) Verified.TryRemove(key, out _);
        return result;
    }

    private static readonly ConcurrentDictionary<string, Lazy<Task<OperationResult>>>
        Verified = new(StringComparer.OrdinalIgnoreCase);

    private static async Task<OperationResult> VerifyFreshAsync(string executable)
    {
        if (!File.Exists(executable))
            return OperationResult.Fail("rclone.exe 파일이 없습니다: " + executable);

        FileInfo info = new(executable);
        if (info.Length < 10_000_000)
            return OperationResult.Fail($"rclone.exe 크기가 비정상입니다 ({info.Length:N0}바이트). " +
                "압축 해제 또는 보안 프로그램 검역으로 파일이 손상됐을 수 있습니다. " +
                "배포 ZIP을 새 폴더에 다시 압축 해제하세요.");

        string hash;
        try
        {
            await using var input = File.OpenRead(executable);
            hash = Convert.ToHexString(await SHA256.HashDataAsync(input));
            string manifest = Path.Combine(Path.GetDirectoryName(executable)!,
                ManifestFileName(executable));
            if (ManifestFileName(executable) == "rclone-filehub-google.sha256" &&
                !string.Equals(hash, GoogleAsyncQuotaEngine.Sha256,
                    StringComparison.OrdinalIgnoreCase))
                return OperationResult.Fail(
                    "Google 전용 rclone 파일의 고정 SHA-256이 일치하지 않습니다. " +
                    "정상 엔진으로 자동 복구하거나 새 배포본을 사용하세요.");
            if (File.Exists(manifest))
            {
                string expected = (await File.ReadAllTextAsync(manifest)).Trim().Split(' ')[0];
                if (!string.Equals(hash, expected, StringComparison.OrdinalIgnoreCase))
                    return OperationResult.Fail(
                        Path.GetFileName(executable) +
                        " 무결성 검사 실패 (SHA-256 불일치).\n" +
                        $"파일: {executable}\n크기: {info.Length:N0}바이트\n" +
                        $"현재 해시: {hash}\n예상 해시: {expected}\n" +
                        "NAS 계정이나 네트워크 문제가 아닙니다. 배포 ZIP을 다시 복사해 " +
                        "새 폴더에 압축 해제하고, Windows 보안의 보호 기록을 확인하세요.");
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return OperationResult.Fail("rclone.exe 파일을 읽지 못했습니다: " + ex.Message);
        }

        try
        {
            using var process = new Process
            {
                StartInfo = new ProcessStartInfo(executable)
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    ArgumentList = { "version" }
                }
            };
            process.Start();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(7));
            try { await process.WaitForExitAsync(timeout.Token); }
            catch (OperationCanceledException)
            {
                if (!process.HasExited) process.Kill(entireProcessTree: true);
                return OperationResult.Fail("rclone version 자체 검사 7초 초과. " +
                    "백신 검사·실행 제한 또는 파일 손상을 확인하세요.");
            }
            string text = await process.StandardOutput.ReadToEndAsync();
            string error = await process.StandardError.ReadToEndAsync();
            if (process.ExitCode != 0 || !text.StartsWith("rclone v", StringComparison.Ordinal))
                return OperationResult.Fail("rclone.exe를 실행했으나 버전 검사에 실패했습니다.\n" +
                    $"종료 코드: {process.ExitCode}\n" +
                    (error.Length > 400 ? error[..400] : error));
            return OperationResult.Ok(text.Split('\n')[0].Trim() + " / SHA256 " + hash);
        }
        catch (Win32Exception ex)
        {
            return OperationResult.Fail(
                "Windows에서 rclone.exe 실행을 거부했습니다.\n" +
                $"파일: {executable}\n크기: {info.Length:N0}바이트\nSHA256: {hash}\n" +
                $"Windows 오류 코드: {ex.NativeErrorCode} — {ex.Message}\n" +
                "배포 ZIP을 다른 새 폴더에 다시 압축 해제하고 Windows 보안 보호 기록을 확인하세요. " +
                "사용 중인 Windows 운영체제 아키텍처(x64)도 확인하세요.");
        }
        catch (Exception ex)
        {
            return OperationResult.Fail("rclone.exe 자체 실행 검사 실패: " + ex.Message);
        }
    }
}
