package com.andre.hibyr4sound;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.provider.Settings;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 10-band graphic equalizer built on Android's DynamicsProcessing effect (pre-EQ stage).
 * Applied to the global output mix (session 0) and, as a fallback, to every audio session that a
 * player announces with ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION (same mechanism LineageOS AudioFX uses).
 */
public final class Eq {
    private static final String TAG = "HiByR4Eq";
    public static final int[] FREQ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final int BANDS = FREQ.length;
    public static final int MAX_DB = 12;

    public static final String[] PRESET_NAMES = {"Flat", "Bass", "Treble", "Vocal", "Rock", "Pop", "Jazz", "Classical", "Custom"};
    public static final int[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {6, 5, 4, 2, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 1, 2, 4, 5, 6},
            {-2, -2, -1, 1, 3, 4, 3, 1, 0, -1},
            {4, 3, 2, 0, -1, -1, 1, 2, 3, 4},
            {-1, 0, 2, 3, 4, 3, 1, 0, -1, -1},
            {3, 2, 1, 2, -1, -1, 0, 1, 2, 3},
            {4, 3, 2, 1, -1, -1, 0, 2, 3, 4},
    };

    private static DynamicsProcessing global;
    private static final Map<Integer, DynamicsProcessing> sessions = new HashMap<>();
    private static boolean receiverRegistered;
    public static String lastError = "";

    /** Settings.Global key written by Settings > HiBy audio > Equalizer (Settings app, uid system).
     *  Format: on,autoPreamp,preamp,preset,g0..g9 (ints, dB). */
    public static final String KEY = "hibyr4_eq";

    static int[] state(Context c) {
        int[] a = new int[14];
        a[1] = 1;
        String s = Settings.Global.getString(c.getContentResolver(), KEY);
        if (s != null) {
            String[] p = s.split(",");
            for (int i = 0; i < a.length && i < p.length; i++) {
                try { a[i] = Integer.parseInt(p[i].trim()); } catch (NumberFormatException ignored) { }
            }
        }
        return a;
    }

    public static boolean enabled(Context c) { return state(c)[0] != 0; }
    public static int gain(Context c, int band) { return Math.max(-MAX_DB, Math.min(MAX_DB, state(c)[4 + band])); }
    public static boolean autoPreamp(Context c) { return state(c)[1] != 0; }
    public static int preamp(Context c) { return Math.max(-MAX_DB, Math.min(0, state(c)[2])); }

    /** Effective input gain: manual preamp, or -max(boost) when auto preamp is on (prevents clipping). */
    public static float effectivePreamp(Context c) {
        if (!autoPreamp(c)) return preamp(c);
        int max = 0;
        for (int i = 0; i < BANDS; i++) max = Math.max(max, gain(c, i));
        return -max;
    }

    private static DynamicsProcessing create(int session) {
        DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS, false, 0, false, 0, false).build();
        return new DynamicsProcessing(0, session, cfg);
    }

    private static void configure(Context c, DynamicsProcessing dp) {
        for (int i = 0; i < BANDS; i++) {
            float cutoff = i < BANDS - 1 ? (float) Math.sqrt((double) FREQ[i] * FREQ[i + 1]) : 20000f;
            dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, cutoff, gain(c, i)));
        }
        dp.setInputGainAllChannelsTo(effectivePreamp(c));
        dp.setEnabled(enabled(c));
    }

    /** (Re)apply all settings to every effect instance. Safe to call often. */
    public static synchronized void apply(Context ctx) {
        Context c = ctx.getApplicationContext();
        lastError = "";
        registerReceiver(c);
        try {
            if (global == null) global = create(0);
            configure(c, global);
        } catch (Throwable t) {
            lastError = "global: " + t;
            Log.w(TAG, "global effect failed", t);
            global = null;
        }
        for (Map.Entry<Integer, DynamicsProcessing> e : sessions.entrySet()) {
            try { configure(c, e.getValue()); } catch (Throwable t) { Log.w(TAG, "session " + e.getKey(), t); }
        }
    }

    private static void registerReceiver(Context c) {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent i) {
                int s = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
                if (s == 0) return;
                synchronized (Eq.class) {
                    if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
                        if (!sessions.containsKey(s)) {
                            try {
                                DynamicsProcessing dp = create(s);
                                configure(context, dp);
                                sessions.put(s, dp);
                            } catch (Throwable t) { Log.w(TAG, "open session " + s, t); }
                        }
                    } else {
                        DynamicsProcessing dp = sessions.remove(s);
                        if (dp != null) dp.release();
                    }
                }
            }
        }, f, Context.RECEIVER_EXPORTED);
        receiverRegistered = true;
    }

    public static int activeSessions() { return sessions.size(); }
    public static boolean globalActive() { return global != null; }
}
