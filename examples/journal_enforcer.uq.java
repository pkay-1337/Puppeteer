import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
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
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * journal_enforcer.uq.java — Brutal Morning & Evening Journal Enforcer.
 *
 * Rules:
 *   - Morning Window: 06:00 AM – 11:00 AM
 *   - Evening Window: 09:00 PM – 11:30 PM (21:00 – 23:30)
 *   - Brutal Lockdown: All non-exempt applications (including social media, browsers,
 *     and launcher) are blocked behind a full-screen lockdown overlay until a valid
 *     journal entry is created in /sdcard/Documents/Journal/.
 *   - Auto-Launch: On the first screen unlock within an active window, Journal
 *     (com.pk.journal) is automatically launched to give an immediate, frictionless start.
 *   - Real-Time Unlock: A FileObserver watches the Journal directory; as soon as a note
 *     or audio recording is saved, the lockdown dissolves instantly with a haptic buzz.
 *   - Single Emergency Pass: Exactly 1 five-minute emergency bypass is permitted per window.
 *   - Safety Exemptions: Phone Dialer, Emergency Calls, Termux, and UQ Shell are strictly exempt.
 */
public class JournalEnforcer extends UqScript {

    private static final String JOURNAL_PKG = "com.pk.journal";
    private static final File JOURNAL_DIR = new File("/sdcard/Documents/Journal");

    private static final int WINDOW_NONE = 0;
    private static final int WINDOW_MORNING = 1;
    private static final int WINDOW_EVENING = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View activeOverlayView;
    private volatile boolean isOverlayShowing;
    private FileObserver fileObserver;

    // Configurable parameters
    private int morningStartHour = 6;      // 06:00 AM
    private int morningEndHour = 11;       // 11:00 AM
    private int eveningStartHour = 21;     // 09:00 PM
    private int eveningEndHour = 23;       // 11:00 PM
    private int eveningEndMinute = 30;     // 11:30 PM
    private int earlyBufferHours = 1;      // Entries up to 1 hr early count for the window
    private int emergencyGraceMins = 5;    // 5-minute emergency grace duration
    private int maxEmergencyPasses = 1;    // 1 pass per window
    private boolean autoLaunch = true;     // Auto-open Journal on first unlock
    private String mode = "brutal";        // "brutal" or "friction"

    // Runtime state
    private int currentDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
    private int lastActiveWindow = WINDOW_NONE;
    private boolean morningAutoLaunched = false;
    private boolean eveningAutoLaunched = false;
    private int morningPassesUsed = 0;
    private int eveningPassesUsed = 0;
    private long emergencyGraceExpiresAt = 0L;
    private boolean wasScreenOff = true;
    private String persistentForegroundPkg = null;

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addNumber("morning_start_hour", "Morning Start Hour (24h)",
                        "Hour when morning window begins (e.g. 6 for 06:00 AM)", 6, 4, 11)
                .addNumber("morning_end_hour", "Morning End Hour (24h)",
                        "Hour when morning window ends (e.g. 11 for 11:00 AM)", 11, 7, 13)
                .addNumber("evening_start_hour", "Evening Start Hour (24h)",
                        "Hour when evening window begins (e.g. 21 for 09:00 PM)", 21, 18, 23)
                .addNumber("evening_end_hour", "Evening End Hour (24h)",
                        "Hour when evening window ends (e.g. 23 for 11:00 PM)", 23, 20, 23)
                .addNumber("evening_end_minute", "Evening End Minute",
                        "Minute when evening window ends (e.g. 30 for 11:30 PM)", 30, 0, 59)
                .addNumber("emergency_grace_mins", "Emergency Pass Duration (min)",
                        "Duration of temporary emergency unlock in minutes", 5, 1, 15)
                .addNumber("max_emergency_passes", "Emergency Passes per Window",
                        "Number of emergency passes allowed per window", 1, 0, 3)
                .addBoolean("auto_launch", "Auto-Launch Journal on Unlock",
                        "Automatically launch Journal app on first unlock during active window", true)
                .addChoice("mode", "Strictness Mode",
                        "Enforcement strictness (brutal locks non-exempt apps)", "brutal",
                        new String[]{"brutal", "friction"});
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        applyConfig(newArgs);
    }

    private void applyConfig(Bundle b) {
        if (b == null) return;
        morningStartHour = b.getInt("morning_start_hour", morningStartHour);
        morningEndHour = b.getInt("morning_end_hour", morningEndHour);
        eveningStartHour = b.getInt("evening_start_hour", eveningStartHour);
        eveningEndHour = b.getInt("evening_end_hour", eveningEndHour);
        eveningEndMinute = b.getInt("evening_end_minute", eveningEndMinute);
        emergencyGraceMins = b.getInt("emergency_grace_mins", emergencyGraceMins);
        maxEmergencyPasses = b.getInt("max_emergency_passes", maxEmergencyPasses);
        autoLaunch = b.getBoolean("auto_launch", autoLaunch);
        String m = b.getString("mode");
        if (m != null && m.length() > 0) {
            mode = m.toLowerCase();
        }
    }

    @Override
    public void main(final ScriptApi api, Bundle args) throws Exception {
        api.log("journal_enforcer: starting Brutal Routine Enforcer");
        applyConfig(args);

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("journal_enforcer error: Context unavailable");
            return;
        }

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            api.log("journal_enforcer error: UsageStatsManager unavailable");
            return;
        }

        // Initialize real-time directory watcher
        startWatchingJournalDir(api);

        api.toast("Journal Enforcer active (Morning 6-11 AM, Night 9-11:30 PM)");

        while (!api.stopRequested()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;

            long nowMs = System.currentTimeMillis();
            Calendar nowCal = Calendar.getInstance();

            // Midnight rollover reset
            int day = nowCal.get(Calendar.DAY_OF_YEAR);
            if (day != currentDayOfYear) {
                currentDayOfYear = day;
                morningAutoLaunched = false;
                eveningAutoLaunched = false;
                morningPassesUsed = 0;
                eveningPassesUsed = 0;
                emergencyGraceExpiresAt = 0L;
                api.log("journal_enforcer: midnight passed, daily window state reset");
            }

            int curHour = nowCal.get(Calendar.HOUR_OF_DAY);
            int curMin = nowCal.get(Calendar.MINUTE);
            int curTimeVal = curHour * 60 + curMin;

            int mStart = morningStartHour * 60;
            int mEnd = morningEndHour * 60;
            int eStart = eveningStartHour * 60;
            int eEnd = eveningEndHour * 60 + eveningEndMinute;

            int currentWindow = WINDOW_NONE;
            if (curTimeVal >= mStart && curTimeVal < mEnd) {
                currentWindow = WINDOW_MORNING;
            } else if (curTimeVal >= eStart && curTimeVal < eEnd) {
                currentWindow = WINDOW_EVENING;
            }

            // Window transition
            if (currentWindow != lastActiveWindow) {
                lastActiveWindow = currentWindow;
                emergencyGraceExpiresAt = 0L;
                api.log("journal_enforcer: active window changed to " + getWindowLabel(currentWindow));
            }

            // Outside enforcement windows -> completely unrestricted
            if (currentWindow == WINDOW_NONE) {
                hideOverlay(api);
                ScriptApi.BUS.put("journal_gate:active", Boolean.FALSE);
                ScriptApi.BUS.put("journal_gate:window", "none");
                continue;
            }

            // Check if journal entry is already satisfied for current window
            boolean isSatisfied = hasEntryForWindow(currentWindow);
            ScriptApi.BUS.put("journal_gate:active", Boolean.valueOf(!isSatisfied));
            ScriptApi.BUS.put("journal_gate:window", (currentWindow == WINDOW_MORNING) ? "morning" : "evening");
            ScriptApi.BUS.put("journal_gate:satisfied", Boolean.valueOf(isSatisfied));

            if (isSatisfied) {
                hideOverlay(api);
                continue;
            }

            // Not yet satisfied: check emergency grace
            if (emergencyGraceExpiresAt > 0L) {
                if (nowMs < emergencyGraceExpiresAt) {
                    hideOverlay(api);
                    continue;
                } else {
                    emergencyGraceExpiresAt = 0L;
                    api.toast("⌛ 5-minute emergency pass expired! Journal entry required.");
                    api.vibrate(600);
                    api.log("journal_enforcer: emergency grace expired for window " + getWindowLabel(currentWindow));
                }
            }

            // Screen on/off detection
            boolean screenOn = isScreenOn(ctx);
            if (!screenOn) {
                hideOverlay(api);
                wasScreenOff = true;
                continue;
            }

            // Screen just turned on in active unsatisfied window -> trigger auto-launch
            if (wasScreenOff) {
                wasScreenOff = false;
                if (autoLaunch) {
                    boolean alreadyLaunched = (currentWindow == WINDOW_MORNING) ? morningAutoLaunched : eveningAutoLaunched;
                    if (!alreadyLaunched) {
                        if (currentWindow == WINDOW_MORNING) morningAutoLaunched = true;
                        if (currentWindow == WINDOW_EVENING) eveningAutoLaunched = true;
                        launchJournalApp(ctx, api);
                        api.toast((currentWindow == WINDOW_MORNING) ? "☀️ Morning Journal Required" : "🌙 Evening Reflection Required");
                        api.vibrate(200);
                    }
                }
            }

            // Query foreground package
            String fgPkg = getForegroundPackage(usm, nowMs, persistentForegroundPkg);
            if (fgPkg != null) {
                persistentForegroundPkg = fgPkg;
            }

            // Check exemptions
            if (isExempt(fgPkg)) {
                hideOverlay(api);
                continue;
            }

            // In Brutal mode, non-exempt app in an unsatisfied window triggers lockdown
            showOverlay(ctx, api, currentWindow);
        }

        hideOverlay(api);
        stopWatchingJournalDir();
        api.log("journal_enforcer: main loop exited");
    }

    private void launchJournalApp(Context ctx, ScriptApi api) {
        try {
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(JOURNAL_PKG);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(intent);
                api.log("journal_enforcer: launched " + JOURNAL_PKG);
            }
        } catch (Throwable t) {
            api.log("journal_enforcer: launchJournalApp failed: " + t);
        }
    }

    private boolean hasEntryForWindow(int window) {
        if (!JOURNAL_DIR.exists() || !JOURNAL_DIR.isDirectory()) return false;
        File[] files = JOURNAL_DIR.listFiles();
        if (files == null || files.length == 0) return false;

        Calendar cal = Calendar.getInstance();
        String todayPrefix = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));

        int minTimeVal;
        int maxTimeVal;

        if (window == WINDOW_MORNING) {
            // Morning: startHour - earlyBuffer up to morningEndHour
            minTimeVal = Math.max(0, (morningStartHour - earlyBufferHours) * 60);
            maxTimeVal = morningEndHour * 60;
        } else if (window == WINDOW_EVENING) {
            // Evening: eveningStartHour - earlyBuffer up to midnight / end time
            minTimeVal = Math.max(0, (eveningStartHour - earlyBufferHours) * 60);
            maxTimeVal = eveningEndHour * 60 + eveningEndMinute;
        } else {
            return false;
        }

        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String name = f.getName();
            if ((name.endsWith(".txt") || name.endsWith(".m4a")) && name.startsWith(todayPrefix)) {
                // Name format: YYYY-MM-DD_HH-mm-ss.*
                if (name.length() >= 16 && name.charAt(10) == '_') {
                    try {
                        int hh = Integer.parseInt(name.substring(11, 13));
                        int mm = Integer.parseInt(name.substring(14, 16));
                        int timeVal = hh * 60 + mm;
                        if (timeVal >= minTimeVal && timeVal <= maxTimeVal) {
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        return false;
    }

    private void startWatchingJournalDir(final ScriptApi api) {
        try {
            if (!JOURNAL_DIR.exists()) {
                JOURNAL_DIR.mkdirs();
            }
            fileObserver = new FileObserver(JOURNAL_DIR.getAbsolutePath(),
                    FileObserver.CREATE | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null) return;
                    if (path.endsWith(".txt") || path.endsWith(".m4a")) {
                        api.log("journal_enforcer: observed new journal file: " + path);
                        mainHandler.post(new Runnable() {
                            public void run() {
                                if (hasEntryForWindow(lastActiveWindow)) {
                                    hideOverlay(api);
                                    api.vibrate(300);
                                    api.toast("🎉 Journal recorded! Phone unlocked.");
                                    ScriptApi.BUS.put("journal_gate:active", Boolean.FALSE);
                                    ScriptApi.BUS.put("journal_gate:satisfied", Boolean.TRUE);
                                }
                            }
                        });
                    }
                }
            };
            fileObserver.startWatching();
            api.log("journal_enforcer: FileObserver registered on " + JOURNAL_DIR.getAbsolutePath());
        } catch (Throwable t) {
            api.log("journal_enforcer: failed to start FileObserver: " + t);
        }
    }

    private void stopWatchingJournalDir() {
        if (fileObserver != null) {
            try { fileObserver.stopWatching(); } catch (Throwable ignored) {}
            fileObserver = null;
        }
    }

    private boolean isExempt(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        // Journal itself must ALWAYS be exempt so user can write
        if (pkg.equals(JOURNAL_PKG)) return true;
        // Terminal / UQ Shell
        if (pkg.equals("com.termux") || pkg.startsWith("com.termux.")) return true;
        if (pkg.equals("com.usq.shell")) return true;
        // Phone / Emergency dialers
        if (pkg.contains("dialer") || pkg.equals("com.android.phone") ||
                pkg.contains("telecom") || pkg.contains("incall") || pkg.contains("emergency")) {
            return true;
        }
        // System UI / Permissions
        if (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") || pkg.equals("android")) {
            return true;
        }
        return false;
    }

    private void showOverlay(final Context ctx, final ScriptApi api, final int window) {
        if (isOverlayShowing) return;
        isOverlayShowing = true;
        ScriptApi.BUS.put("ui:top_overlay_time", Long.valueOf(System.currentTimeMillis()));

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

                    activeOverlayView = buildLockdownView(ctx, api, window);

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT
                    );
                    lp.gravity = Gravity.CENTER;

                    wm.addView(activeOverlayView, lp);
                    api.log("journal_enforcer: lockdown overlay displayed for " + getWindowLabel(window));
                } catch (Throwable t) {
                    api.log("journal_enforcer: showOverlay failed: " + t);
                    isOverlayShowing = false;
                }
            }
        });
    }

    private void hideOverlay(final ScriptApi api) {
        if (!isOverlayShowing) return;
        isOverlayShowing = false;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && activeOverlayView != null) {
                        wm.removeView(activeOverlayView);
                        activeOverlayView = null;
                        if (api != null) api.log("journal_enforcer: overlay dismissed");
                    }
                } catch (Throwable t) {
                    if (api != null) api.log("journal_enforcer: hideOverlay failed: " + t);
                }
            }
        });
    }

    private View buildLockdownView(final Context ctx, final ScriptApi api, final int window) {
        final boolean isMorning = (window == WINDOW_MORNING);

        ScrollView scroll = new ScrollView(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                // Intercept hardware Back key -> redirect directly to Journal
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    try {
                        api.vibrate(40);
                        hideOverlay(api);
                        launchJournalApp(ctx, api);
                        return true;
                    } catch (Throwable ignored) {}
                }
                return super.dispatchKeyEvent(event);
            }
        };
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xF2080A0F); // 95% Deep Obsidian

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24, ctx);
        root.setPadding(pad, pad, pad, pad);

        // Lockdown Card Container
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int cPad = dp(22, ctx);
        card.setPadding(cPad, cPad, cPad, cPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF141720);
        cardBg.setCornerRadius(dp(18, ctx));
        cardBg.setStroke(dp(1, ctx), isMorning ? 0xFF3D3214 : 0xFF281E3D);
        card.setBackground(cardBg);

        // Header Pill
        TextView pill = new TextView(ctx);
        pill.setText(isMorning ? "☀️ MORNING ROUTINE" : "🌙 EVENING REFLECTION");
        pill.setTextSize(11);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextColor(isMorning ? 0xFFFFB300 : 0xFFB388FF);
        int pPadH = dp(12, ctx);
        int pPadV = dp(5, ctx);
        pill.setPadding(pPadH, pPadV, pPadH, pPadV);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(isMorning ? 0x26FFC107 : 0x267C4DFF);
        pillBg.setCornerRadius(dp(12, ctx));
        pill.setBackground(pillBg);
        card.addView(pill);

        // Title
        TextView title = new TextView(ctx);
        title.setText(isMorning ? "Morning Journal Required" : "Night Reflection Required");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14, ctx), 0, dp(6, ctx));
        card.addView(title);

        // Subtitle
        TextView subtitle = new TextView(ctx);
        subtitle.setText(isMorning
                ? "Record or write a brief intention for today. Apps are locked until your entry is saved."
                : "Record your daily reflection before sleeping. Apps are locked until your entry is saved.");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFF94A3B8);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(2, ctx), 1.15f);
        card.addView(subtitle);

        // Window Info Badge
        TextView info = new TextView(ctx);
        String windowText = isMorning
                ? "● Active: 06:00 AM – 11:00 AM  •  No entry logged"
                : "● Active: 09:00 PM – 11:30 PM  •  No entry logged";
        info.setText(windowText);
        info.setTextSize(11);
        info.setTextColor(0xFFF87171); // Warning red accent
        info.setPadding(0, dp(12, ctx), 0, dp(16, ctx));
        card.addView(info);

        // Primary Action: Open Journal
        Button openBtn = new Button(ctx);
        openBtn.setText("✍️  OPEN JOURNAL NOW");
        openBtn.setTextSize(14);
        openBtn.setTypeface(Typeface.DEFAULT_BOLD);
        openBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable openBg = new GradientDrawable();
        openBg.setColor(0xFF15803D); // Emerald 700
        openBg.setCornerRadius(dp(12, ctx));
        openBtn.setBackground(openBg);
        openBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                api.vibrate(50);
                hideOverlay(api);
                launchJournalApp(ctx, api);
            }
        });
        card.addView(openBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48, ctx)));

        // Emergency Pass Button
        final int passesUsed = isMorning ? morningPassesUsed : eveningPassesUsed;
        final boolean hasPass = (passesUsed < maxEmergencyPasses);

        Button passBtn = new Button(ctx);
        passBtn.setTextSize(13);
        passBtn.setTypeface(Typeface.DEFAULT_BOLD);

        if (hasPass) {
            int remaining = maxEmergencyPasses - passesUsed;
            passBtn.setText("⏳ 5-Min Emergency Pass (" + remaining + " Left)");
            passBtn.setTextColor(0xFFE2E8F0);
            GradientDrawable passBg = new GradientDrawable();
            passBg.setColor(0xFF1E2433);
            passBg.setCornerRadius(dp(12, ctx));
            passBg.setStroke(dp(1, ctx), 0xFF334155);
            passBtn.setBackground(passBg);
            passBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (isMorning) morningPassesUsed++; else eveningPassesUsed++;
                    emergencyGraceExpiresAt = System.currentTimeMillis() + (emergencyGraceMins * 60000L);
                    api.vibrate(200);
                    api.toast("⏳ Emergency pass granted: " + emergencyGraceMins + " minutes unlocked");
                    api.log("journal_enforcer: emergency pass used for " + getWindowLabel(window));
                    hideOverlay(api);
                }
            });
        } else {
            passBtn.setText("🔒 Emergency Pass Used (Zero Remaining)");
            passBtn.setTextColor(0xFF64748B);
            GradientDrawable disabledBg = new GradientDrawable();
            disabledBg.setColor(0xFF12151D);
            disabledBg.setCornerRadius(dp(12, ctx));
            passBtn.setBackground(disabledBg);
            passBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    api.vibrate(500);
                    api.toast("No emergency passes left. Please record your journal entry to unlock.");
                }
            });
        }

        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44, ctx));
        pLp.setMargins(0, dp(10, ctx), 0, 0);
        card.addView(passBtn, pLp);

        // Emergency Safety Links (Dialer & Termux)
        LinearLayout bottomRow = new LinearLayout(ctx);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER);
        bottomRow.setPadding(0, dp(14, ctx), 0, 0);

        TextView callTv = new TextView(ctx);
        callTv.setText("📞 Phone Dialer");
        callTv.setTextSize(12);
        callTv.setTextColor(0xFF38BDF8);
        callTv.setPadding(dp(8, ctx), dp(4, ctx), dp(8, ctx), dp(4, ctx));
        callTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent dial = new Intent(Intent.ACTION_DIAL);
                    dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(dial);
                } catch (Throwable ignored) {}
            }
        });
        bottomRow.addView(callTv);

        TextView sep = new TextView(ctx);
        sep.setText("  •  ");
        sep.setTextColor(0xFF475569);
        bottomRow.addView(sep);

        TextView termuxTv = new TextView(ctx);
        termuxTv.setText("💻 Termux Shell");
        termuxTv.setTextSize(12);
        termuxTv.setTextColor(0xFF38BDF8);
        termuxTv.setPadding(dp(8, ctx), dp(4, ctx), dp(8, ctx), dp(4, ctx));
        termuxTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent tx = ctx.getPackageManager().getLaunchIntentForPackage("com.termux");
                    if (tx != null) {
                        tx.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(tx);
                    }
                } catch (Throwable ignored) {}
            }
        });
        bottomRow.addView(termuxTv);

        card.addView(bottomRow);

        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private static String getWindowLabel(int window) {
        if (window == WINDOW_MORNING) return "Morning (6-11 AM)";
        if (window == WINDOW_EVENING) return "Evening (9-11:30 PM)";
        return "None";
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
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
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
        hideOverlay(null);
        stopWatchingJournalDir();
    }
}
