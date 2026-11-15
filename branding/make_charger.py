"""SongateOS off-mode charging animation for Android's charger (healthd charger mode, /system/bin/charger).

Android's charger reads, when present (Android 12+):
    /product/etc/res/values/charger/animation.txt
    /product/etc/res/images/charger/<files>.png
It clears the screen to black, centres the current frame, and draws the battery % with a bitmap font.
Frames are ONE interlaced PNG (tEXt "Frames"); each frame has a battery-level range and the charger only plays the
frames whose range holds the current level - so frame k (ring filled to F_k) gets range 0..F_k: the ring starts at
the current level and sweeps up to full, then the full ring twinkles; repeated a few cycles, then the screen sleeps.

Look: Songate's opening - the wordmark's gradient (coral -> amber -> cream), its glow, its four-pointed star at the
head of the arc, Inter for text, "CHARGING" as the app's coral small caps (the "PLAYING" kicker); the ring centred on the screen.

    python make_charger.py OUTDIR      -> OUTDIR/product/etc/res/... + OUTDIR/preview_*.gif
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from brand import ACCENT, ACCENT2, GLOW, TXT, W, H, Word, glow, inter, mix, sparkle, text

FW, FH = 560, 560            # square frame, centred on the 750x1334 screen by the charger
RC = (FW / 2, FH / 2)        # ring centre = frame centre = screen centre (the user, 2026-11-14: centred, no name)
R, THICK = 196, 16           # ring radius and stroke
STEP = 4                     # % per fill frame: 0,4,...,100 -> 26 frames
HOLD = 8                     # frames of the full ring twinkling at the end
FONT_H = 84                  # percent digits cell height
BLACK = (0, 0, 0)

# gradient along the arc (from 12 o'clock, clockwise): the wordmark's three stops
GA, GB, GC = mix(ACCENT, TXT, .12), mix(ACCENT2, TXT, .2), mix(ACCENT2, TXT, .78)


def grad(u):
    u = np.clip(u, 0, 1)
    out = np.empty(u.shape + (3,), np.float32)
    for i in range(3):
        out[..., i] = np.where(u < .45, GA[i] + (GB[i] - GA[i]) * (u / .45), GB[i] + (GC[i] - GB[i]) * ((u - .45) / .55))
    return out


yy, xx = np.mgrid[0:FH, 0:FW].astype(np.float32) + .5
dx, dy = xx - RC[0], yy - RC[1]
dist = np.hypot(dx, dy)
ang = (np.arctan2(dx, -dy) / (2 * math.pi)) % 1.0          # 0 at 12 o'clock, clockwise


def ring_masks(fill):
    """Antialiased masks: the track (whole circle) and the arc 0..fill with round caps."""
    band = np.clip(THICK / 2 + .5 - np.abs(dist - R), 0, 1)
    track = band
    if fill <= 0:
        return track, np.zeros_like(band)
    if fill >= 1:
        return track, band
    inside = (ang <= fill).astype(np.float32)
    # soft edges + round caps at both ends
    arc = band * inside
    for a in (0.0, fill):
        cx = RC[0] + R * math.sin(2 * math.pi * a)
        cy = RC[1] - R * math.cos(2 * math.pi * a)
        cap = np.clip(THICK / 2 + .5 - np.hypot(xx - cx, yy - cy), 0, 1)
        arc = np.maximum(arc, cap)
    return track, arc


def base_layer(label):
    """Everything that never changes: black, the warm light behind the ring, the label, the name."""
    im = Image.new("RGB", (FW, FH), BLACK)
    a = np.asarray(im, np.float32)
    # warm light behind the ring, fading to pure black well inside the frame (the charger's black around it)
    r = np.hypot(dx, dy * 1.0) / (FW * .5)
    k = np.clip(1 - r, 0, 1) ** 2 * .55
    a += (np.array(mix(BLACK, GLOW, .22), np.float32) - a) * k[..., None]
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    # "CHARGING" / "CHARGED" under the percent, inside the ring (the app's kicker style)
    text(im, label, inter("Bold", 17), RC[0], RC[1] + 62, ACCENT, spacing=0.24)
    return im


BASE = {k: np.asarray(base_layer(k), np.float32) for k in ("CHARGING", "CHARGED")}
TRACK_COL = np.array(mix(BLACK, TXT, .09), np.float32)


def frame(fill, twinkle=0.0, t=0.0, label="CHARGING"):
    a = BASE[label].copy()
    track, arc = ring_masks(fill)
    a += (TRACK_COL - a) * track[..., None]
    if fill > 0:
        # glow of the arc (blurred copy, in the glow red then accent near the stroke)
        g = np.asarray(Image.fromarray((arc * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(14)), np.float32) / 255
        a += (np.array(GLOW, np.float32) - a) * (np.clip(g * 1.25, 0, 1) * .55)[..., None]
        g2 = np.asarray(Image.fromarray((arc * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(4)), np.float32) / 255
        a += (np.array(ACCENT, np.float32) - a) * (np.clip(g2, 0, 1) * .25)[..., None]
        # position along the arc 0..1; the start cap pokes just left of 12 o'clock (ang ~1): that is the start
        pos = np.where((ang > fill) & (ang > .5), 0.0, ang) if fill < 1 else ang
        col = grad(pos / max(fill, 1e-3) if fill < 1 else pos)
        # a soft shimmer travelling along the arc (the wordmark's shine band)
        sh = ((ang / max(fill, 1e-3)) - (t % 1.4 - .2)) / .16
        band = np.exp(-4.5 * sh * sh) * (1 - sh * sh) * (np.abs(sh) < 1)
        col = col + (255 - col) * (band * .55)[..., None]
        a += (col - a) * arc[..., None]
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    # the star at the arc's head (the opening's star), and a few motes of dust behind it
    if fill > 0:
        hx = RC[0] + R * math.sin(2 * math.pi * fill)
        hy = RC[1] - R * math.cos(2 * math.pi * fill)
        size = 34 + 8 * math.sin(t * 9) + 14 * twinkle
        glow(im, hx, hy, 46, mix(ACCENT, (255, 255, 255), .55), .45)     # the opening star's light
        sparkle(im, hx, hy, size, (255, 255, 255), 1.0)
        for i in range(5):
            back = fill - .015 * (i + 1) - .01 * math.sin(t * 5 + i)
            if back <= 0:
                continue
            mx = RC[0] + (R + 10 * math.sin(i * 2.1 + t * 3)) * math.sin(2 * math.pi * back)
            my = RC[1] - (R + 10 * math.cos(i * 1.7 + t * 3)) * math.cos(2 * math.pi * back)
            sparkle(im, mx, my, 6 - i * .7, mix(ACCENT2, (255, 255, 255), .5), .75 - i * .12)
    return im


def interlace(frames):
    """minui's multi-frame PNG: row y of frame k at row y*N + k; tEXt Frames=N."""
    n = len(frames)
    arr = np.stack([np.asarray(f) for f in frames], 1)          # (FH, n, FW, 3)
    return Image.fromarray(arr.reshape(FH * n, FW, 3)), n


def font_atlas():
    """minui font: 96 ASCII cells (0x20..0x7f) in a row, regular row on top, bold row below; 8-bit grey."""
    f = inter("ExtraBold", 70)
    cw = int(max(f.getlength(c) for c in "0123456789")) + 2   # monospace cells: as wide as a digit ("%" drawn to fit)
    atlas = Image.new("L", (96 * cw, 2 * FONT_H), 0)
    d = ImageDraw.Draw(atlas)
    asc = f.getbbox("0")
    for row in range(2):
        for i in range(96):
            ch = chr(0x20 + i)
            if ch not in "0123456789%":
                continue
            w = f.getlength(ch)
            # digits centred in the cell; "%" smaller look by drawing it at a smaller size
            if ch == "%":
                fp = inter("Bold", 40)
                bb = fp.getbbox("%")
                d.text((i * cw + (cw - fp.getlength("%")) / 2, row * FONT_H + (FONT_H - (bb[3] - bb[1])) / 2 - bb[1] - 14), "%", font=fp, fill=255)
            else:
                d.text((i * cw + (cw - w) / 2, row * FONT_H + (FONT_H - (asc[3] - asc[1])) / 2 - asc[1]), ch, font=f, fill=255)
    return atlas, cw


def fail_frame():
    a = BASE["CHARGING"].copy()
    track, _ = ring_masks(0)
    a += (np.array(mix(BLACK, ACCENT, .35), np.float32) - a) * track[..., None]
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    text(im, "!", inter("ExtraBold", 96), RC[0], RC[1] - 10, ACCENT)
    return im


def main(out):
    img_dir = os.path.join(out, "product", "etc", "res", "images", "charger")
    val_dir = os.path.join(out, "product", "etc", "res", "values", "charger")
    os.makedirs(img_dir, exist_ok=True)
    os.makedirs(val_dir, exist_ok=True)

    # (image, ms, min_level, max_level): the charger plays, from the first frame that holds the level, every
    # following frame whose range holds it. Below 100 %: sweep from the level to full + a twinkle ("CHARGING");
    # at 100 %: only the "CHARGED" frames, the full ring with a slow twinkle.
    spec = []
    levels = list(range(0, 101, STEP))
    for i, lv in enumerate(levels):
        spec.append((frame(lv / 100, t=i * .09), 70, 0, min(lv, 99)))
    for j in range(HOLD):
        tw = math.sin(j / (HOLD - 1) * math.pi)
        spec.append((frame(1.0, twinkle=tw, t=(len(levels) + j) * .09), 90, 0, 99))
    CHARGED = 12
    for j in range(CHARGED):
        tw = math.sin(j / (CHARGED - 1) * math.pi)
        spec.append((frame(1.0, twinkle=tw, t=j * .05, label="CHARGED"), 110, 100, 100))
    frames = [s[0] for s in spec]
    lines = ["frame: %d %d %d" % (ms, lo, hi) for _, ms, lo, hi in spec]
    sheet, n = interlace(frames)
    from PIL import PngImagePlugin
    meta = PngImagePlugin.PngInfo()
    meta.add_text("Frames", str(n))
    sheet.save(os.path.join(img_dir, "songate_scale.png"), pnginfo=meta, optimize=True)
    fail_frame().save(os.path.join(img_dir, "songate_fail.png"), optimize=True)
    atlas, cw = font_atlas()
    atlas.save(os.path.join(img_dir, "songate_font.png"), optimize=True)

    # percent position: the ring's centre on the screen (frame centred by the charger), digits a bit above middle
    ring_screen_y = (H - FH) // 2 + RC[1]
    py = int(ring_screen_y - FONT_H / 2 - 14)
    r, g, b = TXT
    desc = ["# SongateOS charging animation (generated by work/bootlogo/make_charger.py)",
            "animation: 3 2 charger/songate_scale",
            "fail: charger/songate_fail",
            "percent_display: c %d %d %d %d 255 charger/songate_font" % (py, r, g, b)] + lines
    open(os.path.join(val_dir, "animation.txt"), "w", newline="\n").write("\n".join(desc) + "\n")

    # previews: the whole screen as the charger would draw it, at 35 % and at 100 %
    atlas_l = atlas

    def screen(fr, level):
        s = Image.new("RGB", (W, H), BLACK)
        s.paste(fr, ((W - FW) // 2, (H - FH) // 2))
        txt = "%d%%" % level
        x = (W - len(txt) * cw) // 2
        for k, ch in enumerate(txt):
            i = ord(ch) - 0x20
            glyph = atlas_l.crop((i * cw, 0, (i + 1) * cw, FONT_H))
            s.paste(Image.new("RGB", glyph.size, TXT), (x + k * cw, py), glyph)
        return s

    for level in (35, 100):
        # as the charger does it: start at the first frame holding the level, then every later frame holding it
        play = [s for s in spec if s[2] <= level <= s[3]]
        seq = [screen(s[0], level) for s in play]
        durs = [s[1] for s in play]
        durs[0] *= 2                                           # first_frame_repeats = 2
        small = [s.resize((W // 2, H // 2), Image.LANCZOS) for s in seq]
        small[0].save(os.path.join(out, "preview_charging_%d.gif" % level), save_all=True, append_images=small[1:],
                      duration=durs, loop=0)
        seq[len(seq) // 2].save(os.path.join(out, "preview_charging_%d.png" % level))
    print("frames", n, "sheet", sheet.size, "font cell", cw, "x", FONT_H, "percent y", py)


if __name__ == "__main__":
    main(sys.argv[1])
