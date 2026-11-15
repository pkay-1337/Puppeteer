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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * blocker.uq.java — Digital Wellbeing & Brutal App Blocker v3.
 * 
 * Features:
 *   - Per-app dynamic limit sliders (5 to 240 mins) for every monitored app
 *   - Per-app strictness mode selector (brutal vs friction) for every app
 *   - Per-app emergency passes counter (0 to 10 passes per day)
 *   - Whitelist: Exempt Apps with zero daily limits
 *   - Accurate per-second active screen-on usage tracking (no wall-clock leakage)
 *   - Resilient periodic active usage nudge alerts (milestone + delta + continuous session triggers)
 *   - Full-screen scrollable overlay in portrait and landscape with Back-key home navigation
 *   - Two-tap intentional 5-minute emergency unlock with remaining passes countdown
 *   - Midnight rollover reset for daily usage and emergency passes
 */
public class Blocker extends UqScript {

    public static class AppRule {
        public int limitMins = 30;
        public String mode = "brutal"; // "brutal" or "friction"
        public int emergencyPasses = 1; // Number of emergency passes allowed per day
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View activeOverlayView;
    private volatile boolean isOverlayShowing;
    private String currentBlockedPkg;

    // Per-app rules map: pkg -> AppRule (limit, mode, passes)
    private final Map<String, AppRule> appRules = new ConcurrentHashMap<String, AppRule>();

    // Whitelist
    private final Set<String> exemptApps = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // General defaults
    private int defaultLimitMins = 30;
    private String defaultStrictness = "brutal";
    private int defaultEmergencyPasses = 1;
    private int nudgeIntervalMins = 5;
    private boolean vibrateNudge = true;

    // Daily active usage tracking (pkg -> total active ms today)
    private final Map<String, Long> appUsageTodayMs = new ConcurrentHashMap<String, Long>();

    // Periodic nudge tracking:
    // pkg -> last notified milestone bucket index (e.g. 1 for 5m, 2 for 10m)
    private final Map<String, Long> lastNotifiedBuckets = new ConcurrentHashMap<String, Long>();
    // pkg -> total usage ms at the time of the last nudge
    private final Map<String, Long> lastNudgeUsageMs = new ConcurrentHashMap<String, Long>();
    // pkg -> timestamp of the last nudge alert (for debounce)
    private final Map<String, Long> lastAlertTimestamp = new ConcurrentHashMap<String, Long>();

    // Emergency grace passes:
    // pkg -> timestamp when active 5-min grace expires
    private final Map<String, Long> emergencyGraceExpiresAt = new ConcurrentHashMap<String, Long>();
    // pkg -> count of passes used today
    private final Map<String, Integer> emergencyPassesUsedToday = new ConcurrentHashMap<String, Integer>();
    private int currentDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);

    private static final String[] ROTATING_QUOTES = new String[] {
        "Stop stalling and reject laziness — scrolling gives nothing back.",
        "Just start what you actually set out to build; momentum follows action.",
        "Put in the hard, focused work right now — no shortcuts, no excuses.",
        "Finish the job: you will thank yourself and feel 10x better the moment it's done.",
        "Discipline is choosing between what you want now and what you want most.",
        "Your future self is watching you right now through memories. Make them proud."
    };

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addAppMap(new ScriptSchema.AppMapBuilder("app_rules", "Monitored Apps & Custom Rules",
                        "Configure individual daily limits, strictness, and emergency passes per app. Tap + Add App to add apps.")
                        .addNumber("limit", "Daily Limit (min)", 30, 5, 240)
                        .addChoice("mode", "Strictness Mode", "brutal", new String[]{"brutal", "friction"})
                        .addNumber("emergency_passes", "Emergency Passes (per day)", 1, 0, 10))
                .addAppMultiSelect("exempt_apps", "Exempt Apps (Whitelist)",
                        "Apps always allowed with no daily limits.")
                .addNumber("default_limit_mins", "Default Daily Limit (min)",
                        "Standard daily allowance for apps without a custom rule", 30, 5, 240)
                .addChoice("default_strictness", "Default Strictness Mode",
                        "Default mode for apps without a custom rule", "brutal",
                        new String[]{"brutal", "friction"})
                .addNumber("default_emergency_passes", "Default Emergency Passes (per day)",
                        "Number of 5-min passes allowed per day for friction apps without custom rule", 1, 0, 10)
                .addNumber("nudge_interval_mins", "Active Usage Nudge (min)",
                        "Show toast notification every X minutes of active app use", 5, 1, 30)
                .addBoolean("vibrate_nudge", "Vibrate on Nudge Alert",
                        "Vibrate for 5 seconds when periodic usage alert triggers", true);
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        applyConfig(newArgs);
    }

    private void applyConfig(Bundle b) {
        if (b == null) return;

        // Parse per-app rules
        String rulesStr = b.getString("app_rules");
        if (rulesStr != null && rulesStr.length() > 0) {
            try {
                JSONObject ro = new JSONObject(rulesStr);
                java.util.Iterator<String> it = ro.keys();
                appRules.clear();
                while (it.hasNext()) {
                    String p = it.next();
                    JSONObject item = ro.optJSONObject(p);
                    if (item != null) {
                        AppRule r = new AppRule();
                        r.limitMins = item.optInt("limit", 30);
                        r.mode = item.optString("mode", "brutal");
                        r.emergencyPasses = item.optInt("emergency_passes", 1);
                        appRules.put(p, r);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Backwards compatibility with initial defaults if app_rules not yet set
        if (rulesStr == null && appRules.isEmpty()) {
            AppRule brave = new AppRule();
            brave.limitMins = b.getInt("brave_limit_mins", 60);
            brave.mode = "brutal";
            brave.emergencyPasses = 1;
            appRules.put("com.brave.browser", brave);

            AppRule lichess = new AppRule();
            lichess.limitMins = b.getInt("lichess_limit_mins", 30);
            lichess.mode = "brutal";
            lichess.emergencyPasses = 1;
            appRules.put("org.lichess.mobileV2", lichess);
        }

        ArrayList<String> eList = b.getStringArrayList("exempt_apps");
        if (eList != null) {
            exemptApps.clear();
            exemptApps.addAll(eList);
        }

        defaultLimitMins = b.getInt("default_limit_mins", defaultLimitMins);
        defaultEmergencyPasses = b.getInt("default_emergency_passes", defaultEmergencyPasses);
        nudgeIntervalMins = b.getInt("nudge_interval_mins", nudgeIntervalMins);
        vibrateNudge = b.getBoolean("vibrate_nudge", vibrateNudge);

        String sMode = b.getString("default_strictness");
        if (sMode == null) sMode = b.getString("strictness_mode");
        if (sMode != null && sMode.length() > 0) {
            defaultStrictness = sMode.toLowerCase();
        }
    }

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        api.log("blocker: starting Digital Wellbeing / App Blocker v3");
        api.toast("Digital Wellbeing Blocker active");

        applyConfig(args);

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("blocker error: Context unavailable");
            return;
        }

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            api.log("blocker error: UsageStatsManager unavailable");
            return;
        }

        String persistentForegroundPkg = null;
        String activeMonitoredPkg = null;
        int activeSessionSeconds = 0;
        int quoteIndex = 0;
        long lastUsmSyncTime = 0;

        while (!api.stopRequested()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;

            long now = System.currentTimeMillis();

            // Midnight rollover reset
            int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
            if (day != currentDayOfYear) {
                currentDayOfYear = day;
                appUsageTodayMs.clear();
                emergencyGraceExpiresAt.clear();
                emergencyPassesUsedToday.clear();
                lastNotifiedBuckets.clear();
                lastNudgeUsageMs.clear();
                lastAlertTimestamp.clear();
                api.log("blocker: midnight passed, daily stats & emergency passes reset");
            }

            // If screen is off, hide overlay and pause active tracking (do NOT accumulate screen-off time)
            if (!isScreenOn(ctx)) {
                hideBlocker(api);
                activeMonitoredPkg = null;
                activeSessionSeconds = 0;
                continue;
            }

            // Retrieve current foreground package with persistent fallback
            String fgPkg = getForegroundPackage(usm, now, persistentForegroundPkg);
            if (fgPkg != null) {
                persistentForegroundPkg = fgPkg;
            }

            // If outside monitored apps (Home, Settings, Whitelist, Termux, etc.)
            if (fgPkg == null || isExempt(fgPkg)) {
                hideBlocker(api);
                activeMonitoredPkg = null;
                activeSessionSeconds = 0;
                continue;
            }

            // Active in a monitored app
            if (!fgPkg.equals(activeMonitoredPkg)) {
                activeMonitoredPkg = fgPkg;
                activeSessionSeconds = 0;

                // Sync with UsageStatsManager on entry: pick maximum of USM ground truth vs tracked
                long usmVal = getTodayUsage(usm, fgPkg);
                Long trackedVal = appUsageTodayMs.get(fgPkg);
                long initialUsage = (trackedVal != null) ? Math.max(usmVal, trackedVal.longValue()) : usmVal;
                appUsageTodayMs.put(fgPkg, Long.valueOf(initialUsage));
            }

            activeSessionSeconds++;

            // Increment active usage by 1000ms
            long currentUsageMs = appUsageTodayMs.get(fgPkg).longValue() + 1000L;

            // Periodically sync with USM in case system flushed higher usage
            if (now - lastUsmSyncTime > 30000L) {
                lastUsmSyncTime = now;
                long usmVal = getTodayUsage(usm, fgPkg);
                if (usmVal > currentUsageMs) {
                    currentUsageMs = usmVal;
                }
            }
            appUsageTodayMs.put(fgPkg, Long.valueOf(currentUsageMs));

            // Check emergency grace pass
            Long graceExpiry = emergencyGraceExpiresAt.get(fgPkg);
            boolean isGraceActive = false;
            if (graceExpiry != null) {
                if (now < graceExpiry.longValue()) {
                    isGraceActive = true;
                } else {
                    emergencyGraceExpiresAt.remove(fgPkg);
                    api.toast("⌛ 5-minute emergency grace has expired for " + api.appLabel(fgPkg));
                    api.vibrate(500);
                    api.log("blocker: 5-minute emergency grace expired for " + fgPkg);
                }
            }

            long limitMs = getLimitMs(fgPkg);
            String appLabel = api.appLabel(fgPkg);
            long remMs = Math.max(0, limitMs - currentUsageMs);
            boolean isExceeded = (currentUsageMs >= limitMs && !isGraceActive);

            // Publish budget metrics to shared bus for inter-script coordination
            ScriptApi.BUS.put("blocker:limit_ms:" + fgPkg, Long.valueOf(limitMs));
            ScriptApi.BUS.put("blocker:used_ms:" + fgPkg, Long.valueOf(currentUsageMs));
            ScriptApi.BUS.put("blocker:rem_ms:" + fgPkg, Long.valueOf(remMs));
            ScriptApi.BUS.put("blocker:is_exceeded:" + fgPkg, Boolean.valueOf(isExceeded));
            ScriptApi.BUS.put("blocker:is_grace:" + fgPkg, Boolean.valueOf(isGraceActive));

            boolean intentOverlayShowing = Boolean.TRUE.equals(ScriptApi.BUS.get("intent:overlay_active:" + fgPkg));

            if (isExceeded && !intentOverlayShowing) {
                // Exceeded limit -> Show full-screen blocker overlay
                String quote = ROTATING_QUOTES[quoteIndex % ROTATING_QUOTES.length];
                showBlocker(ctx, api, fgPkg, appLabel, currentUsageMs, limitMs, quote);
                if (activeSessionSeconds == 1 || (activeSessionSeconds > 0 && activeSessionSeconds % 30 == 0)) {
                    if (vibrateNudge) api.vibrate(1000);
                    quoteIndex++;
                }
            } else {
                // Not exceeded yet OR active emergency grace OR intent overlay is active -> Ensure blocker is hidden
                hideBlocker(api);

                // Periodic active usage nudge alert:
                // Triggers on:
                // 1) Cumulative milestone boundary (5m, 10m, 15m, 20m, etc.)
                // 2) Delta usage since last nudge (e.g. 5m of active use across fragmented sessions)
                // 3) Continuous session interval
                long nudgeIntervalSec = Math.max(60L, (long) nudgeIntervalMins * 60L);
                long nudgeMs = nudgeIntervalSec * 1000L;

                long currentBucket = currentUsageMs / nudgeMs;
                Long prevBucketObj = lastNotifiedBuckets.get(fgPkg);
                if (prevBucketObj == null) {
                    lastNotifiedBuckets.put(fgPkg, Long.valueOf(currentBucket));
                    prevBucketObj = Long.valueOf(currentBucket);
                    lastNudgeUsageMs.put(fgPkg, Long.valueOf(currentUsageMs));
                }

                long prevBucket = prevBucketObj.longValue();
                boolean milestoneCrossed = (currentBucket > prevBucket);

                Long lastUsageObj = lastNudgeUsageMs.get(fgPkg);
                long usageSinceNudge = (lastUsageObj != null) ? (currentUsageMs - lastUsageObj.longValue()) : 0L;
                boolean deltaUsageCrossed = (usageSinceNudge >= nudgeMs);

                boolean sessionStep = (activeSessionSeconds > 0 && activeSessionSeconds % nudgeIntervalSec == 0);

                Long lastAlertTime = lastAlertTimestamp.get(fgPkg);
                long msSinceAlert = (lastAlertTime != null) ? (now - lastAlertTime.longValue()) : 999999L;

                if ((milestoneCrossed || deltaUsageCrossed || sessionStep) && msSinceAlert >= 30000L) {
                    lastNotifiedBuckets.put(fgPkg, Long.valueOf(currentBucket));
                    lastNudgeUsageMs.put(fgPkg, Long.valueOf(currentUsageMs));
                    lastAlertTimestamp.put(fgPkg, Long.valueOf(now));

                    long totalSec = currentUsageMs / 1000L;
                    long h = totalSec / 3600L;
                    long m = (totalSec % 3600L) / 60L;
                    long s = totalSec % 60L;
                    String timeStr = (h > 0) ? (h + "h " + m + "m " + s + "s") : (m + "m " + s + "s");

                    long limMin = limitMs / 60000L;
                    String limStr = (limMin >= 60) ? (limMin / 60) + "h" : limMin + "m";

                    String graceNote = "";
                    if (isGraceActive) {
                        long remSec = Math.max(0, (graceExpiry.longValue() - now) / 1000L);
                        graceNote = " [Grace: " + (remSec / 60) + "m " + (remSec % 60) + "s]";
                    }

                    api.toast("⏳ " + appLabel + ": " + timeStr + " / " + limStr + graceNote);
                    if (vibrateNudge) {
                        api.vibrate(5000);
                    }
                    api.log("nudge: " + appLabel + " usage=" + timeStr + " limit=" + limStr + graceNote);
                }
            }
        }

        hideBlocker(api);
        api.log("blocker: main loop exited");
    }

    private void showBlocker(final Context ctx, final ScriptApi api, final String pkg, final String label,
                             final long usedMs, final long limitMs, final String quote) {
        if (isOverlayShowing && pkg.equals(currentBlockedPkg)) return;
        isOverlayShowing = true;
        currentBlockedPkg = pkg;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm == null) {
                        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    }
                    if (activeOverlayView != null) {
                        try { wm.removeView(activeOverlayView); } catch (Throwable ignored) {}
                        activeOverlayView = null;
                    }

                    activeOverlayView = buildBlockerView(ctx, api, pkg, label, usedMs, limitMs, quote);

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT
                    );
                    lp.gravity = Gravity.CENTER;

                    wm.addView(activeOverlayView, lp);
                    api.log("blocker overlay shown for " + pkg);
                } catch (Throwable t) {
                    api.log("showBlocker failed: " + t);
                    isOverlayShowing = false;
                }
            }
        });
    }

    private void hideBlocker(final ScriptApi api) {
        if (!isOverlayShowing) return;
        isOverlayShowing = false;
        currentBlockedPkg = null;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && activeOverlayView != null) {
                        wm.removeView(activeOverlayView);
                        activeOverlayView = null;
                        if (api != null) api.log("blocker overlay hidden");
                    }
                } catch (Throwable t) {
                    if (api != null) api.log("hideBlocker failed: " + t);
                }
            }
        });
    }

    private View buildBlockerView(final Context ctx, final ScriptApi api, final String pkg,
                                  final String label, final long usedMs, final long limitMs,
                                  final String quote) {
        // ScrollView ensures buttons are never cut off in landscape or on small displays
        ScrollView scroll = new ScrollView(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                // Intercept hardware Back key -> safely navigate to Home
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    try {
                        Intent home = new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(home);
                        return true;
                    } catch (Throwable ignored) {}
                }
                return super.dispatchKeyEvent(event);
            }
        };
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xF90B0D13); // 98% opaque dark obsidian

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24, ctx);
        root.setPadding(pad, pad, pad, pad);

        // Absorb all touches so underlying app cannot be tapped
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });

        // ⛔ Big Warning Icon
        TextView icon = new TextView(ctx);
        icon.setText("⛔");
        icon.setTextSize(50);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);

        // Title
        TextView title = new TextView(ctx);
        title.setText("APP BLOCKED");
        title.setTextSize(24);
        title.setTextColor(0xFFEF4444); // Red
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(6, ctx), 0, 0);
        root.addView(title);

        // App Label
        TextView appTv = new TextView(ctx);
        appTv.setText(label);
        appTv.setTextSize(19);
        appTv.setTextColor(0xFFF8FAFC);
        appTv.setTypeface(Typeface.DEFAULT_BOLD);
        appTv.setGravity(Gravity.CENTER);
        appTv.setPadding(0, dp(2, ctx), 0, dp(14, ctx));
        root.addView(appTv);

        // Stats Card
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int cPad = dp(14, ctx);
        card.setPadding(cPad, cPad, cPad, cPad);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF161922);
        cardBg.setCornerRadius(dp(12, ctx));
        cardBg.setStroke(dp(1, ctx), 0xFF2A2E3D);
        card.setBackground(cardBg);

        long usedMin = usedMs / 60000L;
        long limitMin = limitMs / 60000L;
        String usedStr = (usedMin >= 60) ? (usedMin / 60) + "h " + (usedMin % 60) + "m" : usedMin + "m";
        String limitStr = (limitMin >= 60) ? (limitMin / 60) + "h " + (limitMin % 60) + "m" : limitMin + "m";

        TextView limitTv = new TextView(ctx);
        limitTv.setText("● Daily Limit: " + limitStr);
        limitTv.setTextSize(13);
        limitTv.setTextColor(0xFF94A3B8);
        card.addView(limitTv);

        TextView usedTv = new TextView(ctx);
        usedTv.setText("● Used Today:  " + usedStr);
        usedTv.setTextSize(13);
        usedTv.setTextColor(0xFFF87171); // Red accent
        usedTv.setTypeface(Typeface.DEFAULT_BOLD);
        usedTv.setPadding(0, dp(3, ctx), 0, 0);
        card.addView(usedTv);

        // Time to midnight reset
        String resetStr = getTimeUntilMidnight();
        TextView resetTv = new TextView(ctx);
        resetTv.setText("● Resets:       " + resetStr);
        resetTv.setTextSize(12);
        resetTv.setTextColor(0xFF38BDF8); // Cyan
        resetTv.setPadding(0, dp(3, ctx), 0, 0);
        card.addView(resetTv);

        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Motivational Card
        LinearLayout noteBox = new LinearLayout(ctx);
        noteBox.setOrientation(LinearLayout.VERTICAL);
        int nPad = dp(14, ctx);
        noteBox.setPadding(nPad, nPad, nPad, nPad);
        GradientDrawable noteBg = new GradientDrawable();
        noteBg.setColor(0xFF13161F);
        noteBg.setCornerRadius(dp(10, ctx));
        noteBg.setStroke(dp(1, ctx), 0xFF2A2E3D);
        noteBox.setBackground(noteBg);

        TextView noteHeader = new TextView(ctx);
        noteHeader.setText("⚡ WAKE UP & GET TO WORK");
        noteHeader.setTextSize(12);
        noteHeader.setTextColor(0xFFFBBF24); // Warm Amber
        noteHeader.setTypeface(Typeface.DEFAULT_BOLD);
        noteHeader.setPadding(0, 0, 0, dp(6, ctx));
        noteBox.addView(noteHeader);

        TextView quoteTv = new TextView(ctx);
        quoteTv.setText("\"" + quote + "\"");
        quoteTv.setTextSize(13);
        quoteTv.setTextColor(0xFFE2E8F0);
        quoteTv.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        quoteTv.setLineSpacing(dp(2, ctx), 1.1f);
        noteBox.addView(quoteTv);

        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.setMargins(0, dp(12, ctx), 0, dp(14, ctx));
        root.addView(noteBox, nLp);

        // Strictness Mode Handling (Brutal vs Friction) & Emergency Passes
        String appStrictness = getAppStrictnessMode(pkg);
        final boolean isFriction = "friction".equals(appStrictness);
        final int maxPasses = getMaxEmergencyPasses(pkg);
        Integer usedObj = emergencyPassesUsedToday.get(pkg);
        final int usedCount = (usedObj != null) ? usedObj.intValue() : 0;
        final boolean noPassesRemaining = (usedCount >= maxPasses);

        if (!isFriction || maxPasses <= 0) {
            // Brutal Mode info banner
            LinearLayout brutalBox = new LinearLayout(ctx);
            brutalBox.setOrientation(LinearLayout.VERTICAL);
            int bPad = dp(10, ctx);
            brutalBox.setPadding(bPad, bPad, bPad, bPad);
            GradientDrawable bBg = new GradientDrawable();
            bBg.setColor(0xFF1E1B24);
            bBg.setCornerRadius(dp(8, ctx));
            bBg.setStroke(dp(1, ctx), 0xFF7F1D1D);
            brutalBox.setBackground(bBg);

            TextView bTv = new TextView(ctx);
            bTv.setText("🔒 Brutal Mode Active — Zero bypass until midnight.");
            bTv.setTextSize(12);
            bTv.setTextColor(0xFFFCA5A5);
            bTv.setGravity(Gravity.CENTER);
            brutalBox.addView(bTv);

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.setMargins(0, 0, 0, dp(10, ctx));
            root.addView(brutalBox, bLp);
        } else if (noPassesRemaining) {
            // All passes already used banner
            LinearLayout usedBox = new LinearLayout(ctx);
            usedBox.setOrientation(LinearLayout.VERTICAL);
            int uPad = dp(10, ctx);
            usedBox.setPadding(uPad, uPad, uPad, uPad);
            GradientDrawable uBg = new GradientDrawable();
            uBg.setColor(0xFF1A1F2C);
            uBg.setCornerRadius(dp(8, ctx));
            uBg.setStroke(dp(1, ctx), 0xFF334155);
            usedBox.setBackground(uBg);

            TextView uTv = new TextView(ctx);
            String passWord = (maxPasses == 1) ? "pass" : "passes";
            uTv.setText("⚡ All " + maxPasses + " emergency " + passWord + " already used today (resets at midnight).");
            uTv.setTextSize(12);
            uTv.setTextColor(0xFF94A3B8);
            uTv.setGravity(Gravity.CENTER);
            usedBox.addView(uTv);

            LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            uLp.setMargins(0, 0, 0, dp(10, ctx));
            root.addView(usedBox, uLp);
        } else {
            // Two-tap Intentional Emergency Unlock with remaining count
            final int remaining = maxPasses - usedCount;
            final Button btnUnlock = new Button(ctx);
            btnUnlock.setText("⚡ Emergency 5-Min Unlock (" + remaining + "/" + maxPasses + " left)");
            btnUnlock.setTextSize(14);
            btnUnlock.setTextColor(0xFF0F172A); // Dark Slate text
            btnUnlock.setTypeface(Typeface.DEFAULT_BOLD);
            final GradientDrawable uBg = new GradientDrawable();
            uBg.setColor(0xFFFBBF24); // Amber
            uBg.setCornerRadius(dp(8, ctx));
            btnUnlock.setBackground(uBg);
            btnUnlock.setPadding(dp(14, ctx), dp(10, ctx), dp(14, ctx), dp(10, ctx));

            final boolean[] confirmed = new boolean[]{false};

            btnUnlock.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!confirmed[0]) {
                        confirmed[0] = true;
                        btnUnlock.setText("⚠️ Tap Again to Confirm (Pass " + (usedCount + 1) + " of " + maxPasses + ")");
                        uBg.setColor(0xFFF59E0B); // Vivid amber
                        btnUnlock.setTextColor(0xFF000000);
                        if (api != null) {
                            int leftAfter = remaining - 1;
                            api.toast("⚡ Tap again to unlock 5 minutes (" + leftAfter + " left after this)");
                            api.vibrate(50);
                        }
                    } else {
                        long now = System.currentTimeMillis();
                        emergencyGraceExpiresAt.put(pkg, Long.valueOf(now + (5L * 60L * 1000L)));
                        emergencyPassesUsedToday.put(pkg, Integer.valueOf(usedCount + 1));
                        ScriptApi.BUS.put("intent:start_emergency_session:" + pkg, Long.valueOf(now + (5L * 60L * 1000L)));
                        int leftAfter = remaining - 1;
                        if (api != null) {
                            api.toast("⚡ 5-minute grace unlocked for " + label + "! (" + leftAfter + " left today)");
                            api.vibrate(100);
                            api.log("blocker: emergency grace #" + (usedCount + 1) + "/" + maxPasses + " granted for " + pkg);
                        }
                        hideBlocker(api);
                    }
                }
            });

            LinearLayout.LayoutParams ubLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ubLp.setMargins(0, 0, 0, dp(10, ctx));
            root.addView(btnUnlock, ubLp);
        }

        // Return to Home Screen button
        Button homeBtn = new Button(ctx);
        homeBtn.setText("🏠 Return to Home Screen");
        homeBtn.setTextSize(15);
        homeBtn.setTextColor(0xFFFFFFFF);
        homeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF4F46E5); // Indigo
        btnBg.setCornerRadius(dp(8, ctx));
        homeBtn.setBackground(btnBg);
        homeBtn.setPadding(dp(16, ctx), dp(12, ctx), dp(16, ctx), dp(12, ctx));
        homeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(home);
                } catch (Throwable t) {
                    if (api != null) api.log("home intent failed: " + t);
                }
            }
        });
        root.addView(homeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private String getAppStrictnessMode(String pkg) {
        AppRule rule = appRules.get(pkg);
        if (rule != null && rule.mode != null) {
            return rule.mode.toLowerCase();
        }
        return defaultStrictness;
    }

    private int getMaxEmergencyPasses(String pkg) {
        AppRule rule = appRules.get(pkg);
        if (rule != null) {
            return rule.emergencyPasses;
        }
        return defaultEmergencyPasses;
    }

    private static String getTimeUntilMidnight() {
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long diffMs = Math.max(0, cal.getTimeInMillis() - now);

        long diffHours = diffMs / 3600000L;
        long diffMins = (diffMs % 3600000L) / 60000L;
        return "at Midnight (in " + diffHours + "h " + diffMins + "m)";
    }

    private static String getForegroundPackage(UsageStatsManager usm, long now, String lastKnownPkg) {
        try {
            // Look back up to 30 minutes for the most recent ACTIVITY_RESUMED event
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

        // If continuous session with no recent activity switches, retain last known package
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
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isExempt(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;

        // Custom whitelist configured by user
        if (exemptApps.contains(pkg)) return true;

        // System exempt list
        if (pkg.equals("com.miui.home") || pkg.contains("launcher")) return true;
        if (pkg.startsWith("com.android.systemui") || pkg.contains("systemui")) return true;
        if (pkg.equals("com.android.settings") || pkg.equals("com.xiaomi.misettings") || pkg.contains("settings")) return true;
        if (pkg.equals("com.termux") || pkg.startsWith("com.termux.")) return true;
        if (pkg.equals("com.usq.shell")) return true;
        if (pkg.contains("dialer") || pkg.equals("com.android.phone") || pkg.contains("telecom") || pkg.contains("incall")) return true;
        if (pkg.contains("packageinstaller") || pkg.contains("permissioncontroller")) return true;
        if (pkg.equals("com.google.android.gms") || pkg.equals("android")) return true;

        return false;
    }

    private long getLimitMs(String pkg) {
        AppRule rule = appRules.get(pkg);
        if (rule != null) {
            return (long) rule.limitMins * 60L * 1000L;
        }
        return (long) defaultLimitMins * 60L * 1000L;
    }

    private static long getTodayUsage(UsageStatsManager usm, String pkg) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startToday = cal.getTimeInMillis();
        long now = System.currentTimeMillis();

        Map<String, UsageStats> agg = usm.queryAndAggregateUsageStats(startToday, now);
        if (agg != null && agg.containsKey(pkg)) {
            UsageStats us = agg.get(pkg);
            if (us != null) {
                return us.getTotalTimeInForeground();
            }
        }
        return 0L;
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
        hideBlocker(null);
    }
}
