#!/usr/bin/env python3
"""SongateOS source change in packages/modules/Connectivity.

Run once in the tree: python3 apply_netstats.py ~/lineage21   (idempotent: already applied = no change)
 1. (2026-11-20) SkDestroyListener: the R4's kernel (4.14) has no BPF maps, so the cookie-tag map is null and every
    closed socket logged a NullPointerException with its stack (hundreds a minute, a busy log daemon). With no map
    there is nothing to delete: skip it quietly.
"""
import sys, os

top = sys.argv[1]
p = os.path.join(top, 'packages/modules/Connectivity/service-t/src/com/android/server/net/SkDestroyListener.java')
s = open(p, encoding='utf-8').read()
marker = 'SongateOS (HiBy R4): no BPF maps'
if marker in s:
    print('already applied: SkDestroyListener'); sys.exit(0)
old = '            mCookieTagMap.deleteEntry(new CookieTagMapKey(sockId.cookie));\n'
if s.count(old) != 1:
    sys.exit('pattern not found in SkDestroyListener.java')
s = s.replace(old, '            // SongateOS (HiBy R4): no BPF maps on this kernel, so nothing was ever tagged\n'
                   '            if (mCookieTagMap == null) return;\n' + old)
open(p, 'w', encoding='utf-8').write(s)
print('patched: SkDestroyListener')
