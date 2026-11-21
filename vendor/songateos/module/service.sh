#!/system/bin/sh
# SongateOS (HiBy R4): the root service started by init (songateos.rc, late_start). Was the Magisk module's service.sh.
# Audio: overlaid audio_policy_configuration.xml adds Wired Headset/Headphones ports.
#        HiBy's vendor audio HAL drives the analog output named in "<Name>=;connect=8" (Name = Headphone | Balance).
#        Android sends that itself: its WiredAccessoryManager (SongateOS patch apply_wired.py) registers the jacks as
#        wired headphones with the name as the address, as HiBy's own system does. (The R4's driver has no line-out
#        outputs: "Lineout" and "BalanceLo" power the DAC off, so both line outs are the headphone path at full volume.)
# Battery: let the health HAL read the CW2015/MP2731 sysfs nodes (labeled generic "sysfs") and HiBy's props.
LOG=/data/local/tmp/hiby_r4_fix.log
[ -f $LOG ] && mv -f $LOG $LOG.prev   # keep the previous boot's log (e.g. after a freeze)
echo "$(date) service.sh v7 start" > $LOG
# SELinux rules for the health HAL, HiBy's audio HAL and the service app are compiled into this image
# (vendor/songateos/sepolicy), so nothing is added at runtime any more.
# LED colour file: own label, owned by system (uid 1000), so only the Settings app (system_app) can draw the
# colour directly while the colour wheel is dragged. Everything else about the LED stays with this service.
LEDW=/sys/bus/i2c/devices/i2c-2/2-0030/leds/sgm31324-leds/write_pattern
# (hibyr4_led_file: compiled type + genfscon labels in this image; the chcon below only repeats them)
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
  # CPU governor: HiBy's vendor start-up script puts all eight cores on "performance" (always full speed, a warm
  # player and a shorter battery life). schedutil lets idle cores slow down (user 2026-11-20). After boot_completed
  # and a pause, so HiBy's script has run; written to both clusters directly (its cpu_tuner property is closed to us).
  sleep 10
  for c in 0 4; do echo schedutil > /sys/devices/system/cpu/cpufreq/policy$c/scaling_governor 2>/dev/null; done
  echo "$(date) CPU governor: $(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor)/$(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_governor)" >> $LOG
  # LineageOS Trust's build-signature alert stays on (default): from 2.1.0 SongateOS is signed with its own release keys
  # ("dev-keys"; the betas used AOSP's public test keys and switched the alert off with trust_warnings=1).
  # No SIM / no radio on the R4: stop Qualcomm's VoLTE (IMS) and mobile-data daemons that idle for nothing.
  # NOT touched (Wi-Fi on SM6125 runs through the modem subsystem): modem, rmt_storage, tftp_server, cnss-daemon,
  # pd_mapper, vendor.cnd. Undo: delete this loop and reboot.
  for s in vendor.imsqmidaemon vendor.imsdatadaemon vendor.imsrcsservice vendor.ims_rtp_daemon \
           vendor.netmgrd vendor.port-bridge dpmQmiMgr; do
    [ "$(getprop init.svc.$s)" = "running" ] && { stop $s; echo "$(date) stopped idle phone service $s -> $(getprop init.svc.$s)" >> $LOG; }
  done
  # HiBy apps SongateOS doesn't use (user decisions): they live on HiBy's vendor partition, which SongateOS never
  # writes, so they're removed for the user instead (files stay in /vendor; undo: pm install-existing <package>).
  # Every boot, so updated R4s get it too; only when still installed for the user.
  #   com.hiby.music              HiBy Music (Songate is the player)
  #   com.hiby.dlna.dlnarendor    HiBy Render (DLNA renderer)
  #   com.hiby.instructions       "Technical Support"
  for p in com.hiby.music com.hiby.dlna.dlnarendor com.hiby.instructions; do
    pm list packages --user 0 2>/dev/null | grep -qx "package:$p" || continue
    pm uninstall --user 0 "$p" >/dev/null 2>&1 && echo "$(date) removed HiBy app $p for the user" >> $LOG
  done
  # Phone apps (the R4 has no SIM; user decision): the system image keeps them, so they're removed for the user the
  # same way, every boot while still installed (0.1.0-beta.9 on a fresh install showed Phone and Messaging).
  # com.android.phone (TeleService) stays: Android needs it. Undo: pm install-existing <package>.
  #   dialer, messaging, contacts + backups | SIM toolkit, SIM dialogs | emergency alerts | carrier, MMS, WAP push |
  #   emergency info | IMS entitlement, ONS, QNS | MediaTek in-call (not this device) | phh's qcril AM, Qualcomm IWLAN
  PL=$(pm list packages --user 0 2>/dev/null)
  for p in com.android.dialer com.android.messaging com.android.contacts com.android.calllogbackup \
           org.calyxos.backup.contacts com.android.stk com.android.simappdialog com.android.cellbroadcastreceiver \
           com.android.cellbroadcastreceiver.module com.android.carrierdefaultapp com.android.mms.service \
           com.android.smspush com.android.emergency com.android.imsserviceentitlement com.android.ons \
           com.android.telephony.qns org.lineageos.mediatek.incallservice me.phh.qcrilam vendor.qti.iwlan; do
    echo "$PL" | grep -qx "package:$p" || continue
    pm uninstall --user 0 "$p" >/dev/null 2>&1 && echo "$(date) removed phone app $p for the user" >> $LOG
  done
  # Other apps SongateOS doesn't use (user decision 2026-11-20), the same way:
  #   mark.via                     Via browser (HiBy's, on the vendor partition)
  #   org.lineageos.aperture       Camera (the R4 has no camera) + its lens launcher (googlecamera.fishfood)
  #   org.lineageos.eleven         LineageOS Music (Songate is the player)
  #   org.lineageos.recorder       Recorder (user decision 2026-11-20)
  for p in mark.via org.lineageos.aperture com.google.android.apps.googlecamera.fishfood org.lineageos.eleven \
           org.lineageos.recorder; do
    echo "$PL" | grep -qx "package:$p" || continue
    pm uninstall --user 0 "$p" >/dev/null 2>&1 && echo "$(date) removed unused app $p for the user" >> $LOG
  done
  # SongateOS defaults, once on a new install (after Format Data): the settings Android has no build-time default for
  if [ ! -f /data/songateos/first_boot_done ]; then
    S=io.github.andrythebeast.songate
    # (Android's "setup finished" flags: set by the HiBy R4 service, Setup.java, when Songate's setup is done or
    #  Songate has none; see SONGATEOS_SYSTEM_API.md "Setup")
    appops set $S MANAGE_EXTERNAL_STORAGE allow      # Songate's library reads the music folders
    appops set $S REQUEST_INSTALL_PACKAGES allow     # Songate's own in-app updates
    # no lock screen (user decision): the defaults overlay's def_lockscreen_disabled only reaches the settings
    # database; Android's lock settings service keeps its own flag (it said "not disabled" on beta.3), so set it there
    locksettings set-disabled true
    settings put secure lock_screen_lock_after_timeout 0   # no lock screen: no 5 s re-lock that woke the screen
    settings put secure doze_always_on 0                   # no always-on display
    # gesture navigation (Android's default is three buttons, which Songate's full screen hides with nothing to
    # swipe; SongateOS 0.1.0-beta.3). The same switch as Settings' System navigation, so it can still be changed
    cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
    # Do Not Disturb (priority only: media + alarms play). Through NotificationManager: a plain
    # `settings put global zen_mode 1` is overwritten by it (SongateOS 0.1.0-beta.1 came up with DND off)
    cmd notification set_dnd priority
    # LineageOS "Discover Trust" notice: already seen (LineageSettings.System, LineageOS's own settings provider)
    content insert --uri content://lineagesettings/system --bind name:s:trust_interface_hinted --bind value:s:1 2>/dev/null
    touch /data/songateos/first_boot_done
    echo "$(date) SongateOS first-boot defaults set" >> $LOG
  fi ) &

# Audio first: start as soon as AudioFlinger is up (well before boot_completed).
until service check media.audio_flinger 2>/dev/null | grep -q ": found"; do sleep 1; done
sleep 1
echo "$(date) audioflinger up" >> $LOG
MODDIR=/system_ext/etc/songateos   # data files
BIN=/system_ext/bin/songateos       # scripts
DIR=/data/songateos/hiby
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
# ( bootanim_hold ) &   # SongateOS beta.7: the window manager waits for Songate itself (patches/apply_bootwait.py)
# SongateOS updates: /metadata/songateos hand-off for the updater in the HiBy R4 service (see ota_bridge.sh)
( sh $BIN/ota_bridge.sh ) &
# Hold switch on top of the R4: button locks + sleep when locked (see hold_switch.sh, service HoldSwitch)
( sh $BIN/hold_switch.sh ) &
# restore all saved sound settings (gain, filter, balance, ...) - see hiby_settings.sh
sh $BIN/hiby_settings.sh boot_reset >> $LOG 2>&1   # 3.5 mm line out never survives a reboot
sh $BIN/hiby_settings.sh apply_all >> $LOG 2>&1
sh $BIN/hiby_settings.sh publish >> $LOG 2>&1
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
      # lines of snap that weren't in last_snap. Through a file: Android's awk refuses a multi-line `-v` value
      # ("newline in string"; Magisk's busybox awk took it), which left `changed` empty, so no setting changed from
      # Songate was saved or applied (SongateOS 0.1.0-beta.1 to beta.3)
      printf '%s\n' "$last_snap" > $DIR/watch_snap
      changed=$(echo "$snap" | grep -vxF -f $DIR/watch_snap)
      for k in $(echo "$changed" | sed 's/^\[sys\.hibyr4\.\([^]]*\)\].*/\1/'); do
        case $k in
          ready|st_*) ;;   # status values published by this service for the header card
          ota_*) ;;        # SongateOS updater, handled by ota_bridge.sh
          hold*) ;;        # hold switch state + level, owned by hold_switch.sh and the service
          magisk) ;;       # installed Magisk version, published by ota_bridge.sh
          led_color|led_bright)
            if [ "$pending" != "1" ]; then
              touch $DIR/led_live; pending=1
              sh $BIN/hiby_settings.sh led_live "$(pval "$snap" led_color)" "$(pval "$snap" led_bright)" "$(pval "$snap" led_effect)" >/dev/null 2>&1
            fi ;;
          *)
            v=$(pval "$snap" "$k")
            cur=$(sh $BIN/hiby_settings.sh get "$k")
            [ "$v" = "$cur" ] && continue
            r=$(sh $BIN/hiby_settings.sh set "$k" "$v" 2>&1)
            case "$r" in
              OK*) echo "$(date) settings page: $k=$v" >> $LOG ;;
              *) setprop "sys.hibyr4.$k" "$cur"; echo "$(date) settings page: rejected $k=$v ($r)" >> $LOG ;;
            esac ;;
        esac
      done
      # while dragging, keep the fallback LED write going for the newest value (if Settings can't write it)
      [ "$pending" = "1" ] && [ "$(getprop sys.hibyr4.st_ledw)" != "1" ] && \
        sh $BIN/hiby_settings.sh led_live "$(pval "$snap" led_color)" "$(pval "$snap" led_bright)" "$(pval "$snap" led_effect)" >/dev/null 2>&1
      last_snap=$snap
    elif [ "$pending" = "1" ]; then
      quiet=$((quiet + 1))
      if [ $quiet -ge 7 ]; then   # ~1 s without changes: save colour + brightness in one go (validated)
        c=$(pval "$snap" led_color); b=$(pval "$snap" led_bright)
        r=$(sh $BIN/hiby_settings.sh setmany led_color "$c" led_bright "$b" 2>&1)
        case "$r" in
          OK*) echo "$(date) settings page: led_color=$c led_bright=$b" >> $LOG ;;
          *) sh $BIN/hiby_settings.sh publish; echo "$(date) settings page: rejected LED $c/$b ($r)" >> $LOG ;;
        esac
        rm -f $DIR/led_live; pending=0
        last_snap=$(getprop | grep '^\[sys\.hibyr4\.')
      fi
    fi
    sleep 0.15
  done ) &
# Bluetooth audio (2026-11-15): AudioFlinger takes its master volume from the first audio HAL it loads (HiBy's
# primary HAL reports 0.4). The primary outputs apply master volume in HiBy's HAL, but the Bluetooth (sysbta)
# output applies it in software, where 0.4 left every player "muted by master" = silent Bluetooth. Wired output
# sounded the same at 1.0 (tested). Set 1.0 at boot and each time audioserver restarts (it re-reads 0.4).
# Undo: remove this block and reboot.
master_vol() {
  r=$(CLASSPATH=/system_ext/framework/songateos_mastervol.jar app_process /system/bin MvProbe setvol 1.0 2>&1 | tr '\n' ' ')
  echo "$(date) master volume -> 1.0 ($r)" >> $LOG
}
lastas=""
last=""
lastpid=""
n=0
POL=/vendor/etc/audio_policy_configuration.xml
while true; do
  # Every ~10 s: make sure the policy overlay (Wired Headphones ports) is still mounted.
  # Act only after 3 consecutive misses (avoids false alarms while Magisk sets up mounts at boot).
  n=$((n+1))
  if [ $((n % 10)) -eq 0 ]; then
    if grep -q "Wired Headphones" $POL; then miss=0; else miss=$((miss+1)); echo "$(date) policy check miss $miss" >> $LOG; fi
    if [ "${miss:-0}" -ge 3 ]; then
      mount -o bind /data/songateos/audio_policy_configuration.xml $POL
      echo "$(date) policy overlay was missing -> re-mounted ($(grep -c 'Wired Head' $POL)), restarting audioserver" >> $LOG
      killall audioserver; miss=0
    fi
  fi
  # Every ~10 s: CPU governor (user choice 2026-11-20: schedutil). HiBy's post_boot script ("add by ringsd") sets
  # every core to performance each time Android reports boot completed, so also after a system restart (0.1.0-beta.9:
  # a system_server crash put performance back for good). Put schedutil back once that script has finished.
  if [ $((n % 10)) -eq 5 ] && [ "$(getprop init.svc.qcom-post-boot)" != "running" ]; then
    for c in 0 4; do
      g=/sys/devices/system/cpu/cpufreq/policy$c/scaling_governor
      old=$(cat $g 2>/dev/null)
      [ "$old" = "schedutil" ] || { echo schedutil > $g 2>/dev/null; echo "$(date) CPU governor policy$c was $old -> $(cat $g)" >> $LOG; }
    done
  fi
  # every 3 s: status LED (charging indicator / screen state / custom pattern), writes only on change
  [ $((n % 3)) -eq 0 ] && sh $BIN/hiby_settings.sh led_refresh >/dev/null 2>&1
  # audioserver (re)started -> AudioFlinger read master volume 0.4 from the HAL again (see master_vol above)
  aspid=$(pidof audioserver)
  if [ -n "$aspid" ] && [ "$aspid" != "$lastas" ]; then
    sleep 1; master_vol; lastas=$aspid
    # wired volume in the DAC: Android may have reset the volume behaviour; the service checks and sets it up again
    am broadcast -n com.andre.hibyr4.service/com.andre.hibyr4sound.DacVolume >/dev/null 2>&1
  fi
  # If the vendor audio HAL restarts (low memory, a sample-rate switch), it forgets its settings -> apply them again,
  # at the end of this round (~12 s). Android restores the plugged-in output itself, with its name.
  apid=$(pidof android.hardware.audio.service)
  if [ -n "$apid" ] && [ "$apid" != "$lastpid" ]; then
    if [ -n "$lastpid" ]; then
      echo "$(date) audio HAL restarted ($lastpid -> $apid), applying the settings again" >> $LOG
      i=0; until service check media.audio_flinger 2>/dev/null | grep -q ": found"; do sleep 0.1; i=$((i+1)); [ $i -gt 50 ] && break; done
      reapply=1
    fi
    lastpid=$apid
  fi
  h=$(cat /sys/class/switch/h2w/state 2>/dev/null)
  b=$(cat /sys/class/switch/balance/state 2>/dev/null)
  out35=$(grep '^out35=' $DIR/settings.conf 2>/dev/null | tail -1 | cut -d= -f2)
  out44=$(grep '^out44=' $DIR/settings.conf 2>/dev/null | tail -1 | cut -d= -f2)
  if [ -n "$b" ] && [ "$b" != "0" ]; then
    want=Balance     # also for 4.4 mm line out: "BalanceLo" powers the DAC off on the R4 (tested 2026-11-20)
  elif [ -n "$h" ] && [ "$h" != "0" ]; then
    want=Headphone   # also for 3.5 mm line out: HiBy's "Lineout" mode never powers the DAC on the R4
  else want=none
  fi
  # Outputs are registered with Android by its own WiredAccessoryManager (SongateOS patch apply_wired.py, the way
  # HiBy's system does it): wired headphones with the address Headphone / Balance, which Android passes to HiBy's HAL
  # ("Balance=;connect=8") and re-routes running streams. This loop only reports the output and handles the line outs.
  # Line out (3.5 mm: out35=lineout, 4.4 mm: out44=bal_lo) = headphone path at fixed full volume (the HiBy R4 service
  # sets Android's "full volume" behaviour for the wired headphones while the line-out jack is the one in use; the
  # user's own headphone volume stays saved) + line-out gain = line-out level. Shown as Lineout / BalanceLo.
  # The service also ends it the moment the plug is pulled; this is the backup (out35 -> headphone, out44 -> bal_po),
  # so earphones plugged in later never get full volume.
  if [ "$out35" = "lineout" ] && [ "$want" != "Headphone" ]; then
    sh $BIN/hiby_settings.sh set out35 headphone >/dev/null 2>&1; out35=headphone
    echo "$(date) 3.5 mm line out: plug removed -> back to headphone" >> $LOG
  fi
  if [ "$out44" = "bal_lo" ] && [ "$want" != "Balance" ]; then
    sh $BIN/hiby_settings.sh set out44 bal_po >/dev/null 2>&1; out44=bal_po
    echo "$(date) 4.4 mm line out: plug removed -> back to headphones" >> $LOG
  fi
  shown=$want; lo=0
  [ "$out35" = "lineout" ] && [ "$want" = "Headphone" ] && { lo=1; shown=Lineout; }
  [ "$out44" = "bal_lo" ] && [ "$want" = "Balance" ] && { lo=1; shown=BalanceLo; }
  if [ "$lo" = "1" ] && [ "$(cat $DIR/lo_active 2>/dev/null)" != "$shown" ]; then
    echo "$shown" > $DIR/lo_active
    g=$(sh $BIN/hiby_settings.sh get lo_gain); setprop vendor.audio.hiby.hw.gain "$g"
    setprop sys.hibyr4.st_output "$shown"   # LineOut reads it: full volume only for the jack in line-out mode
    am broadcast -n com.andre.hibyr4.service/com.andre.hibyr4sound.LineOut >/dev/null 2>&1
    echo "$(date) $shown line out ON (fixed full volume, gain $g)" >> $LOG
  elif [ "$lo" = "0" ] && [ -f $DIR/lo_active ]; then
    rm -f $DIR/lo_active
    g=$(sh $BIN/hiby_settings.sh get gain); setprop vendor.audio.hiby.hw.gain "$g"
    setprop sys.hibyr4.st_output "$shown"
    am broadcast -n com.andre.hibyr4.service/com.andre.hibyr4sound.LineOut >/dev/null 2>&1
    echo "$(date) line out OFF (normal volume, gain $g)" >> $LOG
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
  # USB speaker / DAC (Android's USB audio HAL, not HiBy's driver): while one plays, it is the output, at the rate
  # and bit depth its stream was opened with (the kernel's own record), so Songate can show "song -> output"
  # (e.g. 44.1 kHz -> 48 kHz on a speaker that only takes 48 kHz). Values: st_output=USB, st_out_rate, st_out_bits,
  # st_usb_name (the device's name). When USB stops, HiBy's output name comes back.
  if [ $((n % 2)) -eq 1 ]; then
    ur=""; ub=""; un=""
    for c in /proc/asound/card[0-9]*; do
      [ -f $c/usbid ] || continue
      grep -q RUNNING $c/pcm0p/sub0/status 2>/dev/null || continue
      ur=$(sed -n 's/^rate: \([0-9]*\).*/\1/p' $c/pcm0p/sub0/hw_params)
      case "$(sed -n 's/^format: //p' $c/pcm0p/sub0/hw_params)" in
        S16_LE) ub=16 ;; S24_3LE|S24_LE) ub=24 ;; S32_LE) ub=32 ;; *) ub="" ;;
      esac
      cn=${c##*/card}
      un=$(sed -n "s/^ *$cn \[[^]]*\]: USB-Audio - //p" /proc/asound/cards)
      break
    done
    if [ -n "$ur" ] && [ "$ur$ub$un" != "$lastusb" ]; then
      setprop sys.hibyr4.st_output USB; setprop sys.hibyr4.st_out_rate "$ur"; setprop sys.hibyr4.st_out_bits "$ub"
      setprop sys.hibyr4.st_usb_name "$un"
      echo "$(date) USB output: ${un:-USB audio} $ur Hz ${ub:-?}-bit" >> $LOG
      lastusb="$ur$ub$un"
    elif [ -z "$ur" ] && [ -n "$lastusb" ]; then
      setprop sys.hibyr4.st_output "${last:-none}"; setprop sys.hibyr4.st_out_bits ""; setprop sys.hibyr4.st_usb_name ""
      setprop sys.hibyr4.st_out_rate "$(getprop vendor.audio.hiby.hw.sample_rate)"
      echo "$(date) USB output stopped" >> $LOG
      lastusb=""
    fi
  fi
  # "Volume after restart = fixed level": once per boot, when an output is plugged in after boot
  if [ "$want" != "none" ] && [ -z "$volset" ] && [ "$(getprop sys.boot_completed)" = "1" ]; then
    volset=1
    if [ "$(sh $BIN/hiby_settings.sh get vol_restore)" = "fixed" ]; then
      pct=$(sh $BIN/hiby_settings.sh get vol_boot); steps=$(getprop ro.config.media_vol_steps); steps=${steps:-25}
      idx=$(( (pct * steps + 50) / 100 ))
      cmd media_session volume --stream 3 --set $idx >/dev/null 2>&1
      echo "$(date) start volume: $pct% -> $idx/$steps" >> $LOG
    fi
  fi
  if [ "$shown" != "$last" ]; then
    # (a mode switch no longer re-registers the 4.4 mm with Android: it stays "Balance", only the volume changes)
    echo "$(date) h2w=$h balance=$b -> $shown" >> $LOG
    setprop sys.hibyr4.st_output "$shown"   # shown in the Settings page header; LineOut / the API read it
    last=$shown
  fi
  [ "$reapply" = "1" ] && { reapply=0; sh $BIN/hiby_settings.sh apply_all >> $LOG 2>&1; }
  sleep 1
done
