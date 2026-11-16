#!/system/bin/sh
# Runs early at boot (before audioserver / the audio HAL start), independent of Magisk's automatic overlay:
# 1) audio policy with the Wired Headset/Headphones ports
# 2) HiBy's 32-bit audio HAL with a 4-byte patch (0x50234: beq->nop): a bare "connect=8" from Android
#    selects HiBy's own Headphone/analog output instead of "Default" (= S/PDIF). Original: sha256 308be9c2...
#    + 2026-11-16 (sha 47c367e1): 0x4eb7c/0x4ebd4 bne->b in HiBy's set_master_volume: it no longer writes Android's
#    master volume x100 into the DAC volume ("Plat L/R") on headphone/balanced. Our master volume 1.0 (Bluetooth fix,
#    service.sh) put the DAC to 100 for ~1 s after every audioserver restart (out_rate switch). The DAC volume is
#    now only set by the HiBy R4 service (DacVolume) or kept at 100 by hiby_settings.sh when bit_perfect=0.
MODDIR=${0%/*}
L=/data/local/tmp/hiby_r4_pfd.log; : > $L   # new log each boot
F=/vendor/etc/audio_policy_configuration.xml
M=$MODDIR/system/vendor/etc/audio_policy_configuration.xml
chcon u:object_r:vendor_configs_file:s0 $M
# 1b) native rate (setting out_rate, switched at runtime by hiby_settings.sh rate_switch): the headphone music
#     output (mixPort deep_buffer) lists only the last rate used, so it opens at that rate after a reboot.
OR=$(grep '^out_rate=' /data/adb/hiby_r4/settings.conf 2>/dev/null | tail -1 | cut -d= -f2)
case "$OR" in 44100|48000|88200|96000|176400|192000|352800|384000)
  sed "/name=\"deep_buffer\"/,/<\/mixPort>/s/samplingRates=\"[^\"]*\"/samplingRates=\"$OR\"/" $M > /data/adb/hiby_r4/apc.tmp
  grep -q "samplingRates=\"$OR\"" /data/adb/hiby_r4/apc.tmp && cat /data/adb/hiby_r4/apc.tmp > $M
  rm -f /data/adb/hiby_r4/apc.tmp ;;
esac
grep -q "Wired Headphones" $F || mount -o bind $M $F
# 1c) Bit-perfect on the wired outputs (setting bit_perfect, default on; 2026-11-16). Android 14 offers bit-perfect
#     (setPreferredMixerAttributes) only for USB devices, and HiBy's Android 11 HAL has no BIT_PERFECT flag. Two
#     patched copies of this GSI's libraries are mounted over the originals, only if the originals are exactly the
#     ones they were made from (a system update leaves them alone):
#     - libaudiopolicymanagerdefault.so: the 2 "is not usb device" checks (get/setPreferredMixerAttributes) skipped
#     - libaudiohal@6.0.so: the unused SPATIALIZER rewrite before the HAL call now turns BIT_PERFECT into DIRECT
#       (HiBy's HAL then opens its own direct path; with no flag it took it for a 2nd primary output and refused).
#     The policy's "bit_perfect" mixPort is used only when an app asks for bit-perfect. Volume: in the DAC (service).
#     Tested 2026-11-16: 44.1 kHz/24-bit, BitPerfect=true, HiBy's mixer in bypass, DAC opened at 44.1 kHz/24-bit.
BP=$(grep '^bit_perfect=' /data/adb/hiby_r4/settings.conf 2>/dev/null | tail -1 | cut -d= -f2)
if [ "$BP" != "0" ]; then
  A=/system/lib64/libaudiopolicymanagerdefault.so; AP=$MODDIR/bitperfect/libaudiopolicymanagerdefault.so
  H6=/system/lib64/libaudiohal@6.0.so; HP6=$MODDIR/bitperfect/libaudiohal@6.0.so
  if [ "$(sha256sum $A | cut -c1-16)" = "c27eda20ad66b76d" ] && [ "$(sha256sum $H6 | cut -c1-16)" = "0fa791a3ab4db2dd" ] \
     && [ "$(sha256sum $AP | cut -c1-16)" = "5dc062e9a595e605" ] && [ "$(sha256sum $HP6 | cut -c1-16)" = "e9498256583158ff" ]; then
    chcon u:object_r:system_lib_file:s0 $AP $HP6
    mount -o bind $AP $A && mount -o bind $HP6 $H6
    echo "$(date) post-fs-data: bit-perfect libraries mounted ($(sha256sum $A | cut -c1-8) $(sha256sum $H6 | cut -c1-8))" >> $L
  else
    echo "$(date) post-fs-data: bit-perfect NOT enabled: system libraries differ from the ones the patches were made for" >> $L
  fi
fi
echo "$(date) post-fs-data: policy ports present: $(grep -c 'Wired Head' $F)" >> $L
H=/vendor/lib/hw/audio.primary.trinket.so
HP=$MODDIR/system/vendor/lib/hw/audio.primary.trinket.so
if [ -f $HP ]; then
  chcon u:object_r:vendor_file:s0 $HP
  [ "$(sha256sum $H | cut -c1-16)" = "$(sha256sum $HP | cut -c1-16)" ] || mount -o bind $HP $H
  echo "$(date) post-fs-data: audio HAL sha256 now $(sha256sum $H | cut -c1-16)" >> $L
fi
# 3) media volume steps chosen in the app (15/25/50/100); must be set before system_server starts
VS=$(grep '^vol_steps=' /data/adb/hiby_r4/settings.conf 2>/dev/null | tail -1 | cut -d= -f2)
case "$VS" in 15|25|50|100) resetprop ro.config.media_vol_steps "$VS"; echo "$(date) post-fs-data: media_vol_steps=$VS" >> $L ;; esac
# 3b) Media buttons and the screen (setting btn_wake, default 0). HiBy's gpio-keys.kl marks play/pause (key 164) WAKE,
#     so it turns the screen on; previous/next aren't marked. With btn_wake=0 our copy without WAKE is mounted over
#     it (HiBy's file is not changed). Android reads key layouts once at start, so this must happen here.
BW=$(grep '^btn_wake=' /data/adb/hiby_r4/settings.conf 2>/dev/null | tail -1 | cut -d= -f2)
KL=/vendor/usr/keylayout/gpio-keys.kl; KN=$MODDIR/keylayout/gpio-keys-nowake.kl
if [ "$BW" != "1" ] && [ -f $KN ]; then
  chcon u:object_r:vendor_keylayout_file:s0 $KN
  mount -o bind $KN $KL
fi
echo "$(date) post-fs-data: btn_wake=${BW:-0}, play/pause line: $(grep 'key 164' $KL)" >> $L
# 4) Android's "safe media volume" (EU hearing-protection cap) set the headphone volume back to 10/50 at every
#    boot and shows the "raise volume above recommended level" warning. Bypassed; the start volume is chosen in
#    Settings > HiBy audio > Volume after restart (keep last / fixed level, applied by service.sh).
resetprop audio.safemedia.bypass true
echo "$(date) post-fs-data: audio.safemedia.bypass=$(getprop audio.safemedia.bypass)" >> $L
