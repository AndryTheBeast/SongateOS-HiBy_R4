package com.android.settings.core;
public abstract class InstrumentedPreferenceFragment extends com.android.settingslib.core.lifecycle.ObservablePreferenceFragment
    implements com.android.settingslib.core.instrumentation.Instrumentable {
  protected android.content.Context getPrefContext() { return null; }
  @Override public void onCreatePreferences(android.os.Bundle b, String rootKey) {}
}
