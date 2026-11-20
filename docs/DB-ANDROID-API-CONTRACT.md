# Remote Play API contract — DB Android

Status: **proposed, not implemented**. The contract deliberately uses the existing Ani Games Ready DB Android Node API and PostgreSQL. It does not introduce Supabase.

## Existing verified API surface

Source: `anigamesready/PC-Billing` commit `d86e7ecfa82d7af29bb53d067a316984b1fef69b`.

The existing server accepts `POST /rest/v1/rpc/<allowlisted-name>`, `POST /device/*`, `GET /health`, and the supported Auth compatibility routes. RPC calls are resolved from a Node-side allowlist to typed PostgreSQL routines. The DB Android runtime role has execution rights for API functions only; it does not receive direct business-table read/write privileges.

The current server has no `remote_play` schema, customer Remote Play authentication, host Remote Play authentication, session lease API, or media-route exchange. No existing endpoint should be repurposed for these duties.

## Connection rule

- Resolve the same HTTPS API base URL from the existing DB Android connection settings used by the current Android system.
- Do not build a new database URL picker, add Supabase settings, embed database credentials, or hardcode `127.0.0.1` in the customer APK.
- Main local API is `127.0.0.1:8787`; staging is isolated on `127.0.0.1:8788`. A remote customer device must use the existing verified HTTPS gateway for the appropriate environment.
- Before a release, verify the actual API base URL from a second device outside the Redmi network and confirm that it terminates at the same DB Android API deployment.

## Proposed RPC allowlist additions

All names below are new proposals. They must be implemented in SQL, registered in the Node `RPC` allowlist, protected by token-family checks, and covered by staging tests before the Android app may call them.

| RPC | Auth family | Purpose |
|---|---|---|
| `remote_play_login` | none + rate limit | Exchange rental ID + Remote Play activation PIN for a short-lived opaque customer session and refresh token |
| `remote_play_refresh` | refresh token | Rotate the customer session |
| `remote_play_logout` | customer | Revoke the customer's current session |
| `remote_play_dashboard` | customer | Return eligible PCs, host state, rental balance and expiry |
| `remote_play_request_session` | customer | Atomically check eligibility, host grant, balance, cooldown and conflicts; create a lease |
| `remote_play_session_heartbeat` | customer | Renew the lease within the fixed server deadline |
| `remote_play_end_session` | customer | End and settle the session idempotently |
| `remote_play_host_enroll` | one-time host enrollment code | Register the Host Agent to an existing device/org with explicit Admin permission |
| `remote_play_host_heartbeat` | host | Report Agent and Sunshine readiness, active session and network capability |
| `remote_play_host_next_command` | host | Retrieve only commands for the authenticated host |
| `remote_play_host_ack` | host | Acknowledge start/stop and connection outcomes |
| `admin_remote_play_hosts` | Admin | List host Remote Play grants and status |
| `admin_remote_play_set_host_permission` | Admin | Enable/disable Remote Play for a PC |
| `admin_remote_play_set_rental_permission` | Admin | Enable/disable a rental account's Remote Play eligibility |
| `admin_remote_play_revoke_client` | Admin | Revoke a customer client/session |
| `admin_remote_play_sessions` | Admin | Inspect current/historical Remote Play sessions |

Use the existing API path `/rest/v1/rpc/<name>`; do not invent a parallel database URL or expose PostgreSQL through PostgREST. The Node server must authenticate each token family against its own hashed token table. A valid Admin token must not be treated as a customer or host token, and vice versa.

## Proposed data ownership

Add tables only in the same PostgreSQL instance, under a new non-public `remote_play` schema. Suggested entities:

- `client_credentials`: rental_account_id, secret_hash, enabled/revoked fields, created_at; unique active credential policy.
- `client_sessions`: rental_account_id, token_hash, refresh_hash, client_install_id, expires_at, revoked_at, last_seen_at.
- `host_registrations`: existing device_id, org_id, token_hash, host build, Sunshine readiness, last heartbeat, revoked_at.
- `host_permissions`: existing device_id, org_id, enabled, approved_by, approved_at, updated_at.
- `rental_permissions`: existing rental_account_id, org_id, enabled, approved_by, approved_at, updated_at.
- `sessions`: rental_account_id, device_id, opaque session ID, state, start/deadline/end timestamps, initial balance, consumed seconds, settled_at, cooldown_until, client/host acknowledgements.
- `commands`: session ID, host ID, command, idempotency key, status, acknowledgement and timestamps.
- `audit_events`: actor family/id, org, event, target and bounded structured metadata.

Every row must reference existing `public.rental_accounts`, `public.devices` and `public.organizations` identities where applicable. Do not duplicate rental balance, customer name or the PC inventory as an independent source of truth. Do not alter the existing rental/device row formats merely to add Remote Play.

## Transaction requirements

### Customer login
- Do not accept the rental ID alone as a password. Existing rental accounts store a code and balance but do not provide a dedicated customer password field.
- Admin-issued activation PIN must be random, single-use, rate-limited, hashed with a password KDF, and never returned in an ordinary dashboard/list query.
- Login must check account status, organization, expiry and Remote Play permission before minting a revocable token pair.
- Token hashes only are stored; raw session tokens are returned once over HTTPS.

### Session start
Within one PostgreSQL transaction:
1. Authenticate the customer session and lock the customer session/account.
2. Acquire the same `pg_advisory_xact_lock(hashtextextended(org_id || ':' || upper(trim(rental_code)), 0))` lock used by the PC Billing rental-session path.
3. Lock the existing rental account row and validate status, expiry and positive remaining seconds.
4. Confirm Admin's host permission and rental permission are both enabled and the host is online/ready.
5. Reject if any active Remote Play session exists for the rental account or host.
6. Reject if an active/paused `public.rental_sessions` row exists for the same rental account/code.
7. Reject if the prior session cooldown has not elapsed.
8. Create the unique active session and compute `deadline_at = min(now() + 3 hours, now() + available_balance)`.
9. Create a short-lived host grant/command. Never expose a Sunshine Web UI credential or database token to the Android app.

The existing PC Billing `device_start_rental_session` path must be protected against Remote Play conflicts using the same transaction lock and a database guard/compatible function update. Merely checking the two apps independently is not enough to prevent simultaneous races.

### Session end / watchdog
- Determine actual used seconds from server timestamps; never trust a client-reported remaining-time number as authoritative.
- Settlement is exactly-once: lock the account/session, compute bounded elapsed time, decrement the shared `public.rental_accounts.remaining_seconds`, write usage/audit event, set `settled_at`, and revoke host grant in one transaction.
- Maximum charge is 10,800 seconds and cannot exceed the available start balance.
- Expiry, disconnect and lost-heartbeat paths converge to the same settlement function.
- Cooldown starts when the session is settled and ended; default is 600 seconds.
- Duplicate command, heartbeat or stop requests must return the prior result rather than deducting twice.

## Media-route security

The API only returns a short-lived, per-session connection grant and a routable host transport descriptor. It must not publish Sunshine Web UI endpoints or credentials. The chosen media transport must support Moonlight's TCP and UDP streaming requirements and must be tested through mobile carrier NAT and home-router NAT.

Do not put game-stream traffic through the DB Worker, generic Cloudflare HTTP gateway or the Android rental API. Those services remain control-plane only.

## Error contract (suggested)

Use stable error codes, with no secrets in details:

- `REMOTE_PLAY_DISABLED`
- `REMOTE_PLAY_ACTIVATION_REQUIRED`
- `REMOTE_PLAY_ACCOUNT_INACTIVE`
- `REMOTE_PLAY_BALANCE_EMPTY`
- `REMOTE_PLAY_ACCOUNT_EXPIRED`
- `REMOTE_PLAY_HOST_DISABLED`
- `REMOTE_PLAY_HOST_OFFLINE`
- `REMOTE_PLAY_SESSION_ACTIVE`
- `REMOTE_PLAY_HOST_BUSY`
- `REMOTE_PLAY_COOLDOWN_ACTIVE`
- `REMOTE_PLAY_SESSION_EXPIRED`
- `REMOTE_PLAY_SESSION_REVOKED`
- `REMOTE_PLAY_MEDIA_PATH_UNAVAILABLE`

## Acceptance tests before client integration

- Wrong, expired, revoked or cross-family tokens are rejected.
- Brute-force PIN attempts are throttled by IP and normalized rental identifier.
- Rental ID alone never logs in.
- Host disabled by Admin cannot be requested, even if its Agent is online.
- Disabled rental account cannot start a session.
- Concurrent requests for one rental ID result in exactly one active session.
- Concurrent requests for one host result in exactly one active session.
- A PC Billing session and Remote Play session cannot overlap for the same account, including concurrent race tests.
- A session is stopped at the 3-hour or lower balance deadline by the server/Host Agent, even if Android app is closed.
- Repeated stop/reconnect/settlement cannot deduct balance twice.
- Cooldown rejects starts for the full 600 seconds and allows a later start.
- Staging database backup and restore are tested before applying the migration to an existing Redmi database.
