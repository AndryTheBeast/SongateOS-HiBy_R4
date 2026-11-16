import android.media.*;
import java.lang.reflect.*;
import java.util.ArrayList;

// Bit-perfect test player for the R4's wired output (run as root with app_process).
// Usage: BpTest <rate> <bits 16|24|32> <seconds> [dBFS, default -40]
// 1) asks the audio policy for bit-perfect on the wired headphone port (for this process' uid)
// 2) plays a quiet 1 kHz sine at that exact format, so the chain can be checked in dumpsys / the HAL log
public class BpTest {
  public static void main(String[] a) throws Exception {
    int rate = Integer.parseInt(a[0]), bits = Integer.parseInt(a[1]), secs = Integer.parseInt(a[2]);
    double db = a.length > 3 ? Double.parseDouble(a[3]) : -40;
    int enc = bits == 16 ? AudioFormat.ENCODING_PCM_16BIT : bits == 24 ? AudioFormat.ENCODING_PCM_24BIT_PACKED
            : AudioFormat.ENCODING_PCM_32BIT;
    AudioFormat fmt = new AudioFormat.Builder().setSampleRate(rate).setEncoding(enc)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
    AudioAttributes attr = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    // wired output port (native type 0x8 headphone or 0x4 headset)
    Class<?> am = AudioManager.class;
    ArrayList<Object> ports = new ArrayList<>();
    am.getMethod("listAudioDevicePorts", ArrayList.class).invoke(null, ports);
    int portId = -1;
    for (Object p : ports) {
      int type = (int) p.getClass().getMethod("type").invoke(p);
      int role = (int) p.getClass().getMethod("role").invoke(p);
      int id = (int) p.getClass().getMethod("id").invoke(p);
      if (role == 2 /*ROLE_SINK*/ && (type == 0x8 || type == 0x4)) { portId = id; System.out.println("wired port id " + id + " type 0x" + Integer.toHexString(type)); }
    }
    if (portId < 0) { System.out.println("no wired port"); return; }

    AudioMixerAttributes mix = new AudioMixerAttributes.Builder(fmt)
            .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build();
    Method set = Class.forName("android.media.AudioSystem").getMethod("setPreferredMixerAttributes", AudioAttributes.class, int.class,
            int.class, AudioMixerAttributes.class);
    int uid = a.length > 4 ? Integer.parseInt(a[4]) : 1000;  // app_process tracks run as system (1000)
    System.out.println("setPreferredMixerAttributes(" + rate + " Hz, " + bits + "-bit, uid " + uid + ") -> "
            + set.invoke(null, attr, portId, uid, mix));

    int frameBytes = 2 * (bits / 8);
    int buf = Math.max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, enc), rate / 10 * frameBytes);
    AudioTrack t = new AudioTrack.Builder().setAudioAttributes(attr).setAudioFormat(fmt)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buf).build();
    t.play();
    double amp = Math.pow(10, db / 20);
    int chunk = rate / 20;  // 50 ms
    byte[] b = new byte[chunk * frameBytes];
    long n = 0, total = (long) rate * secs;
    while (n < total) {
      int o = 0;
      for (int i = 0; i < chunk; i++, n++) {
        double s = amp * Math.sin(2 * Math.PI * 1000 * n / rate);
        long v = Math.round(s * ((1L << (bits - 1)) - 1));
        for (int c = 0; c < 2; c++) for (int k = 0; k < bits / 8; k++) b[o++] = (byte) (v >> (8 * k));
      }
      t.write(b, 0, b.length);
      if (n == chunk * 20L) System.out.println("playing; routed to " + (t.getRoutedDevice() == null ? "?" : t.getRoutedDevice().getType()));
    }
    t.stop(); t.release();
    Method clr = Class.forName("android.media.AudioSystem").getMethod("clearPreferredMixerAttributes", AudioAttributes.class, int.class, int.class);
    System.out.println("cleared -> " + clr.invoke(null, attr, portId, uid));
    System.exit(0);  // AudioTrack threads would keep app_process alive
  }
}
