# Changelog

## SongateOS 2.2.1

**SongateOS 2.2.1** brings **Songate 2.2.1** to the R4, and makes the update itself smoother: no extra restart after it. From 2.1.0 or 2.1.1 it's a normal update: your music, apps and settings stay.

**Only for the HiBy R4 standard model** (Snapdragon 665, eMMC), **never the R4 EVA.** **Tests were only made on HiBy's firmware of August 2026.** **The 4.4 mm line out wasn't tested on the device** (its headphone mode was).

> [!CAUTION]
> **You install SongateOS at your own risk.** Read [Read this first](docs/00-read-first.md) and make your own **EDL backup** ([guide 1](docs/01-backup-edl.md)) before anything else: it is how you go back.

## ⬆️ From SongateOS 2.1.0 or 2.1.1

**Songate › Settings › Updates** finds 2.2.1, shows what changed, downloads it to the microSD card, checks it and installs it through the recovery. No card? Update from a computer with `adb sideload` ([guide 3](docs/03-install-os.md#updates-need-a-microsd-card)).

## 🆕 First install, from HiBy's firmware

1. 🛟 Put **OrangeFox** or **TWRP** in the recovery with fastboot, and turn verification off in `vbmeta`: the `.img` files below and [guide 2](docs/02-recovery.md).
2. 📦 Follow [guide 3](docs/03-install-os.md): **Format Data**, then install **songateos-2.2.1-R4.zip** from the microSD.
3. 🎵 Restart: the SongateOS logo, the boot animation, then Songate's setup.

Coming from a SongateOS beta: **Format Data** once before the install (the betas used other keys).

## ✨ SongateOS 2.2.1

- 🎵 **Songate 2.2.1** inside (below)
- 🔄 No extra restart after an update: Android's saved overlay maps are made again when the system's apps change. After the 2.1.1 update the system interface could stop once and restart the player

## 🎵 Songate 2.2.1 (since 2.1.1)

- 🎚️ Songate's own keyboard for all typing: quick, with a cursor, Shift and caps lock
- 🎨 Background blur: Classic as before, or Smooth in three strengths. Settings › Appearance › Background
- 🎤 Lyrics in cards (General, Timing, Online), and word-by-word lyrics fill smoothly
- ⏳ Sleep timer: Custom, your own minutes on a number pad
- 🎛️ Equalizer: each band's value shown; tap a band to type its value
- 🔋 Settings is smoother: long pages no longer slow the app down
- 🔧 Numbers that change while you look at them (the battery's details, the sleep timer, the time over the seek bar, download progress) no longer move the words beside them
- 🔧 Closing Songate no longer gets stuck when the network changes, and more fixes for lyrics, tags, sync and your server
- ℹ️ Last.fm is gone: its API now needs a paid subscription. Plays and likes still sync with your own server

## ⚠️ Known limits

- 🔌 **USB speakers that charge the player:** after unplugging one, the R4 was seen once not to sleep until the next restart. With Charge from USB audio off, it doesn't charge from them at all
- 📶 Bluetooth 5.0: no LE Audio. AAC was tested
- 🗂️ Updates and Google apps need a microSD card (the recovery can't read the encrypted internal storage)
- 📖 More in the README's [Known limits](README.md)

## 📦 Files

| File | What |
|---|---|
| `songateos-2.2.1-R4.zip` | 🎵 SongateOS 2.2.1: install from the recovery, or let the player update itself |
| `songateos-recovery-orangefox-2026.11.22.zip` · `songateos-recovery-twrp-2026.11.22.zip` | 🔁 the recoveries as signed packages: flash from a recovery, or switch in Songate › Settings › Recovery |
| `OrangeFox-R12.0-Unofficial-R4.img` · `TWRP-3.7-Unofficial-R4.img` | 🛟 the same recoveries as images, for the first install with fastboot |
| `SHA256SUMS.txt` | 🔍 check every file against it before you use it |

## SongateOS 2.1.1

**SongateOS 2.1.1** fixes what the first owners of 2.1.0 found, and adds **Charge from USB audio**, with **Songate 2.1.1** inside. From 2.1.0 it's a normal update: your music, apps and settings stay.

**Only for the HiBy R4 standard model** (Snapdragon 665, eMMC), **never the R4 EVA.** **Tests were only made on HiBy's firmware of August 2026.** **The 4.4 mm line out wasn't tested on the device** (its headphone mode was).

> [!CAUTION]
> **You install SongateOS at your own risk.** Read [Read this first](docs/00-read-first.md) and make your own **EDL backup** ([guide 1](docs/01-backup-edl.md)) before anything else: it is how you go back.

## ⬆️ From SongateOS 2.1.0

**Songate › Settings › Updates** finds 2.1.1, shows what changed, downloads it to the microSD card, checks it and installs it through the recovery. No card? Update from a computer with `adb sideload` ([guide 3](docs/03-install-os.md#updates-need-a-microsd-card)).

## 🆕 First install, from HiBy's firmware

1. 🛟 Put **OrangeFox** or **TWRP** in the recovery with fastboot, and turn verification off in `vbmeta`: the `.img` files below and [guide 2](docs/02-recovery.md).
2. 📦 Follow [guide 3](docs/03-install-os.md): **Format Data**, then install **songateos-2.1.1-R4.zip** from the microSD.
3. 🎵 Restart: the SongateOS logo, the boot animation, then Songate's setup.

Coming from a SongateOS beta: **Format Data** once before the install (the betas used other keys).

## ✨ SongateOS 2.1.1

- 🔌 **Charge from USB audio** (new, Settings › Sound & DAC › Output): turn it off, and a USB DAC or speaker that also powers the R4 (a JBL Charge 6, say) no longer charges it. The music isn't touched, and a normal charger always charges
- 🔓 **Install Magisk** in Settings › Root works: in 2.1.0 it answered "Look for Magisk first" and did nothing
- 🛟 The Recovery card looks at the releases again before each update, so a replaced package can no longer fail with "HTTP 404"
- 📦 The installer also works right after **Format Data**, without restarting the recovery first, and its log shows the same time as the recovery's
- 🧩 A Google apps install leaves its log on the card, next to the package (`songateos_gapps.log`)
- 🏷️ A neutral build stamp: the build shows `songateos`, not the name of the computer it was built on
- 🎵 Songate 2.1.1 inside

## 🎵 Songate 2.1.1

- 🔌 Charge from USB audio: turn it off and a USB DAC or speaker no longer charges the R4. Settings › Sound & DAC
- ⌨️ Typing a name: a tap on the field moves the cursor there, and no longer types the digit under it
- 🗂️ Updates: the cards stay where they are while SongateOS checks, so a tap can't land on another card's button
- 🛟 After a recovery update that failed, Check is offered again

## ⚠️ Known limits

- 🔌 **USB speakers that charge the player:** after unplugging one, the R4 was seen once not to sleep until the next restart. With Charge from USB audio off, it doesn't charge from them at all
- 📶 Bluetooth 5.0: no LE Audio. AAC was tested
- 🗂️ Updates and Google apps need a microSD card (the recovery can't read the encrypted internal storage)
- 📖 More in the README's [Known limits](README.md)

## 📦 Files

| File | What |
|---|---|
| `songateos-2.1.1-R4.zip` | 🎵 SongateOS 2.1.1: install from the recovery, or let the player update itself |
| `songateos-recovery-orangefox-2026.11.22.zip` · `songateos-recovery-twrp-2026.11.22.zip` | 🔁 the recoveries as signed packages: flash from a recovery, or switch in Songate › Settings › Recovery |
| `OrangeFox-R12.0-Unofficial-R4.img` · `TWRP-3.7-Unofficial-R4.img` | 🛟 the same recoveries as images, for the first install with fastboot |
| `SHA256SUMS.txt` | 🔍 check every file against it before you use it |

## SongateOS 2.1.0 — first public release

**SongateOS 2.1.0** is here: the first public release of Android 14 for the HiBy R4, built around **Songate 2.1.0**. Signed with its own keys, the SongateOS boot logo in the installer, both jacks working the way HiBy's own system does, and bit-perfect that holds through fast skips and every format change.

**Only for the HiBy R4 standard model** (Snapdragon 665, eMMC), **never the R4 EVA.** **Tests were only made on HiBy's firmware of August 2026.** **The 4.4 mm line out wasn't tested on the device for this release** (its headphone mode was).

> [!CAUTION]
> **You install SongateOS at your own risk.** Read [Read this first](docs/00-read-first.md) and make your own **EDL backup** ([guide 1](docs/01-backup-edl.md)) before anything else: it is how you go back.

## 🆕 First install, from HiBy's firmware

1. 🛟 Put **OrangeFox** or **TWRP** in the recovery with fastboot: the `.img` files below ([guide 2](docs/02-recovery.md)).
2. 📦 Follow [guide 3](docs/03-install-os.md): turn verification off in `vbmeta_a`, **Format Data**, then install **songateos-2.1.0-R4.zip** from the microSD.
3. 🎵 Restart: the SongateOS logo, the boot animation, then Songate's setup.

## 🔁 Coming from a SongateOS beta

From this release SongateOS is signed with **its own keys**; the betas used Android's public test keys. So this once, in the recovery: **Wipe › Format Data** first, then install the zip, and set up Songate again (Bluetooth devices need pairing again). Your music on the microSD stays. Updates after this keep your data.

## ⬆️ Updates from now on

**Songate › Settings › Updates** finds the new version, shows what changed, downloads it to the microSD card, checks it and installs it through the recovery. Your music, apps and settings stay. No card? Update from a computer with `adb sideload` ([guide 3](docs/03-install-os.md#updates-need-a-microsd-card)).

## ✨ SongateOS 2.1.0

- 🔐 Signed with SongateOS's own keys: LineageOS's Trust no longer warns, and only SongateOS can make updates your player accepts
- 🖼️ The boot logo comes with the installer: "HIBY R4 · powered by Songate", the low-battery screens and the bootloader's debug and overheating screens, all redrawn in Songate's look
- 🎧 Both jacks work the way HiBy's own system does: plug in mid-song and the music moves over, swap jacks quickly both ways, bit-perfect on 3.5 mm and 4.4 mm
- 🔌 Line out on the 4.4 mm too, like the 3.5 mm: a fixed full volume with the line-out gain
- 🛡️ USB debugging asks first: a computer must be allowed on the player before it can connect
- 🔕 No more "SongateOS is running" notification: the player's service runs as part of the system again
- 🎵 Songate 2.1.0 inside, the version SongateOS now shares
- 🔀 Installs on slot a or b: HiBy's own updates leave a player on either one, and SongateOS now stays on yours

## 🎚️ Sound

- ⏭️ Skip as fast as you like: bit-perfect holds, and Android's audio server no longer stops for a moment
- 🎼 From a 24-bit to a 16-bit song (and back), every song stays bit-perfect at its own rate
- 🔊 The 3.5 mm and 4.4 mm share one volume, as on HiBy OS. Turn it down before plugging in sensitive earphones
- 🔈 Back from line out to headphones, your own volume comes back (it could stay at full)
- 🔇 Nothing plugged in? Bit-perfect waits for a jack (HiBy's driver played silently to its digital output)

## 🎵 Songate 2.1.0

- ▶️ Play when plugged in (Settings › Playback, off by default): the music starts when a jack goes in, bit-perfect straight away
- 🎧 No more music starting by itself when Bluetooth earphones switch off while wired ones are plugged in
- 💡 The status light follows your accent colour with the screen off
- 👆 No phantom play or next presses when you pull a plug
- 🧈 Smoother Settings, and the sleep timer fixed (it now fits an hour or more)
- 🌫️ Automix's frosted cover has a clean edge

## 🧹 Under the hood

- 📞 Plugging in headphones can no longer restart Android when a Bluetooth lookup is refused
- ⚡ The CPU governor stays on "schedutil" (HiBy's start-up put it on "performance" after every boot)
- 🗑️ Phone apps, the camera, the Via browser, LineageOS Music and Recorder are gone for you: the R4 has no phone or camera
- 📉 A network statistics log flood is gone
- ⬆️ The first update after a fresh install no longer asks you to restart into the recovery first: the installer leaves the recovery's record for the updater
- 🔋 Updates install with any charger connected, even a slow one (below 40 %); never below 15 %
- 📝 The whole changelog reaches Songate (it was cut after 4,000 characters)

## ⚠️ Known limits

- 🔌 **USB speakers that charge the player:** after unplugging one, the R4 was seen once not to sleep until the next restart. USB DACs that don't charge it are fine
- 🔀 **Slot b** is new and not yet tested on a player; on slot b use the recoveries of 2026.11.22 (below)
- 📶 Bluetooth 5.0: no LE Audio. AAC was tested
- 🗂️ Updates and Google apps need a microSD card (the recovery can't read the encrypted internal storage)
- 📖 More in the README's [Known limits](README.md)

## 📦 Files

| File | What |
|---|---|
| `songateos-2.1.0-R4.zip` | 🎵 SongateOS 2.1.0: install from the recovery, or let the player update itself |
| `songateos-recovery-orangefox-2026.11.22.zip` · `songateos-recovery-twrp-2026.11.22.zip` | 🔁 the recoveries as signed packages: flash from a recovery, or switch in Songate › Settings › Recovery |
| `OrangeFox-R12.0-Unofficial-R4.img` · `TWRP-3.7-Unofficial-R4.img` | 🛟 the same recoveries as images, for the first install with fastboot |
| `SHA256SUMS.txt` | 🔍 check every file against it before you use it |
