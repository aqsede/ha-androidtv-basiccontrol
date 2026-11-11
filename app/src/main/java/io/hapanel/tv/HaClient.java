package io.hapanel.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** The two Home Assistant REST calls the panel needs. Blocking: call from a background thread. */
final class HaClient {

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final String base;
    private final String auth;

    HaClient(String url, String token) {
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.auth = "Bearer " + token;
    }

    /** GET /api/states/<entity_id> */
    JSONObject state(String entityId) throws IOException {
        String body = request("GET", "/api/states/" + entityId, null);
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from Home Assistant");
        }
    }

    /** POST the right service for this entity: toggle for lights, switches and so on; turn_on for scenes and scripts. */
    void fire(PanelConfig.Item item) throws IOException {
        String domain = item.domain();
        String path;
        if (domain.equals("scene") || domain.equals("script")) {
            path = "/api/services/" + domain + "/turn_on";
        } else if (domain.equals("button") || domain.equals("input_button")) {
            path = "/api/services/" + domain + "/press";
        } else {
            path = "/api/services/homeassistant/toggle";
        }
        String json;
        try {
            json = new JSONObject().put("entity_id", item.id).toString();
        } catch (JSONException e) {
            throw new IOException("Could not build request");
        }
        request("POST", path, json);
    }

    private String request(String method, String path, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Authorization", auth);
            c.setRequestProperty("Accept", "application/json");
            if (json != null) {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new HttpError(code, "Token rejected (HTTP " + code + ")");
            if (code == 404) throw new HttpError(code, "Not found (HTTP 404)");
            if (code >= 400) throw new HttpError(code, "Home Assistant answered HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
