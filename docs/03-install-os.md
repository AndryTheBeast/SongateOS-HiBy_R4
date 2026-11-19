# 3. Install SongateOS

> [!CAUTION]
> **You install SongateOS at your own risk** ([Read this first](00-read-first.md)). Have your EDL backup from guide 1,
> with its checksums checked, before you start. Read this guide fully, then follow it step by step.

> Outline: the OS is still being built. Until then this page describes the plan.

## The final install will put everything in place at once

- **The OS** (system image) with Songate as the launcher and the HiBy R4 support built in: audio paths and DAC
  settings, line out, status lights, Bluetooth audio, battery.
- **The SongateOS boot logo**: the bootloader's first screen ("HIBY R4 · powered by Songate") and its low-battery
  screens, written to the `imagefv` partition.
- **The SongateOS boot animation** and the **charging animation**, inside the system image.

## Will cover

- Requirements: unlocked bootloader, your EDL backup (guide 1), a custom recovery (guide 2).
- Installing from the recovery, on slot a.
- Checks the installer makes before writing: that it's a standard R4, that secure boot is off, and that `imagefv`
  matches a known stock version before it gets the new logo.
- First boot, and setting up Songate.
- Updating, and going back to HiBy's firmware from your backup.

## Updates need a microSD card

SongateOS updates itself from **Songate › Settings › Updates**: it downloads the new version to the **microSD card**,
then restarts into the recovery, which installs it. The card is needed because the R4's internal storage is
encrypted and the recovery can't read it. (Google apps, which also install through the recovery, need the card too.)

**No microSD card?** Update from a computer instead:

1. On the computer: download `songateos-<version>-R4.zip` and `SHA256SUMS.txt` from the
   [Releases](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/releases) page and check the zip's SHA-256 against the
   list (Windows: `certutil -hashfile songateos-<version>-R4.zip SHA256`; macOS/Linux: `sha256sum`).
2. Connect the R4 with USB debugging on (guide 2, "What you need") and restart it into the recovery:
   ```
   adb reboot recovery
   ```
3. In the recovery: **OrangeFox** › Menu › ADB & Sideload › Start sideload, or **TWRP** › Advanced › ADB Sideload ›
   swipe to start.
4. On the computer:
   ```
   adb sideload songateos-<version>-R4.zip
   ```
   The installer checks that it's a standard R4 and the image's checksum before it writes, writes only `system_a`,
   and checks it afterwards. It takes a few minutes; `adb` may show 47% or similar at the end, that's normal.
5. Reboot to system from the recovery. Your music, apps and settings stay.

Zips from SongateOS 0.1.0-beta.8 on are built in 512 MB pieces, so they install without a card. Older zips need
the microSD card even when sideloaded.
