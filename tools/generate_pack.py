#!/usr/bin/env python3
"""Generates the Rewind HUD resource pack (circular clock gauge).

Run:  python3 tools/generate_pack.py     (needs: pip install pillow)
Output: ../resourcepack/  (zip the CONTENTS of that folder to make the pack)
"""
import json
import math
import os
from PIL import Image, ImageDraw

STEPS = 32            # fill levels 0..STEPS. Must match GAUGE_STEPS in RewindPlugin.java
SIZE = 64             # source pixel size of each glyph
SS = 4                # supersampling for smooth edges
DISPLAY_HEIGHT = 18   # on-screen height in GUI pixels
ASCENT = -16          # vertical position. LOWER = further down the screen. Tune me!
PACK_FORMAT = 46      # change to match your Minecraft version if the client complains

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "resourcepack")
BACKING = (10, 10, 16)

PALETTES = {
    "idle":   dict(fill=(90, 220, 255),  track=(44, 58, 78),  face=(18, 22, 34), rim=None),
    "rewind": dict(fill=(214, 130, 255), track=(70, 44, 92),  face=(28, 16, 42), rim=(214, 130, 255)),
}


def draw_frame(pal, level):
    n = SIZE * SS
    img = Image.new("RGBA", (n, n), (*BACKING, 0))
    d = ImageDraw.Draw(img)
    c = n / 2
    R = n * 0.47

    def disc(r, color, a=255):
        d.ellipse([c - r, c - r, c + r, c + r], fill=(*color, a))

    f = level / STEPS
    disc(R, BACKING, 235)                       # backing disc
    if pal["rim"]:                              # glowing rim while rewinding
        disc(R, pal["rim"])
        disc(R * 0.94, BACKING, 240)

    Rt = R * 0.86
    disc(Rt, pal["track"])                      # empty track
    if f >= 1:
        disc(Rt, pal["fill"])
    elif f > 0:                                 # filled arc, clockwise from 12 o'clock
        d.pieslice([c - Rt, c - Rt, c + Rt, c + Rt], -90, -90 + 360 * f, fill=(*pal["fill"], 255))
    disc(R * 0.62, pal["face"])                 # clock face

    for i in range(12):                         # hour ticks
        a = math.radians(i * 30 - 90)
        r0 = R * (0.42 if i % 3 == 0 else 0.48)
        r1 = R * 0.56
        col = (200, 210, 225, 255) if i % 3 == 0 else (110, 120, 140, 255)
        d.line([c + r0 * math.cos(a), c + r0 * math.sin(a),
                c + r1 * math.cos(a), c + r1 * math.sin(a)],
               fill=col, width=int(SS * (2.2 if i % 3 == 0 else 1.4)))

    a = math.radians(-90 + 360 * f)             # clock hand points at the fill edge
    L = R * 0.5
    ex, ey = c + L * math.cos(a), c + L * math.sin(a)
    w = int(SS * 3)
    d.line([c, c, ex, ey], fill=(*pal["fill"], 255), width=w)
    d.ellipse([ex - w / 2, ey - w / 2, ex + w / 2, ey + w / 2], fill=(*pal["fill"], 255))
    d.ellipse([c - w, c - w, c + w, c + w], fill=(*pal["fill"], 255))

    return img.resize((SIZE, SIZE), Image.LANCZOS)


def main():
    tex_dir = os.path.join(OUT, "assets", "rewind", "textures", "gauge")
    font_dir = os.path.join(OUT, "assets", "rewind", "font")
    os.makedirs(tex_dir, exist_ok=True)
    os.makedirs(font_dir, exist_ok=True)

    providers = []
    for name, base in (("idle", 0xE000), ("rewind", 0xE100)):
        for lvl in range(STEPS + 1):
            draw_frame(PALETTES[name], lvl).save(os.path.join(tex_dir, f"{name}_{lvl:02d}.png"))
            providers.append({
                "type": "bitmap",
                "file": f"rewind:gauge/{name}_{lvl:02d}.png",
                "ascent": ASCENT,
                "height": DISPLAY_HEIGHT,
                "chars": [chr(base + lvl)],
            })

    with open(os.path.join(font_dir, "hud.json"), "w") as fh:
        json.dump({"providers": providers}, fh, indent=1)

    with open(os.path.join(OUT, "pack.mcmeta"), "w") as fh:
        json.dump({"pack": {
            "pack_format": PACK_FORMAT,
            "supported_formats": [PACK_FORMAT, 999],
            "min_format": PACK_FORMAT,
            "max_format": 999,
            "description": "Rewind HUD - circular clock gauge"}}, fh, indent=1)

    # preview sheet (not part of the pack)
    lv = [0, 8, 16, 24, 32]
    sheet = Image.new("RGBA", (len(lv) * 136, 2 * 136), (60, 90, 60, 255))
    for row, name in enumerate(("idle", "rewind")):
        for col, l in enumerate(lv):
            g = draw_frame(PALETTES[name], l).resize((128, 128), Image.NEAREST)
            sheet.alpha_composite(g, (col * 136 + 4, row * 136 + 4))
    sheet.save(os.path.join(OUT, "..", "gauge_preview.png"))
    print("done:", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
