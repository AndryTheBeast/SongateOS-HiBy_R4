#!/usr/bin/env python3
"""SongateOS source change in packages/services/Telecomm.

Run once in the tree: python3 apply_telecom.py ~/lineage21   (idempotent: already applied = no change)
 1. (2026-11-20) Plugging in the 3.5 mm jack restarted the whole system once (0.1.0-beta.9, a fresh install, minutes
    after the boot script removed the phone apps for the user): Telecom's call-audio routing asked Bluetooth for its
    active devices, Bluetooth answered "Need android.permission.BLUETOOTH_CONNECT" for Telecom (which holds it), and
    that SecurityException ran up Telecom's handler thread inside system_server. The R4 makes no calls, so Telecom never
    needs the answer: every getActiveDevices() in Telecom's Bluetooth code goes through one helper that treats a
    refusal as "no active device" and logs it once per call, instead of crashing.
"""
import os, re, sys

top = sys.argv[1]
d = os.path.join(top, 'packages/services/Telecomm/src/com/android/server/telecom/bluetooth')
marker = 'SongateOS (HiBy R4): Bluetooth refused'

helper = (
    '    // SongateOS (HiBy R4): Bluetooth refused getActiveDevices() for Telecom once (SecurityException, missing\n'
    '    // BLUETOOTH_CONNECT though it is granted) and that took down system_server on a headset plug. No calls on\n'
    '    // this device: a refusal means "no active device".\n'
    '    public static List<BluetoothDevice> activeDevices(BluetoothAdapter adapter, int profile) {\n'
    '        try {\n'
    '            return adapter.getActiveDevices(profile);\n'
    '        } catch (SecurityException e) {\n'
    '            android.util.Log.w("Telecom", "SongateOS: getActiveDevices(" + profile + ") refused: " + e.getMessage());\n'
    '            return new ArrayList<>();\n'
    '        }\n'
    '    }\n\n')

call = re.compile(r'\b(\w+)\.getActiveDevices\(')
changed = 0
for name in ('BluetoothDeviceManager.java', 'BluetoothRouteManager.java'):
    p = os.path.join(d, name)
    s = open(p, encoding='utf-8').read()
    if marker in s or 'BluetoothDeviceManager.activeDevices(' in s:
        print('already applied: ' + name); continue
    n = len(call.findall(s))
    if n == 0:
        sys.exit('no getActiveDevices() call found in ' + name)
    s = call.sub(r'BluetoothDeviceManager.activeDevices(\1, ', s)
    if name == 'BluetoothDeviceManager.java':
        head = 'public class BluetoothDeviceManager {\n'
        if s.count(head) != 1:
            sys.exit('class header not found in BluetoothDeviceManager.java')
        s = s.replace(head, head + helper)
        # the helper itself must call the real method
        s = s.replace('return BluetoothDeviceManager.activeDevices(adapter, profile);',
                      'return adapter.getActiveDevices(profile);')
    open(p, 'w', encoding='utf-8').write(s)
    changed += 1
    print('patched: %s (%d calls)' % (name, n))
