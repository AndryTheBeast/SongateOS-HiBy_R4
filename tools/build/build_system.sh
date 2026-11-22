#!/bin/bash
# Build SongateOS's system image: the prepared tree (prepare_tree.sh) + this repository's vendor/songateos +
# Songate's APK (from Songate's releases: https://github.com/AndryTheBeast/Songate/releases).
#   TREE=~/lineage21 bash tools/build/build_system.sh <Songate.apk> [jobs]
# Output: $TREE/out/target/product/tdgsi_arm64_ab/system.img (then tools/build/make_release.sh)
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
TREE=${TREE:-$HOME/lineage21}; APK=$1; J=${2:-6}
[ -f "$APK" ] || { echo "usage: build_system.sh <Songate.apk> [jobs]"; exit 1; }
echo "=== layer: $REPO/vendor/songateos -> $TREE/vendor/songateos"
mkdir -p "$TREE/vendor/songateos"
rsync -a --delete --exclude 'prebuilt/Songate/Songate.apk' "$REPO/vendor/songateos/" "$TREE/vendor/songateos/"
cp "$APK" "$TREE/vendor/songateos/prebuilt/Songate/Songate.apk"
find "$TREE/vendor/songateos" -type f \( -name '*.sh' -o -name '*.mk' -o -name '*.bp' -o -name '*.rc' -o -name '*.xml' \
  -o -name '*.te' -o -name '*_contexts' \) -exec sed -i 's/\r$//' {} +
cd "$TREE"
echo "=== source changes (idempotent)"
P=vendor/songateos/patches
python3 $P/apply_av.py frameworks/av
python3 $P/apply_settings.py packages/apps/Settings
for p in fonts charger lineage_props hold board bt_aap bootwait netstats telecom wired adb; do python3 $P/apply_$p.py .; done
export LC_ALL=C
set +e; source build/envsetup.sh >/dev/null; set -e
lunch songateos_r4-ap2a-userdebug
export LINEAGE_BUILD=songateos_r4   # LineageOS's board config needs it, as for lineage_* products
export BUILD_USERNAME=songateos BUILD_HOSTNAME=songateos   # a neutral build stamp (ro.build.user, ro.build.host)
# secure adb (2.1.0): no WITH_ADB_INSECURE; apply_adb.py removes the GSI base's ro.adb.secure=0
m -j$J systemimage
ls -l "$OUT/system.img"; sha256sum "$OUT/system.img"
