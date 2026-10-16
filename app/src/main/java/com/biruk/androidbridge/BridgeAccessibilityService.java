package com.biruk.androidbridge;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

public class BridgeAccessibilityService extends AccessibilityService {
    static volatile BridgeAccessibilityService instance;
    private static final int MAX_SCREEN_CHARS = 12000;
    private static final String CHROME_PACKAGE = "com.android.chrome";

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // This bridge performs only explicit requests; it does not autonomously act on events.
    }

    @Override public void onInterrupt() {
        // No queued automation continues after an interruption.
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public boolean onKeyEvent(KeyEvent event) {
        return false;
    }

    static String readScreenText() {
        BridgeAccessibilityService service = instance;
        if (service == null) return null;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;
        try {
            if (!CHROME_PACKAGE.contentEquals(root.getPackageName())) return null;
            StringBuilder output = new StringBuilder();
            collect(root, output);
            String text = output.toString().trim();
            if (!hasGeminiIndicator(text)) return null;
            return text.length() > MAX_SCREEN_CHARS
                    ? text.substring(0, MAX_SCREEN_CHARS) : text;
        } finally {
            root.recycle();
        }
    }

    static boolean clickText(String wanted) {
        BridgeAccessibilityService service = instance;
        if (service == null || wanted == null || wanted.isEmpty()) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            if (!CHROME_PACKAGE.contentEquals(root.getPackageName())) return false;
            StringBuilder visible = new StringBuilder();
            collect(root, visible);
            if (!hasGeminiIndicator(visible.toString())) return false;
            return clickRecursive(root, wanted);
        } finally {
            root.recycle();
        }
    }

    static boolean typeText(String value) {
        BridgeAccessibilityService service = instance;
        if (service == null || value == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            if (!CHROME_PACKAGE.contentEquals(root.getPackageName())) return false;
            StringBuilder visible = new StringBuilder();
            collect(root, visible);
            if (!hasGeminiIndicator(visible.toString())) return false;

            // Never type into the first arbitrary editable node (for example, Chrome's address bar).
            // The user must focus the intended Gemini input field first.
            AccessibilityNodeInfo field = findFocusedEditable(root);
            if (field == null) return false;
            try {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            } finally {
                field.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    private static boolean hasGeminiIndicator(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("gemini") || lower.contains("gemini.google.com");
    }

    private static void collect(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null || out.length() >= MAX_SCREEN_CHARS) return;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null && text.length() > 0) appendLimited(out, text);
        else if (description != null && description.length() > 0) appendLimited(out, description);

        for (int i = 0; i < node.getChildCount() && out.length() < MAX_SCREEN_CHARS; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    collect(child, out);
                } finally {
                    child.recycle();
                }
            }
        }
    }

    private static void appendLimited(StringBuilder out, CharSequence value) {
        int remaining = MAX_SCREEN_CHARS - out.length();
        if (remaining <= 0) return;
        int length = Math.min(value.length(), remaining);
        out.append(value, 0, length).append('\n');
    }

    private static boolean clickRecursive(AccessibilityNodeInfo node, String wanted) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        String nodeText = text == null ? "" : text.toString();
        String nodeDescription = description == null ? "" : description.toString();
        if ((nodeText.equalsIgnoreCase(wanted) || nodeDescription.equalsIgnoreCase(wanted))
                && node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    if (clickRecursive(child, wanted)) return true;
                } finally {
                    child.recycle();
                }
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result;
                try {
                    result = findFocusedEditable(child);
                } finally {
                    child.recycle();
                }
                if (result != null) return result;
            }
        }
        return null;
    }
}
