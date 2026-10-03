package com.biruk.androidbridge;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;

public class BridgeAccessibilityService extends AccessibilityService {
    static volatile BridgeAccessibilityService instance;

    @Override public void onServiceConnected() {
        instance = this;
    }

    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public boolean onKeyEvent(KeyEvent event) {
        return false;
    }

    static String readScreenText() {
        BridgeAccessibilityService s = instance;
        if (s == null) return "";
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder out = new StringBuilder();
        collect(root, out);
        root.recycle();
        return out.toString().trim();
    }

    private static void collect(AccessibilityNodeInfo n, StringBuilder out) {
        if (n == null) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        if (t != null && t.length() > 0) out.append(t).append('\n');
        else if (d != null && d.length() > 0) out.append(d).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out);
    }

    static boolean clickText(String wanted) {
        BridgeAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        boolean ok = clickRecursive(root, wanted);
        root.recycle();
        return ok;
    }

    private static boolean clickRecursive(AccessibilityNodeInfo n, String wanted) {
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        String a = text == null ? "" : text.toString();
        String b = desc == null ? "" : desc.toString();
        if ((a.equalsIgnoreCase(wanted) || b.equalsIgnoreCase(wanted)) && n.isClickable()) {
            return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (clickRecursive(n.getChild(i), wanted)) return true;
        }
        return false;
    }

    static boolean typeText(String value) {
        BridgeAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo field = findEditable(root);
        boolean ok = false;
        if (field != null) {
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            field.recycle();
        }
        root.recycle();
        return ok;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n.isEditable()) return AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
}
