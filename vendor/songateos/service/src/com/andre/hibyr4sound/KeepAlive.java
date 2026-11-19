package com.andre.hibyr4sound;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the HiBy R4 service's process running while it is not "persistent" (user decision 2026-11-18: dropped for
 * the betas so the service can be updated with adb install while the gestures, switcher and AirPods are tuned; it
 * goes back to persistent for the official release). A foreground service of type systemExempted (for system apps)
 * with a silent, minimised notification: only in the notification shade, never in the status bar, no sound.
 * Started by App at every process start (boot included).
 */
public class KeepAlive extends Service {
    static final String TAG = "SongateKeepAlive";
    private static final String CHANNEL = "songateos_system";
    private static final int ID = 0x5047;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, KeepAlive.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "start", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "SongateOS system", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Keeps SongateOS's gestures, hold switch and lights running");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.andre.hibyr4.service.R.drawable.ic_hiby_audio)
                .setContentTitle("SongateOS is running")
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
