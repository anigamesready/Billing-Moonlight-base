const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store"
};

function reply(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function constantTimeEqual(left, right) {
  if (!left || !right || left.length !== right.length) return false;
  const a = new TextEncoder().encode(left);
  const b = new TextEncoder().encode(right);
  let mismatch = 0;
  for (let i = 0; i < a.length; i++) mismatch |= a[i] ^ b[i];
  return mismatch === 0;
}

async function requestOAuthToken(env, tag) {
  const form = new URLSearchParams({
    grant_type: "client_credentials",
    scope: "auth_keys",
    tags: tag
  });
  const response = await fetch("https://api.tailscale.com/api/v2/oauth/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form
  });
  if (!response.ok) {
    throw new Error(`Tailscale OAuth token exchange failed: HTTP ${response.status}`);
  }
  const data = await response.json();
  if (!data.access_token) throw new Error("Tailscale OAuth response omitted access_token");
  return data.access_token;
}

async function createEphemeralKey(env, role) {
  const tag = role === "host" ? "tag:ani-host" : "tag:ani-client";
  const accessToken = await requestOAuthToken(env, tag);
  const response = await fetch("https://api.tailscale.com/api/v2/tailnet/-/keys", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      capabilities: {
        devices: {
          create: {
            reusable: false,
            ephemeral: true,
            preauthorized: true,
            tags: [tag]
          }
        }
      },
      expirySeconds: 3600,
      description: `Ani Games Ready ${role} ephemeral enrollment`
    })
  });
  if (!response.ok) {
    throw new Error(`Tailscale auth-key creation failed: HTTP ${response.status}`);
  }
  const data = await response.json();
  if (!data.key) throw new Error("Tailscale API response omitted auth key");
  return {
    key: data.key,
    role,
    tag,
    ephemeral: true,
    preauthorized: true,
    reusable: false,
    expires: data.expires ?? null
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return reply({
        ok: true,
        service: "ani-games-tailscale-provisioner",
        credentialConfigured: Boolean(
          env.TAILSCALE_OAUTH_CLIENT_ID && env.TAILSCALE_OAUTH_CLIENT_SECRET
        ),
        apiGuardConfigured: Boolean(env.ANI_PROVISIONER_API_TOKEN)
      });
    }

    if (request.method !== "POST" || url.pathname !== "/v1/auth-key") {
      return reply({ ok: false, error: "not_found" }, 404);
    }

    if (
      !env.TAILSCALE_OAUTH_CLIENT_ID ||
      !env.TAILSCALE_OAUTH_CLIENT_SECRET ||
      !env.ANI_PROVISIONER_API_TOKEN
    ) {
      return reply({ ok: false, error: "provisioner_not_configured" }, 503);
    }

    const authorization = request.headers.get("Authorization") ?? "";
    if (!constantTimeEqual(authorization, `Bearer ${env.ANI_PROVISIONER_API_TOKEN}`)) {
      return reply({ ok: false, error: "unauthorized" }, 401);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return reply({ ok: false, error: "invalid_json" }, 400);
    }

    if (!body || !["client", "host"].includes(body.role)) {
      return reply({ ok: false, error: "role_must_be_client_or_host" }, 400);
    }

    try {
      const result = await createEphemeralKey(env, body.role);
      return reply({ ok: true, ...result });
    } catch (error) {
      // Do not log or return credentials, request bodies, or upstream response bodies.
      return reply({
        ok: false,
        error: "tailscale_api_error",
        message: String(error?.message ?? "upstream_request_failed")
      }, 502);
    }
  }
};
