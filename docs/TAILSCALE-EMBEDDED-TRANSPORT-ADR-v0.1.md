# Embedded Tailscale Transport — Architecture Decision v0.1

Status: **selected for an isolated proof-of-concept; not a release implementation yet**.

This document changes the proposed media-network direction only. It does not change the DB Android quick settings, the existing HTTPS gateway, API ports, database selection, or production Billing service.

## 1. Product requirement

The renter installs exactly one Android application: **Ani Moonlight**.

The renter must not install Cloudflare One, Tailscale, WireGuard, or another VPN application; must not enter an IP address, port, VPN profile, or Sunshine PIN; and must not create a Tailscale account or expose an email address.

The first Ani Moonlight login uses the existing rental ID plus a separately issued Remote Play activation credential. The app then enrolls its own private network identity automatically. Android will still show its mandatory system VPN-consent screen the first time; Android does not permit an application to silently grant itself VPN status.

The Windows rental PC may install/run the Tailscale service as part of the Ani Host Agent setup. This is a host-side dependency, not a renter-side installation.

## 2. Selected network design — no self-owned VPS

Use Tailscale's hosted coordination/control service and its globally operated DERP relay network. The Redmi remains the existing DB Android **control plane only**; it is not a video relay and must never carry Moonlight audio/video.

Connection preference:

1. Tailscale direct peer-to-peer UDP path whenever NAT traversal permits.
2. Tailscale DERP encrypted relay when a direct path cannot be established.
3. Fail closed with a clear connection-quality/network error if neither path can carry the stream.

Tailscale documents direct connections, DERP relays and peer relays as its connection types. DERP is a real fallback but has lower throughput/QoS characteristics than a direct path, so a relayed stream must be tested at realistic bitrates. Do not promise stable 1080p/60 on every carrier merely because the control tunnel connects.

Do not self-host Headscale/DERP on Redmi for this MVP. A phone behind mobile NAT is not a dependable public UDP relay endpoint; a Cloudflare HTTP tunnel is not a replacement for a general Moonlight media relay.

## 3. Android integration — one APK

Do not use the unofficial `tailscale_mobile` TCP-proxy layer as the Moonlight transport. Its Android proxy service is TCP-only, while GameStream media needs UDP as well as TCP.

Instead, create a dedicated Android Gradle module in Ani Moonlight that incorporates the required components from the upstream Tailscale Android client source: its VPNService lifecycle, native Tailscale/WireGuard engine, control-plane registration, NAT traversal, DERP handling and state persistence. The upstream Android client repository is BSD-3-Clause; preserve its copyright/license notices and review every upstream dependency before bundling.

This is a custom integration, not a drop-in official Android SDK. Keep the transport module isolated behind an AniRemoteTransport interface so that Moonlight's decoder, input, audio and GameStream code are not rewritten. Use a full IP tunnel capable of TCP and UDP; a TCP-only localhost proxy is not acceptable.

Android VPN behaviour:
- Call `VpnService.prepare()` and show the system consent activity only when Android says permission is not already granted.
- Route only the assigned Tailscale peer traffic; do not route all customer internet traffic through Ani Games Ready.
- Persist Tailscale node state using app-private storage and Android Keystore protection where applicable.
- Reconnect on network changes and app foreground/resume without asking the renter to type network configuration again.
- If another VPN app currently owns Android's single VPN slot, explain that Android cannot run both simultaneously and let the renter decide. Never silently disable another VPN.

## 4. Tailscale identity and credential provisioning

The Tailscale admin identity and OAuth credentials are server-side secrets. They must never be bundled in the APK, returned to the client, written to app logs, or committed to Git.

Proposed node identities:
- Windows host PCs: `tag:ani-host`.
- Renter Android nodes: `tag:ani-client`.
- Optional backend/control node: `tag:ani-control` only if later needed.

The DB Android server issues a one-use, pre-authorized Tailscale auth key only after it validates the rental ID, activation credential, Remote Play permission and rate limit. The key is tag-scoped so the node assumes the tag identity, not the operator's email identity. The app consumes the key once and stores its own node state; the one-use auth key is erased immediately after registration.

Use an OAuth client with the minimum API scope needed to create short-lived, one-use auth keys and manage the intended tagged nodes. Keep the OAuth client secret only in a protected environment variable on the existing Redmi/Termux API server. If that API process is unavailable, new enrollments/session authorization may wait; do not expose the Tailscale secret to the client.

Use pseudonymous device hostnames (random installation ID or server-generated opaque ID), not the renter's name, phone number, email, or rental username. Admin can map the opaque ID to the rental account in the DB Android database.

## 5. Per-session least-privilege network policy

Do **not** add a broad rule such as `tag:ani-client -> tag:ani-host:* `. That would let every enrolled client attempt to reach every rental PC.

The Tailscale policy must use explicit deny-by-default grants. When a session is approved, the backend obtains the verified Tailscale IP of that client and the selected host, then adds a narrowly scoped grant:

- source: that one client Tailscale IPv4/IPv6 address (/32 or /128);
- destination: that one approved host Tailscale address (/32 or /128);
- ports/protocols: only the verified Moonlight/Sunshine GameStream TCP and UDP port set;
- no access to Sunshine Web UI/API port 47990;
- no access to other client devices, other PCs, or the rest of the tailnet.

Tailscale grants support individual IP addresses as policy selectors. The backend must maintain the complete policy document, update it with optimistic concurrency/serialization, validate it, and avoid overwriting manual owner rules. If policy update or host firewall update fails, the session must not start.

The Windows Host Agent must independently enforce the same session boundary: dynamically allow the active client Tailscale IP only for the required GameStream TCP/UDP ports, and remove that rule on session end, timeout, permission revocation or Host Agent recovery. Do not rely solely on Android UI filtering or on the network policy.

Keep Sunshine Web UI bound to localhost/PC-only administration. Do not expose port 47990 to customers or the public internet. Preserve the current request-scoped Sunshine pairing challenge and start the usage timer only after Moonlight reports an actual connected stream.

At session end:
1. settle rental usage once in the existing Remote Play DB transaction;
2. send Host Agent stop and remove the client-specific Windows firewall allowance;
3. remove the client-to-host Tailscale grant;
4. apply the existing cooldown and retain audit evidence.

## 6. Tailscale plan and scale limits

The current Tailscale Personal plan lists up to 50 tagged resources included; extra tagged resources are listed at $1/month each. Because this design tags clients and hosts to avoid sharing the operator's user identity with renters, the 50-resource limit must be monitored. Do not assume unlimited client devices are free under the tagged-resource model. Confirm current billing semantics before production scale-up.

For the initial diagnostic test, enroll only one authorized Windows host and one test Android client. Do not bulk-enroll renters until pricing and cleanup policy are settled.

## 7. Cloudflare Mesh coexistence and IP-range conflict

The existing Cloudflare Mesh address range used by the previous branch is `100.96.0.0/12`. Tailscale's default node address pool is `100.64.0.0/10`, which contains that Cloudflare range. Running both overlays without planning may cause route/IP conflicts on the Windows host.

Do not change the current DB Android gateway or quick-connection settings. Before enabling Tailscale on the host PC, evaluate Tailscale's IP-pool feature in the tailnet policy and select a non-overlapping subrange outside Cloudflare's `100.96.0.0/12` (candidate for staging: `100.80.0.0/16`, subject to availability and ISP conflict checks). This IP-pool feature is documented as beta; validate actual assignment on the test node. If it cannot be made reliable, keep Cloudflare only for the existing admin/control workflow and migrate the media host in a controlled isolated test—never change the live gateway as a workaround.

## 8. Backend work required (additive, staging first)

Additive Remote Play backend work only; no edits to existing DB selection, API bind address, port 8787/8788, HTTPS gateway, Billing/Reseller APIs, Blynk/WoL relay or production database.

Required server-side operations:
- provision and rotate Tailscale OAuth credentials without returning them to devices;
- issue one-use client/host auth keys after existing Ani permission checks;
- map opaque device IDs to verified Tailscale node IDs and private IPs;
- serialize Tailscale policy updates and preserve the complete policy state;
- authorize one client-to-one-PC route on session start;
- revoke that route on session end, host/client offline, rental expiry or Admin revocation;
- reconcile stale tagged nodes and stale grants after API/Host Agent restarts;
- audit every enrollment, policy update and revocation.

The current customer identity, rental balance, device inventory, shared Billing lock, 3-hour session cap and 10-minute cooldown remain DB Android-owned. Tailscale is only the secure network transport.

## 9. Proof-of-concept and release gates

Do not label the APK final until all checks pass:

1. Clean Android install includes Ani Moonlight only; no Tailscale/Cloudflare/WireGuard client app is installed separately.
2. First launch shows exactly the Android system VPN-consent flow; subsequent launches reconnect without asking for server/IP/profile information.
3. One-time client activation creates a tagged node without exposing the operator email or Tailscale auth key to the device UI/logs.
4. One Windows PC enrolls as `tag:ani-host`, reports its stable tailnet IP and is visible as ready only when Sunshine and the transport are healthy.
5. Exact per-session grant lets the client reach one assigned PC only; another PC and another client remain unreachable.
6. Sunshine TCP control and UDP video/audio/input work over the overlay, with no public Sunshine port forwarding.
7. Test direct P2P on same LAN, different home networks, and Android cellular data; separately force/observe DERP fallback and record latency, packet loss, bitrate and resolution.
8. Test reconnect after Wi-Fi/mobile handoff, app background/foreground, screen lock and host reboot.
9. Admin revocation, renter logout, balance expiry, stop, heartbeat timeout and session end remove network permission and the host firewall allowance.
10. Confirm there is no Supabase code/config, no change to the existing DB quick settings/gateway, and no regression in PC Billing/Admin/Blynk/WoL.
11. Verify Tailscale IP pool does not overlap Cloudflare Mesh or the tested ISP networks; otherwise revise coexistence before expanding.
12. Produce reproducible APK/Host Agent artifacts with exact source commits, CI run IDs, SHA-256 checksums and a regression report.

## 10. Explicitly not claimed

This decision document is not an implemented tunnel, deployed Tailscale tailnet, installed Host Agent, production database migration, working stream or final APK. Those require source implementation, test-tailnet credentials, CI builds and a real Android + Windows + cellular end-to-end test.
