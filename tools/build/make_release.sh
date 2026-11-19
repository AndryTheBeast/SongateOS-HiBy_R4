#!/bin/bash
# Make a release's assets: the signed SongateOS zip (in 512 MB pieces) and the two signed recovery packages, with
# SHA256SUMS.txt. The private key stays OUTSIDE this repository (ota/make_release_key.sh made it).
#   bash tools/build/make_release.sh --version X --key <dir> --out <dir> --system <system.img>
#        [--twrp <recovery.img>] [--fox <recovery.img> --foxzip <OrangeFox's build zip>] [--recovery-version V]
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
while [ $# -gt 0 ]; do
  case "$1" in
    --version) V=$2 ;; --key) KEY=$2 ;; --out) OUT=$2 ;; --system) SYS=$2 ;; --twrp) TW=$2 ;; --fox) FX=$2 ;;
    --foxzip) FZ=$2 ;; --recovery-version) RV=$2 ;; *) echo "unknown option $1"; exit 1 ;;
  esac
  shift 2
done
[ -n "$V" ] && [ -n "$KEY" ] && [ -n "$OUT" ] && [ -f "$SYS" ] || { echo "see the usage at the top"; exit 1; }
RV=${RV:-$(date +%Y.%m.%d)}
HOOK=$(grep -m1 '^HOOK_VERSION=' "$REPO/device/hiby/R4/recovery/root/system/bin/runatboot.sh" | cut -d= -f2)
bash "$REPO/ota/make_ota_zip.sh" --version "$V" --key "$KEY" --out "$OUT" --system "$SYS"
[ -n "$TW" ] && bash "$REPO/ota/make_recovery_package.sh" --kind twrp --img "$TW" --version "$RV" --hook "$HOOK" \
  --key "$KEY" --out "$OUT"
[ -n "$FX" ] && bash "$REPO/ota/make_recovery_package.sh" --kind orangefox --img "$FX" --foxzip "$FZ" --version "$RV" \
  --hook "$HOOK" --key "$KEY" --out "$OUT"
(cd "$OUT" && sha256sum ./*.zip | sed 's# \./# #' > SHA256SUMS.txt && cat SHA256SUMS.txt)
