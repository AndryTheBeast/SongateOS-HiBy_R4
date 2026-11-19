#!/usr/bin/env python3
"""SongateOS: ONE boot animation, held until Songate (the home screen) is fully drawn, then its fade-out part plays.

Android ends the boot animation (service.bootanim.exit=1) as soon as the first windows are drawn; Songate draws an empty
first frame long before its library and UI are ready, so the GSI-era module restarted a second "hold" animation to
cover the gap (service.sh bootanim_hold). Here the window manager itself waits: performEnableScreen() also needs
sys.songateos.home_ready=1, set by the HiBy R4 service when Songate calls home_ready (system API), re-checked every
100 ms, at most 20 s after it first could have ended (then it goes on anyway, so no one is stuck on the animation).
The animation then leaves its loop and plays its closing part (desc.txt "c 1 0 part2", the fade) to the end.

Run in the tree: python3 apply_bootwait.py ~/lineage21   (idempotent; original kept as WindowManagerService.java.lineage)
"""
import os
import shutil
import sys

top = sys.argv[1]
f = os.path.join(top, 'frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java')
MARK = 'songateHomeReady'
anchor = '''            if (!mBootAnimationStopped) {
                Trace.asyncTraceBegin(TRACE_TAG_WINDOW_MANAGER, "Stop bootanim", 0);'''
check = '''            // SongateOS: keep the boot animation until Songate (the home screen) is fully drawn (at most 20 s)
            if (!mForceDisplayEnabled && !mBootAnimationStopped && !songateHomeReady()) {
                mH.removeMessages(H.ENABLE_SCREEN);
                mH.sendEmptyMessageDelayed(H.ENABLE_SCREEN, 100);
                return;
            }

'''
helper = '''    // SongateOS: see performEnableScreen. sys.songateos.home_ready is set by the HiBy R4 service (home_ready call).
    private long mSongateWaitStart = -1;

    private boolean songateHomeReady() {
        if (SystemProperties.getBoolean("sys.songateos.home_ready", false)) return true;
        long now = SystemClock.elapsedRealtime();
        if (mSongateWaitStart < 0) mSongateWaitStart = now;
        if (now - mSongateWaitStart > 20000) {
            Slog.w(TAG, "SongateOS: home not ready after 20 s, ending the boot animation anyway");
            return true;
        }
        return false;
    }

'''
sig = '    private void performEnableScreen() {\n'

s = open(f).read()
if MARK in s:
    print('already applied: WindowManagerService boot wait')
    sys.exit(0)
if s.count(anchor) != 1 or s.count(sig) != 1:
    sys.exit('apply_bootwait: expected code not found once in WindowManagerService.java')
keep = f + '.lineage'
if not os.path.exists(keep):
    shutil.copy2(f, keep)
s = s.replace(anchor, check + anchor)
s = s.replace(sig, helper + sig)
open(f, 'w', newline='\n').write(s)
print('patched: WindowManagerService boot wait (sys.songateos.home_ready)')
