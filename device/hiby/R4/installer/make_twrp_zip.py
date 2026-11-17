#!/usr/bin/env python3
"""Build the TWRP installer zip for the HiBy R4 from a built recovery image.

    python3 make_twrp_zip.py <twrp recovery.img> <output .zip>

The zip holds the image, its SHA-256 and update-binary (next to this script). Flashed from TWRP or OrangeFox, the
script checks it's a standard R4 and that the image is intact, then writes recovery_a and recovery_b and reads both
back. It never changes the active slot.
"""
import hashlib
import os
import sys
import zipfile

def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    img_path, out = sys.argv[1], sys.argv[2]
    img = open(img_path, 'rb').read()
    if img[:8] != b'ANDROID!':
        sys.exit('not a boot-format recovery image: ' + img_path)
    here = os.path.dirname(os.path.abspath(__file__))
    ub = open(os.path.join(here, 'update-binary'), 'rb').read().replace(b'\r\n', b'\n')
    h = hashlib.sha256(img).hexdigest()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        def add(name, data, mode=0o644):
            zi = zipfile.ZipInfo(name, date_time=(2026, 11, 17, 0, 0, 0))   # fixed date: same input, same zip
            zi.external_attr = mode << 16
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, data)
        add('META-INF/com/google/android/update-binary', ub, 0o755)
        add('META-INF/com/google/android/updater-script', b'# TWRP installer for the HiBy R4: see update-binary\n')
        add('recovery.img', img)
        add('recovery.img.sha256', (h + '  recovery.img\n').encode())
    print('image', h)
    print('zip  ', hashlib.sha256(open(out, 'rb').read()).hexdigest(), out)

if __name__ == '__main__':
    main()
