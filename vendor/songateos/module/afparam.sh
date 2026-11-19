#!/system/bin/sh
# SongateOS (HiBy R4): send one key/value string to the audio HAL through AudioFlinger (setParameters, binder txn 21).
# Run by init as the audioserver user (service songateos_afparam): AudioFlinger takes "connect=8;<Name>" only from
# the audioserver uid, and HiBy's HAL switches its engine to the analog output only on that.
P=$(getprop sys.songateos.af_param)
[ -n "$P" ] || exit 0
service call media.audio_flinger 21 i32 0 s16 "$P" >/dev/null 2>&1
