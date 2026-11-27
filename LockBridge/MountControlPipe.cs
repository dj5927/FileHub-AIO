using System.IO.Pipes;
using System.Text;

namespace NASMount.LockBridge;

/// <summary>One bounded control request per connection. An abandoned GUI
/// health/status client is never a request to tear down a mounted drive.</summary>
internal static class MountControlPipe
{
    internal static void Run(string name, Func<string, (string Reply, bool Quit)> handle,
        TimeSpan? requestTimeout = null)
    {
        var timeout = requestTimeout ?? TimeSpan.FromSeconds(5);
        while (true)
        {
            try
            {
                using var pipe = new NamedPipeServerStream(name, PipeDirection.InOut,
                    1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                pipe.WaitForConnection();
                using var deadline = new CancellationTokenSource(timeout);
                using var reader = new StreamReader(pipe, leaveOpen: true);
                string? command = reader.ReadLineAsync(deadline.Token).AsTask()
                    .GetAwaiter().GetResult();
                if (command is null) continue;
                var (reply, quit) = handle(command);
                // Write directly to the pipe, avoiding a buffered writer
                // whose Dispose could throw after a canceled status request.
                byte[] bytes = Encoding.UTF8.GetBytes(reply + "\r\n");
                pipe.WriteAsync(bytes.AsMemory(), deadline.Token).AsTask()
                    .GetAwaiter().GetResult();
                if (quit) return;
            }
            catch (Exception ex) when (ex is IOException or OperationCanceledException
                or UnauthorizedAccessException)
            {
                Console.WriteLine("FILEHUB_CONTROL_CLIENT_ABANDONED type=" +
                    ex.GetType().Name + " mountRetained=true");
                // A busy/failed pipe instance must not spin at full CPU.
                Thread.Sleep(25);
            }
        }
    }

    internal static void RegressionTest()
    {
        string name = "FileHubControlLab_" + Guid.NewGuid().ToString("N");
        var processing = new ManualResetEventSlim(false);
        var allowResponse = new ManualResetEventSlim(false);
        var server = Task.Run(() => Run(name, command =>
        {
            if (command == "ABANDON")
            {
                processing.Set();
                if (!allowResponse.Wait(TimeSpan.FromSeconds(5)))
                    throw new IOException("Lab response deadline");
                return ("READY " + new string('x', 8192), false);
            }
            return command == "QUIT" ? ("OK", true) : ("READY 0 SAFE", false);
        }, TimeSpan.FromMilliseconds(500)));
        NamedPipeClientStream Connect()
        {
            var client = new NamedPipeClientStream(".", name, PipeDirection.InOut,
                PipeOptions.Asynchronous);
            client.Connect(5000);
            return client;
        }
        string Request(string command)
        {
            using var client = Connect();
            using var reader = new StreamReader(client, leaveOpen: true);
            client.Write(Encoding.UTF8.GetBytes(command + "\n"));
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            return reader.ReadLineAsync(deadline.Token).AsTask().GetAwaiter().GetResult() ?? "";
        }
        using (var abandoned = Connect())
        {
            abandoned.Write(Encoding.UTF8.GetBytes("ABANDON\n"));
            if (!processing.Wait(TimeSpan.FromSeconds(5)))
                throw new IOException("Lab command did not reach server");
        }
        allowResponse.Set();
        using (var idle = Connect())
            Thread.Sleep(800);
        if (Request("PING") != "READY 0 SAFE" || server.IsCompleted)
            throw new IOException("Abandoned pipe terminated mount");
        if (Request("QUIT") != "OK" || !server.Wait(TimeSpan.FromSeconds(5)))
            throw new IOException("Safe quit failed");
        Console.WriteLine("MOUNT_CONTROL_ABANDONED_CLIENT_AND_TIMEOUT_PASS");
    }
}
