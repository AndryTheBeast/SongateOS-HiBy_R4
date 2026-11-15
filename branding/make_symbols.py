"""SongateOS versions of the bootloader's battery screens (imagefv, shown by XBL when the battery is too low to boot).
Same names, sizes and 4-colour palette layout as stock (4 bpp BMP, 4 colours used), so nothing else in the volume
moves. Palette: black, a dim warm grey (outline), cream (icons), Songate coral (low level / warnings).

    python make_symbols.py OUTDIR    -> OUTDIR/<name>.png (palette images, 4 colours)
"""
import os
import sys

from PIL import Image, ImageDraw

from brand import ACCENT, BG, TXT, mix

PAL = [(0, 0, 0), tuple(int(c) for c in mix((0, 0, 0), TXT, .42)), TXT, ACCENT]
BLACK, DIM, CREAM, CORAL = range(4)
SIZES = {
    "battery_symbol_Soc10": (545, 237),
    "battery_symbol_NoBattery": (551, 318),
    "battery_symbol_Nocharger": (606, 270),
    "battery_symbol_LowBatteryCharging": (606, 270),
    "battery_symbol_LowBattery": (606, 270),
}


def battery(w, h, level, level_col, inner=None):
    im = Image.new("P", (w, h), BLACK)
    im.putpalette([c for p in PAL for c in p] + [0] * (768 - 12))
    d = ImageDraw.Draw(im)
    m = 14
    cap_w = 22
    body = (m, m + 8, w - m - cap_w - 6, h - m - 8)
    rad = (body[3] - body[1]) // 4
    d.rounded_rectangle(body, radius=rad, outline=DIM, width=10)
    ch = (body[3] - body[1]) * .36
    cy = (body[1] + body[3]) / 2
    d.rounded_rectangle((body[2] + 8, cy - ch / 2, body[2] + 8 + cap_w, cy + ch / 2), radius=8, fill=DIM)
    pad = 24
    fill_l, fill_r = body[0] + pad, body[2] - pad
    if level > 0:
        d.rounded_rectangle((fill_l, body[1] + pad, fill_l + max(2 * rad // 2, (fill_r - fill_l) * level), body[3] - pad),
                            radius=max(6, rad - pad // 2), fill=level_col)
    if inner:
        inner(d, (body[0] + body[2]) / 2 + 30, cy, (body[3] - body[1]) * .62)
    return im


def plug(d, cx, cy, s, col=CREAM):
    """A plug: rounded body, two prongs, cable."""
    bw, bh = s * .42, s * .40
    d.rounded_rectangle((cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), radius=int(s * .1), fill=col)
    pw, ph = s * .07, s * .2
    for dx in (-bw * .24, bw * .24):
        d.rounded_rectangle((cx + dx - pw / 2, cy - bh / 2 - ph, cx + dx + pw / 2, cy - bh / 2 + 4), radius=int(pw / 2), fill=col)
    cw = s * .1
    d.rounded_rectangle((cx - cw / 2, cy + bh / 2 - 4, cx + cw / 2, cy + bh / 2 + s * .28), radius=int(cw / 2), fill=col)


def cross(d, cx, cy, s, col=CORAL):
    r = s * .42
    d.line((cx - r, cy - r, cx + r, cy + r), fill=col, width=int(s * .09))
    d.line((cx - r, cy + r, cx + r, cy - r), fill=col, width=int(s * .09))


def make(name):
    w, h = SIZES[name]
    if name == "battery_symbol_Soc10":          # charging, enough to boot soon
        return battery(w, h, .3, CREAM, lambda d, x, y, s: plug(d, x + 40, y, s, CORAL))
    if name == "battery_symbol_NoBattery":
        return battery(w, h, 0, CORAL, lambda d, x, y, s: cross(d, x - 30, y, s * 1.2))
    if name == "battery_symbol_Nocharger":      # charger not usable
        def f(d, x, y, s):
            plug(d, x + 40, y, s, DIM)
            cross(d, x + 40, y, s * .9)
        return battery(w, h, .08, CORAL, f)
    if name == "battery_symbol_LowBatteryCharging":
        return battery(w, h, .08, CORAL, lambda d, x, y, s: plug(d, x + 40, y, s, CREAM))
    if name == "battery_symbol_LowBattery":
        return battery(w, h, .08, CORAL)
    raise KeyError(name)


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    tiles = []
    for n in SIZES:
        im = make(n)
        im.save(os.path.join(out, n + ".png"))
        tiles.append(im.convert("RGB"))
    sheet = Image.new("RGB", (sum(t.width for t in tiles) + 20 * len(tiles), max(t.height for t in tiles)), (60, 60, 60))
    x = 0
    for t in tiles:
        sheet.paste(t, (x, 0))
        x += t.width + 20
    sheet.save(os.path.join(out, "sheet.png"))
    print("ok", list(SIZES))
