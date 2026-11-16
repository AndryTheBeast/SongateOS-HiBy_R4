package com.andre.hibyr4sound;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * SongateOS system API, phase 2: earbuds with their own controls (noise cancelling, battery…). First kind: Soundcore
 * (Anker), which talks over a plain Bluetooth serial (RFCOMM) channel. Our own code, written from the protocol notes
 * published by the OpenSCQ30 project (framing: header, 2-byte command, little-endian total length, sum checksum).
 * Supported: Liberty 4 NC (A3947). The status layout was confirmed on the R4.
 */
final class Earbuds {
    static final String TAG = "SongateEarbuds";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final String SOUNDCORE_UUID_PREFIX = "0cf12d31-fac3-4553-bd80-d6832e7";   // vendor UUID, last 5 hex vary
    private static final byte[] OUT_HEADER = { 0x08, (byte) 0xee, 0x00, 0x00, 0x00 };
    private static final byte[] IN_HEADER = { 0x09, (byte) 0xff, 0x00, 0x00, 0x01 };

    private Earbuds() { }

    static UUID soundcoreUuid(BluetoothDevice d) {
        ParcelUuid[] u = d.getUuids();
        if (u != null) for (ParcelUuid p : u) if (p.toString().toLowerCase(Locale.ROOT).startsWith(SOUNDCORE_UUID_PREFIX)) return p.getUuid();
        return null;
    }

    static byte[] packet(int c0, int c1, byte[] body) {
        int len = 5 + 2 + 2 + body.length + 1;
        byte[] p = new byte[len];
        System.arraycopy(OUT_HEADER, 0, p, 0, 5);
        p[5] = (byte) c0; p[6] = (byte) c1;
        p[7] = (byte) (len & 0xff); p[8] = (byte) ((len >> 8) & 0xff);
        System.arraycopy(body, 0, p, 9, body.length);
        int sum = 0;
        for (int i = 0; i < len - 1; i++) sum += p[i] & 0xff;
        p[len - 1] = (byte) sum;
        return p;
    }

    static String hex(byte[] b, int off, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = off; i < off + n && i < b.length; i++) s.append(String.format(Locale.ROOT, "%02x", b[i] & 0xff));
        return s.toString();
    }

    /** Sends one command and returns the body of the first reply with the wanted command, or throws. */
    static byte[] exchange(BluetoothDevice d, UUID uuid, byte[][] outs, int want0, int want1, long timeoutMs) throws IOException {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && a.isDiscovering()) a.cancelDiscovery();
        final BluetoothSocket s = d.createRfcommSocketToServiceRecord(uuid);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(timeoutMs); s.close(); } catch (InterruptedException ignored) { } catch (IOException ignored) { }
        });
        watchdog.start();
        try {
            s.connect();
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();
            for (int k = 0; k < outs.length; k++) {
                if (k > 0) try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                os.write(outs[k]);
                os.flush();
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[1024];
            while (true) {
                int n = is.read(tmp);
                if (n < 0) throw new IOException("closed");
                buf.write(tmp, 0, n);
                byte[] b = buf.toByteArray();
                int i = 0;
                while (true) {
                    int h = find(b, i);
                    if (h < 0 || b.length - h < 10) break;
                    int len = (b[h + 7] & 0xff) | ((b[h + 8] & 0xff) << 8);
                    if (len < 10 || len > 4096) { i = h + 1; continue; }
                    if (b.length - h < len) break;
                    int sum = 0;
                    for (int k = h; k < h + len - 1; k++) sum += b[k] & 0xff;
                    boolean good = (byte) sum == b[h + len - 1];
                    int c0 = b[h + 5] & 0xff, c1 = b[h + 6] & 0xff;
                    Log.i(TAG, "in " + String.format(Locale.ROOT, "%02x%02x", c0, c1) + " len " + len + (good ? "" : " BAD SUM")
                            );   // never the body: the status holds the earbuds' serial number
                    if (good && c0 == want0 && c1 == want1) {
                        byte[] body = new byte[len - 10];
                        System.arraycopy(b, h + 9, body, 0, body.length);
                        return body;
                    }
                    i = h + len;
                }
            }
        } finally {
            watchdog.interrupt();
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static int find(byte[] b, int from) {
        outer:
        for (int i = from; i <= b.length - 5; i++) {
            for (int k = 0; k < 5; k++) if (b[i + k] != IN_HEADER[k]) continue outer;
            return i;
        }
        return -1;
    }

    private static String ascii(byte[] b, int off, int n) {
        if (b.length < off + n) return "";
        boolean blank = true;
        for (int i = off; i < off + n; i++) if (b[i] != 0 && b[i] != (byte) 0xff) blank = false;
        return blank ? "" : new String(b, off, n, StandardCharsets.US_ASCII);
    }

    // Liberty 4 NC (A3947) status: battery at 2..5, firmware 6..15, the 7 sound-mode bytes at 126 (found on the R4:
    // 126 changed 0 -> 1 when the buds were switched to transparency). Set = command 06 81 with the same 7 bytes.
    private static final int MODES = 126;
    private static final String[] MODE = { "noise_cancelling", "transparency", "normal" };
    private static final String[] ANC = { "manual", "adaptive", "transport" };
    private static final String[] TRANSPARENCY = { "full", "vocal" };
    private static final String[] TRANSPORT = { "plane", "train", "bus", "car" };

    private static boolean liberty4nc(BluetoothDevice d) {
        String n = d.getName();
        return n != null && n.toLowerCase(Locale.ROOT).replace(" ", "").contains("liberty4nc");
    }
    private static String name(String[] names, int v) { return v >= 0 && v < names.length ? names[v] : String.valueOf(v); }
    private static int index(String[] names, String v) { for (int i = 0; i < names.length; i++) if (names[i].equals(v)) return i; return -1; }

    private static byte[] state(BluetoothDevice d, UUID u, byte[] before) throws IOException {
        byte[][] outs = before == null ? new byte[][]{ packet(0x01, 0x01, new byte[0]) }
                : new byte[][]{ before, packet(0x01, 0x01, new byte[0]) };
        byte[] st = exchange(d, u, outs, 0x01, 0x01, 6000);
        if (st.length < MODES + 7) throw new IOException("short status (" + st.length + " bytes)");
        return st;
    }

    private static Bundle answer(BluetoothDevice d, byte[] st) {
        Bundle b = SystemApi.ok();
        b.putString("address", d.getAddress());
        b.putString("name", d.getName());
        b.putString("kind", "soundcore");
        b.putInt("battery_left", Math.min(5, st[2] & 0xff) * 20);
        b.putInt("battery_right", Math.min(5, st[3] & 0xff) * 20);
        b.putString("charging_left", st[4] != 0 ? "1" : "0");
        b.putString("charging_right", st[5] != 0 ? "1" : "0");
        b.putString("firmware", ascii(st, 6, 5));
        b.putStringArray("modes", MODE);
        b.putString("mode", name(MODE, st[MODES] & 0xff));
        b.putString("anc", name(ANC, st[MODES + 3] & 0xff));
        b.putInt("anc_level", (st[MODES + 1] >> 4) & 0x0f);
        b.putString("transparency", name(TRANSPARENCY, st[MODES + 2] & 0xff));
        b.putString("wind", (st[MODES + 4] & 1) != 0 ? "1" : "0");
        b.putString("transport", name(TRANSPORT, st[MODES + 6] & 0xff));
        return b;   // no serial number, no raw bytes
    }

    private static UUID uuidOrNull(BluetoothDevice d) { return liberty4nc(d) ? soundcoreUuid(d) : null; }

    static Bundle get(BluetoothDevice d) throws IOException {
        UUID u = uuidOrNull(d);
        if (u == null) return SystemApi.err("unsupported", "The R4 can't control these earbuds yet");
        return answer(d, state(d, u, null));
    }

    /** extras (all optional, only the given ones change): mode, anc, anc_level 1-5, transparency, wind "0"/"1", transport. */
    static Bundle set(BluetoothDevice d, Bundle e) throws IOException {
        UUID u = uuidOrNull(d);
        if (u == null) return SystemApi.err("unsupported", "The R4 can't control these earbuds yet");
        if (e == null) e = new Bundle();
        byte[] st = state(d, u, null);
        byte[] m = new byte[7];
        System.arraycopy(st, MODES, m, 0, 7);
        String v;
        if ((v = e.getString("mode")) != null) { int i = index(MODE, v); if (i < 0) return bad("mode"); m[0] = (byte) i; }
        if ((v = e.getString("anc")) != null) { int i = index(ANC, v); if (i < 0) return bad("anc"); m[3] = (byte) i; }
        if ((v = e.getString("anc_level")) != null) {
            int n; try { n = Integer.parseInt(v); } catch (Exception x) { n = -1; }
            if (n < 1 || n > 5) return SystemApi.err("bad_arg", "anc_level must be 1 to 5");
            m[1] = (byte) ((n << 4) | (m[1] & 0x0f));
        }
        if ((v = e.getString("transparency")) != null) { int i = index(TRANSPARENCY, v); if (i < 0) return bad("transparency"); m[2] = (byte) i; }
        if ((v = e.getString("wind")) != null) {
            if (!"0".equals(v) && !"1".equals(v)) return bad("wind");
            m[4] = (byte) (("1".equals(v) ? 1 : 0) | (m[4] & 0x02));
        }
        if ((v = e.getString("transport")) != null) { int i = index(TRANSPORT, v); if (i < 0) return bad("transport"); m[6] = (byte) i; }
        m[4] &= 0x01;   // bit 1 = "wind detected" is reported by the buds, not set
        byte[] after = state(d, u, packet(0x06, 0x81, m));
        Bundle b = answer(d, after);
        boolean took = after[MODES] == m[0] && after[MODES + 3] == m[3] && after[MODES + 2] == m[2]
                && (after[MODES + 1] & 0xf0) == (m[1] & 0xf0);
        if (!took) { b.putBoolean("ok", false); b.putString("error", "failed: The earbuds didn't take the change"); }
        SystemApi.notify("earbuds");
        return b;
    }

    private static Bundle bad(String what) { return SystemApi.err("bad_arg", "Unknown " + what + " value"); }
}
