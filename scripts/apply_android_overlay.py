#!/usr/bin/env python3
"""Apply the Ani Remote Play Java overlay to an upstream Moonlight Android checkout."""
from __future__ import annotations
import argparse
import os
import re
from urllib.parse import urlsplit
import shutil
from pathlib import Path

def read(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(path)
    return path.read_text(encoding="utf-8")

def write(path: Path, value: str) -> None:
    path.write_text(value, encoding="utf-8", newline="\n")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0:
        if new in text:
            return text
        raise RuntimeError(f"Cannot patch {label}: expected source block not found")
    if count != 1:
        raise RuntimeError(f"Cannot patch {label}: expected 1 source block, got {count}")
    return text.replace(old, new, 1)

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream", default="upstream", help="Moonlight Android checkout")
    parser.add_argument("--overlay", default="android-overlay", help="Ani overlay folder")
    args = parser.parse_args()
    root = Path(args.upstream).resolve()
    overlay = Path(args.overlay).resolve()

    if not (root / "app/src/main/java/com/limelight/Game.java").exists():
        raise SystemExit("The upstream path is not a Moonlight Android source checkout")
    if not overlay.exists():
        raise SystemExit(f"Ani overlay folder not found: {overlay}")

    # Copy only our isolated Java additions into the upstream app package.
    java_src = overlay / "app/src/main/java/com/limelight"
    java_dst = root / "app/src/main/java/com/limelight"
    for source in java_src.glob("*.java"):
        shutil.copy2(source, java_dst / source.name)

    # Distinct app identity. Keep upstream root/non-root flavor behavior intact.
    gradle_path = root / "app/build.gradle"
    gradle = read(gradle_path)
    # Build-time value must match the existing DB Android HTTPS gateway.
    # Empty is allowed only for diagnostic builds; the app then fails closed at login.
    api_base = os.environ.get("DB_ANDROID_API_BASE_URL", "").strip().rstrip("/")
    if api_base:
        parsed = urlsplit(api_base)
        host = (parsed.hostname or "").lower()
        if (parsed.scheme != "https" or not host or parsed.username or parsed.password or
                parsed.query or parsed.fragment or
                parsed.path not in ("", "/", "/functions/v1/ani-games-api") or
                host in ("localhost", "127.0.0.1", "::1", "0.0.0.0") or
                host.endswith(".trycloudflare.com") or "supabase.co" in host):
            raise RuntimeError("DB_ANDROID_API_BASE_URL must be the existing stable DB Android HTTPS gateway")
    gradle = gradle.replace(
        "android {",
        'def dbAndroidApiBaseUrl = (project.findProperty("dbAndroidApiBaseUrl") ?: System.getenv("DB_ANDROID_API_BASE_URL") ?: "").toString()\n\nandroid {',
        1
    )
    api_field = '            buildConfigField "String", "DB_ANDROID_API_BASE_URL", groovy.json.JsonOutput.toJson(dbAndroidApiBaseUrl)'
    gradle, count = re.subn(
        r'(nonRoot\s*\{[\s\S]*?buildConfigField "boolean", "ROOT_BUILD", "false"\s*\n)',
        lambda m: m.group(1) + api_field + "\n",
        gradle, count=1
    )
    if count != 1:
        raise RuntimeError("Could not inject DB Android API endpoint BuildConfig field")
    gradle = re.sub(r'(?m)^\s*minSdk\s+21\s*$', '        minSdk 23', gradle, count=1)
    if 'minSdk 23' not in gradle:
        raise RuntimeError("minSdk patch failed")
    gradle, n = re.subn(
        r'(nonRoot\s*\{[\s\S]*?applicationId\s+)"com\\.limelight"',
        r'\1"id.anigamesready.moonlight"', gradle, count=1
    )
    # The regex above uses a literal Java string in the source file.
    if n == 0:
        gradle, n = re.subn(
            r'(nonRoot\s*\{[\s\S]*?applicationId\s+)"com\.limelight"',
            r'\1"id.anigamesready.moonlight"', gradle, count=1
        )
    if n != 1:
        raise RuntimeError("Could not assign the Ani Moonlight application ID")
    gradle = gradle.replace('versionName "12.2"', 'versionName "12.2-ani"', 1)
    gradle = gradle.replace('resValue "string", "app_label", "Moonlight"',
                            'resValue "string", "app_label", "Ani Moonlight"', 1)
    gradle = gradle.replace('resValue "string", "app_label_root", "Moonlight (Root)"',
                            'resValue "string", "app_label_root", "Ani Moonlight (Root)"', 1)
    gradle = gradle.replace('resValue "string", "app_label", "Moonlight (Debug)"',
                            'resValue "string", "app_label", "Ani Moonlight (Beta)"')
    gradle = gradle.replace('resValue "string", "app_label_root", "Moonlight (Root Debug)"',
                            'resValue "string", "app_label_root", "Ani Moonlight (Root Beta)"')
    write(gradle_path, gradle)

    # Give each installation its own certificate CN, which the Host Agent matches
    # against the exact one-time pairing challenge in the backend.
    crypto_path = java_dst / "binding/crypto/AndroidCryptoProvider.java"
    crypto = read(crypto_path)
    if "import com.limelight.AniSecurePrefs;" not in crypto:
        crypto = crypto.replace("import com.limelight.LimeLog;\n",
                                "import com.limelight.LimeLog;\nimport com.limelight.AniSecurePrefs;\n", 1)
    crypto = replace_once(
        crypto,
        '    private final File certFile;\n    private final File keyFile;\n',
        '    private final File certFile;\n    private final File keyFile;\n    private final Context appContext;\n',
        "Android client certificate context"
    )
    crypto = replace_once(
        crypto,
        '    public AndroidCryptoProvider(Context c) {\n        String dataPath = c.getFilesDir().getAbsolutePath();',
        '    public AndroidCryptoProvider(Context c) {\n        appContext = c.getApplicationContext();\n        String dataPath = c.getFilesDir().getAbsolutePath();',
        "Android crypto constructor"
    )
    crypto = replace_once(
        crypto,
        '        nameBuilder.addRDN(BCStyle.CN, "NVIDIA GameStream Client");',
        '        nameBuilder.addRDN(BCStyle.CN, AniSecurePrefs.getClientName(appContext));',
        "unique Android certificate CN"
    )
    write(crypto_path, crypto)

    # Do not run local LAN mDNS discovery in Ani Remote Play mode; the dashboard
    # injects only the host selected and authorized by the server.
    svc_path = java_dst / "computers/ComputerManagerService.java"
    svc = read(svc_path)
    svc = replace_once(
        svc,
        '            // Start mDNS autodiscovery too\n            discoveryBinder.startDiscovery(MDNS_QUERY_PERIOD_MS);',
        '            // Ani Remote uses the centrally authorized host address; do not leak back to LAN discovery.\n'
        '            if (!getSharedPreferences("AniRemote", 0).getBoolean("remote_play_mode", false)) {\n'
        '                discoveryBinder.startDiscovery(MDNS_QUERY_PERIOD_MS);\n'
        '            }',
        "disable local mDNS in Ani mode"
    )
    write(svc_path, svc)

    # Expose the native client database so the selected host can be isolated.
    svc = replace_once(
        svc,
        '        public ComputerDetails getComputer(String uuid) {',
        '        public List<ComputerDetails> getAllComputers() {\n'
        '            return dbManager.getAllComputers();\n'
        '        }\n\n'
        '        public ComputerDetails getComputer(String uuid) {',
        "expose stored Moonlight hosts"
    )
    write(svc_path, svc)

    # Replace manual PIN display with a server-issued pairing challenge in Ani mode.
    pc_path = java_dst / "PcView.java"
    pc = read(pc_path)
    dialog_block = (
        '                        // Spin the dialog off in a thread because it blocks\n'
        '                        Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),\n'
        '                                getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\\n\\n"+\n'
        '                                getResources().getString(R.string.pair_pairing_help), false);'
    )
    challenge_block = (
        '                        boolean aniRemote = AniSecurePrefs.isRemoteMode(PcView.this);\n'
        '                        if (aniRemote) {\n'
        '                            try {\n'
        '                                String token = AniSecurePrefs.getClientToken(PcView.this);\n'
        '                                String sessionId = AniSecurePrefs.getActiveSession(PcView.this);\n'
        '                                if (sessionId == null || sessionId.isEmpty()) {\n'
        '                                    throw new IOException("REMOTE_SESSION_NOT_FOUND");\n'
        '                                }\n'
        '                                AniRemoteApi.preparePairing(PcView.this, sessionId, pinStr, AniSecurePrefs.getClientName(PcView.this));\n'
        '                            } catch (Exception pairingError) {\n'
        '                                throw new IOException("REMOTE_PAIRING_CHALLENGE_FAILED: " + pairingError.getMessage(), pairingError);\n'
        '                            }\n'
        '                        } else {\n'
        '                            // Preserve normal upstream Moonlight pairing outside Ani mode.\n'
        + dialog_block +
        '\n                        }'
    )
    pc = replace_once(pc, dialog_block, challenge_block, "automatic pairing dialog")
    write(pc_path, pc)

    # Timer activation is tied to an actual successful stream connection.
    game_path = java_dst / "Game.java"
    game = read(game_path)
    game = replace_once(
        game,
        '                connected = true;\n                connecting = false;\n                updatePipAutoEnter();',
        '                connected = true;\n                connecting = false;\n'
        '                AniSessionController.onStreamConnected(Game.this, findViewById(R.id.aniRemoteTimer));\n'
        '                updatePipAutoEnter();',
        "start server timer only after stream is connected"
    )
    # For Ani sessions, every stream termination returns to the Ani dashboard.
    game = replace_once(
        game,
        '                // Stop processing controller input\n                controllerHandler.stop();',
        '                if (AniSecurePrefs.isRemoteMode(Game.this)) {\n'
        '                    AniSessionController.stop(Game.this, "stream_terminated");\n'
        '                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {\n'
        '                        Toast.makeText(Game.this, "Koneksi remote terputus. Sesi ditutup.", Toast.LENGTH_LONG).show();\n'
        '                    }\n'
        '                    finish();\n'
        '                    Intent dashboard = new Intent(Game.this, AniDashboardActivity.class);\n'
        '                    dashboard.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);\n'
        '                    startActivity(dashboard);\n'
        '                    return;\n'
        '                }\n'
        '                // Stop processing controller input\n                controllerHandler.stop();',
        "return to Ani dashboard when stream terminates"
    )
    game = replace_once(
        game,
        '    protected void onDestroy() {\n        super.onDestroy();',
        '    protected void onDestroy() {\n'
        '        if (isFinishing() && AniSecurePrefs.isRemoteMode(this)) {\n'
        '            AniSessionController.stop(this, "activity_finished");\n'
        '        }\n'
        '        super.onDestroy();',
        "stop the rental on activity destruction"
    )
    write(game_path, game)

    # Stopwatch overlay remains visible over the stream and inside Android PiP.
    layout_path = root / "app/src/main/res/layout/activity_game.xml"
    layout = read(layout_path)
    timer_view = (
        '\n    <TextView\n'
        '        android:id="@+id/aniRemoteTimer"\n'
        '        android:layout_width="wrap_content"\n'
        '        android:layout_height="wrap_content"\n'
        '        android:layout_marginEnd="12dp"\n'
        '        android:layout_marginRight="12dp"\n'
        '        android:layout_marginBottom="12dp"\n'
        '        android:layout_gravity="end|bottom"\n'
        '        android:background="#D91A2A3D"\n'
        '        android:paddingLeft="12dp"\n'
        '        android:paddingRight="12dp"\n'
        '        android:paddingTop="8dp"\n'
        '        android:paddingBottom="8dp"\n'
        '        android:textColor="#FFFFFF"\n'
        '        android:textSize="12sp"\n'
        '        android:visibility="gone"\n'
        '        android:elevation="8dp" />\n'
    )
    if 'android:id="@+id/aniRemoteTimer"' not in layout:
        layout = layout.replace('</merge>', timer_view + '\n</merge>', 1)
    write(layout_path, layout)

    # Launcher becomes the User ID login; original Moonlight screens stay internal
    # and are launched by the authenticated remote flow.
    manifest_path = root / "app/src/main/AndroidManifest.xml"
    manifest = read(manifest_path)
    pc_start = manifest.find('<activity\n            android:name=".PcView"')
    if pc_start < 0:
        raise RuntimeError("PcView activity block not found in manifest")
    pc_end = manifest.find('</activity>', pc_start)
    if pc_end < 0:
        raise RuntimeError("PcView activity close tag not found")
    pc_end += len('</activity>')
    pc_block = manifest[pc_start:pc_end]
    pc_block, removed = re.subn(r'\s*<intent-filter>[\s\S]*?</intent-filter>', '', pc_block, count=1)
    if removed == 0 and 'android.intent.category.LAUNCHER' in pc_block:
        raise RuntimeError("Could not remove the upstream launcher intent filter")
    new_activities = (
        '<activity\n'
        '            android:name=".AniLoginActivity"\n'
        '            android:exported="true"\n'
        '            android:resizeableActivity="true"\n'
        '            android:windowSoftInputMode="adjustResize">\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n'
        '                <category android:name="android.intent.category.LAUNCHER" />\n'
        '            </intent-filter>\n'
        '        </activity>\n'
        '        <activity\n'
        '            android:name=".AniDashboardActivity"\n'
        '            android:exported="false"\n'
        '            android:resizeableActivity="true" />\n'
    )
    pc_block = pc_block.replace('android:exported="true"', 'android:exported="false"', 1)
    manifest = manifest[:pc_start] + new_activities + pc_block + manifest[pc_end:]
    write(manifest_path, manifest)

    # Guard against accidental omission when the upstream source changes.
    required = [
        "app/src/main/java/com/limelight/AniRemoteApi.java",
        "app/src/main/java/com/limelight/AniSecurePrefs.java",
        "app/src/main/java/com/limelight/AniLoginActivity.java",
        "app/src/main/java/com/limelight/AniDashboardActivity.java",
        "app/src/main/java/com/limelight/AniSessionController.java",
    ]
    missing = [p for p in required if not (root / p).exists()]
    if missing:
        raise RuntimeError("Missing overlay sources: " + ", ".join(missing))

    print("Ani Remote Play overlay applied successfully.")
    print("Package: id.anigamesready.moonlight (release nonRoot; .debug suffix on debug build)")
    print("Upstream Moonlight: v12.2")

if __name__ == "__main__":
    main()
