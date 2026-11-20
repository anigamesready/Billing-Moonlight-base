package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AniLoginActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText userIdInput;
    private EditText activationPinInput;
    private TextView messageView;
    private Button loginButton;
    private ProgressBar progress;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8,14,27));
        getWindow().setNavigationBarColor(Color.rgb(8,14,27));
        render();

        String savedCode = AniSecurePrefs.getRentalCode(this);
        if (savedCode != null && !savedCode.trim().isEmpty()) {
            userIdInput.setText(savedCode);
            trySavedSession();
        }
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8,14,27));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26),dp(28),dp(26),dp(24));
        scroll.addView(root);

        TextView brand = new TextView(this);
        brand.setText("ANI GAMES READY");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(25);
        brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        root.addView(brand,new LinearLayout.LayoutParams(-1,dp(52)));

        TextView subtitle = new TextView(this);
        subtitle.setText("REMOTE PC GAMING");
        subtitle.setTextColor(Color.rgb(126,151,184));
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle,new LinearLayout.LayoutParams(-1,dp(28)));

        View gap = new View(this);
        root.addView(gap,new LinearLayout.LayoutParams(1,dp(24)));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18),dp(22),dp(18),dp(20));
        panel.setBackground(rounded(Color.rgb(20,34,54),18));
        root.addView(panel,new LinearLayout.LayoutParams(-1,-2));

        TextView title = new TextView(this);
        title.setText("Masuk ke akun rental");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        panel.addView(title);

        TextView userLabel = label("USER ID RENTAL");
        userLabel.setPadding(0,dp(20),0,dp(7));
        panel.addView(userLabel);

        userIdInput = new EditText(this);
        userIdInput.setSingleLine(true);
        userIdInput.setTextColor(Color.WHITE);
        userIdInput.setHintTextColor(Color.rgb(111,132,159));
        userIdInput.setHint("Masukkan User ID");
        userIdInput.setTextSize(16);
        userIdInput.setPadding(dp(12),dp(10),dp(12),dp(10));
        userIdInput.setBackground(rounded(Color.rgb(10,21,37),10));
        panel.addView(userIdInput,new LinearLayout.LayoutParams(-1,dp(52)));

        TextView pinLabel = label("KODE AKTIVASI REMOTE PLAY");
        pinLabel.setPadding(0,dp(16),0,dp(7));
        panel.addView(pinLabel);

        activationPinInput = new EditText(this);
        activationPinInput.setSingleLine(true);
        activationPinInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        activationPinInput.setTextColor(Color.WHITE);
        activationPinInput.setHintTextColor(Color.rgb(111,132,159));
        activationPinInput.setHint("Kode dari Admin");
        activationPinInput.setTextSize(16);
        activationPinInput.setPadding(dp(12),dp(10),dp(12),dp(10));
        activationPinInput.setBackground(rounded(Color.rgb(10,21,37),10));
        panel.addView(activationPinInput,new LinearLayout.LayoutParams(-1,dp(52)));

        loginButton = new Button(this);
        loginButton.setText("MASUK");
        loginButton.setTextColor(Color.rgb(4,22,26));
        loginButton.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        loginButton.setBackground(rounded(Color.rgb(75,226,179),12));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1,dp(50));
        buttonParams.topMargin=dp(18);
        panel.addView(loginButton,buttonParams);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(34),dp(34));
        progressParams.gravity=Gravity.CENTER;
        progressParams.topMargin=dp(16);
        panel.addView(progress,progressParams);

        messageView = new TextView(this);
        messageView.setTextColor(Color.rgb(210,220,233));
        messageView.setTextSize(13);
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(0,dp(16),0,0);
        panel.addView(messageView,new LinearLayout.LayoutParams(-1,-2));

        TextView footer = new TextView(this);
        footer.setText("Kode aktivasi diterbitkan Admin. Satu akun hanya dapat berjalan pada satu sesi Remote Play.");
        footer.setTextColor(Color.rgb(112,133,160));
        footer.setTextSize(11);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0,dp(24),0,0);
        root.addView(footer,new LinearLayout.LayoutParams(-1,-2));

        loginButton.setOnClickListener(v -> {
            String code=userIdInput.getText().toString().trim();
            String pin=activationPinInput.getText().toString().trim();
            if(code.isEmpty()) {
                messageView.setText("User ID belum diisi.");
                return;
            }
            if(!pin.matches("(?i)[0-9a-f]{16}")) {
                messageView.setText("Kode aktivasi harus 16 karakter huruf A–F dan angka 0–9 dari Admin.");
                return;
            }
            hideKeyboard();
            login(code,pin);
        });
        setContentView(scroll);
    }

    private void trySavedSession() {
        String savedToken;
        try {
            savedToken = AniSecurePrefs.getClientToken(this);
        } catch (Exception e) {
            savedToken = null;
        }
        if (savedToken == null || savedToken.length() != 64) return;

        setBusy(true,"Memeriksa sesi tersimpan...");
        worker.execute(() -> {
            try {
                JSONObject ignored = AniRemoteApi.listHosts(this);
                if (!destroyed) runOnUiThread(() -> openDashboard(AniSecurePrefs.getRentalCode(this)));
            } catch(Exception e) {
                if (!destroyed) runOnUiThread(() -> {
                    setBusy(false,"Sesi tidak dapat digunakan. Masukkan kode aktivasi baru atau periksa koneksi.");
                    if (e instanceof AniRemoteApi.ApiException &&
                            ((AniRemoteApi.ApiException)e).statusCode == 401) {
                        AniSecurePrefs.clearAuth(this);
                    }
                });
            }
        });
    }

    private void login(String code, String pin) {
        setBusy(true,"Memeriksa User ID dan kode aktivasi...");
        worker.execute(() -> {
            try {
                JSONObject response = AniRemoteApi.login(this,code,pin);
                String accessToken = response.optString("access_token","");
                String refreshToken = response.optString("refresh_token","");
                if(accessToken.length()!=64 || refreshToken.length()!=64)
                    throw new IllegalStateException("REMOTE_PLAY_LOGIN_RESPONSE_INVALID");
                AniSecurePrefs.saveTokenPair(this,accessToken,refreshToken);
                AniSecurePrefs.setRentalCode(this,code);
                if (!destroyed) runOnUiThread(() -> openDashboard(code));
            } catch(Exception e) {
                String error=e.getMessage()==null?"REMOTE_LOGIN_FAILED":e.getMessage();
                if (!destroyed) runOnUiThread(() -> {
                    setBusy(false,explain(error));
                    activationPinInput.setText("");
                });
            }
        });
    }

    private String explain(String error) {
        if(error.contains("DB_ANDROID_API_ENDPOINT_NOT_CONFIGURED"))
            return "Endpoint DB Android belum dipasang pada build. Hubungi Admin.";
        if(error.contains("DB_ANDROID_API_ENDPOINT"))
            return "Konfigurasi server DB Android belum valid. Hubungi Admin.";
        if(error.contains("REMOTE_PLAY_INVALID_CREDENTIALS") || error.contains("REMOTE_PLAY_ACTIVATION_REQUIRED") || error.contains("ACTIVATION_INVALID"))
            return "Kode aktivasi salah, kedaluwarsa, atau sudah digunakan. Minta kode baru ke Admin.";
        if(error.contains("REMOTE_PLAY_DEVICE_ALREADY_BOUND"))
            return "Akun sudah terikat ke perangkat lain. Hubungi Admin untuk reset perangkat.";
        if(error.contains("REMOTE_PLAY_ACCOUNT_NOT_FOUND"))
            return "User ID tidak ditemukan. Periksa kembali ID rental.";
        if(error.contains("REMOTE_PLAY_ACCOUNT_INACTIVE"))
            return "Akun rental tidak aktif. Hubungi Admin.";
        if(error.contains("LOGIN_RATE_LIMITED") || error.contains("RATE_LIMITED"))
            return "Terlalu banyak percobaan. Coba lagi setelah beberapa saat.";
        if(error.contains("REMOTE_PLAY_SERVICE_UNAVAILABLE"))
            return "Server DB Android sedang tidak tersedia. Coba beberapa saat lagi.";
        if(error.startsWith("HTTP"))
            return "Koneksi gagal. Periksa internet lalu coba lagi.";
        return error;
    }

    private void openDashboard(String code) {
        setBusy(false,"");
        Intent intent=new Intent(this,AniDashboardActivity.class);
        intent.putExtra("rental_code",code);
        startActivity(intent);
        finish();
    }

    private void setBusy(boolean busy,String message) {
        if (loginButton == null) return;
        loginButton.setEnabled(!busy);
        userIdInput.setEnabled(!busy);
        activationPinInput.setEnabled(!busy);
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
        messageView.setText(message==null?"":message);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.hideSoftInputFromWindow(activationPinInput.getWindowToken(),0);
        } catch(Exception ignored) {}
    }

    private TextView label(String text) {
        TextView t=new TextView(this);
        t.setText(text); t.setTextColor(Color.rgb(165,184,208)); t.setTextSize(11);
        return t;
    }

    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private int dp(float v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    @Override protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
