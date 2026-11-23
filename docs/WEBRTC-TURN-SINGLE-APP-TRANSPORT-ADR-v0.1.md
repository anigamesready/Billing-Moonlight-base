# Ani Moonlight — Single-App WebRTC + Managed TURN Transport v0.1

Status: **preferred no-self-hosted-VPS prototype direction; not implemented or release-ready**.

This architecture is selected for a technical proof of concept because the product owner has only a Redmi/Termux DB Android server and Windows rental PCs, no public VPS, and needs renters to install one app only. It supersedes free-Tailscale and free-ZeroTier assumptions for the initial Ani Games Ready commercial prototype. Do not claim a working stream until the end-to-end gates at the end of this document pass.

## 1. User experience

Renter installs only the Ani Moonlight APK.

First use:
1. Enter rental ID plus the one-time Remote Play activation code issued by Admin.
2. The backend verifies rental status, balance, expiry, and Remote Play permission.
3. The client creates an opaque installation identity. No Cloudflare/Tailscale account, email, network profile, IP, port, or Sunshine PIN is shown or entered.
4. The app stores its revocable Ani device credential securely. Later launches reuse it and refresh the session silently while valid.
5. Renter selects an eligible PC and presses Connect.

The application uses its own app-scoped WebRTC transport. It does not install or turn on Android's system VPN, and does not require Cloudflare One, Tailscale, ZeroTier or WireGuard as a second app. There is no Android VPN consent dialog for this app-scoped design.

The Windows rental PC still needs the Ani Host Agent service and Sunshine. Admin installs/enrolls the host once. This is an administrative PC prerequisite, not another renter phone app.

## 2. No-VPS network architecture

The existing DB Android HTTPS API remains the source of truth for rental authentication, inventory, Admin permissions, session locks, balance settlement, hard deadline, cooldown and audit.

The existing API/Worker is used for authenticated signaling/control metadata only: session ID, SDP offer/answer, ICE candidates and stop/revoke notices. It never carries game video/audio payloads.

WebRTC ICE uses STUN and attempts direct peer-to-peer connectivity first. If the path cannot be established because of NAT/firewall restrictions, Cloudflare Realtime TURN supplies a managed relay on Cloudflare's global anycast network. No Ani-owned VPS or relay PC is required.

Cloudflare's managed TURN is a connectivity relay, not a VPN. It only relays packets for this WebRTC connection. The service supports a peer-to-peer application that generates short-lived TURN credentials.

Do not use Cloudflare Tunnel, a normal HTTP Worker proxy, or the Redmi phone as a media relay.

## 3. Moonlight protocol adaptation — critical implementation work

Cloudflare TURN does not implement TURN TCP relaying (RFC 6062). Therefore do not point Moonlight's raw TCP/UDP sockets at TURN as if it were a full VPN.

Instead, create a custom app-scoped, encrypted packet/stream transport over WebRTC DataChannels:

- One reliable, ordered channel for connection control and tunnel negotiation.
- Separate reliable/ordered DataChannel streams for each required Sunshine TCP connection, so one blocked stream does not head-of-line block unrelated TCP control connections.
- Unordered, low-latency DataChannel messages for GameStream UDP datagrams, with sequence/flow identifiers, bounded fragmentation/reassembly and a drop policy that preserves real-time behaviour.
- The Windows Host Agent maps authorized tunnel streams/datagrams to the intended local Sunshine listener ports and returns responses through the matching DataChannel flow.
- The Android Moonlight integration routes only the required GameStream TCP/UDP endpoints through this adapter. Other Android app traffic is untouched.
- Keep packet framing bounded, validate every flow ID/port against a server-issued per-session allowlist, rate-limit setup, and reject malformed/oversized datagrams.
- Use WebRTC's DTLS/SCTP protection end-to-end. TURN sees encrypted WebRTC packets and relays them; it must not terminate the game/media encryption.

The exact Moonlight TCP/UDP port set must be taken from the pinned Moonlight/Sunshine version and existing Host Agent code, not guessed or widened. Sunshine administration port 47990 must remain local-only and must never be reachable through the renter tunnel.

## 4. Server and credential boundary

Cloudflare Realtime TURN has a long-lived TURN key. Keep that key and its API token exclusively in a server-side secret store. Do not place either in APK, Android resources, source code, native libraries, logs, customer responses, Host Agent config, Windows registry, or a public GitHub repository/build artifact.

After Ani DB Android validates the rental and creates a session lease, a separate, isolated TURN credential broker can call Cloudflare's credential-generation API and return short-lived ICE server details to the authenticated endpoints. The credential API supports a TTL; Cloudflare documents a maximum expiry of 48 hours and recommends refreshing credentials for longer sessions.

Preferred separation:
- Keep the current DB Android API configuration and gateway untouched.
- Add a separate Worker called ani-remoteplay-turn-broker with its own secret bindings and narrow service authentication, or use the existing API server's secret environment only if the current deployment model can protect it reliably.
- The broker accepts only an authenticated, short-lived, server-signed session assertion tied to one client ID, one PC ID and one lease deadline. It must not accept arbitrary caller-supplied rental IDs as proof.
- Broker generates per-session temporary TURN credentials and returns them only to the two endpoints in that exact session.
- Renter-facing identity is a random opaque device ID, not the renter's name, phone, email or Cloudflare account identity. Cloudflare credentials are technical relay credentials, not an account login.
- Revoke relay credentials on session end or Admin revocation where supported; otherwise rely on short TTL plus immediate WebRTC close and host-side access teardown.

No TURN key is created or stored by this source-only ADR. No change has been made to the live Cloudflare account or current DB gateway.

## 5. Pairing, authorization and per-PC isolation

1. Admin enables Remote Play for the selected PC and rental ID in Admin > Pairing Remote Play.
2. Host is enrolled once using a one-time code. Host Agent keeps its host token protected with Windows DPAPI.
3. Renter's first login consumes a one-time activation credential and binds an opaque client identity. It does not create a Cloudflare user or expose the owner's email.
4. At Connect, the existing DB issues one exclusive session lease for that rental + PC, enforces no conflicting Billing session, remaining balance, expiry, max 3 hours and 10-minute cooldown.
5. DB issues a session-scoped WebRTC authorization and one-time signaling room/nonce. Only the assigned client and host can exchange SDP/ICE candidates for that session.
6. Host Agent validates the signed lease, opens the WebRTC peer connection, and maps traffic only to the approved Sunshine GameStream TCP/UDP ports.
7. Start rental time only after Moonlight confirms the Sunshine stream is actually connected.
8. On End, timeout, lost heartbeat, expiry or Admin revoke: stop Sunshine, close DataChannels, revoke signaling authorization, settle rental seconds once, and apply the existing cooldown.

A different renter must not be able to connect to the same PC through another session, and one renter must not be able to reach another PC or local service. Add defense in depth: DB session lock, one-use signaling nonce, host-side session validation, strict port map and dynamic Windows firewall/session rules as necessary.

## 6. Cloudflare Realtime TURN capability and cost caveats

Verified Cloudflare documentation (reviewed 2026-11-23):
- Realtime TURN can be used with a custom peer-to-peer WebRTC application; it is not limited to Cloudflare SFU.
- TURN is only needed when ICE cannot establish a direct peer path. Direct peer-to-peer avoids TURN relay egress charges.
- Standalone Realtime TURN pricing documented as USD $0.05 per real-time GB outbound from Cloudflare to TURN clients, with a combined first 1,000 GB monthly free allowance for Realtime SFU/TURN.
- Per-allocation documented limits include 5–10 kpps and 50–100 Mbps inbound/outbound. Hitting limits causes packet drops.
- TURN credentials are generated server-side from a long-lived TURN key and an API token; the long-lived key must not be included in the app. Short-lived credentials can expire up to 48 hours in the future.
- Cloudflare TURN supports TURN client-to-server UDP/TCP/TLS transports, but does not support the RFC 6062 TCP-relaying extension. This design encapsulates Moonlight TCP byte streams inside WebRTC DataChannels; it does not rely on TURN TCP relaying.

The first 1 TB free allowance is shared by TURN and Realtime SFU, not a guaranteed per-product allowance. Actual metered traffic and current Cloudflare billing terms must be checked in the account before production. If TURN is used for every high-bitrate session, costs can grow. The app should prefer direct ICE paths, show connection type (Direct or Relay) in diagnostics, enforce bitrate presets, and monitor egress.

Performance note: 50–100 Mbps and 5–10 kpps are service limits per TURN allocation, not a promise of 1080p/60 performance. Test actual Sunshine bitrate, frame rate, input latency, packet loss, Android mobile carrier behaviour and host upstream bandwidth before claiming a resolution/quality tier.

## 7. No-VPS deployment responsibilities

- Redmi/Termux remains the DB Android control backend, not media server. It needs to be reachable through the already configured stable gateway when the user logs in or creates a session. Do not change its current quick-connect settings or ports.
- Cloudflare Worker broker (new isolated service) stores TURN secrets and issues short TTL relay credentials. It does not process media.
- Cloudflare TURN supplies managed relay capacity only when direct ICE fails. It removes the need to run a public TURN server/VPS.
- Windows Host Agent is the peer endpoint and bridges authorized GameStream traffic to local Sunshine.
- Android Ani Moonlight is the other peer endpoint and includes the WebRTC transport code.
- If the Redmi is offline, no new rental login/session authorization can be created. Active media may continue only for the already issued session lease if the transport remains connected, but host-side hard deadline remains enforced locally from the signed lease. Production reliability requires separate backend uptime later; this prototype does not promise 24/7 availability.

## 8. Isolated implementation and regression order

Do not modify the master/stable branch, live DB, DB quick settings menu, gateway URL, DB selection, API port 8787/8788, Billing services, Admin login or Blynk/WoL relay.

Implementation order:
1. Build a standalone native WebRTC DataChannel echo/bandwidth test for Android and Windows Host Agent. Use Cloudflare STUN/TURN credentials from a test secret; do not involve Sunshine yet.
2. Verify direct connection over same Wi-Fi, different home networks, Android cellular, Wi-Fi-to-cellular handoff, and forced TURN relay.
3. Record throughput, bitrate, p95 RTT, packet loss, packets/s, CPU and memory. Test 5/10/15/20/30 Mbps streams and cap before any measured TURN service limit.
4. Implement TCP byte-stream multiplexing and UDP datagram transport in a separately tested module. Add loopback echo tests and packet-fragmentation/backpressure/reconnect tests.
5. Adapt Moonlight's networking boundaries to use the transport adapter without changing its decoder, controller, audio/video pipeline or pairing key lifecycle.
6. Connect the existing DB Android RPC session lease and authenticated signaling. New database functions remain additive and must be tested in staging before live migration.
7. Integrate Host Agent with Sunshine local API, request-scoped pairing, approved-port mapping and DPAPI protected credentials.
8. Add Admin pairing console controls and session/relay diagnostics. Admin build must retain the already-working DB login/gateway configuration; do not overwrite its quick DB/connection settings.
9. Run concurrency, revocation, expiry, 3-hour cap, 10-minute cooldown, Billing lock, disconnect, Android update and Host Agent recovery regression tests.
10. Only publish a diagnostic APK after steps 1–8 are actually implemented and CI passes. Release only after an actual Android + Windows + cellular/remote-network stream test passes.

## 9. Explicit limitations and no false claims

This ADR is an architecture decision and implementation plan. It is not a deployed TURN broker, working DataChannel tunnel, completed Host Agent bridge, DB migration, Admin APK, or working stream. No credentials have been generated, no Cloudflare account setting was changed, and no live database or gateway setting was touched.

Cloudflare TURN is a promising no-VPS fallback, but the custom TCP/UDP-to-DataChannel transport is a substantial engineering task. If it cannot sustain tested Moonlight traffic, stop and evaluate a properly licensed managed overlay or dedicated relay rather than shipping an unreliable VPN/stream path.
