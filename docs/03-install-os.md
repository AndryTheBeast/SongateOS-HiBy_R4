# 3. Install SongateOS

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
