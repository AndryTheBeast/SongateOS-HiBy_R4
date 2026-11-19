package com.andre.hibyr4sound;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AirPods controls (earbuds kind "airpods"; user, 2026-11-18). Apple's accessory protocol (AAP) on a classic L2CAP
 * channel, PSM 0x1001, basic mode - which SongateOS's Bluetooth stack allows since beta.5 (patches/apply_bt_aap.py).
 * Our own code, written from the protocol notes published by the LibrePods project (AirPods Pro 2 captures):
 *   framing: one L2CAP packet = one message; "04 00 04 00" + 2-byte little-endian opcode + payload (no length field);
 *   setup:   handshake 00 00 04 00 01 00 02 00 00..., feature flags 4D 00 D7, notifications 0F 00 FF FF FF FF;
 *   battery (opcode 0x04): count, then per part [component 08 case / 04 left / 02 right] 01 [level] [status] 01,
 *            status 01 charging, 02 discharging, 04 disconnected;
 *   settings (opcode 0x09): [id] [value] 00 00 00 - 0x0D noise control 01 off / 02 noise cancellation /
 *            03 transparency / 04 adaptive; 0x28 conversation awareness 01 on / 02 off. The AirPods echo a change;
 *   ear detection (opcode 0x06): [primary] [secondary], 00 in ear / 01 out / 02 in case.
 * The AirPods keep the channel open and send changes by themselves, so the service keeps one session per pair and
 * answers earbuds_get from the latest state. Every received packet is logged (tag SongateAirPods) while the formats
 * are confirmed on Pro 3.
 */
final class AirPods {
    static final String TAG = "SongateAirPods";
    static final String AAP_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a";
    static final String[] MODES = { "off", "anc", "transparency", "adaptive" };   // AAP 01..04

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static Context sCtx;

    /**
     * Service start: remember the context and open the channel to AirPods that are already connected (after an
     * update or a service restart there is no new "connected" broadcast; user test 2026-11-19).
     */
    static void init(Context c) {
        sCtx = c.getApplicationContext();
        // Bluetooth may not be ready in the first seconds after a service start: look again for up to ~30 s
        new Thread(() -> {
            for (int attempt = 0; attempt < 15; attempt++) {
                if (attempt > 0) SystemClock.sleep(2000);
                if (!SESSIONS.isEmpty()) return;                       // a connect broadcast or Songate got there first
                try {
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    if (ad == null || !ad.isEnabled()) continue;
                    for (BluetoothDevice d : ad.getBondedDevices()) {
                        Object on = BluetoothDevice.class.getMethod("isConnected").invoke(d);
                        if (Boolean.TRUE.equals(on) && is(d)) {
                            Log.i(TAG, "already connected at start (look " + (attempt + 1) + ")");
                            if (session(d) != null) return;
                        }
                    }
                } catch (Throwable t) { Log.w(TAG, "start scan: " + t); }
            }
        }, "AirPods start").start();
    }

    // The case only reports its battery while a bud is inside with the lid open: keep the last level it gave
    // (user, 2026-11-19), per pair, on this device only.
    private static SharedPreferences casePrefs() {
        return sCtx == null ? null : sCtx.getSharedPreferences("airpods_case", Context.MODE_PRIVATE);
    }

    private static void rememberCase(BluetoothDevice d, int level, boolean charging) {
        SharedPreferences p = casePrefs();
        if (p == null || level < 0) return;
        p.edit().putInt(d.getAddress() + ".level", level).putBoolean(d.getAddress() + ".charging", charging)
                .putLong(d.getAddress() + ".time", System.currentTimeMillis()).apply();
    }

    private AirPods() { }

    /** AirPods (any model): Apple's accessory service is listed, or the name says so. */
    static boolean is(BluetoothDevice d) {
        try {
            ParcelUuid[] u = d.getUuids();
            if (u != null) for (ParcelUuid p : u) if (AAP_UUID.equalsIgnoreCase(p.toString())) return true;
            String n = d.getName();
            return n != null && n.toLowerCase(Locale.ROOT).contains("airpods");
        } catch (SecurityException e) { return false; }
    }

    // ------------------------------------------------------------------ one open channel per pair
    private static final class Session implements Runnable {
        final BluetoothDevice dev;
        BluetoothSocket sock;
        OutputStream out;
        volatile boolean alive;
        volatile int batL = -1, batR = -1, batC = -1;
        volatile String chgL = "0", chgR = "0", chgC = "0";
        volatile int mode = -1;               // 1..4 as AAP sends them
        volatile int conversation = -1;       // 1 on, 2 off
        volatile int adaptive = -1;           // Adaptive mode strength 0..100 (setting 0x2E)
        volatile int earPrimary = -1, earSecondary = -1;
        volatile long lastPacket;

        Session(BluetoothDevice d) { dev = d; }

        boolean open() {
            try {
                sock = (BluetoothSocket) BluetoothDevice.class.getMethod("createInsecureL2capSocket", int.class).invoke(dev, 0x1001);
                final BluetoothSocket s = sock;
                Thread watchdog = new Thread(() -> { try { Thread.sleep(8000); if (!alive) s.close(); } catch (Exception ignored) { } });
                watchdog.start();
                sock.connect();
                out = sock.getOutputStream();
                alive = true;
                send(new byte[]{ 0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0, 0, 0, 0, 0, 0, 0, 0 });       // handshake
                send(aap(0x4D, new byte[]{ (byte) 0xD7, 0, 0, 0, 0, 0, 0, 0 }));                              // feature flags
                send(aap(0x0F, new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }));              // notifications
                Thread t = new Thread(this, "AirPods " + dev.getAddress());
                t.setDaemon(true);
                t.start();
                // right after the pair (re)connects it can ignore the notification request (user test 2026-11-19:
                // no battery, ear or mode updates after taking them out of the case): ask again until battery comes
                Thread nudge = new Thread(() -> {
                    for (int i = 0; i < 4 && alive; i++) {
                        SystemClock.sleep(i == 0 ? 1500 : 2500);
                        if (!alive || batL >= 0 || batR >= 0) return;
                        try {
                            Log.i(TAG, "no battery yet: asking for notifications again");
                            send(aap(0x4D, new byte[]{ (byte) 0xD7, 0, 0, 0, 0, 0, 0, 0 }));
                            send(aap(0x0F, new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }));
                        } catch (IOException e) { return; }
                    }
                }, "AirPods nudge");
                nudge.setDaemon(true);
                nudge.start();
                Log.i(TAG, "connected");
                return true;
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                Log.w(TAG, "connect: " + c);
                close();
                return false;
            }
        }

        synchronized void send(byte[] p) throws IOException {
            Log.d(TAG, "tx " + Earbuds.hex(p, 0, Math.min(p.length, 48)));
            out.write(p); out.flush();
        }

        void close() {
            alive = false;
            try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        }

        @Override public void run() {
            byte[] buf = new byte[1024];
            try {
                InputStream in = sock.getInputStream();
                while (alive) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    lastPacket = SystemClock.elapsedRealtime();
                    parse(buf, n);
                }
            } catch (IOException e) {
                Log.i(TAG, "channel closed: " + e.getMessage());
            } finally {
                close();
                SESSIONS.remove(dev.getAddress());
                SystemApi.notify("earbuds");
            }
        }

        private void parse(byte[] b, int n) {
            Log.d(TAG, "rx " + Earbuds.hex(b, 0, Math.min(n, 48)));
            if (n < 6 || b[0] != 0x04 || b[1] != 0x00 || b[2] != 0x04 || b[3] != 0x00) return;
            int op = (b[4] & 0xff) | ((b[5] & 0xff) << 8);
            boolean changed = false;
            if (op == 0x04 && n >= 7) {                                   // battery
                int count = b[6] & 0xff;
                for (int i = 0; i < count && 7 + i * 5 + 4 < n; i++) {
                    int o = 7 + i * 5, comp = b[o] & 0xff, level = b[o + 2] & 0xff, st = b[o + 3] & 0xff;
                    // 01 charging, 05 = a bud sitting in the case (charging there; seen 2026-11-19), 02 not, 04 absent
                    String chg = st == 0x01 || st == 0x05 ? "1" : "0";
                    if (st == 0x04) level = -1;                           // that part isn't connected
                    if (comp == 0x04) { batL = level; chgL = chg; }
                    else if (comp == 0x02) { batR = level; chgR = chg; }
                    else if (comp == 0x08) { batC = level; chgC = chg; rememberCase(dev, level, st == 0x01); }
                }
                changed = true;
                SystemApi.notify("bt");                                   // the Bluetooth list shows it too
            } else if (op == 0x09 && n >= 8) {                            // a setting changed
                int id = b[6] & 0xff, v = b[7] & 0xff;
                if (id == 0x0D) { mode = v; changed = true; Log.i(TAG, "noise control " + v); }
                else if (id == 0x28) { conversation = v; changed = true; }
                else if (id == 0x2E) { adaptive = v; changed = true; Log.i(TAG, "adaptive level " + v); }
            } else if (op == 0x06 && n >= 8) {                            // ear detection
                earPrimary = b[6] & 0xff; earSecondary = b[7] & 0xff; changed = true;
                Log.i(TAG, "ear " + earPrimary + "/" + earSecondary);
            }
            if (changed) SystemApi.notify("earbuds");
        }
    }

    static byte[] aap(int opcode, byte[] payload) {
        byte[] p = new byte[6 + payload.length];
        p[0] = 0x04; p[1] = 0x00; p[2] = 0x04; p[3] = 0x00;
        p[4] = (byte) (opcode & 0xff); p[5] = (byte) ((opcode >> 8) & 0xff);
        System.arraycopy(payload, 0, p, 6, payload.length);
        return p;
    }

    private static Session session(BluetoothDevice d) {
        Session s = SESSIONS.get(d.getAddress());
        if (s != null && s.alive) return s;
        s = new Session(d);
        if (!s.open()) return null;
        SESSIONS.put(d.getAddress(), s);
        return s;
    }

    /** Called when an AirPods pair connects, so the state is there before Songate asks. */
    static void onConnected(BluetoothDevice d) {
        if (!is(d)) return;
        new Thread(() -> session(d), "AirPods connect").start();
    }

    static void onDisconnected(BluetoothDevice d) {
        Session s = SESSIONS.remove(d.getAddress());
        if (s != null) s.close();
    }

    // ------------------------------------------------------------------ API (through Earbuds.get / set)
    /** For the Bluetooth list: the lower earbud's level, -1 if no open channel or no level yet. No connecting. */
    static int battery(BluetoothDevice d) {
        Session s = SESSIONS.get(d.getAddress());
        if (s == null || !s.alive) return -1;
        if (s.batL >= 0 && s.batR >= 0) return Math.min(s.batL, s.batR);
        return Math.max(s.batL, s.batR);
    }

    static Bundle get(BluetoothDevice d) {
        Session s = session(d);
        if (s == null) return SystemApi.err("failed", "Couldn't reach the AirPods");
        long until = SystemClock.elapsedRealtime() + 2500;               // first answers after connecting
        while ((s.mode < 0 || s.batL < 0 && s.batR < 0) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
        return answer(d, s);
    }

    /** extras (only the given ones change): mode off|anc|transparency|adaptive, conversation "0"/"1". */
    static Bundle set(BluetoothDevice d, Bundle e) {
        Session s = session(d);
        if (s == null) return SystemApi.err("failed", "Couldn't reach the AirPods");
        if (e == null) e = new Bundle();
        try {
            String v;
            if ((v = e.getString("mode")) != null) {
                int i = java.util.Arrays.asList(MODES).indexOf(v);
                if (i < 0) return SystemApi.err("bad_arg", "Unknown mode value");
                int want = i + 1;
                // AirPods Pro refuse "off" until the "allow Off" listening-mode option (setting 0x34) is on; an
                // iPhone leaves it off by default (user test 2026-11-19: off fell back to cancelling)
                if (want == 1) { s.send(aap(0x09, new byte[]{ 0x34, 0x01, 0, 0, 0 })); SystemClock.sleep(150); }
                s.send(aap(0x09, new byte[]{ 0x0D, (byte) want, 0, 0, 0 }));
                long until = SystemClock.elapsedRealtime() + 2000;
                while (s.mode != want && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
                if (s.mode != want) { Bundle b = answer(d, s); b.putBoolean("ok", false); b.putString("error", "failed: The AirPods didn't take the change"); return b; }
            }
            if ((v = e.getString("conversation")) != null) {
                if (!"0".equals(v) && !"1".equals(v)) return SystemApi.err("bad_arg", "conversation must be 0 or 1");
                int want = "1".equals(v) ? 1 : 2;
                s.send(aap(0x09, new byte[]{ 0x28, (byte) want, 0, 0, 0 }));
                long until = SystemClock.elapsedRealtime() + 2000;
                while (s.conversation != want && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
                // the pair takes this setting without echoing it (user test 2026-11-19: off at the next connect),
                // so no answer means accepted; the real value comes back at the next connect
                if (s.conversation != want) { Log.i(TAG, "conversation " + want + ": no echo, taken as accepted"); s.conversation = want; }
            }
            if ((v = e.getString("adaptive_level")) != null) {
                int want;
                try { want = Integer.parseInt(v); } catch (NumberFormatException x) { want = -1; }
                if (want < 0 || want > 100) return SystemApi.err("bad_arg", "adaptive_level must be 0-100");
                // the pair never echoes this one (user test 2026-11-19), and waiting made a slider drag queue up
                // for seconds: send and answer at once, so Songate's slider can follow the finger
                s.send(aap(0x09, new byte[]{ 0x2E, (byte) want, 0, 0, 0 }));
                s.adaptive = want;
            }
        } catch (IOException x) {
            return SystemApi.err("failed", "The AirPods connection dropped");
        }
        SystemApi.notify("earbuds");
        return answer(d, s);
    }

    private static String ear(int v) { return v == 0 ? "in" : v == 1 ? "out" : v == 2 ? "case" : ""; }

    private static Bundle answer(BluetoothDevice d, Session s) {
        Bundle b = SystemApi.ok();
        b.putString("address", d.getAddress());
        b.putString("name", d.getName());
        b.putString("kind", "airpods");
        b.putInt("battery_left", s.batL);
        b.putInt("battery_right", s.batR);
        b.putInt("battery_case", s.batC);
        b.putString("charging_left", s.chgL);
        b.putString("charging_right", s.chgR);
        b.putString("charging_case", s.chgC);
        // the last level the case gave, for when it isn't reporting now: -1 = never seen; age in seconds
        SharedPreferences p = casePrefs();
        int last = p == null ? -1 : p.getInt(d.getAddress() + ".level", -1);
        long when = p == null ? 0 : p.getLong(d.getAddress() + ".time", 0);
        b.putInt("battery_case_last", last);
        b.putLong("battery_case_last_age_s", last < 0 ? -1 : Math.max(0, (System.currentTimeMillis() - when) / 1000));
        b.putString("charging_case_last", p != null && p.getBoolean(d.getAddress() + ".charging", false) ? "1" : "0");
        b.putStringArray("modes", MODES);
        b.putString("mode", s.mode >= 1 && s.mode <= 4 ? MODES[s.mode - 1] : "");
        b.putString("conversation", s.conversation == 1 ? "1" : s.conversation == 2 ? "0" : "");
        b.putInt("adaptive_level", s.adaptive);                 // 0..100, -1 = unknown; used in Adaptive mode only
        b.putString("ear_primary", ear(s.earPrimary));      // which bud is "primary" isn't documented yet
        b.putString("ear_secondary", ear(s.earSecondary));
        return b;
    }
}
