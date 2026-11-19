#!/bin/bash
# Build a signed SongateOS recovery package (TWRP or OrangeFox) for the updater's recovery switch.
#
#   make_recovery_package.sh --kind twrp|orangefox --img recovery.img --version 2026.11.16 --key <dir> --out <dir>
#                            [--hook 2] [--tree ~/lineage21]
#
# Output: <out>/songateos-recovery-<kind>-<version>.zip, signed whole-file (signapk -w) with songateos_release.pk8
# like the SongateOS updates. Attach it to a GitHub release (with its line in SHA256SUMS.txt): the HiBy R4 service
# finds the newest package of each kind across the releases, checks the signature against the release certificate,
# and its root helper writes recovery.img to recovery_a and recovery_b. The same zip can be flashed by hand from TWRP
# or OrangeFox (recovery-update-binary).
#   --hook  the SongateOS update hook version inside that recovery (runatboot.sh HOOK_VERSION)
#   --key   folder with songateos_release.pk8 + .x509.pem; NEVER inside this repository
#   --foxzip (OrangeFox only) OrangeFox's own build zip: its sdcard/Fox/FoxFiles (AromaFM, Magisk add-on) go into the
#           package as FoxFiles/, and the installer / the updater copy them to <microSD>/Fox/FoxFiles, where this
#           OrangeFox build looks for them (FOX_MISCELLANEOUS_ROOT_DIRECTORY=/external_sd)
#   --tree  Android source tree with prebuilts/sdk/tools/lib/signapk.jar (default ~/lineage21)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
KIND=""; IMG=""; VER=""; KEY=""; OUT=""; HOOK=2; TREE=$HOME/lineage21; FOXZIP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --kind) KIND=$2; shift 2 ;; --img) IMG=$2; shift 2 ;; --version) VER=$2; shift 2 ;;
    --key) KEY=$2; shift 2 ;; --out) OUT=$2; shift 2 ;; --hook) HOOK=$2; shift 2 ;; --tree) TREE=$2; shift 2 ;;
    --foxzip) FOXZIP=$2; shift 2 ;;
    *) echo "unknown option $1"; exit 1 ;;
  esac
done
case "$KIND" in
  twrp)      NAME=TWRP;      TITLE="TWRP 3.7" ;;
  orangefox) NAME=OrangeFox; TITLE="OrangeFox R12.0" ;;
  *) echo "--kind twrp or orangefox"; exit 1 ;;
esac
echo "$VER" | grep -qE '^[0-9]+(\.[0-9]+)+$' || { echo "--version like 2026.11.16"; exit 1; }
echo "$HOOK" | grep -qE '^[0-9]+$' || { echo "--hook: a number"; exit 1; }
[ -f "$IMG" ] || { echo "--img: no file"; exit 1; }
[ "$(head -c 8 "$IMG")" = "ANDROID!" ] || { echo "--img: not a recovery image"; exit 1; }
[ "$(stat -c %s "$IMG")" = 100663296 ] || { echo "--img: not 96 MB (the R4's recovery partition size)"; exit 1; }
[ -f "$KEY/songateos_release.pk8" ] && [ -f "$KEY/songateos_release.x509.pem" ] || { echo "--key: no songateos_release.pk8/.x509.pem"; exit 1; }
cmp -s "$KEY/songateos_release.x509.pem" "$HERE/keys/songateos_release.x509.pem" || { echo "--key does not match system/ota/keys"; exit 1; }
SIGNAPK=$TREE/prebuilts/sdk/tools/lib/signapk.jar
[ -f "$SIGNAPK" ] || { echo "signapk.jar not found in $TREE"; exit 1; }
JNI=$(dirname "$(find "$TREE/prebuilts/sdk/tools" -name 'libconscrypt_openjdk_jni.so' -path '*linux*' | head -1)")
mkdir -p "$OUT"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/z/META-INF/com/google/android"
cp "$HERE/recovery-update-binary" "$W/z/META-INF/com/google/android/update-binary"
echo "# SongateOS recovery package: see update-binary" > "$W/z/META-INF/com/google/android/updater-script"
cp "$IMG" "$W/z/recovery.img"
SHA=$(sha256sum "$IMG" | cut -d' ' -f1)
echo "$SHA  recovery.img" > "$W/z/recovery.img.sha256"
FOXFILES=0
if [ "$KIND" = orangefox ]; then
  [ -f "$FOXZIP" ] || { echo "--foxzip: OrangeFox's build zip is needed (for its FoxFiles)"; exit 1; }
  mkdir -p "$W/fox" && unzip -q "$FOXZIP" 'sdcard/Fox/FoxFiles/*' -d "$W/fox"
  [ -d "$W/fox/sdcard/Fox/FoxFiles" ] || { echo "--foxzip: no sdcard/Fox/FoxFiles inside"; exit 1; }
  mv "$W/fox/sdcard/Fox/FoxFiles" "$W/z/FoxFiles"; FOXFILES=1
  # AromaFM can't draw on the R4 (its only framebuffer is HiBy's hidden "Virtual FB"; the screen is DRM-only):
  # left out, so OrangeFox doesn't offer it (it only shows it when the file is there)
  rm -rf "$W/z/FoxFiles/AromaFM"
fi
{ echo "kind=$KIND"; echo "name=$NAME"; echo "title=$TITLE"; echo "version=$VER"; echo "hook=$HOOK"; echo "sha256=$SHA"; \
  echo "foxfiles=$FOXFILES"; } \
  > "$W/z/recovery.prop"
(cd "$W/z" && TZ=UTC touch -t 202601010000 META-INF/com/google/android/* recovery.* && \
  zip -q -X -r "$W/unsigned.zip" META-INF recovery.img recovery.img.sha256 recovery.prop && \
  { [ "$FOXFILES" = 0 ] || zip -q -X -r "$W/unsigned.zip" FoxFiles; })
NAME_OUT=songateos-recovery-$KIND-$VER.zip
java -Xmx2g -Djava.library.path="$JNI" -jar "$SIGNAPK" -w \
  "$KEY/songateos_release.x509.pem" "$KEY/songateos_release.pk8" "$W/unsigned.zip" "$OUT/$NAME_OUT"
(cd "$OUT" && sha256sum "$NAME_OUT")
echo "image sha256 $SHA"
