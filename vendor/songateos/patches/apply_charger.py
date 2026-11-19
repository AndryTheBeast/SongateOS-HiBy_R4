#!/usr/bin/env python3
"""SongateOS: our off-mode charging animation description becomes the source of LineageOS's
lineage_charger_animation module (vendor/lineage/charger/animation.txt), which installs it at
/product/etc/res/values/charger/animation.txt. Our images (songate_*.png) are copied by songateos.mk.

Run in the tree: python3 apply_charger.py ~/lineage21   (idempotent; the original is kept as animation.txt.lineage)
"""
import sys, os, shutil

top = sys.argv[1]
ours = os.path.join(top, 'vendor/songateos/branding/charger/res/values/charger/animation.txt')
theirs = os.path.join(top, 'vendor/lineage/charger/animation.txt')
keep = theirs + '.lineage'
if not os.path.exists(keep):
    shutil.copy2(theirs, keep)
if open(ours, 'rb').read() == open(theirs, 'rb').read():
    print('already applied: charger animation.txt')
else:
    shutil.copyfile(ours, theirs)
    print('patched: charger animation.txt (Songate charging animation)')
