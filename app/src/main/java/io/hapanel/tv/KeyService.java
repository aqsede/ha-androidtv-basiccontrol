package io.hapanel.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;

/**
 * Opens (or closes) the panel from one remote button, from any app. Only the key named in config.json
 * ("key") is consumed; every other key passes through untouched. Same pattern as the launcher's
 * InputKeyService. Opt-in: ./ha.sh key-on.
 *
 * With "log_keys": true in config.json, each key press is logged as "key KEYCODE_X (n)" under the tag
 * HaPanel, so you can find which of your remote's buttons reach apps at all.
 */
public class KeyService extends AccessibilityService {

    private static final String TAG = "HaPanel";

    private int key = KeyEvent.KEYCODE_UNKNOWN;
    private boolean logKeys;
    private long stamp = -1;

    @Override
    protected void onServiceConnected() {
        reload(true);
    }

    /** Re-reads config.json only when its modification time changed: one stat per key press. */
    private void reload(boolean force) {
        File f = PanelConfig.file(this);
        long m = f.lastModified();
        if (!force && m == stamp) return;
        stamp = m;
        PanelConfig c = PanelConfig.load(this);
        key = c.key;
        logKeys = c.logKeys;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        reload(false);
        int code = e.getKeyCode();
        if (logKeys && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Log.i(TAG, "key " + KeyEvent.keyCodeToString(code) + " (" + code + ")");
        }
        if (key == KeyEvent.KEYCODE_UNKNOWN || code != key) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) toggle();
        return true; // consume both DOWN and UP of our key
    }

    private void toggle() {
        PanelActivity open = PanelActivity.current;
        if (open != null) {
            open.finish();
            return;
        }
        try {
            startActivity(new Intent(this, PanelActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ex) {
            Log.w(TAG, "could not open panel: " + ex);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
