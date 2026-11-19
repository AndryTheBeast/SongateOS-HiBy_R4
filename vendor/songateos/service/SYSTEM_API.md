# SongateOS system settings API (v1, 2026-11-16)

**Status:** phase 1 implemented in HiBy R4 service v31 and tested with Songate. Phase 2: Bluetooth codec in v32, display in v33, earbuds controls (Soundcore Liberty 4 NC) in v35, storage in v36, date & time in v37, about + USB in v38, hold switch in v45, recovery switch in v46, root (Magisk) in v47.
**Not in Songate (user decision 2026-11-16):** date & time and language stay in Android's own Settings. The time
methods exist in the service but Songate doesn't need them; the language methods are not implemented.

The system settings Songate shows in its own UI (Wi-Fi, Bluetooth, audio output, system colours, display, storage,
time, language, system) are
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
    `theme`, `display`, `storage`, `time`, `lang`, `power`, `ota`, `usb`, `about`, `setup`, `apps`);
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
| `…/system/theme` | the system colour or dark / light changed |
| `…/system/display` | brightness, timeout |
| `…/system/earbuds` | after `earbuds_set` |
| `…/system/storage` | card inserted / ejected / mounted |
| `…/system/time` | zone, auto time, 24-hour |
| `…/system/usb` | cable, USB mode |
| `…/system/hold` | hold switch flipped, hold settings changed |
| `…/system/recovery` | recovery switch progress |
| `…/system/root` | Magisk install / removal progress |
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
| `bt_codec_get` | address, or empty = the connected headphones | – | `address`, `codec` (`sbc` `aac` `aptx` `aptx_hd` `ldac` `lc3` `opus`), `rate` (Hz, int), `bits` (int), `ldac` (`hq` `sq` `mq` `abr`, LDAC only), `available` (String[]: codecs both sides support) |
| `bt_codec_set` | address, or empty | `codec`, `ldac` (optional, LDAC only) | `ok`; Android re-negotiates, the new codec comes through `…/system/bt` (re-read with `bt_codec_get`) |

`devices` entries:
- `address`, `name`, `kind` (`headphones` `speaker` `car` `phone` `computer` `other`);
- `bonded`, `connected` ("0"/"1"), `battery` (0–100, or absent);
- `rssi` (found devices only).

Paired devices come first, then the found ones.

**Codec (cap `bt_codec`, service v32):**
- `bt_codec_get` / `bt_codec_set` work only on a device connected for music. Otherwise `not_connected` ("No Bluetooth
  headphones connected" / "That device isn't connected for music").
- `busy` right after Bluetooth turns on or a device connects ("Bluetooth audio isn't ready yet, try again", "The codec
  isn't known yet, try again"): retry after the next `…/system/bt` notification.
- `unsupported` when the codec isn't in `available` ("These headphones don't support that codec").
- The rate and bit depth are left to Android (it picks the best both sides support for that codec); there's no
  separate setting for them in v1.
- The choice is Android's own per-device preference: it stays across reconnects and restarts.

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

### System colours (cap `theme`)

The colours of the parts Android still draws on SongateOS: the status bar, notifications and quick settings, Android's
dialogs and the pages under "Advanced". Songate's own interface keeps its colours (Appearance › Interface colours).

| method | arg | extras | returns |
|---|---|---|---|
| `theme_get` | – | – | `colour` (`#rrggbb` in use), `default` (`#fb695a`, the boot animation's coral), `dark` ("0"/"1"), `exact` ("0"/"1": the exact palette below is on) |
| `theme_set` | – | `colour` (`#rrggbb`, or `default`), `dark` ("0"/"1"): one or both | `ok` |

- **First boot:** coral `#fb695a` (the boot animation's), dark.
- **Any colour works,** but very dark or very light ones give grey system colours.
- **Dark / light follows Songate:** Songate sends `dark` whenever its own Appearance brightness changes, so the
  system is always dark or light with it. There's no separate switch for the system.
- **A change restarts open screens:** Android re-colours every app's open screens by restarting them once, Songate
  included. So `theme_set` is for a user's choice, never for following the cover. Songate must come back to where it
  was.
- **Implementation:** the service writes Android's colour setting (`THEME_CUSTOMIZATION_OVERLAY_PACKAGES`: the colour,
  palette style `TONAL_SPOT`, colour source `preset`) and Android's night mode. These names stay inside the service;
  the user never sees them.
- **Exact palette:** on top of that, the service generates the whole system palette from the colour (for coral, the
  boot animation's own table with its black `#140809`; other colours through OKLCH) and registers it as a fabricated
  overlay on `android`, re-registered at every boot and kept highest. `Settings.Global songateos_exact_palette` = 0
  turns it off (then only Android's Monet colours apply).

### Earbuds controls (cap `earbuds`, service v35)

Earbuds with their own settings (noise cancelling, transparency…), controlled over the earbuds' own Bluetooth channel.
Supported now: **Soundcore Liberty 4 NC**. Others answer `unsupported` ("The R4 can't control these earbuds yet").
AirPods may follow after the SongateOS image (they need a Bluetooth stack fix).

| method | arg | extras | returns |
|---|---|---|---|
| `earbuds_get` | address, or empty = the connected headphones | – | see below |
| `earbuds_set` | address, or empty | any of `mode`, `anc`, `anc_level`, `transparency`, `wind`, `transport` (Strings); only the given ones change | the same fields as `earbuds_get`, read back from the earbuds after the change |

Fields:
- `kind` (`soundcore`), `name`, `address`, `firmware`;
- `battery_left`, `battery_right` (0–100, in steps of 20: the earbuds report fifths), `charging_left`, `charging_right`
  ("0"/"1"). No case battery yet;
- `modes` (String[] the earbuds offer) and `mode`: `noise_cancelling` `transparency` `normal`;
- `anc` (how noise cancelling works): `adaptive` (the earbuds choose the strength), `manual` (uses `anc_level` 1–5),
  `transport` (uses `transport`: `plane` `train` `bus` `car`);
- `transparency`: `full` or `vocal` (voices only);
- `wind` ("0"/"1"): wind-noise reduction.

Notes:
- Each call opens the earbuds' channel, takes about 1 s, then closes it. Don't poll; read when the page or pop-up opens.
- Changes made on the earbuds themselves (touch) are **not** pushed. `…/system/earbuds` fires only after `earbuds_set`.
- After `earbuds_set`, `ok=false` with `failed: The earbuds didn't take the change` means the earbuds answered but kept the old values.
- Errors:
  - `not_connected`, `busy`, `off`: same as `bt_codec_get`;
  - `unsupported`;
  - `bad_arg` ("Unknown mode value", "anc_level must be 1 to 5", …);
  - `failed` (no answer within 6 s).
- No serial numbers or raw data are passed on.

### Hold switch (cap `hold`, service v45)

The switch on top of the R4 locks the player's own buttons. The settings below decide what it locks.

| method | arg | extras | returns |
|---|---|---|---|
| `hold_get` | – | – | `locked` ("0"/"1": the switch's position now), `level`, `levels` (String[]), `sleep` ("0"/"1") |
| `hold_set` | – | `level` and/or `sleep` (Strings) | the same fields as `hold_get` |

- `level`:
  - `media`: play/pause, previous and next locked; volume and power still work;
  - `volume` (default): media and volume locked; power still turns the screen on and off;
  - `all`: every button on the R4 locked, until the switch is unlocked.
- `sleep` (default "1"): the screen turns off when the switch is locked, and back on when it's unlocked.
- Only the R4's own buttons. A wired headphone remote and Bluetooth buttons always work.
- While locked, buttons that are still allowed react with a short delay on the LineageOS GSI (about 0.5–1 s), and the
  long press on power (power menu) doesn't work. From SongateOS 0.1.0-beta.3 Android's key handling filters the
  locked buttons itself: allowed buttons work with no delay, long-press power included.
- At start-up the switch's position is known only after its first flip. Until then `locked` is "0".
- Observer `…/system/hold`: fires on every flip and every `hold_set`.
- Errors: `bad_arg` ("level must be media, volume or all", "sleep must be 0 or 1").

### Recovery switch (cap `recovery`, service v46)

TWRP or OrangeFox, downloaded through the SongateOS update channel and put in place from Android. No restart needed.

| method | arg | extras | returns |
|---|---|---|---|
| `recovery_get` | – | – | see below |
| `recovery_check` | – | – | `ok` = started; looks for recovery packages in the SongateOS releases (result through `recovery_get` / the observer) |
| `recovery_switch` | `twrp` / `orangefox` | – | `ok` = started; downloads that recovery's newest package and puts it in place |

`recovery_get` fields:
- `installed`: `twrp`, `orangefox`, `other` or `none`. `installed_ready` ("0"/"1") = it has the SongateOS update support
  and was checked. `installed_version` (e.g. `2026.11.16`) when it was put in place by a switch.
- `kinds` (String[]), and for each kind with a package: `twrp_version` / `orangefox_version`, `twrp_size` /
  `orangefox_size` (Long bytes).
- `state`: `idle` `checking` `downloading` `verifying` `switching` `done` `error`; `error` (text); `progress` (Long
  bytes while downloading); `last_check` (ms).

How it works and what the user should know:
- Packages are `songateos-recovery-<kind>-<version>.zip` in the releases, signed with the SongateOS release key; a
  package with any other signature is refused and deleted.
- Both recovery slots (`recovery_a` and `recovery_b`) get the new recovery, each read back after writing. The
  player's active slot is never changed.
- Offer "Switch to OrangeFox" / "Switch to TWRP", and "Update" when `<installed>_version` is newer than
  `installed_version`.
- Needs the microSD card (download) and the internet (GitHub).
- Observer `…/system/recovery`.
- Errors in `error`, e.g. "Insert a microSD card first.", "The recovery package is not signed with the SongateOS key,
  it was deleted.", "Couldn't switch: …".

### Root (Magisk) (cap `root`, service v47)

Optional root for users who want it. SongateOS itself doesn't need it.

| method | arg | extras | returns |
|---|---|---|---|
| `root_get` | – | – | `installed` ("0"/"1"), `version` (installed Magisk, e.g. `31.1`), `latest` (from its last check), `keep` ("0"/"1"), `state`, `error` |
| `root_check` | – | – | `ok` = started; looks for the newest official Magisk |
| `root_install` | – | – | `ok` = started; downloads, checks and installs (or updates) Magisk |
| `root_remove` | – | – | `ok` = started; puts the original boot image back |
| `root_set` | – | `keep` ("0"/"1") | the same fields as `root_get` |

- `state`: `idle` `checking` `downloading` `verifying` `installing` `removing` `restart` `error`. **`restart`**: done,
  a restart finishes it; offer a "Restart now" button (`power restart`). After the restart `state` is `idle`.
- Only Magisk's official releases on GitHub (topjohnwu/Magisk); the app must be signed by Magisk's developer, or
  it's refused.
- Install uses Magisk's own patch tool on the player's boot image, like the Magisk app's "Direct install"; the
  Magisk app is installed too. Removal puts back the exact original boot image (checked).
- `keep` ("Keep root after updates", default on): SongateOS updates don't replace the boot image today, so root
  stays anyway; the setting is for future updates that will.
- Warn before installing: root apps can change anything on the player.
- Observer `…/system/root`.

### Display (cap `display`)

| method | arg | extras | returns |
|---|---|---|---|
| `display_get` | – | – | `brightness` 0–100, `timeout` (seconds), `timeouts` (Int[] offered: 15, 30, 60, 120, 300, 600, 1800, 0); `0` = never |
| `display_set` | `brightness` / `timeout` | `value` | `ok` |

There's no automatic brightness: the R4 has no light sensor that Android uses.

**Service v33:**
- `display_set` takes `arg` = `brightness` or `timeout` and the number in extras `value` (a String, like phase 1):
  brightness `0`–`100`; timeout in seconds, one of `timeouts`.
- **Brightness percent = the position on Android's own brightness slider** (perceptual scale, not linear backlight).
  So 50 looks like the middle of the quick settings slider. 0 is the dimmest the screen allows, not off.
- Setting brightness also switches Android to manual brightness (nothing else on the R4 changes it).
- **Never** (service v41): timeout `0` means the screen never turns off by itself (Android stores it as
  `Integer.MAX_VALUE`, like its own "Never"). Songate's sleep timer and dimming are separate and keep working.
- `…/system/display` fires for changes from anywhere, the quick settings slider included. While the user drags
  Songate's slider, send values as it moves; the observer is coalesced to one notification per 300 ms.
- Errors: `bad_arg` ("brightness must be 0 to 100", "timeout must be one of the offered values", "value must be a
  number", "brightness or timeout").

### Storage (cap `storage`)

| method | arg | extras | returns |
|---|---|---|---|
| `storage_get` | – | – | `volumes` (list: `id`, `label`, `kind` `internal`/`sd`/`usb`, `state` `mounted`/`unmounted`/`ejecting`/`unmountable`, `total`, `free` bytes, `fs`) |
| `storage_eject` | id | – | `ok` (`sd`/`usb` only) |
| `storage_mount` | id | – | `ok` |

Formatting isn't part of v1; it stays in Android's Settings.

**Service v36:**
- `volumes`: internal storage first (`id` `internal`, `label` "Internal storage"), then each microSD card or USB drive.
  - Their `id` is Android's volume id (e.g. `public:179,65`). Keep it only while the card stays in.
  - `label` is the card's own name, or "microSD card" / "USB drive".
- `total` / `free` (Long, bytes) only while `mounted`.
- `state` can also be `checking`, `formatting` or `removed`.
- `storage_eject` = safely remove: Android stops using the card first. **Pause playback from the card before**
  (Songate's own library may be on it). Answers `ok` at once; the end comes through `…/system/storage` (state
  `ejecting` → `unmounted`).
- `storage_mount` uses the card again without taking it out.
- Errors:
  - `bad_arg` ("Only a microSD card or USB drive can do that");
  - `not_found` ("That card or drive isn't there").

### Date & time (cap `time`), language (cap `lang`)

| method | arg | extras | returns |
|---|---|---|---|
| `time_get` | – | – | `auto_time`, `auto_zone`, `zone` (id), `zone_name`, `hour24`, `now` (epoch ms) |
| `time_set` | `auto_time` / `auto_zone` / `zone` / `hour24` / `now` | `value` | `ok` (`now` and `zone` only while the matching auto setting is off) |
| `zones_list` | optional search text | – | `zones` (list: `id`, `name`, `offset` minutes), sorted by offset then name |
| `lang_get` | – | – | `current` (BCP-47, e.g. `pt-PT`), `available` (list: `tag`, `name` in its own language) |
| `lang_set` | tag | – | `ok`. Android restarts every visible activity with the new language, Songate included |

**Date & time, service v37:**
- `time_get` also returns:
  - `zone_long` ("Greenwich Mean Time"; `zone_name` is the city, "London");
  - `offset` (minutes from UTC now);
  - `hour24_auto` ("1" = the 12/24-hour format follows the language).
- `time_set`:
  - `arg` is `auto_time`, `auto_zone`, `zone`, `hour24` or `now`; the value goes in extras `value`.
  - `hour24` takes `0`, `1` or `auto`.
  - `now` takes milliseconds since 1970 as a String.
- `zone` and `now` answer `auto_on` ("Turn off the automatic time zone first" / "Turn off the automatic time first")
  while the matching automatic setting is on.
- `zones_list`:
  - each entry has `id`, `name` (city), `long` (zone name), `offset` (minutes);
  - about 400 zones, sorted by offset then name;
  - the optional search text matches city, zone name or id.
- Names are in the R4's current language.
- `…/system/time` fires on any change of these settings, the zone, or a time jump. It doesn't fire every minute:
  Songate's own clock ticks by itself.
- On the R4, automatic time needs Wi-Fi (network time). With no mobile network or location there is nothing to
  detect the zone from, so the automatic zone most likely just keeps the last zone (not tested).

### First-boot setup (cap `setup`, service v52)

SongateOS has no Android setup wizard: the first-boot setup is Songate's own, in Songate's style. The service keeps
its state and finishes Android's side.

| method | arg | extras | returns |
|---|---|---|---|
| `setup_get` | – | – | `done` ("1" = Songate's setup was finished), `android` ("1" = Android's setup flags are set) |
| `setup_done` | – | – | the same as `setup_get`, after setting both |
| `setup_reset` | – | – | development only (system uid): `done` back to "0"; Android's flags stay set |

- **Songate declares that it has setup screens** in its manifest, on `<application>`:
  `<meta-data android:name="songateos.setup" android:value="true"/>`. Without it the service doesn't wait for Songate:
  Android's flags are set at the first start (a Songate without setup screens still gets a working R4).
- **When to show the setup:** at Songate's start, when `sys_info` lists `setup` and `setup_get` → `done` is "0".
  Call `setup_done` on the last screen. After that, `done` stays "1" (also after updates); only Format Data clears it.
- **Android's flags** (`device_provisioned`, `user_setup_complete`): until they are set, SystemUI keeps the status
  bar, quick settings and gesture navigation off, and Settings refuses developer options. That suits a full-screen
  setup, and the safety net makes sure it can't stay that way:
  - set by `setup_done`;
  - set straight away when Songate is missing, disabled or doesn't declare setup screens;
  - set from the second boot on when the setup still isn't done (someone restarted instead of finishing it). `done`
    stays "0", so Songate's setup still shows; only the bars come back.
- Observer `…/system/setup` fires when `done` or `android` changes.
- **Screens** (Songate's choice; all through calls that exist already):
  1. Welcome: SongateOS wordmark. English (British) only, so no language screen.
  2. Wi-Fi (`wifi_*`): join a network, or skip for offline use.
  3. Time zone (`time_get`, `zones_list`, `time_set zone`): needed because the R4 has no mobile network; with Wi-Fi,
     automatic time sets the clock (the R4's clock resets after every restart until it does).
  4. Music: the microSD music folders and the files permission (Songate's own; All files access is already allowed
     at first boot).
  5. Sound (`output_*` and the HiBy settings): output, gain, bit-perfect.
  6. Colour (`theme_get`, `theme_set`): coral (default) or another colour.
  7. Hold switch (`hold_get`, `hold_set`): what the top switch locks.
  8. Bluetooth (`bt_*`): pair headphones; optional.
  9. Done → `setup_done`.

### Gestures and the app switcher (cap `apps`, service v54)

SongateOS has no LineageOS launcher, so Android's Quickstep (home swipe, recent apps) is gone. In gesture navigation
(`navigation_mode` 2) the service handles the bottom edge itself: swipe up = home (Songate); swipe up and hold
(about 0.3 s still after moving 9 % of the screen up) = Songate's app switcher, opened with the intent action
`songateos.intent.action.APP_SWITCHER` (package `io.github.andrythebeast.songate`, `FLAG_ACTIVITY_NEW_TASK`), with a
haptic tick; without such an activity in Songate it goes home instead. Back stays Android's side-edge gesture.
Three-button navigation keeps Android's buttons.

| method | arg | extras | returns |
|---|---|---|---|
| `apps_recent` | – | – | `apps`: list of {`task` (Int), `package`, `label`, `icon` (PNG bytes, 96 px), `shot` (JPEG bytes, 240 px wide, may be missing)}, newest first; Songate, home screens and the service left out |
| `apps_open` | task | – | `ok` (brings the app back; starts it again from its recent entry if it was stopped) |
| `apps_close` | task or `all` | – | `ok` (closes the app's task) |

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

**Update checks, service v53:** while `auto` is on (`ota_set auto 1`, default), the service checks once after every boot,
as soon as unmetered Wi-Fi is up (about 30 s after boot at the earliest), on top of the daily check. Songate starts no
check of its own: it reads `ota_status` when Settings opens and follows `…/system/ota`. `notes` is always the GitHub
release body (GitHub Markdown; SongateOS release notes use only headings, bullet lists and bold). On SongateOS, Songate
updates only with the OS (no Songate update tab, no APK installs).

There's no device serial number in `about` (personal identifier).

**About and USB, service v38:**
- **About:** Songate merges these into its own About page.
  - `about` returns `device` ("HiBy R4"), `songateos`, `android`, `patch` (system security patch) and `vendor_patch`
    (HiBy's vendor patch, 2021 on the R4), `build`, `kernel`, `recovery`, `service` (this service's version), `ram`
    (bytes).
  - It also returns `battery` (0–100), `battery_status` (`charging` `full` `discharging`) and `battery_temp` (tenths of
    a °C).
- **USB:** `usb_get` → `connected` ("0"/"1": a computer is connected), `mode`, `modes`
  (`charge` `mtp` `ptp` `midi`).
  - `charge` = no data. `mtp` = file transfer: the computer sees the internal storage and the microSD card. `ptp` =
    photos. `midi` = the R4 as a MIDI device.
  - `usb_set <mode>` works only while connected (`not_connected`: "Connect the R4 to a computer first").
  - Android goes back to `charge` when the cable is unplugged. Plugging in again starts with no data, as on phones.
  - Observer `…/system/usb` fires on plugging in and out and on mode changes.

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
| Theme | `Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES` JSON (`android.theme.customization.system_palette` = `#rrggbb`, `…theme_style` = `TONAL_SPOT`, `…color_source` = `preset`, read by SystemUI `ThemeOverlayController`); `UiModeManager.setNightModeActivated` / night mode `yes`/`no`. First-boot default written once by the service when the setting is empty |
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

1. Permission, `sys_info`, Wi-Fi, Bluetooth (without codec), audio output switcher, system colours, `power`, OTA
   access for Songate.
2. Bluetooth codec, display, storage, date & time, language, `about`, USB.
3. Pairing confirmation inside Songate (instead of Android's dialog); a theme overlay so the remaining Android screens
   (permission prompts, installer, Advanced) use Songate's colours and fonts.
