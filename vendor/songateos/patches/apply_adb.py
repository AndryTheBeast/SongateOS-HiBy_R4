#!/usr/bin/env python3
"""SongateOS 2.1.0: secure adb (user, 2026-11-20). The PC must be allowed once on the player ("Allow USB debugging?").

The GSI sets ro.adb.secure=0 itself in three places: the phh treble base (device/phh/treble/system.prop and
base.mk) and AOSP's GSI board file build/make/target/board/gsi_system_ext.prop ("GSI always disables adb
authentication"; system_ext's build.prop loads after system's, so it would win). Every SongateOS beta accepted any
computer without asking. Without WITH_ADB_INSECURE, LineageOS sets
ro.adb.secure=1 (vendor/lineage/config/common.mk), and the build stops on the conflicting assignments
("found duplicate sysprop assignments"). This removes phh's two lines; LineageOS's ro.adb.secure=1 stays.
phh's persist.sys.usb.config=adb is kept (adb on from the first boot, still behind the on-screen prompt).

Run in the tree: python3 apply_adb.py ~/lineage21   (idempotent; originals kept as *.lineage)
"""
import os
import shutil
import sys

top = sys.argv[1]
edits = [
    ('device/phh/treble/system.prop', 'ro.adb.secure=0\n'),
    ('device/phh/treble/base.mk', '\tro.adb.secure=0 \\\n'),
    ('build/make/target/board/gsi_system_ext.prop', '# GSI always disables adb authentication\nro.adb.secure=0\n'),
]
for rel, line in edits:
    f = os.path.join(top, rel)
    s = open(f).read()
    n = s.count(line)
    if n == 0:
        print('already applied:', rel)
        continue
    if n != 1:
        sys.exit('apply_adb: expected the line once in ' + rel)
    keep = f + '.lineage'
    if not os.path.exists(keep):
        shutil.copy2(f, keep)
    open(f, 'w').write(s.replace(line, ''))
    print('patched:', rel)
