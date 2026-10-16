import { createServer } from "node:http";
import { randomInt, randomUUID, createHmac, timingSafeEqual } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const PORT = Number(process.env.PORT || 8787);
const MCP_API_KEY = process.env.MCP_API_KEY || "";
const DEVICE_SIGNING_SECRET = process.env.DEVICE_SIGNING_SECRET || "";
const MAX_BODY = 24 * 1024;
const DEVICE_ONLINE_MS = 75_000;
const PAIR_CODE_TTL_MS = 5 * 60_000;
const COMMAND_TIMEOUT_MS = 28_000;

const pairCodes = new Map();
const devices = new Map();
const pending = new Map();
const pairAttempts = new Map();

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
  return MCP_API_KEY.length >= 32 && DEVICE_SIGNING_SECRET.length >= 32;
}
function mcpAuthorized(req) {
  return relayConfigured() && safeEqualText(bearer(req), MCP_API_KEY);
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
    json(res, 503, { status: "CONFIGURATION_REQUIRED", reason: "Set MCP_API_KEY and DEVICE_SIGNING_SECRET in the hosting dashboard." });
    return;
  }
  if (!mcpAuthorized(req)) {
    res.writeHead(401, {
      "WWW-Authenticate": 'Bearer realm="ai-player-relay"',
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

  if (req.method === "GET" && url.pathname === "/") {
    return json(res, 200, { service: "ai-player-android-relay", status: relayConfigured() ? "ready" : "configuration_required", configured: relayConfigured(), mcp: "/mcp", note: "Relay health only; no phone connection is implied." });
  }
  if (req.method === "GET" && url.pathname === "/health") {
    return json(res, 200, { status: relayConfigured() ? "ok" : "configuration_required", version: "1.0.0", configured: relayConfigured() });
  }
  if (url.pathname === "/mcp" && ["POST", "GET", "DELETE"].includes(req.method || "")) {
    if (req.method !== "POST") {
      res.writeHead(405, { Allow: "POST", "Cache-Control": "no-store" }).end();
      return;
    }
    return mcpHandler(req, res);
  }

  if (req.method === "POST" && url.pathname === "/device/pair") {
    if (!relayConfigured()) return json(res, 503, { status: "CONFIGURATION_REQUIRED", reason: "Relay secrets are not configured by the owner." });
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
  console.warn("Relay starts in locked configuration mode; configure secrets in the hosting dashboard before pairing or MCP use.");
}

httpServer.listen(PORT, "0.0.0.0", () => {
  console.log("AI Player Relay listening on port " + PORT);
});
