using System.Collections.Concurrent;
using System.Management;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace AniGamesReady.HostAgent;

/// <summary>
/// Windows-only bridge between the DB Android control plane and local Sunshine.
/// GameStream traffic is never sent through this HTTP client.
/// </summary>
public sealed class HostAgentWorker : BackgroundService
{
    private const string AgentVersion = "0.2.0";
    private readonly ILogger<HostAgentWorker> _logger;
    private readonly ConcurrentDictionary<string, Task> _pairingTasks = new();
    private readonly HashSet<string> _closedSessions = new(StringComparer.OrdinalIgnoreCase);
    private HostAgentConfig? _config;
    private HttpClient? _api;
    private HttpClient? _sunshine;

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
                // Only allow Sunshine's self-signed certificate on loopback.
                var host = request.RequestUri?.Host;
                return host == "127.0.0.1" ||
                       string.Equals(host, "localhost", StringComparison.OrdinalIgnoreCase);
            }
        };
        _sunshine = new HttpClient(handler)
        {
            BaseAddress = new Uri(EnsureSlash(_config.SunshineApiBaseUrl)),
            Timeout = TimeSpan.FromSeconds(30)
        };

        await base.StartAsync(cancellationToken);
        _logger.LogInformation("Ani Games Ready Remote Host Agent {Version} started", AgentVersion);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                if (!_config!.Enrolled)
                    await EnrollHost(stoppingToken);

                var status = await SendHeartbeat(stoppingToken);
                if (IsExplicitlyDisabled(status))
                {
                    _logger.LogWarning("Host permission is disabled by Admin; closing Sunshine stream");
                    await CloseSunshineStream(stoppingToken);
                }

                var sessions = await PostRpc("remote_play_host_sessions", new { }, _config.HostToken, stoppingToken);
                await ProcessSessions(sessions, stoppingToken);

                var commandResponse = await PostRpc(
                    "remote_play_host_next_command", new { }, _config.HostToken, stoppingToken);
                await ProcessCommand(commandResponse, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex,
                    "DB Android control-plane request failed; no new stream is authorized. Server lease/watchdog remains authoritative.");
            }

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(Math.Clamp(_config!.PollIntervalSeconds, 3, 30)), stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }
    }

    private async Task EnrollHost(CancellationToken ct)
    {
        var result = await PostRpc("remote_play_host_enroll", new
        {
            p_enrollment_code = _config!.EnrollmentCode,
            p_host_token_hash = Sha256(_config.HostToken),
            p_agent_version = AgentVersion,
            p_media_route = MediaRoute(),
            p_metadata = ReadPcMetadata()
        }, null, ct);

        if (result.TryGetProperty("ok", out var ok) && ok.ValueKind == JsonValueKind.False)
            throw new InvalidOperationException("HOST_ENROLLMENT_REJECTED");

        _config.Enrolled = true;
        _config.EnrollmentCodeProtected = "";
        _config.Save();
        _logger.LogInformation("Host Agent enrollment completed");
    }

    private async Task<JsonElement> SendHeartbeat(CancellationToken ct)
    {
        bool sunshineReady = false;
        try
        {
            using var response = await _sunshine!.GetAsync("api/serverinfo", ct);
            sunshineReady = response.IsSuccessStatusCode;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            _logger.LogWarning("Sunshine local API is not ready: {Reason}", ex.Message);
        }

        return await PostRpc("remote_play_host_heartbeat", new
        {
            p_agent_version = AgentVersion,
            p_sunshine_ready = sunshineReady,
            p_media_route = MediaRoute(),
            p_metadata = ReadPcMetadata()
        }, _config!.HostToken, ct);
    }

    private async Task ProcessSessions(JsonElement response, CancellationToken ct)
    {
        if (!TryGetArray(response, "sessions", out var sessions)) return;

        foreach (var session in sessions.EnumerateArray())
        {
            string id = GetString(session, "session_id");
            string state = GetString(session, "status");
            if (string.IsNullOrWhiteSpace(id)) continue;

            if (state is "starting" or "pairing")
                await EnsurePairingTask(id, ct);

            if (state is "ended" or "expired" or "failed" or "revoked")
                await CloseSunshineStreamOnce(id, ct);
        }

        foreach (var key in _pairingTasks.Where(x => x.Value.IsCompleted).Select(x => x.Key).ToList())
            _pairingTasks.TryRemove(key, out _);

        lock (_closedSessions)
        {
            if (_closedSessions.Count > 512) _closedSessions.Clear();
        }
    }

    private async Task EnsurePairingTask(string sessionId, CancellationToken ct)
    {
        var challenge = await PostRpc("remote_play_host_pairing_poll",
            new { p_session_id = sessionId }, _config!.HostToken, ct);

        if (!challenge.TryGetProperty("pending", out var pending) ||
            pending.ValueKind != JsonValueKind.True)
            return;

        string challengeId = GetString(challenge, "challenge_id");
        string pin = GetString(challenge, "pin");
        string clientName = GetString(challenge, "client_name");
        if (string.IsNullOrWhiteSpace(challengeId) || pin.Length != 4 ||
            !pin.All(char.IsDigit) || string.IsNullOrWhiteSpace(clientName))
            return;

        string taskKey = sessionId + ":" + challengeId;
        if (_pairingTasks.ContainsKey(taskKey)) return;

        var task = ApprovePairing(sessionId, challengeId, pin, clientName, ct);
        if (!_pairingTasks.TryAdd(taskKey, task)) return;
    }

    private async Task ApprovePairing(
        string sessionId, string challengeId, string pin, string clientName, CancellationToken ct)
    {
        bool succeeded = false;
        string sunshinePairingId = "";
        try
        {
            var until = DateTimeOffset.UtcNow.AddSeconds(90);
            while (DateTimeOffset.UtcNow < until && !ct.IsCancellationRequested)
            {
                var pending = await SunshineGet("api/pin", ct);
                if (pending.TryGetProperty("pairings", out var pairings) &&
                    pairings.ValueKind == JsonValueKind.Array)
                {
                    foreach (var item in pairings.EnumerateArray())
                    {
                        if (!string.Equals(GetString(item, "name"), clientName, StringComparison.Ordinal))
                            continue;

                        sunshinePairingId = GetString(item, "id");
                        if (sunshinePairingId.Length != 32) continue;

                        using var body = JsonContent(new
                        {
                            pairing_id = sunshinePairingId,
                            pin,
                            name = clientName
                        });
                        using var response = await _sunshine!.PostAsync("api/pin", body, ct);
                        string text = await response.Content.ReadAsStringAsync(ct);
                        response.EnsureSuccessStatusCode();
                        using var document = JsonDocument.Parse(text);
                        succeeded = document.RootElement.TryGetProperty("status", out var result) &&
                                    result.ValueKind == JsonValueKind.True;
                        return;
                    }
                }
                await Task.Delay(TimeSpan.FromSeconds(1), ct);
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
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
                await PostRpc("remote_play_host_pairing_complete", new
                {
                    p_session_id = sessionId,
                    p_challenge_id = challengeId,
                    p_success = succeeded,
                    p_sunshine_pairing_id = sunshinePairingId
                }, _config!.HostToken, CancellationToken.None);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Could not report pairing result for session {SessionId}", sessionId);
            }
        }
    }

    private async Task ProcessCommand(JsonElement response, CancellationToken ct)
    {
        if (!response.TryGetProperty("command", out var command) ||
            command.ValueKind != JsonValueKind.Object)
            return;

        string commandId = GetString(command, "command_id");
        string type = GetString(command, "command_type");
        string sessionId = GetString(command, "session_id");
        bool success = false;
        string error = "";

        try
        {
            switch (type)
            {
                case "stop_stream":
                case "close_stream":
                case "revoke_host":
                    await CloseSunshineStream(ct);
                    success = true;
                    break;
                case "refresh_host":
                    success = true;
                    break;
                default:
                    error = "UNSUPPORTED_HOST_COMMAND";
                    break;
            }
        }
        catch (Exception ex)
        {
            error = ex.GetType().Name;
            _logger.LogWarning(ex, "Host command {CommandType} failed", type);
        }

        if (!string.IsNullOrWhiteSpace(commandId))
        {
            await PostRpc("remote_play_host_ack", new
            {
                p_command_id = commandId,
                p_status = success ? "completed" : "failed",
                p_result = new { session_id = sessionId, error }
            }, _config!.HostToken, ct);
        }
    }

    private async Task CloseSunshineStreamOnce(string sessionId, CancellationToken ct)
    {
        lock (_closedSessions)
        {
            if (!_closedSessions.Add(sessionId)) return;
        }
        await CloseSunshineStream(ct);
        _logger.LogInformation("Sunshine stream closed for ended session {SessionId}", sessionId);
    }

    private async Task CloseSunshineStream(CancellationToken ct)
    {
        using var response = await _sunshine!.PostAsync("api/apps/close", null, ct);
        response.EnsureSuccessStatusCode();
    }

    private async Task<JsonElement> PostRpc(
        string function, object body, string? bearerToken, CancellationToken ct)
    {
        using var content = JsonContent(body);
        using var request = new HttpRequestMessage(HttpMethod.Post, "rest/v1/rpc/" + function)
        {
            Content = content
        };
        request.Headers.Accept.ParseAdd("application/json");
        request.Headers.CacheControl = new System.Net.Http.Headers.CacheControlHeaderValue { NoStore = true };
        if (!string.IsNullOrWhiteSpace(bearerToken))
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", bearerToken);

        using var response = await _api!.SendAsync(request, ct);
        string text = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
        {
            string code = "DB_ANDROID_API_ERROR";
            try
            {
                using var errorDocument = JsonDocument.Parse(text);
                code = GetString(errorDocument.RootElement, "error");
                if (string.IsNullOrWhiteSpace(code)) code = GetString(errorDocument.RootElement, "message");
            }
            catch { }
            throw new HttpRequestException(code, null, response.StatusCode);
        }

        using var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text);
        return document.RootElement.Clone();
    }

    private async Task<JsonElement> SunshineGet(string path, CancellationToken ct)
    {
        using var response = await _sunshine!.GetAsync(path, ct);
        string text = await response.Content.ReadAsStringAsync(ct);
        response.EnsureSuccessStatusCode();
        using var document = JsonDocument.Parse(text);
        return document.RootElement.Clone();
    }

    private object MediaRoute() => new
    {
        transport = _config!.MediaTransport,
        address = _config.MediaAddress,
        port = _config.MediaPort
    };

    private static bool IsExplicitlyDisabled(JsonElement status)
    {
        return (status.TryGetProperty("host_allowed", out var allowed) && allowed.ValueKind == JsonValueKind.False) ||
               (status.TryGetProperty("permission_enabled", out var enabled) && enabled.ValueKind == JsonValueKind.False);
    }

    private static bool TryGetArray(JsonElement value, string property, out JsonElement array)
    {
        if (value.ValueKind == JsonValueKind.Array)
        {
            array = value;
            return true;
        }
        if (value.ValueKind == JsonValueKind.Object &&
            value.TryGetProperty(property, out array) && array.ValueKind == JsonValueKind.Array)
            return true;
        array = default;
        return false;
    }

    private static StringContent JsonContent(object value) =>
        new(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json");

    private static string EnsureSlash(string value) => value.EndsWith('/') ? value : value + "/";

    private static string GetString(JsonElement value, string key) =>
        value.ValueKind == JsonValueKind.Object &&
        value.TryGetProperty(key, out var item) && item.ValueKind == JsonValueKind.String
            ? item.GetString() ?? ""
            : "";

    private static string Sha256(string value)
    {
        byte[] digest = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(digest).ToLowerInvariant();
    }

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

        string ramText = Wmi("SELECT TotalPhysicalMemory FROM Win32_ComputerSystem", "TotalPhysicalMemory");
        double ramGb = ulong.TryParse(ramText, out var bytes)
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
