package com.andre.hibyr4.settings;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

/**
 * Settings > System > SongateOS update, in the same look as the HiBy pages (header card, line icons, pill controls).
 * The work (GitHub check, download, checks, hand-off to the recovery) is done by the HiBy R4 service
 * (com.andre.hibyr4sound.Updater); this page shows its state and sends the user's choices through the service's
 * provider (download/install are only accepted from Settings, uid system).
 */
public class HiBySystemUpdate extends HbFragment {
    static final Uri API = Uri.parse("content://com.andre.hibyr4.settings");
    static final String TITLE = "SongateOS update";

    private final Handler mH = new Handler(Looper.getMainLooper());
    private String mShown = "";
    private HeaderView mHeader;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (!isResumed()) return;
            Bundle s = call("ota_status", null, null);
            if (!key(s).equals(mShown)) rebuild();
            else if (mHeader != null && "downloading".equals(s.getString("state"))) {
                mHeader.setTexts(headTitle(s), headLine(s));
                mHeader.setProgress(progress(s));
            }
            mH.postDelayed(this, 1000);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        try { if (getActivity() != null) getActivity().setTitle(TITLE); } catch (RuntimeException ignored) { }
        mH.removeCallbacks(mTick);
        mH.postDelayed(mTick, 1000);
    }

    @Override
    public void onPause() {
        mH.removeCallbacks(mTick);
        super.onPause();
    }

    private Bundle call(String method, String arg, Bundle extras) {
        try {
            Bundle b = ctx().getContentResolver().call(API, method, arg, extras);
            return b != null ? b : new Bundle();
        } catch (RuntimeException e) {
            Bundle b = new Bundle();
            b.putBoolean("ok", false);
            b.putString("error", "HiBy R4 service not available");
            b.putString("state", "error");
            return b;
        }
    }

    private static String key(Bundle s) {
        return s.getString("state", "") + "|" + s.getString("latest", "") + "|" + s.getString("error", "") + "|"
                + s.getString("recovery", "") + "|" + s.getBoolean("auto") + "|" + s.getBoolean("beta") + "|"
                + s.getLong("last_check");
    }

    private static String mb(long b) { return (b >> 20) + " MB"; }

    private static float progress(Bundle s) {
        long size = s.getLong("size"), done = s.getLong("progress");
        return size > 0 ? (float) done / size : 0f;
    }

    private static String installed(Bundle s) {
        String cur = s.getString("current", "0.0.0");
        return "0.0.0".equals(cur) ? "LineageOS GSI + module" : "SongateOS " + cur;
    }

    private static String headTitle(Bundle s) {
        switch (s.getString("state", "idle")) {
            case "checking": return "Checking…";
            case "up_to_date": return "Up to date";
            case "available": return "Update available";
            case "downloading": return "Downloading";
            case "verifying": return "Checking download";
            case "ready": return "Ready to install";
            case "staging": return "Restarting to install";
            case "error": return "Update problem";
            default: return "SongateOS";
        }
    }

    private static String headLine(Bundle s) {
        String st = s.getString("state", "idle"), latest = s.getString("latest", "");
        long size = s.getLong("size"), done = s.getLong("progress");
        switch (st) {
            case "available":
            case "ready":
            case "verifying":
            case "staging":
                return "SongateOS " + latest + " · " + mb(size);
            case "downloading":
                return mb(done) + " of " + mb(size) + (size > 0 ? " · " + (done * 100 / size) + " %" : "");
            default:
                return installed(s);
        }
    }

    private String[] chips(Bundle s) {
        String rec = s.getString("recovery", "");
        long last = s.getLong("last_check");
        String st = s.getString("state", "idle");
        String first = "available".equals(st) || "ready".equals(st) || "downloading".equals(st) || "verifying".equals(st)
                ? "Now " + installed(s).replace("SongateOS ", "") : null;
        String recChip = rec.startsWith("ok") ? "Recovery ready" : "Recovery not checked";
        String lastChip = last > 0 ? "Checked " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE) : "Never checked";
        return first != null ? new String[]{first, recChip, lastChip} : new String[]{recChip, lastChip};
    }

    @Override
    protected void build(PreferenceScreen screen) {
        Bundle s = call("ota_status", null, null);
        mShown = key(s);
        String st = s.getString("state", "idle");
        String latest = s.getString("latest", "");
        String err = s.getString("error", "");

        // ---- header card ----
        mHeader = new HeaderView(ctx(), Ui.UPDATE, headTitle(s), headLine(s), chips(s));
        if ("downloading".equals(st)) mHeader.setProgress(progress(s));
        custom(screen, "ota_header", mHeader);

        // ---- the one thing to do now ----
        PreferenceCategory act = cat(screen, "Update");
        boolean haveRelease = !latest.isEmpty();
        switch (st) {
            case "available":
            case "error":
                if (haveRelease) {
                    custom(act, "ota_act", new ActionView(ctx(), Ui.DOWNLOAD, "SongateOS " + latest,
                            mb(s.getLong("size")) + " to the microSD card, then checked",
                            new String[]{"Download", "Check again"},
                            i -> run(i == 0 ? "ota_download" : "ota_check")));
                    break;
                }
                // no release known: offer a check
                custom(act, "ota_act", new ActionView(ctx(), Ui.UPDATE, "Check for updates",
                        "Looks for a new SongateOS release on GitHub", new String[]{"Check now"}, i -> run("ota_check")));
                break;
            case "downloading":
                custom(act, "ota_act", new ActionView(ctx(), Ui.DOWNLOAD, "Downloading " + latest,
                        "Continues where it stopped when you download again", new String[]{"Pause"},
                        i -> run("ota_cancel")));
                break;
            case "ready":
                custom(act, "ota_act", new ActionView(ctx(), Ui.UPDATE, "Install SongateOS " + latest,
                        "Restarts into recovery and back · about 5 minutes",
                        new String[]{"Install", "Check again"},
                        i -> { if (i == 0) confirmInstall(); else run("ota_check"); }));
                break;
            case "checking":
            case "verifying":
            case "staging": {
                ActionView wait = new ActionView(ctx(), Ui.UPDATE, headTitle(s), null, new String[]{"Please wait…"}, null);
                wait.setEnabled(false);
                custom(act, "ota_act", wait);
                break;
            }
            default:
                custom(act, "ota_act", new ActionView(ctx(), Ui.UPDATE, "Check for updates",
                        "Looks for a new SongateOS release on GitHub", new String[]{"Check now"}, i -> run("ota_check")));
        }
        if (!err.isEmpty()) icon(text(act, "ota_error", "Last problem", err), Ui.NOTES);

        // ---- release notes ----
        String notes = s.getString("notes", "");
        if (haveRelease && !notes.isEmpty()) {
            PreferenceCategory n = cat(screen, "What's new in " + latest + (s.getBoolean("prerelease") ? " (beta)" : ""));
            icon(text(n, "ota_notes", null, notes.replace("\r", "")), Ui.NOTES).setSelectable(false);
        }

        // ---- options ----
        PreferenceCategory opt = cat(screen, "Options");
        icon(sw(opt, "auto", "Check automatically", "Once a day on Wi-Fi. Nothing installs without you",
                s.getBoolean("auto")), Ui.AUTO);
        icon(sw(opt, "beta", "Beta updates", "Also offer pre-releases", s.getBoolean("beta")), Ui.BETA);
        icon(text(opt, "ota_installed", "Installed", installed(s)), Ui.CHECK);

        // ---- recovery & restart ----
        PreferenceCategory rs = cat(screen, "Recovery & restart");
        String rec = s.getString("recovery", "");
        String recNote = rec.startsWith("ok")
                ? (rec.split(":").length > 1 ? rec.split(":")[1] : "SongateOS recovery") + " ready for updates"
                : rec.startsWith("changed") ? "Changed – check it again"
                : "Not checked yet";
        custom(rs, "ota_recovery", new ActionView(ctx(), Ui.RECOVERY, "Recovery", recNote,
                new String[]{"Check it", "Open recovery"}, i -> {
                    if (i == 0) confirmReboot("Check the recovery?",
                            "The R4 restarts into the recovery, which records that it supports updates, and comes "
                                    + "back to Android by itself (about a minute).", "recovery_check");
                    else confirmReboot("Restart into recovery?",
                            "The R4 restarts into the recovery and stays there. To come back, choose Reboot › "
                                    + "System in the recovery.", "recovery");
                }));
        custom(rs, "ota_restart", new ActionView(ctx(), Ui.POWER, "Restart", "Restart the R4 now",
                new String[]{"Restart"}, i -> confirmReboot("Restart now?", "The R4 restarts into Android.", "system")));
    }

    private void confirmReboot(String title, String msg, String target) {
        new android.app.AlertDialog.Builder(ctx())
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Restart", (d, w) -> say(call("reboot", target, null)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void run(String method) {
        say(call(method, null, null));
        mH.postDelayed(this::rebuild, 300);
    }

    private SwitchPreference sw(PreferenceCategory g, String key, String title, String summary, boolean on) {
        SwitchPreference p = new SwitchPreference(ctx());
        p.setKey("ota_" + key);
        p.setTitle(title);
        p.setSummary(summary);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        g.addPreference(p);
        p.setChecked(on);
        p.setOnPreferenceChangeListener((pref, nv) -> {
            Bundle e = new Bundle();
            e.putString("value", Boolean.TRUE.equals(nv) ? "1" : "0");
            return say(call("ota_set", key, e));
        });
        return p;
    }

    private boolean say(Bundle r) {
        if (r.containsKey("ok") && !r.getBoolean("ok")) {
            String e = r.getString("error", "");
            if (!e.isEmpty()) toast(e);
            return false;
        }
        return true;
    }

    private void confirmInstall() {
        Bundle pre = call("ota_preflight", null, null);
        if (!pre.getBoolean("ok")) {
            new android.app.AlertDialog.Builder(ctx())
                    .setTitle("Can't install yet")
                    .setMessage(pre.getString("error", "Unknown reason"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new android.app.AlertDialog.Builder(ctx())
                .setTitle("Install SongateOS update?")
                .setMessage("The R4 restarts into recovery, installs the update and restarts by itself. "
                        + "Don't press any buttons and keep the microSD card in.\n\n"
                        + "Only the system is replaced; your music, apps and settings stay.")
                .setPositiveButton("Install", (d, w) -> run("ota_install"))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
