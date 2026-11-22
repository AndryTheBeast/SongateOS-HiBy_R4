# 7. Back to stock

How to return your R4 to HiBy's firmware, exactly as it was when you made your backup.

> **You need your own EDL backup** ([guide 1](01-backup-edl.md)). Everything here writes **your** files back to
> **your** player. Never use someone else's backup: the player would get another unit's identifiers and calibration.
>
> **Check the files first:** in your backup folder, `sha256sum -c SHA256SUMS.txt` must say `OK` for every file you
> write.

Pick the case that matches what you changed.

## Only the recovery or root (Magisk)

The player still starts. Put the original back from fastbootd:

```
adb reboot fastboot
fastboot flash recovery_<slot> D:\R4_backup\recovery_<slot>.bin      (if you installed TWRP or OrangeFox)
fastboot flash boot_<slot> D:\R4_backup\boot_<slot>.bin              (if you rooted with Magisk)
fastboot reboot
```

Nothing else is needed: your files and settings stay.

## The system too (LineageOS, SongateOS), or the player doesn't start

Another system changes the `super` partition (system, vendor, product), `vbmeta` and often `boot`. Putting them all
back is done with EDL, because the backup holds `super` as one 6 GB image.

> Tested end to end on an R4 in November 2026: from SongateOS, these steps gave HiBy's setup screen on the first start.
> Every file comes from your own backup and none of them is a protected partition. Read each step and go slowly.

1. **Save your files.** Step 2 erases internal storage. Music on the microSD stays.
2. **Erase the other system's data first,** while your custom recovery is still installed: in TWRP or OrangeFox,
   **Wipe › Format Data**, type `yes`. HiBy's firmware can't read data left by another system, and it's easier to clear
   it now than after the restore.
3. **Make a folder with only these files**, copied (not moved) from your backup:
   - `boot_<slot>.bin`
   - `dtbo_<slot>.bin`
   - `vbmeta_<slot>.bin`
   - `vbmeta_system_<slot>.bin`
   - `recovery_<slot>.bin`
   - `super.bin`
   - `imagefv_<slot>.bin` (the boot logo: SongateOS changes it)

   **Nothing else.** In particular, no `xbl`, `tz`, `abl`, `persist`, `modemst`, `fsg`, `devinfo`, `frp`, `privdata`
   or `gpt` files.
4. **Check them** in that folder: `sha256sum` each file and compare with your backup's `SHA256SUMS.txt`.
5. **Enter EDL** ([guide 1](01-backup-edl.md#enter-edl-on-the-r4)) and write the folder in one session:
   ```
   .venv\Scripts\python edl.py wl D:\R4_restore --memory=emmc --loader=LOADER
   ```
   `wl` writes each file to the partition with the same name. It takes a few minutes, mostly for `super` (about
   4 minutes for 6 GB).

   You can also write one partition per EDL session with `edl.py w <partition> <file>` instead, if you prefer to go
   step by step.
6. Leave EDL with **Power + Next** (hold until the screen turns on, then let go). The first start takes a few minutes,
   and you're back on HiBy's firmware, at its setup screen. (If you skipped step 2: turn the player off, start HiBy's
   stock recovery with the buttons, from off **Power + Previous**, let go of Power when the screen turns on and keep
   holding Previous, and choose **Wipe data/factory reset**.)
7. If Android shows **"Cannot load Android system"** instead, choose **Factory data reset**: some data from the other
   system was left behind.

Your backup's `vbmeta_<slot>` turns Android's verification back on. To put TWRP or OrangeFox in again later, follow
guide 2 as written: it turns verification off before starting the recovery.

## If something goes wrong

- **The player shows nothing and doesn't start:** EDL still works. Enter it and write `boot_<slot>` and `vbmeta_<slot>` from
  your backup again.
- **It keeps restarting at the HiBy logo:** data from the other system is probably still there. After a few failed
  starts Android offers "Factory data reset" by itself (step 7).
- **EDL doesn't connect:** see the one-command-per-session rule in
  [guide 1](01-backup-edl.md#the-one-rule-that-matters-one-command-per-edl-session).
