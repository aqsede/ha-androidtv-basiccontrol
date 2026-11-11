package io.hapanel.tv;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full-screen glass panel of Home Assistant tiles. D-pad moves, OK toggles (or fires a scene/script),
 * Back closes. While visible it re-reads each entity every few seconds; the moment it is paused or
 * closed nothing runs at all (no service, no socket).
 */
public class PanelActivity extends Activity {

    /** The open panel, so KeyService can close it with the same button that opened it. */
    static PanelActivity current;

    private static final long POLL_MS = 5000;

    private static final int GREY = 0xFFB0B7BE;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int ERROR = 0xFFFF8A80;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Tile> tiles = new ArrayList<>();
    private final AtomicInteger pending = new AtomicInteger();
    private ExecutorService exec;
    private PanelConfig cfg;
    private HaClient ha;
    private TextView status;
    private long stamp = -2;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        exec = Executors.newFixedThreadPool(3);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        long m = PanelConfig.file(this).lastModified();
        if (m != stamp || tiles.isEmpty() && cfg == null) {
            stamp = m;
            buildUi();
        }
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        if (current == this) current = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (exec != null) exec.shutdownNow();
        super.onDestroy();
    }

    // ---- UI ----------------------------------------------------------------------------------

    private void buildUi() {
        cfg = PanelConfig.load(this);
        ha = cfg.error == null ? new HaClient(cfg.url, cfg.token) : null;
        tiles.clear();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xE6101418); // ~90% dark: the wallpaper behind Home shows through faintly

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(64), dp(40), dp(64), dp(24));
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        col.addView(text(getString(R.string.app_name), 30, WHITE, true));
        status = text("", 14, GREY, false);
        status.setPadding(0, dp(4), 0, dp(20));
        col.addView(status);

        if (cfg.error != null) {
            showError(cfg.error);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        } else {
            clearError();
            ScrollView scroll = new ScrollView(this);
            scroll.setClipToPadding(false);
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rows, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
            col.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            final int cols = cfg.columns;
            for (PanelConfig.Group group : cfg.groups) {
                if (!group.room.isEmpty()) {
                    TextView heading = text(group.room, 18, 0xFFE6E9EC, true);
                    heading.setLetterSpacing(0.04f);
                    heading.setPadding(dp(10), dp(rows.getChildCount() == 0 ? 0 : 18), dp(10), dp(2));
                    rows.addView(heading);
                }
                List<PanelConfig.Item> items = group.items;
                for (int i = 0; i < items.size(); i += cols) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    for (int j = 0; j < cols; j++) {
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(104), 1f);
                        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
                        if (i + j < items.size()) {
                            Tile t = new Tile(items.get(i + j));
                            tiles.add(t);
                            row.addView(t, lp);
                        } else {
                            row.addView(new View(this), lp); // keeps the last row's tiles the same width
                        }
                    }
                    rows.addView(row);
                }
            }
            if (!tiles.isEmpty()) tiles.get(0).requestFocus();
        }
        setContentView(root);
    }

    private void showError(String msg) {
        if (status == null) return;
        status.setTextColor(ERROR);
        status.setText(msg);
    }

    private void clearError() {
        if (status == null || cfg == null || cfg.error != null) return;
        status.setTextColor(GREY);
        status.setText("OK toggles   \u00B7   Back closes");
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Drawable background(int fill) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(fill, WHITE, dp(3)));
        s.addState(new int[]{}, shape(fill, 0x00000000, 0));
        return s;
    }

    private GradientDrawable shape(int fill, int stroke, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(fill);
        if (strokeWidth > 0) g.setStroke(strokeWidth, stroke);
        return g;
    }

    // ---- Home Assistant -----------------------------------------------------------------------

    private void refreshAll() {
        if (ha == null || pending.get() > 0) return; // a slow or unreachable HA must not pile up requests
        for (Tile t : tiles) refreshOne(t);
    }

    private void refreshOne(final Tile t) {
        final HaClient client = ha;
        if (client == null) return;
        pending.incrementAndGet();
        try {
            exec.execute(() -> {
                try {
                    final JSONObject st = client.state(t.item.id);
                    ui.post(() -> {
                        t.apply(st);
                        clearError();
                    });
                } catch (HaClient.HttpError e) {
                    ui.post(() -> {
                        if (e.code == 404) t.markMissing();
                        else showError(e.getMessage());
                    });
                } catch (IOException e) {
                    ui.post(() -> showError("Can't reach Home Assistant: " + e.getMessage()));
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) { // executor already shut down
            pending.decrementAndGet();
        }
    }

    private void toggle(final Tile t) {
        final HaClient client = ha;
        if (client == null) return;
        final boolean before = t.on;
        if (t.item.momentary()) t.sub.setText("\u2026");
        else if (t.known) t.showOptimistic(!before);
        try {
            exec.execute(() -> {
                try {
                    client.fire(t.item);
                    Thread.sleep(400); // give the device a moment before asking how it ended up
                    final JSONObject st = client.state(t.item.id);
                    ui.post(() -> {
                        t.apply(st);
                        clearError();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    ui.post(() -> {
                        if (!t.item.momentary() && t.known) t.showOptimistic(before);
                        showError("Failed: " + e.getMessage());
                    });
                }
            });
        } catch (RuntimeException rejected) {
            // activity is closing
        }
    }

    // ---- One tile ------------------------------------------------------------------------------

    private final class Tile extends LinearLayout {
        final PanelConfig.Item item;
        final TextView name;
        final TextView sub;
        boolean on;
        boolean known;

        Tile(PanelConfig.Item item) {
            super(PanelActivity.this);
            this.item = item;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(18), dp(10), dp(18), dp(10));
            setFocusable(true);
            setClickable(true);

            name = text(item.name.isEmpty() ? item.id : item.name, 20, WHITE, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            sub = text("\u2026", 14, GREY, false);
            addView(name);
            addView(sub);
            paint(0x33FFFFFF, WHITE, GREY);

            setOnClickListener(v -> toggle(this));
            setOnFocusChangeListener((v, focused) ->
                    v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(120).start());
        }

        void apply(JSONObject st) {
            String state = st.optString("state", "unknown");
            JSONObject attrs = st.optJSONObject("attributes");
            String friendly = attrs == null ? "" : attrs.optString("friendly_name", "");
            if (item.name.isEmpty() && !friendly.isEmpty()) name.setText(friendly);

            if (item.momentary()) {
                known = true;
                on = false;
                sub.setText(item.domain().equals("script") && state.equals("on") ? "Running" : "Run");
                paint(0x33FFFFFF, WHITE, GREY);
            } else if (state.equals("unavailable") || state.equals("unknown")) {
                known = false;
                on = false;
                sub.setText(Character.toUpperCase(state.charAt(0)) + state.substring(1));
                paint(0x1AFFFFFF, 0xFF8A9096, 0xFF6F757A);
            } else {
                known = true;
                boolean isOn = state.equals("on") || state.equals("open");
                show(isOn, state.equals("on") ? "On" : state.equals("off") ? "Off"
                        : Character.toUpperCase(state.charAt(0)) + state.substring(1));
            }
        }

        void showOptimistic(boolean isOn) {
            show(isOn, isOn ? "On" : "Off");
        }

        void markMissing() {
            known = false;
            sub.setText("Not found");
            paint(0x1AFFFFFF, 0xFF8A9096, 0xFF6F757A);
        }

        private void show(boolean isOn, String label) {
            on = isOn;
            sub.setText(label);
            if (isOn) paint(0xFFF2B84B, 0xFF1A1A1A, 0xFF4A3A10);
            else paint(0x33FFFFFF, WHITE, GREY);
        }

        private void paint(int fill, int nameColor, int subColor) {
            setBackground(background(fill));
            name.setTextColor(nameColor);
            sub.setTextColor(subColor);
        }
    }
}
