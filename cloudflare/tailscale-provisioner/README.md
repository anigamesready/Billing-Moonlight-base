# Ani Games Ready — Tailscale Provisioner (Stage)

Isolated Cloudflare Worker for issuing one-time Tailscale auth keys to Ani client and host enrollments. This is staging-only; it does not modify the existing Android DB gateway or media path.

## Endpoints

- `GET /health`: reports whether required bindings are present (never returns values).
- `POST /v1/auth-key`: requires `Authorization: Bearer <ANI_PROVISIONER_API_TOKEN>` and JSON `{"role":"client"}` or `{"role":"host"}`.

## Required Worker secrets

Set these through Cloudflare Worker Settings → Variables and Secrets. Never commit them to Git:

- `TAILSCALE_OAUTH_CLIENT_ID`
- `TAILSCALE_OAUTH_CLIENT_SECRET`
- `ANI_PROVISIONER_API_TOKEN` (random high-entropy value; do not reuse the Tailscale secret)

## Key policy

- client → `tag:ani-client`
- host → `tag:ani-host`
- one-time, non-reusable, ephemeral, preauthorized
- auth key expires after 3600 seconds if unused
- Tailscale OAuth token is obtained server-side; no OAuth secret is sent to Android or Windows clients

The OAuth credential must have the `auth_keys` scope and be allowed to issue the requested child tags via the tailnet `tagOwners` policy.

## Deployment

Deploy only this folder as a separate Worker named `ani-games-tailscale-provisioner-stage`. Keep the existing `ani-games-db-android-stage-gateway` unchanged. Do not add routes to the production billing gateway.

After adding secrets, call `GET /health`; both configuration flags must be true. Then test authenticated issuance for both roles. Never paste returned auth keys into logs, GitHub, or chat.

## Important limitations

Ephemeral nodes are removed after going offline, but removal is not guaranteed to be instantaneous. Revoking an auth key does not deauthorize a node already enrolled; immediate device removal requires the separate Tailscale device-management permission (`devices:core`) and device deletion. This Worker intentionally does not perform device deletion.
