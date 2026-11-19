package com.andre.hibyr4.settings;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Bridge between the Settings pages (running inside the Settings app, uid system) and the HiBy R4 Magisk module.
 * HiBy settings: system properties sys.hibyr4.KEY. The module's root service publishes the saved values there at
 * boot and applies every change it sees (validated by hiby_settings.sh). Settings may set sys.* props (SELinux:
 * system_app -> system_prop). Equalizer: Settings.Global "hibyr4_eq", read by the HiBy R4 Sound helper app.
 */
final class Hb {
    static final String TAG = "HiByR4Settings";
    static final String PREFIX = "sys.hibyr4.";

    private static Method sGet, sSet;

    static String get(String key, String def) {
        try {
            if (sGet == null) sGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            String v = (String) sGet.invoke(null, PREFIX + key, def);
            return v == null || v.isEmpty() ? def : v;
        } catch (Throwable t) {
            Log.w(TAG, "get " + key, t);
            return def;
        }
    }

    static boolean set(String key, String value) {
        try {
            if (sSet == null) sSet = Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class);
            sSet.invoke(null, PREFIX + key, value);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "set " + key + "=" + value, t);
            return false;
        }
    }

    /** true when the module's service has published the settings (it sets sys.hibyr4.ready=1). */
    static boolean ready() { return "1".equals(get("ready", "")); }

    static int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, Integer.toString(def))); } catch (NumberFormatException e) { return def; }
    }

    // ---------------- equalizer (same format as com.andre.hibyr4sound.Eq) ----------------
    static final String EQ_KEY = "hibyr4_eq";
    static final int[] FREQ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final int BANDS = 10, MAX_DB = 12;
    // index: 0 on, 1 auto preamp, 2 preamp (dB, <= 0), 3 preset, 4..13 band gains (dB)
    static final int I_ON = 0, I_AUTO = 1, I_PRE = 2, I_PRESET = 3, I_BAND = 4, EQ_LEN = 14;
    static final String[] PRESET_NAMES = {"Flat", "Bass", "Treble", "Vocal", "Rock", "Pop", "Jazz", "Classical", "Custom"};
    static final int CUSTOM = 8;
    static final int[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {6, 5, 4, 2, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 1, 2, 4, 5, 6},
            {-2, -2, -1, 1, 3, 4, 3, 1, 0, -1},
            {4, 3, 2, 0, -1, -1, 1, 2, 3, 4},
            {-1, 0, 2, 3, 4, 3, 1, 0, -1, -1},
            {3, 2, 1, 2, -1, -1, 0, 1, 2, 3},
            {4, 3, 2, 1, -1, -1, 0, 2, 3, 4},
    };

    static int[] eqLoad(ContentResolver cr) {
        int[] a = new int[EQ_LEN];
        a[I_AUTO] = 1;
        String s = Settings.Global.getString(cr, EQ_KEY);
        if (s != null) {
            String[] p = s.split(",");
            for (int i = 0; i < EQ_LEN && i < p.length; i++) {
                try { a[i] = Integer.parseInt(p[i].trim()); } catch (NumberFormatException ignored) { }
            }
        }
        return a;
    }

    static boolean eqSave(ContentResolver cr, int[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < EQ_LEN; i++) { if (i > 0) b.append(','); b.append(a[i]); }
        try {
            return Settings.Global.putString(cr, EQ_KEY, b.toString());
        } catch (Throwable t) {
            Log.w(TAG, "eq save", t);
            return false;
        }
    }

    static String eqSummary(ContentResolver cr) {
        int[] a = eqLoad(cr);
        if (a[I_ON] == 0) return "Off";
        int p = a[I_PRESET];
        return "On – " + (p >= 0 && p < PRESET_NAMES.length ? PRESET_NAMES[p] : "Custom");
    }

    static String db(int v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + Math.abs(v) + " dB"; }
}
