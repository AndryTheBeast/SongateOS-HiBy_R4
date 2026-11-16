package com.andre.hibyr4sound;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * Opens the HiBy pages inside the Settings app (this app runs as the same user as Settings, so it may use the
 * internal SubSettings host). Public actions (caller needs com.andre.hibyr4.permission.HIBY_SETTINGS):
 *   com.andre.hibyr4.action.OPEN_AUDIO_SETTINGS  -> Settings > HiBy audio
 *   com.andre.hibyr4.action.OPEN_LED_SETTINGS    -> Settings > Display > Status LED
 *   com.andre.hibyr4.action.OPEN_EQ_SETTINGS     -> Settings > HiBy audio > Equalizer
 * Also the target of the Settings tiles (Settings normally opens its own page for those instead).
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String a = getIntent() == null ? null : getIntent().getAction();
        String frag = "com.andre.hibyr4.settings.HiByAudioSettings", title = "HiBy audio";
        if ("com.andre.hibyr4.action.OPEN_LED_SETTINGS".equals(a)) {
            frag = "com.andre.hibyr4.settings.HiByLedSettings";
            title = "Status LED";
        } else if ("com.andre.hibyr4.action.OPEN_EQ_SETTINGS".equals(a)) {
            frag = "com.andre.hibyr4.settings.HiByEqSettings";
            title = "Equalizer";
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName("com.android.settings", "com.android.settings.SubSettings"));
            i.putExtra(":settings:show_fragment", frag);
            i.putExtra(":settings:show_fragment_title", title);
            startActivity(i);
        } catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS)); } catch (RuntimeException ignored) { }
        }
        finish();
    }
}
