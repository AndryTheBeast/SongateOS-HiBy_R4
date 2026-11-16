#!/system/bin/sh
# SongateOS for the HiBy R4: make the patched audio libraries from YOUR OWN device's files.
#
# No HiBy or Android binaries are shipped in this repository. This script reads the original files on the R4,
# checks that they are exactly the versions the patches were made for (SHA-256), changes a few instructions,
# checks the result (SHA-256) and writes the patched copies into the Magisk module. Run it as root on the R4:
#
#   su -c sh patch_libs.sh /data/adb/modules/hiby_r4_gsi_fix
#
# Nothing on the system is changed: the module mounts the copies at boot (post-fs-data.sh), and only if the
# originals still match. Remove the copies (or the module) and reboot to go back.
#
# Patches (offsets are file offsets, values are the new 4-byte instructions, little endian):
#  1. HiBy's audio HAL /vendor/lib/hw/audio.primary.trinket.so (32-bit ARM)
#     0x4f234  beq -> nop   a bare "connect=8" from Android selects HiBy's Headphone/analog output, not S/PDIF
#     0x4db7c  bne -> b     set_master_volume never writes Android's master volume x100 into the DAC volume
#     0x4dbd4  bne -> b     (same, right channel). The DAC volume is set by the HiBy R4 service instead.
#  2. Android 14 (LineageOS 21 GSI) /system/lib64/libaudiopolicymanagerdefault.so
#     0x7ff54, 0x80610  jump past the "is not usb device" checks in get/setPreferredMixerAttributes:
#     bit-perfect (Android 14 mixer attributes) is offered for the wired outputs too, not only USB
#  3. /system/lib64/libaudiohal@6.0.so
#     0x14224, 0x1422c, 0x14230  the (unused here) SPATIALIZER flag rewrite before the HAL call now turns
#     BIT_PERFECT into DIRECT: HiBy's Android 11 HAL has no BIT_PERFECT flag and opens its direct path
set -e
MOD=${1:-/data/adb/modules/hiby_r4_gsi_fix}
[ -d "$MOD" ] || { echo "module folder $MOD not found"; exit 1; }

sha() { sha256sum "$1" | cut -d' ' -f1; }

# patch NAME SOURCE TARGET SHA_IN SHA_OUT "OFFSET BYTES" ...   (BYTES = 8 hex digits, file order)
patch() {
  name=$1 src=$2 dst=$3 in=$4 out=$5; shift 5
  if [ -f "$dst" ] && [ "$(sha "$dst")" = "$out" ]; then echo "$name: already patched"; return 0; fi
  s=$(sha "$src")
  if [ "$s" != "$in" ]; then
    echo "$name: SKIPPED - $src is not the expected version ($s). Nothing written."
    return 0
  fi
  mkdir -p "${dst%/*}"; cp "$src" "$dst.tmp"
  for p in "$@"; do
    off=${p% *}; hex=${p#* }
    printf "\\x${hex%??????}\\x$(echo $hex | cut -c3-4)\\x$(echo $hex | cut -c5-6)\\x${hex#??????}" |
      dd of="$dst.tmp" bs=1 seek=$((off)) conv=notrunc 2>/dev/null
  done
  if [ "$(sha "$dst.tmp")" != "$out" ]; then rm -f "$dst.tmp"; echo "$name: FAILED (result checksum)"; return 1; fi
  mv "$dst.tmp" "$dst"; chmod 644 "$dst"
  echo "$name: patched -> $dst"
}

# 1. HiBy's audio HAL. If the module already mounts its copy over /vendor, the running file is not the original:
#    then put HiBy's original (from your EDL backup or a vendor image) in $MOD/orig/audio.primary.trinket.so.
H=/vendor/lib/hw/audio.primary.trinket.so
if [ "$(sha $H)" != "308be9c24dd2cc884c242fce1a4c7b1cb884998a69dfe480298b4ae8f62914b4" ] && [ -f "$MOD/orig/audio.primary.trinket.so" ]; then
  H=$MOD/orig/audio.primary.trinket.so
fi
patch "HiBy audio HAL" $H "$MOD/system/vendor/lib/hw/audio.primary.trinket.so" \
  308be9c24dd2cc884c242fce1a4c7b1cb884998a69dfe480298b4ae8f62914b4 \
  47c367e14a6198e378ee3b00af30a3cff607eff82cf9344c61ca40e227720c15 \
  "0x4f234 00f020e3" "0x4db7c 030000ea" "0x4dbd4 030000ea"
[ -f "$MOD/system/vendor/lib/hw/audio.primary.trinket.so" ] && chcon u:object_r:vendor_file:s0 "$MOD/system/vendor/lib/hw/audio.primary.trinket.so"

# 2 + 3. Android's audio policy manager and HAL client (bit-perfect on the wired outputs)
patch "audio policy manager" /system/lib64/libaudiopolicymanagerdefault.so "$MOD/bitperfect/libaudiopolicymanagerdefault.so" \
  c27eda20ad66b76d3a32107592b48f912bccd8bf298b623d84e19a3d06e570ba \
  5dc062e9a595e605ac2e85ee84e8ac25e64f107a8485487294c38b86a665e0ab \
  "0x7ff54 44000014" "0x80610 4f000014"
patch "audio HAL client 6.0" /system/lib64/libaudiohal@6.0.so "$MOD/bitperfect/libaudiohal@6.0.so" \
  0fa791a3ab4db2dddf0fcc2937775a5c9755b8cea81e15541821016d394ed5b3 \
  e9498256583158ff0c8cbd49c1788000ddf410e4efdcda5addc140d1eb134494 \
  "0x14224 e97a0b12" "0x1422c 29010032" "0x14230 ff020c72"
[ -d "$MOD/bitperfect" ] && chcon u:object_r:system_lib_file:s0 "$MOD"/bitperfect/*.so 2>/dev/null
echo "Done. Reboot to apply."
