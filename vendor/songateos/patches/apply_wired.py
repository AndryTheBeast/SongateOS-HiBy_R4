#!/usr/bin/env python3
"""SongateOS source change in frameworks/base: the HiBy R4's wired outputs, registered the way HiBy's own system does.

Run once in the tree: python3 apply_wired.py ~/lineage21   (idempotent: already applied = no change)
 1. (2026-11-20) HiBy's stock WiredAccessoryManager (read from the R4's stock services.jar, Android 12) watches the
    R4's jack switches and registers EVERY analog output with Android as DEVICE_OUT_WIRED_HEADPHONE, with the output's
    name as the device address: "Headphone" (3.5 mm), "Balance" / "BalanceLo" (4.4 mm). Android's policy manager then
    sends HiBy's HAL "<address>=;connect=8", which is how the HAL learns which output to drive, and it re-routes
    running streams itself. AOSP registered the 3.5 mm with the address "controller=0;stream=0" (HAL name "Default"
    = S/PDIF, so SongateOS's boot script had to re-send the name afterwards) and knew nothing about the 4.4 mm (Android
    played to it as the speaker; streams started before the plug stayed on S/PDIF: silent, and the player raced).
    Now: one wired output at a time, the 4.4 mm before the 3.5 mm (as SongateOS always chose), re-evaluated on the
    switches' uevents and on the broadcast com.andre.hibyr4.action.OUTPUT_REFRESH (sent by SongateOS's boot script
    when the 4.4 mm mode changes between headphones and line out, sys.hibyr4.out44 = bal_po | bal_lo).
    (2026-11-20, beta.11 test) The 4.4 mm is always registered as "Balance", line out or not. HiBy's stock WAM uses
    "BalanceLo" only for players with a separate "balance_lo" jack switch; the R4 has only h2w and balance, and its
    driver powers the DAC off for "BalanceLo" (silence, the player races). The driver's bal_po_lo_switch = bal_lo
    changes nothing either (still headphone level, volume works). So the 4.4 mm line out is the headphone path at
    fixed full volume + line-out gain, like the 3.5 mm (HiBy R4 service LineOut, boot script). The refresh broadcast
    is kept (it only re-checks the jacks).
"""
import os, sys

top = sys.argv[1]
p = os.path.join(top, 'frameworks/base/services/core/java/com/android/server/WiredAccessoryManager.java')
s = open(p, encoding='utf-8').read()
marker = 'SongateOS (HiBy R4)'
if marker in s:
    print('already applied: WiredAccessoryManager'); sys.exit(0)


def rep(old, new):
    global s
    if s.count(old) != 1:
        sys.exit('pattern not found (%d) in WiredAccessoryManager.java: %r' % (s.count(old), old[:70]))
    s = s.replace(old, new)


rep('import android.content.Context;\n',
    'import android.content.BroadcastReceiver;\n'
    'import android.content.Context;\n'
    'import android.content.Intent;\n'
    'import android.content.IntentFilter;\n')
rep('import android.os.PowerManager.WakeLock;\n',
    'import android.os.PowerManager.WakeLock;\n'
    'import android.os.SystemProperties;\n')
rep('import java.util.Locale;\n', 'import java.util.Locale;\nimport java.util.Objects;\n')

# keep the context for the refresh receiver
rep('    public WiredAccessoryManager(Context context, InputManagerService inputManager) {\n',
    '    public WiredAccessoryManager(Context context, InputManagerService inputManager) {\n'
    '        mR4Context = context;\n')

# start watching the 4.4 mm after the stock observers
rep('''        } else {
            mObserver.init();
        }
    }
''', '''        } else {
            mObserver.init();
        }
        r4Init();
    }
''')

# the 3.5 mm (h2w) no longer registers itself: the R4 code registers the one wired output with its name
rep('''    private void setDeviceStateLocked(int headset,
            int headsetState, int prevHeadsetState, String headsetNameAddr) {
        if ((headsetState & headset) != (prevHeadsetState & headset)) {
''', '''    private void setDeviceStateLocked(int headset,
            int headsetState, int prevHeadsetState, String headsetNameAddr) {
        // SongateOS (HiBy R4): the 3.5 mm goes through r4UpdateLocked (address "Headphone"), see below
        if ((headset & (BIT_HEADSET | BIT_HEADSET_NO_MIC | BIT_LINEOUT)) != 0
                && headsetNameAddr.startsWith(NAME_H2W)) {
            if ((headsetState & headset) != (prevHeadsetState & headset)) r4UpdateLocked();
            return;
        }
        if ((headsetState & headset) != (prevHeadsetState & headset)) {
''')

r4 = '''
    // ---- SongateOS (HiBy R4): wired outputs registered the way HiBy's own system does ----
    // Every analog output is DEVICE_OUT_WIRED_HEADPHONE with the output's name as the address. The policy manager
    // sends the address to HiBy's HAL ("Balance=;connect=8"), which drives that output; Android re-routes running
    // streams to it. One output at a time: the 4.4 mm before the 3.5 mm.
    private static final String R4_H2W_STATE = "/sys/class/switch/h2w/state";
    private static final String R4_BALANCE_STATE = "/sys/class/switch/balance/state";
    private static final String R4_BALANCE_DEVPATH = "DEVPATH=/devices/virtual/switch/balance";
    private static final String R4_ACTION_REFRESH = "com.andre.hibyr4.action.OUTPUT_REFRESH";
    private final Context mR4Context;
    private String mR4Output;   // address registered now, null = none

    private static int r4Read(String path) {
        try (FileReader f = new FileReader(path)) {
            char[] b = new char[16];
            int n = f.read(b, 0, b.length);
            return n > 0 ? Integer.parseInt(new String(b, 0, n).replaceAll("\\\\D+", "")) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void r4UpdateLocked() {
        String want = null;
        if (r4Read(R4_BALANCE_STATE) > 0) {
            // always "Balance": the R4's driver has no 4.4 mm line-out output ("BalanceLo" powers the DAC off,
            // tested 2026-11-20); SongateOS's 4.4 mm line out is this output at fixed full volume (like the 3.5 mm)
            want = "Balance";
        } else if (r4Read(R4_H2W_STATE) > 0) {
            want = "Headphone";
        }
        if (Objects.equals(want, mR4Output)) return;
        Slog.i(TAG, "SongateOS: wired output " + mR4Output + " -> " + want);
        if (mR4Output != null) {
            mAudioManager.setWiredDeviceConnectionState(AudioManager.DEVICE_OUT_WIRED_HEADPHONE, 0,
                    mR4Output, "HiBy R4 " + mR4Output);
        }
        if (want != null) {
            mAudioManager.setWiredDeviceConnectionState(AudioManager.DEVICE_OUT_WIRED_HEADPHONE, 1,
                    want, "HiBy R4 " + want);
        }
        mR4Output = want;
    }

    private void r4Post() {
        mHandler.post(() -> {
            synchronized (mLock) {
                r4UpdateLocked();
            }
        });
    }

    private final UEventObserver mR4BalanceObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            r4Post();
        }
    };

    private void r4Init() {
        mR4BalanceObserver.startObserving(R4_BALANCE_DEVPATH);
        mR4Context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                r4Post();
            }
        }, new IntentFilter(R4_ACTION_REFRESH), Context.RECEIVER_EXPORTED);
        synchronized (mLock) {
            r4UpdateLocked();
        }
    }

    private String switchCodeToString(int switchValues, int switchMask) {'''
rep('\n    private String switchCodeToString(int switchValues, int switchMask) {', r4)

open(p, 'w', encoding='utf-8').write(s)
print('patched: WiredAccessoryManager')
