using System.Text;

namespace NASMount;

/// <summary>
/// An FTP/SFTP code page is not rclone's filename escaping option.
/// Non-UTF8 profiles use a loopback WebDAV adapter that actually applies
/// the chosen Encoding to remote filename operations.
/// </summary>
internal static class CharsetSupport
{
    internal static readonly string[] Names =
    [
        "UTF-8", "서버 기본", "CP949 / EUC-KR (한국어)",
        "EUC-KR / CP51949 (한국어)", "Shift-JIS / CP932 (일본어)",
        "EUC-JP (일본어)", "GBK / CP936 (중국어 간체)",
        "GB18030 (중국어 간체)", "Big5 / CP950 (중국어 번체)",
        "KOI8-R (키릴어)", "KOI8-U (우크라이나어)",
        "Windows-1250 (중부 유럽)", "Windows-1251 (키릴어)",
        "Windows-1252 (서유럽)", "Windows-1253 (그리스어)",
        "Windows-1254 (터키어)", "Windows-1255 (히브리어)",
        "Windows-1256 (아랍어)", "Windows-1257 (발트어)",
        "Windows-1258 (베트남어)",
        "ISO-8859-1", "ISO-8859-2", "ISO-8859-3", "ISO-8859-4",
        "ISO-8859-5", "ISO-8859-6", "ISO-8859-7", "ISO-8859-8",
        "ISO-8859-9", "ISO-8859-10", "ISO-8859-11", "ISO-8859-13",
        "ISO-8859-14", "ISO-8859-15", "ISO-8859-16", "US-ASCII"
    ];

    static CharsetSupport() =>
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);

    internal static bool RequiresGateway(NasProfile profile) =>
        RequiresGateway(profile.Protocol, profile.Protocol == "FTP"
            ? (profile.FtpDisableUtf8 ? "서버 기본" : profile.FtpCharset)
            : profile.SftpCharset);

    internal static bool RequiresGateway(string protocol, string value) =>
        protocol is "FTP" or "SFTP" &&
        value != "UTF-8" && value != "서버 기본";

    internal static Encoding? GetEncoding(string value)
    {
        if (value == "UTF-8") return new UTF8Encoding(false, true);
        if (value == "서버 기본") return null;
        string key = value.Split(' ', 2)[0];
        string codepage = key switch
        {
            "CP949" => "949", "EUC-KR" => "51949",
            "Shift-JIS" => "932", "EUC-JP" => "euc-jp",
            "GBK" => "936", "GB18030" => "gb18030",
            "Big5" => "950", "KOI8-R" => "koi8-r",
            "KOI8-U" => "koi8-u", "US-ASCII" => "us-ascii",
            _ => key
        };
        if (key.StartsWith("ISO-8859-", StringComparison.OrdinalIgnoreCase))
            codepage = "iso-8859-" + key["ISO-8859-".Length..];
        if (key.StartsWith("Windows-", StringComparison.OrdinalIgnoreCase))
            codepage = "windows-" + key["Windows-".Length..];
        try
        {
            return int.TryParse(codepage, out int numericCodepage)
                ? Encoding.GetEncoding(numericCodepage,
                    EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback)
                : Encoding.GetEncoding(codepage,
                    EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
        }
        catch (ArgumentException) { return null; }
        catch (NotSupportedException) { return null; }
    }

    internal static bool IsSupported(string protocol, string value) =>
        Names.Contains(value) &&
        (value == "UTF-8" || protocol == "FTP" && value == "서버 기본" ||
         RequiresGateway(protocol, value) && GetEncoding(value) is not null);

    internal static string DescribeUnsupported(string protocol, string value) =>
        $"{value} 문자셋은 현재 {protocol}에서 안전하게 변환할 수 없습니다. " +
        "변환 불가능한 파일명은 자동 대체하지 않고 연결/저장을 차단합니다.";

    internal static void SelfTest()
    {
        foreach (string sample in Names)
        {
            if (sample == "서버 기본" || !IsSupported("SFTP", sample)) continue;
            var encoding = GetEncoding(sample)!;
            string name = sample.StartsWith("CP949") ||
                          sample.StartsWith("EUC-KR") ? "한글 파일.txt" :
                          sample.StartsWith("Shift-JIS") ||
                          sample.StartsWith("EUC-JP") ? "日本語.txt" :
                          sample.StartsWith("Big5") ||
                          sample.StartsWith("GBK") ||
                          sample.StartsWith("GB18030") ? "中文.txt" :
                          "Test.txt";
            if (encoding.GetString(encoding.GetBytes(name)) != name)
                throw new InvalidOperationException("CHARSET_ROUNDTRIP_FAILED " + sample);
        }
        if (IsSupported("SFTP", "서버 기본") ||
            IsSupported("FTP", "unknown-charset") ||
            !RequiresGateway("SFTP", "CP949 / EUC-KR (한국어)") ||
            RequiresGateway("FTP", "UTF-8"))
            throw new InvalidOperationException("CHARSET_POLICY_FAIL");
        Console.WriteLine("CHARSET_CODEPAGE_ROUNDTRIP_POLICY_PASS");
    }
}
