package com.andre.hibyr4sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;

/**
 * Hold switch (the switch on top of the R4). The module's hold_switch.sh (root) watches the switch and the R4's
 * buttons directly in the hardware and calls hold_switch / hold_key here. HiBy's key maps mix the buttons over two
 * devices: gpio-keys = volume up, volume down, play/pause, previous; qpnp_pon = power, next. While locked, both are
 * switched off in Android's input reader (every level), so they're ignored even with the screen off, and the helper
 * passes on what the level keeps working:
 *  - "media":  volume up, volume down, power (play/pause, previous, next locked)
 *  - "volume": power (media + volume locked)
 *  - "all":    nothing
 * Long-press power (power menu) doesn't work while locked. "Sleep when locked": screen off on lock, on again on
 * unlock. hold_get / hold_set for Songate, observer /system/hold. Only the R4's own buttons: the wired remote
 * (earpods_adc2) and Bluetooth buttons always work. HiBy's driver can't switch single keys off, hence whole devices.
 */
final class HoldSwitch {
    static final String TAG = "SongateHold";
    static final String[] LEVELS = { "media", "volume", "all" };
    private static final String SIDE_DEVICE = "gpio-keys";
    private static final String POWER_DEVICE = "qpnp_pon";
    /** v42/v43 used an accessibility key filter; removed again at start if it's still listed. */
    private static final String OLD_FILTER = "com.andre.hibyr4.service/com.andre.hibyr4sound.HoldKeyFilter";

    private static Context sApp;
    private static volatile boolean sLocked;

    private HoldSwitch() { }

    private static void setprop(String k, String v) {
        try { Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class).invoke(null, k, v); }
        catch (Throwable t) { Log.w(TAG, "setprop " + k, t); }
    }

    private static SharedPreferences prefs() {   // device-protected: the service starts before the user is unlocked
        return sApp.createDeviceProtectedStorageContext().getSharedPreferences("hold", Context.MODE_PRIVATE);
    }
    static String level() { return prefs().getString("level", "volume"); }
    static boolean sleep() { return prefs().getBoolean("sleep", true); }

    static void start(Context c) {
        if (sApp != null) return;
        sApp = c.getApplicationContext();
        setprop("sys.hibyr4.hold_level", level());
        sLocked = "1".equals(Updater.sysprop("sys.hibyr4.hold", "0"));
        removeOldFilter();
        apply();   // also undoes anything left over from a crash
    }

    /** From hold_switch.sh (root) on every flip; boot = the starting state (no sleep / wake). */
    static Bundle onSwitch(String arg, boolean boot) {
        boolean was = sLocked;
        sLocked = "1".equals(arg);
        apply();
        if (!boot && was != sLocked && sleep()) {
            if (sLocked) goToSleep(); else wakeUp();
        }
        SystemApi.notify("hold");
        return SystemApi.ok();
    }

    /** From hold_switch.sh (root): a key the level lets through, read while its device is off in Android. */
    static Bundle onKey(String key) {
        if (!sLocked || inFramework()) return SystemApi.ok();
        String lv = level();
        if (("volup".equals(key) || "voldown".equals(key)) && "media".equals(lv)) {
            AudioManager am = sApp.getSystemService(AudioManager.class);
            am.adjustSuggestedStreamVolume("volup".equals(key) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.USE_DEFAULT_STREAM_TYPE, interactive() ? AudioManager.FLAG_SHOW_UI : 0);
        } else if ("power".equals(key) && ("media".equals(lv) || "volume".equals(lv))) {
            if (interactive()) goToSleep(); else wakeUp();
        }
        return SystemApi.ok();
    }

    static Bundle get() {
        Bundle b = SystemApi.ok();
        b.putString("locked", sLocked ? "1" : "0");
        b.putString("level", level());
        b.putStringArray("levels", LEVELS);
        b.putString("sleep", sleep() ? "1" : "0");
        return b;
    }

    static Bundle set(Bundle e) {
        if (e == null) return SystemApi.err("bad_arg", "level or sleep");
        String lv = e.getString("level"), sl = e.getString("sleep");
        if (lv != null && java.util.Arrays.asList(LEVELS).indexOf(lv) < 0)
            return SystemApi.err("bad_arg", "level must be media, volume or all");
        if (sl != null && !"0".equals(sl) && !"1".equals(sl)) return SystemApi.err("bad_arg", "sleep must be 0 or 1");
        SharedPreferences.Editor ed = prefs().edit();
        if (lv != null) { ed.putString("level", lv); setprop("sys.hibyr4.hold_level", lv); }
        if (sl != null) ed.putBoolean("sleep", "1".equals(sl));
        ed.commit();
        apply();
        SystemApi.notify("hold");
        return get();
    }

    /**
     * SongateOS image (ro.songateos.hold_framework=1): Android's key policy itself drops the locked buttons
     * (PhoneWindowManager, patches/apply_hold.py), so the devices stay on and nothing is passed on.
     */
    static boolean inFramework() { return "1".equals(Updater.sysprop("ro.songateos.hold_framework", "0")); }

    /** The R4's button devices: both off while locked, whatever the level (the helper passes keys on); always on
     *  when the framework does the filtering (also undoes devices left off by an older service). */
    private static void apply() {
        boolean on = !sLocked || inFramework();
        setDeviceEnabled(SIDE_DEVICE, on);
        setDeviceEnabled(POWER_DEVICE, on);
    }

    private static boolean interactive() { return sApp.getSystemService(PowerManager.class).isInteractive(); }

    private static void setDeviceEnabled(String name, boolean enabled) {
        try {
            InputManager im = sApp.getSystemService(InputManager.class);
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d == null || !name.equals(d.getName())) continue;
                if (d.isEnabled() == enabled) return;
                InputManager.class.getMethod(enabled ? "enableInputDevice" : "disableInputDevice", int.class).invoke(im, id);
                Log.i(TAG, name + (enabled ? " on" : " off"));
                return;
            }
            Log.w(TAG, "input device " + name + " not found");
        } catch (Throwable t) { Log.w(TAG, "input device " + name, t); }
    }

    private static void removeOldFilter() {
        android.content.ContentResolver cr = sApp.getContentResolver();
        String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur == null || !cur.contains(OLD_FILTER)) return;
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String s : cur.split(":")) if (!s.isEmpty() && !s.equals(OLD_FILTER)) list.add(s);
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", list));
        if (list.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
    }

    private static void goToSleep() {
        try {
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            PowerManager.class.getMethod("goToSleep", long.class).invoke(pm, SystemClock.uptimeMillis());
        } catch (Throwable t) { Log.w(TAG, "sleep", t); }
    }

    private static void wakeUp() {
        try {
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class)
                    .invoke(pm, SystemClock.uptimeMillis(), 0 /* WAKE_REASON_UNKNOWN */, "songateos:hold");
        } catch (Throwable t) { Log.w(TAG, "wake", t); }
    }
}
