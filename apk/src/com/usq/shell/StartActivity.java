package com.usq.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Modern management screen for UQ Shell (FR-HOST-8).
 * Dark theme, script status chips, start/stop/remove actions,
 * in-app logs viewer, and permission health indicators.
 */
public class StartActivity extends Activity {

    private LinearLayout scriptsBox;
    private TextView statusLine;
    private LinearLayout bannerBox;

    private static final int COLOR_BG = 0xFF0F1117;
    private static final int COLOR_SURFACE = 0xFF1A1D26;
    private static final int COLOR_BORDER = 0xFF2A2E3D;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF8FAFC;
    private static final int COLOR_TEXT_SECONDARY = 0xFF94A3B8;
    private static final int COLOR_TEXT_MUTED = 0xFF64748B;
    private static final int COLOR_ACCENT = 0xFF6366F1;
    private static final int COLOR_SUCCESS = 0xFF10B981;
    private static final int COLOR_DANGER = 0xFFEF4444;
    private static final int COLOR_WARNING = 0xFFF59E0B;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, ScriptService.class));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // Header: App Title + Refresh Button
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("🎭 Puppeteer");
        title.setTextSize(22);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        statusLine = new TextView(this);
        statusLine.setText("Connecting to host (127.0.0.1:7823)…");
        statusLine.setTextSize(12);
        statusLine.setTextColor(COLOR_TEXT_SECONDARY);
        statusLine.setPadding(0, dp(2), 0, 0);
        titleCol.addView(statusLine);

        Button refresh = createPillButton("↻ Refresh", 0xFF232734, COLOR_TEXT_PRIMARY, new Runnable() {
            public void run() { load(); }
        });
        header.addView(refresh);
        root.addView(header);

        // Permission notice banners container
        bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(bannerBox);

        // Scripts ScrollView
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        scriptsBox = new LinearLayout(this);
        scriptsBox.setOrientation(LinearLayout.VERTICAL);
        sc.addView(scriptsBox);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        checkPermissions();
        load();
    }

    private void checkPermissions() {
        bannerBox.removeAllViews();

        boolean overlayOk = Settings.canDrawOverlays(this);
        if (!overlayOk) {
            bannerBox.addView(createPermissionBanner(
                    "Display over other apps missing (required for cards)",
                    "Grant",
                    COLOR_WARNING,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Throwable t) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                            }
                        }
                    }
            ));
        }

        boolean usageOk = hasUsagePermission();
        if (!usageOk) {
            bannerBox.addView(createPermissionBanner(
                    "Usage access missing (required for 24h stats)",
                    "Grant",
                    COLOR_ACCENT,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            try {
                                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                            } catch (Throwable ignored) { }
                        }
                    }
            ));
        }

        boolean vpnOk = (android.net.VpnService.prepare(this) == null);
        if (!vpnOk) {
            bannerBox.addView(createPermissionBanner(
                    "Firewall capability not enabled (needed to block web/internet)",
                    "Enable",
                    0xFF8B5CF6,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            try {
                                Intent intent = android.net.VpnService.prepare(StartActivity.this);
                                if (intent != null) {
                                    startActivityForResult(intent, 9081);
                                }
                            } catch (Throwable t) {
                                toast("VPN prompt failed: " + t);
                            }
                        }
                    }
            ));
        }

        boolean secureSettingsOk = (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED);
        if (!secureSettingsOk) {
            bannerBox.addView(createPermissionBanner(
                    "Grayscale requires WRITE_SECURE_SETTINGS (run adb grant once)",
                    "How-to",
                    COLOR_WARNING,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            new AlertDialog.Builder(StartActivity.this)
                                    .setTitle("Enable Grayscale Permission")
                                    .setMessage("To allow Puppeteer to switch your display to Grayscale dynamically, run this command once via ADB on your PC or Wireless Debugging:\n\n"
                                            + "adb shell pm grant com.usq.shell android.permission.WRITE_SECURE_SETTINGS\n\n"
                                            + "This grants standard secure display filter control.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
            ));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9081) {
            if (resultCode == RESULT_OK) {
                toast("🛡️ Firewall capability authorized!");
            }
            checkPermissions();
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private boolean hasUsagePermission() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private View createPermissionBanner(String msg, String btnText, int accentColor, View.OnClickListener onClick) {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(10);
        banner.setPadding(p, dp(8), p, dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x22FFFFFF & accentColor | 0x1E000000);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), accentColor);
        banner.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        banner.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(12);
        tv.setTextColor(COLOR_TEXT_PRIMARY);
        banner.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btn = createPillButton(btnText, accentColor, 0xFFFFFFFF, null);
        btn.setTextSize(12);
        btn.setOnClickListener(onClick);
        banner.addView(btn);

        return banner;
    }

    private void load() { load(0); }

    private void load(final int attempt) {
        new Thread(new Runnable() { public void run() {
            try {
                final JSONArray arr = new JSONArray();
                JSONObject o = new JSONObject(httpGet("http://127.0.0.1:7823/list"));
                if (!o.optBoolean("ok")) throw new Exception(o.optString("error", "unknown"));
                JSONArray s = o.optJSONArray("scripts");
                for (int i = 0; s != null && i < s.length(); i++) arr.put(s.opt(i));
                runOnUiThread(new Runnable() { public void run() { render(arr); } });
            } catch (Throwable t) {
                if (attempt < 8) {
                    try { Thread.sleep(600); } catch (InterruptedException ie) { return; }
                    runOnUiThread(new Runnable() { public void run() { load(attempt + 1); } });
                } else {
                    runOnUiThread(new Runnable() { public void run() {
                        statusLine.setText("Host unreachable (is service running?)");
                        statusLine.setTextColor(COLOR_DANGER);
                        scriptsBox.removeAllViews();
                    }});
                }
            }
        }}).start();
    }

    private void render(JSONArray arr) {
        scriptsBox.removeAllViews();
        statusLine.setTextColor(COLOR_TEXT_SECONDARY);

        if (arr.length() == 0) {
            statusLine.setText("0 scripts installed · Host active");
            scriptsBox.addView(createEmptyState());
            return;
        }

        statusLine.setText(arr.length() + " script(s) installed · Host active");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null) scriptsBox.addView(createScriptCard(e));
        }
    }

    private View createEmptyState() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        card.setPadding(p, p, p, p);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_SURFACE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COLOR_BORDER);
        card.setBackground(bg);

        TextView t1 = new TextView(this);
        t1.setText("No Scripts Installed");
        t1.setTextSize(16);
        t1.setTextColor(COLOR_TEXT_PRIMARY);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText("\nBuild and push your first script from Termux:\n");
        t2.setTextSize(13);
        t2.setTextColor(COLOR_TEXT_SECONDARY);
        card.addView(t2);

        TextView code = new TextView(this);
        code.setText("puppet buildpush ~/Puppeteer/examples/blocker.uq.java");
        code.setTextSize(12);
        code.setTextColor(0xFF38BDF8); // Sky blue
        code.setTypeface(Typeface.MONOSPACE);
        code.setBackgroundColor(0xFF13151D);
        code.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.addView(code);

        return card;
    }

    private View createScriptCard(final JSONObject e) {
        final String id = e.optString("id", "?");
        final String name = e.optString("name", id);
        final String version = e.optString("version", "0");
        final String entry = e.optString("entry", "?");
        final String state = e.optString("state", "STOPPED");
        final boolean isRunning = "RUNNING".equals(state);
        final boolean resident = e.optBoolean("resident");
        final boolean autoStart = e.optBoolean("autoStart");
        final long lastRunAt = e.optLong("lastRunAt", 0);
        final String desc = e.optString("description", "");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        card.setPadding(p, p, p, p);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_SURFACE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COLOR_BORDER);
        card.setBackground(bg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);

        // Row 1: Script Title + Status Badge
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row1);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.HORIZONTAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        row1.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(16);
        tvName.setTextColor(COLOR_TEXT_PRIMARY);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(tvName);

        TextView tvVer = new TextView(this);
        tvVer.setText(" v" + version);
        tvVer.setTextSize(12);
        tvVer.setTextColor(COLOR_TEXT_MUTED);
        titleBox.addView(tvVer);

        // Status chip
        row1.addView(createStatusChip(state));

        // Row 2: Metadata (entry, tags, last run)
        TextView tvEntry = new TextView(this);
        tvEntry.setText(entry);
        tvEntry.setTextSize(11);
        tvEntry.setTextColor(COLOR_TEXT_MUTED);
        tvEntry.setTypeface(Typeface.MONOSPACE);
        tvEntry.setPadding(0, dp(4), 0, 0);
        card.addView(tvEntry);

        // Tags & Details
        LinearLayout tagsRow = new LinearLayout(this);
        tagsRow.setOrientation(LinearLayout.HORIZONTAL);
        tagsRow.setPadding(0, dp(6), 0, 0);

        if (resident) tagsRow.addView(createTagChip("RESIDENT", 0xFF312E81, 0xFFA5B4FC));
        if (autoStart) tagsRow.addView(createTagChip("AUTOSTART", 0xFF581C87, 0xFFE9D5FF));

        if (lastRunAt > 0) {
            TextView tvLast = new TextView(this);
            tvLast.setText("Last run: " + formatDate(lastRunAt));
            tvLast.setTextSize(11);
            tvLast.setTextColor(COLOR_TEXT_MUTED);
            tvLast.setPadding(dp(6), dp(2), 0, 0);
            tagsRow.addView(tvLast);
        }
        card.addView(tagsRow);

        if (desc.length() > 0) {
            TextView tvDesc = new TextView(this);
            tvDesc.setText(desc);
            tvDesc.setTextSize(12);
            tvDesc.setTextColor(COLOR_TEXT_SECONDARY);
            tvDesc.setPadding(0, dp(6), 0, 0);
            card.addView(tvDesc);
        }

        // Row 3: Action Buttons Bar
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(12), 0, 0);
        card.addView(actions);

        // Start button
        Button btnStart = createPillButton(isRunning ? "Running" : "▶ Start",
                isRunning ? 0xFF1E293B : COLOR_SUCCESS,
                isRunning ? COLOR_TEXT_MUTED : 0xFFFFFFFF,
                isRunning ? null : new Runnable() {
                    public void run() { act("start", id); }
                });
        if (isRunning) btnStart.setEnabled(false);
        actions.addView(btnStart);

        // Stop button
        Button btnStop = createPillButton("■ Stop",
                isRunning ? COLOR_DANGER : 0xFF1E293B,
                isRunning ? 0xFFFFFFFF : COLOR_TEXT_MUTED,
                isRunning ? new Runnable() {
                    public void run() { act("stop", id); }
                } : null);
        if (!isRunning) btnStop.setEnabled(false);
        actions.addView(btnStop);

        // Logs button
        Button btnLogs = createPillButton("📄 Logs", 0xFF262A36, COLOR_TEXT_SECONDARY, new Runnable() {
            public void run() { showLogsDialog(id); }
        });
        actions.addView(btnLogs);

        // Config button
        Button btnConfig = createPillButton("⚙ Config", 0xFF262A36, COLOR_TEXT_PRIMARY, new Runnable() {
            public void run() { showConfigDialog(id, name); }
        });
        actions.addView(btnConfig);

        // Spacer
        View spacer = new View(this);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // Remove button
        Button btnRemove = createPillButton("✕", 0xFF262A36, COLOR_DANGER, new Runnable() {
            public void run() { confirmRemove(id); }
        });
        actions.addView(btnRemove);

        return card;
    }

    private View createStatusChip(String state) {
        int bgCol, textCol;
        if ("RUNNING".equals(state)) {
            bgCol = 0xFF064E3B; textCol = 0xFF34D399; // Emerald
        } else if ("CRASHED".equals(state)) {
            bgCol = 0xFF450A0A; textCol = 0xFFF87171; // Red
        } else if ("STUCK".equals(state)) {
            bgCol = 0xFF451A03; textCol = 0xFFFBBF24; // Amber
        } else {
            bgCol = 0xFF27272A; textCol = 0xFFA1A1AA; // Neutral
        }

        TextView tv = new TextView(this);
        tv.setText("● " + state);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(textCol);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgCol);
        gd.setCornerRadius(dp(12));
        tv.setBackground(gd);

        return tv;
    }

    private View createTagChip(String text, int bgCol, int textCol) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(9);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(textCol);
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgCol);
        gd.setCornerRadius(dp(4));
        tv.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        tv.setLayoutParams(lp);

        return tv;
    }

    private Button createPillButton(String label, int bgColor, int textColor, final Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(8));
        b.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        b.setLayoutParams(lp);

        if (r != null) {
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { r.run(); }
            });
        }
        return b;
    }

    private void act(final String what, final String id) {
        new Thread(new Runnable() { public void run() {
            String msg;
            try {
                JSONObject o = new JSONObject(
                        httpCall("http://127.0.0.1:7823/" + what + "?id=" + id, "POST"));
                msg = o.optBoolean("ok") ? (what + " ok: " + id)
                        : (what + " failed: " + o.optString("error"));
            } catch (Throwable t) {
                msg = what + " failed: " + t;
            }
            final String m = msg;
            runOnUiThread(new Runnable() {
                public void run() {
                    statusLine.setText(m);
                    statusLine.setTextColor(COLOR_ACCENT);
                }
            });
            load();
        }}).start();
    }

    private void confirmRemove(final String id) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Remove Script")
                .setMessage("Are you sure you want to delete script \"" + id + "\"?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        act("remove", id);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLogsDialog(final String id) {
        new Thread(new Runnable() { public void run() {
            try {
                final String log = httpGet("http://127.0.0.1:7823/logs?id=" + id + "&tail=60");
                runOnUiThread(new Runnable() {
                    public void run() {
                        LinearLayout wrap = new LinearLayout(StartActivity.this);
                        wrap.setOrientation(LinearLayout.VERTICAL);
                        int pOuter = dp(14);
                        wrap.setPadding(pOuter, dp(8), pOuter, dp(8));

                        ScrollView sc = new ScrollView(StartActivity.this);
                        int p = dp(12);
                        sc.setPadding(p, p, p, p);
                        GradientDrawable gd = new GradientDrawable();
                        gd.setColor(0xFF0A0C10);
                        gd.setCornerRadius(dp(8));
                        gd.setStroke(dp(1), 0xFF2A2E3D);
                        sc.setBackground(gd);

                        TextView tv = new TextView(StartActivity.this);
                        tv.setText(log.length() > 0 ? log : "(no logs recorded yet)");
                        tv.setTextSize(11);
                        tv.setTypeface(Typeface.MONOSPACE);
                        tv.setTextColor(0xFF38BDF8);
                        tv.setTextIsSelectable(true);
                        tv.setLineSpacing(dp(2), 1.0f);
                        sc.addView(tv);

                        int h = (log.length() < 120) ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(340);
                        wrap.addView(sc, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, h));

                        new AlertDialog.Builder(StartActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                .setTitle("Logs: " + id)
                                .setView(wrap)
                                .setPositiveButton("Close", null)
                                .show();
                    }
                });
            } catch (final Throwable t) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        statusLine.setText("Logs error: " + t.getMessage());
                    }
                });
            }
        }}).start();
    }

    private interface ValueGetter {
        Object get() throws Exception;
    }

    private static class AppItem implements Comparable<AppItem> {
        final String label;
        final String pkg;
        AppItem(String label, String pkg) { this.label = label; this.pkg = pkg; }
        public int compareTo(AppItem o) { return this.label.compareToIgnoreCase(o.label); }
    }

    private void showConfigDialog(final String id, final String scriptName) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String res = httpGet("http://127.0.0.1:7823/schema?id=" + id);
                    final JSONObject obj = new JSONObject(res);
                    final JSONArray schemaArr = obj.optJSONArray("schema");
                    final JSONObject currentVals = obj.optJSONObject("values") != null ?
                            obj.optJSONObject("values") : new JSONObject();

                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (schemaArr == null || schemaArr.length() == 0) {
                                new AlertDialog.Builder(StartActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                        .setTitle("Configuration")
                                        .setMessage("No configurable parameters declared for \"" + scriptName + "\".")
                                        .setPositiveButton("Close", null)
                                        .show();
                                return;
                            }
                            renderConfigModal(id, scriptName, schemaArr, currentVals);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            statusLine.setText("Config load error: " + t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void renderConfigModal(final String id, final String scriptName,
                                   final JSONArray schemaArr, final JSONObject currentVals) {
        final Map<String, ValueGetter> getters = new HashMap<String, ValueGetter>();

        ScrollView sc = new ScrollView(this);
        int pOuter = dp(16);
        sc.setPadding(pOuter, dp(10), pOuter, dp(10));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        sc.addView(container);

        for (int i = 0; i < schemaArr.length(); i++) {
            JSONObject p = schemaArr.optJSONObject(i);
            if (p == null) continue;
            final String pId = p.optString("id");
            final String pType = p.optString("type");
            final String pTitle = p.optString("title", pId);
            String pDesc = p.optString("description", "");

            LinearLayout itemCard = new LinearLayout(this);
            itemCard.setOrientation(LinearLayout.VERTICAL);
            int pPad = dp(12);
            itemCard.setPadding(pPad, pPad, pPad, pPad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF161922);
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(1), 0xFF2A2E3D);
            itemCard.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(10));
            itemCard.setLayoutParams(lp);

            TextView titleTv = new TextView(this);
            titleTv.setText(pTitle);
            titleTv.setTextSize(13);
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleTv.setTextColor(COLOR_TEXT_PRIMARY);
            itemCard.addView(titleTv);

            if (pDesc.length() > 0) {
                TextView descTv = new TextView(this);
                descTv.setText(pDesc);
                descTv.setTextSize(11);
                descTv.setTextColor(COLOR_TEXT_SECONDARY);
                descTv.setPadding(0, dp(2), 0, dp(6));
                itemCard.addView(descTv);
            }

            if ("boolean".equals(pType)) {
                boolean defVal = p.optBoolean("defaultValue", false);
                boolean curVal = currentVals.has(pId) ? currentVals.optBoolean(pId) : defVal;
                final Switch sw = new Switch(this);
                sw.setChecked(curVal);
                sw.setPadding(0, dp(4), 0, 0);
                itemCard.addView(sw);
                getters.put(pId, new ValueGetter() {
                    public Object get() { return Boolean.valueOf(sw.isChecked()); }
                });

            } else if ("app_multiselect".equals(pType)) {
                final Set<String> selected = new HashSet<String>();
                JSONArray curArr = currentVals.optJSONArray(pId);
                if (curArr != null) {
                    for (int k = 0; k < curArr.length(); k++) {
                        selected.add(curArr.optString(k));
                    }
                }
                final Button btnPick = createPillButton("Select Apps (" + selected.size() + " selected)",
                        0xFF2A2E3D, COLOR_TEXT_PRIMARY, null);
                btnPick.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        showAppMultiSelectPicker(pTitle, selected, new Runnable() {
                            public void run() {
                                btnPick.setText("Select Apps (" + selected.size() + " selected)");
                            }
                        });
                    }
                });
                itemCard.addView(btnPick);
                getters.put(pId, new ValueGetter() {
                    public Object get() {
                        JSONArray a = new JSONArray();
                        for (String s : selected) a.put(s);
                        return a;
                    }
                });

            } else if ("number".equals(pType)) {
                final int min = p.optInt("min", 0);
                final int max = p.optInt("max", 100);
                int defVal = p.optInt("defaultValue", min);
                int curVal = currentVals.has(pId) ? currentVals.optInt(pId) : defVal;

                final TextView valTv = new TextView(this);
                valTv.setText("Value: " + curVal);
                valTv.setTextSize(12);
                valTv.setTextColor(COLOR_ACCENT);

                final SeekBar sb = new SeekBar(this);
                sb.setMax(Math.max(1, max - min));
                sb.setProgress(Math.max(0, curVal - min));
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        valTv.setText("Value: " + (min + progress));
                    }
                    public void onStartTrackingTouch(SeekBar seekBar) {}
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });

                itemCard.addView(valTv);
                itemCard.addView(sb);
                getters.put(pId, new ValueGetter() {
                    public Object get() { return Integer.valueOf(min + sb.getProgress()); }
                });

            } else if ("app_map".equals(pType)) {
                final JSONArray fieldDefs = p.optJSONArray("fields");
                final Map<String, JSONObject> appEntries = new HashMap<String, JSONObject>();
                final Set<String> selectedPkgs = new HashSet<String>();

                JSONObject curMap = currentVals.optJSONObject(pId);
                if (curMap != null) {
                    java.util.Iterator<String> it = curMap.keys();
                    while (it.hasNext()) {
                        String pkg = it.next();
                        JSONObject val = curMap.optJSONObject(pkg);
                        if (val != null) {
                            appEntries.put(pkg, val);
                            selectedPkgs.add(pkg);
                        }
                    }
                }

                final LinearLayout mapBox = new LinearLayout(this);
                mapBox.setOrientation(LinearLayout.VERTICAL);
                mapBox.setPadding(0, dp(6), 0, 0);

                final Runnable[] rebuildHolder = new Runnable[1];
                rebuildHolder[0] = new Runnable() {
                    public void run() {
                        mapBox.removeAllViews();
                        if (appEntries.isEmpty()) {
                            TextView emptyTv = new TextView(StartActivity.this);
                            emptyTv.setText("No apps configured in this list. Tap + Add App to add entries.");
                            emptyTv.setTextSize(11);
                            emptyTv.setTextColor(COLOR_TEXT_MUTED);
                            emptyTv.setPadding(0, dp(4), 0, dp(8));
                            mapBox.addView(emptyTv);
                        } else {
                            List<String> sortedPkgs = new ArrayList<String>(appEntries.keySet());
                            Collections.sort(sortedPkgs);
                            for (int k = 0; k < sortedPkgs.size(); k++) {
                                final String pkg = sortedPkgs.get(k);
                                final JSONObject entryObj = appEntries.get(pkg);
                                if (entryObj == null) continue;

                                LinearLayout appCard = new LinearLayout(StartActivity.this);
                                appCard.setOrientation(LinearLayout.VERTICAL);
                                int acPad = dp(10);
                                appCard.setPadding(acPad, acPad, acPad, acPad);
                                GradientDrawable acBg = new GradientDrawable();
                                acBg.setColor(0xFF1E222D);
                                acBg.setCornerRadius(dp(8));
                                acBg.setStroke(dp(1), 0xFF2F3547);
                                appCard.setBackground(acBg);

                                LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                aLp.setMargins(0, 0, 0, dp(8));
                                appCard.setLayoutParams(aLp);

                                // Header row: App Name + Delete button
                                LinearLayout topRow = new LinearLayout(StartActivity.this);
                                topRow.setOrientation(LinearLayout.HORIZONTAL);
                                topRow.setGravity(Gravity.CENTER_VERTICAL);

                                TextView nameTv = new TextView(StartActivity.this);
                                nameTv.setText(getAppLabel(pkg) + "\n" + pkg);
                                nameTv.setTextSize(12);
                                nameTv.setTypeface(Typeface.DEFAULT_BOLD);
                                nameTv.setTextColor(COLOR_TEXT_PRIMARY);
                                topRow.addView(nameTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                                Button btnDel = createPillButton("✕", 0xFF2A2E3D, COLOR_DANGER, null);
                                btnDel.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) {
                                        appEntries.remove(pkg);
                                        selectedPkgs.remove(pkg);
                                        rebuildHolder[0].run();
                                    }
                                });
                                topRow.addView(btnDel);
                                appCard.addView(topRow);

                                // Generic sub-fields renderer
                                if (fieldDefs != null) {
                                    for (int fIdx = 0; fIdx < fieldDefs.length(); fIdx++) {
                                        final JSONObject fDef = fieldDefs.optJSONObject(fIdx);
                                        if (fDef == null) continue;
                                        final String fId = fDef.optString("id");
                                        final String fType = fDef.optString("type");
                                        final String fTitle = fDef.optString("title", fId);

                                        if ("number".equals(fType)) {
                                            final int fMin = fDef.optInt("min", 0);
                                            final int fMax = fDef.optInt("max", 100);
                                            int curVal = entryObj.has(fId) ? entryObj.optInt(fId) : fDef.optInt("defaultValue", fMin);
                                            try { entryObj.put(fId, curVal); } catch (Throwable ignored) {}

                                            final TextView numTv = new TextView(StartActivity.this);
                                            numTv.setText(fTitle + ": " + curVal);
                                            numTv.setTextSize(11);
                                            numTv.setTextColor(COLOR_ACCENT);
                                            numTv.setPadding(0, dp(6), 0, 0);
                                            appCard.addView(numTv);

                                            final SeekBar sb = new SeekBar(StartActivity.this);
                                            sb.setMax(Math.max(1, fMax - fMin));
                                            sb.setProgress(Math.max(0, curVal - fMin));
                                            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                                                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                                                    int v = fMin + progress;
                                                    numTv.setText(fTitle + ": " + v);
                                                    try { entryObj.put(fId, v); } catch (Throwable ignored) {}
                                                }
                                                public void onStartTrackingTouch(SeekBar seekBar) {}
                                                public void onStopTrackingTouch(SeekBar seekBar) {}
                                            });
                                            appCard.addView(sb);

                                        } else if ("boolean".equals(fType)) {
                                            boolean curVal = entryObj.has(fId) ? entryObj.optBoolean(fId) : fDef.optBoolean("defaultValue", false);
                                            try { entryObj.put(fId, curVal); } catch (Throwable ignored) {}

                                            LinearLayout boolRow = new LinearLayout(StartActivity.this);
                                            boolRow.setOrientation(LinearLayout.HORIZONTAL);
                                            boolRow.setGravity(Gravity.CENTER_VERTICAL);
                                            boolRow.setPadding(0, dp(4), 0, 0);

                                            TextView bTitle = new TextView(StartActivity.this);
                                            bTitle.setText(fTitle);
                                            bTitle.setTextSize(11);
                                            bTitle.setTextColor(COLOR_TEXT_SECONDARY);
                                            boolRow.addView(bTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                                            Switch sw = new Switch(StartActivity.this);
                                            sw.setChecked(curVal);
                                            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                                                    try { entryObj.put(fId, isChecked); } catch (Throwable ignored) {}
                                                }
                                            });
                                            boolRow.addView(sw);
                                            appCard.addView(boolRow);

                                        } else if ("choice".equals(fType)) {
                                            final JSONArray optArr = fDef.optJSONArray("options");
                                            final List<String> opts = new ArrayList<String>();
                                            if (optArr != null) {
                                                for (int oi = 0; oi < optArr.length(); oi++) opts.add(optArr.optString(oi));
                                            }
                                            String defVal = fDef.optString("defaultValue", opts.size() > 0 ? opts.get(0) : "");
                                            String curVal = entryObj.has(fId) ? entryObj.optString(fId) : defVal;
                                            try { entryObj.put(fId, curVal); } catch (Throwable ignored) {}

                                            LinearLayout choiceRow = new LinearLayout(StartActivity.this);
                                            choiceRow.setOrientation(LinearLayout.HORIZONTAL);
                                            choiceRow.setGravity(Gravity.CENTER_VERTICAL);
                                            choiceRow.setPadding(0, dp(4), 0, 0);

                                            TextView cTitle = new TextView(StartActivity.this);
                                            cTitle.setText(fTitle + ": ");
                                            cTitle.setTextSize(11);
                                            cTitle.setTextColor(COLOR_TEXT_SECONDARY);
                                            choiceRow.addView(cTitle);

                                            final Button btnChoice = createPillButton(curVal, 0xFF2A2E3D, COLOR_TEXT_PRIMARY, null);
                                            btnChoice.setOnClickListener(new View.OnClickListener() {
                                                public void onClick(View v) {
                                                    if (opts.isEmpty()) return;
                                                    String cur = entryObj.optString(fId, "");
                                                    int nextIdx = (opts.indexOf(cur) + 1) % opts.size();
                                                    String nextVal = opts.get(nextIdx);
                                                    btnChoice.setText(nextVal);
                                                    try { entryObj.put(fId, nextVal); } catch (Throwable ignored) {}
                                                }
                                            });
                                            choiceRow.addView(btnChoice);
                                            appCard.addView(choiceRow);

                                        } else {
                                            String defVal = fDef.optString("defaultValue", "");
                                            String curVal = entryObj.has(fId) ? entryObj.optString(fId) : defVal;
                                            try { entryObj.put(fId, curVal); } catch (Throwable ignored) {}

                                            TextView tTitle = new TextView(StartActivity.this);
                                            tTitle.setText(fTitle);
                                            tTitle.setTextSize(11);
                                            tTitle.setTextColor(COLOR_TEXT_SECONDARY);
                                            tTitle.setPadding(0, dp(4), 0, dp(2));
                                            appCard.addView(tTitle);

                                            final EditText et = new EditText(StartActivity.this);
                                            et.setText(curVal);
                                            et.setTextSize(11);
                                            et.setTextColor(COLOR_TEXT_PRIMARY);
                                            et.setPadding(dp(8), dp(6), dp(8), dp(6));
                                            GradientDrawable etBg = new GradientDrawable();
                                            etBg.setColor(0xFF0F1117);
                                            etBg.setCornerRadius(dp(6));
                                            etBg.setStroke(dp(1), 0xFF2A2E3D);
                                            et.setBackground(etBg);
                                            et.addTextChangedListener(new TextWatcher() {
                                                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                                                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                                                public void afterTextChanged(Editable s) {
                                                    try { entryObj.put(fId, s.toString()); } catch (Throwable ignored) {}
                                                }
                                            });
                                            appCard.addView(et);
                                        }
                                    }
                                }

                                mapBox.addView(appCard);
                            }
                        }
                    }
                };

                rebuildHolder[0].run();
                itemCard.addView(mapBox);

                Button btnAdd = createPillButton("+ Add App", COLOR_ACCENT, 0xFFFFFFFF, null);
                btnAdd.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        showAppMultiSelectPicker("Select Apps to Configure", selectedPkgs, new Runnable() {
                            public void run() {
                                for (String p : selectedPkgs) {
                                    if (!appEntries.containsKey(p)) {
                                        JSONObject newEntry = new JSONObject();
                                        if (fieldDefs != null) {
                                            for (int i = 0; i < fieldDefs.length(); i++) {
                                                JSONObject fd = fieldDefs.optJSONObject(i);
                                                if (fd != null) {
                                                    try { newEntry.put(fd.optString("id"), fd.opt("defaultValue")); } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                        appEntries.put(p, newEntry);
                                    }
                                }
                                List<String> cur = new ArrayList<String>(appEntries.keySet());
                                for (int i = 0; i < cur.size(); i++) {
                                    String p = cur.get(i);
                                    if (!selectedPkgs.contains(p)) {
                                        appEntries.remove(p);
                                    }
                                }
                                rebuildHolder[0].run();
                            }
                        });
                    }
                });
                itemCard.addView(btnAdd);

                getters.put(pId, new ValueGetter() {
                    public Object get() {
                        JSONObject o = new JSONObject();
                        try {
                            for (Map.Entry<String, JSONObject> e : appEntries.entrySet()) {
                                o.put(e.getKey(), e.getValue());
                            }
                        } catch (Throwable ignored) {}
                        return o;
                    }
                });

            } else if ("choice".equals(pType)) {
                final JSONArray optArr = p.optJSONArray("options");
                final List<String> opts = new ArrayList<String>();
                if (optArr != null) {
                    for (int oi = 0; oi < optArr.length(); oi++) opts.add(optArr.optString(oi));
                }
                String defVal = p.optString("defaultValue", opts.size() > 0 ? opts.get(0) : "");
                final String[] curChoice = new String[] { currentVals.has(pId) ? currentVals.optString(pId) : defVal };

                final Button btnChoice = createPillButton(curChoice[0], 0xFF2A2E3D, COLOR_TEXT_PRIMARY, null);
                btnChoice.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (opts.isEmpty()) return;
                        int nextIdx = (opts.indexOf(curChoice[0]) + 1) % opts.size();
                        curChoice[0] = opts.get(nextIdx);
                        btnChoice.setText(curChoice[0]);
                    }
                });
                itemCard.addView(btnChoice);
                getters.put(pId, new ValueGetter() {
                    public Object get() { return curChoice[0]; }
                });

            } else {
                String defVal = p.optString("defaultValue", "");
                String curVal = currentVals.has(pId) ? currentVals.optString(pId) : defVal;

                final EditText et = new EditText(this);
                et.setText(curVal);
                et.setTextColor(COLOR_TEXT_PRIMARY);
                et.setTextSize(13);
                et.setPadding(dp(8), dp(8), dp(8), dp(8));
                GradientDrawable etBg = new GradientDrawable();
                etBg.setColor(0xFF0F1117);
                etBg.setCornerRadius(dp(6));
                etBg.setStroke(dp(1), 0xFF2A2E3D);
                et.setBackground(etBg);
                itemCard.addView(et);
                getters.put(pId, new ValueGetter() {
                    public Object get() { return et.getText().toString(); }
                });
            }

            container.addView(itemCard);
        }

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("⚙ " + scriptName + " Settings")
                .setView(sc)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            JSONObject payload = new JSONObject();
                            for (Map.Entry<String, ValueGetter> entry : getters.entrySet()) {
                                payload.put(entry.getKey(), entry.getValue().get());
                            }
                            saveConfig(id, payload);
                        } catch (Throwable t) {
                            statusLine.setText("Config error: " + t.getMessage());
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String getAppLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private void showAppMultiSelectPicker(final String title, final Set<String> selectedPkgs, final Runnable onUpdated) {
        new Thread(new Runnable() {
            public void run() {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN, null);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                final List<AppItem> apps = new ArrayList<AppItem>();
                Set<String> seen = new HashSet<String>();
                if (ris != null) {
                    for (ResolveInfo ri : ris) {
                        String p = ri.activityInfo.packageName;
                        if (!seen.add(p)) continue;
                        String l = ri.loadLabel(pm).toString();
                        apps.add(new AppItem(l, p));
                    }
                }
                Collections.sort(apps);

                runOnUiThread(new Runnable() {
                    public void run() {
                        LinearLayout wrap = new LinearLayout(StartActivity.this);
                        wrap.setOrientation(LinearLayout.VERTICAL);
                        int p = dp(14);
                        wrap.setPadding(p, dp(8), p, dp(8));

                        final EditText search = new EditText(StartActivity.this);
                        search.setHint("Search launchable apps…");
                        search.setTextColor(COLOR_TEXT_PRIMARY);
                        search.setHintTextColor(COLOR_TEXT_MUTED);
                        search.setTextSize(13);
                        GradientDrawable sBg = new GradientDrawable();
                        sBg.setColor(0xFF161922);
                        sBg.setCornerRadius(dp(8));
                        sBg.setStroke(dp(1), 0xFF2A2E3D);
                        search.setBackground(sBg);
                        search.setPadding(dp(10), dp(8), dp(10), dp(8));
                        wrap.addView(search);

                        final ScrollView sc = new ScrollView(StartActivity.this);
                        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
                        final LinearLayout listLayout = new LinearLayout(StartActivity.this);
                        listLayout.setOrientation(LinearLayout.VERTICAL);
                        listLayout.setPadding(0, dp(8), 0, 0);
                        sc.addView(listLayout);
                        wrap.addView(sc);

                        final Runnable renderList = new Runnable() {
                            public void run() {
                                listLayout.removeAllViews();
                                String q = search.getText().toString().trim().toLowerCase(Locale.US);
                                for (int i = 0; i < apps.size(); i++) {
                                    final AppItem ai = apps.get(i);
                                    if (q.length() > 0 && !ai.label.toLowerCase(Locale.US).contains(q) && !ai.pkg.toLowerCase(Locale.US).contains(q)) {
                                        continue;
                                    }
                                    final CheckBox cb = new CheckBox(StartActivity.this);
                                    cb.setText(ai.label + "\n" + ai.pkg);
                                    cb.setTextSize(12);
                                    cb.setTextColor(COLOR_TEXT_PRIMARY);
                                    cb.setChecked(selectedPkgs.contains(ai.pkg));
                                    cb.setPadding(dp(6), dp(6), dp(6), dp(6));
                                    cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                                            if (isChecked) selectedPkgs.add(ai.pkg);
                                            else selectedPkgs.remove(ai.pkg);
                                        }
                                    });
                                    listLayout.addView(cb);
                                }
                            }
                        };

                        search.addTextChangedListener(new TextWatcher() {
                            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                            public void onTextChanged(CharSequence s, int start, int before, int count) { renderList.run(); }
                            public void afterTextChanged(Editable s) {}
                        });

                        renderList.run();

                        new AlertDialog.Builder(StartActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                .setTitle(title)
                                .setView(wrap)
                                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        if (onUpdated != null) onUpdated.run();
                                    }
                                })
                                .show();
                    }
                });
            }
        }).start();
    }

    private void saveConfig(final String id, final JSONObject payload) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    httpPostJson("http://127.0.0.1:7823/config?id=" + id, payload.toString());
                    runOnUiThread(new Runnable() {
                        public void run() {
                            statusLine.setText("Settings saved for \"" + id + "\" (live update dispatched)");
                            statusLine.setTextColor(COLOR_SUCCESS);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            statusLine.setText("Config save error: " + t.getMessage());
                            statusLine.setTextColor(COLOR_DANGER);
                        }
                    });
                }
            }
        }).start();
    }

    private static String httpPostJson(String url, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.close();
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
        if (in != null) in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String httpCall(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
        if (in != null) in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private String formatDate(long ts) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ts));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
