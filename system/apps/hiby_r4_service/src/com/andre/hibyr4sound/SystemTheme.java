package com.andre.hibyr4sound;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.app.UiModeManager;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SongateOS system colours (design: SYSTEM_API.md "System colours").
 *
 * Android builds its system palette (Material You) from one colour, the "system_palette" in
 * Settings.Secure theme_customization_overlay_packages, and in dark mode shows only very light tones of it on tiles,
 * sliders and buttons (coral becomes pale peach). On top of that, this class lays an "exact colour" palette: the chosen
 * colour itself where dark mode shows its lightest accent, a dark tint of it for text on top, and warm blacks / creams
 * tinted with it for backgrounds and text. For SongateOS's coral #fb695a (the boot animation's) it uses the hand-tuned
 * values tested on the R4 (2026-11-16), with the boot animation's black #140809.
 *
 * The palette is a fabricated overlay owned by this app ("com.andre.hibyr4.service:SongateOSPalette"). Fabricated
 * overlays don't reliably survive a restart and SystemUI re-registers its own colour overlays whenever the theme
 * changes, so this class registers ours at every start, rebuilds it when the colour changes, and moves it back on top
 * when another overlay lands above it. Off switch: Settings.Global songateos_exact_palette = 0.
 */
public final class SystemTheme {
    static final String TAG = "SongateTheme";
    static final String SETTING = "theme_customization_overlay_packages";
    static final String KEY_PALETTE = "android.theme.customization.system_palette";
    static final String KEY_ACCENT = "android.theme.customization.accent_color";
    static final String KEY_STYLE = "android.theme.customization.theme_style";
    static final String KEY_SOURCE = "android.theme.customization.color_source";
    static final String KEY_FONT = "android.theme.customization.font";
    static final String FONT_INTER = "me.andre.hibyr4.overlay.font.inter";
    static final String OVERLAY_NAME = "SongateOSPalette";
    static final String TARGET = "android";
    static final int DEFAULT_COLOUR = 0xfffb695a;          // the boot animation's coral (branding/brand.py ACCENT)
    static final String OFF_SWITCH = "songateos_exact_palette";

    private static Context sApp;
    private static Handler sH;
    private static int sApplied = 0;                        // colour of the registered palette (0 = none)

    private SystemTheme() { }

    // ------------------------------------------------------------------ start / events
    static void start(Context c) {
        if (sApp != null) return;
        sApp = c.getApplicationContext();
        try { defaultsIfUnset(sApp); } catch (RuntimeException e) { Log.w(TAG, "defaults", e); }
        HandlerThread t = new HandlerThread("SystemTheme");
        t.start();
        sH = new Handler(t.getLooper());
        sApp.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(SETTING), false,
                new ContentObserver(sH) { @Override public void onChange(boolean self) { schedule(600); } });
        sApp.getContentResolver().registerContentObserver(Settings.Global.getUriFor(OFF_SWITCH), false,
                new ContentObserver(sH) { @Override public void onChange(boolean self) { schedule(0); } });
        // SystemUI re-registers its colour overlays on every theme change; they may land above ours
        IntentFilter f = new IntentFilter("android.intent.action.OVERLAY_CHANGED");   // Intent.ACTION_OVERLAY_CHANGED (hidden)
        f.addDataScheme("package");
        f.addDataSchemeSpecificPart(TARGET, 0 /* PatternMatcher.PATTERN_LITERAL */);
        sApp.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { sH.removeCallbacks(sTop); sH.postDelayed(sTop, 400); }
        }, f, null, sH, Context.RECEIVER_EXPORTED);
        schedule(3000);   // at start: after SystemUI has applied its own colours
    }

    private static final Runnable sApply = SystemTheme::applyNow;
    private static final Runnable sTop = SystemTheme::ensureOnTop;

    private static void schedule(long ms) {
        if (sH == null) return;
        sH.removeCallbacks(sApply);
        sH.postDelayed(sApply, ms);
    }

    // ------------------------------------------------------------------ API (provider: theme_get / theme_set)
    static Bundle get(Context c) {
        Bundle b = new Bundle();
        b.putString("colour", hex(currentColour(c)));
        b.putString("default", hex(DEFAULT_COLOUR));
        b.putString("dark", isDark(c) ? "1" : "0");
        b.putString("exact", Settings.Global.getInt(c.getContentResolver(), OFF_SWITCH, 1) != 0 ? "1" : "0");
        return b;
    }

    /** colour: "#rrggbb" or "default" (null = keep); dark: "0"/"1" (null = keep). Returns an error or null. */
    static String set(Context c, String colour, String dark) {
        if (colour == null && dark == null) return "bad_arg: nothing to change";
        if (colour != null) {
            int argb;
            if ("default".equals(colour)) argb = DEFAULT_COLOUR;
            else if (colour.matches("#[0-9a-fA-F]{6}")) argb = 0xff000000 | Integer.parseInt(colour.substring(1), 16);
            else return "bad_arg: colour must be #rrggbb or default";
            try {
                String cur = Settings.Secure.getString(c.getContentResolver(), SETTING);
                JSONObject o = cur == null || cur.isEmpty() ? new JSONObject() : new JSONObject(cur);
                String h = hex(argb).toUpperCase(Locale.ROOT);
                o.put(KEY_PALETTE, h);
                o.put(KEY_ACCENT, h);
                o.put(KEY_STYLE, "TONAL_SPOT");
                o.put(KEY_SOURCE, "preset");
                if (!o.has(KEY_FONT) && fontInstalled(c)) o.put(KEY_FONT, FONT_INTER);
                if (!Settings.Secure.putString(c.getContentResolver(), SETTING, o.toString())) return "failed: setting refused";
            } catch (Exception e) {
                return "failed: " + e.getMessage();
            }
        }
        if (dark != null) {
            if (!"0".equals(dark) && !"1".equals(dark)) return "bad_arg: dark must be 0 or 1";
            try {
                c.getSystemService(UiModeManager.class).setNightMode("1".equals(dark) ? UiModeManager.MODE_NIGHT_YES
                        : UiModeManager.MODE_NIGHT_NO);
            } catch (RuntimeException e) {
                return "failed: " + e.getMessage();
            }
        }
        return null;
    }

    /** First start ever on SongateOS: coral + Inter if nothing is set yet. Never overrides an existing choice. */
    static void defaultsIfUnset(Context c) {
        String cur = Settings.Secure.getString(c.getContentResolver(), SETTING);
        if (cur == null || cur.isEmpty()) {
            String err = set(c, "default", null);
            Log.i(TAG, "first start: system colour set to coral" + (err != null ? " (" + err + ")" : ""));
        }
    }

    static boolean isDark(Context c) {
        try { return c.getSystemService(UiModeManager.class).getNightMode() == UiModeManager.MODE_NIGHT_YES; }
        catch (RuntimeException e) { return true; }
    }

    static int currentColour(Context c) {
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), SETTING);
            if (cur == null || cur.isEmpty()) return DEFAULT_COLOUR;
            String p = new JSONObject(cur).optString(KEY_PALETTE, "");
            if (!p.startsWith("#")) p = "#" + p;
            if (p.matches("#[0-9a-fA-F]{6}")) return 0xff000000 | Integer.parseInt(p.substring(1), 16);
        } catch (Exception ignored) { }
        return DEFAULT_COLOUR;
    }

    static boolean fontInstalled(Context c) {
        try { c.getPackageManager().getPackageInfo(FONT_INTER, 0); return true; } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ the overlay
    private static void applyNow() {
        Context c = sApp;
        boolean on = Settings.Global.getInt(c.getContentResolver(), OFF_SWITCH, 1) != 0;
        try {
            OverlayManager om = c.getSystemService(OverlayManager.class);
            if (!on) {
                if (sApplied != 0) {
                    unregister();
                    sApplied = 0;
                    Log.i(TAG, "exact palette off");
                }
                return;
            }
            int colour = currentColour(c);
            FabricatedOverlay fo = newOverlay(palette(colour));
            // register + enable in one transaction (Builder.setEnabled is hidden: system app, reflection)
            Class<?> bc = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
            Object b = bc.getConstructor().newInstance();
            bc.getMethod("registerFabricatedOverlay", FabricatedOverlay.class).invoke(b, fo);
            bc.getMethod("setEnabled", OverlayIdentifier.class, boolean.class, int.class).invoke(b, fo.getIdentifier(), true, 0);
            om.commit((OverlayManagerTransaction) bc.getMethod("build").invoke(b));
            sApplied = colour;
            Log.i(TAG, "exact palette registered for " + hex(colour));
            SystemApi.notify("theme");
            ensureOnTop();
        } catch (Throwable t) {
            Log.w(TAG, "palette", t);
        }
    }

    /**
     * The public constructor leaves the owning package empty for this app (shared system uid), and OverlayManager then
     * refuses the commit ("UID 1000 does own packagename "). Set it explicitly, as `cmd overlay fabricate` does
     * (FabricatedOverlay.setOwningPackage is hidden: system app, reflection). Must happen before getIdentifier().
     */
    private static FabricatedOverlay newOverlay() throws Exception { return newOverlay(null); }

    /**
     * Two framework generations: newer builds have FabricatedOverlay.setOwningPackage; the R4's GSI (UQ1A, early 2024)
     * only has the hidden FabricatedOverlay.Builder(owningPackage, name, targetPackage), which is how SystemUI makes its
     * own colour overlays. Values (resource name -> ARGB colour) go in here so both paths fill the same overlay.
     */
    private static FabricatedOverlay newOverlay(Map<String, Integer> colours) throws Exception {
        String owner = sApp.getPackageName();
        try {
            FabricatedOverlay fo = new FabricatedOverlay(OVERLAY_NAME, TARGET);
            FabricatedOverlay.class.getMethod("setOwningPackage", String.class).invoke(fo, owner);
            if (colours != null) for (Map.Entry<String, Integer> e : colours.entrySet())
                fo.setResourceValue("android:color/" + e.getKey(), TypedValue.TYPE_INT_COLOR_ARGB8, e.getValue(), null);
            return fo;
        } catch (NoSuchMethodException older) {
            Class<?> bc = Class.forName("android.content.om.FabricatedOverlay$Builder");
            Object b = bc.getConstructor(String.class, String.class, String.class).newInstance(owner, OVERLAY_NAME, TARGET);
            Method set = bc.getMethod("setResourceValue", String.class, int.class, int.class);
            if (colours != null) for (Map.Entry<String, Integer> e : colours.entrySet())
                set.invoke(b, "android:color/" + e.getKey(), TypedValue.TYPE_INT_COLOR_ARGB8, e.getValue());
            return (FabricatedOverlay) bc.getMethod("build").invoke(b);
        }
    }

    /**
     * Remove our overlay. Through the hidden Builder like the registration: the public
     * OverlayManagerTransaction.newInstance() is for an app's overlays on itself and refuses a target like "android"
     * ("Can't work for this context").
     */
    private static void unregister() throws Exception {
        Class<?> bc = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
        Object b = bc.getConstructor().newInstance();
        bc.getMethod("unregisterFabricatedOverlay", OverlayIdentifier.class).invoke(b, newOverlay().getIdentifier());
        sApp.getSystemService(OverlayManager.class).commit((OverlayManagerTransaction) bc.getMethod("build").invoke(b));
    }

    private static long sMoveWindow = 0;
    private static int sMoves = 0;

    /**
     * Our overlay must be the last (highest) overlay on "android". IOverlayManager.setHighestPriority takes a package
     * name and can't find a fabricated overlay by its id on the R4's build ("Unable to retrieve overlay information"),
     * but overlays registered or enabled later land higher. So when something sits above ours (SystemUI re-applies its
     * colour overlays after every theme change), ours is unregistered and registered again. At most 3 times a minute,
     * so this can never loop if a build orders overlays differently.
     */
    private static void ensureOnTop() {
        if (sApplied == 0) return;
        try {
            Object iom = iOverlayManager();
            @SuppressWarnings("unchecked")
            List<OverlayInfo> list = (List<OverlayInfo>) iom.getClass()
                    .getMethod("getOverlayInfosForTarget", String.class, int.class).invoke(iom, TARGET, 0);
            if (list == null || list.isEmpty()) return;
            // the highest ENABLED overlay (the list also holds disabled ones, e.g. unused navbar variants)
            Method enabled = OverlayInfo.class.getMethod("isEnabled");
            OverlayInfo last = null;
            for (int i = list.size() - 1; i >= 0 && last == null; i--)
                if ((Boolean) enabled.invoke(list.get(i))) last = list.get(i);
            if (last == null) return;
            if (OVERLAY_NAME.equals(last.getOverlayName())
                    && String.valueOf(last.getOverlayIdentifier()).contains(sApp.getPackageName())) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sMoveWindow > 60_000) { sMoveWindow = now; sMoves = 0; }
            if (++sMoves > 3) { Log.w(TAG, "palette not on top, giving up for this minute"); return; }
            Log.i(TAG, "palette below " + last.getOverlayIdentifier() + ": registering it again on top");
            unregister();
            sApplied = 0;
            applyNow();
        } catch (Throwable t) {
            Log.w(TAG, "priority", t);
        }
    }

    private static Object iOverlayManager() throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "overlay");
        return Class.forName("android.content.om.IOverlayManager$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, b);
    }

    // ------------------------------------------------------------------ palettes
    /** Resource name (without "android:color/") -> ARGB. Shared tone ramps serve both modes; named colours per mode. */
    static Map<String, Integer> palette(int seed) {
        return (seed & 0xffffff) == (DEFAULT_COLOUR & 0xffffff) ? coral() : generated(seed);
    }

    /** SongateOS coral: the values tested on the R4 (work/theme_test/songateos_palette_dark.xml) + light mode. */
    private static Map<String, Integer> coral() {
        Map<String, Integer> m = new LinkedHashMap<>();
        int[] a = { 0xfffff7f5, 0xffffe9e5, 0xfffb695a, 0xffff8f81, 0xfffb695a, 0xfff04f40, 0xffd93a2c, 0xffb42b20,
                    0xff8a1f17, 0xff5e1510, 0xff2a0b08 };
        int[] n = { 0xfffdf7f4, 0xfff9f2ec, 0xffefe5df, 0xffd6c8c2, 0xffb9aaa4, 0xff9a8b86, 0xff7c6d69, 0xff5f514e,
                    0xff3d2e2c, 0xff241615, 0xff140809, 0xff0a0304 };
        ramps(m, a, n);
        named(m, a, n, new int[]{ 0xff0e0405, 0xff1b0d0d, 0xff221312, 0xff2b1a18, 0xff35231f, 0xff3d2a26 },
                new int[]{ 0xffffffff, 0xfffcf5f0, 0xfff6ede7, 0xfff0e6e0, 0xffeadfd8, 0xfffdf7f4 });
        return m;
    }

    private static final int[] ACCENT_TONES = { 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900 };
    private static final int[] NEUTRAL_TONES = { 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000 };

    /**
     * Any other colour, same rules as coral: the colour itself at tone 100/300 (dark mode's tiles and buttons), kept
     * between OKLCH lightness 0.62 and 0.80 so text stays readable on it; lighter and darker steps around it at the same
     * hue; tone 900 = dark tint for text on it (at least 4.5:1); neutrals = blacks and creams tinted with the hue.
     */
    private static Map<String, Integer> generated(int seed) {
        double[] s = Ok.lch(seed);
        double h = s[2], cs = Math.min(s[1], 0.26), l0 = Math.max(0.62, Math.min(0.80, s[0]));
        int base = Ok.rgb(l0, cs, h);
        int[] a = new int[11];
        a[0] = Ok.rgb(0.985, cs * 0.06, h);
        a[1] = Ok.rgb(0.95, cs * 0.18, h);
        a[2] = base;
        a[3] = Ok.rgb(Math.min(0.9, l0 + 0.08), cs * 0.8, h);
        a[4] = base;
        a[5] = Ok.rgb(l0 - 0.06, cs, h);
        a[6] = Ok.rgb(l0 - 0.13, cs, h);
        a[7] = Ok.rgb(l0 - 0.21, cs * 0.95, h);
        a[8] = Ok.rgb(l0 - 0.30, cs * 0.85, h);
        a[9] = Ok.rgb(l0 - 0.38, cs * 0.7, h);
        double l9 = 0.22;
        a[10] = Ok.rgb(l9, Math.min(cs, 0.06), h);
        while (Ok.contrast(a[10], base) < 4.5 && l9 > 0.08) { l9 -= 0.02; a[10] = Ok.rgb(l9, Math.min(cs, 0.06), h); }
        double cn = Math.min(0.025, cs * 0.15);
        double[] nl = { 0.985, 0.962, 0.925, 0.84, 0.75, 0.65, 0.56, 0.47, 0.33, 0.235, 0.155, 0.11 };
        int[] n = new int[12];
        for (int i = 0; i < 12; i++) n[i] = Ok.rgb(nl[i], cn * (i < 2 ? 0.6 : 1), h);
        double[] dl = { 0.13, 0.18, 0.205, 0.235, 0.265, 0.29 }, ll = { 1.0, 0.975, 0.955, 0.94, 0.925, 0.985 };
        int[] dark = new int[6], light = new int[6];
        for (int i = 0; i < 6; i++) { dark[i] = Ok.rgb(dl[i], cn, h); light[i] = Ok.rgb(ll[i], cn * 0.5, h); }
        Map<String, Integer> m = new LinkedHashMap<>();
        ramps(m, a, n);
        named(m, a, n, dark, light);
        return m;
    }

    private static void ramps(Map<String, Integer> m, int[] a, int[] n) {
        for (int i = 0; i < ACCENT_TONES.length; i++) m.put("system_accent1_" + ACCENT_TONES[i], a[i]);
        for (int i = 0; i < NEUTRAL_TONES.length; i++) {
            m.put("system_neutral1_" + NEUTRAL_TONES[i], n[i]);
            m.put("system_neutral2_" + NEUTRAL_TONES[i], n[i]);
        }
    }

    /**
     * Android 14's named colours. dark[] = surface containers lowest, low, (container), high, highest, bright;
     * light[] = the same for light mode. a[] index: 2 = the colour, 7 = darker (light mode text/buttons), 10 = text on it.
     */
    private static void named(Map<String, Integer> m, int[] a, int[] n, int[] dark, int[] light) {
        int bg = n[10], cream = n[1];
        m.put("system_primary_dark", a[2]);
        m.put("system_on_primary_dark", a[10]);
        m.put("system_primary_container_dark", a[2]);
        m.put("system_on_primary_container_dark", a[10]);
        m.put("system_primary_fixed", a[2]);
        m.put("system_on_primary_fixed", a[10]);
        m.put("system_background_dark", bg);
        m.put("system_surface_dark", bg);
        m.put("system_surface_dim_dark", bg);
        m.put("system_surface_container_lowest_dark", dark[0]);
        m.put("system_surface_container_low_dark", dark[1]);
        m.put("system_surface_container_dark", dark[2]);
        m.put("system_surface_container_high_dark", dark[3]);
        m.put("system_surface_container_highest_dark", dark[4]);
        m.put("system_surface_bright_dark", dark[5]);
        m.put("system_on_surface_dark", cream);
        m.put("system_on_background_dark", cream);
        // light mode (Songate's Light): cream backgrounds, the colour on containers, a darker step for text on cream
        int onCream = Ok.contrast(a[7], cream) >= 4.5 ? a[7] : a[8];
        m.put("system_primary_light", onCream);
        m.put("system_on_primary_light", 0xffffffff);
        m.put("system_primary_container_light", a[2]);
        m.put("system_on_primary_container_light", a[10]);
        m.put("system_background_light", cream);
        m.put("system_surface_light", cream);
        m.put("system_surface_bright_light", light[5]);
        m.put("system_surface_container_lowest_light", light[0]);
        m.put("system_surface_container_low_light", light[1]);
        m.put("system_surface_container_light", light[2]);
        m.put("system_surface_container_high_light", light[3]);
        m.put("system_surface_container_highest_light", light[4]);
        m.put("system_on_surface_light", bg);
        m.put("system_on_background_light", bg);
    }

    static String hex(int argb) { return String.format("#%06x", argb & 0xffffff); }

    /** OKLab / OKLCH (Björn Ottosson) with sRGB gamut mapping by reducing chroma. */
    static final class Ok {
        static double lin(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
        static double gam(double c) { return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055; }

        static double[] lch(int argb) {
            double r = lin(((argb >> 16) & 255) / 255.0), g = lin(((argb >> 8) & 255) / 255.0), b = lin((argb & 255) / 255.0);
            double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
            double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
            double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
            double L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
            double A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
            double B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
            return new double[]{ L, Math.hypot(A, B), Math.atan2(B, A) };
        }

        /** linear rgb of (L, C, h), may be out of gamut */
        static double[] toLin(double L, double C, double h) {
            double A = C * Math.cos(h), B = C * Math.sin(h);
            double l = L + 0.3963377774 * A + 0.2158037573 * B, m = L - 0.1055613458 * A - 0.0638541728 * B,
                    s = L - 0.0894841775 * A - 1.2914855480 * B;
            l = l * l * l; m = m * m * m; s = s * s * s;
            return new double[]{ 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s };
        }

        static boolean in(double[] c) {
            for (double v : c) if (v < -1e-4 || v > 1 + 1e-4) return false;
            return true;
        }

        static int rgb(double L, double C, double h) {
            L = Math.max(0, Math.min(1, L));
            double lo = 0, hi = C;
            double[] c = toLin(L, C, h);
            if (!in(c)) {
                for (int i = 0; i < 24; i++) { double mid = (lo + hi) / 2; if (in(toLin(L, mid, h))) lo = mid; else hi = mid; }
                c = toLin(L, lo, h);
            }
            int r = (int) Math.round(Math.max(0, Math.min(1, gam(c[0]))) * 255),
                    g = (int) Math.round(Math.max(0, Math.min(1, gam(c[1]))) * 255),
                    b = (int) Math.round(Math.max(0, Math.min(1, gam(c[2]))) * 255);
            return 0xff000000 | r << 16 | g << 8 | b;
        }

        static double lum(int argb) {
            return 0.2126 * lin(((argb >> 16) & 255) / 255.0) + 0.7152 * lin(((argb >> 8) & 255) / 255.0)
                    + 0.0722 * lin((argb & 255) / 255.0);
        }

        static double contrast(int x, int y) {
            double a = lum(x), b = lum(y);
            return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
        }
    }
}
