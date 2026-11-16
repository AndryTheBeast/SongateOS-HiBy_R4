#!/system/bin/sh
# SongateOS OTA hook for the HiBy R4 recoveries (TWRP and OrangeFox).
#
# Both recoveries run /system/bin/runatboot.sh at startup, before they look for an OpenRecoveryScript
# (TWRP: twrp.cpp, OrangeFox: RunFoxScript). That is the only reason this file has this name.
#
# 1. Every recovery boot (only when /metadata/songateos exists, i.e. the SongateOS updater is in use):
#    write /metadata/songateos/recovery.info (which recovery, hook version, sha256 of this recovery partition),
#    so Android can check that recovery_a is a SongateOS recovery with this hook before it offers to install.
#    If /metadata/songateos/return.cmd exists ("Check it" in Settings › SongateOS update), delete it and reboot
#    straight back to the system.
# 2. If /metadata/songateos/ota.cmd exists (written by the SongateOS updater, see OTA_DESIGN.md):
#    - read it and DELETE it at once (a failing install can never loop),
#    - check the zip path and its SHA-256 on the microSD,
#    - on success: write /tmp/openrecoveryscript (install with signature check against the SongateOS key only,
#      then reboot); on failure: write ota.result and reboot to the system unchanged.
# Nothing here writes to any partition except the small files in /metadata/songateos.
HOOK_VERSION=2
# mksh's default TMPDIR is /data/local, which isn't mounted in the recovery
export TMPDIR=/tmp
LOG=/tmp/songateos_ota.log
M=""            # where /metadata is mounted
MOUNTED_META=0
SD=""           # where the microSD is mounted
MOUNTED_SD=0

log() { echo "songateos_ota: $*"; echo "$(date '+%H:%M:%S') $*" >> $LOG; }

cleanup() {
  [ "$MOUNTED_SD" = 1 ] && umount /tmp/sgos_sd 2>/dev/null
  [ "$MOUNTED_META" = 1 ] && umount /tmp/sgos_meta 2>/dev/null
}

# While an update runs: keep copying the recovery's own log, this hook's log and the installer's trace (all in
# RAM, lost at the reboot) into /metadata/songateos/ every 2 s for up to 4 minutes, through an own rw mount.
# Detached with no stdio, so the recovery doesn't wait for it.
start_log_saver() {
  mkdir -p /tmp/sgos_logmnt
  mount -t ext4 -o rw,noatime /dev/block/bootdevice/by-name/metadata /tmp/sgos_logmnt 2>/dev/null || { log "log saver: no mount"; return; }
  setsid sh -c 'L=/tmp/sgos_logmnt/songateos; i=0
    while [ $i -lt 120 ]; do
      cp -f /tmp/recovery.log $L/last_recovery.log 2>/dev/null
      cp -f /tmp/songateos_ota.log $L/hook.log 2>/dev/null
      cp -f /tmp/songateos_install.log $L/install_tmp.log 2>/dev/null
      sync; sleep 2; i=$((i + 1))
    done' </dev/null >/dev/null 2>&1 &
  log "log saver started"
}

result() {  # $1 status, $2 detail
  [ -n "$M" ] || return
  { echo "status=$1"; echo "detail=$2"; echo "version=$VERSION"; echo "time=$(date +%s)"; } > "$M/songateos/ota.result.tmp"
  mv -f "$M/songateos/ota.result.tmp" "$M/songateos/ota.result"; sync
}

# Not in fastbootd mode (TWRP runs this script there too). BCB command field = first 32 bytes of misc.
BCB=$(dd if=/dev/block/bootdevice/by-name/misc bs=32 count=1 2>/dev/null | tr -d '\000')
case "$BCB" in boot-fastboot*) log "fastbootd mode, nothing to do"; exit 0 ;; esac

# /metadata (ext4, not encrypted)
if mountpoint -q /metadata 2>/dev/null; then
  M=/metadata
else
  mkdir -p /tmp/sgos_meta
  if mount -t ext4 -o rw,noatime,nosuid,nodev /dev/block/bootdevice/by-name/metadata /tmp/sgos_meta; then
    M=/tmp/sgos_meta; MOUNTED_META=1
  else
    log "cannot mount /metadata"; exit 0
  fi
fi
trap cleanup EXIT
[ -d "$M/songateos" ] || { log "no $M/songateos, updater not in use"; exit 0; }

# 1. recovery.info
SLOT=$(getprop ro.boot.slot_suffix)
NAME=TWRP; [ -n "$(getprop ro.build.fox_id)$(getprop ro.orangefox.version)" ] || [ -d /FFiles ] && NAME=OrangeFox
RSHA=$(sha256sum /dev/block/bootdevice/by-name/recovery$SLOT 2>/dev/null | cut -d' ' -f1)
{ echo "name=$NAME"; echo "hook=$HOOK_VERSION"; echo "slot=$SLOT"; echo "sha256=$RSHA"
  echo "twrp=$(getprop ro.twrp.version)"; echo "time=$(date +%s)"; } > "$M/songateos/recovery.info.tmp"
mv -f "$M/songateos/recovery.info.tmp" "$M/songateos/recovery.info"
log "recovery.info: $NAME hook $HOOK_VERSION slot $SLOT"

# 2. pending update?
CMD="$M/songateos/ota.cmd"
# "Check the recovery" from Settings: recovery.info is written above, go straight back to Android
if [ -f "$M/songateos/return.cmd" ]; then
  rm -f "$M/songateos/return.cmd"; sync
  if [ ! -f "$CMD" ]; then
    log "recovery check visit: back to the system"
    { echo "print SongateOS: recovery checked, restarting to the system"; echo "reboot system"; } > /tmp/openrecoveryscript
    exit 0
  fi
fi
[ -f "$CMD" ] || exit 0
VERSION=""; ZIP=""; SHA=""
while IFS='=' read -r k v; do
  case "$k" in
    version) VERSION=$v ;;
    zip) ZIP=$v ;;
    sha256) SHA=$v ;;
  esac
done < "$CMD"
rm -f "$CMD"; sync          # before anything else: never twice
log "update request: version '$VERSION' zip '$ZIP'"
start_log_saver



fail() { log "FAILED: $1"; result fail "$1"
  { echo "print SongateOS update failed: $1"; echo "print Rebooting to the system, nothing was changed."
    echo "reboot system"; } > /tmp/openrecoveryscript
  exit 0; }

# zip = path relative to the microSD root; plain characters only, no "..", must end in .zip
case "$ZIP" in
  ""|/*|*..*|*[!A-Za-z0-9._/-]*) fail "bad zip path" ;;
  *.zip) ;;
  *) fail "bad zip path" ;;
esac
echo "$SHA" | grep -qE '^[0-9a-f]{64}$' || fail "bad checksum in request"
echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$' || fail "bad version in request"

# microSD (exFAT; the recoveries also know vfat)
if mountpoint -q /external_sd 2>/dev/null; then
  SD=/external_sd
else
  mkdir -p /tmp/sgos_sd
  if mount -t exfat -o ro /dev/block/mmcblk1p1 /tmp/sgos_sd 2>/dev/null || mount -o ro /dev/block/mmcblk1p1 /tmp/sgos_sd 2>/dev/null; then
    SD=/tmp/sgos_sd; MOUNTED_SD=1
  else
    fail "microSD not found"
  fi
fi
[ -f "$SD/$ZIP" ] || fail "update file not on the microSD"
log "checking SHA-256 of $ZIP ..."
HAVE=$(sha256sum "$SD/$ZIP" | cut -d' ' -f1)
[ "$HAVE" = "$SHA" ] || fail "checksum mismatch"
log "checksum OK"
[ "$MOUNTED_SD" = 1 ] && { umount /tmp/sgos_sd; MOUNTED_SD=0; }

# Signature check against the SongateOS release key only (this boot only: the ramdisk is rebuilt every boot).
KEYS=/system/etc/security/songateos_otacerts.zip
[ -f "$KEYS" ] || fail "recovery has no SongateOS key"
cp -f /system/etc/security/otacerts.zip /tmp/otacerts.orig.zip 2>/dev/null
cp -f "$KEYS" /system/etc/security/otacerts.zip || fail "cannot load the SongateOS key"

result started "installing"
# No here-document: Android's mksh builds those in its TMPDIR, which defaults to /data/local - not mounted in
# the recovery (encrypted), so "cat <<EOF" silently wrote an EMPTY script (OrangeFox then ran nothing and rebooted).
printf '%s\n' "print SongateOS update $VERSION" "mount /external_sd" "set tw_signed_zip_verify 1" \
  "install /external_sd/$ZIP" "set tw_signed_zip_verify 0" "reboot system" > /tmp/openrecoveryscript
if ! grep -q "^install /external_sd/$ZIP\$" /tmp/openrecoveryscript; then
  cp -f /tmp/otacerts.orig.zip /system/etc/security/otacerts.zip 2>/dev/null
  fail "could not write the recovery script"
fi
log "OpenRecoveryScript written: $(tr '\n' ';' < /tmp/openrecoveryscript)"
exit 0
