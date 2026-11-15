"""SongateOS boot animation (bootanimation.zip, 750x1334 @ 30 fps): Songate's own opening (source/view_opening.cpp),
ported frame by frame - the star crossing, the name unfurling behind it in a trail of dust, the shimmer and the last
twinkles - then a loop while Android boots (the glow breathing, the shimmer passing, stardust rising, a slim progress
pill like the app's seek bar with a light running along it), and a fade out when boot completes.

desc.txt:  750 1334 30
           c 1 0 part0      intro, always played to the end
           p 0 0 part1      loop until boot completes
           c 1 0 part2      outro (played after the loop when boot completes)

    python make_bootanim.py OUTDIR       -> OUTDIR/bootanimation.zip + OUTDIR/preview_bootanim.gif
"""
import math
import os
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw

from brand import (ACCENT, ACCENT2, BG, GLOW, TXT, W, H, Word, clamp, eased, glow, mix, noise, sparkle)

FPS = 30
K_CROSS, K_FADE_AT = 0.8, 1.6          # view_opening.cpp: the star's crossing; the intro lasts kFadeAt
LOOP_S, OUT_S = 3.0, 0.6
DUST = 56
WHITE = (255, 255, 255)
LIGHT, WARM = mix(ACCENT, WHITE, .55), mix(ACCENT2, WHITE, .5)

word = Word(140, pad=150)
CX, CY = W / 2, H * 0.47
WX = CX - word.w / 2                    # the letters' left edge
PILL_Y, PILL_W, PILL_H = CY + word.h * 0.5 + 92, 150, 4


def backdrop():
    im = Image.new("RGB", (W, H), BG)
    glow(im, W / 2, H * 0.47, W * 0.9, mix(BG, GLOW, 0.11), 0.9)   # same light as the boot logo: no jump between them
    return im


BACK = backdrop()


def dust(im, t, alpha, x, w, h):
    """view_opening.cpp's dust: each mote lights as the star passes its place, drifts up and twinkles out."""
    for i in range(DUST):
        u = noise(i)
        born = K_CROSS * math.acos(1 - 2 * clamp(u)) / math.pi
        life = 0.8 + 0.7 * noise(i + 100)
        k = (t - born) / life
        if k <= 0 or k >= 1:
            continue
        px = x + w * (u * 1.06 - 0.03) + (noise(i + 200) - .5) * 20 * k * 1.6
        py = CY + (noise(i + 300) - .5) * h * 1.5 - (22 + 46 * noise(i + 400)) * k * 1.6
        tw = .55 + .45 * math.sin(t * (9 + 7 * noise(i + 500)) + 6 * u)
        a = math.sin(math.sqrt(k) * math.pi) * tw
        sparkle(im, px, py, (6 + 11 * noise(i + 600)) * (1 - .4 * k) * .8, LIGHT if noise(i + 700) < .5 else WARM, a * alpha)


def intro_frame(t):
    """openingScene(t, 1) for 0 <= t < kFadeAt."""
    im = BACK.copy()
    w, h = word.w, word.h
    run = eased(t / K_CROSS)
    swell = math.sin(clamp(t / (K_CROSS * 1.5)) * math.pi)
    shine = -0.25 + 1.5 * (t - K_CROSS * .75) / (.75 + K_CROSS * .25) if K_CROSS * .75 <= t < K_CROSS + .75 else -1
    glow_a = (0.3 + 0.35 * swell) * eased((run - .75) / .25)
    word.draw(im, CX, CY, unfurl=run, shine=shine, glow_a=glow_a * 1.4)
    dust(im, t, 1.0, WX, w, h)
    # the star: waits at the start, crosses on an arc, goes out at the end of the name
    sx = WX + w * run
    sy = CY - h * .18 * math.sin(run * math.pi)
    left = 1 if t < K_CROSS else 1 - eased((t - K_CROSS) / .35)
    if left > 0:
        size = ((46 + 26 * math.sin(t * 13)) * (.6 + .4 * left) + 16 * swell) * .55
        glow(im, sx, sy, 60, LIGHT, .3 * left)
        sparkle(im, sx, sy, size, WHITE, left)
    for at, x, y, size in ((0.92, WX + w - 8, CY - h * .42, 36), (1.1, WX + w * .64, CY - h * .6, 24)):
        k = (t - at) / .6
        if 0 < k < 1:
            a = math.sin(k * math.pi)
            sparkle(im, x, y, size * a * .5, WHITE, a)
    # the progress pill fades in during the last part of the intro
    pill(im, t, eased((t - 1.0) / .6))
    return im


def pill(im, t, alpha):
    """A slim track like the app's seek bar, a coral light travelling along it (loops every 1.5 s)."""
    if alpha <= 0:
        return
    x0, y0 = CX - PILL_W / 2, PILL_Y - PILL_H / 2
    layer = np.asarray(im, np.float32)
    xs = np.arange(int(x0), int(x0 + PILL_W) + 1)
    ys = np.arange(int(y0) - 1, int(y0 + PILL_H) + 2)
    # antialiased capsule mask
    gx, gy = np.meshgrid(xs + .5, ys + .5)
    r = PILL_H / 2
    cx = np.clip(gx, x0 + r, x0 + PILL_W - r)
    m = np.clip(r + .5 - np.hypot(gx - cx, gy - PILL_Y), 0, 1)
    track = np.array(mix(BG, TXT, .14), np.float32)
    # the light: a soft band, coral to cream, moving left to right
    ph = (t % 1.5) / 1.5
    c = -0.25 + 1.5 * ph
    u = (gx - x0) / PILL_W
    d = (u - c) / .22
    band = np.exp(-4.5 * d * d) * (1 - d * d) * (np.abs(d) < 1)
    col = track + (np.array(mix(ACCENT, ACCENT2, clamp(c)), np.float32) - track) * band[..., None]
    sl = layer[ys[0]:ys[-1] + 1, xs[0]:xs[-1] + 1]
    sl += (col - sl) * (m * alpha)[..., None]
    out = Image.fromarray(np.clip(layer, 0, 255).astype(np.uint8))
    im.paste(out)


def loop_frame(t):
    """Seamless loop, LOOP_S long: glow breathing, shimmer once per loop, rising stardust, two twinkles."""
    im = BACK.copy()
    tau = t / LOOP_S
    breathe = .5 - .5 * math.cos(2 * math.pi * tau)
    shine = -0.25 + 1.5 * clamp((t - 0.4) / 1.3) if 0.4 <= t < 1.7 else -1
    word.draw(im, CX, CY, unfurl=1.0, shine=shine, glow_a=(0.3 + 0.12 * breathe) * 1.4)
    w, h = word.w, word.h
    for i in range(22):                                       # stardust rising slowly around the name, looping
        k = (tau * (1 + int(noise(i + 900) * 2)) + noise(i + 800)) % 1.0
        u = noise(i + 1000)
        px = WX + w * (u * 1.2 - .1) + 8 * math.sin(2 * math.pi * (k + u))
        py = CY + h * (.7 - 1.6 * k)
        a = math.sin(k * math.pi) * (.55 + .45 * math.sin(2 * math.pi * (3 * tau + u)))
        sparkle(im, px, py, 3 + 5 * noise(i + 1100), LIGHT if noise(i + 1200) < .5 else WARM, a * .8)
    for at, x, y, size in ((1.35, WX + w - 8, CY - h * .42, 30), (2.2, WX + w * .64, CY - h * .6, 20)):
        k = (t - at) / .6
        if 0 < k < 1:
            a = math.sin(k * math.pi)
            sparkle(im, x, y, size * a * .5, WHITE, a)
    pill(im, t + K_FADE_AT, 1.0)                              # continues the intro's pill phase
    return im


def outro_frame(t):
    a = 1 - eased(t / OUT_S)
    im = loop_frame(0.0)
    return Image.blend(Image.new("RGB", (W, H), (0, 0, 0)), im, a)


def main(out):
    os.makedirs(out, exist_ok=True)
    parts = {
        "part0": [intro_frame(i / FPS) for i in range(int(K_FADE_AT * FPS))],
        "part1": [loop_frame(i / FPS) for i in range(int(LOOP_S * FPS))],
        "part2": [outro_frame(i / FPS) for i in range(int(OUT_S * FPS) + 1)],
    }
    desc = "%d %d %d\nc 1 0 part0\np 0 0 part1\nc 1 0 part2\n" % (W, H, FPS)
    zpath = os.path.join(out, "bootanimation.zip")
    with zipfile.ZipFile(zpath, "w", compression=zipfile.ZIP_STORED) as z:   # bootanimation needs STORED entries
        z.writestr("desc.txt", desc)
        for name, frames in parts.items():
            for i, f in enumerate(frames):
                p = os.path.join(out, "_tmp.png")
                f.save(p, optimize=True)
                z.write(p, "%s/%05d.png" % (name, i))
        os.remove(os.path.join(out, "_tmp.png"))
    # preview: intro + 2 loops + outro, half size
    seq = parts["part0"] + parts["part1"] * 2 + parts["part2"]
    small = [f.resize((W // 2, H // 2), Image.LANCZOS) for f in seq]
    small[0].save(os.path.join(out, "preview_bootanim.gif"), save_all=True, append_images=small[1:],
                  duration=int(1000 / FPS), loop=0)
    for name, idx in (("intro_mid", 14), ("intro_end", 47)):
        parts["part0"][idx].save(os.path.join(out, "still_%s.png" % name))
    parts["part1"][30].save(os.path.join(out, "still_loop.png"))
    print("frames", {k: len(v) for k, v in parts.items()}, "zip", os.path.getsize(zpath) // 1024, "KB")


if __name__ == "__main__":
    main(sys.argv[1])
