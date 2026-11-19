package com.andre.hibyr4sound;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Samsung Galaxy Buds controls (earbuds kind "galaxybuds"; user, 2026-11-19, tested on a Buds4 Pro). Our own code,
 * written from the protocol facts documented by the GalaxyBudsClient project (no code taken from it):
 *   channel:  RFCOMM, service 2e73a4ad-332d-41fc-90e2-16bef06523f2 (Buds3 / Buds4 generation); no handshake - the
 *             buds send their full status (0x61) by themselves as soon as it opens;
 *   framing:  FD | u16 LE header (bits 0-9 = id + payload + 2 CRC bytes) | id | payload | CRC16 LE | DD;
 *             CRC-16/XMODEM (poly 0x1021, init 0) over id + payload. RFCOMM is a stream: frames can arrive glued
 *             together or split, so they're cut out of a buffer;
 *   0x61 extended status: [2] battery L, [3] R, [6] placement (high nibble L, low R: 1 in ear, 2 out, 3 in case,
 *             4 case closed), [7] case battery, [12] noise control, [23] ambient volume, [24] noise reduction level,
 *             [26] detect conversations, [27] its timeout (0/1/2 = 5/10/15 s), [28] noise control with one bud,
 *             [42] charging bits (bit4 L, bit2 R, bit0 case);
 *   0x60 status: [1] L, [2] R, [5] placement, [6] case, [7] charging bits;  0x77 noise control changed: [0] mode;
 *   set: 0x78 noise control (0 off, 1 noise cancelling, 2 ambient), 0x84 ambient volume (0-4 on Buds4 Pro),
 *        0x83 noise reduction level, 0x7A detect conversations, 0x7B its timeout, 0x6F noise control with one bud.
 * Buds4 Pro drop the connection when settings arrive too fast (slider drags): sends are spaced (SPACING_MS).
 * Values decoded on the user's friend's pair 2026-11-19 (both out of ear, off, ambient 4: matched).
 */
final class GalaxyBuds {
    static final String TAG = "SongateGalaxyBuds";
    static final String SPP_UUID = "2e73a4ad-332d-41fc-90e2-16bef06523f2";
    static final String[] MODES = { "off", "anc", "ambient" };          // 0x78 values 0..2
    private static final long SPACING_MS = 200;

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static Context sCtx;

    private GalaxyBuds() { }

    static void init(Context c) {
        sCtx = c.getApplicationContext();
        new Thread(() -> {
            for (int attempt = 0; attempt < 15; attempt++) {
                if (attempt > 0) SystemClock.sleep(2000);
                if (!SESSIONS.isEmpty()) return;
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
        }, "GalaxyBuds start").start();
    }

    /** Galaxy Buds of the Buds3 / Buds4 generation: Samsung's control service is listed. */
    static boolean is(BluetoothDevice d) {
        try {
            ParcelUuid[] u = d.getUuids();
            if (u != null) for (ParcelUuid p : u) if (SPP_UUID.equalsIgnoreCase(p.toString())) return true;
        } catch (SecurityException ignored) { }
        return false;
    }

    // the case only reports while a bud is inside: keep its last level, like the AirPods
    private static SharedPreferences casePrefs() {
        return sCtx == null ? null : sCtx.getSharedPreferences("galaxybuds_case", Context.MODE_PRIVATE);
    }

    private static void rememberCase(BluetoothDevice d, int level, boolean charging) {
        SharedPreferences p = casePrefs();
        if (p == null || level <= 0) return;
        p.edit().putInt(d.getAddress() + ".level", level).putBoolean(d.getAddress() + ".charging", charging)
                .putLong(d.getAddress() + ".time", System.currentTimeMillis()).apply();
    }

    // ------------------------------------------------------------------ framing
    static int crc16(byte[] b, int off, int n) {
        int c = 0;
        for (int i = off; i < off + n; i++) {
            c ^= (b[i] & 0xff) << 8;
            for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x1021) & 0xffff : (c << 1) & 0xffff;
        }
        return c;
    }

    static byte[] frame(int id, byte... payload) {
        int len = 1 + payload.length + 2;
        byte[] p = new byte[len + 4];
        p[0] = (byte) 0xFD;
        p[1] = (byte) (len & 0xff); p[2] = (byte) ((len >> 8) & 0x03);
        p[3] = (byte) id;
        System.arraycopy(payload, 0, p, 4, payload.length);
        int c = crc16(p, 3, 1 + payload.length);
        p[4 + payload.length] = (byte) (c & 0xff); p[5 + payload.length] = (byte) (c >> 8);
        p[len + 3] = (byte) 0xDD;
        return p;
    }

    // ------------------------------------------------------------------ one open channel per pair
    private static final class Session implements Runnable {
        final BluetoothDevice dev;
        BluetoothSocket sock;
        OutputStream out;
        volatile boolean alive;
        volatile int batL = -1, batR = -1, batC = -1;
        volatile boolean chgL, chgR, chgC;
        volatile int placeL = -1, placeR = -1;
        volatile int mode = -1, ambient = -1, ancLevel = -1, conversation = -1, convTimeout = -1, oneBud = -1;
        volatile boolean haveStatus;
        volatile int ackMode = -1;                     // the last noise control the buds acknowledged
        final java.util.concurrent.atomic.AtomicInteger ambientSeq = new java.util.concurrent.atomic.AtomicInteger(),
                ancSeq = new java.util.concurrent.atomic.AtomicInteger();
        private long lastSend;
        private final ByteArrayOutputStream acc = new ByteArrayOutputStream();

        Session(BluetoothDevice d) { dev = d; }

        boolean open() {
            try {
                sock = dev.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID));
                final BluetoothSocket s = sock;
                Thread watchdog = new Thread(() -> { try { Thread.sleep(8000); if (!alive) s.close(); } catch (Exception ignored) { } });
                watchdog.start();
                sock.connect();
                out = sock.getOutputStream();
                alive = true;
                Thread t = new Thread(this, "GalaxyBuds " + dev.getAddress());
                t.setDaemon(true);
                t.start();
                Log.i(TAG, "connected");
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "connect: " + (t.getCause() != null ? t.getCause() : t));
                close();
                return false;
            }
        }

        /** Spaced sends: the Buds4 Pro drop the channel when settings come too fast. */
        synchronized void send(byte[] p) throws IOException {
            long wait = lastSend + SPACING_MS - SystemClock.elapsedRealtime();
            if (wait > 0) SystemClock.sleep(wait);
            Log.d(TAG, "tx " + Earbuds.hex(p, 0, Math.min(p.length, 48)));
            out.write(p); out.flush();
            lastSend = SystemClock.elapsedRealtime();
        }

        void close() {
            alive = false;
            try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        }

        @Override public void run() {
            byte[] buf = new byte[2048];
            try {
                InputStream in = sock.getInputStream();
                while (alive) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    acc.write(buf, 0, n);
                    cut();
                }
            } catch (IOException e) {
                Log.i(TAG, "channel closed: " + e.getMessage());
            } finally {
                close();
                SESSIONS.remove(dev.getAddress());
                SystemApi.notify("earbuds");
            }
        }

        /** Cut whole frames out of the received bytes. */
        private void cut() {
            byte[] b = acc.toByteArray();
            int i = 0;
            while (true) {
                while (i < b.length && b[i] != (byte) 0xFD) i++;
                if (b.length - i < 4) break;
                int len = ((b[i + 1] & 0xff) | ((b[i + 2] & 0xff) << 8)) & 0x3ff;
                if (len < 3) { i++; continue; }
                int end = i + 3 + len;                       // index of DD
                if (end >= b.length) break;                  // not all here yet
                if (b[end] != (byte) 0xDD) { i++; continue; }
                int crc = (b[end - 2] & 0xff) | ((b[end - 1] & 0xff) << 8);
                if (crc16(b, i + 3, len - 2) == crc) {
                    int id = b[i + 3] & 0xff;
                    byte[] p = Arrays.copyOfRange(b, i + 4, end - 2);
                    try { parse(id, p); } catch (RuntimeException x) { Log.w(TAG, "parse " + id, x); }
                } else Log.w(TAG, "bad CRC, dropped");
                i = end + 1;
            }
            acc.reset();
            if (i < b.length) acc.write(b, i, b.length - i);
        }

        private int at(byte[] p, int i) { return i < p.length ? p[i] & 0xff : -1; }

        private void placement(int v) { if (v >= 0) { placeL = v >> 4; placeR = v & 0x0f; } }

        private void charging(int v) { if (v >= 0) { chgL = (v & 0x10) != 0; chgR = (v & 0x04) != 0; chgC = (v & 0x01) != 0; } }

        private void parse(int id, byte[] p) {
            // 0xCD (model + serial number) and 0x68 (firmware) aren't logged: the serial is personal
            if (id != 0xCD && id != 0x68) Log.d(TAG, "rx " + String.format("%02x", id) + " " + Earbuds.hex(p, 0, Math.min(p.length, 48)));
            boolean changed = true;
            if (id == 0x61 && p.length >= 29) {                  // full status
                batL = at(p, 2); batR = at(p, 3); placement(at(p, 6)); batC = at(p, 7);
                mode = at(p, 12); ambient = at(p, 23); ancLevel = at(p, 24);
                conversation = at(p, 26); convTimeout = at(p, 27); oneBud = at(p, 28);
                charging(at(p, 42));
                haveStatus = true;
                rememberCase(dev, batC, chgC);
                SystemApi.notify("bt");
            } else if (id == 0x60 && p.length >= 7) {            // battery / placement
                batL = at(p, 1); batR = at(p, 2); placement(at(p, 5)); batC = at(p, 6);
                charging(at(p, 7));
                rememberCase(dev, batC, chgC);
                SystemApi.notify("bt");
            } else if (id == 0x77 && p.length >= 1) {            // noise control changed (also from a touch on the buds)
                mode = at(p, 0); Log.i(TAG, "noise control " + mode);
            } else if (id == 0x42 && p.length >= 2) {            // acknowledgement: [what was set] [value]
                int what = at(p, 0), v = at(p, 1);
                if (what == 0x78) { mode = v; ackMode = v; }
                else if (what == 0x84) ambient = v;
                else if (what == 0x83) ancLevel = v;
                else if (what == 0x7A) conversation = v;
                else if (what == 0x7B) convTimeout = v;
                else if (what == 0x6F) oneBud = v;
                else changed = false;
            } else changed = false;
            if (changed) SystemApi.notify("earbuds");
        }
    }

    private static Session session(BluetoothDevice d) {
        Session s = SESSIONS.get(d.getAddress());
        if (s != null && s.alive) return s;
        s = new Session(d);
        if (!s.open()) return null;
        SESSIONS.put(d.getAddress(), s);
        return s;
    }

    static void onConnected(BluetoothDevice d) {
        if (!is(d)) return;
        new Thread(() -> session(d), "GalaxyBuds connect").start();
    }

    static void onDisconnected(BluetoothDevice d) {
        Session s = SESSIONS.remove(d.getAddress());
        if (s != null) s.close();
    }

    // ------------------------------------------------------------------ API (through Earbuds.get / set)
    static Bundle get(BluetoothDevice d) {
        Session s = session(d);
        if (s == null) return SystemApi.err("failed", "Couldn't reach the Galaxy Buds");
        long until = SystemClock.elapsedRealtime() + 2500;
        while (!s.haveStatus && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
        return answer(d, s);
    }

    private static int intArg(String v, int min, int max) {
        try { int n = Integer.parseInt(v); return n < min || n > max ? Integer.MIN_VALUE : n; }
        catch (NumberFormatException x) { return Integer.MIN_VALUE; }
    }

    /**
     * extras (only the given ones change): mode off|anc|ambient, ambient_level 0-4, anc_level 0-2,
     * conversation "0"/"1", conversation_timeout 5|10|15, one_bud "0"/"1". Levels: only the newest value of a burst is sent.
     */
    static Bundle set(BluetoothDevice d, Bundle e) {
        Session s = session(d);
        if (s == null) return SystemApi.err("failed", "Couldn't reach the Galaxy Buds");
        if (e == null) e = new Bundle();
        try {
            String v;
            if ((v = e.getString("mode")) != null) {
                int i = Arrays.asList(MODES).indexOf(v);
                if (i < 0) return SystemApi.err("bad_arg", "Unknown mode value");
                s.ackMode = -1;
                s.send(frame(0x78, (byte) i));
                long until = SystemClock.elapsedRealtime() + 1500;   // the buds acknowledge in ~0.3-0.5 s (0x42)
                while (s.ackMode != i && SystemClock.elapsedRealtime() < until) SystemClock.sleep(30);
                if (s.ackMode != i) { Log.i(TAG, "mode " + i + ": no acknowledgement, taken as accepted"); s.mode = i; }
            }
            if ((v = e.getString("ambient_level")) != null) {
                int n = intArg(v, 0, 4);
                if (n == Integer.MIN_VALUE) return SystemApi.err("bad_arg", "ambient_level must be 0-4");
                int my = s.ambientSeq.incrementAndGet();
                synchronized (s) {            // a slider drag: only the newest value goes out (sends are spaced)
                    if (my == s.ambientSeq.get()) { s.send(frame(0x84, (byte) n)); s.ambient = n; }
                }
            }
            if ((v = e.getString("anc_level")) != null) {
                // 0 = weakest .. 2 = strongest (2 is the buds' default; 0 vs 2 heard by the user 2026-11-19)
                int n = intArg(v, 0, 2);
                if (n == Integer.MIN_VALUE) return SystemApi.err("bad_arg", "anc_level must be 0-2");
                int my = s.ancSeq.incrementAndGet();
                synchronized (s) {
                    if (my == s.ancSeq.get()) { s.send(frame(0x83, (byte) n)); s.ancLevel = n; }
                }
            }
            if ((v = e.getString("conversation")) != null) {
                if (!"0".equals(v) && !"1".equals(v)) return SystemApi.err("bad_arg", "conversation must be 0 or 1");
                int n = "1".equals(v) ? 1 : 0;
                s.send(frame(0x7A, (byte) n)); s.conversation = n;
            }
            if ((v = e.getString("conversation_timeout")) != null) {
                int n = "5".equals(v) ? 0 : "10".equals(v) ? 1 : "15".equals(v) ? 2 : -1;
                if (n < 0) return SystemApi.err("bad_arg", "conversation_timeout must be 5, 10 or 15");
                s.send(frame(0x7B, (byte) n)); s.convTimeout = n;
            }
            if ((v = e.getString("one_bud")) != null) {
                if (!"0".equals(v) && !"1".equals(v)) return SystemApi.err("bad_arg", "one_bud must be 0 or 1");
                int n = "1".equals(v) ? 1 : 0;
                s.send(frame(0x6F, (byte) n)); s.oneBud = n;
            }
        } catch (IOException x) {
            return SystemApi.err("failed", "The Galaxy Buds connection dropped");
        }
        SystemApi.notify("earbuds");
        return answer(d, s);
    }

    private static String ear(int v) { return v == 1 ? "in" : v == 2 ? "out" : v == 3 || v == 4 ? "case" : ""; }

    private static Bundle answer(BluetoothDevice d, Session s) {
        Bundle b = SystemApi.ok();
        b.putString("address", d.getAddress());
        b.putString("name", d.getName());
        b.putString("kind", "galaxybuds");
        boolean inL = s.placeL > 0, inR = s.placeR > 0;
        b.putInt("battery_left", inL || s.placeL < 0 ? s.batL : -1);
        b.putInt("battery_right", inR || s.placeR < 0 ? s.batR : -1);
        // the case reports 0 while no bud is inside: treat that as "not reporting now"
        b.putInt("battery_case", s.batC > 0 ? s.batC : -1);
        b.putString("charging_left", s.chgL ? "1" : "0");
        b.putString("charging_right", s.chgR ? "1" : "0");
        b.putString("charging_case", s.chgC ? "1" : "0");
        SharedPreferences p = casePrefs();
        int last = p == null ? -1 : p.getInt(d.getAddress() + ".level", -1);
        long when = p == null ? 0 : p.getLong(d.getAddress() + ".time", 0);
        b.putInt("battery_case_last", last);
        b.putLong("battery_case_last_age_s", last < 0 ? -1 : Math.max(0, (System.currentTimeMillis() - when) / 1000));
        b.putString("charging_case_last", p != null && p.getBoolean(d.getAddress() + ".charging", false) ? "1" : "0");
        b.putStringArray("modes", MODES);
        b.putString("mode", s.mode >= 0 && s.mode < MODES.length ? MODES[s.mode] : "");
        b.putInt("ambient_level", s.ambient);          // 0..4, -1 unknown
        b.putInt("ambient_level_max", 4);
        b.putInt("anc_level", s.ancLevel);             // noise cancelling strength 0..2, -1 unknown
        b.putInt("anc_level_max", 2);
        b.putString("conversation", s.conversation == 1 ? "1" : s.conversation == 0 ? "0" : "");
        b.putInt("conversation_timeout", s.convTimeout == 0 ? 5 : s.convTimeout == 1 ? 10 : s.convTimeout == 2 ? 15 : -1);
        b.putString("one_bud", s.oneBud == 1 ? "1" : s.oneBud == 0 ? "0" : "");
        b.putString("ear_left", ear(s.placeL));
        b.putString("ear_right", ear(s.placeR));
        return b;   // no serial number, no raw bytes
    }
}
