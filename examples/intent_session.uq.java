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
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * intent_session.uq.java — Intent-Driven Focus Sessions (Integrated with App Blocker).
 * 
 * Architecture (Approach 2 - Unified Lifecycle):
 *   - Inter-script coordination with 'blocker' via ScriptApi.BUS.
 *   - Caps session duration at current remaining daily budget.
 *   - When session expires, allows extending up to the remaining daily limit.
 *   - Hands off seamlessly to App Blocker once daily limit hits 0.
 *   - Seamlessly adopts 5-min Emergency Passes without double-prompting.
 *   - Mutual exclusion: prevents overlay stacking or visual flickering.
 */
public class IntentSession extends UqScript {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private ScriptApi api;

    // Configured apps
    private final Set<String> monitoredApps = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private int defaultDurationMins = 5;
    private int fallbackDailyLimitMins = 30;

    // Active session state
    public static class Session {
        public String pkg;
        public String intent;
        public long durationMs;
        public long startTimestamp;
        public long expiresAt;
        public boolean isEmergency = false;
        public int extendedMins = 0;
    }

    private final Map<String, Session> activeSessions = new ConcurrentHashMap<String, Session>();

    // UI overlays
    private View promptOverlayView;
    private FrameLayout hudPillView;
    private TextView hudText;
    private WindowManager.LayoutParams hudLp;
    private View completeOverlayView;

    private String currentMonitoredPkg = null;
    private volatile boolean isPromptShowing = false;
    private volatile boolean isHudShowing = false;
    private volatile boolean isCompleteShowing = false;

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addAppMultiSelect("monitored_apps", "Intent-Enforced Apps",
                        "Apps that require intent & time before using. Prompts appear when opened.")
                .addNumber("default_duration_mins", "Default Duration (mins)",
                        "Standard session time pre-selected in dialog", 5, 1, 60)
                .addNumber("fallback_daily_limit_mins", "Fallback Daily Limit (mins)",
                        "Daily budget assumed if App Blocker is not monitoring this app", 30, 5, 180);
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        applyConfig(newArgs);
    }

    private void applyConfig(Bundle b) {
        if (b == null) return;
        ArrayList<String> apps = b.getStringArrayList("monitored_apps");
        if (apps != null && !apps.isEmpty()) {
            monitoredApps.clear();
            monitoredApps.addAll(apps);
        }
        defaultDurationMins = b.getInt("default_duration_mins", defaultDurationMins);
        fallbackDailyLimitMins = b.getInt("fallback_daily_limit_mins", fallbackDailyLimitMins);
    }

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        this.api = api;
        api.log("intent_session: starting Intent-Driven Sessions daemon (integrated mode)");

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("intent_session error: Context unavailable");
            return;
        }

        // Initialize default monitored apps if empty
        if (monitoredApps.isEmpty()) {
            monitoredApps.add("com.linkedin.android");
            monitoredApps.add("com.brave.browser");
            monitoredApps.add("org.lichess.mobileV2");
            monitoredApps.add("com.substack.app");
            monitoredApps.add("com.zeptoconsumerapp");
            monitoredApps.add("com.myntra.android");
        }

        if (args != null) {
            applyConfig(args);
        }
        api.log("intent_session: monitoring " + monitoredApps.size() + " apps: " + monitoredApps);

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);

        // Pre-create HUD pill
        initHudPill(ctx);

        String lastKnownActivePkg = null;

        while (!api.stopRequested()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;

            long now = System.currentTimeMillis();
            String fgPkg = getForegroundPackage(usm, now, lastKnownActivePkg);
            if (fgPkg != null) {
                lastKnownActivePkg = fgPkg;
            }

            boolean isMonitored = (fgPkg != null && monitoredApps.contains(fgPkg));

            if (!isMonitored) {
                // Outside monitored apps -> Hide HUD and prompts
                if (currentMonitoredPkg != null) {
                    setOverlayActiveBus(currentMonitoredPkg, false);
                    currentMonitoredPkg = null;
                }
                hidePrompt();
                hideHud();
                hideComplete();
            } else {
                currentMonitoredPkg = fgPkg;

                // 1. Check for Emergency Pass trigger from App Blocker
                Long emgSession = (Long) ScriptApi.BUS.remove("intent:start_emergency_session:" + fgPkg);
                if (emgSession != null && emgSession > now) {
                    Session es = new Session();
                    es.pkg = fgPkg;
                    es.intent = "⚡ Emergency Unlock Pass";
                    es.durationMs = 5L * 60000L;
                    es.startTimestamp = now;
                    es.expiresAt = emgSession;
                    es.isEmergency = true;
                    activeSessions.put(fgPkg, es);
                    hidePrompt();
                    hideComplete();
                    setOverlayActiveBus(fgPkg, false);
                    api.log("intent_session: adopted 5m emergency pass for " + fgPkg);
                }

                // 2. Query real-time budget from blocker / usage stats
                long remDailyMs = getRemainingDailyMs(usm, fgPkg, now);
                boolean isDailyExceeded = isDailyExceeded(usm, fgPkg, now);
                boolean isDailyGrace = isDailyGraceActive(fgPkg);

                Session session = activeSessions.get(fgPkg);

                if (session == null) {
                    // No active session
                    if (isDailyExceeded && !isDailyGrace) {
                        // Daily limit exhausted and not in emergency grace -> Yield completely to blocker!
                        hidePrompt();
                        hideHud();
                        hideComplete();
                        setOverlayActiveBus(fgPkg, false);
                    } else {
                        // We have budget remaining -> Show Intent Prompt Dialog
                        hideHud();
                        hideComplete();
                        setOverlayActiveBus(fgPkg, true);
                        showPrompt(ctx, fgPkg, remDailyMs);
                    }
                } else {
                    // Active session in progress
                    hidePrompt();

                    if (now >= session.expiresAt) {
                        // Session expired!
                        hideHud();
                        setOverlayActiveBus(fgPkg, true);
                        showComplete(ctx, session, remDailyMs);
                    } else {
                        // Active countdown
                        hideComplete();
                        setOverlayActiveBus(fgPkg, false);
                        long remMs = session.expiresAt - now;
                        long remSec = remMs / 1000L;
                        String timeStr = String.format("%02d:%02d", (int)(remSec / 60), (int)(remSec % 60));
                        updateHud(true, session.intent, timeStr, remDailyMs);
                    }
                }
            }
        }

        removeAllViews();
        api.log("intent_session: daemon stopped");
    }

    private void setOverlayActiveBus(String pkg, boolean active) {
        if (pkg != null) {
            ScriptApi.BUS.put("intent:overlay_active:" + pkg, Boolean.valueOf(active));
        }
    }

    private long getRemainingDailyMs(UsageStatsManager usm, String pkg, long now) {
        Long bRem = (Long) ScriptApi.BUS.get("blocker:rem_ms:" + pkg);
        if (bRem != null) {
            return Math.max(0L, bRem.longValue());
        }

        // Fallback ground truth calculation
        Long bLimit = (Long) ScriptApi.BUS.get("blocker:limit_ms:" + pkg);
        long limitMs = (bLimit != null) ? bLimit.longValue() : ((long) fallbackDailyLimitMins * 60000L);
        long usedMs = getTodayUsageMs(usm, pkg, now);
        return Math.max(0L, limitMs - usedMs);
    }

    private boolean isDailyExceeded(UsageStatsManager usm, String pkg, long now) {
        Boolean bExc = (Boolean) ScriptApi.BUS.get("blocker:is_exceeded:" + pkg);
        if (bExc != null) {
            return bExc.booleanValue();
        }
        return getRemainingDailyMs(usm, pkg, now) <= 0L;
    }

    private boolean isDailyGraceActive(String pkg) {
        Boolean bGrace = (Boolean) ScriptApi.BUS.get("blocker:is_grace:" + pkg);
        return (bGrace != null && bGrace.booleanValue());
    }

    private long getTodayUsageMs(UsageStatsManager usm, String pkg, long now) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startToday = cal.getTimeInMillis();

        try {
            Map<String, UsageStats> agg = usm.queryAndAggregateUsageStats(startToday, now);
            if (agg != null && agg.containsKey(pkg)) {
                UsageStats us = agg.get(pkg);
                if (us != null) {
                    return us.getTotalTimeInForeground();
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    private void showPrompt(final Context ctx, final String pkg, final long remDailyMs) {
        if (isPromptShowing) return;
        isPromptShowing = true;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm == null) {
                        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    }
                    if (promptOverlayView != null) {
                        try { wm.removeView(promptOverlayView); } catch (Throwable ignored) {}
                        promptOverlayView = null;
                    }

                    promptOverlayView = buildPromptView(ctx, pkg, remDailyMs);

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT
                    );
                    lp.gravity = Gravity.CENTER;

                    wm.addView(promptOverlayView, lp);
                } catch (Throwable t) {
                    if (api != null) api.log("showPrompt error: " + t);
                    isPromptShowing = false;
                }
            }
        });
    }

    private void hidePrompt() {
        if (!isPromptShowing) return;
        isPromptShowing = false;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && promptOverlayView != null) {
                        wm.removeView(promptOverlayView);
                        promptOverlayView = null;
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private View buildPromptView(final Context ctx, final String pkg, final long remDailyMs) {
        ScrollView scroll = new ScrollView(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    hidePrompt();
                    setOverlayActiveBus(pkg, false);
                    try {
                        Intent home = new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(home);
                    } catch (Throwable ignored) {}
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xEE0B0E17); // 93% obsidian backdrop

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(20, ctx);
        root.setPadding(pad, pad, pad, pad);

        // Main Card
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int cPad = dp(20, ctx);
        card.setPadding(cPad, cPad, cPad, cPad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF131826);
        bg.setCornerRadius(dp(16, ctx));
        bg.setStroke(dp(1.5f, ctx), 0xFF38BDF8); // Sky blue accent
        card.setBackground(bg);

        // Header Title
        TextView title = new TextView(ctx);
        title.setText("🎯 SET SESSION INTENT");
        title.setTextSize(18);
        title.setTextColor(0xFF38BDF8);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        String appLabel = (api != null) ? api.appLabel(pkg) : pkg;
        int remDailyMins = (int) Math.max(1, remDailyMs / 60000L);

        TextView sub = new TextView(ctx);
        sub.setText("What is your goal in " + appLabel + "?\n⚡ Daily budget remaining: " + remDailyMins + "m");
        sub.setTextSize(13);
        sub.setTextColor(0xFF94A3B8);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4, ctx), 0, dp(14, ctx));
        card.addView(sub);

        // Custom Intent EditText
        final EditText etIntent = new EditText(ctx);
        etIntent.setHint("e.g. Check order status, reply to Alex...");
        etIntent.setHintTextColor(0xFF64748B);
        etIntent.setTextColor(0xFFFFFFFF);
        etIntent.setTextSize(14);
        etIntent.setSingleLine(true);
        etIntent.setPadding(dp(12, ctx), dp(10, ctx), dp(12, ctx), dp(10, ctx));

        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(0xFF1E2638);
        etBg.setCornerRadius(dp(8, ctx));
        etBg.setStroke(dp(1, ctx), 0xFF334155);
        etIntent.setBackground(etBg);
        card.addView(etIntent);

        // Quick Intent Chips Row
        TextView quickTv = new TextView(ctx);
        quickTv.setText("Quick presets:");
        quickTv.setTextSize(11);
        quickTv.setTextColor(0xFF64748B);
        quickTv.setPadding(0, dp(10, ctx), 0, dp(4, ctx));
        card.addView(quickTv);

        LinearLayout chipsRow = new LinearLayout(ctx);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);

        final String[] presets = new String[]{"✉️ Reply / Msg", "🔍 Specific Query", "💼 Work Task", "⚡ Quick 2m Check"};
        for (final String preset : presets) {
            Button btnChip = new Button(ctx);
            btnChip.setText(preset);
            btnChip.setTextSize(10);
            btnChip.setTextColor(0xFFE2E8F0);
            GradientDrawable chBg = new GradientDrawable();
            chBg.setColor(0xFF1E293B);
            chBg.setCornerRadius(dp(6, ctx));
            btnChip.setBackground(chBg);
            btnChip.setPadding(dp(6, ctx), dp(4, ctx), dp(6, ctx), dp(4, ctx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2, ctx), 0, dp(2, ctx), 0);

            btnChip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    etIntent.setText(preset);
                }
            });
            chipsRow.addView(btnChip, lp);
        }
        card.addView(chipsRow);

        // Duration Section
        final int maxMins = remDailyMins;
        final int[] selectedMins = new int[]{Math.min(defaultDurationMins, maxMins)};

        final TextView durTv = new TextView(ctx);
        durTv.setText("⏳ Duration: " + selectedMins[0] + " mins (max " + maxMins + "m)");
        durTv.setTextSize(13);
        durTv.setTextColor(0xFFFBBF24); // Amber
        durTv.setTypeface(Typeface.DEFAULT_BOLD);
        durTv.setPadding(0, dp(14, ctx), 0, dp(4, ctx));
        card.addView(durTv);

        // Adaptive Time Pills Row: e.g. 2m, 5m, 10m, 15m, All Xm
        LinearLayout timePills = new LinearLayout(ctx);
        timePills.setOrientation(LinearLayout.HORIZONTAL);

        final SeekBar sb = new SeekBar(ctx);
        sb.setMax(Math.max(1, maxMins - 1));
        sb.setProgress(Math.max(0, selectedMins[0] - 1));

        int[] candidatePills = new int[]{2, 5, 10, 15};
        List<Integer> validPills = new ArrayList<Integer>();
        for (int p : candidatePills) {
            if (p < maxMins) validPills.add(Integer.valueOf(p));
        }
        validPills.add(Integer.valueOf(maxMins)); // Always offer full remaining balance

        for (final Integer ptObj : validPills) {
            final int pt = ptObj.intValue();
            Button pill = new Button(ctx);
            pill.setText(pt == maxMins ? "All " + pt + "m" : pt + "m");
            pill.setTextSize(10);
            pill.setTextColor(0xFFFFFFFF);
            GradientDrawable pBg = new GradientDrawable();
            pBg.setColor(pt == maxMins ? 0xFF0D9488 : 0xFF334155);
            pBg.setCornerRadius(dp(6, ctx));
            pill.setBackground(pBg);
            pill.setPadding(dp(6, ctx), dp(4, ctx), dp(6, ctx), dp(4, ctx));
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pLp.setMargins(dp(2, ctx), 0, dp(2, ctx), 0);

            pill.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectedMins[0] = pt;
                    sb.setProgress(Math.max(0, pt - 1));
                    durTv.setText("⏳ Duration: " + pt + " mins (max " + maxMins + "m)");
                }
            });
            timePills.addView(pill, pLp);
        }
        card.addView(timePills);

        // Fine SeekBar (1 to maxMins)
        sb.setPadding(0, dp(8, ctx), 0, dp(8, ctx));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = 1 + progress;
                if (val > maxMins) val = maxMins;
                selectedMins[0] = val;
                durTv.setText("⏳ Duration: " + val + " mins (max " + maxMins + "m)");
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        card.addView(sb);

        // Start Session Button
        Button btnStart = new Button(ctx);
        btnStart.setText("🚀 Start Focused Session");
        btnStart.setTextSize(15);
        btnStart.setTextColor(0xFF0F172A);
        btnStart.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable sBg = new GradientDrawable();
        sBg.setColor(0xFF38BDF8); // Sky blue
        sBg.setCornerRadius(dp(8, ctx));
        btnStart.setBackground(sBg);
        btnStart.setPadding(dp(16, ctx), dp(12, ctx), dp(16, ctx), dp(12, ctx));

        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String intentText = etIntent.getText().toString().trim();
                if (intentText.isEmpty()) {
                    intentText = "Focused Session";
                }
                long now = System.currentTimeMillis();
                Session s = new Session();
                s.pkg = pkg;
                s.intent = intentText;
                s.durationMs = selectedMins[0] * 60000L;
                s.startTimestamp = now;
                s.expiresAt = now + s.durationMs;

                activeSessions.put(pkg, s);
                hidePrompt();
                setOverlayActiveBus(pkg, false);

                if (api != null) {
                    api.toast("🎯 Session started: " + selectedMins[0] + "m for \"" + intentText + "\"");
                    api.vibrate(50);
                    api.log("intent_session: started " + selectedMins[0] + "m session for " + pkg + " with intent: " + intentText);
                }
            }
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, dp(14, ctx), 0, 0);
        card.addView(btnStart, btnLp);

        // Exit to Home button
        Button btnExit = new Button(ctx);
        btnExit.setText("✕ Cancel & Return Home");
        btnExit.setTextSize(12);
        btnExit.setTextColor(0xFF94A3B8);
        GradientDrawable eBg = new GradientDrawable();
        eBg.setColor(0x00000000); // Transparent
        btnExit.setBackground(eBg);
        btnExit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hidePrompt();
                setOverlayActiveBus(pkg, false);
                try {
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(home);
                } catch (Throwable ignored) {}
            }
        });
        card.addView(btnExit);

        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(root);
        return scroll;
    }

    private void initHudPill(final Context ctx) {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm == null) {
                        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    }
                    DisplayMetrics dm = ctx.getResources().getDisplayMetrics();

                    hudLp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            dp(34, ctx),
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                            PixelFormat.TRANSLUCENT
                    );
                    hudLp.gravity = Gravity.TOP | Gravity.START;
                    hudLp.x = dm.widthPixels / 2 - dp(85, ctx);
                    hudLp.y = dp(28, ctx); // Near top status bar

                    hudPillView = new FrameLayout(ctx);
                    hudPillView.setVisibility(View.GONE);

                    GradientDrawable pBg = new GradientDrawable();
                    pBg.setColor(0xE60F172A); // 90% Obsidian
                    pBg.setCornerRadius(dp(17, ctx));
                    pBg.setStroke(dp(1.5f, ctx), 0xFF38BDF8); // Cyan border
                    hudPillView.setBackground(pBg);
                    hudPillView.setPadding(dp(12, ctx), dp(4, ctx), dp(12, ctx), dp(4, ctx));

                    hudText = new TextView(ctx);
                    hudText.setText("🎯 Focus · ⏳ 05:00");
                    hudText.setTextSize(12);
                    hudText.setTextColor(0xFFF8FAFC);
                    hudText.setTypeface(Typeface.DEFAULT_BOLD);
                    hudText.setGravity(Gravity.CENTER);
                    hudPillView.addView(hudText);

                    // Drag listener + Tap for remaining balance
                    hudPillView.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX, initialY;
                        private float touchX, touchY;
                        private boolean isDrag;
                        private long downTime;

                        public boolean onTouch(View v, MotionEvent event) {
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    initialX = hudLp.x;
                                    initialY = hudLp.y;
                                    touchX = event.getRawX();
                                    touchY = event.getRawY();
                                    isDrag = false;
                                    downTime = System.currentTimeMillis();
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    int dx = (int) (event.getRawX() - touchX);
                                    int dy = (int) (event.getRawY() - touchY);
                                    if (Math.abs(dx) > dp(6, ctx) || Math.abs(dy) > dp(6, ctx)) isDrag = true;
                                    if (isDrag) {
                                        hudLp.x = initialX + dx;
                                        hudLp.y = initialY + dy;
                                        try { wm.updateViewLayout(hudPillView, hudLp); } catch (Throwable ignored) {}
                                    }
                                    return true;
                                case MotionEvent.ACTION_UP:
                                    if (!isDrag && (System.currentTimeMillis() - downTime < 350)) {
                                        // Single tap on HUD -> show remaining time toast
                                        if (currentMonitoredPkg != null) {
                                            Session s = activeSessions.get(currentMonitoredPkg);
                                            if (s != null && api != null) {
                                                long rem = Math.max(0, s.expiresAt - System.currentTimeMillis()) / 1000L;
                                                Long bRem = (Long) ScriptApi.BUS.get("blocker:rem_ms:" + currentMonitoredPkg);
                                                long dailyRemMins = (bRem != null) ? (bRem.longValue() / 60000L) : -1L;
                                                String dailyInfo = (dailyRemMins >= 0) ? " · Daily: " + dailyRemMins + "m left" : "";
                                                api.toast("🎯 \"" + s.intent + "\" · " + (rem / 60) + "m " + (rem % 60) + "s left" + dailyInfo);
                                                api.vibrate(25);
                                            }
                                        }
                                    }
                                    return true;
                            }
                            return false;
                        }
                    });

                    wm.addView(hudPillView, hudLp);
                } catch (Throwable t) {
                    if (api != null) api.log("initHudPill error: " + t);
                }
            }
        });
    }

    private void updateHud(final boolean visible, final String intent, final String timeStr, final long remDailyMs) {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (hudPillView != null && hudText != null) {
                        hudPillView.setVisibility(visible ? View.VISIBLE : View.GONE);
                        if (visible) {
                            String truncatedIntent = (intent.length() > 14) ? intent.substring(0, 13) + "…" : intent;
                            hudText.setText("🎯 " + truncatedIntent + " · ⏳ " + timeStr);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void hideHud() {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (hudPillView != null) {
                        hudPillView.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void showComplete(final Context ctx, final Session session, final long remDailyMs) {
        if (isCompleteShowing) return;
        isCompleteShowing = true;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    if (completeOverlayView != null) {
                        try { wm.removeView(completeOverlayView); } catch (Throwable ignored) {}
                        completeOverlayView = null;
                    }

                    completeOverlayView = buildCompleteView(ctx, session, remDailyMs);

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT
                    );
                    lp.gravity = Gravity.CENTER;

                    wm.addView(completeOverlayView, lp);
                    if (api != null) api.vibrate(500);
                } catch (Throwable t) {
                    isCompleteShowing = false;
                }
            }
        });
    }

    private void hideComplete() {
        if (!isCompleteShowing) return;
        isCompleteShowing = false;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && completeOverlayView != null) {
                        wm.removeView(completeOverlayView);
                        completeOverlayView = null;
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private View buildCompleteView(final Context ctx, final Session session, final long remDailyMs) {
        ScrollView scroll = new ScrollView(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    activeSessions.remove(session.pkg);
                    hideComplete();
                    setOverlayActiveBus(session.pkg, false);
                    try {
                        Intent home = new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(home);
                    } catch (Throwable ignored) {}
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xF50B0E17);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24, ctx);
        root.setPadding(pad, pad, pad, pad);

        TextView icon = new TextView(ctx);
        icon.setText("🎯 ⌛");
        icon.setTextSize(46);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);

        TextView title = new TextView(ctx);
        title.setText("SESSION TIME EXPIRED");
        title.setTextSize(22);
        title.setTextColor(0xFFF59E0B); // Amber
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8, ctx), 0, 0);
        root.addView(title);

        final int remDailyMins = (int) Math.max(0, remDailyMs / 60000L);

        TextView desc = new TextView(ctx);
        if (remDailyMins > 0) {
            desc.setText("Your session for \"" + session.intent + "\" has finished.\n⚡ Daily balance remaining: " + remDailyMins + " minutes");
        } else {
            desc.setText("Your session for \"" + session.intent + "\" has finished.\n🛑 You have used your full daily limit for today.");
        }
        desc.setTextSize(14);
        desc.setTextColor(0xFFE2E8F0);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(6, ctx), 0, dp(18, ctx));
        root.addView(desc);

        // Home button
        Button homeBtn = new Button(ctx);
        homeBtn.setText("🏠 Exit to Home Screen");
        homeBtn.setTextSize(15);
        homeBtn.setTextColor(0xFFFFFFFF);
        homeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(0xFF4F46E5); // Indigo
        hBg.setCornerRadius(dp(8, ctx));
        homeBtn.setBackground(hBg);
        homeBtn.setPadding(dp(16, ctx), dp(12, ctx), dp(16, ctx), dp(12, ctx));
        homeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                activeSessions.remove(session.pkg);
                hideComplete();
                setOverlayActiveBus(session.pkg, false);
                try {
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(home);
                } catch (Throwable ignored) {}
            }
        });
        root.addView(homeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Extension section (only if remDailyMins > 0)
        if (remDailyMins > 0) {
            final int maxExtMins = remDailyMins;
            final int[] selectedExt = new int[]{Math.min(5, maxExtMins)};

            TextView extHeading = new TextView(ctx);
            extHeading.setText("⚡ Extend session (up to daily balance of " + maxExtMins + "m):");
            extHeading.setTextSize(12);
            extHeading.setTextColor(0xFF94A3B8);
            extHeading.setPadding(0, dp(16, ctx), 0, dp(6, ctx));
            root.addView(extHeading);

            final TextView extTv = new TextView(ctx);
            extTv.setText("Extend by: +" + selectedExt[0] + " mins");
            extTv.setTextSize(13);
            extTv.setTextColor(0xFFFBBF24);
            extTv.setTypeface(Typeface.DEFAULT_BOLD);
            extTv.setPadding(0, 0, 0, dp(6, ctx));
            root.addView(extTv);

            final SeekBar extSb = new SeekBar(ctx);
            extSb.setMax(Math.max(1, maxExtMins - 1));
            extSb.setProgress(Math.max(0, selectedExt[0] - 1));

            // Pills: 2m, 5m, 10m, All Xm
            LinearLayout pillsRow = new LinearLayout(ctx);
            pillsRow.setOrientation(LinearLayout.HORIZONTAL);
            int[] extPillTimes = new int[]{2, 5, 10};
            List<Integer> extPills = new ArrayList<Integer>();
            for (int p : extPillTimes) {
                if (p < maxExtMins) extPills.add(Integer.valueOf(p));
            }
            extPills.add(Integer.valueOf(maxExtMins));

            for (final Integer epObj : extPills) {
                final int ep = epObj.intValue();
                Button pill = new Button(ctx);
                pill.setText(ep == maxExtMins ? "All " + ep + "m" : "+" + ep + "m");
                pill.setTextSize(10);
                pill.setTextColor(0xFFFFFFFF);
                GradientDrawable pBg = new GradientDrawable();
                pBg.setColor(ep == maxExtMins ? 0xFF0D9488 : 0xFF334155);
                pBg.setCornerRadius(dp(6, ctx));
                pill.setBackground(pBg);
                pill.setPadding(dp(6, ctx), dp(4, ctx), dp(6, ctx), dp(4, ctx));
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                pLp.setMargins(dp(2, ctx), 0, dp(2, ctx), 0);

                pill.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        selectedExt[0] = ep;
                        extSb.setProgress(Math.max(0, ep - 1));
                        extTv.setText("Extend by: +" + ep + " mins");
                    }
                });
                pillsRow.addView(pill, pLp);
            }
            root.addView(pillsRow);

            // SeekBar for fine-grained extension
            extSb.setPadding(0, dp(8, ctx), 0, dp(8, ctx));
            extSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = 1 + progress;
                    if (val > maxExtMins) val = maxExtMins;
                    selectedExt[0] = val;
                    extTv.setText("Extend by: +" + val + " mins");
                }
                public void onStartTrackingTouch(SeekBar seekBar) {}
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            root.addView(extSb);

            // Extend Button
            Button extBtn = new Button(ctx);
            extBtn.setText("⏳ Confirm Extension & Keep Working");
            extBtn.setTextSize(14);
            extBtn.setTextColor(0xFF0F172A);
            extBtn.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable extBg = new GradientDrawable();
            extBg.setColor(0xFFFBBF24); // Amber
            extBg.setCornerRadius(dp(8, ctx));
            extBtn.setBackground(extBg);
            extBtn.setPadding(dp(14, ctx), dp(10, ctx), dp(14, ctx), dp(10, ctx));

            extBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    session.extendedMins += selectedExt[0];
                    session.expiresAt = System.currentTimeMillis() + (selectedExt[0] * 60000L);
                    hideComplete();
                    setOverlayActiveBus(session.pkg, false);
                    if (api != null) {
                        api.toast("⚡ +" + selectedExt[0] + " minutes added to session!");
                        api.vibrate(50);
                    }
                }
            });

            LinearLayout.LayoutParams extLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            extLp.setMargins(0, dp(6, ctx), 0, 0);
            root.addView(extBtn, extLp);
        }

        scroll.addView(root);
        return scroll;
    }

    private void removeAllViews() {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null) {
                        if (promptOverlayView != null) wm.removeView(promptOverlayView);
                        if (hudPillView != null) wm.removeView(hudPillView);
                        if (completeOverlayView != null) wm.removeView(completeOverlayView);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String getForegroundPackage(UsageStatsManager usm, long now, String lastKnownPkg) {
        try {
            UsageEvents events = usm.queryEvents(now - 15000, now);
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
        return null;
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

    private static int dp(float v, Context ctx) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onStop() {
        if (currentMonitoredPkg != null) {
            setOverlayActiveBus(currentMonitoredPkg, false);
        }
        removeAllViews();
    }
}
