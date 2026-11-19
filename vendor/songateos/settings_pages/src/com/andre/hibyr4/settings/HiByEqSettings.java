package com.andre.hibyr4.settings;

import android.content.ContentResolver;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.settings.widget.SeekBarPreference;
import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.MainSwitchPreference;

/**
 * Settings > HiBy audio > Equalizer: main switch bar, graphic EQ (drag the points), presets, preamp.
 * Stored in Settings.Global "hibyr4_eq"; the HiBy R4 Sound helper app observes it and applies it at once.
 */
public class HiByEqSettings extends HbFragment {

    private int[] a;
    private ListPreference preset;
    private EqGraphView graph;
    private SeekBarPreference preamp;

    private ContentResolver cr() { return ctx().getContentResolver(); }

    private boolean save() {
        if (!Hb.eqSave(cr(), a)) { toast("Could not save the equalizer"); return false; }
        return true;
    }

    private int[] bands() {
        int[] g = new int[Hb.BANDS];
        System.arraycopy(a, Hb.I_BAND, g, 0, Hb.BANDS);
        return g;
    }

    @Override
    protected void build(PreferenceScreen s) {
        a = Hb.eqLoad(cr());

        MainSwitchPreference on = new MainSwitchPreference(ctx());
        on.setKey("eq_on");
        on.setTitle("Use equalizer");
        on.setPersistent(false);
        s.addPreference(on);
        on.setChecked(a[Hb.I_ON] != 0);
        on.addOnSwitchChangeListener((sw, checked) -> {
            a[Hb.I_ON] = checked ? 1 : 0;
            save();
            if (graph != null) graph.setActive(checked);
        });

        graph = new EqGraphView(ctx(), bands(), (band, db) -> {
            a[Hb.I_BAND + band] = db;
            if (a[Hb.I_PRESET] != Hb.CUSTOM) {
                a[Hb.I_PRESET] = Hb.CUSTOM;
                preset.setValue(Integer.toString(Hb.CUSTOM));
                preset.setSummary(Hb.PRESET_NAMES[Hb.CUSTOM]);
            }
            updatePreamp();
            save();
        });
        graph.setActive(a[Hb.I_ON] != 0);
        LayoutPreference gp = new LayoutPreference(ctx(), graph);
        gp.setKey("eq_graph");
        gp.setSelectable(false);
        s.addPreference(gp);

        String[] vals = new String[Hb.PRESET_NAMES.length];
        for (int i = 0; i < vals.length; i++) vals[i] = Integer.toString(i);
        preset = new ListPreference(ctx());
        preset.setKey("eq_preset");
        preset.setTitle("Preset");
        preset.setDialogTitle("Preset");
        preset.setEntries(Hb.PRESET_NAMES);
        preset.setEntryValues(vals);
        preset.setPersistent(false);
        preset.setIconSpaceReserved(false);
        s.addPreference(preset);
        int pi = Math.max(0, Math.min(Hb.CUSTOM, a[Hb.I_PRESET]));
        preset.setValue(Integer.toString(pi));
        preset.setSummary(Hb.PRESET_NAMES[pi]);
        preset.setOnPreferenceChangeListener((p, nv) -> {
            int n = Integer.parseInt(String.valueOf(nv));
            a[Hb.I_PRESET] = n;
            if (n < Hb.PRESETS.length) {
                System.arraycopy(Hb.PRESETS[n], 0, a, Hb.I_BAND, Hb.BANDS);
                graph.setGains(bands());
                updatePreamp();
            }
            preset.setSummary(Hb.PRESET_NAMES[n]);
            return save();
        });

        PreferenceCategory pc = cat(s, "Preamp");
        SwitchPreference auto = new SwitchPreference(ctx());
        auto.setKey("eq_auto");
        auto.setTitle("Automatic preamp");
        auto.setSummary("Lowers the level by the largest boost, so boosting never clips");
        auto.setPersistent(false);
        auto.setIconSpaceReserved(false);
        pc.addPreference(auto);
        auto.setChecked(a[Hb.I_AUTO] != 0);
        auto.setOnPreferenceChangeListener((p, nv) -> {
            a[Hb.I_AUTO] = Boolean.TRUE.equals(nv) ? 1 : 0;
            updatePreamp();
            return save();
        });

        preamp = new SeekBarPreference(ctx());
        preamp.setKey("eq_pre");
        preamp.setTitle("Preamp");
        preamp.setPersistent(false);
        preamp.setIconSpaceReserved(false);
        preamp.setContinuousUpdates(true);
        preamp.setMax(0);
        preamp.setMin(-Hb.MAX_DB);
        pc.addPreference(preamp);
        preamp.setOnPreferenceChangeListener((p, nv) -> {
            a[Hb.I_PRE] = (Integer) nv;
            updatePreamp();
            return save();
        });
        updatePreamp();

        text(s, "eq_note", "", "Drag the points to shape the sound. Applied to all audio – don't use LineageOS AudioFX at the same time.")
                .setSelectable(false);
    }

    private void updatePreamp() {
        if (preamp == null) return;
        boolean auto = a[Hb.I_AUTO] != 0;
        int v;
        if (auto) {
            int max = 0;
            for (int i = 0; i < Hb.BANDS; i++) max = Math.max(max, a[Hb.I_BAND + i]);
            v = -max;
        } else {
            v = Math.max(-Hb.MAX_DB, Math.min(0, a[Hb.I_PRE]));
        }
        preamp.setEnabled(!auto);
        preamp.setProgress(v);
        preamp.setSummary(Hb.db(v) + (auto ? " (automatic)" : ""));
    }
}
