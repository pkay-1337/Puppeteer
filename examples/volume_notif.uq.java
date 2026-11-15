import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import uq.script.ScriptApi;
import uq.script.UqScript;

/**
 * volume_notif.uq.java — Notification Shade Volume Controller.
 * 
 * Replaces floating screen overlays with an ongoing notification in the
 * Android notification shade.
 * 
 * Features:
 *   - Tap notification: Smoothly collapses notification shade and triggers
 *     the native system volume slider with zero screen freeze / deadlock.
 *   - "🔊 Sliders" button: Smoothly opens system volume sliders.
 *   - "🔇 Mute" / "🔊 Unmute" button: Full-fidelity media & ringer muting
 *     with volume save & restore (0% <-> saved volume level).
 *   - "✕ Dismiss" button: Stops the script and removes the notification.
 *   - Auto-updates volume percentage when hardware volume keys are pressed.
 *   - Single notification only: cleans up stale test notifications on launch
 *     and cancel on stop.
 * 
 * Usage:
 *   usq buildpush examples/volume_notif.uq.java --resident --id volnotif --name "Volume Control"
 */
public class VolumeNotif extends UqScript {

    private static final String ACTION_SLIDERS = "com.usq.ACTION_VOL_SLIDERS";
    private static final String ACTION_MUTE = "com.usq.ACTION_VOL_MUTE";
    private static final String ACTION_DISMISS = "com.usq.ACTION_VOL_DISMISS";
    private static final int NOTIF_ID = 8801;
    private static final String CHANNEL_ID = "uq_volume_control";

    private BroadcastReceiver receiver;
    private NotificationManager nm;
    private Context savedCtx;
    private long lastSliderAt = 0;
    private int savedVolume = -1;

    @Override
    public void main(final ScriptApi api, Bundle args) throws Exception {
        api.log("volume_notif: starting notification volume control");

        final Context ctx = getContext(api);
        if (ctx == null) {
            api.log("volume_notif error: Context unavailable");
            return;
        }
        savedCtx = ctx;

        final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            api.log("volume_notif error: AudioManager unavailable");
            return;
        }

        int initialVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        savedVolume = (initialVol > 0) ? initialVol : (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 2 / 3);

        nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            // Cancel any leftover notifications from previous tests
            try {
                nm.cancel(NOTIF_ID);
                nm.cancel(9001);
                nm.cancel(9002);
            } catch (Throwable ignored) {}

            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "UQ Shell: Volume Control", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Persistent volume controller in notification shade");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                String act = intent.getAction();
                if (ACTION_SLIDERS.equals(act)) {
                    long now = System.currentTimeMillis();
                    if (now - lastSliderAt < 600) return;
                    lastSliderAt = now;

                    api.log("action: open volume sliders");
                    // 1. Close notification shade first
                    closeShade(c);

                    // 2. Delay adjustVolume by 260ms so MIUI shade collapse animation
                    // finishes completely. Calling FLAG_SHOW_UI concurrently with
                    // ACTION_CLOSE_SYSTEM_DIALOGS freezes MIUI's VolumeDialogImpl.
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
                                api.vibrate(30);
                            } catch (Throwable t) {
                                api.log("adjustVolume error: " + t);
                            }
                        }
                    }, 260);

                } else if (ACTION_MUTE.equals(act)) {
                    int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    boolean isMuted = (cur == 0) || am.isStreamMute(AudioManager.STREAM_MUSIC);

                    if (!isMuted) {
                        // MUTE: save current volume level and set stream to 0
                        if (cur > 0) savedVolume = cur;
                        api.log("action: mute (saving vol=" + savedVolume + ")");
                        try {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0);
                            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0);
                        } catch (Throwable t) {
                            api.log("mute error: " + t);
                        }
                        api.vibrate(40);
                        api.toast("🔇 Volume Muted");
                    } else {
                        // UNMUTE: restore saved volume level
                        int target = (savedVolume > 0) ? savedVolume : (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 2 / 3);
                        api.log("action: unmute (restoring vol=" + target + ")");
                        try {
                            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0);
                            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0);
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                        } catch (Throwable t) {
                            api.log("unmute error: " + t);
                        }
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int pct = (max > 0) ? (target * 100 / max) : 0;
                        api.vibrate(40);
                        api.toast("🔊 Volume Restored (" + pct + "%)");
                    }
                    postNotification(ctx, am);

                } else if (ACTION_DISMISS.equals(act)) {
                    api.log("action: dismiss");
                    cleanup(ctx);
                    api.stop();
                } else if ("android.media.VOLUME_CHANGED_ACTION".equals(act)
                        || "android.media.STREAM_MUTE_CHANGED_ACTION".equals(act)) {
                    postNotification(ctx, am);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SLIDERS);
        filter.addAction(ACTION_MUTE);
        filter.addAction(ACTION_DISMISS);
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
        filter.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        ctx.registerReceiver(receiver, filter);

        postNotification(ctx, am);
        api.toast("Volume control notification pinned");

        // Passive keep-alive: volume updates arrive via reactive broadcasts
        while (!api.stopRequested()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                break;
            }
        }

        cleanup(ctx);
        api.log("volume_notif: exited cleanly");
    }

    private void postNotification(Context ctx, AudioManager am) {
        if (nm == null) return;
        try {
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int pct = (max > 0) ? (current * 100 / max) : 0;
            boolean isMuted = (current == 0) || am.isStreamMute(AudioManager.STREAM_MUSIC);

            String title = isMuted ? "Volume Control · Muted (0%)" : ("Volume Control · Media " + pct + "%");
            String muteLabel = isMuted ? "🔊 Unmute" : "🔇 Mute";

            PendingIntent piSliders = PendingIntent.getBroadcast(ctx, 1,
                    new Intent(ACTION_SLIDERS), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            PendingIntent piMute = PendingIntent.getBroadcast(ctx, 2,
                    new Intent(ACTION_MUTE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            PendingIntent piDismiss = PendingIntent.getBroadcast(ctx, 3,
                    new Intent(ACTION_DISMISS), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Action actSliders = new Notification.Action.Builder(
                    android.R.drawable.ic_media_play, "🔊 Sliders", piSliders).build();

            Notification.Action actMute = new Notification.Action.Builder(
                    android.R.drawable.ic_lock_silent_mode, muteLabel, piMute).build();

            Notification.Action actDismiss = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "✕ Dismiss", piDismiss).build();

            Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                    .setContentTitle(title)
                    .setContentText("Tap to open system volume sliders")
                    .setContentIntent(piSliders)
                    .addAction(actSliders)
                    .addAction(actMute)
                    .addAction(actDismiss)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();

            nm.notify(NOTIF_ID, n);
        } catch (Throwable ignored) {}
    }

    private void closeShade(Context c) {
        try {
            c.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Throwable ignored) {}
    }

    private void cleanup(Context ctx) {
        if (nm != null) {
            try {
                nm.cancel(NOTIF_ID);
                nm.cancel(9001);
                nm.cancel(9002);
            } catch (Throwable ignored) {}
        }
        if (receiver != null && ctx != null) {
            try { ctx.unregisterReceiver(receiver); } catch (Throwable ignored) {}
            receiver = null;
        }
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
        if (nm != null) {
            try {
                nm.cancel(NOTIF_ID);
                nm.cancel(9001);
                nm.cancel(9002);
            } catch (Throwable ignored) {}
        }
        if (receiver != null && savedCtx != null) {
            try { savedCtx.unregisterReceiver(receiver); } catch (Throwable ignored) {}
            receiver = null;
        }
    }
}
