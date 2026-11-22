# 5. Audio: bit-perfect, DAC volume and MQA

How sound gets from a music app to the HiBy R4's DAC on SongateOS, and what was changed to make it bit-perfect.

## The path

```
app ─► Android (AudioFlinger) ─► HiBy's audio HAL ─► Snapdragon DSP ─► HBC3000 (HiBy's clock chip) ─► ES9018C2M DAC
```

- **Normal playback:** Android mixes all apps, applies the volume and the system equalizer, and sends the result to
  HiBy's HAL. With the native-rate setting (`out_rate`), the mix runs at the song's rate, but it isn't bit-perfect.
- **Bit-perfect playback (wired):** an app that asks for it (Android 14 `setPreferredMixerAttributes`) gets its own
  output at the file's exact format. Android passes the samples through unchanged, and HiBy's own mixer runs in
  "bypass". Supported: 16-bit up to 192 kHz, 24-bit and 32-bit up to 384 kHz, stereo.

## What had to change

1. **Android allows bit-perfect only on USB.** Two checks in Android's audio policy library are skipped, so the
   3.5 mm and 4.4 mm outputs offer it too.
2. **HiBy's driver is from Android 11** and doesn't know Android 14's bit-perfect flag. It's handed over as a "direct"
   stream, which HiBy's driver already plays untouched (it's what HiBy's own player used).
3. **The volume.** Android doesn't apply its volume to a bit-perfect stream, so the volume is done **in the DAC chip**,
   as on HiBy's own system:
   - The HiBy R4 service puts the wired outputs in Android's "the device does its own volume" mode and sends every
     volume step to HiBy's DAC volume (0–100, HiBy's scale). The volume steps setting (15/25/50/100) and balance keep
     working.
   - This applies to all wired playback, bit-perfect or not. Low steps are quieter than Android's own curve, as on
     HiBy OS.
   - HiBy's driver also copied Android's master volume into the DAC volume (full volume for a second after every
     audio restart). That's patched out.
4. **Touch and notification sounds are off by default.** Every system click made Android rebuild the bit-perfect
   output's route, and HiBy's driver re-routed the stream each time, which could cause about 3 s of noise.

5. **Both jacks, registered the way HiBy's own system does it.** HiBy's Android 12 registers every analog output as
   Android wired headphones, with the output's name as the device address: `Headphone` (3.5 mm) and `Balance`
   (4.4 mm). Android then hands that name to HiBy's driver (`Balance=;connect=8`), which picks the output from it.
   SongateOS does the same (Android's `WiredAccessoryManager` and the address hand-over to HiBy's older driver are
   patched). One output at a time, the 4.4 mm before the 3.5 mm. So:
   - plugging in mid-song moves the music over, and quick swaps between the jacks work both ways;
   - pulling a plug pauses the music;
   - **the 3.5 mm and 4.4 mm share one volume** (to Android they're the same kind of device), as on HiBy's own system;
   - bit-perfect is offered on both, and not when nothing is plugged in (HiBy's driver would play to its S/PDIF path).
6. **Line out.** HiBy's own line-out outputs (`Lineout`, `BalanceLo`) switch the DAC off on the R4, and its driver
   ignores the line-out switch for the 4.4 mm. So on SongateOS, line out on either jack is the headphone path at a
   **fixed full volume** plus the line-out gain: the volume keys do nothing, your headphone volume is kept and comes
   back when you switch to headphones, and pulling the plug always switches back to headphones (so earphones plugged
   in later never get full volume).
7. **Two fixes in Android's audio server.** Skipping songs fast with bit-perfect on could stop Android's audio
   server for a moment (a track the mixer had already dropped), and changing from a 24-bit to a 16-bit song could
   lose bit-perfect for that song (Android reopened the bit-perfect output in a format HiBy's driver refuses). Both
   are patched.

The source changes are in [`vendor/songateos/patches`](../vendor/songateos/patches) (`apply_av.py` for the audio
policy); the start-up scripts that set up HiBy's driver are in [`vendor/songateos/module`](../vendor/songateos/module).

## Checked on the device

- The DAC clock follows the song: at each stream start the kernel sets HiBy's clock chip to the song's rate
  (`hbc3000_set_samplerate samplerate=44100`).
- 24-bit gets through: HiBy's transport to the clock chip carries a 32-bit word per channel at the song's rate (it's
  labelled "4 × 16-bit at half rate", but moves frames at the full rate).
- The bits arrive unchanged: Tidal MQA files played bit-perfect are recognised by HiBy's driver as MQA / MQA Studio
  and decoded (the DAC then runs at 705.6 kHz). MQA is only detected when every bit is untouched.

## MQA

On a bit-perfect stream, HiBy's driver decodes MQA by itself. The DAC then runs at the decoded rate (e.g. 705.6 kHz
for a 44.1 kHz Tidal file), and **Settings › HiBy audio** shows it, e.g. "ES9018C2M DAC · 705.6 kHz · MQA Studio".
Anything that changes the samples (app volume, ReplayGain, EQ, crossfade) turns bit-perfect, and so MQA, off.

## Settings

**Settings › HiBy audio › Output › Bit-perfect wired output**: on by default. Takes effect after a restart. When it's
off, the wired outputs work as before (Android's own volume, no bit-perfect).

**Settings › Sound & DAC › Output › Charge from USB audio** (from 2.1.1): on by default. Turn it off, and a USB DAC
or speaker that also powers the R4 (a JBL Charge 6, say) no longer charges it: when such a device is plugged in while
it's powering the player, the player stops drawing power from the port (the charger's input is suspended). The music
isn't touched. Unplug it, or turn the setting back on, and charging comes back at once. A normal charger is never
affected, and neither is a DAC the R4 powers itself.

## Notes for testing

- On the R4, never cut `dumpsys media.audio_flinger` short (`| head`, `grep -m1`): HiBy's driver dumps into the same
  pipe and dies when it closes early, which restarts all audio. Save it to a file first.
- The kernel logs every DAC volume change (`dmesg | grep plat_lch_vol_put`), which makes volume issues easy to see.
- `tools/dev/BpTest.java` plays a quiet bit-perfect test tone, to check the path without a music app.
