package com.andre.hibyr4.settings;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.widget.SeekBarPreference;

/** Base for the HiBy pages: builds the screen in code (no resources needed inside Settings) and rebuilds on resume. */
public abstract class HbFragment extends SettingsPreferenceFragment {

    interface Fmt { String f(int v); }

    protected abstract void build(PreferenceScreen screen);

    @Override
    public int getMetricsCategory() { return 0; }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        rebuild();
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();   // values may have changed in a sub page or from the module
    }

    protected void rebuild() {
        PreferenceScreen s = getPreferenceManager().createPreferenceScreen(ctx());
        build(s);
        setPreferenceScreen(s);
    }

    protected Context ctx() { return getPrefContext(); }

    protected void toast(String msg) { Toast.makeText(ctx(), msg, Toast.LENGTH_SHORT).show(); }

    // ---------------- builders ----------------
    protected PreferenceCategory cat(PreferenceGroup g, String title) {
        PreferenceCategory c = new PreferenceCategory(ctx());
        c.setTitle(title);
        c.setKey("cat_" + title);
        c.setIconSpaceReserved(false);
        g.addPreference(c);
        return c;
    }

    protected Preference text(PreferenceGroup g, String key, String title, String summary) {
        Preference p = new Preference(ctx());
        p.setKey(key);
        p.setTitle(title);
        p.setSummary(summary);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        g.addPreference(p);
        return p;
    }

    protected Preference page(PreferenceGroup g, String key, String title, String summary, Class<?> fragment) {
        Preference p = text(g, key, title, summary);
        p.setFragment(fragment.getName());
        return p;
    }

    protected ListPreference list(PreferenceGroup g, final String key, String title, String[] vals, String[] labels, String def) {
        final ListPreference p = new ListPreference(ctx());
        p.setKey(key);
        p.setTitle(title);
        p.setDialogTitle(title);
        p.setEntries(labels);
        p.setEntryValues(vals);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        g.addPreference(p);
        String cur = Hb.get(key, def);
        p.setValue(cur);
        p.setSummary(labelOf(vals, labels, cur));
        p.setOnPreferenceChangeListener((pref, nv) -> {
            String v = String.valueOf(nv);
            if (!send(key, v)) return false;
            p.setSummary(labelOf(vals, labels, v));
            return true;
        });
        return p;
    }

    protected SwitchPreference toggle(PreferenceGroup g, final String key, String title, String summary, String def) {
        SwitchPreference p = new SwitchPreference(ctx());
        p.setKey(key);
        p.setTitle(title);
        if (summary != null) p.setSummary(summary);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        g.addPreference(p);
        p.setChecked("1".equals(Hb.get(key, def)));
        p.setOnPreferenceChangeListener((pref, nv) -> send(key, Boolean.TRUE.equals(nv) ? "1" : "0"));
        return p;
    }

    /** Slider for an integer setting min..max (Settings' own SeekBarPreference, as used by native pages). */
    protected SeekBarPreference seek(PreferenceGroup g, final String key, final String title, final int min, int max,
                                     int def, final Fmt fmt) {
        final SeekBarPreference p = new SeekBarPreference(ctx());
        p.setKey(key);
        p.setTitle(title);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        p.setContinuousUpdates(true);   // apply while dragging
        p.setMax(max);
        p.setMin(min);
        g.addPreference(p);
        int cur = Math.max(min, Math.min(max, Hb.getInt(key, def)));
        p.setProgress(cur);
        p.setSummary(fmt.f(cur));
        p.setOnPreferenceChangeListener((pref, nv) -> {
            int v = (Integer) nv;
            if (!send(key, Integer.toString(v))) return false;
            p.setSummary(fmt.f(v));
            return true;
        });
        return p;
    }

    /** A custom-drawn row (header card, segmented buttons, balance, graphs) inside the Settings list. */
    protected com.android.settingslib.widget.LayoutPreference custom(PreferenceGroup g, String key, android.view.View v) {
        com.android.settingslib.widget.LayoutPreference p = new com.android.settingslib.widget.LayoutPreference(ctx(), v);
        p.setKey(key);
        p.setSelectable(false);
        g.addPreference(p);
        return p;
    }

    protected <T extends Preference> T icon(T p, int type) {
        p.setIcon(new Ui.Icon(ctx(), type, new Ui(ctx()).text2));
        p.setIconSpaceReserved(true);
        return p;
    }

    protected boolean send(String key, String v) {
        if (!Hb.ready()) { toast("HiBy service is not running"); return false; }
        if (!Hb.set(key, v)) { toast("Could not change " + key); return false; }
        return true;
    }

    static String labelOf(String[] vals, String[] labels, String v) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return labels[i];
        return v;
    }
}
