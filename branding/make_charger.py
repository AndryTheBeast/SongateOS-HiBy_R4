"""SongateOS off-mode charging animation for Android's charger (healthd charger mode, /system/bin/charger).

Android's charger reads, when present (Android 12+):
    /product/etc/res/values/charger/animation.txt
    /product/etc/res/images/charger/<files>.png
It clears the screen to black, centres the current frame, and draws the battery % with a bitmap font.
Frames are ONE interlaced PNG (tEXt "Frames"); each frame has a battery-level range and the charger only plays the
frames whose range holds the current level. v4 (user, 2026-11-19): each level 0..99 % has its own few frames, its
range just that level: the ring stays at the level (it moves on one step with each percent), and only the sparkles
loop. 100 % has the "CHARGED" loop. All frames are loaded at start, so the frame is kept small (488 px, ~390 MB).

Look: Songate's opening - the wordmark's gradient (coral -> amber -> cream), its glow, its four-pointed star at the
head of the arc, Inter for text, "CHARGING" as the app's coral small caps (the "PLAYING" kicker); the ring centred on the screen.

    python make_charger.py OUTDIR      -> OUTDIR/product/etc/res/... + OUTDIR/preview_*.gif
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from brand import ACCENT, ACCENT2, FRAUNCES, GLOW, TXT, W, H, Word, glow, inter, mix, sparkle, text

FW, FH = 488, 488            # square frame, centred on the 750x1334 screen by the charger (v4: smaller, 400 frames)
RC = (FW / 2, FH / 2)        # ring centre = frame centre = screen centre (the user, 2026-11-14: centred, no name)
R, THICK = 196, 16           # ring radius and stroke
STEP = 2                     # % per fill frame: 0,2,...,100 -> 51 frames (v2: smoother, user 2026-11-15)
HOLD = 16                    # frames of the full ring twinkling at the end (v3 sweep only)
PHASES = 4                   # v4: sparkle frames per level, one seamless loop
PHASE_MS = 160               # v4: per sparkle frame (a loop of 0.64 s)
FONT_H = 84                  # percent digits cell height
SUFFIX = os.environ.get("CHG_SUFFIX", "bolt")   # what the charger shows after the number: bolt / none / pct
FONT = os.environ.get("CHG_FONT", "fraunces")   # number font: fraunces (v3) / inter (v2)
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


LOOP = None   # v4: the phase 0..1 of a seamless loop, or None (v3's free-running t)


def S(t, freq, ph=0.0):
    """sin(t*freq + ph); in a v4 loop, a whole number of cycles per loop so the last frame meets the first."""
    if LOOP is not None:
        return math.sin(2 * math.pi * LOOP * max(1, round(freq / 4)) + ph)
    return math.sin(t * freq + ph)


def frame(fill, twinkle=0.0, t=0.0, label="CHARGING", digits=2):
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
        sh = ((ang / max(fill, 1e-3)) - ((LOOP * 1.4 if LOOP is not None else t % 1.4) - .2)) / .16
        band = np.exp(-4.5 * sh * sh) * (1 - sh * sh) * (np.abs(sh) < 1)
        col = col + (255 - col) * (band * .55)[..., None]
        a += (col - a) * arc[..., None]
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    # the star at the arc's head (the opening's star), and a few motes of dust behind it
    if fill > 0:
        hx = RC[0] + R * math.sin(2 * math.pi * fill)
        hy = RC[1] - R * math.cos(2 * math.pi * fill)
        size = 34 + 8 * S(t, 9) + 14 * twinkle
        glow(im, hx, hy, 46, mix(ACCENT, (255, 255, 255), .55), .45)     # the opening star's light
        sparkle(im, hx, hy, size, (255, 255, 255), 1.0)
        for i in range(5):
            back = fill - .015 * (i + 1) - .01 * S(t, 5, i)
            if back <= 0:
                continue
            mx = RC[0] + (R + 10 * S(t, 3, i * 2.1)) * math.sin(2 * math.pi * back)
            my = RC[1] - (R + 10 * S(t, 3, i * 1.7 + math.pi / 2)) * math.cos(2 * math.pi * back)
            sparkle(im, mx, my, 6 - i * .7, mix(ACCENT2, (255, 255, 255), .5), .75 - i * .12)
    if FONT == "fraunces":
        number_sparkle(im, t, digits)
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
            # digits centred in the cell. The charger always prints "<level>%", so the "%" cell decides the suffix:
            # "bolt" = a lightning bolt drawn in its place, "none" = blank (the number then sits half a cell left),
            # "pct" = a smaller "%"
            if ch == "%" and SUFFIX == "none":
                continue
            if ch == "%" and SUFFIX == "bolt":
                top = row * FONT_H + (FONT_H - (asc[3] - asc[1])) / 2      # digit top
                hgt = asc[3] - asc[1]                                      # digit height
                bw, bh = cw * .66, hgt * .92
                # +2 px: its right side space then matches the digits' left side, so "<level><bolt>" is centred on
                # the screen (measured over every level 1-100: within 1.5 px; the charger centres whole cells)
                x0, y0 = i * cw + (cw - bw) / 2 + 2, top + (hgt - bh) / 2
                pts = [(.58, 0), (0, .58), (.40, .58), (.26, 1), (1, .38), (.58, .38), (.86, 0)]
                d.polygon([(x0 + px * bw, y0 + py * bh) for px, py in pts], fill=255)
                continue
            if ch == "%":
                fp = inter("Bold", 40)
                bb = fp.getbbox("%")
                d.text((i * cw + (cw - fp.getlength("%")) / 2, row * FONT_H + (FONT_H - (bb[3] - bb[1])) / 2 - bb[1] - 14), "%", font=fp, fill=255)
            else:
                dx = -3 if ch == "1" else 0          # a "1" is narrow: nudged left so a leading 1 (1x, 100) stays centred
                d.text((i * cw + (cw - w) / 2 + dx, row * FONT_H + (FONT_H - (asc[3] - asc[1])) / 2 - asc[1]), ch, font=f, fill=255)
    return atlas, cw


def font_atlas_fraunces():
    """v3 (user 2026-11-15: "a better font, sparkly"): digits in Fraunces Black (Songate's wordmark face, the boot
    logo's "R4"), the "%" cell a lightning bolt in the same weight. Drawn at 4x and scaled down (smooth edges).
    The charger centres whole cells, so the visible ink is balanced automatically: the bolt's right space = the
    digits' typical left space, and a narrow "1" is moved half-way towards it."""
    S = 4
    f = ImageFont.truetype(str(FRAUNCES), 96 * S)
    f.set_variation_by_axes([72, 900, 100, 1])                  # opsz, wght, SOFT, WONK (display size)
    bb0 = f.getbbox("0")
    dig_h = bb0[3] - bb0[1]
    cw = int(max(f.getlength(c) for c in "0123456789") / S) + 2
    cell = lambda: Image.new("L", (cw * S, FONT_H * S), 0)
    top = (FONT_H * S - dig_h) / 2 - bb0[1]

    def draw_digit(ch, dx=0):
        im = cell()
        ImageDraw.Draw(im).text(((cw * S - f.getlength(ch)) / 2 + dx * S, top), ch, font=f, fill=255)
        return im

    def left_pad(im):
        cols = np.where(np.asarray(im).max(0) > 60)[0]
        return cols.min() / S

    digits = {c: draw_digit(c) for c in "0123456789"}
    pads = sorted(left_pad(digits[c]) for c in "0234567890")
    lmed = pads[len(pads) // 2]
    # A wide "1" (user 2026-11-15: big gap in "100"). The charger draws every character in an equal-width cell, and
    # Fraunces' "1" is much narrower than its "0", so it sat alone with gaps around it. Like a tabular figure, it's
    # drawn wider: the "1" at weight 620, stretched to 86 % of the "0" - its stroke then matches the Black digits.
    f1 = ImageFont.truetype(str(FRAUNCES), 96 * S)
    f1.set_variation_by_axes([72, 620, 100, 1])
    one = Image.new("L", (int(f1.getlength("1")) + 8 * S, FONT_H * S), 0)
    ImageDraw.Draw(one).text((4 * S, top), "1", font=f1, fill=255)
    cols = np.where(np.asarray(one).max(0) > 60)[0]
    one = one.crop((cols.min(), 0, cols.max() + 1, FONT_H * S))
    zero_w = np.ptp(np.where(np.asarray(digits["0"]).max(0) > 60)[0]) + 1
    one = one.resize((int(zero_w * .86), FONT_H * S), Image.LANCZOS)
    digits["1"] = cell()
    digits["1"].paste(one, ((cw * S - one.size[0]) // 2, 0))

    # bolt: same height as the digits, chunky like Fraunces Black, corners softened (SOFT axis)
    bolt = cell()
    bh, bw = dig_h * 1.0, cw * S * .74
    y0 = (FONT_H * S - bh) / 2
    x0 = cw * S - lmed * S - bw                                  # right space = the digits' left space
    pts = [(.60, 0), (0, .58), (.42, .58), (.22, 1), (1, .36), (.58, .36), (.88, 0)]
    ImageDraw.Draw(bolt).polygon([(x0 + px * bw, y0 + py * bh) for px, py in pts], fill=255)
    # (no blur: drawn at 4x and scaled down is smooth enough, and a blur thinned it next to the heavy digits)

    atlas = Image.new("L", (96 * cw, 2 * FONT_H), 0)
    for row in range(2):
        for ch, im in list(digits.items()) + [("%", bolt)]:
            i = ord(ch) - 0x20
            atlas.paste(im.resize((cw, FONT_H), Image.LANCZOS), (i * cw, row * FONT_H))
    return atlas, cw


ATLAS, CW = font_atlas_fraunces()
NUM_Y = RC[1] - FONT_H / 2 - 14               # top of the number's cells, in frame coordinates (as main's py)


def number_sparkle(im, t, digits):
    """Sparkle around the number (drawn under it: the charger paints the digits on top in one colour):
    a warm glow behind it, a star at its top right (like the boot logo's "R4"), and a few motes twinkling."""
    cx, cy = RC[0], NUM_Y + FONT_H / 2
    glow(im, cx, cy, 120, mix(BLACK, GLOW, .5), .30 + .06 * S(t, 4))
    right = cx + (digits + 1) * CW / 2                          # the bolt's right edge
    tw = .5 + .5 * S(t, 6.0)
    glow(im, right + 6, NUM_Y + 12, 30, mix(ACCENT, (255, 255, 255), .5), .25 + .2 * tw)
    sparkle(im, right + 6, NUM_Y + 12, 16 + 10 * tw, (255, 255, 255), 1.0)
    left = cx - (digits + 1) * CW / 2
    for k, (mx, my, ph, sz) in enumerate(((left - 12, NUM_Y + 62, 1.3, 7), (cx - 8, NUM_Y - 6, 2.6, 6),
                                          (right + 14, NUM_Y + 70, 4.1, 6), (left + 18, NUM_Y - 2, 5.2, 5))):
        a = max(0.0, S(t, 5.0, ph))
        if a > .05:
            sparkle(im, mx, my, sz * (.6 + .6 * a), mix(ACCENT2, (255, 255, 255), .6), .9 * a)


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
    global LOOP
    spec = []
    # v4: each level 0..99 % its own loop of PHASES frames (range = that level only): the ring holds the level, the
    # sparkles keep moving, and the ring moves on one step with each percent. 100 %: the CHARGED loop.
    for lv in range(0, 100):
        for k in range(PHASES):
            LOOP = k / PHASES
            spec.append((frame(lv / 100, twinkle=.5 + .5 * math.sin(2 * math.pi * LOOP), digits=1 if lv < 10 else 2),
                         PHASE_MS, lv, lv))
    for k in range(PHASES * 2):
        LOOP = k / (PHASES * 2)
        spec.append((frame(1.0, twinkle=.5 + .5 * math.sin(2 * math.pi * LOOP), label="CHARGED", digits=3),
                     PHASE_MS, 100, 100))
    LOOP = None
    frames = [s[0] for s in spec]
    lines = ["frame: %d %d %d" % (ms, lo, hi) for _, ms, lo, hi in spec]
    sheet, n = interlace(frames)
    from PIL import PngImagePlugin
    meta = PngImagePlugin.PngInfo()
    meta.add_text("Frames", str(n))
    sheet.save(os.path.join(img_dir, "songate_scale.png"), pnginfo=meta, optimize=True)
    fail_frame().save(os.path.join(img_dir, "songate_fail.png"), optimize=True)
    atlas, cw = (ATLAS, CW) if FONT == "fraunces" else font_atlas()
    atlas.save(os.path.join(img_dir, "songate_font.png"), optimize=True)

    # percent position: the ring's centre on the screen (frame centred by the charger), digits a bit above middle
    ring_screen_y = (H - FH) // 2 + RC[1]
    py = int(ring_screen_y - FONT_H / 2 - 14)
    r, g, b = TXT
    desc = ["# SongateOS charging animation (generated by branding/make_charger.py)",
            "animation: 30 1 charger/songate_scale",            # v4: ~20 s of the level's loop, then sleep
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
        seq, durs = seq * 4, durs * 4                          # a few loops in the preview
        small = [s.resize((W // 2, H // 2), Image.LANCZOS) for s in seq]
        small[0].save(os.path.join(out, "preview_charging_%d.gif" % level), save_all=True, append_images=small[1:],
                      duration=durs, loop=0)
        seq[len(seq) // 2].save(os.path.join(out, "preview_charging_%d.png" % level))
    print("frames", n, "sheet", sheet.size, "font cell", cw, "x", FONT_H, "percent y", py)


if __name__ == "__main__":
    main(sys.argv[1])
