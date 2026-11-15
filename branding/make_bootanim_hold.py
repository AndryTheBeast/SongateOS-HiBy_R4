"""SongateOS "hold" boot animation: started again by the module right after Android's own boot animation ends, and
kept running until the default launcher (Songate) is on screen (Android stops the first animation as soon as its
placeholder home screen appears, before the launcher can start).

    part0  fade in from black (0.4 s)      - the first animation's outro just faded to black
    part1  the same loop as bootanimation.zip (glow breathing, stardust, the progress pill)
    part2  the same fade out to black

    python make_bootanim_hold.py MAIN_bootanimation.zip OUT.zip
"""
import io
import sys
import zipfile

from PIL import Image

import make_bootanim as mb

FADE_IN = 12          # frames at 30 fps


def main(main_zip, out):
    src = zipfile.ZipFile(main_zip)
    names = [n for n in src.namelist() if n.startswith(("part1/", "part2/"))]
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED) as z:     # bootanimation needs STORED entries
        z.writestr("desc.txt", "%d %d %d\nc 1 0 part0\np 0 0 part1\nc 1 0 part2\n" % (mb.W, mb.H, mb.FPS))
        black = Image.new("RGB", (mb.W, mb.H), (0, 0, 0))
        for i in range(FADE_IN):
            a = mb.eased((i + 1) / FADE_IN)
            f = Image.blend(black, mb.loop_frame(i / mb.FPS), a)
            buf = io.BytesIO()
            f.save(buf, "PNG", optimize=True)
            z.writestr("part0/%05d.png" % i, buf.getvalue())
        for n in names:                                       # the loop and the outro, byte for byte
            z.writestr(n, src.read(n))
    print("hold zip", out, "parts:", FADE_IN, "fade-in +", len(names), "loop/outro frames")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
