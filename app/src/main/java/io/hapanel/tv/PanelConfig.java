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
 * config.json in the app's private files directory, written by ./ha.sh config (through run-as).
 *
 * {
 *   "url": "http://192.168.1.20:8123",
 *   "token": "long-lived access token",
 *   "columns": 4,                        optional, 2 to 6, default 4
 *   "key": "KEYCODE_PROG_RED",           optional: remote button for the key service
 *   "log_keys": false,                   optional: log key names to logcat (tag HaPanel)
 *   "entities": [
 *     { "room": "Living Room", "entities": [ {"id": "light.x", "name": "Left"}, "fan.y" ] },
 *     { "room": "Bedroom",     "entities": [ ... ] },
 *     "switch.loose_one"                  entities outside a room are allowed, without heading
 *   ]
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

    /** A room: a heading and its tiles. An empty room name means no heading. */
    static final class Group {
        final String room;
        final List<Item> items = new ArrayList<>();

        Group(String room) {
            this.room = room;
        }
    }

    final String url;
    final String token;
    final int key;
    final boolean logKeys;
    final int columns;
    final List<Group> groups;
    /** Non-null when the panel cannot work; shown on screen instead of tiles. */
    final String error;

    private PanelConfig(String url, String token, int key, boolean logKeys, int columns,
                        List<Group> groups, String error) {
        this.url = url;
        this.token = token;
        this.key = key;
        this.logKeys = logKeys;
        this.columns = columns;
        this.groups = groups;
        this.error = error;
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "config.json");
    }

    static PanelConfig load(Context c) {
        File f = file(c);
        List<Group> groups = new ArrayList<>();
        if (!f.isFile()) {
            return new PanelConfig("", "", KeyEvent.KEYCODE_UNKNOWN, false, 4, groups,
                    "No config.json yet.\nPush it with ./ha.sh config config.json");
        }
        int key = KeyEvent.KEYCODE_UNKNOWN;
        boolean logKeys = false;
        int columns = 4;
        try {
            JSONObject j = new JSONObject(read(f));
            Object k = j.opt("key");
            if (k instanceof Number) {
                key = ((Number) k).intValue();
            } else if (k instanceof String && !((String) k).trim().isEmpty()) {
                key = KeyEvent.keyCodeFromString(((String) k).trim());
            }
            logKeys = j.optBoolean("log_keys", false);
            columns = Math.max(2, Math.min(6, j.optInt("columns", 4)));

            String url = j.optString("url", "").trim();
            String token = j.optString("token", "").trim();
            if (url.isEmpty() || token.isEmpty()) {
                return new PanelConfig(url, token, key, logKeys, columns, groups,
                        "config.json needs \"url\" and \"token\".");
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return new PanelConfig(url, token, key, logKeys, columns, groups,
                        "\"url\" must start with http:// or https://");
            }

            StringBuilder skipped = new StringBuilder();
            JSONArray arr = j.optJSONArray("entities");
            Group loose = null; // collects entities listed outside any room
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Object o = arr.opt(i);
                    if (o instanceof JSONObject && ((JSONObject) o).has("entities")) {
                        JSONObject g = (JSONObject) o;
                        Group grp = new Group(g.optString("room", g.optString("name", "")).trim());
                        JSONArray inner = g.optJSONArray("entities");
                        if (inner != null) {
                            for (int n = 0; n < inner.length(); n++) {
                                Item it = parseItem(inner.opt(n), skipped);
                                if (it != null) grp.items.add(it);
                            }
                        }
                        if (!grp.items.isEmpty()) groups.add(grp);
                        loose = null;
                    } else {
                        Item it = parseItem(o, skipped);
                        if (it != null) {
                            if (loose == null) {
                                loose = new Group("");
                                groups.add(loose);
                            }
                            loose.items.add(it);
                        }
                    }
                }
            }
            if (groups.isEmpty()) {
                String msg = "config.json has no valid \"entities\".";
                if (skipped.length() > 0) msg += " Skipped:" + skipped;
                return new PanelConfig(url, token, key, logKeys, columns, groups, msg);
            }
            return new PanelConfig(url, token, key, logKeys, columns, groups, null);
        } catch (Exception e) {
            return new PanelConfig("", "", key, logKeys, columns, new ArrayList<Group>(),
                    "config.json is not valid JSON: " + e.getMessage());
        }
    }

    /** One entity: "light.x" or {"id": "light.x", "name": "Label"}; null (and noted) when the id is invalid. */
    private static Item parseItem(Object o, StringBuilder skipped) {
        String id = "";
        String name = "";
        if (o instanceof String) {
            id = ((String) o).trim();
        } else if (o instanceof JSONObject) {
            id = ((JSONObject) o).optString("id", "").trim();
            name = ((JSONObject) o).optString("name", "").trim();
        }
        if (validId(id)) return new Item(id, name);
        skipped.append(' ').append(id.isEmpty() ? "(empty)" : id);
        return null;
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
