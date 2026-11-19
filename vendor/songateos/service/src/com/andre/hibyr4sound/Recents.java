package com.andre.hibyr4sound;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Open apps for Songate's app switcher (system API area "apps", service v54). The service runs as the system uid, so
 * it may list, show and close other apps' tasks; Songate draws the switcher.
 *   apps_recent           -> apps: list of {task (int), package, label, icon (PNG bytes), shot (JPEG bytes, 480 px wide, 8 newest only)}
 *                            newest first; Songate itself, home screens and the service are left out
 *   apps_open  <task>     -> brings that app back
 *   apps_close <task>     -> closes it (removes the task); apps_close "all" closes every listed app
 * Screenshots: Android's last snapshot of each task at 480 px wide (the card size in Songate; 240 px looked blurry,
 * beta.4), JPEG 80, for the 8 newest apps only (one Binder call holds about 1 MB).
 */
final class Recents {
    static final String TAG = "SongateRecents";
    private static final int MAX = 20, ICON = 96, SHOT_W = 480, SHOTS = 8;   // card-sized shots for the 8 newest

    private Recents() { }

    private static List<ActivityManager.RecentTaskInfo> tasks(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        @SuppressWarnings("deprecation")
        List<ActivityManager.RecentTaskInfo> all = am.getRecentTasks(MAX, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
        List<ActivityManager.RecentTaskInfo> out = new ArrayList<>();
        for (ActivityManager.RecentTaskInfo t : all) {
            String pkg = pkg(t);
            if (pkg == null || pkg.equals(Setup.SONGATE) || pkg.equals(c.getPackageName())) continue;
            Intent b = t.baseIntent;
            if (b != null && b.hasCategory(Intent.CATEGORY_HOME)) continue;
            out.add(t);
        }
        return out;
    }

    private static String pkg(ActivityManager.RecentTaskInfo t) {
        ComponentName cn = t.baseActivity != null ? t.baseActivity
                : t.baseIntent != null ? t.baseIntent.getComponent() : null;
        return cn == null ? null : cn.getPackageName();
    }

    static Bundle list(Context c) {
        PackageManager pm = c.getPackageManager();
        ArrayList<Bundle> apps = new ArrayList<>();
        int shots = 0;
        for (ActivityManager.RecentTaskInfo t : tasks(c)) {
            String p = pkg(t);
            Bundle b = new Bundle();
            b.putInt("task", t.taskId);
            b.putString("package", p);
            try {
                b.putString("label", pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString());
                b.putByteArray("icon", png(pm.getApplicationIcon(p)));
            } catch (Exception e) {
                b.putString("label", p);
            }
            byte[] shot = shots < SHOTS ? shot(t.taskId) : null;   // one Binder call holds ~1 MB: shots for the newest only
            if (shot != null) shots++;
            if (shot != null) b.putByteArray("shot", shot);
            apps.add(b);
        }
        Bundle r = SystemApi.ok();
        r.putParcelableArrayList("apps", apps);
        return r;
    }

    /** One open app for the overlay switcher. */
    static final class Item {
        int task; String pkg; String label; Drawable icon;
    }

    static List<Item> items(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Item> out = new ArrayList<>();
        for (ActivityManager.RecentTaskInfo t : tasks(c)) {
            Item it = new Item();
            it.task = t.taskId; it.pkg = pkg(t);
            try {
                it.label = pm.getApplicationLabel(pm.getApplicationInfo(it.pkg, 0)).toString();
                it.icon = pm.getApplicationIcon(it.pkg);
            } catch (Exception e) { it.label = it.pkg; }
            out.add(it);
        }
        return out;
    }

    static Bundle open(Context c, String arg) {
        int id;
        try { id = Integer.parseInt(arg); } catch (Exception e) { return SystemApi.err("bad_arg", "task must be a number"); }
        try {
            c.getSystemService(ActivityManager.class).moveTaskToFront(id, 0);
            return SystemApi.ok();
        } catch (RuntimeException e) {
            // not running any more: start it again from its recent entry
            try {
                Object atm = atm();
                atm.getClass().getMethod("startActivityFromRecents", int.class, Bundle.class).invoke(atm, id, null);
                return SystemApi.ok();
            } catch (Throwable t) {
                Log.w(TAG, "open " + id, t);
                return SystemApi.err("failed", "Couldn't open that app");
            }
        }
    }

    /** The swipe was cancelled: put the app back at once, without Android's transition (the card covers it). */
    static boolean reopenInstant(Context c, int id) {
        try {
            Bundle o = android.app.ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle();
            c.getSystemService(ActivityManager.class).moveTaskToFront(id, 0, o);
            return true;
        } catch (RuntimeException e) { Log.w(TAG, "reopen " + id, e); return false; }
    }

    static Bundle close(Context c, String arg) {
        List<Integer> ids = new ArrayList<>();
        if ("all".equals(arg)) { for (ActivityManager.RecentTaskInfo t : tasks(c)) ids.add(t.taskId); }
        else try { ids.add(Integer.parseInt(arg)); } catch (Exception e) { return SystemApi.err("bad_arg", "task must be a number or all"); }
        List<String> pkgs = new ArrayList<>();
        for (ActivityManager.RecentTaskInfo t : tasks(c)) if (ids.contains(t.taskId)) { String p = pkg(t); if (p != null) pkgs.add(p); }
        try {
            Object atm = atm();
            Method rm = atm.getClass().getMethod("removeTask", int.class);
            for (int id : ids) rm.invoke(atm, id);
            for (String p : pkgs) stopIdleMusicApp(c, p);
            return SystemApi.ok();
        } catch (Throwable t) {
            Log.w(TAG, "close", t);
            return SystemApi.err("failed", "Couldn't close that app");
        }
    }

    /**
     * Closing a card only removes the window: a music app keeps its background service and its paused media session,
     * so Songate kept showing it (user, 2026-11-19, Spotify). An app with a media session that isn't playing right now
     * is stopped fully; one that is playing keeps playing (the user's choice).
     */
    private static void stopIdleMusicApp(Context c, String pkg) {
        if (pkg.equals(Setup.SONGATE) || pkg.equals(c.getPackageName()) || pkg.equals("android")) return;
        try {
            MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
            boolean hasSession = false, playing = false;
            for (MediaController mc : msm.getActiveSessions(null)) {
                if (!pkg.equals(mc.getPackageName())) continue;
                hasSession = true;
                PlaybackState st = mc.getPlaybackState();
                int s = st == null ? PlaybackState.STATE_NONE : st.getState();
                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
                        || s == PlaybackState.STATE_CONNECTING) playing = true;
            }
            if (!hasSession || playing) return;
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.class.getMethod("forceStopPackage", String.class).invoke(am, pkg);
            Log.i(TAG, "closed " + pkg + " fully (paused music app)");
        } catch (Throwable t) {
            Log.w(TAG, "stop " + pkg, t);
        }
    }

    /**
     * Apps with a running process (for the app drawer's "running" mark; user, 2026-11-19). Only apps the drawer shows
     * (they have a launcher entry); Songate, this service and Android itself are left out.
     *   apps_running -> running: list of {package, state: "foreground" | "visible" | "background" | "service"}
     */
    static Bundle running(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        PackageManager pm = c.getPackageManager();
        java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (p.pkgList == null) continue;
            String st = p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ? "foreground"
                    : p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ? "visible"
                    : p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ? "service" : "background";
            for (String pkg : p.pkgList) {
                if (pkg.equals(Setup.SONGATE) || pkg.equals(c.getPackageName()) || pkg.equals("android")) continue;
                if (pm.getLaunchIntentForPackage(pkg) == null) continue;
                String had = seen.get(pkg);
                if (had == null || rank(st) < rank(had)) seen.put(pkg, st);   // keep the most important process
            }
        }
        ArrayList<Bundle> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : seen.entrySet()) {
            Bundle b = new Bundle();
            b.putString("package", e.getKey());
            b.putString("state", e.getValue());
            out.add(b);
        }
        Bundle r = SystemApi.ok();
        r.putParcelableArrayList("running", out);
        return r;
    }

    private static int rank(String st) {
        switch (st) { case "foreground": return 0; case "visible": return 1; case "service": return 2; default: return 3; }
    }

    /**
     * Stop an app completely (the drawer's "Stop" option): its tasks are closed and its processes and services end,
     * like Android's "Force stop". Songate, this service and Android itself can't be stopped.
     *   apps_kill <package> -> ok
     */
    static Bundle kill(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return SystemApi.err("bad_arg", "package missing");
        if (pkg.equals(Setup.SONGATE) || pkg.equals(c.getPackageName()) || pkg.equals("android"))
            return SystemApi.err("not_allowed", "That app can't be stopped");
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) { return SystemApi.err("not_found", "No such app"); }
        try {
            Object atm = atm();
            Method rm = atm.getClass().getMethod("removeTask", int.class);
            for (ActivityManager.RecentTaskInfo t : tasks(c)) if (pkg.equals(pkg(t))) rm.invoke(atm, t.taskId);
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.class.getMethod("forceStopPackage", String.class).invoke(am, pkg);
            Log.i(TAG, "stopped " + pkg + " (apps_kill)");
            SystemApi.notify("apps");
            return SystemApi.ok();
        } catch (Throwable t) {
            Log.w(TAG, "kill " + pkg, t);
            return SystemApi.err("failed", "Couldn't stop that app");
        }
    }

    // ---------- helpers ----------
    private static Object atm() throws Exception {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }

    /** Android's last snapshot of the task as a JPEG, SHOT_W wide (for the apps_recent API). */
    private static byte[] shot(int taskId) {
        Bitmap small = shotBitmap(taskId, SHOT_W);
        if (small == null) return null;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 80, o);
        return o.toByteArray();
    }

    /** Android's last snapshot of the task (hidden API: IActivityTaskManager.getTaskSnapshot), scaled to width. */
    static Bitmap shotBitmap(int taskId, int width) {
        try {
            Object atm = atm();
            Object snap = null;
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("getTaskSnapshot")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2) snap = m.invoke(atm, taskId, false);
                else if (p.length == 3) snap = m.invoke(atm, taskId, false, true);   // take one if needed (the current app)
                if (snap != null) break;
            }
            if (snap == null) {
                // the app in front has no stored snapshot yet: ask for one now (IActivityTaskManager.takeTaskSnapshot)
                for (Method m : atm.getClass().getMethods()) {
                    if (!m.getName().equals("takeTaskSnapshot")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1) snap = m.invoke(atm, taskId);
                    else if (p.length == 2) snap = m.invoke(atm, taskId, true);
                    if (snap != null) break;
                }
            }
            if (snap == null) { Log.i(TAG, "no snapshot for task " + taskId); return null; }
            HardwareBuffer hb = (HardwareBuffer) snap.getClass().getMethod("getHardwareBuffer").invoke(snap);
            ColorSpace cs = (ColorSpace) snap.getClass().getMethod("getColorSpace").invoke(snap);
            if (hb == null) return null;
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
            if (hw == null) return null;
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            int h = Math.max(1, Math.round(sw.getHeight() * (width / (float) sw.getWidth())));
            return Bitmap.createScaledBitmap(sw, width, h, true);
        } catch (Throwable t) {
            Log.w(TAG, "snapshot " + taskId + ": " + t);
            return null;
        }
    }

    private static byte[] png(Drawable d) {
        Bitmap b = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, ICON, ICON);
        d.draw(cv);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }
}
