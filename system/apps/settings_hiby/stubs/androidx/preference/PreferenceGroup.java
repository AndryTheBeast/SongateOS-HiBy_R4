package androidx.preference;
import android.content.Context; import android.util.AttributeSet;
public abstract class PreferenceGroup extends Preference { public PreferenceGroup(Context c, AttributeSet a) { super(c, a); } public boolean addPreference(Preference p) { return true; } }
