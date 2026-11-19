using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace AniGamesReady.HostAgent;

public sealed class HostAgentConfig
{
    public string ApiBaseUrl { get; set; } = "https://wzkjpycjizexxcxrfdvr.supabase.co/functions/v1/moonlight-api";
    public string DeviceTokenProtected { get; set; } = "";
    public string TunnelAddress { get; set; } = "";
    public int TunnelPort { get; set; } = 47989;
    public string SunshineApiBaseUrl { get; set; } = "https://127.0.0.1:47990";
    public string SunshineUsername { get; set; } = "admin";
    public string SunshinePasswordProtected { get; set; } = "";
    public int PollIntervalSeconds { get; set; } = 5;

    public string DeviceToken => Unprotect(DeviceTokenProtected);
    public string SunshinePassword => Unprotect(SunshinePasswordProtected);

    public static string ConfigPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                     "AniGamesReady", "HostAgent", "config.json");

    public static HostAgentConfig Load()
    {
        if (!File.Exists(ConfigPath))
            throw new FileNotFoundException("Host Agent belum dikonfigurasi. Jalankan install-host-agent.ps1 sebagai Administrator.", ConfigPath);

        var config = JsonSerializer.Deserialize<HostAgentConfig>(File.ReadAllText(ConfigPath),
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true })
            ?? throw new InvalidDataException("File config Host Agent tidak valid.");

        if (string.IsNullOrWhiteSpace(config.DeviceToken) || config.DeviceToken.Length < 32)
            throw new InvalidDataException("Device token Windows tidak valid.");
        if (string.IsNullOrWhiteSpace(config.TunnelAddress))
            throw new InvalidDataException("TunnelAddress belum diatur. Isi IP WireGuard atau alamat publik yang dapat dijangkau.");
        if (!Uri.TryCreate(config.SunshineApiBaseUrl, UriKind.Absolute, out var sunshineUri) ||
            (sunshineUri.Host != "127.0.0.1" && !string.Equals(sunshineUri.Host, "localhost", StringComparison.OrdinalIgnoreCase)))
            throw new InvalidDataException("Sunshine API hanya boleh diarahkan ke localhost demi keamanan.");
        return config;
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
