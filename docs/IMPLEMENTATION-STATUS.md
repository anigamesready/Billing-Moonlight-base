# Remote Play implementation status

Updated: 2026-11-20 (Asia/Jakarta)

## Current workspace

- Repository: `anigamesready/Billing-Moonlight-base`
- Working branch: `feature/remote-play-db-android-v0.1`
- Parent baseline: `main` at `1407c9deaa1828eabba9dd223be6c1b764dbef63`
- Current change scope: documentation and integration contract only; no Android/Windows runtime code has been committed.

## Audited inputs

| Input | SHA-256 | Verified identity |
|---|---|---|
| `app-nonRoot-release.apk` | `80ff02a1b674fcfaea54c43120c50f4514856a3be9a1d05b9299cd487aef9c2e` | Android package `com.limelight`, version `12.2`, versionCode `315`; Moonlight streaming client, not Ani dashboard |
| `MoonlightSetup-6.2.0.exe` | `ee2a1b56ec83010f4f8a275bb71892a7dc7a3bc874dfaebe706b74d5d10831bd` | Windows executable; not an Ani Host Agent |
| `Sunshine-Windows-AMD64-installer.msi` | `67cb7451fc522d3741d19362aeda3ef0d05ca32f2aeaf5faa9a09768ef6a3174` | Sunshine Windows x64 installer; not an Ani Host Agent |
| QRIS JPEG | Not packaged into app yet | Top-up display asset only |

APK metadata reports Moonlight v12.2 source commit in `META-INF/version-control-info.textproto`, while `git.properties` contains a different abbreviated identifier (`05b2e96`). Treat the uploaded binary as a functional reference only; the reproducible release must be built from the pinned upstream source commit and independently verified.

## Backend evidence

- Repository: `anigamesready/PC-Billing`.
- Audited source: `db-android-core/server/server.mjs` at commit `d86e7ecfa82d7af29bb53d067a316984b1fef69b`.
- Existing Node API defaults to loopback `127.0.0.1:8787`; isolated staging instructions specify a separate database/API on `127.0.0.1:8788`.
- Current API includes allowlisted Admin/Billing RPCs and the existing PC device protocol. It does not currently expose Remote Play customer auth, host registration, host authorization, session lease or cooldown APIs.
- Current Web/API relay is for bounded HTTP control routes, not Moonlight audio/video streaming.
- No proof yet that the audited backend commit, staging migrations, current tunnel endpoint or relay agent exactly match the live Redmi installation. No live database or Android server was changed in this task.

## Implementation state

| Work item | State |
|---|---|
| Confirm DB backend choice (existing DB Android, no Supabase) | Done |
| Pin Moonlight Android baseline | Done |
| Inspect uploaded APK/EXE/MSI identity | Done, with APK provenance note above |
| Define reuse of existing rental accounts and PC inventory | Designed |
| Define isolated `remote_play` schema and RPC contract | Proposed; not deployed |
| Add customer activation credential, host token and admin permission flow | Not implemented |
| Enforce cross-PC-Billing / Remote Play single-session lock | Not implemented |
| Enforce 3-hour cap, shared balance settlement and 10-minute cooldown | Not implemented |
| Implement Android Ani dashboard and Moonlight seamless launch | Not implemented |
| Implement Windows Host Agent | Not implemented |
| Implement and test UDP-capable game-stream network path | Not implemented |
| Build signed APK/EXE and end-to-end test | Not started |

## Stop / release conditions

Do not release a merely rebranded Moonlight APK. The app is not final until the Android dashboard and native streaming flow, DB Android API additions, Host Agent, host authorization, cross-session transaction guard, media networking and the full test matrix are implemented and verified.

No production database migrations, Supabase changes, Termux process changes, Sunshine configuration changes or public port openings were made.
