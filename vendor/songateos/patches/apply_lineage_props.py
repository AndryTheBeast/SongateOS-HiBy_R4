#!/usr/bin/env python3
"""SongateOS: LineageOS's default notification and alarm sounds become optional (?=), like AOSP's own, so
songateos.mk's empty values (no notification or alarm sound: the R4 has no speaker) win instead of failing the
build with "duplicate sysprop assignments".

Run in the tree: python3 apply_lineage_props.py ~/lineage21   (idempotent; the original is kept as common_mobile.mk.lineage)
"""
import sys, os, shutil

top = sys.argv[1]
f = os.path.join(top, 'vendor/lineage/config/common_mobile.mk')
keep = f + '.lineage'
if not os.path.exists(keep):
    shutil.copy2(f, keep)
s = open(f).read()
new = s
for p in ('ro.config.notification_sound=Argon.ogg', 'ro.config.alarm_alert=Hassium.ogg'):
    new = new.replace(p, p.replace('=', '?=', 1))
if new == s:
    if 'ro.config.notification_sound?=Argon.ogg' in s and 'ro.config.alarm_alert?=Hassium.ogg' in s:
        print('already applied: common_mobile.mk sound properties')
    else:
        sys.exit('apply_lineage_props: expected lines not found in common_mobile.mk')
else:
    open(f, 'w').write(new)
    print('patched: common_mobile.mk sound properties made optional')
