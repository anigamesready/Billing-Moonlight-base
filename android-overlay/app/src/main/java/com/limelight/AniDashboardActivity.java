package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AniDashboardActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView accountText, remainingText, expiryText, statusText, messageText;
    private Button topupButton, refreshButton;
    private boolean busy=false;
    private boolean expiredPopupShown=false;
    private String currentCode="";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8,14,27));
        getWindow().setNavigationBarColor(Color.rgb(8,14,27));
        currentCode=getIntent().getStringExtra("rental_code");
        if(currentCode==null || currentCode.isEmpty()) currentCode=AniSecurePrefs.getRentalCode(this);
        AniSecurePrefs.setRemoteMode(this,true);
        // A fresh dashboard means the previous streaming Activity was destroyed or force-stopped.
        // Ask the backend to close any persisted lease before displaying available PCs.
        AniSessionController.recoverBeforeDashboard(this);
        render();
        refresh();
    }

    private void render() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8,14,27));
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18),dp(24),dp(18),dp(28));
        scroll.addView(content);

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView brand=new TextView(this);
        brand.setText("ANI MOONLIGHT");
        brand.setTextSize(21);
        brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        brand.setTextColor(Color.WHITE);
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView chip=new TextView(this);
        chip.setText("REMOTE PLAY");
        chip.setTextSize(10);
        chip.setTextColor(Color.rgb(89,230,185));
        chip.setPadding(dp(10),dp(6),dp(10),dp(6));
        chip.setBackground(rounded(Color.rgb(18,54,50),20));
        header.addView(chip);
        content.addView(header);

        LinearLayout profile=card();
        profile.setOrientation(LinearLayout.VERTICAL);
        TextView label=label("USER ID");
        profile.addView(label);
        accountText=bigText(currentCode);
        profile.addView(accountText);
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0,dp(15),0,0);
        LinearLayout left=new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout right=new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL);
        left.addView(label("SISA WAKTU"));
        remainingText=bigAccent("Memuat...");
        left.addView(remainingText);
        right.addView(label("BATAS AKTIF"));
        expiryText=normalText("-");
        right.addView(expiryText);
        row.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        profile.addView(row);
        statusText=normalText("Memeriksa akun...");
        statusText.setPadding(0,dp(14),0,0);
        profile.addView(statusText);
        content.addView(profile);

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        topupButton=new Button(this);
        topupButton.setText("TOPUP BILLING");
        topupButton.setTextColor(Color.rgb(4,22,26));
        topupButton.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        topupButton.setBackground(rounded(Color.rgb(75,226,179),12));
        actions.addView(topupButton,new LinearLayout.LayoutParams(0,dp(48),1));
        refreshButton=new Button(this);
        refreshButton.setText("REFRESH");
        refreshButton.setTextColor(Color.WHITE);
        refreshButton.setBackground(rounded(Color.rgb(35,55,80),12));
        LinearLayout.LayoutParams refreshParams=new LinearLayout.LayoutParams(dp(112),dp(48));
        refreshParams.leftMargin=dp(8);
        actions.addView(refreshButton,refreshParams);
        content.addView(actions);

        LinearLayout meshCard=card();
        meshCard.setOrientation(LinearLayout.VERTICAL);
        meshCard.setPadding(dp(14),dp(12),dp(14),dp(12));
        TextView meshTitle=label("KONEKSI PRIVAT");
        meshTitle.setTextColor(Color.rgb(91,230,183));
        meshCard.addView(meshTitle);
        TextView meshDescription=normalText(
            "PC Remote Play memakai Cloudflare Mesh. HP harus sudah terhubung ke organisasi Cloudflare One yang sama dengan PC Host sebelum menekan HUBUNGKAN PC."
        );
        meshDescription.setPadding(0,dp(6),0,dp(8));
        meshCard.addView(meshDescription);
        Button meshButton=new Button(this);
        meshButton.setText("BUKA / PASANG CLOUDFLARE ONE");
        meshButton.setTextColor(Color.WHITE);
        meshButton.setBackground(rounded(Color.rgb(35,55,80),10));
        meshButton.setOnClickListener(v->openCloudflareOne());
        meshCard.addView(meshButton,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout.LayoutParams meshParams=new LinearLayout.LayoutParams(-1,-2);
        meshParams.topMargin=dp(12);
        content.addView(meshCard,meshParams);

        TextView pcTitle=new TextView(this);
        pcTitle.setText("PILIH PC GAMING");
        pcTitle.setTextColor(Color.WHITE);
        pcTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        pcTitle.setTextSize(15);
        pcTitle.setPadding(dp(3),dp(25),0,dp(9));
        content.addView(pcTitle);

        messageText=normalText("");
        messageText.setPadding(dp(3),dp(4),dp(3),dp(4));
        content.addView(messageText);

        topupButton.setOnClickListener(v->showTopup());
        refreshButton.setOnClickListener(v->refresh());
        setContentView(scroll);
    }

    private void refresh() {
        if(busy) return;
        busy=true;
        messageText.setText("Mengambil status rental dan seluruh PC...");
        worker.execute(()->{
            try {
                JSONObject response=AniRemoteApi.listHosts(this);
                runOnUiThread(()->{
                    busy=false;
                    showDashboard(response);
                });
            } catch(Exception e) {
                runOnUiThread(()->{
                    busy=false;
                    messageText.setText("Tidak dapat memuat dashboard. Periksa internet lalu tekan Refresh.");
                });
            }
        });
    }

    private void showDashboard(JSONObject data) {
        JSONObject rental = data.optJSONObject("rental_account");
        boolean canPlay = false;
        if (rental != null) {
            long remaining = rental.optLong("remaining_seconds", 0);
            remainingText.setText(formatSeconds(remaining));
            expiryText.setText(formatExpiry(rental.optString("expiry_at", "")));

            String accountState = rental.optString("status", "unknown");
            boolean remoteEnabled = data.optBoolean("remote_play_enabled", false);
            long cooldownSeconds = Math.max(0, data.optLong("cooldown_seconds", 0));
            boolean activeSession = data.optJSONObject("active_session") != null;
            boolean cooldownActive = cooldownSeconds > 0 && !activeSession;
            canPlay = data.optBoolean("can_start", false) &&
                    accountState.equals("active") && remaining > 0 && remoteEnabled &&
                    !cooldownActive && !activeSession;

            String unavailableReason;
            if (!remoteEnabled) unavailableReason = "AKSES ADMIN OFF";
            else if (accountState.equals("disabled") || accountState.equals("deleted") ||
                     accountState.equals("inactive")) unavailableReason = "AKUN NONAKTIF";
            else if (remaining <= 0 || accountState.equals("expired")) unavailableReason = "TOPUP DULU";
            else if (activeSession) unavailableReason = "SESI AKTIF";
            else if (cooldownActive) unavailableReason = "COOLDOWN";
            else unavailableReason = "TIDAK TERSEDIA";

            String stateLabel;
            if (!remoteEnabled) stateLabel = "REMOTE PLAY BELUM DIIZINKAN ADMIN";
            else if (accountState.equals("disabled") || accountState.equals("deleted") ||
                     accountState.equals("inactive")) stateLabel = "AKUN RENTAL NONAKTIF";
            else if (remaining <= 0) stateLabel = "BILLING HABIS · TOPUP UNTUK BERMAIN";
            else if (accountState.equals("expired")) stateLabel = "MASA AKTIF EXPIRED · TOPUP UNTUK BERMAIN";
            else if (activeSession) stateLabel = "SESI REMOTE PLAY SEDANG AKTIF";
            else if (cooldownActive) stateLabel = "COOLDOWN · TUNGGU " + formatSeconds(cooldownSeconds);
            else stateLabel = canPlay ? "AKUN AKTIF · SIAP BERMAIN" : "AKSES REMOTE PLAY BELUM SIAP";

            statusText.setText(stateLabel);
            statusText.setTextColor(canPlay ? Color.rgb(91,230,183) : Color.rgb(255,190,100));

            if (!canPlay && !expiredPopupShown &&
                    (remaining <= 0 || accountState.equals("expired"))) {
                expiredPopupShown = true;
                new AlertDialog.Builder(this)
                    .setTitle("Billing habis")
                    .setMessage("Sisa waktu rental habis atau masa aktif sudah expired. Dashboard tetap bisa dibuka, tetapi akses PC dikunci sampai billing diisi ulang.")
                    .setPositiveButton("Isi ulang sekarang",(d,w)->showTopup())
                    .setNegativeButton("Nanti",null).show();
            }
        }

        JSONArray hosts = data.optJSONArray("hosts");
        if(content.getChildCount()>6) content.removeViews(6, content.getChildCount() - 6);
        if (hosts == null || hosts.length() == 0) {
            messageText.setText("Belum ada PC Remote Play yang diizinkan Admin atau Host Agent belum online.");
            return;
        }
        messageText.setText(hosts.length() + " PC terdaftar. Status diperbarui dari DB Android.");
        for (int i = 0; i < hosts.length(); i++) {
            JSONObject host = hosts.optJSONObject(i);
            if (host != null) addHostCard(host, canPlay, unavailableReason);
        }
    }

    private void addHostCard(JSONObject host, boolean canPlay, String unavailableReason) {
        String name = host.optString("device_name", "PC Gaming");
        boolean hostOnline = host.optBoolean("online", false);
        boolean hostAvailable = host.optBoolean("available", false);
        String state = !hostOnline ? "offline" : hostAvailable ? "available" : "busy";
        String id = host.optString("device_id", "");
        String gpu = "";
        String cpu = "";
        LinearLayout item=card();
        item.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=new TextView(this);
        badge.setText(hostStatus(state));
        badge.setTextColor(statusColor(state));
        badge.setTextSize(10);
        badge.setPadding(dp(9),dp(5),dp(9),dp(5));
        badge.setBackground(rounded(Color.rgb(10,22,36),20));
        top.addView(badge);
        item.addView(top);
        String specs="";
        if(!cpu.isEmpty()) specs += cpu;
        if(!gpu.isEmpty()) specs += (specs.isEmpty()?"":" · ")+gpu;
        String mediaAddress=host.optString("media_address","");
        String networkStatus=isCloudflareMeshAddress(mediaAddress)
            ?"Jalur privat: Cloudflare Mesh"
            :(host.optBoolean("media_path_ready",false)?"Jalur privat tersedia":"Jalur media belum siap");
        TextView detail=normalText((specs.isEmpty()?"Host Agent: "+host.optString("host_agent_version","terdaftar"):specs)+" • "+networkStatus);
        detail.setPadding(0,dp(8),0,dp(12));
        item.addView(detail);

        Button connect=new Button(this);
        boolean available=state.equals("available") && canPlay && !id.isEmpty();
        connect.setEnabled(available);
        connect.setTextColor(available?Color.rgb(4,22,26):Color.rgb(150,166,185));
        connect.setText(available?"HUBUNGKAN PC":state.equals("busy")?"SEDANG DIGUNAKAN":state.equals("offline")?"OFFLINE":unavailableReason);
        connect.setBackground(rounded(available?Color.rgb(75,226,179):Color.rgb(42,56,75),10));
        item.addView(connect,new LinearLayout.LayoutParams(-1,dp(44)));
        connect.setOnClickListener(v->connect(host));
        content.addView(item);
    }

    private void connect(JSONObject host) {
        if(busy) return;
        String advertisedMediaAddress=host.optString("media_address","");
        if(isCloudflareMeshAddress(advertisedMediaAddress) && !hasCloudflareMeshAddress()) {
            new AlertDialog.Builder(this)
                .setTitle("Cloudflare One belum tersambung")
                .setMessage("PC menggunakan Cloudflare Mesh, tetapi HP belum memiliki alamat Mesh 100.96.x.x. Buka Cloudflare One Agent, masuk ke organisasi yang sama, aktifkan koneksi VPN, lalu kembali ke Ani Moonlight dan tekan Refresh.")
                .setPositiveButton("Buka Cloudflare One",(d,w)->openCloudflareOne())
                .setNegativeButton("Nanti",null)
                .show();
            return;
        }
        String hostId=host.optString("device_id","");
        String name=host.optString("device_name","PC");
        busy=true;
        messageText.setText("Mengunci sesi dan menyiapkan koneksi ke "+name+"...");
        worker.execute(()->{
            String token="";
            JSONObject started=null;
            try {
                token=AniSecurePrefs.getClientToken(this);
                started=AniRemoteApi.startSession(this,hostId,UUID.randomUUID().toString());
                String sessionId=started.optString("session_id","");
                String address=started.optString("media_address","");
                int port=started.optInt("media_port",47989);
                if(sessionId.isEmpty() || address.isEmpty()) {
                    try { if(!sessionId.isEmpty()) AniRemoteApi.stopSession(this,sessionId,"host_route_unavailable"); } catch(Exception ignored) {}
                    throw new Exception("HOST_AGENT_OR_ROUTE_NOT_READY");
                }
                AniSecurePrefs.saveActiveSession(this,sessionId);
                String finalToken=token;
                String finalSessionId=sessionId;
                String finalAddress=address;
                runOnUiThread(()->{
                    busy=false;
                    addHostAndOpen(name,finalAddress,port,finalToken,finalSessionId);
                });
            } catch(Exception e) {
                if(started!=null) {
                    String sid=started.optString("session_id","");
                    if(!sid.isEmpty() && !started.optBoolean("idempotent",false)) {
                        try { AniRemoteApi.stopSession(this,sid,"connect_prepare_failed"); } catch(Exception ignored) {}
                    }
                }
                runOnUiThread(()->{
                    busy=false;
                    messageText.setText(e.getMessage()!=null && e.getMessage().contains("HOST_AGENT_OR_ROUTE_NOT_READY")
                        ?"PC belum memiliki rute media TCP/UDP yang aktif. Host Agent belum siap."
                        :"Gagal memulai sesi: "+(e.getMessage()==null?"REMOTE_PLAY_ERROR":e.getMessage()));
                });
            }
        });
    }

    private void addHostAndOpen(String name,String address,int port,String token,String sessionId) {
        final ServiceConnection[] connectionRef = new ServiceConnection[1];
        connectionRef[0] = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName cls, IBinder binder) {
                ComputerManagerService.ComputerManagerBinder manager=(ComputerManagerService.ComputerManagerBinder)binder;
                new Thread(()->{
                    boolean added=false;
                    try {
                        manager.waitForReady();
                        ComputerDetails host=new ComputerDetails();
                        host.name=name;
                        host.manualAddress=new ComputerDetails.AddressTuple(address,port);
                        added=manager.addComputerBlocking(host);
                        if(!added) throw new Exception("MOONLIGHT_HOST_POLL_FAILED");
                        // Keep only the host selected by the server. Preserve its pinned
                        // certificate so reconnecting to this PC does not require re-pairing.
                        for (ComputerDetails stored : manager.getAllComputers()) {
                            if (stored.uuid != null && !stored.uuid.equals(host.uuid)) {
                                manager.removeComputer(stored);
                            }
                        }
                        runOnUiThread(()->{
                            try { unbindService(connectionRef[0]); } catch(Exception ignored) {}
                            messageText.setText("PC terhubung. Ketuk PC yang dipilih untuk memulai pairing otomatis.");
                            startActivity(new Intent(AniDashboardActivity.this, PcView.class));
                        });
                    } catch(Exception e) {
                        try { AniRemoteApi.stopSession(AniDashboardActivity.this,sessionId,"host_unreachable"); } catch(Exception ignored) {}
                        AniSecurePrefs.clearActiveSession(AniDashboardActivity.this);
                        runOnUiThread(()->{
                            try { unbindService(connectionRef[0]); } catch(Exception ignored) {}
                            messageText.setText("PC tidak dapat dijangkau melalui rute yang didaftarkan. Sesi dibatalkan.");
                        });
                    }
                }).start();
            }
            @Override public void onServiceDisconnected(ComponentName cls) {}
        };
        bindService(new Intent(this,ComputerManagerService.class),connectionRef[0],Context.BIND_AUTO_CREATE);
    }

    private static boolean isCloudflareMeshAddress(String value) {
        if(value==null || value.isEmpty()) return false;
        String[] parts=value.split("\\.");
        if(parts.length!=4) return false;
        try {
            int a=Integer.parseInt(parts[0]),b=Integer.parseInt(parts[1]);
            int c=Integer.parseInt(parts[2]),d=Integer.parseInt(parts[3]);
            return a==100 && b>=96 && b<=111 && c>=0 && c<=255 && d>=0 && d<=255;
        } catch(NumberFormatException ignored) { return false; }
    }

    private boolean hasCloudflareMeshAddress() {
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            if(interfaces==null) return false;
            while(interfaces.hasMoreElements()) {
                NetworkInterface network=interfaces.nextElement();
                try {
                    if(!network.isUp() || network.isLoopback()) continue;
                    Enumeration<InetAddress> addresses=network.getInetAddresses();
                    while(addresses.hasMoreElements()) {
                        InetAddress address=addresses.nextElement();
                        if(address instanceof Inet4Address &&
                           isCloudflareMeshAddress(address.getHostAddress())) return true;
                    }
                } catch(SocketException ignored) {}
            }
        } catch(SocketException ignored) {}
        return false;
    }

    private void openCloudflareOne() {
        try {
            Intent installed=getPackageManager().getLaunchIntentForPackage("com.cloudflare.cloudflareoneagent");
            if(installed!=null) {
                startActivity(installed);
                return;
            }
            Intent store=new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=com.cloudflare.cloudflareoneagent"));
            startActivity(store);
        } catch(Exception e) {
            new AlertDialog.Builder(this)
                .setTitle("Cloudflare One Agent")
                .setMessage("Pasang Cloudflare One Agent dari Google Play, lalu hubungkan ke organisasi Cloudflare yang sama dengan PC Host.")
                .setPositiveButton("OK",null).show();
        }
    }

    private void showTopup() {
        String token;
        try { token=AniSecurePrefs.getClientToken(this); }
        catch(Exception e) { token=""; }
        final String savedToken=token;
        worker.execute(()->{
            JSONObject info=null;
            try { info=AniRemoteApi.topupInfo(this); } catch(Exception ignored) {}
            JSONObject finalInfo=info;
            runOnUiThread(()->{
                String qris=finalInfo==null?"":finalInfo.optString("qris_image_url","");
                String wa=finalInfo==null?"082298316902":finalInfo.optString("merchant_whatsapp","082298316902");
                String text="TOPUP BILLING ANI GAMES READY\n\n";
                if(qris.isEmpty()) text+="QRIS belum tersedia di server. Admin perlu memasang file QRIS merchant yang asli.\n\n";
                else text+="QRIS tersedia: "+qris+"\n\n";
                text+="Setelah transfer, kirim bukti pembayaran dan User ID melalui WhatsApp "+wa+". Saldo ditambahkan setelah Admin memverifikasi pembayaran.";
                new AlertDialog.Builder(this)
                    .setTitle("Topup Billing")
                    .setMessage(text)
                    .setPositiveButton("Buka WhatsApp",(d,w)->{
                        String phone=wa.replaceAll("[^0-9]","");
                        if(phone.startsWith("0")) phone="62"+phone.substring(1);
                        String msg="Halo Ani Games Ready, saya ingin topup billing. User ID: "+currentCode;
                        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+phone+"?text="+Uri.encode(msg)))); }
                        catch(Exception ignored) {}
                    })
                    .setNegativeButton("Tutup",null).show();
            });
        });
    }

    private LinearLayout card() {
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15),dp(15),dp(15),dp(15));
        box.setBackground(rounded(Color.rgb(20,34,54),14));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(10); p.bottomMargin=dp(4);
        box.setLayoutParams(p);
        return box;
    }
    private TextView label(String text) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(10);t.setTextColor(Color.rgb(158,180,207));return t;
    }
    private TextView bigText(String text) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(22);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setTextColor(Color.WHITE);return t;
    }
    private TextView bigAccent(String text) {
        TextView t=bigText(text);t.setTextColor(Color.rgb(84,231,183));t.setTextSize(21);return t;
    }
    private TextView normalText(String text) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(12);t.setTextColor(Color.rgb(187,202,221));return t;
    }
    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private int dp(float v) {return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private String formatSeconds(long seconds) {
        seconds=Math.max(0,seconds);
        long h=seconds/3600,m=(seconds%3600)/60,s=seconds%60;
        return String.format(Locale.US,"%02d:%02d:%02d",h,m,s);
    }
    private String formatExpiry(String value) {
        if(value==null || value.isEmpty() || value.equals("null")) return "Tidak ditentukan";
        try { return value.replace("T"," ").replace("Z"," UTC").substring(0,Math.min(value.length(),19)); }
        catch(Exception e) { return value; }
    }
    private String hostStatus(String state) {
        switch(state) {
            case "available":return "TERSEDIA";
            case "busy":return "DIGUNAKAN";
            case "offline":return "OFFLINE";
            case "maintenance":return "MAINTENANCE";
            default:return state.toUpperCase(Locale.US);
        }
    }
    private int statusColor(String state) {
        if(state.equals("available")) return Color.rgb(91,230,183);
        if(state.equals("busy")) return Color.rgb(255,190,100);
        return Color.rgb(255,135,135);
    }
    private String accessLabel(String state) {
        switch(state) {
            case "active":return "AKUN AKTIF · SIAP BERMAIN";
            case "time_empty":return "BILLING HABIS · TOPUP UNTUK BERMAIN";
            case "expired":return "MASA AKTIF EXPIRED · TOPUP UNTUK BERMAIN";
            case "cooldown":return "COOLDOWN · TUNGGU SEBELUM LANJUT BERMAIN";
            default:return state.toUpperCase(Locale.US);
        }
    }

    @Override protected void onResume() { super.onResume(); if(messageText!=null) refresh(); }
    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }
}
