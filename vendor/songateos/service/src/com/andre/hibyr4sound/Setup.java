package com.andre.hibyr4sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

/**
 * SongateOS first-boot setup (system API area "setup"). The setup screens are Songate's; this class keeps the state.
 *
 * Two separate things:
 *  - done: Songate's setup was finished (setup_done). Songate shows its setup while this is "0".
 *  - android: Android's own "setup finished" flags (Settings.Global.DEVICE_PROVISIONED, Settings.Secure
 *    USER_SETUP_COMPLETE). SongateOS has no setup wizard, so nothing else sets them; while they are 0 SystemUI keeps
 *    the status bar, quick settings and gesture navigation off and Settings refuses developer options.
 *
 * setup_done sets both. Safety net for the Android flags (never for "done"): they are set anyway when Songate is
 * missing, disabled or has no setup screens, and from the second boot on with setup still not done, so a broken or skipped
 * setup can never leave the R4 without its status bar and navigation.
 */
final class Setup {
    static final String TAG = "SongateSetup";
    private static final String PREFS = "setup";
    private static final String K_DONE = "done";
    private static final String K_FIRST_BOOT = "first_boot_count";
    static final String SONGATE = "io.github.andrythebeast.songate";

    private Setup() { }

    private static SharedPreferences prefs(Context c) {
        // device-protected: readable before the first unlock (the service starts at boot)
        return c.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean androidDone(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) == 1
                && Settings.Secure.getInt(c.getContentResolver(), "user_setup_complete", 0) == 1;
    }

    private static void markAndroidDone(Context c, String why) {
        if (androidDone(c)) return;
        Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(c.getContentResolver(), "user_setup_complete", 1);
        Log.i(TAG, "Android setup flags set (" + why + ")");
        SystemApi.notify("setup");
    }

    /** At every start of the service. */
    static void onStart(Context c) {
        try {
            SharedPreferences p = prefs(c);
            if (p.getBoolean(K_DONE, false)) { markAndroidDone(c, "setup was done"); return; }
            if (!songateUsable(c)) { markAndroidDone(c, "Songate missing, disabled or without setup screens"); return; }
            // per boot, not per process start (the service process can restart within one boot)
            int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
            int first = p.getInt(K_FIRST_BOOT, -1);
            if (first < 0) p.edit().putInt(K_FIRST_BOOT, boot).apply();
            else if (boot > first) markAndroidDone(c, "second boot without a finished setup");
        } catch (RuntimeException e) {
            Log.w(TAG, "start", e);
        }
    }

    /**
     * Songate is installed, enabled and has setup screens: its manifest declares
     * <meta-data android:name="songateos.setup" android:value="true"/> on the application. A Songate without them
     * (2.1.0 BETA 1) never calls setup_done, so the Android flags are set straight away.
     */
    private static boolean songateUsable(Context c) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(SONGATE, PackageManager.GET_META_DATA);
            return ai.enabled && ai.metaData != null && ai.metaData.getBoolean("songateos.setup", false);
        } catch (Exception e) {
            return false;
        }
    }

    static Bundle get(Context c) {
        Bundle b = SystemApi.ok();
        b.putString("done", prefs(c).getBoolean(K_DONE, false) ? "1" : "0");
        b.putString("android", androidDone(c) ? "1" : "0");
        return b;
    }

    static Bundle done(Context c) {
        prefs(c).edit().putBoolean(K_DONE, true).apply();
        markAndroidDone(c, "setup_done");
        SystemApi.notify("setup");
        return get(c);
    }

    /** Development only (system uid): Songate's setup shows again at its next start; Android's flags stay set. */
    static Bundle reset(Context c) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) return SystemApi.err("no_permission", "System only");
        prefs(c).edit().putBoolean(K_DONE, false).remove(K_FIRST_BOOT).apply();
        SystemApi.notify("setup");
        return get(c);
    }
}
