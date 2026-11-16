package com.andre.hibyr4sound;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SongateOS system API, phase 1 (design: SYSTEM_API.md): what Songate needs to show Wi-Fi, Bluetooth, the audio output
 * switcher and power in its own UI. Android 14 lets only system apps do most of this; this service runs as the system
 * uid. Methods arrive through HiBySettingsProvider.call(); every one needs SYSTEM_SETTINGS (Songate's certificate) or
 * the system uid, except sys_info (HIBY_SETTINGS). Answers: "ok" (Boolean) and, on failure, "error" = "code: text".
 * Changes are announced with notifyChange on content://com.andre.hibyr4.settings/system/<area>.
 */
final class SystemApi {
    static final String TAG = "SongateSysApi";
    static final String PERM = "com.andre.hibyr4.permission.SYSTEM_SETTINGS";
    static final String BASE = "content://com.andre.hibyr4.settings/system/";
    static final int API = 1;
    static final String[] CAPS = { "wifi", "bt", "output", "theme", "power", "ota" };

    private static Context sApp;
    private static Handler sH;
    private static final Set<String> sPending = new HashSet<>();

    private SystemApi() { }

    // ------------------------------------------------------------------ start, permission, notifications
    static void start(Context c) {
        if (sApp != null) return;
        sApp = c.getApplicationContext();
        HandlerThread t = new HandlerThread("SystemApi");
        t.start();
        sH = new Handler(t.getLooper());
        IntentFilter wifi = new IntentFilter();
        wifi.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wifi.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        wifi.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        wifi.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        wifi.addAction("android.net.wifi.CONFIGURED_NETWORKS_CHANGE");
        sApp.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { onWifiEvent(i); }
        }, wifi, null, sH, Context.RECEIVER_EXPORTED);
        IntentFilter bt = new IntentFilter();
        for (String a : new String[]{ BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter.ACTION_DISCOVERY_STARTED,
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED, BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED,
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_NAME_CHANGED,
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED",
                "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED",
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" }) bt.addAction(a);
        sApp.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { onBtEvent(i); }
        }, bt, null, sH, Context.RECEIVER_EXPORTED);
        audio().registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { onOutputsChanged(added, null); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { onOutputsChanged(null, removed); }
        }, sH);
        sH.postDelayed(SystemApi::restoreOutputChoice, 5000);
    }

    /** Songate (its certificate, through the permission) or the system / platform apps. */
    static boolean trusted(Context c) {
        return Binder.getCallingUid() == Process.SYSTEM_UID
                || c.checkCallingPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    /** Coalesced: at most one notification per area every 300 ms. */
    static void notify(String area) {
        if (sH == null) return;
        synchronized (sPending) { if (!sPending.add(area)) return; }
        sH.postDelayed(() -> {
            synchronized (sPending) { sPending.remove(area); }
            sApp.getContentResolver().notifyChange(Uri.parse(BASE + area), null);
        }, 300);
    }

    static Bundle ok() { Bundle b = new Bundle(); b.putBoolean("ok", true); return b; }
    static Bundle err(String code, String text) {
        Bundle b = new Bundle(); b.putBoolean("ok", false); b.putString("error", code + ": " + text); return b;
    }
    private static Bundle res(boolean ok, String failText) { return ok ? ok() : err("failed", failText); }

    /** Returns null when the method isn't part of the system API (the provider goes on with its own methods). */
    static Bundle call(Context c, String method, String arg, Bundle extras) {
        if ("sys_info".equals(method)) return sysInfo();
        boolean ours = method.startsWith("wifi_") || method.startsWith("bt_") || method.startsWith("output_")
                || method.equals("power");
        if (!ours) return null;
        if (!trusted(c)) return err("no_permission", "Only Songate can change system settings");
        String v = extras == null ? null : extras.getString("value");
        try {
            switch (method) {
                case "wifi_get": return wifiGet();
                case "wifi_set_enabled": return bool(v) == null ? err("bad_arg", "value must be 0 or 1") : wifiSetEnabled(bool(v));
                case "wifi_scan": return wifiScan();
                case "wifi_connect": return wifiConnect(extras);
                case "wifi_connect_saved": return wifiConnectSaved(arg);
                case "wifi_forget": return wifiForget(arg);
                case "bt_get": return btGet();
                case "bt_set_enabled": return bool(v) == null ? err("bad_arg", "value must be 0 or 1") : btSetEnabled(bool(v));
                case "bt_discover": return btDiscover(arg);
                case "bt_pair": case "bt_connect": case "bt_disconnect": case "bt_unpair": return btDevice(method, arg);
                case "output_get": return outputGet();
                case "output_set": return outputSet(arg);
                case "power": return power(arg);
            }
        } catch (Throwable t) {
            Log.w(TAG, method, t);
            return err("failed", "Something went wrong (" + t.getClass().getSimpleName() + ")");
        }
        return err("unsupported", "Unknown method " + method);
    }

    private static Boolean bool(String v) { return "1".equals(v) ? Boolean.TRUE : "0".equals(v) ? Boolean.FALSE : null; }

    static Bundle sysInfo() {
        Bundle b = ok();
        b.putInt("api", API);
        b.putStringArray("caps", CAPS);
        b.putString("songateos", Updater.currentVersion());
        b.putString("ready", Updater.sysprop("sys.hibyr4.ready", ""));
        return b;
    }

    // ------------------------------------------------------------------ Wi-Fi
    private static WifiManager wifi() { return sApp.getSystemService(WifiManager.class); }

    private static volatile boolean sScanning;
    private static String sTarget;          // network the user asked to join
    private static long sTargetAt;
    private static String sFail;            // wrong_password | no_ip | rejected | timeout (after a failed join)
    private static boolean sWasAuthenticating;

    private static void onWifiEvent(Intent i) {
        String a = i.getAction();
        if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(a)) sScanning = false;
        if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(a) && sTarget != null) {
            SupplicantState s = i.getParcelableExtra(WifiManager.EXTRA_NEW_STATE, SupplicantState.class);
            if (s == SupplicantState.FOUR_WAY_HANDSHAKE || s == SupplicantState.AUTHENTICATING) sWasAuthenticating = true;
            if (i.getIntExtra("supplicantError", 0) == 1 /* WifiManager.ERROR_AUTHENTICATING */) failJoin("wrong_password");
        }
        checkJoin();
        notify("wifi");
    }

    private static void failJoin(String why) {
        if (sTarget == null) return;
        sFail = why;
        sTarget = null;
        Log.i(TAG, "join failed: " + why);
    }

    /** Joined the target → clear; Android disabled it for a wrong password → wrong_password; 45 s → timeout. */
    private static void checkJoin() {
        if (sTarget == null) return;
        WifiInfo wi = wifi().getConnectionInfo();
        if (sTarget.equals(unquote(wi.getSSID())) && wi.getSupplicantState() == SupplicantState.COMPLETED && wi.getIpAddress() != 0) {
            sTarget = null; sFail = null; sWasAuthenticating = false;
            return;
        }
        WifiConfiguration cfg = savedBySsid(sTarget);
        if (cfg != null && disableReasonIsWrongPassword(cfg)) { failJoin("wrong_password"); return; }
        if (SystemClock.elapsedRealtime() - sTargetAt > 45_000) failJoin(sWasAuthenticating ? "no_ip" : "timeout");
    }

    private static boolean disableReasonIsWrongPassword(WifiConfiguration cfg) {
        try {
            Object st = WifiConfiguration.class.getMethod("getNetworkSelectionStatus").invoke(cfg);
            int reason = (Integer) st.getClass().getMethod("getNetworkSelectionDisableReason").invoke(st);
            int wrong = st.getClass().getField("DISABLED_BY_WRONG_PASSWORD").getInt(null);
            return reason == wrong;
        } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("unchecked")
    private static List<WifiConfiguration> saved() {
        try {
            return (List<WifiConfiguration>) WifiManager.class.getMethod("getPrivilegedConfiguredNetworks").invoke(wifi());
        } catch (Throwable t) {
            List<WifiConfiguration> l = wifi().getConfiguredNetworks();
            return l == null ? new ArrayList<>() : l;
        }
    }

    private static WifiConfiguration savedBySsid(String ssid) {
        for (WifiConfiguration c : saved()) if (ssid.equals(unquote(c.SSID))) return c;
        return null;
    }

    private static String unquote(String s) {
        if (s == null || "<unknown ssid>".equals(s)) return "";
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static int level(int rssi) {
        WifiManager wm = wifi();
        int max = Math.max(1, wm.getMaxSignalLevel());
        return Math.round(wm.calculateSignalLevel(rssi) * 4f / max);
    }

    private static String scanSecurity(String caps) {
        if (caps == null) return "open";
        if (caps.contains("EAP")) return "eap";
        if (caps.contains("SAE") && !caps.contains("PSK")) return "sae";
        if (caps.contains("PSK") || caps.contains("SAE")) return "psk";
        if (caps.contains("OWE")) return "owe";
        if (caps.contains("WEP")) return "wep";
        return "open";
    }

    private static String savedSecurity(WifiConfiguration c) {
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) return "sae";
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) return "psk";
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) return "owe";
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) return "eap";
        return "open";
    }

    private static String currentSecurity(WifiInfo wi) {
        switch (wi.getCurrentSecurityType()) {
            case WifiInfo.SECURITY_TYPE_OPEN: return "open";
            case WifiInfo.SECURITY_TYPE_OWE: return "owe";
            case WifiInfo.SECURITY_TYPE_SAE: return "sae";
            case WifiInfo.SECURITY_TYPE_PSK: return "psk";
            case WifiInfo.SECURITY_TYPE_EAP: case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE: return "eap";
            default: return "psk";
        }
    }

    private static String ip(int v) {
        return String.format(Locale.ROOT, "%d.%d.%d.%d", v & 255, (v >> 8) & 255, (v >> 16) & 255, (v >> 24) & 255);
    }

    static Bundle wifiGet() {
        checkJoin();
        WifiManager wm = wifi();
        Bundle b = ok();
        int s = wm.getWifiState();
        b.putString("enabled", s == WifiManager.WIFI_STATE_ENABLED ? "1" : "0");
        b.putString("state", s == WifiManager.WIFI_STATE_ENABLED ? "on" : s == WifiManager.WIFI_STATE_ENABLING ? "turning_on"
                : s == WifiManager.WIFI_STATE_DISABLING ? "turning_off" : "off");
        b.putString("scanning", sScanning ? "1" : "0");
        WifiInfo wi = wm.getConnectionInfo();
        SupplicantState ss = wi.getSupplicantState();
        String curSsid = unquote(wi.getSSID());
        String link;
        if (ss == SupplicantState.COMPLETED) link = wi.getIpAddress() != 0 ? "connected" : "obtaining_ip";
        else if (ss == SupplicantState.ASSOCIATING || ss == SupplicantState.ASSOCIATED || ss == SupplicantState.AUTHENTICATING
                || ss == SupplicantState.FOUR_WAY_HANDSHAKE || ss == SupplicantState.GROUP_HANDSHAKE) link = "authenticating";
        else if (sTarget != null) link = "connecting";
        else link = sFail != null ? "failed" : "none";
        b.putString("link", link);
        if (sFail != null && sTarget == null) b.putString("fail", sFail);
        // while joining, "current" names the network being joined (Android's while it knows it, else the user's pick)
        String shown = !curSsid.isEmpty() ? curSsid : sTarget;
        if (shown != null && !shown.isEmpty() && !"none".equals(link) && !"failed".equals(link)) {
            Bundle cur = new Bundle();
            cur.putString("ssid", shown);
            if (!curSsid.isEmpty()) {
                cur.putInt("level", level(wi.getRssi()));
                cur.putInt("freq", wi.getFrequency());
                cur.putInt("speed", wi.getLinkSpeed());
                if (wi.getIpAddress() != 0) cur.putString("ip", ip(wi.getIpAddress()));
                cur.putString("security", currentSecurity(wi));
            }
            b.putBundle("current", cur);
        }
        // one entry per network name: scan results (strongest signal) + saved networks
        Map<String, Bundle> by = new LinkedHashMap<>();
        Map<String, Integer> rssi = new HashMap<>();
        List<ScanResult> scan = s == WifiManager.WIFI_STATE_ENABLED ? wm.getScanResults() : new ArrayList<>();
        for (ScanResult r : scan) {
            String name = r.getWifiSsid() != null ? unquote(r.getWifiSsid().toString()) : r.SSID;
            if (name == null || name.isEmpty()) continue;
            Integer old = rssi.get(name);
            if (old != null && old >= r.level) continue;
            rssi.put(name, r.level);
            Bundle n = new Bundle();
            n.putString("key", "ssid:" + name);
            n.putString("ssid", name);
            n.putInt("level", level(r.level));
            n.putString("security", scanSecurity(r.capabilities));
            n.putString("saved", "0");
            by.put(name, n);
        }
        for (WifiConfiguration c : saved()) {
            String name = unquote(c.SSID);
            if (name.isEmpty()) continue;
            Bundle n = by.get(name);
            if (n == null) {
                n = new Bundle();
                n.putString("ssid", name);
                n.putString("security", savedSecurity(c));
                by.put(name, n);
            }
            n.putString("key", "id:" + c.networkId);
            n.putString("saved", "1");
        }
        ArrayList<Bundle> list = new ArrayList<>(by.values());
        for (Bundle n : list) n.putString("connected", "connected".equals(link) && n.getString("ssid", "").equals(curSsid) ? "1" : "0");
        list.sort((x, y) -> {
            int c = Integer.compare("1".equals(y.getString("connected")) ? 1 : 0, "1".equals(x.getString("connected")) ? 1 : 0);
            if (c != 0) return c;
            return Integer.compare(y.getInt("level", -1), x.getInt("level", -1));
        });
        b.putParcelableArrayList("networks", list);
        return b;
    }

    @SuppressWarnings("deprecation")
    static Bundle wifiSetEnabled(boolean on) {
        boolean r = wifi().setWifiEnabled(on);
        notify("wifi");
        return res(r, on ? "Wi-Fi didn't turn on" : "Wi-Fi didn't turn off");
    }

    @SuppressWarnings("deprecation")
    static Bundle wifiScan() {
        if (!wifi().isWifiEnabled()) return err("off", "Wi-Fi is off");
        sScanning = wifi().startScan();
        notify("wifi");
        return res(sScanning, "Couldn't look for networks right now");
    }

    static Bundle wifiConnect(Bundle e) throws Exception {
        if (!wifi().isWifiEnabled()) return err("off", "Wi-Fi is off");
        String ssid = e == null ? null : e.getString("ssid");
        String sec = e == null ? "psk" : e.getString("security", "psk");
        String pw = e == null ? null : e.getString("password");
        if (ssid == null || ssid.isEmpty() || ssid.getBytes("UTF-8").length > 32) return err("bad_arg", "Network name missing or too long");
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + ssid + "\"";
        c.hiddenSSID = "1".equals(e.getString("hidden"));
        switch (sec) {
            case "open": c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN); break;
            case "owe": c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE); break;
            case "psk": case "sae":
                if (pw == null || pw.length() < 8 || pw.length() > 63) return err("bad_arg", "The password needs 8 to 63 characters");
                c.setSecurityParams("sae".equals(sec) ? WifiConfiguration.SECURITY_TYPE_SAE : WifiConfiguration.SECURITY_TYPE_PSK);
                c.preSharedKey = "\"" + pw + "\"";
                break;
            default: return err("unsupported", "This kind of network is set up in Android's settings");
        }
        startJoin(ssid);
        Class<?> listener = Class.forName("android.net.wifi.WifiManager$ActionListener");
        WifiManager.class.getMethod("connect", WifiConfiguration.class, listener).invoke(wifi(), c, null);
        return ok();
    }

    private static void startJoin(String ssid) {
        sTarget = ssid; sTargetAt = SystemClock.elapsedRealtime(); sFail = null; sWasAuthenticating = false;
        sH.removeCallbacks(sJoinTimeout);
        sH.postDelayed(sJoinTimeout, 46_000);
        notify("wifi");
    }

    private static final Runnable sJoinTimeout = () -> { checkJoin(); notify("wifi"); };

    private static Integer netId(String key) {
        if (key == null || !key.startsWith("id:")) return null;
        try { return Integer.parseInt(key.substring(3)); } catch (NumberFormatException e) { return null; }
    }

    static Bundle wifiConnectSaved(String key) throws Exception {
        if (!wifi().isWifiEnabled()) return err("off", "Wi-Fi is off");
        Integer id = netId(key);
        if (id == null) return err("bad_arg", "Not a saved network");
        String ssid = null;
        for (WifiConfiguration c : saved()) if (c.networkId == id) ssid = unquote(c.SSID);
        if (ssid == null) return err("bad_arg", "That network isn't saved any more");
        startJoin(ssid);
        Class<?> listener = Class.forName("android.net.wifi.WifiManager$ActionListener");
        WifiManager.class.getMethod("connect", int.class, listener).invoke(wifi(), id, null);
        return ok();
    }

    static Bundle wifiForget(String key) throws Exception {
        Integer id = netId(key);
        if (id == null) return err("bad_arg", "Not a saved network");
        Class<?> listener = Class.forName("android.net.wifi.WifiManager$ActionListener");
        WifiManager.class.getMethod("forget", int.class, listener).invoke(wifi(), id, null);
        notify("wifi");
        return ok();
    }

    // ------------------------------------------------------------------ Bluetooth
    private static BluetoothAdapter bt() { return sApp.getSystemService(BluetoothManager.class).getAdapter(); }

    private static final Map<String, Integer> sFound = new LinkedHashMap<>();   // address -> rssi (this discovery)

    private static void onBtEvent(Intent i) {
        String a = i.getAction();
        if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(a)) synchronized (sFound) { sFound.clear(); }
        if (BluetoothDevice.ACTION_FOUND.equals(a)) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (d != null) synchronized (sFound) {
                sFound.put(d.getAddress(), (int) i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE));
            }
        }
        notify("bt");
        if (a.startsWith("android.bluetooth.a2dp") || BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)
                || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) notify("output");
    }

    private static Object call0(Object o, String m) {
        try { return o.getClass().getMethod(m).invoke(o); } catch (Throwable t) { return null; }
    }

    private static String kind(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        if (c == null) return "other";
        switch (c.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                switch (c.getDeviceClass()) {
                    case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER: case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                    case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO: return "speaker";
                    case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO: return "car";
                    default: return "headphones";
                }
            case BluetoothClass.Device.Major.PHONE: return "phone";
            case BluetoothClass.Device.Major.COMPUTER: return "computer";
            default: return "other";
        }
    }

    private static Bundle device(BluetoothDevice d, Integer rssi) {
        Bundle n = new Bundle();
        n.putString("address", d.getAddress());
        String name = d.getName();
        n.putString("name", name == null || name.isEmpty() ? d.getAddress() : name);
        n.putString("kind", kind(d));
        n.putString("bonded", d.getBondState() == BluetoothDevice.BOND_BONDED ? "1" : "0");
        n.putString("connected", Boolean.TRUE.equals(call0(d, "isConnected")) ? "1" : "0");
        Object bat = call0(d, "getBatteryLevel");
        if (bat instanceof Integer && (Integer) bat >= 0) n.putInt("battery", (Integer) bat);
        if (rssi != null && rssi != Short.MIN_VALUE) n.putInt("rssi", rssi);
        return n;
    }

    static Bundle btGet() {
        BluetoothAdapter a = bt();
        Bundle b = ok();
        if (a == null) return err("unsupported", "No Bluetooth");
        int s = a.getState();
        b.putString("enabled", s == BluetoothAdapter.STATE_ON ? "1" : "0");
        b.putString("state", s == BluetoothAdapter.STATE_ON ? "on" : s == BluetoothAdapter.STATE_TURNING_ON ? "turning_on"
                : s == BluetoothAdapter.STATE_TURNING_OFF ? "turning_off" : "off");
        b.putString("name", a.getName());
        b.putString("discovering", a.isDiscovering() ? "1" : "0");
        ArrayList<Bundle> list = new ArrayList<>();
        Set<String> bonded = new HashSet<>();
        if (s == BluetoothAdapter.STATE_ON) {
            for (BluetoothDevice d : a.getBondedDevices()) { bonded.add(d.getAddress()); list.add(device(d, null)); }
            list.sort((x, y) -> Integer.compare("1".equals(y.getString("connected")) ? 1 : 0, "1".equals(x.getString("connected")) ? 1 : 0));
            List<Bundle> found = new ArrayList<>();
            synchronized (sFound) {
                for (Map.Entry<String, Integer> e : sFound.entrySet())
                    if (!bonded.contains(e.getKey())) found.add(device(a.getRemoteDevice(e.getKey()), e.getValue()));
            }
            found.sort((x, y) -> Integer.compare(y.getInt("rssi", -999), x.getInt("rssi", -999)));
            list.addAll(found);
        }
        b.putParcelableArrayList("devices", list);
        return b;
    }

    @SuppressWarnings("deprecation")
    static Bundle btSetEnabled(boolean on) {
        BluetoothAdapter a = bt();
        if (a == null) return err("unsupported", "No Bluetooth");
        boolean r = on ? a.enable() : a.disable();
        notify("bt");
        return res(r, on ? "Bluetooth didn't turn on" : "Bluetooth didn't turn off");
    }

    static Bundle btDiscover(String arg) {
        BluetoothAdapter a = bt();
        if (a == null) return err("unsupported", "No Bluetooth");
        if (!a.isEnabled()) return err("off", "Bluetooth is off");
        if ("start".equals(arg)) {
            synchronized (sFound) { sFound.clear(); }
            if (a.isDiscovering()) a.cancelDiscovery();
            return res(a.startDiscovery(), "Couldn't look for devices right now");
        }
        if ("stop".equals(arg)) { a.cancelDiscovery(); notify("bt"); return ok(); }
        return err("bad_arg", "start or stop");
    }

    static Bundle btDevice(String method, String address) throws Exception {
        BluetoothAdapter a = bt();
        if (a == null) return err("unsupported", "No Bluetooth");
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) return err("bad_arg", "Not a Bluetooth address");
        if (!a.isEnabled()) return err("off", "Bluetooth is off");
        BluetoothDevice d = a.getRemoteDevice(address);
        boolean r;
        switch (method) {
            case "bt_pair":
                if (a.isDiscovering()) a.cancelDiscovery();   // pairing is more reliable without discovery running
                r = d.createBond();
                break;
            case "bt_connect": r = ((Integer) BluetoothDevice.class.getMethod("connect").invoke(d)) == 0; break;
            case "bt_disconnect": r = ((Integer) BluetoothDevice.class.getMethod("disconnect").invoke(d)) == 0; break;
            default: r = (Boolean) BluetoothDevice.class.getMethod("removeBond").invoke(d); break;
        }
        notify("bt");
        return res(r, "Android refused it");
    }

    // ------------------------------------------------------------------ audio output
    private static AudioManager audio() { return sApp.getSystemService(AudioManager.class); }
    private static SharedPreferences prefs() {
        return sApp.createDeviceProtectedStorageContext().getSharedPreferences("sysapi", Context.MODE_PRIVATE);
    }

    private static boolean wired(int t) {
        return t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || t == AudioDeviceInfo.TYPE_LINE_ANALOG;
    }
    private static boolean usb(int t) {
        return t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    /** "wired", "bt:<address>", "usb:<address>", or null for outputs that aren't offered (speaker, earpiece, …). */
    private static String outId(int type, String address) {
        if (wired(type)) return "wired";
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return "bt:" + address;
        if (usb(type)) return "usb:" + address;
        return null;
    }

    private static void onOutputsChanged(AudioDeviceInfo[] added, AudioDeviceInfo[] removed) {
        String choice = prefs().getString("output", "auto");
        if (removed != null && !"auto".equals(choice))
            for (AudioDeviceInfo d : removed) if (d.isSink() && choice.equals(outId(d.getType(), d.getAddress()))) {
                Log.i(TAG, "chosen output " + choice + " went away: automatic");
                prefs().edit().putString("output", "auto").apply();
                try { applyPreferred(null); } catch (Throwable t) { Log.w(TAG, "output auto", t); }
            }
        if (added != null && !"auto".equals(choice))
            for (AudioDeviceInfo d : added) if (d.isSink() && choice.equals(outId(d.getType(), d.getAddress()))) {
                try { applyPreferred(d); } catch (Throwable t) { Log.w(TAG, "output back", t); }
            }
        notify("output");
        sH.postDelayed(() -> notify("output"), 1500);   // the module updates the wired status (3.5 / 4.4 mm) a moment later
    }

    private static void restoreOutputChoice() {
        String choice = prefs().getString("output", "auto");
        if ("auto".equals(choice)) return;
        AudioDeviceInfo d = findOutput(choice);
        try {
            if (d != null) applyPreferred(d);
            else prefs().edit().putString("output", "auto").apply();
        } catch (Throwable t) { Log.w(TAG, "output restore", t); }
    }

    private static AudioDeviceInfo findOutput(String id) {
        for (AudioDeviceInfo d : audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (id.equals(outId(d.getType(), d.getAddress()))) return d;
        return null;
    }

    private static final AudioAttributes MEDIA = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

    /** The audio product strategy media plays through (hidden API: AudioProductStrategy). */
    private static Object mediaStrategy() throws Exception {
        Class<?> ps = Class.forName("android.media.audiopolicy.AudioProductStrategy");
        @SuppressWarnings("unchecked")
        List<Object> all = (List<Object>) AudioManager.class.getMethod("getAudioProductStrategies").invoke(null);
        Method supports = ps.getMethod("supportsAudioAttributes", AudioAttributes.class);
        for (Object s : all) if ((Boolean) supports.invoke(s, MEDIA)) return s;
        throw new IllegalStateException("no media strategy");
    }

    /** d = output to prefer for all media, or null for automatic (Android's own rule). */
    private static boolean applyPreferred(AudioDeviceInfo d) throws Exception {
        Object strategy = mediaStrategy();
        Class<?> ps = Class.forName("android.media.audiopolicy.AudioProductStrategy");
        Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
        if (d == null) return (Boolean) AudioManager.class.getMethod("removePreferredDeviceForStrategy", ps).invoke(audio(), strategy);
        Object attrs = ada.getConstructor(AudioDeviceInfo.class).newInstance(d);
        return (Boolean) AudioManager.class.getMethod("setPreferredDeviceForStrategy", ps, ada).invoke(audio(), strategy, attrs);
    }

    /** The output media plays on right now (hidden API: getDevicesForAttributes). */
    private static String activeOutput() {
        try {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) AudioManager.class.getMethod("getDevicesForAttributes", AudioAttributes.class)
                    .invoke(audio(), MEDIA);
            for (Object a : l) {
                int t = (Integer) a.getClass().getMethod("getType").invoke(a);
                String addr = (String) a.getClass().getMethod("getAddress").invoke(a);
                String id = outId(t, addr);
                if (id != null) return id;
            }
        } catch (Throwable t) { Log.w(TAG, "active output", t); }
        return "";
    }

    private static Object sA2dp;
    private static void ensureA2dp() {
        if (sA2dp != null || bt() == null) return;
        bt().getProfileProxy(sApp, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int p, BluetoothProfile proxy) { sA2dp = proxy; SystemApi.notify("output"); }
            @Override public void onServiceDisconnected(int p) { sA2dp = null; }
        }, BluetoothProfile.A2DP);
    }

    private static final String[] CODECS = { "sbc", "aac", "aptx", "aptx_hd", "ldac", "lc3", "opus" };

    private static String codec(String address) {
        ensureA2dp();
        if (sA2dp == null) return null;
        try {
            BluetoothDevice d = bt().getRemoteDevice(address);
            Object st = sA2dp.getClass().getMethod("getCodecStatus", BluetoothDevice.class).invoke(sA2dp, d);
            if (st == null) return null;
            Object cfg = st.getClass().getMethod("getCodecConfig").invoke(st);
            int type = (Integer) cfg.getClass().getMethod("getCodecType").invoke(cfg);
            return type >= 0 && type < CODECS.length ? CODECS[type] : null;
        } catch (Throwable t) { return null; }
    }

    static Bundle outputGet() {
        Bundle b = ok();
        String active = activeOutput(), choice = prefs().getString("output", "auto");
        boolean bitPerfect = "1".equals(Updater.sysprop("sys.hibyr4.bit_perfect", "1"));
        ArrayList<Bundle> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AudioDeviceInfo d : audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            String id = outId(d.getType(), d.getAddress());
            if (id == null || !seen.add(id)) continue;
            Bundle o = new Bundle();
            o.putString("id", id);
            if ("wired".equals(id)) {
                // 3.5 and 4.4 mm are one Android device: the module knows which one is plugged in and how it's used
                String out = Updater.sysprop("sys.hibyr4.st_output", "");
                boolean lineout35 = "lineout".equals(Updater.sysprop("sys.hibyr4.out35", "headphone"));
                String kind = "Balance".equals(out) ? "balanced_44" : "BalanceLo".equals(out) ? "balanced_lineout_44"
                        : lineout35 ? "lineout_35" : "headphone_35";
                o.putString("kind", kind);
                o.putString("name", kind.equals("balanced_44") ? "Headphones 4.4 mm" : kind.equals("balanced_lineout_44")
                        ? "Line out 4.4 mm" : kind.equals("lineout_35") ? "Line out 3.5 mm" : "Headphones 3.5 mm");
                o.putString("bitperfect", bitPerfect ? "1" : "0");
                o.putString("dac_volume", bitPerfect && !kind.contains("lineout") ? "1" : "0");
            } else if (id.startsWith("bt:")) {
                o.putString("kind", "bluetooth");
                o.putString("name", String.valueOf(d.getProductName()));
                String c = codec(d.getAddress());
                if (c != null) o.putString("codec", c);
                o.putString("bitperfect", "0");
                o.putString("dac_volume", "0");
            } else {
                o.putString("kind", "usb_dac");
                o.putString("name", String.valueOf(d.getProductName()));
                o.putString("bitperfect", "0");
                o.putString("dac_volume", "0");
            }
            o.putString("active", id.equals(active) ? "1" : "0");
            list.add(o);
        }
        b.putParcelableArrayList("outputs", list);
        b.putString("active", active);
        b.putString("choice", choice);
        return b;
    }

    static Bundle outputSet(String id) throws Exception {
        if (id == null || id.isEmpty()) return err("bad_arg", "Which output?");
        if ("auto".equals(id)) {
            boolean r = applyPreferred(null);
            prefs().edit().putString("output", "auto").apply();
            notify("output");
            return ok();   // removing a preference that wasn't set also reports false: automatic either way
        }
        AudioDeviceInfo d = findOutput(id);
        if (d == null) return err("bad_arg", "That output isn't connected");
        boolean r = applyPreferred(d);
        if (r) prefs().edit().putString("output", id).apply();
        notify("output");
        return res(r, "Android didn't switch the output");
    }

    // ------------------------------------------------------------------ power
    static Bundle power(String arg) {
        if (arg == null) return err("bad_arg", "restart, shutdown, recovery or recovery_check");
        switch (arg) {
            case "restart": return fromError(Updater.rebootAsync(sApp, "system"));
            case "recovery": return fromError(Updater.rebootAsync(sApp, "recovery"));
            case "recovery_check": return fromError(Updater.rebootAsync(sApp, "recovery_check"));
            case "shutdown":
                sH.post(() -> {
                    try {
                        PowerManager.class.getMethod("shutdown", boolean.class, String.class, boolean.class)
                                .invoke(sApp.getSystemService(PowerManager.class), false, "userrequested", false);
                    } catch (Throwable t) { Log.w(TAG, "shutdown", t); }
                });
                return ok();
            default: return err("bad_arg", "restart, shutdown, recovery or recovery_check");
        }
    }

    private static Bundle fromError(String e) { return e == null ? ok() : err("busy", e); }
}
