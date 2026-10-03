package com.biruk.androidbridge;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import android.os.Handler;
import android.os.Looper;

public final class BridgeServer {
    private static volatile ServerSocket server;
    private static volatile Thread thread;

    static synchronized void startIfNeeded() {
        if (!MainActivity.enabled || server != null) return;
        thread = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(8765, 20, InetAddress.getByName("127.0.0.1"));
                server = ss;
                while (MainActivity.enabled && !ss.isClosed()) {
                    try { handle(ss.accept()); } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {
            } finally {
                stop();
            }
        }, "bridge-server");
        thread.start();
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
    }

    private static void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            String line = r.readLine();
            if (line == null) return;
            String[] p = line.split(" ");
            if (p.length < 2) return;
            String path = p[1];
            int len = 0;
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                if (h.toLowerCase().startsWith("content-length:")) {
                    try { len = Integer.parseInt(h.substring(h.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
                }
            }
            char[] body = new char[len];
            if (len > 0) r.read(body);
            String json = new String(body);
            String response;
            if (!MainActivity.enabled) {
                response = "{\"status\":\"BLOCKED\",\"reason\":\"bridge_off\"}";
            } else if (!authorized(json)) {
                response = "{\"status\":\"BLOCKED\",\"reason\":\"bad_pairing_token\"}";
            } else {
                response = dispatch(path, json);
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) {}
    }

    private static boolean authorized(String json) {
        return json.contains("\"token\":\"" + MainActivity.token + "\"");
    }

    private static String dispatch(String path, String json) {
        if (path.equals("/status")) return "{\"status\":\"READY\",\"accessibility\":" + (BridgeAccessibilityService.instance != null) + "}";
        if (path.equals("/open-url")) {
            String url = value(json, "url");
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return "{\"status\":\"FAILED\",\"reason\":\"invalid_url\"}";
            new Handler(Looper.getMainLooper()).post(() -> MainActivity.openUrl(url));
            return "{\"status\":\"SUCCEEDED\",\"action\":\"OPEN_URL\"}";
        }
        if (path.equals("/read-screen")) return "{\"status\":\"SUCCEEDED\",\"text\":\"" + esc(BridgeAccessibilityService.readScreenText()) + "\"}";
        if (path.equals("/click-text")) {
            String text = value(json, "text");
            return "{\"status\":\"SUCCEEDED\",\"clicked\":" + BridgeAccessibilityService.clickText(text == null ? "" : text) + "}";
        }
        if (path.equals("/type")) {
            String value = value(json, "value");
            return "{\"status\":\"SUCCEEDED\",\"typed\":" + BridgeAccessibilityService.typeText(value == null ? "" : value) + "}";
        }
        return "{\"status\":\"FAILED\",\"reason\":\"unknown_command\"}";
    }

    private static String value(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int a = json.indexOf(marker);
        if (a < 0) return null;
        a += marker.length();
        int b = json.indexOf("\"", a);
        if (b < 0) return null;
        return json.substring(a, b);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", "\\n");
    }
}
