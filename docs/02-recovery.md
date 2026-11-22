# 2. Install a custom recovery (TWRP or OrangeFox)

A custom recovery lets you flash images and zips, make backups to the microSD, and open a root shell, even when
Android doesn't start. Both work on the R4 and are built from this repository's
[device tree](../device/hiby/R4/README.md); pick the one you like.

> **Standard HiBy R4 only.** Never flash a recovery built for the R4 EVA or any other device.
>
> **Do [guide 1](01-backup-edl.md) first.** Your EDL backup holds your stock recovery (`recovery_<slot>.bin`), which is how
> you undo this.

**Downloads:** the recovery images are on the [Releases](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/releases)
page, each with its SHA-256 in `SHA256SUMS.txt`: an **`.img`** for the first install (this guide) and a **`.zip`** for
switching later from inside the recovery.

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

- `unlocked: yes` is expected: HiBy ships the R4 with its bootloader unlocked, so there's nothing to unlock and
  nothing gets wiped. If it says `no`, stop here and ask in the Discussions: that isn't a stock R4.
- `current-slot` is **your slot**, `a` or `b`; it must match the one you noted in guide 1. **Never switch slots**
  (`fastboot set_active`): after HiBy's updates the other slot's system is gone or outdated, and the player wouldn't
  start.
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
fastboot flash recovery_<slot> <recovery>.img
fastboot --disable-verity --disable-verification flash vbmeta_<slot> D:\R4_backup\vbmeta_<slot>.bin
fastboot reboot recovery
```

- After `adb reboot fastboot` the R4 shows the recovery screen with **fastbootd** at the top. Check with
  `fastboot devices` that the PC sees it. **On Windows it may show nothing** the first time: HiBy's fastbootd needs a
  driver. Install [Zadig](https://zadig.akeo.ie), choose **Options › List All Devices**, pick **`R4`**, check the USB ID
  reads **`18D1 4EE0`**, set **WinUSB** and click **Replace Driver**. (TWRP's and OrangeFox's fastbootd need nothing.)
- **The third line turns Android's verification off in `vbmeta_<slot>`**, using the file from **your own** backup.
  HiBy's firmware has it on; SongateOS needs it off (guide 3 counts on this step). It changes one flag and nothing
  else; to undo it, flash the same file without the two `--disable` options.
- The recovery starts. **If HiBy's own recovery appears instead** (seen on two R4s fresh from HiBy's firmware):
  your TWRP or OrangeFox is already in place. Choose **Power off** in HiBy's menu, then start the recovery with the
  buttons (below). If HiBy's recovery still appears, check that the `vbmeta` line above ran.
- Next time, open the recovery with `adb reboot recovery`, or with the buttons: from off, hold
  **Power + Previous**; when the screen turns on, let go of Power and keep holding Previous until the recovery
  appears.

## TWRP or OrangeFox

| | TWRP | OrangeFox |
|---|---|---|
| Look | classic | more modern, with more options |
| Flash images and zips, terminal, file manager | yes | yes |
| Backups to microSD or USB OTG | yes | yes |
| Built-in extras | – | Magisk installer and uninstaller on the microSD card (they don't work on the R4: use [guide 6](06-root-magisk.md)) |
| Its own folder (logs, extras) | – | `Fox/` on the microSD card |

**OrangeFox on the R4 keeps its folder on the microSD card** (`Fox/`), because it can't read the internal storage. The
SongateOS OrangeFox package copies its extras to `Fox/FoxFiles` on the card when you install it, and replaces them
when you install a newer one. Without a card OrangeFox works the same, just without those extras. (OrangeFox's AromaFM
file manager isn't included: it can't draw on the R4's screen. OrangeFox's own file manager does the same job.)

**Both recoveries set their clock from SongateOS** (from recovery hook 5 on), so files they write get the right date
instead of 1970.

## Switch between TWRP and OrangeFox

Once one of them is installed, you can switch from inside the recovery, without a PC:

1. Copy the other recovery's **zip** to the microSD card.
2. In the recovery: **Install**, switch to the microSD, pick the zip, swipe to flash.
3. **Reboot › Recovery**: the other recovery starts.

The zips first check it's a standard R4 and that the image is intact, and stop without writing anything if not.
They then show each step and read the partitions back.

**From SongateOS, without a recovery menu:** Songate › Settings › Recovery lists TWRP and OrangeFox and switches to the
one you pick, with the same checks (it also checks the package's SongateOS signature).

**Both zips write `recovery_a` and `recovery_b`, on purpose.** The player stays on its slot: the zips never switch
the active slot. But with the custom recovery in both slots, a player that ends up on the other slot by mistake can
still start the recovery and go back with **Reboot › Slot A** (or **Slot B**). With HiBy's stock recovery in the
other slot, that way back needs a PC or EDL.

(The first install with the `.img` above writes only `recovery_<slot>`. Flash a zip once afterwards if you want
the other slot covered too.)

## What works

- Display, touch, brightness; USB: adb, MTP, fastbootd.
- microSD (exFAT, FAT32, ext4) and USB OTG storage.
- Battery level and charging state.
- Backup and restore of boot, dtbo, vbmeta, super, metadata and recovery.

**Not yet:** decrypting internal storage (`/data`). The recovery can't read your files there. Use the microSD or a USB
stick for anything you want to flash or back up. Don't choose "Format Data" unless you mean to erase everything on the
player. (The first install of SongateOS needs it once: [guide 3](03-install-os.md#first-install-coming-from-hibys-firmware).)

## Undo: back to the stock recovery

From Android or the custom recovery:

```
adb reboot fastboot
fastboot flash recovery_a D:\R4_backup\recovery_a.bin
fastboot flash recovery_b D:\R4_backup\recovery_b.bin      (both, if a zip wrote both; else only recovery_<slot>)
fastboot flash vbmeta_<slot> D:\R4_backup\vbmeta_<slot>.bin      (verification back on; only if you don't run SongateOS)
fastboot reboot
```

Check the file first (`sha256sum -c SHA256SUMS.txt` in your backup folder). If the player doesn't start at all, write
`recovery_<slot>` with EDL instead ([guide 1, "Restore one partition"](01-backup-edl.md#restore-one-partition)).

## Next

- [Root with Magisk](06-root-magisk.md)
- [Back to stock](07-back-to-stock.md)
