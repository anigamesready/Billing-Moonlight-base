# Ani Games Ready Windows Host Agent

The Host Agent is a Windows service that connects the existing DB Android control API to a locally installed Sunshine host. It is separate from the PC Billing service.

## Requirements

- Windows 10/11 x64.
- Sunshine x64 installed and paired/configured locally.
- DB Android existing stable HTTPS gateway URL, exactly the same endpoint used by Ani Games Ready apps.
- Admin-issued, one-use Host Agent enrollment code for this PC.
- WireGuard media route already configured so the customer's phone can reach the host's GameStream TCP **and UDP** ports.
- Sunshine Web UI/API bound to loopback only. The Agent connects only to `https://127.0.0.1:47990`.

## Install

1. Extract the complete release ZIP to a local folder.
2. Open PowerShell as Administrator in that folder.
3. Run `./install-host-agent.ps1`.
4. Enter the same stable HTTPS DB Android gateway URL, the Admin enrollment code, the PC's WireGuard address and the local Sunshine credentials.
5. The script generates a unique 256-bit Host Agent token and protects both it and the enrollment credential with Windows DPAPI (LocalMachine).
6. The Windows service enrolls the Host Agent with DB Android, sends host/Sunshine heartbeats, fetches host-scoped commands and handles server-issued Sunshine pairing challenges.

## Security

- No Supabase URL, SDK or key is used.
- No PostgreSQL password, service secret, Admin token or PC Billing token is stored by this Agent.
- Host Agent token is distinct from PC Billing device credentials and is stored only as DPAPI ciphertext on this PC.
- Sunshine administration is never exposed through the public API gateway.
- The DB Android API handles control-plane JSON only; the Agent does not proxy video/audio data.

## Important current limitation

This Agent reports a preconfigured WireGuard route. It does **not** create WireGuard tunnels, perform ICE/STUN/TURN NAT traversal or relay GameStream UDP. A direct-P2P/relay media path must be provisioned and verified separately. Do not expose Sunshine ports or the Sunshine Web UI directly to the public internet as a workaround.

The DB Android functions listed in `docs/DB-ANDROID-REMOTE-PLAY-RPC-CONTRACT-v2.md` must exist in the backend before enrollment or sessions can work. This source does not deploy migrations or modify the live Redmi database.
