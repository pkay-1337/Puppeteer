package com.usq.shell;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Central scheduler for UQ Shell (FR-CRON-1..8).
 * Supports cron expressions (5-field) and fixed intervals (everyMs).
 * Spawns one-shot runs or delivers onTick for resident scripts.
 * Manages exact AlarmManager wakeups and in-service tick loops.
 */
public class Scheduler implements Runnable {

    public static class ScheduleItem {
        public String scriptId;
        public String scheduleId;
        public String cronExpr;
        public long everyMs;
        public boolean persist;
        public JSONObject args;
        public long lastFiredAt;
        public long nextFireAt;
        public CronExpression cron;

        public ScheduleItem(String scriptId, String scheduleId, String cronExpr,
                            long everyMs, boolean persist, JSONObject args) {
            this.scriptId = scriptId;
            this.scheduleId = scheduleId;
            this.cronExpr = cronExpr;
            this.everyMs = everyMs;
            this.persist = persist;
            this.args = args;
            if (cronExpr != null && cronExpr.trim().length() > 0) {
                this.cron = new CronExpression(cronExpr);
            }
        }
    }

    private final Context ctx;
    private final Registry reg;
    private final ScriptRunner runner;
    private final List<ScheduleItem> items = new ArrayList<ScheduleItem>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private Thread tickerThread;

    public Scheduler(Context ctx, Registry reg, ScriptRunner runner) {
        this.ctx = ctx;
        this.reg = reg;
        this.runner = runner;
    }

    public synchronized void start() {
        running = true;
        loadFromRegistry();
        rescheduleAlarm();

        tickerThread = new Thread(this, "uq-scheduler");
        tickerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (tickerThread != null) {
            tickerThread.interrupt();
        }
        cancelAlarm();
    }

    @Override
    public void run() {
        while (running) {
            try {
                // Sleep for 10 seconds between active awake checks
                Thread.sleep(10000);
            } catch (InterruptedException ie) {
                break;
            }
            if (!running) break;
            try {
                checkAndFire();
            } catch (Throwable t) {
                android.util.Log.w("uq-scheduler", "ticker error: " + t);
            }
        }
    }

    public synchronized void schedule(String scriptId, String scheduleId, String cronExpr,
                                     long everyMs, boolean persist, JSONObject args) {
        // Remove existing if matching scriptId & scheduleId
        unschedule(scriptId, scheduleId);

        ScheduleItem item = new ScheduleItem(scriptId, scheduleId, cronExpr, everyMs, persist, args);
        long now = System.currentTimeMillis();
        item.nextFireAt = calculateNextFire(item, now);
        items.add(item);

        if (persist) {
            saveToRegistry(scriptId);
        }
        rescheduleAlarm();
    }

    public synchronized void unschedule(String scriptId, String scheduleId) {
        Iterator<ScheduleItem> it = items.iterator();
        boolean removed = false;
        boolean wasPersist = false;
        while (it.hasNext()) {
            ScheduleItem item = it.next();
            if (item.scriptId.equals(scriptId) && item.scheduleId.equals(scheduleId)) {
                if (item.persist) wasPersist = true;
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            if (wasPersist) saveToRegistry(scriptId);
            rescheduleAlarm();
        }
    }

    public synchronized void unscheduleAllForScript(String scriptId, boolean onlyNonPersisted) {
        Iterator<ScheduleItem> it = items.iterator();
        boolean removed = false;
        while (it.hasNext()) {
            ScheduleItem item = it.next();
            if (item.scriptId.equals(scriptId)) {
                if (!onlyNonPersisted || !item.persist) {
                    it.remove();
                    removed = true;
                }
            }
        }
        if (removed) {
            rescheduleAlarm();
        }
    }

    /**
     * Checks all schedules and fires those due.
     * Called both from ticker loop and AlarmReceiver.
     */
    public synchronized void checkAndFire() {
        long now = System.currentTimeMillis();
        boolean anyFired = false;

        for (ScheduleItem item : items) {
            if (item.nextFireAt > 0 && now >= item.nextFireAt) {
                item.lastFiredAt = now;
                item.nextFireAt = calculateNextFire(item, now);
                fireSchedule(item, now);
                anyFired = true;
            }
        }

        if (anyFired) {
            rescheduleAlarm();
        }
    }

    private void fireSchedule(final ScheduleItem item, final long firedAt) {
        JSONObject scriptEntry = reg.get(item.scriptId);
        if (scriptEntry == null) return;

        boolean resident = scriptEntry.optBoolean("resident", false);
        boolean isRunning = runner.isRunning(item.scriptId);

        if (resident && isRunning) {
            // Deliver onTick to resident script (FR-CRON-5)
            runner.deliverTick(item.scriptId, item.scheduleId);
        } else if (isRunning) {
            // Non-resident script still running from previous invocation -> skip to avoid pileup
            android.util.Log.i("uq-cron", "script " + item.scriptId + " already running; skipping fire");
        } else {
            // Spawn fresh one-shot run (FR-CRON-4)
            final JSONObject extraArgs = new JSONObject();
            try {
                if (item.args != null) {
                    Iterator<String> it = item.args.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        extraArgs.put(k, item.args.opt(k));
                    }
                }
                extraArgs.put("trigger", "cron");
                extraArgs.put("scheduleId", item.scheduleId);
                extraArgs.put("firedAt", firedAt);
            } catch (Throwable ignored) { }

            mainHandler.post(new Runnable() {
                public void run() {
                    try {
                        runner.start(item.scriptId, extraArgs);
                    } catch (Throwable t) {
                        android.util.Log.w("uq-cron", "failed to start scheduled " + item.scriptId + ": " + t);
                    }
                }
            });
        }
    }

    private long calculateNextFire(ScheduleItem item, long afterMs) {
        if (item.cron != null) {
            return item.cron.nextFire(afterMs);
        } else if (item.everyMs > 0) {
            return afterMs + item.everyMs;
        }
        return -1;
    }

    public synchronized void rescheduleAlarm() {
        long earliest = -1;
        for (ScheduleItem item : items) {
            if (item.nextFireAt > 0) {
                if (earliest < 0 || item.nextFireAt < earliest) {
                    earliest = item.nextFireAt;
                }
            }
        }

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(ctx, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 4201, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (earliest > 0) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pi);
            } catch (Throwable t) {
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pi);
                } catch (Throwable ignored) { }
            }
        } else {
            am.cancel(pi);
        }
    }

    private void cancelAlarm() {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 4201, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    public synchronized void loadFromRegistry() {
        items.clear();
        JSONArray scripts = reg.list();
        long now = System.currentTimeMillis();

        for (int i = 0; i < scripts.length(); i++) {
            JSONObject s = scripts.optJSONObject(i);
            if (s == null) continue;
            String scriptId = s.optString("id");
            JSONArray scheds = s.optJSONArray("schedules");
            if (scheds != null) {
                for (int j = 0; j < scheds.length(); j++) {
                    JSONObject sc = scheds.optJSONObject(j);
                    if (sc == null) continue;
                    String schedId = sc.optString("id", "s" + j);
                    String cron = sc.optString("cron", null);
                    long everyMs = sc.optLong("everyMs", 0);
                    JSONObject args = sc.optJSONObject("args");
                    ScheduleItem item = new ScheduleItem(scriptId, schedId, cron, everyMs, true, args);
                    item.nextFireAt = calculateNextFire(item, now);
                    items.add(item);
                }
            }
        }
    }

    private synchronized void saveToRegistry(String scriptId) {
        JSONObject s = reg.get(scriptId);
        if (s == null) return;

        JSONArray arr = new JSONArray();
        for (ScheduleItem item : items) {
            if (item.scriptId.equals(scriptId) && item.persist) {
                JSONObject sc = new JSONObject();
                try {
                    sc.put("id", item.scheduleId);
                    if (item.cronExpr != null) sc.put("cron", item.cronExpr);
                    if (item.everyMs > 0) sc.put("everyMs", item.everyMs);
                    if (item.args != null) sc.put("args", item.args);
                    arr.put(sc);
                } catch (Throwable ignored) { }
            }
        }
        try {
            s.put("schedules", arr);
            reg.put(scriptId, s);
        } catch (Throwable ignored) { }
    }

    public synchronized JSONArray listStatus() {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        for (ScheduleItem item : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("scriptId", item.scriptId);
                o.put("scheduleId", item.scheduleId);
                if (item.cronExpr != null) o.put("cron", item.cronExpr);
                if (item.everyMs > 0) o.put("everyMs", item.everyMs);
                o.put("persist", item.persist);
                o.put("nextFireAt", item.nextFireAt);
                long inSec = (item.nextFireAt - now) / 1000L;
                o.put("nextFireInSec", Math.max(0, inSec));
                arr.put(o);
            } catch (Throwable ignored) { }
        }
        return arr;
    }
}
