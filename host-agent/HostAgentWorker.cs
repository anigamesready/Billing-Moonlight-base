using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace AniGamesReady.HostAgent;

/// <summary>
/// Windows service bridging the DB Android control plane to local Sunshine.
/// The DB HTTP gateway carries control JSON only; it never carries GameStream media.
/// </summary>
public sealed class HostAgentWorker : BackgroundService
{
    private const string AgentVersion = "0.2.1";
    private readonly ILogger<HostAgentWorker> _logger;
    private HostAgentConfig? _config;
    private HttpClient? _api;
    private HttpClient? _sunshine;
    private volatile string _activeSessionId = "";
    private int _pairingWorkerRunning;

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
                // Sunshine's self-signed Web UI certificate is trusted only on loopback.
                string? host = request.RequestUri?.Host;
                return host == "127.0.0.1" ||
                       string.Equals(host, "localhost", StringComparison.OrdinalIgnoreCase);
            }
        };
        _sunshine = new HttpClient(handler)
        {
            BaseAddress = new Uri(EnsureSlash(_config.SunshineApiBaseUrl)),
            Timeout = TimeSpan.FromMinutes(3)
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

                JsonElement hostState = await SendHeartbeat(stoppingToken);
                if (hostState.TryGetProperty("permission_enabled", out var allowed) &&
                    allowed.ValueKind == JsonValueKind.False)
                {
                    _logger.LogWarning("Remote Play permission disabled by Admin; closing Sunshine stream");
                    await CloseSunshineStream(stoppingToken);
                    _activeSessionId = "";
                }

                JsonElement commandResponse = await PostRpc(
                    "remote_play_host_next_command", new { }, _config.HostToken, stoppingToken);
                await ProcessCommand(commandResponse, stoppingToken);
                QueuePairingWorker(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex,
                    "DB Android control-plane request failed. No new session can be authorized while the host is offline.");
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
        // DB Android creates the host token and stores its SHA-256 hash.
        // The raw token is returned once and encrypted locally with Windows DPAPI.
        JsonElement result = await PostRpc("remote_play_host_enroll", new
        {
            p_enrollment_code = _config!.EnrollmentCode,
            p_agent_version = AgentVersion
        }, null, ct);

        string hostToken = GetString(result, "host_token");
        if (hostToken.Length != 64 || !hostToken.All(Uri.IsHexDigit))
            throw new InvalidOperationException("HOST_ENROLLMENT_RESPONSE_INVALID");

        _config.HostTokenProtected = HostAgentConfig.Protect(hostToken);
        _config.EnrollmentCodeProtected = "";
        _config.Enrolled = true;
        _config.Save();
        _logger.LogInformation("Host Agent enrolled with DB Android; device ID {DeviceId}",
            GetString(result, "device_id"));
    }

    private async Task<JsonElement> SendHeartbeat(CancellationToken ct)
    {
        bool sunshineReady = await IsSunshineReady(ct);
        JsonElement response = await PostRpc("remote_play_host_heartbeat", new
        {
            p_sunshine_ready = sunshineReady,
            p_media_address = _config!.MediaAddress,
            p_agent_version = AgentVersion
        }, _config.HostToken, ct);

        // The DB Android heartbeat returns the authoritative active_session.
        // This also recovers the session after a Windows service restart.
        if (response.TryGetProperty("active_session", out var active) &&
            active.ValueKind == JsonValueKind.Object)
        {
            string sessionId = GetString(active, "id");
            if (!string.IsNullOrWhiteSpace(sessionId))
                _activeSessionId = sessionId;
        }
        else
        {
            _activeSessionId = "";
        }
        return response;
    }

    private async Task<bool> IsSunshineReady(CancellationToken ct)
    {
        try
        {
            using var response = await _sunshine!.GetAsync("api/apps", ct);
            if (!response.IsSuccessStatusCode) return false;
            string text = await response.Content.ReadAsStringAsync(ct);
            using var document = JsonDocument.Parse(text);
            return document.RootElement.ValueKind == JsonValueKind.Array ||
                   (document.RootElement.ValueKind == JsonValueKind.Object &&
                    document.RootElement.TryGetProperty("apps", out var apps) &&
                    apps.ValueKind == JsonValueKind.Array);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
        {
            _logger.LogWarning("Local Sunshine API unavailable: {Reason}", ex.Message);
            return false;
        }
    }

    private async Task ProcessCommand(JsonElement response, CancellationToken ct)
    {
        if (!response.TryGetProperty("command", out var command) ||
            command.ValueKind != JsonValueKind.Object)
            return;

        // Exact JSON keys returned by api.remote_play_host_next_command in DB Android v0.1.
        string commandId = GetString(command, "id");
        string type = GetString(command, "command");
        string sessionId = GetString(command, "session_id");
        if (string.IsNullOrWhiteSpace(commandId) || string.IsNullOrWhiteSpace(type))
            return;

        bool success = false;
        string error = "";
        try
        {
            switch (type)
            {
                case "start_stream":
                    // The Moonlight client launches GameStream after the DB-authorized
                    // session is accepted. The Agent confirms Sunshine readiness and
                    // retains the session ID so pairing can be approved by pairing_id.
                    success = await IsSunshineReady(ct);
                    if (success) _activeSessionId = sessionId;
                    else error = "SUNSHINE_NOT_READY";
                    break;

                case "stop_stream":
                    await CloseSunshineStream(ct);
                    if (string.Equals(_activeSessionId, sessionId, StringComparison.OrdinalIgnoreCase))
                        _activeSessionId = "";
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
            _logger.LogWarning(ex, "Host command {Command} failed for session {SessionId}", type, sessionId);
        }

        await PostRpc("remote_play_host_ack", new
        {
            p_command_id = commandId,
            p_status = success ? "completed" : "failed",
            p_result = new { session_id = sessionId, error, agent_version = AgentVersion }
        }, _config!.HostToken, ct);
    }

    private void QueuePairingWorker(CancellationToken ct)
    {
        string sessionId = _activeSessionId;
        if (string.IsNullOrWhiteSpace(sessionId) ||
            Interlocked.CompareExchange(ref _pairingWorkerRunning, 1, 0) != 0)
            return;

        _ = Task.Run(async () =>
        {
            try
            {
                await ProcessPendingPairing(sessionId, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
            catch (Exception ex)
            {
                // Never log the challenge PIN, bearer token, or Sunshine credentials.
                _logger.LogWarning(ex, "Automatic Sunshine pairing check failed for session {SessionId}", sessionId);
            }
            finally
            {
                Interlocked.Exchange(ref _pairingWorkerRunning, 0);
            }
        }, ct);
    }

    private async Task ProcessPendingPairing(string sessionId, CancellationToken ct)
    {
        if (!string.Equals(_activeSessionId, sessionId, StringComparison.OrdinalIgnoreCase)) return;

        JsonElement challenge = await PostRpc("remote_play_host_pairing_poll",
            new { p_session_id = sessionId }, _config!.HostToken, ct);
        if (!challenge.TryGetProperty("pending", out var pending) || pending.ValueKind != JsonValueKind.True)
            return;

        string challengeId = GetString(challenge, "challenge_id");
        string clientName = GetString(challenge, "client_name");
        string pin = GetString(challenge, "pin");
        if (challengeId.Length != 36 || !Guid.TryParse(challengeId, out _) ||
            pin.Length != 4 || !pin.All(char.IsDigit) ||
            string.IsNullOrWhiteSpace(clientName) || clientName.Length > 128)
        {
            _logger.LogWarning("DB Android returned an invalid pairing challenge for session {SessionId}", sessionId);
            return;
        }

        JsonElement pendingPairings = await GetSunshinePendingPairings(ct);
        if (!pendingPairings.TryGetProperty("pairings", out var pairings) ||
            pairings.ValueKind != JsonValueKind.Array)
            return;

        var matches = new List<(string Id, string Address)>();
        foreach (JsonElement item in pairings.EnumerateArray())
        {
            string name = GetString(item, "name");
            string address = NormalizePeerAddress(GetString(item, "address"));
            if (string.Equals(name, clientName, StringComparison.Ordinal) &&
                !string.IsNullOrWhiteSpace(address))
            {
                matches.Add((GetString(item, "id"), address));
            }
        }

        // Fail closed on an ambiguous Sunshine queue. A unique, exact installation
        // name plus a valid peer address is required; never approve the first item.
        if (matches.Count != 1)
        {
            if (matches.Count > 1)
                _logger.LogWarning("More than one Sunshine pairing request matched the active client; approval withheld.");
            return;
        }

        var selected = matches[0];
        if (selected.Id.Length != 32 || !selected.Id.All(Uri.IsHexDigit) ||
            !string.Equals(_activeSessionId, sessionId, StringComparison.OrdinalIgnoreCase))
            return;

        var payload = new
        {
            pairing_id = selected.Id,
            pin,
            name = clientName
        };
        using var content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json");
        using var response = await _sunshine!.PostAsync("api/pin", content, ct);
        string responseText = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw new HttpRequestException("SUNSHINE_PAIRING_APPROVAL_HTTP_ERROR", null, response.StatusCode);

        bool paired;
        using (var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(responseText) ? "{}" : responseText))
        {
            paired = document.RootElement.TryGetProperty("status", out var status) &&
                status.ValueKind == JsonValueKind.True;
        }

        await PostRpc("remote_play_host_pairing_complete", new
        {
            p_session_id = sessionId,
            p_challenge_id = challengeId,
            p_success = paired,
            p_sunshine_pairing_id = paired ? selected.Id.ToLowerInvariant() : null,
            p_failure_reason = paired ? null : "sunshine_pairing_rejected"
        }, _config.HostToken, ct);

        if (paired)
            _logger.LogInformation("Sunshine paired the authorized Ani client for session {SessionId}", sessionId);
        else
            _logger.LogWarning("Sunshine rejected the authorized Ani pairing challenge for session {SessionId}", sessionId);
    }

    private async Task<JsonElement> GetSunshinePendingPairings(CancellationToken ct)
    {
        using var response = await _sunshine!.GetAsync("api/pin", ct);
        string text = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw new HttpRequestException("SUNSHINE_PAIRING_LIST_HTTP_ERROR", null, response.StatusCode);
        using var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text);
        return document.RootElement.Clone();
    }

    private static string NormalizePeerAddress(string raw)
    {
        if (!IPAddress.TryParse(raw, out var address)) return "";
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        return address.ToString();
    }

    private async Task CloseSunshineStream(CancellationToken ct)
    {
        using var response = await _sunshine!.PostAsync("api/apps/close", null, ct);
        response.EnsureSuccessStatusCode();
    }

    private async Task<JsonElement> PostRpc(
        string function, object body, string? bearerToken, CancellationToken ct)
    {
        using var content = new StringContent(
            JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        using var request = new HttpRequestMessage(HttpMethod.Post, "rest/v1/rpc/" + function)
        {
            Content = content
        };
        request.Headers.Accept.ParseAdd("application/json");
        request.Headers.CacheControl = new System.Net.Http.Headers.CacheControlHeaderValue { NoStore = true };
        if (!string.IsNullOrWhiteSpace(bearerToken))
            request.Headers.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", bearerToken);

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
            catch (JsonException) { }
            throw new HttpRequestException(code, null, response.StatusCode);
        }

        using var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text);
        return document.RootElement.Clone();
    }

    private static string GetString(JsonElement value, string key) =>
        value.ValueKind == JsonValueKind.Object &&
        value.TryGetProperty(key, out var item) && item.ValueKind == JsonValueKind.String
            ? item.GetString() ?? ""
            : "";

    private static string EnsureSlash(string value) => value.EndsWith('/') ? value : value + "/";

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        _api?.Dispose();
        _sunshine?.Dispose();
        await base.StopAsync(cancellationToken);
    }
}
