import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import uq.script.ScriptApi;
import uq.script.UqScript;

/**
 * card.uq.java — Today's Usage Card (SRS M2, acceptance A3/A5).
 * Floating draggable overlay showing top foreground app usage for the current day.
 * Usage: usq buildpush examples/card.uq.java --resident --name "Usage Card"
 */
public class Card extends UqScript {

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        api.log("card: starting today's usage overlay");

        List<String> lines = buildLines(api);
        for (String l : lines) api.log("card: " + l);
        int handle = api.overlayCard(lines);
        api.toast("Floating usage card (Today) active");

        while (!api.stopRequested()) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                break;
            }
            if (api.stopRequested()) break;
            lines = buildLines(api);
            api.updateOverlayCard(handle, lines);
        }
        api.log("card: main loop exited");
    }

    private List<String> buildLines(ScriptApi api) {
        List<String> lines = new ArrayList<String>();
        try {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long startToday = cal.getTimeInMillis();
            long now = System.currentTimeMillis();

            Context ctx = getContext(api);
            List<ScriptApi.Usage> list = null;
            long totalTodayMs = 0;

            if (ctx != null) {
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    Map<String, UsageStats> agg = usm.queryAndAggregateUsageStats(startToday, now);
                    if (agg != null) {
                        list = new ArrayList<ScriptApi.Usage>();
                        for (UsageStats us : agg.values()) {
                            long total = us.getTotalTimeInForeground();
                            if (total > 0) {
                                totalTodayMs += total;
                                list.add(new ScriptApi.Usage(us.getPackageName(), total, us.getLastTimeUsed()));
                            }
                        }
                        Collections.sort(list, new Comparator<ScriptApi.Usage>() {
                            public int compare(ScriptApi.Usage a, ScriptApi.Usage b) {
                                return (b.totalMs < a.totalMs) ? -1 : ((b.totalMs == a.totalMs) ? 0 : 1);
                            }
                        });
                    }
                }
            }

            // Fallback to topPackagesToday via reflection / direct if available
            if (list == null) {
                try {
                    Method m = api.getClass().getMethod("topPackagesToday", int.class);
                    list = (List<ScriptApi.Usage>) m.invoke(api, 5);
                } catch (Throwable ignored) {
                    list = api.topPackages(5, 1);
                }
            }

            long totalSec = totalTodayMs / 1000L;
            long hours = totalSec / 3600L;
            long mins = (totalSec % 3600L) / 60L;
            String totalStr;
            if (hours > 0) {
                totalStr = hours + "h " + mins + "m";
            } else if (mins > 0) {
                totalStr = mins + "m";
            } else {
                totalStr = totalSec + "s";
            }

            lines.add("Top Apps Today (" + totalStr + "):");
            if (list == null || list.isEmpty()) {
                lines.add("  (no usage recorded today)");
            } else {
                int limit = Math.min(5, list.size());
                for (int i = 0; i < limit; i++) {
                    ScriptApi.Usage u = list.get(i);
                    String label = api.appLabel(u.pkg);
                    long uSec = u.totalMs / 1000L;
                    long uHours = uSec / 3600L;
                    long uMins = (uSec % 3600L) / 60L;
                    String timeStr;
                    if (uHours > 0) {
                        timeStr = uHours + "h " + uMins + "m";
                    } else if (uMins > 0) {
                        timeStr = uMins + "m";
                    } else {
                        timeStr = uSec + "s";
                    }
                    lines.add(String.format(" %d. %-14s %s", (i + 1), truncate(label, 14), timeStr));
                }
            }
        } catch (Throwable t) {
            lines.add("Error: " + t.getMessage());
        }
        return lines;
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

    private static String truncate(String s, int max) {
        if (s == null) return "?";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public void onStop() {
        // Overlay cleanup is handled automatically by ScriptApi.cleanup()
    }
}
