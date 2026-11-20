# Ani Games Ready Windows Host Agent

The Host Agent is a Windows service that connects the existing DB Android control API to a locally installed Sunshine host. It is separate from the PC Billing service.

## Requirements

- Windows 10/11 x64.
- Sunshine x64 installed and paired/configured locally. Use v2026.906.222525 or newer; older releases had a high-severity flaw that could approve the wrong pending pairing when multiple clients attempted pairing.
- DB Android existing stable HTTPS gateway URL, exactly the same endpoint used by Ani Games Ready apps.
- Admin-issued, one-use Host Agent enrollment code for this PC.
- A private media overlay shared by the Windows host and Android client. Preferred: Cloudflare One Client (Cloudflare Mesh) signed in to the same Zero Trust organization on both devices. Existing WireGuard remains supported.
- Sunshine Web UI/API bound to loopback only. The Agent connects only to `https://127.0.0.1:47990`.

## Install

1. Extract the complete release ZIP to a local folder.
2. Open PowerShell as Administrator in that folder.
3. Run `./install-host-agent.ps1`.
4. Enter the same stable HTTPS DB Android gateway URL, the one-use Admin enrollment code and the local Sunshine credentials. Choose **M** for Cloudflare Mesh (recommended) or **W** for a preconfigured WireGuard route.
5. DB Android issues a unique 256-bit Host Agent token once during enrollment. The Windows service protects it with Windows DPAPI (LocalMachine); the one-use enrollment code is erased from the local config after enrollment.
6. With Cloudflare Mesh selected, the service discovers this PC's `100.96.0.0/12` address from the Cloudflare One adapter on every heartbeat. It will not report the PC ready until both Sunshine and the media route are ready. With WireGuard selected, the configured private overlay address is used.
7. On the Android phone, install Cloudflare One Client, join the same Zero Trust organization, allow its VPN profile, and connect it before starting a stream. Both endpoints must be enrolled in the same organization and use Traffic and DNS mode.

## Cloudflare Mesh account setup

Cloudflare Mesh requires an enrolled device rule, device-to-device traffic enabled, unique device IPs, Mesh range `100.96.0.0/12` routed through both device profiles, and TCP/UDP Gateway proxy enabled. Windows Firewall rules created by the installer allow only the standard Moonlight GameStream TCP/UDP ports from `100.96.0.0/12`; port `47990` (Sunshine Web UI/API) is not opened to Mesh peers. Keep Sunshine UPnP disabled and do not forward Sunshine ports on the router.

The Cloudflare One Client Android VPN permission is a one-time user approval on the phone; it cannot be silently enabled by this APK. Official setup: https://developers.cloudflare.com/mesh/get-started/ and https://developers.cloudflare.com/mesh/guides/connect-client-devices/.

## Security

- No Supabase URL, SDK or key is used.
- No PostgreSQL password, service secret, Admin token or PC Billing token is stored by this Agent.
- Host Agent token is distinct from PC Billing device credentials and is stored only as DPAPI ciphertext on this PC.
- Sunshine administration is never exposed through the public API gateway.
- The DB Android API handles control-plane JSON only; the Agent does not proxy video/audio data.

## Important current limitation

Cloudflare Mesh setup is not automatic at the account level. Admin must enable Mesh/device-to-device settings and enroll the Windows and Android devices once. The current Cloudflare account inspection found no enrolled devices and Access is not yet enabled; therefore a real media-path test is still pending. The Agent does **not** embed the Cloudflare One VPN client or silently grant Android VPN permission. Do not expose Sunshine ports or the Sunshine Web UI directly to the public internet as a workaround.

The DB Android functions listed in `docs/DB-ANDROID-REMOTE-PLAY-RPC-CONTRACT-v2.md` must exist in the backend before enrollment or sessions can work. This source does not deploy migrations or modify the live Redmi database.


Sunshine pairing version note: the Host Agent is designed for request-scoped pairing approval with the random pairing_id. See official Sunshine API documentation at https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2api.html and the security advisory GHSA-36ff-frg7-492f at https://github.com/LizardByte/Sunshine/security/advisories/GHSA-36ff-frg7-492f.
