# 9. Earbuds

SongateOS talks to some earbuds directly, so their controls and battery appear in Songate (its earbuds card and the
volume pop-up), with no app from the earbuds' maker. Music itself plays over Bluetooth as usual; the R4 has Bluetooth
5.0, and AAC was tested.

| | AirPods | Galaxy Buds | Soundcore |
|---|---|---|---|
| Tested with | AirPods Pro 3 | Galaxy Buds4 Pro | Liberty 4 NC |
| Battery | left, right, case (last known when it's closed) | left, right, case | left, right |
| Modes | off, noise cancelling, transparency, adaptive | off, noise cancelling, ambient | noise cancelling, transparency, normal |
| Strength | adaptive level (0–100) | noise cancelling (low, medium, high), ambient volume (5 steps) | noise cancelling (1–5), transparency type, wind |
| Also | conversation awareness, in-ear detection | conversation detection (5/10/15 s), noise control with one earbud, in-ear detection | transport modes |

Other models of the same families may work and are welcome as reports. Galaxy Buds need to be from the Buds3 or Buds4
generation.

## Good to know

- **AirPods:** Android normally can't open the channel AirPods use for their controls. SongateOS's Bluetooth includes
  the fix for that, so nothing else needs installing. "Off" is turned on as a mode when you first pick it.
- **Changes made on the earbuds** (a squeeze or touch, taking one out) show up in Songate straight away.
- **Pairing** is done the usual way in Settings › Bluetooth; the controls appear once they're connected.

## Credits

The earbud protocols were learnt from the notes published by [LibrePods](https://github.com/librepods-org/librepods)
(AirPods) and [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) (Galaxy Buds). SongateOS's code is
its own; none of theirs is included. SongateOS isn't affiliated with Apple, Samsung or Anker.
