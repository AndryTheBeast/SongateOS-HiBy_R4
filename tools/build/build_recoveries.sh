#!/bin/bash
# Build TWRP (twrp-12.1) and OrangeFox (fox_12.1) for the HiBy R4 from this repository's device tree.
#   TWRP_TREE=~/twrp FOX_TREE=~/fox bash tools/build/build_recoveries.sh
# The trees: TWRP's minimal manifest
#   repo init -u https://github.com/minimal-manifest-twrp/platform_manifest_twrp_aosp.git -b twrp-12.1
# and for OrangeFox the same tree with bootable/recovery and vendor/recovery from https://gitlab.com/OrangeFox
# (branch fox_12.1). Output: recovery.img in each tree's out/target/product/R4/.
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
build() {   # $1 = tree
  mkdir -p "$1/device/hiby"; rsync -a --delete "$REPO/device/hiby/R4/" "$1/device/hiby/R4/"
  find "$1/device/hiby/R4" -type f \( -name '*.sh' -o -name '*.mk' -o -name '*.rc' \) -exec sed -i 's/\r$//' {} +
  (
    cd "$1"
    # FOX_DELETE_AROMAFM: OrangeFox reads it from the environment. AromaFM can't draw on the R4 (no usable framebuffer).
    export ALLOW_MISSING_DEPENDENCIES=true LC_ALL=C FOX_BUILD_DEVICE=R4 FOX_DELETE_AROMAFM=1
    set +e; source build/envsetup.sh >/dev/null; set -e
    lunch twrp_R4-eng >/dev/null
    [ "$(get_build_var TARGET_DEVICE)" = R4 ] || { echo "lunch did not select the R4"; exit 1; }
    mka -j"$(nproc)" adbd recoveryimage
  )
  ls -l "$1/out/target/product/R4/recovery.img"; sha256sum "$1/out/target/product/R4/recovery.img"
}
T=${TWRP_TREE:-$HOME/twrp}; [ -d "$T" ] && { echo "=== TWRP"; build "$T"; }
F=${FOX_TREE:-$HOME/fox}; [ -d "$F" ] && { echo "=== OrangeFox"; build "$F"; }
