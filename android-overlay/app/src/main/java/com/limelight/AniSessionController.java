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
import java.time.OffsetDateTime;
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

    private AniSessionController() {}

    public static void onStreamConnected(Activity activity, TextView timerView) {
        final String session = AniSecurePrefs.getActiveSession(activity);
        if (session == null || session.isEmpty()) {
            // Normal Moonlight launch outside Ani Remote Play.
            return;
        }
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
                JSONObject result = AniRemoteApi.markActive(activity, session);
                if (!result.optBoolean("ok", false)) throw new IllegalStateException("SESSION_ACTIVATION_DENIED");
                long deadline = OffsetDateTime.parse(result.getString("deadline_at")).toInstant().toEpochMilli();
                long serverNow = OffsetDateTime.parse(result.getString("server_time")).toInstant().toEpochMilli();
                serverDeadlineMillis = deadline;
                serverOffsetMillis = serverNow - System.currentTimeMillis();
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
                failClosed(activity, "Sesi tidak dapat diaktifkan oleh server. Stream dihentikan.");
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

    private static void sendHeartbeat() {
        if (!running || activeSessionId.isEmpty() || clientToken.isEmpty()) return;
        try {
            JSONObject response = AniRemoteApi.heartbeat(activityRef.get(), activeSessionId);
            if (response.optBoolean("stop_stream", false)) {
                running = false;
                MAIN.post(() -> {
                    Activity activity = activityRef.get();
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, "Waktu sesi berakhir. Kembali ke dashboard.", Toast.LENGTH_LONG).show();
                        activity.finish();
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
        String session = activeSessionId;
        if (session == null || session.isEmpty()) {
            session = AniSecurePrefs.getActiveSession(activity);
        }
        if (session == null || session.isEmpty()) return;

        final String sessionToStop = session;
        final String token;
        try {
            token = AniSecurePrefs.getClientToken(activity);
        } catch (Exception e) {
            return;
        }
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
            }
        });
        TextView view = timerRef.get();
        if (view != null) MAIN.post(() -> view.setVisibility(TextView.GONE));
    }

    public static void recoverBeforeDashboard(Activity activity) {
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
    }
}
