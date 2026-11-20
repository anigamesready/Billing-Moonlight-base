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
            Timeout = TimeSpan.FromSeconds(20)
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
                }

                JsonElement commandResponse = await PostRpc(
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
        return await PostRpc("remote_play_host_heartbeat", new
        {
            p_sunshine_ready = sunshineReady,
            p_media_address = _config!.MediaAddress,
            p_agent_version = AgentVersion
        }, _config.HostToken, ct);
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
                    // Sunshine is already a service. A successful API health check lets the
                    // backend atomically move this session from starting -> active on ACK.
                    success = await IsSunshineReady(ct);
                    if (!success) error = "SUNSHINE_NOT_READY";
                    break;

                case "stop_stream":
                    await CloseSunshineStream(ct);
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
