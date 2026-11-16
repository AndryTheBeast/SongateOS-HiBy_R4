# 2. Install a custom recovery (TWRP or OrangeFox)

A custom recovery lets you flash images and zips, make backups to the microSD, and open a root shell, even when
Android doesn't start. Both work on the R4 and are built from this repository's
[device tree](../device/hiby/R4/README.md); pick the one you like.

> **Standard HiBy R4 only.** Never flash a recovery built for the R4 EVA or any other device.
>
> **Do [guide 1](01-backup-edl.md) first.** Your EDL backup holds your stock recovery (`recovery_a.bin`), which is how
> you undo this.

**Downloads:** the recovery images are on the [Releases](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/releases)
page, each with its SHA-256 in `SHA256SUMS.txt`. Use the **`.img`** files with this guide.

## What you need

- Your EDL backup (guide 1).
- Google's [Android platform tools](https://developer.android.com/tools/releases/platform-tools) (`adb` and
  `fastboot`) on the PC.
- **USB debugging** on the R4: Settings › About › tap the build number seven times, then Developer options › USB
  debugging. Accept the prompt on the R4 when you first connect.

## 1. Check the bootloader is unlocked

```
adb reboot bootloader
fastboot getvar unlocked
fastboot getvar current-slot
fastboot reboot
```

- `unlocked: yes` is required. If it says `no`, stop here: this guide hasn't been tried on a locked R4.
- `current-slot: a` is expected. **Stay on slot a.** On many units slot b has no system in the dynamic partitions;
  switching to it (`fastboot set_active b`) leaves the player unable to start.
- Never run `fastboot flashing lock` or `fastboot oem lock`: locking with modified images can leave the player
  unbootable.

## 2. Check the image

Download the recovery `.img` and `SHA256SUMS.txt` from the release, then compare:

- PowerShell: `Get-FileHash .\<recovery>.img`
- Git Bash / Linux: `sha256sum -c SHA256SUMS.txt --ignore-missing`

Don't flash it if the checksum is different.

## 3. Flash it from fastbootd

The R4's bootloader rejects `fastboot flash` and `fastboot boot`. Flashing works from **fastbootd**, the fastboot
built into the recovery:

```
adb reboot fastboot
fastboot flash recovery_a <recovery>.img
fastboot reboot recovery
```

- After `adb reboot fastboot` the R4 shows the recovery screen with **fastbootd** at the top. Check with
  `fastboot devices` that the PC sees it.
- The recovery starts. Next time, open it with `adb reboot recovery`, or from the power menu if your system offers
  "Recovery".

## TWRP or OrangeFox

| | TWRP | OrangeFox |
|---|---|---|
| Look | classic | more modern, with more options |
| Flash images and zips, terminal, file manager | yes | yes |
| Backups to microSD or USB OTG | yes | yes |
| Built-in extras | – | AromaFM file manager, Magisk installer in the zip version |

To switch, flash the other image the same way. The OrangeFox **zip** version writes to both `recovery_a` and
`recovery_b`; the `.img` in this guide only touches `recovery_a`.

## What works

- Display, touch, brightness; USB: adb, MTP, fastbootd.
- microSD (exFAT, FAT32, ext4) and USB OTG storage.
- Battery level and charging state.
- Backup and restore of boot, dtbo, vbmeta, super, metadata and recovery.

**Not yet:** decrypting internal storage (`/data`). The recovery can't read your files there. Use the microSD or a USB
stick for anything you want to flash or back up. Don't choose "Format Data" unless you mean to erase everything on the
player.

## Undo: back to the stock recovery

From Android or the custom recovery:

```
adb reboot fastboot
fastboot flash recovery_a D:\R4_backup\recovery_a.bin
fastboot reboot
```

Check the file first (`sha256sum -c SHA256SUMS.txt` in your backup folder). If the player doesn't start at all, write
`recovery_a` with EDL instead ([guide 1, "Restore one partition"](01-backup-edl.md#restore-one-partition)).

## Next

- [Root with Magisk](06-root-magisk.md)
- [Back to stock](07-back-to-stock.md)
