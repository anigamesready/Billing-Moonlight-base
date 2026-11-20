# Ani Games Ready Remote Play — Architecture

Status: **design / integration work in progress**. This document is not evidence that any live server or APK has been changed.

## 1. Verified source baselines

| Component | Verified source |
|---|---|
| Android streaming engine | Moonlight Android v12.2, commit `b48494cb96bff23d8886c4775cc4f39a1075495d` |
| Latest DB Android API source reviewed | `anigamesready/PC-Billing`, branch `feature/db-android-stop-pause-policy-20261118`, commit `38a75ad85adf60a96a82cd1e7050568119f66a18` |
| DB Android relay implementation | Same DB Android branch; API gateway and WebSocket control relay code is in the repository |
| Latest PC Billing compatibility branch reviewed | `feature/pc-v12.4.9-stop-pause-20261118` (must remain separate from the Remote Play host agent) |
| Streaming host | Sunshine (upstream LizardByte project); installer supplied by the user is not Ani Host Agent source |
| Workspace | `anigamesready/Billing-Moonlight-base`, branch `feature/remote-play-db-android-v0.1` |

The latest DB Android source branch contains the existing local Node API, Billing/Admin/Reseller RPCs, staging setup, API gateways/relay, and migration `012_billing_stop_pause_policy.sql`. The Node API defaults to loopback port 8787; its separate staging package is on port 8788. The inspected API still does **not** define Remote Play customer authentication, host registration/permission, remote session lease, or cooldown functions.

This is repository evidence only. It does not prove the same commit, migration 012, staging tunnel, WebSocket agent, or Worker configuration is installed and online on the Redmi today. The older commit `d86e7ecfa82d7af29bb53d067a316984b1fef69b` was a previous relay-stage snapshot; the newer DB Android branch above is the preferred source reference for the next backend work.

## 2. System boundaries

```text
Ani Games Ready Android DB system (existing Redmi / Termux / PostgreSQL)
        |
        | existing configured HTTPS API base URL
        v
Node API (existing auth + RPC allowlist)
        |
        +---- existing public.rental_accounts (balance / expiry / status)
        +---- existing public.devices (PC inventory / org ownership)
        |
        +---- additive remote_play schema (host grants, client credentials,
        |     host status, leases, cooldown, audit)
                 |
                 +---- Android Remote Play app
                 |       - rental customer login
                 |       - PC list / balance / expiry
                 |       - session request / stop
                 |       - Moonlight streaming engine
                 |
                 +---- Windows Host Agent on each authorized PC
                         - enroll host
                         - heartbeat / availability
                         - verify session grant
                         - start/stop Sunshine application session
                         - end session / acknowledge commands

Media path (separate from the API):
Android Moonlight <---- secure routed network / direct peer path ----> Sunshine PC
```

### Must remain separate

- No Supabase project, SDK, key, Edge Function, DB1/DB2 route, or Supabase failover logic in this product.
- No second PostgreSQL instance or duplicate rental-account database for Remote Play.
- Keep the existing `public.rental_accounts`, `public.devices`, Billing PC protocol and Blynk/WoL relay intact.
- Remote Play-only records belong in an additive `remote_play` schema in the **same existing DB Android PostgreSQL database**, accessed only through approved Node API functions.
- Do not grant Android or Host Agent clients direct PostgreSQL access.

## 3. Control plane vs media plane

**Control plane** uses the same configured DB Android HTTPS API connection as the current Android apps. It carries customer login, balance/expiry, PC inventory, Admin permissions, host heartbeat, session creation, lease renewal, end-session and audit events. The app must obtain the same API base URL from the shared DB Android connection configuration; do not add a second hardcoded database endpoint.

The DB Android server has local main/stage addresses of `127.0.0.1:8787` and `127.0.0.1:8788`. Those loopback addresses are not usable by a customer's phone on the internet. Use the already configured, HTTPS-accessible DB Android API endpoint and verify it from a non-Redmi network before enabling customer login. The existing stage Worker / WebSocket relay is an API transport only; it is not a video-stream relay.

**Media plane** is the native Moonlight/Sunshine GameStream connection. Sunshine uses both TCP and UDP traffic; an HTTP API Worker cannot carry the streaming protocol by simply forwarding JSON routes. The final product therefore needs a separately secured routable network path, preferably direct peer-to-peer when available, with a tested relay/overlay fallback. Do not expose the Sunshine Web UI to the public internet and do not send game audio/video through the DB Android API Worker.

The network transport is not implemented or end-to-end verified yet. It is a release blocker.

## 4. Identity and data ownership

- Rental ownership and balance continue to come from the existing `public.rental_accounts` row, identified by its stable UUID and organization.
- Host inventory and organization ownership continue to come from `public.devices` where possible. A host must have an explicit Remote Play grant before appearing as available.
- A customer-facing rental code is an account identifier, not automatically a safe public password. The existing rental table has no dedicated customer password field. Provide a Remote Play activation credential/PIN issued by Admin or another approved proof before creating a revocable customer session. Store only a password hash; never store plaintext PINs.
- Keep host access tokens separate from customer tokens and PC Billing device tokens. Store token hashes in PostgreSQL; store the raw Host Agent token only in Windows DPAPI-protected local storage.
- Moonlight client private keys stay on the Android device. Never upload a private key to PostgreSQL or send it to the Host Agent.

## 5. Session invariants (server-enforced)

1. A rental account may have at most one active Remote Play session.
2. A rental account may not start Remote Play while it already has an active/paused PC Billing session, and PC Billing may not start a conflicting rental session while Remote Play is active.
3. A PC host may have at most one active Remote Play session.
4. Both pathways must take the same organization + normalized rental-code transaction lock before checking/creating a session. This is necessary to avoid a race between the existing PC Billing session-start RPC and the new Remote Play session-start RPC.
5. A session's hard cap is 10,800 seconds (3 hours), further bounded by the available rental balance and expiry.
6. On end, disconnect, expiry, or missed lease timeout, the server settles consumed seconds once and updates the shared rental balance atomically. Repeated stop/ack requests must be idempotent.
7. Start the 600-second (10-minute) cooldown when a session ends. Check it inside PostgreSQL before issuing another grant.
8. The Android countdown is informational only; the DB deadline and Host Agent stop command are authoritative.
9. Balance is not sufficient or account is expired/disabled: reject a session request, but continue serving the dashboard and top-up details.

## 6. Admin permissions

Admin owns the allow/deny controls:

- enable or disable Remote Play per PC host;
- allow or deny Remote Play for individual rental accounts (or all accounts within a clearly configured organization policy);
- revoke a client activation/session;
- revoke a host registration;
- inspect active sessions and audit history.

Reseller does not receive Remote Play Admin controls, PC Windows commands, or WoL rights by default. Any future reseller access must be separately specified and organization-scoped.

## 7. Runtime and operational safety

- API stays on the same DB Android server and retains its existing bind, database role, TLS/tunnel and secret configuration.
- New SQL is additive and limited to the `remote_play` schema plus narrowly scoped API functions.
- Add new API functions to the current Node RPC allowlist; preserve the existing Billing/Admin/Reseller route map and its least-privilege runtime DB role.
- Do not remove, rename or replay the existing Billing stop/pause policy migration 012. Remote Play migrations are separate and additive.
- Never modify the live Redmi database as a build step. Test the additive migration on a fresh disposable PostgreSQL database and on a verified staging database first.
- Never stop or reconfigure the Blynk/WoL relay as part of Remote Play.
- All host, client, session, credential and admin operations need audit events, rate limits, idempotency keys and revocation support.
