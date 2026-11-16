package com.andre.hibyr4sound;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileOutputStream;
import java.util.List;

/**
 * Both lights of the R4 while LED mode = "custom" (in mode "hiby" HiBy's init rules drive them, in "off" the module).
 *
 * Colour LED (front, SGM31324 RGB + a white LED in the same spot):
 *   write_pattern = 10 register bytes "00 t1 t2 00 E t5 R G B 06": E = 0x40 | b<<4 | g<<2 | r (1 = on, 2 = breathing),
 *   t1/t2/t5 = breathing timing, R/G/B levels (max 0x60 as HiBy). led_pattern = HiBy's table (from the device tree);
 *   pattern 5 = the white LED (its own GPIO), pattern 0 = all off - the only way to switch the white LED off again
 *   (a raw write_pattern leaves it on: tested 2026-11-15). The driver ignores an index equal to its last one -> 0 first.
 *   Priority: low battery > notification > charging > off with screen > beat pulse / audio quality colour > own colour.
 *
 * Back light (white, behind the three orange strips) = gpio-leds "ambience-gpio", on/off only (soc:leds/led_enable):
 *   Priority: beat flash > notification > charging blink > base (screen | on | off | music).
 *
 * Settings come from sys.hibyr4.* (saved by the module); the module sends a broadcast after every LED setting change.
 * The module relabels the three LED files (hibyr4_led_file, owner system) so this system app may write them.
 */
public class Lights extends BroadcastReceiver {
    static final String TAG = "HiByR4Lights";
    private static final String LED = "/sys/bus/i2c/devices/i2c-2/2-0030/leds/sgm31324-leds/";
    private static final String RAW = LED + "write_pattern", IDX = LED + "led_pattern";
    private static final String BACK = "/sys/devices/platform/soc/soc:leds/led_enable";
    private static final String OFF = "00000000000000000006";
    private static final long BLINK_MS = 1000, QUALITY_POLL_MS = 1500;

    private static Context sCtx;
    private static Handler sH;
    private static AudioManager sAm;
    private static PowerManager.WakeLock sWake;
    private static boolean sScreenOn = true, sPlaying, sPlugged, sFull, sNotifPending;
    private static int sBattery = 100;

    // ---------- start / events ----------
    @Override
    public void onReceive(Context c, Intent i) { if (sH != null) sH.post(Lights::update); }

    static void start(Context c) {
        sCtx = c.getApplicationContext();
        sH = new Handler(Looper.getMainLooper());
        sAm = sCtx.getSystemService(AudioManager.class);
        PowerManager pm = sCtx.getSystemService(PowerManager.class);
        sWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hibyr4:backlight_blink");
        sWake.setReferenceCounted(false);
        sScreenOn = pm.isInteractive();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        Intent b = sCtx.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a)) { sScreenOn = true; sNotifPending = false; }
                else if (Intent.ACTION_SCREEN_OFF.equals(a)) sScreenOn = false;
                else battery(i);
                update();
            }
        }, f, null, sH);
        if (b != null) battery(b);

        sAm.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) { sH.post(Lights::update); }
        }, sH);
        update();
    }

    private static void battery(Intent i) {
        sPlugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        sFull = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_FULL;
        int lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (lv >= 0 && sc > 0) sBattery = lv * 100 / sc;
    }

    /** From the notification listener. */
    static void onNotification() {
        if (sH == null) return;
        sH.post(() -> {
            if (!sScreenOn && (on("led_notif") || on("led_back_notif"))) { sNotifPending = true; update(); }
        });
    }

    private static String p(String key) { return HiBySettingsProvider.prop(key); }
    private static boolean on(String key) { return "1".equals(p(key)); }
    private static int pint(String key, int def) { try { return Integer.parseInt(p(key)); } catch (Exception e) { return def; } }
    private static String hex(String key, String def) { String v = p(key); return v.matches("[0-9a-fA-F]{6}") ? v.toLowerCase() : def; }

    // ---------- main decision (main thread) ----------
    static void update() {
        if (sCtx == null) return;
        sH.removeCallbacks(TICK);
        boolean custom = "custom".equals(p("led_mode"));
        sPlaying = sAm.isMusicActive();
        syncNotifAccess();
        if (!custom) {
            stopBeat(); releaseWake();
            sRaw = null; sWhite = null; sBack = null; sColourMode = ""; sBackMode = "";
            return;
        }
        boolean beatLed = on("led_beat") && sScreenOn && sPlaying;
        boolean beatBack = on("led_back_beat") && sScreenOn && sPlaying;
        boolean beat = (beatLed || beatBack) && startBeat();
        if (!beat) stopBeat();
        sBeatLed = beat && beatLed;
        sBeatBack = beat && beatBack;

        boolean needTick = colour() | back();
        if (needTick) sH.postDelayed(TICK, sPlaying ? Math.min(BLINK_MS, QUALITY_POLL_MS) : BLINK_MS);
    }

    private static final Runnable TICK = Lights::update;

    // ---------- colour LED ----------
    private static String sRaw;          // last raw pattern written (null = unknown)
    private static Boolean sWhite;       // white LED state (null = unknown)
    private static int sIdx = -1;        // last HiBy index written
    private static String sColourMode = "";
    private static boolean sBeatLed;
    private static String sBeatColour = "ffffff";

    /** Returns true when a periodic re-check is needed (audio quality colour while playing). */
    private static boolean colour() {
        String own = hex("led_color", "00a0ff");
        int br = pint("led_bright", 60);
        boolean ownBreath = "breath".equals(p("led_effect"));

        if (on("led_lowbat") && !sPlugged && sBattery <= pint("led_lowbat_level", 15)) {
            setColourMode("low battery"); raw(pattern("ff0000", br, 1)); return false;
        }
        if (sNotifPending && on("led_notif")) {
            setColourMode("notification"); raw(pattern(hex("led_notif_color", "4fc3ff"), br, 2)); return false;
        }
        if (on("led_charge") && sPlugged && !sFull) {
            setColourMode("charging"); index(6); return false;
        }
        if (on("led_screen") && !sScreenOn) { setColourMode("off with screen"); raw(OFF); return false; }

        String c = own; boolean breath = ownBreath; boolean poll = false; String what = "own colour";
        if (on("led_quality") && sPlaying) {
            poll = true;
            // DSD: only HiBy's driver can tell (it reports "dsd" for its own DSD path); everything else from the
            // sample rate Android reports for the playing track (works for any app and any output, Bluetooth too)
            if ("dsd".equals(vendorProp("vendor.audio.hiby.hw.sample_quality"))) {
                if (!sBeatLed) { setColourMode("quality: DSD (white LED)"); white(); return true; }
                c = "ffffff"; breath = false; what = "quality: DSD";
            } else {
                int rate = playingRate();
                if (rate > 192000) { c = hex("led_q_ultra", "ff3c8c"); what = "quality: " + rate + " Hz"; }
                else if (rate > 48000) { c = hex("led_q_hires", "ff3c30"); what = "quality: " + rate + " Hz"; }
                else if (rate > 0) { c = hex("led_q_cd", "ff9030"); what = "quality: " + rate + " Hz"; }
                if (rate > 0) breath = false;
            }
        }

        if (sBeatLed) {                  // dim between beats, full brightness on the beat (beat() writes the peaks)
            sBeatColour = c; sBeatBright = br;
            // bass() draws the brightness continuously from the audio
            setColourMode("beat pulse, " + what);
            return poll;
        }
        setColourMode(what);
        raw(pattern(c, br, breath ? 3 : 0));
        // Settings draws the colour itself while the wheel is dragged only when our own colour is showing
        String st = c.equals(own) ? "custom" : "other";
        if (!st.equals(p("st_led"))) HiBySettingsProvider.setProp("st_led", st);
        return poll;
    }

    private static int sBeatBright = 60;

    private static void setColourMode(String m) {
        if (!m.equals(sColourMode)) { Log.i(TAG, "colour LED: " + m); sColourMode = m; }
        if (!m.equals("own colour") && !"other".equals(p("st_led"))) HiBySettingsProvider.setProp("st_led", "other");
    }

    /**
     * Pattern bytes. fx: 0 solid, 1 slow breathing (low battery), 2 quick breathing (notification),
     * 3 HiBy's breathing timing (the "Breathing" effect, same as hiby_settings.sh).
     */
    static String pattern(String hex, int br, int fx) {
        int r = Integer.parseInt(hex.substring(0, 2), 16), g = Integer.parseInt(hex.substring(2, 4), 16),
                b = Integer.parseInt(hex.substring(4, 6), 16);
        int R = level(r, 1.0, br), G = level(g, 0.45, br), B = level(b, 0.8, br);
        int t1 = 0, t2 = 0, t5 = 0;
        if (fx == 1) { t1 = 0x1E; t2 = 0x7D; t5 = 0xBB; }          // HiBy's charging breath
        else if (fx == 2) { t1 = 0x0E; t2 = 0x7D; t5 = 0xBB; }     // HiBy's fast-charging breath
        else if (fx == 3) { t1 = 30; t2 = 125; t5 = 187; }          // the custom "Breathing" effect
        int m = fx == 0 ? 1 : 2;
        int mr = R > 0 ? m : 0, mg = G > 0 ? m : 0, mb = B > 0 ? m : 0;
        int e = (mr + mg + mb) > 0 ? (0x40 | (mb << 4) | (mg << 2) | mr) : 0;
        return String.format("00%02X%02X%02X%02X%02X%02X%02X%02X06", t1, t2, 0, e, t5, R, G, B);
    }

    private static int level(int c, double k, int br) {
        if (c <= 0) return 0;
        int v = (int) (96 * Math.pow(c / 255.0, 2.2) * k * br / 100 + 0.5);
        return Math.max(1, v);
    }

    private static void raw(String pat) {
        if (Boolean.TRUE.equals(sWhite) || sWhite == null) { index(0); sWhite = false; }
        if (pat.equals(sRaw)) return;
        if (put(RAW, pat + "\n")) { sRaw = pat; sIdx = -1; } else sRaw = null;
    }

    /** HiBy table index (0 first: the driver ignores the same index twice). */
    private static void index(int i) {
        if (i == sIdx) return;
        if (i != 0) put(IDX, "0");
        put(IDX, Integer.toString(i));
        sIdx = i; sRaw = null;
        sWhite = i == 5;
    }

    private static void white() { if (!Boolean.TRUE.equals(sWhite) || sIdx != 5) index(5); }

    /**
     * Highest sample rate among the started media players (AudioPlaybackConfiguration.getSampleRate(), hidden API;
     * this platform-signed system app may use it). 0 = unknown.
     */
    private static int playingRate() {
        int best = 0;
        try {
            for (AudioPlaybackConfiguration c : sAm.getActivePlaybackConfigurations()) {
                if (c.getAudioAttributes().getUsage() != android.media.AudioAttributes.USAGE_MEDIA) continue;
                if ((Integer) AudioPlaybackConfiguration.class.getMethod("getPlayerState").invoke(c) != 2) continue; // STARTED
                // Android 14 (LineageOS 21): getSampleRate() (hidden, from FormatInfo; checked on the R4 2026-11-15)
                best = Math.max(best, (Integer) AudioPlaybackConfiguration.class.getMethod("getSampleRate").invoke(c));
            }
        } catch (Throwable t) {
            if (!sRateWarned) { Log.w(TAG, "sample rate unavailable: " + t); sRateWarned = true; }
        }
        return best;
    }

    private static boolean sRateWarned;

    private static String vendorProp(String k) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
                    .invoke(null, k, "");
        } catch (Throwable t) { return ""; }
    }

    // ---------- back light ----------
    private static Boolean sBack;
    private static boolean sBlinkPhase, sBeatBack;
    private static String sBackMode = "";

    /** Returns true when the charging blink needs the next tick. */
    private static boolean back() {
        if (sBeatBack) { releaseWake(); backMode("beat"); return false; }   // beat() drives it
        if (sNotifPending && on("led_back_notif")) { releaseWake(); backMode("notification"); backWrite(true); return false; }
        if (sPlugged && on("led_back_charge")) {
            if (sFull) { releaseWake(); backMode("charged"); backWrite(true); return false; }
            backMode("charging");
            if (!sScreenOn) sWake.acquire(10 * 60 * 1000L);   // blink in sleep too (only while plugged in)
            sBlinkPhase = !sBlinkPhase; backWrite(sBlinkPhase);
            return true;
        }
        releaseWake();
        String base = p("led_back");
        boolean v;
        switch (base) {
            case "on": v = true; break;
            case "off": v = false; break;
            case "music": v = sPlaying; break;
            default: v = sScreenOn; base = "screen"; break;
        }
        backMode(base);
        backWrite(v);
        return false;
    }

    private static void backMode(String m) { if (!m.equals(sBackMode)) { Log.i(TAG, "back light: " + m); sBackMode = m; } }

    private static void releaseWake() { if (sWake != null && sWake.isHeld()) sWake.release(); }

    private static void backWrite(boolean v) {
        if (sBack != null && sBack == v) return;
        sBack = put(BACK, v ? "on" : "off") ? v : null;
    }

    private static boolean put(String file, String s) {
        try (FileOutputStream o = new FileOutputStream(file)) {
            o.write(s.getBytes());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "write " + file + ": " + e);
            return false;
        }
    }

    // ---------- beat detection (shared) ----------
    private static Visualizer sVis;

    private static boolean startBeat() {
        if (sVis != null) return true;
        try {
            Visualizer vis = new Visualizer(0);      // session 0 = the output mix
            vis.setEnabled(false);
            int[] r = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, r[1]));
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] w, int rate) { bass(w, v.getSamplingRate()); }
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int rate) { }
            }, Visualizer.getMaxCaptureRate(), true, false);
            vis.setEnabled(true);
            sVis = vis; sEnv = 0; sPeak = 0; sFloor = 0; sLp = 0; sShown = 0; sBackHit = false;
            Log.i(TAG, "bass follower on (capture " + vis.getCaptureSize() + ")");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Visualizer unavailable: " + t);
            return false;
        }
    }

    private static void stopBeat() {
        if (sVis == null) return;
        try { sVis.setEnabled(false); sVis.release(); } catch (Throwable ignored) { }
        sVis = null;
        sH.removeCallbacks(SECOND_STEP);
        sBackHit = false;
        Log.i(TAG, "bass follower off");
    }

    // v3 (user: "smooth dimming and lighting up with the beat, not abrupt, follow the bass"): no beat events any more.
    // The colour LED brightness follows the bass envelope continuously: low-pass (~150 Hz) the waveform, RMS per
    // capture, scale between the track's recent quiet floor and loud peak, fast attack / slow release, and one
    // interpolated step between captures (~40 updates/s). The back light (on/off only) lights on the strongest hits.
    private static double sEnv, sPeak, sFloor, sLp, sShown;
    private static boolean sBackHit;
    private static long sBackOnAt;
    private static final double MIN_LEVEL = 0.12;           // LED never fully dark while pulsing (fraction of brightness)

    /** Waveform bytes are unsigned 8-bit (128 = silence); sampling rate in mHz. Main thread. */
    private static void bass(byte[] w, int samplingRateMilliHz) {
        double fs = samplingRateMilliHz / 1000.0;
        if (fs <= 0) fs = 48000;
        double a = 1 - Math.exp(-2 * Math.PI * 150 / fs);   // one-pole low-pass at ~150 Hz (two passes = steeper)
        double lp1 = sLp, lp2 = sLp, sum = 0;
        for (byte b : w) {
            double x = ((b & 0xff) - 128) / 128.0;
            lp1 += a * (x - lp1);
            lp2 += a * (lp1 - lp2);
            sum += lp2 * lp2;
        }
        sLp = lp2;
        double rms = Math.sqrt(sum / Math.max(1, w.length));

        // recent loud peak (decays over ~3 s) and quiet floor (rises slowly over ~2 s): the range we map to 0..1
        sPeak = Math.max(rms, sPeak * 0.985);
        sFloor = rms < sFloor ? sFloor + (rms - sFloor) * 0.5 : sFloor + (rms - sFloor) * 0.02;
        double range = Math.max(sPeak - sFloor, 0.01);
        double target = Math.max(0, Math.min(1, (rms - sFloor) / range));
        target = target * target * (3 - 2 * target);          // smoothstep: soft at both ends

        // fast attack, slow release
        sEnv += (target - sEnv) * (target > sEnv ? 0.65 : 0.18);

        if (sBeatLed) {
            double from = sShown, to = sEnv;
            showLevel((from + to) / 2);                       // half step now, the rest ~25 ms later
            sH.removeCallbacks(SECOND_STEP);
            sNext = to;
            sH.postDelayed(SECOND_STEP, 25);
        }
        if (sBeatBack) {
            long now = SystemClock.uptimeMillis();
            if (!sBackHit && sEnv > 0.8) { sBackHit = true; sBackOnAt = now; backWrite(true); }
            else if (sBackHit && sEnv < 0.45 && now - sBackOnAt > 80) { sBackHit = false; backWrite(false); }
            else if (!sBackHit) backWrite(false);
        }
    }

    private static double sNext;
    private static final Runnable SECOND_STEP = () -> { if (sVis != null && sBeatLed) showLevel(sNext); };

    private static void showLevel(double lv) {
        sShown = lv;
        int br = (int) Math.round(sBeatBright * (MIN_LEVEL + (1 - MIN_LEVEL) * lv));
        raw(pattern(sBeatColour, Math.max(1, br), 0));
    }

    // ---------- notification access for our listener ----------
    private static Boolean sNotifGranted;

    /** Grants/revokes this app's own notification listener to follow the two notification options. */
    private static void syncNotifAccess() {
        boolean want = "custom".equals(p("led_mode")) && (on("led_notif") || on("led_back_notif"));
        if (sNotifGranted != null && sNotifGranted == want) return;
        try {
            NotificationManager nm = sCtx.getSystemService(NotificationManager.class);
            ComponentName cn = new ComponentName(sCtx, LightsNotif.class);
            NotificationManager.class.getMethod("setNotificationListenerAccessGranted", ComponentName.class, boolean.class)
                    .invoke(nm, cn, want);
            Log.i(TAG, "notification listener " + (want ? "on" : "off"));
        } catch (Throwable t) {
            Log.w(TAG, "notification access: " + t);
        }
        sNotifGranted = want;
        if (!want) sNotifPending = false;
    }
}
