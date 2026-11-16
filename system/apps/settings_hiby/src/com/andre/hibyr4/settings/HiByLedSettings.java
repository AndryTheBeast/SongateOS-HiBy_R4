package com.andre.hibyr4.settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.settingslib.widget.LayoutPreference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings > Display > Status LED. Both lights of the R4: the colour LED on the front (SGM31324 RGB + a white LED)
 * and the white back light behind the three orange strips. In mode "custom" the HiBy R4 service draws them
 * (Lights.java); in "hiby" HiBy's own rules do, in "off" both stay dark.
 *
 * Layout: mode, Colour (what the LED shows normally), Alerts (they override the colour; highest first),
 * While music plays, Back light. Sub-options are active only while their switch is on.
 */
public class HiByLedSettings extends HbFragment {

    private static final String[] MODES = {"hiby", "off", "custom"};
    private static final String[] MODE_LABELS = {"HiBy (charging and audio quality)", "Off", "Custom"};

    // colour presets for the feature colours (Songate palette first)
    private static final String[] COLOURS = {"ff3c30", "ff9030", "f9f2ec", "ff3c8c", "ff00af", "ff2020", "ff7a1a",
            "ffd21a", "3ddc84", "1de9b6", "4fc3ff", "2962ff", "9c4dff"};
    private static final String[] COLOUR_LABELS = {"Songate coral", "Songate amber", "Cream", "Pink", "Magenta", "Red",
            "Orange", "Yellow", "Green", "Teal", "Light blue", "Blue", "Purple"};

    private final List<Preference> customOnly = new ArrayList<>();
    private ColorPickerView picker;
    private boolean custom;

    @Override
    protected void build(PreferenceScreen s) {
        customOnly.clear();
        depsByKey.clear(); state.clear();
        ListPreference mode = icon(list(s, "led_mode", "LED mode", MODES, MODE_LABELS, "hiby"), Ui.LED);
        mode.setOnPreferenceChangeListener((p, nv) -> {
            String v = String.valueOf(nv);
            if (!send("led_mode", v)) return false;
            mode.setSummary(labelOf(MODES, MODE_LABELS, v));
            enableCustom("custom".equals(v));
            return true;
        });

        // ---- Colour: what the front LED shows normally ----
        PreferenceCategory cc = cat(s, "Colour");
        picker = new ColorPickerView(ctx(), Hb.get("led_color", "00a0ff").toLowerCase(), (hex, fin) -> {
            LedLive.draw(hex, Hb.getInt("led_bright", 60), "breath".equals(Hb.get("led_effect", "solid")));
            send("led_color", hex);
        });
        LayoutPreference pp = new LayoutPreference(ctx(), picker);
        pp.setKey("led_picker");
        pp.setSelectable(false);
        cc.addPreference(pp);

        com.android.settings.widget.SeekBarPreference bright = seek(cc, "led_bright", "Brightness", 0, 100, 60, v -> v + " %");
        bright.setOnPreferenceChangeListener((p, nv) -> {
            int v = (Integer) nv;
            LedLive.draw(Hb.get("led_color", "00a0ff"), v, "breath".equals(Hb.get("led_effect", "solid")));
            if (!send("led_bright", Integer.toString(v))) return false;
            bright.setSummary(v + " %");
            return true;
        });
        customOnly.add(bright);
        customOnly.add(list(cc, "led_effect", "Effect", new String[]{"solid", "breath"},
                new String[]{"Solid", "Breathing"}, "solid"));
        customOnly.add(toggle(cc, "led_screen", "Off with screen", "Dark while the screen is off (alerts still show)", "0"));

        // ---- Alerts: override the colour, highest priority first ----
        PreferenceCategory ac = cat(s, "Alerts");
        SwitchPreference low = toggle(ac, "led_lowbat", "Low battery", "Red breathing while not charging", "0");
        customOnly.add(low);
        dep(low, "led_lowbat", list(ac, "led_lowbat_level", "Warn below", new String[]{"5", "10", "15", "20", "25", "30"},
                // fullwidth percent sign: ListPreference formats its summary, a plain "%" crashes it
                new String[]{"5 ％", "10 ％", "15 ％", "20 ％", "25 ％", "30 ％"}, "15"));
        SwitchPreference notif = toggle(ac, "led_notif", "Notifications",
                "Screen off: breathes for new notifications until you wake the device", "0");
        customOnly.add(notif);
        dep(notif, "led_notif", list(ac, "led_notif_color", "Notification colour", COLOURS, COLOUR_LABELS, "4fc3ff"));
        customOnly.add(toggle(ac, "led_charge", "Charging", "HiBy's red breathing while charging", "1"));

        // ---- While music plays ----
        PreferenceCategory mc = cat(s, "While music plays");
        SwitchPreference quality = toggle(mc, "led_quality", "Audio quality colour",
                "Colour by the track's sample rate; DSD lights the white LED", "0");
        customOnly.add(quality);
        dep(quality, "led_quality",
                list(mc, "led_q_cd", "Up to 48 kHz", COLOURS, COLOUR_LABELS, "ff9030"),
                list(mc, "led_q_hires", "88 to 192 kHz", COLOURS, COLOUR_LABELS, "ff3c30"),
                list(mc, "led_q_ultra", "Above 192 kHz", COLOURS, COLOUR_LABELS, "ff3c8c"));
        customOnly.add(toggle(mc, "led_beat", "Bass pulse", "Screen on: brightness smoothly follows the bass", "0"));

        // ---- Back light: white, behind the three orange strips (on/off only) ----
        PreferenceCategory bl = cat(s, "Back light");
        customOnly.add(list(bl, "led_back", "On when", new String[]{"screen", "on", "off", "music"},
                new String[]{"The screen is on", "Always", "Never", "Music plays"}, "screen"));
        customOnly.add(toggle(bl, "led_back_notif", "Notifications", "Screen off: on for new notifications until you wake the device", "0"));
        customOnly.add(toggle(bl, "led_back_charge", "Charging", "Slow blink while charging, steady when full", "0"));
        customOnly.add(toggle(bl, "led_back_beat", "Bass hits", "Screen on and music playing: lights on the strongest bass hits", "0"));

        enableCustom("custom".equals(Hb.get("led_mode", "hiby")));
    }

    /** switch key -> is it on (tracked here: Settings' minified androidx may lack isChecked()) */
    private final Map<String, Boolean> state = new LinkedHashMap<>();
    private final Map<String, List<Preference>> depsByKey = new LinkedHashMap<>();

    /** children follow the switch: enabled only while it is on (and the mode is custom) */
    private void dep(SwitchPreference sw, String key, Preference... children) {
        List<Preference> l = new ArrayList<>();
        for (Preference c : children) l.add(c);
        depsByKey.put(key, l);
        state.put(key, "1".equals(Hb.get(key, "0")));
        sw.setOnPreferenceChangeListener((p, nv) -> {
            boolean v = Boolean.TRUE.equals(nv);
            if (!send(key, v ? "1" : "0")) return false;
            state.put(key, v);
            for (Preference c : l) c.setEnabled(custom && v);
            return true;
        });
    }

    private void enableCustom(boolean on) {
        custom = on;
        for (Preference p : customOnly) p.setEnabled(on);
        for (Map.Entry<String, List<Preference>> e : depsByKey.entrySet())
            for (Preference c : e.getValue()) c.setEnabled(on && Boolean.TRUE.equals(state.get(e.getKey())));
        if (picker != null) picker.setActive(on);
    }
}
