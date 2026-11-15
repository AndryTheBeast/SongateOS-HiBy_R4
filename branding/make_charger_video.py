"""60 fps preview video of the SongateOS charging animation (design preview: every frame computed fresh, not the
charger's 87 stored frames). Uses make_charger's frame(), font atlas and number position.

    python make_charger_video.py OUT.mp4 [LEVEL]
"""
import math
import os
import subprocess
import sys
import tempfile

from PIL import Image

import make_charger as mc

FPS = 60
SWEEP_S, HOLD_S, CYCLES, CHARGED_S = 2.2, 1.3, 2, 3.0


def screen(fr, level):
    s = Image.new("RGB", (mc.W, mc.H), mc.BLACK)
    s.paste(fr, ((mc.W - mc.FW) // 2, (mc.H - mc.FH) // 2))
    txt = "%d%%" % level
    x = (mc.W - len(txt) * mc.CW) // 2
    py = int((mc.H - mc.FH) // 2 + mc.NUM_Y)
    for k, ch in enumerate(txt):
        i = ord(ch) - 0x20
        glyph = mc.ATLAS.crop((i * mc.CW, 0, (i + 1) * mc.CW, mc.FONT_H))
        s.paste(Image.new("RGB", glyph.size, mc.TXT), (x + k * mc.CW, py), glyph)
    return s


def main(out, level=35):
    frames = []
    t = 0.0
    for _ in range(CYCLES):
        n = int(SWEEP_S * FPS)
        for k in range(n):
            u = k / (n - 1)
            e = 1 - (1 - u) ** 3                                   # ease-out
            fill = level / 100 + (1 - level / 100) * e
            frames.append((fill, 0.0, t, "CHARGING", level)); t += 1 / FPS
        n = int(HOLD_S * FPS)
        for k in range(n):
            frames.append((1.0, math.sin(k / (n - 1) * math.pi), t, "CHARGING", level)); t += 1 / FPS
    n = int(CHARGED_S * FPS)
    for k in range(n):
        frames.append((1.0, math.sin(k / (n - 1) * math.pi), t, "CHARGED", 100)); t += 1 / FPS

    with tempfile.TemporaryDirectory() as tmp:
        for idx, (fill, tw, tt, label, lv) in enumerate(frames):
            fr = mc.frame(fill, twinkle=tw, t=tt, label=label, digits=len(str(lv)))
            screen(fr, lv).save(os.path.join(tmp, "f%04d.png" % idx))
            if idx % 60 == 0:
                print("frame", idx, "/", len(frames), flush=True)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS), "-i",
                        os.path.join(tmp, "f%04d.png"), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16",
                        "-preset", "slow", "-movflags", "+faststart", out], check=True)
    print("saved", out, len(frames), "frames,", round(len(frames) / FPS, 1), "s @", FPS, "fps")


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 35)
