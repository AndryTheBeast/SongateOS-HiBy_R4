# 8. Updates and Google apps

SongateOS updates itself, and installs or removes Google apps, through the recovery. Both need a **microSD card**:
the R4's internal storage is encrypted and the recovery can't read it, so everything the recovery installs is put on
the card first.

## Updates from the player

**Songate › Settings › Updates › Check for updates.**

1. SongateOS looks for a newer version: first on the microSD card, then on this repository's
   [Releases](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/releases).
2. A release is downloaded to the card (`Android/data/com.andre.hibyr4.service/files/ota/`) and checked twice: against
   the release's `SHA256SUMS.txt` and against the SongateOS signature. A damaged or unsigned file is deleted.
3. **Install** restarts the player into the recovery, which checks the file again, writes the new system and restarts
   into SongateOS. Your music, apps and settings stay.

Betas are only offered when you've chosen to get betas in Settings › Updates.

### From a zip you copied to the card

Copy `songateos-<version>-R4.zip` to the top folder of the card, then **Check for updates**. If it's newer than what
the player runs, is for the R4 and carries the SongateOS signature, it's offered right away ("From the microSD card"),
with no download.

### Without a microSD card

Update from a computer with `adb sideload`: [guide 3, "Updates need a microSD card"](03-install-os.md#updates-need-a-microsd-card).

## Google apps

SongateOS has no Google apps of its own. If you want the Play Store, pick one of two packages in
**Songate › Settings › Google apps**; SongateOS downloads it from its maker and the recovery installs it.

| | Core | Basic |
|---|---|---|
| Maker | [NikGapps](https://nikgapps.com) Core (SourceForge) | [MindTheGapps](https://github.com/MindTheGapps) (GitHub) |
| What you get | Play Store and Google Play services | the same, plus Google's basic apps (calendar and contacts sync, speech) |
| Size | about 125 MB | about 430 MB |

- The download is checked against the maker's own checksum before anything is installed.
- **Keep after updates** (on by default) adds your Google apps to every SongateOS update, so you never start without
  them. The package stays on the card for that; don't delete it.
- **Switching** between Core and Basic works: install the other one.

### Removing Google apps

Google apps have no uninstaller, so **Remove** installs your current SongateOS version again without them. It uses the
same version's zip if it's on the card, and otherwise downloads it from Releases.

After the restart, SongateOS removes what Google apps left behind in your data (Play services' updates), so Android
starts cleanly. You'll need to sign in to Google again if you install them later.

## What the start-up does for you

- **Leftovers of removed Google apps** are cleaned up before Android starts.
- **Android's app cache** is cleared whenever the system's apps change (an update, Core ↔ Basic), so apps don't start
  with a stale description of themselves.
- **The recovery's clock** is handed over by SongateOS, so files the recovery writes get the right date.

## When something goes wrong

The update's own log is kept next to the zip on the card (`songateos_install.log`, `songateos_recovery_tail.log`), and
Settings › Updates shows the last result. See [guide 10](10-troubleshooting.md).
