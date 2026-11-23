# Ani Moonlight — Single-App Client and Media Relay Architecture

Status: architecture decision / implementation not complete.

## Product decision

A rental customer installs **one Android app only**: the branded Ani Moonlight APK, built from the pinned Moonlight Android engine. Do not require customers to install Cloudflare One, WARP, WireGuard, Tailscale, or another networking app, and do not ask them to type IP addresses, ports, organization names, or pairing PINs.

The APK owns the customer flow:
1. First login with rental ID and a one-time activation credential issued by Admin.
2. The app creates a stable client installation identity and persists its Moonlight client certificate/private key using Android Keystore-backed storage.
3. On first use with an authorized PC, the app and Host Agent perform one server-authorized Sunshine pairing. The user does not manually enter a PIN.
4. Later sessions reuse the already-paired client identity. Pair again only after a deliberate revoke, app-data reset/reinstall, certificate replacement, or a PC-side re-pair.
5. Future launches refresh the customer session silently where possible; no repeated activation code unless the account/device was revoked.

Android may display its standard one-time VPN permission sheet because the app embeds a private tunnel. This is a system consent, not a second installed app. Only the selected Host /32 route is put through the tunnel; ordinary phone internet traffic stays on the normal network.

## Network decision

The current Cloudflare Mesh route is useful for owner/admin device management, but it is not the customer transport. Keep current Mesh registration as-is for the owner Redmi and Windows management device; do not enroll renters into that Cloudflare organization.

Use a separate Ani media plane:
- Each Windows PC has Sunshine and the Ani Host Agent.
- The Host Agent establishes an outbound authenticated tunnel to an Ani public media relay. No inbound router port-forwarding is required on a rental site.
- Ani Moonlight embeds a WireGuard-compatible tunnel client inside the APK (Android VpnService + a maintained userspace WireGuard backend). It requests a short-lived, device-bound peer grant from the existing DB Android control API and connects to the relay.
- The relay assigns a private overlay /32 to the client and a stable private address to each Host. Client tunnel routes only the chosen Host /32.
- Relay firewall policy is default-deny and permits only the authenticated client-to-selected-Host GameStream ports for the active server lease. Deny lateral access to other PCs, LANs, relay management ports, and the public internet.
- The relay forwards raw GameStream TCP and UDP packets. The DB Android API / Worker remains control-plane JSON only and must never proxy game video/audio.
- Prefer direct peer media only when verified reachable; otherwise use the relay path. Relay is the reliable fallback for CGNAT and restrictive NAT. Do not claim internet-wide support until both paths and mobile-carrier tests pass.

The initial implementation should use the relay as the deterministic fallback. A later direct-path optimization may use authenticated ICE/STUN/UDP hole punching, with relay fallback; it must not delay the first working release.

## Media protocol and ports

The relay must carry all GameStream protocol traffic, not just the control API. Sunshine default port family includes TCP 47984, 47989, 48010 and UDP 47998, 47999, 48000, 48002, 48010. Sunshine offsets related ports when its base port changes, and documentation warns that custom ports may not work with every Moonlight client. Therefore, do not rely on custom external port remapping until verified against the exact pinned Moonlight v12.2 engine. First prototype a routed virtual IP where default GameStream port numbers remain unchanged.

Never expose Sunshine Web UI / API (including TCP 47990) on the public internet. Keep its Web UI and pairing API restricted to loopback. The Host Agent is the only component allowed to call local Sunshine administrative endpoints.

## Pairing and identity

Replace the current per-session pairing challenge as the normal path with a persistent client-to-host trust record:
- stable client_install_id, public certificate fingerprint, Sunshine host/device ID, organization ID, paired_at, last_used_at, revoked_at;
- one pairing approval for a client-install / Host pair;
- client private key stays on the Android device and must never be uploaded to the DB or relay;
- Admin can revoke a client or clear its pairing record;
- reinstallation or lost client key requires a new activation/re-pair;
- initial authorization still requires both Admin rental permission and Host permission;
- a new session must re-check rental balance, expiry, concurrent Billing lock, Host availability, cooldown, and per-session hard deadline regardless of prior pairing.

The Host Agent must approve only a fresh server-authorized challenge for an exact client identity and exact Host. It must fail closed for stale, duplicate, mismatched, or revoked challenges.

## Session lifecycle

Keep existing server invariants:
- one active session per rental and one active session per Host;
- shared lock with PC Billing;
- maximum 10,800 seconds per session and additionally bounded by remaining rental seconds / expiry;
- 600-second cooldown;
- settle exactly once on explicit stop, deadline, disconnect, or missed client lease;
- start billable timer only after Moonlight reports an actual connected stream;
- stop tunnel ACL and revoke session peer grant as soon as the session is settled;
- Android countdown is informational; DB lease + Host watchdog remain authoritative.

## Security controls

- Use short-lived session credentials; do not embed a global WireGuard private key or relay secret in the APK.
- Generate client tunnel keypair locally and register only its public key.
- Protect Host Agent relay private key with Windows DPAPI LocalMachine.
- Rotate/revoke peer credentials on session end, client revoke, host revoke, or enrollment reset.
- Authenticate Host Agent to relay via a server-minted short-lived credential; relay management API is not public.
- Apply per-peer rate limits, anti-replay, session TTL, bandwidth caps, and audit events.
- Firewall relay management endpoints separately from media forwarding.
- No customer can choose arbitrary destination IP, port, or peer.
- Preserve Sunshine Web UI loopback restriction and existing Host Agent backup/rollback behavior.

## Rollout / release gates

1. Implement isolated relay service and provisioning API; no current DB quick settings, gateway URL, API port, or stable Admin app settings may be changed.
2. Test host outbound tunnel from a normal residential connection and client tunnel from Wi-Fi, cellular, and carrier CGNAT.
3. Verify TCP and every required UDP GameStream port through the virtual route using the exact pinned Moonlight v12.2 code.
4. Prove one client can reach only the authorized Host /32 and cannot reach another Host, PC LAN, or relay management.
5. Verify one-time pairing persists across sessions and survives normal app restarts; test revoke/reinstall/key loss.
6. Verify the stream-connected callback is still the only event that starts billable time.
7. Run Billing conflict, balance settlement, 3-hour deadline, cooldown, reconnect, mobile process death, and Host restart tests.
8. Only then remove the Cloudflare One requirement from customer UI and produce a customer APK.

A CI build, a successful API login, or a Cloudflare Mesh Connected badge is not an end-to-end streaming test.
