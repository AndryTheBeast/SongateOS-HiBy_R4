package com.andre.hibyr4.settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

/** Settings > HiBy audio (main page), modelled on HiBy OS's "Audio settings" page. */
public class HiByAudioSettings extends HbFragment {

    private static final String[] GAIN = {"low", "high"};
    private static final String[] FILTER = {"fast_rolloff", "slow_rolloff", "minimum_phase"};
    private static final String[] FILTER_NAMES = {"Fast roll-off", "Slow roll-off", "Minimum phase"};
    private static final String[] OUT44 = {"bal_po", "bal_lo"};
    private static final String[] STEPS = {"15", "25", "50", "100"};

    private HeaderView header;

    private String[] chips() {
        int[] eq = Hb.eqLoad(ctx().getContentResolver());
        return new String[]{
                "Gain " + ("high".equals(Hb.get("gain", "low")) ? "High" : "Low"),
                FILTER_NAMES[idx(FILTER, Hb.get("filter", "fast_rolloff"), 0)],
                eq[Hb.I_ON] != 0 ? "EQ " + Hb.PRESET_NAMES[Math.max(0, Math.min(Hb.CUSTOM, eq[Hb.I_PRESET]))] : "EQ off"};
    }

    private static int idx(String[] vals, String v, int def) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return i;
        return def;
    }

    @Override
    protected void build(PreferenceScreen s) {
        boolean ready = Hb.ready();
        String gain = Hb.get("gain", "low"), filter = Hb.get("filter", "fast_rolloff");
        int[] eq = Hb.eqLoad(ctx().getContentResolver());

        // ---- header card: what is playing where ----
        String out = Hb.get("st_output", "none");
        String title = "Balance".equals(out) ? "Balanced 4.4 mm" : "BalanceLo".equals(out) ? "Balanced line out"
                : "Headphone".equals(out) ? ("lineout".equals(Hb.get("out35", "headphone")) ? "Line out 3.5 mm"
                : "Headphone 3.5 mm") : "No headphones";
        String rate = Hb.get("st_rate", "");
        if ("0".equals(rate)) rate = "";   // nothing playing
        // MQA / DSD as recognised by HiBy's driver: the rate is then the decoded / DSD rate, not the file's
        String q = Hb.get("st_quality", "");
        String kind = "mqastudio".equals(q) ? " · MQA Studio" : "mqa".equals(q) ? " · MQA" : "mqb".equals(q) ? " · MQB"
                : "dsd".equals(q) ? " · DSD" : "";
        String line = !ready ? "HiBy service not running" : rate.isEmpty() ? "ES9018C2M DAC"
                : "ES9018C2M DAC · " + khz(rate) + kind;
        header = new HeaderView(ctx(), title, line, chips());
        custom(s, "header", header);

        // ---- Sound ----
        PreferenceCategory sound = cat(s, "Sound");
        custom(sound, "gain", new SegmentedView(ctx(), Ui.GAIN, "Gain", null,
                new String[]{"Low", "High"}, idx(GAIN, gain, 0), i -> sendAndRefresh("gain", GAIN[i])));
        custom(sound, "filter", new SegmentedView(ctx(), Ui.FILTER, "Digital filter", "DAC reconstruction filter – very subtle",
                new String[]{"Fast", "Slow", "Min. phase"}, idx(FILTER, filter, 0), i -> sendAndRefresh("filter", FILTER[i])));
        custom(sound, "balance", new BalanceView(ctx(), Math.max(-10, Math.min(10, Hb.getInt("balance", 0))),
                v -> send("balance", Integer.toString(v))));
        icon(seek(sound, "dsd_comp", "DSD gain compensation", 0, 9, 6, v -> "Level " + v), Ui.DSD);

        // ---- Equalizer ----
        PreferenceCategory eqc = cat(s, "Equalizer");
        int[] g = new int[Hb.BANDS];
        System.arraycopy(eq, Hb.I_BAND, g, 0, Hb.BANDS);
        Preference e = page(eqc, "eq", "Equalizer", Hb.eqSummary(ctx().getContentResolver()), HiByEqSettings.class);
        Ui ui = new Ui(ctx());
        e.setIcon(new Ui.MiniCurve(ctx(), g, eq[Hb.I_ON] != 0, ui.accent, ui.text2));
        e.setIconSpaceReserved(true);

        // ---- Output ----
        PreferenceCategory out44 = cat(s, "Output");
        custom(out44, "out44", new SegmentedView(ctx(), Ui.JACK, "4.4 mm balanced", "Not tested yet on this unit",
                new String[]{"Headphone", "Line out"}, idx(OUT44, Hb.get("out44", "bal_po"), 0), i -> send("out44", OUT44[i])));
        // 3.5 mm line out = headphone path at fixed full volume (HiBy's own line-out mode doesn't power the DAC on
        // the R4). Ends by itself when the plug is pulled or at a restart.
        final SegmentedView[] o35 = new SegmentedView[1];
        o35[0] = new SegmentedView(ctx(), Ui.HEADPHONE, "3.5 mm", "Line out: fixed full volume, ends when unplugged",
                new String[]{"Headphone", "Line out"}, "lineout".equals(Hb.get("out35", "headphone")) ? 1 : 0, i -> {
                    if (i == 0) return send("out35", "headphone");
                    new android.app.AlertDialog.Builder(ctx())
                            .setTitle("Switch to line out?")
                            .setMessage("The 3.5 mm output will play at fixed full volume and the volume keys won't "
                                    + "change it. Use it only with an amplifier or powered speakers, never with "
                                    + "headphones or earphones.\n\nIt switches back to headphone when you unplug "
                                    + "the cable or restart the player.")
                            .setPositiveButton("Line out", (d, w) -> { if (send("out35", "lineout")) o35[0].setSelected(1); })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return false;   // selected only after confirming
                });
        custom(out44, "out35", o35[0]);
        custom(out44, "lo_gain", new SegmentedView(ctx(), Ui.GAIN, "Line-out level", "DAC gain while line out is on",
                new String[]{"Low", "High"}, idx(GAIN, Hb.get("lo_gain", "high"), 1), i -> send("lo_gain", GAIN[i])));
        // bit-perfect on 3.5/4.4 mm (module patches + "bit_perfect" output) and the wired volume in the DAC (service)
        icon(toggle(out44, "bit_perfect", "Bit-perfect wired output",
                "Apps can play bit-perfect on 3.5 mm and 4.4 mm; the volume is set in the DAC. Takes effect after a restart",
                "1"), Ui.JACK);

        // ---- Controls ----
        PreferenceCategory ctl = cat(s, "Controls");
        icon(toggle(ctl, "remote", "Headphone remote", "Play/pause and track buttons on the cable", "1"), Ui.REMOTE);
        // HiBy's key layout marks play/pause WAKE; the module mounts a copy without it at boot when this is off
        icon(toggle(ctl, "btn_wake", "Media buttons wake the screen", "Play/pause turns the screen on. Takes effect after a restart", "0"), Ui.REMOTE);
        custom(ctl, "vol_steps", new SegmentedView(ctx(), Ui.STEPS, "Volume steps", "Takes effect after a restart",
                STEPS, idx(STEPS, Hb.get("vol_steps", "25"), 1), i -> {
                    boolean ok = send("vol_steps", STEPS[i]);
                    if (ok) toast("Volume steps change after a restart");
                    return ok;
                }));
        // volume after a restart: keep the last one, or always start at a fixed level
        final String[] RESTORE = {"last", "fixed"};
        final int steps = Hb.getInt("vol_steps", 25);
        final com.android.settings.widget.SeekBarPreference[] startVol = new com.android.settings.widget.SeekBarPreference[1];
        custom(ctl, "vol_restore", new SegmentedView(ctx(), Ui.GAIN, "Volume after restart", "Android's safe-volume cap is off",
                new String[]{"Keep last", "Fixed level"}, idx(RESTORE, Hb.get("vol_restore", "last"), 0), i -> {
                    boolean ok = send("vol_restore", RESTORE[i]);
                    if (ok && startVol[0] != null) startVol[0].setEnabled(i == 1);
                    return ok;
                }));
        startVol[0] = icon(seek(ctl, "vol_boot", "Start volume", 0, 100, 20,
                v -> v + " %  (step " + ((v * steps + 50) / 100) + " of " + steps + ")"), Ui.STEPS);
        startVol[0].setEnabled("fixed".equals(Hb.get("vol_restore", "last")));
        // no turbo: the R4 doesn't support it (ro.vendor.turbo unset, stock HiBy OS hides it; no effect when set)
    }

    private static String khz(String hz) {
        try {
            int v = Integer.parseInt(hz);
            return v % 1000 == 0 ? (v / 1000) + " kHz" : String.format("%.1f kHz", v / 1000f);
        } catch (NumberFormatException e) { return hz; }
    }

    /** send, then show the new value in the header card */
    private boolean sendAndRefresh(String key, String v) {
        boolean ok = send(key, v);
        if (ok && header != null) header.setChips(chips());
        return ok;
    }
}
