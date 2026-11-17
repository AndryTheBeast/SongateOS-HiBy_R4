package com.andre.hibyr4sound;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Bundle;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Development only (2026-11-16): first look at AirPods on the R4, before an "airpods" kind in Earbuds.
 *  - bleScan: 10 s Bluetooth LE scan for Apple's "proximity pairing" broadcast (manufacturer 0x004C, type 0x07), the
 *    one an iPhone uses for its pop-up card: battery, charging, in-ear. Read-only, needs no Bluetooth stack fix.
 *  - aapProbe: one attempt to open the AirPods control channel (classic L2CAP, PSM 0x1001) and send the opening
 *    handshake. Android's stack has a bug that usually blocks this (LibrePods works around it); this tells whether it
 *    does on this GSI. Nothing is changed on the AirPods: only the handshake is sent, then the channel is closed.
 */
final class AirPodsProbe {
    static final String TAG = "SongateAirPods";
    private static final int APPLE = 0x004C;

    private AirPodsProbe() { }

    static Bundle bleScan(long ms) throws InterruptedException {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) return SystemApi.err("off", "Bluetooth is off");
        BluetoothLeScanner sc = a.getBluetoothLeScanner();
        if (sc == null) return SystemApi.err("off", "Bluetooth LE isn't available");
        final Map<String, int[]> seen = Collections.synchronizedMap(new LinkedHashMap<>());   // payload hex -> {count, best rssi}
        ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                if (r.getScanRecord() == null) return;
                byte[] m = r.getScanRecord().getManufacturerSpecificData(APPLE);
                if (m == null || m.length < 2 || m[0] != 0x07) return;   // proximity pairing only
                String h = Earbuds.hex(m, 0, m.length);
                synchronized (seen) {
                    int[] v = seen.get(h);
                    if (v == null) seen.put(h, new int[]{ 1, r.getRssi() });
                    else { v[0]++; v[1] = Math.max(v[1], r.getRssi()); }
                }
            }
        };
        ScanFilter f = new ScanFilter.Builder().setManufacturerData(APPLE, new byte[]{ 0x07 }, new byte[]{ (byte) 0xff }).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        sc.startScan(Collections.singletonList(f), s, cb);
        try { Thread.sleep(ms); } finally { sc.stopScan(cb); }
        Bundle b = SystemApi.ok();
        ArrayList<Bundle> list = new ArrayList<>();
        synchronized (seen) {
            for (Map.Entry<String, int[]> e : seen.entrySet()) {
                Bundle x = new Bundle();
                x.putString("data", e.getKey());
                x.putInt("count", e.getValue()[0]);
                x.putInt("rssi", e.getValue()[1]);
                list.add(x);
            }
        }
        b.putParcelableArrayList("broadcasts", list);
        return b;
    }

    static Bundle aapProbe(BluetoothDevice d) {
        Bundle b = SystemApi.ok();
        BluetoothSocket s = null;
        try {
            // classic (BR/EDR) L2CAP socket: hidden BluetoothDevice.createInsecureL2capSocket(int psm)
            s = (BluetoothSocket) BluetoothDevice.class.getMethod("createInsecureL2capSocket", int.class).invoke(d, 0x1001);
            final BluetoothSocket fs = s;
            Thread watchdog = new Thread(() -> { try { Thread.sleep(8000); fs.close(); } catch (Exception ignored) { } });
            watchdog.start();
            s.connect();
            b.putString("connect", "ok");
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();
            // AAP opening handshake (as documented by the LibrePods project)
            os.write(new byte[]{ 0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 });
            os.flush();
            byte[] buf = new byte[512];
            int n = is.read(buf);
            b.putInt("reply_length", n);
            if (n > 0) b.putString("reply_start", Earbuds.hex(buf, 0, Math.min(n, 16)));   // header only
            watchdog.interrupt();
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            b.putString("connect", "failed");
            b.putString("why", c.getClass().getSimpleName() + ": " + c.getMessage());
            Log.w(TAG, "aap probe", c);
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) { }
        }
        return b;
    }
}
