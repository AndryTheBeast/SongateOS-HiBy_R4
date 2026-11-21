# Changelog

## SongateOS 2.1.0 — first public release

SongateOS's version now follows Songate's: this release ships **Songate 2.1.0**.

**Installing:** from HiBy's firmware, follow [guide 3](docs/03-install-os.md) (vbmeta, Format Data, then the zip).
**From a SongateOS beta:** install the zip and **Format Data** once: from this release SongateOS is signed with its
own keys, and the betas used Android's public test keys. Updates after this keep your data.

### New

- **Signed with SongateOS's own keys.** LineageOS's Trust no longer warns about public keys, and only SongateOS can
  make updates the player accepts.
- **The boot logo comes with the zip.** The first screen ("HIBY R4 · powered by Songate"), the low-battery screens
  and the bootloader's debug and overheating screens are installed with the OS, as a patch over HiBy's stock logo
  (checked before and after; any other logo is left alone).
- **Both jacks work the way HiBy's own system does.** The 3.5 mm and 4.4 mm are registered as Android wired
  headphones with HiBy's output names, so plugging in mid-song moves the music over, quick swaps work both ways and
  bit-perfect runs on both. The two jacks now share one volume, as on HiBy OS.
- **Line out on the 4.4 mm**, like the 3.5 mm: the headphone path at a fixed full volume with the line-out gain.
- **Safer USB debugging:** a computer must be allowed on the player ("Allow USB debugging?") before it can connect.
- **The HiBy R4 service runs as a system service again**, without the "SongateOS is running" notification. It is
  updated only with the OS.
- **Songate 2.1.0:** "Play when plugged in" (off by default) starts bit-perfect straight away; no more music starting
  by itself when Bluetooth earphones switch off while wired ones are plugged in; smoother Settings; the LED follows
  the accent colour with the screen off; no phantom play/next presses when unplugging.

### Fixed

- Skipping songs fast with bit-perfect on could stop Android's audio server for a moment.
- Changing from a 24-bit to a 16-bit song could lose bit-perfect for that song.
- Switching line out back to headphones could leave the volume at full.
- Bit-perfect is no longer offered with nothing plugged in (HiBy's driver played to its S/PDIF path, silently).
- Plugging in headphones could restart Android when a Bluetooth lookup was refused.
- The CPU governor stays on "schedutil" (HiBy's start-up switched it to "performance" after every boot).
- Phone apps, the camera, the Via browser, LineageOS Music and Recorder are no longer installed for the user (the
  player has no phone or camera); a network statistics log flood is gone.

### Known limits

- **4.4 mm line out** wasn't tested on the device for this release (the 4.4 mm headphone mode was).
- **USB speakers that charge the player:** after unplugging one, the player was seen once not to sleep until the next
  restart. USB DACs that don't charge it are fine.
- See also [Known limits](README.md) in the README.
