import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, createHmac, randomBytes } from "node:crypto";

const port = 18000 + (process.pid % 20000);
const origin = `http://127.0.0.1:${port}`;
const ownerPin = "ci-test-owner-pin-7d52eacf";
const deviceSecret = "ci-device-secret-" + "d".repeat(48);
const child = spawn(process.execPath, ["relay/server.js"], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    PORT: String(port),
    PUBLIC_ORIGIN: origin,
    OWNER_PIN: ownerPin,
    DEVICE_SIGNING_SECRET: deviceSecret
  },
  stdio: ["ignore", "pipe", "pipe"]
});
let childLogs = "";
child.stdout.on("data", chunk => { childLogs += chunk.toString(); });
child.stderr.on("data", chunk => { childLogs += chunk.toString(); });

async function waitForServer() {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(origin + "/health");
      if (r.ok) return;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Relay server did not start: " + childLogs);
}

async function postForm(path, values) {
  return await fetch(origin + path, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(values),
    redirect: "manual"
  });
}
async function mcp(token, request) {
  const response = await fetch(origin + "/mcp", {
    method: "POST",
    headers: {
      "authorization": "Bearer " + token,
      "content-type": "application/json",
      "accept": "application/json, text/event-stream"
    },
    body: JSON.stringify(request)
  });
  const body = await response.text();
  if (!response.ok) throw new Error("MCP HTTP " + response.status + ": " + body);
  try { return JSON.parse(body); }
  catch { throw new Error("MCP response was not JSON: " + body.slice(0, 500)); }
}

try {
  await waitForServer();
  const health = await (await fetch(origin + "/health")).json();
  assert.equal(health.configured, true);

  const protectedMetadata = await (await fetch(origin + "/.well-known/oauth-protected-resource")).json();
  assert.equal(protectedMetadata.resource, origin);
  assert.deepEqual(protectedMetadata.authorization_servers, [origin]);

  const authMetadata = await (await fetch(origin + "/.well-known/oauth-authorization-server")).json();
  assert.equal(authMetadata.authorization_endpoint, origin + "/oauth/authorize");
  assert.equal(authMetadata.token_endpoint_auth_methods_supported.includes("none"), true);

  const redirectUri = "https://chatgpt.com/connector/oauth/ci-test";
  const register = await fetch(origin + "/oauth/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      client_name: "AI Player CI smoke test",
      redirect_uris: [redirectUri],
      token_endpoint_auth_method: "none",
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"]
    })
  });
  assert.equal(register.status, 201);
  const client = await register.json();
  assert.ok(client.client_id);

  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  const state = randomBytes(18).toString("base64url");
  const authUrl = new URL(origin + "/oauth/authorize");
  authUrl.searchParams.set("response_type", "code");
  authUrl.searchParams.set("client_id", client.client_id);
  authUrl.searchParams.set("redirect_uri", redirectUri);
  authUrl.searchParams.set("state", state);
  authUrl.searchParams.set("code_challenge", challenge);
  authUrl.searchParams.set("code_challenge_method", "S256");
  authUrl.searchParams.set("resource", origin);
  authUrl.searchParams.set("scope", "bridge:control");

  const authPage = await fetch(authUrl);
  assert.equal(authPage.status, 200);
  const html = await authPage.text();
  const requestIdMatch = html.match(/name="request_id" value="([^"]+)"/);
  assert.ok(requestIdMatch, "OAuth page should include a short-lived request handle");

  const approve = await postForm("/oauth/authorize", {
    request_id: requestIdMatch[1],
    owner_pin: ownerPin
  });
  assert.equal(approve.status, 302);
  const callback = new URL(approve.headers.get("location"));
  assert.equal(callback.origin + callback.pathname, redirectUri);
  assert.equal(callback.searchParams.get("state"), state);
  const code = callback.searchParams.get("code");
  assert.ok(code);

  const tokenResponse = await postForm("/oauth/token", {
    grant_type: "authorization_code",
    code,
    client_id: client.client_id,
    redirect_uri: redirectUri,
    code_verifier: verifier,
    resource: origin
  });
  assert.equal(tokenResponse.status, 200);
  const tokens = await tokenResponse.json();
  assert.ok(tokens.access_token);
  assert.ok(tokens.refresh_token);

  const expiredDevicePayload = Buffer.from(JSON.stringify({
    sub: "expired-device",
    exp: Date.now() - 1000
  })).toString("base64url");
  const expiredDeviceSignature = createHmac("sha256", deviceSecret)
    .update(expiredDevicePayload).digest("base64url");
  const expiredDeviceToken = expiredDevicePayload + "." + expiredDeviceSignature;
  const expiredDeviceResponse = await fetch(origin + "/device/next?wait=1", {
    headers: { authorization: "Bearer " + expiredDeviceToken }
  });
  assert.equal(expiredDeviceResponse.status, 401, "expired device tokens must be rejected");

  const unauthenticated = await fetch(origin + "/mcp", {
    method: "POST",
    headers: { "content-type": "application/json", "accept": "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 9, method: "initialize", params: {
      protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "smoke", version: "1.0" }
    }})
  });
  assert.equal(unauthenticated.status, 401);
  assert.match(unauthenticated.headers.get("www-authenticate") || "", /oauth-protected-resource/);

  const initialized = await mcp(tokens.access_token, {
    jsonrpc: "2.0", id: 1, method: "initialize",
    params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "relay-ci-smoke", version: "1.0.0" } }
  });
  assert.equal(initialized.result.serverInfo.name, "ai-player-android-relay");

  const tools = await mcp(tokens.access_token, { jsonrpc: "2.0", id: 2, method: "tools/list" });
  const names = tools.result.tools.map(tool => tool.name);
  assert.ok(names.includes("create_pairing_code"));
  assert.ok(names.includes("open_gemini"));
  assert.ok(names.includes("emergency_stop"));

  const codeTool = await mcp(tokens.access_token, {
    jsonrpc: "2.0", id: 3, method: "tools/call",
    params: { name: "create_pairing_code", arguments: {} }
  });
  const pairing = JSON.parse(codeTool.result.content[0].text);
  assert.equal(pairing.status, "PAIRING_CODE_CREATED");

  const deviceId = "ci-device-" + randomBytes(4).toString("hex");
  const pairingResponse = await fetch(origin + "/device/pair", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: pairing.code, deviceId })
  });
  assert.equal(pairingResponse.status, 200);
  const device = await pairingResponse.json();
  assert.equal(device.status, "PAIRED");

  const pollPromise = fetch(origin + "/device/next?wait=5", {
    headers: { authorization: "Bearer " + device.token }
  });
  await new Promise(resolve => setTimeout(resolve, 100));
  const commandPromise = mcp(tokens.access_token, {
    jsonrpc: "2.0", id: 4, method: "tools/call",
    params: { name: "open_gemini", arguments: {} }
  });
  const poll = await pollPromise;
  assert.equal(poll.status, 200);
  const commandEnvelope = await poll.json();
  assert.equal(commandEnvelope.status, "COMMAND");
  assert.equal(commandEnvelope.command.action, "OPEN_GEMINI");

  const resultResponse = await fetch(origin + "/device/result", {
    method: "POST",
    headers: {
      "authorization": "Bearer " + device.token,
      "content-type": "application/json"
    },
    body: JSON.stringify({
      commandId: commandEnvelope.command.id,
      result: { status: "ACCEPTED", verified: false, reason: "test result" }
    })
  });
  assert.equal(resultResponse.status, 200);
  const commandResult = await commandPromise;
  const output = JSON.parse(commandResult.result.content[0].text);
  assert.equal(output.status, "ACCEPTED");
  assert.equal(output.verified, false);

  const refreshResponse = await postForm("/oauth/token", {
    grant_type: "refresh_token",
    refresh_token: tokens.refresh_token,
    client_id: client.client_id,
    resource: origin
  });
  assert.equal(refreshResponse.status, 200);
  const refreshed = await refreshResponse.json();
  assert.ok(refreshed.access_token);

  const reusedCode = await postForm("/oauth/token", {
    grant_type: "authorization_code",
    code,
    client_id: client.client_id,
    redirect_uri: redirectUri,
    code_verifier: verifier,
    resource: origin
  });
  assert.equal(reusedCode.status, 400);
  console.log("Relay smoke test passed: OAuth PKCE, authenticated MCP, phone pairing, command polling, result reporting, and replay rejection.");
} catch (error) {
  console.error(error);
  console.error(childLogs);
  process.exitCode = 1;
} finally {
  child.kill("SIGTERM");
}
