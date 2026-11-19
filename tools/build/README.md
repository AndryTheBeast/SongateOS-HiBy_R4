# Building SongateOS

Linux, or WSL2 with Ubuntu 22.04. About 300 GB free and 32 GB of RAM or more. The scripts use plain default paths
(`~/lineage21`, `~/twrp`, `~/fox`); set `TREE`, `TWRP_TREE` and `FOX_TREE` if yours live elsewhere.

| Step | Script | Takes |
|---|---|---|
| 1. Get and prepare the LineageOS 21 TrebleDroid source | `prepare_tree.sh` | hours (mostly the download) |
| 2. Build the system image with the SongateOS layer and Songate | `build_system.sh <Songate.apk>` | about 50 min after the first build |
| 3. Build TWRP and OrangeFox from the device tree | `build_recoveries.sh` | about 10 min each |
| 4. Sign the release: the OS zip, the recovery packages, checksums | `make_release.sh` | a few minutes |

Songate's APK comes from [Songate's releases](https://github.com/AndryTheBeast/Songate/releases); it isn't stored here.

The release key is yours to make (`ota/make_release_key.sh`) and must never be committed. Zips signed with another key
still install by hand from a recovery, but the player's updater only accepts zips signed with the key whose
certificate is in `ota/keys/`.

`tools/dev/` holds two small test tools: `SysProbe.java` calls the HiBy service's API from a shell (as the system user),
and `BpTest.java` tests the Binder calls behind it.
