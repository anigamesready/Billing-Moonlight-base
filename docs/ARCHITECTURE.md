# Ani Games Ready Remote Play — Architecture

## Purpose

An isolated Moonlight/Sunshine remote-play product that uses the existing Billing Ani Games Ready database for shared rental identity and PC inventory. Remote-play-only state is kept in the private `remote_play` schema in the same Supabase project.

## Upstream source baselines

- Moonlight Android: upstream tag `v12.2` (Apache-2.0).
- Sunshine: use the latest stable release supported by the supplied Windows installer; the current verified stable line is `v2026.914.233613`. Do not build from a pre-release without an explicit test reason.
- Sunshine's authenticated local REST API supports `GET /api/pin` (list pending PIN approvals), `POST /api/pin` (approve one explicit pairing_id with a four-digit PIN and friendly name), `DELETE /api/pin` (cancel), `GET /api/clients/list`, and client unpair/update endpoints. The Host Agent must use this API on loopback only.

## Data ownership

Shared, unchanged source data:
- `public.rental_accounts`: user code, remaining seconds, expiry and rental status.
- `public.devices`: Windows PC inventory, device name and current online/heartbeat state.
- `public.device_tokens`: existing Windows host token identity.
- `public.organizations`: tenant boundary.

Remote Play-only data:
- `remote_play.config`: per-org maximum session, cooldown and host liveness rules.
- `remote_play.client_devices`: one mobile installation per rental account; only token hash is stored.
- `remote_play.hosts`: Remote Play host Agent registration and VPN/P2P address.
- `remote_play.sessions`: exclusive Remote Play session lease and server deadlines.

No duplicate rental accounts, PC inventory, payment balance or PC Billing session ledger is created.

## Session rules

- One rental account can have at most one live Remote Play session.
- One host PC can have at most one live Remote Play session.
- Existing PC Billing active/paused sessions block Remote Play starts.
- Existing Remote Play sessions block conflicting PC Billing session inserts.
- Maximum wall-clock session length is 10,800 seconds (3 hours).
- The server charges elapsed whole seconds against the shared rental balance on session close.
- Reaching the 3-hour hard cap sets a 600-second cooldown (configurable from 300 to 600 seconds). The cooldown applies to that rental account/device, not to the PC; another eligible user can use the PC immediately.
- No time or expired/disabled account can still open its dashboard and Topup screen, but cannot start a stream.
- Backend is authoritative. Client timers are display-only.
- Host Agent watchdog finalizes sessions after the hard cap, balance deadline, or missing host heartbeat.

## Network and security

- Supabase is control plane only; never carry game video/audio through Supabase.
- Android sends its own device-bound client token; the database stores only SHA-256(token).
- Host Agent uses the existing Windows device token and calls the local Sunshine API via loopback.
- Never expose Sunshine Web UI/REST administration to the public Internet and never put its password in the Android APK.
- Attempt a direct peer-to-peer route after authorization. WireGuard may be used as a managed rendezvous/control tunnel; it is not itself a guarantee of NAT traversal. A relay is the fallback for CGNAT/unreachable peers.
- Do not set a fake/default host address. A host is selectable only after it is registered and has both a recent DB heartbeat and Host Agent heartbeat.
- First device binding is first-claim for the User ID. Device replacement requires an Admin reset workflow; this must be available before public release.

## Known implementation state

- Applied database migration: `20261119092733 / remote_play_integration_v1`.
- Deployed Edge Function: `moonlight-api`, version 1.
- The upstream Android and Sunshine sources have been located and key flows audited.
- Android UI/build integration, Windows Host Agent deployment, direct P2P/WireGuard configuration, automatic pairing handshake, QRIS asset hosting and end-to-end tests are still build tasks. Do not call the app release-ready until these are complete.
