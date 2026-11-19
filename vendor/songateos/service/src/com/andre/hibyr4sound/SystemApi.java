package com.andre.hibyr4sound;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.app.AlarmManager;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
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
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
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
    static final String[] CAPS = { "wifi", "bt", "bt_codec", "display", "earbuds", "output", "storage", "time", "about", "usb", "hold", "recovery", "root", "theme", "power", "ota", "setup", "apps", "gapps" };

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
        AirPods.init(sApp);
        GalaxyBuds.init(sApp);
        OutputWatch.init(sApp);              // 4.4 mm: "becoming noisy" on unplug, DAC step per output (2026-11-19)
        audio().registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { onOutputsChanged(added, null); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { onOutputsChanged(null, removed); }
        }, sH);
        sH.postDelayed(SystemApi::restoreOutputChoice, 5000);
        connectA2dp();
        ContentObserver disp = new ContentObserver(sH) {
            @Override public void onChange(boolean self) { SystemApi.notify("display"); }
        };
        for (String k : new String[]{ Settings.System.SCREEN_BRIGHTNESS, "screen_brightness_float", Settings.System.SCREEN_OFF_TIMEOUT })
            sApp.getContentResolver().registerContentObserver(Settings.System.getUriFor(k), false, disp);
        ContentObserver time = new ContentObserver(sH) {
            @Override public void onChange(boolean self) { SystemApi.notify("time"); }
        };
        sApp.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.AUTO_TIME), false, time);
        sApp.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.AUTO_TIME_ZONE), false, time);
        sApp.getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.TIME_12_24), false, time);
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        sApp.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { SystemApi.notify("time"); }
        }, tf, null, sH, Context.RECEIVER_EXPORTED);
        Intent usb = sApp.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { onUsbState(i); }
        }, new IntentFilter("android.hardware.usb.action.USB_STATE"), null, sH, Context.RECEIVER_EXPORTED);
        if (usb != null) sUsbConnected = usb.getBooleanExtra("connected", false);   // sticky
        IntentFilter vol = new IntentFilter("android.os.storage.action.VOLUME_STATE_CHANGED");
        sApp.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { SystemApi.notify("storage"); }
        }, vol, null, sH, Context.RECEIVER_EXPORTED);
        IntentFilter media = new IntentFilter();
        for (String a : new String[]{ Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT,
                Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL }) media.addAction(a);
        media.addDataScheme("file");
        sApp.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { SystemApi.notify("storage"); }
        }, media, null, sH, Context.RECEIVER_EXPORTED);
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
        if ("hold_switch".equals(method)) {   // from the module's hold_switch.sh only (root)
            if (Binder.getCallingUid() != 0) return err("no_permission", "Only the system can report the hold switch");
            return HoldSwitch.onSwitch(arg, extras != null && "1".equals(extras.getString("boot")));
        }
        if ("hold_key".equals(method)) {      // from the module's hold_switch.sh only (root)
            if (Binder.getCallingUid() != 0) return err("no_permission", "Only the system can pass on hold keys");
            return HoldSwitch.onKey(arg);
        }
        boolean ours = method.startsWith("wifi_") || method.startsWith("bt_") || method.startsWith("output_")
                || method.startsWith("display_") || method.startsWith("earbuds_") || method.startsWith("storage_") || method.startsWith("time_") || method.startsWith("usb_") || method.startsWith("hold_") || method.startsWith("recovery_") || method.startsWith("root_") || method.startsWith("setup_") || method.startsWith("apps_") || method.startsWith("media_") || method.startsWith("gapps_") || method.equals("about") || method.equals("home_ready") || method.equals("zones_list") || method.equals("power");
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
                case "bt_codec_get": return btCodecGet(arg);
                case "bt_codec_set": return btCodecSet(arg, extras);
                case "earbuds_get": { Bundle[] e = new Bundle[1]; BluetoothDevice d = a2dpDevice(arg, e); return d == null ? e[0] : Earbuds.get(d); }
                case "earbuds_dev_ble": return AirPodsProbe.bleScan(10000);   // development only
                case "earbuds_dev_aap": {                                       // development only
                    BluetoothAdapter ad = bt();
                    if (ad == null || arg == null || !BluetoothAdapter.checkBluetoothAddress(arg)) return err("bad_arg", "Not a Bluetooth address");
                    return AirPodsProbe.aapProbe(ad.getRemoteDevice(arg));
                }
                case "earbuds_dev_uuids": { Bundle[] e = new Bundle[1]; BluetoothDevice d = a2dpDevice(arg, e); return d == null ? e[0] : BudsProbe.uuids(d); }   // development only
                case "earbuds_dev_spp": { Bundle[] e = new Bundle[1]; BluetoothDevice d = a2dpDevice(arg, e); return d == null ? e[0] : BudsProbe.spp(d, extras); }   // development only
                case "earbuds_set": { Bundle[] e = new Bundle[1]; BluetoothDevice d = a2dpDevice(arg, e); return d == null ? e[0] : Earbuds.set(d, extras); }
                case "root_get": return MagiskRoot.get(c);
                case "root_set": return MagiskRoot.set(c, extras);
                case "root_check": return MagiskRoot.checkAsync(c);
                case "root_install": return MagiskRoot.installAsync(c);
                case "root_remove": return MagiskRoot.removeAsync(c, "dry".equals(arg) && Binder.getCallingUid() == Process.SYSTEM_UID);
                case "root_install_file":   // development: an APK already on the R4; system uid only
                    if (Binder.getCallingUid() != Process.SYSTEM_UID) return err("no_permission", "System only");
                    return MagiskRoot.installFileAsync(c, arg);
                case "recovery_get": return RecoverySwitch.get(c);
                case "recovery_check": return RecoverySwitch.checkAsync(c);
                case "recovery_switch": return RecoverySwitch.switchAsync(c, arg);
                case "recovery_switch_file":   // development: a package already on the R4; system uid only
                    if (Binder.getCallingUid() != Process.SYSTEM_UID) return err("no_permission", "System only");
                    return RecoverySwitch.switchFileAsync(c, arg);
                case "hold_get": return HoldSwitch.get();
                case "hold_set": return HoldSwitch.set(extras);
                case "about": return about();
                case "usb_get": return usbGet();
                case "usb_set": return usbSet(arg);
                case "time_get": return timeGet();
                case "time_set": return timeSet(arg, v);
                case "zones_list": return zonesList(arg);
                case "storage_get": return storageGet();
                case "storage_eject": return storageMount(arg, false);
                case "storage_mount": return storageMount(arg, true);
                case "display_get": return displayGet();
                case "display_set": return displaySet(arg, v);
                case "output_get": return outputGet();
                case "output_set": return outputSet(arg);
                case "power": return power(arg);
                case "home_ready": {
                    // Songate's UI is fully drawn: the window manager may end the boot animation now (it waits for
                    // this, at most 20 s; patches/apply_bootwait.py). Only matters during boot; harmless later.
                    Updater.setSysprop("sys.songateos.home_ready", "1");
                    return ok();
                }
                case "setup_get": return Setup.get(c);
                case "setup_done": return Setup.done(c);
                case "setup_reset": return Setup.reset(c);   // development: system uid only
                case "apps_recent": return Recents.list(c);
                case "apps_open": return Recents.open(c, arg);
                case "apps_close": return Recents.close(c, arg);
                case "apps_running": return Recents.running(c);
                case "apps_kill": return Recents.kill(c, arg);
                case "media_play_search": return PlaySearch.play(c, arg, extras);   // Songate's "Play in <app>" (2026-11-19)
                case "gapps_get": return Gapps.get(c);
                case "gapps_check": return Gapps.checkAsync(c);
                case "gapps_install": return Gapps.installAsync(c, arg);
                case "gapps_remove": return Gapps.removeAsync(c);
                case "gapps_set": return Gapps.set(c, arg, v);
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
        // AirPods and Galaxy Buds: open their control channel as soon as they connect (the state is there before Songate asks)
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (d != null) {
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) { AirPods.onConnected(d); GalaxyBuds.onConnected(d); }
                else { AirPods.onDisconnected(d); GalaxyBuds.onDisconnected(d); }
            }
        }
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
        else {   // AirPods don't report it to Android; the service has it from their own channel (lower bud)
            int ap = AirPods.battery(d);
            if (ap >= 0) n.putInt("battery", ap);
        }
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

    // ------------------------------------------------------------------ Bluetooth codec (phase 2)
    private static volatile BluetoothA2dp sA2dp;
    private static volatile boolean sA2dpAsked;

    private static void connectA2dp() {
        BluetoothAdapter a = bt();
        if (a == null || sA2dp != null || sA2dpAsked) return;
        sA2dpAsked = true;
        a.getProfileProxy(sApp, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int p, BluetoothProfile proxy) { sA2dp = (BluetoothA2dp) proxy; SystemApi.notify("bt"); SystemApi.notify("output"); }
            @Override public void onServiceDisconnected(int p) { sA2dp = null; sA2dpAsked = false; }
        }, BluetoothProfile.A2DP);
    }

    /** Hidden (system API) on the public SDK: called by reflection. */
    private static BluetoothCodecStatus codecStatus(BluetoothA2dp p, BluetoothDevice d) {
        try {
            return (BluetoothCodecStatus) BluetoothA2dp.class.getMethod("getCodecStatus", BluetoothDevice.class).invoke(p, d);
        } catch (ReflectiveOperationException x) { Log.w(TAG, "getCodecStatus", x); return null; }
    }

    private static final int[][] CODECS = {
        { BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC }, { BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC },
        { BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX }, { BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD },
        { BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC }, { BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 },
        { BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS } };
    private static final String[] CODEC_NAMES = { "sbc", "aac", "aptx", "aptx_hd", "ldac", "lc3", "opus" };
    private static final String[] LDAC_NAMES = { "hq", "sq", "mq", "abr" };   // codecSpecific1 1000..1003

    private static String codecName(int type) {
        for (int i = 0; i < CODECS.length; i++) if (CODECS[i][0] == type) return CODEC_NAMES[i];
        return "codec" + type;
    }
    private static int codecType(String name) {
        for (int i = 0; i < CODEC_NAMES.length; i++) if (CODEC_NAMES[i].equals(name)) return CODECS[i][0];
        return -1;
    }
    private static int rateHz(int r) {
        switch (r) {
            case BluetoothCodecConfig.SAMPLE_RATE_44100: return 44100;
            case BluetoothCodecConfig.SAMPLE_RATE_48000: return 48000;
            case BluetoothCodecConfig.SAMPLE_RATE_88200: return 88200;
            case BluetoothCodecConfig.SAMPLE_RATE_96000: return 96000;
            case BluetoothCodecConfig.SAMPLE_RATE_176400: return 176400;
            case BluetoothCodecConfig.SAMPLE_RATE_192000: return 192000;
            default: return 0;
        }
    }
    private static int bits(int b) {
        return b == BluetoothCodecConfig.BITS_PER_SAMPLE_16 ? 16 : b == BluetoothCodecConfig.BITS_PER_SAMPLE_24 ? 24
                : b == BluetoothCodecConfig.BITS_PER_SAMPLE_32 ? 32 : 0;
    }

    /** The A2DP device: the address given, or the connected one when arg is empty. Null + error in out[0]. */
    private static BluetoothDevice a2dpDevice(String address, Bundle[] out) {
        BluetoothAdapter a = bt();
        if (a == null) { out[0] = err("unsupported", "No Bluetooth"); return null; }
        if (!a.isEnabled()) { out[0] = err("off", "Bluetooth is off"); return null; }
        BluetoothA2dp p = sA2dp;
        if (p == null) { connectA2dp(); out[0] = err("busy", "Bluetooth audio isn't ready yet, try again"); return null; }
        List<BluetoothDevice> conn = p.getConnectedDevices();
        if (address == null || address.isEmpty()) {
            if (conn.isEmpty()) { out[0] = err("not_connected", "No Bluetooth headphones connected"); return null; }
            return conn.get(0);
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) { out[0] = err("bad_arg", "Not a Bluetooth address"); return null; }
        for (BluetoothDevice d : conn) if (d.getAddress().equals(address)) return d;
        out[0] = err("not_connected", "That device isn't connected for music");
        return null;
    }

    static Bundle btCodecGet(String address) {
        Bundle[] e = new Bundle[1];
        BluetoothDevice d = a2dpDevice(address, e);
        if (d == null) return e[0];
        BluetoothCodecStatus st = codecStatus(sA2dp, d);
        if (st == null || st.getCodecConfig() == null) return err("busy", "The codec isn't known yet, try again");
        BluetoothCodecConfig c = st.getCodecConfig();
        Bundle b = ok();
        b.putString("address", d.getAddress());
        b.putString("codec", codecName(c.getCodecType()));
        b.putInt("rate", rateHz(c.getSampleRate()));
        b.putInt("bits", bits(c.getBitsPerSample()));
        if (c.getCodecType() == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            long q = c.getCodecSpecific1() - 1000;
            b.putString("ldac", q >= 0 && q < 4 ? LDAC_NAMES[(int) q] : "abr");
        }
        ArrayList<String> avail = new ArrayList<>();
        for (BluetoothCodecConfig s : st.getCodecsSelectableCapabilities()) {
            String n = codecName(s.getCodecType());
            if (!avail.contains(n)) avail.add(n);
        }
        b.putStringArray("available", avail.toArray(new String[0]));
        return b;
    }

    static Bundle btCodecSet(String address, Bundle extras) {
        Bundle[] e = new Bundle[1];
        BluetoothDevice d = a2dpDevice(address, e);
        if (d == null) return e[0];
        String name = extras == null ? null : extras.getString("codec");
        int type = codecType(name);
        if (type < 0) return err("bad_arg", "codec must be one of sbc, aac, aptx, aptx_hd, ldac, lc3, opus");
        String ldac = extras.getString("ldac");
        long spec = 0;
        if (ldac != null && !ldac.isEmpty()) {
            if (type != BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) return err("bad_arg", "ldac quality is for LDAC only");
            int i = java.util.Arrays.asList(LDAC_NAMES).indexOf(ldac);
            if (i < 0) return err("bad_arg", "ldac must be hq, sq, mq or abr");
            spec = 1000 + i;
        }
        BluetoothCodecStatus st = codecStatus(sA2dp, d);
        boolean can = false;
        if (st != null) for (BluetoothCodecConfig s : st.getCodecsSelectableCapabilities()) if (s.getCodecType() == type) can = true;
        if (!can) return err("unsupported", "These headphones don't support that codec");
        BluetoothCodecConfig want = new BluetoothCodecConfig.Builder()
                .setCodecType(type).setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_NONE).setBitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_NONE)
                .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_NONE).setCodecSpecific1(spec).build();
        try {
            BluetoothA2dp.class.getMethod("setCodecConfigPreference", BluetoothDevice.class, BluetoothCodecConfig.class)
                    .invoke(sA2dp, d, want);
        } catch (ReflectiveOperationException x) {
            Log.w(TAG, "setCodecConfigPreference", x);
            return err("failed", "Android refused the codec change");
        }
        notify("bt");
        return ok();
    }

    // ------------------------------------------------------------------ about + USB (phase 2)
    static Bundle about() {
        Bundle b = ok();
        b.putString("device", "HiBy R4");
        b.putString("songateos", Updater.currentVersion());
        b.putString("android", android.os.Build.VERSION.RELEASE);
        b.putString("patch", android.os.Build.VERSION.SECURITY_PATCH);
        b.putString("vendor_patch", Updater.sysprop("ro.vendor.build.security_patch", ""));
        b.putString("build", android.os.Build.DISPLAY);
        b.putString("kernel", System.getProperty("os.version", ""));
        b.putString("recovery", Updater.sysprop("sys.hibyr4.ota_recovery", ""));
        b.putString("service", versionName());
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        sApp.getSystemService(android.app.ActivityManager.class).getMemoryInfo(mi);
        b.putLong("ram", mi.totalMem);
        Intent bat = sApp.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int lvl = bat.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = bat.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            if (lvl >= 0 && scale > 0) b.putInt("battery", lvl * 100 / scale);
            int st = bat.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0);
            b.putString("battery_status", st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                    : st == android.os.BatteryManager.BATTERY_STATUS_FULL ? "full" : "discharging");
            b.putInt("battery_temp", bat.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0));   // tenths of °C
        }
        return b;   // no serial number or other personal identifiers
    }

    private static String versionName() {
        try { return sApp.getPackageManager().getPackageInfo(sApp.getPackageName(), 0).versionName; } catch (Throwable t) { return ""; }
    }

    // UsbManager.getCurrentFunctions / setCurrentFunctions and the FUNCTION_* constants are hidden: reflection.
    private static final String[] USB_MODES = { "charge", "mtp", "ptp", "midi" };
    private static final String[] USB_FIELDS = { "FUNCTION_NONE", "FUNCTION_MTP", "FUNCTION_PTP", "FUNCTION_MIDI" };
    private static volatile boolean sUsbConnected;

    private static long usbConst(String f) throws Exception { return android.hardware.usb.UsbManager.class.getField(f).getLong(null); }

    static void onUsbState(Intent i) {
        sUsbConnected = i.getBooleanExtra("connected", false);
        notify("usb");
    }

    static Bundle usbGet() throws Exception {
        android.hardware.usb.UsbManager um = sApp.getSystemService(android.hardware.usb.UsbManager.class);
        long cur = (Long) android.hardware.usb.UsbManager.class.getMethod("getCurrentFunctions").invoke(um);
        String mode = "charge";
        for (int k = 1; k < USB_MODES.length; k++) if ((cur & usbConst(USB_FIELDS[k])) != 0) mode = USB_MODES[k];
        Bundle b = ok();
        b.putString("connected", sUsbConnected ? "1" : "0");
        b.putString("mode", mode);
        b.putStringArray("modes", USB_MODES);
        return b;
    }

    static Bundle usbSet(String mode) throws Exception {
        int k = java.util.Arrays.asList(USB_MODES).indexOf(mode == null ? "" : mode);
        if (k < 0) return err("bad_arg", "charge, mtp, ptp or midi");
        if (!sUsbConnected) return err("not_connected", "Connect the R4 to a computer first");
        android.hardware.usb.UsbManager um = sApp.getSystemService(android.hardware.usb.UsbManager.class);
        android.hardware.usb.UsbManager.class.getMethod("setCurrentFunctions", long.class).invoke(um, usbConst(USB_FIELDS[k]));
        notify("usb");
        return ok();
    }

    // ------------------------------------------------------------------ date & time (phase 2)
    private static String zoneCity(String id, java.util.Locale loc) {
        try {
            String c = android.icu.text.TimeZoneNames.getInstance(loc).getExemplarLocationName(id);
            if (c != null && !c.isEmpty()) return c;
        } catch (Throwable ignored) { }
        int slash = id.lastIndexOf('/');
        return (slash >= 0 ? id.substring(slash + 1) : id).replace('_', ' ');
    }
    private static String zoneLong(String id, java.util.Locale loc) {
        return java.util.TimeZone.getTimeZone(id).getDisplayName(false, java.util.TimeZone.LONG, loc);
    }
    private static boolean hour24() {
        String v = Settings.System.getString(sApp.getContentResolver(), Settings.System.TIME_12_24);
        if ("24".equals(v)) return true;
        if ("12".equals(v)) return false;
        return android.text.format.DateFormat.is24HourFormat(sApp);
    }

    static Bundle timeGet() {
        java.util.Locale loc = sApp.getResources().getConfiguration().getLocales().get(0);
        String zone = java.util.TimeZone.getDefault().getID();
        Bundle b = ok();
        b.putString("auto_time", Settings.Global.getInt(sApp.getContentResolver(), Settings.Global.AUTO_TIME, 1) != 0 ? "1" : "0");
        b.putString("auto_zone", Settings.Global.getInt(sApp.getContentResolver(), Settings.Global.AUTO_TIME_ZONE, 1) != 0 ? "1" : "0");
        b.putString("zone", zone);
        b.putString("zone_name", zoneCity(zone, loc));
        b.putString("zone_long", zoneLong(zone, loc));
        b.putInt("offset", java.util.TimeZone.getTimeZone(zone).getOffset(System.currentTimeMillis()) / 60000);
        b.putString("hour24", hour24() ? "1" : "0");
        b.putString("hour24_auto", Settings.System.getString(sApp.getContentResolver(), Settings.System.TIME_12_24) == null ? "1" : "0");
        b.putLong("now", System.currentTimeMillis());
        return b;
    }

    static Bundle timeSet(String what, String value) {
        if (value == null) return err("bad_arg", "value is missing");
        android.content.ContentResolver cr = sApp.getContentResolver();
        switch (what == null ? "" : what) {
            case "auto_time": case "auto_zone": {
                Boolean on = bool(value);
                if (on == null) return err("bad_arg", "value must be 0 or 1");
                Settings.Global.putInt(cr, "auto_time".equals(what) ? Settings.Global.AUTO_TIME : Settings.Global.AUTO_TIME_ZONE, on ? 1 : 0);
                notify("time");
                return ok();
            }
            case "hour24": {
                Boolean on = "auto".equals(value) ? null : bool(value);
                if (on == null && !"auto".equals(value)) return err("bad_arg", "value must be 0, 1 or auto");
                Settings.System.putString(cr, Settings.System.TIME_12_24, on == null ? null : on ? "24" : "12");
                if (on == null) on = android.text.format.DateFormat.is24HourFormat(sApp);
                // what Android's Settings does, so the status bar clock switches at once
                Intent i = new Intent(Intent.ACTION_TIME_CHANGED);
                i.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING | 0x01000000 /* FLAG_RECEIVER_INCLUDE_BACKGROUND, hidden */);
                i.putExtra("android.intent.extra.TIME_PREF_24_HOUR_FORMAT", on ? 1 : 0);
                sApp.sendBroadcast(i);
                notify("time");
                return ok();
            }
            case "zone": {
                if (Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE, 1) != 0)
                    return err("auto_on", "Turn off the automatic time zone first");
                if (!java.util.Arrays.asList(java.util.TimeZone.getAvailableIDs()).contains(value))
                    return err("bad_arg", "Unknown time zone");
                sApp.getSystemService(AlarmManager.class).setTimeZone(value);
                notify("time");
                return ok();
            }
            case "now": {
                if (Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 1) != 0)
                    return err("auto_on", "Turn off the automatic time first");
                long ms;
                try { ms = Long.parseLong(value); } catch (Exception e) { return err("bad_arg", "now must be milliseconds since 1970"); }
                if (ms < 1704067200000L || ms > 4102444800000L) return err("bad_arg", "That date is out of range");
                sApp.getSystemService(AlarmManager.class).setTime(ms);
                notify("time");
                return ok();
            }
        }
        return err("bad_arg", "auto_time, auto_zone, zone, hour24 or now");
    }

    static Bundle zonesList(String search) {
        java.util.Locale loc = sApp.getResources().getConfiguration().getLocales().get(0);
        String q = search == null ? "" : search.trim().toLowerCase(loc);
        long now = System.currentTimeMillis();
        ArrayList<Bundle> list = new ArrayList<>();
        for (String id : android.icu.util.TimeZone.getAvailableIDs(android.icu.util.TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, null, null)) {
            String name = zoneCity(id, loc), lng = zoneLong(id, loc);
            if (!q.isEmpty() && !name.toLowerCase(loc).contains(q) && !lng.toLowerCase(loc).contains(q) && !id.toLowerCase(loc).contains(q)) continue;
            Bundle z = new Bundle();
            z.putString("id", id);
            z.putString("name", name);
            z.putString("long", lng);
            z.putInt("offset", java.util.TimeZone.getTimeZone(id).getOffset(now) / 60000);
            list.add(z);
        }
        list.sort((x, y) -> x.getInt("offset") != y.getInt("offset") ? Integer.compare(x.getInt("offset"), y.getInt("offset"))
                : x.getString("name").compareToIgnoreCase(y.getString("name")));
        Bundle b = ok();
        b.putParcelableArrayList("zones", list);
        return b;
    }

    // ------------------------------------------------------------------ storage (phase 2)
    // StorageManager.getVolumes / mount / unmount and VolumeInfo / DiskInfo are hidden: reflection.
    private static final String[] VOL_STATES = { "unmounted", "checking", "mounted", "mounted", "formatting", "ejecting",
            "unmountable", "removed", "removed" };

    private static List<?> volumes() throws Exception {
        StorageManager sm = sApp.getSystemService(StorageManager.class);
        return (List<?>) StorageManager.class.getMethod("getVolumes").invoke(sm);
    }
    private static Object field(Object o, String f) {
        try { return o.getClass().getField(f).get(o); } catch (Throwable t) { return null; }
    }

    static Bundle storageGet() throws Exception {
        Bundle b = ok();
        ArrayList<Bundle> list = new ArrayList<>();
        for (Object v : volumes()) {
            int type = (Integer) field(v, "type");          // 0 public (SD/USB), 1 private (/data), 2 emulated
            String id = (String) field(v, "id");
            int state = (Integer) field(v, "state");
            Bundle o = new Bundle();
            String path;
            if (type == 1 && "private".equals(id)) {
                o.putString("id", "internal");
                o.putString("kind", "internal");
                o.putString("label", "Internal storage");
                path = "/data";
            } else if (type == 0) {
                Object disk = call0(v, "getDisk");
                boolean usb = disk != null && Boolean.TRUE.equals(call0(disk, "isUsb"));
                o.putString("id", id);
                o.putString("kind", usb ? "usb" : "sd");
                String label = (String) call0(v, "getDescription");
                Object fsLabel = field(v, "fsLabel");
                o.putString("label", fsLabel instanceof String && !((String) fsLabel).isEmpty() ? (String) fsLabel
                        : label != null ? label : usb ? "USB drive" : "microSD card");
                Object fs = field(v, "fsType");
                if (fs instanceof String) o.putString("fs", (String) fs);
                Object p = field(v, "path");
                path = p instanceof String ? (String) p : null;
            } else continue;
            o.putString("state", state >= 0 && state < VOL_STATES.length ? VOL_STATES[state] : "unmounted");
            if (state == 2 || state == 3) {
                try {
                    StatFs f = new StatFs(path);
                    o.putLong("total", f.getTotalBytes());
                    o.putLong("free", f.getAvailableBytes());
                } catch (Throwable ignored) { }
            }
            list.add(o);
        }
        b.putParcelableArrayList("volumes", list);
        return b;
    }

    static Bundle storageMount(String id, boolean mount) throws Exception {
        if (id == null || "internal".equals(id)) return err("bad_arg", "Only a microSD card or USB drive can do that");
        for (Object v : volumes()) {
            if (!id.equals(field(v, "id"))) continue;
            if ((Integer) field(v, "type") != 0) return err("bad_arg", "Only a microSD card or USB drive can do that");
            int state = (Integer) field(v, "state");
            if (mount && (state == 2 || state == 3)) return ok();
            if (!mount && state != 2 && state != 3) return ok();
            StorageManager sm = sApp.getSystemService(StorageManager.class);
            StorageManager.class.getMethod(mount ? "mount" : "unmount", String.class).invoke(sm, id);
            notify("storage");
            return ok();
        }
        return err("not_found", "That card or drive isn't there");
    }

    // ------------------------------------------------------------------ display (phase 2)
    static final int[] TIMEOUTS = { 15, 30, 60, 120, 300, 600, 1800, 0 };   // 0 = never

    // Android's brightness slider works in a perceptual ("gamma", HLG) scale; the backlight in a linear one. These are
    // SystemUI's BrightnessUtils formulas, so a percentage here is the same position as on Android's own slider.
    private static final float R = 0.5f, A = 0.17883277f, B = 0.28466892f, C = 0.55991073f;
    private static float percentToLinear(float pct, float min, float max) {
        float n = Math.max(0f, Math.min(1f, pct / 100f));
        float r = n <= R ? (n / R) * (n / R) : (float) Math.exp((n - C) / A) + B;
        return min + (max - min) * Math.max(0f, Math.min(12f, r)) / 12f;
    }
    private static float linearToPercent(float v, float min, float max) {
        float n = max > min ? (v - min) / (max - min) * 12f : 0f;
        float r = n <= 1f ? (float) Math.sqrt(Math.max(0f, n)) * R : A * (float) Math.log(n - B) + C;
        return Math.max(0f, Math.min(1f, r)) * 100f;
    }
    /** min / max backlight (hidden PowerManager.getBrightnessConstraint), 0..1 when unknown. */
    private static float[] brightnessRange() {
        float min = 0f, max = 1f;
        try {
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            Method m = PowerManager.class.getMethod("getBrightnessConstraint", int.class);
            min = (Float) m.invoke(pm, 0); max = (Float) m.invoke(pm, 1);
        } catch (Throwable t) { Log.w(TAG, "brightness range", t); }
        return new float[]{ min, max };
    }
    private static float brightnessNow() {
        try {
            DisplayManager dm = sApp.getSystemService(DisplayManager.class);
            return (Float) DisplayManager.class.getMethod("getBrightness", int.class).invoke(dm, 0);
        } catch (Throwable t) {
            int v = Settings.System.getInt(sApp.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 255);
            return v / 255f;
        }
    }

    static Bundle displayGet() {
        float[] r = brightnessRange();
        Bundle b = ok();
        b.putInt("brightness", Math.round(linearToPercent(brightnessNow(), r[0], r[1])));
        int ms = Settings.System.getInt(sApp.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 60000);
        b.putInt("timeout", ms == Integer.MAX_VALUE || ms < 0 ? 0 : ms / 1000);   // 0 = never (Android stores Integer.MAX_VALUE)
        b.putIntArray("timeouts", TIMEOUTS);
        return b;
    }

    static Bundle displaySet(String what, String value) throws Exception {
        int n;
        try { n = Integer.parseInt(value); } catch (Exception e) { return err("bad_arg", "value must be a number"); }
        if ("brightness".equals(what)) {
            if (n < 0 || n > 100) return err("bad_arg", "brightness must be 0 to 100");
            float[] r = brightnessRange();
            Settings.System.putInt(sApp.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            DisplayManager dm = sApp.getSystemService(DisplayManager.class);
            DisplayManager.class.getMethod("setBrightness", int.class, float.class).invoke(dm, 0, percentToLinear(n, r[0], r[1]));
            notify("display");
            return ok();
        }
        if ("timeout".equals(what)) {
            boolean offered = false;
            for (int t : TIMEOUTS) if (t == n) offered = true;
            if (!offered) return err("bad_arg", "timeout must be one of the offered values");
            Settings.System.putInt(sApp.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, n == 0 ? Integer.MAX_VALUE : n * 1000);
            notify("display");
            return ok();
        }
        return err("bad_arg", "brightness or timeout");
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

    private static String codec(String address) {
        BluetoothA2dp p = sA2dp;
        if (p == null) { connectA2dp(); return null; }
        try {
            BluetoothCodecStatus st = codecStatus(p, bt().getRemoteDevice(address));
            return st == null || st.getCodecConfig() == null ? null : codecName(st.getCodecConfig().getCodecType());
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
