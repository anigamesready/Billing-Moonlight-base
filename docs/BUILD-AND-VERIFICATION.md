# Build and verification plan

Status: **not yet built as Ani Games Ready Remote Play**.

## 1. Inputs audited

| File | What it is | Verification |
|---|---|---|
| `app-nonRoot-release.apk` | Moonlight Android upstream baseline | Binary manifest: package `com.limelight`, versionName `12.2`, versionCode `315`; embedded VCS metadata identifies upstream Moonlight v12.2 commit |
| `MoonlightSetup-6.2.0.exe` | Windows Moonlight client installer | PE32 executable; not an Ani Games Ready Host Agent |
| `Sunshine-Windows-AMD64-installer.msi` | Sunshine Windows host installer | MSI; upstream Sunshine installer, not an Ani Games Ready Host Agent |
| QRIS JPEG | Top-up display asset | Uploaded asset exists; must be copied into the Android project only after the final app branding/layout is ready |

SHA-256 values for the files in the current conversation are recorded in the task handover, not hardcoded into the application. Recompute before any later release packaging.

The APK is a convenient audited baseline artifact, but release builds must compile from the pinned upstream source in this repository's CI, not patch the binary APK in place. The final application must use its own package ID and signing key; it must not impersonate the official `com.limelight` package.

## 2. Android application

- Source: Moonlight Android v12.2 at the exact commit in `ARCHITECTURE.md`.
- Create the Ani dashboard/login flow ahead of Moonlight's streaming screen while retaining Moonlight's decoder, controller, touch, audio and GameStream implementation.
- Use a new package ID, provisionally `com.anigames.remoteplay`, and app label `Ani Games Ready Remote`. Verify all authorities, deep links, file providers, backup keys and native library loads after the package change.
- Add the existing DB Android API client adapter; use the current shared API base URL configuration and secure Android Keystore-backed customer session storage.
- Customer UI: login/activation, PC list, online/available/in-use state, balance, expiry, start, active-session countdown, end, cooldown countdown and top-up details.
- Do not make the user enter a Sunshine IP or manually pair a PC. The server/Host Agent must issue a short-lived per-session host grant and perform the host-side pairing/authorization.
- Preserve Moonlight's certificate and private-key lifecycle on the Android device. Never upload private keys to the API.
- Do not copy a Supabase SDK, key, endpoint, function, project reference, or dual-DB route into Gradle, resources, native code or app config.

## 3. Windows Host Agent

Build a separate signed/self-contained Windows x64 Host Agent (not a replacement for Sunshine):

- Run as a least-privilege Windows service or controlled background agent with a visible tray/status surface.
- Enroll using a one-time Admin-issued host code; bind to an existing `public.devices` / organization identity and an explicit Remote Play permission.
- Store its host token with Windows DPAPI. Never store DB passwords, PostgreSQL URL or Admin token on the PC.
- Heartbeat the DB Android API, report Sunshine readiness, receive only host-scoped commands and acknowledge results.
- Start/stop the authorized Sunshine streaming session; stop at server deadline and after Admin revocation.
- Keep Sunshine Web UI bound to localhost or LAN-only administration. Do not expose it to WAN.
- Do not operate or stop the existing Blynk/WoL relay.

The Host Agent source and Windows packaging workflow are still required; the uploaded Sunshine MSI does not supply this Ani-specific agent.

## 4. Backend work required

Use the existing Node API and PostgreSQL. Add a dedicated `remote_play` schema and API functions/RPC allowlist additions. Do not apply schema changes to the live Redmi DB from CI.

Backend CI must:
- create a disposable PostgreSQL database;
- apply only the approved DB Android migrations and the new additive Remote Play migration in order (never apply catalog snapshot `001_db1_catalog_snapshot.sql`);
- verify role grants, token hashes, transaction locks, unique active-session constraints, cross-PC Billing session guards, balance settlement, 3-hour cap and 10-minute cooldown;
- test login rate limit, token expiry/revocation, host permission revocation, lost heartbeat and idempotent settlement;
- backup and restore the disposable database and rerun the contract checks.

## 5. Network work required

- Control requests use the same verified HTTPS endpoint/config as DB Android.
- Moonlight media uses a separate secure path that carries GameStream TCP **and UDP** traffic. API/Worker HTTP reverse proxies are not the streaming path.
- Prefer direct peer connectivity when possible, with a tested UDP-capable secure relay/overlay fallback. The app should receive a per-session route from the control API, not ask the user to type an IP address.
- Test home Wi-Fi, mobile LTE/5G, symmetric NAT, changed public IP, host reconnect and app background/foreground.
- Do not open Sunshine Web UI or expose the whole PC network.

## 6. CI and artifact requirements

Required reproducible outputs:
- `Ani-Games-Ready-RemotePlay-Android.apk`
- `Ani-Games-Ready-RemotePlay-HostAgent-x64.exe` (or signed installer)
- version manifest with Android applicationId/versionCode, Windows file/product version, exact source commits, workflow run IDs, SHA-256 checksums and dependency lock information.

Before release:
- Android clean install, update/replace, login, dashboard, controller input, video/audio, screen rotation, background/resume, network drop/reconnect and logout tests.
- Windows fresh install, startup, service recovery, Sunshine detection, admin permission revoke, 3-hour auto-stop, network loss, reboot and uninstall/rollback tests.
- Database concurrency and balance settlement tests listed in `DB-ANDROID-API-CONTRACT.md`.
- Paired-PC end-to-end test, including the same rental account being denied on a second PC.
- Confirm no Supabase runtime code/config remains in the built APK or Host Agent.
- Verify APK signature, package ID, version, executable metadata, file sizes and SHA-256.

A successful upstream Moonlight build alone is **not** a successful Ani Games Ready Remote Play release.
