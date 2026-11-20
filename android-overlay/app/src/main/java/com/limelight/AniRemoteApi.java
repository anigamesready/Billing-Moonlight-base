package com.limelight;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Control-plane adapter for the existing Ani Games Ready DB Android Node API.
 * This class never transports Moonlight video/audio and contains no DB credentials.
 */
public final class AniRemoteApi {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AniRemoteApi() {}

    public static final class ApiException extends Exception {
        public final int statusCode;
        public final String errorCode;
        ApiException(int statusCode, String errorCode) {
            super(errorCode == null ? ("HTTP " + statusCode) : errorCode);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }
    }

    /** Build-time value is populated from the existing DB Android endpoint setting. */
    private static String apiBaseUrl() throws Exception {
        String raw = BuildConfig.DB_ANDROID_API_BASE_URL == null
                ? "" : BuildConfig.DB_ANDROID_API_BASE_URL.trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty()) throw new ApiException(0, "DB_ANDROID_API_ENDPOINT_NOT_CONFIGURED");

        URI uri = URI.create(raw);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new ApiException(0, "DB_ANDROID_API_ENDPOINT_INVALID");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") ||
                host.equals("0.0.0.0") || host.endsWith(".trycloudflare.com")) {
            throw new ApiException(0, "DB_ANDROID_API_ENDPOINT_NOT_PRODUCTION");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!path.isEmpty() && !path.equals("/") &&
                !path.equals("/functions/v1/ani-games-api")) {
            throw new ApiException(0, "DB_ANDROID_API_ENDPOINT_PATH_INVALID");
        }
        return raw;
    }

    private static JSONObject rpc(String name, JSONObject body, String bearerToken) throws Exception {
        HttpURLConnection connection = null;
        try {
            String endpoint = apiBaseUrl() + "/rest/v1/rpc/" + name;
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (bearerToken != null && !bearerToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = stream == null ? "{}" : readAll(stream);
            JSONObject result = responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText);
            if (status < 200 || status >= 300) {
                throw new ApiException(status, result.optString("error",
                        result.optString("message", "REMOTE_PLAY_REQUEST_FAILED")));
            }
            if (result.has("ok") && !result.optBoolean("ok", false)) {
                throw new ApiException(status, result.optString("error", "REMOTE_PLAY_REQUEST_FAILED"));
            }
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder value = new StringBuilder(64);
        for (byte b : bytes) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    private static String sha256(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte b : digest) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    public static JSONObject login(Context context, String rentalCode, String activationPin,
                                   String accessToken, String refreshToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_rental_code", rentalCode.trim());
        body.put("p_activation_pin", activationPin);
        body.put("p_client_install_id", AniSecurePrefs.getClientId(context));
        body.put("p_device_name", Build.MANUFACTURER + " " + Build.MODEL);
        body.put("p_app_version", BuildConfig.VERSION_NAME);
        body.put("p_access_token_hash", sha256(accessToken));
        body.put("p_refresh_token_hash", sha256(refreshToken));
        return rpc("remote_play_login", body, null);
    }

    public static JSONObject refresh(String oldRefreshToken, String newAccessToken,
                                      String newRefreshToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_refresh_token_hash", sha256(oldRefreshToken));
        body.put("p_new_access_token_hash", sha256(newAccessToken));
        body.put("p_new_refresh_token_hash", sha256(newRefreshToken));
        return rpc("remote_play_refresh", body, null);
    }

    public static JSONObject listHosts(String clientToken) throws Exception {
        return rpc("remote_play_dashboard", new JSONObject(), clientToken);
    }

    public static JSONObject startSession(String clientToken, String hostDeviceId,
                                          String idempotencyKey) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_device_id", hostDeviceId);
        body.put("p_idempotency_key", idempotencyKey);
        return rpc("remote_play_request_session", body, clientToken);
    }

    public static JSONObject markActive(String clientToken, String sessionId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_session_id", sessionId);
        return rpc("remote_play_session_mark_streaming", body, clientToken);
    }

    public static JSONObject heartbeat(String clientToken, String sessionId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_session_id", sessionId);
        return rpc("remote_play_session_heartbeat", body, clientToken);
    }

    public static JSONObject stopSession(String clientToken, String sessionId, String reason) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_session_id", sessionId);
        body.put("p_reason", reason == null ? "client_stop" : reason);
        return rpc("remote_play_end_session", body, clientToken);
    }

    public static JSONObject preparePairing(String clientToken, String sessionId,
                                            String pin, String clientName) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_session_id", sessionId);
        body.put("p_pin", pin);
        body.put("p_client_name", clientName);
        return rpc("remote_play_pairing_prepare", body, clientToken);
    }

    public static JSONObject topupInfo(String clientToken) throws Exception {
        return rpc("remote_play_topup_info", new JSONObject(), clientToken);
    }

    public static JSONObject logout(String clientToken) throws Exception {
        return rpc("remote_play_logout", new JSONObject(), clientToken);
    }
}
