# 2. Install a custom recovery (TWRP or OrangeFox)

> Outline: the full guide is being written. Build details are in [`device/hiby/R4/README.md`](../device/hiby/R4/README.md).
>
> **Downloads:** the recovery images (OrangeFox and TWRP) are on the [Releases](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/releases) page, with SHA-256 checksums.

## Will cover

- Unlocking the bootloader, and checking it's unlocked and that secure boot is off (`fastboot getvar secure`).
- Why `fastboot flash` and `fastboot boot` don't work from the R4's bootloader, and how to use **fastbootd** instead
  (`adb reboot fastboot`).
- Saving your stock recovery first (it's also in your EDL backup).
- Flashing to `recovery_a` and booting into it.
- TWRP or OrangeFox: what's different, and how to switch between them.
- What works (USB, MTP, microSD, OTG, backups, battery level) and what doesn't yet (decrypting internal storage).
- Going back to the stock recovery.
