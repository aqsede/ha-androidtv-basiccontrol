package io.hapanel.tv;

import android.content.Context;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * config.json in the app's external files directory
 * (/sdcard/Android/data/io.hapanel.tv/files/config.json), pushed over adb with ./ha.sh config.
 *
 * {
 *   "url": "http://192.168.1.20:8123",
 *   "token": "long-lived access token",
 *   "key": "KEYCODE_PROG_RED",          optional: remote button that opens/closes the panel
 *   "log_keys": false,                  optional: log every key name to logcat (tag HaPanel) to find a key
 *   "entities": [ {"id": "light.x", "name": "Bedroom"}, "switch.y" ]
 * }
 */
final class PanelConfig {

    static final class Item {
        final String id;
        final String name; // may be empty: the entity's friendly_name is used then

        Item(String id, String name) {
            this.id = id;
            this.name = name;
        }

        String domain() {
            return id.substring(0, id.indexOf('.'));
        }

        /** Scenes, scripts and buttons are fired, not toggled. */
        boolean momentary() {
            String d = domain();
            return d.equals("scene") || d.equals("script") || d.equals("button") || d.equals("input_button");
        }
    }

    final String url;
    final String token;
    final int key;
    final boolean logKeys;
    final List<Item> items;
    /** Non-null when the panel cannot work; shown on screen instead of tiles. */
    final String error;

    private PanelConfig(String url, String token, int key, boolean logKeys, List<Item> items, String error) {
        this.url = url;
        this.token = token;
        this.key = key;
        this.logKeys = logKeys;
        this.items = items;
        this.error = error;
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "config.json");
    }

    static PanelConfig load(Context c) {
        File f = file(c);
        List<Item> items = new ArrayList<>();
        if (!f.isFile()) {
            return new PanelConfig("", "", KeyEvent.KEYCODE_UNKNOWN, false, items,
                    "No config.json yet.\nPush it with ./ha.sh config config.json\n(" + f.getAbsolutePath() + ")");
        }
        int key = KeyEvent.KEYCODE_UNKNOWN;
        boolean logKeys = false;
        try {
            JSONObject j = new JSONObject(read(f));
            Object k = j.opt("key");
            if (k instanceof Number) {
                key = ((Number) k).intValue();
            } else if (k instanceof String && !((String) k).trim().isEmpty()) {
                key = KeyEvent.keyCodeFromString(((String) k).trim());
            }
            logKeys = j.optBoolean("log_keys", false);

            String url = j.optString("url", "").trim();
            String token = j.optString("token", "").trim();
            if (url.isEmpty() || token.isEmpty()) {
                return new PanelConfig(url, token, key, logKeys, items, "config.json needs \"url\" and \"token\".");
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return new PanelConfig(url, token, key, logKeys, items, "\"url\" must start with http:// or https://");
            }

            StringBuilder skipped = new StringBuilder();
            JSONArray arr = j.optJSONArray("entities");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Object o = arr.opt(i);
                    String id = "";
                    String name = "";
                    if (o instanceof String) {
                        id = ((String) o).trim();
                    } else if (o instanceof JSONObject) {
                        id = ((JSONObject) o).optString("id", "").trim();
                        name = ((JSONObject) o).optString("name", "").trim();
                    }
                    if (validId(id)) items.add(new Item(id, name));
                    else skipped.append(' ').append(id.isEmpty() ? "(empty)" : id);
                }
            }
            if (items.isEmpty()) {
                String msg = "config.json has no valid \"entities\".";
                if (skipped.length() > 0) msg += " Skipped:" + skipped;
                return new PanelConfig(url, token, key, logKeys, items, msg);
            }
            return new PanelConfig(url, token, key, logKeys, items, null);
        } catch (Exception e) {
            return new PanelConfig("", "", key, logKeys, items, "config.json is not valid JSON: " + e.getMessage());
        }
    }

    /** Entity ids look like domain.object_id: lower-case letters, digits, underscore, one dot. */
    static boolean validId(String id) {
        int dot = id.indexOf('.');
        if (dot < 1 || dot == id.length() - 1 || id.indexOf('.', dot + 1) >= 0) return false;
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.';
            if (!ok) return false;
        }
        return true;
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
