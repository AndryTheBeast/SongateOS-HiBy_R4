"""Nine directions for the SongateOS boot logo (750x1334), numbered on one sheet: logo_options.png.
Each keeps "powered by Songate" at the bottom; the centre changes. Pick one by number.
"""
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from brand import (ACCENT, ACCENT2, BG, GLOW, TXT, W, H, Word, glow, inter, mix, sparkle, text)

WHITE = (255, 255, 255)
STAR = (255, 244, 236)
GA, GB, GC = mix(ACCENT, TXT, .12), mix(ACCENT2, TXT, .2), mix(ACCENT2, TXT, .78)
NEON = [(0.18, (22, 214, 255)), (0.45, (106, 123, 255)), (0.66, (255, 63, 192)), (0.86, (255, 45, 74))]   # the app icon
WARM = [(0.0, GA), (0.45, GB), (1.0, GC)]


def ic(c):
    return tuple(int(round(v)) for v in c)


def backdrop(light=.09):
    im = Image.new("RGB", (W, H), BG)
    glow(im, W / 2, H * 0.47, W * 0.9, mix(BG, GLOW, light), 0.9)
    return im


def bottom(im):
    by = H - 150
    text(im, "POWERED BY", inter("SemiBold", 19), W / 2, by - 62, mix(TXT, BG, 0.45), spacing=0.22)
    word = Word(66)
    wx, wy = word.draw(im, W / 2, by, unfurl=1.0, shine=0.72, glow_a=0.5)
    sparkle(im, wx + word.w - 4, wy + word.h * 0.06, 15, STAR)
    sparkle(im, wx + word.w * 0.64, wy - 10, 8, STAR)


def ramp(t, stops):
    t = np.clip(t, 0, 1)
    xs = [s[0] for s in stops]
    return np.stack([np.interp(t, xs, [s[1][c] for s in stops]) for c in range(3)], -1).astype(np.float32)


def paint(im, mask, stops, ax=1.0, ay=0.0, box=None, glow_col=None, glow_amt=.6, blur=18):
    """Colour a full-screen 'L' mask with a gradient across its box (direction ax, ay) + a glow under it."""
    a = np.asarray(im, np.float32)
    m = np.asarray(mask, np.float32) / 255
    if glow_col is not None and glow_amt > 0:
        g = np.asarray(mask.filter(ImageFilter.GaussianBlur(blur)), np.float32) / 255
        a += (np.array(glow_col, np.float32) - a) * (np.clip(g * 1.3, 0, 1) * glow_amt)[..., None]
    x0, y0, x1, y1 = box or mask.getbbox()
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    t = (((xx - x0) / max(1, x1 - x0)) * ax + ((yy - y0) / max(1, y1 - y0)) * ay) / (ax + ay)
    col = ramp(t, stops)
    a += (col - a) * m[..., None]
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)))


def gate_masks(cx, cy, size, note=True):
    """Songate's gate (android-icon.py ARCH/NOTE, viewBox 0..100) as full-screen masks, 4x supersampled."""
    S = 4
    big_a = Image.new("L", (W * S, H * S), 0)
    big_n = Image.new("L", (W * S, H * S), 0)
    k = size / 100 * S
    ox, oy = cx * S - 50 * k, cy * S - 50 * k
    P = lambda x, y: (ox + x * k, oy + y * k)
    da, dn = ImageDraw.Draw(big_a), ImageDraw.Draw(big_n)
    sw = 17.5 * k
    # posts from y=47 to 91 at x=16 and 84, the half circle r=34 around (50,47)
    for x in (16, 84):
        da.line([P(x, 47), P(x, 91)], fill=255, width=int(sw))
        cxp, cyp = P(x, 91)
        da.ellipse((cxp - sw / 2, cyp - sw / 2, cxp + sw / 2, cyp + sw / 2), fill=255)
    c0 = P(50, 47)
    r = 34 * k
    da.arc((c0[0] - r - sw / 2, c0[1] - r - sw / 2, c0[0] + r + sw / 2, c0[1] + r + sw / 2), 180, 360, fill=255, width=int(sw))
    if note:
        nc = P(43.5, 72.5)
        nr = 11.5 * k
        dn.ellipse((nc[0] - nr, nc[1] - nr, nc[0] + nr, nc[1] + nr), fill=255)
        nw = 9 * k
        pts = [P(51, 72), P(51, 40.5), P(64.5, 46.5)]
        dn.line(pts, fill=255, width=int(nw), joint="curve")
        for p in pts:
            dn.ellipse((p[0] - nw / 2, p[1] - nw / 2, p[0] + nw / 2, p[1] + nw / 2), fill=255)
    return big_a.resize((W, H), Image.LANCZOS), big_n.resize((W, H), Image.LANCZOS)


def ring(im, cx, cy, r, thick, glow_amt=.5):
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32) + .5
    dx, dy = xx - cx, yy - cy
    ang = (np.arctan2(dx, -dy) / (2 * math.pi)) % 1.0
    band = np.clip(thick / 2 + .5 - np.abs(np.hypot(dx, dy) - r), 0, 1)
    mask = Image.fromarray((band * 255).astype(np.uint8))
    a = np.asarray(im, np.float32)
    g = np.asarray(mask.filter(ImageFilter.GaussianBlur(16)), np.float32) / 255
    a += (np.array(GLOW, np.float32) - a) * (np.clip(g * 1.3, 0, 1) * glow_amt)[..., None]
    u = 1 - np.abs(ang - .5) * 2              # coral at the top, cream at the bottom, no seam
    a += (ramp(u, WARM) - a) * band[..., None]
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)))


# ---------------------------------------------------------------------------------------------------------------- 1..9

def o1_ring():
    """The ring (the charging ring, closed), the star at its top; HiBy over R4 inside."""
    im = backdrop()
    cx, cy, r = W / 2, H * 0.45, 190
    ring(im, cx, cy, r, 6, .45)
    glow(im, cx, cy - r, 70, mix(ACCENT, WHITE, .5), .35)
    sparkle(im, cx, cy - r, 30, WHITE)
    text(im, "HiBy", inter("SemiBold", 34), cx, cy - 64, mix(TXT, BG, .1), spacing=0.04)
    Word(150, pad=90, word="R4").draw(im, cx, cy + 34, glow_a=0.22, shine=0.75)
    bottom(im)
    return im


def o2_oneline():
    """'HiBy R4' in the wordmark's own face and gradient, one star - as calm as the Songate name."""
    im = backdrop()
    cx, cy = W / 2, H * 0.46
    w = Word(118, pad=120, word="HiBy R4")
    lx, ly = w.draw(im, cx, cy, shine=0.8, glow_a=0.24)
    sparkle(im, lx + w.w + 4, ly + w.h * .02, 22, STAR)
    sparkle(im, lx + w.w * .63, ly - 22, 10, STAR)
    ImageDraw.Draw(im).rounded_rectangle((cx - 22, ly + w.h + 44, cx + 22, ly + w.h + 48), radius=2, fill=ic(mix(ACCENT, BG, .2)))
    text(im, "DIGITAL AUDIO PLAYER", inter("SemiBold", 17), cx, ly + w.h + 84, mix(TXT, BG, .45), spacing=0.3)
    bottom(im)
    return im


def o3_editorial():
    """Editorial: HIBY between two coral hairlines, a big cream R4 with a quiet glow."""
    im = backdrop()
    cx, cy = W / 2, H * 0.455
    r4 = Word(200, pad=120, word="R4", stops=(mix(ACCENT2, TXT, .5), TXT, TXT))
    lx, ly = r4.draw(im, cx, cy + 24, shine=0.78, glow_a=0.16)
    yl = ly - 52
    text(im, "HIBY", inter("SemiBold", 26), cx, yl, mix(TXT, BG, .15), spacing=0.5)
    d = ImageDraw.Draw(im)
    for s in (-1, 1):
        x0 = cx + s * 92
        d.line((x0, yl, x0 + s * 80, yl), fill=ic(mix(ACCENT, BG, .3)), width=2)
    sparkle(im, lx + r4.w + 6, ly + r4.h * .04, 26, STAR)
    bottom(im)
    return im


def o4_fine_serif():
    """Fine serif: 'HiBy R4' in Fraunces Light at display size (opsz 144), cream, very quiet."""
    im = backdrop(.07)
    cx, cy = W / 2, H * 0.46
    w = Word(124, pad=120, word="HiBy R4", wght=330, soft=0, wonk=0, opsz=144,
             stops=(mix(ACCENT2, TXT, .55), TXT, mix(ACCENT2, TXT, .55)))
    lx, ly = w.draw(im, cx, cy, shine=0.5, glow_a=0.12)
    sparkle(im, lx + w.w + 10, ly + 4, 18, STAR)
    text(im, "Hi-Res Digital Audio Player", inter("Regular", 20), cx, ly + w.h + 62, mix(TXT, BG, .5), spacing=0.06)
    bottom(im)
    return im


def o5_gate_neon():
    """Songate's own emblem, exactly the app icon's neon gate and note, above 'HiBy R4'."""
    im = backdrop(.06)
    cx, cy = W / 2, H * 0.41
    arch, note = gate_masks(cx, cy, 250)
    bb = arch.getbbox()
    paint(im, arch, NEON, 1, .55, box=bb, glow_col=(255, 63, 192), glow_amt=.55, blur=22)
    paint(im, note, [(0, WHITE), (1, WHITE)])
    text(im, "HiBy R4", inter("ExtraBold", 56), cx, bb[3] + 86, TXT, spacing=0.01)
    bottom(im)
    return im


def o6_gate_warm():
    """The gate in Songate's warm wordmark colours, R4 standing in it instead of the note; HIBY above."""
    im = backdrop()
    cx, cy = W / 2, H * 0.45
    arch, _ = gate_masks(cx, cy, 330, note=False)
    bb = arch.getbbox()
    paint(im, arch, WARM, 1, .4, box=bb, glow_col=GLOW, glow_amt=.55, blur=20)
    Word(88, pad=80, word="R4").draw(im, cx, (bb[1] + bb[3]) / 2 + 56, glow_a=0.18, shine=0.7)
    text(im, "HIBY", inter("Bold", 24), cx, bb[1] - 46, mix(TXT, BG, .2), spacing=0.55)
    glow(im, cx, bb[1] + 2, 60, mix(ACCENT, WHITE, .5), .3)
    sparkle(im, cx, bb[1] + 9, 24, WHITE)
    bottom(im)
    return im


def o7_visualizer():
    """The app's visualizer bars (now playing) as a mirrored wave under 'HiBy R4'."""
    im = backdrop()
    cx, cy = W / 2, H * 0.44
    w = Word(104, pad=110, word="HiBy R4")
    lx, ly = w.draw(im, cx, cy, shine=0.8, glow_a=0.22)
    n, bw, gap = 23, 12, 7
    total = n * bw + (n - 1) * gap
    base = ly + w.h + 130
    mask = Image.new("L", (W * 4, H * 4), 0)
    d = ImageDraw.Draw(mask)
    for i in range(n):
        u = i / (n - 1)
        hgt = 10 + 70 * math.exp(-((u - .5) / .22) ** 2) * (.6 + .4 * abs(math.sin(i * 1.7)))
        x = cx - total / 2 + i * (bw + gap)
        d.rounded_rectangle((x * 4, (base - hgt / 2) * 4, (x + bw) * 4, (base + hgt / 2) * 4), radius=bw * 2, fill=255)
    mask = mask.resize((W, H), Image.LANCZOS)
    paint(im, mask, WARM, 1, 0, glow_col=GLOW, glow_amt=.45, blur=12)
    bottom(im)
    return im


def o8_star():
    """The opening's star as the emblem, big and glowing, 'HiBy R4' under it in the wordmark's face."""
    im = backdrop()
    cx, cy = W / 2, H * 0.38
    glow(im, cx, cy, 190, mix(GLOW, ACCENT, .4), .45)
    glow(im, cx, cy, 90, mix(ACCENT, WHITE, .5), .5)
    sparkle(im, cx, cy, 120, WHITE)
    sparkle(im, cx + 110, cy - 70, 16, STAR, .9)
    sparkle(im, cx - 96, cy + 60, 10, STAR, .7)
    Word(96, pad=100, word="HiBy R4").draw(im, cx, cy + 210, shine=-1, glow_a=0.2)
    bottom(im)
    return im


def o9_disc():
    """A record seen from above: grooves, a warm label with R4, the star glinting on the rim; HIBY above."""
    im = backdrop(.07)
    cx, cy, R = W / 2, H * 0.45, 230
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32) + .5
    dx, dy = xx - cx, yy - cy
    dist = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx)
    disc = np.clip(R + .5 - dist, 0, 1)
    grooves = .5 + .5 * np.sin(dist * 1.4)
    sheen = np.clip(np.cos(2 * (ang + .7)), 0, 1) ** 6 * .5          # light catching the grooves
    base = np.array((14, 10, 11), np.float32)
    col = base[None, None, :] + (14 * grooves + 60 * sheen * (dist > 95))[..., None] * np.array((1.0, .8, .75), np.float32)
    a = np.asarray(im, np.float32)
    g = np.asarray(Image.fromarray((disc * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(26)), np.float32) / 255
    a += (np.array(GLOW, np.float32) - a) * (g * .35)[..., None]
    a += (col - a) * disc[..., None]
    lab = np.clip(92 + .5 - dist, 0, 1)
    a += (ramp((dx + 92) / 184, WARM) - a) * lab[..., None]
    hole = np.clip(6 + .5 - dist, 0, 1)
    a += (np.array(BG, np.float32) - a) * hole[..., None]
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)))
    text(im, "R4", inter("ExtraBold", 54), cx, cy - 30, (40, 14, 14))
    text(im, "HiBy", inter("Bold", 18), cx, cy + 34, (70, 30, 28), spacing=0.2)
    glow(im, cx + R * .71, cy - R * .71, 50, mix(ACCENT, WHITE, .5), .35)
    sparkle(im, cx + R * .71, cy - R * .71, 26, WHITE)
    bottom(im)
    return im


OPTIONS = [o1_ring, o2_oneline, o3_editorial, o4_fine_serif, o5_gate_neon, o6_gate_warm, o7_visualizer, o8_star, o9_disc]

if __name__ == "__main__":
    tiles = []
    for i, f in enumerate(OPTIONS, 1):
        im = f()
        im.save("logo_option_%d.png" % i)
        t = im.resize((W // 2, H // 2), Image.LANCZOS)
        d = ImageDraw.Draw(t)
        d.rounded_rectangle((14, 14, 70, 62), radius=12, fill=(255, 255, 255))
        d.text((42, 38), str(i), font=inter("ExtraBold", 30), fill=(20, 8, 9), anchor="mm")
        tiles.append(t)
        print("option", i, f.__name__)
    tw, th = tiles[0].size
    sheet = Image.new("RGB", (tw * 3 + 40, th * 3 + 40), (70, 70, 70))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % 3) * (tw + 20), (i // 3) * (th + 20)))
    sheet.save("logo_options.png")
