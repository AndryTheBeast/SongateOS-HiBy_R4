#!/usr/bin/env python3
"""SongateOS: USB speakers and DACs through Android's standard USB audio HAL (audio.usb.default), not HiBy's primary HAL.

HiBy's audio policy (Qualcomm's layout) sends USB Device / USB Headset outputs to the primary module (hifi_playback),
but HiBy's primary HAL never opens a USB sound card: with a USB speaker (JBL Charge 6, 2026-11-18) Android routed
music to "USB_HEADSET" on the primary output and the speaker's card stayed closed - no sound. This removes the USB
device ports and routes from the primary module and puts the standard USB module (TrebleDroid's
usb_audio_policy_configuration.xml: usb_accessory + usb_device output/input) in place of HiBy's accessory-only one.

    python3 usb_audio_policy.py <audio_policy_configuration.xml>   (in place; idempotent)
"""
import re
import sys

USB_MODULE = '''<module name="usb" halVersion="2.0">
            <!-- SongateOS: Android's standard USB audio HAL for USB speakers and DACs (see patches/usb_audio_policy.py) -->
            <mixPorts>
                <mixPort name="usb_accessory output" role="source">
                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
                             samplingRates="44100" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                </mixPort>
                <mixPort name="usb_device output" role="source"/>
                <mixPort name="usb_device input" role="sink"/>
            </mixPorts>
            <devicePorts>
                <devicePort tagName="USB Host Out" type="AUDIO_DEVICE_OUT_USB_ACCESSORY" role="sink">
                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
                             samplingRates="44100" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                </devicePort>
                <devicePort tagName="USB Device Out" type="AUDIO_DEVICE_OUT_USB_DEVICE" role="sink"/>
                <devicePort tagName="USB Headset Out" type="AUDIO_DEVICE_OUT_USB_HEADSET" role="sink"/>
                <devicePort tagName="USB Device In" type="AUDIO_DEVICE_IN_USB_DEVICE" role="source"/>
                <devicePort tagName="USB Headset In" type="AUDIO_DEVICE_IN_USB_HEADSET" role="source"/>
            </devicePorts>
            <routes>
                <route type="mix" sink="USB Host Out"
                       sources="usb_accessory output"/>
                <route type="mix" sink="USB Device Out"
                       sources="usb_device output"/>
                <route type="mix" sink="USB Headset Out"
                       sources="usb_device output"/>
                <route type="mix" sink="usb_device input"
                       sources="USB Device In,USB Headset In"/>
            </routes>
        </module>'''

p = sys.argv[1]
s = open(p, encoding='utf-8').read()
if 'patches/usb_audio_policy.py' in s:
    print('already applied:', p)
    sys.exit(0)

prim_start = s.index('<module name="primary"')
prim_end = s.index('</module>', prim_start) + len('</module>')
prim = s[prim_start:prim_end]
n0 = len(prim)
# USB device ports out of the primary module
prim, k1 = re.subn(r'\s*<devicePort tagName="USB (Device|Headset) (Out|In)"[^>]*>\s*</devicePort>', '', prim)
# their routes
prim, k2 = re.subn(r'\s*<route type="mix" sink="USB (Device|Headset) Out"\s+sources="[^"]*"\s*/>', '', prim)
prim, k3 = re.subn(r'\s*<route type="mix" sink="hifi_input" sources="USB Device In,USB Headset In"\s*/>', '', prim)
prim, k4 = re.subn(r',USB Device In,USB Headset In', '', prim)
if (k1, k2) != (4, 2):
    sys.exit('usb_audio_policy: unexpected primary module (%d ports, %d routes)' % (k1, k2))
s = s[:prim_start] + prim + s[prim_end:]

usb_start = s.index('<module name="usb"')
usb_end = s.index('</module>', usb_start) + len('</module>')
s = s[:usb_start] + USB_MODULE + s[usb_end:]
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('patched %s: primary -%d ports -%d routes -%d hifi_input -%d source lists; usb module replaced' % (p, k1, k2, k3, k4))
