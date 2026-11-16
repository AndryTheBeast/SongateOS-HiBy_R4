#!/system/bin/sh
# SongateOS updates: root side of the updater (HiBy R4 service, com.andre.hibyr4sound.Updater).
# The service app (system uid) can't write /metadata or read the recovery partition, so:
#   at boot  - make /metadata/songateos (root, 0700); publish
#              sys.hibyr4.ota_recovery = ok:<name>:hook<N>  when recovery_a is the recovery that last wrote
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
# The recovery side is device/hiby/R4/recovery/root/system/bin/runatboot.sh (TWRP and OrangeFox).
LOG=/data/local/tmp/hiby_r4_fix.log
D=/metadata/songateos
APPCMD=/data/user_de/0/com.andre.hibyr4.service/files/ota.cmd
log() { echo "$(date) ota: $*" >> $LOG; }

recovery_check() {
  info=$D/recovery.info
  if [ ! -f $info ]; then setprop sys.hibyr4.ota_recovery none; return; fi
  name=$(grep '^name=' $info | cut -d= -f2); hook=$(grep '^hook=' $info | cut -d= -f2)
  want=$(grep '^sha256=' $info | cut -d= -f2)
  have=$(sha256sum /dev/block/by-name/recovery_a 2>/dev/null | cut -d' ' -f1)
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
  ver=""; zip=""; sha=""; bad=0
  while IFS='=' read -r k v; do
    case "$k" in
      version) ver=$v ;; zip) zip=$v ;; sha256) sha=$v ;;
      "") ;; *) bad=1 ;;
    esac
  done < $APPCMD
  echo "$ver" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$' || bad=1
  echo "$sha" | grep -qE '^[0-9a-f]{64}$' || bad=1
  case "$zip" in ""|/*|*..*|*[!A-Za-z0-9._/-]*) bad=1 ;; *.zip) ;; *) bad=1 ;; esac
  [ $bad = 0 ] || { setprop sys.hibyr4.ota_state "error:bad request"; log "rejected request"; return; }
  mkdir -p $D; chmod 0700 $D
  { echo "version=$ver"; echo "zip=$zip"; echo "sha256=$sha"; } > $D/ota.cmd.tmp
  mv -f $D/ota.cmd.tmp $D/ota.cmd; sync
  rm -f $D/ota.result
  setprop sys.hibyr4.ota_result ""
  setprop sys.hibyr4.ota_state "staged:$1"
  log "staged update $ver ($zip)"
}

mkdir -p $D; chmod 0700 $D
recovery_check
publish_result
last=$(getprop sys.hibyr4.ota_req)
while true; do
  req=$(getprop sys.hibyr4.ota_req)
  if [ "$req" != "$last" ]; then
    last=$req
    n=${req#*:}
    case "$req" in
      stage:*) stage "$n" ;;
      unstage:*) rm -f $D/ota.cmd; sync; setprop sys.hibyr4.ota_state "unstaged:$n"; log "unstaged" ;;
      ack:*) rm -f $D/ota.result; sync; setprop sys.hibyr4.ota_result "" ;;
      recheck:*) recovery_check ;;
      return:*) mkdir -p $D; chmod 0700 $D; touch $D/return.cmd; sync
                setprop sys.hibyr4.ota_state "return:$n"; log "recovery check visit staged (comes back by itself)" ;;
    esac
  fi
  sleep 1
done
