package com.biruk.androidbridge;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class BridgeServer {
    private static final int PORT = 8765;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 32768;
    private static volatile ServerSocket server;
    private static volatile Thread serverThread;

    private BridgeServer() {}

    static synchronized boolean isRunning() {
        return server != null && server.isBound() && !server.isClosed();
    }

    static synchronized void startIfNeeded() {
        if (!MainActivity.enabled || isRunning()
                || serverThread != null && serverThread.isAlive()) return;
        serverThread = new Thread(BridgeServer::runServer, "android-bridge-server");
        serverThread.start();
    }

    private static void runServer() {
        ServerSocket local = null;
        try {
            local = new ServerSocket();
            local.setReuseAddress(true);
            local.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 10);
            synchronized (BridgeServer.class) {
                if (!MainActivity.enabled) {
                    local.close();
                    return;
                }
                server = local;
            }
            while (MainActivity.enabled && !local.isClosed()) {
                try {
                    Socket client = local.accept();
                    client.setSoTimeout(5000);
                    handle(client);
                } catch (IOException e) {
                    if (MainActivity.enabled && !local.isClosed()) {
                        // Accept loop remains available after a transient client error.
                    }
                }
            }
        } catch (IOException ignored) {
            // A bind failure is reflected by isRunning() remaining false.
        } finally {
            try { if (local != null) local.close(); } catch (IOException ignored) {}
            synchronized (BridgeServer.class) {
                if (server == local) server = null;
                if (serverThread == Thread.currentThread()) serverThread = null;
            }
        }
    }

    static synchronized void stop() {
        MainActivity.enabled = false;
        ServerSocket current = server;
        server = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) {}
        }
    }

    private static void handle(Socket socket) {
        try (Socket client = socket) {
            Request request;
            try {
                request = readRequest(client.getInputStream());
            } catch (HttpException e) {
                writeJson(client.getOutputStream(), e.statusCode, error("FAILED", e.getMessage()));
                return;
            }

            if ("GET".equals(request.method) && "/health".equals(request.path)) {
                JSONObject response = new JSONObject();
                put(response, "status", "LOCAL_SERVER_AVAILABLE");
                put(response, "bridgeEnabled", MainActivity.enabled);
                put(response, "note", "This only verifies the local phone endpoint, not plugin connectivity.");
                writeJson(client.getOutputStream(), 200, response);
                return;
            }

            if (!"POST".equals(request.method)) {
                writeJson(client.getOutputStream(), 405, error("FAILED", "post_required"));
                return;
            }
            if (!MainActivity.enabled) {
                writeJson(client.getOutputStream(), 503, error("BLOCKED", "bridge_off"));
                return;
            }

            JSONObject body;
            try {
                body = request.body.isEmpty() ? new JSONObject() : new JSONObject(request.body);
            } catch (JSONException e) {
                writeJson(client.getOutputStream(), 400, error("FAILED", "invalid_json"));
                return;
            }

            if (!authorized(body)) {
                writeJson(client.getOutputStream(), 401, error("BLOCKED", "invalid_pairing_token"));
                return;
            }

            dispatch(request.path, body, client.getOutputStream());
        } catch (IOException ignored) {
            // A disconnected client must not crash the bridge.
        }
    }

    private static Request readRequest(InputStream input) throws IOException, HttpException {
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        int state = 0;
        while (headerBuffer.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new HttpException(400, "incomplete_headers");
            headerBuffer.write(value);
            if (state == 0 && value == '\r') state = 1;
            else if (state == 1 && value == '\n') state = 2;
            else if (state == 2 && value == '\r') state = 3;
            else if (state == 3 && value == '\n') break;
            else state = value == '\r' ? 1 : 0;
        }
        byte[] rawHeaders = headerBuffer.toByteArray();
        if (rawHeaders.length >= MAX_HEADER_BYTES
                && !(rawHeaders.length >= 4
                && rawHeaders[rawHeaders.length - 4] == '\r'
                && rawHeaders[rawHeaders.length - 3] == '\n'
                && rawHeaders[rawHeaders.length - 2] == '\r'
                && rawHeaders[rawHeaders.length - 1] == '\n')) {
            throw new HttpException(431, "headers_too_large");
        }
        String headerText = new String(rawHeaders, StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) throw new HttpException(400, "missing_request_line");
        String[] requestLine = lines[0].trim().split(" +");
        if (requestLine.length < 2) throw new HttpException(400, "malformed_request_line");

        String method = requestLine[0].toUpperCase(Locale.ROOT);
        String target = requestLine[1];
        int queryIndex = target.indexOf('?');
        String path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        if (!path.startsWith("/")) throw new HttpException(400, "invalid_path");

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new HttpException(400, "transfer_encoding_not_supported");
        }
        int contentLength = 0;
        String contentLengthHeader = headers.get("content-length");
        if (contentLengthHeader != null) {
            try {
                contentLength = Integer.parseInt(contentLengthHeader);
            } catch (NumberFormatException e) {
                throw new HttpException(400, "invalid_content_length");
            }
        }
        if (contentLength < 0) throw new HttpException(400, "invalid_content_length");
        if (contentLength > MAX_BODY_BYTES) throw new HttpException(413, "body_too_large");

        byte[] rawBody = new byte[contentLength];
        int offset = 0;
        while (offset < rawBody.length) {
            int count = input.read(rawBody, offset, rawBody.length - offset);
            if (count < 0) throw new HttpException(400, "incomplete_body");
            offset += count;
        }
        return new Request(method, path, new String(rawBody, StandardCharsets.UTF_8));
    }

    private static boolean authorized(JSONObject body) {
        String expected = MainActivity.token;
        String supplied = body.optString("token", "");
        if (expected == null || expected.isEmpty() || supplied.isEmpty()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static void dispatch(String path, JSONObject body, OutputStream output) throws IOException {
        try {
            JSONObject response;
            int httpCode = 200;
            switch (path) {
                case "/status":
                    response = new JSONObject();
                    put(response, "status", "READY");
                    put(response, "bridgeEnabled", MainActivity.enabled);
                    put(response, "serverListening", isRunning());
                    put(response, "accessibilityConnected", BridgeAccessibilityService.instance != null);
                    put(response, "pluginConnected", false);
                    put(response, "note", "The plugin connection is not provided by the local server.");
                    break;
                case "/open-url": {
                    String url = body.optString("url", "");
                    if (!isAllowedGeminiUrl(url)) {
                        response = error("BLOCKED", "only_https_gemini_google_com_is_allowed");
                        httpCode = 403;
                        break;
                    }
                    Boolean opened = callOnMain(() -> MainActivity.openUrl(url));
                    response = new JSONObject();
                    put(response, "status", Boolean.TRUE.equals(opened) ? "ACCEPTED" : "FAILED");
                    put(response, "action", "OPEN_URL");
                    put(response, "verified", false);
                    put(response, "reason", Boolean.TRUE.equals(opened)
                            ? "Chrome launch requested; verify the actual page before continuing."
                            : "chrome_not_available_or_launch_failed");
                    if (!Boolean.TRUE.equals(opened)) httpCode = 500;
                    break;
                }
                case "/read-screen": {
                    String text = callOnMain(BridgeAccessibilityService::readScreenText);
                    if (text == null) {
                        response = error("BLOCKED", "accessibility_not_connected_or_not_gemini_in_chrome");
                        httpCode = 403;
                    } else {
                        response = new JSONObject();
                        put(response, "status", "SUCCEEDED");
                        put(response, "text", text);
                        put(response, "verified", true);
                    }
                    break;
                }
                case "/click-text": {
                    String text = body.optString("text", "");
                    if (text.isEmpty() || text.length() > 160) {
                        response = error("FAILED", "text_missing_or_too_long");
                        httpCode = 400;
                        break;
                    }
                    Boolean clicked = callOnMain(() -> BridgeAccessibilityService.clickText(text));
                    response = new JSONObject();
                    put(response, "status", Boolean.TRUE.equals(clicked) ? "ACCEPTED" : "FAILED");
                    put(response, "clicked", Boolean.TRUE.equals(clicked));
                    put(response, "verified", false);
                    put(response, "reason", Boolean.TRUE.equals(clicked)
                            ? "Android accepted the click request; read the screen to verify its effect."
                            : "gemini_chrome_required_or_text_not_clickable");
                    if (!Boolean.TRUE.equals(clicked)) httpCode = 409;
                    break;
                }
                case "/type": {
                    String value = body.optString("value", "");
                    if (value.length() > 10000) {
                        response = error("FAILED", "text_too_large");
                        httpCode = 413;
                        break;
                    }
                    Boolean typed = callOnMain(() -> BridgeAccessibilityService.typeText(value));
                    response = new JSONObject();
                    put(response, "status", Boolean.TRUE.equals(typed) ? "ACCEPTED" : "FAILED");
                    put(response, "typed", Boolean.TRUE.equals(typed));
                    put(response, "verified", false);
                    put(response, "reason", Boolean.TRUE.equals(typed)
                            ? "Android accepted the text-entry request; the text was not sent. Read the screen to verify."
                            : "gemini_chrome_required_and_editable_field_must_be_focused");
                    if (!Boolean.TRUE.equals(typed)) httpCode = 409;
                    break;
                }
                default:
                    response = error("FAILED", "unknown_command");
                    httpCode = 404;
            }
            writeJson(output, httpCode, response);
        } catch (Exception e) {
            writeJson(output, 500, error("FAILED", "internal_error"));
        }
    }

    private static boolean isAllowedGeminiUrl(String value) {
        try {
            android.net.Uri uri = android.net.Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "gemini.google.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> T callOnMain(Callable<T> callable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return callable.call(); } catch (Exception e) { return null; }
        }
        FutureTask<T> task = new FutureTask<>(callable);
        new Handler(Looper.getMainLooper()).post(task);
        try {
            return task.get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            task.cancel(true);
            return null;
        }
    }

    private static JSONObject error(String status, String reason) {
        JSONObject object = new JSONObject();
        put(object, "status", status);
        put(object, "reason", reason);
        put(object, "verified", false);
        return object;
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException ignored) {}
    }

    private static void writeJson(OutputStream output, int code, JSONObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        String label;
        switch (code) {
            case 200: label = "OK"; break;
            case 400: label = "Bad Request"; break;
            case 401: label = "Unauthorized"; break;
            case 403: label = "Forbidden"; break;
            case 404: label = "Not Found"; break;
            case 405: label = "Method Not Allowed"; break;
            case 409: label = "Conflict"; break;
            case 413: label = "Payload Too Large"; break;
            case 431: label = "Request Header Fields Too Large"; break;
            case 500: label = "Internal Server Error"; break;
            case 503: label = "Service Unavailable"; break;
            default: label = "Error";
        }
        String headers = "HTTP/1.1 " + code + " " + label + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static final class Request {
        final String method;
        final String path;
        final String body;
        Request(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }

    private static final class HttpException extends Exception {
        final int statusCode;
        HttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
