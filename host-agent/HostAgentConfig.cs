using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace AniGamesReady.HostAgent;

public sealed class HostAgentConfig
{
    public string ApiBaseUrl { get; set; } = "";
    public string HostTokenProtected { get; set; } = "";
    public string EnrollmentCodeProtected { get; set; } = "";
    public bool Enrolled { get; set; }
    public string MediaAddress { get; set; } = "";
    public int MediaPort { get; set; } = 47989;
    public string MediaTransport { get; set; } = "wireguard";
    public string SunshineApiBaseUrl { get; set; } = "https://127.0.0.1:47990";
    public string SunshineUsername { get; set; } = "admin";
    public string SunshinePasswordProtected { get; set; } = "";
    public int PollIntervalSeconds { get; set; } = 8;

    public string HostToken => Unprotect(HostTokenProtected);
    public string EnrollmentCode => Unprotect(EnrollmentCodeProtected);
    public string SunshinePassword => Unprotect(SunshinePasswordProtected);

    public static string ConfigPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                     "AniGamesReady", "HostAgent", "config.json");

    public static HostAgentConfig Load()
    {
        if (!File.Exists(ConfigPath))
            throw new FileNotFoundException(
                "Host Agent belum dikonfigurasi. Jalankan install-host-agent.ps1 sebagai Administrator.",
                ConfigPath);

        var config = JsonSerializer.Deserialize<HostAgentConfig>(File.ReadAllText(ConfigPath),
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true })
            ?? throw new InvalidDataException("File config Host Agent tidak valid.");

        if (!Uri.TryCreate(config.ApiBaseUrl, UriKind.Absolute, out var apiUri) ||
            apiUri.Scheme != Uri.UriSchemeHttps || string.IsNullOrWhiteSpace(apiUri.Host) ||
            apiUri.UserInfo.Length > 0 || !string.IsNullOrEmpty(apiUri.Query) ||
            !string.IsNullOrEmpty(apiUri.Fragment) ||
            (apiUri.AbsolutePath != "/" && apiUri.AbsolutePath != "" &&
             apiUri.AbsolutePath != "/functions/v1/ani-games-api") ||
            apiUri.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase) ||
            apiUri.Host.Equals("127.0.0.1", StringComparison.OrdinalIgnoreCase) ||
            apiUri.Host.EndsWith(".trycloudflare.com", StringComparison.OrdinalIgnoreCase) ||
            apiUri.Host.Contains("supabase.co", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("API harus memakai HTTPS gateway DB Android stabil yang sama dengan aplikasi Ani.");

        if (config.Enrolled)
        {
            if (string.IsNullOrWhiteSpace(config.HostToken) ||
                config.HostToken.Length != 64 ||
                !config.HostToken.All(Uri.IsHexDigit))
                throw new InvalidDataException("Host token Windows tidak valid.");
        }
        else if (string.IsNullOrWhiteSpace(config.EnrollmentCode))
        {
            throw new InvalidDataException("Kode enrollment Admin belum tersedia.");
        }

        if (!System.Net.IPAddress.TryParse(config.MediaAddress, out var mediaIp) ||
            !(mediaIp.GetAddressBytes().Length == 16 ? (mediaIp.GetAddressBytes()[0] & 0xfe) == 0xfc :
                mediaIp.GetAddressBytes()[0] == 10 ||
                (mediaIp.GetAddressBytes()[0] == 172 && mediaIp.GetAddressBytes()[1] >= 16 && mediaIp.GetAddressBytes()[1] <= 31) ||
                (mediaIp.GetAddressBytes()[0] == 192 && mediaIp.GetAddressBytes()[1] == 168)))
            throw new InvalidDataException("MediaAddress wajib berupa IP privat WireGuard/VPN yang diterima DB Android.");
        if (config.MediaPort is < 1 or > 65535)
            throw new InvalidDataException("MediaPort tidak valid.");
        if (!string.Equals(config.MediaTransport, "wireguard", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Saat ini Host Agent hanya menerima rute media WireGuard yang sudah disiapkan.");

        if (!Uri.TryCreate(config.SunshineApiBaseUrl, UriKind.Absolute, out var sunshineUri) ||
            sunshineUri.Scheme != Uri.UriSchemeHttps ||
            (sunshineUri.Host != "127.0.0.1" &&
             !string.Equals(sunshineUri.Host, "localhost", StringComparison.OrdinalIgnoreCase)) ||
            sunshineUri.Port != 47990)
            throw new InvalidDataException("Sunshine API hanya boleh https://127.0.0.1:47990.");

        return config;
    }

    public void Save()
    {
        var folder = Path.GetDirectoryName(ConfigPath)!;
        Directory.CreateDirectory(folder);
        var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(ConfigPath, json, new UTF8Encoding(false));
    }

    public static string Protect(string value)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        var protectedBytes = ProtectedData.Protect(bytes, null, DataProtectionScope.LocalMachine);
        return Convert.ToBase64String(protectedBytes);
    }

    private static string Unprotect(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return "";
        var protectedBytes = Convert.FromBase64String(value);
        return Encoding.UTF8.GetString(ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.LocalMachine));
    }
}
