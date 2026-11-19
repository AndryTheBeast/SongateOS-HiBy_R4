#!/usr/bin/env python3
"""Check a whole-file signed update zip (signapk -w) the way the recovery's verifier does.

    verify_ota_zip.py <zip> <certificate.x509.pem>

Footer layout (AOSP recovery/install/verifier.cpp): the zip comment ends with 6 bytes
  [signature_start: u16 LE] [0xffff] [comment_size: u16 LE]
The PKCS#7 signature is the `signature_start - 6` bytes before that footer; the signed data is the whole file
except the comment and its 2-byte length field. Uses only the openssl command line tool.
"""
import os
import struct
import subprocess
import sys
import tempfile


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    path, cert = sys.argv[1], sys.argv[2]
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        f.seek(size - 6)
        sig_start, magic, comment_size = struct.unpack("<HHH", f.read(6))
        if magic != 0xFFFF:
            sys.exit("FAIL: no whole-file signature footer")
        if sig_start > comment_size:
            sys.exit("FAIL: bad footer")
        eocd = size - (comment_size + 22)
        f.seek(eocd)
        if f.read(4) != b"PK\x05\x06":
            sys.exit("FAIL: EOCD not where the comment size says")
        f.seek(eocd + 20)
        if struct.unpack("<H", f.read(2))[0] != comment_size:
            sys.exit("FAIL: comment size mismatch")
        f.seek(size - sig_start)
        sig = f.read(sig_start - 6)
        signed_len = size - comment_size - 2
        with tempfile.TemporaryDirectory() as t:
            sp = os.path.join(t, "sig.der")
            dp = os.path.join(t, "data.bin")
            with open(sp, "wb") as o:
                o.write(sig)
            with open(dp, "wb") as o:
                f.seek(0)
                left = signed_len
                while left:
                    b = f.read(min(left, 1 << 22))
                    o.write(b)
                    left -= len(b)
            # signature must verify with exactly our certificate (not just any embedded one)
            r = subprocess.run(["openssl", "cms", "-verify", "-binary", "-inform", "DER", "-in", sp,
                                "-content", dp, "-certfile", cert, "-nointern", "-noverify",
                                "-out", os.devnull], capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit("FAIL: signature does not verify with %s\n%s" % (cert, r.stderr.strip()))
    print("OK: %s is signed with %s" % (os.path.basename(path), os.path.basename(cert)))


if __name__ == "__main__":
    main()
