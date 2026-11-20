using System.Security.Cryptography;
using System.Net;
using System.Net.NetworkInformation;
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

        string transport = (config.MediaTransport ?? "").Trim().ToLowerInvariant();
        if (transport == "wireguard")
        {
            if (!IPAddress.TryParse(config.MediaAddress, out var mediaIp) ||
                !IsPrivateOverlayAddress(mediaIp))
                throw new InvalidDataException("MediaAddress wajib berupa IP privat WireGuard yang diterima DB Android.");
        }
        else if (transport == "cloudflare_mesh")
        {
            // The Host Agent discovers the current 100.96.0.0/12 address from
            // the Cloudflare One Client adapter on every heartbeat. Static values
            // are optional and, when supplied, must be inside the exact Mesh range.
            if (!string.IsNullOrWhiteSpace(config.MediaAddress) &&
                (!IPAddress.TryParse(config.MediaAddress, out var meshIp) ||
                 !IsCloudflareMeshAddress(meshIp)))
                throw new InvalidDataException("MediaAddress Cloudflare Mesh harus berada di 100.96.0.0/12.");
        }
        else
        {
            throw new InvalidDataException("MediaTransport harus wireguard atau cloudflare_mesh.");
        }
        if (config.MediaPort is < 1 or > 65535)
            throw new InvalidDataException("MediaPort tidak valid.");
        if (!IsSupportedMediaTransport(transport))
            throw new InvalidDataException("MediaTransport harus wireguard atau cloudflare_mesh.");

        if (!Uri.TryCreate(config.SunshineApiBaseUrl, UriKind.Absolute, out var sunshineUri) ||
            sunshineUri.Scheme != Uri.UriSchemeHttps ||
            (sunshineUri.Host != "127.0.0.1" &&
             !string.Equals(sunshineUri.Host, "localhost", StringComparison.OrdinalIgnoreCase)) ||
            sunshineUri.Port != 47990)
            throw new InvalidDataException("Sunshine API hanya boleh https://127.0.0.1:47990.");

        return config;
    }

    public string ResolveMediaAddress()
    {
        if (string.Equals(MediaTransport, "wireguard", StringComparison.OrdinalIgnoreCase))
            return MediaAddress.Trim();

        if (!string.Equals(MediaTransport, "cloudflare_mesh", StringComparison.OrdinalIgnoreCase))
            return "";

        foreach (var network in NetworkInterface.GetAllNetworkInterfaces()
                     .Where(n => n.OperationalStatus == OperationalStatus.Up)
                     .OrderBy(n => n.Name, StringComparer.OrdinalIgnoreCase))
        {
            string identity = network.Name + " " + network.Description;
            if (!identity.Contains("Cloudflare", StringComparison.OrdinalIgnoreCase) &&
                !identity.Contains("WARP", StringComparison.OrdinalIgnoreCase))
                continue;

            foreach (var address in network.GetIPProperties().UnicastAddresses)
            {
                if (IsCloudflareMeshAddress(address.Address))
                    return address.Address.ToString();
            }
        }
        return "";
    }

    public static bool IsSupportedMediaTransport(string? transport)
    {
        string value = (transport ?? "").Trim();
        return value.Equals("wireguard", StringComparison.OrdinalIgnoreCase) ||
               value.Equals("cloudflare_mesh", StringComparison.OrdinalIgnoreCase);
    }

    public static bool IsCloudflareMeshAddress(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        byte[] bytes = address.GetAddressBytes();
        // Cloudflare Mesh allocates from 100.96.0.0/12 (100.96–100.111).
        return bytes.Length == 4 && bytes[0] == 100 && bytes[1] >= 96 && bytes[1] <= 111;
    }

    private static bool IsPrivateOverlayAddress(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        byte[] bytes = address.GetAddressBytes();
        if (bytes.Length == 16)
            return (bytes[0] & 0xfe) == 0xfc;
        if (bytes.Length != 4) return false;
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) ||
            (bytes[0] == 192 && bytes[1] == 168);
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
