#!/system/bin/sh
# HiBy R4 on LineageOS GSI (personal module, v7: + bridge for the HiBy pages inside Settings, line out disabled).
# Audio: overlaid audio_policy_configuration.xml adds Wired Headset/Headphones ports.
#        HiBy's vendor audio HAL only switches its engine to the analog headphone/balanced output when it
#        receives "connect=8;<Name>" (Name = Headphone | Balance | Lineout | BalanceLo). HiBy's own system did
#        that; here we send it via AudioFlinger setParameters (binder txn 21) as the audioserver uid (1041),
#        because AudioFlinger strips "connect" from other callers.
# Battery: let the health HAL read the CW2015/MP2731 sysfs nodes (labeled generic "sysfs") and HiBy's props.
LOG=/data/local/tmp/hiby_r4_fix.log
[ -f $LOG ] && mv -f $LOG $LOG.prev   # keep the previous boot's log (e.g. after a freeze)
echo "$(date) service.sh v7 start" > $LOG
magiskpolicy --live \
  "allow hal_health_default sysfs file { read open getattr }" \
  "allow hal_health_default sysfs dir { read open search getattr }" \
  "allow hal_health_default vendor_audio_prop file { read open getattr map }" \
  "allow hal_health_default vendor_usb_prop file { read open getattr map }" \
  "allow hal_audio_default property_socket sock_file write" \
  "allow hal_audio_default init unix_stream_socket connectto" \
  "allow hal_audio_default vendor_audio_prop property_service set" \
  "allow system_app audio_device dir { search read open getattr }" \
  "allow system_app audio_device chr_file { read write open ioctl getattr }" >> $LOG 2>&1
# ^ last two (2026-11-16, also in sepolicy.rule so they exist before the service app starts): the HiBy R4 service
#   sets the wired volume in the DAC (tinymix "Plat Left/Right Playback Volume", DacVolume.java).
# ^ last three (v6): let HiBy's audio HAL set its own vendor.audio.hiby.hw.sample_rate / sample_quality, as it tries
#   to every second (EACCES on the GSI). sample_quality drives HiBy's quality LED colours in LED mode "hiby".
echo "$(date) magiskpolicy rc=$?" >> $LOG
# LED colour file: own label, owned by system (uid 1000), so only the Settings app (system_app) can draw the
# colour directly while the colour wheel is dragged. Everything else about the LED stays with this service.
LEDW=/sys/bus/i2c/devices/i2c-2/2-0030/leds/sgm31324-leds/write_pattern
magiskpolicy --live "type hibyr4_led_file" "typeattribute hibyr4_led_file fs_type" \
  "typeattribute hibyr4_led_file sysfs_type" "allow hibyr4_led_file sysfs filesystem associate" \
  "allow magisk sysfs file relabelfrom" "allow magisk hibyr4_led_file file { relabelto relabelfrom write open getattr setattr }" \
  "allow system_app hibyr4_led_file file { write open getattr }" \
  "allow init hibyr4_led_file file { write open getattr }" \
  "allow vendor_init hibyr4_led_file file { write open getattr }" \
  "allow system_app vendor_audio_prop file { read open getattr map }" >> $LOG 2>&1
# ^ last rule: the service app reads HiBy's vendor.audio.hiby.hw.sample_quality (DSD -> white LED in "audio quality colour")
# Back light switch (led_enable = ambience GPIO) and HiBy's pattern index (led_pattern: charging pattern, the white
# LED = pattern 5, pattern 0 = all off): same label, so the service app (Lights.java) drives both lights in custom mode.
# init / vendor_init (HiBy's led rules in mode "hiby") keep write access through the two rules above.
for f in /sys/devices/platform/soc/soc:leds/led_enable /sys/bus/i2c/devices/i2c-2/2-0030/leds/sgm31324-leds/led_pattern; do
  if chcon u:object_r:hibyr4_led_file:s0 $f 2>>$LOG && chown system:system $f && chmod 0220 $f; then
    echo "$(date) LED file ready for the service app: $(ls -lZ $f)" >> $LOG
  else
    echo "$(date) LED file setup failed: $f" >> $LOG
  fi
done
if chcon u:object_r:hibyr4_led_file:s0 $LEDW 2>>$LOG && chown system:system $LEDW && chmod 0220 $LEDW; then
  setprop sys.hibyr4.st_ledw 1; echo "$(date) LED file ready for Settings: $(ls -lZ $LEDW)" >> $LOG
else
  setprop sys.hibyr4.st_ledw 0; echo "$(date) LED file setup failed, using the slower fallback" >> $LOG
fi
# Battery fix in the background (needs boot_completed), so audio does not wait for it.
( while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
  stop health-hal-2-1; sleep 1; start health-hal-2-1
  echo "$(date) health HAL restarted: $(getprop init.svc.health-hal-2-1)" >> $LOG
  # No SIM / no radio on the R4: stop Qualcomm's VoLTE (IMS) and mobile-data daemons that idle for nothing.
  # NOT touched (Wi-Fi on SM6125 runs through the modem subsystem): modem, rmt_storage, tftp_server, cnss-daemon,
  # pd_mapper, vendor.cnd. Undo: delete this loop and reboot.
  for s in vendor.imsqmidaemon vendor.imsdatadaemon vendor.imsrcsservice vendor.ims_rtp_daemon \
           vendor.netmgrd vendor.port-bridge dpmQmiMgr; do
    [ "$(getprop init.svc.$s)" = "running" ] && { stop $s; echo "$(date) stopped idle phone service $s -> $(getprop init.svc.$s)" >> $LOG; }
  done ) &

send_param() {  # $1 = key/value string for the audio HAL
  su 1041 -c "service call media.audio_flinger 21 i32 0 s16 '$1'" >> $LOG 2>&1
  echo "$(date) sent: $1" >> $LOG
}
# Audio first: start as soon as AudioFlinger is up (well before boot_completed).
until service check media.audio_flinger 2>/dev/null | grep -q ": found"; do sleep 1; done
sleep 1
echo "$(date) audioflinger up" >> $LOG
MODDIR=${0%/*}
DIR=/data/adb/hiby_r4
# Boot animation until the launcher is on screen (SongateOS, 2026-11-15). Android stops the boot animation as soon
# as its placeholder home (Settings' FallbackHome, "Android is starting...") is shown, before the user's storage is
# unlocked and the real launcher can start - and only marks boot as completed after that, so the first animation
# can't be held. Instead: when it has stopped, start it again with bootanimation_hold.zip (fade in, the same loop,
# fade out) until the default launcher has focus, then let it fade out. Max 20 s. FallbackHome is black meanwhile
# (overlay HiByR4FallbackHome). Proper fix later in the SongateOS build: the window manager waits for the launcher.
bootanim_hold() {
  Z=$MODDIR/bootanimation_hold.zip; [ -f $Z ] || return
  i=0; until [ "$(getprop init.svc.bootanim)" = "stopped" ]; do sleep 0.1; i=$((i + 1)); [ $i -gt 1200 ] && return; done
  # the default launcher = holder of the HOME role. Not "resolve-activity": before the storage is unlocked it only
  # finds FallbackHome. roles.xml lives in device-encrypted storage, readable right after boot (tested 2026-11-15).
  home=$(grep -A1 'role name="android.app.role.HOME"' /data/misc_de/0/apexdata/com.android.permission/roles.xml 2>/dev/null | grep -o 'holder name="[^"]*"' | cut -d'"' -f2)
  [ -z "$home" ] && home=$(cmd role get-role-holders android.app.role.HOME 2>/dev/null | head -1)
  case "$home" in ""|android|com.android.settings) echo "$(date) boot hold: no launcher set ($home), skipped" >> $LOG; return ;; esac
  chcon u:object_r:system_file:s0 $Z 2>/dev/null
  for f in /product/media/bootanimation.zip /product/media/bootanimation-dark.zip; do [ -e $f ] && mount --bind $Z $f; done
  setprop service.bootanim.exit 0
  start bootanim
  t0=$(date +%s)
  n=0; while [ $n -lt 100 ]; do                      # ~20 s
    dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus" | grep -q " $home/" && break
    sleep 0.2; n=$((n + 1))
  done
  sleep 0.4                                          # let the launcher draw its first frames
  setprop service.bootanim.exit 1
  i=0; until [ "$(getprop init.svc.bootanim)" = "stopped" ]; do sleep 0.1; i=$((i + 1)); [ $i -gt 50 ] && break; done
  for f in /product/media/bootanimation-dark.zip /product/media/bootanimation.zip; do umount $f 2>/dev/null; done
  [ $n -lt 100 ] && r="$home on screen" || r="timeout"
  echo "$(date) boot hold: $r after $(( $(date +%s) - t0 )) s" >> $LOG
}
( bootanim_hold ) &
# SongateOS updates: /metadata/songateos hand-off for the updater in the HiBy R4 service (see ota_bridge.sh)
( sh $MODDIR/ota_bridge.sh ) &
# Hold switch on top of the R4: button locks + sleep when locked (see hold_switch.sh, service HoldSwitch)
( sh $MODDIR/hold_switch.sh ) &
# restore all saved sound settings (gain, filter, balance, ...) - see hiby_settings.sh
sh $MODDIR/hiby_settings.sh boot_reset >> $LOG 2>&1   # 3.5 mm line out never survives a reboot
sh $MODDIR/hiby_settings.sh apply_all >> $LOG 2>&1
sh $MODDIR/hiby_settings.sh publish >> $LOG 2>&1
# Bridge for Settings > HiBy audio (pages inside the Settings app, uid system, no root): they write
# sys.hibyr4.KEY; every 0.3 s we look for props that changed and apply them through hiby_settings.sh
# (which validates; an invalid value is put back to the saved one).
# LED colour/brightness while dragging (colour wheel, brightness slider): the Settings page draws the LED itself
# (instant, see the LED file setup above); "led_live" here is the fallback. The values are saved together about
# 1 s after the last change; until then $DIR/led_live tells led_refresh not to put the old saved colour back.
pval() { echo "$1" | grep "^\[sys\.hibyr4\.$2\]" | sed 's/^[^:]*: \[\(.*\)\]$/\1/'; }
( last_snap=""; quiet=0; pending=0
  while true; do
    snap=$(getprop | grep '^\[sys\.hibyr4\.')
    if [ "$snap" != "$last_snap" ]; then
      quiet=0
      changed=$(echo "$snap" | awk -v old="$last_snap" 'BEGIN { n = split(old, a, "\n"); for (i = 1; i <= n; i++) o[a[i]] = 1 } !($0 in o)')
      for k in $(echo "$changed" | sed 's/^\[sys\.hibyr4\.\([^]]*\)\].*/\1/'); do
        case $k in
          ready|st_*) ;;   # status values published by this service for the header card
          ota_*) ;;        # SongateOS updater, handled by ota_bridge.sh
          hold*) ;;        # hold switch state + level, owned by hold_switch.sh and the service
          led_color|led_bright)
            if [ "$pending" != "1" ]; then
              touch $DIR/led_live; pending=1
              sh $MODDIR/hiby_settings.sh led_live "$(pval "$snap" led_color)" "$(pval "$snap" led_bright)" "$(pval "$snap" led_effect)" >/dev/null 2>&1
            fi ;;
          *)
            v=$(pval "$snap" "$k")
            cur=$(sh $MODDIR/hiby_settings.sh get "$k")
            [ "$v" = "$cur" ] && continue
            r=$(sh $MODDIR/hiby_settings.sh set "$k" "$v" 2>&1)
            case "$r" in
              OK*) echo "$(date) settings page: $k=$v" >> $LOG ;;
              *) setprop "sys.hibyr4.$k" "$cur"; echo "$(date) settings page: rejected $k=$v ($r)" >> $LOG ;;
            esac ;;
        esac
      done
      # while dragging, keep the fallback LED write going for the newest value (if Settings can't write it)
      [ "$pending" = "1" ] && [ "$(getprop sys.hibyr4.st_ledw)" != "1" ] && \
        sh $MODDIR/hiby_settings.sh led_live "$(pval "$snap" led_color)" "$(pval "$snap" led_bright)" "$(pval "$snap" led_effect)" >/dev/null 2>&1
      last_snap=$snap
    elif [ "$pending" = "1" ]; then
      quiet=$((quiet + 1))
      if [ $quiet -ge 7 ]; then   # ~1 s without changes: save colour + brightness in one go (validated)
        c=$(pval "$snap" led_color); b=$(pval "$snap" led_bright)
        r=$(sh $MODDIR/hiby_settings.sh setmany led_color "$c" led_bright "$b" 2>&1)
        case "$r" in
          OK*) echo "$(date) settings page: led_color=$c led_bright=$b" >> $LOG ;;
          *) sh $MODDIR/hiby_settings.sh publish; echo "$(date) settings page: rejected LED $c/$b ($r)" >> $LOG ;;
        esac
        rm -f $DIR/led_live; pending=0
        last_snap=$(getprop | grep '^\[sys\.hibyr4\.')
      fi
    fi
    sleep 0.15
  done ) &
# Android's own "connect=8" (when it registers the headphones) makes HiBy's HAL reset its output name to
# "Default" (= S/PDIF). So we watch whether the audio policy currently has the wired headphones connected,
# and re-send our "connect=8;<Name>" every time that goes from not-connected to connected (after Android's).
apm_wired() {
  ap=$(dumpsys media.audio_policy 2>/dev/null)   # read to the end first (an early-closed pipe can kill HiBy's HAL)
  echo "$ap" | sed -n '/Available output devices/,/Available input devices/p' | grep -q WIRED_HEADPHONE
}
# Bluetooth audio (2026-11-15): AudioFlinger takes its master volume from the first audio HAL it loads (HiBy's
# primary HAL reports 0.4). The primary outputs apply master volume in HiBy's HAL, but the Bluetooth (sysbta)
# output applies it in software, where 0.4 left every player "muted by master" = silent Bluetooth. Wired output
# sounded the same at 1.0 (tested). Set 1.0 at boot and each time audioserver restarts (it re-reads 0.4).
# Undo: remove this block and reboot.
master_vol() {
  r=$(CLASSPATH=$MODDIR/mastervol.dex app_process /system/bin MvProbe setvol 1.0 2>&1 | tr '\n' ' ')
  echo "$(date) master volume -> 1.0 ($r)" >> $LOG
}
lastas=""
last=""
lastpid=""
lastapm=0
n=0
POL=/vendor/etc/audio_policy_configuration.xml
while true; do
  # Every ~10 s: make sure the policy overlay (Wired Headphones ports) is still mounted.
  # Act only after 3 consecutive misses (avoids false alarms while Magisk sets up mounts at boot).
  n=$((n+1))
  if [ $((n % 10)) -eq 0 ]; then
    if grep -q "Wired Headphones" $POL; then miss=0; else miss=$((miss+1)); echo "$(date) policy check miss $miss" >> $LOG; fi
    if [ "${miss:-0}" -ge 3 ]; then
      mount -o bind $MODDIR/system/vendor/etc/audio_policy_configuration.xml $POL
      echo "$(date) policy overlay was missing -> re-mounted ($(grep -c 'Wired Head' $POL)), restarting audioserver" >> $LOG
      killall audioserver; miss=0
    fi
  fi
  # every 3 s: status LED (charging indicator / screen state / custom pattern), writes only on change
  [ $((n % 3)) -eq 0 ] && sh $MODDIR/hiby_settings.sh led_refresh >/dev/null 2>&1
  # audioserver (re)started -> AudioFlinger read master volume 0.4 from the HAL again (see master_vol above)
  aspid=$(pidof audioserver)
  if [ -n "$aspid" ] && [ "$aspid" != "$lastas" ]; then
    sleep 1; master_vol; lastas=$aspid
    # wired volume in the DAC: Android may have reset the volume behaviour; the service checks and sets it up again
    am broadcast -n com.andre.hibyr4.service/com.andre.hibyr4sound.DacVolume >/dev/null 2>&1
  fi
  # If the vendor audio HAL restarts (e.g. low memory), it forgets the output name -> resend it.
  apid=$(pidof android.hardware.audio.service)
  if [ -n "$apid" ] && [ "$apid" != "$lastpid" ]; then
    [ -n "$lastpid" ] && { echo "$(date) audio HAL restarted ($lastpid -> $apid), re-sending output + settings" >> $LOG; last=""; sleep 2; sh $MODDIR/hiby_settings.sh apply_all >> $LOG 2>&1; }
    lastpid=$apid
  fi
  h=$(cat /sys/class/switch/h2w/state 2>/dev/null)
  b=$(cat /sys/class/switch/balance/state 2>/dev/null)
  out35=$(grep '^out35=' $DIR/settings.conf 2>/dev/null | tail -1 | cut -d= -f2)
  out44=$(grep '^out44=' $DIR/settings.conf 2>/dev/null | tail -1 | cut -d= -f2)
  if [ -n "$b" ] && [ "$b" != "0" ]; then
    [ "$out44" = "bal_lo" ] && want=BalanceLo || want=Balance
  elif [ -n "$h" ] && [ "$h" != "0" ]; then
    want=Headphone   # also for 3.5 mm line out: HiBy's "Lineout" mode never powers the DAC on the R4
  else want=none
  fi
  rm -f $DIR/reroute
  # 3.5 mm line out = headphone path at fixed full volume (the HiBy R4 service sets Android's "full volume"
  # behaviour for the wired headphones; the user's own headphone volume stays saved) + DAC gain = line-out level.
  # The service also ends it the moment the plug is pulled; this is the backup (out35 -> headphone).
  if [ "$out35" = "lineout" ] && [ "$want" != "Headphone" ]; then
    sh $MODDIR/hiby_settings.sh set out35 headphone >/dev/null 2>&1; out35=headphone
    echo "$(date) 3.5 mm line out: plug removed -> back to headphone" >> $LOG
  fi
  lo=0; [ "$out35" = "lineout" ] && [ "$want" = "Headphone" ] && lo=1
  if [ "$lo" = "1" ] && [ ! -f $DIR/lo_active ]; then
    touch $DIR/lo_active
    g=$(sh $MODDIR/hiby_settings.sh get lo_gain); setprop vendor.audio.hiby.hw.gain "$g"
    am broadcast -n com.andre.hibyr4.service/com.andre.hibyr4sound.LineOut >/dev/null 2>&1
    echo "$(date) 3.5 mm line out ON (fixed full volume, gain $g)" >> $LOG
  elif [ "$lo" = "0" ] && [ -f $DIR/lo_active ]; then
    rm -f $DIR/lo_active
    g=$(sh $MODDIR/hiby_settings.sh get gain); setprop vendor.audio.hiby.hw.gain "$g"
    am broadcast -n com.andre.hibyr4.service/com.andre.hibyr4sound.LineOut >/dev/null 2>&1
    echo "$(date) 3.5 mm line out OFF (normal volume, gain $g)" >> $LOG
  fi
  # sample rate of the current stream (set by HiBy's HAL) for the Settings page header
  if [ $((n % 2)) -eq 1 ]; then
    sr=$(getprop vendor.audio.hiby.hw.sample_rate)
    [ "$sr" != "$lastsr" ] && { setprop sys.hibyr4.st_rate "$sr"; lastsr=$sr; }
    # HiBy's stream type (low/standard/high/dsd/mqa/mqastudio/mqb). MQA is recognised by HiBy's driver only when the
    # bits arrive unchanged (bit-perfect); st_rate is then the decoded rate (e.g. 705600 for a 44.1 kHz MQA file).
    sq=$(getprop vendor.audio.hiby.hw.sample_quality)
    [ "$sq" != "$lastsq" ] && { setprop sys.hibyr4.st_quality "${sq:-none}"; lastsq=$sq; }
  fi
  # every 2 s: did Android (re)connect the wired headphones? Then our name must be sent again, after it.
  if [ $((n % 2)) -eq 0 ]; then
    if apm_wired; then apm=1; else apm=0; fi
    if [ "$apm" != "$lastapm" ]; then
      echo "$(date) audio policy wired headphones: $lastapm -> $apm" >> $LOG
      [ "$apm" = "1" ] && last=""
      lastapm=$apm
    fi
    # "Volume after restart = fixed level": once per boot, when the headphones are registered after boot
    if [ "$apm" = "1" ] && [ -z "$volset" ] && [ "$(getprop sys.boot_completed)" = "1" ]; then
      volset=1
      if [ "$(sh $MODDIR/hiby_settings.sh get vol_restore)" = "fixed" ]; then
        pct=$(sh $MODDIR/hiby_settings.sh get vol_boot); steps=$(getprop ro.config.media_vol_steps); steps=${steps:-25}
        idx=$(( (pct * steps + 50) / 100 ))
        cmd media_session volume --stream 3 --set $idx >/dev/null 2>&1
        echo "$(date) start volume: $pct% -> $idx/$steps" >> $LOG
      fi
    fi
  fi
  if [ "$want" != "$last" ]; then
    [ -n "$last" ] && [ "$last" != "none" ] && send_param "disconnect=8;$last"
    [ "$want" != "none" ] && [ -n "$last" ] && sleep 0.3
    [ "$want" != "none" ] && send_param "connect=8;$want"
    # A running stream is not re-routed by the HAL when only the name changes (e.g. mode switch in the app),
    # so also set HiBy's output switch directly (engine stays on its analog target for all four modes).
    case $want in
      Headphone) sel=Headset ;; Lineout) sel=Lineout ;; Balance) sel=Balance ;; BalanceLo) sel=BalanceLo ;; *) sel="" ;;
    esac
    [ -n "$sel" ] && [ -n "$last" ] && [ "$last" != "none" ] && tinymix -D 0 "Output Select" "$sel" >/dev/null 2>&1 && echo "$(date) Output Select -> $sel" >> $LOG
    echo "$(date) h2w=$h balance=$b -> $want" >> $LOG
    setprop sys.hibyr4.st_output "$want"   # shown in the Settings page header
    last=$want
  fi
  sleep 1
done
