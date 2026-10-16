import { createServer } from "node:http";
import { randomInt, randomUUID, createHash, createHmac, timingSafeEqual } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const PORT = Number(process.env.PORT || 8787);
const DEVICE_SIGNING_SECRET = process.env.DEVICE_SIGNING_SECRET || "";
const OWNER_PIN = process.env.OWNER_PIN || "";
const PUBLIC_ORIGIN = (process.env.PUBLIC_ORIGIN || "https://biruk-ai-player-relay.onrender.com").replace(/\/$/, "");
const OAUTH_SCOPE = "bridge:control";
const OAUTH_RESOURCE = PUBLIC_ORIGIN;
const MAX_BODY = 24 * 1024;
const DEVICE_ONLINE_MS = 75_000;
const PAIR_CODE_TTL_MS = 5 * 60_000;
const COMMAND_TIMEOUT_MS = 28_000;

const pairCodes = new Map();
const devices = new Map();
const pending = new Map();
const pairAttempts = new Map();
const oauthAuthorizeRequests = new Map();
const oauthCodes = new Map();
const oauthAttemptLimits = new Map();

function now() { return Date.now(); }
function json(res, status, value) {
  const body = Buffer.from(JSON.stringify(value), "utf8");
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Connection": "close"
  });
  res.end(body);
}
function safeEqualText(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const aa = Buffer.from(a, "utf8");
  const bb = Buffer.from(b, "utf8");
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}
function bearer(req) {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7) : "";
}
function relayConfigured() {
  return DEVICE_SIGNING_SECRET.length >= 32 && OWNER_PIN.length >= 16;
}
function deriveOAuthKey(purpose) {
  return createHmac("sha256", DEVICE_SIGNING_SECRET).update("ai-player-relay:" + purpose).digest();
}
function signOAuthPayload(payload, purpose) {
  const encoded = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
  const signature = createHmac("sha256", deriveOAuthKey(purpose)).update(encoded).digest("base64url");
  return encoded + "." + signature;
}
function verifyOAuthPayload(token, purpose) {
  if (!relayConfigured() || typeof token !== "string" || token.length > 16384) return null;
  const parts = token.split(".");
  if (parts.length !== 2) return null;
  const expected = createHmac("sha256", deriveOAuthKey(purpose)).update(parts[0]).digest("base64url");
  if (!safeEqualText(parts[1], expected)) return null;
  try {
    return JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
  } catch {
    return null;
  }
}
function validOAuthClient(clientId) {
  const client = verifyOAuthPayload(clientId, "oauth-client");
  if (!client || !Array.isArray(client.redirect_uris) || client.redirect_uris.length < 1) return null;
  if (!client.redirect_uris.every(isAllowedRedirectUri)) return null;
  return client;
}
function verifyAccessToken(token) {
  const claims = verifyOAuthPayload(token, "oauth-access");
  if (!claims || claims.kind !== "access" || claims.iss !== PUBLIC_ORIGIN
      || claims.aud !== OAUTH_RESOURCE || !Number.isFinite(claims.exp) || claims.exp <= Math.floor(now() / 1000)
      || typeof claims.scope !== "string" || !claims.scope.split(" ").includes(OAUTH_SCOPE)) return null;
  return claims;
}
function verifyRefreshToken(token) {
  const claims = verifyOAuthPayload(token, "oauth-refresh");
  if (!claims || claims.kind !== "refresh" || claims.iss !== PUBLIC_ORIGIN
      || claims.aud !== OAUTH_RESOURCE || !Number.isFinite(claims.exp) || claims.exp <= Math.floor(now() / 1000)
      || typeof claims.client_id !== "string") return null;
  return claims;
}
function validMcpRequest(req) {
  return !!verifyAccessToken(bearer(req));
}
function isAllowedRedirectUri(value) {
  try {
    const u = new URL(value);
    if (u.protocol !== "https:" || u.username || u.password || u.port && u.port !== "443") return false;
    if (u.hostname !== "chatgpt.com" && u.hostname !== "chat.openai.com") return false;
    return u.pathname.startsWith("/connector/oauth/") || u.pathname === "/connector_platform_oauth_redirect";
  } catch {
    return false;
  }
}
function html(res, status, markup) {
  const body = Buffer.from(markup, "utf8");
  res.writeHead(status, {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    "X-Frame-Options": "DENY",
    "Connection": "close"
  });
  res.end(body);
}
function escapeHtml(value) {
  return String(value).replace(/[&<>"]/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[char]));
}
function authorizePage(requestId, notice = "") {
  const safeNotice = notice ? "<p class='notice'>" + escapeHtml(notice) + "</p>" : "";
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Authorize Android Bridge</title><style>body{font:16px system-ui,sans-serif;max-width:34rem;margin:3rem auto;padding:0 1rem;color:#172033}main{border:1px solid #ddd;border-radius:12px;padding:1.2rem}input,button{font:inherit;padding:.7rem;width:100%;box-sizing:border-box;margin:.4rem 0}button{background:#173c78;color:#fff;border:0;border-radius:8px}small{color:#555}.notice{color:#a33}</style></head><body><main><h1>Connect Android Bridge</h1><p>Allow your private ai-player plugin to send authorized commands to your paired phone.</p><p>Enter the private relay owner PIN you configured in the hosting dashboard. This does not ask for your Google password.</p>${safeNotice}<form method="post" action="/oauth/authorize"><input type="hidden" name="request_id" value="${escapeHtml(requestId)}"><label for="pin">Relay owner PIN</label><input id="pin" name="owner_pin" type="password" minlength="16" required autocomplete="current-password"><button type="submit">Authorize this connection</button></form><small>Only authorize a connection you initiated. Keep the PIN private.</small></main></body></html>`;
}
async function readBodyText(req) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > MAX_BODY) throw Object.assign(new Error("body_too_large"), { status: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}
async function readForm(req) {
  const text = await readBodyText(req);
  return new URLSearchParams(text);
}
function tooManyOAuthAttempts(ip) {
  const current = now();
  let record = oauthAttemptLimits.get(ip);
  if (!record || record.resetAt <= current) {
    record = { count: 0, resetAt: current + 10 * 60_000 };
    oauthAttemptLimits.set(ip, record);
  }
  record.count++;
  return record.count > 12;
}
function issueMcpTokens(clientId, resource = OAUTH_RESOURCE) {
  const issuedAt = Math.floor(now() / 1000);
  const accessClaims = {
    iss: PUBLIC_ORIGIN, aud: resource, sub: "bridge-owner", scope: OAUTH_SCOPE,
    kind: "access", iat: issuedAt, exp: issuedAt + 15 * 60, jti: randomUUID()
  };
  const refreshClaims = {
    iss: PUBLIC_ORIGIN, aud: resource, sub: "bridge-owner", scope: OAUTH_SCOPE,
    kind: "refresh", client_id: clientId, iat: issuedAt, exp: issuedAt + 30 * 24 * 60 * 60, jti: randomUUID()
  };
  return {
    access_token: signOAuthPayload(accessClaims, "oauth-access"),
    token_type: "Bearer",
    expires_in: 900,
    refresh_token: signOAuthPayload(refreshClaims, "oauth-refresh"),
    refresh_token_expires_in: 30 * 24 * 60 * 60,
    scope: OAUTH_SCOPE
  };
}
async function readJson(req) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > MAX_BODY) throw Object.assign(new Error("body_too_large"), { status: 413 });
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw Object.assign(new Error("invalid_json"), { status: 400 }); }
}
function signDeviceToken(deviceId, exp) {
  const payload = Buffer.from(JSON.stringify({ sub: deviceId, exp })).toString("base64url");
  const sig = createHmac("sha256", DEVICE_SIGNING_SECRET).update(payload).digest("base64url");
  return payload + "." + sig;
}
function verifyDeviceToken(token) {
  if (!DEVICE_SIGNING_SECRET || typeof token !== "string") return null;
  const parts = token.split(".");
  if (parts.length !== 2) return null;
  const expected = createHmac("sha256", DEVICE_SIGNING_SECRET).update(parts[0]).digest("base64url");
  if (!safeEqualText(parts[1], expected)) return null;
  try {
    const obj = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
    if (!obj.sub || !Number.isFinite(obj.exp) || obj.exp <= now()) return null;
    return { deviceId: obj.sub, exp: obj.exp };
  } catch { return null; }
}
function getDeviceFromRequest(req) {
  const verified = verifyDeviceToken(bearer(req));
  if (!verified) return null;
  let device = devices.get(verified.deviceId);
  if (!device) {
    device = { id: verified.deviceId, token: bearer(req), lastSeen: 0, queue: [], waiter: null };
    devices.set(device.id, device);
  }
  device.lastSeen = now();
  return device;
}
function purgeExpired() {
  const current = now();
  for (const [code, item] of pairCodes) if (item.expiresAt <= current) pairCodes.delete(code);
  for (const [id, device] of devices) {
    if (current - device.lastSeen > 24 * 60 * 60_000 && device.queue.length === 0 && !device.waiter) devices.delete(id);
  }
  for (const [ip, item] of pairAttempts) if (item.resetAt <= current) pairAttempts.delete(ip);
}
function issuePairCode() {
  purgeExpired();
  let code;
  do { code = String(randomInt(100000, 1_000_000)); } while (pairCodes.has(code));
  pairCodes.set(code, { expiresAt: now() + PAIR_CODE_TTL_MS });
  return code;
}
function toolResult(value) {
  return { content: [{ type: "text", text: JSON.stringify(value, null, 2) }] };
}
function onlineDevices() {
  const cutoff = now() - DEVICE_ONLINE_MS;
  return [...devices.values()].filter(d => d.lastSeen >= cutoff);
}
function currentDevice() {
  const online = onlineDevices().sort((a, b) => b.lastSeen - a.lastSeen);
  return online[0] || null;
}
function enqueue(device, command) {
  return new Promise((resolve) => {
    const item = { ...command, deviceId: device.id, resolve, timer: null };
    item.timer = setTimeout(() => {
      pending.delete(item.id);
      resolve({ status: "FAILED", verified: false, reason: "device_response_timeout", commandId: item.id });
    }, COMMAND_TIMEOUT_MS);
    pending.set(item.id, item);
    if (device.waiter) {
      const waiter = device.waiter;
      device.waiter = null;
      clearTimeout(waiter.timer);
      device.lastSeen = now();
      waiter.resolve(item);
    } else {
      device.queue.push(item);
    }
  });
}
async function runCommand(action, payload = {}) {
  const device = currentDevice();
  if (!device) return { status: "WAITING_FOR_DEVICE", verified: false, reason: "Open the Android Bridge app, pair it, and turn the bridge ON." };
  const command = { id: randomUUID(), action, payload, issuedAt: new Date().toISOString() };
  return await enqueue(device, command);
}
function createMcpServer() {
  const server = new McpServer({ name: "ai-player-android-relay", version: "1.0.0" });

  server.tool("create_pairing_code",
    "Create a one-time 6-digit pairing code valid for 5 minutes. Enter it into the Android Bridge app on the user's phone. Do not claim the phone is paired until bridge_status reports it.",
    {},
    async () => {
      const code = issuePairCode();
      return toolResult({ status: "PAIRING_CODE_CREATED", code, expiresInSeconds: 300, instructions: "Enter this one-time code into the Android Bridge app on the user's phone, then turn the bridge ON." });
    });

  server.tool("bridge_status",
    "Report whether an Android Bridge phone has recently connected to this relay. This does not verify Chrome or Gemini unless a screen-read tool is used.",
    {},
    async () => {
      purgeExpired();
      const device = currentDevice();
      return toolResult({
        status: device ? "DEVICE_CONNECTED" : "WAITING_FOR_DEVICE",
        deviceId: device?.id || null,
        lastSeen: device ? new Date(device.lastSeen).toISOString() : null,
        pluginToRelay: "AUTHENTICATED_REQUEST_RECEIVED",
        phoneConnection: device ? "RECENT_POLL_RECEIVED" : "NOT_CONNECTED",
        note: "A recent device poll is not proof that Chrome/Gemini is open or that an action succeeded."
      });
    });

  server.tool("open_gemini",
    "Ask the paired Android phone to open the official Gemini site in Chrome. Reports whether the phone accepted the launch request; it cannot prove the page finished loading.",
    {},
    async () => toolResult(await runCommand("OPEN_GEMINI")));

  server.tool("read_gemini_screen",
    "Read visible screen text only when the Android accessibility service detects Chrome and a visible Gemini indicator. The indicator is a heuristic, not cryptographic proof of origin.",
    {},
    async () => toolResult(await runCommand("READ_GEMINI_SCREEN")));

  server.tool("click_gemini_text",
    "Click an exact visible text label only when the active accessibility window is Chrome and includes a visible Gemini indicator. Use only for low-risk user-authorized actions.",
    { text: z.string().min(1).max(160) },
    async ({ text }) => toolResult(await runCommand("CLICK_GEMINI_TEXT", { text })));

  server.tool("type_gemini_text",
    "Type text only into an already-focused editable field in a Chrome window with a visible Gemini indicator. It does not send the message automatically; the UI must expose that action and the user should verify the result.",
    { text: z.string().max(10000) },
    async ({ text }) => toolResult(await runCommand("TYPE_GEMINI_TEXT", { text })));

  server.tool("emergency_stop",
    "Immediately request the paired phone to stop bridge polling and disable automation. If the phone is offline, this cannot remotely stop it; use the physical Emergency Stop button in the app.",
    {},
    async () => toolResult(await runCommand("EMERGENCY_STOP")));

  return server;
}

function mcpHandler(req, res) {
  if (!relayConfigured()) {
    json(res, 503, { status: "CONFIGURATION_REQUIRED", reason: "Set OWNER_PIN and DEVICE_SIGNING_SECRET in the hosting dashboard." });
    return;
  }
  if (!validMcpRequest(req)) {
    res.writeHead(401, {
      "WWW-Authenticate": 'Bearer resource_metadata="' + PUBLIC_ORIGIN + '/.well-known/oauth-protected-resource", scope="' + OAUTH_SCOPE + '"',
      "Cache-Control": "no-store"
    }).end("Unauthorized");
    return;
  }
  (async () => {
    const server = createMcpServer();
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
      enableJsonResponse: true
    });
    res.on("close", () => {
      transport.close();
      server.close();
    });
    await server.connect(transport);
    await transport.handleRequest(req, res);
  })().catch((error) => {
    console.error("mcp_request_failed", error?.message || "unknown");
    if (!res.headersSent) json(res, 500, { error: "mcp_request_failed" });
  });
}

const httpServer = createServer(async (req, res) => {
  const url = new URL(req.url || "/", "http://" + (req.headers.host || "localhost"));

  if (req.method === "GET" && url.pathname === "/.well-known/oauth-protected-resource") {
    return json(res, 200, {
      resource: OAUTH_RESOURCE,
      authorization_servers: [PUBLIC_ORIGIN],
      scopes_supported: [OAUTH_SCOPE],
      resource_documentation: PUBLIC_ORIGIN + "/"
    });
  }
  if (req.method === "GET" && url.pathname === "/.well-known/oauth-authorization-server") {
    return json(res, 200, {
      issuer: PUBLIC_ORIGIN,
      authorization_endpoint: PUBLIC_ORIGIN + "/oauth/authorize",
      token_endpoint: PUBLIC_ORIGIN + "/oauth/token",
      registration_endpoint: PUBLIC_ORIGIN + "/oauth/register",
      response_types_supported: ["code"],
      grant_types_supported: ["authorization_code", "refresh_token"],
      token_endpoint_auth_methods_supported: ["none"],
      code_challenge_methods_supported: ["S256"],
      scopes_supported: [OAUTH_SCOPE]
    });
  }
  if (req.method === "POST" && url.pathname === "/oauth/register") {
    if (!relayConfigured()) return json(res, 503, { error: "server_error", error_description: "Relay owner PIN and device signing secret must be configured." });
    try {
      const body = await readJson(req);
      const redirectUris = [...new Set(Array.isArray(body.redirect_uris) ? body.redirect_uris : [])];
      if (redirectUris.length < 1 || redirectUris.length > 10 || !redirectUris.every(isAllowedRedirectUri)) {
        return json(res, 400, { error: "invalid_redirect_uri", error_description: "Only approved HTTPS ChatGPT callback URLs are accepted." });
      }
      const clientName = typeof body.client_name === "string" ? body.client_name.slice(0, 120) : "ChatGPT";
      const clientId = signOAuthPayload({
        client_name: clientName,
        redirect_uris: redirectUris,
        token_endpoint_auth_method: "none",
        created_at: now()
      }, "oauth-client");
      return json(res, 201, {
        client_id: clientId,
        client_name: clientName,
        redirect_uris: redirectUris,
        token_endpoint_auth_method: "none",
        grant_types: ["authorization_code", "refresh_token"],
        response_types: ["code"]
      });
    } catch (error) {
      return json(res, error.status || 400, { error: "invalid_client_metadata", error_description: error.message || "Invalid client registration." });
    }
  }
  if (req.method === "GET" && url.pathname === "/oauth/authorize") {
    if (!relayConfigured()) return html(res, 503, "<!doctype html><meta charset='utf-8'><title>Setup required</title><h1>Relay setup required</h1><p>The owner must configure OWNER_PIN and DEVICE_SIGNING_SECRET in the hosting dashboard before connecting ChatGPT.</p>");
    purgeExpired();
    const params = url.searchParams;
    const clientId = params.get("client_id") || "";
    const redirectUri = params.get("redirect_uri") || "";
    const client = validOAuthClient(clientId);
    const state = params.get("state") || "";
    const challenge = params.get("code_challenge") || "";
    const resource = params.get("resource") || OAUTH_RESOURCE;
    const scope = (params.get("scope") || OAUTH_SCOPE).split(" ").filter(Boolean);
    if (params.get("response_type") !== "code" || !client || !client.redirect_uris.includes(redirectUri)
        || !isAllowedRedirectUri(redirectUri) || !state || state.length > 1024
        || !/^[A-Za-z0-9_-]{43,128}$/.test(challenge)
        || params.get("code_challenge_method") !== "S256"
        || resource !== OAUTH_RESOURCE || !scope.includes(OAUTH_SCOPE)) {
      return html(res, 400, "<!doctype html><meta charset='utf-8'><title>Invalid request</title><h1>Authorization request rejected</h1><p>The OAuth client, redirect URI, resource, state, scope, or PKCE challenge was invalid.</p>");
    }
    const requestId = randomUUID() + randomUUID().replace(/-/g, "");
    oauthAuthorizeRequests.set(requestId, {
      clientId, redirectUri, state, challenge, resource,
      scope: OAUTH_SCOPE, expiresAt: now() + 5 * 60_000, attempts: 0
    });
    return html(res, 200, authorizePage(requestId));
  }
  if (req.method === "POST" && url.pathname === "/oauth/authorize") {
    if (!relayConfigured()) return html(res, 503, "<!doctype html><meta charset='utf-8'><title>Setup required</title><h1>Relay setup required</h1>");
    const ip = req.socket.remoteAddress || "unknown";
    if (tooManyOAuthAttempts(ip)) return html(res, 429, "<!doctype html><meta charset='utf-8'><title>Too many attempts</title><h1>Too many attempts</h1><p>Wait ten minutes, then try again.</p>");
    try {
      const form = await readForm(req);
      const requestId = form.get("request_id") || "";
      const suppliedPin = form.get("owner_pin") || "";
      const item = oauthAuthorizeRequests.get(requestId);
      if (!item || item.expiresAt <= now()) {
        oauthAuthorizeRequests.delete(requestId);
        return html(res, 400, "<!doctype html><meta charset='utf-8'><title>Request expired</title><h1>Authorization request expired</h1><p>Return to ChatGPT and try connecting again.</p>");
      }
      item.attempts++;
      if (!safeEqualText(suppliedPin, OWNER_PIN)) {
        if (item.attempts >= 5) oauthAuthorizeRequests.delete(requestId);
        return html(res, 401, authorizePage(requestId, item.attempts >= 5
          ? "Too many incorrect attempts. Restart the connection from ChatGPT."
          : "PIN did not match. Try again."));
      }
      oauthAuthorizeRequests.delete(requestId);
      const code = randomUUID() + "." + randomUUID().replace(/-/g, "");
      oauthCodes.set(code, { ...item, expiresAt: now() + 5 * 60_000, used: false });
      const redirect = new URL(item.redirectUri);
      redirect.searchParams.set("code", code);
      redirect.searchParams.set("state", item.state);
      res.writeHead(302, {
        "Location": redirect.toString(),
        "Cache-Control": "no-store",
        "Referrer-Policy": "no-referrer"
      }).end();
      return;
    } catch (error) {
      return html(res, error.status || 400, "<!doctype html><meta charset='utf-8'><title>Authorization failed</title><h1>Authorization failed</h1><p>Return to ChatGPT and retry.</p>");
    }
  }
  if (req.method === "POST" && url.pathname === "/oauth/token") {
    if (!relayConfigured()) return json(res, 503, { error: "server_error", error_description: "Relay is not configured." });
    try {
      const form = await readForm(req);
      const grantType = form.get("grant_type") || "";
      const clientId = form.get("client_id") || "";
      const client = validOAuthClient(clientId);
      if (!client) return json(res, 401, { error: "invalid_client" });

      if (grantType === "authorization_code") {
        const code = form.get("code") || "";
        const item = oauthCodes.get(code);
        oauthCodes.delete(code);
        if (!item || item.used || item.expiresAt <= now() || item.clientId !== clientId
            || form.get("redirect_uri") !== item.redirectUri
            || !client.redirect_uris.includes(item.redirectUri)) {
          return json(res, 400, { error: "invalid_grant", error_description: "Authorization code expired, reused, or mismatched." });
        }
        const verifier = form.get("code_verifier") || "";
        if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) return json(res, 400, { error: "invalid_grant" });
        const calculated = createHash("sha256").update(verifier).digest("base64url");
        if (!safeEqualText(calculated, item.challenge)) return json(res, 400, { error: "invalid_grant", error_description: "PKCE validation failed." });
        if (form.get("resource") && form.get("resource") !== item.resource) return json(res, 400, { error: "invalid_target" });
        return json(res, 200, issueMcpTokens(clientId, item.resource));
      }

      if (grantType === "refresh_token") {
        const refresh = verifyRefreshToken(form.get("refresh_token") || "");
        if (!refresh || refresh.client_id !== clientId || form.get("resource") && form.get("resource") !== refresh.aud) {
          return json(res, 400, { error: "invalid_grant", error_description: "Refresh token expired or mismatched." });
        }
        return json(res, 200, issueMcpTokens(clientId, refresh.aud));
      }

      return json(res, 400, { error: "unsupported_grant_type" });
    } catch (error) {
      return json(res, error.status || 400, { error: "invalid_request", error_description: error.message || "Invalid OAuth token request." });
    }
  }

  if (req.method === "GET" && url.pathname === "/") {
    return json(res, 200, { service: "ai-player-android-relay", status: relayConfigured() ? "ready" : "configuration_required", configured: relayConfigured(), mcp: "/mcp", auth: "OAuth 2.1 + PKCE", note: "Relay health only; no phone connection is implied." });
  }
  if (req.method === "GET" && url.pathname === "/health") {
    return json(res, 200, { status: relayConfigured() ? "ok" : "configuration_required", version: "1.1.0", configured: relayConfigured(), auth: "OAuth 2.1 + PKCE" });
  }
  if (url.pathname === "/mcp" && ["POST", "GET", "DELETE"].includes(req.method || "")) {
    if (req.method !== "POST") {
      res.writeHead(405, { Allow: "POST", "Cache-Control": "no-store" }).end();
      return;
    }
    return mcpHandler(req, res);
  }

  if (req.method === "POST" && url.pathname === "/device/pair") {
    if (!relayConfigured()) return json(res, 503, { status: "CONFIGURATION_REQUIRED", reason: "OWNER_PIN and DEVICE_SIGNING_SECRET are not configured by the owner." });
    const ip = req.socket.remoteAddress || "unknown";
    const attempt = pairAttempts.get(ip) || { count: 0, resetAt: now() + 60_000 };
    if (attempt.resetAt <= now()) { attempt.count = 0; attempt.resetAt = now() + 60_000; }
    attempt.count++;
    pairAttempts.set(ip, attempt);
    if (attempt.count > 12) return json(res, 429, { status: "BLOCKED", reason: "pair_rate_limited" });
    try {
      const body = await readJson(req);
      const code = String(body.code || "");
      const deviceId = String(body.deviceId || "");
      const stored = pairCodes.get(code);
      if (!/^\d{6}$/.test(code) || !stored || stored.expiresAt <= now()) {
        return json(res, 401, { status: "FAILED", reason: "invalid_or_expired_pairing_code" });
      }
      if (!/^[a-zA-Z0-9_-]{8,80}$/.test(deviceId)) return json(res, 400, { status: "FAILED", reason: "invalid_device_id" });
      pairCodes.delete(code);
      const exp = now() + 30 * 24 * 60 * 60_000;
      const token = signDeviceToken(deviceId, exp);
      const device = { id: deviceId, token, lastSeen: now(), queue: [], waiter: null };
      devices.set(deviceId, device);
      return json(res, 200, { status: "PAIRED", deviceId, token, expiresAt: new Date(exp).toISOString() });
    } catch (error) {
      return json(res, error.status || 400, { status: "FAILED", reason: error.message || "bad_request" });
    }
  }

  if (req.method === "GET" && url.pathname === "/device/next") {
    const device = getDeviceFromRequest(req);
    if (!device) return json(res, 401, { status: "BLOCKED", reason: "invalid_device_token" });
    if (device.queue.length) {
      const command = device.queue.shift();
      return json(res, 200, { status: "COMMAND", command: { id: command.id, action: command.action, payload: command.payload } });
    }
    const wait = Math.min(Math.max(Number(url.searchParams.get("wait") || 20), 1), 25);
    const result = await new Promise((resolve) => {
      const existing = device.waiter;
      if (existing) {
        clearTimeout(existing.timer);
        existing.resolve(null);
      }
      const waiter = {
        resolve,
        timer: setTimeout(() => {
          if (device.waiter === waiter) device.waiter = null;
          resolve(null);
        }, wait * 1000)
      };
      device.waiter = waiter;
    });
    device.lastSeen = now();
    if (!result) return json(res, 200, { status: "IDLE" });
    return json(res, 200, { status: "COMMAND", command: { id: result.id, action: result.action, payload: result.payload } });
  }

  if (req.method === "POST" && url.pathname === "/device/result") {
    const device = getDeviceFromRequest(req);
    if (!device) return json(res, 401, { status: "BLOCKED", reason: "invalid_device_token" });
    try {
      const body = await readJson(req);
      const commandId = String(body.commandId || "");
      const item = pending.get(commandId);
      if (!item || item.deviceId !== device.id) return json(res, 404, { status: "FAILED", reason: "unknown_command" });
      const result = body.result && typeof body.result === "object" ? body.result : {};
      const safe = {
        status: typeof result.status === "string" ? result.status.slice(0, 64) : "FAILED",
        verified: result.verified === true,
        reason: typeof result.reason === "string" ? result.reason.slice(0, 400) : undefined,
        text: typeof result.text === "string" ? result.text.slice(0, 12000) : undefined,
        action: typeof result.action === "string" ? result.action.slice(0, 80) : undefined
      };
      clearTimeout(item.timer);
      pending.delete(commandId);
      item.resolve({ ...safe, commandId });
      return json(res, 200, { status: "RESULT_RECEIVED" });
    } catch (error) {
      return json(res, error.status || 400, { status: "FAILED", reason: error.message || "bad_request" });
    }
  }

  if (req.method === "OPTIONS") {
    res.writeHead(204, { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "POST, GET, OPTIONS", "Access-Control-Allow-Headers": "content-type, authorization" }).end();
    return;
  }
  json(res, 404, { error: "not_found" });
});

if (!relayConfigured()) {
  console.warn("Relay starts in locked configuration mode; configure OWNER_PIN and DEVICE_SIGNING_SECRET in the hosting dashboard before pairing or MCP use.");
}

httpServer.listen(PORT, "0.0.0.0", () => {
  console.log("AI Player Relay listening on port " + PORT);
});
