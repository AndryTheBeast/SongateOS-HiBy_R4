# 10. Troubleshooting and logs

## The player doesn't start

- **Stuck on the boot logo or restarting over and over:** start the recovery with the buttons: from off, hold
  **Power + Previous**; when the screen turns on, let go of Power and keep holding Previous. Then install the SongateOS
  zip again from the recovery. Your data stays.
- **Nothing on the screen at all:** charge it for 30 minutes, then try again. If the recovery doesn't start either,
  restore it from your EDL backup ([guide 1](01-backup-edl.md#restore-one-partition)).
- **Going back to HiBy's firmware:** [guide 7](07-back-to-stock.md).

## Sound

- **No sound after plugging in:** pull the plug out fully and in again; the R4 switches its output when the jack
  changes. Check Songate's output line shows the jack you're using.
- **Very quiet on the 4.4 mm:** update to 0.1.0-beta.8 or newer (it gave the 4.4 mm its own DAC volume).
- **Clicks when a sound plays:** touch and notification sounds are off by default for this reason; leave them off.

## Updates and Google apps

- **"Updates need a microSD card":** put one in, or update from a computer ([guide 3](03-install-os.md#updates-need-a-microsd-card)).
- **An update didn't install:** the logs are next to the zip on the card (`songateos_install.log` and
  `songateos_recovery_tail.log`). Attach them to a bug report.
- **Google apps crash after installing:** restart once. If it keeps happening, remove them and install them again
  ([guide 8](08-updates-google-apps.md)).

## Getting a log for a bug report

1. On the R4: open Android's **Settings** app (in Songate's Apps page) › About › tap the build number seven times,
   then turn on **USB debugging** in System › Developer options.
2. On a computer with [Android platform tools](https://developer.android.com/tools/releases/platform-tools):
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
