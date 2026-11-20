# Remote Play implementation status

Updated: 2026-11-20 (Asia/Jakarta)

## Workspace boundaries

- App/Host Agent repository: `anigamesready/Billing-Moonlight-base`.
- Current isolated work branch: `feature/ani-remote-play-db-android-v0.2`.
- Parent baseline: `feature/remote-play-db-android-v0.1` at `9fb46359d1a118a1e65cd7ffd59d034986342d33`.
- The Moonlight repository is the only place for Moonlight Android overlay, custom Windows Host Agent, app CI and Moonlight release artifacts.
- `anigamesready/PC-Billing` is read-only source reference in this task. No code or files are copied into it or committed there.
- Main branch and all other Ani Games Ready repositories remain unchanged.

## Current findings

- Main branch is a README-only workspace.
- `feature/ani-remote-play-v1` is an older exploratory branch with Android/Host Agent scaffolding and a CI workflow, but its API client, Host Agent and docs contain Supabase-specific endpoints and assumptions. Do not merge or reuse its Supabase backend.
- `feature/remote-play-db-android-v0.1` records the correct DB Android direction: reuse the existing Node API + PostgreSQL on the Redmi/Termux server; keep Remote Play-only state additive and separate.
- Current DB Android source reference: `anigamesready/PC-Billing`, branch `feature/db-android-stop-pause-policy-20261118`, commit `38a75ad85adf60a96a82cd1e7050568119f66a18`.
- That source has the existing Node API, Admin/Billing/Reseller RPCs and migration 012, but no Remote Play login, host enrollment/permissions, session lease, cooldown or Host Agent RPCs.
- Node API defaults to loopback `127.0.0.1:8787`; isolated staging uses `127.0.0.1:8788`. Neither is directly reachable from a customer phone over the internet.
- The live, stable HTTPS DB Android gateway and the exact shared configuration mechanism used by existing Android apps have not yet been verified. Do not hardcode any URL or build a customer APK against loopback/temporary Quick Tunnel.
- Moonlight Android baseline remains v12.2, commit `b48494cb96bff23d8886c4775cc4f39a1075495d`.
- Uploaded `app-nonRoot-release.apk` is official/upstream Moonlight v12.2 (package `com.limelight`), not an Ani Games Ready app. Uploaded MoonlightSetup EXE and Sunshine MSI are upstream installers, not the custom Host Agent.

## Implementation checklist

| Work item | State |
|---|---|
| Confirm separate repo boundary | Done |
| Create isolated Moonlight branch | Done |
| Confirm no Supabase for Remote Play | Done |
| Pin upstream Moonlight Android source | Done |
| Audit prior Supabase-oriented implementation branch | Done; retain only as code reference |
| Audit latest DB Android API source | Done at repository level; live Redmi state not verified |
| Define exact DB Android customer/host RPC contract | In progress |
| Verify existing shared API endpoint config mechanism | Blocked on identifying a source-compatible shared config provider |
| Implement Android activation PIN login + dashboard + secure token rotation | Not implemented |
| Integrate Moonlight client cert/pairing + stream lifecycle | Not implemented |
| Implement Windows Host Agent using DB Android token family | Not implemented |
| Implement real TCP+UDP media path (direct P2P/relay fallback) | Not implemented |
| Add backend RPCs to separate DB Android backend repository | Outside this repository's source scope; still a release dependency |
| Build reproducible Android APK and Windows Host Agent | Not started |
| Run Android, Windows, backend concurrency and end-to-end tests | Not started |

## Security and release gates

- Do not add Supabase SDKs, URLs, keys, Edge Functions, migration directories or dual-DB routing.
- Do not mutate live Redmi database or existing Billing migrations.
- Customer authentication requires a separate Admin-issued activation secret; rental ID alone is never a password.
- Customer and Host Agent tokens are separate families; store token hashes on server and encrypted/DPAPI-protected raw tokens on devices.
- The Android app receives no database credentials and no Sunshine admin secret.
- The Host Agent uses Sunshine's local-only API endpoint. Never expose Sunshine Web UI/REST administration to the internet.
- Do not present the uploaded upstream Moonlight APK or any debug build as the final Ani Games Ready app.
- Release only after exact API availability, stable HTTPS endpoint, Android/Windows clean builds, signature/package verification, secure TCP+UDP media path, 3-hour cap, 10-minute cooldown, balance settlement and PC Billing concurrency/regression tests all pass.

No live database, Termux service, Cloudflare gateway, Sunshine installation or other repository has been changed.
