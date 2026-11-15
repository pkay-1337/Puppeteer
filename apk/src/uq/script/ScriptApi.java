package uq.script;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The facade handed to every script.
 * Scope: ui/toast/overlay, usage stats, scheduler (cron/interval), notify, net,
 * file (sandboxed), sys (termux, vibrate), self (id/args/log/stop).
 */
public class ScriptApi {

    public interface SchedulerFacade {
        void cron(String scriptId, String schedId, String expr, boolean persist);
        void every(String scriptId, String schedId, long ms, boolean persist);
        void unschedule(String scriptId, String schedId);
    }

    private final Context ctx;
    private final String id;
    private final Bundle args;
    private final File dir;
    private final Logger log;
    private final SchedulerFacade sched;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopRequested;

    // Overlay state tracking
    private final Map<Integer, View> overlays = new HashMap<Integer, View>();
    private final Map<Integer, TextView> overlayCards = new HashMap<Integer, TextView>();
    private final AtomicInteger nextOverlayId = new AtomicInteger(1);

    public ScriptApi(Context ctx, String id, Bundle args, File dir, Logger log) {
        this(ctx, id, args, dir, log, null);
    }

    public ScriptApi(Context ctx, String id, Bundle args, File dir, Logger log, SchedulerFacade sched) {
        this.ctx = ctx;
        this.id = id;
        this.args = args;
        this.dir = dir;
        this.log = log;
        this.sched = sched;
    }

    // ---- self ----

    public String id() { return id; }

    public Context context() { return ctx; }

    public Bundle args() { return new Bundle(args); }

    public File scriptDir() { return dir; }

    public String logPath() { return log.path(); }

    public void log(String msg) { log.write(ts() + " " + msg); }

    public void toast(final String text) {
        main.post(new Runnable() { public void run() {
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
        }});
    }

    public void vibrate(int ms) {
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.vibrate(ms);
    }

    /** Graceful self-termination: main() should exit soon (sleep is interrupted). */
    public void stop() {
        stopRequested = true;
        Thread.currentThread().interrupt();
    }

    public boolean stopRequested() { return stopRequested; }

    // ---- scheduler (FR-CRON-3) ----

    public void cron(String expr, String scheduleId, boolean persist) {
        if (sched != null) {
            sched.cron(id, scheduleId, expr, persist);
            log("scheduled cron [" + expr + "] id=" + scheduleId + " persist=" + persist);
        } else {
            log("scheduler unavailable");
        }
    }

    public void every(long ms, String scheduleId, boolean persist) {
        if (sched != null) {
            sched.every(id, scheduleId, ms, persist);
            log("scheduled interval [" + ms + "ms] id=" + scheduleId + " persist=" + persist);
        } else {
            log("scheduler unavailable");
        }
    }

    public void unschedule(String scheduleId) {
        if (sched != null) {
            sched.unschedule(id, scheduleId);
            log("unscheduled id=" + scheduleId);
        }
    }

    // ---- notify ----

    public void notify(int nid, String title, String text) {
        notify(nid, title, text, "uq");
    }

    public void notify(int nid, String title, String text, String channel) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    channel, "UQ Shell: " + channel, NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(ctx, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .build();
            nm.notify(nid, n);
        } catch (Throwable t) {
            log("notify failed: " + t);
        }
    }

    public void cancelNotify(int nid) {
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(nid);
    }

    // ---- usage stats (FR-API-2) ----

    public static class Usage {
        public final String pkg;
        public final long totalMs;
        public final long lastUsedMs;

        public Usage(String pkg, long totalMs, long lastUsedMs) {
            this.pkg = pkg;
            this.totalMs = totalMs;
            this.lastUsedMs = lastUsedMs;
        }
    }

    public boolean hasUsagePermission() {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public List<Usage> usageRange(long startMs, long endMs) {
        if (!hasUsagePermission()) {
            throw new IllegalStateException("Usage access not granted. Please enable in Settings -> Apps -> Special app access -> Usage access -> UQ Shell");
        }
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) throw new IllegalStateException("UsageStatsManager unavailable");
        Map<String, UsageStats> agg = usm.queryAndAggregateUsageStats(startMs, endMs);
        List<Usage> list = new ArrayList<Usage>();
        if (agg != null) {
            for (UsageStats us : agg.values()) {
                long total = us.getTotalTimeInForeground();
                if (total > 0) {
                    list.add(new Usage(us.getPackageName(), total, us.getLastTimeUsed()));
                }
            }
        }
        Collections.sort(list, new Comparator<Usage>() {
            public int compare(Usage a, Usage b) {
                return (b.totalMs < a.totalMs) ? -1 : ((b.totalMs == a.totalMs) ? 0 : 1);
            }
        });
        return list;
    }

    public List<Usage> usageDays(int days) {
        long end = System.currentTimeMillis();
        long start = end - ((long) Math.max(1, days) * 86400000L);
        return usageRange(start, end);
    }

    public List<Usage> usage24h() {
        return usageDays(1);
    }

    public List<Usage> usageToday() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return usageRange(cal.getTimeInMillis(), System.currentTimeMillis());
    }

    public List<Usage> topPackages(int n, int days) {
        List<Usage> all = usageDays(days);
        if (all.size() <= n) return all;
        return new ArrayList<Usage>(all.subList(0, n));
    }

    public List<Usage> topPackagesToday(int n) {
        List<Usage> all = usageToday();
        if (all.size() <= n) return all;
        return new ArrayList<Usage>(all.subList(0, n));
    }

    public String appLabel(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null) return label.toString();
        } catch (Throwable ignored) { }
        return pkg;
    }

    // ---- overlay UI (FR-API-1) ----

    public boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(ctx);
    }

    public int overlayView(final View v, final int x, final int y, final int w, final int h) {
        if (!hasOverlayPermission()) {
            throw new IllegalStateException("Display over other apps not granted. Please enable in Settings -> Apps -> Manage apps -> UQ Shell -> Other permissions -> Display over other apps");
        }
        final int handle = nextOverlayId.getAndIncrement();
        final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w <= 0 ? WindowManager.LayoutParams.WRAP_CONTENT : w,
                h <= 0 ? WindowManager.LayoutParams.WRAP_CONTENT : h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;

        synchronized (overlays) {
            overlays.put(handle, v);
        }

        main.post(new Runnable() {
            public void run() {
                try {
                    wm.addView(v, lp);
                } catch (Throwable t) {
                    log("overlayView failed: " + t);
                }
            }
        });
        return handle;
    }

    public int overlayCard(final List<String> lines) {
        return overlayCard(lines, dp(20), dp(100));
    }

    public int overlayCard(final List<String> lines, final int startX, final int startY) {
        if (!hasOverlayPermission()) {
            throw new IllegalStateException("Display over other apps not granted. Please enable in Settings -> Apps -> Manage apps -> UQ Shell -> Other permissions -> Display over other apps");
        }
        final int handle = nextOverlayId.getAndIncrement();
        final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = startX;
        lp.y = startY;

        final CountDownLatch latch = new CountDownLatch(1);

        main.post(new Runnable() {
            public void run() {
                try {
                    int p = dp(12);
                    final LinearLayout card = new LinearLayout(ctx);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(p, p, p, p);

                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xF0181A22); // 94% sleek dark slate
                    bg.setCornerRadius(dp(12));
                    bg.setStroke(dp(1), 0x33FFFFFF);
                    card.setBackground(bg);

                    // Header row: Script title & Close (X) button
                    LinearLayout header = new LinearLayout(ctx);
                    header.setOrientation(LinearLayout.HORIZONTAL);
                    header.setGravity(Gravity.CENTER_VERTICAL);
                    card.addView(header, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    TextView title = new TextView(ctx);
                    title.setText("UQ Shell · " + id);
                    title.setTextSize(12);
                    title.setTextColor(0xFF38BDF8); // Sky blue
                    title.setTypeface(Typeface.DEFAULT_BOLD);
                    header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    TextView closeBtn = new TextView(ctx);
                    closeBtn.setText(" ✕ ");
                    closeBtn.setTextSize(14);
                    closeBtn.setTextColor(0xFFEF4444); // Red
                    closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
                    closeBtn.setPadding(dp(6), 0, dp(6), 0);
                    closeBtn.setClickable(true);
                    closeBtn.setOnTouchListener(new View.OnTouchListener() {
                        public boolean onTouch(View v, MotionEvent event) {
                            if (event.getAction() == MotionEvent.ACTION_UP) {
                                removeOverlay(handle);
                                stop();
                            }
                            return true;
                        }
                    });
                    header.addView(closeBtn, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    // Body: Content lines in monospace
                    final TextView body = new TextView(ctx);
                    body.setTextSize(11);
                    body.setTextColor(0xFFF1F5F9);
                    body.setTypeface(Typeface.MONOSPACE);
                    body.setPadding(0, dp(6), 0, 0);
                    body.setText(formatLines(lines));
                    card.addView(body);

                    // Smooth draggable behavior on card
                    card.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX, initialY;
                        private float initialTouchX, initialTouchY;
                        private boolean moved;

                        public boolean onTouch(View v, MotionEvent event) {
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    initialX = lp.x;
                                    initialY = lp.y;
                                    initialTouchX = event.getRawX();
                                    initialTouchY = event.getRawY();
                                    moved = false;
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    int dx = (int) (event.getRawX() - initialTouchX);
                                    int dy = (int) (event.getRawY() - initialTouchY);
                                    if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) {
                                        moved = true;
                                        lp.x = initialX + dx;
                                        lp.y = initialY + dy;
                                        try { wm.updateViewLayout(card, lp); } catch (Throwable ignored) {}
                                    }
                                    return true;
                                case MotionEvent.ACTION_UP:
                                    return true;
                            }
                            return false;
                        }
                    });

                    wm.addView(card, lp);
                    synchronized (overlays) {
                        overlays.put(handle, card);
                        overlayCards.put(handle, body);
                    }
                } catch (Throwable t) {
                    log("overlayCard failed: " + t);
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        return handle;
    }

    public void updateOverlayCard(final int handle, final List<String> lines) {
        main.post(new Runnable() {
            public void run() {
                TextView body;
                synchronized (overlays) {
                    body = overlayCards.get(handle);
                }
                if (body != null) {
                    body.setText(formatLines(lines));
                }
            }
        });
    }

    public void removeOverlay(final int handle) {
        final View v;
        synchronized (overlays) {
            v = overlays.remove(handle);
            overlayCards.remove(handle);
        }
        if (v != null) {
            main.post(new Runnable() {
                public void run() {
                    try {
                        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                        if (wm != null) wm.removeView(v);
                    } catch (Throwable ignored) { }
                }
            });
        }
    }

    public void cleanup() {
        final List<View> views = new ArrayList<View>();
        synchronized (overlays) {
            views.addAll(overlays.values());
            overlays.clear();
            overlayCards.clear();
        }
        if (!views.isEmpty()) {
            main.post(new Runnable() {
                public void run() {
                    try {
                        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                        if (wm != null) {
                            for (View v : views) {
                                try { wm.removeView(v); } catch (Throwable ignored) { }
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            });
        }
    }

    // ---- net ----

    public static class Http {
        public final int status;
        public final String body;
        public Http(int status, String body) { this.status = status; this.body = body; }
    }

    public Http httpGet(String url) throws IOException { return http("GET", url, null, null); }

    public Http httpGet(String url, Map<String, String> headers) throws IOException {
        return http("GET", url, null, headers);
    }

    public Http httpPost(String url, String body) throws IOException {
        return http("POST", url, body, null);
    }

    public Http httpPost(String url, String body, Map<String, String> headers) throws IOException {
        return http("POST", url, body, headers);
    }

    private Http http(String method, String url, String body, Map<String, String> headers)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        if (headers != null) {
            for (Map.Entry<String, String> en : headers.entrySet())
                c.setRequestProperty(en.getKey(), en.getValue());
        }
        if (body != null) {
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int status = c.getResponseCode();
        InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
        }
        return new Http(status, new String(bo.toByteArray(), "UTF-8"));
    }

    // ---- file (sandboxed to app files, script dir, shared storage) ----

    private File sandbox(String path) throws IOException {
        File f = new File(path).getCanonicalFile();
        String p = f.getAbsolutePath();
        String[] roots = new String[] {
                ctx.getFilesDir().getCanonicalPath(),
                dir.getCanonicalPath(),
                "/storage/emulated"
        };
        for (String r : roots) {
            if (p.equals(r) || p.startsWith(r + File.separator)) return f;
        }
        throw new SecurityException("path outside sandbox: " + path);
    }

    public String read(String path) throws IOException {
        File f = sandbox(path);
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    public void write(String path, String text) throws IOException {
        writeAll(sandbox(path), text, false);
    }

    public void append(String path, String text) throws IOException {
        writeAll(sandbox(path), text, true);
    }

    public List<String> list(String path) throws IOException {
        File f = sandbox(path);
        List<String> out = new ArrayList<String>();
        String[] a = f.list();
        if (a != null) for (String s : a) out.add(s);
        return out;
    }

    private static void writeAll(File f, String text, boolean append) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, append), "UTF-8");
        w.write(text);
        w.close();
    }

    // ---- sys ----

    /** Fire a command inside the Termux environment (RUN_COMMAND intent).
     *  Needs Termux's allow-external-apps=true in ~/.termux/termux.properties. */
    public void termux(String command) {
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", command});
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            ctx.startService(i);
            log("termux: RUN_COMMAND sent");
        } catch (Throwable t) {
            log("termux: failed: " + t);
        }
    }

    // ---- firewall / vpn ----

    /** Check if on-device firewall capability is prepared / authorized by the user. */
    public boolean isFirewallPrepared() {
        try {
            Class<?> cls = Class.forName("com.usq.shell.UqVpnService");
            java.lang.reflect.Method m = cls.getMethod("isPrepared", Context.class);
            return ((Boolean) m.invoke(null, ctx)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Check if firewall is currently blocking internet traffic. */
    public boolean isInternetBlocked() {
        try {
            Class<?> cls = Class.forName("com.usq.shell.UqVpnService");
            java.lang.reflect.Method m = cls.getMethod("isBlocking");
            return ((Boolean) m.invoke(null)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Block all internet traffic for X seconds (excluding Termux & UQ Shell). */
    public void blockInternet(int seconds) {
        try {
            Class<?> cls = Class.forName("com.usq.shell.UqVpnService");
            java.lang.reflect.Method m = cls.getMethod("startBlock", Context.class, ArrayList.class, int.class);
            m.invoke(null, ctx, null, seconds);
            log("firewall: internet blocked for " + seconds + "s");
        } catch (Throwable t) {
            log("firewall error: " + t);
        }
    }

    /** Block internet traffic for specific application packages for X seconds. */
    public void blockAppsInternet(List<String> packages, int seconds) {
        try {
            Class<?> cls = Class.forName("com.usq.shell.UqVpnService");
            java.lang.reflect.Method m = cls.getMethod("startBlock", Context.class, ArrayList.class, int.class);
            ArrayList<String> list = (packages != null) ? new ArrayList<String>(packages) : null;
            m.invoke(null, ctx, list, seconds);
            log("firewall: apps blocked for " + seconds + "s: " + list);
        } catch (Throwable t) {
            log("firewall error: " + t);
        }
    }

    /** Immediately unblock and restore internet traffic. */
    public void restoreInternet() {
        try {
            Class<?> cls = Class.forName("com.usq.shell.UqVpnService");
            java.lang.reflect.Method m = cls.getMethod("stopBlock", Context.class);
            m.invoke(null, ctx);
            log("firewall: internet restored");
        } catch (Throwable t) {
            log("firewall restore error: " + t);
        }
    }

    /** Check whether WRITE_SECURE_SETTINGS permission is granted (needed for display daltonizer/grayscale). */
    public boolean hasWriteSecureSettings() {
        try {
            return ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Enable or disable system-wide display Grayscale (monochrome daltonizer). Returns true if applied. */
    public boolean setGrayscale(boolean enable) {
        try {
            android.provider.Settings.Secure.putInt(
                    ctx.getContentResolver(),
                    "accessibility_display_daltonizer_enabled",
                    enable ? 1 : 0
            );
            if (enable) {
                android.provider.Settings.Secure.putInt(
                        ctx.getContentResolver(),
                        "accessibility_display_daltonizer",
                        0 // 0 = Monochromacy (Grayscale)
                );
            }
            log("display: grayscale set to " + enable);
            return true;
        } catch (Throwable t) {
            log("display: setGrayscale failed: " + t.getMessage());
            return false;
        }
    }

    /** Check if system display is currently in Grayscale mode. */
    public boolean isGrayscale() {
        try {
            int enabled = android.provider.Settings.Secure.getInt(
                    ctx.getContentResolver(),
                    "accessibility_display_daltonizer_enabled",
                    0
            );
            int mode = android.provider.Settings.Secure.getInt(
                    ctx.getContentResolver(),
                    "accessibility_display_daltonizer",
                    -1
            );
            return (enabled == 1 && mode == 0);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- In-Memory Inter-Script Bus ----

    /** Shared in-memory event and state bus accessible across all scripts running in this process. */
    public static final ConcurrentHashMap<String, Object> BUS = new ConcurrentHashMap<String, Object>();

    public void busSet(String key, Object val) {
        if (val == null) BUS.remove(key);
        else BUS.put(key, val);
    }

    public Object busGet(String key) {
        return BUS.get(key);
    }

    public long busGetLong(String key, long def) {
        Object v = BUS.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        return def;
    }

    public boolean busGetBool(String key, boolean def) {
        Object v = BUS.get(key);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return def;
    }

    // ---- helpers ----

    private static String formatLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static String ts() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
