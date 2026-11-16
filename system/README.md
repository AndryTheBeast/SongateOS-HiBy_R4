# System module

The part of SongateOS that runs on top of the LineageOS 21 GSI on the HiBy R4 (standard model), for now as a Magisk
module (`hiby_r4_gsi_fix`). The full SongateOS build will carry the same pieces inside the system image.

| Folder | What's in it |
|---|---|
| [`module/hiby_r4_gsi_fix`](module/hiby_r4_gsi_fix) | Boot scripts and configs: HiBy's DAC outputs (3.5 mm / 4.4 mm, line out), sound settings, lights, Bluetooth audio fix, native rate (`out_rate`), wired bit-perfect, boot hand-off to Songate |
| [`patches`](patches) | `patch_libs.sh`: makes the patched audio libraries **from your own device's files** (no binaries in this repo) |
| [`apps/hiby_r4_service`](apps/hiby_r4_service) | HiBy R4 service (system app): the HiBy settings API for apps, equalizer, lights, line out, wired volume in the DAC, the SongateOS updater |
| [`apps/settings_hiby`](apps/settings_hiby) | The "HiBy audio", "Lights" and "SongateOS update" pages added to Android's Settings |
| [`ota`](ota) | System updates from GitHub releases: release key tool, update zip builder and installer, signature checker ([how it works](ota/README.md)) |
| [`apps/mastervol`](apps/mastervol) | Helper that sets Android's master volume (Bluetooth fix) |
| [`overlays`](overlays) | No voice/SMS, black boot hand-off screen, black default wallpaper |
| [`tools`](tools) | `BpTest`: a bit-perfect test player for checking the audio path with logs |
| [`build.sh`](build.sh) | Builds the module (Linux or WSL, Android SDK, no Gradle) |

## Build and install

```bash
PLATFORM_KEY=/path/to/aosp/build/make/target/product/security \
SETTINGS_APK=/path/to/Settings.apk  bash system/build.sh
```

- `PLATFORM_KEY`: the AOSP **test** platform key. The LineageOS GSI is signed with it, so the system apps and overlays
  built here are accepted as platform apps. It's public, but not included here.
- `SETTINGS_APK`: the GSI's own Settings app, pulled from your R4
  (`adb pull /system_ext/priv-app/Settings/Settings.apk`). The HiBy pages are added to it as an extra dex.
- Output: `system/out/hiby_r4_gsi_fix`. Copy it to `/data/adb/modules/hiby_r4_gsi_fix` on the R4, then, as root:

```bash
adb shell su -c sh /data/adb/modules/hiby_r4_gsi_fix/patch_libs.sh /data/adb/modules/hiby_r4_gsi_fix
```

The patch script checks each original file's SHA-256 before and after patching. A file that isn't the exact version
the patch was made for is skipped and left alone. Then reboot.

## The binary patches

All three are a few instructions each; `patches/patch_libs.sh` lists every offset and value.

| File | Change | Why |
|---|---|---|
| HiBy's `audio.primary.trinket.so` (HAL) | 1 branch removed | Android's bare "connect=8" selects HiBy's headphone output instead of S/PDIF |
| HiBy's `audio.primary.trinket.so` (HAL) | 2 branches made unconditional | Android's master volume is no longer copied into the DAC volume (it set the DAC to full after every audio restart) |
| Android's `libaudiopolicymanagerdefault.so` | 2 jumps | Android 14 offers bit-perfect only for USB; this allows the wired outputs too |
| Android's `libaudiohal@6.0.so` | 3 instructions | HiBy's Android 11 HAL has no bit-perfect flag: it's handed over as "direct", which HiBy's HAL plays untouched |

The module mounts the patched copies at boot only if the files on the system are still the originals. After a system
update it simply doesn't enable them.

## The HiBy settings API

Apps (Songate) read and change the R4's sound and light settings through `content://com.andre.hibyr4.settings`
(permission `com.andre.hibyr4.permission.HIBY_SETTINGS`): keys for gain, filter, balance, outputs, LEDs, volume
steps, `out_rate`, `bit_perfect`, and status values such as `st_rate` (the DAC's rate) and `st_quality`
(PCM / DSD / MQA).

## The system settings API (design)

Songate's own pages for Wi-Fi, Bluetooth, the audio output switcher, display, storage, date & time, language and the
system (update, restart) will use a second part of the same provider, locked to Songate's signing certificate:
[`apps/hiby_r4_service/SYSTEM_API.md`](apps/hiby_r4_service/SYSTEM_API.md). It's a design; the service side is built
phase by phase.
