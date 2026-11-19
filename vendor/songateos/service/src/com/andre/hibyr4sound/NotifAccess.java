package com.andre.hibyr4sound;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.List;

/**
 * Notification access for Songate (user decision 2026-11-18: allowed automatically). Songate shows other apps' music
 * through Android's media sessions, which needs its notification listener allowed. At every start of this service
 * (and when packages change, see App), every notification listener that Songate declares is allowed ONCE: the
 * component is remembered, so if the user later switches it off in Settings it stays off.
 */
final class NotifAccess {
    static final String TAG = "SongateNotifAccess";
    private static final String LISTENER = "android.service.notification.NotificationListenerService";

    private NotifAccess() { }

    static void grantOnce(Context c) {
        try {
            SharedPreferences p = c.createDeviceProtectedStorageContext().getSharedPreferences("notif_access", Context.MODE_PRIVATE);
            List<ResolveInfo> l = c.getPackageManager().queryIntentServices(
                    new Intent(LISTENER).setPackage(Setup.SONGATE), PackageManager.GET_META_DATA);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            for (ResolveInfo ri : l) {
                if (ri.serviceInfo == null) continue;
                ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                String key = cn.flattenToString();
                if (p.getBoolean(key, false)) continue;               // allowed before: the user's choice stands
                // system API (hidden in the public SDK): NotificationManager.setNotificationListenerAccessGranted
                try {
                    NotificationManager.class.getMethod("setNotificationListenerAccessGranted", ComponentName.class, boolean.class)
                            .invoke(nm, cn, true);
                } catch (ReflectiveOperationException e) {
                    Log.w(TAG, "allow " + key, e);
                    continue;
                }
                p.edit().putBoolean(key, true).apply();
                Log.i(TAG, "notification access allowed for " + key);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "grant", e);
        }
    }
}
