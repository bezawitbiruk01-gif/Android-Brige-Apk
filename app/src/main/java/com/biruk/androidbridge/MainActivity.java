package com.biruk.androidbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.security.SecureRandom;

public class MainActivity extends Activity {
    static volatile boolean enabled = false;
    static String token;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = makeToken();
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("Android Bridge");
        title.setTextSize(26);

        TextView status = new TextView(this);
        status.setText("Bridge: OFF\nLocal endpoint: 127.0.0.1:8765\nPairing token: " + token);

        Button toggle = new Button(this);
        toggle.setText(enabled ? "Turn OFF" : "Turn ON");
        toggle.setOnClickListener(v -> {
            enabled = !enabled;
            toggle.setText(enabled ? "Turn OFF" : "Turn ON");
            status.setText("Bridge: " + (enabled ? "ON" : "OFF")
                    + "\nLocal endpoint: 127.0.0.1:8765\nPairing token: " + token);
            BridgeServer.startIfNeeded();
        });

        Button accessibility = new Button(this);
        accessibility.setText("Enable Accessibility Service");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button emergency = new Button(this);
        emergency.setText("EMERGENCY STOP");
        emergency.setOnClickListener(v -> {
            enabled = false;
            BridgeServer.stop();
            toggle.setText("Turn ON");
            status.setText("Bridge: OFF (Emergency Stop)\nLocal endpoint: 127.0.0.1:8765\nPairing token: " + token);
        });

        root.addView(title);
        root.addView(status);
        root.addView(toggle);
        root.addView(accessibility);
        root.addView(emergency);
        setContentView(root);
    }

    static String makeToken() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    static void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivityHolder.activity.startActivity(i);
        } catch (Exception ignored) {}
    }

    static class MainActivityHolder {
        static Activity activity;
    }

    @Override protected void onResume() {
        super.onResume();
        MainActivityHolder.activity = this;
        BridgeServer.startIfNeeded();
    }
}
