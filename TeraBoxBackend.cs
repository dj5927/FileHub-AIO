namespace NASMount;

/// <summary>Unofficial backend. The caller stores session secrets in Credential Manager.</summary>
internal static class TeraBoxBackend
{
    internal static bool HasCookie(string? raw)
    {
        try { _ = NormalizeCookie(raw); return true; }
        catch (ArgumentException) { return false; }
    }

    internal static string NormalizeCookie(string? raw)
    {
        string value = (raw ?? "").Trim();
        if (value.StartsWith("Cookie:", StringComparison.OrdinalIgnoreCase))
            value = value[7..].Trim();
        if (value.Length == 0 || value.Length > 4096 || value.Any(char.IsControl))
            throw new ArgumentException("테라박스 로그인 쿠키(ndus)를 입력하세요.");
        if (value.Contains(';') || value.Contains('='))
        {
            var parts = value.Split(';', StringSplitOptions.TrimEntries);
            if (!parts.Any(x => x.StartsWith("ndus=", StringComparison.OrdinalIgnoreCase) && x.Length > 5))
                throw new ArgumentException("쿠키에 ndus= 항목이 없습니다.");
            return value;
        }
        if (value.Any(char.IsWhiteSpace))
            throw new ArgumentException("ndus 값에 공백이 있습니다.");
        return value;
    }
}
