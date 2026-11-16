package com.andre.hibyr4sound;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 3.5 mm line out = the normal headphone path at fixed full volume. HiBy's own "Lineout" output mode never powers
 * the DAC on the R4 (tested 2026-11-14), so it is never used.
 *
 * Fixed volume uses Android's "full volume" device behaviour for the wired headphones (system API, this app runs as
 * the system uid): the output plays at maximum, the volume keys do nothing, and the user's own headphone volume is
 * kept untouched and comes back as soon as the behaviour is set back to "variable".
 *
 * State: line out is wanted when sys.hibyr4.out35 = "lineout" (set by Settings / the API, saved by the module).
 * The moment the 3.5 mm plug is pulled, this switches back to variable volume and sets out35 to "headphone"
 * (the module then saves it and puts the normal gain back), so earphones plugged in later never get full volume.
 * The module also sends a "sync" broadcast (as root, explicit component) when line out starts or ends.
 */
public class LineOut extends BroadcastReceiver {
    static final String TAG = "HiByR4LineOut";
    private static final int ROLE_OUTPUT = 2;          // AudioDeviceAttributes.ROLE_OUTPUT
    private static final int BEHAVIOR_VARIABLE = 0;    // AudioManager.DEVICE_VOLUME_BEHAVIOR_VARIABLE
    private static final int BEHAVIOR_FULL = 1;        // AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL
    private static final int[] WIRED = {AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET};
    private static Boolean sFull;

    @Override
    public void onReceive(Context c, Intent i) { sync(c); }

    static boolean wanted() { return "lineout".equals(HiBySettingsProvider.prop("out35")); }

    static boolean wiredPresent(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            for (int t : WIRED) if (d.getType() == t) return true;
        return false;
    }

    /** Full volume only while line out is wanted and a 3.5 mm plug is in. */
    static synchronized void sync(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        boolean full = wanted() && wiredPresent(am);
        if (sFull != null && sFull == full) return;
        SharedPreferences sp = prefs(c);
        // Android overwrites the stored headphone volume with the maximum while "full volume" is on (tested
        // 2026-11-14), so save it before and write it back to the headphone output afterwards (works unplugged too).
        if (full && !sp.contains("hp_index")) {
            int v = deviceVolume(c, AudioDeviceInfo.TYPE_WIRED_HEADPHONES);
            // fallback: the 3.5 mm output is the active one when line out starts
            if (v < 0 && wiredPresent(am)) v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v >= 0) sp.edit().putInt("hp_index", v).commit();
            Log.i(TAG, "saved headphone volume " + v);
        }
        // Back from line out with DAC volume on: DacVolume sets the absolute behaviour itself (DAC first, then the
        // hand-over), so "variable" is skipped there - variable + DAC 100 for a moment would be the old level.
        if (!full && DacVolume.enabled() && sFull == null) { sFull = false; DacVolume.post(c); return; }
        if (setBehavior(am, full ? BEHAVIOR_FULL : BEHAVIOR_VARIABLE)) {
            sFull = full;
            Log.i(TAG, "wired headphones volume: " + (full ? "fixed (line out)" : "variable"));
            if (!full && sp.contains("hp_index")) {
                int v = sp.getInt("hp_index", -1);
                boolean ok = true;
                // setDeviceVolume is skipped by Android for the output in use ("device in use"): then the normal
                // stream volume is that output's volume. Unplugged, setDeviceVolume sets the stored headphone volume.
                for (int t : WIRED) ok &= setDeviceVolume(c, t, v);
                if (wiredPresent(am)) { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); ok = true; }
                if (ok) sp.edit().remove("hp_index").commit();
                Log.i(TAG, "headphone volume back to " + v + (ok ? "" : " (FAILED)"));
            }
            DacVolume.post(c);   // DAC at 100 for line out / volume back in the DAC afterwards
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("lineout", Context.MODE_PRIVATE);
    }

    private static Object volumeInfo(int index) throws Exception {
        Class<?> b = Class.forName("android.media.VolumeInfo$Builder");
        Object builder = b.getConstructor(int.class).newInstance(AudioManager.STREAM_MUSIC);
        if (index >= 0) b.getMethod("setVolumeIndex", int.class).invoke(builder, index);
        return b.getMethod("build").invoke(builder);
    }

    private static Object device(int type) throws Exception {
        return Class.forName("android.media.AudioDeviceAttributes").getConstructor(int.class, int.class, String.class)
                .newInstance(ROLE_OUTPUT, type, "");
    }

    /** android.media.AudioDeviceVolumeManager (system API; per-device volume lives there on Android 14). */
    private static Object advm(Context c) throws Exception {
        return Class.forName("android.media.AudioDeviceVolumeManager").getConstructor(Context.class).newInstance(c);
    }

    /** Music volume index stored for one output (AudioDeviceVolumeManager.getDeviceVolume), -1 on error. */
    static int deviceVolume(Context c, int type) {
        try {
            Class<?> vi = Class.forName("android.media.VolumeInfo"), ada = Class.forName("android.media.AudioDeviceAttributes");
            Object m = advm(c);
            Object r = m.getClass().getMethod("getDeviceVolume", vi, ada).invoke(m, volumeInfo(-1), device(type));
            return (Integer) vi.getMethod("getVolumeIndex").invoke(r);
        } catch (Throwable t) {
            Log.e(TAG, "getDeviceVolume failed", t);
            return -1;
        }
    }

    /** Set the music volume index of one output, even when it isn't the active one (setDeviceVolume). */
    static boolean setDeviceVolume(Context c, int type, int index) {
        try {
            Class<?> vi = Class.forName("android.media.VolumeInfo"), ada = Class.forName("android.media.AudioDeviceAttributes");
            Object m = advm(c);
            m.getClass().getMethod("setDeviceVolume", vi, ada).invoke(m, volumeInfo(index), device(type));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setDeviceVolume failed", t);
            return false;
        }
    }

    private static boolean setBehavior(AudioManager am, int behavior) {
        try {
            Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
            Constructor<?> ctor = ada.getConstructor(int.class, int.class, String.class);
            Method set = AudioManager.class.getMethod("setDeviceVolumeBehavior", ada, int.class);
            for (int t : WIRED) set.invoke(am, ctor.newInstance(ROLE_OUTPUT, t, ""), behavior);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setDeviceVolumeBehavior failed", t);
            return false;
        }
    }

    /** Called once from App.onCreate: start variable (unless line out is on), watch plug in / out. */
    static void start(final Context c) {
        final AudioManager am = c.getSystemService(AudioManager.class);
        sFull = null;
        sync(c);
        am.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { sync(c); }

            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                if (wanted() && !wiredPresent(am)) {
                    HiBySettingsProvider.setProp("out35", "headphone");   // the module saves it + restores gain
                    Log.i(TAG, "3.5 mm plug removed: line out off");
                }
                sync(c);
            }
        }, null);
    }
}
