package com.android.settings.widget;
import android.content.Context;
// real: extends com.android.settingslib.RestrictedPreference -> TwoTargetPreference -> androidx Preference
public class SeekBarPreference extends androidx.preference.Preference { public SeekBarPreference(Context c) { super(c); }
  public void setMax(int m) {} public void setMin(int m) {} public void setProgress(int p) {} public void setContinuousUpdates(boolean b) {} }
