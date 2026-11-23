import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

const originalFetch = globalThis.fetch;

function makeRequest(path, options = {}) {
  return new Request("https://worker.test" + path, options);
}

test.afterEach(() => {
  globalThis.fetch = originalFetch;
});

test("health only reports binding presence, never values", async () => {
  const res = await worker.fetch(makeRequest("/health"), {
    TAILSCALE_OAUTH_CLIENT_ID: "id",
    TAILSCALE_OAUTH_CLIENT_SECRET: "secret",
    ANI_PROVISIONER_API_TOKEN: "guard"
  });
  const body = await res.json();
  assert.equal(res.status, 200);
  assert.equal(body.credentialConfigured, true);
  assert.equal(body.apiGuardConfigured, true);
  assert.equal(JSON.stringify(body).includes("secret"), false);
});

test("key issuance rejects missing service guard", async () => {
  const res = await worker.fetch(makeRequest("/v1/auth-key", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ role: "client" })
  }), {});
  assert.equal(res.status, 503);
});

test("key issuance rejects unauthorized callers", async () => {
  const res = await worker.fetch(makeRequest("/v1/auth-key", {
    method: "POST",
    headers: { Authorization: "Bearer wrong", "Content-Type": "application/json" },
    body: JSON.stringify({ role: "client" })
  }), {
    TAILSCALE_OAUTH_CLIENT_ID: "id",
    TAILSCALE_OAUTH_CLIENT_SECRET: "secret",
    ANI_PROVISIONER_API_TOKEN: "correct"
  });
  assert.equal(res.status, 401);
});

for (const role of ["client", "host"]) {
  test(`issues a one-time ephemeral preauthorized key for ${role}`, async () => {
    const calls = [];
    globalThis.fetch = async (url, options) => {
      calls.push({ url: String(url), options });
      if (String(url).endsWith("/oauth/token")) {
        const form = new URLSearchParams(options.body);
        assert.equal(form.get("grant_type"), "client_credentials");
        assert.equal(form.get("client_id"), "id");
        assert.equal(form.get("client_secret"), "secret");
        assert.equal(form.get("scope"), "auth_keys");
        assert.equal(form.get("tags"), role === "host" ? "tag:ani-host" : "tag:ani-client");
        return new Response(JSON.stringify({ access_token: "access-test" }), {
          status: 200, headers: { "Content-Type": "application/json" }
        });
      }
      assert.equal(options.headers.Authorization, "Bearer access-test");
      const body = JSON.parse(options.body);
      const tag = role === "host" ? "tag:ani-host" : "tag:ani-client";
      assert.deepEqual(body.capabilities.devices.create, {
        reusable: false, ephemeral: true, preauthorized: true, tags: [tag]
      });
      assert.equal(body.expirySeconds, 3600);
      return new Response(JSON.stringify({ key: "test-key-only" }), {
        status: 200, headers: { "Content-Type": "application/json" }
      });
    };

    const res = await worker.fetch(makeRequest("/v1/auth-key", {
      method: "POST",
      headers: {
        Authorization: "Bearer correct",
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ role })
    }), {
      TAILSCALE_OAUTH_CLIENT_ID: "id",
      TAILSCALE_OAUTH_CLIENT_SECRET: "secret",
      ANI_PROVISIONER_API_TOKEN: "correct"
    });

    const response = await res.json();
    assert.equal(res.status, 200);
    assert.equal(response.ok, true);
    assert.equal(response.key, "test-key-only");
    assert.equal(response.role, role);
    assert.equal(response.ephemeral, true);
    assert.equal(response.preauthorized, true);
    assert.equal(response.reusable, false);
    assert.equal(calls.length, 2);
  });
}

test("rejects unknown enrollment role", async () => {
  const res = await worker.fetch(makeRequest("/v1/auth-key", {
    method: "POST",
    headers: { Authorization: "Bearer correct", "Content-Type": "application/json" },
    body: JSON.stringify({ role: "admin" })
  }), {
    TAILSCALE_OAUTH_CLIENT_ID: "id",
    TAILSCALE_OAUTH_CLIENT_SECRET: "secret",
    ANI_PROVISIONER_API_TOKEN: "correct"
  });
  assert.equal(res.status, 400);
});
