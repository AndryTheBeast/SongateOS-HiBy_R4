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

**Android's signing keys.** Without your own, the image is signed with Android's public test keys (LineageOS's Trust
warns, and anyone could sign updates for it). Make a set with the source tree's `development/tools/make_key`, for
`releasekey platform shared media networkstack sdk_sandbox bluetooth nfc` (plus `testkey` as a copy of `releasekey`),
in a folder outside the tree, with a `keys.mk` holding
`PRODUCT_DEFAULT_DEV_CERTIFICATE := vendor/lineage-priv/keys/releasekey`, then link that folder as
`vendor/lineage-priv/keys` (LineageOS includes `keys.mk` from there). Keep the keys safe and never commit them:
an image signed with other keys can't be installed over one signed with these without wiping the data.

The update key is yours to make (`ota/make_release_key.sh`) and must never be committed. Zips signed with another key
still install by hand from a recovery, but the player's updater only accepts zips signed with the key whose
certificate is in `ota/keys/`.

`tools/dev/` holds two small test tools: `SysProbe.java` calls the HiBy service's API from a shell (as the system user),
and `BpTest.java` tests the Binder calls behind it.
