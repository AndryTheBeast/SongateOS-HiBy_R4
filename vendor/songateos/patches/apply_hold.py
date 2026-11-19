#!/usr/bin/env python3
"""SongateOS: the HiBy R4 hold switch in Android's key policy (PhoneWindowManager.interceptKeyBeforeQueueing).

While the switch on top of the R4 is locked (sys.hibyr4.hold = 1, published by hold_switch.sh on every flip), the
R4's own buttons are filtered at the very start of the key policy, by the level the user chose
(sys.hibyr4.hold_level): "media" locks play/pause, previous and next; "volume" also the volume buttons; "all" every
button, power included. Only HiBy's two button devices (gpio-keys, qpnp_pon): the wired remote and Bluetooth buttons
always work. Allowed keys go on with no delay (long-press power included); blocked keys are dropped before anything
can wake the screen. Replaces the GSI-era design (both devices switched off + keys passed on by a helper through
`content call`, 0.5-1 s late). The service and hold_switch.sh see ro.songateos.hold_framework=1 and leave the
devices on.

Run in the tree: python3 apply_hold.py ~/lineage21   (idempotent; the original is kept as PhoneWindowManager.java.lineage)
"""
import sys, os, shutil

top = sys.argv[1]
f = os.path.join(top, 'frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java')
keep = f + '.lineage'
MARK = 'songateHoldBlocks'

s = open(f, encoding='utf-8').read()
if MARK in s:
    print('already applied: PhoneWindowManager hold switch')
    sys.exit(0)
if not os.path.exists(keep):
    shutil.copy2(f, keep)

sig = '    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {\n'
first = '        final int keyCode = event.getKeyCode();\n'
assert s.count(sig) == 1, 'interceptKeyBeforeQueueing signature not found exactly once'
i = s.index(sig)
j = s.index(first, i)
assert j - i < 200, 'keyCode line not right after the signature'
for imp in ('import android.os.SystemProperties;', 'import android.util.SparseArray;', 'import android.view.InputDevice;'):
    assert imp in s, 'missing ' + imp

helper = '''    // SongateOS (HiBy R4): the hold switch on top of the player. While it is locked (sys.hibyr4.hold = 1, set by
    // SongateOS's hold_switch.sh on every flip), the R4's own buttons are filtered here, before anything else sees
    // them, by the level the user chose (sys.hibyr4.hold_level): "media" locks play/pause, previous and next;
    // "volume" also the volume buttons; "all" every button, power included. Only HiBy's two button devices
    // (gpio-keys, qpnp_pon): the wired remote and Bluetooth buttons always work.
    private final SparseArray<Boolean> mSongateHoldDevices = new SparseArray<>();

    private boolean songateHoldBlocks(KeyEvent event, int keyCode) {
        if (!"1".equals(SystemProperties.get("sys.hibyr4.hold"))) return false;
        final int id = event.getDeviceId();
        Boolean r4;
        synchronized (mSongateHoldDevices) { r4 = mSongateHoldDevices.get(id); }
        if (r4 == null) {
            final InputDevice d = InputDevice.getDevice(id);
            final String n = d == null ? "" : d.getName();
            r4 = "gpio-keys".equals(n) || "qpnp_pon".equals(n);
            if (d != null) {
                synchronized (mSongateHoldDevices) { mSongateHoldDevices.put(id, r4); }
            }
        }
        if (!r4) return false;
        final boolean power = keyCode == KeyEvent.KEYCODE_POWER;
        final boolean volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
        switch (SystemProperties.get("sys.hibyr4.hold_level", "volume")) {
            case "media": return !power && !volume;
            case "all": return true;
            default: return !power;   // "volume"
        }
    }

'''
check = '''        if (songateHoldBlocks(event, keyCode)) {
            return 0;   // SongateOS hold switch: a locked R4 button, dropped (no action, no wake)
        }
'''
# the helper goes above the method's comment / javadoc / @Override block (the blank line before it)
h = s.rindex('\n\n', 0, i) + 2
block = s[h:i]
assert all(l.strip().startswith(('//', '/*', '*', '@')) for l in block.splitlines() if l.strip()), 'unexpected lines before the method'
s = s[:h] + helper + s[h:j + len(first)] + check + s[j + len(first):]
open(f, 'w', encoding='utf-8').write(s)
print('patched: PhoneWindowManager hold switch')
