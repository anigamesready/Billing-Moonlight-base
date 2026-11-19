using System.Collections.Concurrent;
using System.Management;
using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace AniGamesReady.HostAgent;

public sealed class HostAgentWorker : BackgroundService
{
    private readonly ILogger<HostAgentWorker> _logger;
    private HostAgentConfig? _config;
    private HttpClient? _api;
    private HttpClient? _sunshine;
    private readonly ConcurrentDictionary<string, Task> _pairingTasks = new();
    private readonly HashSet<string> _closedSessions = new(StringComparer.OrdinalIgnoreCase);
    private DateTimeOffset _lastRegistration = DateTimeOffset.MinValue;

    public HostAgentWorker(ILogger<HostAgentWorker> logger) => _logger = logger;

    public override async Task StartAsync(CancellationToken cancellationToken)
    {
        _config = HostAgentConfig.Load();
        _api = new HttpClient
        {
            BaseAddress = new Uri(EnsureSlash(_config.ApiBaseUrl)),
            Timeout = TimeSpan.FromSeconds(20)
        };

        var handler = new HttpClientHandler
        {
            Credentials = new NetworkCredential(_config.SunshineUsername, _config.SunshinePassword),
            ServerCertificateCustomValidationCallback = (request, certificate, chain, errors) =>
            {
                // The self-signed certificate exception is restricted to loopback.
                var host = request.RequestUri?.Host;
                return host == "127.0.0.1" ||
                       string.Equals(host, "localhost", StringComparison.OrdinalIgnoreCase);
            }
        };
        _sunshine = new HttpClient(handler)
        {
            BaseAddress = new Uri(EnsureSlash(_config.SunshineApiBaseUrl)),
            Timeout = TimeSpan.FromMinutes(6)
        };

        await base.StartAsync(cancellationToken);
        _logger.LogInformation("Ani Remote Host Agent started. Route address: {Address}", _config.TunnelAddress);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await RegisterHostIfNeeded(stoppingToken);
                var response = await PostApi("host/sessions/list",
                    new { deviceToken = _config!.DeviceToken }, stoppingToken);
                await ProcessSessions(response, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Remote control heartbeat failed; the DB watchdog will expire stale leases.");
            }

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(Math.Clamp(_config!.PollIntervalSeconds, 3, 15)), stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }
    }

    private async Task RegisterHostIfNeeded(CancellationToken ct)
    {
        if (DateTimeOffset.UtcNow - _lastRegistration < TimeSpan.FromMinutes(5)) return;
        var metadata = ReadPcMetadata();
        await PostApi("host/register", new
        {
            deviceToken = _config!.DeviceToken,
            tunnelAddress = _config.TunnelAddress,
            agentVersion = "1.0.0",
            metadata
        }, ct);
        _lastRegistration = DateTimeOffset.UtcNow;
    }

    private async Task ProcessSessions(JsonElement response, CancellationToken ct)
    {
        if (!response.TryGetProperty("sessions", out var sessions) || sessions.ValueKind != JsonValueKind.Array)
            return;

        foreach (var session in sessions.EnumerateArray())
        {
            var id = GetString(session, "session_id");
            var status = GetString(session, "status");
            if (string.IsNullOrWhiteSpace(id)) continue;

            if (status == "starting")
                await EnsurePairingTask(id, ct);

            if (status is "ended" or "expired" or "failed")
                await CloseSunshineStreamOnce(id, ct);
        }

        foreach (var key in _pairingTasks.Where(x => x.Value.IsCompleted).Select(x => x.Key).ToList())
            _pairingTasks.TryRemove(key, out _);
        if (_closedSessions.Count > 512) _closedSessions.Clear();
    }

    private async Task EnsurePairingTask(string sessionId, CancellationToken ct)
    {
        var challenge = await PostApi("host/pairing/poll",
            new { deviceToken = _config!.DeviceToken, sessionId }, ct);

        if (!challenge.TryGetProperty("pending", out var pending) || !pending.GetBoolean())
            return;

        var challengeId = GetString(challenge, "challenge_id");
        var pin = GetString(challenge, "pin");
        var clientName = GetString(challenge, "client_name");
        if (string.IsNullOrWhiteSpace(challengeId) || pin.Length != 4 || string.IsNullOrWhiteSpace(clientName))
            return;

        if (_pairingTasks.ContainsKey(challengeId)) return;

        var task = ApprovePairing(sessionId, challengeId, pin, clientName, ct);
        if (!_pairingTasks.TryAdd(challengeId, task))
            return;
    }

    private async Task ApprovePairing(
        string sessionId,
        string challengeId,
        string pin,
        string clientName,
        CancellationToken stoppingToken)
    {
        var succeeded = false;
        string pairingId = "";
        try
        {
            // Sunshine v2026.914 stable exposes pending pairing requests on this authenticated local API.
            var until = DateTimeOffset.UtcNow.AddSeconds(90);
            while (DateTimeOffset.UtcNow < until && !stoppingToken.IsCancellationRequested)
            {
                var pending = await SunshineGet("api/pin", stoppingToken);
                if (pending.TryGetProperty("pairings", out var pairings) && pairings.ValueKind == JsonValueKind.Array)
                {
                    foreach (var item in pairings.EnumerateArray())
                    {
                        var pendingName = GetString(item, "name");
                        if (!string.Equals(pendingName, clientName, StringComparison.Ordinal))
                            continue;

                        pairingId = GetString(item, "id");
                        if (pairingId.Length != 32) continue;

                        using var body = JsonContent(new
                        {
                            pairing_id = pairingId,
                            pin,
                            name = clientName
                        });
                        using var result = await _sunshine!.PostAsync("api/pin", body, stoppingToken);
                        var payload = await result.Content.ReadAsStringAsync(stoppingToken);
                        result.EnsureSuccessStatusCode();
                        using var document = JsonDocument.Parse(payload);
                        succeeded = document.RootElement.TryGetProperty("status", out var status) &&
                                    status.ValueKind == JsonValueKind.True;
                        return;
                    }
                }
                await Task.Delay(TimeSpan.FromSeconds(1), stoppingToken);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Automatic Sunshine pairing failed for session {SessionId}", sessionId);
        }
        finally
        {
            try
            {
                await PostApi("host/pairing/complete", new
                {
                    deviceToken = _config!.DeviceToken,
                    sessionId,
                    challengeId,
                    success = succeeded,
                    sunshinePairingId = pairingId
                }, CancellationToken.None);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Could not report pairing result for {SessionId}", sessionId);
            }
        }
    }

    private async Task CloseSunshineStreamOnce(string sessionId, CancellationToken ct)
    {
        lock (_closedSessions)
        {
            if (!_closedSessions.Add(sessionId)) return;
        }

        try
        {
            using var response = await _sunshine!.PostAsync("api/apps/close", null, ct);
            response.EnsureSuccessStatusCode();
            _logger.LogInformation("Sunshine stream closed for ended remote session {SessionId}", sessionId);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not close Sunshine stream for {SessionId}", sessionId);
        }
    }

    private async Task<JsonElement> PostApi(string path, object value, CancellationToken ct)
    {
        using var body = JsonContent(value);
        using var response = await _api!.PostAsync(path, body, ct);
        var text = await response.Content.ReadAsStringAsync(ct);
        response.EnsureSuccessStatusCode();
        using var doc = JsonDocument.Parse(text);
        return doc.RootElement.Clone();
    }

    private async Task<JsonElement> SunshineGet(string path, CancellationToken ct)
    {
        using var response = await _sunshine!.GetAsync(path, ct);
        var text = await response.Content.ReadAsStringAsync(ct);
        response.EnsureSuccessStatusCode();
        using var doc = JsonDocument.Parse(text);
        return doc.RootElement.Clone();
    }

    private static StringContent JsonContent(object value) =>
        new(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json");

    private static string EnsureSlash(string value) => value.EndsWith('/') ? value : value + "/";

    private static string GetString(JsonElement value, string key) =>
        value.TryGetProperty(key, out var item) && item.ValueKind == JsonValueKind.String
            ? item.GetString() ?? ""
            : "";

    private static Dictionary<string, object> ReadPcMetadata()
    {
        static string Wmi(string query, string property)
        {
            try
            {
                using var searcher = new ManagementObjectSearcher(query);
                foreach (ManagementObject item in searcher.Get())
                    return Convert.ToString(item[property]) ?? "";
            }
            catch { }
            return "";
        }

        var ramText = Wmi("SELECT TotalPhysicalMemory FROM Win32_ComputerSystem", "TotalPhysicalMemory");
        var ramGb = ulong.TryParse(ramText, out var bytes)
            ? Math.Round(bytes / 1073741824d, 1)
            : 0d;

        return new Dictionary<string, object>
        {
            ["os"] = "Windows",
            ["computerName"] = Environment.MachineName,
            ["cpu"] = Wmi("SELECT Name FROM Win32_Processor", "Name"),
            ["gpu"] = Wmi("SELECT Name FROM Win32_VideoController", "Name"),
            ["ramGB"] = ramGb,
            ["cpuThreads"] = Environment.ProcessorCount
        };
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        _api?.Dispose();
        _sunshine?.Dispose();
        await base.StopAsync(cancellationToken);
    }
}
