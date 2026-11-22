package com.andre.hibyr4sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root (Magisk) for SongateOS users who want it: install / update and removal through the SongateOS update channel.
 *  - Only Magisk's official GitHub releases (topjohnwu/Magisk, latest); the APK must be com.topjohnwu.magisk signed
 *    with topjohnwu's certificate (MAGISK_CERT). Checked here before anything is handed on.
 *  - The module's root helper (ota_bridge.sh) does the rest with Magisk's own tools from that APK: install = Magisk's
 *    boot_patch.sh on the current boot partition of the running slot ("direct install"), written and read back, the Magisk app installed;
 *    removal = the exact stock boot image back (Magisk's backup, our copy, or Magisk's ramdisk restore).
 *  - A restart finishes either. SongateOS itself doesn't need Magisk.
 * "Keep root after updates": SongateOS updates don't replace the boot partition, so Magisk stays; the option is kept for updates
 * that will (then the helper re-patches after the update).
 * API: root_get, root_check, root_install, root_remove; observer /system/root.
 */
final class MagiskRoot {
    static final String TAG = "SongateRoot";
    static final String PKG = "com.topjohnwu.magisk";
    /** SHA-256 of topjohnwu's signing certificate (CN=John Wu), from the official Magisk 31.1 APK. */
    static final String MAGISK_CERT = "b4cb83b4dad99f997dbe872f013aa16c14eec41d167021f371f7e1330f273ee6";
    private static final Pattern APK = Pattern.compile("Magisk-v([0-9]+(?:\\.[0-9]+)*)\\.apk");
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static volatile boolean sBusy;

    private MagiskRoot() { }

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("root", Context.MODE_PRIVATE);
    }

    private static void state(Context c, String s, String err) {
        prefs(c).edit().putString("state", s).putString("error", err == null ? "" : err).apply();
        SystemApi.notify("root");
    }

    /** At service start: a pending "restart" is finished by the restart that started us. */
    static void start(Context c) {
        SharedPreferences p = prefs(c);
        if ("restart".equals(p.getString("state", ""))) p.edit().putString("state", "idle").apply();
    }

    static Bundle get(Context c) {
        SharedPreferences p = prefs(c);
        Bundle b = SystemApi.ok();
        String v = Updater.sysprop("sys.hibyr4.magisk", "");
        b.putString("installed", v.isEmpty() ? "0" : "1");
        b.putString("version", v);
        b.putString("latest", p.getString("latest", ""));
        b.putString("keep", p.getBoolean("keep", true) ? "1" : "0");
        b.putString("state", p.getString("state", "idle"));   // idle checking downloading verifying installing removing restart error
        b.putString("error", p.getString("error", ""));
        b.putString("dry_sha256", p.getString("dry_sha256", ""));
        return b;
    }

    static Bundle set(Context c, Bundle e) {
        String k = e == null ? null : e.getString("keep");
        if (!"0".equals(k) && !"1".equals(k)) return SystemApi.err("bad_arg", "keep must be 0 or 1");
        prefs(c).edit().putBoolean("keep", "1".equals(k)).apply();
        SystemApi.notify("root");
        return get(c);
    }

    static Bundle checkAsync(Context c) {
        if (sBusy) return SystemApi.err("busy", "Busy, try again in a moment");
        sBusy = true;
        Context app = c.getApplicationContext();
        EX.execute(() -> { try { check(app); } finally { sBusy = false; } });
        return SystemApi.ok();
    }

    private static void check(Context c) {
        state(c, "checking", null);
        try {
            JSONObject r = new JSONObject(Updater.httpText("https://api.github.com/repos/topjohnwu/Magisk/releases/latest"));
            JSONArray as = r.optJSONArray("assets");
            String ver = null, url = null; long size = 0;
            if (as != null) for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.getJSONObject(i);
                Matcher m = APK.matcher(a.optString("name"));
                if (m.matches()) { ver = m.group(1); url = a.optString("browser_download_url"); size = a.optLong("size"); }
            }
            if (ver == null) { state(c, "error", "No Magisk APK in its latest release."); return; }
            prefs(c).edit().putString("latest", ver).putString("url", url).putLong("size", size).apply();
            state(c, "idle", null);
        } catch (Exception e) {
            Log.w(TAG, "check", e);
            state(c, "error", "Couldn't look for Magisk: " + msg(e));
        }
    }

    static Bundle installAsync(Context c) {
        if (sBusy) return SystemApi.err("busy", "Busy, try again in a moment");
        if (prefs(c).getString("url", "").isEmpty()) return SystemApi.err("not_found", "Look for Magisk first");
        sBusy = true;
        Context app = c.getApplicationContext();
        EX.execute(() -> { try { download(app); } finally { sBusy = false; } });
        return SystemApi.ok();
    }

    /** Development: install from an APK already on the R4 (same certificate check). System uid only. */
    static Bundle installFileAsync(Context c, String path) {
        if (sBusy) return SystemApi.err("busy", "Busy");
        File f = path == null ? null : new File(path);
        if (f == null || !f.isFile()) return SystemApi.err("bad_arg", "No such file");
        sBusy = true;
        Context app = c.getApplicationContext();
        EX.execute(() -> { try { install(app, f); } finally { sBusy = false; } });
        return SystemApi.ok();
    }

    /** dry = only build the stock boot image and report its sha256 (nothing written). */
    static Bundle removeAsync(Context c, boolean dry) {
        if (sBusy) return SystemApi.err("busy", "Busy, try again in a moment");
        if (Updater.sysprop("sys.hibyr4.magisk", "").isEmpty()) return SystemApi.err("not_installed", "Magisk isn't installed");
        sBusy = true;
        Context app = c.getApplicationContext();
        EX.execute(() -> {
            try {
                state(app, "removing", null);
                writeCmd(app, Updater.sysprop("sys.hibyr4.magisk", "0"), "0000000000000000000000000000000000000000000000000000000000000000",
                        dry ? "dry" : "remove");
                String res = ask(app, "magisk_remove");
                if (res.startsWith("mag_dry:")) {
                    prefs(app).edit().putString("dry_sha256", res.substring(res.lastIndexOf(':') + 1)).apply();
                    state(app, "idle", null);
                } else if (res.startsWith("mag_removed:")) state(app, "restart", null);
                else state(app, "error", res.startsWith("mag_error:") ? "Couldn't remove Magisk: " + res.substring(res.indexOf(':', 10) + 1)
                        : "The SongateOS module didn't answer.");
            } catch (Exception e) {
                Log.w(TAG, "remove", e); state(app, "error", "Couldn't remove Magisk: " + msg(e));
            } finally { sBusy = false; }
        });
        return SystemApi.ok();
    }

    private static void download(Context c) {
        SharedPreferences p = prefs(c);
        try {
            File ota = Updater.otaDir(c);
            if (ota == null) { state(c, "error", "Insert a microSD card first."); return; }
            File dir = new File(ota.getParentFile(), "root");
            if (!dir.isDirectory() && !dir.mkdirs()) { state(c, "error", "Could not use the microSD card."); return; }
            File out = new File(dir, "Magisk-v" + p.getString("latest", "") + ".apk");
            state(c, "downloading", null);
            HttpURLConnection con = Updater.open(p.getString("url", ""));
            try {
                if (con.getResponseCode() != 200) throw new java.io.IOException("HTTP " + con.getResponseCode());
                try (InputStream in = con.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16]; int n; long have = 0;
                    while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); have += n; if (have > 64L << 20) throw new java.io.IOException("too large"); }
                }
            } finally { con.disconnect(); }
            install(c, out);
        } catch (Exception e) {
            Log.w(TAG, "download", e);
            state(c, "error", "Download failed: " + msg(e));
        }
    }

    private static void install(Context c, File apk) {
        state(c, "verifying", null);
        try {
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi == null || !PKG.equals(pi.packageName) || pi.signingInfo == null || pi.signingInfo.hasMultipleSigners()) {
                state(c, "error", "That isn't the official Magisk app."); return;
            }
            Signature[] sig = pi.signingInfo.getApkContentsSigners();
            if (sig.length != 1 || !MAGISK_CERT.equals(hex(MessageDigest.getInstance("SHA-256").digest(sig[0].toByteArray())))) {
                state(c, "error", "That Magisk APK isn't signed by Magisk's developer."); return;
            }
            String ver = pi.versionName == null ? "0" : pi.versionName.replaceAll("[^0-9.]", "");
            File dst = new File(c.createDeviceProtectedStorageContext().getFilesDir(), "magisk.apk");
            copy(apk, dst);
            writeCmd(c, ver.isEmpty() ? "0" : ver, Updater.sha256(dst), "install");
            state(c, "installing", null);
            String res = ask(c, "magisk");
            if (res.startsWith("mag_done:")) state(c, "restart", null);
            else state(c, "error", res.startsWith("mag_error:") ? "Couldn't install Magisk: " + res.substring(res.indexOf(':', 10) + 1)
                    : "The SongateOS module didn't answer.");
        } catch (Exception e) {
            Log.w(TAG, "install", e);
            state(c, "error", "Couldn't install Magisk: " + msg(e));
        }
    }

    private static void writeCmd(Context c, String ver, String sha, String mode) throws java.io.IOException {
        File cmd = new File(c.createDeviceProtectedStorageContext().getFilesDir(), "mag.cmd");
        try (OutputStream o = new FileOutputStream(cmd)) {
            o.write(("version=" + ver + "\nsha256=" + sha + "\nmode=" + mode + "\n").getBytes());
        }
    }

    /** Hands a request to the root helper and waits for its answer (patching takes ~10-30 s). */
    private static String ask(Context c, String req) throws InterruptedException {
        String nonce = Long.toString(System.currentTimeMillis() % 1000000000L);
        Updater.setSysprop("sys.hibyr4.ota_req", req + ":" + nonce);
        String res = "";
        for (int i = 0; i < 180; i++) {
            Thread.sleep(1000);
            res = Updater.sysprop("sys.hibyr4.ota_state", "");
            if (res.matches("mag_(done|removed|dry|error):" + nonce + "(:.*)?")) return res;
        }
        return res;
    }

    private static void copy(File a, File b) throws java.io.IOException {
        try (InputStream in = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(); }
}
