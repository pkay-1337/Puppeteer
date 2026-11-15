package com.usq.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * Wakes the host up from Doze/idle to execute scheduled cron tasks (FR-CRON-6).
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = null;
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uq:scheduler_alarm");
            wl.acquire(5000);
        }
        try {
            Scheduler s = ScriptService.getScheduler();
            if (s != null) {
                s.checkAndFire();
            }
        } finally {
            if (wl != null && wl.isHeld()) {
                try { wl.release(); } catch (Throwable ignored) { }
            }
        }
    }
}
