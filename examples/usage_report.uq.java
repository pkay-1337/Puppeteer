import android.os.Bundle;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import uq.script.ScriptApi;
import uq.script.UqScript;

/**
 * usage_report.uq.java — 7-Day Usage Analytics Report (SRS M4, FR-DOC-2).
 * Aggregates device screen time across the past week, calculates application
 * breakdown percentages, and writes a formatted report to the script directory.
 * Usage: usq buildpush examples/usage_report.uq.java --name "Usage Report"
 */
public class UsageReport extends UqScript {

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        int days = 7;
        if (args != null && args.containsKey("days")) {
            days = args.getInt("days", 7);
        }

        api.log("Generating " + days + "-day usage report...");

        List<ScriptApi.Usage> stats = api.usageDays(days);
        if (stats == null || stats.isEmpty()) {
            api.toast("No usage stats recorded for past " + days + " days");
            return;
        }

        long totalTimeMs = 0;
        for (ScriptApi.Usage u : stats) {
            totalTimeMs += u.totalMs;
        }

        long totalMinutes = totalTimeMs / 60000L;
        long totalHours = totalMinutes / 60L;
        long remMinutes = totalMinutes % 60L;

        StringBuilder sb = new StringBuilder();
        sb.append("=========================================\n");
        sb.append("       UQ SHELL USAGE REPORT (").append(days).append(" DAYS)\n");
        sb.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append("\n");
        sb.append(String.format(Locale.US, "Total Screen Time: %dh %02dm\n", totalHours, remMinutes));
        sb.append(String.format(Locale.US, "Daily Average:     %.1fh/day\n", (double) totalHours / (double) days));
        sb.append("=========================================\n");
        sb.append(String.format(Locale.US, "%-4s %-20s %-10s %s\n", "Rank", "Application", "Time", "Share"));
        sb.append("-----------------------------------------\n");

        int topCount = Math.min(10, stats.size());
        for (int i = 0; i < topCount; i++) {
            ScriptApi.Usage u = stats.get(i);
            String label = api.appLabel(u.pkg);
            double pct = totalTimeMs > 0 ? ((double) u.totalMs * 100.0 / totalTimeMs) : 0.0;
            long mins = u.totalMs / 60000L;
            long hrs = mins / 60L;
            mins = mins % 60L;
            String timeStr = hrs > 0 ? (hrs + "h " + mins + "m") : (mins + "m");

            sb.append(String.format(Locale.US, "#%-3d %-20s %-10s %5.1f%%\n",
                    (i + 1), truncate(label, 20), timeStr, pct));
        }
        sb.append("=========================================\n");

        String report = sb.toString();
        api.log(report);

        // Save report to private directory
        File out = new File(api.scriptDir(), "weekly_report.txt");
        api.write(out.getAbsolutePath(), report);

        api.toast(String.format(Locale.US, "Report complete: %dh %02dm total screen time", totalHours, remMinutes));
        api.notify(1043, "Weekly Usage Report", String.format(Locale.US, "%dh %dm total over %d days", totalHours, remMinutes, days));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "?";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
