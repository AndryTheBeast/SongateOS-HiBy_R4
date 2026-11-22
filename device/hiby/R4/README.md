# HiBy R4 recovery device tree (TWRP and OrangeFox)

Custom recovery for the **HiBy R4** digital audio player: **standard model only** (Qualcomm SM6125 "trinket", eMMC).

> **Not for the R4 EVA** or any other HiBy model. Flashing a recovery built for another device can leave the player
> unable to boot.

One device tree builds both recoveries; pick the one you like:
- **TWRP** 3.7 (twrp-12.1)
- **OrangeFox** R12 (fox_12.1); its extra options are in `orangefox.mk`

## What works

| | |
|---|---|
| Display, touch, brightness | ✅ |
| USB: adb, MTP, fastbootd | ✅ (own configfs USB setup, `recovery/root/init.recovery.usb.rc`) |
| microSD (exFAT/FAT32/ext4), USB OTG storage | ✅ |
| Battery level and charging state | ✅ (read from the cw2015 fuel gauge) |
| Backup / restore: boot, dtbo, vbmeta, super, metadata, recovery | ✅ |
| Flash images and zips, terminal, file manager | ✅ |
| **Decrypting internal storage (/data)** | ❌ not supported yet. Use microSD or USB OTG for files and backups |

Protected partitions (xbl, tz, abl, persist, modem, devinfo, frp, …) are deliberately not listed, so the recovery
can't wipe or restore them.

## Before you start

- The **bootloader must be unlocked**.
- On the R4, HiBy's bootloader fastboot rejects `fastboot boot` and `fastboot flash`. Flashing works from
  **fastbootd** (`adb reboot fastboot`) or from a running custom recovery.
- **Back up your own stock recovery first** (from fastbootd you can't read partitions; use a root shell,
  `dd if=/dev/block/by-name/recovery$(getprop ro.boot.slot_suffix) of=/sdcard/stock_recovery.img`, or a full EDL
  backup). Never use someone else's backup on your unit.
- Stay on **your slot**, `a` or `b` (`fastboot getvar current-slot`). HiBy's own updates leave a player on either one,
  and the other slot's system is then gone or outdated: never switch slots.

## Install

```
adb reboot fastboot
fastboot flash recovery_<slot> <recovery image>.img
fastboot reboot recovery
```

Undo: flash your own stock recovery back the same way (`fastboot flash recovery_<slot> stock_recovery.img`).

## Installer zip (TWRP)

[`installer/`](installer) builds the TWRP installer zip from a built `recovery.img`:
`python3 installer/make_twrp_zip.py out/target/product/R4/recovery.img TWRP-3.7-Unofficial-R4.zip`. Its
`update-binary` checks the device (R4, trinket, eMMC) and the image, writes `recovery_a` and `recovery_b`, and reads
them back. OrangeFox builds its own zip.

## Build

Both builds use a standard 12.1 minimal manifest; clone this repo to `device/hiby/R4`.

**TWRP** ([twrp-12.1 minimal manifest](https://github.com/minimal-manifest-twrp/platform_manifest_twrp_aosp)):
```
source build/envsetup.sh
lunch twrp_R4-eng
mka adbd recoveryimage
```

**OrangeFox** ([fox_12.1 sync](https://gitlab.com/OrangeFox/sync)):
```
export ALLOW_MISSING_DEPENDENCIES=true FOX_BUILD_DEVICE=R4 LC_ALL=C
source build/envsetup.sh
lunch twrp_R4-eng            # run it directly, not through a pipe
mka adbd recoveryimage
```

Output: `out/target/product/R4/recovery.img` (OrangeFox also makes an `OrangeFox-…-R4.img` and a zip).

## Notes

- **Kernel, dtb, dtbo** are prebuilt, taken unmodified from the stock HiBy R4 recovery (kernel 4.14.190-perf #9,
  checksums in `prebuilt/SOURCES.sha256`). HiBy hasn't published the kernel source.
- The recovery partition is 96 MB, so both recoveries fit with room to spare.
- Virtual A/B with separate `recovery_a`/`recovery_b` partitions. The tree contains no boot control HAL on purpose,
  so the recovery can't switch slots. Everything slot-dependent (the SongateOS update hook, Google apps installs,
  OrangeFox's Magisk add-on) uses the slot the player started on (`ro.boot.slot_suffix`).

## License

Apache-2.0 (see the SPDX headers). The prebuilt kernel is GPL-2.0, from HiBy's stock firmware.
