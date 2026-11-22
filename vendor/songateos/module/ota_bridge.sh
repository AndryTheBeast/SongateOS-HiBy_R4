#!/system/bin/sh
# SongateOS updates: root side of the updater (HiBy R4 service, com.andre.hibyr4sound.Updater).
# The service app (system uid) can't write /metadata or read the recovery partition, so:
#   at boot  - make /metadata/songateos (root, 0700); publish
#              sys.hibyr4.ota_recovery = ok:<name>:hook<N>  when recovery_<slot> is the recovery that last wrote
#                                                           /metadata/songateos/recovery.info (same sha256)
#                                        none | changed:<name> otherwise
#              sys.hibyr4.ota_result   = status|version|detail  from /metadata/songateos/ota.result (if any)
#   sys.hibyr4.ota_req = stage:<nonce> - validate the app's ota.cmd and copy it to /metadata/songateos/ota.cmd,
#                                        answer sys.hibyr4.ota_state = staged:<nonce> | error:<reason>
#                        unstage:<n>   - remove a staged command
#                        ack:<n>       - the app has shown ota.result: delete it
#                        recheck:<n>   - recompute sys.hibyr4.ota_recovery
#                        return:<n>    - "check the recovery" visit: touch /metadata/songateos/return.cmd, so the
#                                        recovery hook records itself and reboots straight back to Android
#                        rec:<n>       - recovery switch: the app has verified a signed SongateOS recovery package
#                                        and left recovery.img + rec.cmd (kind, name, version, hook, sha256) in its
#                                        device-protected files. Re-check the image (sha256, boot header, exact
#                                        partition size), write it to recovery_a and recovery_b, read both back,
#                                        record it in recovery.info. Answer rec_done:<n> | rec_error:<n>:<reason>
#                        magisk:<n>    - install / update Magisk from the checked APK the app left (see magisk_install)
#                                        mag_done:<n> | mag_error:<n>:<reason>
#                        magisk_remove:<n> - put the stock boot image back (mode=dry: only report its sha256)
#                                        mag_removed:<n> | mag_dry:<n>:<sha256> | mag_error:<n>:<reason>
#   at boot also: sys.hibyr4.magisk = installed Magisk version ("" = none)
# The recovery side is device/hiby/R4/recovery/root/system/bin/runatboot.sh (TWRP and OrangeFox).
LOG=/data/local/tmp/hiby_r4_fix.log
D=/metadata/songateos
APPCMD=/data/user_de/0/com.andre.hibyr4.service/files/ota.cmd
# the slot the player runs on (2.2: an R4 may run on slot a or b after HiBy's own updates); its recovery and boot
SLOT=$(getprop ro.boot.slot_suffix)
log() { echo "$(date) ota: $*" >> $LOG; }

recovery_check() {
  info=$D/recovery.info
  if [ ! -f $info ]; then setprop sys.hibyr4.ota_recovery none; return; fi
  name=$(grep '^name=' $info | cut -d= -f2); hook=$(grep '^hook=' $info | cut -d= -f2)
  want=$(grep '^sha256=' $info | cut -d= -f2)
  have=$(sha256sum /dev/block/by-name/recovery$SLOT 2>/dev/null | cut -d' ' -f1)
  if [ -n "$want" ] && [ "$want" = "$have" ]; then
    setprop sys.hibyr4.ota_recovery "ok:$name:hook$hook"
  else
    setprop sys.hibyr4.ota_recovery "changed:$name"
  fi
  log "recovery check: $(getprop sys.hibyr4.ota_recovery)"
}

publish_result() {
  r=$D/ota.result
  if [ -f $r ]; then
    st=$(grep '^status=' $r | cut -d= -f2 | tr -cd 'A-Za-z0-9_')
    ver=$(grep '^version=' $r | cut -d= -f2 | tr -cd 'A-Za-z0-9.-')
    det=$(grep '^detail=' $r | cut -d= -f2- | tr -cd 'A-Za-z0-9 ._:()-' | cut -c1-60)
    setprop sys.hibyr4.ota_result "$st|$ver|$det"
    log "result: $st $ver $det"
  else
    setprop sys.hibyr4.ota_result ""
  fi
}

stage() {  # $1 nonce
  [ -f $APPCMD ] || { setprop sys.hibyr4.ota_state "error:no request file"; return; }
  [ "$(stat -c %s $APPCMD)" -lt 512 ] || { setprop sys.hibyr4.ota_state "error:request too large"; return; }
  # SongateOS update (version + zip + sha256) and/or Google apps (gapps_zip + gapps_sha256 + gapps_name; the
  # recovery hook v3 installs them after SongateOS, or alone)
  ver=""; zip=""; sha=""; gzip=""; gsha=""; gname=""; bad=0
  while IFS='=' read -r k v; do
    case "$k" in
      version) ver=$v ;; zip) zip=$v ;; sha256) sha=$v ;;
      gapps_zip) gzip=$v ;; gapps_sha256) gsha=$v ;; gapps_name) gname=$v ;;
      "") ;; *) bad=1 ;;
    esac
  done < $APPCMD
  okpath() { case "$1" in ""|/*|*..*|*[!A-Za-z0-9._/-]*) return 1 ;; *.zip) return 0 ;; *) return 1 ;; esac; }
  [ -n "$zip$gzip" ] || bad=1
  if [ -n "$zip$ver$sha" ]; then
    echo "$ver" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$' || bad=1
    echo "$sha" | grep -qE '^[0-9a-f]{64}$' || bad=1
    okpath "$zip" || bad=1
  fi
  if [ -n "$gzip$gsha$gname" ]; then
    echo "$gsha" | grep -qE '^[0-9a-f]{64}$' || bad=1
    okpath "$gzip" || bad=1
    case "$gname" in core|basic) ;; *) bad=1 ;; esac
  fi
  [ $bad = 0 ] || { setprop sys.hibyr4.ota_state "error:bad request"; log "rejected request"; return; }
  mkdir -p $D; chmod 0700 $D
  { [ -n "$zip" ] && { echo "version=$ver"; echo "zip=$zip"; echo "sha256=$sha"; }
    [ -n "$gzip" ] && { echo "gapps_zip=$gzip"; echo "gapps_sha256=$gsha"; echo "gapps_name=$gname"; }
    true; } > $D/ota.cmd.tmp
  mv -f $D/ota.cmd.tmp $D/ota.cmd; sync
  rm -f $D/ota.result
  setprop sys.hibyr4.ota_result ""
  setprop sys.hibyr4.ota_state "staged:$1"
  log "staged update ${ver:-(none)} (${zip:-no system}) gapps ${gname:-(none)}"
}

REC_DIR=/data/user_de/0/com.andre.hibyr4.service/files
rec_switch() {  # $1 nonce
  local cmd=$REC_DIR/rec.cmd img=$REC_DIR/rec.img kind="" name="" ver="" hook="" sha="" bad=0 p
  fail() { rm -f $img $cmd; setprop sys.hibyr4.ota_state "rec_error:$1:$2"; log "recovery switch: $2"; }
  [ -f $cmd ] && [ -f $img ] || { fail "$1" "files missing"; return; }
  [ "$(stat -c %s $cmd)" -lt 512 ] || { fail "$1" "request too large"; return; }
  while IFS='=' read -r k v; do
    case "$k" in
      kind) kind=$v ;; name) name=$v ;; version) ver=$v ;; hook) hook=$v ;; sha256) sha=$v ;;
      "") ;; *) bad=1 ;;
    esac
  done < $cmd
  case "$kind:$name" in twrp:TWRP|orangefox:OrangeFox) ;; *) bad=1 ;; esac
  echo "$ver" | grep -qE '^[0-9]+(\.[0-9]+)+$' || bad=1
  echo "$hook" | grep -qE '^[0-9]+$' || bad=1
  echo "$sha" | grep -qE '^[0-9a-f]{64}$' || bad=1
  [ $bad = 0 ] || { fail "$1" "bad request"; return; }
  [ "$(sha256sum $img | cut -d' ' -f1)" = "$sha" ] || { fail "$1" "image checksum mismatch"; return; }
  [ "$(head -c 8 $img)" = "ANDROID!" ] || { fail "$1" "not a recovery image"; return; }
  isz=$(stat -c %s $img)
  for p in recovery_a recovery_b; do
    [ "$(blockdev --getsize64 /dev/block/by-name/$p)" = "$isz" ] || { fail "$1" "$p size mismatch"; return; }
  done
  for p in recovery_a recovery_b; do
    dd if=$img of=/dev/block/by-name/$p bs=1048576 2>/dev/null; sync
    [ "$(sha256sum /dev/block/by-name/$p | cut -d' ' -f1)" = "$sha" ] || { fail "$1" "$p did not read back the same"; return; }
  done
  mkdir -p $D; chmod 0700 $D
  { echo "name=$name"; echo "hook=$hook"; echo "slot=$SLOT"; echo "sha256=$sha"; echo "twrp="
    echo "time=$(date +%s)"; echo "source=switch $kind $ver"; } > $D/recovery.info.tmp
  mv -f $D/recovery.info.tmp $D/recovery.info; sync
  rm -f $img $cmd
  recovery_check
  setprop sys.hibyr4.ota_state "rec_done:$1"
  log "recovery switch: $name $ver written to recovery_a + recovery_b"
}

# ---- Magisk (root) install / removal for the HiBy R4 service (requests magisk:<n>, magisk_remove:<n>) ----
# The service has checked the APK's signing certificate (topjohnwu's official one) and left magisk.apk + mag.cmd
# (sha256, version, mode) in its device-protected files. Install = Magisk's own boot_patch.sh, run with the binaries
# from that APK, on the current boot_<slot> (like the Magisk app's "direct install"), then that boot partition is written and read back
# and the Magisk app installed. Removal = Magisk's exact stock-boot backup when it has one, else its ramdisk
# restore; mode=dry only builds the result and reports its sha256 (nothing written).
MAG_TMP=/data/local/tmp/songateos_magisk
mag_fail() { rm -rf $MAG_TMP; rm -f $REC_DIR/magisk.apk $REC_DIR/mag.cmd; setprop sys.hibyr4.ota_state "mag_error:$1:$2"; log "magisk: $2"; }

mag_tools() {  # unpack Magisk's tools from the APK into $MAG_TMP
  rm -rf $MAG_TMP; mkdir -p $MAG_TMP; chmod 0700 $MAG_TMP
  cd $MAG_TMP || return 1
  for f in magisk magiskinit magiskboot magiskpolicy busybox init-ld; do
    unzip -o -p $1 "lib/arm64-v8a/lib$f.so" > $f 2>/dev/null && [ -s $f ] || return 1
  done
  unzip -o -p $1 lib/armeabi-v7a/libmagisk.so > magisk32 2>/dev/null; [ -s magisk32 ] || rm -f magisk32
  for f in boot_patch.sh util_functions.sh; do unzip -o -p $1 "assets/$f" > $f 2>/dev/null && [ -s $f ] || return 1; done
  unzip -o -p $1 assets/stub.apk > stub.apk 2>/dev/null; [ -s stub.apk ] || rm -f stub.apk
  chmod 0755 *
}

mag_read_cmd() {  # sets mver, msha, mmode
  local cmd=$REC_DIR/mag.cmd bad=0
  mver=""; msha=""; mmode=""
  [ -f $cmd ] && [ "$(stat -c %s $cmd)" -lt 256 ] || return 1
  while IFS='=' read -r k v; do
    case "$k" in version) mver=$v ;; sha256) msha=$v ;; mode) mmode=$v ;; "") ;; *) bad=1 ;; esac
  done < $cmd
  echo "$mver" | grep -qE '^[0-9]+(\.[0-9]+)*$' || bad=1
  echo "$msha" | grep -qE '^[0-9a-f]{64}$' || bad=1
  case "$mmode" in install|remove|dry) ;; *) bad=1 ;; esac
  [ $bad = 0 ]
}

mag_write_boot() {  # $1 image; writes boot_<slot> and reads it back
  local img=$1 isz psz
  [ "$(head -c 8 $img)" = "ANDROID!" ] || return 1
  isz=$(stat -c %s $img); psz=$(blockdev --getsize64 /dev/block/by-name/boot$SLOT)
  [ "$isz" -le "$psz" ] || return 1
  dd if=$img of=/dev/block/by-name/boot$SLOT bs=1048576 2>/dev/null; sync
  [ "$(head -c $isz /dev/block/by-name/boot$SLOT | sha256sum | cut -d' ' -f1)" = "$(sha256sum $img | cut -d' ' -f1)" ]
}

magisk_install() {  # $1 nonce
  local apk=$REC_DIR/magisk.apk
  mag_read_cmd && [ "$mmode" = install ] || { mag_fail "$1" "bad request"; return; }
  [ -f $apk ] && [ "$(sha256sum $apk | cut -d' ' -f1)" = "$msha" ] || { mag_fail "$1" "APK checksum mismatch"; return; }
  mag_tools $apk || { mag_fail "$1" "not a Magisk APK"; return; }
  dd if=/dev/block/by-name/boot$SLOT of=$MAG_TMP/boot.img bs=1048576 2>/dev/null
  # stock boot (not patched yet): keep our own copy for removal, next to Magisk's
  ./magiskboot unpack boot.img >/dev/null 2>&1; ./magiskboot cpio ramdisk.cpio test >/dev/null 2>&1; st=$?
  rm -f kernel kernel_dtb ramdisk.cpio dtb extra recovery_dtbo second
  if [ $(( st & 3 )) -eq 0 ]; then
    mkdir -p /data/songateos; chmod 0700 /data/songateos
    cp -f boot.img /data/songateos/boot_stock.img
    sha256sum boot.img | cut -d' ' -f1 > /data/songateos/boot_stock.sha256
  fi
  KEEPVERITY=true KEEPFORCEENCRYPT=true PATCHVBMETAFLAG=false RECOVERYMODE=false ASH_STANDALONE=1 \
    ./busybox sh boot_patch.sh boot.img >> $LOG 2>&1
  [ -f new-boot.img ] || { mag_fail "$1" "patching failed"; return; }
  mag_write_boot $MAG_TMP/new-boot.img || { mag_fail "$1" "boot$SLOT did not read back the same"; return; }
  cp -f $apk /data/local/tmp/songateos_magisk.apk && chmod 0644 /data/local/tmp/songateos_magisk.apk
  pm install -r /data/local/tmp/songateos_magisk.apk >/dev/null 2>&1; rm -f /data/local/tmp/songateos_magisk.apk
  cd /; rm -rf $MAG_TMP; rm -f $apk $REC_DIR/mag.cmd
  setprop sys.hibyr4.ota_state "mag_done:$1"
  log "magisk: $mver patched into boot$SLOT (restart to finish)"
}

magisk_remove() {  # $1 nonce
  local out cfg sha1 b
  mag_read_cmd && { [ "$mmode" = remove ] || [ "$mmode" = dry ]; } || { mag_fail "$1" "bad request"; return; }
  rm -rf $MAG_TMP; mkdir -p $MAG_TMP; chmod 0700 $MAG_TMP; cd $MAG_TMP
  cp -f /data/adb/magisk/magiskboot . 2>/dev/null || { mag_fail "$1" "Magisk isn't installed"; return; }
  dd if=/dev/block/by-name/boot$SLOT of=boot.img bs=1048576 2>/dev/null
  ./magiskboot unpack boot.img >/dev/null 2>&1
  ./magiskboot cpio ramdisk.cpio test >/dev/null 2>&1; st=$?
  [ $(( st & 3 )) -eq 1 ] || { mag_fail "$1" "boot$SLOT is not patched by Magisk"; return; }
  ./magiskboot cpio ramdisk.cpio "extract .backup/.magisk config" >/dev/null 2>&1
  sha1=$(grep '^SHA1=' config 2>/dev/null | cut -d= -f2)
  out=""
  b=/data/magisk_backup_$sha1/boot.img.gz
  if [ -n "$sha1" ] && [ -f $b ]; then           # Magisk's own exact backup of the stock boot image
    ./magiskboot decompress $b stock.img >/dev/null 2>&1
    [ "$(./magiskboot sha1 stock.img)" = "$sha1" ] && out=$MAG_TMP/stock.img
  fi
  if [ -z "$out" ] && [ -f /data/songateos/boot_stock.img ]; then   # our copy, taken before patching
    [ "$(sha256sum /data/songateos/boot_stock.img | cut -d' ' -f1)" = "$(cat /data/songateos/boot_stock.sha256)" ] && \
      out=/data/songateos/boot_stock.img
  fi
  if [ -z "$out" ]; then                          # Magisk's ramdisk restore
    ./magiskboot cpio ramdisk.cpio restore >/dev/null 2>&1 && ./magiskboot repack boot.img >/dev/null 2>&1 && \
      out=$MAG_TMP/new-boot.img
  fi
  [ -n "$out" ] && [ -f "$out" ] || { mag_fail "$1" "no way to restore the stock boot"; return; }
  rsha=$(sha256sum $out | cut -d' ' -f1)
  if [ "$mmode" = dry ]; then
    cd /; rm -rf $MAG_TMP; rm -f $REC_DIR/mag.cmd
    setprop sys.hibyr4.ota_state "mag_dry:$1:$rsha"
    log "magisk: removal dry run, stock boot would be $rsha"
    return
  fi
  mag_write_boot $out || { mag_fail "$1" "boot$SLOT did not read back the same"; return; }
  pm uninstall com.topjohnwu.magisk >/dev/null 2>&1
  cd /; rm -rf $MAG_TMP; rm -f $REC_DIR/mag.cmd
  setprop sys.hibyr4.ota_state "mag_removed:$1"
  log "magisk: stock boot restored ($rsha), restart to finish"
}

mkdir -p $D; chmod 0700 $D
recovery_check
setprop sys.hibyr4.magisk "$(magisk -v 2>/dev/null | cut -d: -f1)"   # installed Magisk version, "" = none
publish_result
# The recoveries have no clock of their own (no time_daemon, /data unreadable): they start at 1970 and every file
# they write is dated 1970 (user, 2026-11-19). Leave the time in /metadata for the recovery hook (v5) to set:
# every 5 minutes, and once more as soon as a restart begins (sys.powerctl is set first).
clock() { date +%s > $D/clock.tmp 2>/dev/null && mv -f $D/clock.tmp $D/clock; }
clock; tick=0; pwr=""
last=$(getprop sys.hibyr4.ota_req)
while true; do
  tick=$((tick + 1)); [ $((tick % 300)) = 0 ] && clock
  p=$(getprop sys.powerctl); [ -n "$p" ] && [ "$p" != "$pwr" ] && { pwr=$p; clock; sync; }
  req=$(getprop sys.hibyr4.ota_req)
  if [ "$req" != "$last" ]; then
    last=$req
    n=${req#*:}
    case "$req" in
      stage:*) stage "$n" ;;
      unstage:*) rm -f $D/ota.cmd; sync; setprop sys.hibyr4.ota_state "unstaged:$n"; log "unstaged" ;;
      ack:*) rm -f $D/ota.result; sync; setprop sys.hibyr4.ota_result "" ;;
      recheck:*) recovery_check ;;
      rec:*) rec_switch "$n" ;;
      magisk:*) magisk_install "$n"; setprop sys.hibyr4.magisk "$(magisk -v 2>/dev/null | cut -d: -f1)" ;;
      magisk_remove:*) magisk_remove "$n" ;;
      return:*) mkdir -p $D; chmod 0700 $D; touch $D/return.cmd; sync
                setprop sys.hibyr4.ota_state "return:$n"; log "recovery check visit staged (comes back by itself)" ;;
    esac
  fi
  sleep 1
done
