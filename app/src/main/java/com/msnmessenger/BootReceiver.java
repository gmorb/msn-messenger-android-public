package com.msnmessenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Restarts notifications after the phone reboots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences p = c.getSharedPreferences("msn", Context.MODE_PRIVATE);
        if (p.getString("token", null) != null && !p.getBoolean("isDemo", false)) NotifyService.start(c);
    }
}
