#!/usr/bin/env python3
"""Static contract checks for the isolated Ani Remote Play source tree."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
required = [
    "android-overlay/app/src/main/java/com/limelight/AniRemoteApi.java",
    "android-overlay/app/src/main/java/com/limelight/AniSecurePrefs.java",
    "android-overlay/app/src/main/java/com/limelight/AniLoginActivity.java",
    "android-overlay/app/src/main/java/com/limelight/AniDashboardActivity.java",
    "android-overlay/app/src/main/java/com/limelight/AniSessionController.java",
    "host-agent/HostAgentConfig.cs",
    "host-agent/HostAgentWorker.cs",
    "host-agent/install-host-agent.ps1",
    "scripts/apply_android_overlay.py",
]
missing = [p for p in required if not (ROOT / p).is_file()]
if missing:
    raise SystemExit("Missing required source files: " + ", ".join(missing))

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

api = read(required[0])
login = read(required[2])
secure = read(required[1])
worker = read(required[6])
config = read(required[5])
installer = read(required[7])
patcher = read(required[8])

for token in ("remote_play_login", "remote_play_refresh", "remote_play_dashboard",
              "remote_play_request_session", "remote_play_session_mark_streaming",
              "remote_play_session_heartbeat", "remote_play_end_session",
              "remote_play_pairing_prepare"):
    if token not in api:
        raise SystemExit("DB Android RPC not wired in Android client: " + token)

for token in ("USER ID RENTAL", "KODE AKTIVASI REMOTE PLAY", "AniRemoteApi.login"):
    if token not in login:
        raise SystemExit("Activation-code login incomplete: " + token)

for token in ("AndroidKeyStore", "AES/GCM/NoPadding", "saveRefreshToken"):
    if token not in secure:
        raise SystemExit("Android token at-rest protection missing: " + token)

for token in ("remote_play_host_enroll", "remote_play_host_heartbeat",
              "remote_play_host_next_command", "remote_play_host_sessions",
              "remote_play_host_pairing_poll", "remote_play_host_pairing_complete"):
    if token not in worker:
        raise SystemExit("Host Agent RPC not wired: " + token)

if "127.0.0.1" not in config or "47990" not in config:
    raise SystemExit("Sunshine API must stay bound to local loopback.")
for token in ("expected_client_address", "NormalizePeerAddress", 'GetString(item, "address")'):
    if token not in worker:
        raise SystemExit("Sunshine pairing is not bound to the server-approved peer address: " + token)
if "ProtectedData.Protect" not in config or "HostTokenProtected" not in config:
    raise SystemExit("Host Agent token must be protected using Windows DPAPI.")
if "DB_ANDROID_API_BASE_URL" not in patcher or "supabase.co" not in patcher:
    raise SystemExit("Build-time DB Android endpoint validation is missing.")
if "kode aktivasi" not in installer.lower() and "enrollment" not in installer.lower():
    raise SystemExit("Host enrollment setup is missing.")

# No legacy Supabase function URL or project hostname may leak into runtime sources.
runtime_sources = [
    api, login, secure, read(required[3]), read(required[4]),
    config, worker, installer
]
for source in runtime_sources:
    lowered = source.lower()
    if "wzkjpycjizexxcxrfdvr" in lowered or "https://*.supabase.co" in lowered:
        raise SystemExit("Legacy Supabase endpoint detected in runtime sources.")
    for line in lowered.splitlines():
        if "supabase.co" in line and ("https://" in line or "functions/v1" in line):
            raise SystemExit("Legacy Supabase URL detected in runtime sources.")

print("Ani Remote Play static source checks passed.")
print("Note: this does not replace backend RPC, Android runtime, Sunshine pairing, or media-path tests.")
