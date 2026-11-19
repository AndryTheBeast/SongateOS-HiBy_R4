#!/usr/bin/env python3
"""SongateOS source changes in frameworks/av (replace the module's binary patches of the GSI's audio libraries).

Run once in the tree: python3 apply_av.py ~/lineage21/frameworks/av   (idempotent: already applied = no change)
 1. AudioPolicyManager: mixer attributes (Android 14's bit-perfect API) for wired outputs too, not only USB.
 2. libaudiohal (HIDL < 7.1): HiBy's HAL 6.0 has no BIT_PERFECT output flag; open its DIRECT path instead.
 3. (2026-11-19) the speaker too: on the R4 Android's "speaker" is the 4.4 mm balanced output (Android only knows the
    3.5 mm jack; the R4 has no speaker), so bit-perfect on the 4.4 mm needs it. Applied on top of 1.
"""
import sys, os

av = sys.argv[1]

def edit(rel, old, new, marker):
    p = os.path.join(av, rel)
    s = open(p, encoding='utf-8').read()
    if marker in s:
        print('already applied:', rel); return
    n = s.count(old)
    if n == 0:
        sys.exit('pattern not found in ' + rel)
    s = s.replace(old, new)
    open(p, 'w', encoding='utf-8').write(s)
    print('patched (%d place%s): %s' % (n, '' if n == 1 else 's', rel))

# 1. both "is not usb" checks (getSupportedMixerAttributes, setPreferredMixerAttributes)
edit('services/audiopolicy/managerdefault/AudioPolicyManager.cpp',
     'if (!audio_is_usb_out_device(deviceDescriptor->type())) {',
     '// SongateOS (HiBy R4): bit-perfect mixer attributes on the wired outputs too, not only USB\n'
     '    if (!audio_is_usb_out_device(deviceDescriptor->type())\n'
     '            && deviceDescriptor->type() != AUDIO_DEVICE_OUT_WIRED_HEADPHONE\n'
     '            && deviceDescriptor->type() != AUDIO_DEVICE_OUT_WIRED_HEADSET\n'
     '            && deviceDescriptor->type() != AUDIO_DEVICE_OUT_LINE) {',
     'SongateOS (HiBy R4): bit-perfect mixer attributes')

# 3. the speaker (= the R4's 4.4 mm balanced output) in both checks of 1.
edit('services/audiopolicy/managerdefault/AudioPolicyManager.cpp',
     '            && deviceDescriptor->type() != AUDIO_DEVICE_OUT_LINE) {',
     '            && deviceDescriptor->type() != AUDIO_DEVICE_OUT_LINE\n'
     '            // SongateOS (HiBy R4): "speaker" is the 4.4 mm balanced output\n'
     '            && deviceDescriptor->type() != AUDIO_DEVICE_OUT_SPEAKER) {',
     '"speaker" is the 4.4 mm balanced output')

# 2. after the spatializer flag rewrite, before the flags go to the HIDL HAL
edit('media/libaudiohal/impl/DeviceHalHidl.cpp',
     '''        flags = (audio_output_flags_t)
                (flags | AUDIO_OUTPUT_FLAG_FAST | AUDIO_OUTPUT_FLAG_DEEP_BUFFER);
    }
#endif
''',
     '''        flags = (audio_output_flags_t)
                (flags | AUDIO_OUTPUT_FLAG_FAST | AUDIO_OUTPUT_FLAG_DEEP_BUFFER);
    }
    // SongateOS (HiBy R4): HALs before 7.1 have no BIT_PERFECT flag; HiBy's opens its DIRECT path instead
    if ((flags & AUDIO_OUTPUT_FLAG_BIT_PERFECT) != 0) {
        flags = (audio_output_flags_t)
                ((flags & ~AUDIO_OUTPUT_FLAG_BIT_PERFECT) | AUDIO_OUTPUT_FLAG_DIRECT);
    }
#endif
''',
     'SongateOS (HiBy R4): HALs before 7.1')
