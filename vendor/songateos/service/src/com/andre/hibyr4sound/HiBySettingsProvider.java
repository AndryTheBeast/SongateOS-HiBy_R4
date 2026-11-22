package com.andre.hibyr4sound;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Public API for other apps (e.g. a launcher / music player) to read and change the HiBy R4 settings.
 * Authority: com.andre.hibyr4.settings  - caller needs com.andre.hibyr4.permission.HIBY_SETTINGS.
 *
 * call(method, arg, extras):
 *   "get"        -> Bundle of all settings (String values) + status keys (ready, st_output, st_rate, st_led) + "eq"
 *   "set"        arg=key, extras{"value"} -> Bundle{"ok":bool, "error":String}; applied by the module within ~0.3 s
 *   "eq_get"     -> Bundle{"eq": "on,auto,pre,preset,g0..g9"}
 *   "eq_set"     extras{"eq": same format} -> Bundle{"ok"}
 *   "led_live"   extras{"color":"rrggbb","bright":int,"effect":"solid|breath"} -> draws the custom colour at once
 *                (only while LED mode is "custom"; save it with "set led_color" afterwards)
 *   "ota_status" -> Bundle{state, current, latest, notes, size, progress, error, recovery, result, auto, beta, ...}
 *   "ota_check"  -> starts a check for a SongateOS update (result in ota_status)
 *   "ota_download" / "ota_install" / "ota_cancel" / "ota_set" -> Settings (uid system) only
 * query(content://com.andre.hibyr4.settings/all) -> rows (key, value) of the same data as "get".
 *
 * Runs as the system user (platform-signed, sharedUserId android.uid.system), so it may set the sys.hibyr4.*
 * properties that the module's root service applies - the same path the Settings pages use.
 */
public class HiBySettingsProvider extends ContentProvider {
    static final String TAG = "HiByR4Api";
    public static final String PERMISSION = "com.andre.hibyr4.permission.HIBY_SETTINGS";
    static final String PREFIX = "sys.hibyr4.";

    /** key -> allowed values (same rules as the module's hiby_settings.sh, which validates again) */
    static final String[][] KEYS = {
            {"gain", "low|high"},
            {"filter", "fast_rolloff|slow_rolloff|minimum_phase"},
            {"balance", "-?([0-9]|10)"},
            {"out35", "headphone|lineout"},
            {"out44", "bal_po|bal_lo"},
            {"lo_gain", "low|high"},
            {"dsd_comp", "[0-9]"},
            {"remote", "0|1"},
            {"led_mode", "hiby|off|custom"},
            {"led_color", "[0-9a-fA-F]{6}"},
            {"led_bright", "[0-9]|[1-9][0-9]|100"},
            {"led_effect", "solid|breath"},
            {"led_charge", "0|1"},
            {"led_screen", "0|1"},
            {"led_back", "on|screen|off|music"},
            {"led_back_charge", "0|1"},
            {"led_back_notif", "0|1"},
            {"led_back_beat", "0|1"},
            {"led_notif", "0|1"},
            {"led_notif_color", "[0-9a-fA-F]{6}"},
            {"led_lowbat", "0|1"},
            {"led_lowbat_level", "5|10|15|20|25|30"},
            {"led_quality", "0|1"},
            {"led_q_cd", "[0-9a-fA-F]{6}"},
            {"led_q_hires", "[0-9a-fA-F]{6}"},
            {"led_q_ultra", "[0-9a-fA-F]{6}"},
            {"led_beat", "0|1"},
            {"vol_steps", "15|25|50|100"},
            {"vol_restore", "last|fixed"},
            {"vol_boot", "[0-9]|[1-9][0-9]|100"},
            {"btn_wake", "0|1"},
            {"bit_perfect", "0|1"},
            {"out_rate", "44100|48000|88200|96000|176400|192000|352800|384000"},
            {"usb_nocharge", "0|1"},   // 2.1.1: a USB audio device (DAC, speaker) that powers the R4 doesn't charge it
    };
    static final String[] STATUS = {"ready", "st_output", "st_rate", "st_led", "st_out_rate", "st_quality", "st_usb_nocharge"};

    private static Method sGet, sSet;

    static String prop(String key) {
        try {
            if (sGet == null) sGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            return (String) sGet.invoke(null, PREFIX + key, "");
        } catch (Throwable t) { return ""; }
    }

    static boolean setProp(String key, String value) {
        try {
            if (sSet == null) sSet = Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class);
            sSet.invoke(null, PREFIX + key, value);
            return true;
        } catch (Throwable t) { Log.w(TAG, "set " + key, t); return false; }
    }

    static String allowed(String key) {
        for (String[] k : KEYS) if (k[0].equals(key)) return k[1];
        return null;
    }

    private void enforce() {
        if (getContext().checkCallingOrSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("needs " + PERMISSION);
    }

    private Bundle all() {
        Bundle b = new Bundle();
        for (String[] k : KEYS) b.putString(k[0], prop(k[0]));
        for (String s : STATUS) b.putString(s, prop(s));
        String eq = Settings.Global.getString(getContext().getContentResolver(), Eq.KEY);
        b.putString("eq", eq == null ? "" : eq);
        return b;
    }

    private static Bundle result(boolean ok, String error) {
        Bundle b = new Bundle();
        b.putBoolean("ok", ok);
        if (error != null) b.putString("error", error);
        return b;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforce();
        Bundle sys = SystemApi.call(getContext(), method, arg, extras);   // sys_info, wifi_*, bt_*, output_*, power
        if (sys != null) return sys;
        switch (method) {
            case "get":
                return all();
            case "set": {
                String v = extras == null ? null : extras.getString("value");
                String re = allowed(arg);
                if (re == null) return result(false, "unknown key " + arg);
                if (v == null || !Pattern.matches(re, v)) return result(false, "bad value " + v + " for " + arg);
                if (!"1".equals(prop("ready"))) return result(false, "HiBy service (module) not running");
                boolean ok = setProp(arg, v);
                // LED settings (presets, mode, effect, brightness): draw at once. The LED code otherwise only
                // redraws on screen, battery and playback events, so a preset showed up seconds later.
                if (ok && arg.startsWith("led_")) Lights.changed();
                return result(ok, null);
            }
            case "eq_get": {
                Bundle b = new Bundle();
                String eq = Settings.Global.getString(getContext().getContentResolver(), Eq.KEY);
                b.putString("eq", eq == null ? "" : eq);
                return b;
            }
            case "eq_set": {
                String eq = extras == null ? null : extras.getString("eq");
                if (eq == null || !Pattern.matches("-?\\d+(,-?\\d+){13}", eq)) return result(false, "eq must be 14 integers");
                return result(Settings.Global.putString(getContext().getContentResolver(), Eq.KEY, eq), null);
            }
            case "led_live": {
                String c = extras == null ? null : extras.getString("color");
                if (c == null || !Pattern.matches("[0-9a-fA-F]{6}", c)) return result(false, "color must be rrggbb");
                if (!"custom".equals(prop("st_led"))) return result(false, "LED is not showing the custom colour");
                int br = extras.getInt("bright", 60);
                boolean breath = "breath".equals(extras.getString("effect", "solid"));
                return result(LedLive.draw(c.toLowerCase(), Math.max(0, Math.min(100, br)), breath), null);
            }
            // ---- SongateOS updates (see Updater). Status and check: any caller with the permission (a launcher may
            // show "update available"). Download, install and options: only Settings (uid system), where the user
            // sees the release notes and confirms.
            case "ota_status":
                return Updater.status(getContext());
            case "ota_check":
                return result(Updater.checkAsync(getContext(), true, null), null);
            case "ota_download": {
                if (!fromSystem()) return result(false, "no_permission: Only Songate can change system settings");
                String err = Updater.downloadAsync(getContext());
                return result(err == null, err);
            }
            case "ota_cancel":
                if (!fromSystem()) return result(false, "no_permission: Only Songate can change system settings");
                Updater.cancel();
                return result(true, null);
            case "ota_install": {
                if (!fromSystem()) return result(false, "no_permission: Only Songate can change system settings");
                String err = Updater.installAsync(getContext());
                return result(err == null, err);
            }
            case "ota_preflight": {
                String err = Updater.preflight(getContext());
                return result(err == null, err);
            }
            // ---- System colours (SYSTEM_API.md "System colours"). theme_set will move to the SYSTEM_SETTINGS permission
            // (Songate's certificate) with the rest of the system API; until then only Settings / the system may set.
            case "theme_get":
                return SystemTheme.get(getContext());
            case "theme_set": {
                if (!fromSystem()) return result(false, "no_permission: Only Songate can change system settings");
                String err = SystemTheme.set(getContext(), extras == null ? null : extras.getString("colour"),
                        extras == null ? null : extras.getString("dark"));
                return result(err == null, err);
            }
            case "reboot": {   // arg: system | recovery | recovery_check (recovery records itself and comes back)
                if (!fromSystem()) return result(false, "no_permission: Only Songate can change system settings");
                String err = Updater.rebootAsync(getContext(), arg);
                return result(err == null, err);
            }
            case "ota_set": {
                if (!fromSystem()) return result(false, "no_permission: Only Songate can change system settings");
                String v = extras == null ? null : extras.getString("value");
                return result(Updater.set(getContext(), arg, v), null);
            }
        }
        return result(false, "unknown method " + method);
    }

    /** Settings / the system, or Songate through the SYSTEM_SETTINGS permission (its certificate). */
    private boolean fromSystem() { return SystemApi.trusted(getContext()); }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        enforce();
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        Bundle b = all();
        for (String k : b.keySet()) c.addRow(new Object[]{k, b.getString(k)});
        return c;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }

    /** Same LED formula as the module (hiby_settings.sh) and the Settings page. */
    static final class LedLive {
        static final String FILE = "/sys/bus/i2c/devices/i2c-2/2-0030/leds/sgm31324-leds/write_pattern";

        static int level(int c, double k, int br) {
            if (c <= 0) return 0;
            return Math.max(1, (int) (96 * Math.pow(c / 255.0, 2.2) * k * br / 100 + 0.5));
        }

        static boolean draw(String hex, int br, boolean breath) {
            int r = Integer.parseInt(hex.substring(0, 2), 16), g = Integer.parseInt(hex.substring(2, 4), 16),
                    b = Integer.parseInt(hex.substring(4, 6), 16);
            int R = level(r, 1.0, br), G = level(g, 0.45, br), B = level(b, 0.8, br);
            int m = breath ? 2 : 1;
            int mr = R > 0 ? m : 0, mg = G > 0 ? m : 0, mb = B > 0 ? m : 0;
            int e = (mr + mg + mb) > 0 ? (0x40 | (mb << 4) | (mg << 2) | mr) : 0;
            String p = String.format("00%02X%02X%02X%02X%02X%02X%02X%02X06", breath ? 30 : 0, breath ? 125 : 0, 0, e,
                    breath ? 187 : 0, R, G, B);
            try (FileOutputStream o = new FileOutputStream(FILE)) {
                o.write((p + "\n").getBytes());
                return true;
            } catch (Exception ex) { return false; }
        }
    }
}
