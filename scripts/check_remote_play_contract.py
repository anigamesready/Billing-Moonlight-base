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
              "remote_play_request_session", "remote_play_session_heartbeat",
              "remote_play_end_session", "remote_play_pairing_prepare"):
    if token not in api:
        raise SystemExit("DB Android RPC not wired in Android client: " + token)

for token in ("USER ID RENTAL", "KODE AKTIVASI REMOTE PLAY", "AniRemoteApi.login"):
    if token not in login:
        raise SystemExit("Activation-code login incomplete: " + token)
dashboard = read("android-overlay/app/src/main/java/com/limelight/AniDashboardActivity.java")
for token in ("hasCloudflareMeshAddress()", "100.96.x.x",
              "com.cloudflare.cloudflareoneagent", "media_address"):
    if token not in dashboard:
        raise SystemExit("Android Cloudflare Mesh preflight is missing: " + token)

for token in ("AndroidKeyStore", "AES/GCM/NoPadding", "saveRefreshToken"):
    if token not in secure:
        raise SystemExit("Android token at-rest protection missing: " + token)

for token in ("remote_play_host_enroll", "remote_play_host_heartbeat",
              "remote_play_host_next_command", "remote_play_host_pairing_poll",
              "remote_play_host_pairing_complete"):
    if token not in worker:
        raise SystemExit("Host Agent RPC not wired: " + token)

if "127.0.0.1" not in config or "47990" not in config:
    raise SystemExit("Sunshine API must stay bound to local loopback.")
for token in ("NormalizePeerAddress", 'GetString(item, "address")',
              'GetString(item, "name")', "SunshinePendingPairings",
              'GetString(challenge, "expected_client_address")',
              "IsApprovedOverlayAddress(expectedClientAddress)",
              "string.Equals(address, expectedClientAddress"):
    if token not in worker:
        raise SystemExit("Sunshine pairing is not bound to the exact DB-approved peer address: " + token)
if "IsApprovedOverlayAddress" not in config:
    raise SystemExit("Host Agent approved overlay address validator is missing.")
if "ProtectedData.Protect" not in config or "HostTokenProtected" not in config:
    raise SystemExit("Host Agent token must be protected using Windows DPAPI.")
for token in ("ResolveMediaAddress()", "IsCloudflareMeshAddress", "100.96.0.0/12",
              'transport == "cloudflare_mesh"', 'transport == "wireguard"'):
    if token not in config:
        raise SystemExit("Host Agent Cloudflare Mesh address validation is missing: " + token)
for token in ("ResolveMediaAddress()", "_mediaRouteWarningShown",
              "mediaRouteReady ? mediaAddress : null", "AgentVersion = \"0.3.0\""):
    if token not in worker:
        raise SystemExit("Host Agent dynamic Mesh heartbeat is missing: " + token)
for token in ("MediaTransport = $mediaTransport", "Cloudflare Mesh TCP",
              "Cloudflare Mesh UDP", "100.96.0.0/12", "48002", "Block Sunshine Web UI from Mesh",
              "sc.exe failure $serviceName", "sc.exe failureflag $serviceName"):
    if token not in installer:
        raise SystemExit("Host Agent installer safety/recovery is incomplete: " + token)
for token in ("await base.StopAsync(cancellationToken)",
              "new CancellationTokenSource(TimeSpan.FromSeconds(4))",
              "await CloseSunshineStream(shutdown.Token)"):
    if token not in worker:
        raise SystemExit("Host Agent stop cleanup is incomplete: " + token)
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
