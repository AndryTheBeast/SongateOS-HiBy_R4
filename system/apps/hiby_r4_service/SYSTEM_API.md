# SongateOS system settings API (design v1, 2026-11-16, NOT implemented yet)

The system settings Songate shows in its own UI (Wi-Fi, Bluetooth, audio output, display, storage, time, language,
system) are
done by the **HiBy R4 service** (`com.andre.hibyr4.service`, platform-signed, system uid). Normal apps aren't allowed
to do most of this on Android 14 (turn Wi-Fi on/off, join and save networks, Bluetooth on/off and codec, time,
language, USB mode). Songate draws the pages; the service does the work.

It extends the existing HiBy settings API ([system/README.md](../../README.md#the-hiby-settings-api)):
- **Same channel:** provider `content://com.andre.hibyr4.settings`, `ContentResolver.call(uri, method, arg, extras)`
  → `Bundle`.
- **Same answer:** `ok` (Boolean) and `error` (String) on failure.

New: a **stronger permission** for everything here, and **change notifications** through content observers.

## 1. Permission and versions

```xml
<!-- service manifest (declared there; Songate only uses it) -->
<permission android:name="com.andre.hibyr4.permission.SYSTEM_SETTINGS"
    android:protectionLevel="signature|knownSigner"
    android:knownCerts="@array/songate_certs" />
```

- **Who gets it:**
  - apps signed with the platform key (Settings, the service itself);
  - apps whose signing certificate SHA-256 is in `songate_certs`: Songate's release certificate, and for testing,
    the developer's debug certificate (test builds only, never in a release image).
- **Every other app is refused,** even with `HIBY_SETTINGS` (`error = "no_permission"`). `knownSigner` is Android 12+;
  the R4 runs 14.
- **Songate declares** `<uses-permission android:name="com.andre.hibyr4.permission.SYSTEM_SETTINGS" />`. It's granted
  at install when the certificate matches. If Songate was installed before the service knew its certificate,
  reinstall it once.
- **Check what this system offers:**
  - `sys_info` → `api` (Int, 1 for this version) and `caps` (String[]: `wifi`, `bt`, `bt_codec`, `output`,
    `display`, `storage`, `time`, `lang`, `power`, `ota`, `usb`, `about`);
  - plus `songateos` (version, `0.0.0` on the GSI) and `ready` (the module service is running).
  - Songate shows a page only when its cap is listed; otherwise it offers Android's page as a fallback.

`sys_info` needs only `HIBY_SETTINGS`, so Songate can check before the strong permission is granted.

## 2. Change notifications

The service calls `notifyChange` on these URIs when the state changes, whoever changed it. Songate registers a
`ContentObserver` while a page is visible and calls the matching `*_get` again.

| URI | changes when |
|---|---|
| `content://com.andre.hibyr4.settings/system/wifi` | on/off, scan results, connecting / connected / failed, saved list |
| `…/system/bt` | on/off, discovery results, bond and connection changes, codec |
| `…/system/output` | an output appears or disappears (plug in / out, Bluetooth or USB DAC connects), the active output changes, its codec or bit-perfect state changes |
| `…/system/display` | brightness, timeout |
| `…/system/storage` | card inserted / ejected / mounted |
| `…/system/time` | zone, auto time, 24-hour |
| `…/system/usb` | cable, USB mode |
| `…/system/ota` | update state (see the OTA methods in the HiBy API) |

## 3. Methods

All need `SYSTEM_SETTINGS` (except `sys_info`). Booleans are passed as String `"0"`/`"1"` in `extras["value"]`, like
the HiBy `set`. Lists come back as `ArrayList<Bundle>`.

### Wi-Fi (cap `wifi`)

| method | arg | extras | returns |
|---|---|---|---|
| `wifi_get` | – | – | `enabled`, `state` (`off` `turning_on` `on` `turning_off`), `link` (`none` `connecting` `authenticating` `obtaining_ip` `connected` `failed`), `fail` (`wrong_password` `no_ip` `rejected` `timeout`, after a failure), `current` (Bundle: `ssid`, `level` 0–4, `freq` MHz, `speed` Mbit/s, `ip`, `security`), `networks` (list, see below), `scanning` |
| `wifi_set_enabled` | – | `value` | `ok` |
| `wifi_scan` | – | – | `ok`; results arrive through the observer |
| `wifi_connect` | – | `ssid`, `security` (`open` `owe` `psk` `sae`), `password` (psk/sae, 8–63 chars), `hidden` ("0"/"1") | `ok` = started; progress and result through `link` / `fail` |
| `wifi_connect_saved` | `key` | – | `ok` |
| `wifi_forget` | `key` | – | `ok` |

`networks` merges the scan results and the saved networks, one entry per network name, strongest first:
- `key` (stable id for the saved / connect calls), `ssid`, `level` 0–4 (absent when not in range), `security`;
- `saved` ("0"/"1"), `connected` ("0"/"1").

**Passwords:**
- Songate passes the password once, in `wifi_connect`.
- The service never returns stored passwords, and neither side logs them.
- Android keeps saved networks, the same as when they're added in Settings.

Enterprise networks (802.1X), hotspots and captive-portal sign-ins stay in Android's Settings ("Advanced").

### Bluetooth (cap `bt`, codec part cap `bt_codec`)

| method | arg | extras | returns |
|---|---|---|---|
| `bt_get` | – | – | `enabled`, `state`, `name` (the R4's Bluetooth name), `discovering`, `devices` (list) |
| `bt_set_enabled` | – | `value` | `ok` |
| `bt_discover` | `start` / `stop` | – | `ok`; found devices arrive through the observer |
| `bt_pair` | address | – | `ok` = started. Devices that need a PIN or confirmation still show Android's pairing dialog in v1 (most headphones don't) |
| `bt_connect` / `bt_disconnect` | address | – | `ok` (A2DP + headset profiles) |
| `bt_unpair` | address | – | `ok` |
| `bt_codec_get` | address | – | `codec` (`sbc` `aac` `aptx` `aptx_hd` `ldac` `lc3`…), `rate` Hz, `bits`, `ldac` (`hq` `sq` `mq` `abr`, LDAC only), `available` (String[] codecs both sides support) |
| `bt_codec_set` | address | `codec`, `ldac` (optional) | `ok`; Android re-negotiates, the result comes through the observer |

`devices` entries:
- `address`, `name`, `kind` (`headphones` `speaker` `car` `phone` `computer` `other`);
- `bonded`, `connected` ("0"/"1"), `battery` (0–100, or absent);
- `rssi` (found devices only).

Paired devices come first, then the found ones.

### Audio output (cap `output`)

The output switcher for Songate's volume overlay (and its settings), like the one in Android's volume panel: which
output all media plays on.

| method | arg | extras | returns |
|---|---|---|---|
| `output_get` | – | – | `outputs` (list, see below), `active` (id of the output media plays on now), `choice` (`auto` or the id the user picked) |
| `output_set` | id, or `auto` | – | `ok`. Moves all media to that output at once. `auto` = Android's normal rule: the newest connected output wins |

`outputs` entries:
- `id`: stable (`wired`, `bt:<address>`, `usb:<card>`).
- `kind`: one of
  - `headphone_35`, `lineout_35`: 3.5 mm;
  - `balanced_44`, `balanced_lineout_44`: 4.4 mm;
  - `bluetooth`;
  - `usb_dac`.
- `name`: for the user, e.g. "Headphones 3.5 mm", the Bluetooth device's name, the USB DAC's product name.
- `active` ("0"/"1").
- `codec`: Bluetooth only, same values as `bt_codec_get`.
- `bitperfect` ("1" when Songate can play bit-perfect on it: wired outputs while the HiBy `bit_perfect` setting is on;
  USB DACs only if the DAC offers it).
- `dac_volume` ("1" when the volume is set in the R4's DAC, i.e. wired with bit-perfect on).

What's listed and how it behaves:
- **Wired:** 3.5 mm and 4.4 mm are one Android device. The service tells them apart by what's plugged in (HiBy status
  `st_output`) and the HiBy `out35` / `out44` modes. So at most **one** wired entry is listed: the one plugged in.
- **Not listed:** the built-in speaker and earpiece; the R4 has no usable speaker output.
- **Choices are remembered:** a picked output stays chosen until the user picks another or `auto`, also across
  restarts. If the picked output goes away (unplugged, Bluetooth off), media falls back to `auto` by itself;
  `active` and the observer show it.
- **One output for everything:** switching moves all media, not only Songate's. Songate's own player follows the
  system output, the same as with Android's switcher.

### Display (cap `display`)

| method | arg | extras | returns |
|---|---|---|---|
| `display_get` | – | – | `brightness` 0–100, `timeout` (seconds), `timeouts` (Int[] offered: 15, 30, 60, 120, 300, 600, 1800) |
| `display_set` | `brightness` / `timeout` | `value` | `ok` |

There's no automatic brightness: the R4 has no light sensor that Android uses.

### Storage (cap `storage`)

| method | arg | extras | returns |
|---|---|---|---|
| `storage_get` | – | – | `volumes` (list: `id`, `label`, `kind` `internal`/`sd`/`usb`, `state` `mounted`/`unmounted`/`ejecting`/`unmountable`, `total`, `free` bytes, `fs`) |
| `storage_eject` | id | – | `ok` (`sd`/`usb` only) |
| `storage_mount` | id | – | `ok` |

Formatting isn't part of v1; it stays in Android's Settings.

### Date & time (cap `time`), language (cap `lang`)

| method | arg | extras | returns |
|---|---|---|---|
| `time_get` | – | – | `auto_time`, `auto_zone`, `zone` (id), `zone_name`, `hour24`, `now` (epoch ms) |
| `time_set` | `auto_time` / `auto_zone` / `zone` / `hour24` / `now` | `value` | `ok` (`now` and `zone` only while the matching auto setting is off) |
| `zones_list` | optional search text | – | `zones` (list: `id`, `name`, `offset` minutes), sorted by offset then name |
| `lang_get` | – | – | `current` (BCP-47, e.g. `pt-PT`), `available` (list: `tag`, `name` in its own language) |
| `lang_set` | tag | – | `ok`. Android restarts every visible activity with the new language, Songate included |

### System (caps `power`, `ota`, `about`, `usb`)

| method | arg | extras | returns |
|---|---|---|---|
| `power` | `restart` / `shutdown` / `recovery` / `recovery_check` | – | `ok`. The same restart paths as the update page (no "Factory data reset" screen) |
| `about` | – | – | `songateos`, `android`, `patch` (security patch date), `build`, `kernel`, `recovery` (same as `ota_status`), `battery` (level, status, temperature) |
| `usb_get` | – | – | `connected`, `mode` (`charge` `mtp`) |
| `usb_set` | `charge` / `mtp` | – | `ok` |

**OTA:** `ota_download`, `ota_install`, `ota_cancel`, `ota_set` and `reboot` (today Settings-only) are also accepted
from `SYSTEM_SETTINGS` holders, so Songate can host the whole update page. The OTA methods and fields are unchanged
(see the HiBy settings API).

There's no device serial number in `about` (personal identifier).

## 4. Errors

`ok = false` with `error = "<code>: <text for the user>"`. The codes:

| code | meaning |
|---|---|
| `no_permission` | the caller isn't Songate / Settings |
| `bad_arg` | missing or invalid argument |
| `unsupported` | this system doesn't offer it (check `caps`) |
| `busy` | another operation of the same kind is running |
| `off` | Wi-Fi / Bluetooth is switched off |
| `failed` | Android refused or the operation failed |

## 5. Service side (for the implementation, not for Songate)

- **Code:** new class `SystemApi` in the service app, dispatched from `HiBySettingsProvider.call`. Permission
  checked per method with `checkCallingPermission(SYSTEM_SETTINGS)`.
- **Android calls:**

| area | calls |
|---|---|
| Wi-Fi | `WifiManager.setWifiEnabled`, `startScan`/`getScanResults`, and the system APIs `connect(WifiConfiguration, listener)`, `forget`, `getPrivilegedConfiguredNetworks` |
| Bluetooth | `BluetoothAdapter`/`BluetoothDevice`, `BluetoothA2dp` codec calls (`getCodecStatus`, `setCodecConfigPreference`) |
| Output | `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`, `getDevicesForAttributes(USAGE_MEDIA)` (active), `setPreferredDeviceForStrategy` / `removePreferredDeviceForStrategy` on the media strategy (`MODIFY_AUDIO_ROUTING`), `AudioDeviceCallback` for changes. **Test on the R4:** wired chosen while Bluetooth stays connected (the module must still send HiBy's `connect=8;<Name>`); switching while bit-perfect is playing; DAC volume (`DacVolume`) handover between wired and Bluetooth |
| Time | `AlarmManager.setTimeZone`/`setTime`; `Settings.Global.AUTO_TIME*` |
| Language | `LocalePicker.updateLocales` |
| Storage | `StorageManager.mount`/`unmount` |
| USB | `UsbManager.setCurrentFunctions` |
| Power | `PowerManager` and `ACTION_REQUEST_SHUTDOWN` |

- **How the system calls are made:** with the Magisk module (public SDK build) through reflection; in the SongateOS
  image (in-tree, `platform_apis`) directly. The service runs as the system user, so it holds the needed signature
  permissions.
- **Observers:** the service listens to Android's broadcasts / callbacks (Wi-Fi state + scan + network, Bluetooth
  state + bond + ACL + A2DP codec, volume mount, time and zone changes, USB state). It calls `notifyChange` on the
  URIs above, at most every 300 ms per URI.
- **Certificates:** `songate_certs` holds Songate's release certificate SHA-256, plus the developer's debug
  certificate in test builds only.

## 6. Phases

1. Permission, `sys_info`, Wi-Fi, Bluetooth (without codec), audio output switcher, `power`, OTA access for Songate.
2. Bluetooth codec, display, storage, date & time, language, `about`, USB.
3. Pairing confirmation inside Songate (instead of Android's dialog); a theme overlay so the remaining Android screens
   (permission prompts, installer, Advanced) use Songate's colours and fonts.
