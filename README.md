<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/wordmark-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/wordmark-light.png">
    <img src="docs/wordmark-dark.png" width="620" alt="SongateOS">
  </picture>
</p>
<p align="center"><b>Android 14 for the HiBy R4, built around Songate.</b><br>
The player starts straight into <a href="https://github.com/AndryTheBeast/Songate">Songate</a>, and everything around it follows: HiBy's DAC on 3.5 mm and 4.4 mm, bit-perfect with the volume in the DAC, your earbuds' noise cancelling and battery, the status lights and the hold switch, all in Songate. Updates come to the player itself.<br>
By <a href="https://github.com/AndryTheBeast"><b>AndryTheBeast</b></a> · version 2.1.0 · based on <a href="https://lineageos.org"><b>LineageOS 21</b></a> and <a href="https://github.com/TrebleDroid"><b>TrebleDroid</b></a><br>
<a href="../../releases"><b>⬇️ Download</b></a> · <a href="https://github.com/AndryTheBeast/Songate"><b>🎵 Songate</b></a> · <a href="docs/00-read-first.md"><b>⚠️ Read this first</b></a></p>

https://github.com/user-attachments/assets/7fe84c88-fa13-4ba3-95f0-70fb62ad859c


> [!IMPORTANT]
> **HiBy R4 standard model only** (Snapdragon 665, eMMC). **Never on the R4 EVA** or any other player.
>
> **Tests were only made on HiBy's firmware of August 2026.** An unlocked bootloader is needed.
>
> **Make your own EDL backup first** ([guide 1](docs/01-backup-edl.md)): it is how you go back.

> [!CAUTION]
> **You install SongateOS at your own risk. Its author is not responsible for any bricked player, lost data or lost
> warranty.** A player that stops booting almost always comes from skipped or rushed steps, not from the OS. Read
> every guide fully, then follow it exactly as written. Start with [Read this first](docs/00-read-first.md).

---

SongateOS is LineageOS 21 (Android 14) with a layer made for the R4: Songate as the only home screen, HiBy's own audio
path kept intact and made bit-perfect, and the player's hardware brought into Songate's settings. Nothing of HiBy's
firmware is included: SongateOS sets up the drivers already on your player.

## ✨ Highlights

- 🎵 **Songate is the home screen.** The player starts straight into your music, and every screen follows Songate's look, from the boot logo to the charging animation.
- 🎚️ **HiBy's audio, untouched.** The ES9018C2M DAC on 3.5 mm and 4.4 mm balanced, bit-perfect up to 384 kHz with the volume done in the DAC, gain, filters, balance and line out. MQA is decoded by HiBy's own driver.
- 🎧 **Your earbuds, controlled.** Noise cancelling, ambient level, conversation detection and battery for AirPods, Galaxy Buds and Soundcore, right in Songate.
- 💡 **The status lights.** Notifications, charging, low battery, the colour of the music's quality and a bass pulse.
- 👆 **Gestures and recent apps.** Swipe home, swipe and hold for your open apps, and close what runs in the background.
- 🔒 **The hold switch.** The switch on top locks the buttons.
- ⬆️ **Updates from the player.** Downloaded to the microSD card and installed by the recovery, with your Google apps kept.
- 🧩 **Google apps if you want them.** Core or Basic, downloaded from their makers, and removable again.
- 🛟 **TWRP or OrangeFox.** Both built for the R4, and switchable from Settings.

## 💾 Installation

**Read [Read this first](docs/00-read-first.md), then every guide fully, before you run anything.** Then, in order:

1. **[Back up your R4 with EDL](docs/01-backup-edl.md).** A complete, checked copy of your own player. Do not skip it.
2. **[Install a custom recovery](docs/02-recovery.md).** TWRP or OrangeFox, from fastbootd.
3. **[Install SongateOS](docs/03-install-os.md).** The system, the boot logo and the animations, from the recovery.

What you need: a Windows, macOS or Linux computer with [Android platform tools](https://developer.android.com/tools/releases/platform-tools),
a USB-C cable, and a **microSD card** (for updates and Google apps; see [guide 8](docs/08-updates-google-apps.md)).

Going back to HiBy's firmware: [guide 7](docs/07-back-to-stock.md), from your own backup.

## 📖 The guides

| | Guide | |
|---|---|---|
| 0 | [Read this first](docs/00-read-first.md) | the risks, and how to avoid them |
| 1 | [Back up your R4 with EDL](docs/01-backup-edl.md) | your way back, whatever happens |
| 2 | [Install a custom recovery](docs/02-recovery.md) | TWRP or OrangeFox |
| 3 | [Install SongateOS](docs/03-install-os.md) | the first install, and updates from a computer |
| 4 | [Boot logo and animations](docs/04-branding.md) | how they're made, and how to go back to HiBy's |
| 5 | [Audio](docs/05-audio.md) | bit-perfect, the DAC volume, the 4.4 mm and MQA |
| 6 | [Root with Magisk](docs/06-root-magisk.md) | optional |
| 7 | [Back to stock](docs/07-back-to-stock.md) | HiBy's firmware from your backup |
| 8 | [Updates and Google apps](docs/08-updates-google-apps.md) | from the player, the card or a computer |
| 9 | [Earbuds](docs/09-earbuds.md) | AirPods, Galaxy Buds and Soundcore |
| 10 | [Troubleshooting and logs](docs/10-troubleshooting.md) | and how to report a bug |

<details>
<summary><b>📖 Everything else</b></summary>

### 🎚️ Audio

HiBy's audio driver and DSP stay as they are; SongateOS tells them which output to use and gets Android's sound to
them untouched. Bit-perfect playback is offered on both wired outputs: an app that asks for it (Songate does) gets
its own stream at the file's exact format, and the volume moves into the DAC chip, as on HiBy's own system. Gain,
the DAC's filters, DSD compensation, balance, line out and the volume steps are in Settings › HiBy audio. Both jacks
are registered with Android the way HiBy's own system does it, so they share one volume, plugging in mid-song moves
the music over, and pulling a plug pauses it. Line out on either jack is the headphone path at a fixed full volume.
Details: [guide 5](docs/05-audio.md).

### 🎧 Earbuds

SongateOS speaks the control protocols of AirPods, Galaxy Buds (Buds3 and Buds4 generations) and Soundcore Liberty 4
NC directly, so Songate shows their modes, levels and battery with no app from the maker. Changes made on the earbuds
show up at once. Details: [guide 9](docs/09-earbuds.md).

### 💡 Lights and buttons

The colour LED and the back light show notifications, charging and low battery, and can follow the music: the colour
of its quality (CD, hi-res, DSD, MQA) or a pulse on the bass. The hold switch locks the buttons; the media buttons
work with the screen off, and can wake it if you want.

### 👆 Gestures and recent apps

A swipe from the bottom goes home and fades the app into Songate; swipe and hold for your open apps, then swipe a
card away to close it. Songate's Apps page marks the apps running in the background and can stop them fully.

### ⬆️ Updates

Settings › Updates checks this repository's releases (or a newer zip you copied to the card), downloads to the
microSD, checks the file's checksum and its SongateOS signature, and installs it through the recovery. Your data
stays, and Google apps are kept if you have them. Without a microSD card, update from a computer. Details:
[guide 8](docs/08-updates-google-apps.md).

### 🧩 Google apps

Not included. Settings › Google apps offers NikGapps Core (Play Store and services) or MindTheGapps (with Google's
basic apps), downloaded from their makers and checked against their own checksums. Remove puts SongateOS back
without them and cleans up what they left behind.

### 🛟 Recoveries

TWRP 3.7 and OrangeFox R12, built from this repository's device tree. They install SongateOS's updates by
themselves, keep their logs and extras on the microSD card, and take the time from SongateOS so files get real
dates. Switch between them in Settings › Recovery. Details: [guide 2](docs/02-recovery.md).

### 🔓 Root

Optional, with Magisk, from Settings › Root or by hand ([guide 6](docs/06-root-magisk.md)).

</details>

<details>
<summary><b>🛠️ Building it yourself</b></summary>

LineageOS 21 with TrebleDroid's patches, plus this repository's layer in `vendor/songateos`, then signed with your
own key. The steps and scripts are in [`tools/build`](tools/build/README.md).

| Folder | What's in it |
|---|---|
| [`vendor/songateos`](vendor/songateos) | The SongateOS layer: the HiBy R4 service (system API, audio, lights, earbuds, updates), overlays, Settings pages, start-up scripts, SELinux rules and the source patches for Android |
| [`device/hiby/R4`](device/hiby/R4) | The recovery device tree for TWRP (twrp-12.1) and OrangeFox (fox_12.1), with SongateOS's update hook |
| [`ota`](ota) | The update installer, the zip builders and the public release certificate |
| [`branding`](branding) | Generators for the boot logo, the low-battery screens, the animations and this page's wordmark |
| [`tools`](tools) | Build scripts, and two test tools for the service's API |
| [`docs`](docs) | The guides |

Not included, on purpose: HiBy or Qualcomm firmware (stock images, the EDL loader, HiBy's audio libraries, also not
in patched form), Google's apps, Songate's APK (it comes from [Songate's releases](https://github.com/AndryTheBeast/Songate/releases))
and anyone's personal backup.

</details>

<details>
<summary><b>⚠️ Known limits</b></summary>

- **Model:** the HiBy R4 standard model only. The R4 EVA uses different storage and was never tested.
- **Firmware:** tests were only made on HiBy's firmware of August 2026.
- **Updates need a microSD card:** the recovery can't read the encrypted internal storage. Without a card, update from a computer ([guide 3](docs/03-install-os.md#updates-need-a-microsd-card)).
- **Bluetooth 5.0:** no LE Audio or LC3. AAC was tested; other codecs are still to be checked.
- **Spotify** can be searched from Songate but plays only inside Spotify: it doesn't accept "play" requests from other apps.
- **Kernel:** HiBy's prebuilt kernel is used; its source isn't published, so kernel-level features such as KernelSU aren't possible.
- **Signing:** SongateOS is signed with its own keys from 2.1.0. The betas used Android's public test keys, so going from a beta to 2.1.0 needs **Format Data** once ([guide 3](docs/03-install-os.md#coming-from-a-songateos-beta)).
- **4.4 mm line out** wasn't tested on the device for 2.1.0 (its headphone mode was).
- **USB speakers that charge the player:** after unplugging one, the R4 was seen once not to sleep until the next restart. Unplugging USB DACs that don't charge it is fine. Being looked into.

</details>

<details>
<summary><b>📜 About, licence and credits</b></summary>

### About

SongateOS began as a few fixes to make a LineageOS GSI play music on the R4 at all, and grew into an OS around
[Songate](https://github.com/AndryTheBeast/Songate), the music player by the same author. Its SongateOS layer, the
HiBy R4 service, the recovery device tree, the update system and the branding are its own.

### 📜 Licence

SongateOS's own files are under the **Apache-2.0 licence** (`LICENSE`). The fonts are under the SIL Open Font
Licence. TWRP and OrangeFox are GPL-3.0; the recovery images contain HiBy's prebuilt kernel, GPL-2.0, whose source HiBy
hasn't published.

### 🙏 Credits

- 🧱 **[LineageOS](https://lineageos.org)** and **[TrebleDroid](https://github.com/TrebleDroid)** (and Andy Yan's [lineage_build_unified](https://github.com/AndyCGYan/lineage_build_unified)): the base system.
- 🛟 **[TWRP](https://twrp.me)** and **[OrangeFox Recovery](https://orangefox.download)**: the recoveries, built from their sources.
- 🔓 **[Magisk](https://github.com/topjohnwu/Magisk)** (GPL-3.0): root, downloaded from its own releases when you choose it in Settings › Root. SongateOS itself never includes it; only the OrangeFox recovery package carries OrangeFox's standard Magisk installer and uninstaller, as every OrangeFox build does.
- 🧩 **[NikGapps](https://nikgapps.com)** and **[MindTheGapps](https://github.com/MindTheGapps)**: Google apps, downloaded from their makers when you choose them. SongateOS never hosts Google's apps.
- 🎧 **[LibrePods](https://github.com/librepods-org/librepods)** and **[GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient)**: their published protocol notes made the earbud controls possible. SongateOS's code is its own; none of theirs is included.
- 🔤 **Inter** and **Fraunces** (SIL Open Font Licence), as in Songate.
- 🎵 **[Songate](https://github.com/AndryTheBeast/Songate)** (MIT): the music player at the heart of it.

SongateOS is an unofficial project, not affiliated with HiBy, Google, Apple, Samsung, Anker or Spotify.

### 👥 Authors

- **[AndryTheBeast](https://github.com/AndryTheBeast)**: SongateOS, the HiBy R4 service, the device tree, and Songate.

</details>
