package androidx.preference;
import android.content.Context;
public class ListPreference extends DialogPreference { public ListPreference(Context c) { super(c); }
  public void setEntries(CharSequence[] e) {} public void setEntryValues(CharSequence[] e) {} public void setValue(String v) {}
  @Override public void setSummary(CharSequence s) {} }
