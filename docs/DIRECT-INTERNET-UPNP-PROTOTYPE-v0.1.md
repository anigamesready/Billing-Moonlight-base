# Ani Remote Play — Direct Internet / UPnP Prototype v0.1

Status: **simplest no-additional-client-app test path; not tested on the owner's router yet**.

## Product decision

During the current prototype stage, do not add a VPN dependency, managed media relay, TURN tunnel, or a second Android client application. Keep the architecture as simple as possible:

- Redmi/Termux DB Android remains temporary control plane only: rental login, activation code, PC availability, Admin permission, session lease, balance, cooldown, pairing authorization and audit.
- Windows rental PC runs Sunshine and Ani Host Agent.
- Android renter installs only Ani Moonlight.
- Media goes directly between the selected Android client and the selected Windows host over the public Internet using Sunshine's native GameStream transport.
- The Android app receives the approved host endpoint from the existing control API and does not ask the renter to type an IP, port or Sunshine PIN.
- Pairing is done by the existing server-authorized Host Agent flow, not by asking the renter to operate Sunshine Web UI.

This is a prototype strategy for testing one host first. It does not guarantee that every ISP/router allows direct reachability.

## 1. Direct connectivity options

Try in this order:

1. Native Sunshine UPnP, which asks a compatible router to open the streaming ports.
2. Moonlight Internet Hosting Tool on the Windows host for network diagnosis and supported UPnP/NAT-PMP/PCP mapping. The tool documentation says it supports IPv4, IPv6 and dual stack, multiple NATs, and CGNAT when the ISP supports PCP. Its documented limitation is one GameStream PC per public IP.
3. Native public IPv6, when the ISP and router provide globally reachable IPv6 and the router firewall can be configured safely.

No media traffic is sent through the Redmi or ordinary HTTP Worker. No public VPS is required for a direct path.

## 2. Important limitations

- UPnP/NAT-PMP/PCP depends on router and ISP support.
- A CGNAT provider that does not provide PCP/usable public IPv6 may prevent unsolicited inbound GameStream connections.
- The official Moonlight Internet Hosting Tool documents a one-GameStream-PC-per-public-IP limit. Start with one Windows host. Multi-PC port-family mapping must be engineered and tested separately; do not assume the tool automatically supports multiple hosts behind the same public address.
- Some mobile carriers, guest Wi-Fi and corporate networks block UDP. Direct streaming may fail from those networks.
- There is no automatic relay fallback in this prototype. If a direct route fails, report the diagnosed reason; do not silently route media through the DB API/Worker or Redmi.

## 3. Security conditions

- Never expose Sunshine Web UI/API port 47990 to the public Internet.
- Keep Sunshine Web UI origin policy restricted to local PC administration (origin_web_ui_allowed = pc) where supported and retain local-only API access.
- Only the intended GameStream ports may be forwarded; verify the actual port family from the installed Sunshine version/config rather than copying an unverified list.
- Windows Firewall must allow only the needed GameStream ports and must keep the Web UI/API protected.
- Keep Sunshine pairing challenge and client certificate verification. A rental ID or activation code alone is not a substitute for Sunshine's cryptographic pairing.
- Disable UPnP automatically on session end only if doing so does not disrupt other software; router mapping lifecycle must be verified.
- Do not change bind_address to localhost: that would also break remote streaming.
- Do not expose router administration, RDP or other LAN devices.
- Test this path on one isolated host before enabling any rental customer.

## 4. Minimal first test

1. Choose exactly one Windows test host.
2. Install and configure Sunshine; keep the Web UI local-only.
3. Enable Sunshine UPnP only for the test host, or run the official Moonlight Internet Streaming Tester to diagnose the router path.
4. Verify the host has a globally reachable IPv4 address, a PCP-capable mapping path, or a usable global IPv6 address. Compare the router WAN address with the public address; do not share account credentials or router admin passwords.
5. Test from a different network, preferably the Android phone on mobile data with Wi-Fi disabled.
6. Test the actual sequence: Ani activation login -> Admin permission -> host session grant -> Moonlight pairing -> video/audio/controller -> stop -> settlement and cooldown.
7. Record the result as DIRECT_OK or BLOCKED_CGNAT / BLOCKED_UDP / BLOCKED_ROUTER / BLOCKED_FIREWALL.

No changes to live DB, API port, DB selection, Quick DB menu, stable gateway URL, Billing system, Admin login, Blynk or WoL are part of this test.

## 5. Decision if direct route fails

Do not build a complicated relay before the first direct-route test.

If direct connectivity fails:
- First determine whether the ISP provides public IPv6 or PCP.
- If the host remains behind untraversable CGNAT, a fully free and reliable public relay/overlay with commercial rental use and a single embedded client app is not established by this prototype.
- Free hosted mesh offerings are not automatically suitable: Tailscale Personal and ZeroTier Personal are non-commercial; ZeroTier libzt SDK needs a commercial license for certain closed-source embedded products; NetBird's hosted terms prohibit offering/ leasing/ commercially exploiting the service to third parties, and its Android app source is GPL-3. Do not ship a paid rental service on those free plans or embed their code without a separate license review.
- Do not treat a personal/free VPN plan as a production rental solution. It may only be considered for a bounded internal proof-of-concept if its terms permit that specific evaluation.
- At that point choose between (a) a commercially authorized overlay/OEM SDK, (b) a managed TURN/relay budget, or (c) a separate customer-installed VPN app. Be explicit about cost and user friction before proceeding.

## 6. Test and release gates

A successful UPnP status check alone is not a successful stream. Release remains blocked until:
- actual video and audio work from an external network;
- controller input and reconnect are tested;
- Sunshine Web UI is not publicly accessible;
- one rental cannot access a different host;
- server lease/cooldown/settlement and Billing conflict locks pass;
- public endpoint rotation and Android network changes are handled;
- regression checks show the existing DB, Admin login, Billing, Blynk and WoL are untouched.

This source document is a test plan only. No router, Sunshine, DB, gateway, VPN account or live PC has been changed.
