# SongateOS branding tools

Python 3 with Pillow and NumPy (`pip install pillow numpy`). All screens are 750×1334 and use Songate's look:
the Fraunces wordmark with its coral → amber → cream gradient, the soft glow, the four-pointed star, Inter for text.

| Script | Makes |
|---|---|
| `make_logo.py OUT.png` | the boot logo (HIBY between two hairlines, a big R4, "powered by Songate") |
| `logo_variants.py OUTDIR` | the nine design options the logo was chosen from |
| `make_symbols.py OUTDIR` | the five bootloader battery screens (4-colour palette PNGs) |
| `build_imagefv.py STOCK_imagefv.bin LOGO.png OUT.bin [SYMBOLS_DIR]` | a new `imagefv` image from **your own** stock one |
| `make_bootanim.py OUTDIR` | `bootanimation.zip` (Songate's opening, a loop, a fade out) + a preview GIF |
| `make_bootanim_hold.py MAIN.zip OUT.zip` | the "hold" animation (fade in, the same loop, fade out) used until the launcher is on screen |
| `make_charger.py OUTDIR` | the off-mode charging animation (`product/etc/res/...`) + previews |
| `make_charger_video.py OUT.mp4 [LEVEL]` | a 60 fps preview video of the charging animation (needs ffmpeg) |

`brand.py` holds the shared pieces: colours, the wordmark, sparkles, glow, fonts.

## imagefv: read this first

`build_imagefv.py` only replaces the logo and battery bitmaps, recompresses the volume, fixes its sizes and checksum,
and updates the segment hash. It then checks the result by parsing it back. HiBy's signature no longer matches
afterwards, so this is **only for players with secure boot off** (`fastboot getvar secure` → `no`).
Always start from **your own** stock `imagefv` (from your EDL backup), and keep that backup to go back.

Previews are in [`previews/`](previews), including the charging animation as a [60 fps video](previews/charging_60fps.mp4).
