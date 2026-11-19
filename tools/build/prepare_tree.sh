#!/bin/bash
# Download and prepare the LineageOS 21 TrebleDroid source (lineage_build_unified, branch lineage-21-td) the way its
# buildbot does before compiling: sync, repopick, the TrebleDroid patches, generate + TrebleApp. No compiling.
#   TREE=~/lineage21 bash tools/build/prepare_tree.sh        (safe to re-run: it resumes the sync)
set -e
TREE=${TREE:-$HOME/lineage21}; mkdir -p "$TREE"; cd "$TREE"
step() { echo; echo "=== $(date '+%F %T') $*"; }
if [ ! -d .repo ]; then
  step "repo init (LineageOS lineage-21.0, shallow, git-lfs)"
  repo init -u https://github.com/LineageOS/android.git -b lineage-21.0 --git-lfs --depth=1 </dev/null
fi
[ -d lineage_build_unified ] || git clone https://github.com/AndyCGYan/lineage_build_unified lineage_build_unified -b lineage-21-td
[ -d lineage_patches_unified ] || git clone https://github.com/AndyCGYan/lineage_patches_unified lineage_patches_unified -b lineage-21-td
mkdir -p .repo/local_manifests; cp lineage_build_unified/local_manifests_treble/*.xml .repo/local_manifests/
step "repo sync"
for try in 1 2 3; do
  repo sync -c --force-sync --no-clone-bundle --no-tags --optimized-fetch --retry-fetches=5 -j8 && break
  echo "sync attempt $try failed, retrying"; sleep 30
done
if [ ! -f .songateos_prepped ]; then
  step "repopick (the LineageOS Gerrit changes the buildbot uses)"
  set +e; source build/envsetup.sh >/dev/null 2>&1; set -e
  for c in 321337 321338 321339 368923; do repopick $c -r -f || echo "repopick $c FAILED"; done
  step "TrebleDroid patches"
  for g in patches_treble_prerequisite patches_treble_td patches_platform patches_treble; do
    bash ./lineage_build_unified/apply_patches.sh ./lineage_patches_unified/$g
  done
  step "TrebleDroid: generate + TrebleApp"
  (cd device/phh/treble && git clean -fdx && bash generate.sh lineage)
  (cd treble_app && bash build.sh release && cp TrebleApp.apk ../vendor/hardware_overlay/TrebleApp/app.apk)
  (cd vendor/hardware_overlay && git add TrebleApp/app.apk && git commit -q -m "[TEMP] Up TrebleApp" || true)
  touch .songateos_prepped
fi
step "done"
