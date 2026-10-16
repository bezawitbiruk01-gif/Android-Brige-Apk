package com.biruk.androidbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    static volatile boolean enabled = false;
    static volatile String token;
    private TextView status;
    private Button toggle;
    private EditText relayUrlInput;
    private EditText pairCodeInput;
    static final String PREFS = "bridge_private_prefs";
    static final String KEY_TOKEN = "pairing_token";
    static final String KEY_RELAY_URL = "relay_url";
    static final String KEY_PAIR_CODE = "relay_pair_code";
    static final String KEY_RELAY_TOKEN = "relay_device_token";
    static final String KEY_RELAY_STATUS = "relay_status";
    static final String KEY_BRIDGE_ENABLED = "bridge_enabled";
    static final String GEMINI_URL = "https://gemini.google.com/";
    static final String DEFAULT_RELAY_URL = "https://biruk-ai-player-relay.onrender.com";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = getOrCreateToken(this);
        enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_BRIDGE_ENABLED, false);
        buildUi();
        refreshStatus("Bridge starts OFF unless you explicitly turn it ON.");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Android Bridge");
        title.setTextSize(26);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explanation = new TextView(this);
        explanation.setText("Pair this phone with the ai-player plugin using a one-time code. Keep the bridge OFF except when you want it to act.");
        explanation.setTextSize(14);
        addViewWithTopMargin(root, explanation, pad / 3);

        TextView relayLabel = new TextView(this);
        relayLabel.setText("Relay URL (HTTPS)");
        addViewWithTopMargin(root, relayLabel, pad / 2);
        relayUrlInput = new EditText(this);
        relayUrlInput.setSingleLine(true);
        relayUrlInput.setInputType( android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        relayUrlInput.setHint("https://your-relay.onrender.com");
        relayUrlInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_RELAY_URL, DEFAULT_RELAY_URL));
        root.addView(relayUrlInput);

        TextView pairLabel = new TextView(this);
        pairLabel.setText("One-time pairing code (from the plugin)");
        addViewWithTopMargin(root, pairLabel, pad / 2);
        pairCodeInput = new EditText(this);
        pairCodeInput.setSingleLine(true);
        pairCodeInput.setHint("6-digit code");
        pairCodeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pairCodeInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_PAIR_CODE, ""));
        root.addView(pairCodeInput);

        Button saveSettings = new Button(this);
        saveSettings.setText("Save relay settings");
        saveSettings.setOnClickListener(v -> {
            saveRelaySettings();
            hideKeyboard();
            refreshStatus("Relay settings saved on this phone.");
        });
        addViewWithTopMargin(root, saveSettings, pad / 2);

        status = new TextView(this);
        status.setTextSize(14);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = pad / 2;
        statusParams.bottomMargin = pad / 2;
        root.addView(status, statusParams);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (enabled) {
                stopBridge("Bridge stopped by user.");
            } else {
                if (!saveRelaySettings()) return;
                String relayToken = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_RELAY_TOKEN, "");
                String pairCode = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_PAIR_CODE, "");
                if ((relayToken == null || relayToken.isEmpty())
                        && (pairCode == null || !pairCode.matches("\\d{6}"))) {
                    Toast.makeText(this,
                            "First create a pairing code with the ai-player plugin and enter it above.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                enabled = true;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_BRIDGE_ENABLED, true).apply();
                BridgeServer.startIfNeeded();
                Intent service = new Intent(this, BridgeRelayService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
                hideKeyboard();
                refreshStatus("Bridge is ON. Checking relay connection…");
            }
        });
        root.addView(toggle);

        Button openGemini = new Button(this);
        openGemini.setText("Open Gemini in Chrome");
        openGemini.setOnClickListener(v -> {
            boolean opened = openUrl(GEMINI_URL);
            refreshStatus(opened
                    ? "Chrome launch was requested. Check the phone screen; this does not prove Gemini finished loading."
                    : "Could not open Gemini in Chrome. Check that Chrome is installed.");
        });
        root.addView(openGemini);

        Button accessibility = new Button(this);
        accessibility.setText("Accessibility settings");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button copyLocalToken = new Button(this);
        copyLocalToken.setText("Copy local-only token (not relay pairing code)");
        copyLocalToken.setOnClickListener(v -> {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Local Android Bridge token", token));
                Toast.makeText(this, "Local token copied. It does not pair the plugin.", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copyLocalToken);

        Button emergency = new Button(this);
        emergency.setText("EMERGENCY STOP");
        emergency.setOnClickListener(v -> stopBridge("EMERGENCY STOP: local and relay automation stopped."));
        addViewWithTopMargin(root, emergency, pad / 2);

        scroll.addView(root);
        setContentView(scroll);
        updateToggleText();
    }

    private void addViewWithTopMargin(LinearLayout root, android.view.View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        root.addView(view, params);
    }

    private boolean saveRelaySettings() {
        String url = relayUrlInput == null ? "" : relayUrlInput.getText().toString().trim();
        String code = pairCodeInput == null ? "" : pairCodeInput.getText().toString().trim();
        if (!url.startsWith("https://") || url.contains("@")) {
            Toast.makeText(this, "Enter a valid public HTTPS Relay URL.", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!code.isEmpty() && !code.matches("\\d{6}")) {
            Toast.makeText(this, "The pairing code must be exactly 6 digits.", Toast.LENGTH_LONG).show();
            return false;
        }
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String oldUrl = prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL);
        android.content.SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_RELAY_URL, url)
                .putString(KEY_PAIR_CODE, code);
        if (!url.replaceAll("/+$", "").equals(oldUrl.replaceAll("/+$", ""))) {
            // A device token belongs to the old relay; never send it to a different host.
            editor.remove(KEY_RELAY_TOKEN).remove("relay_device_id");
        }
        editor.apply();
        return true;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(relayUrlInput.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private void stopBridge(String message) {
        enabled = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_BRIDGE_ENABLED, false).apply();
        BridgeServer.stop();
        RelayClient.stop();
        stopService(new Intent(this, BridgeRelayService.class));
        refreshStatus(message);
    }

    private void updateToggleText() {
        if (toggle != null) toggle.setText(enabled ? "Turn Bridge OFF" : "Turn Bridge ON");
    }

    private void refreshStatus(String message) {
        if (status != null) {
            status.setText(message
                    + "\n\nBridge enabled: " + enabled
                    + "\nLocal endpoint: 127.0.0.1:8765 (phone only)"
                    + "\nLocal server listening: " + BridgeServer.isRunning()
                    + "\nRelay: " + RelayClient.getStatus(this)
                    + "\nAccessibility service enabled: " + (BridgeAccessibilityService.instance != null)
                    + "\nAllowed launch target: https://gemini.google.com/"
                    + "\nScreen actions require Chrome and a visible Gemini indicator."
                    + "\nThis page cannot by itself prove that the plugin or Gemini is connected.");
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
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    static boolean openUrl(String value) {
        Activity activity = MainActivityHolder.activity;
        if (activity == null || value == null) return false;
        Uri uri;
        try { uri = Uri.parse(value); } catch (Exception e) { return false; }
        if (!isAllowedGeminiUrl(uri)) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage("com.android.chrome");
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAllowedGeminiUrl(Uri uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && "gemini.google.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getUserInfo() == null;
    }

    static JSONObject executeRelayCommand(Context context, JSONObject command) {
        Callable<JSONObject> action = () -> executeRelayCommandOnMain(context, command);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return action.call(); }
            catch (Exception e) { return failure("command_execution_failed"); }
        }
        FutureTask<JSONObject> task = new FutureTask<>(action);
        new Handler(Looper.getMainLooper()).post(task);
        try { return task.get(6, TimeUnit.SECONDS); }
        catch (Exception e) { task.cancel(true); return failure("main_thread_timeout"); }
    }

    private static JSONObject executeRelayCommandOnMain(Context context, JSONObject command) {
        String action = command.optString("action", "");
        JSONObject payload = command.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        try {
            switch (action) {
                case "OPEN_GEMINI": {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_URL));
                    intent.setPackage("com.android.chrome");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.getApplicationContext().startActivity(intent);
                    JSONObject response = new JSONObject();
                    response.put("status", "ACCEPTED");
                    response.put("verified", false);
                    response.put("reason", "Chrome launch requested; manually verify that Gemini actually opened.");
                    response.put("action", action);
                    return response;
                }
                case "READ_GEMINI_SCREEN": {
                    String screen = BridgeAccessibilityService.readScreenText();
                    if (screen == null) return failure("chrome_gemini_window_not_detected_or_accessibility_not_enabled");
                    JSONObject response = new JSONObject();
                    response.put("status", "SUCCEEDED");
                    response.put("verified", true);
                    response.put("text", screen);
                    response.put("reason", "Visible text read from a Chrome window with a Gemini UI indicator; page origin is not cryptographically verified.");
                    return response;
                }
                case "CLICK_GEMINI_TEXT": {
                    String wanted = payload.optString("text", "");
                    boolean clicked = BridgeAccessibilityService.clickText(wanted);
                    JSONObject response = new JSONObject();
                    response.put("status", clicked ? "ACCEPTED" : "FAILED");
                    response.put("verified", false);
                    response.put("action", action);
                    response.put("reason", clicked
                            ? "Android accepted the click request; call read_gemini_screen to verify the resulting screen."
                            : "gemini_chrome_not_detected_or_text_not_clickable");
                    return response;
                }
                case "TYPE_GEMINI_TEXT": {
                    String text = payload.optString("text", "");
                    boolean typed = BridgeAccessibilityService.typeText(text);
                    JSONObject response = new JSONObject();
                    response.put("status", typed ? "ACCEPTED" : "FAILED");
                    response.put("verified", false);
                    response.put("action", action);
                    response.put("reason", typed
                            ? "Android accepted the text-entry request; the prompt was not sent. Call read_gemini_screen to verify the visible field."
                            : "gemini_chrome_not_detected_or_no_focused_editable_field");
                    return response;
                }
                case "EMERGENCY_STOP": {
                    enabled = false;
                    context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean(KEY_BRIDGE_ENABLED, false).apply();
                    BridgeServer.stop();
                    JSONObject response = new JSONObject();
                    response.put("status", "SUCCEEDED");
                    response.put("verified", true);
                    response.put("reason", "Automation is disabled on the phone. The relay service will stop after reporting this result.");
                    return response;
                }
                default:
                    return failure("unsupported_action");
            }
        } catch (Exception e) {
            return failure("action_failed");
        }
    }

    private static JSONObject failure(String reason) {
        JSONObject response = new JSONObject();
        try {
            response.put("status", "FAILED");
            response.put("verified", false);
            response.put("reason", reason);
        } catch (Exception ignored) {}
        return response;
    }

    static class MainActivityHolder {
        static volatile Activity activity;
    }

    @Override protected void onResume() {
        super.onResume();
        MainActivityHolder.activity = this;
        if (enabled) BridgeServer.startIfNeeded();
        refreshStatus("Bridge status refreshed.");
    }

    @Override protected void onDestroy() {
        if (MainActivityHolder.activity == this) MainActivityHolder.activity = null;
        super.onDestroy();
    }
}
