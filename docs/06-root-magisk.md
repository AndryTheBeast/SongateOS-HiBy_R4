# 6. Root with Magisk

[Magisk](https://github.com/topjohnwu/Magisk) gives root by patching the player's **boot image**. Nothing else on the
player changes, and undoing it is one command.

**On SongateOS 2.1.1 and later,** Songate › Settings › Root › **Install Magisk** does this for you. (In 2.1.0 it
answered "Look for Magisk first" and did nothing: use this guide there.) This manual way was tested on SongateOS, on
slot a and b.

> **Not from the recovery.** Flashing Magisk's zip in TWRP or OrangeFox (including OrangeFox's Magisk add-on) doesn't
> work on the R4: the recovery can't read the encrypted data partition, so the install fails with nothing written,
> and OrangeFox may need a restart afterwards. Patch the boot image with the Magisk app, as below.

> **Tested so far:** root with Magisk works on `boot_a` on this project's own R4, running LineageOS (the base of
> SongateOS), and on `boot_b` on a tester's R4 running SongateOS 2.1.0, removal included. On HiBy's stock firmware, an R4 owner in the [XDA thread](https://xdaforums.com/t/4746303/) reports the
> same method working (firmware 1.70); this project hasn't tried it there itself. The steps are the same either way.
>
> **Do [guide 1](01-backup-edl.md) first.** Your `boot_<slot>.bin` (your slot, guide 1) is both what you patch and how you undo it.

## What you need

- Your EDL backup (guide 1), taken on the firmware the player runs **now**. A boot image from an older firmware can
  stop the player from starting.
- Android platform tools and USB debugging, as in [guide 2](02-recovery.md#what-you-need).
- The Magisk app, **only** from its official [GitHub releases](https://github.com/topjohnwu/Magisk/releases).

## 1. Check the boot image matches

The boot image you patch must be the one that's on the player now. Check it against your backup's checksum
(`sha256sum -c SHA256SUMS.txt`: `boot_<slot>.bin: OK`). If you've updated HiBy's firmware since the backup, make a new
backup first.

## 2. Patch it on the R4

1. Install the Magisk app on the R4 (`adb install Magisk-vXX.apk`).
2. Copy your boot image to the player:
   ```
   adb push D:\R4_backup\boot_<slot>.bin /sdcard/Download/boot.img
   ```
3. In Magisk: **Install** › **Select and Patch a File** › pick `boot.img`. Leave the options as they are.
4. Copy the result back to the PC (the name ends in random letters):
   ```
   adb shell ls /sdcard/Download/magisk_patched*
   adb pull /sdcard/Download/magisk_patched-XXXXX.img
   ```

## 3. Flash it from fastbootd

```
adb reboot fastboot
fastboot flash boot_<slot> magisk_patched-XXXXX.img
fastboot reboot
```

- Use **`boot_<slot>`** (your slot), not plain `boot` and not the other slot.
- After the restart, open Magisk: it shows the installed version, and apps can ask for root.
- **Root over adb** (`adb shell su`) is refused at first (`su: request rejected (2000)`): in Magisk choose
  **Superuser › + › Shell** to allow it.

## Updates

- **HiBy firmware updates** replace the boot image and remove root. After an update: make a new backup (or at least
  a new copy of `boot_<slot>`), then patch and flash again.
- **SongateOS updates** don't touch the boot image, so root stays.
- **Magisk updates** go through the Magisk app as usual.

## Undo: remove root

Flash your own unpatched boot image back:

```
adb reboot fastboot
fastboot flash boot_<slot> D:\R4_backup\boot_<slot>.bin
fastboot reboot
```

Or in the Magisk app: **Uninstall Magisk** › **Complete Uninstall**.

If the player doesn't start after flashing the patched image, it usually still reaches fastbootd or the recovery
(`adb reboot fastboot` from the recovery). If nothing starts, write `boot_<slot>` back with EDL
([guide 1, "Restore one partition"](01-backup-edl.md#restore-one-partition)).
