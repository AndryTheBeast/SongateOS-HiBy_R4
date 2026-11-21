# Changelog

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
- 🧈 Smoother Settings, and the sleep timer fixed

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
- 📶 Bluetooth 5.0: no LE Audio. AAC was tested
- 🗂️ Updates and Google apps need a microSD card (the recovery can't read the encrypted internal storage)
- 📖 More in the README's [Known limits](README.md)

## 📦 Files

| File | What |
|---|---|
| `songateos-2.1.0-R4.zip` | 🎵 SongateOS 2.1.0: install from the recovery, or let the player update itself |
| `OrangeFox-R12.0-Unofficial-R4.img` · `TWRP-3.7-Unofficial-R4.img` | 🛟 the recoveries, for the first install with fastboot |
| `songateos-recovery-orangefox-2026.11.19.3.zip` · `songateos-recovery-twrp-2026.11.19.3.zip` | 🔁 the same recoveries as signed packages: flash from a recovery, or switch in Songate › Settings › Recovery |
| `SHA256SUMS.txt` | 🔍 check every file against it before you use it |
