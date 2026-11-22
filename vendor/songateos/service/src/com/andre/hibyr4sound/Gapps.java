package com.andre.hibyr4sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google apps for SongateOS (system API area "gapps", service v54; user, 2026-11-18). Two variants, downloaded from
 * their makers' official pages when the user picks one (SongateOS never hosts Google's apps):
 *  - core:  NikGapps Core (Play Store + Play Services only), SourceForge, newest from the project's RSS feed (MD5);
 *  - basic: MindTheGapps (LineageOS's recommendation), GitHub releases, with its .sha256sum.
 * Install: download to the microSD (…/files/gapps/), check the maker's checksum, then stage a recovery request
 * (gapps_zip + gapps_sha256 + gapps_name; ota_bridge.sh copies it to /metadata, the recovery hook v3 installs it)
 * and restart into the recovery. "Keep after updates": Updater adds the kept zip to every SongateOS update request.
 * Remove: Google apps have no uninstaller, so SongateOS itself is installed again (the release of this version must be
 * on GitHub).
 */
final class Gapps {
    static final String TAG = "SongateGapps";
    static final String CORE = "core", BASIC = "basic";
    private static final String MTG_API = "https://api.github.com/repos/MindTheGapps/14.0.0-arm64/releases?per_page=5";
    private static final Pattern MTG_ZIP = Pattern.compile("MindTheGapps-14\\.0\\.0-arm64-([0-9]{8}_[0-9]{6})\\.zip");
    private static final String NIK_RSS = "https://sourceforge.net/projects/nikgapps/rss?path=/Releases/Android-14";
    private static final Pattern NIK_ZIP = Pattern.compile("NikGapps-core-arm64-14-([0-9]{8})-signed\\.zip");
    private static final String PLAY_STORE = "com.android.vending";
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static volatile boolean sBusy;
    private static volatile long sProgress, sTotal;

    private Gapps() { }

    static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("gapps", Context.MODE_PRIVATE);
    }

    private static void state(Context c, String st, String err) {
        prefs(c).edit().putString("state", st).putString("error", err == null ? "" : err).apply();
        SystemApi.notify("gapps");
    }

    /** Google apps are in the system when the Play Store is a system app. */
    static boolean present(Context c) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(PLAY_STORE, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) { return false; }
    }

    static String installed(Context c) { return present(c) ? prefs(c).getString("installed", "basic") : ""; }
    static boolean keep(Context c) { return prefs(c).getBoolean("keep", true); }

    // ---------------------------------------------------------------- API
    static Bundle get(Context c) {
        SharedPreferences p = prefs(c);
        Bundle b = SystemApi.ok();
        b.putString("installed", installed(c));
        b.putString("installed_version", present(c) ? p.getString("installed_version", "") : "");
        b.putString("keep", keep(c) ? "1" : "0");
        b.putString("state", p.getString("state", "idle"));
        b.putInt("progress", sTotal > 0 ? (int) Math.min(100, sProgress * 100 / sTotal) : 0);
        b.putString("error", p.getString("error", ""));
        ArrayList<Bundle> vs = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("variants", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Bundle v = new Bundle();
                v.putString("id", o.getString("id"));
                v.putString("name", o.getString("name"));
                v.putString("summary", o.getString("summary"));
                v.putLong("size", o.getLong("size"));
                v.putString("version", o.getString("version"));
                vs.add(v);
            }
        } catch (Exception ignored) { }
        b.putParcelableArrayList("variants", vs);
        return b;
    }

    static Bundle set(Context c, String key, String value) {
        if (!"keep".equals(key)) return SystemApi.err("bad_arg", "only keep can be set");
        if (!"0".equals(value) && !"1".equals(value)) return SystemApi.err("bad_arg", "keep must be 0 or 1");
        prefs(c).edit().putBoolean("keep", "1".equals(value)).apply();
        SystemApi.notify("gapps");
        return get(c);
    }

    static Bundle checkAsync(Context c) {
        if (sBusy) return SystemApi.err("busy", "Google apps are busy");
        sBusy = true;
        Context app = c.getApplicationContext();
        EX.execute(() -> { try { check(app); } finally { sBusy = false; } });
        return SystemApi.ok();
    }

    static Bundle installAsync(Context c, String id) {
        if (!CORE.equals(id) && !BASIC.equals(id)) return SystemApi.err("bad_arg", "core or basic");
        if (sBusy) return SystemApi.err("busy", "Google apps are busy");
        if (present(c)) return SystemApi.err("installed", "Google apps are already installed. Remove them first.");
        String why = Updater.preflightBasics(c);
        if (why != null) return SystemApi.err("not_ready", why);
        sBusy = true;
        Context app = c.getApplicationContext();
        EX.execute(() -> {
            try { install(app, id); }
            catch (Exception e) { Log.w(TAG, "install", e); state(app, "error", "Couldn't install Google apps: " + msg(e)); }
            finally { sBusy = false; }
        });
        return SystemApi.ok();
    }

    static Bundle removeAsync(Context c) {
        if (!present(c)) return SystemApi.err("not_installed", "Google apps aren't installed.");
        prefs(c).edit().putBoolean("keep_once_off", true).commit();   // this reinstall must not add them back
        String err = Updater.reinstallCurrentAsync(c, "Removing Google apps");
        if (err != null) { prefs(c).edit().remove("keep_once_off").apply(); return SystemApi.err("failed", err); }
        state(c, "removing", null);
        return SystemApi.ok();
    }

    // ---------------------------------------------------------------- check
    private static void check(Context c) {
        state(c, "checking", null);
        JSONArray vs = new JSONArray();
        String err = null;
        try { vs.put(nikCore()); } catch (Exception e) { Log.w(TAG, "NikGapps", e); err = "NikGapps: " + msg(e); }
        try { vs.put(mindTheGapps()); } catch (Exception e) { Log.w(TAG, "MindTheGapps", e); err = (err == null ? "" : err + "; ") + "MindTheGapps: " + msg(e); }
        prefs(c).edit().putString("variants", vs.toString()).putLong("last_check", System.currentTimeMillis()).apply();
        state(c, vs.length() == 0 ? "error" : "idle", vs.length() == 0 ? "Couldn't look for Google apps: " + err : null);
    }

    /** Newest NikGapps-core-arm64-14-YYYYMMDD-signed.zip from the SourceForge RSS feed (newest first). */
    private static JSONObject nikCore() throws Exception {
        String rss = Updater.httpText(NIK_RSS);
        for (String item : rss.split("<item>")) {
            Matcher m = NIK_ZIP.matcher(item);
            if (!m.find()) continue;
            String link = between(item, "<link>", "</link>");
            String md5 = attrHash(item);
            long size = Long.parseLong(attr(item, "filesize"));
            if (link == null || md5 == null || !link.startsWith("https://sourceforge.net/")) continue;
            return new JSONObject().put("id", CORE).put("name", "Core")
                    .put("summary", "Play Store and Google Play services only")
                    .put("version", m.group(1)).put("size", size).put("url", link)
                    .put("file", m.group(0)).put("md5", md5.toLowerCase());
        }
        throw new Exception("no Android 14 Core build listed");
    }

    /** Newest MindTheGapps 14.0.0 arm64 release on GitHub, with its .sha256sum. */
    private static JSONObject mindTheGapps() throws Exception {
        JSONArray rels = new JSONArray(Updater.httpText(MTG_API));
        for (int i = 0; i < rels.length(); i++) {
            JSONObject r = rels.getJSONObject(i);
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
            JSONArray as = r.optJSONArray("assets");
            if (as == null) continue;
            JSONObject zip = null, sum = null;
            for (int j = 0; j < as.length(); j++) {
                JSONObject a = as.getJSONObject(j);
                String n = a.optString("name");
                if (MTG_ZIP.matcher(n).matches()) zip = a;
                else if (n.endsWith(".zip.sha256sum")) sum = a;
            }
            if (zip == null || sum == null) continue;
            Matcher m = MTG_ZIP.matcher(zip.getString("name"));
            m.matches();
            return new JSONObject().put("id", BASIC).put("name", "Basic")
                    .put("summary", "Play Store, Google Play services and Google's basic apps")
                    .put("version", m.group(1)).put("size", zip.getLong("size"))
                    .put("url", zip.getString("browser_download_url")).put("file", zip.getString("name"))
                    .put("sums_url", sum.getString("browser_download_url"));
        }
        throw new Exception("no Android 14 release listed");
    }

    // ---------------------------------------------------------------- install
    static File dir(Context c) {
        File ota = Updater.otaDir(c);
        if (ota == null) return null;
        File d = new File(ota.getParentFile(), "gapps");
        return d.isDirectory() || d.mkdirs() ? d : null;
    }

    private static void install(Context c, String id) throws Exception {
        SharedPreferences p = prefs(c);
        JSONObject v = null;
        JSONArray a = new JSONArray(p.getString("variants", "[]"));
        for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).getString("id"))) v = a.getJSONObject(i);
        if (v == null) { check(c); a = new JSONArray(p.getString("variants", "[]"));
            for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).getString("id"))) v = a.getJSONObject(i); }
        if (v == null) { state(c, "error", "That Google apps package isn't available right now."); return; }
        File d = dir(c);
        if (d == null) { state(c, "error", "Insert a microSD card to download Google apps."); return; }
        String name = v.getString("file");
        long size = v.getLong("size");
        if (d.getUsableSpace() < size + (64L << 20)) { state(c, "error", "Not enough space on the microSD card (" + Updater.mb(size) + " needed)."); return; }
        File out = new File(d, name);
        File[] old = d.listFiles();
        if (old != null) for (File f : old) if (!f.equals(out)) f.delete();       // one package on the card at a time
        PowerManager.WakeLock wl = c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SongateOS:gapps");
        wl.acquire(3 * 3600 * 1000L);
        try {
            state(c, "downloading", null);
            if (!out.isFile() || out.length() != size) download(v.getString("url"), out, size);
            if (out.length() != size) { out.delete(); state(c, "error", "Download incomplete, try again."); return; }
            state(c, "verifying", null);
            if (v.has("md5")) {
                if (!v.getString("md5").equals(digest(out, "MD5"))) { out.delete(); state(c, "error", "The download was damaged (checksum), it was deleted. Try again."); return; }
            } else {
                String sums = Updater.httpText(v.getString("sums_url"));
                String want = sums.trim().split("\\s+")[0].toLowerCase();
                if (!want.matches("[0-9a-f]{64}") || !want.equals(digest(out, "SHA-256"))) { out.delete(); state(c, "error", "The download was damaged (checksum), it was deleted. Try again."); return; }
            }
            String sha = digest(out, "SHA-256");
            String rel = relOnSd(c, out);
            if (rel == null) { state(c, "error", "The Google apps file is not on the microSD card."); return; }
            p.edit().putString("file", out.getAbsolutePath()).putString("rel", rel).putString("sha256", sha)
                    .putString("pending", id).putString("pending_version", v.getString("version")).apply();
            state(c, "installing", null);
            String err = stage(c, "gapps_zip=" + rel + "\ngapps_sha256=" + sha + "\ngapps_name=" + id + "\n");
            if (err != null) { state(c, "error", err); return; }
            Log.i(TAG, id + " " + v.getString("version") + " staged, restarting into the recovery");
            Updater.rebootToRecovery(c.getSystemService(PowerManager.class), true);
        } finally {
            if (wl.isHeld()) wl.release();
        }
    }

    /** Whether updates will add the kept Google apps (cheap: no checksum; keptRequestLines() does the full check). */
    static boolean keptWanted(Context c) {
        return !prefs(c).getBoolean("keep_once_off", false) && keep(c) && present(c);
    }

    /** The kept Google apps zip for an update request ("" when there's none to keep). */
    static String keptRequestLines(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("keep_once_off", false) || !keep(c) || !present(c)) return "";
        File f = new File(p.getString("file", "/nonexistent"));
        String sha = p.getString("sha256", ""), rel = p.getString("rel", ""), id = p.getString("installed", "");
        if (!f.isFile() || sha.isEmpty() || rel.isEmpty() || id.isEmpty()) return "";
        try { if (!sha.equals(digest(f, "SHA-256"))) return ""; } catch (Exception e) { return ""; }
        return "gapps_zip=" + rel + "\ngapps_sha256=" + sha + "\ngapps_name=" + id + "\n";
    }

    /** After a restart: was a staged install or removal done? */
    static void onBoot(Context c) {
        SharedPreferences p = prefs(c);
        String pending = p.getString("pending", "");
        String st = p.getString("state", "idle");
        if (!pending.isEmpty()) {
            if (present(c)) {
                p.edit().putString("installed", pending).putString("installed_version", p.getString("pending_version", ""))
                        .remove("pending").remove("pending_version").apply();
                state(c, "idle", null);
            } else if ("installing".equals(st)) {
                p.edit().remove("pending").remove("pending_version").apply();
                state(c, "error", "Google apps weren't installed. See the recovery log in Settings › Updates.");
            }
        }
        if ("removing".equals(st)) {
            p.edit().remove("keep_once_off").apply();
            if (!present(c)) { p.edit().remove("installed").remove("installed_version").apply(); state(c, "idle", null); }
            else state(c, "error", "Google apps are still there.");
        }
    }

    static String stage(Context c, String body) throws Exception {
        File cmd = new File(c.createDeviceProtectedStorageContext().getFilesDir(), "ota.cmd");
        try (OutputStream o = new FileOutputStream(cmd)) { o.write(body.getBytes(StandardCharsets.US_ASCII)); }
        cmd.setReadable(true, true);
        String nonce = Long.toString(System.currentTimeMillis());
        Updater.setSysprop("sys.hibyr4.ota_state", "");
        if (!Updater.setSysprop("sys.hibyr4.ota_req", "stage:" + nonce)) return "Could not reach the SongateOS service.";
        for (int i = 0; i < 100 && !("staged:" + nonce).equals(Updater.sysprop("sys.hibyr4.ota_state", "")); i++) Thread.sleep(150);
        String st = Updater.sysprop("sys.hibyr4.ota_state", "");
        if (!("staged:" + nonce).equals(st)) return st.startsWith("error") ? "The service refused the request: " + st : "The SongateOS service did not answer.";
        return null;
    }

    // ---------------------------------------------------------------- helpers
    private static void download(String url, File out, long size) throws Exception {
        sTotal = size; sProgress = 0;
        HttpURLConnection con = Updater.open(url);
        con.setInstanceFollowRedirects(true);
        // SourceForge's /download answers with a chain of redirects (to a mirror); follow them by hand across hosts
        for (int hop = 0; hop < 8; hop++) {
            int code = con.getResponseCode();
            if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                String next = con.getHeaderField("Location");
                con.disconnect();
                if (!next.startsWith("https://")) throw new Exception("insecure redirect");
                con = Updater.open(next);
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            break;
        }
        try (InputStream in = con.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[1 << 16]; int n; long told = 0;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n); sProgress += n;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - told >= 1000) { told = now; SystemApi.notify("gapps"); }
            }
        } finally { con.disconnect(); }
    }

    static String digest(File f, String alg) throws Exception {
        MessageDigest md = MessageDigest.getInstance(alg);
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 20]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }

    static String relOnSd(Context c, File f) {
        StorageVolume vol = c.getSystemService(StorageManager.class).getStorageVolume(f);
        if (vol == null || vol.getDirectory() == null || !vol.isRemovable()) return null;
        String root = vol.getDirectory().getAbsolutePath() + "/", abs = f.getAbsolutePath();
        if (!abs.startsWith(root)) return null;
        String rel = abs.substring(root.length());
        return rel.matches("[A-Za-z0-9._/-]+\\.zip") && !rel.contains("..") ? rel : null;
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a); if (i < 0) return null; int j = s.indexOf(b, i + a.length());
        return j < 0 ? null : s.substring(i + a.length(), j).trim();
    }

    private static String attr(String s, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : "0";
    }

    private static String attrHash(String s) {
        Matcher m = Pattern.compile("<media:hash algo=\"md5\">([0-9a-fA-F]{32})</media:hash>").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(); }
}
