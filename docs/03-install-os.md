# 3. Install SongateOS

> [!CAUTION]
> **You install SongateOS at your own risk** ([Read this first](00-read-first.md)). Have your EDL backup from guide 1,
> with its checksums checked, before you start. Read this guide fully, then follow it step by step.

## What the zip installs

One zip from the recovery puts everything in place:

- **The OS** (`system_<slot>`, your slot from guide 1): LineageOS 21 with Songate as the home screen and the HiBy R4 support built in: audio
  paths and DAC settings, line out, status lights, Bluetooth audio, battery, hold switch.
- **The SongateOS boot logo** (`imagefv_<slot>`): the bootloader's first screen ("HIBY R4 · powered by Songate") and its
  low-battery, debug and overheating screens. The zip carries only SongateOS's own pictures as a patch, never HiBy's
  logo file. It is applied only if your R4 shows **exactly** HiBy's stock logo (or an earlier SongateOS logo): the
  installer checks the checksum before, patches a copy, checks the result, and only then writes it and reads it back.
  Any other logo is left as it is. This needs a standard R4 whose bootloader doesn't check the logo's signature
  (`fastboot getvar secure` says `no`), as on the R4s tested.
- **The boot animation and the charging animation**, inside the system image.

Nothing else is written: not the other slot, boot, vbmeta, the recovery or the bootloader chain. To go back, see the end of
this guide.

## First install, coming from HiBy's firmware

You need OrangeFox or TWRP in the recovery (guide 2), your EDL backup (guide 1), the SongateOS zip on the microSD
card (checked against `SHA256SUMS.txt`), and [Android platform tools](https://developer.android.com/tools/releases/platform-tools)
on the computer. Internal storage is erased in step 2: save anything on it first. Music on the microSD stays.

**The microSD card must be in the player.** Without one, OrangeFox still shows an empty `/external_sd` folder that
looks like an empty card. No card reader? Put the card in the player, start the recovery, and copy the zip from the
computer, then check it on the player:
```
adb push songateos-2.1.1-R4.zip /external_sd/
adb shell sha256sum /external_sd/songateos-2.1.1-R4.zip
```

1. **Android's verification off in `vbmeta_<slot>`: already done** if you followed guide 2, which needs it to start
   the recovery. HiBy's firmware has it on, and SongateOS can't start with it on. The installer checks this first and
   stops with `vbmeta_<slot> flags are 00000000, need 00000003` (nothing written) if it's still on. In that case, with
   the R4 in the recovery and connected by USB, use the `vbmeta_<slot>.bin` from **your own** backup:
   ```
   adb reboot fastboot
   fastboot --disable-verity --disable-verification flash vbmeta_<slot> D:\R4_backup\vbmeta_<slot>.bin
   ```
   This changes one flag in `vbmeta_<slot>` and nothing else. Then `fastboot reboot recovery`.
   To undo it, flash the same file without the two `--disable` options.
2. **Format Data.** In OrangeFox or TWRP: **Wipe › Format Data**, type `yes`. HiBy's Android encrypts its data
   partition, and SongateOS can't use another system's data: without this step it stops at "Cannot load Android
   system" or keeps restarting. Do it even right after a factory reset in HiBy's recovery, if HiBy's system has
   started once since. **Then Reboot › Recovery** before step 3: Format Data also takes down the recovery's view of the
   `super` partition. (The 2.1.1 installer maps what it needs itself; 2.1.0's stopped with "cannot read the vendor
   partition", nothing written.)
3. **Install the zip** from the microSD: OrangeFox › Files, or TWRP › Install. It checks the device, makes room in
   `super` (on R4s that installed a HiBy update, it removes HiBy's unused update leftovers there and first saves the old
   partition table next to the zip as `super_table_before_songateos.bin`; keep that file), writes `system_<slot>` and checks
   it, then writes the boot logo (see above). It takes a few minutes. The log is next to the zip
   (`songateos_install.log`). Near the start it shows the slot it writes, e.g. **`HiBy R4, slot b, vbmeta flags 3: OK`**: it must be the
   slot you noted in guide 1. If it isn't, don't reboot: ask in the Discussions with the log.
4. **Reboot › System.** You'll see the SongateOS logo, then the boot animation. The first start takes a few minutes,
   then Songate's setup appears.

Steps 1 and 2 are only for the first install. Updates (below) keep your data.

### After the first start

- **USB debugging** is off until you turn it on (Android's Settings app, in Songate's Apps page › About › tap the build
  number seven times, then System › Developer options). The first time a computer connects, the R4 asks **"Allow USB
  debugging?"**: tick "Always allow from this computer" and tap Allow. Without that, `adb` shows the R4 as
  "unauthorized".
- The **clock** sets itself once Wi-Fi is connected.

## Coming from a SongateOS beta

The betas were signed with Android's public test keys; SongateOS 2.1.0 and later are signed with SongateOS's own
keys. Android refuses to start a system with new keys over data made by the old ones, so the first install of 2.1.0
over a beta needs **Format Data** once:

1. Save what you need from internal storage (the microSD isn't touched).
2. In the recovery: **Wipe › Format Data**, type `yes`, then **Reboot › Recovery**, **then** install the 2.1.0 zip (in
   this order: Format Data also clears the record the installer leaves for the updater, and the restart lets the
   recovery see the `super` partition again).
3. Reboot › System and set up Songate again. Bluetooth devices need pairing again.

`vbmeta_<slot>` is already right if a beta ran. After this, updates keep your data again.

## Updates need a microSD card

SongateOS updates itself from **Songate › Settings › Updates**: it downloads the new version to the **microSD card**,
then restarts into the recovery, which installs it. The card is needed because the R4's internal storage is
encrypted and the recovery can't read it. (Google apps, which also install through the recovery, need the card too.)

**No microSD card?** Update from a computer instead:

1. On the computer: download `songateos-<version>-R4.zip` and `SHA256SUMS.txt` from the
   [Releases](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/releases) page and check the zip's SHA-256 against the
   list (Windows: `certutil -hashfile songateos-<version>-R4.zip SHA256`; macOS/Linux: `sha256sum`).
2. Connect the R4 with USB debugging on (see above) and restart it into the recovery:
   ```
   adb reboot recovery
   ```
3. In the recovery: **OrangeFox** › Menu › ADB & Sideload › Start sideload, or **TWRP** › Advanced › ADB Sideload ›
   swipe to start.
4. On the computer:
   ```
   adb sideload songateos-<version>-R4.zip
   ```
   The installer checks that it's a standard R4 and the image's checksum before it writes, writes `system_<slot>` (and the
   logo, only if it changed), and checks them afterwards. It takes a few minutes; `adb` may show 47% or similar at the
   end, that's normal.
5. Reboot to system from the recovery. Your music, apps and settings stay.

The zips are built in 512 MB pieces, so they install without a card when sideloaded.

## Going back

- **To an earlier SongateOS version:** install its zip from the recovery. Going back to a beta from 2.1.0 needs
  Format Data (the keys differ, see above).
- **HiBy's boot logo:** write `imagefv_<slot>.bin` from your own backup ([guide 1](01-backup-edl.md#restore-one-partition),
  or [guide 4](04-branding.md)).
- **HiBy's firmware:** [guide 7](07-back-to-stock.md), from your own backup.
