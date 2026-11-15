# 1. Back up your R4 with EDL

> Outline: the full guide is being written.

A complete backup of **your own** player, taken before anything else. It's your way back from any mistake, including
a player that no longer starts. Never use someone else's backup on your unit: partitions like `persist`, `modemst`,
`devinfo` and the calibration data are unique to each device.

## Will cover

- What EDL (Emergency Download mode) is, and why it can back up and restore even a player that won't boot.
- Tools: [bkerler/edl](https://github.com/bkerler/edl) (Python), drivers on Windows (Zadig / WinUSB), a USB cable.
- The firehose loader that works with the R4, and how to check you have the right one. Never try random loaders.
- Entering EDL on the R4.
- Reading every partition and the GPT, **one command per EDL session**, with `--memory=emmc`.
- Writing `SHA256SUMS.txt` for the backup and verifying it, then storing two copies off the player.
- Restoring a single partition from the backup, after checking its checksum against `SHA256SUMS.txt`.
- Partitions you must never write: xbl, xbl_config, tz, rpm, hyp, abl, devcfg, keymaster, persist, modemst1/2, fsg,
  devinfo, frp, privdata1/2, the GPT.
