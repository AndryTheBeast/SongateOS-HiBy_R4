# 1. Back up your R4 with EDL

A complete copy of **your own** player, taken before you change anything. It's your way back from any mistake,
including a player that no longer starts.

> [!CAUTION]
> **Read [Read this first](00-read-first.md) before anything else.** You install SongateOS at your own risk; its author
> isn't responsible for a player that no longer starts. Read each guide fully, then follow it exactly as written.

> **Standard HiBy R4 only** (Qualcomm SM6125, eMMC). Not the R4 EVA: it uses different storage and its own files.
>
> **Your backup only fits your unit.** Partitions such as `persist`, `modemst1/2`, `fsg`, `devinfo` and `privdata`
> hold data unique to each player (calibration, identifiers). Never restore someone else's backup to your R4, and
> never give yours to anyone to flash.

Reading is safe: this guide only **reads** from the player. Nothing on it changes.

Tested on Windows 11 with bkerler/edl 3.62. Linux should work the same way without the driver steps, but hasn't been
tested for this guide.

## What EDL is

EDL (Emergency Download mode) is built into the Qualcomm chip, below the bootloader. It works even when Android, the
recovery and the bootloader are broken, so a backup taken with it can always be written back. The PC talks to it with
a small program, the **firehose loader**, which it uploads to the player first.

## What you need

- A PC with **Python 3** and **Git**.
- A good USB-C data cable, plugged straight into the PC (no hub).
- About **35 GB free** on the PC for the backup, and somewhere else to keep a second copy.
- The firehose loader for the R4 (next section).

### The loader

The R4 accepts **`prog_firehose_ddr_car_radio.elf`**. It's Qualcomm's file, so this project can't host it; R4 owners
share it in the [XDA HiBy R4 thread](https://xdaforums.com/t/4746303/). Check it's exactly this file before you use
it:

```
SHA-256  2a2123157b81d78701a54822305749d751d789e1cd83c15cea5bf891efab30e0
```

On Windows (PowerShell): `Get-FileHash .\prog_firehose_ddr_car_radio.elf`. On Linux: `sha256sum prog_firehose_ddr_car_radio.elf`.

**If the checksum is different, don't use the file.** Never try other loaders found online: a wrong loader can fail in
ways that are hard to get out of.

### The edl tool

```
git clone https://github.com/bkerler/edl
cd edl
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt        (Linux: .venv/bin/python -m pip …)
```

### Windows only: the USB driver and a one-line fix

1. Install **UsbDk** (the `.msi` from [daynix/UsbDk releases](https://github.com/daynix/UsbDk/releases)). It lets edl
   reach the player without replacing any Windows driver. Zadig isn't needed.
2. In the edl folder, edit `edlclient/Library/Connection/usblib.py`. In the Windows branch of `__init__`, replace
   ```python
   elif is_windows():
       self.backend = None
   ```
   with
   ```python
   elif is_windows():
       import usb.backend.libusb1
       self.backend = usb.backend.libusb1.get_backend()
   ```
   Without it, edl on Windows doesn't apply its UsbDk setting and can't open the player.

## Enter EDL on the R4

1. Connect the R4 to the PC with the USB cable.
2. Hold **Power + Previous + Play/Pause + Next** together, until the screen **flashes once**, then let go of all
   buttons.
3. The screen stays **black**. That's normal. Windows shows a new device `QUSB_BULK` (USB ID `05c6:9008`).

**To leave EDL:** hold **Power + Next** until the screen turns on, then let go. The player then starts normally.

| | Buttons |
|---|---|
| Enter EDL (USB connected) | Power + Previous + Play/Pause + Next, until the screen flashes once; then let go |
| Leave EDL | Power + Next, until the screen turns on; then let go |
| Start the recovery (from off) | Power + Previous; when the screen turns on, let go of Power and keep Previous held |

The player can't be restarted out of EDL by a command (`edl reset` doesn't bring it back): use the buttons.

## The one rule that matters: one command per EDL session

On the R4, the player stops answering **as soon as an edl command finishes**. Windows then shows "Unknown USB Device
(Device Descriptor Request Failed)", and a second command, even `edl reset`, waits forever.

So for **every** edl command:

1. Enter EDL (above).
2. Run **one** edl command. One command can read or write several partitions, listed with commas (see "Restore one
   partition").
3. When it ends, leave EDL (Power + Next) and enter it again before the next command.

If an edl command hangs on "Waiting for the device": close it (Ctrl+C, or end the leftover `python` process in Task
Manager), leave EDL with the buttons, enter it again, and start the command again.

## Make the backup

All commands run from the edl folder. Replace `LOADER` with the path to your loader file, and `D:\R4_backup` with a
folder of your own (it must exist and be empty). Every command needs **`--memory=emmc`**: the R4 uses eMMC, not UFS.

**Session 1: check the connection and save the partition list**

```
.venv\Scripts\python edl.py printgpt --memory=emmc --loader=LOADER > D:\R4_backup\partition_table.txt
```

The first lines should show `CPU detected: "nicobar_IoT_modem"` and `MSM_ID:0x001750e1`. The file lists about 79
partitions, among them `boot_a`, `recovery_a`, `super`, `persist` and `userdata`. If the CPU is different, stop: it
isn't a standard R4.

**Session 2: read everything** (re-enter EDL first)

```
.venv\Scripts\python edl.py rl D:\R4_backup --memory=emmc --skip=userdata --genxml --loader=LOADER
```

- This reads every partition except `userdata` into `<name>.bin` files, plus `gpt_main0.bin`, `gpt_backup0.bin` and
  `rawprogram0.xml`.
- It takes about **10 minutes**; `super.bin` alone is 6 GB.
- `userdata` is left out on purpose. It's encrypted with keys tied to the player, so a copy can't be used anyway. Your
  music and files need their own copy (copy them over USB, or keep them on the microSD).

**When it's done:** check that the folder has about 80 `.bin` files and that none of them is 0 bytes. The sizes match
the partition sizes in `partition_table.txt`.

## Write the checksums

They tell you later that a file is still exactly what was read from your player.

Git Bash or Linux, in the backup folder:
```
sha256sum *.bin rawprogram0.xml > SHA256SUMS.txt
```

PowerShell, in the backup folder:
```powershell
Get-ChildItem *.bin, rawprogram0.xml | ForEach-Object { "$((Get-FileHash $_ -Algorithm SHA256).Hash.ToLower())  $($_.Name)" } | Set-Content SHA256SUMS.txt
```

Check them at any time with `sha256sum -c SHA256SUMS.txt` (Git Bash / Linux): every line must say `OK`.

## Keep it safe

- Keep **two copies**, on two different drives, not on the player.
- Add a note in the folder with the date and your HiBy firmware version (Settings › About), so you know what it
  belongs to.
- Don't edit, rename or "clean up" files in the backup. Work on copies.
- Don't share it: it contains your player's identifiers.

## Restore one partition

You'll rarely need this; most guides undo their own changes from fastbootd. EDL is for when the player doesn't start
any more.

1. Check the file first: `sha256sum -c SHA256SUMS.txt` must say `OK` for it.
2. Enter EDL, then write it:
   ```
   .venv\Scripts\python edl.py w recovery_a D:\R4_backup\recovery_a.bin --memory=emmc --loader=LOADER
   ```
   Several partitions go in the same command, names and files each separated by commas:
   ```
   .venv\Scripts\python edl.py w boot_a,vbmeta_a D:\R4_backup\boot_a.bin,D:\R4_backup\vbmeta_a.bin --memory=emmc --loader=LOADER
   ```
3. Leave EDL with the buttons (Power + Next) and start the player.

**Only ever write these:** `boot_a`, `recovery_a`, `dtbo_a`, `vbmeta_a`, `vbmeta_system_a`, `super`, `imagefv`,
`metadata`, `misc`.

**Never write these**, not even from your own backup, unless you know exactly why: `xbl`, `xbl_config`, `tz`, `rpm`,
`hyp`, `abl`, `devcfg`, `keymaster`, `persist`, `modemst1`, `modemst2`, `fsg`, `devinfo`, `frp`, `privdata1`,
`privdata2`, or the GPT. They're what makes the player start at all and what keeps EDL itself usable. A mistake there
can't be fixed from the PC.

Writing with EDL was tested end to end on an R4 (November 2026): HiBy's firmware was written back from a backup
(`boot_a`, `vbmeta_a`, `recovery_a`, `recovery_b`, `imagefv_a`, `misc`, `super`) and read back identical, bit for bit.
Write only from your own verified backup.

## Next

[2. Install a custom recovery](02-recovery.md)
