#!/bin/bash
# Build and sign a SongateOS update zip (run in WSL/Linux).
#
#   make_ota_zip.sh --version 1.0.0 --key <private key folder> --out <folder> \
#                   (--system <system.img> | --test) [--imagefv <new.img> --imagefv-base <stock.img> [--imagefv-old <a,b>]]
#
#   --system   the SongateOS system image (raw or sparse ext4; sparse is converted)
#   --test     dry-run zip: the installer runs every check and writes nothing (safe end-to-end OTA test)
#   --imagefv  optional boot logo (the SongateOS imagefv, built by branding's build_imagefv.py from HiBy's stock one).
#              The zip never carries the image itself (it is HiBy's signed container): only imagefv.patch = the
#              bytes where it differs from --imagefv-base (HiBy's stock logo) or any --imagefv-old (earlier SongateOS
#              logos), and their map. The installer applies it only over one of those exact logos (sha256 checked
#              before and after); any other logo is left as it is.
#   --tree     Android source tree with prebuilts/sdk/tools/lib/signapk.jar (default ~/lineage21)
#
# Output in <folder>: songateos-<version>-R4.zip (or ...-R4-test.zip) and SHA256SUMS.txt.
# The zip is signed whole-file (signapk -w) with songateos_release.pk8: TWRP/OrangeFox check it against
# songateos_otacerts.zip, Android's updater with RecoverySystem.verifyPackage against the same certificate.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
VER=""; KEY=""; OUT=""; SYS=""; TEST=0; FV=""; FV_BASE=""; FV_OLD=""; TREE=$HOME/lineage21
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VER=$2; shift 2 ;;
    --key) KEY=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --system) SYS=$2; shift 2 ;;
    --test) TEST=1; shift ;;
    --imagefv) FV=$2; shift 2 ;;
    --imagefv-base) FV_BASE=$2; shift 2 ;;
    --imagefv-old) FV_OLD=$2; shift 2 ;;
    --tree) TREE=$2; shift 2 ;;
    *) echo "unknown option $1"; exit 1 ;;
  esac
done
echo "$VER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$' || { echo "--version X.Y.Z[-tag] needed"; exit 1; }
[ -f "$KEY/songateos_release.pk8" ] && [ -f "$KEY/songateos_release.x509.pem" ] || { echo "--key: no songateos_release.pk8/.x509.pem in '$KEY'"; exit 1; }
cmp -s "$KEY/songateos_release.x509.pem" "$HERE/keys/songateos_release.x509.pem" || { echo "--key does not match system/ota/keys/songateos_release.x509.pem"; exit 1; }
[ -n "$OUT" ] || { echo "--out needed"; exit 1; }
[ "$TEST" = 1 ] || [ -f "$SYS" ] || { echo "--system <img> or --test needed"; exit 1; }
[ -z "$FV" ] || [ -f "$FV_BASE" ] || { echo "--imagefv needs --imagefv-base <HiBy's stock imagefv>"; exit 1; }
SIGNAPK=$TREE/prebuilts/sdk/tools/lib/signapk.jar
JNI=$TREE/prebuilts/sdk/tools/linux/lib64
[ -f "$SIGNAPK" ] || { echo "no $SIGNAPK"; exit 1; }

mkdir -p "$OUT"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/z/META-INF/com/google/android" "$W/z/songateos"
cp "$HERE/update-binary" "$W/z/META-INF/com/google/android/update-binary"
cp "$HERE/updater-script" "$W/z/META-INF/com/google/android/updater-script"

KIND=full; NAME=songateos-$VER-R4.zip
{ echo "device=R4"; echo "version=$VER"; echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$W/z/songateos/ota.prop"
if [ "$TEST" = 1 ]; then
  KIND=test; NAME=songateos-$VER-R4-test.zip
else
  IMG=$SYS
  if [ "$(head -c4 "$SYS" | od -An -tx1 | tr -d ' \n')" = "3aff26ed" ]; then
    S2I=$(command -v simg2img || echo "$TREE/out/host/linux-x86/bin/simg2img")
    echo "sparse image -> raw ($S2I)"
    "$S2I" "$SYS" "$W/system.img"; IMG=$W/system.img
  fi
  [ "$(od -An -tx1 -j1080 -N2 "$IMG" | tr -d ' \n')" = "53ef" ] || { echo "system image is not ext4"; exit 1; }
  echo "system_size=$(stat -c %s "$IMG")" >> "$W/z/songateos/ota.prop"
  echo "system_sha256=$(sha256sum "$IMG" | cut -d' ' -f1)" >> "$W/z/songateos/ota.prop"
  # in 512 MB pieces (system.img.00, .01, ...): the recoveries' unzip holds a whole entry in RAM when it writes to a
  # pipe, so one 2.97 GB entry needed the microSD to unpack (2026-11-19: pieces need no card, e.g. adb sideload)
  (cd "$W/z" && split -b 536870912 -d -a 2 "$(readlink -f "$IMG")" system.img.)
  echo "system_parts=$(ls "$W/z" | grep -c '^system\.img\.[0-9][0-9]$')" >> "$W/z/songateos/ota.prop"
  echo "system_part_size=536870912" >> "$W/z/songateos/ota.prop"
fi
echo "kind=$KIND" >> "$W/z/songateos/ota.prop"
if [ -n "$FV" ]; then
  # patch = the new logo's bytes wherever it differs from the stock logo or an older SongateOS logo (so it turns any
  # of them into the new one); ranges merged when less than 64 bytes apart
  OLDS=(); [ -z "$FV_OLD" ] || IFS=',' read -ra OLDS <<< "$FV_OLD"
  python3 - "$FV" "$W/z/imagefv.patch" "$W/fvmap" "$FV_BASE" "${OLDS[@]}" <<'PY'
import sys
new = open(sys.argv[1], "rb").read()
bases = [open(p, "rb").read() for p in sys.argv[4:]]
assert all(len(b) == len(new) for b in bases), "imagefv sizes differ"
diff = [any(b[i] != new[i] for b in bases) for i in range(len(new))]
runs, i, n = [], 0, len(new)
while i < n:
    if diff[i]:
        j = i
        while j < n and (diff[j] or any(diff[j:j + 64])):
            j += 1
        runs.append((i, j - i)); i = j
    else:
        i += 1
with open(sys.argv[2], "wb") as f:
    for o, l in runs:
        f.write(new[o:o + l])
open(sys.argv[3], "w").write(",".join("%d:%d" % r for r in runs))
print("imagefv patch: %d ranges, %d bytes" % (len(runs), sum(l for _, l in runs)))
PY
  FROM=$(sha256sum "$FV_BASE" | cut -d' ' -f1)
  for o in "${OLDS[@]}"; do FROM="$FROM,$(sha256sum "$o" | cut -d' ' -f1)"; done
  echo "imagefv_sha256=$(sha256sum "$FV" | cut -d' ' -f1)" >> "$W/z/songateos/ota.prop"
  echo "imagefv_size=$(stat -c %s "$FV")" >> "$W/z/songateos/ota.prop"
  echo "imagefv_from=$FROM" >> "$W/z/songateos/ota.prop"
  echo "imagefv_map=$(cat "$W/fvmap")" >> "$W/z/songateos/ota.prop"
fi
echo "--- ota.prop"; cat "$W/z/songateos/ota.prop"

# small files first, the big image last
(cd "$W/z" && zip -q -X -r "$W/unsigned.zip" META-INF songateos && \
  { ! ls system.img.[0-9][0-9] >/dev/null 2>&1 || zip -q -X "$W/unsigned.zip" system.img.[0-9][0-9]; } && \
  { [ ! -e imagefv.patch ] || zip -q -X "$W/unsigned.zip" imagefv.patch; })
echo "signing ($(stat -c %s "$W/unsigned.zip") bytes)..."
java -Xmx2g -Djava.library.path="$JNI" -jar "$SIGNAPK" -w \
  "$KEY/songateos_release.x509.pem" "$KEY/songateos_release.pk8" "$W/unsigned.zip" "$OUT/$NAME"
python3 "$HERE/verify_ota_zip.py" "$OUT/$NAME" "$HERE/keys/songateos_release.x509.pem"
(cd "$OUT" && { grep -v " $NAME\$" SHA256SUMS.txt 2>/dev/null || true; sha256sum "$NAME"; } > SHA256SUMS.new && mv SHA256SUMS.new SHA256SUMS.txt)
echo "--- $OUT"; ls -l "$OUT/$NAME"; cat "$OUT/SHA256SUMS.txt"
