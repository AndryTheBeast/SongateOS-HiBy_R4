#!/system/bin/sh
# Hold switch (the switch on top of the R4): root side. Started from service.sh.
# The switch is gpio-keys (/dev/input/event3) EV_SW SW_KEYPAD_SLIDE; measured on the R4: 0 = locked, 1 = unlocked.
# HiBy's key maps (gpio-keys.kl, qpnp_pon.kl) mix the buttons over two input devices:
#   gpio-keys (event3): 163 volume up, 165 volume down, 164 play/pause, 115 previous (+ the switch)
#   qpnp_pon  (event0): 116 power, 114 next
# While locked, the HiBy R4 service switches BOTH devices off in Android (every level), so Android ignores them even
# with the screen off. This helper still reads the hardware directly, so it can:
#  - report every flip to the service (content call hold_switch) and publish sys.hibyr4.hold = 1 locked / 0;
#  - pass on the buttons the level keeps working (content call hold_key):
#      "media":  volume up, volume down, power        (play/pause, previous, next locked)
#      "volume": power                                (+ volume locked)
#      "all":    nothing
# HiBy's driver can't switch single keys off (its disabled_keys file refuses these keys), hence whole devices.
# The wired headphone remote (earpods_adc2) and Bluetooth buttons are never touched.
LOG=/data/local/tmp/hiby_r4_fix.log
SIDE=/dev/input/event3    # gpio-keys: switch, volume up / down, play/pause, previous
PON=/dev/input/event0     # qpnp_pon: power, next
log() { echo "$(date) hold: $*" >> $LOG; }
svc() { content call --uri content://com.andre.hibyr4.settings --method "$1" --arg "$2" ${3:+--extra "$3"} > /dev/null 2>&1; }
locked() { [ "$(getprop sys.hibyr4.hold)" = 1 ]; }
level() { getprop sys.hibyr4.hold_level; }

flip() {      # $1 = 1 locked / 0 unlocked, $2 = 1 for the starting state (no sleep / wake)
  setprop sys.hibyr4.hold $1
  svc hold_switch "$1" "boot:s:${2:-0}"
  log "switch $([ "$1" = 1 ] && echo locked || echo unlocked)"
}

[ -e $SIDE ] && [ -e $PON ] || { log "button devices not found, not starting"; exit 0; }

# starting state: unlocked, unless Android's input reader already knows the switch.
# dumpsys shows SwitchValues in hex; bit 10 (0x400) = SW_KEYPAD_SLIDE set = unlocked. 0 can also mean "not known yet".
setprop sys.hibyr4.hold 0
sv=$(dumpsys input | awk '/Device [0-9]+: gpio-keys/{f=1} f&&/SwitchValues:/{print $2; exit}')
if [ -n "$sv" ] && [ $(( 0x$sv & 0x400 )) -ne 0 ]; then flip 0 1; else svc hold_switch 0 "boot:s:1"; fi

# power: passed on at "media" and "volume"
( getevent -ql $PON 2>/dev/null | while read type code value; do
    [ "$code" = KEY_POWER ] && [ "$value" = DOWN ] && locked && case "$(level)" in media|volume) svc hold_key power ;; esac
  done
  log "power watcher ended" ) &

# switch flips; volume up / down passed on at "media" (raw codes 163 / 165, see the key map above)
getevent -ql $SIDE 2>/dev/null | while read type code value; do
  case "$code" in
    SW_KEYPAD_SLIDE)
      case "$value" in 00000000) flip 1 ;; *) flip 0 ;; esac ;;
    KEY_NEXTSONG|KEY_PREVIOUSSONG)
      case "$value" in
        DOWN|REPEAT) locked && [ "$(level)" = media ] &&           svc hold_key "$([ "$code" = KEY_NEXTSONG ] && echo volup || echo voldown)" ;;
      esac ;;
  esac
done
log "switch watcher ended"
