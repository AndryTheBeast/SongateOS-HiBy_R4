import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Test tool: calls the HiBy R4 service's API like Songate would and prints the whole answer, nested lists included
 * (Android's `content call` prints lists only as "Supplier{...}"). Run on the R4 as the system uid:
 *   su 1000 -c "CLASSPATH=/data/local/tmp/sysprobe.dex app_process / SysProbe <method> [arg] [key=value ...]"
 * Read-only by itself: it only does what the method asked for does.
 */
public class SysProbe {
    static final String AUTH = "com.andre.hibyr4.settings";

    public static void main(String[] a) throws Exception {
        String method = a[0], arg = null;
        Bundle extras = new Bundle();
        for (int i = 1; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq > 0) extras.putString(a[i].substring(0, eq), a[i].substring(eq + 1));
            else arg = a[i];
        }
        Class<?> amc = Class.forName("android.app.ActivityManager");
        Object am = amc.getMethod("getService").invoke(null);
        IBinder token = new Binder();
        Object holder = am.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(am, AUTH, 0, token, "sysprobe");
        Object provider = holder.getClass().getField("provider").get(holder);
        Class<?> asc = Class.forName("android.content.AttributionSource");
        Object attr = asc.getConstructor(int.class, String.class, String.class).newInstance(android.os.Process.myUid(), "android", null);
        Method call = Class.forName("android.content.IContentProvider").getMethod("call", asc, String.class, String.class,
                String.class, Bundle.class);
        Bundle r;
        try {
            r = (Bundle) call.invoke(provider, attr, AUTH, method, arg, extras);
        } finally {
            am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(am, AUTH, token, 0);
        }
        print(r, "");
    }

    static void print(Bundle b, String ind) {
        if (b == null) { System.out.println(ind + "(null)"); return; }
        List<String> keys = new ArrayList<>(b.keySet());
        java.util.Collections.sort(keys);
        for (String k : keys) {
            Object v = b.get(k);
            if (v instanceof ArrayList) {
                System.out.println(ind + k + ":");
                int n = 0;
                for (Object o : (ArrayList<?>) v) {
                    System.out.println(ind + "  [" + (n++) + "]");
                    if (o instanceof Bundle) print((Bundle) o, ind + "    "); else System.out.println(ind + "    " + o);
                }
            } else if (v instanceof String[]) {
                System.out.println(ind + k + " = " + java.util.Arrays.toString((String[]) v));
            } else if (v instanceof int[]) {
                System.out.println(ind + k + " = " + java.util.Arrays.toString((int[]) v));
            } else {
                System.out.println(ind + k + " = " + v);
            }
        }
    }
}
