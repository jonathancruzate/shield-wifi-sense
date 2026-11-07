package com.jonathancruzate.shieldwifisense;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** v1.1: start the sampling service at boot (Android 11 device:
 * startForegroundService from a BOOT_COMPLETED receiver works).
 * The host sampler's relaunch-if-dead remains as a backstop. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : "";
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            Intent svc = new Intent(context, SenseService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                Log.i("ShieldWifiSense", "SenseService started from boot receiver");
            } catch (Throwable t) {
                Log.e("ShieldWifiSense", "boot start failed", t);
            }
        }
    }
}
