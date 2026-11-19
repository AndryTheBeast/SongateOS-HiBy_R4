package com.andre.hibyr4sound;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wired volume in the DAC (setting bit_perfect, default on; 2026-11-16).
 *
 * Bit-perfect playback (Android 14 setPreferredMixerAttributes, enabled on the wired outputs by the module) sends the
 * samples to HiBy's driver untouched, so Android's software volume no longer applies. The volume is therefore done
 * the way HiBy OS does it, in the ES9018C2M: HiBy's platform volume ("Plat Left/Right Playback Volume", 0..100, even
 * values only), for all wired playback, bit-perfect or not.
 *
 * Android's "absolute volume" device behaviour (system API, this app runs as the system uid) is set for the wired
 * headphones/headset: Android keeps the volume slider and the steps (15/25/50/100), applies no software volume on
 * that output and reports every change here. Scale = HiBy's own: step / max steps -> 0..100 (tested by ear: works,
 * no clicks; low steps are quieter than Android's curve, as on HiBy OS).
 *
 * The DAC is set first and the behaviour handed over afterwards, so the level never jumps up. Balance (-10..10) is
 * applied here too (the quieter side scaled down), so it also works bit-perfect; the module keeps Android's software
 * balance at 0 while this is on. 3.5 mm line out keeps Android's "full volume" behaviour (LineOut) with the DAC at 100.
 * The module sends a refresh broadcast after boot settings, balance changes and audio HAL restarts.
 *
 * 4.4 mm balanced (2026-11-19): Android only knows the 3.5 mm jack, so with only the 4.4 mm plug in it plays to
 * "speaker" (HiBy's driver is on its balanced output, service.sh). The R4 has no speaker, so "speaker" always means
 * the 4.4 mm: it gets the same absolute behaviour, with its own saved step, and the DAC follows it while no 3.5 mm
 * plug is in. Before this the 4.4 mm got Android's speaker curve in software on top of the DAC: very quiet (user test).
 * (Registering the 4.4 mm as wired headphones instead made HiBy's driver switch to its 3.5 mm/S/PDIF outputs.)
 */
public class DacVolume extends BroadcastReceiver {
    static final String TAG = "HiByR4DacVol";
    private static final int ROLE_OUTPUT = 2;   // AudioDeviceAttributes.ROLE_OUTPUT
    private static final int[] WIRED = {AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET};
    private static final int SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;   // = the 4.4 mm balanced output on the R4
    private static final int[] DAC_DEVICES = {AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, SPEAKER};
    private static final java.util.concurrent.ScheduledExecutorService EX = Executors.newSingleThreadScheduledExecutor();
    private static boolean sAbs;               // absolute behaviour registered (not during line out)
    private static int sIdx = -1, sMax = 25;   // last volume step
    private static int sL = -1, sR = -1;       // last values written to the DAC
    private static Object sListener;

    @Override
    public void onReceive(Context c, Intent i) {
        final Context app = c.getApplicationContext();
        final PendingResult pr = goAsync();
        EX.execute(() -> {
            try {
                sync(app);
                if (sAbs) {                                                    // not during line out
                    // the output may have changed (3.5 mm <-> 4.4 mm): take that output's own saved step
                    boolean wired = LineOut.wiredPresent(app.getSystemService(AudioManager.class));
                    int s = LineOut.deviceVolume(app, wired ? AudioDeviceInfo.TYPE_WIRED_HEADPHONES : SPEAKER);
                    if (s >= 0) sIdx = s;
                    write(true);
                }
            } finally { pr.finish(); }
        });
    }

    static boolean enabled() { return !"0".equals(HiBySettingsProvider.prop("bit_perfect")); }

    /** Called once from App.onCreate. */
    static void start(Context c) {
        final Context app = c.getApplicationContext();
        EX.execute(() -> sync(app));
    }

    /** From LineOut after it changed the behaviour (line out on/off, plug in/out). */
    static void post(Context c) {
        final Context app = c.getApplicationContext();
        EX.execute(() -> sync(app));
    }

    private static final int BEHAVIOR_ABSOLUTE = 3;   // AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE
    private static boolean sWatching;

    /** Current volume behaviour of the wired headphones (-1 on error). */
    private static int behavior(AudioManager am) {
        try {
            Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
            Object dev = ada.getConstructor(int.class, int.class, String.class)
                    .newInstance(ROLE_OUTPUT, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "");
            return (Integer) AudioManager.class.getMethod("getDeviceVolumeBehavior", ada).invoke(am, dev);
        } catch (Throwable t) { return -1; }
    }

    /**
     * Android resets device volume behaviours on its own: at boot (restoreDeviceVolumeBehavior, a few seconds after
     * this app has started - seen 2026-11-16) and when the audio service restarts. Every change is reported here,
     * and the DAC volume is set up again whenever the wired headphones are no longer "absolute" (except line out).
     */
    private static void watch(Context c) {
        if (sWatching) return;
        try {
            Class<?> advmC = Class.forName("android.media.AudioDeviceVolumeManager");
            Object advm = advmC.getConstructor(Context.class).newInstance(c);
            Class<?> lis = Class.forName("android.media.AudioDeviceVolumeManager$OnDeviceVolumeBehaviorChangedListener");
            Object l = Proxy.newProxyInstance(lis.getClassLoader(), new Class<?>[]{lis}, (p, m, a) -> {
                if (m.getName().equals("onDeviceVolumeBehaviorChanged") && a != null && a.length == 2) {
                    int t = (Integer) a[0].getClass().getMethod("getType").invoke(a[0]);
                    int b = (Integer) a[1];
                    if ((t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == SPEAKER)
                            && b != BEHAVIOR_ABSOLUTE) {
                        Log.i(TAG, "wired volume behaviour changed to " + b + " (type " + t + ")");
                        EX.execute(() -> { sAbs = false; sync(c); });
                    }
                }
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("toString")) return "DacVolume behaviour listener";
                return null;
            });
            advmC.getMethod("addOnDeviceVolumeBehaviorChangedListener", java.util.concurrent.Executor.class, lis)
                    .invoke(advm, EX, l);
            sWatching = true;
        } catch (Throwable t) {
            Log.e(TAG, "behaviour listener failed", t);
        }
    }

    private static void sync(Context c) {
        if (!enabled()) { Log.i(TAG, "off (bit_perfect=0)"); return; }   // takes effect after a restart
        AudioManager am = c.getSystemService(AudioManager.class);
        watch(c);
        if (LineOut.wanted() && LineOut.wiredPresent(am)) {   // line out: full volume, DAC at 100 (LineOut sets FULL)
            sAbs = false;
            set(100, 100);
            Log.i(TAG, "line out: DAC 100");
            return;
        }
        if (sAbs && behavior(am) != BEHAVIOR_ABSOLUTE) {
            Log.i(TAG, "absolute volume was reset by Android (behaviour " + behavior(am) + "): setting it up again");
            sAbs = false;
        }
        if (sAbs) return;
        sMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean wired = LineOut.wiredPresent(am);
        int idx = LineOut.deviceVolume(c, wired ? AudioDeviceInfo.TYPE_WIRED_HEADPHONES : SPEAKER);
        if (idx < 0) idx = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        sIdx = idx;
        write(true);   // DAC first: Android's software volume still applies until the hand-over, so it can only be quieter
        try {
            Class<?> advmC = Class.forName("android.media.AudioDeviceVolumeManager");
            Object advm = advmC.getConstructor(Context.class).newInstance(c);
            Class<?> lis = Class.forName("android.media.AudioDeviceVolumeManager$OnAudioDeviceVolumeChangedListener");
            if (sListener == null) sListener = Proxy.newProxyInstance(lis.getClassLoader(), new Class<?>[]{lis}, (p, m, a) -> {
                if (m.getName().equals("onAudioDeviceVolumeChanged") && a != null && a.length == 2) {
                    int v = (Integer) a[1].getClass().getMethod("getVolumeIndex").invoke(a[1]);
                    final int dt = (Integer) a[0].getClass().getMethod("getType").invoke(a[0]);
                    // While the audio service restarts (e.g. an out_rate switch), Android reports the maximum here
                    // for a moment (seen 2026-11-16: DAC 70 -> 100 -> 70 within a second). So the value used is the
                    // user's own saved step for the wired output, and nothing is written unless the wired output
                    // is really in "absolute" mode right now.
                    EX.execute(() -> {
                        AudioManager am2 = c.getSystemService(AudioManager.class);
                        int b = behavior(am2);
                        boolean wiredNow = LineOut.wiredPresent(am2);
                        // the DAC follows the output that is playing: 3.5 mm while its plug is in, else the 4.4 mm
                        if ((dt == SPEAKER) == wiredNow) return;
                        int user = LineOut.deviceVolume(c, wiredNow ? AudioDeviceInfo.TYPE_WIRED_HEADPHONES : SPEAKER);
                        Log.i(TAG, "volume reported " + v + " (type " + dt + "), saved step " + user + ", behaviour " + b);
                        if (b != BEHAVIOR_ABSOLUTE || user < 0) return;
                        sIdx = user;
                        write(false);
                    });
                }
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("toString")) return "DacVolume listener";
                return null;
            });
            Method set = null;
            for (Method m : advmC.getMethods())
                if (m.getName().equals("setDeviceAbsoluteVolumeBehavior") && m.getParameterCount() == 5) set = m;
            Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
            for (int t : DAC_DEVICES) {
                int ti = LineOut.deviceVolume(c, t);
                if (ti < 0) ti = idx;
                set.invoke(advm, ada.getConstructor(int.class, int.class, String.class).newInstance(ROLE_OUTPUT, t, ""),
                        volumeInfo(ti, sMax), EX, sListener, false);
            }
            sAbs = true;
            sRetry = 0;
            Log.i(TAG, "wired volume in the DAC: step " + idx + "/" + sMax + ", behaviour now " + behavior(am));
            // Android keeps its software volume on the output until the next volume change (tested 2026-11-16: the
            // first key press after the hand-over jumped up). Nudge the volume one step down and back, so it drops
            // its software volume now. Down first: the in-between level can only be quieter.
            {   // 3.5 mm or 4.4 mm ("speaker"): both now take the DAC volume
                am.setStreamVolume(AudioManager.STREAM_MUSIC, idx > 0 ? idx - 1 : 0, 0);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
                Log.i(TAG, "software volume dropped (nudge " + idx + ")");
            }
        } catch (Throwable t) {
            // Never put the DAC up here (an earlier version went to 100 for a second when this failed while the audio
            // service restarted). The DAC keeps the user's level; Android's software volume may add to it meanwhile
            // (quieter only). Try again a little later.
            Log.e(TAG, "absolute volume failed (try " + (sRetry + 1) + "), DAC stays at the user's level", t);
            if (++sRetry <= 10) EX.schedule(() -> { if (!sAbs) sync(c); }, 2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    private static int sRetry;

    private static Object volumeInfo(int index, int max) throws Exception {
        Class<?> b = Class.forName("android.media.VolumeInfo$Builder");
        Object builder = b.getConstructor(int.class).newInstance(AudioManager.STREAM_MUSIC);
        b.getMethod("setMinVolumeIndex", int.class).invoke(builder, 0);
        b.getMethod("setMaxVolumeIndex", int.class).invoke(builder, max);
        b.getMethod("setVolumeIndex", int.class).invoke(builder, index);
        return b.getMethod("build").invoke(builder);
    }

    /** Step + balance -> DAC values (HiBy's scale, even values). force = write even if unchanged. */
    private static void write(boolean force) {
        if (!sAbs && !force) return;
        if (sIdx < 0 || sMax <= 0) return;
        int v = even(Math.round(sIdx * 100f / sMax));
        int bal = 0;
        try { bal = Integer.parseInt(HiBySettingsProvider.prop("balance")); } catch (NumberFormatException ignored) { }
        bal = Math.max(-10, Math.min(10, bal));
        int l = v, r = v;
        if (bal < 0) r = even(Math.round(v * (10 + bal) / 10f));   // negative = towards the left: right quieter
        if (bal > 0) l = even(Math.round(v * (10 - bal) / 10f));
        if (force) { sL = -1; sR = -1; }
        set(l, r);
    }

    private static int even(int v) { v = Math.max(0, Math.min(100, v)); return v - (v % 2); }

    private static void set(int l, int r) {
        if (l == sL && r == sR) return;
        Log.i(TAG, "DAC " + l + "/" + r);
        if (l != sL && mix("Plat Left Playback Volume", l)) sL = l;
        if (r != sR && mix("Plat Right Playback Volume", r)) sR = r;
    }

    private static boolean mix(String ctl, int v) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/tinymix", "-D", "0", ctl, Integer.toString(v)});
            int rc = p.waitFor();
            if (rc != 0) Log.e(TAG, ctl + " " + v + " failed (" + rc + ")");
            return rc == 0;
        } catch (Exception e) {
            Log.e(TAG, ctl + " " + v + " failed", e);
            return false;
        }
    }
}
