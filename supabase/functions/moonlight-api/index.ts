import { createClient } from "npm:@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};
const json = (status: number, body: unknown, extra: Record<string,string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type":"application/json; charset=utf-8", "Cache-Control":"no-store", ...extra },
  });

function secretKey(): string {
  const direct = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || Deno.env.get("SUPABASE_SECRET_KEY");
  if (direct) return direct;
  try {
    const all = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}");
    if (typeof all.default === "string" && all.default) return all.default;
  } catch (_) {}
  return "";
}
async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2,"0")).join("");
}
function makeToken(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), b => b.toString(16).padStart(2,"0")).join("");
}
function pathOf(req: Request): string {
  return new URL(req.url).pathname.replace(/^\/+|\/+$/g,"").replace(/^functions\/v1\/moonlight-api\/?/,"").replace(/^moonlight-api\/?/,"");
}
function isUuid(v: string): boolean { return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v); }

const supabaseUrl = (Deno.env.get("SUPABASE_URL") || "").replace(/\/+$/,"");
const serviceKey = secretKey();
const admin = supabaseUrl && serviceKey ? createClient(supabaseUrl, serviceKey, {
  db: { schema:"api" },
  auth: { persistSession:false, autoRefreshToken:false, detectSessionInUrl:false },
}) : null;

async function rpc(name: string, args: Record<string,unknown>) {
  if (!admin) throw new Error("REMOTE_PLAY_SERVICE_UNAVAILABLE");
  const { data, error } = await admin.rpc(name, args);
  if (error) throw new Error(error.message || "REMOTE_PLAY_BACKEND_ERROR");
  return data;
}

async function login(req: Request, body: any) {
  const code = String(body.code || "").trim().toUpperCase();
  const clientId = String(body.clientId || "").trim();
  const deviceName = String(body.deviceName || "Android device").trim().slice(0,120);
  const appVersion = String(body.appVersion || "").trim().slice(0,64);
  const suppliedToken = String(body.clientToken || "").trim();
  if (!/^[A-Z0-9-]{4,80}$/.test(code) || clientId.length < 8 || clientId.length > 128 ||
      (suppliedToken && !/^[0-9a-f]{64}$/i.test(suppliedToken))) {
    return json(400,{ok:false,error:"INVALID_REQUEST"});
  }
  if (!serviceKey || !admin) return json(503,{ok:false,error:"REMOTE_PLAY_SERVICE_UNAVAILABLE"});

  const ip = (req.headers.get("cf-connecting-ip") || req.headers.get("x-real-ip") || "").trim().slice(0,128);
  const rawToken = suppliedToken || makeToken();
  const tokenHash = await sha256Hex(rawToken);
  const buckets = [
    { key: await sha256Hex(serviceKey+"|moonlight-client|"+clientId.toUpperCase()), max:5, clear:true },
    { key: await sha256Hex(serviceKey+"|moonlight-code|"+code), max:5, clear:true },
  ];
  if (ip) buckets.unshift({key:await sha256Hex(serviceKey+"|moonlight-ip|"+ip),max:60,clear:false});

  for (const b of buckets) {
    const { data, error } = await admin.rpc("device_pairing_rate_limit_consume",{
      p_key_hash:b.key,p_max_attempts:b.max
    });
    if (error || !data || data.allowed !== true) {
      const retry = Math.max(1,Math.min(900,Number(data?.retry_after_seconds)||60));
      return json(429,{ok:false,error:"LOGIN_RATE_LIMITED",retry_after_seconds:retry},{"Retry-After":String(retry)});
    }
  }

  const data = await rpc("remote_play_login",{
    p_code:code,p_client_id:clientId,p_device_name:deviceName,
    p_token_hash:tokenHash,p_last_ip:ip
  });

  await admin.rpc("device_pairing_rate_limit_clear",{
    p_key_hashes:buckets.filter(x=>x.clear).map(x=>x.key)
  }).catch(()=>null);

  return json(200,{...data,client_token:rawToken,app_version:appVersion});
}

async function handler(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") return new Response(null,{status:204,headers:CORS});
  const path = pathOf(req);
  if (req.method === "GET" && path === "health") {
    return json(admin ? 200 : 503,{ok:!!admin,service:"ani-moonlight-api",api_version:1});
  }
  if (req.method !== "POST") return json(405,{ok:false,error:"METHOD_NOT_ALLOWED"});
  const raw = await req.text();
  if (raw.length > 8192) return json(413,{ok:false,error:"REQUEST_TOO_LARGE"});
  let body: any;
  try { body = JSON.parse(raw); } catch (_) { return json(400,{ok:false,error:"INVALID_JSON"}); }

  try {
    if (path === "auth/login") return await login(req,body);

    if (path === "hosts/list") {
      const token=String(body.clientToken||"");
      if (token.length<64) return json(401,{ok:false,error:"REMOTE_CLIENT_TOKEN_INVALID"});
      return json(200,await rpc("remote_play_list_hosts",{p_client_token:token}));
    }

    if (path === "host/register") {
      const token=String(body.deviceToken||"");
      if (token.length<32) return json(401,{ok:false,error:"INVALID_DEVICE_TOKEN"});
      const metadata=body.metadata && typeof body.metadata==="object" ? body.metadata : {};
      return json(200,await rpc("remote_play_register_host",{
        p_device_token:token,
        p_tunnel_address:body.tunnelAddress ? String(body.tunnelAddress) : null,
        p_host_agent_version:String(body.agentVersion||"").slice(0,64),
        p_metadata:metadata
      }));
    }

    if (path === "session/start") {
      const token=String(body.clientToken||"");
      const hostId=String(body.hostDeviceId||"");
      const eventId=String(body.sourceEventId||"").slice(0,128);
      if (token.length<64 || !isUuid(hostId)) return json(400,{ok:false,error:"INVALID_REQUEST"});
      return json(200,await rpc("remote_play_start_session",{
        p_client_token:token,p_host_device_id:hostId,p_source_event_id:eventId||null
      }));
    }

    if (path === "host/session/active") {
      const token=String(body.deviceToken||"");
      const sessionId=String(body.sessionId||"");
      if (token.length<32 || !isUuid(sessionId)) return json(400,{ok:false,error:"INVALID_REQUEST"});
      return json(200,await rpc("remote_play_mark_active",{p_device_token:token,p_session_id:sessionId}));
    }

    if (path === "host/session/heartbeat") {
      const token=String(body.deviceToken||"");
      const sessionId=String(body.sessionId||"");
      if (token.length<32 || !isUuid(sessionId)) return json(400,{ok:false,error:"INVALID_REQUEST"});
      return json(200,await rpc("remote_play_heartbeat_session",{p_device_token:token,p_session_id:sessionId}));
    }

    if (path === "session/stop") {
      const token=String(body.clientToken||"");
      const sessionId=String(body.sessionId||"");
      const reason=String(body.reason||"user_stop").slice(0,80);
      if (token.length<64 || !isUuid(sessionId)) return json(400,{ok:false,error:"INVALID_REQUEST"});
      return json(200,await rpc("remote_play_stop_client_session",{
        p_client_token:token,p_session_id:sessionId,p_reason:reason
      }));
    }

    if (path === "host/session/stop") {
      const token=String(body.deviceToken||"");
      const sessionId=String(body.sessionId||"");
      const reason=String(body.reason||"host_stopped").slice(0,80);
      if (token.length<32 || !isUuid(sessionId)) return json(400,{ok:false,error:"INVALID_REQUEST"});
      return json(200,await rpc("remote_play_stop_host_session",{
        p_device_token:token,p_session_id:sessionId,p_reason:reason
      }));
    }

    if (path === "host/watchdog") {
      const token=String(body.deviceToken||"");
      if (token.length<32) return json(401,{ok:false,error:"INVALID_DEVICE_TOKEN"});
      return json(200,await rpc("remote_play_host_watchdog",{p_device_token:token}));
    }

    if (path === "topup/info") {
      const token=String(body.clientToken||"");
      if (token.length<64) return json(401,{ok:false,error:"REMOTE_CLIENT_TOKEN_INVALID"});
      return json(200,await rpc("remote_play_topup_info",{p_client_token:token}));
    }

    return json(404,{ok:false,error:"NOT_FOUND"});
  } catch (e) {
    const message = e instanceof Error ? e.message : "REMOTE_PLAY_ERROR";
    const map: Record<string,number> = {
      REMOTE_RENTAL_CODE_INVALID:400,
      REMOTE_CLIENT_ID_INVALID:400,
      REMOTE_CLIENT_TOKEN_INVALID:401,
      REMOTE_CLIENT_TOKEN_INVALID:401,
      REMOTE_DEVICE_ALREADY_BOUND:409,
      REMOTE_DEVICE_REVOKED:403,
      REMOTE_RENTAL_NOT_FOUND:404,
      REMOTE_RENTAL_CODE_AMBIGUOUS:409,
      REMOTE_RENTAL_DISABLED:403,
      REMOTE_RENTAL_EXPIRED:403,
      REMOTE_RENTAL_TIME_EMPTY:403,
      REMOTE_COOLDOWN_ACTIVE:429,
      REMOTE_HOST_NOT_AVAILABLE:404,
      REMOTE_HOST_OFFLINE:503,
      REMOTE_PC_BUSY_BILLING:409,
      REMOTE_RENTAL_ACTIVE_LOCAL:409,
      REMOTE_RENTAL_LEASED_LOCAL:409,
      REMOTE_SESSION_CONFLICT:409,
      REMOTE_SESSION_NOT_FOUND:404,
      REMOTE_SESSION_NOT_ACTIVE:409,
      INVALID_DEVICE_TOKEN:401,
      REMOTE_PLAY_SERVICE_UNAVAILABLE:503
    };
    const status = map[message] || (message.includes("REMOTE_PLAY") || message.includes("BACKEND") ? 503 : 400);
    return json(status,{ok:false,error:message});
  }
}
Deno.serve(handler);
