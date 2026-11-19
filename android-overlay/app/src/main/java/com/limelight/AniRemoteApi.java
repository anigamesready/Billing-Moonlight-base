package com.limelight;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small control-plane client. Never use this API to transport game video/audio. */
public final class AniRemoteApi {
    public static final String BASE_URL =
            "https://wzkjpycjizexxcxrfdvr.supabase.co/functions/v1/moonlight-api";
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

    private static JSONObject post(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = input == null ? "{}" : readAll(input);
            JSONObject result = new JSONObject(response);
            if (status < 200 || status >= 300 || !result.optBoolean("ok", true)) {
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

    public static JSONObject login(Context context, String code, String clientToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("code", code);
        body.put("clientId", AniSecurePrefs.getClientId(context));
        body.put("deviceName", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        body.put("appVersion", BuildConfig.VERSION_NAME + "-ani");
        body.put("clientToken", clientToken);
        return post("/auth/login", body);
    }

    public static JSONObject listHosts(String clientToken) throws Exception {
        JSONObject body = new JSONObject(); body.put("clientToken", clientToken);
        return post("/hosts/list", body);
    }

    public static JSONObject startSession(String clientToken, String hostDeviceId, String eventId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("clientToken", clientToken);
        body.put("hostDeviceId", hostDeviceId);
        body.put("sourceEventId", eventId);
        return post("/session/start", body);
    }

    public static JSONObject markActive(String clientToken, String sessionId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("clientToken", clientToken); body.put("sessionId", sessionId);
        return post("/session/active", body);
    }

    public static JSONObject heartbeat(String clientToken, String sessionId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("clientToken", clientToken); body.put("sessionId", sessionId);
        return post("/session/heartbeat", body);
    }

    public static JSONObject stopSession(String clientToken, String sessionId, String reason) throws Exception {
        JSONObject body = new JSONObject();
        body.put("clientToken", clientToken); body.put("sessionId", sessionId); body.put("reason", reason);
        return post("/session/stop", body);
    }

    public static JSONObject preparePairing(String clientToken, String sessionId, String pin, String clientName) throws Exception {
        JSONObject body = new JSONObject();
        body.put("clientToken", clientToken);
        body.put("sessionId", sessionId);
        body.put("pin", pin);
        body.put("clientName", clientName);
        return post("/pairing/prepare", body);
    }

    public static JSONObject topupInfo(String clientToken) throws Exception {
        JSONObject body = new JSONObject(); body.put("clientToken", clientToken);
        return post("/topup/info", body);
    }
}
