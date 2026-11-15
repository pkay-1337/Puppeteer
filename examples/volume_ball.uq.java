import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * volume_ball.uq.java — Floating Volume Ball Overlay (with Declarative Parameters).
 * 
 * A draggable circular assistive ball that stays on top of apps.
 * Clicking it instantly triggers the Android system volume sliders.
 * 
 * Features:
 *   - Declarative parameter schema: hidelist (apps) & hideLandscape (boolean).
 *   - Automatically hides when in landscape mode (configurable via GUI).
 *   - Automatically hides in selected launchable apps (configurable via GUI).
 *   - Real-time live configuration updates via onConfigChanged().
 * 
 * Usage:
 *   usq buildpush examples/volume_ball.uq.java --resident --id volball --name "Volume Ball"
 */
public class VolumeBall extends UqScript {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View ballView;
    private ScriptApi api;

    private volatile boolean hideLandscape = true;
    private volatile List<String> hideList = new ArrayList<String>();

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
            .addAppMultiSelect("hidelist", "Hide in Applications",
                "Automatically hide the volume ball when these applications are active")
            .addBoolean("hideLandscape", "Hide in Landscape Mode",
                "Automatically hide the volume ball when the phone is held horizontally", true);
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        if (newArgs != null) {
            hideLandscape = newArgs.getBoolean("hideLandscape", true);
            ArrayList<String> list = newArgs.getStringArrayList("hidelist");
            hideList = (list != null) ? list : new ArrayList<String>();
            if (api != null) {
                api.log("volume_ball config updated: hideLandscape=" + hideLandscape + ", hidelist=" + hideList.size() + " apps");
            }
        }
    }

    @Override
    public void main(final ScriptApi api, Bundle args) throws Exception {
        this.api = api;
        api.log("volume_ball: starting floating volume ball");

        final Context ctx = getContext(api);
        if (ctx == null) {
            api.log("volume_ball error: Context unavailable");
            return;
        }

        final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            api.log("volume_ball error: AudioManager unavailable");
            return;
        }

        final UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);

        // Load initial parameters from args
        if (args != null) {
            hideLandscape = args.getBoolean("hideLandscape", true);
            ArrayList<String> list = args.getStringArrayList("hidelist");
            hideList = (list != null) ? list : new ArrayList<String>();
        }
        api.log("volume_ball loaded with hideLandscape=" + hideLandscape + ", hidelist=" + hideList.size() + " apps");

        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    int size = dp(48, ctx);

                    final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            size, size,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                            PixelFormat.TRANSLUCENT
                    );
                    lp.gravity = Gravity.TOP | Gravity.START;
                    // Initial position: right edge, centered vertically
                    lp.x = dm.widthPixels - size - dp(12, ctx);
                    lp.y = dm.heightPixels / 2 - size / 2;

                    final FrameLayout ball = new FrameLayout(ctx);

                    final GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.OVAL);
                    bg.setColor(0xE61A1D26); // 90% opaque dark slate
                    bg.setStroke(dp(1.5f, ctx), 0xFF38BDF8); // Cyan border
                    ball.setBackground(bg);

                    TextView icon = new TextView(ctx);
                    icon.setText("🔊");
                    icon.setTextSize(18);
                    icon.setGravity(Gravity.CENTER);
                    FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
                    iconLp.gravity = Gravity.CENTER;
                    ball.addView(icon, iconLp);

                    // Touch Listener: Drag & Tap
                    ball.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX, initialY;
                        private float initialTouchX, initialTouchY;
                        private boolean isDragging;
                        private long touchDownTime;

                        public boolean onTouch(View v, MotionEvent event) {
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    initialX = lp.x;
                                    initialY = lp.y;
                                    initialTouchX = event.getRawX();
                                    initialTouchY = event.getRawY();
                                    isDragging = false;
                                    touchDownTime = System.currentTimeMillis();

                                    bg.setColor(0xFF2E3446);
                                    ball.invalidate();
                                    return true;

                                case MotionEvent.ACTION_MOVE:
                                    float dx = event.getRawX() - initialTouchX;
                                    float dy = event.getRawY() - initialTouchY;
                                    if (Math.hypot(dx, dy) > dp(6, ctx)) {
                                        isDragging = true;
                                    }
                                    if (isDragging) {
                                        lp.x = initialX + (int) dx;
                                        lp.y = initialY + (int) dy;
                                        try { wm.updateViewLayout(ball, lp); } catch (Throwable ignored) {}
                                    }
                                    return true;

                                case MotionEvent.ACTION_UP:
                                    bg.setColor(0xE61A1D26);
                                    ball.invalidate();

                                    long duration = System.currentTimeMillis() - touchDownTime;
                                    if (!isDragging && duration < 600) {
                                        try {
                                            api.vibrate(35);
                                            am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
                                            api.log("volume ball tapped -> system volume sliders opened");
                                        } catch (Throwable t) {
                                            api.log("adjustVolume error: " + t);
                                        }
                                    }
                                    return true;

                                case MotionEvent.ACTION_CANCEL:
                                    bg.setColor(0xE61A1D26);
                                    ball.invalidate();
                                    return true;
                            }
                            return false;
                        }
                    });

                    wm.addView(ball, lp);
                    ballView = ball;
                    api.log("volume ball overlay added to window");
                    api.toast("Floating Volume Ball active");
                } catch (Throwable t) {
                    api.log("failed to create volume ball: " + t);
                }
            }
        });

        // Resident monitoring loop for Landscape and Hidelist auto-hide
        while (!api.stopRequested()) {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;

            long now = System.currentTimeMillis();
            String currentFg = (usm != null) ? getForegroundPackage(usm, now) : null;

            int orientation = ctx.getResources().getConfiguration().orientation;
            boolean isLandscape = (orientation == Configuration.ORIENTATION_LANDSCAPE);

            boolean shouldHide = (hideLandscape && isLandscape) ||
                                 (currentFg != null && hideList.contains(currentFg));

            updateBallVisibility(!shouldHide);
        }

        removeBall();
        api.log("volume_ball: main loop exited");
    }

    private void updateBallVisibility(final boolean visible) {
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    if (ballView != null) {
                        int target = visible ? View.VISIBLE : View.GONE;
                        if (ballView.getVisibility() != target) {
                            ballView.setVisibility(target);
                            if (api != null) {
                                api.log("volume ball visibility changed to: " + (visible ? "VISIBLE" : "HIDDEN"));
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
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

    private static String getForegroundPackage(UsageStatsManager usm, long now) {
        try {
            UsageEvents events = usm.queryEvents(now - 20000, now);
            UsageEvents.Event ev = new UsageEvents.Event();
            String lastPkg = null;
            long lastTime = 0;
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == 1 /* ACTIVITY_RESUMED */) {
                    if (ev.getTimeStamp() > lastTime) {
                        lastTime = ev.getTimeStamp();
                        lastPkg = ev.getPackageName();
                    }
                }
            }
            if (lastPkg != null) return lastPkg;
        } catch (Throwable ignored) {}

        try {
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 20000, now);
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
    }
}
