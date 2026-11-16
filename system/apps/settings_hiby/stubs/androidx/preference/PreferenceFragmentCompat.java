package androidx.preference;
import android.os.Bundle;
public abstract class PreferenceFragmentCompat extends androidx.fragment.app.Fragment {
  public PreferenceManager getPreferenceManager() { return null; }
  public void setPreferenceScreen(PreferenceScreen s) {}
  public abstract void onCreatePreferences(Bundle b, String rootKey);
}
