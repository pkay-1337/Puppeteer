import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * fidget_interrupter.uq.java — Mindful Pattern Interrupt & Fidget Loop Breaker.
 *
 * Detects subconscious "Dopamine Hunting" / "App Hopping":
 *   1. Rapid App Hopping: Switching between apps >= 5 times in 60 seconds with < 12s average dwell.
 *   2. Launcher Lingering: Aimlessly scrolling the app drawer/launcher for >= 45 continuous seconds.
 *   3. The Pattern Interrupt:
 *      - Double-tap haptic buzz (a physical "tap on the wrist").
 *      - Temporary 15-second color drain (hardware monochrome daltonizer) to reset visual stimulation.
 *      - Sleek floating heads-up card at the top of the screen asking:
 *        "What are you looking for? Dump restless thoughts into Journal or pause."
 *      - One-tap redirect to Journal (com.pk.journal).
 *      - Auto-dismisses in 15 seconds so you are never locked or stranded.
 *      - 3-minute cooldown between triggers to prevent nuisance alerts.
 */
public class FidgetInterrupter extends UqScript {

    private static final String JOURNAL_PKG = "com.pk.journal";

    private static class AppHop {
        public final String pkg;
        public final long timestamp;

        public AppHop(String pkg, long timestamp) {
            this.pkg = pkg;
            this.timestamp = timestamp;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View activeAlertView;
    private volatile boolean isAlertShowing = false;
    private Runnable autoDismissRunnable = null;

    // Configurable parameters
    private int hopThreshold = 5;               // 5 app switches in window
    private int windowSeconds = 60;             // 60-second rolling window
    private int dwellThresholdSeconds = 12;     // Avg dwell < 12s considered hopping
    private int launcherLingerSeconds = 45;     // 45s continuous launcher idling
    private int grayscaleDurationSeconds = 15;  // 15s monochrome dopamine reset
    private int cooldownMinutes = 3;            // 3-minute debounce between alerts
    private boolean vibrateOnTrigger = true;
    private boolean enableGrayscale = true;

    // Runtime state
    private final List<AppHop> hopHistory = Collections.synchronizedList(new ArrayList<AppHop>());
    private String lastForegroundPkg = null;
    private long launcherDwellStart = 0L;
    private long lastTriggerTime = 0L;
    private boolean originalGrayscaleState = false;
    private Runnable restoreGrayscaleRunnable = null;

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addNumber("hop_threshold", "App Switch Threshold",
                        "Number of app switches in the time window to flag fidgeting", 5, 3, 10)
                .addNumber("window_seconds", "Rolling Window (Seconds)",
                        "Rolling evaluation duration in seconds", 60, 30, 180)
                .addNumber("dwell_threshold_seconds", "Max Dwell Time (Seconds)",
                        "Average time in each app below which it counts as hopping", 12, 5, 30)
                .addNumber("launcher_linger_seconds", "Launcher Idling Threshold (Seconds)",
                        "Continuous seconds on home screen/drawer before trigger", 45, 20, 120)
                .addNumber("grayscale_duration_seconds", "Grayscale Reset (Seconds)",
                        "Duration of temporary monochrome color drain (0 to disable)", 15, 0, 60)
                .addNumber("cooldown_minutes", "Cooldown Between Alerts (Minutes)",
                        "Minimum wait between triggers to prevent spam", 3, 1, 15)
                .addBoolean("vibrate_on_trigger", "Double-Tap Haptic Alert",
                        "Physical vibration pulse to break autopilot", true)
                .addBoolean("enable_grayscale", "Temporary Grayscale Drain",
                        "Drain screen color for a few seconds to kill dopamine stimulation", true);
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        applyConfig(newArgs);
    }

    private void applyConfig(Bundle b) {
        if (b == null) return;
        hopThreshold = b.getInt("hop_threshold", hopThreshold);
        windowSeconds = b.getInt("window_seconds", windowSeconds);
        dwellThresholdSeconds = b.getInt("dwell_threshold_seconds", dwellThresholdSeconds);
        launcherLingerSeconds = b.getInt("launcher_linger_seconds", launcherLingerSeconds);
        grayscaleDurationSeconds = b.getInt("grayscale_duration_seconds", grayscaleDurationSeconds);
        cooldownMinutes = b.getInt("cooldown_minutes", cooldownMinutes);
        vibrateOnTrigger = b.getBoolean("vibrate_on_trigger", vibrateOnTrigger);
        enableGrayscale = b.getBoolean("enable_grayscale", enableGrayscale);
    }

    @Override
    public void main(final ScriptApi api, Bundle args) throws Exception {
        api.log("fidget_interrupter: starting Mindful Fidget Interrupter");
        applyConfig(args);

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("fidget_interrupter error: Context unavailable");
            return;
        }

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            api.log("fidget_interrupter error: UsageStatsManager unavailable");
            return;
        }

        api.toast("🧠 Mindful Fidget Interrupter active");

        while (!api.stopRequested()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;

            long nowMs = System.currentTimeMillis();

            // Skip tracking if screen is turned off
            if (!isScreenOn(ctx)) {
                hopHistory.clear();
                launcherDwellStart = 0L;
                lastForegroundPkg = null;
                continue;
            }

            // Detect current foreground package
            String fgPkg = getForegroundPackage(usm, nowMs, lastForegroundPkg);
            if (fgPkg == null) continue;

            // Skip internal exempt tools (Termux, Puppeteer host, etc.)
            if (isExempt(fgPkg)) {
                lastForegroundPkg = fgPkg;
                continue;
            }

            // Check if app transition occurred
            if (!fgPkg.equals(lastForegroundPkg)) {
                recordHop(fgPkg, nowMs);
                lastForegroundPkg = fgPkg;

                // Reset launcher dwell when exiting launcher
                if (isLauncher(fgPkg)) {
                    launcherDwellStart = nowMs;
                } else {
                    launcherDwellStart = 0L;
                }
            } else {
                // Same app continues
                if (isLauncher(fgPkg)) {
                    if (launcherDwellStart == 0L) launcherDwellStart = nowMs;
                }
            }

            // Check cooldown
            long cooldownMs = cooldownMinutes * 60000L;
            if (nowMs - lastTriggerTime < cooldownMs) {
                continue;
            }

            // Evaluation: 1. Rapid App Hopping Loop
            if (isHoppingLoop(nowMs)) {
                lastTriggerTime = nowMs;
                hopHistory.clear();
                api.log("fidget_interrupter: triggered RAPID_HOPPING alert");
                triggerInterrupt(ctx, api, "rapid_hopping");
                continue;
            }

            // Evaluation: 2. Launcher Lingering / Aimless Scrolling
            if (launcherDwellStart > 0L && (nowMs - launcherDwellStart >= (launcherLingerSeconds * 1000L))) {
                lastTriggerTime = nowMs;
                launcherDwellStart = 0L;
                api.log("fidget_interrupter: triggered LAUNCHER_LINGER alert");
                triggerInterrupt(ctx, api, "launcher_linger");
            }
        }

        dismissAlert(api);
        api.log("fidget_interrupter: main loop exited");
    }

    private void recordHop(String pkg, long nowMs) {
        synchronized (hopHistory) {
            hopHistory.add(new AppHop(pkg, nowMs));
            long cutoff = nowMs - (windowSeconds * 1000L);
            while (!hopHistory.isEmpty() && hopHistory.get(0).timestamp < cutoff) {
                hopHistory.remove(0);
            }
        }
    }

    private boolean isHoppingLoop(long nowMs) {
        synchronized (hopHistory) {
            int size = hopHistory.size();
            if (size < hopThreshold) return false;

            int distinctSwitches = 0;
            long totalDwellMs = 0L;

            for (int i = 1; i < size; i++) {
                AppHop prev = hopHistory.get(i - 1);
                AppHop curr = hopHistory.get(i);
                if (!prev.pkg.equals(curr.pkg)) {
                    distinctSwitches++;
                    totalDwellMs += (curr.timestamp - prev.timestamp);
                }
            }

            if (distinctSwitches < hopThreshold - 1) return false;

            long avgDwellSec = (distinctSwitches > 0) ? (totalDwellMs / distinctSwitches / 1000L) : 999L;
            return (avgDwellSec <= dwellThresholdSeconds);
        }
    }

    private void triggerInterrupt(final Context ctx, final ScriptApi api, final String reason) {
        // 1. Double-pulse haptic pattern
        if (vibrateOnTrigger) {
            try {
                api.vibrate(80);
                mainHandler.postDelayed(new Runnable() {
                    public void run() {
                        try { api.vibrate(120); } catch (Throwable ignored) {}
                    }
                }, 160);
            } catch (Throwable ignored) {}
        }

        // 2. Temporary Grayscale Circuit Breaker
        if (enableGrayscale && grayscaleDurationSeconds > 0) {
            try {
                originalGrayscaleState = api.isGrayscale();
                if (!originalGrayscaleState) {
                    api.setGrayscale(true);
                    if (restoreGrayscaleRunnable != null) {
                        mainHandler.removeCallbacks(restoreGrayscaleRunnable);
                    }
                    restoreGrayscaleRunnable = new Runnable() {
                        public void run() {
                            try {
                                api.setGrayscale(false);
                                api.log("fidget_interrupter: restored screen color");
                            } catch (Throwable ignored) {}
                        }
                    };
                    mainHandler.postDelayed(restoreGrayscaleRunnable, grayscaleDurationSeconds * 1000L);
                }
            } catch (Throwable t) {
                api.log("fidget_interrupter: grayscale toggle failed: " + t);
            }
        }

        // 3. Show Heads-Up Pattern Interrupt Card
        mainHandler.post(new Runnable() {
            public void run() {
                showAlertView(ctx, api, reason);
            }
        });
    }

    private void showAlertView(final Context ctx, final ScriptApi api, final String reason) {
        try {
            if (wm == null) {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            }
            if (activeAlertView != null) {
                try { wm.removeView(activeAlertView); } catch (Throwable ignored) {}
                activeAlertView = null;
            }

            activeAlertView = buildInterruptCard(ctx, api, reason);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(32, ctx);

            wm.addView(activeAlertView, lp);
            isAlertShowing = true;
            api.log("fidget_interrupter: pattern interrupt card displayed");

            // Auto-dismiss after 16 seconds so user is never stuck
            if (autoDismissRunnable != null) {
                mainHandler.removeCallbacks(autoDismissRunnable);
            }
            autoDismissRunnable = new Runnable() {
                public void run() {
                    dismissAlert(api);
                }
            };
            mainHandler.postDelayed(autoDismissRunnable, 16000L);

        } catch (Throwable t) {
            api.log("fidget_interrupter: showAlertView failed: " + t);
            isAlertShowing = false;
        }
    }

    private View buildInterruptCard(final Context ctx, final ScriptApi api, String reason) {
        final boolean isLinger = "launcher_linger".equals(reason);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int rPad = dp(16, ctx);
        root.setPadding(rPad, 0, rPad, 0);

        // Container Card
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int cPad = dp(16, ctx);
        card.setPadding(cPad, cPad, cPad, cPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xF511141C); // 96% Dark Obsidian
        cardBg.setCornerRadius(dp(18, ctx));
        cardBg.setStroke(dp(1, ctx), 0xFF38BDF8); // Electric cyan accent border
        card.setBackground(cardBg);

        // Top Pill Badge
        LinearLayout badgeRow = new LinearLayout(ctx);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView pill = new TextView(ctx);
        pill.setText(isLinger ? "🧠 IDLE LINGERING DETECTED" : "🧠 APP HOPPING DETECTED");
        pill.setTextSize(10);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextColor(0xFF38BDF8);
        pill.setPadding(dp(8, ctx), dp(3, ctx), dp(8, ctx), dp(3, ctx));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x2638BDF8);
        pillBg.setCornerRadius(dp(8, ctx));
        pill.setBackground(pillBg);
        badgeRow.addView(pill);

        TextView timerTv = new TextView(ctx);
        timerTv.setText("  •  Breathing pause");
        timerTv.setTextSize(11);
        timerTv.setTextColor(0xFF94A3B8);
        badgeRow.addView(timerTv);

        card.addView(badgeRow);

        // Question Title
        TextView title = new TextView(ctx);
        title.setText(isLinger
                ? "Aimlessly scrolling the app drawer?"
                : "Pause: What are you looking for?");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, dp(8, ctx), 0, dp(4, ctx));
        card.addView(title);

        // Subtitle Body
        TextView body = new TextView(ctx);
        body.setText(isLinger
                ? "You've been lingering on the launcher for 45s without opening anything. Step back or journal your thought."
                : "Switched apps 5 times in 60s with under 10s dwell. Your brain is seeking stimulation on autopilot.");
        body.setTextSize(12);
        body.setTextColor(0xFFCBD5E1);
        body.setLineSpacing(dp(2, ctx), 1.15f);
        card.addView(body);

        // Action Buttons Row
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, dp(12, ctx), 0, 0);

        // Primary: Dump in Journal
        Button journalBtn = new Button(ctx);
        journalBtn.setText("✍️  Dump in Journal");
        journalBtn.setTextSize(12);
        journalBtn.setTypeface(Typeface.DEFAULT_BOLD);
        journalBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable jBg = new GradientDrawable();
        jBg.setColor(0xFF0284C7); // Sky blue 600
        jBg.setCornerRadius(dp(10, ctx));
        journalBtn.setBackground(jBg);
        journalBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    api.vibrate(40);
                    dismissAlert(api);
                    restoreGrayscaleNow(api);
                    Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(JOURNAL_PKG);
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(intent);
                    }
                } catch (Throwable ignored) {}
            }
        });
        LinearLayout.LayoutParams jLp = new LinearLayout.LayoutParams(
                0, dp(40, ctx), 1.0f);
        jLp.setMargins(0, 0, dp(8, ctx), 0);
        btnRow.addView(journalBtn, jLp);

        // Secondary: Dismiss & Breathe
        Button dismissBtn = new Button(ctx);
        dismissBtn.setText("🧘  Dismiss");
        dismissBtn.setTextSize(12);
        dismissBtn.setTypeface(Typeface.DEFAULT_BOLD);
        dismissBtn.setTextColor(0xFFE2E8F0);
        GradientDrawable dBg = new GradientDrawable();
        dBg.setColor(0xFF1E293B); // Slate 800
        dBg.setCornerRadius(dp(10, ctx));
        dismissBtn.setBackground(dBg);
        dismissBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                api.vibrate(30);
                dismissAlert(api);
                restoreGrayscaleNow(api);
            }
        });
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40, ctx));
        btnRow.addView(dismissBtn, dLp);

        card.addView(btnRow);
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void dismissAlert(final ScriptApi api) {
        if (!isAlertShowing) return;
        isAlertShowing = false;

        if (autoDismissRunnable != null) {
            mainHandler.removeCallbacks(autoDismissRunnable);
            autoDismissRunnable = null;
        }

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && activeAlertView != null) {
                        wm.removeView(activeAlertView);
                        activeAlertView = null;
                        if (api != null) api.log("fidget_interrupter: card dismissed");
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void restoreGrayscaleNow(ScriptApi api) {
        if (restoreGrayscaleRunnable != null) {
            mainHandler.removeCallbacks(restoreGrayscaleRunnable);
            restoreGrayscaleRunnable = null;
        }
        try {
            if (api != null && !originalGrayscaleState) {
                api.setGrayscale(false);
            }
        } catch (Throwable ignored) {}
    }

    private boolean isLauncher(String pkg) {
        if (pkg == null) return false;
        return (pkg.equals("com.miui.home") || pkg.contains("launcher") || pkg.contains("home"));
    }

    private boolean isExempt(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals("com.termux") || pkg.startsWith("com.termux.")) return true;
        if (pkg.equals("com.usq.shell")) return true;
        if (pkg.equals(JOURNAL_PKG)) return true;
        // Phone / Emergency
        if (pkg.contains("dialer") || pkg.equals("com.android.phone") || pkg.contains("telecom") || pkg.contains("incall")) {
            return true;
        }
        // Authentication & password managers (legitimate fast copy-pasting)
        if (pkg.contains("authenticator") || pkg.contains("bitwarden") || pkg.contains("keepass") || pkg.contains("authy")) {
            return true;
        }
        return false;
    }

    private static String getForegroundPackage(UsageStatsManager usm, long now, String lastKnownPkg) {
        try {
            UsageEvents events = usm.queryEvents(now - 1800000L, now);
            UsageEvents.Event ev = new UsageEvents.Event();
            String lastPkg = null;
            long lastTime = 0;
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == 1 /* ACTIVITY_RESUMED */) {
                    if (ev.getTimeStamp() >= lastTime) {
                        lastTime = ev.getTimeStamp();
                        lastPkg = ev.getPackageName();
                    }
                }
            }
            if (lastPkg != null) return lastPkg;
        } catch (Throwable ignored) {}

        if (lastKnownPkg != null) return lastKnownPkg;

        try {
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 1800000L, now);
            String topPkg = null;
            long topTime = 0;
            if (list != null) {
                for (UsageStats u : list) {
                    if (u.getLastTimeUsed() > topTime) {
                        topTime = u.getLastTimeUsed();
                        topPkg = u.getPackageName();
                    }
                }
            }
            return topPkg;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isScreenOn(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private static Context getContext(ScriptApi api) {
        try {
            Method m = api.getClass().getMethod("context");
            return (Context) m.invoke(api);
        } catch (Throwable ignored) {}
        try {
            Field f = api.getClass().getDeclaredField("ctx");
            f.setAccessible(true);
            return (Context) f.get(api);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int dp(int v, Context ctx) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onStop() {
        dismissAlert(null);
        if (restoreGrayscaleRunnable != null) {
            mainHandler.removeCallbacks(restoreGrayscaleRunnable);
            restoreGrayscaleRunnable = null;
        }
    }
}
