package com.limelight;

import android.content.Context;
import com.wireguard.android.backend.BackendException;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyPair;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Embedded, client-side WireGuard tunnel for Ani Moonlight.
 * The Android app creates its private key locally, stores it encrypted with
 * Android Keystore, and routes only the selected Host /32 through the tunnel.
 */
public final class AniTunnelManager {
    private static final String TUNNEL_NAME = "aniMoonlight";
    private static final Object LOCK = new Object();
    private static GoBackend backend;
    private static Tunnel tunnel;
    private static String activeClientAddress = "";
    private static String activeHostAddress = "";

    private AniTunnelManager() {}

    private static GoBackend getBackend(Context context) {
        if (backend == null) backend = new GoBackend(context.getApplicationContext());
        if (tunnel == null) {
            tunnel = new Tunnel() {
                @Override public String getName() { return TUNNEL_NAME; }
                @Override public void onStateChange(State state) {
                    // State is queried by the connection flow; never log key material.
                }
            };
        }
        return backend;
    }

    /** Creates a stable per-install keypair. Only the public key leaves the device. */
    public static String getOrCreateClientPublicKey(Context context) throws Exception {
        synchronized (LOCK) {
            String privateKey = AniSecurePrefs.getSecretValue(context, "wireguard_client_private_key");
            KeyPair pair;
            if (privateKey == null || privateKey.isEmpty()) {
                pair = new KeyPair();
                AniSecurePrefs.saveSecretValue(context, "wireguard_client_private_key",
                        pair.getPrivateKey().toBase64());
            } else {
                pair = new KeyPair(Key.fromBase64(privateKey));
            }
            return pair.getPublicKey().toBase64();
        }
    }

    /**
     * Connects using a server-issued per-session grant. Expected fields:
     * client_address, host_address, relay_endpoint, relay_public_key and optionally
     * preshared_key. AllowedIPs is deliberately restricted to the selected Host /32.
     */
    public static void connect(Context context, JSONObject grant) throws Exception {
        synchronized (LOCK) {
            String clientAddress = grant.optString("client_address", "").trim();
            String hostAddress = grant.optString("host_address", "").trim();
            String endpoint = grant.optString("relay_endpoint", "").trim();
            String relayPublicKey = grant.optString("relay_public_key", "").trim();
            String psk = grant.optString("preshared_key", "").trim();

            requirePrivateIpv4(clientAddress, "client_address");
            requirePrivateIpv4(hostAddress, "host_address");
            if (endpoint.isEmpty() || endpoint.length() > 255 || endpoint.contains("://") ||
                    endpoint.contains("/") || endpoint.contains("@")) {
                throw new IllegalArgumentException("REMOTE_PLAY_RELAY_ENDPOINT_INVALID");
            }
            Key.fromBase64(relayPublicKey);
            if (!psk.isEmpty()) Key.fromBase64(psk);

            String privateKey = AniSecurePrefs.getSecretValue(context, "wireguard_client_private_key");
            if (privateKey == null || privateKey.isEmpty())
                throw new IllegalStateException("REMOTE_PLAY_CLIENT_TUNNEL_KEY_MISSING");

            StringBuilder configText = new StringBuilder();
            configText.append("[Interface]\n")
                    .append("PrivateKey = ").append(privateKey).append("\n")
                    .append("Address = ").append(clientAddress).append("/32\n")
                    .append("MTU = 1280\n\n")
                    .append("[Peer]\n")
                    .append("PublicKey = ").append(relayPublicKey).append("\n");
            if (!psk.isEmpty()) configText.append("PresharedKey = ").append(psk).append("\n");
            configText.append("Endpoint = ").append(endpoint).append("\n")
                    .append("AllowedIPs = ").append(hostAddress).append("/32\n")
                    .append("PersistentKeepalive = 25\n");

            Config parsed = Config.parse(new ByteArrayInputStream(
                    configText.toString().getBytes(StandardCharsets.UTF_8)));
            GoBackend b = getBackend(context);
            if (b.getState(tunnel) == Tunnel.State.UP) {
                b.setState(tunnel, Tunnel.State.DOWN, null);
            }
            b.setState(tunnel, Tunnel.State.UP, parsed);
            activeClientAddress = clientAddress;
            activeHostAddress = hostAddress;
            AniSecurePrefs.putLocalValue(context, "wireguard_active_client_address", clientAddress);
            AniSecurePrefs.putLocalValue(context, "wireguard_active_host_address", hostAddress);
        }
    }

    public static void disconnect(Context context) {
        synchronized (LOCK) {
            try {
                if (backend != null && tunnel != null &&
                        backend.getState(tunnel) == Tunnel.State.UP) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null);
                }
            } catch (Exception ignored) {
                // Server-side lease and relay ACL are authoritative and expire independently.
            } finally {
                activeClientAddress = "";
                activeHostAddress = "";
                if (context != null) {
                    AniSecurePrefs.putLocalValue(context, "wireguard_active_client_address", "");
                    AniSecurePrefs.putLocalValue(context, "wireguard_active_host_address", "");
                }
            }
        }
    }

    public static String getClientAddress(Context context) {
        synchronized (LOCK) {
            if (!activeClientAddress.isEmpty()) return activeClientAddress;
            return AniSecurePrefs.getLocalValue(context, "wireguard_active_client_address", "");
        }
    }

    public static String getHostAddress(Context context) {
        synchronized (LOCK) {
            if (!activeHostAddress.isEmpty()) return activeHostAddress;
            return AniSecurePrefs.getLocalValue(context, "wireguard_active_host_address", "");
        }
    }

    private static void requirePrivateIpv4(String value, String field) throws Exception {
        if (value.isEmpty()) throw new IllegalArgumentException("REMOTE_PLAY_" + field.toUpperCase(Locale.ROOT) + "_MISSING");
        InetAddress parsed = InetAddress.getByName(value);
        byte[] b = parsed.getAddress();
        if (b.length != 4 ||
                !(b[0] == 10 ||
                  (b[0] == (byte) 172 && (b[1] & 0xff) >= 16 && (b[1] & 0xff) <= 31) ||
                  (b[0] == (byte) 192 && (b[1] & 0xff) == 168)) ||
                !parsed.getHostAddress().equals(value)) {
            throw new IllegalArgumentException("REMOTE_PLAY_" + field.toUpperCase(Locale.ROOT) + "_NOT_PRIVATE_IPV4");
        }
    }
}
