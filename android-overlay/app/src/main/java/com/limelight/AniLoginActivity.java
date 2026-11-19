package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AniLoginActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText userIdInput;
    private TextView messageView;
    private Button loginButton;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8,14,27));
        getWindow().setNavigationBarColor(Color.rgb(8,14,27));
        render();

        String savedCode = AniSecurePrefs.getRentalCode(this);
        if (savedCode != null && !savedCode.trim().isEmpty()) {
            userIdInput.setText(savedCode);
            login(savedCode.trim(), false);
        }
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26),dp(36),dp(26),dp(24));
        root.setBackgroundColor(Color.rgb(8,14,27));

        TextView brand = new TextView(this);
        brand.setText("ANI MOONLIGHT");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(26);
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
        root.addView(gap,new LinearLayout.LayoutParams(1,dp(35)));

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

        TextView label = new TextView(this);
        label.setText("USER ID RENTAL");
        label.setTextColor(Color.rgb(165,184,208));
        label.setTextSize(11);
        label.setPadding(0,dp(20),0,dp(7));
        panel.addView(label);

        userIdInput = new EditText(this);
        userIdInput.setSingleLine(true);
        userIdInput.setTextColor(Color.WHITE);
        userIdInput.setHintTextColor(Color.rgb(111,132,159));
        userIdInput.setHint("Masukkan User ID");
        userIdInput.setTextSize(16);
        userIdInput.setPadding(dp(12),dp(10),dp(12),dp(10));
        userIdInput.setBackground(rounded(Color.rgb(10,21,37),10));
        panel.addView(userIdInput,new LinearLayout.LayoutParams(-1,dp(52)));

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
        footer.setText("Satu User ID terikat ke satu perangkat terdaftar.\nJika ganti perangkat, hubungi Admin.");
        footer.setTextColor(Color.rgb(112,133,160));
        footer.setTextSize(11);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0,dp(24),0,0);
        root.addView(footer,new LinearLayout.LayoutParams(-1,-2));

        loginButton.setOnClickListener(v -> {
            String code=userIdInput.getText().toString().trim();
            if(code.isEmpty()) {
                messageView.setText("User ID belum diisi.");
                return;
            }
            hideKeyboard();
            login(code,true);
        });
        setContentView(root);
    }

    private void login(String code, boolean showErrors) {
        setBusy(true);
        worker.execute(() -> {
            try {
                String token=AniSecurePrefs.getOrCreateClientToken(this);
                JSONObject response=AniRemoteApi.login(this,code,token);
                AniSecurePrefs.setRentalCode(this,code);
                String returnedToken=response.optString("client_token",token);
                AniSecurePrefs.saveClientToken(this,returnedToken);
                runOnUiThread(() -> {
                    setBusy(false);
                    Intent intent=new Intent(this,AniDashboardActivity.class);
                    intent.putExtra("rental_code",code);
                    startActivity(intent);
                    finish();
                });
            } catch(Exception e) {
                String error=e.getMessage()==null?"REMOTE_LOGIN_FAILED":e.getMessage();
                runOnUiThread(() -> {
                    setBusy(false);
                    if(showErrors || !error.equals("REMOTE_RENTAL_NOT_FOUND")) {
                        messageView.setText(explain(error));
                    }
                });
            }
        });
    }

    private String explain(String error) {
        if(error.contains("REMOTE_DEVICE_ALREADY_BOUND"))
            return "User ID sudah terikat ke perangkat lain. Hubungi Admin untuk reset perangkat.";
        if(error.contains("REMOTE_RENTAL_NOT_FOUND"))
            return "User ID tidak ditemukan. Periksa kembali ID rental.";
        if(error.contains("REMOTE_RENTAL_DISABLED"))
            return "Akun rental dinonaktifkan. Hubungi Admin.";
        if(error.contains("LOGIN_RATE_LIMITED"))
            return "Terlalu banyak percobaan. Coba lagi setelah beberapa saat.";
        if(error.contains("REMOTE_PLAY_SERVICE_UNAVAILABLE"))
            return "Server sedang tidak tersedia. Coba beberapa saat lagi.";
        if(error.contains("REMOTE_CLIENT_TOKEN_INVALID"))
            return "Identitas perangkat tidak valid. Hubungi Admin untuk reset perangkat.";
        if(error.startsWith("HTTP"))
            return "Koneksi gagal. Periksa internet lalu coba lagi.";
        return error;
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        userIdInput.setEnabled(!busy);
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
        messageView.setText(busy?"Memeriksa akun rental...":"");
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.hideSoftInputFromWindow(userIdInput.getWindowToken(),0);
        } catch(Exception ignored) {}
    }

    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private int dp(float v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
