# 4. Boot logo and animations

> Outline: the tools are in [`branding/`](../branding); see its README for how to run them.

| Screen | Shown by | Lives in | Status |
|---|---|---|---|
| Boot logo "HIBY R4 · powered by Songate" | bootloader, first second | `imagefv` partition (`logo_custom.bmp`) | done |
| Low-battery screens (5) | bootloader, when the battery is too low to start | `imagefv` (`battery_symbol_*.bmp`) | done |
| Boot animation (Songate's opening) | Android, while it starts | `/product/media/bootanimation.zip` | done |
| Charging animation (ring + level in Fraunces + lightning bolt, sparkles) | Android's charger, player switched off | `/product/etc/res/` | built, ships with the OS |

## Will cover

- How the boot logo is stored (a signed UEFI firmware volume inside an ELF), what the builder changes, and why it's
  only safe when secure boot is off.
- Rebuilding the images from the generators.
- Going back to HiBy's logo: write your own stock `imagefv` from your EDL backup.
