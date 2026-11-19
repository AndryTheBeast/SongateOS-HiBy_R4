#!/usr/bin/env python3
"""SongateOS changes in packages/apps/Settings: compile the HiBy pages into Settings and keep them from the shrinker.

Run once in the tree: python3 apply_settings.py ~/lineage21/packages/apps/Settings   (idempotent)
 1. Android.bp, Settings-core srcs: + ":songateos_settings_pages" (vendor/songateos/settings_pages)
 2. proguard.flags: keep com.andre.hibyr4.settings.** (the pages are opened by class name from the service's tiles)
"""
import sys, os

d = sys.argv[1]

def edit(rel, old, new, marker):
    p = os.path.join(d, rel)
    s = open(p, encoding='utf-8').read()
    if marker in s:
        print('already applied:', rel); return
    if s.count(old) != 1:
        sys.exit('pattern not found exactly once in ' + rel)
    open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
    print('patched:', rel)

edit('Android.bp',
     '''    srcs: [
        "src/**/*.java",
        "src/**/*.kt",
    ],''',
     '''    srcs: [
        "src/**/*.java",
        "src/**/*.kt",
        ":songateos_settings_pages", // SongateOS: the HiBy pages
    ],''',
     ':songateos_settings_pages')

p = os.path.join(d, 'proguard.flags')
s = open(p, encoding='utf-8').read()
if 'com.andre.hibyr4.settings' in s:
    print('already applied: proguard.flags')
else:
    open(p, 'a', encoding='utf-8').write(
        '\n# SongateOS: the HiBy pages are opened by class name (the HiBy R4 service\'s Settings tiles)\n'
        '-keep class com.andre.hibyr4.settings.** { *; }\n')
    print('patched: proguard.flags')
