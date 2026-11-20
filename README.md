# Ani Games Ready Remote Play

Isolated integration workspace for the Ani Games Ready Moonlight/Sunshine remote-play product.

## Technology baseline

- Android streaming engine: upstream [Moonlight Android](https://github.com/moonlight-stream/moonlight-android), tag `v12.2`, commit `b48494cb96bff23d8886c4775cc4f39a1075495d`.
- Windows streaming host: upstream [Sunshine](https://github.com/LizardByte/Sunshine), minimum pairing-safe baseline v2026.906.222525 (later versions recommended).
- User, rental balance, PC inventory, authorization and session control: the existing **Ani Games Ready DB Android** Node API + PostgreSQL on the Redmi/Termux server.
- Windows per-PC service: separate Ani Remote Play Host Agent source in host-agent/; CI build succeeds, but on-PC deployment/runtime checks remain pending.

## Database rule — do not use Supabase

- Do not add Supabase SDKs, Supabase URLs, anon/service keys, Edge Functions, DB1/DB2 routing, or a second Supabase project to this product.
- Reuse the same DB Android API base URL/configuration already used by the Ani Games Ready Android system. Do not hardcode a guessed URL in the APK or create another database connection configuration.
- Reuse existing rental accounts and PC inventory by ID. Keep Remote Play-only records in an additive `remote_play` PostgreSQL schema on the existing DB Android database; do not repurpose or rename existing Billing tables/functions.
- Existing API default ports are `8787` (main) and `8788` (isolated staging). A customer phone must use the configured HTTPS-accessible DB Android API endpoint, never its own `127.0.0.1`.
- No migration is to be applied to the live Redmi database as part of this source work. Staging and restore verification come first.

## Product behaviour

- Rental-ID login with a separately protected Remote Play activation credential; an account identifier alone must not become a public bearer password.
- Dashboard shows eligible PCs, online/available/in-use state, balance and expiry.
- Admin must explicitly authorize each PC and the relevant rental accounts for Remote Play.
- Server-side exclusive lock: one rental account can have only one active session across Remote Play and PC Billing.
- Maximum 3 hours per remote session, also bounded by remaining rental time; settle usage on the server.
- 10-minute cooldown after a session ends. Enforce the cooldown in PostgreSQL, not only in the Android UI.
- Balance exhausted or account expired: block starting a session but keep the dashboard and top-up information visible.
- QRIS/top-up image and WhatsApp contact are display assets only; they do not grant access or change the rental balance.

## Network separation

The DB Android HTTPS API is the **control plane** only (login, inventory, permission, session lease, heartbeat and stop command). It is not a Moonlight video/audio transport. Sunshine's GameStream TCP/UDP traffic must use a separately secured, routable network path. Do not expose Sunshine's Web UI or send game-stream media through an ordinary HTTP Worker/API relay.

## Build status

This repository began as a README-only workspace. The isolated branch feature/ani-remote-play-db-android-v0.2 now contains an Android Moonlight overlay, DB Android RPC adapter, activation-PIN login, Android Keystore token storage, a Windows Host Agent and CI build definitions. Static checks pass and the Windows Host Agent has compiled in CI. The Android overlay has been applied successfully in CI; Gradle compilation is still under verification. No customer release exists yet: DB Android Remote Play RPCs are only specified in the companion contract, the stable public API URL is not verified, and WireGuard remains a preconfigured media-route assumption—not an implemented P2P/relay system. See:

- [Architecture](docs/ARCHITECTURE.md)
- [DB Android Remote Play RPC contract v2](docs/DB-ANDROID-REMOTE-PLAY-RPC-CONTRACT-v2.md)
- [Build and verification gates](docs/BUILD-AND-VERIFICATION.md)

Do not label any artifact final until DB Android RPCs are deployed and tested in staging, Android and Windows builds pass, the existing HTTPS gateway is configured, Sunshine pairing is tested, GameStream TCP+UDP works over the chosen network path, and the 3-hour cap, 10-minute cooldown, shared Billing lock and end-to-end regressions pass. CI debug APKs are diagnostic only.
