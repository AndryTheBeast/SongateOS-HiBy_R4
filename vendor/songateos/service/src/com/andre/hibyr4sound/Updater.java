package com.andre.hibyr4sound;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SongateOS system updates from GitHub releases (design: work/OTA_DESIGN.md).
 *
 * check    : GET api.github.com/repos/<repo>/releases (no token, generic User-Agent, no personal data). Picks the
 *            newest non-draft release tagged songateos-X.Y.Z (pre-releases only with "beta" on) that carries
 *            songateos-X.Y.Z-R4.zip and SHA256SUMS.txt. recovery-* releases are ignored.
 * download : to <microSD>/Android/data/<this app>/files/ota/ (resumable), then SHA-256 against SHA256SUMS.txt and
 *            the whole-file signature against the SongateOS release certificate (res/raw/otacerts.zip). A file
 *            that fails is deleted.
 * install  : pre-flight checks (battery, standard R4 + HiBy vendor, slot a or b, SongateOS recovery with the OTA hook,
 *            file unchanged), then hands a small command to the module (root) which writes
 *            /metadata/songateos/ota.cmd, and reboots to recovery. The recovery hook installs and reboots.
 * result   : after the reboot the module publishes /metadata/songateos/ota.result as sys.hibyr4.ota_result.
 *
 * Nothing installs without the user pressing Install in Settings > System > SongateOS update.
 */
public final class Updater {
    static final String TAG = "SongateOTA";
    static final String DEFAULT_REPO = "AndryTheBeast/SongateOS-HiBy_R4";
    static final String UA = "SongateOS-Updater/1";
    static final int JOB_ID = 0x50A7;
    static final int JOB_ID_BOOT = 0x50A8;   // one check after every boot (user, 2026-11-18)
    static final String CHANNEL = "songateos_updates";
    static final int NOTIF_ID = 0x50A7;
    static final Pattern TAG_RE = Pattern.compile("songateos-([0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?)");
    static final Pattern VER_RE = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?");
    static final Pattern REPO_RE = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    static final long MIN_FREE_EXTRA = 64L << 20;

    // states
    static final String IDLE = "idle", CHECKING = "checking", UP_TO_DATE = "up_to_date", AVAILABLE = "available",
            DOWNLOADING = "downloading", VERIFYING = "verifying", READY = "ready", STAGING = "staging", ERROR = "error";

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static volatile boolean sBusy, sCancel;
    private static volatile long sProgress;

    private Updater() { }

    // ------------------------------------------------------------------ state
    static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("ota", Context.MODE_PRIVATE);
    }

    static String sysprop(String key, String def) {
        try {
            Method m = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            String v = (String) m.invoke(null, key, def);
            return v == null ? def : v;
        } catch (Throwable t) { return def; }
    }

    static boolean setSysprop(String key, String value) {
        try {
            Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class).invoke(null, key, value);
            return true;
        } catch (Throwable t) { Log.w(TAG, "setprop " + key, t); return false; }
    }

    /** Installed SongateOS version; the LineageOS GSI with the Magisk module has none yet -> 0.0.0. */
    static String currentVersion() {
        String v = sysprop("ro.songateos.version", "");
        return VER_RE.matcher(v).matches() ? v : "0.0.0";
    }

    static boolean auto(Context c) { return prefs(c).getBoolean("auto", true); }
    static boolean beta(Context c) { return prefs(c).getBoolean("beta", false); }
    static String repo(Context c) { return prefs(c).getString("repo", DEFAULT_REPO); }

    private static void state(Context c, String s, String error) {
        SharedPreferences.Editor e = prefs(c).edit().putString("state", s);
        if (error != null) e.putString("error", error); else e.remove("error");
        e.apply();
        Log.i(TAG, "state " + s + (error != null ? ": " + error : ""));
        SystemApi.notify("ota");   // Songate's update page listens on content://…/system/ota
    }

    static Bundle status(Context c) {
        SharedPreferences p = prefs(c);
        Bundle b = new Bundle();
        String st = p.getString("state", IDLE);
        if ((DOWNLOADING.equals(st) || VERIFYING.equals(st) || CHECKING.equals(st)) && !sBusy) st = ERROR; // died
        if (READY.equals(st) && !new File(p.getString("file", "/nonexistent")).isFile()) {
            // the verified file was removed from the card: offer the download again
            st = p.getString("zip_url", "").isEmpty() ? IDLE : AVAILABLE;
            p.edit().putString("state", st).remove("file").remove("sha256").putLong("progress", 0).apply();
        }
        b.putString("state", st);
        b.putString("error", p.getString("error", ""));
        b.putString("current", currentVersion());
        // updates are stored on the microSD card (the recovery can't read the encrypted internal storage): without one
        // there are no updates from the player itself; Songate explains it and points to "update from a computer"
        b.putBoolean("card", otaDir(c) != null);
        b.putString("latest", p.getString("latest", ""));
        b.putString("notes", p.getString("notes", ""));
        b.putString("url", p.getString("html_url", ""));
        b.putLong("size", p.getLong("size", 0));
        // verifying / ready: the whole file is there (older Songates showed "0 MB of …" while it was checked)
        b.putLong("progress", DOWNLOADING.equals(st) ? sProgress
                : VERIFYING.equals(st) || READY.equals(st) ? p.getLong("size", 0) : p.getLong("progress", 0));
        b.putLong("last_check", p.getLong("last_check", 0));
        b.putBoolean("prerelease", p.getBoolean("prerelease", false));
        b.putBoolean("auto", auto(c));
        b.putBoolean("beta", beta(c));
        b.putString("repo", repo(c));
        b.putString("recovery", sysprop("sys.hibyr4.ota_recovery", ""));
        b.putString("result", sysprop("sys.hibyr4.ota_result", ""));
        return b;
    }

    static boolean set(Context c, String key, String value) {
        SharedPreferences.Editor e = prefs(c).edit();
        switch (key) {
            case "auto": e.putBoolean("auto", "1".equals(value)); e.apply(); schedule(c); return true;
            case "beta": e.putBoolean("beta", "1".equals(value)); e.apply(); return true;
            case "repo": { // for testing against a public test repository
                if (value != null && !value.isEmpty() && !REPO_RE.matcher(value).matches()) return false;
                if (value == null || value.isEmpty()) e.remove("repo"); else e.putString("repo", value);
                e.apply();
                // a release found in the other repository doesn't belong here: forget it and its download
                String file = prefs(c).getString("file", "");
                if (!file.isEmpty()) new File(file).delete();
                clearRelease(c);
                prefs(c).edit().remove("file").remove("notified").remove("last_check").apply();
                state(c, IDLE, null);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ scheduling
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!auto(c)) { js.cancel(JOB_ID); return; }
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(24L * 3600 * 1000)
                .setPersisted(true)
                .build());
    }

    /**
     * One check after every boot, as soon as unmetered Wi-Fi is up (on top of the daily one), while "check
     * automatically" is on: an update shows on Songate's Updates page without waiting a day (user, 2026-11-18).
     */
    static void scheduleBootCheck(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!auto(c)) { js.cancel(JOB_ID_BOOT); return; }
        js.schedule(new JobInfo.Builder(JOB_ID_BOOT, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setMinimumLatency(30_000)            // after the boot rush (audio, Songate)
                .build());
    }

    // ------------------------------------------------------------------ check
    static boolean checkAsync(Context c, boolean user, Runnable done) {
        if (sBusy) return false;
        sBusy = true;
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            try { check(app, user); } finally { sBusy = false; if (done != null) done.run(); }
        });
        return true;
    }

    private static void check(Context c, boolean user) {
        SharedPreferences p = prefs(c);
        String prev = p.getString("state", IDLE);
        if (READY.equals(prev) && !user) return;     // a verified update is waiting for the user
        state(c, CHECKING, null);
        // a newer SongateOS zip copied to the microSD card wins: no download needed (and works while the repository is
        // private). Checked (version file + SongateOS signature) here, and again by the recovery.
        try {
            String[] v = new String[1];
            File card = newerOnCard(c, currentVersion(), v);
            if (card != null) {
                if (READY.equals(prev) && card.getAbsolutePath().equals(p.getString("file", "")) && v[0].equals(p.getString("latest", ""))) {
                    state(c, READY, null); return;
                }
                state(c, VERIFYING, null);
                String sha = sha256(card);
                p.edit().putString("latest", v[0]).putString("tag", "").putString("notes", "From the microSD card: " + card.getName())
                        .putString("html_url", "").putBoolean("prerelease", v[0].contains("-"))
                        .putString("zip_name", card.getName()).putString("zip_url", "").putString("sums_url", "")
                        .putLong("size", card.length()).putLong("progress", card.length())
                        .putString("file", card.getAbsolutePath()).putString("sha256", sha)
                        .putLong("last_check", System.currentTimeMillis()).apply();
                Log.i(TAG, "update " + v[0] + " found on the card: " + card.getName());
                state(c, READY, null);
                if (!user && !v[0].equals(p.getString("notified", ""))) {
                    notify(c, "SongateOS " + v[0] + " is ready", "It's on the microSD card. Tap to install it.");
                    p.edit().putString("notified", v[0]).apply();
                }
                return;
            }
        } catch (Exception e) { Log.w(TAG, "card check", e); }
        try {
            String json = httpText("https://api.github.com/repos/" + repo(c) + "/releases?per_page=30");
            JSONArray arr = new JSONArray(json);
            JSONObject best = null; String bestVer = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                if (r.optBoolean("draft")) continue;
                if (r.optBoolean("prerelease") && !beta(c)) continue;
                Matcher m = TAG_RE.matcher(r.optString("tag_name"));
                if (!m.matches()) continue;               // recovery-* and other releases
                String v = m.group(1);
                if (asset(r, "songateos-" + v + "-R4.zip") == null || asset(r, "SHA256SUMS.txt") == null) continue;
                if (bestVer == null || compare(v, bestVer) > 0) { best = r; bestVer = v; }
            }
            p.edit().putLong("last_check", System.currentTimeMillis()).apply();
            if (best == null || compare(bestVer, currentVersion()) <= 0) {
                if (!READY.equals(prev)) { clearRelease(c); state(c, UP_TO_DATE, null); }
                else state(c, READY, null);
                return;
            }
            String same = p.getString("latest", "");
            JSONObject zip = asset(best, "songateos-" + bestVer + "-R4.zip");
            // a release re-published with the same version (2.1.0, 2026-11-21) is a different file: GitHub's checksum
            // of the asset tells, so a download of the earlier zip is never installed for it
            String digest = zip.optString("digest", "");
            boolean sameFile = !digest.startsWith("sha256:") || digest.substring(7).equalsIgnoreCase(p.getString("sha256", ""));
            if (READY.equals(prev) && bestVer.equals(same) && sameFile) { state(c, READY, null); return; }
            // the whole release text (Songate shows it as its changelog); only a runaway body is cut
            String notes = best.optString("body", "");
            if (notes.length() > 20000) notes = notes.substring(0, 20000) + "…";
            p.edit().putString("latest", bestVer)
                    .putString("tag", best.optString("tag_name"))
                    .putString("notes", notes)
                    .putString("html_url", best.optString("html_url"))
                    .putBoolean("prerelease", best.optBoolean("prerelease"))
                    .putString("zip_name", zip.optString("name"))
                    .putString("zip_url", zip.optString("browser_download_url"))
                    .putLong("size", zip.optLong("size"))
                    .putString("sums_url", asset(best, "SHA256SUMS.txt").optString("browser_download_url"))
                    .putLong("progress", 0)
                    .apply();
            state(c, AVAILABLE, null);
            if (!user && !bestVer.equals(p.getString("notified", ""))) {
                notify(c, "SongateOS " + bestVer + " available", "Tap to see what's new and install it.");
                p.edit().putString("notified", bestVer).apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "check", e);
            String m = msg(e);   // the approved wording for "no public release yet" is shown as it is
            state(c, READY.equals(prev) ? READY : ERROR, READY.equals(prev) ? null : m.startsWith(NO_RELEASE) ? m : "Check failed: " + m);
        }
    }

    /**
     * Installs the running SongateOS version again (used to remove Google apps, which have no uninstaller): looks up
     * its release, downloads and verifies it like an update, then installs it by itself. Returns an error or null.
     */
    static String reinstallCurrentAsync(Context c, String why) {
        if (sBusy) return "busy";
        String pre = preflightBasics(c, false);
        if (pre != null) return pre;
        sBusy = true;
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            try {
                String cur = currentVersion();
                // the same version's zip already on the microSD card (e.g. copied there for a recovery install):
                // use it instead of downloading (user, 2026-11-19; also works while the repo is private)
                File local = localCopy(app, cur);
                if (local != null) {
                    state(app, VERIFYING, null);
                    String sha = sha256(local);
                    prefs(app).edit().putString("latest", cur).putString("notes", why).putString("file", local.getAbsolutePath())
                            .putString("sha256", sha).putString("zip_name", local.getName()).putLong("size", local.length())
                            .putLong("progress", local.length()).apply();
                    state(app, READY, null);
                    Log.i(TAG, "reinstall from the card: " + local.getName());
                    String err = install(app);
                    if (err != null) { state(app, READY, null); prefs(app).edit().putString("error", err).apply(); }
                    return;
                }
                JSONArray arr = new JSONArray(httpText("https://api.github.com/repos/" + repo(app) + "/releases?per_page=30"));
                JSONObject rel = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    if (!r.optBoolean("draft") && ("songateos-" + cur).equals(r.optString("tag_name"))) rel = r;
                }
                JSONObject zip = rel == null ? null : asset(rel, "songateos-" + cur + "-R4.zip");
                if (zip == null || asset(rel, "SHA256SUMS.txt") == null) {
                    Gapps.prefs(app).edit().remove("keep_once_off").apply();
                    Gapps.prefs(app).edit().putString("state", "error")
                            .putString("error", "SongateOS " + cur + " isn't on GitHub, so it can't be installed again.").apply();
                    SystemApi.notify("gapps");
                    return;
                }
                prefs(app).edit().putString("latest", cur).putString("tag", rel.optString("tag_name"))
                        .putString("notes", why).putString("zip_name", zip.optString("name"))
                        .putString("zip_url", zip.optString("browser_download_url")).putLong("size", zip.optLong("size"))
                        .putString("sums_url", asset(rel, "SHA256SUMS.txt").optString("browser_download_url"))
                        .putLong("progress", 0).putBoolean("auto_install", true).apply();
                state(app, AVAILABLE, null);
                download(app);
                if (READY.equals(prefs(app).getString("state", IDLE)) && prefs(app).getBoolean("auto_install", false)) {
                    prefs(app).edit().remove("auto_install").apply();
                    String err = install(app);
                    if (err != null) { state(app, READY, null); prefs(app).edit().putString("error", err).apply(); }
                }
            } catch (Exception e) {
                Log.w(TAG, "reinstall", e);
                state(app, ERROR, "Couldn't install SongateOS again: " + msg(e));
            } finally {
                sBusy = false;
            }
        });
        return null;
    }

    private static void clearRelease(Context c) {
        prefs(c).edit().remove("latest").remove("tag").remove("notes").remove("html_url").remove("zip_name")
                .remove("zip_url").remove("size").remove("sums_url").remove("progress").remove("sha256").apply();
    }

    private static JSONObject asset(JSONObject release, String name) {
        JSONArray a = release.optJSONArray("assets");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && name.equals(o.optString("name"))) return o;
        }
        return null;
    }

    /** a > b: positive. 1.2.0 > 1.2.0-beta.1 > 1.1.9. */
    static int compare(String a, String b) {
        String[] pa = a.split("-", 2), pb = b.split("-", 2);
        String[] na = pa[0].split("\\."), nb = pb[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            long x = Long.parseLong(na[i]), y = Long.parseLong(nb[i]);
            if (x != y) return x > y ? 1 : -1;
        }
        if (pa.length == 1 && pb.length == 1) return 0;
        if (pa.length == 1) return 1;
        if (pb.length == 1) return -1;
        return pa[1].compareTo(pb[1]);
    }

    // ------------------------------------------------------------------ download + verify
    /**
     * songateos-<version>-R4.zip on the microSD card (the update folder or the card's top folder) that really is that
     * version for the R4 (its songateos/ota.prop: device=R4, version, kind=full) and is signed with the SongateOS key.
     * null if there is none.
     */
    static File localCopy(Context c, String version) {
        String name = "songateos-" + version + "-R4.zip";
        for (File d : cardDirs(c)) {
            File f = new File(d, name);
            if (valid(c, f, version)) return f;
        }
        return null;
    }

    /** The update folder on the microSD card and the card's top folder. */
    private static java.util.List<File> cardDirs(Context c) {
        java.util.List<File> dirs = new java.util.ArrayList<>();
        File od = otaDir(c);
        if (od != null) dirs.add(od);
        for (StorageVolume v : c.getSystemService(StorageManager.class).getStorageVolumes()) {
            if (v.isRemovable() && v.getDirectory() != null && Environment.MEDIA_MOUNTED.equals(v.getState())) dirs.add(v.getDirectory());
        }
        return dirs;
    }

    private static final Pattern CARD_ZIP = Pattern.compile("songateos-([0-9][0-9A-Za-z.\\-]*)-R4\\.zip");

    /**
     * The newest SongateOS zip on the microSD card that is newer than the running version (user, 2026-11-19: updates
     * from the card, e.g. while the GitHub repository is private). Same checks as localCopy. null if there is none.
     */
    static File newerOnCard(Context c, String current, String[] versionOut) {
        File best = null; String bestV = null;
        for (File d : cardDirs(c)) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                Matcher m = CARD_ZIP.matcher(f.getName());
                if (!m.matches()) continue;
                String v = m.group(1);
                if (compare(v, current) <= 0 || (bestV != null && compare(v, bestV) <= 0)) continue;
                if (valid(c, f, v)) { best = f; bestV = v; }
            }
        }
        if (best != null) versionOut[0] = bestV;
        return best;
    }

    /** f is a full SongateOS zip of this version for the R4 (songateos/ota.prop) signed with the SongateOS key. */
    private static boolean valid(Context c, File f, String version) {
        {
            if (!f.isFile() || !f.canRead()) return false;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
                java.util.zip.ZipEntry e = z.getEntry("songateos/ota.prop");
                if (e == null) return false;
                java.util.Properties pr = new java.util.Properties();
                try (InputStream in = z.getInputStream(e)) { pr.load(in); }
                if (!"R4".equals(pr.getProperty("device")) || !version.equals(pr.getProperty("version"))
                        || !"full".equals(pr.getProperty("kind"))) { Log.i(TAG, "local " + f + ": not this version"); return false; }
            } catch (IOException x) { Log.w(TAG, "local " + f + ": " + x); return false; }
            try {
                RecoverySystem.verifyPackage(f, null, certs(c));
            } catch (Exception x) { Log.w(TAG, "local " + f + ": not signed with the SongateOS key"); return false; }
            return true;
        }
    }

    static File otaDir(Context c) {
        for (File f : c.getExternalFilesDirs(null)) {
            if (f == null) continue;
            try {
                if (Environment.isExternalStorageRemovable(f)
                        && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(f))) {
                    File d = new File(f, "ota");
                    if (d.isDirectory() || d.mkdirs()) return d;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    static String downloadAsync(Context c) {
        SharedPreferences p = prefs(c);
        String st = p.getString("state", IDLE);
        if (sBusy) return "busy";
        if (!AVAILABLE.equals(st) && !ERROR.equals(st)) return "no update to download";
        if (p.getString("zip_url", "").isEmpty()) return "no update to download";
        sBusy = true; sCancel = false;
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            try { download(app); } finally { sBusy = false; }
        });
        return null;
    }

    static void cancel() { sCancel = true; }

    private static void download(Context c) {
        SharedPreferences p = prefs(c);
        PowerManager.WakeLock wl = c.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SongateOS:ota");
        WifiManager.WifiLock wifi = c.getSystemService(WifiManager.class)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SongateOS:ota");
        wl.acquire(3 * 3600 * 1000L);
        wifi.acquire();
        try {
            File dir = otaDir(c);
            if (dir == null) { state(c, ERROR, "Insert a microSD card to download the update."); return; }
            String name = p.getString("zip_name", "");
            long size = p.getLong("size", 0);
            File out = new File(dir, name), part = new File(dir, name + ".part");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!f.equals(out) && !f.equals(part)) f.delete();  // older updates
            long have = part.exists() ? part.length() : 0;
            if (dir.getUsableSpace() + have < size + MIN_FREE_EXTRA) {
                state(c, ERROR, "Not enough space on the microSD card (" + mb(size) + " needed)."); return;
            }
            state(c, DOWNLOADING, null);
            String sums = httpText(p.getString("sums_url", ""));
            String want = null;
            for (String line : sums.split("\n")) {
                String[] t = line.trim().split("\\s+\\*?", 2);
                if (t.length == 2 && t[1].equals(name) && t[0].matches("[0-9a-fA-F]{64}")) want = t[0].toLowerCase();
            }
            if (want == null) { state(c, ERROR, "SHA256SUMS.txt has no line for " + name); return; }
            if (!out.exists()) {
                fetch(p.getString("zip_url", ""), part, size);
                if (sCancel) { state(c, AVAILABLE, null); return; }
                if (part.length() != size) { state(c, ERROR, "Download incomplete, try again."); return; }
                if (!part.renameTo(out)) { state(c, ERROR, "Could not save the update file."); return; }
            }
            state(c, VERIFYING, null);
            String got = sha256(out);
            if (!want.equals(got)) {
                out.delete(); state(c, ERROR, "The download was damaged (checksum), it was deleted. Try again."); return;
            }
            try {
                RecoverySystem.verifyPackage(out, null, certs(c));
            } catch (Exception e) {
                out.delete(); state(c, ERROR, "The update is not signed with the SongateOS key, it was deleted."); return;
            }
            p.edit().putString("sha256", got).putString("file", out.getAbsolutePath()).putLong("progress", size).apply();
            state(c, READY, null);
        } catch (Exception e) {
            Log.w(TAG, "download", e);
            state(c, ERROR, "Download failed: " + msg(e));
        } finally {
            if (wifi.isHeld()) wifi.release();
            if (wl.isHeld()) wl.release();
        }
    }

    private static void fetch(String url, File part, long size) throws IOException {
        long have = part.exists() ? part.length() : 0;
        if (have > size) { part.delete(); have = 0; }
        sProgress = have;
        if (have == size) return;
        HttpURLConnection con = open(url);
        if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
        int code = con.getResponseCode();
        boolean append = have > 0 && code == 206;
        if (code != 200 && code != 206) throw new IOException("HTTP " + code);
        if (!append) { have = 0; sProgress = 0; }
        try (InputStream in = con.getInputStream(); OutputStream o = new FileOutputStream(part, append)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long told = 0;
            while (!sCancel && (n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                have += n;
                sProgress = have;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - told >= 1000) { told = now; SystemApi.notify("ota"); }   // progress for Songate, once a second
            }
        } finally {
            con.disconnect();
        }
    }

    /** res/raw/otacerts.zip (the release certificate) as a file, for RecoverySystem.verifyPackage. */
    static File certs(Context c) throws IOException {
        File f = new File(c.createDeviceProtectedStorageContext().getFilesDir(), "songateos_otacerts.zip");
        try (InputStream in = c.getResources().openRawResource(com.andre.hibyr4.service.R.raw.otacerts); OutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        return f;
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 20]; int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }

    // ------------------------------------------------------------------ install
    /** Runs the pre-flight checks; returns null when everything is fine, else the reason. */
    static String preflight(Context c) {
        SharedPreferences p = prefs(c);
        if (!READY.equals(p.getString("state", IDLE))) return "No verified update is waiting.";
        File f = new File(p.getString("file", "/nonexistent"));
        if (!f.isFile()) return "The update file is gone from the microSD card. Download it again.";
        // an update that brings the kept Google apps along needs the same recovery as a Google apps install
        return preflightBasics(c, Gapps.keptWanted(c));
    }

    /** Google apps: the same checks, and a recovery with hook 3, or hook 6 on slot b (it installs Google apps zips). */
    static String preflightBasics(Context c) { return preflightBasics(c, true); }

    static String preflightBasics(Context c, boolean gapps) {
        BatteryManager bm = c.getSystemService(BatteryManager.class);
        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        // 40 %, or any charger plugged in (2.1.0 re-release: a slow USB charger counts too; Android's isCharging() stays
        // false when the current is small, which refused the install at 30 % on a plugged-in player). Below 15 % never.
        Intent bat = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean plugged = bm.isCharging() || (bat != null && bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
        if (level < 15) return "Charge to at least 15 % first, with the charger connected.";
        if (level < 40 && !plugged) return "Charge to at least 40 %, or connect the charger, first.";
        if (!"R4".equals(sysprop("ro.product.vendor.device", "")) || !"R4".equals(sysprop("ro.product.vendor.model", ""))
                || !"HiBy".equals(sysprop("ro.product.vendor.brand", "")) || !"trinket".equals(sysprop("ro.board.platform", "")))
            return "This is not a standard HiBy R4.";
        if (!sysprop("ro.vendor.build.fingerprint", "").startsWith("HiBy/R4/R4:"))
            return "HiBy's R4 vendor partition is not in place.";
        // either slot (2.1.0): HiBy's own updates leave an R4 on slot a or b; the installer writes the running slot
        String slot = sysprop("ro.boot.slot_suffix", "");
        if (!"_a".equals(slot) && !"_b".equals(slot)) return "Unknown active slot (" + slot + ").";
        String rec = sysprop("sys.hibyr4.ota_recovery", "");
        if (!rec.startsWith("ok")) {
            return rec.isEmpty() || rec.startsWith("none")
                    ? "Restart into recovery once (SongateOS TWRP or OrangeFox with update support), then try again."
                    : "The recovery has changed since its last check (" + rec + "). Restart into recovery once.";
        }
        if (!"1".equals(sysprop("sys.hibyr4.ready", ""))) return "The SongateOS service (module) is not running.";
        if (gapps) {
            Matcher m = Pattern.compile("hook([0-9]+)").matcher(rec);
            int hook = m.find() ? Integer.parseInt(m.group(1)) : 0;
            if (hook < 3)
                return "Your recovery can't install Google apps yet. Update it on the Recovery card first.";
            // hooks 3-5 look for Google apps' folders on slot a only; hook 6 uses the running slot
            if ("_b".equals(slot) && hook < 6)
                return "On slot b, Google apps need a newer recovery (2026.11.22 or later). Update it on the Recovery card first.";
        }
        return null;
    }

    /** Checks, stages the command through the module and reboots to recovery. Returns an error or null. */
    static String installAsync(Context c) {
        if (sBusy) return "busy";
        String why = preflight(c);
        if (why != null) return why;
        sBusy = true;
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            String err = null;
            try { err = install(app); } catch (Exception e) { err = "Install failed: " + msg(e); }
            finally { sBusy = false; }
            if (err != null) { state(app, READY, null); prefs(app).edit().putString("error", err).apply(); }
        });
        return null;
    }

    private static String install(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        state(c, STAGING, null);
        File f = new File(p.getString("file", ""));
        String sha = p.getString("sha256", "");
        if (!sha.equals(sha256(f))) { f.delete(); state(c, ERROR, "The update file changed on the card; download it again."); return null; }
        StorageVolume vol = c.getSystemService(StorageManager.class).getStorageVolume(f);
        if (vol == null || vol.getDirectory() == null || !vol.isRemovable()) return "The update file is not on the microSD card.";
        String root = vol.getDirectory().getAbsolutePath() + "/";
        String abs = f.getAbsolutePath();
        if (!abs.startsWith(root)) return "Unexpected update path.";
        String rel = abs.substring(root.length());
        if (!rel.matches("[A-Za-z0-9._/-]+\\.zip") || rel.contains("..")) return "Unexpected update path " + rel;
        String ver = p.getString("latest", "");
        File cmd = new File(c.createDeviceProtectedStorageContext().getFilesDir(), "ota.cmd");
        try (OutputStream o = new FileOutputStream(cmd)) {
            o.write(("version=" + ver + "\nzip=" + rel + "\nsha256=" + sha + "\n" + Gapps.keptRequestLines(c))
                    .getBytes(StandardCharsets.US_ASCII));
        }
        cmd.setReadable(true, true);
        String nonce = Long.toString(System.currentTimeMillis());
        setSysprop("sys.hibyr4.ota_state", "");
        if (!setSysprop("sys.hibyr4.ota_req", "stage:" + nonce)) return "Could not reach the SongateOS service.";
        for (int i = 0; i < 100 && !("staged:" + nonce).equals(sysprop("sys.hibyr4.ota_state", "")); i++) Thread.sleep(150);
        String st = sysprop("sys.hibyr4.ota_state", "");
        if (!("staged:" + nonce).equals(st)) {
            return st.startsWith("error") ? "The service refused the update: " + st : "The SongateOS service did not answer.";
        }
        p.edit().putString("installing", ver).apply();
        Log.i(TAG, "update " + ver + " staged, rebooting to recovery");
        rebootToRecovery(c.getSystemService(PowerManager.class), true);
        return null;
    }

    // ------------------------------------------------------------------ restart (Settings › SongateOS update)
    /** target: "system", "recovery" (stays there) or "recovery_check" (the recovery hook writes recovery.info and
     *  reboots back to Android by itself; staged through the module as /metadata/songateos/return.cmd). */
    static String rebootAsync(Context c, String target) {
        if (!"system".equals(target) && !"recovery".equals(target) && !"recovery_check".equals(target))
            return "unknown restart target";
        if (sBusy) return "busy";
        if (STAGING.equals(prefs(c).getString("state", IDLE))) return "an update is being prepared";
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            try {
                PowerManager pm = app.getSystemService(PowerManager.class);
                if ("system".equals(target)) { pm.reboot(null); return; }
                if ("recovery_check".equals(target)) {
                    String nonce = Long.toString(System.currentTimeMillis());
                    setSysprop("sys.hibyr4.ota_req", "return:" + nonce);
                    for (int i = 0; i < 60 && !("return:" + nonce).equals(sysprop("sys.hibyr4.ota_state", "")); i++)
                        Thread.sleep(150);
                    if (!("return:" + nonce).equals(sysprop("sys.hibyr4.ota_state", ""))) {
                        Log.w(TAG, "module did not stage the return, recovery will stay open");
                    }
                }
                Log.i(TAG, "reboot " + target);
                rebootToRecovery(pm, false);
            } catch (Exception e) {
                Log.w(TAG, "reboot", e);
            }
        });
        return null;
    }

    /**
     * Restart into recovery without Android's "Factory data reset" screen. AOSP's ShutdownThread shows that title for
     * the plain reason "recovery" (it assumes a reset). Both reasons used here still reach the kernel as
     * "reboot,recovery" (PowerManagerService.lowLevelReboot):
     *  - update install: "recovery-update" -> Android's "System update / Restarting" screen;
     *  - recovery visit: LineageOS PowerManager.rebootCustom("recovery") (hidden API, the power menu's "Recovery"
     *    entry) -> the normal restart screen. Falls back to the plain reason if rebootCustom isn't there.
     */
    static void rebootToRecovery(PowerManager pm, boolean forUpdate) {
        if (forUpdate) { pm.reboot("recovery-update"); return; }
        try {
            PowerManager.class.getMethod("rebootCustom", String.class).invoke(pm, "recovery");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "rebootCustom not available, plain reboot to recovery", e);
            pm.reboot("recovery");
        }
    }

    // ------------------------------------------------------------------ after the reboot
    /** Called at boot: report the recovery's result once, tidy up, and (re)schedule the daily check. */
    static final String NO_RELEASE = "No SongateOS release published yet";

    static void onBoot(Context c) {
        schedule(c);
        scheduleBootCheck(c);
        try { Gapps.onBoot(c); } catch (RuntimeException e) { Log.w(TAG, "gapps boot", e); }
        // text stored by checks before v32 (it stays until the next check otherwise)
        if (prefs(c).getString("error", "").contains("repository private?")) prefs(c).edit().putString("error", NO_RELEASE).apply();
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            String res = "";
            for (int i = 0; i < 60 && (res = sysprop("sys.hibyr4.ota_result", "")).isEmpty(); i++) {
                if ("1".equals(sysprop("sys.hibyr4.ready", "")) && i > 10) break;
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
            SharedPreferences p = prefs(app);
            String installing = p.getString("installing", "");
            if (res.isEmpty()) {
                if (!installing.isEmpty()) {
                    notify(app, "SongateOS update not installed", "The recovery did not run the update. Open the update page for details.");
                    p.edit().remove("installing").apply();
                    state(app, READY, null);
                }
                return;
            }
            // res = status|version|detail
            String[] r = res.split("\\|", 3);
            String status = r[0], ver = r.length > 1 ? r[1] : "", detail = r.length > 2 ? r[2] : "";
            if ("ok".equals(status)) {
                notify(app, "SongateOS " + ver + " installed", "Your R4 is up to date.");
                String file = p.getString("file", "");
                if (!file.isEmpty()) new File(file).delete();
                clearRelease(app);
                p.edit().remove("file").remove("installing").apply();
                state(app, UP_TO_DATE, null);
            } else if ("test_ok".equals(status)) {
                notify(app, "SongateOS test update ran", "All installer checks passed; nothing was changed.");
                p.edit().remove("installing").apply();
                state(app, READY, null);
            } else {
                notify(app, "SongateOS update failed", detail.isEmpty() ? status : detail);
                p.edit().remove("installing").apply();
                state(app, READY, null);
                p.edit().putString("error", "Last install: " + status + (detail.isEmpty() ? "" : " – " + detail)).apply();
            }
            setSysprop("sys.hibyr4.ota_req", "ack:" + System.currentTimeMillis());
        });
    }

    // ------------------------------------------------------------------ helpers
    static void notify(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "System updates", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent("com.andre.hibyr4.action.OPEN_UPDATE_SETTINGS").setClassName(c, MainActivity.class.getName());
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIF_ID, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build());
    }

    static HttpURLConnection open(String url) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("not https: " + url);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", UA);
        con.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
        return con;
    }

    static String httpText(String url) throws IOException {
        HttpURLConnection con = open(url);
        try {
            int code = con.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("GitHub rate limit, try again later");
            if (code == 404) throw new IOException(NO_RELEASE);
            if (code != 200) throw new IOException("HTTP " + code);
            StringBuilder s = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                char[] b = new char[8192]; int n;
                while ((n = r.read(b)) > 0) { s.append(b, 0, n); if (s.length() > 4_000_000) throw new IOException("answer too large"); }
            }
            return s.toString();
        } finally {
            con.disconnect();
        }
    }

    static String mb(long b) { return (b >> 20) + " MB"; }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(); }
}
