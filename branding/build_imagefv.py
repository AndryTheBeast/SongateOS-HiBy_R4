"""Build a HiBy R4 imagefv partition image with a new boot logo (the first logo, shown by XBL/UEFI).

imagefv (your own stock backup, never modified) = signed ELF:
  seg0 ELF header, seg1 hash table (SHA-384 per segment + HiBy signature/certs), seg2 @0x3000 = UEFI FV (0x1f0000)
  FV -> one FFS file -> LZMA GUID section -> [raw section, FV-image section -> inner FV with BMP files]
  The boot logo is the inner file "logo_custom.bmp": 750x1334, 24-bit, bottom-up BMP.
We only replace that BMP's pixels (same size/header), recompress, fix the FFS/section sizes + FFS header checksum,
and write the new SHA-384 of seg2 into the hash table. The signature stays HiBy's and no longer matches: fine only
when secure boot is off (check: fastboot getvar secure -> no). Everything is verified by parsing it back.

usage: python build_imagefv.py ORIG_imagefv.bin LOGO.png OUT.bin [SYMBOLS_DIR]
"""
import glob
import hashlib
import io
import lzma
import os
import struct
import sys
import uuid

import numpy as np
from PIL import Image

W, H = 750, 1334
SEG2, SEG2_LEN = 0x3000, 0x1f0000
HASH_SEG = 0x1000
SEG2_HASH_AT = HASH_SEG + 264        # SHA-384 of seg2 inside the hash table (found by matching the stock image)
FFS = 0x3048                         # the single FFS file in the outer FV
SEC = FFS + 24                       # its LZMA GUID section (24-byte header)
LZMA_GUID = uuid.UUID("ee4e5898-3914-4259-9d6e-dc7bd79403cf").bytes_le   # EDK2 LZMA custom decompress


def lzma_unpack(sec_bytes):
    """Decode like EDK2's LzmaDecompress (LZMA SDK): 13-byte header with the exact size; an end marker after
    the data is optional (stock has none, Python's encoder always writes one - both are valid for LzmaDec)."""
    c = sec_bytes[24:]
    p = c[0]
    lc, lp, pb = p % 9, (p // 9) % 5, p // 45
    dict_size = int.from_bytes(c[1:5], "little")
    usize = int.from_bytes(c[5:13], "little")
    dec = lzma.LZMADecompressor(format=lzma.FORMAT_RAW, filters=[
        {"id": lzma.FILTER_LZMA1, "dict_size": dict_size, "lc": lc, "lp": lp, "pb": pb}])
    out = dec.decompress(c[13:], max_length=usize)
    assert len(out) == usize, (len(out), usize)
    return out


def lzma_pack(raw):
    body = lzma.compress(raw, format=lzma.FORMAT_RAW,
                         filters=[{"id": lzma.FILTER_LZMA1, "dict_size": 1 << 24, "lc": 3, "lp": 0, "pb": 2}])
    return bytes([0x5d]) + (1 << 24).to_bytes(4, "little") + len(raw).to_bytes(8, "little") + body


def find_bmp(inner):
    name = "logo_custom.bmp".encode("utf-16le")
    i = inner.find(name)
    assert i > 0, "logo_custom.bmp not found"
    o = inner.find(b"BM", i)
    size, = struct.unpack_from("<I", inner, o + 2)
    w, h = struct.unpack_from("<ii", inner, o + 18)
    bpp, = struct.unpack_from("<H", inner, o + 28)
    off, = struct.unpack_from("<I", inner, o + 10)
    assert (w, h, bpp) == (W, H, 24), (w, h, bpp)
    return o, size, off


def bmp_pixels(png_path):
    im = Image.open(png_path).convert("RGB")
    assert im.size == (W, H), "logo must be %dx%d, got %s" % (W, H, im.size)
    row = W * 3
    pad = (4 - row % 4) % 4
    out = bytearray()
    px = im.tobytes()
    for y in range(H - 1, -1, -1):                       # bottom-up
        line = px[y * row:(y + 1) * row]
        bgr = bytearray(line)
        bgr[0::3], bgr[2::3] = line[2::3], line[0::3]   # RGB -> BGR
        out += bgr + b"\0" * pad
    return bytes(out)


def find_named_bmp(inner, name):
    i = inner.find(name.encode("utf-16le"))
    assert i > 0, name
    return inner.find(b"BM", i)


def replace_bmp4(inner, name, png_path):
    """Swap a 4-bpp BMP's palette (its used colours) and pixels in place; same size, same header."""
    o = find_named_bmp(inner, name + ".bmp")
    off, = struct.unpack_from("<I", inner, o + 10)
    hs, w, h, planes, bpp, comp = struct.unpack_from("<IiiHHI", inner, o + 14)
    used, = struct.unpack_from("<I", inner, o + 46)
    ncol = used or 16                       # biClrUsed 0 = the full 16-colour palette (stock Debug* bitmaps)
    assert bpp == 4 and comp == 0 and h > 0 and off == 14 + hs + 4 * ncol, (name, bpp, comp, off, used)
    im = Image.open(png_path)
    assert im.mode == "P" and im.size == (w, h), (name, im.mode, im.size)
    idx = np.asarray(im, dtype=np.uint8)
    assert idx.max() < ncol, (name, int(idx.max()), ncol)
    pal = im.getpalette()[:3 * ncol]
    pal += [0] * (3 * ncol - len(pal))
    for k in range(ncol):
        r, g, b = pal[3 * k:3 * k + 3]
        inner[o + 14 + hs + 4 * k:o + 14 + hs + 4 * k + 4] = bytes([b, g, r, 0])
    rowb = ((w * 4 + 31) // 32) * 4
    for y in range(h):
        row = idx[h - 1 - y]
        if w % 2:
            row = np.append(row, np.uint8(0)).astype(np.uint8)
        packed = ((row[0::2] << 4) | row[1::2]).astype(np.uint8)
        assert len(packed) == (w + 1) // 2
        start = o + off + y * rowb
        inner[start:start + len(packed)] = packed.tobytes()
        inner[start + len(packed):start + rowb] = b"\0" * (rowb - len(packed))
    return o


def ffs_header_checksum(hdr):
    h = bytearray(hdr)
    h[16] = 0          # IntegrityCheck.Header
    h[17] = 0          # IntegrityCheck.File (excluded)
    h[23] = 0          # State (excluded)
    return (-sum(h)) & 0xff


def build(orig, png, symbols=None):
    d = bytearray(orig)
    sec_size = int.from_bytes(d[SEC:SEC + 3], "little")
    assert d[SEC + 3] == 0x02 and bytes(d[SEC + 4:SEC + 20]) == LZMA_GUID
    inner = bytearray(lzma_unpack(bytes(d[SEC:SEC + sec_size])))
    o, size, off = find_bmp(inner)
    pix = bmp_pixels(png)
    assert off + len(pix) <= size, (off, len(pix), size)
    inner[o + off:o + off + len(pix)] = pix
    n_before = len(inner)
    for name, p in (symbols or {}).items():
        replace_bmp4(inner, name, p)
    assert len(inner) == n_before

    comp = lzma_pack(bytes(inner))
    new_sec = (24 + len(comp)).to_bytes(3, "little") + b"\x02" + bytes(d[SEC + 4:SEC + 24]) + comp
    ffs_hdr = bytearray(d[FFS:FFS + 24])
    ffs_size = 24 + len(new_sec)
    ffs_hdr[20:23] = ffs_size.to_bytes(3, "little")
    ffs_hdr[16] = ffs_header_checksum(ffs_hdr)
    assert ffs_hdr[17] == 0xAA and ffs_hdr[19] & 0x40 == 0     # no file checksum used
    body = bytes(ffs_hdr) + new_sec
    end = SEG2 + SEG2_LEN
    assert FFS + len(body) <= end, "new logo compresses too big for the volume (%d bytes over)" % (FFS + len(body) - end)
    d[FFS:end] = body + b"\xff" * (end - FFS - len(body))
    d[SEG2_HASH_AT:SEG2_HASH_AT + 48] = hashlib.sha384(bytes(d[SEG2:end])).digest()
    return bytes(d), len(body)


def bmp_span(inner, name):
    o = find_named_bmp(inner, name)
    size, = struct.unpack_from("<I", inner, o + 2)
    return o, o + size


def verify(img, orig, png, symbols=None):
    assert len(img) == len(orig)
    assert img[:SEG2] == orig[:SEG2_HASH_AT] + img[SEG2_HASH_AT:SEG2_HASH_AT + 48] + orig[SEG2_HASH_AT + 48:SEG2]
    assert img[SEG2 + SEG2_LEN:] == orig[SEG2 + SEG2_LEN:]
    assert img[SEG2:FFS] == orig[SEG2:FFS]                       # FV header unchanged
    assert hashlib.sha384(img[SEG2:SEG2 + SEG2_LEN]).digest() == img[SEG2_HASH_AT:SEG2_HASH_AT + 48]
    hdr = img[FFS:FFS + 24]
    assert ffs_header_checksum(hdr) == hdr[16]
    ffs_size = int.from_bytes(hdr[20:23], "little")
    sec_size = int.from_bytes(img[SEC:SEC + 3], "little")
    assert sec_size == ffs_size - 24
    inner_new = lzma_unpack(img[SEC:SEC + sec_size])
    inner_old = lzma_unpack(orig[SEC:SEC + int.from_bytes(orig[SEC:SEC + 3], "little")])
    o, size, off = find_bmp(inner_new)
    assert len(inner_new) == len(inner_old)
    # every changed byte lies inside a bitmap we replaced (the logo, the symbols)
    spans = [(o, o + size)] + [bmp_span(inner_new, n + ".bmp") for n in (symbols or {})]
    a = np.frombuffer(inner_new, np.uint8)
    b = np.frombuffer(inner_old, np.uint8)
    changed = np.nonzero(a != b)[0]
    inside = np.zeros(len(changed), bool)
    for s, e in spans:
        inside |= (changed >= s) & (changed < e)
    assert inside.all(), "changes outside the replaced bitmaps at %s" % changed[~inside][:5]
    pix = bmp_pixels(png)
    assert inner_new[o + off:o + off + len(pix)] == pix
    for n, p in (symbols or {}).items():          # each symbol decodes back to exactly the design
        s, e = bmp_span(inner_new, n + ".bmp")
        got = np.asarray(Image.open(io.BytesIO(inner_new[s:e])).convert("RGB"))
        want = np.asarray(Image.open(p).convert("RGB"))
        assert (got == want).all(), n
    return inner_new[o:o + size]


if __name__ == "__main__":
    orig_path, png, out = sys.argv[1:4]
    sym_dir = sys.argv[4] if len(sys.argv) > 4 else None    # optional: dir of <name>.png 4-colour battery symbols
    symbols = {os.path.basename(p)[:-4]: p for p in glob.glob(os.path.join(sym_dir, "*_symbol*.png"))} if sym_dir else {}
    orig = open(orig_path, "rb").read()
    img, used = build(orig, png, symbols)
    bmp = verify(img, orig, png, symbols)
    print("replaced:", ["logo_custom"] + sorted(symbols))
    open(out, "wb").write(img)
    open(out + ".logo.bmp", "wb").write(bmp)
    print("OK  %s  volume used %d / %d bytes  sha256 %s" % (out, used, SEG2_LEN - (FFS - SEG2), hashlib.sha256(img).hexdigest()))
