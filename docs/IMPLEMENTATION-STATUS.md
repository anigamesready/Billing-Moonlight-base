# Remote Play implementation status

Updated: 2026-11-20 (Asia/Jakarta)

## Repository boundaries

- All Moonlight Android source integration, Android build scripts, Windows Host Agent and CI artifacts stay in this repository.
- Working branch: feature/ani-remote-play-db-android-v0.2.
- anigamesready/PC-Billing is a read-only source reference for this task. No code, migration or deployment has been committed there.
- Main branch and all other Ani Games Ready repositories remain unchanged.

## Audited baseline

- Android streaming baseline: Moonlight Android v12.2 at commit b48494cb96bff23d8886c4775cc4f39a1075495d.
- Windows streaming baseline: upstream Sunshine x64. The Host Agent expects Sunshine v2026.906.222525 or newer because pairing approval must identify the exact pending pairing_id.
- DB Android source reference: anigamesready/PC-Billing, branch feature/db-android-stop-pause-policy-20261118, commit 38a75ad85adf60a96a82cd1e7050568119f66a18.
- The inspected DB Android Node API defaults to loopback 127.0.0.1:8787; isolated stage uses 127.0.0.1:8788. Neither is a customer-facing HTTPS address.
- The inspected DB Android source does not yet contain Remote Play login, host enrollment/permissions, session lease, cooldown or Host Agent RPCs.
- Stable production HTTPS gateway and the exact shared endpoint-config mechanism used by existing Ani apps have not yet been verified. Android build fails closed if DB_ANDROID_API_BASE_URL is blank.
- The previous feature/ani-remote-play-v1 branch is reference only; its Supabase implementation was not reused.

## Implementation progress

| Work item | State |
|---|---|
| Keep project work inside Moonlight repository and isolated branch | Done |
| Pin upstream Moonlight source | Done |
| DB Android API contract v2 | Written in docs/DB-ANDROID-REMOTE-PLAY-RPC-CONTRACT-v2.md |
| Android DB Android RPC adapter; no Supabase SDK or URL | Source written; backend RPCs not deployed |
| User ID + Admin activation PIN login | Source written; no live API test |
| Access/refresh tokens generated on device and encrypted with Android Keystore | Source written; runtime test pending |
| Dashboard, PC selection and top-up contact | Source written; live API and QRIS image display still pending |
| Moonlight pairing challenge and per-install client certificate CN | Overlay script applies successfully in CI; real Sunshine pairing test pending |
| Stream lifecycle, 3-hour timer UI and heartbeat client | Source written; server deadline and mobile runtime test pending |
| Windows Host Agent service and DPAPI token protection | Source written; self-contained Windows publish succeeded in CI |
| Sunshine local API probe and session-scoped pairing | Source written; Windows/Sunshine runtime test pending |
| Static source/security checks | Passed CI |
| Android overlay patch application | Passed CI |
| Android Gradle compilation and package verification | Under verification in latest CI |
| Stable DB Android HTTPS gateway | Blocked on verifying the existing deployed URL/config |
| DB Android Remote Play RPC implementation and staging migration | Not yet implemented in the separate backend |
| WireGuard provisioning, direct P2P and relay fallback | Not implemented; Host Agent currently only accepts a preconfigured WireGuard route |
| Full GameStream TCP+UDP test across mobile/carrier NAT | Not tested |
| Shared Billing lock, 3-hour limit, 10-minute cooldown, settlement and recovery regression | Not tested against staging |
| Final signed release APK | Not built |

## CI evidence

- Static source/security checks have passed on the feature branch.
- Windows Host Agent self-contained publish and diagnostic package assembly have succeeded in CI.
- The Android overlay patch was applied successfully to the pinned Moonlight source in CI.
- An Android Gradle compilation is the current build gate. A debug APK from CI, if produced, is diagnostic only and is not a customer release.
- Do not treat the uploaded upstream Moonlight APK as this Ani app.

## Release gates

1. Implement the exact customer and Host Agent RPCs in the separate DB Android backend, with rate limits, auth families and transactional account/PC locks.
2. Confirm the same stable HTTPS gateway and endpoint configuration already used by Ani apps. Do not use localhost, a temporary Quick Tunnel or Supabase.
3. Test activation PIN lifecycle, token refresh/revocation, Admin permissions, a single active user session across Billing and Remote Play, one session per PC, idempotent settlement, 3-hour hard deadline and 600-second cooldown.
4. Test Sunshine pairing with a real Moonlight client certificate and exact pairing_id. Keep Sunshine Web UI bound to localhost.
5. Implement and test a real route for all GameStream TCP and UDP traffic, including mobile-carrier NAT/CGNAT. The DB Android API is control-plane JSON only, not a media relay.
6. Test on a real Android phone and Windows host, then run Billing concurrency, disconnect/recovery and balance regressions.
7. Produce the final signed APK only after all release gates pass.

No live Redmi database, Termux API service, Sunshine installation, Billing source or other Ani Games Ready repository was changed by this Moonlight branch work.
