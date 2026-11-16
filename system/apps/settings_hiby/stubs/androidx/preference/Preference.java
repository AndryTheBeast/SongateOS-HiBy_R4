package androidx.preference;
import android.content.Context; import android.util.AttributeSet;
public class Preference {
  public interface OnPreferenceChangeListener { boolean onPreferenceChange(Preference p, Object v); }
  public interface OnPreferenceClickListener { boolean onPreferenceClick(Preference p); }
  public Preference(Context c) {} public Preference(Context c, AttributeSet a) {}
  public void setKey(String k) {} public void setTitle(CharSequence t) {} public void setSummary(CharSequence s) {}
  public void setEnabled(boolean b) {} public void setVisible(boolean b) {} public void setPersistent(boolean b) {}
  public void setIconSpaceReserved(boolean b) {} public void setFragment(String f) {} public void setSelectable(boolean b) {} public void setIcon(android.graphics.drawable.Drawable d) {}
  public void setOnPreferenceChangeListener(OnPreferenceChangeListener l) {}
  public void setOnPreferenceClickListener(OnPreferenceClickListener l) {}
}
