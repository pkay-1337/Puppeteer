import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * bedtime_firewall.uq.java — Scheduled Bedtime Internet Firewall.
 * 
 * Rules:
 *   - 9:45 PM (21:45): Floating display-over-apps warning popup:
 *     "Internet cuts off in 15 minutes (at 10:00 PM). Finish tasks now."
 *   - 10:00 PM (22:00): Full internet traffic blackhole engaged until 8:00 AM.
 *   - 8:00 AM (08:00): Internet automatically restored.
 *   - Termux & UQ Shell are strictly excluded so you never lose device control.
 *   - All times and durations are dynamically configurable via schema.
 */
public class BedtimeFirewall extends UqScript {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View activeWarningView;
    private volatile boolean isWarningShowing;

    // Configurable parameters
    private int bedtimeHour = 22;      // 10 PM
    private int bedtimeMinute = 0;
    private int wakeHour = 8;          // 8 AM
    private int wakeMinute = 0;
    private int warningMinsBefore = 15; // 15 mins before bedtime (9:45 PM)
    private boolean vibrateWarning = true;

    // Daily state
    private int lastWarningDay = -1;
    private boolean wasBedtimeActive = false;

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addNumber("bedtime_hour", "Bedtime Hour (24h)",
                        "Hour when internet is blocked (e.g. 22 for 10 PM)", 22, 0, 23)
                .addNumber("bedtime_minute", "Bedtime Minute",
                        "Minute of the hour when internet is blocked", 0, 0, 59)
                .addNumber("wake_hour", "Morning Restore Hour (24h)",
                        "Hour when internet is restored (e.g. 8 for 8 AM)", 8, 0, 23)
                .addNumber("wake_minute", "Morning Restore Minute",
                        "Minute of the hour when internet is restored", 0, 0, 59)
                .addNumber("warning_mins_before", "Advance Warning Notice (mins)",
                        "Minutes before bedtime to display popup warning (e.g. 15 for 9:45 PM)", 15, 1, 60)
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
        vibrateWarning = b.getBoolean("vibrate_warning", vibrateWarning);
    }

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        api.log("bedtime_firewall: starting Bedtime Internet Blocker daemon");
        applyConfig(args);

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("bedtime_firewall error: Context unavailable");
            return;
        }

        if (!api.isFirewallPrepared()) {
            api.toast("⚠️ Bedtime Firewall requires VPN authorization in UQ Shell!");
            api.log("bedtime_firewall: Firewall permission not yet granted in UQ Shell.");
        }

        while (!api.stopRequested()) {
            try {
                Thread.sleep(5000); // Check every 5 seconds
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
                ScriptApi.BUS.put("bedtime:active", Boolean.TRUE);
                ScriptApi.BUS.put("bedtime:wake_time", formatTime(wakeHour, wakeMinute));

                if (!wasBedtimeActive || !api.isInternetBlocked()) {
                    wasBedtimeActive = true;
                    int secondsUntilWake = getSecondsUntil(now, wakeHour, wakeMinute);
                    api.blockInternet(secondsUntilWake);
                    api.toast("🌙 Bedtime Firewall Active: Internet is blocked until " + formatTime(wakeHour, wakeMinute));
                    api.log("bedtime_firewall: Bedtime started. Internet blocked until " + formatTime(wakeHour, wakeMinute) + " (" + (secondsUntilWake / 60) + "m)");
                }
            } else {
                // Outside Bedtime Window (8:00 AM to 10:00 PM)
                ScriptApi.BUS.put("bedtime:active", Boolean.FALSE);
                if (wasBedtimeActive) {
                    wasBedtimeActive = false;
                    api.restoreInternet();
                    api.toast("☀️ Good morning! Internet access restored.");
                    api.log("bedtime_firewall: Morning reached (" + formatTime(wakeHour, wakeMinute) + "). Internet restored.");
                }

                // 2. Advance Warning Window (9:45 PM to 10:00 PM)
                if (inWarningWindow) {
                    if (lastWarningDay != currentDay) {
                        lastWarningDay = currentDay;
                        int minsRemaining = bedtimeMins - curMinsOfDay;
                        if (minsRemaining < 0) minsRemaining += 1440;
                        showWarning(ctx, api, minsRemaining, bedtimeHour, bedtimeMinute);
                        if (vibrateWarning) {
                            api.vibrate(800);
                        }
                        api.toast("⚠️ Bedtime notice: Internet will be blocked in " + minsRemaining + " minutes!");
                        api.log("bedtime_firewall: 15-minute bedtime warning displayed (" + minsRemaining + "m until cutoff)");
                    }
                } else {
                    hideWarning();
                }
            }
        }

        hideWarning();
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

    private void showWarning(final Context ctx, final ScriptApi api, final int minsLeft,
                             final int bHour, final int bMin) {
        if (isWarningShowing) return;
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
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    private void hideWarning() {
        if (!isWarningShowing) return;
        isWarningShowing = false;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && activeWarningView != null) {
                        wm.removeView(activeWarningView);
                        activeWarningView = null;
                    }
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
                hideWarning();
                if (api != null) {
                    api.toast("👍 " + minsLeft + " minutes remaining until bedtime firewall.");
                }
            }
        });

        card.addView(btnOk, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        outer.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return outer;
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
        ScriptApi.BUS.put("bedtime:active", Boolean.FALSE);
    }
}
