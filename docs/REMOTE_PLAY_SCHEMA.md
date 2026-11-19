# Remote Play schema deployment record

Applied on 2026-11-19 to Supabase project `wzkjpycjizexxcxrfdvr` (Billing Ani Games Ready, primary, ap-northeast-1).

Migration version: `20261119092733`
Migration name: `remote_play_integration_v1`

Created private schema `remote_play` with:
- `config`
- `client_devices`
- `hosts`
- `sessions`

All tables have RLS enabled. Direct client table access is revoked; Edge Function uses server-side service credentials to call restricted RPCs. Existing public rental/device tables were not altered structurally and no data was copied or rewritten.

Created RPCs:
- `api.remote_play_login`
- `api.remote_play_register_host`
- `api.remote_play_list_hosts`
- `api.remote_play_start_session`
- `api.remote_play_mark_active`
- `api.remote_play_heartbeat_session`
- `api.remote_play_stop_client_session`
- `api.remote_play_stop_host_session`
- `api.remote_play_host_watchdog`
- `api.remote_play_topup_info`
- internal `remote_play.finish_session`

A narrow BEFORE INSERT guard on `public.rental_sessions` prevents new local billing sessions for an account or PC with a live Remote Play lease. For `pc-heartbeat` mirrored inserts, the guard skips only the conflicting mirrored row so existing device liveness reporting can continue.

Defaults:
- max session: 10,800 seconds
- cooldown: 600 seconds
- host online threshold: 45 seconds
- topup WhatsApp: 082298316902
- QRIS image URL: unset until the exact supplied image is hosted in Supabase Storage

Operational note: this record documents the applied migration; the SQL migration source should be kept in sync with the live database before production release.
