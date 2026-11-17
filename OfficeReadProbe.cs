using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;

namespace NASMount;

/// <summary>
/// Read-only timing diagnostic for XLSX random reads. Nothing is written to
/// the NAS. It compares the same byte ranges via mounted drive and WebDAV.
/// This is NOT a full Excel start-to-finish benchmark.
/// </summary>
internal static class OfficeReadProbe
{
    internal sealed record Sample(double MetadataMs, double FirstMs, double TailMs,
        int BytesFirst, int BytesTail, byte[] FirstHash, byte[] TailHash,
        bool RangeSupported = true, double OpenMs = 0);

    internal static Uri FileUri(NasProfile p, string relativePath)
    {
        if (relativePath.StartsWith('/') || relativePath.StartsWith('\\') ||
            relativePath.Split('/', '\\').Any(s => s is ".." or "."))
            throw new ArgumentException("WebDAV 상대 경로가 올바르지 않습니다.");
        string safe = string.Join("/", relativePath.Replace('\\','/').Split('/',
            StringSplitOptions.RemoveEmptyEntries).Select(Uri.EscapeDataString));
        if (safe.Length == 0) throw new ArgumentException("파일을 선택하세요.");
        return new Uri(WebDavSaveProbe.RootUri(p), safe);
    }

    internal static async Task<string> Compare(NasProfile p, string file, string? password,
        CancellationToken cancellationToken = default)
    {
        if (p.Protocol != "WebDAV") return "WebDAV 연결만 지원합니다.";
        string driveRoot = p.Letter.ToUpperInvariant() + @":\";
        string full = Path.GetFullPath(file);
        if (!full.StartsWith(driveRoot, StringComparison.OrdinalIgnoreCase) ||
            !File.Exists(full))
            return "선택한 파일이 해당 NAS 드라이브에 없습니다.";
        string relative = full[driveRoot.Length..];
        const int size = 256 * 1024;
        var local = await ReadMount(full, size, cancellationToken);
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(40) };
        if (!p.IsAnonymous && !string.IsNullOrEmpty(p.Username))
            http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
                "Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(p.Username+":"+(password??""))));
        Sample remote = await ReadRemote(http, FileUri(p,relative), size, cancellationToken);
        bool equalHead = local.BytesFirst == remote.BytesFirst &&
            CryptographicOperations.FixedTimeEquals(local.FirstHash, remote.FirstHash);
        bool equalTail = remote.RangeSupported &&
            local.BytesTail == remote.BytesTail &&
            CryptographicOperations.FixedTimeEquals(local.TailHash, remote.TailHash);
        string fmt(double milliseconds) => milliseconds.ToString("N0") + " ms";
        string report =
            "Excel 파일 읽기 진단 (같은 파일, 각각 최대 256KiB씩 검사)\n\n" +
            "                             드라이브         WebDAV 직접\n" +
            $"파일 정보/요청       {fmt(local.MetadataMs),-17} {fmt(remote.MetadataMs)}\n" +
            $"파일 핸들 열기    {fmt(local.OpenMs)} (드라이브 단계)\n" +
            $"파일 앞부분 읽기    {fmt(local.FirstMs),-17} {fmt(remote.FirstMs)}\n" +
            $"파일 끝부분 읽기    {fmt(local.TailMs),-17} {fmt(remote.TailMs)}\n\n" +
            "데이터 앞부분: " + (equalHead ? "일치" : "불일치 또는 서버 Range 미지원") + "\n" +
            "데이터 끝부분: " + (equalTail ? "일치" :
                remote.RangeSupported ? "불일치 / 캐시 갱신 점검 필요" : "서버 HTTP Range 미지원") + "\n" +
            "서버 HTTP Range 지원: " + (remote.RangeSupported ? "예" : "아니요") + "\n\n" +
            "이 수치는 Windows/WinFsp/서버 측 캐시 상황에 따라 달라집니다.\n" +
            "Excel 전체 열기 시간이나 잠금 동작을 직접 측정한 것은 아닙니다.";
        return report;
    }

    private static async Task<Sample> ReadMount(string path,int chunk,CancellationToken ct)
    {
        var timer = Stopwatch.StartNew();
        var fileInfo = new FileInfo(path);
        long length = fileInfo.Length;
        if (length == 0) throw new IOException("빈 파일은 비교할 수 없습니다.");
        double metadata = timer.Elapsed.TotalMilliseconds;
        timer.Restart();
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete, 65536, FileOptions.Asynchronous |
            FileOptions.RandomAccess);
        double openMs=timer.Elapsed.TotalMilliseconds;
        int amount = (int)Math.Min(chunk,length);
        timer.Restart();
        byte[] first = await ReadAmount(fs,amount,ct);
        double firstMs = timer.Elapsed.TotalMilliseconds;
        fs.Seek(Math.Max(0,length-chunk),SeekOrigin.Begin);
        timer.Restart();
        byte[] tail = await ReadAmount(fs,amount,ct);
        double tailMs = timer.Elapsed.TotalMilliseconds;
        return new Sample(metadata,firstMs,tailMs,first.Length,tail.Length,
            SHA256.HashData(first),SHA256.HashData(tail),OpenMs:openMs);
    }

    internal static async Task<Sample> ReadRemote(HttpClient http,Uri uri,int chunk,
        CancellationToken ct)
    {
        var timer=Stopwatch.StartNew();
        long size;
        using(var head=new HttpRequestMessage(HttpMethod.Head,uri))
        using(var response=await http.SendAsync(head,HttpCompletionOption.ResponseHeadersRead,ct))
        {
            response.EnsureSuccessStatusCode();
            size=response.Content.Headers.ContentLength ??
                throw new IOException("WebDAV HEAD에서 파일 크기를 반환하지 않았습니다.");
        }
        if(size==0)throw new IOException("빈 파일은 비교할 수 없습니다.");
        double metadata=timer.Elapsed.TotalMilliseconds;
        int amount=(int)Math.Min(chunk,size);
        var first=await ReadRange(http,uri,0,amount,ct);
        var tail=await ReadRange(http,uri,Math.Max(0,size-amount),amount,ct);
        return new Sample(metadata,first.ElapsedMs,tail.ElapsedMs,
            first.Data.Length,tail.Data.Length,
            SHA256.HashData(first.Data),SHA256.HashData(tail.Data),
            first.Partial && tail.Partial);
    }

    private sealed record RangeResult(byte[] Data,double ElapsedMs,bool Partial);
    private static async Task<RangeResult> ReadRange(HttpClient http,Uri uri,long offset,
        int amount,CancellationToken ct)
    {
        using var request=new HttpRequestMessage(HttpMethod.Get,uri);
        request.Headers.Range=new RangeHeaderValue(offset,offset+amount-1);
        var timer=Stopwatch.StartNew();
        using var response=await http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead,ct);
        response.EnsureSuccessStatusCode();
        using var body=await response.Content.ReadAsStreamAsync(ct);
        byte[] bytes=new byte[amount];
        int position=0;
        while(position<amount)
        {
            int read=await body.ReadAsync(bytes.AsMemory(position,amount-position),ct);
            if(read==0)break;
            position+=read;
        }
        if(position!=amount)Array.Resize(ref bytes,position);
        return new RangeResult(bytes,timer.Elapsed.TotalMilliseconds,
            response.StatusCode==HttpStatusCode.PartialContent);
    }

    private static async Task<byte[]> ReadAmount(Stream stream,int amount,CancellationToken ct)
    {
        byte[] bytes=new byte[amount];
        int position=0;
        while(position<amount)
        {
            int read=await stream.ReadAsync(bytes.AsMemory(position,amount-position),ct);
            if(read==0)break;
            position+=read;
        }
        if(position!=amount)Array.Resize(ref bytes,position);
        return bytes;
    }
}
