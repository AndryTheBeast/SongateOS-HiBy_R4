package com.andre.hibyr4sound;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the app process at boot so the equalizer is applied without opening the app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Eq.apply(context);
    }
}
