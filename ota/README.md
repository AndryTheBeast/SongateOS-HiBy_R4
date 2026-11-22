# SongateOS updates (OTA)

SongateOS updates itself from this repository's GitHub releases: **Songate › Settings › Updates** finds a
new release, downloads it to the microSD card, checks it, and on your **Install** restarts into the recovery, which
installs it and restarts back into SongateOS. Your music, apps, settings and root stay.

## How it fits together

```
 Android (HiBy R4 service app)                 root (module)                 recovery (TWRP / OrangeFox)
 ───────────────────────────────               ─────────────                 ───────────────────────────
 check   GET api.github.com/…/releases
 download → <microSD>/Android/data/
           com.andre.hibyr4.service/files/ota/
 verify  SHA256SUMS.txt + signature
         (release certificate)
 checks  battery, standard R4, HiBy vendor,
         slot a or b, our recovery with the hook
 install ota.cmd ──────────────────────────▶  ota_bridge.sh copies it to
                                              /metadata/songateos/ota.cmd
         reboot("recovery") ──────────────────────────────────────────────▶  runatboot.sh: reads + deletes ota.cmd,
                                                                             checks path + SHA-256, loads the
                                                                             SongateOS key, writes an
                                                                             OpenRecoveryScript: install (signature
                                                                             checked) and reboot
                                                                             update-binary: device checks, writes
                                                                             system_<slot> (+ logo), verifies, writes
                                                                             /metadata/songateos/ota.result
 notification "installed" / "failed"  ◀──────  ota_bridge.sh publishes ota.result
```

- `/metadata` is the only place both Android and the recoveries can read: there is no cache partition, and the
  recoveries don't decrypt `/data`. Only a few small text files go there.
- The update zip itself goes on the microSD card (exFAT), which both recoveries read.
- On the LineageOS GSI the root part is the Magisk module's `ota_bridge.sh`. In the full SongateOS build it becomes
  an init service with its own SELinux type.

## Files here

| File | What it is |
|---|---|
| `make_release_key.sh` | Makes the release key once. The private key stays **offline**, never in this repo. |
| `keys/songateos_release.x509.pem` | The release certificate (public). The recoveries (`songateos_otacerts.zip` in the device tree) and the updater (`res/raw/otacerts.zip`, made by `build.sh`) check against it. |
| `make_ota_zip.sh` | Builds and signs an update zip (`songateos-X.Y.Z-R4.zip`) and its `SHA256SUMS.txt`. |
| `update-binary` | The installer inside the zip (a shell script, run by TWRP / OrangeFox). |
| `verify_ota_zip.py` | Checks a zip's signature the same way the recovery does. |

## What an update zip may write

`update-binary` checks everything before its first write and stops if anything is off:

- the recovery is ours for the R4, the device tree model is the **standard HiBy R4** (the R4 EVA and other models
  stop here), the SoC is SM6125, and the vendor partition is HiBy's R4 vendor;
- the active slot (`ro.boot.slot_suffix`) is `a` or `b`, and that slot's `vbmeta` has verification off (flags 3);
- the image fits in that slot's `system`, or `lptools` can grow it inside `super`.

Everything below is on the slot the player started on, written `<slot>`. The installer never switches slots.

Then it writes only:

- `system_<slot>`, and reads it back against the zip's SHA-256;
- `imagefv_<slot>` (boot logo), only if the zip has one **and** the logo there is exactly HiBy's stock one or a
  SongateOS logo listed in the zip (`imagefv_from`). It's a patch (`imagefv.patch`), checked against the expected
  SHA-256 before it's written and read back after. Any other logo is left alone.
- `/metadata/songateos/ota.result` and `recovery.info` (which recovery ran the install, so Android knows it after a
  Format Data).

It never writes the other slot, boot, dtbo, vbmeta, recovery, xbl, tz, rpm, hyp, abl, devcfg, keymaster, persist,
modemst1/2, fsg, devinfo, frp, privdata1/2, the GPT, or userdata. Magisk lives in `boot_<slot>`, which isn't touched,
so root stays.

A zip built with `--test` runs every check and writes nothing. Use it to try the whole chain safely.

## Making a release

```bash
# once
bash ota/make_release_key.sh /path/outside/the/repo/songateos_release

# each release
bash ota/make_ota_zip.sh --version 1.0.0 --system out/target/product/tdgsi_arm64_ab/system.img \
     --key /path/outside/the/repo/songateos_release --out release/
```

Upload `songateos-1.0.0-R4.zip` and `SHA256SUMS.txt` to a GitHub release tagged `songateos-1.0.0`.

- Pre-releases are offered only to users who turned on "Beta updates".
- `recovery-*` releases are ignored by the updater.

## Requirements on the R4

- A SongateOS recovery with the update hook in `recovery_<slot>`: hook 2+ for updates, 3+ for Google apps, and
  **6+ on slot b** (hook 6 is the first that finds Google apps' folders on slot b). The recoveries of version
  2026.11.22 (TWRP v4.8, OrangeFox `ota11`, in the SongateOS 2.1.0 release) have hook 6.
- At least 15 % battery with the charger connected, or 40 % without.
- After flashing the recovery, tap Songate › Settings › Updates › Recovery › **Check it** once. The R4
  restarts into the recovery, which records itself, then comes straight back.
- A microSD card with room for the update.
- The repository must be public: the updater uses GitHub's API without a token. For tests, the updater can point at
  another public repository (the `repo` option, Settings only).

## Tested on the R4 (2026-11-16, OrangeFox ota4, dry-run zip)

| Test | Result |
|---|---|
| Check / Download / Install from Settings, through a public test repository | passed |
| Signature check in the recovery (SongateOS key only; the AOSP test key is refused) | passed |
| All installer checks (model, SoC, vendor, slot a, vbmeta flags) | passed (slot b: not yet tested on a player) |
| Restart back to Android and the "installed / test ran" notification | passed |
| Wrong checksum: refused before install, notification "update failed", no retry loop | passed |
| "Check it" recovery visit | passed |

## Rules learned the hard way

- **No here-documents (`cat <<EOF`) in recovery scripts.** Android's shell builds them in its `TMPDIR`, which defaults
  to `/data/local`. That folder isn't mounted in the recovery (/data is encrypted), so the output is silently empty.
  An empty OpenRecoveryScript counts as "success" in OrangeFox, so it simply reboots. Use `printf`/`echo`.
- **The updater-script stays a one-line comment.** OrangeFox treats a zip as a "ROM" when its updater-script uses
  `block_image_update` or `package_extract_file … boot.img`, or when the zip has `system.new.dat` + `vendor.new.dat`.
  After a ROM install it patches the boot image (AVB / dm-verity) and may patch vbmeta. Our installer is the shell
  `update-binary` only, so none of that runs.
- **Don't trust the recovery's own `/metadata` mount to be writable.** The installer tests it and uses its own mount
  when it isn't.
- **Restart reasons:** Android shows "Factory data reset" for a plain `reboot("recovery")`. The updater uses
  `"recovery-update"` for installs ("System update" screen) and LineageOS's `rebootCustom("recovery")` for visits.
- **Diagnostics:** while an update runs, the hook copies the recovery log, its own log and the installer trace to
  `/metadata/songateos/`. The installer also leaves `songateos_install.log` next to the zip on the microSD.

## Recovery packages (recovery switch)

Users switch between TWRP and OrangeFox from the update page; the HiBy R4 service fetches a **recovery package** from
the releases, checks it, and its root helper writes both recovery slots.

```
make_recovery_package.sh --kind twrp --img <TWRP recovery.img> --version 2026.11.16 --key <key folder> --out <dir>
make_recovery_package.sh --kind orangefox --img <OrangeFox recovery.img> --version 2026.11.16 --key <key folder> --out <dir>
```

- Output: `songateos-recovery-<kind>-<version>.zip`, signed whole-file with the release key like the updates.
  Attach it to a release, with its line in `SHA256SUMS.txt`. The service uses the newest package of each kind across
  the releases.
- Contents: `recovery.img`, its `sha256`, and `recovery.prop` (kind, name, title, version, update-hook version).
- The same zip can be flashed by hand from TWRP or OrangeFox (`recovery-update-binary`). It checks it's a standard R4
  and that the image is intact, writes `recovery_a` and `recovery_b`, and reads them back.
- `--hook`: the SongateOS update hook version inside that recovery (`runatboot.sh` `HOOK_VERSION`, 6 today).
