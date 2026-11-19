"""The name for the README, in Songate's own magical letters (as Songate's tools/brand/wordmark-png.py draws "Songate":
Fraunces Black, the gradient, the glow, a shimmer and two sparkles), here as "SongateOS". Two PNGs with a transparent
background, one for each of GitHub's themes.

    python branding/make_readme_wordmark.py      (needs Pillow and numpy; writes docs/wordmark-dark.png, wordmark-light.png)
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / 'branding' / 'fonts' / 'Fraunces' / 'Fraunces.ttf'
TEXT = 'SongateOS'
PX = 300                                                     # sharp at the ~620 px they're shown
ACCENT, ACCENT2, TXT, GLOW = (251, 105, 90), (233, 181, 137), (249, 242, 236), (197, 55, 45)   # Songate's colours


def mix(p, q, t):
    return tuple(p[i] + (q[i] - p[i]) * t for i in range(3))


class Word:
    def __init__(self, px, pad):
        f = ImageFont.truetype(str(FONT), px * 3)
        f.set_variation_by_axes([36, 900, 100, 1])           # opsz, wght, SOFT, WONK: as Songate's wordmark
        l, t, r, b = f.getbbox(TEXT)
        big = Image.new('L', (r - l + 60, b - t + 60), 0)
        ImageDraw.Draw(big).text((30 - l, 30 - t), TEXT, font=f, fill=255)
        big = big.crop(big.getbbox())
        word = big.resize((big.width // 3, big.height // 3), Image.LANCZOS)
        self.w, self.h, self.pad = word.width, word.height, pad
        canvas = Image.new('L', (self.w + 2 * pad, self.h + 2 * pad), 0)
        canvas.paste(word, (pad, pad))
        self.a = np.asarray(canvas, dtype=np.float32) / 255
        glow = np.asarray(canvas.filter(ImageFilter.GaussianBlur(px * .2)), dtype=np.float32) / 255
        self.glow = glow / glow.max()
        self.u = (np.arange(self.a.shape[1]) - pad) / self.w


def wordmark(word, stops, glow_col, glow_a, shine, sparkle_col):
    """Letters over their glow, as straight-alpha RGBA (Songate's wordmark(), unchanged)."""
    a, b, c = (np.array(s, dtype=np.float32) for s in stops)
    u = word.u
    col = np.where((u < .45)[:, None], a + (b - a) * (u / .45)[:, None], b + (c - b) * ((u - .45) / .55)[:, None])[None, :, :]
    d = (u - shine) / .16
    band = (np.exp(-4.5 * d * d) * (1 - d * d) * (np.abs(d) < 1))[None, :, None].astype(np.float32)
    col = col + (255 - col) * band * .6
    la = word.a[:, :, None]
    ga = np.clip(word.glow * glow_a, 0, 1)[:, :, None]
    out_a = la + ga * (1 - la)
    rgb = (col * la + np.array(glow_col, dtype=np.float32) * ga * (1 - la)) / np.maximum(out_a, 1e-4)
    im = np.concatenate([rgb, out_a * 255], 2)
    for sx, sy, size in ((word.pad + word.w - PX * .03, word.pad + word.h * .06, PX * .17),
                         (word.pad + word.w * .64, word.pad - PX * .03, PX * .09)):
        n = int(size * 2) | 1
        c1 = np.linspace(-1, 1, n, dtype=np.float32)
        xx, yy = np.meshgrid(c1, c1)
        m = np.clip(np.clip(1 - (np.abs(xx) ** .55 + np.abs(yy) ** .55), 0, 1) ** 1.2 * 2.2, 0, 1)[:, :, None]
        x0, y0 = int(sx - n / 2), int(sy - n / 2)
        r = im[y0:y0 + n, x0:x0 + n]
        na = m + r[:, :, 3:] / 255 * (1 - m)
        r[:, :, :3] = (np.array(sparkle_col, dtype=np.float32) * m + r[:, :, :3] * (r[:, :, 3:] / 255) * (1 - m)) / np.maximum(na, 1e-4)
        r[:, :, 3:] = na * 255
    pic = Image.fromarray(np.clip(im, 0, 255).astype(np.uint8), 'RGBA')
    return pic.crop(pic.getchannel('A').point(lambda v: 255 if v > 6 else 0).getbbox())


word = Word(PX, pad=int(PX * .34))
dark = wordmark(word, (mix(ACCENT, TXT, .12), mix(ACCENT2, TXT, .2), mix(ACCENT2, TXT, .78)), GLOW, .5, .72, (255, 244, 236))
light = wordmark(word, ((226, 62, 47), (208, 96, 44), (150, 74, 40)), ACCENT, .34, -1, (240, 150, 96))
(ROOT / 'docs').mkdir(exist_ok=True)
dark.save(ROOT / 'docs' / 'wordmark-dark.png', optimize=True)
light.save(ROOT / 'docs' / 'wordmark-light.png', optimize=True)
print(dark.size, light.size)
