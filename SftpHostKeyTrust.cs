using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Renci.SshNet;

namespace NASMount;

/// <summary>
/// RaiDrive-style first-use SFTP SSH host-key approval.
/// Probe terminates during SSH key exchange BEFORE user authentication.
/// After explicit approval, rclone uses its HOST_KEYS pin; never disables SSH
/// host-key verification and never modifies the user's global known_hosts.
/// </summary>
internal static class SftpHostKeyTrust
{
    internal sealed record Observation(string Host, int Port,
        string Algorithm, string PublicKeyBase64, string Sha256Fingerprint)
    {
        internal string RclonePinnedValue => Algorithm + " " + PublicKeyBase64;
    }

    internal static int EffectivePort(NasProfile p) =>
        p.Port is > 0 and <= 65535 ? p.Port : 22;

    internal static string NormalizeFingerprint(string? fingerprint)
    {
        string raw = (fingerprint ?? "").Trim();
        if (raw.StartsWith("SHA256:", StringComparison.OrdinalIgnoreCase))
            raw = raw[7..];
        return raw.TrimEnd('=');
    }

    internal static bool MatchesFingerprint(string expected, string observed) =>
        NormalizeFingerprint(expected).Length > 0 &&
        string.Equals(NormalizeFingerprint(expected),
            NormalizeFingerprint(observed), StringComparison.Ordinal);

    // SSH.NET's HostKeyName is occasionally the negotiated SIGNATURE
    // algorithm ("rsa-sha2-512"), while rclone host_keys demands the
    // algorithm inside the SSH PUBLIC KEY blob ("ssh-rsa").
    internal static string WirePublicKeyAlgorithm(byte[] raw)
    {
        if (raw.Length < 12)
            throw new CryptographicException("SSH 공개키 데이터가 너무 짧습니다.");
        int length = BinaryPrimitives.ReadInt32BigEndian(raw.AsSpan(0, 4));
        if (length is < 5 or > 90 || length > raw.Length - 4)
            throw new CryptographicException("SSH 공개키 종류 길이가 올바르지 않습니다.");
        string name = Encoding.ASCII.GetString(raw, 4, length);
        if (name.Any(c => !char.IsAsciiLetterOrDigit(c) &&
            c is not ('-' or '_' or '.' or '@' or '+')))
            throw new CryptographicException("SSH 공개키 종류에 잘못된 문자가 있습니다.");
        return name;
    }

    internal static byte[] TestPublicKey(string algorithm)
    {
        byte[] raw = RandomNumberGenerator.GetBytes(96);
        byte[] name = Encoding.ASCII.GetBytes(algorithm);
        BinaryPrimitives.WriteInt32BigEndian(raw.AsSpan(0, 4), name.Length);
        name.CopyTo(raw.AsSpan(4));
        return raw;
    }

    // Upgrade only an EXISTING fingerprint-approved RSA key. Never change
    // the key bytes or trust a changed server without explicit approval.
    internal static bool RepairPreviousRsaPin(NasProfile p)
    {
        if (p.Protocol != "SFTP" ||
            p.SftpTrustedPort != EffectivePort(p) ||
            !p.SftpTrustedHost.Equals(p.Host, StringComparison.OrdinalIgnoreCase))
            return false;
        string[] parts = p.SftpPinnedHostKey.Split(' ', 2,
            StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length != 2) return false;
        try
        {
            byte[] raw = Convert.FromBase64String(parts[1]);
            string sha = "SHA256:" + Convert.ToBase64String(
                SHA256.HashData(raw)).TrimEnd('=');
            if (!MatchesFingerprint(p.SftpHostFingerprint, sha)) return false;
            string canonical = WirePublicKeyAlgorithm(raw);
            if (parts[0].Equals(canonical, StringComparison.Ordinal)) return false;
            p.SftpPinnedHostKey = canonical + " " + parts[1];
            return true;
        }
        catch (Exception ex) when (ex is FormatException or CryptographicException)
        {
            return false;
        }
    }

    internal static bool HasTrustedKey(NasProfile profile)
    {
        if (profile.Protocol != "SFTP" ||
            string.IsNullOrWhiteSpace(profile.SftpPinnedHostKey) ||
            !profile.SftpTrustedHost.Equals(profile.Host,
                StringComparison.OrdinalIgnoreCase) ||
            profile.SftpTrustedPort != EffectivePort(profile)) return false;
        string[] parts = profile.SftpPinnedHostKey.Split(' ', 2,
            StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length != 2 || parts[0].Length < 5) return false;
        try
        {
            byte[] key = Convert.FromBase64String(parts[1]);
            if (key.Length < 24 || key.Length > 8192) return false;
            if (!parts[0].Equals(WirePublicKeyAlgorithm(key),
                    StringComparison.Ordinal)) return false;
            string sha256 = "SHA256:" + Convert.ToBase64String(
                SHA256.HashData(key)).TrimEnd('=');
            return MatchesFingerprint(profile.SftpHostFingerprint, sha256);
        }
        catch (Exception ex) when (ex is FormatException or CryptographicException)
        { return false; }
    }

    /// <summary>
    /// No user password is used. CanTrust=false aborts the handshake before
    /// SSH authentication, allowing the user to inspect an untrusted key.
    /// </summary>
    internal static async Task<Observation> ObserveAsync(NasProfile profile)
    {
        string host = profile.Host;
        int port = EffectivePort(profile);
        return await Task.Run(() =>
        {
            Observation? observed = null;
            using var client = new SshClient(host, port,
                "nas-mount-host-key-probe", "never-sent");
            client.ConnectionInfo.Timeout = TimeSpan.FromSeconds(9);
            client.HostKeyReceived += (_, e) =>
            {
                try
                {
                    if (!string.IsNullOrWhiteSpace(e.HostKeyName) &&
                        e.HostKey is { Length: > 24 and < 8192 })
                    {
                        byte[] raw = (byte[])e.HostKey.Clone();
                        string sha = "SHA256:" +
                            Convert.ToBase64String(SHA256.HashData(raw)).TrimEnd('=');
                        observed = new Observation(host, port,
                            WirePublicKeyAlgorithm(raw),
                            Convert.ToBase64String(raw), sha);
                    }
                }
                finally { e.CanTrust = false; }
            };
            try { client.Connect(); }
            catch (Exception) when (observed is not null)
            {
                // Expected: rejecting an untrusted key aborts SSH before auth.
            }
            if (observed is null)
                throw new IOException(host + ":" + port +
                    " 서버에서 SSH 호스트 공개키를 받지 못했습니다. " +
                    "SFTP(SSH) 서비스와 포트(일반적으로 22)를 확인하세요.");
            return observed;
        }).WaitAsync(TimeSpan.FromSeconds(13));
    }

    internal static void Approve(NasProfile p, Observation observed)
    {
        if (p.Protocol != "SFTP" ||
            !p.Host.Equals(observed.Host, StringComparison.OrdinalIgnoreCase) ||
            EffectivePort(p) != observed.Port)
            throw new InvalidOperationException("서버 주소/포트가 키 확인 도중 변경됐습니다.");
        // User-supplied out-of-band fingerprint, if any, MUST match.
        if (!string.IsNullOrWhiteSpace(p.SftpHostFingerprint) &&
            !MatchesFingerprint(p.SftpHostFingerprint, observed.Sha256Fingerprint))
            throw new InvalidOperationException(
                "설정된 SSH 서버 키 지문과 실제 서버의 지문이 다릅니다. " +
                "서버 관리자에게 확인하기 전에는 승인할 수 없습니다.");
        string publicKey = WirePublicKeyAlgorithm(
            Convert.FromBase64String(observed.PublicKeyBase64));
        p.SftpPinnedHostKey = publicKey + " " + observed.PublicKeyBase64;
        p.SftpHostFingerprint = observed.Sha256Fingerprint;
        p.SftpTrustedHost = observed.Host;
        p.SftpTrustedPort = observed.Port;
        if (!HasTrustedKey(p))
            throw new InvalidOperationException("서버 공개키 검증에 실패했습니다.");
    }

    internal static string TrustPrompt(Observation o) =>
        "SFTP 서버의 SSH 공개키를 처음 확인했습니다.\n\n" +
        "서버: " + o.Host + ":" + o.Port + "\n" +
        "키 종류: " + o.Algorithm + "\n" +
        "SSH SHA256 지문:\n" + o.Sha256Fingerprint + "\n\n" +
        "가능하면 NAS 관리화면 또는 서버 관리자에게 받은 지문과 비교하세요.\n" +
        "이 키를 승인하면 해당 연결에만 저장하고 다음부터 자동 검증합니다.\n" +
        "서버 키가 바뀌면 재승인 없이 연결하지 않습니다.\n\n" +
        "표시된 SSH 서버 키를 신뢰하고 연결할까요?";

    internal static void SelfTest()
    {
        static void Ensure(bool ok, string detail)
        {
            if (!ok) throw new InvalidOperationException("SFTP_TRUST_FAIL " + detail);
        }
        var p = new NasProfile {
            Protocol = "SFTP", Host = "sftp.example.net", Port = 22,
            SftpHostFingerprint = ""
        };
        byte[] key = TestPublicKey("ssh-ed25519");
        string sha = "SHA256:" + Convert.ToBase64String(
            SHA256.HashData(key)).TrimEnd('=');
        var observed = new Observation(p.Host, p.Port, "ssh-ed25519",
            Convert.ToBase64String(key), sha);
        Ensure(!HasTrustedKey(p), "first-use not pretrusted");
        Approve(p, observed);
        Ensure(HasTrustedKey(p), "approved pin valid");
        Ensure(MatchesFingerprint(p.SftpHostFingerprint, sha), "fingerprint verified");
        var restored = JsonSerializer.Deserialize<NasProfile>(
            JsonSerializer.Serialize(p)) ??
            throw new InvalidOperationException("SFTP_TRUST_FAIL config deserialization");
        Ensure(HasTrustedKey(restored), "trusted pin survives app restart config roundtrip");
        var otherHost = p.Clone(); otherHost.Host = "changed.example.net";
        Ensure(!HasTrustedKey(otherHost), "changed host not trusted");
        var otherPort = p.Clone(); otherPort.Port = 2222;
        Ensure(!HasTrustedKey(otherPort), "changed port not trusted");
        var tampered = p.Clone(); tampered.SftpPinnedHostKey += "AAAA";
        Ensure(!HasTrustedKey(tampered), "tampered key refused");
        var mismatch = new NasProfile {
            Protocol = "SFTP", Host = p.Host, Port = p.Port,
            SftpHostFingerprint = "SHA256:unrelatedServerKey"
        };
        try { Approve(mismatch, observed); throw new InvalidOperationException(
            "SFTP_TRUST_FAIL fingerprint mismatch accepted"); }
        catch (InvalidOperationException e) when (!e.Message.StartsWith("SFTP_TRUST_FAIL",
            StringComparison.Ordinal)) { }
        Ensure(!HasTrustedKey(mismatch), "pin not stored on mismatch");
        Ensure(!HasTrustedKey(new NasProfile { Protocol = "FTP" }),
            "FTP never treated as SSH");
        byte[] rsaBlob = TestPublicKey("ssh-rsa");
        Ensure(WirePublicKeyAlgorithm(rsaBlob) == "ssh-rsa",
            "RSA signature name must not become the host-key algorithm");
        var rsa = new NasProfile { Protocol = "SFTP", Host = p.Host, Port = 22 };
        string rsaSha = "SHA256:" + Convert.ToBase64String(
            SHA256.HashData(rsaBlob)).TrimEnd('=');
        rsa.SftpPinnedHostKey = "rsa-sha2-512 " + Convert.ToBase64String(rsaBlob);
        rsa.SftpHostFingerprint = rsaSha;
        rsa.SftpTrustedHost = rsa.Host;
        rsa.SftpTrustedPort = rsa.Port;
        Ensure(!HasTrustedKey(rsa), "noncanonical RSA pin refused");
        Ensure(RepairPreviousRsaPin(rsa), "old RSA pin repaired");
        Ensure(HasTrustedKey(rsa), "repaired RSA pin accepted");
        Console.WriteLine("SFTP_PINNED_HOST_KEY_POLICY_PASS");
    }
}
