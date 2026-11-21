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
        Setup.onStart(this);     // SongateOS first-boot setup state + the safety net for Android's setup flags
        // persistent from 2.1.0: the betas' "SongateOS is running" channel (KeepAlive) is removed if an update left it
        try { getSystemService(android.app.NotificationManager.class).deleteNotificationChannel("songateos_system"); }
        catch (RuntimeException e) { /* nothing to remove */ }
        Switcher.init(this);     // SongateOS app switcher overlay (driven by the gesture strip)
        GestureNav.start(this);  // SongateOS gestures: swipe up = home, swipe up and hold = Songate's app switcher
        NotifAccess.grantOnce(this);  // Songate's notification listener allowed once (media from other apps)
        android.content.IntentFilter pf = new android.content.IntentFilter(android.content.Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                if (i.getData() != null && Setup.SONGATE.equals(i.getData().getSchemeSpecificPart())) NotifAccess.grantOnce(c);
            }
        }, pf);
        HoldSwitch.start(this);  // hold switch on top of the R4: button locks, sleep when locked (see HoldSwitch)
        MagiskRoot.start(this);  // root (Magisk): clears a finished "restart" state
    }
}
