package com.andre.hibyr4sound;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Development only (2026-11-19): looking at Samsung Galaxy Buds before writing their support.
 *   earbuds_dev_uuids [address]                         -> the services (SDP UUIDs) the earbuds offer
 *   earbuds_dev_spp   [address] uuid=… ms=… send=hex     -> opens that RFCOMM channel, optionally sends one packet,
 *                                                          and returns everything received within ms (hex, per read)
 */
final class BudsProbe {
    private static final String TAG = "SongateBudsProbe";

    private BudsProbe() { }

    static Bundle uuids(BluetoothDevice d) {
        Bundle r = SystemApi.ok();
        ArrayList<String> out = new ArrayList<>();
        ParcelUuid[] u = d.getUuids();
        if (u != null) for (ParcelUuid p : u) out.add(p.toString());
        r.putStringArrayList("uuids", out);
        return r;
    }

    static Bundle spp(BluetoothDevice d, Bundle e) {
        String us = e == null ? null : e.getString("uuid");
        if (us == null) return SystemApi.err("bad_arg", "uuid missing");
        long ms = 6000;
        try { ms = Long.parseLong(e.getString("ms", "6000")); } catch (NumberFormatException ignored) { }
        String send = e.getString("send");
        ArrayList<String> rx = new ArrayList<>();
        BluetoothSocket s = null;
        try {
            s = d.createRfcommSocketToServiceRecord(UUID.fromString(us));
            s.connect();
            Log.i(TAG, "connected " + us);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            if (send != null && !send.isEmpty()) {
                byte[] p = hex(send);
                out.write(p); out.flush();
                Log.i(TAG, "tx " + send);
            }
            long until = SystemClock.elapsedRealtime() + ms;
            byte[] buf = new byte[2048];
            while (SystemClock.elapsedRealtime() < until) {
                if (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    String h = Earbuds.hex(buf, 0, n);
                    rx.add(h);
                    Log.i(TAG, "rx " + h);
                } else SystemClock.sleep(20);
            }
            Bundle r = SystemApi.ok();
            r.putStringArrayList("rx", rx);
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "spp " + us, t);
            Bundle r = SystemApi.err("failed", String.valueOf(t));
            r.putStringArrayList("rx", rx);
            return r;
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) { }
        }
    }

    private static byte[] hex(String h) {
        h = h.replaceAll("[^0-9a-fA-F]", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
