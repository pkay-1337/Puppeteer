package com.usq.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * The host: foreground service owning registry, runner, scheduler and HTTP control server.
 * Auto-starts scripts marked autoStart and arms persisted schedules on launch.
 */
public class ScriptService extends Service {

    private static Scheduler schedulerInstance;

    private Registry registry;
    private ScriptRunner runner;
    private HttpControl http;
    private Scheduler scheduler;

    public static Scheduler getScheduler() {
        return schedulerInstance;
    }

    @Override public void onCreate() {
        super.onCreate();
        File files = getFilesDir();
        File dexDir = new File(files, "dex");
        File scriptsDir = new File(files, "scripts");
        File logsDir = new File(files, "logs");
        dexDir.mkdirs();
        scriptsDir.mkdirs();
        logsDir.mkdirs();

        registry = new Registry(new File(files, "registry.json"));
        registry.load();
        runner = new ScriptRunner(this, registry, dexDir, scriptsDir, logsDir);

        startForeground(1001, buildNotif());

        // Initialize and start scheduler (FR-CRON)
        scheduler = new Scheduler(this, registry, runner);
        schedulerInstance = scheduler;
        scheduler.start();

        // Autostart scripts marked autoStart
        JSONArray arr = registry.list();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null || !e.optBoolean("autoStart", false)) continue;
            try {
                runner.start(e.optString("id"), null);
            } catch (Throwable t) {
                android.util.Log.w("uq", "autostart " + e.optString("id") + " failed: " + t);
            }
        }

        http = new HttpControl(this, registry, runner, dexDir, logsDir, scheduler);
        new Thread(http, "uq-http").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (http != null) http.stop();
        if (scheduler != null) scheduler.stop();
        schedulerInstance = null;
        super.onDestroy();
    }

    private Notification buildNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("uq", "UQ Shell",
                NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, "uq")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("UQ Shell")
                .setContentText("script host active · scheduler armed")
                .build();
    }
}
