package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local identity and encrypted Remote Play token storage. Activation PINs are never stored. */
public final class AniSecurePrefs {
    private static final String PREF = "AniRemote";
    private static final String SECRET_PREF = "AniRemoteSecure";
    private static final String KEY_ALIAS = "AniMoonlightRemoteTokenV2";
    private AniSecurePrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static SharedPreferences secretPrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(SECRET_PREF, Context.MODE_PRIVATE);
    }

    public static String getClientId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("client_id", null);
        if (id == null || id.length() < 8) {
            id = UUID.randomUUID().toString();
            p.edit().putString("client_id", id).apply();
        }
        return id;
    }

    public static String getClientName(Context c) {
        String compact = getClientId(c).replace("-", "").toUpperCase(java.util.Locale.US);
        if (compact.length() > 12) compact = compact.substring(0, 12);
        return "ANI-" + compact;
    }

    private static SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static void saveSecret(Context c, String key, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[1 + iv.length + encrypted.length];
        combined[0] = (byte) iv.length;
        System.arraycopy(iv, 0, combined, 1, iv.length);
        System.arraycopy(encrypted, 0, combined, 1 + iv.length, encrypted.length);
        secretPrefs(c).edit().putString(key, Base64.encodeToString(combined, Base64.NO_WRAP)).apply();
    }

    private static String getSecret(Context c, String key) throws Exception {
        String encoded = secretPrefs(c).getString(key, null);
        if (encoded == null) return null;
        byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
        if (combined.length < 30) throw new IllegalStateException("Stored token data is invalid");
        int ivLength = combined[0] & 0xff;
        if (ivLength < 12 || combined.length <= 1 + ivLength) {
            throw new IllegalStateException("Stored token data is invalid");
        }
        byte[] iv = new byte[ivLength];
        byte[] ciphertext = new byte[combined.length - 1 - ivLength];
        System.arraycopy(combined, 1, iv, 0, ivLength);
        System.arraycopy(combined, 1 + ivLength, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    /** Generic Android-Keystore encrypted storage for device-bound tunnel private keys. */
    public static void saveSecretValue(Context c, String key, String value) throws Exception {
        if (key == null || !key.matches("[a-z0-9_]{3,64}") || value == null || value.length() > 4096)
            throw new IllegalArgumentException("REMOTE_SECURE_VALUE_INVALID");
        saveSecret(c, "secure_" + key, value);
    }
    public static String getSecretValue(Context c, String key) throws Exception {
        if (key == null || !key.matches("[a-z0-9_]{3,64}"))
            throw new IllegalArgumentException("REMOTE_SECURE_KEY_INVALID");
        return getSecret(c, "secure_" + key);
    }
    public static void putLocalValue(Context c, String key, String value) {
        if (key == null || !key.matches("[a-z0-9_]{3,64}")) throw new IllegalArgumentException("REMOTE_LOCAL_KEY_INVALID");
        prefs(c).edit().putString(key, value == null ? "" : value).apply();
    }
    public static String getLocalValue(Context c, String key, String fallback) {
        if (key == null || !key.matches("[a-z0-9_]{3,64}")) throw new IllegalArgumentException("REMOTE_LOCAL_KEY_INVALID");
        return prefs(c).getString(key, fallback);
    }

    public static void saveClientToken(Context c, String token) throws Exception {
        saveSecret(c, "client_token", token);
    }
    public static String getClientToken(Context c) throws Exception {
        return getSecret(c, "client_token");
    }
    public static void saveRefreshToken(Context c, String token) throws Exception {
        saveSecret(c, "refresh_token", token);
    }
    public static String getRefreshToken(Context c) throws Exception {
        return getSecret(c, "refresh_token");
    }
    public static void saveTokenPair(Context c, String access, String refresh) throws Exception {
        saveClientToken(c, access);
        saveRefreshToken(c, refresh);
    }
    public static void clearAuth(Context c) {
        secretPrefs(c).edit().remove("client_token").remove("refresh_token").apply();
        prefs(c).edit().remove("active_session_id").remove("deadline_at").apply();
    }

    public static String getOrCreateClientToken(Context c) throws Exception {
        String token = getClientToken(c);
        if (token != null && token.length() == 64) return token;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) hex.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        token = hex.toString();
        saveClientToken(c, token);
        return token;
    }

    public static String getRentalCode(Context c) { return prefs(c).getString("rental_code", ""); }
    public static void setRentalCode(Context c, String code) { prefs(c).edit().putString("rental_code", code).apply(); }

    public static void setRemoteMode(Context c, boolean enabled) {
        prefs(c).edit().putBoolean("remote_play_mode", enabled).apply();
    }
    public static boolean isRemoteMode(Context c) {
        return prefs(c).getBoolean("remote_play_mode", false);
    }

    public static void saveActiveSession(Context c, String sessionId) {
        prefs(c).edit().putString("active_session_id", sessionId).apply();
    }
    public static String getActiveSession(Context c) {
        return prefs(c).getString("active_session_id", "");
    }
    public static void clearActiveSession(Context c) {
        prefs(c).edit().remove("active_session_id").remove("deadline_at").apply();
    }
    public static void saveDeadline(Context c, String deadline) {
        prefs(c).edit().putString("deadline_at", deadline).apply();
    }
    public static String getDeadline(Context c) {
        return prefs(c).getString("deadline_at", "");
    }
}
