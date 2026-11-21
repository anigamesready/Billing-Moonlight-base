package com.limelight;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Server-authoritative Remote Play timer and client liveness heartbeat. */
public final class AniSessionController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService WORKER = Executors.newScheduledThreadPool(2);
    private static volatile String activeSessionId = "";
    private static volatile String clientToken = "";
    private static volatile long serverDeadlineMillis;
    private static volatile long serverOffsetMillis;
    private static volatile boolean expanded;
    private static volatile boolean running;
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static WeakReference<TextView> timerRef = new WeakReference<>(null);
    private static ScheduledFuture<?> ticker;
    private static ScheduledFuture<?> heartbeat;
    private static ScheduledFuture<?> connectingHeartbeat;
    private static volatile boolean connecting;
    private static volatile long connectingDeadlineMillis;
    private static final long CONNECTING_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    private AniSessionController() {}

    /** Keep the server lease alive while Moonlight is pairing/starting the game. */
    public static void beginConnecting(Activity activity, String sessionId) throws Exception {
        if (activity == null || sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("REMOTE_CONNECT_SESSION_INVALID");
        }
        String token = AniSecurePrefs.getClientToken(activity);
        if (token == null || token.length() != 64) {
            throw new IllegalStateException("REMOTE_PLAY_TOKEN_REQUIRED");
        }
        stopLoops();
        activeSessionId = sessionId;
        clientToken = token;
        activityRef = new WeakReference<>(activity);
        running = false;
        connectingDeadlineMillis = System.currentTimeMillis() + CONNECTING_TIMEOUT_MS;
        connecting = true;
        connectingHeartbeat = WORKER.scheduleAtFixedRate(
                AniSessionController::sendConnectingHeartbeat, 15, 20, TimeUnit.SECONDS);
    }

    public static boolean isConnecting() {
        return connecting && activeSessionId != null && !activeSessionId.isEmpty();
    }

    public static void onStreamConnected(Activity activity, TextView timerView) {
        final String session = AniSecurePrefs.getActiveSession(activity);
        if (session == null || session.isEmpty()) {
            // Normal Moonlight launch outside Ani Remote Play.
            return;
        }
        stopConnectingHeartbeat();
        try {
            clientToken = AniSecurePrefs.getClientToken(activity);
        } catch (Exception e) {
            failClosed(activity, "Identitas perangkat tidak dapat dibaca.");
            return;
        }
        if (clientToken == null || clientToken.length() != 64) {
            failClosed(activity, "Token perangkat tidak valid.");
            return;
        }

        activeSessionId = session;
        activityRef = new WeakReference<>(activity);
        timerRef = new WeakReference<>(timerView);
        running = false;
        if (timerView != null) {
            timerView.setVisibility(TextView.VISIBLE);
            timerView.setText("⏱  Memulai timer...");
            timerView.setOnClickListener(v -> toggleExpanded());
        }

        WORKER.execute(() -> {
            try {
                // Mark the actual transport-connected moment; this starts server billing.
                // The call is idempotent so repeated Game callbacks cannot reset the clock.
                JSONObject result = AniRemoteApi.markStreamConnected(activity, session);
                String state = result.optString("status", "");
                long remaining = result.optLong("remaining_seconds", 0);
                if (!result.optBoolean("ok", false) || result.optBoolean("settled", false) ||
                        remaining <= 0 || !state.equals("active")) {
                    throw new IllegalStateException("STREAM_CONNECTION_NOT_ACCEPTED_BY_SERVER");
                }
                // Rebase the on-screen countdown on the server deadline returned
                // by the stream-connected event, so billing and UI start together.
                serverOffsetMillis = 0;
                serverDeadlineMillis = System.currentTimeMillis() + remaining * 1000L;
                running = true;
                MAIN.post(() -> {
                    if (timerView != null) {
                        timerView.setVisibility(TextView.VISIBLE);
                        styleTimer(timerView);
                        updateTimerText();
                    }
                    startLoops();
                });
            } catch (Exception e) {
                failClosed(activity, "Sesi belum aktif di server. Host Agent harus menerima perintah mulai.");
            }
        });
    }

    private static void startLoops() {
        stopLoops();
        ticker = WORKER.scheduleAtFixedRate(() -> MAIN.post(AniSessionController::updateTimerText),
                0, 1, TimeUnit.SECONDS);
        heartbeat = WORKER.scheduleAtFixedRate(AniSessionController::sendHeartbeat,
                10, 10, TimeUnit.SECONDS);
    }

    private static void sendConnectingHeartbeat() {
        if (!connecting || activeSessionId == null || activeSessionId.isEmpty()) return;
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;
        if (System.currentTimeMillis() >= connectingDeadlineMillis) {
            stopConnectingSession(activity, "connection_timeout");
            return;
        }
        try {
            JSONObject response = AniRemoteApi.heartbeat(activity, activeSessionId);
            String state = response.optString("status", "");
            long remaining = response.optLong("remaining_seconds", 0);
            if (!response.optBoolean("ok", false) || response.optBoolean("settled", false) ||
                    remaining <= 0 || !(state.equals("starting") || state.equals("active"))) {
                stopConnectingSession(activity, "connection_lease_lost");
            }
        } catch (Exception ignored) {
            // A transient API outage is tolerated; the independent host watchdog remains authoritative.
        }
    }

    private static void stopConnectingSession(Activity activity, String reason) {
        final String session = activeSessionId;
        stopConnectingHeartbeat();
        running = false;
        if (session == null || session.isEmpty()) return;
        WORKER.execute(() -> {
            try {
                AniRemoteApi.stopSession(activity, session, reason);
            } catch (Exception ignored) {}
            try {
                AniSecurePrefs.clearActiveSession(activity);
            } catch (Exception ignored) {}
            if (session.equals(activeSessionId)) {
                activeSessionId = "";
                clientToken = "";
            }
            MAIN.post(() -> {
                if (!activity.isFinishing()) {
                    Toast.makeText(activity, "Waktu persiapan koneksi habis. Silakan kembali dan pilih PC lagi.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static void sendHeartbeat() {
        Activity activity = activityRef.get();
        if (!running || activeSessionId.isEmpty() || clientToken.isEmpty() ||
                activity == null || activity.isFinishing()) return;
        try {
            JSONObject response = AniRemoteApi.heartbeat(activity, activeSessionId);
            String state = response.optString("status", "");
            long remaining = response.optLong("remaining_seconds", 0);
            boolean mustStop = !response.optBoolean("ok", false) ||
                    response.optBoolean("settled", false) || remaining <= 0 ||
                    !(state.equals("starting") || state.equals("active"));
            if (!mustStop) {
                serverDeadlineMillis = System.currentTimeMillis() + remaining * 1000L;
            } else {
                running = false;
                MAIN.post(() -> {
                    Activity currentActivity = activityRef.get();
                    if (currentActivity != null && !currentActivity.isFinishing()) {
                        Toast.makeText(currentActivity, "Sesi selesai atau sudah ditutup server. Kembali ke dashboard.", Toast.LENGTH_LONG).show();
                        currentActivity.finish();
                    }
                });
            }
        } catch (Exception ignored) {
            // Fail closed on the host watchdog if client heartbeat remains absent.
            // Do not terminate a healthy stream solely for a transient control-plane outage.
        }
    }

    private static void updateTimerText() {
        if (!running) return;
        TextView view = timerRef.get();
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;
        long remainingMillis = serverDeadlineMillis - (System.currentTimeMillis() + serverOffsetMillis);
        long remainingSeconds = Math.max(0, (remainingMillis + 999) / 1000);
        long hours = remainingSeconds / 3600;
        long minutes = (remainingSeconds % 3600) / 60;
        long seconds = remainingSeconds % 60;
        if (view != null) {
            if (expanded) {
                view.setText(String.format(Locale.US, "⏱  SISA WAKTU\n%02d:%02d:%02d\nKetuk untuk ringkas", hours, minutes, seconds));
            } else {
                view.setText(String.format(Locale.US, "⏱  %02d:%02d:%02d", hours, minutes, seconds));
            }
        }
        if (remainingSeconds <= 0) {
            running = false;
            activity.runOnUiThread(activity::finish);
        }
    }

    private static void toggleExpanded() {
        expanded = !expanded;
        updateTimerText();
    }

    private static void styleTimer(TextView view) {
        view.setTextColor(Color.WHITE);
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(14, 9, 14, 9);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xD91A2A3D);
        background.setCornerRadius(18);
        background.setStroke(1, 0x8858DDB5);
        view.setBackground(background);
        view.setClickable(true);
    }

    public static void stop(Activity activity, String reason) {
        stop(activity, reason, null);
    }

    public static void stop(Activity activity, String reason, Runnable onComplete) {
        connecting = false;
        connectingDeadlineMillis = 0;
        String session = activeSessionId;
        if (session == null || session.isEmpty()) session = AniSecurePrefs.getActiveSession(activity);
        if (session == null || session.isEmpty()) {
            postStopCompletion(activity, onComplete);
            return;
        }
        final String sessionToStop = session;
        running = false;
        stopLoops();
        WORKER.execute(() -> {
            try {
                AniRemoteApi.stopSession(activity, sessionToStop, reason == null ? "stream_stopped" : reason);
                AniSecurePrefs.clearActiveSession(activity);
                activeSessionId = "";
                clientToken = "";
            } catch (Exception ignored) {
                // Keep the session id locally so the next dashboard start can retry.
            } finally {
                postStopCompletion(activity, onComplete);
            }
        });
        TextView view = timerRef.get();
        if (view != null) MAIN.post(() -> view.setVisibility(TextView.GONE));
    }

    private static void postStopCompletion(Activity activity, Runnable completion) {
        if (completion == null) return;
        MAIN.post(() -> {
            if (activity == null || !activity.isFinishing()) completion.run();
        });
    }
    public static void recoverBeforeDashboard(Activity activity) {
        connecting = false;
        connectingDeadlineMillis = 0;
        running = false;
        stopLoops();
        String session = AniSecurePrefs.getActiveSession(activity);
        if (session == null || session.isEmpty()) return;
        final String token;
        try {
            token = AniSecurePrefs.getClientToken(activity);
        } catch (Exception e) {
            return;
        }
        WORKER.execute(() -> {
            try {
                AniRemoteApi.stopSession(activity, session, "client_restarted");
                AniSecurePrefs.clearActiveSession(activity);
            } catch (Exception ignored) {}
        });
    }

    private static void failClosed(Activity activity, String message) {
        running = false;
        MAIN.post(() -> {
            try {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                activity.finish();
            } catch (Exception ignored) {}
        });
    }

    private static synchronized void stopLoops() {
        if (ticker != null) { ticker.cancel(false); ticker = null; }
        if (heartbeat != null) { heartbeat.cancel(false); heartbeat = null; }
        stopConnectingHeartbeat();
    }

    private static synchronized void stopConnectingHeartbeat() {
        connecting = false;
        connectingDeadlineMillis = 0;
        if (connectingHeartbeat != null) {
            connectingHeartbeat.cancel(false);
            connectingHeartbeat = null;
        }
    }
}
