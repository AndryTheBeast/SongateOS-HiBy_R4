#!/usr/bin/env python3
"""SongateOS: a system image that Google apps can be installed into (user, 2026-11-18).

TrebleDroid builds the system image with deduplicated blocks (BOARD_EXT4_SHARE_DUP_BLOCKS := true), which makes the
ext4 file system read-only for good, and with only 32 MB to spare. Google apps (installed by the recovery, see the
HiBy R4 service's Gapps and the recovery hook v3) need a writable file system and room: MindTheGapps' zip is 431 MB.
  device/phh/treble/board-base.mk:            BOARD_EXT4_SHARE_DUP_BLOCKS := true  -> false
  device/phh/treble/tdgsi_arm64_ab/BoardConfig.mk: BOARD_SYSTEMIMAGE_PARTITION_RESERVED_SIZE 33554432 -> 671088640
The image grows by about 640 MB (zeros, so the update zip barely grows); system_a stays well inside super.

Run in the tree: python3 apply_board.py ~/lineage21   (idempotent; originals kept as *.lineage)
"""
import os
import shutil
import sys

top = sys.argv[1]
RESERVED = '671088640'   # 640 MB
edits = [
    ('device/phh/treble/board-base.mk',
     'BOARD_EXT4_SHARE_DUP_BLOCKS := true', 'BOARD_EXT4_SHARE_DUP_BLOCKS := false'),
    ('device/phh/treble/tdgsi_arm64_ab/BoardConfig.mk',
     'BOARD_SYSTEMIMAGE_PARTITION_RESERVED_SIZE := 33554432',
     'BOARD_SYSTEMIMAGE_PARTITION_RESERVED_SIZE := ' + RESERVED),
]
for rel, old, new in edits:
    f = os.path.join(top, rel)
    s = open(f).read()
    if new in s and old not in s:
        print('already applied:', rel)
        continue
    if s.count(old) != 1:
        sys.exit('apply_board: expected line not found once in ' + rel + ': ' + old)
    keep = f + '.lineage'
    if not os.path.exists(keep):
        shutil.copy2(f, keep)
    open(f, 'w').write(s.replace(old, new))
    print('patched:', rel)
