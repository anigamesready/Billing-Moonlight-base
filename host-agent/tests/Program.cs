using System.Net;
using AniGamesReady.HostAgent;

static void Assert(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException(message);
}

var configPath = HostAgentConfig.ConfigPath;
var configDirectory = Path.GetDirectoryName(configPath)!;
byte[]? original = File.Exists(configPath) ? File.ReadAllBytes(configPath) : null;
try
{
    Assert(HostAgentConfig.IsSupportedMediaTransport("cloudflare_mesh"),
        "Cloudflare Mesh transport must be supported.");
    Assert(HostAgentConfig.IsSupportedMediaTransport("WireGuard"),
        "WireGuard transport must remain supported.");
    Assert(!HostAgentConfig.IsSupportedMediaTransport("public_internet"),
        "Unapproved transports must be rejected.");

    Assert(HostAgentConfig.IsCloudflareMeshAddress(IPAddress.Parse("100.96.0.0")),
        "Mesh range lower boundary rejected.");
    Assert(HostAgentConfig.IsCloudflareMeshAddress(IPAddress.Parse("100.111.255.255")),
        "Mesh range upper boundary rejected.");
    Assert(!HostAgentConfig.IsCloudflareMeshAddress(IPAddress.Parse("100.95.255.254")),
        "Address below Mesh range must be rejected.");
    Assert(!HostAgentConfig.IsCloudflareMeshAddress(IPAddress.Parse("100.112.0.1")),
        "Address above Mesh range must be rejected.");

    var mesh = new HostAgentConfig
    {
        ApiBaseUrl = "https://ani-games-db-android-stage-gateway.anigamesready.workers.dev",
        Enrolled = false,
        EnrollmentCodeProtected = HostAgentConfig.Protect("test-enrollment-code-00000001"),
        MediaTransport = "cloudflare_mesh",
        MediaAddress = "",
        MediaPort = 47989,
        SunshineApiBaseUrl = "https://127.0.0.1:47990",
        SunshineUsername = "admin",
        SunshinePasswordProtected = HostAgentConfig.Protect("test-sunshine-password"),
        PollIntervalSeconds = 8
    };
    mesh.Save();
    var loadedMesh = HostAgentConfig.Load();
    Assert(loadedMesh.MediaTransport == "cloudflare_mesh",
        "HostAgentConfig.Load rejected Cloudflare Mesh transport.");
    var discovered = loadedMesh.ResolveMediaAddress();
    Assert(string.IsNullOrEmpty(discovered) ||
           HostAgentConfig.IsCloudflareMeshAddress(IPAddress.Parse(discovered)),
        "Resolver returned an address outside the Mesh range.");

    mesh.MediaAddress = "100.95.255.254";
    mesh.Save();
    bool invalidMeshRejected = false;
    try { _ = HostAgentConfig.Load(); }
    catch (InvalidDataException) { invalidMeshRejected = true; }
    Assert(invalidMeshRejected, "Static Mesh address outside 100.96.0.0/12 was accepted.");

    var wireguard = new HostAgentConfig
    {
        ApiBaseUrl = mesh.ApiBaseUrl,
        Enrolled = false,
        EnrollmentCodeProtected = mesh.EnrollmentCodeProtected,
        MediaTransport = "wireguard",
        MediaAddress = "10.20.30.40",
        MediaPort = 47989,
        SunshineApiBaseUrl = "https://127.0.0.1:47990",
        SunshineUsername = "admin",
        SunshinePasswordProtected = mesh.SunshinePasswordProtected,
        PollIntervalSeconds = 8
    };
    wireguard.Save();
    var loadedWireguard = HostAgentConfig.Load();
    Assert(loadedWireguard.MediaTransport == "wireguard" &&
           loadedWireguard.ResolveMediaAddress() == "10.20.30.40",
        "Existing WireGuard configuration regressed.");

    Console.WriteLine("PASS: Host Agent accepts Cloudflare Mesh at runtime, validates exact /12 boundaries, rejects adjacent CGNAT, and preserves WireGuard.");
}
finally
{
    if (original is not null)
    {
        Directory.CreateDirectory(configDirectory);
        File.WriteAllBytes(configPath, original);
    }
    else
    {
        if (File.Exists(configPath)) File.Delete(configPath);
        if (Directory.Exists(configDirectory) && !Directory.EnumerateFileSystemEntries(configDirectory).Any())
            Directory.Delete(configDirectory);
    }
}
