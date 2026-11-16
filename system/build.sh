#!/bin/bash
# Build the SongateOS system module for the HiBy R4 (Linux or WSL; no Gradle).
#
# Needs:
#   ANDROID_SDK   Android SDK with build-tools 34.0.0 and platforms/android-34 (default: ~/android-sdk)
#   PLATFORM_KEY  folder with platform.pk8 + platform.x509.pem: the AOSP *test* platform key, which is the key the
#                 LineageOS GSI is signed with (AOSP build/make/target/product/security; default: ./keys)
#   SETTINGS_APK  the GSI's own Settings.apk, pulled from your R4 (adb pull /system_ext/priv-app/Settings/Settings.apk)
#
# Output: out/hiby_r4_gsi_fix/ = the module folder without the patched libraries. Copy it to the R4
# (/data/adb/modules/hiby_r4_gsi_fix), run patches/patch_libs.sh there as root, then reboot.
set -e
cd "$(dirname "$0")"; HERE=$PWD
SDK=${ANDROID_SDK:-$HOME/android-sdk}; BT=$SDK/build-tools/34.0.0; AJ=$SDK/platforms/android-34/android.jar
KEY=${PLATFORM_KEY:-$HERE/keys}
[ -f "$KEY/platform.pk8" ] || { echo "platform key not found in $KEY (set PLATFORM_KEY)"; exit 1; }
W=out/work; OUT=$HERE/out/hiby_r4_gsi_fix   # W relative: the repo path may contain spaces
rm -rf "$HERE/out"; mkdir -p "$W" "$OUT"
cp -r module/hiby_r4_gsi_fix/. "$OUT/"
sign() { "$BT/zipalign" -p -f 4 "$1" "$1.al"; "$BT/apksigner" sign --v4-signing-enabled false --key "$KEY/platform.pk8" --cert "$KEY/platform.x509.pem" --out "$2" "$1.al"; }

# HiBy R4 service (system uid: HiBy settings API, equalizer, lights, line out, DAC volume)
mkdir -p "$W/svc/c" "$W/svc/cls" "$W/svc/dex" "$W/svc/gen"
# res + res/raw/otacerts.zip = the SongateOS release certificate (public) the updater checks update zips against
cp -r apps/hiby_r4_service/res "$W/svc/res"; mkdir -p "$W/svc/res/raw"
(cd ota/keys && zip -q -X "$HERE/$W/svc/res/raw/otacerts.zip" songateos_release.x509.pem)
"$BT/aapt2" compile --dir "$W/svc/res" -o "$W/svc/c/"
"$BT/aapt2" link -o "$W/svc/base.apk" -I "$AJ" --manifest apps/hiby_r4_service/AndroidManifest.xml --min-sdk-version 30 \
  --target-sdk-version 34 --version-code 25 --version-name 25.0 --java "$W/svc/gen" "$W"/svc/c/*.flat
javac --release 11 -Xlint:-options -classpath "$AJ" -d "$W/svc/cls" $(find apps/hiby_r4_service/src "$W/svc/gen" -name '*.java')
"$BT/d8" --lib "$AJ" --min-api 30 --output "$W/svc/dex" $(find "$W/svc/cls" -name '*.class')
cp "$W/svc/base.apk" "$W/svc/u.apk"; (cd "$W/svc/dex" && zip -q ../u.apk classes.dex)
mkdir -p "$OUT/system/priv-app/HiByR4Service"; sign "$W/svc/u.apk" "$OUT/system/priv-app/HiByR4Service/HiByR4Service.apk"

# Settings with the HiBy pages: the GSI's own Settings.apk + classes3.dex, re-signed with the same test key
if [ -n "$SETTINGS_APK" ]; then
  mkdir -p "$W/set/stub" "$W/set/cls" "$W/set/dex"
  javac --release 11 -Xlint:-options -classpath "$AJ" -d "$W/set/stub" $(find apps/settings_hiby/stubs -name '*.java')
  javac --release 11 -Xlint:-options -classpath "$AJ:$W/set/stub" -d "$W/set/cls" $(find apps/settings_hiby/src -name '*.java')
  (cd "$W/set/stub" && jar cf ../stubs.jar .)
  "$BT/d8" --lib "$AJ" --classpath "$W/set/stubs.jar" --min-api 30 --output "$W/set/dex" $(find "$W/set/cls" -name '*.class')
  unzip -l "$SETTINGS_APK" | grep -q classes3.dex && { echo "Settings.apk already has classes3.dex"; exit 1; }
  cp "$SETTINGS_APK" "$W/set/s.apk"; zip -q -d "$W/set/s.apk" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.MF' || true
  cp "$W/set/dex/classes.dex" "$W/set/classes3.dex"; (cd "$W/set" && zip -q -0 s.apk classes3.dex)
  mkdir -p "$OUT/system/system_ext/priv-app/Settings"; touch "$OUT/system/system_ext/priv-app/Settings/.replace"
  sign "$W/set/s.apk" "$OUT/system/system_ext/priv-app/Settings/Settings.apk"
else
  echo "SETTINGS_APK not set: Settings pages skipped"
fi

# Overlays: no voice/SMS, black FallbackHome, black default wallpaper
for o in novoice:HiByR4NoVoice fallbackhome:HiByR4FallbackHome blackwallpaper:HiByR4BlackWallpaper; do
  d=${o%%:*}; n=${o##*:}; mkdir -p "$W/ov_$d/c"
  "$BT/aapt2" compile --dir overlays/$d/res -o "$W/ov_$d/c/"
  "$BT/aapt2" link -o "$W/ov_$d/u.apk" -I "$AJ" --manifest overlays/$d/AndroidManifest.xml --min-sdk-version 30 \
    --target-sdk-version 34 "$W"/ov_$d/c/*.flat
  mkdir -p "$OUT/system/product/overlay"; sign "$W/ov_$d/u.apk" "$OUT/system/product/overlay/$n.apk"
done

# Master volume helper (Bluetooth fix), run by service.sh with app_process
mkdir -p "$W/mv/cls" "$W/mv/dex"
javac --release 11 -Xlint:-options -classpath "$AJ" -d "$W/mv/cls" apps/mastervol/MvProbe.java
"$BT/d8" --lib "$AJ" --min-api 30 --output "$W/mv/dex" "$W/mv/cls/MvProbe.class"; cp "$W/mv/dex/classes.dex" "$OUT/mastervol.dex"

# Boot hand-off animation: BOOTANIM_HOLD = the zip made by ../branding/make_bootanim_hold.py (optional)
[ -n "$BOOTANIM_HOLD" ] && cp "$BOOTANIM_HOLD" "$OUT/bootanimation_hold.zip"
cp patches/patch_libs.sh "$OUT/"
rm -rf "$W"; echo "Module in $OUT"
