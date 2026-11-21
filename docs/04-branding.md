# 4. Boot logo and animations

Every screen from power-on to Songate follows Songate's look. The generators are in [`branding/`](../branding)
(see its README for how to run them).

| Screen | Shown by | Lives in | Installed by |
|---|---|---|---|
| Boot logo "HIBY R4 · powered by Songate" | bootloader, first second | `imagefv` partition (`logo_custom.bmp`) | the SongateOS zip (a patch, see below) |
| Low-battery screens (5) | bootloader, when the battery is too low to start | `imagefv` (`battery_symbol_*.bmp`) | the same patch |
| Debug-board and overheating screens (4) | bootloader, rarely | `imagefv` (`battery_symbol_Debug*.bmp`, `tsens_thermal_*.bmp`) | the same patch |
| Boot animation (Songate's opening) | Android, while it starts | `/product/media/bootanimation.zip` | the system image |
| Charging animation (ring up to the level, Fraunces digits, sparkles) | Android's charger, player switched off | `/product/etc/res/` | the system image |

## How the boot logo is stored

The `imagefv` partition is a signed ELF holding a UEFI firmware volume, which holds an LZMA-compressed volume with 10
bitmaps. `build_imagefv.py` replaces the pixels and palettes of all 10 with SongateOS's (same sizes and headers),
packs the volume again and writes the new SHA-384 into the ELF's hash table. HiBy's signature no longer matches, which
is fine only when the bootloader doesn't check it: `fastboot getvar secure` must say `no` (it does on the R4s tested).
Everything is checked by parsing the result back, and rebuilt with HiBy's own pictures it gives HiBy's file byte for
byte.

## How the zip installs it

The SongateOS zip never carries the logo partition itself (that's HiBy's signed file). It carries `imagefv.patch`:
only the bytes where SongateOS's logo differs from HiBy's stock one (our compressed pictures and the new hash, about
420 KB). The installer applies it only when the R4's logo is exactly HiBy's stock one or an earlier SongateOS logo,
checks the patched copy against the expected checksum, and only then writes it and reads it back. Anything else is
left alone.

## Going back to HiBy's logo

Write `imagefv_a.bin` from your own EDL backup: from Android as root or from the recovery with `dd` (check the file's
checksum against your backup's `SHA256SUMS.txt` first), or with EDL as in [guide 1](01-backup-edl.md#restore-one-partition).
Note: SongateOS updates that carry a logo write SongateOS's again over HiBy's stock one.
