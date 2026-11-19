#!/usr/bin/env python3
"""SongateOS: LineageOS's font list with Inter added (Songate's font) as the source of LineageOS's
fonts_customization.xml module (packages/overlays/Lineage/fonts/etc).

Run in the tree: python3 apply_fonts.py ~/lineage21   (idempotent: copies vendor/songateos/fonts/fonts_customization.xml
over LineageOS's file only when it differs; the original is kept as fonts_customization.xml.lineage)
"""
import sys, os, shutil

top = sys.argv[1]
ours = os.path.join(top, 'vendor/songateos/fonts/fonts_customization.xml')
theirs = os.path.join(top, 'packages/overlays/Lineage/fonts/etc/fonts_customization.xml')
keep = theirs + '.lineage'
if not os.path.exists(keep):
    shutil.copy2(theirs, keep)
if open(ours, 'rb').read() == open(theirs, 'rb').read():
    print('already applied: fonts_customization.xml')
else:
    shutil.copyfile(ours, theirs)
    print('patched: fonts_customization.xml (Inter added)')
