# 10. Troubleshooting and logs

## The player doesn't start

- **Stuck on the boot logo or restarting over and over:** start the recovery with the buttons: from off, hold
  **Power + Previous**; when the screen turns on, let go of Power and keep holding Previous. Then install the SongateOS
  zip again from the recovery. Your data stays.
- **Nothing on the screen at all:** charge it for 30 minutes, then try again. If the recovery doesn't start either,
  restore it from your EDL backup ([guide 1](01-backup-edl.md#restore-one-partition)).
- **Going back to HiBy's firmware:** [guide 7](07-back-to-stock.md).
- **HiBy's own recovery starts instead of TWRP or OrangeFox:** Android's verification is still on in
  `vbmeta_<slot>` (it is on HiBy's firmware). See [guide 2, step 3](02-recovery.md#3-flash-it-from-fastbootd): your
  recovery is already in place, only the `vbmeta` line is missing.

## Sound

- **No sound after plugging in:** pull the plug out fully and in again; the R4 switches its output when the jack
  changes. Check Songate's output line shows the jack you're using.
- **Same volume on both jacks:** that's how it works (as on HiBy's own system): to Android both are wired
  headphones. Turn it down before plugging in sensitive earphones.
- **Line out is loud and the volume keys do nothing:** line out is a fixed full volume. Switch the jack back to
  headphones in Sound & DAC; pulling the plug does it too.
- **Clicks when a sound plays:** touch and notification sounds are off by default for this reason; leave them off.

## Battery

- **The battery drains fast and the player gets warm while "asleep":** restart it. This was seen once after
  unplugging a USB speaker that also charged the player (its USB port stayed awake). If you can repeat it, a log
  helps (below).

## Updates and Google apps

- **"Updates need a microSD card":** put one in, or update from a computer ([guide 3](03-install-os.md#updates-need-a-microsd-card)).
- **"vbmeta_a (or vbmeta_b) flags are 00000000, need 00000003":** Android's verification is still on (HiBy's firmware has it on).
  Nothing was written. Turn it off as in [guide 3, step 1](03-install-os.md#first-install-coming-from-hibys-firmware),
  then install again.
- **"Cannot load Android system" or restarting over and over after the first install:** the data partition is still
  HiBy's, or from a SongateOS beta (different keys). In the recovery: **Wipe › Format Data**, type `yes`, then restart.
- **"ERROR: cannot read the vendor partition" right after Format Data:** nothing was written. Format Data takes down
  the recovery's view of the `super` partition until it restarts: **Reboot › Recovery**, then install the zip again.
- **An update didn't install:** the logs are next to the zip on the card (`songateos_install.log` and
  `songateos_recovery_tail.log`). Attach them to a bug report.
- **Google apps crash after installing:** restart once. If it keeps happening, remove them and install them again
  ([guide 8](08-updates-google-apps.md)).

## Getting a log for a bug report

1. On the R4: open Android's **Settings** app (in Songate's Apps page) › About › tap the build number seven times,
   then turn on **USB debugging** in System › Developer options.
2. Connect it to a computer with [Android platform tools](https://developer.android.com/tools/releases/platform-tools)
   and allow the computer on the R4 ("Allow USB debugging?"), then:
   ```
   adb logcat -d > songateos-log.txt
   ```
3. Do it right after the problem happens: the log only holds the last few minutes.

**Before posting, open the file and remove anything you don't want to share:** Wi-Fi and Bluetooth names, device
addresses, account names. Attach it to the [bug report](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/issues/new/choose).

From the recovery, its own log is at `/tmp/recovery.log`:

```
adb pull /tmp/recovery.log
```
