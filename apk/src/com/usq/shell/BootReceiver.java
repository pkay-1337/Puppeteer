package com.usq.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot persistence: bring the host back after reboot. */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context, ScriptService.class));
        }
    }
}
