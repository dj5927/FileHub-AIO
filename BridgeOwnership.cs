using System.IO.Pipes;
using System.Runtime.InteropServices;

namespace NASMount;

internal enum BridgePresence { None, Active, Busy }

internal readonly record struct BridgeProbe(BridgePresence Presence, string Detail)
{
    internal bool IsOccupied => Presence != BridgePresence.None;
}

/// <summary>
/// Read-only cross-process preflight for the existing per-profile bridge pipe.
/// This deliberately NEVER sends QUIT, kills processes, clears caches or
/// changes mount letters. A surviving old bridge must remain safe for upload.
/// </summary>
internal static class BridgeOwnership
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, EntryPoint = "WaitNamedPipeW",
        SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool WaitNamedPipe(string name, uint timeoutMs);

    internal static async Task<BridgeProbe> ProbeAsync(Guid profileId,
        CancellationToken cancellationToken = default)
    {
        string name = "NASMountBridge_" + profileId.ToString("N");
        string path = @"\\.\pipe\" + name;
        if (!WaitNamedPipe(path, 200))
        {
            int code = Marshal.GetLastWin32Error();
            if (code is 2 or 3) return new(BridgePresence.None, "");
            // ERROR_PIPE_BUSY (231) and ERROR_SEM_TIMEOUT (121) identify an
            // existing pipe with no free instance. Never start another bridge.
            if (code is 231 or 121)
                return new(BridgePresence.Busy, "기존 잠금 브리지 파이프가 사용 중입니다.");
            return new(BridgePresence.Busy,
                "기존 잠금 브리지 파이프 상태 확인 실패. Windows 오류 " + code);
        }
        try
        {
            using var pipe = new NamedPipeClientStream(".", name,
                PipeDirection.InOut, PipeOptions.Asynchronous);
            await pipe.ConnectAsync(850, cancellationToken);
            using var writer = new StreamWriter(pipe, leaveOpen: true) { AutoFlush = true };
            using var reader = new StreamReader(pipe, leaveOpen: true);
            await writer.WriteLineAsync("PING");
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(1000);
            string response = await reader.ReadLineAsync(timeout.Token) ?? "";
            return new(BridgePresence.Active, response.StartsWith("READY", StringComparison.Ordinal)
                ? "이 연결을 관리하는 이전 잠금 브리지가 살아 있습니다. " + response
                : "이 연결 이름의 잠금 브리지 파이프가 이미 존재합니다.");
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return new(BridgePresence.Busy, "기존 잠금 브리지가 응답하지 않습니다.");
        }
        catch (IOException e)
        {
            // Old server may exit between WaitNamedPipe and Connect. A new
            // connection may retry, but never ignore a potentially busy pipe.
            if (!WaitNamedPipe(path, 0) && Marshal.GetLastWin32Error() is (2 or 3))
                return new(BridgePresence.None, "");
            return new(BridgePresence.Busy,
                "기존 잠금 파이프에 접근할 수 없습니다: " + e.Message);
        }
        catch (TimeoutException)
        {
            return new(BridgePresence.Busy, "기존 잠금 브리지 연결 응답이 지연됩니다.");
        }
    }

    internal static string UserMessage(BridgeProbe probe) =>
        "같은 NAS 연결의 이전 잠금 브리지가 아직 실행 중입니다.\n" +
        probe.Detail + "\n\n" +
        "다른 FileHub AIO가 트레이에서 실행 중이면 그 프로그램에서 파일 저장과 " +
        "업로드 완료를 확인하고 정상적으로 연결 해제한 뒤 종료하세요.\n" +
        "이전 프로그램이 비정상 종료되었다면 먼저 작업 관리자의 " +
        "NASMount / rclone / NASMountLockBridge 프로세스와 로그를 확인하세요.\n" +
        "미전송 데이터 손실을 막기 위해 자동 강제 종료나 캐시 삭제는 하지 않습니다.";
}
