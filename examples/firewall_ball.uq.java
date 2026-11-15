import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * firewall_ball.uq.java — Floating Interactive Per-App Firewall Ball.
 * 
 * Features:
 *   - Configurable "default_blocked_apps": launch with NO internet by default.
 *   - Configurable "default_allowed_apps": launch with normal internet.
 *   - Red apps stay behind the firewall even when you exit (no background internet).
 *   - Draggable floating indicator ball appears ONLY when a monitored app is open.
 *   - 🔴 RED BALL = App has zero internet access (blackholed).
 *   - 🟢 GREEN BALL = App is allowed internet access.
 *   - Tapping the ball instantly toggles internet for that specific app.
 *   - Ball automatically hides when you leave the app.
 *   - Termux & UQ Shell are strictly excluded so SSH is never cut off.
 */
public class FirewallBall extends UqScript {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout ballView;
    private TextView iconView;
    private GradientDrawable ballBg;
    private WindowManager.LayoutParams ballLp;
    private ScriptApi api;

    // Configuration lists
    private final Set<String> defaultBlockedApps = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> defaultAllowedApps = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private boolean resetOnExit = true;
    private boolean hapticFeedback = true;

    // Live runtime firewall set: packages currently blackholed
    private final Set<String> currentlyBlockedPkgs = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // State tracking
    private String currentMonitoredPkg = null;
    private volatile boolean isBallVisible = false;

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addAppMultiSelect("default_blocked_apps", "Default Blocked Apps (Start 🔴)",
                        "Apps that have NO internet by default. Floating red ball appears; tap to unblock. Stays blocked in background.")
                .addAppMultiSelect("default_allowed_apps", "Default Allowed Apps (Start 🟢)",
                        "Apps that have internet by default. Floating green ball appears; tap to cut off internet anytime.")
                .addBoolean("reset_on_exit", "Reset on App Exit",
                        "When leaving an app, automatically reset its state back to default (puts red apps back behind firewall)", true)
                .addBoolean("haptic_feedback", "Haptic Feedback",
                        "Vibrate phone when tapping the ball to toggle internet", true);
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        applyConfig(newArgs);
    }

    private void applyConfig(Bundle b) {
        if (b == null) return;

        ArrayList<String> blocked = b.getStringArrayList("default_blocked_apps");
        defaultBlockedApps.clear();
        if (blocked != null) {
            defaultBlockedApps.addAll(blocked);
        }

        ArrayList<String> allowed = b.getStringArrayList("default_allowed_apps");
        defaultAllowedApps.clear();
        if (allowed != null) {
            defaultAllowedApps.addAll(allowed);
        }

        resetOnExit = b.getBoolean("reset_on_exit", resetOnExit);
        hapticFeedback = b.getBoolean("haptic_feedback", hapticFeedback);

        // Update currentlyBlockedPkgs with defaultBlockedApps
        for (String p : defaultBlockedApps) {
            currentlyBlockedPkgs.add(p);
        }

        syncFirewall();

        if (api != null) {
            api.log("firewall_ball: config updated: blocked=" + defaultBlockedApps.size() + ", allowed=" + defaultAllowedApps.size());
        }
    }

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        this.api = api;
        api.log("firewall_ball: starting Floating Firewall Ball");

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("firewall_ball error: Context unavailable");
            return;
        }

        if (!api.isFirewallPrepared()) {
            api.toast("⚠️ FirewallBall requires VPN permission. Tap Enable in UQ Shell!");
            api.log("firewall_ball: VPN permission not yet granted.");
        }

        // Default initial lists if not yet set in args
        if (args != null) {
            applyConfig(args);
        } else {
            // Seed defaults for common distraction apps
            defaultBlockedApps.add("com.brave.browser");
            defaultBlockedApps.add("com.linkedin.android");
            defaultBlockedApps.add("org.lichess.mobileV2");
            currentlyBlockedPkgs.addAll(defaultBlockedApps);
            syncFirewall();
        }

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);

        // Build the floating overlay view
        initBallView(ctx);

        String lastKnownActivePkg = null;
        long lastTopOverlayTime = 0L;

        while (!api.stopRequested()) {
            try {
                Thread.sleep(400); // Check foreground app every 400ms
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;

            long now = System.currentTimeMillis();
            String fgPkg = getForegroundPackage(usm, now, lastKnownActivePkg);
            if (fgPkg != null) {
                lastKnownActivePkg = fgPkg;
            }

            boolean isMonitored = isMonitoredApp(fgPkg);

            if (!isMonitored) {
                // Outside monitored apps (Home, Settings, Termux, etc.)
                if (currentMonitoredPkg != null) {
                    // Just exited a monitored app
                    if (resetOnExit && defaultBlockedApps.contains(currentMonitoredPkg)) {
                        // Reset back to blocked so it stays behind firewall in background!
                        currentlyBlockedPkgs.add(currentMonitoredPkg);
                        syncFirewall();
                    }
                    currentMonitoredPkg = null;
                }
                updateBallVisibility(false, null, false);
            } else {
                // Inside a monitored app (e.g. Brave, LinkedIn, etc.)
                boolean appChanged = !fgPkg.equals(currentMonitoredPkg);
                if (appChanged) {
                    currentMonitoredPkg = fgPkg;
                    if (resetOnExit) {
                        if (defaultBlockedApps.contains(fgPkg)) {
                            currentlyBlockedPkgs.add(fgPkg);
                        } else if (defaultAllowedApps.contains(fgPkg)) {
                            currentlyBlockedPkgs.remove(fgPkg);
                        }
                        syncFirewall();
                    }
                }

                boolean isBlocked = currentlyBlockedPkgs.contains(fgPkg);
                updateBallVisibility(true, fgPkg, isBlocked);

                // Elevate ball to top whenever an overlay opens (e.g. blocker or intent session)
                Long topTime = (Long) ScriptApi.BUS.get("ui:top_overlay_time");
                if (topTime != null && topTime.longValue() > lastTopOverlayTime) {
                    lastTopOverlayTime = topTime.longValue();
                    bringBallToFront();
                } else if (appChanged) {
                    bringBallToFront();
                }
            }
        }

        removeBall();
        api.restoreInternet();
        api.log("firewall_ball: stopped");
    }

    private boolean isMonitoredApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (defaultBlockedApps.contains(pkg) || defaultAllowedApps.contains(pkg)) {
            return true;
        }
        // Also show ball if blocker or intent session is actively showing an overlay for this app
        if (Boolean.TRUE.equals(ScriptApi.BUS.get("intent:overlay_active:" + pkg)) ||
            Boolean.TRUE.equals(ScriptApi.BUS.get("blocker:overlay_active:" + pkg)) ||
            Boolean.TRUE.equals(ScriptApi.BUS.get("blocker:is_exceeded:" + pkg))) {
            return true;
        }
        return false;
    }

    private synchronized void syncFirewall() {
        if (api == null) return;
        try {
            if (currentlyBlockedPkgs.isEmpty()) {
                api.restoreInternet();
            } else {
                api.blockAppsInternet(new ArrayList<String>(currentlyBlockedPkgs), 0);
            }
        } catch (Throwable t) {
            api.log("syncFirewall error: " + t);
        }
    }

    private void initBallView(final Context ctx) {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    final int size = dp(44, ctx);

                    ballLp = new WindowManager.LayoutParams(
                            size, size,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                            PixelFormat.TRANSLUCENT
                    );
                    ballLp.gravity = Gravity.TOP | Gravity.START;
                    // Initial position: right edge, center height
                    ballLp.x = dm.widthPixels - size - dp(12, ctx);
                    ballLp.y = dm.heightPixels / 2 - size / 2;

                    // Ball container
                    ballView = new FrameLayout(ctx);
                    ballView.setVisibility(View.GONE);

                    ballBg = new GradientDrawable();
                    ballBg.setShape(GradientDrawable.OVAL);
                    ballBg.setColor(0xFFEF4444); // Red default
                    ballBg.setStroke(dp(2.5f, ctx), 0xFFB91C1C);
                    ballView.setBackground(ballBg);

                    // Inner Icon / Indicator
                    iconView = new TextView(ctx);
                    iconView.setText("🚫");
                    iconView.setTextSize(16);
                    iconView.setGravity(Gravity.CENTER);
                    FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    iLp.gravity = Gravity.CENTER;
                    ballView.addView(iconView, iLp);

                    // Touch listener: dragging + click detection
                    ballView.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX, initialY;
                        private float initialTouchX, initialTouchY;
                        private boolean isDragging;
                        private long touchDownTime;

                        public boolean onTouch(View v, MotionEvent event) {
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    initialX = ballLp.x;
                                    initialY = ballLp.y;
                                    initialTouchX = event.getRawX();
                                    initialTouchY = event.getRawY();
                                    isDragging = false;
                                    touchDownTime = System.currentTimeMillis();
                                    return true;

                                case MotionEvent.ACTION_MOVE:
                                    int dx = (int) (event.getRawX() - initialTouchX);
                                    int dy = (int) (event.getRawY() - initialTouchY);
                                    if (Math.abs(dx) > dp(6, ctx) || Math.abs(dy) > dp(6, ctx)) {
                                        isDragging = true;
                                    }
                                    if (isDragging) {
                                        ballLp.x = initialX + dx;
                                        ballLp.y = initialY + dy;
                                        try {
                                            wm.updateViewLayout(ballView, ballLp);
                                        } catch (Throwable ignored) {}
                                    }
                                    return true;

                                case MotionEvent.ACTION_UP:
                                    long clickDuration = System.currentTimeMillis() - touchDownTime;
                                    if (!isDragging && clickDuration < 400) {
                                        // Clicked! Toggle firewall state for current app
                                        onBallClicked(ctx);
                                    }
                                    return true;
                            }
                            return false;
                        }
                    });

                    wm.addView(ballView, ballLp);
                    api.log("firewall_ball: overlay initialized");
                } catch (Throwable t) {
                    api.log("initBallView failed: " + t);
                }
            }
        });
    }

    private void onBallClicked(Context ctx) {
        if (currentMonitoredPkg == null) return;
        final String pkg = currentMonitoredPkg;
        final String label = (api != null) ? api.appLabel(pkg) : pkg;

        boolean wasBlocked = currentlyBlockedPkgs.contains(pkg);

        if (wasBlocked) {
            // Unblock (turn green)
            currentlyBlockedPkgs.remove(pkg);
            defaultBlockedApps.remove(pkg);
            defaultAllowedApps.add(pkg);
            syncFirewall();
            updateBallVisuals(false);
            if (api != null) {
                if (hapticFeedback) api.vibrate(40);
                api.toast("🟢 " + label + ": Internet ALLOWED");
                api.log("firewall_ball: " + pkg + " unblocked by user tap (turned GREEN)");
            }
        } else {
            // Block (turn red)
            currentlyBlockedPkgs.add(pkg);
            defaultBlockedApps.add(pkg);
            defaultAllowedApps.remove(pkg);
            syncFirewall();
            updateBallVisuals(true);
            if (api != null) {
                if (hapticFeedback) api.vibrate(100);
                api.toast("🔴 " + label + ": Internet BLOCKED");
                api.log("firewall_ball: " + pkg + " blocked by user tap (turned RED)");
            }
        }
    }

    private void updateBallVisuals(final boolean isBlocked) {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (ballBg != null && iconView != null) {
                        if (isBlocked) {
                            // 🔴 RED: Blocked
                            ballBg.setColor(0xFFEF4444); // Vivid Red
                            ballBg.setStroke(dp(2.5f, iconView.getContext()), 0xFFB91C1C);
                            iconView.setText("🚫");
                        } else {
                            // 🟢 GREEN: Allowed
                            ballBg.setColor(0xFF10B981); // Emerald Green
                            ballBg.setStroke(dp(2.5f, iconView.getContext()), 0xFF047857);
                            iconView.setText("🌐");
                        }
                        if (ballView != null) ballView.invalidate();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void updateBallVisibility(final boolean visible, final String pkg, final boolean isBlocked) {
        if (isBallVisible == visible && !visible) return;
        final boolean justBecameVisible = (!isBallVisible && visible);
        isBallVisible = visible;

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (ballView != null) {
                        ballView.setVisibility(visible ? View.VISIBLE : View.GONE);
                        if (visible) {
                            updateBallVisuals(isBlocked);
                            if (justBecameVisible) {
                                bringBallToFrontInternal();
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void bringBallToFront() {
        mainHandler.post(new Runnable() {
            public void run() {
                bringBallToFrontInternal();
            }
        });
    }

    private void bringBallToFrontInternal() {
        try {
            if (wm == null || ballView == null || !isBallVisible) return;
            if (ballView.getWindowToken() != null) {
                try {
                    wm.removeViewImmediate(ballView);
                } catch (Throwable ignored) {}
            }
            try {
                wm.addView(ballView, ballLp);
            } catch (Throwable t) {
                try { wm.updateViewLayout(ballView, ballLp); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void removeBall() {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && ballView != null) {
                        wm.removeView(ballView);
                        ballView = null;
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
        removeBall();
        if (api != null) api.restoreInternet();
    }
}
