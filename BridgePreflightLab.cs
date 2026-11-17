using System.IO.Pipes;

namespace NASMount;

/// <summary>
/// Exercise orphan-bridge detection with a disposable, unique local named pipe
/// without ever contacting a NAS or touching a real running mount.
/// </summary>
internal static class BridgePreflightLab
{
    internal static async Task RunAsync()
    {
        Guid id = Guid.NewGuid();
        string name = "NASMountBridge_" + id.ToString("N");
        BridgeProbe absent = await BridgeOwnership.ProbeAsync(id);
        if (absent.Presence != BridgePresence.None)
            throw new InvalidOperationException("Absent bridge should be detected as absent: " + absent);

        // A healthy old bridge answers PING; clients should detect ownership
        // rather than try to create another single-instance server.
        using (var server = new NamedPipeServerStream(name, PipeDirection.InOut, 1,
                   PipeTransmissionMode.Byte, PipeOptions.Asynchronous))
        {
            var serve = Task.Run(async () =>
            {
                await server.WaitForConnectionAsync();
                using var reader = new StreamReader(server, leaveOpen: true);
                using var writer = new StreamWriter(server, leaveOpen: true) { AutoFlush = true };
                string? incoming = await reader.ReadLineAsync();
                if (incoming != "PING")
                    throw new InvalidOperationException("Unexpected probe command: " + incoming);
                await writer.WriteLineAsync("READY 0");
                // Let StreamWriter flush/dispose before the server stream is
                // disconnected by its outer using scope.
            });
            BridgeProbe live = await BridgeOwnership.ProbeAsync(id);
            await serve.WaitAsync(TimeSpan.FromSeconds(5));
            if (live.Presence != BridgePresence.Active)
                throw new InvalidOperationException("Live bridge was not detected: " + live);
        }

        // All pipe instances are now occupied; no code is allowed to attempt
        // creating another bridge or terminating the existing process.
        using (var server = new NamedPipeServerStream(name, PipeDirection.InOut, 1,
                   PipeTransmissionMode.Byte, PipeOptions.Asynchronous))
        using (var client = new NamedPipeClientStream(".", name,
                   PipeDirection.InOut, PipeOptions.Asynchronous))
        {
            var wait = server.WaitForConnectionAsync();
            await client.ConnectAsync(1500);
            await wait.WaitAsync(TimeSpan.FromSeconds(5));
            BridgeProbe busy = await BridgeOwnership.ProbeAsync(id);
            if (busy.Presence != BridgePresence.Busy)
                throw new InvalidOperationException("Occupied pipe not detected as busy: " + busy);
        }
        Console.WriteLine("BRIDGE_PREFLIGHT_LAB_PASS none/active/busy");
    }
}
