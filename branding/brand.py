"""Songate's look for SongateOS screens (boot logo, boot animation, charging): the wordmark, sparkles and glow drawn
the way the app does it (tools/preview/make_film.py Word/sparkle, source/gfx.cpp, source/view_opening.cpp), at any size.
Fonts (SIL OFL, in fonts/): Fraunces for the name, Inter for text - the same files the Songate app ships."""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONTS = Path(__file__).resolve().parent / "fonts"
FRAUNCES = FONTS / "Fraunces" / "Fraunces.ttf"
INTER = FONTS / "Inter"

# the app's opening colours (make_film.py; shell.cpp's defaults)
ACCENT, ACCENT2, TXT, GLOW = (251, 105, 90), (233, 181, 137), (249, 242, 236), (197, 55, 45)
BG = (20, 8, 9)                      # the opening's warm near-black (screenshot docs/screenshots/opening.webp)
W, H = 750, 1334                     # the R4's screen, portrait


def mix(p, q, t):
    return tuple(p[i] + (q[i] - p[i]) * t for i in range(3))


def clamp(v, a=0.0, b=1.0):
    return max(a, min(b, v))


def eased(t):
    t = clamp(t)
    return t * t * (3 - 2 * t)


def noise(i):
    """view_opening.cpp's noise(): the same every time."""
    x = (i * 2654435761 + 0x9E3779B9) & 0xffffffff
    x ^= x >> 15
    x = (x * 2246822519) & 0xffffffff
    x ^= x >> 13
    return (x % 10000) / 10000.0


def inter(weight, px):
    return ImageFont.truetype(str(INTER / ("Inter-%s.ttf" % weight)), px)


class Word:
    """The name, as Gfx::wordmark draws it: gradient a→b→c (middle stop 0.45), glow, shimmer band, unfurl."""

    def __init__(self, px, pad=None, word="Songate", wght=900, soft=100, wonk=1, stops=None, opsz=36):
        pad = int(px * 0.5) if pad is None else pad
        f = ImageFont.truetype(str(FRAUNCES), px * 3)
        f.set_variation_by_axes([opsz, wght, soft, wonk])   # opsz, wght, SOFT, WONK (tools/brand/wordmark.py: 36,900,100,1)
        l, t, r, b = f.getbbox(word)
        big = Image.new("L", (r - l + 60, b - t + 60), 0)
        ImageDraw.Draw(big).text((30 - l, 30 - t), word, font=f, fill=255)
        big = big.crop(big.getbbox())
        word = big.resize((big.width // 3, big.height // 3), Image.LANCZOS)
        self.w, self.h, self.pad = word.width, word.height, pad
        canvas = Image.new("L", (self.w + 2 * pad, self.h + 2 * pad), 0)
        canvas.paste(word, (pad, pad))
        self.a = np.asarray(canvas, dtype=np.float32) / 255
        glow = np.asarray(canvas.filter(ImageFilter.GaussianBlur(px * .2)), dtype=np.float32) / 255
        self.glow = glow / glow.max()
        tight = np.asarray(canvas.filter(ImageFilter.GaussianBlur(px * .05)), dtype=np.float32) / 255
        self.tight = tight / tight.max()
        a, b, c = stops or (mix(ACCENT, TXT, .12), mix(ACCENT2, TXT, .2), mix(ACCENT2, TXT, .78))
        ts = (np.arange(self.a.shape[1]) - pad) / self.w
        self.u = ts
        col = [np.where(ts < .45, a[i] + (b[i] - a[i]) * (ts / .45), b[i] + (c[i] - b[i]) * ((ts - .45) / .55)) for i in range(3)]
        self.col = np.stack(col, 1)[None, :, :].astype(np.float32)

    def draw(self, frame, cx, cy, unfurl=1.0, shine=-1.0, glow_a=0.5, alpha=1.0):
        """On an RGB frame (PIL): letters unfurled from the left (0..1), shimmer at shine (-.25..1.25), glow."""
        x, y = int(round(cx - self.a.shape[1] / 2)), int(round(cy - self.a.shape[0] / 2))
        box = (x, y, x + self.a.shape[1], y + self.a.shape[0])
        px = np.asarray(frame.crop(box), dtype=np.float32)
        edge = np.clip((unfurl * 1.3 - .15 - self.u) / .15, 0, 1)[None, :]
        edge = edge * edge * (3 - 2 * edge)
        band = None
        if -.5 < shine < 1.5:
            d = (self.u - shine) / .16
            band = (np.exp(-4.5 * d * d) * (1 - d * d) * (np.abs(d) < 1))[None, :].astype(np.float32)
        ga = glow_a * alpha
        g = self.glow * edge * ga * 1.15
        px += (np.array(GLOW, dtype=np.float32) - px) * g[:, :, None]
        t = self.tight * edge * ga * .28
        px += (np.array(ACCENT, dtype=np.float32) - px) * t[:, :, None]
        col = self.col
        if band is not None:
            col = col + (255 - col) * (band * .85)[:, :, None]
        a = (self.a * edge * alpha)[:, :, None]
        px += (col - px) * a
        frame.paste(Image.fromarray(np.clip(px, 0, 255).astype(np.uint8)), (x, y))
        return x + self.pad, y + self.pad   # the letters' top-left


_star_cache = {}


def sparkle(frame, x, y, size, col=(255, 255, 255), a=1.0):
    """The app's four-pointed star (wordmark-png.py's shape), additive-ish over the frame."""
    if a <= 0 or size < 1:
        return
    n = int(size * 2) | 1          # as wordmark-png.py: the star's arms reach the full square
    if n not in _star_cache:
        c1 = np.linspace(-1, 1, n, dtype=np.float32)
        xx, yy = np.meshgrid(c1, c1)
        _star_cache[n] = np.clip(np.clip(1 - (np.abs(xx) ** .55 + np.abs(yy) ** .55), 0, 1) ** 1.2 * 2.2, 0, 1)
    m = _star_cache[n] * a
    x0, y0 = int(round(x - n / 2)), int(round(y - n / 2))
    if x0 < 0 or y0 < 0 or x0 + n > frame.width or y0 + n > frame.height:
        return
    px = np.asarray(frame.crop((x0, y0, x0 + n, y0 + n)), dtype=np.float32)
    px += (np.array(col, dtype=np.float32) - px) * m[:, :, None]
    frame.paste(Image.fromarray(np.clip(px, 0, 255).astype(np.uint8)), (x0, y0))


def glow(frame, cx, cy, rad, col, a):
    """Gfx::glow: radial gradient, colour to transparent at the radius."""
    if a <= 0 or rad < 1:
        return
    r = int(rad)
    x0, y0 = int(cx) - r, int(cy) - r
    c1 = np.arange(2 * r, dtype=np.float32) + .5 - r
    xx, yy = np.meshgrid(c1, c1)
    m = np.clip(1 - np.hypot(xx, yy) / r, 0, 1) * a
    bx0, by0 = max(0, x0), max(0, y0)
    bx1, by1 = min(frame.width, x0 + 2 * r), min(frame.height, y0 + 2 * r)
    if bx1 <= bx0 or by1 <= by0:
        return
    m = m[by0 - y0:by1 - y0, bx0 - x0:bx1 - x0]
    px = np.asarray(frame.crop((bx0, by0, bx1, by1)), dtype=np.float32)
    px += (np.array(col, dtype=np.float32) - px) * m[:, :, None]
    frame.paste(Image.fromarray(np.clip(px, 0, 255).astype(np.uint8)), (bx0, by0))


def background():
    """The opening's warm near-black with a faint warm light behind the middle (the app's veil)."""
    im = Image.new("RGB", (W, H), BG)
    glow(im, W / 2, H * 0.47, W * 0.95, mix(BG, GLOW, 0.16), 0.9)
    return im


def text(frame, s, font, cx, cy, col, alpha=1.0, spacing=0.0):
    """Centred text (optionally letter-spaced, em fraction), blended at alpha."""
    widths = [font.getlength(ch) for ch in s]
    sp = font.size * spacing
    total = sum(widths) + sp * (len(s) - 1)
    asc, desc = font.getmetrics()
    layer = Image.new("L", (int(total) + 4, asc + desc + 4), 0)
    d = ImageDraw.Draw(layer)
    x = 2
    for ch, w in zip(s, widths):
        d.text((x, 2), ch, font=font, fill=255)
        x += w + sp
    bb = font.getbbox("H")
    top = cy - (bb[1] + bb[3]) / 2 - 2
    mask = layer.point(lambda v: int(v * alpha))
    frame.paste(Image.new("RGB", layer.size, tuple(int(c) for c in col)), (int(round(cx - total / 2 - 2)), int(round(top))), mask)
    return total
