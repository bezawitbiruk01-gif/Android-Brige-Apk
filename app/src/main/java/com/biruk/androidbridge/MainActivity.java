package com.biruk.androidbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;

public class MainActivity extends Activity {
    static volatile boolean enabled = false;
    static volatile String token;
    private TextView status;
    private Button toggle;
    private static final String PREFS = "bridge_private_prefs";
    private static final String KEY_TOKEN = "pairing_token";
    private static final String GEMINI_URL = "https://gemini.google.com/";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = getOrCreateToken(this);
        buildUi();
        refreshStatus("Bridge is OFF by default.");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Android Bridge");
        title.setTextSize(26);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextSize(15);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = pad / 2;
        statusParams.bottomMargin = pad / 2;
        root.addView(status, statusParams);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (enabled) {
                enabled = false;
                BridgeServer.stop();
                refreshStatus("Bridge stopped.");
            } else {
                enabled = true;
                BridgeServer.startIfNeeded();
                refreshStatus("Starting local bridge. This is not a plugin connection.");
            }
        });
        root.addView(toggle);

        Button openGemini = new Button(this);
        openGemini.setText("Open Gemini in Chrome");
        openGemini.setOnClickListener(v -> {
            boolean opened = openUrl(GEMINI_URL);
            refreshStatus(opened
                    ? "Chrome was asked to open Gemini. Check the phone screen to verify."
                    : "Could not open Gemini in Chrome. Check that Chrome is installed.");
        });
        root.addView(openGemini);

        Button accessibility = new Button(this);
        accessibility.setText("Accessibility settings");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button copyToken = new Button(this);
        copyToken.setText("Copy pairing token");
        copyToken.setOnClickListener(v -> {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Android Bridge pairing token", token));
                Toast.makeText(this, "Pairing token copied. Keep it private.", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copyToken);

        Button emergency = new Button(this);
        emergency.setText("EMERGENCY STOP");
        emergency.setOnClickListener(v -> {
            enabled = false;
            BridgeServer.stop();
            refreshStatus("EMERGENCY STOP: all bridge commands are blocked.");
        });
        root.addView(emergency);

        setContentView(root);
        updateToggleText();
    }

    private void updateToggleText() {
        if (toggle != null) toggle.setText(enabled ? "Turn Bridge OFF" : "Turn Bridge ON");
    }

    private void refreshStatus(String message) {
        if (status != null) {
            status.setText(message
                    + "\n\nBridge enabled: " + enabled
                    + "\nLocal endpoint: 127.0.0.1:8765"
                    + "\nServer listening: " + BridgeServer.isRunning()
                    + "\nAccessibility connected: " + (BridgeAccessibilityService.instance != null)
                    + "\nPairing token: " + token
                    + "\nAllowed URL launch: https://gemini.google.com/"
                    + "\nScreen actions require Chrome and a visible Gemini indicator."
                    + "\nThe local endpoint is NOT directly reachable by the ChatGPT plugin.");
        }
        updateToggleText();
    }

    private static String getOrCreateToken(Context context) {
        String existing = context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, null);
        if (existing != null && existing.length() >= 32) return existing;
        String created = makeToken();
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TOKEN, created).apply();
        return created;
    }

    static String makeToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    static boolean openUrl(String value) {
        Activity activity = MainActivityHolder.activity;
        if (activity == null || value == null) return false;
        Uri uri;
        try {
            uri = Uri.parse(value);
        } catch (Exception e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"gemini.google.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getUserInfo() != null) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage("com.android.chrome");
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static class MainActivityHolder {
        static volatile Activity activity;
    }

    @Override protected void onResume() {
        super.onResume();
        MainActivityHolder.activity = this;
        BridgeServer.startIfNeeded();
        refreshStatus("Bridge status refreshed.");
    }

    @Override protected void onDestroy() {
        if (MainActivityHolder.activity == this) MainActivityHolder.activity = null;
        super.onDestroy();
    }
}
