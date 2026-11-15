package com.usq.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.util.ArrayList;
import java.util.List;

/**
 * UqVpnService — On-device local firewall & blackhole tunnel.
 * Routes non-exempt or target app traffic into an unforwarded TUN interface.
 * Excludes Termux (SSH) and UQ Shell so device management is never cut off.
 */
public class UqVpnService extends VpnService {

    public static final String ACTION_BLOCK = "com.usq.shell.vpn.BLOCK";
    public static final String ACTION_RESTORE = "com.usq.shell.vpn.RESTORE";
    public static final String EXTRA_TARGET_PKGS = "target_pkgs";
    public static final String EXTRA_DURATION_SEC = "duration_sec";

    private static volatile boolean isBlockingActive = false;
    private ParcelFileDescriptor vpnInterface;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoRestoreRunnable = new Runnable() {
        @Override
        public void run() {
            stopVpn();
            stopSelf();
        }
    };

    public static boolean isPrepared(Context ctx) {
        try {
            return VpnService.prepare(ctx) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isBlocking() {
        return isBlockingActive;
    }

    public static void startBlock(Context ctx, ArrayList<String> targetPkgs, int durationSec) {
        try {
            Intent intent = new Intent(ctx, UqVpnService.class);
            intent.setAction(ACTION_BLOCK);
            if (targetPkgs != null) {
                intent.putStringArrayListExtra(EXTRA_TARGET_PKGS, targetPkgs);
            }
            intent.putExtra(EXTRA_DURATION_SEC, durationSec);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable ignored) {}
    }

    public static void stopBlock(Context ctx) {
        try {
            Intent intent = new Intent(ctx, UqVpnService.class);
            intent.setAction(ACTION_RESTORE);
            ctx.startService(intent);
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_RESTORE.equals(action)) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_BLOCK.equals(action)) {
            ArrayList<String> targetPkgs = intent.getStringArrayListExtra(EXTRA_TARGET_PKGS);
            int durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 60);
            startVpn(targetPkgs, durationSec);
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void startVpn(List<String> targetPkgs, int durationSec) {
        stopVpn();
        if (targetPkgs != null && targetPkgs.isEmpty()) {
            // Empty list passed -> no apps to block, restore all traffic
            return;
        }
        try {
            ensureNotification();

            Builder builder = new Builder();
            builder.setSession("UQ Firewall");

            // Local dummy IP address
            builder.addAddress("10.222.0.1", 32);

            // Blackhole route: 0.0.0.0/0 for IPv4
            builder.addRoute("0.0.0.0", 0);
            try {
                builder.addAddress("fd00::1", 128);
                builder.addRoute("::", 0);
            } catch (Throwable ignored) {}

            // Set blackhole DNS only when blocking the ENTIRE device (not in per-app mode)
            if (targetPkgs == null) {
                try {
                    builder.addDnsServer("10.222.0.1");
                } catch (Throwable ignored) {}
            }

            // Note: addAllowedApplication and addDisallowedApplication are MUTUALLY EXCLUSIVE in Android VpnService.Builder!
            if (targetPkgs != null && !targetPkgs.isEmpty()) {
                // Per-app mode: route ONLY targetPkgs into the blackhole.
                // All other apps (including Termux, LinkedIn, Chrome, etc.) automatically bypass the VPN!
                for (String pkg : targetPkgs) {
                    try {
                        builder.addAllowedApplication(pkg);
                    } catch (Throwable ignored) {}
                }
            } else {
                // Whole-device mode: exclude Termux & UQ Shell so SSH and control are never cut off!
                try {
                    builder.addDisallowedApplication("com.termux");
                    builder.addDisallowedApplication("com.usq.shell");
                } catch (Throwable ignored) {}
            }

            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                isBlockingActive = true;

                // Auto-restore after durationSec if > 0
                handler.removeCallbacks(autoRestoreRunnable);
                if (durationSec > 0) {
                    handler.postDelayed(autoRestoreRunnable, durationSec * 1000L);
                }
            }
        } catch (Throwable t) {
            isBlockingActive = false;
        }
    }

    private void stopVpn() {
        handler.removeCallbacks(autoRestoreRunnable);
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Throwable ignored) {}
            vpnInterface = null;
        }
        isBlockingActive = false;
        stopForeground(true);
    }

    private void ensureNotification() {
        String channelId = "uq_vpn_channel";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(channelId, "UQ Firewall", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active when UQ Shell firewall is blocking internet traffic");
            nm.createNotificationChannel(ch);
        }
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, channelId);
        } else {
            nb = new Notification.Builder(this);
        }
        nb.setContentTitle("UQ Firewall Active")
          .setContentText("Internet blocking active")
          .setSmallIcon(android.R.drawable.ic_lock_lock);
        startForeground(7824, nb.build());
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
