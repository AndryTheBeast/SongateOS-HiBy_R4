package com.andre.hibyr4sound;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Follows the wired output service.sh has chosen (sys.hibyr4.st_output: Headphone | Lineout | Balance | BalanceLo |
 * none; set when a jack changes). Android only knows the 3.5 mm jack (see DacVolume), so for the 4.4 mm:
 *  - pulling the 4.4 mm plug: Android sent no "audio becoming noisy", so players kept playing (user, 2026-11-19);
 *    it is sent here, as Android does for the 3.5 mm;
 *  - any change: the DAC volume takes the new output's own saved step.
 * The service can't read the jack switches itself (SELinux), but it can read this property.
 */
final class OutputWatch {
    static final String TAG = "SongateOutput";
    private static volatile boolean sStarted;

    private OutputWatch() { }

    private static String prop(String k) {
        try {
            Method g = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            return (String) g.invoke(null, k, "");
        } catch (Throwable t) { return ""; }
    }

    static void init(Context c) {
        if (sStarted) return;
        sStarted = true;
        final Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            String last = prop("sys.hibyr4.st_output");
            while (true) {
                SystemClock.sleep(400);
                String now = prop("sys.hibyr4.st_output");
                if (now.equals(last)) continue;
                Log.i(TAG, "output " + last + " -> " + now);
                boolean wasBal = last.startsWith("Balance");
                if (wasBal && ("none".equals(now) || now.isEmpty())) {
                    try {
                        Intent i = new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                        Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class)
                                .invoke(app, i, UserHandle.class.getField("ALL").get(null));
                        Log.i(TAG, "4.4 mm pulled: audio becoming noisy sent");
                    } catch (Throwable x) { Log.w(TAG, "noisy: " + x); }
                }
                app.sendBroadcast(new Intent().setClassName(app, DacVolume.class.getName()));
                last = now;
            }
        }, "Output watch");
        t.setDaemon(true);
        t.start();
    }
}
