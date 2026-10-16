package com.biruk.androidbridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class RelayClient {
    private static final String PREFS = "bridge_private_prefs";
    private static final String KEY_DEVICE_ID = "relay_device_id";
    private static final String KEY_DEVICE_TOKEN = "relay_device_token";
    private static final String KEY_RELAY_URL = "relay_url";
    private static final String KEY_PAIR_CODE = "relay_pair_code";
    private static final String KEY_RELAY_STATUS = "relay_status";
    private static volatile boolean stopRequested = false;
    private static volatile Thread worker;

    private RelayClient() {}

    public static synchronized void start(Context context) {
        if (worker != null && worker.isAlive()) return;
        stopRequested = false;
        Context app = context.getApplicationContext();
        worker = new Thread(() -> runLoop(app), "android-bridge-relay-client");
        worker.start();
    }

    public static synchronized void stop() {
        stopRequested = true;
        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;
    }

    public static String getStatus(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RELAY_STATUS, "Not connected");
    }

    private static void setStatus(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RELAY_STATUS, value).apply();
    }

    private static void runLoop(Context context) {
        boolean stopServiceOnExit = false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String baseUrl = normalizeBaseUrl(prefs.getString(KEY_RELAY_URL, ""));
            if (baseUrl == null) {
                setStatus(context, "Relay URL is invalid. Use a public HTTPS URL.");
                stopServiceOnExit = true;
                return;
            }

            String deviceId = prefs.getString(KEY_DEVICE_ID, null);
            if (deviceId == null || deviceId.length() < 8) {
                deviceId = UUID.randomUUID().toString();
                prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
            }

            String deviceToken = prefs.getString(KEY_DEVICE_TOKEN, null);
            if (deviceToken == null || deviceToken.isEmpty()) {
                String code = prefs.getString(KEY_PAIR_CODE, "");
                if (code == null || !code.matches("\\d{6}")) {
                    setStatus(context, "Waiting for a 6-digit pairing code from the ai-player plugin.");
                    stopServiceOnExit = true;
                    return;
                }
                setStatus(context, "Pairing with relay…");
                JSONObject body = new JSONObject();
                body.put("code", code);
                body.put("deviceId", deviceId);
                JSONObject paired = requestJson(baseUrl + "/device/pair", "POST", body, null, 10000);
                if (!"PAIRED".equals(paired.optString("status")) || paired.optString("token").isEmpty()) {
                    setStatus(context, "Pairing failed: " + paired.optString("reason", "check the code and try again"));
                    prefs.edit().remove(KEY_PAIR_CODE).apply();
                    stopServiceOnExit = true;
                    return;
                }
                deviceToken = paired.getString("token");
                prefs.edit().putString(KEY_DEVICE_TOKEN, deviceToken).remove(KEY_PAIR_CODE).apply();
                setStatus(context, "Paired. Connecting to relay…");
            }

            while (!stopRequested && !Thread.currentThread().isInterrupted()) {
                try {
                    JSONObject response = requestJson(baseUrl + "/device/next?wait=20", "GET", null, deviceToken, 28000);
                    String status = response.optString("status");
                    if ("COMMAND".equals(status)) {
                        JSONObject command = response.optJSONObject("command");
                        if (command == null) continue;
                        String action = command.optString("action");
                        String commandId = command.optString("id");
                        JSONObject result = MainActivity.executeRelayCommand(context, command);
                        JSONObject report = new JSONObject();
                        report.put("commandId", commandId);
                        report.put("result", result);
                        try {
                            requestJson(baseUrl + "/device/result", "POST", report, deviceToken, 10000);
                            setStatus(context, "Connected. Last action: " + action + " → " + result.optString("status"));
                        } catch (Exception e) {
                            setStatus(context, "Could not send the action result to relay.");
                        }
                        if ("EMERGENCY_STOP".equals(action)) {
                            stopRequested = true;
                            stopServiceOnExit = true;
                        }
                    } else if ("IDLE".equals(status)) {
                        setStatus(context, "Connected. Waiting for a command.");
                    } else {
                        setStatus(context, "Relay returned an unexpected status.");
                        SystemClock.sleep(2000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (stopRequested) break;
                    setStatus(context, "Connection problem. Retrying automatically.");
                    SystemClock.sleep(4000);
                }
            }
        } catch (Exception e) {
            setStatus(context, "Relay setup failed. Check the URL and pairing code.");
            stopServiceOnExit = true;
        } finally {
            synchronized (RelayClient.class) {
                if (worker == Thread.currentThread()) worker = null;
            }
            if (stopServiceOnExit && !stopRequested) {
                context.stopService(new Intent(context, BridgeRelayService.class));
            } else if (stopRequested) {
                context.stopService(new Intent(context, BridgeRelayService.class));
            }
        }
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String candidate = value.trim();
        try {
            URL url = new URL(candidate);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
                    || url.getPort() != -1 && url.getPort() != 443) return null;
            if (url.getPath() != null && !url.getPath().isEmpty() && !"/".equals(url.getPath())) return null;
            return "https://" + url.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject requestJson(String address, String method, JSONObject body,
                                          String token, int timeoutMs) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] responseBytes = readLimited(stream, 32768);
            String responseText = new String(responseBytes, StandardCharsets.UTF_8);
            JSONObject response;
            try { response = new JSONObject(responseText); }
            catch (Exception e) { throw new Exception("HTTP " + code + " response was not JSON"); }
            if (code >= 400) throw new Exception("HTTP " + code + ": " + response.optString("reason", "request_failed"));
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream stream, int limit) throws Exception {
        if (stream == null) return new byte[0];
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new Exception("response_too_large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
