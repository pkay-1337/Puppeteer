import android.os.Bundle;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import uq.script.ScriptApi;
import uq.script.UqScript;

/**
 * cron_logger.uq.java — Scheduled periodic logger (SRS M3, acceptance A6).
 * Spawns on cron/interval trigger, vibrates phone, shows toast, and logs event.
 * Usage: usq buildpush examples/cron_logger.uq.java --name "Cron Logger" --cron tick:"* /1 * * * *"
 */
public class CronLogger extends UqScript {

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        String trigger = args != null ? args.getString("trigger", "manual") : "manual";
        String schedId = args != null ? args.getString("scheduleId", "-") : "-";
        long firedAt = args != null ? args.getLong("firedAt", System.currentTimeMillis()) : System.currentTimeMillis();
        String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(firedAt));

        String line = String.format("[%s] fire: sched=%s at=%s", trigger, schedId, timeStr);
        api.log(line);

        // Vibrate phone so user physically feels the cron trigger
        api.vibrate(400);

        // Toast on screen so user visibly sees the cron trigger
        api.toast("⏰ [Cron] " + schedId + " fired at " + timeStr);

        // Post notification
        api.notify(1042, "UQ Cron Triggered", line);

        File out = new File(api.scriptDir(), "cron_runs.txt");
        api.append(out.getAbsolutePath(), line + "\n");
    }
}
