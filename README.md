# Ani Games Ready Moonlight

This repository is the isolated integration workspace for Ani Games Ready Remote Play.

## Upstream bases

- Moonlight Android: https://github.com/moonlight-stream/moonlight-android
- Sunshine: https://github.com/LizardByte/Sunshine

The Android streaming engine will be built from the upstream Moonlight Android source.
Sunshine remains the host streaming engine; a separate Windows Host Agent will manage
Ani authentication, host registration, session leases, cooldowns, and automatic session cleanup.

## Data ownership

- Existing rental accounts and PC inventory are read from the existing Billing Ani Games Ready Supabase project.
- Remote Play-specific client registrations, host capabilities, and remote session leases live in the separate `remote_play` schema.
- Existing PC Billing tables and functions are not repurposed for Moonlight-only state.

## Initial source baseline

- Moonlight Android tag: v12.2
- Sunshine host installer/source baseline: v2026.918.174827

See `docs/ARCHITECTURE.md` and `supabase/migrations` for the integration contract.
