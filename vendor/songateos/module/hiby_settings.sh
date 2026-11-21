#!/system/bin/sh
# HiBy R4 sound settings (personal module). Run as root.
#   hiby_settings.sh list            -> key=value for every setting (current saved values)
#   hiby_settings.sh set KEY VALUE   -> validate, save, apply now
#   hiby_settings.sh apply_all       -> apply every saved setting (used at boot)
# Values are applied the way HiBy OS did it: vendor properties that HiBy's own
# /vendor/etc/init/hw/init.hiby.audio.rc forwards to the DAC driver (sa_sound_setting).
DIR=/data/songateos/hiby
BIN=/system_ext/bin/songateos
CONF=$DIR/settings.conf
LOG=/data/local/tmp/hiby_r4_fix.log
S=/sys/devices/platform/sa_sound_setting
mkdir -p $DIR

# key | allowed values (regex) | default
defaults() {
cat <<'EOF'
gain|low|high|low
filter|fast_rolloff|slow_rolloff|minimum_phase|fast_rolloff
balance|-?([0-9]|10)|0
out35|headphone|lineout|headphone
out44|bal_po|bal_lo|bal_po
lo_gain|low|high|high
dsd_comp|[0-9]|6
remote|0|1|1
led_mode|hiby|off|custom|hiby
led_color|[0-9a-fA-F]{6}|00a0ff
led_bright|[0-9]|[1-9][0-9]|100|60
led_effect|solid|breath|solid
led_charge|0|1|1
led_screen|0|1|0
led_back|on|screen|off|music|screen
led_back_charge|0|1|0
led_back_notif|0|1|0
led_back_beat|0|1|0
led_notif|0|1|0
led_notif_color|[0-9a-fA-F]{6}|4fc3ff
led_lowbat|0|1|0
led_lowbat_level|5|10|15|20|25|30|15
led_quality|0|1|0
led_q_cd|[0-9a-fA-F]{6}|ff9030
led_q_hires|[0-9a-fA-F]{6}|ff3c30
led_q_ultra|[0-9a-fA-F]{6}|ff3c8c
led_beat|0|1|0
vol_steps|15|25|50|100|25
vol_restore|last|fixed|last
vol_boot|[0-9]|[1-9][0-9]|100|20
btn_wake|0|1|0
out_rate|44100|48000|88200|96000|176400|192000|352800|384000|96000
bit_perfect|0|1|1
EOF
}

# ---------- status LED (SGM31324 RGB driver, 10 register bytes per pattern, from HiBy's device tree) ----------
# byte4 = 0x40 | blue<<4 | green<<2 | red  (per channel: 1 = on, 2 = breathing); bytes 6/7/8 = red/green/blue level;
# bytes 1-3 and 5 = breathing timing (copied from HiBy's charging pattern); byte9 = 0x06 (as in all HiBy patterns).
LEDDIR=/sys/bus/i2c/devices/i2c-2/2-0030/leds/sgm31324-leds
led_custom_pattern() {   # optional args: colour brightness effect (else the saved values)
  c=${1:-$(get led_color)}; br=${2:-$(get led_bright)}; fx=${3:-$(get led_effect)}
  r=$((0x${c%????})); g=$((0x$(echo $c | cut -c3-4))); b=$((0x${c#????}))
  # max level 0x60 (as HiBy's patterns). Gamma 2.2 + per-channel balance: the LED's green is much brighter
  # than red (orange looked yellow, yellow looked green-yellow - tested 2026-11-14).
  set -- $(awk -v r=$r -v g=$g -v b=$b -v br=$br -v gr=${LED_GR:-1.0} -v gg=${LED_GG:-0.45} -v gb=${LED_GB:-0.8} 'function L(c,k){ if(c<=0) return 0; v=int(96*((c/255)^2.2)*k*br/100+0.5); return v<1?1:v }
    BEGIN{ print L(r,gr), L(g,gg), L(b,gb) }')
  R=$1; G=$2; B=$3
  m=1; t1=0; t2=0; t3=0; t5=0
  [ "$fx" = "breath" ] && { m=2; t1=30; t2=125; t5=187; }
  mr=0; mg=0; mb=0; [ $R -gt 0 ] && mr=$m; [ $G -gt 0 ] && mg=$m; [ $B -gt 0 ] && mb=$m
  e=$(( (mr + mg + mb) > 0 ? (0x40 | (mb << 4) | (mg << 2) | mr) : 0 ))
  printf '00%02X%02X%02X%02X%02X%02X%02X%02X06' $t1 $t2 $t3 $e $t5 $R $G $B
}
# HiBy's own pattern choice, same rules as /vendor/etc/init/hw/init.hiby.led.rc (its triggers only fire when a
# property changes, so switching back from "custom" left the custom colour on - we write the pattern ourselves).
hiby_pattern() {
  if [ "$(getprop vendor.audio.hiby.charging)" = "yes" ]; then
    [ "$(getprop vendor.audio.hiby.chargetype)" = "usb_hvdcp" ] && echo 7 || echo 6; return
  fi
  case $(getprop vendor.audio.hiby.hw.sample_quality) in
    low) echo 2 ;; standard) echo 3 ;; high) echo 4 ;; dsd) echo 5 ;; mqa) echo 8 ;; mqastudio) echo 9 ;; mqb) echo 10 ;;
    *) [ "$(getprop vendor.audio.hw.screen_state)" = "off" ] && echo 0 || echo 1 ;;
  esac
}
led_refresh() {   # called on setting changes and every few seconds by service.sh (writes only on change)
  # a live colour/brightness change from the Settings page is not saved yet: don't put the saved colour back
  [ "$FORCE" != "1" ] && [ -f $DIR/led_live ] && return
  mode=$(get led_mode)
  st=$(cat /sys/class/power_supply/cw2015/status 2>/dev/null); chg=no; [ "$st" = "Charging" ] && chg=yes
  scr=$(getprop debug.tracing.screen_state)   # 2 = screen on, 1 = off
  case $mode in
    hiby)
      setprop vendor.audio.hiby.chargetype normal
      [ "$(getprop vendor.audio.hiby.charging)" != "$chg" ] && setprop vendor.audio.hiby.charging $chg
      [ "$(getprop vendor.audio.hiby.hw.led)" != "on" ] && setprop vendor.audio.hiby.hw.led on
      [ "$(getprop vendor.audio.hiby.hw.led_gpio)" != "on" ] && setprop vendor.audio.hiby.hw.led_gpio on   # HiBy rules own the back light
      want="pat:$(hiby_pattern)" ;;
    off)
      [ "$(getprop vendor.audio.hiby.hw.led)" != "off" ] && { setprop vendor.audio.hiby.hw.led off; setprop vendor.audio.hiby.hw.led_gpio off; }
      # HiBy's driver only turns off its own pattern: a colour the service drew in custom mode stayed on (custom -> off,
      # 2026-11-18). Write the chip's "off" pattern too, so off is off whatever the mode was before.
      want="raw:00000000000000000006" ;;
    custom)
      # "custom" matches none of HiBy's init triggers, so they no longer overwrite our pattern
      [ "$(getprop vendor.audio.hiby.hw.led)" != "custom" ] && setprop vendor.audio.hiby.hw.led custom
      # "custom" matches none of HiBy's led_gpio rules, so led_enable is free. led_enable is NOT the RGB chip's power:
      # it is the device tree's "ambience-gpio" = the white back light behind the orange strips (tested 2026-11-15).
      # In custom mode the HiBy R4 service app draws both lights (Lights.java: own colour, charging, off with screen,
      # notification, low battery, audio quality colour, beat pulse; back light modes). Nothing to write here.
      [ "$(getprop vendor.audio.hiby.hw.led_gpio)" != "custom" ] && setprop vendor.audio.hiby.hw.led_gpio custom
      want="app" ;;
  esac
  # an LED setting changed: let the service app re-check the lights (it acts only in custom mode)
  [ "$FORCE" = "1" ] && am broadcast -n com.andre.hibyr4.service/com.andre.hibyr4sound.Lights >/dev/null 2>&1 &
  if [ "$want" = "app" ]; then echo app > $DIR/led_state; echo "app scr=$scr" > $DIR/led_key; return; fi
  # redraw after every screen on/off too: if the LED power was cut meanwhile, the chip lost our pattern
  ckey="$want scr=$scr"
  [ "$FORCE" != "1" ] && [ "$ckey" = "$(cat $DIR/led_key 2>/dev/null)" ] && return
  echo "$ckey" > $DIR/led_key
  case $want in
    # the driver ignores a pattern index equal to its last one (even after a raw write_pattern): 0 first
    pat:*) echo 0 > $LEDDIR/led_pattern; echo ${want#pat:} > $LEDDIR/led_pattern ;;
    raw:*) echo ${want#raw:} > $LEDDIR/write_pattern ;;
  esac
  echo "$want" > $DIR/led_state
  # tell the Settings page whether it may draw the colour itself while the wheel is dragged
  case $want in raw:00000000000000000006) st=off ;; raw:*) st=custom ;; *) st=other ;; esac
  [ "$(getprop sys.hibyr4.st_led)" != "$st" ] && setprop sys.hibyr4.st_led $st
}
allowed() { defaults | grep "^$1|" | cut -d'|' -f2- | sed 's/|[^|]*$//'; }
default_of() { defaults | grep "^$1|" | awk -F'|' '{print $NF}'; }
get() { v=$(grep "^$1=" $CONF 2>/dev/null | tail -1 | cut -d= -f2-); [ -n "$v" ] && echo "$v" || default_of "$1"; }

apply_key() {
  k=$1; v=$2
  case $k in
    gain)          # while 3.5 mm line out is active (service.sh), the DAC gain is the line-out level
                   [ -f $DIR/lo_active ] && v=$(get lo_gain)
                   setprop vendor.audio.hiby.hw.gain "$v" ;;
    filter)        setprop vendor.audio.hiby.hw.digital_filter "$v" ;;
    balance)       # Android's own master balance (AudioFlinger mixer, ramped in software): no DAC writes, so no
                   # dip when changing (tested by ear 2026-11-14). -10..10 -> -1.0..1.0 (+-10 = other side silent).
                   # The DAC's platform L/R volumes stay at 100 (writing them briefly muted the DAC = the old dips);
                   # HiBy's lrbalance stays 0 (odd values broke one channel, tested).
                   setprop vendor.audio.hiby.hw.lrbalance 0
                   # bit_perfect on (default): wired volume AND balance are done in the DAC's platform L/R volumes by
                   # the HiBy R4 service (DacVolume), so Android's software balance stays 0 (it would not apply to
                   # bit-perfect streams and would come on top for the others). The service reads sys.hibyr4.balance.
                   if [ "$(get bit_perfect)" != "0" ]; then
                     settings put system master_balance 0
                     am broadcast -n com.andre.hibyr4.service/com.andre.hibyr4sound.DacVolume >/dev/null 2>&1 &
                     rm -f $DIR/plat_lr
                     return 0
                   fi
                   if [ "$FORCE" = "1" ] || [ "$(cat $DIR/plat_lr 2>/dev/null)" != "100 100" ]; then
                     tinymix -D 0 "Plat Left Playback Volume" 100 >/dev/null 2>&1
                     tinymix -D 0 "Plat Right Playback Volume" 100 >/dev/null 2>&1
                     echo "100 100" > $DIR/plat_lr
                   fi
                   a=${v#-}; s=""; [ "$v" -lt 0 ] && s="-"
                   if [ "$a" = "10" ]; then mb="${s}1.0"; elif [ "$a" = "0" ]; then mb="0"; else mb="${s}0.$a"; fi
                   settings put system master_balance "$mb" ;;
    # 4.4 mm line out = the "Balance" path at fixed full volume (service.sh), like the 3.5 mm. The R4's driver ignores
    # bal_po_lo_switch (tested 2026-11-20: bal_lo still plays at headphone level); set anyway, as HiBy's system does.
    out44)         setprop vendor.audio.hiby.hw.bal_po_lo_switch "$v" ;;
    # 3.5 mm line out = the headphone path at fixed full volume (service.sh). HiBy's own "Lineout" output mode
    # never powers the DAC on the R4 (i2c error -107, HAL write errors, froze once) - never used.
    out35)         ;;
    lo_gain)       echo "$v" > $S/lo_gain
                   [ -f $DIR/lo_active ] && setprop vendor.audio.hiby.hw.gain "$v" ;;
    dsd_comp)      setprop vendor.audio.hiby.hw.dsd_compensate "$v" ;;
    remote)        [ "$v" = "1" ] && setprop vendor.audio.hw.earpods_enable enable || setprop vendor.audio.hw.earpods_enable disable ;;  # driver wants words
    led_*)         FORCE=1 led_refresh ;;
    btn_wake) ;;                         # read by post-fs-data.sh at the next boot (key layout, see there)
    out_rate)      ( sh $0 rate_switch "$v" >/dev/null 2>&1 & ) ;;   # in the background: it restarts audioserver
    bit_perfect) ;;                      # used at the next boot (post-fs-data.sh libraries, service DacVolume)
    vol_steps|vol_restore|vol_boot) ;;   # used at boot (post-fs-data.sh / service.sh)   # read by post-fs-data.sh at the next boot (ro.config.media_vol_steps via resetprop)
  esac
  echo "$(date) setting $k=$v applied" >> $LOG
}

case "$1" in
  list)
    for k in $(defaults | cut -d'|' -f1); do echo "$k=$(get $k)"; done ;;
  set)
    k=$2; v=$3; re=$(allowed "$k")
    [ -z "$re" ] && { echo "ERR unknown key $k"; exit 2; }
    echo "$v" | grep -Eqx -e "$re" || { echo "ERR bad value $v for $k (allowed: $re)"; exit 3; }
    grep -v "^$k=" $CONF 2>/dev/null > $CONF.tmp; echo "$k=$v" >> $CONF.tmp; mv $CONF.tmp $CONF
    apply_key "$k" "$v"; setprop "sys.hibyr4.$k" "$v"; echo "OK $k=$v" ;;
  get)
    get "$2" ;;
  setmany)   # KEY VALUE [KEY VALUE ...]: validate all, save all, then apply each (one LED refresh for led_* keys)
    shift; args="$*"
    while [ $# -ge 2 ]; do
      re=$(allowed "$1"); [ -z "$re" ] && { echo "ERR unknown key $1"; exit 2; }
      echo "$2" | grep -Eqx -e "$re" || { echo "ERR bad value $2 for $1"; exit 3; }
      shift 2
    done
    set -- $args; led=0
    while [ $# -ge 2 ]; do
      grep -v "^$1=" $CONF 2>/dev/null > $CONF.tmp; echo "$1=$2" >> $CONF.tmp; mv $CONF.tmp $CONF
      setprop "sys.hibyr4.$1" "$2"
      case $1 in led_*) led=1 ;; *) apply_key "$1" "$2" ;; esac
      shift 2
    done
    [ $led = 1 ] && FORCE=1 led_refresh
    echo "OK $args" ;;
  boot_reset)   # at boot: 3.5 mm line out back to headphone (earphones may have been plugged in while off)
    rm -f $DIR/lo_active
    grep -q '^out35=lineout' $CONF 2>/dev/null && { grep -v '^out35=' $CONF > $CONF.tmp; echo "out35=headphone" >> $CONF.tmp; mv $CONF.tmp $CONF; }
    true ;;
  apply_all)
    FORCE=1
    for k in $(defaults | cut -d'|' -f1); do apply_key "$k" "$(get $k)"; done ;;
  publish)   # values for the Settings pages (sys.hibyr4.KEY, settable by Settings; service.sh applies changes)
    for k in $(defaults | cut -d'|' -f1); do setprop "sys.hibyr4.$k" "$(get $k)"; done
    setprop sys.hibyr4.ready 1 ;;
  rate_switch)   # $2 = rate. Native rate on the wired outputs (2026-11-15).
    # Android opens the headphone music output (mixPort "deep_buffer") once, at the highest rate the policy lists, and
    # HiBy's HAL runs the DAC at that rate; changing it while open makes audioserver abort (tested). So: the policy
    # lists only the wanted rate, and audioserver is restarted (~2 s) when it differs. Tested: 44.1 and 192 kHz end to
    # end (Songate -> mixer -> HAL pcm_open rate). Status: sys.hibyr4.st_out_rate = open rate, 0 while switching.
    R=$2; POL=$MODDIR_POL; [ -z "$POL" ] && POL=/data/songateos/audio_policy_configuration.xml
    until mkdir $DIR/rate_lock 2>/dev/null; do sleep 0.2; done          # one switch at a time
    trap 'rmdir $DIR/rate_lock 2>/dev/null' EXIT
    [ "$(get out_rate)" = "$R" ] || { rmdir $DIR/rate_lock; exit 0; }   # superseded by a newer request
    # dumpsys output is always read to the end into a variable first: if a reader closes the pipe early, HiBy's audio
    # HAL gets SIGPIPE while dumping and dies (seen 2026-11-16 with "dumpsys media.audio_flinger | head").
    open_rate() {
      ap=$(dumpsys media.audio_policy 2>/dev/null)
      h=$(echo "$ap" | grep "IOProfile name:deep_buffer" | grep -o "I/O handle: [0-9]*" | grep -o "[0-9]*$")
      [ -n "$h" ] || return
      af=$(dumpsys media.audio_flinger 2>/dev/null)
      echo "$af" | grep -A3 "name AudioOut_$(printf %X $h)," | grep -m1 "Sample rate:" | grep -o "[0-9]*"
    }
    cur=$(grep -A4 'name="deep_buffer"' $POL | grep -m1 -o 'samplingRates="[^"]*"' | cut -d'"' -f2)
    if [ "$cur" = "$R" ]; then                             # policy already right (e.g. at boot): never restart
      i=0; until [ "$(open_rate)" = "$R" ]; do sleep 0.5; i=$((i + 1)); [ $i -gt 40 ] && break; done
      setprop sys.hibyr4.st_out_rate "$(open_rate)"; echo "OK already $R"; exit 0
    fi
    setprop sys.hibyr4.st_out_rate 0
    sed "/name=\"deep_buffer\"/,/<\/mixPort>/s/samplingRates=\"[^\"]*\"/samplingRates=\"$R\"/" $POL > $DIR/apc.tmp
    grep -q "samplingRates=\"$R\"" $DIR/apc.tmp || { echo "ERR policy edit"; exit 4; }
    cat $DIR/apc.tmp > $POL; rm -f $DIR/apc.tmp            # in place: the file is bind-mounted over /vendor/etc
    killall audioserver
    i=0; until [ "$(open_rate)" = "$R" ]; do sleep 0.25; i=$((i + 1)); [ $i -gt 60 ] && break; done
    o=$(open_rate); setprop sys.hibyr4.st_out_rate "${o:-0}"
    echo "$(date) out_rate $cur -> $R: output open at ${o:-?} Hz after $((i / 4)) s" >> $LOG
    echo "OK $o" ;;
  led_refresh) led_refresh ;;
  led_pattern_preview) led_custom_pattern; echo ;;
  led_live)   # $2 colour $3 brightness $4 effect: show it now (custom colour on screen only), nothing saved
    case $(cat $DIR/led_state 2>/dev/null) in
      raw:00000000000000000006) ;;   # "off with screen" is active
      raw:*) echo "$2" | grep -Eqx '[0-9a-fA-F]{6}' && echo "$3" | grep -Eqx '[0-9]|[1-9][0-9]|100' || exit 3
             p=$(led_custom_pattern "$2" "$3" "$4"); echo "$p" > $LEDDIR/write_pattern; echo "raw:$p" > $DIR/led_state ;;
    esac ;;
  *) echo "usage: $0 list | get KEY | set KEY VALUE | apply_all | publish | led_refresh"; exit 1 ;;
esac
