"""SongateOS boot logo (the first screen, drawn by the bootloader from imagefv's logo_custom.bmp): 750x1334.
The user's pick (2026-11-14, option 3 of logo_variants.py): editorial - "HIBY" in spaced caps between two coral
hairlines that fade outwards, a big cream "R4" in the Songate wordmark's face (Fraunces Black, soft, wonky) with a
quiet glow, one star; "powered by Songate" at the bottom, as the app draws its name.

    python make_logo.py OUT.png
"""
import sys

import numpy as np
from PIL import Image

from brand import (ACCENT, ACCENT2, BG, GLOW, TXT, W, H, Word, glow, inter, mix, sparkle, text)

STAR = (255, 244, 236)


def hairline(im, x0, x1, y, col, fade_to_left):
    """A 2 px line from x0 to x1 whose alpha fades to nothing at its outer end."""
    a = np.asarray(im, np.float32)
    xs = np.arange(int(x0), int(x1))
    t = np.clip((xs + .5 - x0) / max(1, x1 - x0), 0, 1)
    alpha = (t if fade_to_left else 1 - t) ** 1.4          # 1 next to the text, 0 at the outer end
    for dy, k in ((0, 1.0), (1, 1.0), (-1, .25), (2, .25)):  # 2 px core + soft antialiased edges
        row = a[int(y) + dy, xs]
        row += (np.array(col, np.float32) - row) * (alpha * k)[:, None]
        a[int(y) + dy, xs] = row
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)))


im = Image.new("RGB", (W, H), BG)
glow(im, W / 2, H * 0.47, W * 0.9, mix(BG, GLOW, 0.09), 0.9)          # the app's soft warm light

cx, cy = W / 2, H * 0.455
r4 = Word(200, pad=120, word="R4", stops=(mix(ACCENT2, TXT, .5), TXT, TXT))
glow(im, cx, cy + 24, 200, mix(BG, GLOW, .45), .22)                     # a tighter warm light right behind R4
lx, ly = r4.draw(im, cx, cy + 24, unfurl=1.0, shine=0.78, glow_a=0.14)

# HIBY: spaced caps, its hairlines measured from the text so both gaps are equal
yl = ly - 52
tw = text(im, "HIBY", inter("SemiBold", 26), cx, yl, mix(TXT, BG, .15), spacing=0.5)
gap, length = 30, 96
line_col = mix(ACCENT, BG, .25)
hairline(im, cx - tw / 2 - gap - length, cx - tw / 2 - gap, yl, line_col, fade_to_left=True)
hairline(im, cx + tw / 2 + gap, cx + tw / 2 + gap + length, yl, line_col, fade_to_left=False)

sparkle(im, lx + r4.w + 6, ly + r4.h * .04, 26, STAR)

# bottom: "powered by" + the wordmark, as the app draws it
by = H - 150
text(im, "POWERED BY", inter("SemiBold", 19), W / 2, by - 62, mix(TXT, BG, 0.45), spacing=0.22)
word = Word(66)
wx, wy = word.draw(im, W / 2, by, unfurl=1.0, shine=0.72, glow_a=0.5)
sparkle(im, wx + word.w - 4, wy + word.h * 0.06, 15, STAR)
sparkle(im, wx + word.w * 0.64, wy - 10, 8, STAR)
im.save(sys.argv[1])
print("saved", sys.argv[1], im.size)
