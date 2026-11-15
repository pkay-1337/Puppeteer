import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * grayscale.uq.java — Dynamic App-Based & Bedtime Grayscale Daemon.
 * 
 * Features:
 *   - Automatically turns the display 100% Monochrome (Grayscale) whenever
 *     a monitored app is in the foreground.
 *   - Instantly restores vivid color when returning to Home or non-monitored apps.
 *   - Optional Bedtime Grayscale mode (e.g., automatically grayscale entire phone 10PM - 8AM).
 *   - Optional Whole-Device Grayscale mode for full dopamine detox.
 *   - Gentle haptic feedback on color transitions.
 *   - Safe cleanup: always restores normal color if script stops or crashes.
 */
public class Grayscale extends UqScript {

    private ScriptApi api;
    private final Set<String> monitoredApps = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile boolean grayscaleEntireDevice = false;
    private volatile boolean restoreOnExit = true;
    private volatile boolean hapticFeedback = true;
    private volatile boolean bedtimeGrayscale = false;
    private volatile int bedtimeHour = 22;
    private volatile int wakeHour = 8;

    private volatile boolean isCurrentlyGrayscale = false;
    private volatile boolean warnedMissingPermission = false;

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addAppMultiSelect("monitored_apps", "Grayscale Trigger Apps",
                        "Apps that automatically trigger Monochrome display when opened.")
                .addBoolean("grayscale_entire_device", "Always Grayscale (Whole Device)",
                        "Keep display permanently in Grayscale for full dopamine detox", false)
                .addBoolean("restore_on_exit", "Restore Color on Exit",
                        "Switch back to full color as soon as you exit the monitored app", true)
                .addBoolean("bedtime_grayscale", "Bedtime Grayscale (10 PM - 8 AM)",
                        "Automatically force Grayscale across the entire device at night", false)
                .addBoolean("haptic_feedback", "Haptic Pulse",
                        "Subtle vibration feedback when color mode transitions", true);
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
        grayscaleEntireDevice = b.getBoolean("grayscale_entire_device", grayscaleEntireDevice);
        restoreOnExit = b.getBoolean("restore_on_exit", restoreOnExit);
        hapticFeedback = b.getBoolean("haptic_feedback", hapticFeedback);
        bedtimeGrayscale = b.getBoolean("bedtime_grayscale", bedtimeGrayscale);
        bedtimeHour = b.getInt("bedtime_hour", bedtimeHour);
        wakeHour = b.getInt("wake_hour", wakeHour);
    }

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        this.api = api;
        api.log("grayscale: starting Dynamic Grayscale daemon");

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("grayscale error: Context unavailable");
            return;
        }

        // Initialize default monitored apps if empty
        if (monitoredApps.isEmpty()) {
            monitoredApps.add("com.linkedin.android");
            monitoredApps.add("com.brave.browser");
            monitoredApps.add("com.android.chrome");
            monitoredApps.add("org.lichess.mobileV2");
            monitoredApps.add("com.substack.app");
            monitoredApps.add("com.myntra.android");
            monitoredApps.add("com.zeptoconsumerapp");
        }

        if (args != null) {
            applyConfig(args);
        }
        api.log("grayscale: monitoring " + monitoredApps.size() + " apps: " + monitoredApps);

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        String lastKnownActivePkg = null;

        // Check initial display state
        isCurrentlyGrayscale = checkIsGrayscale(ctx);

        while (!api.stopRequested()) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;

            long now = System.currentTimeMillis();
            String fgPkg = getForegroundPackage(usm, now, lastKnownActivePkg);
            if (fgPkg != null) {
                lastKnownActivePkg = fgPkg;
            }

            boolean shouldBeGrayscale = false;

            if (grayscaleEntireDevice) {
                shouldBeGrayscale = true;
            } else if (bedtimeGrayscale && isBedtimeNow()) {
                shouldBeGrayscale = true;
            } else if (fgPkg != null && monitoredApps.contains(fgPkg)) {
                shouldBeGrayscale = true;
            }

            if (shouldBeGrayscale && !isCurrentlyGrayscale) {
                // Turn grayscale ON
                boolean success = setGrayscaleMode(ctx, true);
                if (success) {
                    isCurrentlyGrayscale = true;
                    String label = (fgPkg != null) ? api.appLabel(fgPkg) : "System";
                    api.log("grayscale: ON (monitored: " + label + ")");
                    if (hapticFeedback) api.vibrate(35);
                }
            } else if (!shouldBeGrayscale && isCurrentlyGrayscale && restoreOnExit) {
                // Turn grayscale OFF (restore color)
                boolean success = setGrayscaleMode(ctx, false);
                if (success) {
                    isCurrentlyGrayscale = false;
                    api.log("grayscale: OFF (color restored)");
                    if (hapticFeedback) api.vibrate(35);
                }
            }
        }

        // On clean exit, ensure normal color is restored
        setGrayscaleMode(ctx, false);
        isCurrentlyGrayscale = false;
        api.log("grayscale: daemon stopped, display color restored");
    }

    private boolean isBedtimeNow() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (bedtimeHour > wakeHour) {
            // e.g. 22:00 to 08:00
            return (hour >= bedtimeHour || hour < wakeHour);
        } else {
            return (hour >= bedtimeHour && hour < wakeHour);
        }
    }

    private boolean setGrayscaleMode(Context ctx, boolean enable) {
        // First try via ScriptApi helper if available
        try {
            Method m = api.getClass().getMethod("setGrayscale", boolean.class);
            Object res = m.invoke(api, enable);
            if (res instanceof Boolean) {
                return (Boolean) res;
            }
        } catch (Throwable ignored) {}

        // Fallback to direct Settings.Secure
        try {
            ContentResolver cr = ctx.getContentResolver();
            Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", enable ? 1 : 0);
            if (enable) {
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer", 0);
            }
            return true;
        } catch (SecurityException se) {
            if (!warnedMissingPermission) {
                warnedMissingPermission = true;
                if (api != null) {
                    api.log("grayscale error: WRITE_SECURE_SETTINGS not granted. Run: adb shell pm grant com.usq.shell android.permission.WRITE_SECURE_SETTINGS");
                    api.toast("⚠️ Grayscale requires WRITE_SECURE_SETTINGS via ADB");
                }
            }
            return false;
        } catch (Throwable t) {
            if (api != null) api.log("grayscale setGrayscaleMode error: " + t);
            return false;
        }
    }

    private boolean checkIsGrayscale(Context ctx) {
        // Try via ScriptApi helper
        try {
            Method m = api.getClass().getMethod("isGrayscale");
            Object res = m.invoke(api);
            if (res instanceof Boolean) {
                return (Boolean) res;
            }
        } catch (Throwable ignored) {}

        // Direct Settings.Secure fallback
        try {
            ContentResolver cr = ctx.getContentResolver();
            int enabled = Settings.Secure.getInt(cr, "accessibility_display_daltonizer_enabled", 0);
            int mode = Settings.Secure.getInt(cr, "accessibility_display_daltonizer", -1);
            return (enabled == 1 && mode == 0);
        } catch (Throwable t) {
            return false;
        }
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

    @Override
    public void onStop() {
        if (api != null) {
            Context ctx = getContext(api);
            if (ctx != null) {
                setGrayscaleMode(ctx, false);
            }
        }
    }
}
