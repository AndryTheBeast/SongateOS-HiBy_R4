package com.andre.hibyr4sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RecoverySystem;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Recovery switch: download TWRP or OrangeFox through the SongateOS update channel and put it in place from Android.
 *  - Packages: songateos-recovery-<kind>-<version>.zip in the GitHub releases (system/ota/make_recovery_package.sh),
 *    signed whole-file with the SongateOS release key like the updates; the newest of each kind across releases.
 *  - The service downloads to the microSD, checks SHA256SUMS.txt (when the release has it) and the signature, takes
 *    recovery.img + recovery.prop out, checks the image's sha256 against recovery.prop, and leaves rec.img + rec.cmd in
 *    its device-protected files. The module's root helper (ota_bridge.sh, request rec:<n>) re-checks them, writes
 *    recovery_a and recovery_b, reads both back and records the recovery in /metadata/songateos/recovery.info.
 *  - The active slot is never changed; nothing restarts.
 * API: recovery_get, recovery_check, recovery_switch <kind>; observer /system/recovery.
 */
final class RecoverySwitch {
    static final String TAG = "SongateRecovery";
    static final String[] KINDS = { "twrp", "orangefox" };
    private static final Pattern ASSET = Pattern.compile("songateos-recovery-(twrp|orangefox)-([0-9]+(?:\\.[0-9]+)+)\\.zip");
    private static final long IMG_SIZE = 100663296L;   // the R4's recovery partition
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static volatile boolean sBusy;
    private static volatile long sProgress;

    private RecoverySwitch() { }

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("recovery", Context.MODE_PRIVATE);
    }

    private static void state(Context c, String s, String err) {
        prefs(c).edit().putString("state", s).putString("error", err == null ? "" : err).apply();
        SystemApi.notify("recovery");
    }

    /** Installed recovery (from the root helper's check), packages found, and the switch state. */
    static Bundle get(Context c) {
        SharedPreferences p = prefs(c);
        Bundle b = SystemApi.ok();
        String rec = Updater.sysprop("sys.hibyr4.ota_recovery", "");   // ok:<Name>:hook<N> | changed:<Name> | none
        String name = rec.startsWith("ok:") || rec.startsWith("changed:") ? rec.split(":")[1] : "";
        b.putString("installed", "TWRP".equals(name) ? "twrp" : "OrangeFox".equals(name) ? "orangefox" : name.isEmpty() ? "none" : "other");
        b.putString("installed_ready", rec.startsWith("ok:") ? "1" : "0");   // with SongateOS update support, checked
        String iv = p.getString("installed_sha", "").equals(p.getString("last_sha", "-")) ? p.getString("installed_version", "") : "";
        b.putString("installed_version", rec.startsWith("ok:") ? iv : "");
        b.putStringArray("kinds", KINDS);
        for (String k : KINDS) {
            String v = p.getString(k + "_version", "");
            if (!v.isEmpty()) { b.putString(k + "_version", v); b.putLong(k + "_size", p.getLong(k + "_size", 0)); }
        }
        b.putString("state", p.getString("state", "idle"));   // idle checking downloading verifying switching done error
        b.putString("error", p.getString("error", ""));
        b.putLong("progress", sProgress);
        b.putLong("last_check", p.getLong("last_check", 0));
        return b;
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
            JSONArray rels = new JSONArray(Updater.httpText("https://api.github.com/repos/" + Updater.repo(c) + "/releases?per_page=30"));
            SharedPreferences.Editor e = prefs(c).edit();
            for (String k : KINDS) {
                String best = null; JSONObject bestAsset = null, bestRel = null;
                for (int i = 0; i < rels.length(); i++) {
                    JSONObject r = rels.getJSONObject(i);
                    if (r.optBoolean("draft")) continue;
                    JSONArray as = r.optJSONArray("assets");
                    if (as == null) continue;
                    for (int j = 0; j < as.length(); j++) {
                        JSONObject a = as.getJSONObject(j);
                        Matcher m = ASSET.matcher(a.optString("name"));
                        if (!m.matches() || !k.equals(m.group(1))) continue;
                        if (best == null || compare(m.group(2), best) > 0) { best = m.group(2); bestAsset = a; bestRel = r; }
                    }
                }
                if (best == null) { e.remove(k + "_version").remove(k + "_url").remove(k + "_size").remove(k + "_sums"); continue; }
                e.putString(k + "_version", best).putString(k + "_url", bestAsset.optString("browser_download_url"))
                 .putLong(k + "_size", bestAsset.optLong("size")).putString(k + "_name", bestAsset.optString("name"))
                 .putString(k + "_sums", sumsUrl(bestRel));
            }
            e.putLong("last_check", System.currentTimeMillis()).apply();
            state(c, "idle", null);
        } catch (Exception ex) {
            Log.w(TAG, "check", ex);
            String m = msg(ex);   // the approved wording for "no public release yet" is shown as it is
            state(c, "error", m.startsWith(Updater.NO_RELEASE) ? m : "Couldn't look for recoveries: " + m);
        }
    }

    private static String sumsUrl(JSONObject release) {
        JSONArray as = release.optJSONArray("assets");
        if (as != null) for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a != null && "SHA256SUMS.txt".equals(a.optString("name"))) return a.optString("browser_download_url");
        }
        return "";
    }

    /** Dotted numbers, e.g. 2026.11.16 > 2026.11.9. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? Long.parseLong(x[i]) : 0, q = i < y.length ? Long.parseLong(y[i]) : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    static Bundle switchAsync(Context c, String kind) {
        if (!"twrp".equals(kind) && !"orangefox".equals(kind)) return SystemApi.err("bad_arg", "twrp or orangefox");
        if (sBusy) return SystemApi.err("busy", "Busy, try again in a moment");
        if (prefs(c).getString(kind + "_url", "").isEmpty()) return SystemApi.err("not_found", "No package for that recovery yet, look again first");
        sBusy = true;
        Context app = c.getApplicationContext();
        EX.execute(() -> { try { download(app, kind); } finally { sBusy = false; } });
        return SystemApi.ok();
    }

    /** Development: switch from a package already on the R4 (same checks, no download). System uid only. */
    static Bundle switchFileAsync(Context c, String path) {
        if (sBusy) return SystemApi.err("busy", "Busy");
        File f = path == null ? null : new File(path);
        if (f == null || !f.isFile()) return SystemApi.err("bad_arg", "No such file");
        sBusy = true;
        Context app = c.getApplicationContext();
        EX.execute(() -> { try { apply(app, f, null); } finally { sBusy = false; } });
        return SystemApi.ok();
    }

    private static void download(Context c, String kind) {
        SharedPreferences p = prefs(c);
        try {
            File ota = Updater.otaDir(c);
            if (ota == null) { state(c, "error", "Insert a microSD card first."); return; }
            File dir = new File(ota.getParentFile(), "recovery");
            if (!dir.isDirectory() && !dir.mkdirs()) { state(c, "error", "Could not use the microSD card."); return; }
            String name = p.getString(kind + "_name", "");
            long size = p.getLong(kind + "_size", 0);
            File out = new File(dir, name);
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!f.getName().equals(name)) f.delete();
            state(c, "downloading", null);
            if (!out.isFile() || out.length() != size) fetch(p.getString(kind + "_url", ""), out, size);
            if (out.length() != size) { out.delete(); state(c, "error", "Download incomplete, try again."); return; }
            String sums = p.getString(kind + "_sums", "");
            if (!sums.isEmpty()) {
                String want = null;
                for (String line : Updater.httpText(sums).split("\n")) {
                    String[] t = line.trim().split("\\s+\\*?", 2);
                    if (t.length == 2 && t[1].equals(name) && t[0].matches("[0-9a-fA-F]{64}")) want = t[0].toLowerCase();
                }
                if (want != null && !want.equals(Updater.sha256(out))) {
                    out.delete(); state(c, "error", "The download was damaged (checksum), it was deleted. Try again."); return;
                }
            }
            apply(c, out, kind);
        } catch (Exception e) {
            Log.w(TAG, "download", e);
            state(c, "error", "Download failed: " + msg(e));
        }
    }

    private static void fetch(String url, File out, long size) throws Exception {
        HttpURLConnection con = Updater.open(url);
        try {
            if (con.getResponseCode() != 200) throw new java.io.IOException("HTTP " + con.getResponseCode());
            sProgress = 0;
            try (InputStream in = con.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16]; int n; long told = 0;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n); sProgress += n;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - told >= 1000) { told = now; SystemApi.notify("recovery"); }
                    if (sProgress > size + 1) throw new java.io.IOException("larger than announced");
                }
            }
        } finally { con.disconnect(); }
    }

    /** Signature, contents, then the root helper. kind = what the user asked for (null: whatever the package is). */
    private static void apply(Context c, File pkg, String kind) {
        state(c, "verifying", null);
        File dir = c.createDeviceProtectedStorageContext().getFilesDir();
        File img = new File(dir, "rec.img"), cmd = new File(dir, "rec.cmd");
        try {
            try {
                RecoverySystem.verifyPackage(pkg, null, Updater.certs(c));
            } catch (Exception e) {
                pkg.delete(); state(c, "error", "The recovery package is not signed with the SongateOS key, it was deleted."); return;
            }
            java.util.Properties prop = new java.util.Properties();
            try (ZipFile z = new ZipFile(pkg)) {
                ZipEntry pe = z.getEntry("recovery.prop"), ie = z.getEntry("recovery.img");
                if (pe == null || ie == null || ie.getSize() != IMG_SIZE) { state(c, "error", "Not a SongateOS recovery package."); return; }
                try (InputStream in = z.getInputStream(pe)) { prop.load(in); }
                try (InputStream in = z.getInputStream(ie); OutputStream o = new FileOutputStream(img)) {
                    byte[] b = new byte[1 << 20]; int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
            }
            String k = prop.getProperty("kind", ""), name = prop.getProperty("name", ""), ver = prop.getProperty("version", "");
            String hook = prop.getProperty("hook", ""), sha = prop.getProperty("sha256", "");
            if (kind != null && !kind.equals(k)) { img.delete(); state(c, "error", "The package is a different recovery."); return; }
            if (!sha.equals(Updater.sha256(img))) { img.delete(); state(c, "error", "The recovery image in the package is damaged."); return; }
            try (OutputStream o = new FileOutputStream(cmd)) {
                o.write(("kind=" + k + "\nname=" + name + "\nversion=" + ver + "\nhook=" + hook + "\nsha256=" + sha + "\n").getBytes());
            }
            state(c, "switching", null);
            String nonce = Long.toString(System.currentTimeMillis() % 1000000000L);
            Updater.setSysprop("sys.hibyr4.ota_req", "rec:" + nonce);
            String res = "";
            for (int i = 0; i < 120; i++) {   // the root helper polls once a second; writing both slots takes a few
                Thread.sleep(1000);
                res = Updater.sysprop("sys.hibyr4.ota_state", "");
                if (res.equals("rec_done:" + nonce) || res.startsWith("rec_error:" + nonce + ":")) break;
            }
            if (res.equals("rec_done:" + nonce)) {
                prefs(c).edit().putString("installed_version", ver).putString("installed_sha", sha).putString("last_sha", sha).apply();
                state(c, "done", null);
                Log.i(TAG, name + " " + ver + " in place");
            } else {
                img.delete(); cmd.delete();
                state(c, "error", res.startsWith("rec_error:") ? "Couldn't switch: " + res.substring(res.indexOf(':', 10) + 1)
                        : "The SongateOS module didn't answer.");
            }
        } catch (Exception e) {
            Log.w(TAG, "apply", e);
            img.delete(); cmd.delete();
            state(c, "error", "Couldn't switch: " + msg(e));
        }
    }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(); }
}
