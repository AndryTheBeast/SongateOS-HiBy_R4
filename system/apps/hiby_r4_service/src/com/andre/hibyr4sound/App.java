package com.andre.hibyr4sound;

import android.app.Application;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Headless helper (persistent system app): holds the equalizer effect. Its settings live in Settings.Global
 * "hibyr4_eq", written by Settings > HiBy audio > Equalizer; every change is applied at once.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(Eq.KEY), false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) { Eq.apply(App.this); }
                });
        Eq.apply(this);
        LineOut.start(this);   // 3.5 mm line out: fixed volume while on, reset when the plug is pulled
        DacVolume.start(this); // wired volume in the DAC (bit-perfect support), see DacVolume
        Lights.start(this);    // colour LED + white back light in LED mode "custom" (notifications, battery, quality, beat)
        SystemTheme.start(this); // SongateOS system colours: the exact palette on top of Android's (see SystemTheme)
        SystemApi.start(this);   // SongateOS system API for Songate: Wi-Fi, Bluetooth, output, power (see SystemApi)
    }
}
