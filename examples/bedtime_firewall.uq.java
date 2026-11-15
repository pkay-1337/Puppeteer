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
import android.view.KeyEvent;
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
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * bedtime_firewall.uq.java — Scheduled Bedtime Internet & Phone Lockdown.
 * 
 * Rules:
 *   - 9:45 PM (21:45): Floating display-over-apps warning popup:
 *     "Internet cuts off in 15 minutes (at 10:00 PM). Finish tasks now."
 *   - 10:00 PM (22:00): Full internet traffic blackhole engaged until 8:00 AM.
 *   - Phone Lockdown Overlay (Optional Toggle):
 *     When enabled, covers the entire screen with an unignorable AMOLED sleep card.
 *     Only the Phone Dialer (for emergency calls) and Termux are accessible.
 *   - 8:00 AM (08:00): Device access and internet automatically restored.
 *   - Safety: Phone Dialer, Emergency Calls, Termux, and UQ Shell are strictly exempt.
 */
public class BedtimeFirewall extends UqScript {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View activeWarningView;
    private volatile boolean isWarningShowing = false;
    private TextView warningBadgeTv;
    private Button warningBtnOk;
    private boolean warningAcknowledged = false;
    private int lastWarningVibeDay = -1;
    private int lastWarningNotifMin = -1;
    private static final int NOTIF_WARNING_ID = 1099;
    private ScriptApi savedApi;

    private View activeLockdownView;
    private volatile boolean isLockdownShowing = false;

    // Configurable parameters
    private int bedtimeHour = 22;      // 10 PM
    private int bedtimeMinute = 0;
    private int wakeHour = 8;          // 8 AM
    private int wakeMinute = 0;
    private int warningMinsBefore = 15; // 15 mins before bedtime (9:45 PM)
    private boolean blockPhoneUsage = true; // Toggle for full-screen phone lockout
    private boolean vibrateWarning = true;

    // Daily state
    private boolean wasBedtimeActive = false;
    private String lastForegroundPkg = null;

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addNumber("bedtime_hour", "Bedtime Hour (24h)",
                        "Hour when internet & phone lock engages (e.g. 22 for 10 PM)", 22, 0, 23)
                .addNumber("bedtime_minute", "Bedtime Minute",
                        "Minute of the hour when bedtime engages", 0, 0, 59)
                .addNumber("wake_hour", "Morning Restore Hour (24h)",
                        "Hour when internet & phone access is restored (e.g. 8 for 8 AM)", 8, 0, 23)
                .addNumber("wake_minute", "Morning Restore Minute",
                        "Minute of the hour when access is restored", 0, 0, 59)
                .addNumber("warning_mins_before", "Advance Warning Notice (mins)",
                        "Minutes before bedtime to display popup warning (e.g. 15 for 9:45 PM)", 15, 1, 60)
                .addBoolean("block_phone_usage", "Block Phone Usage (Full Screen Lock)",
                        "Lock down device with full-screen overlay allowing only Phone calls during bedtime", true)
                .addBoolean("vibrate_warning", "Vibrate on Warning Alert",
                        "Vibrate phone when 15-minute bedtime warning displays", true);
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        applyConfig(newArgs);
    }

    private void applyConfig(Bundle b) {
        if (b == null) return;
        bedtimeHour = b.getInt("bedtime_hour", bedtimeHour);
        bedtimeMinute = b.getInt("bedtime_minute", bedtimeMinute);
        wakeHour = b.getInt("wake_hour", wakeHour);
        wakeMinute = b.getInt("wake_minute", wakeMinute);
        warningMinsBefore = b.getInt("warning_mins_before", warningMinsBefore);
        blockPhoneUsage = b.getBoolean("block_phone_usage", blockPhoneUsage);
        vibrateWarning = b.getBoolean("vibrate_warning", vibrateWarning);
    }

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        savedApi = api;
        api.log("bedtime_firewall: starting Bedtime Internet & Phone Blocker daemon");
        applyConfig(args);

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("bedtime_firewall error: Context unavailable");
            return;
        }

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);

        if (!api.isFirewallPrepared()) {
            api.toast("⚠️ Bedtime Firewall requires VPN authorization in UQ Shell!");
            api.log("bedtime_firewall: Firewall permission not yet granted in UQ Shell.");
        }

        while (!api.stopRequested()) {
            // Adaptive sleep: 1s when actively locking phone during bedtime, 3s otherwise
            int sleepMs = (blockPhoneUsage && wasBedtimeActive) ? 1000 : 3000;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;

            Calendar now = Calendar.getInstance();
            int currentDay = now.get(Calendar.DAY_OF_YEAR);
            int curMinsOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

            int bedtimeMins = bedtimeHour * 60 + bedtimeMinute;
            int wakeMins = wakeHour * 60 + wakeMinute;
            int warningStartMins = bedtimeMins - warningMinsBefore;
            if (warningStartMins < 0) warningStartMins += 1440;

            boolean inBedtimeWindow = isInWindow(curMinsOfDay, bedtimeMins, wakeMins);
            boolean inWarningWindow = !inBedtimeWindow && isInWindow(curMinsOfDay, warningStartMins, bedtimeMins);

            // 1. Bedtime Window (10:00 PM to 8:00 AM)
            if (inBedtimeWindow) {
                hideWarning();
                if (lastWarningNotifMin != -1) {
                    api.cancelNotify(NOTIF_WARNING_ID);
                    lastWarningNotifMin = -1;
                }
                warningAcknowledged = false;
                ScriptApi.BUS.put("bedtime:active", Boolean.TRUE);
                ScriptApi.BUS.put("bedtime:wake_time", formatTime(wakeHour, wakeMinute));

                if (!wasBedtimeActive || !api.isInternetBlocked()) {
                    wasBedtimeActive = true;
                    int secondsUntilWake = getSecondsUntil(now, wakeHour, wakeMinute);
                    api.blockInternet(secondsUntilWake);
                    api.toast("🌙 Bedtime Active: Internet & device locked until " + formatTime(wakeHour, wakeMinute));
                    api.log("bedtime_firewall: Bedtime started. Internet blocked until " + formatTime(wakeHour, wakeMinute) + " (" + (secondsUntilWake / 60) + "m)");
                }

                // Full-screen phone usage lock (if toggle enabled)
                if (blockPhoneUsage) {
                    if (!isScreenOn(ctx)) {
                        hideLockdown();
                        lastForegroundPkg = null;
                    } else {
                        long nowMs = System.currentTimeMillis();
                        String fgPkg = getForegroundPackage(usm, nowMs, lastForegroundPkg);
                        if (fgPkg != null) lastForegroundPkg = fgPkg;

                        if (isPhoneOrAllowedApp(fgPkg)) {
                            // User is actively in Phone dialer or emergency terminal
                            hideLockdown();
                        } else {
                            // Non-exempt app (including Launcher, Brave, etc.) -> show lockdown
                            showLockdown(ctx, api, wakeHour, wakeMinute);
                        }
                    }
                } else {
                    hideLockdown();
                }

            } else {
                // Outside Bedtime Window (8:00 AM to 10:00 PM)
                ScriptApi.BUS.put("bedtime:active", Boolean.FALSE);
                hideLockdown();

                if (wasBedtimeActive) {
                    wasBedtimeActive = false;
                    api.restoreInternet();
                    api.toast("☀️ Good morning! Device and internet restored.");
                    api.log("bedtime_firewall: Morning reached (" + formatTime(wakeHour, wakeMinute) + "). Restored.");
                }

                // 2. Advance Warning Window (9:45 PM to 10:00 PM)
                if (inWarningWindow) {
                    int minsRemaining = bedtimeMins - curMinsOfDay;
                    if (minsRemaining < 0) minsRemaining += 1440;

                    // Keep a persistent heads-up/lockscreen notification during countdown
                    if (minsRemaining != lastWarningNotifMin) {
                        lastWarningNotifMin = minsRemaining;
                        api.notify(NOTIF_WARNING_ID,
                                "🌙 Bedtime in " + minsRemaining + "m (" + formatTime(bedtimeHour, bedtimeMinute) + ")",
                                "Internet & device will lock at " + formatTime(bedtimeHour, bedtimeMinute) + ". Wrap up now.");
                    }

                    if (!warningAcknowledged) {
                        // If screen is on, ensure the overlay is displayed with current countdown
                        if (isScreenOn(ctx)) {
                            showWarning(ctx, api, minsRemaining, bedtimeHour, bedtimeMinute);
                            if (lastWarningVibeDay != currentDay) {
                                lastWarningVibeDay = currentDay;
                                if (vibrateWarning) {
                                    api.vibrate(800);
                                }
                                api.toast("⚠️ Bedtime notice: Internet will be blocked in " + minsRemaining + " minutes!");
                                api.log("bedtime_firewall: bedtime warning displayed (" + minsRemaining + "m until cutoff)");
                            }
                        }
                    } else {
                        hideWarning();
                    }
                } else {
                    hideWarning();
                    if (lastWarningNotifMin != -1) {
                        api.cancelNotify(NOTIF_WARNING_ID);
                        lastWarningNotifMin = -1;
                    }
                    warningAcknowledged = false;
                }
            }
        }

        hideWarning();
        hideLockdown();
        if (lastWarningNotifMin != -1) {
            api.cancelNotify(NOTIF_WARNING_ID);
            lastWarningNotifMin = -1;
        }
        ScriptApi.BUS.put("bedtime:active", Boolean.FALSE);
        api.log("bedtime_firewall: daemon stopped");
    }

    private static boolean isInWindow(int current, int start, int end) {
        if (start < end) {
            return current >= start && current < end;
        } else {
            // Spans across midnight (e.g. 22:00 to 08:00)
            return current >= start || current < end;
        }
    }

    private static int getSecondsUntil(Calendar now, int targetHour, int targetMinute) {
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, targetHour);
        target.set(Calendar.MINUTE, targetMinute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        if (now.after(target)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }

        long diffMs = Math.max(1000L, target.getTimeInMillis() - now.getTimeInMillis());
        return (int) (diffMs / 1000L);
    }

    private static String formatTime(int h, int m) {
        int hour12 = h % 12;
        if (hour12 == 0) hour12 = 12;
        String ampm = (h < 12) ? "AM" : "PM";
        String minStr = (m < 10) ? ("0" + m) : String.valueOf(m);
        return hour12 + ":" + minStr + " " + ampm;
    }

    private boolean isPhoneOrAllowedApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        // Phone calls & emergency dialers
        if (pkg.contains("dialer") || pkg.equals("com.android.phone") ||
                pkg.contains("telecom") || pkg.contains("incall") || pkg.contains("emergency")) {
            return true;
        }
        // Terminal & developer rescue
        if (pkg.equals("com.termux") || pkg.startsWith("com.termux.")) return true;
        if (pkg.equals("com.usq.shell")) return true;
        // System UI (lockscreen, notification pull-down)
        if (pkg.contains("systemui") || pkg.contains("permissioncontroller")) return true;
        return false;
    }

    private void showLockdown(final Context ctx, final ScriptApi api, final int wHour, final int wMin) {
        if (isLockdownShowing) return;
        isLockdownShowing = true;
        ScriptApi.BUS.put("ui:top_overlay_time", Long.valueOf(System.currentTimeMillis()));

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm == null) {
                        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    }
                    if (activeLockdownView != null) {
                        try { wm.removeView(activeLockdownView); } catch (Throwable ignored) {}
                        activeLockdownView = null;
                    }

                    activeLockdownView = buildLockdownView(ctx, api, wHour, wMin);

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT
                    );
                    lp.gravity = Gravity.CENTER;

                    wm.addView(activeLockdownView, lp);
                    api.log("bedtime_firewall: bedtime phone usage lockdown displayed");
                } catch (Throwable t) {
                    api.log("bedtime_firewall: showLockdown failed: " + t);
                    isLockdownShowing = false;
                }
            }
        });
    }

    private void hideLockdown() {
        if (!isLockdownShowing) return;
        isLockdownShowing = false;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && activeLockdownView != null) {
                        wm.removeView(activeLockdownView);
                        activeLockdownView = null;
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private View buildLockdownView(final Context ctx, final ScriptApi api, final int wHour, final int wMin) {
        ScrollView scroll = new ScrollView(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    try {
                        api.vibrate(40);
                        api.toast("🌙 Bedtime Lockdown: Phone calls only");
                        return true;
                    } catch (Throwable ignored) {}
                }
                return super.dispatchKeyEvent(event);
            }
        };
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xF608090E); // 96% Deep Obsidian Black

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24, ctx);
        root.setPadding(pad, pad, pad, pad);

        // Lockdown Card Container
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int cPad = dp(24, ctx);
        card.setPadding(cPad, cPad, cPad, cPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF12141C);
        cardBg.setCornerRadius(dp(20, ctx));
        cardBg.setStroke(dp(1, ctx), 0xFF312E81); // Indigo accent border
        card.setBackground(cardBg);

        // Moon Pill Badge
        TextView pill = new TextView(ctx);
        pill.setText("🌙 BEDTIME LOCKDOWN ACTIVE");
        pill.setTextSize(11);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextColor(0xFFA5B4FC); // Indigo 300
        int pPadH = dp(12, ctx);
        int pPadV = dp(5, ctx);
        pill.setPadding(pPadH, pPadV, pPadH, pPadV);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x266366F1); // 15% Indigo 500
        pillBg.setCornerRadius(dp(12, ctx));
        pill.setBackground(pillBg);
        card.addView(pill);

        // Title
        TextView title = new TextView(ctx);
        title.setText("Sleep Mode Engaged");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14, ctx), 0, dp(6, ctx));
        card.addView(title);

        // Subtitle
        TextView subtitle = new TextView(ctx);
        subtitle.setText("Device and internet are locked until morning to protect your circadian rhythm. Only phone calls are permitted.");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFF94A3B8);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(2, ctx), 1.15f);
        card.addView(subtitle);

        // Info Badge
        TextView info = new TextView(ctx);
        info.setText("● Locked until " + formatTime(wHour, wMin));
        info.setTextSize(12);
        info.setTextColor(0xFF38BDF8); // Cyan accent
        info.setPadding(0, dp(12, ctx), 0, dp(18, ctx));
        card.addView(info);

        // Emergency Phone Action Button
        Button callBtn = new Button(ctx);
        callBtn.setText("📞  OPEN PHONE DIALER");
        callBtn.setTextSize(14);
        callBtn.setTypeface(Typeface.DEFAULT_BOLD);
        callBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable callBg = new GradientDrawable();
        callBg.setColor(0xFF1D4ED8); // Blue 700
        callBg.setCornerRadius(dp(12, ctx));
        callBtn.setBackground(callBg);
        callBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    api.vibrate(40);
                    hideLockdown();
                    Intent dial = new Intent(Intent.ACTION_DIAL);
                    dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(dial);
                } catch (Throwable ignored) {}
            }
        });
        card.addView(callBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48, ctx)));

        // Emergency Termux link
        TextView termuxTv = new TextView(ctx);
        termuxTv.setText("💻 Termux Terminal");
        termuxTv.setTextSize(12);
        termuxTv.setTextColor(0xFF64748B);
        termuxTv.setGravity(Gravity.CENTER);
        termuxTv.setPadding(0, dp(16, ctx), 0, 0);
        termuxTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    hideLockdown();
                    Intent tx = ctx.getPackageManager().getLaunchIntentForPackage("com.termux");
                    if (tx != null) {
                        tx.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(tx);
                    }
                } catch (Throwable ignored) {}
            }
        });
        card.addView(termuxTv);

        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void showWarning(final Context ctx, final ScriptApi api, final int minsLeft,
                             final int bHour, final int bMin) {
        if (isWarningShowing) {
            updateWarningCountdown(minsLeft, bHour, bMin);
            return;
        }
        isWarningShowing = true;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm == null) {
                        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    }
                    if (activeWarningView != null) {
                        try { wm.removeView(activeWarningView); } catch (Throwable ignored) {}
                        activeWarningView = null;
                    }

                    activeWarningView = buildWarningView(ctx, api, minsLeft, bHour, bMin);

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                            PixelFormat.TRANSLUCENT
                    );
                    lp.gravity = Gravity.CENTER;

                    wm.addView(activeWarningView, lp);
                } catch (Throwable t) {
                    if (api != null) api.log("showWarning failed: " + t);
                    isWarningShowing = false;
                }
            }
        });
    }

    private void updateWarningCountdown(final int minsLeft, final int bHour, final int bMin) {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (warningBadgeTv != null) {
                        warningBadgeTv.setText("Internet cuts off in " + minsLeft + " minutes (at " + formatTime(bHour, bMin) + ")");
                    }
                    if (warningBtnOk != null) {
                        warningBtnOk.setText("✓ Understood — I'll Wrap Up (" + minsLeft + "m left)");
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void hideWarning() {
        if (!isWarningShowing && activeWarningView == null) return;
        isWarningShowing = false;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && activeWarningView != null) {
                        wm.removeView(activeWarningView);
                        activeWarningView = null;
                    }
                    warningBadgeTv = null;
                    warningBtnOk = null;
                } catch (Throwable ignored) {}
            }
        });
    }

    private View buildWarningView(final Context ctx, final ScriptApi api, final int minsLeft,
                                  final int bHour, final int bMin) {
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        int margin = dp(20, ctx);
        outer.setPadding(margin, margin, margin, margin);

        // Card container
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(20, ctx);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF50F172A); // 96% Dark Slate obsidian
        bg.setCornerRadius(dp(16, ctx));
        bg.setStroke(dp(2, ctx), 0xFFF59E0B); // Amber warning accent
        card.setBackground(bg);

        // Header icon
        TextView iconTv = new TextView(ctx);
        iconTv.setText("⏳ 🌙");
        iconTv.setTextSize(36);
        iconTv.setGravity(Gravity.CENTER);
        card.addView(iconTv);

        // Title
        TextView titleTv = new TextView(ctx);
        titleTv.setText("BEDTIME WARNING");
        titleTv.setTextSize(20);
        titleTv.setTextColor(0xFFFBBF24); // Warm Amber
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, dp(6, ctx), 0, 0);
        card.addView(titleTv);

        // Subtitle countdown badge
        TextView badgeTv = new TextView(ctx);
        badgeTv.setText("Internet cuts off in " + minsLeft + " minutes (at " + formatTime(bHour, bMin) + ")");
        badgeTv.setTextSize(13);
        badgeTv.setTextColor(0xFF38BDF8); // Sky blue
        badgeTv.setTypeface(Typeface.DEFAULT_BOLD);
        badgeTv.setGravity(Gravity.CENTER);
        badgeTv.setPadding(0, dp(4, ctx), 0, dp(12, ctx));
        card.addView(badgeTv);
        warningBadgeTv = badgeTv;

        // Explanatory message
        TextView msgTv = new TextView(ctx);
        msgTv.setText("Internet access will be blocked overnight until " + formatTime(wakeHour, wakeMinute) + ".\n\nPlease wrap up any critical tasks, finish sending your messages, and close your tabs now.");
        msgTv.setTextSize(13);
        msgTv.setTextColor(0xFFE2E8F0);
        msgTv.setGravity(Gravity.CENTER);
        msgTv.setLineSpacing(dp(2, ctx), 1.15f);
        msgTv.setPadding(0, 0, 0, dp(18, ctx));
        card.addView(msgTv);

        // Acknowledge Button
        Button btnOk = new Button(ctx);
        btnOk.setText("✓ Understood — I'll Wrap Up (" + minsLeft + "m left)");
        btnOk.setTextSize(14);
        btnOk.setTextColor(0xFF0F172A);
        btnOk.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFFFBBF24); // Amber
        btnBg.setCornerRadius(dp(8, ctx));
        btnOk.setBackground(btnBg);
        btnOk.setPadding(dp(16, ctx), dp(10, ctx), dp(16, ctx), dp(10, ctx));

        btnOk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                warningAcknowledged = true;
                hideWarning();
                if (api != null) {
                    api.toast("👍 Understood — wrapping up before bedtime.");
                }
            }
        });

        card.addView(btnOk, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        warningBtnOk = btnOk;

        outer.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return outer;
    }

    private static String getForegroundPackage(UsageStatsManager usm, long now, String lastKnownPkg) {
        if (usm == null) return lastKnownPkg;
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
        hideWarning();
        hideLockdown();
        if (savedApi != null) {
            try { savedApi.cancelNotify(NOTIF_WARNING_ID); } catch (Throwable ignored) {}
        }
        ScriptApi.BUS.put("bedtime:active", Boolean.FALSE);
    }
}
