#!/usr/bin/env python3
"""SongateOS source changes in frameworks/av (replace the module's binary patches of the GSI's audio libraries).

Run once in the tree: python3 apply_av.py ~/lineage21/frameworks/av   (idempotent: already applied = no change)
 1. AudioPolicyManager: mixer attributes (Android 14's bit-perfect API) for wired outputs too, not only USB.
 2. libaudiohal (HIDL < 7.1): HiBy's HAL 6.0 has no BIT_PERFECT output flag; open its DIRECT path instead.
 3. (2026-11-19) the speaker too: on the R4 Android's "speaker" is the 4.4 mm balanced output (Android only knows the
    3.5 mm jack; the R4 has no speaker), so bit-perfect on the 4.4 mm needs it. Applied on top of 1.
 4. (2026-11-19) reopenOutputsWithDevices with no device left: close the output instead of reopening it. Pulling out
    the 3.5 mm while an app holds bit-perfect for it made Android 14 reopen that output with an empty device list,
    and SwAudioOutputDescriptor::open aborted audioserver ("open failed to get device descriptor").
 5. (2026-11-20) libaudiohal (HIDL < 7.1): a wired device's address reaches HiBy's HAL. HiBy's HAL picks its analog
    output from the address in "<address>=;connect=8" (Headphone | Balance | BalanceLo; WiredAccessoryManager
    registers the jacks with these, apply_wired.py, as HiBy's own system does). HIDL 6.0's setConnectedState keeps an
    address only for Bluetooth, USB and the like, so the HAL got a bare "connect=8" and always chose the 3.5 mm.
    For wired headphones/headsets with an address, send it as Android 12 did: through setParameters.
 6. (2026-11-20) BitPerfectThread::prepareTracks_l: skip a track the mixer no longer has. Skipping songs fast made
    MixerThread::prepareTracks_l remove the old track from the mixer while it was still in mActiveTracks, and the
    TEE_BUFFER setParameter on it aborted audioserver ("invalid name: 95"). Android 14 bug, seen on beta.11.
 7. (2026-11-20) stopOutput: keep a bit_perfect-only output open when its last bit-perfect client stops. Android 14
    reopens it with no config and no flags; on a song change that closed the output the NEXT song had just opened,
    and HiBy's HAL refuses the bit_perfect mixPort's default (384 kHz 32-bit, no flag), so the output was lost and
    the next song fell back to the mixer. startOutput already reopens it when the next song needs another format.
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

# 4. a bit-perfect (preferred mixer attributes) output whose device went away: close it, don't reopen it on nothing
edit('services/audiopolicy/managerdefault/AudioPolicyManager.cpp',
     '''        sp<SwAudioOutputDescriptor> desc = mOutputs.valueFor(output);
        closeOutput(output);
        openOutputWithProfileAndDevice(desc->mProfile, devices);
''',
     '''        sp<SwAudioOutputDescriptor> desc = mOutputs.valueFor(output);
        closeOutput(output);
        // SongateOS (HiBy R4): no device left (a jack pulled out under bit-perfect): opening would abort audioserver
        if (devices.isEmpty()) {
            ALOGW("%s: output %d has no device left, closed and not reopened", __func__, output);
            continue;
        }
        openOutputWithProfileAndDevice(desc->mProfile, devices);
''',
     'a jack pulled out under bit-perfect')

# 5. wired devices: the address goes to the HAL (HIDL 6.0's setConnectedState drops it)
edit('media/libaudiohal/impl/DeviceHalHidl.cpp',
     '''    DeviceAddress hidlAddress;
    if (status_t result = CoreUtils::deviceAddressFromHal(
                    port->ext.device.type, port->ext.device.address, &hidlAddress);
''',
     r'''    // SongateOS (HiBy R4): HiBy's HAL takes its output name from a wired device address ("Balance=;connect=8");
    // HIDL 6.0's setConnectedState drops that address for wired devices, so send it the way Android 12 did
    if ((port->ext.device.type == AUDIO_DEVICE_OUT_WIRED_HEADPHONE ||
            port->ext.device.type == AUDIO_DEVICE_OUT_WIRED_HEADSET) && port->ext.device.address[0] != '\0') {
        AudioParameter param(String8(port->ext.device.address));
        param.addInt(String8(connected ? AudioParameter::keyDeviceConnect
                                       : AudioParameter::keyDeviceDisconnect), port->ext.device.type);
        return setParameters(param.toString());
    }
    DeviceAddress hidlAddress;
    if (status_t result = CoreUtils::deviceAddressFromHal(
                    port->ext.device.type, port->ext.device.address, &hidlAddress);
''',
     'takes its output name from a wired device address')

# 6. bit-perfect thread: a track the mixer already dropped (fast song changes) must not abort audioserver
edit('services/audioflinger/Threads.cpp',
     '''    if (mActiveTracks.size() == 1 && mActiveTracks[0]->isBitPerfect()) {
        const int trackId = mActiveTracks[0]->id();
        mAudioMixer->setParameter(
                    trackId, AudioMixer::TRACK, AudioMixer::TEE_BUFFER, (void *)mSinkBuffer);
''',
     '''    // SongateOS (HiBy R4): the mixer may already have dropped a track that is still listed as active (fast song
    // changes); setParameter on it aborts audioserver ("invalid name")
    if (mActiveTracks.size() == 1 && mActiveTracks[0]->isBitPerfect()
            && mAudioMixer->exists(mActiveTracks[0]->id())) {
        const int trackId = mActiveTracks[0]->id();
        mAudioMixer->setParameter(
                    trackId, AudioMixer::TRACK, AudioMixer::TEE_BUFFER, (void *)mSinkBuffer);
''',
     'listed as active (fast song')
edit('services/audioflinger/Threads.cpp',
     '''        for (const auto& track : mActiveTracks) {
            const int trackId = track->id();
            mAudioMixer->setParameter(
                        trackId, AudioMixer::TRACK, AudioMixer::TEE_BUFFER, nullptr);
''',
     '''        for (const auto& track : mActiveTracks) {
            const int trackId = track->id();
            if (!mAudioMixer->exists(trackId)) continue;  // SongateOS (HiBy R4): already dropped, see above
            mAudioMixer->setParameter(
                        trackId, AudioMixer::TRACK, AudioMixer::TEE_BUFFER, nullptr);
''',
     'already dropped, see above')

# 7. stopOutput: don't reopen a bit_perfect-only output with no config/flags (HiBy's HAL refuses it, output lost)
edit('services/audiopolicy/managerdefault/AudioPolicyManager.cpp',
     '''            info->decreaseActiveClient();
            if (info->getActiveClientCount() == 0) {
                reopenOutput(outputDesc, nullptr /*config*/, AUDIO_OUTPUT_FLAG_NONE, __func__);
            }
''',
     '''            info->decreaseActiveClient();
            // SongateOS (HiBy R4): a bit_perfect-only output can't open without its flag (HiBy's HAL refuses the
            // default config), and on a song change this would close the output the next song just opened.
            // Keep it; startOutput reopens it if the next client needs another format.
            if (info->getActiveClientCount() == 0
                    && (outputDesc->mProfile->getFlags() & AUDIO_OUTPUT_FLAG_BIT_PERFECT) == 0) {
                reopenOutput(outputDesc, nullptr /*config*/, AUDIO_OUTPUT_FLAG_NONE, __func__);
            }
''',
     'a bit_perfect-only output can')
