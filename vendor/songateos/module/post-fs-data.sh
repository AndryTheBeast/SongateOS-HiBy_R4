#!/system/bin/sh
# SongateOS (HiBy R4): early boot, before audioserver / the audio HAL / system_server start. Root, run by init from
# /system_ext/etc/init/songateos.rc ("on post-fs-data"). Was the Magisk module's post-fs-data.sh.
# 1) audio policy with the Wired Headset/Headphones ports and the bit_perfect mixPort (copy in /data, bind-mounted;
#    the native-rate setting edits that copy)
# 2) HiBy's 32-bit audio HAL with 3 instruction patches, made from THIS player's own /vendor file (HiBy's file is never
#    shipped): a bare "connect=8" selects HiBy's Headphone output (0x4f234 beq->nop), and HiBy's set_master_volume no
#    longer writes Android's master volume into the DAC volume (0x4db7c / 0x4dbd4 bne->b). Checked by SHA-256 before
#    and after; a different HAL (another firmware) is left alone.
# (Bit-perfect on the wired outputs needs no patch here: it's a source change in this image's audio framework.)
MODDIR=/system_ext/etc/songateos   # data files
BIN=/system_ext/bin/songateos       # scripts
D=/data/songateos; C=$D/hiby/settings.conf
mkdir -p $D/hiby; chmod 0700 $D
L=/data/local/tmp/hiby_r4_pfd.log; : > $L
conf() { grep "^$1=" $C 2>/dev/null | tail -1 | cut -d= -f2; }
sha() { sha256sum "$1" | cut -d' ' -f1; }

# 1) audio policy
F=/vendor/etc/audio_policy_configuration.xml
M=$D/audio_policy_configuration.xml
cp -f $MODDIR/audio_policy_configuration.xml $M
OR=$(conf out_rate)
case "$OR" in 44100|48000|88200|96000|176400|192000|352800|384000)
  sed "/name=\"deep_buffer\"/,/<\/mixPort>/s/samplingRates=\"[^\"]*\"/samplingRates=\"$OR\"/" $M > $M.tmp
  grep -q "samplingRates=\"$OR\"" $M.tmp && cat $M.tmp > $M
  rm -f $M.tmp ;;
esac
chmod 0644 $M; chcon u:object_r:vendor_configs_file:s0 $M
grep -q "Wired Headphones" $F || mount -o bind $M $F
echo "$(date) post-fs-data: policy ports present: $(grep -c 'Wired Head' $F)" >> $L

# 2) HiBy's audio HAL, patched from this player's own file
H=/vendor/lib/hw/audio.primary.trinket.so
HP=$D/audio.primary.trinket.so
H_IN=308be9c24dd2cc884c242fce1a4c7b1cb884998a69dfe480298b4ae8f62914b4
H_OUT=47c367e14a6198e378ee3b00af30a3cff607eff82cf9344c61ca40e227720c15
if [ ! -f $HP ] || [ "$(sha $HP)" != "$H_OUT" ]; then
  if [ "$(sha $H)" = "$H_IN" ]; then
    cp $H $HP.tmp
    for p in "0x4f234 00f020e3" "0x4db7c 030000ea" "0x4dbd4 030000ea"; do
      off=${p% *}; hex=${p#* }
      printf "\\x${hex%??????}\\x$(echo $hex | cut -c3-4)\\x$(echo $hex | cut -c5-6)\\x${hex#??????}" |
        dd of=$HP.tmp bs=1 seek=$((off)) conv=notrunc 2>/dev/null
    done
    if [ "$(sha $HP.tmp)" = "$H_OUT" ]; then mv -f $HP.tmp $HP; else rm -f $HP.tmp; fi
  else
    echo "$(date) post-fs-data: HiBy audio HAL is not the expected version, left alone" >> $L
  fi
fi
if [ -f $HP ] && [ "$(sha $HP)" = "$H_OUT" ]; then
  chmod 0644 $HP; chcon u:object_r:vendor_file:s0 $HP
  [ "$(sha $H)" = "$H_OUT" ] || mount -o bind $HP $H
fi
echo "$(date) post-fs-data: audio HAL sha256 now $(sha256sum $H | cut -c1-16)" >> $L

# 2b) headphone jack: the R4 reports it through the h2w switch (/sys/class/switch/h2w), not through input events.
#     TrebleDroid's vndk-detect (on post-fs, before this) sets persist.sys.overlay.devinputjack=true on every
#     Qualcomm non-Samsung device, which enables its static overlay config_useDevInputEventForAudioJack=true:
#     WiredAccessoryManager then never sees the jack and Android routes the 3.5/4.4 mm output as the speaker (no
#     wired bit-perfect, no wired DAC volume; SongateOS 0.1.0-beta.2). The overlay's property is checked when
#     system_server scans packages, after this.
setprop persist.sys.overlay.devinputjack false
echo "$(date) post-fs-data: devinputjack=$(getprop persist.sys.overlay.devinputjack)" >> $L

# 3) media volume steps chosen in Settings (15/25/50/100), before system_server reads them. TrebleDroid's
#    rw-system.sh (on post-fs, before this) already forces ro.config.media_vol_steps=25 with resetprop_phh, so a
#    plain setprop fails ("Read-only property was already set"; SongateOS 0.1.0-beta.2): override it the same way.
VS=$(conf vol_steps); [ -z "$VS" ] && VS=25
case "$VS" in 15|25|50|100)
  /system/bin/resetprop_phh ro.config.media_vol_steps "$VS"
  echo "$(date) post-fs-data: media_vol_steps=$VS -> $(getprop ro.config.media_vol_steps)" >> $L ;;
esac

# 3b) media buttons and the screen (setting btn_wake, default 0): HiBy's gpio-keys.kl marks play/pause (164) WAKE;
#     with btn_wake=0 a copy without WAKE is mounted over it (HiBy's file is not changed). Android reads key layouts
#     once at start, so this must happen here.
BW=$(conf btn_wake)
KL=/vendor/usr/keylayout/gpio-keys.kl; KN=$MODDIR/keylayout/gpio-keys-nowake.kl
if [ "$BW" != "1" ] && [ -f $KN ]; then mount -o bind $KN $KL; fi
echo "$(date) post-fs-data: btn_wake=${BW:-0}, play/pause line: $(grep 'key 164' $KL)" >> $L

# 4) Android's "safe media volume" cap (EU hearing protection) reset the headphone volume at every boot: bypassed; the
#    start volume is chosen in Settings (keep last / fixed level, applied by service.sh)
setprop audio.safemedia.bypass true
echo "$(date) post-fs-data: audio.safemedia.bypass=$(getprop audio.safemedia.bypass)" >> $L

# 5) Google apps removed (Settings > Google apps > Remove, or an update without "keep"; 2026-11-19): their updates
#    stay in /data without the system part they belong to, and Play services then crashes Android's core process at
#    boot (SecurityException, FLAG_SINGLE_USER / INTERACT_ACROSS_USERS: seen on the R4 twice). Before Android starts,
#    each leftover update whose system part is gone is removed; Android then drops the package and its data itself.
#    Only these three Google packages, and only when no copy of them is in the system image.
sys_has() {   # $1 = name pattern of the system app's folder
  for p in /system/priv-app /system/app /system/product/priv-app /system/product/app /product/priv-app /product/app \
           /system/system_ext/priv-app /system_ext/priv-app; do
    ls -d $p/$1 >/dev/null 2>&1 && return 0
  done
  return 1
}
for pair in "com.google.android.gms:*GmsCore*" "com.android.vending:Phonesky*" "com.google.android.gsf:GoogleServicesFramework*"; do
  pkg=${pair%%:*}; pat=${pair#*:}
  sys_has "$pat" && continue
  for d in /data/app/*/$pkg-* /data/app/$pkg-*; do
    [ -d "$d" ] || continue
    rm -rf "$d"
    echo "$(date) post-fs-data: Google apps not in the system: removed the leftover update $d" >> $L
  done
done

# 6) Android's parsed-package cache (/data/system/package_cache) is keyed by file path and date, and the recovery gives
#    every installed file the same date: after switching Google apps Core <-> Basic, Android kept the old Play Store's
#    description for the new file and the Play Store crashed on every start (2026-11-19). When the system's apps change
#    (names + sizes of every system APK), the cache is cleared before Android reads it; it rebuilds itself.
APKS=$(for p in /system/priv-app /system/app /system/product/priv-app /system/product/app /system/system_ext/priv-app \
         /system/system_ext/app /product/priv-app /product/app /system_ext/priv-app /system_ext/app; do
         ls -l $p/*/*.apk 2>/dev/null | awk '{print $5, $NF}'; done | sort | sha256sum | cut -d' ' -f1)
if [ "$APKS" != "$(cat $D/system_apks.sha 2>/dev/null)" ]; then
  rm -rf /data/system/package_cache/*
  echo "$APKS" > $D/system_apks.sha
  echo "$(date) post-fs-data: system apps changed: Android's package cache cleared" >> $L
fi
