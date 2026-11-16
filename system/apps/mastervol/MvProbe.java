import java.lang.reflect.Method;
public class MvProbe {
  public static void main(String[] a) throws Exception {
    Class<?> as = Class.forName("android.media.AudioSystem");
    if (a.length >= 1 && a[0].equals("setvol")) {
      Method m = as.getMethod("setMasterVolume", float.class);
      System.out.println("setMasterVolume(" + a[1] + ") -> " + m.invoke(null, Float.parseFloat(a[1])));
    }
    if (a.length >= 1 && a[0].equals("setmute")) {
      Method m = as.getMethod("setMasterMute", boolean.class);
      System.out.println("setMasterMute(" + a[1] + ") -> " + m.invoke(null, Boolean.parseBoolean(a[1])));
    }
    System.out.println("getMasterVolume = " + as.getMethod("getMasterVolume").invoke(null));
    System.out.println("getMasterMute = " + as.getMethod("getMasterMute").invoke(null));
  }
}
