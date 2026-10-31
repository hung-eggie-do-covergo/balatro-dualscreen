"""Regenerate the release APK's icon (this project's own original artwork).

Two stacked pixel-art screens over a red swirl: an ace of spades on the
top one, a fanned hand on the bottom one -- the app's split, as a picture. Every pixel is generated here; no Balatro asset is involved
(rule #1), and nothing is traced from the game's icon or logo. Personal
builds can still use the real artwork via `tools/build.py --icon`.

Drawn on a 96 px grid and upscaled with nearest-neighbour, so it stays
crisp like the game it sits beside.

Outputs (committed in the repo):
  android/app/src/main/res/drawable-nodpi/ds_icon_bg.png   adaptive background
  android/app/src/main/res/drawable-nodpi/ds_icon_fg.png   adaptive foreground
  android/app/src/main/res/mipmap-nodpi/ds_icon.png        legacy square

Requires Pillow. Only needed when changing the design.
"""
import math
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

G = 96          # pixel grid
OUT = 1024

# Swirl palette, darkest to lightest, plus the blue undertow.
REDS = [(58, 14, 20), (110, 24, 30), (176, 38, 42), (232, 74, 62)]
BLUE = (40, 92, 176)
INK = (24, 22, 30)
CREAM = (246, 238, 222)
SHADE = (214, 200, 180)
SPADE = (34, 34, 52)
FELT = (30, 58, 47)
SUIT_RED = (214, 56, 56)
SUIT_BLUE = (40, 92, 176)

# 7 x 9 spade, '#' filled. Generic card-suit shape.
SPADE_PX = [
    "...#...",
    "..###..",
    ".#####.",
    "#######",
    "#######",
    "#######",
    ".##.##.",
    "...#...",
    "..###..",
]
# 3 x 5 letter A.
A_PX = [
    ".#.",
    "#.#",
    "###",
    "#.#",
    "#.#",
]


def swirl():
    img = Image.new("RGB", (G, G))
    px = img.load()
    c = (G - 1) / 2
    for y in range(G):
        for x in range(G):
            dx, dy = x - c, y - c
            r = math.hypot(dx, dy) / c
            a = math.atan2(dy, dx)
            # Arms wind tighter towards the centre.
            v = math.sin(3 * a + 7 * r) * 0.5 + 0.5
            v = v * (0.55 + 0.45 * r)
            band = min(int(v * 4), 3)
            colour = REDS[band]
            if math.sin(3 * a + 7 * r + 2.4) > 0.86 and r > 0.25:
                colour = BLUE
            px[x, y] = colour
    return img


def stamp(px, pattern, x0, y0, colour, scale=1):
    for j, row in enumerate(pattern):
        for i, ch in enumerate(row):
            if ch == "#":
                for sy in range(scale):
                    for sx in range(scale):
                        px[x0 + i * scale + sx, y0 + j * scale + sy] = colour


def rect(px, x0, y0, w, h, fill, outline, cut_corners=True):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            if cut_corners and x in (x0, x0 + w - 1) and y in (y0, y0 + h - 1):
                continue
            edge = x in (x0, x0 + w - 1) or y in (y0, y0 + h - 1)
            px[x, y] = (outline if edge else fill) + (255,)


def mini_card(px, x0, y0, suit):
    rect(px, x0, y0, 9, 13, CREAM, INK)
    for y in range(y0 + 1, y0 + 12):
        px[x0 + 7, y] = SHADE + (255,)
    # A suit pip: a 3 x 3 diamond.
    cx, cy = x0 + 4, y0 + 6
    for dx, dy in ((0, -1), (-1, 0), (0, 0), (1, 0), (0, 1)):
        px[cx + dx, cy + dy] = suit + (255,)


def card():
    img = Image.new("RGBA", (G, G), (0, 0, 0, 0))
    px = img.load()
    # Both screens sit inside the adaptive safe circle (radius ~32 of 96).
    x0, w, h, gap = 28, 40, 22, 2
    top, bottom = 25, 25 + h + gap
    for y0 in (top, bottom):
        rect(px, x0, y0, w, h, FELT, CREAM)
    # Top screen: one card, the ace of spades.
    cw, ch = 15, 18
    cx0, cy0 = x0 + (w - cw) // 2, top + (h - ch) // 2
    rect(px, cx0, cy0, cw, ch, CREAM, INK)
    for y in range(cy0 + 1, cy0 + ch - 1):
        px[cx0 + cw - 2, y] = SHADE + (255,)
    stamp(px, A_PX, cx0 + 2, cy0 + 2, SPADE + (255,))
    stamp(px, SPADE_PX, cx0 + 5, cy0 + 6, SPADE + (255,))
    # Bottom screen: a fanned hand, the middle cards raised.
    for i, (dy, suit) in enumerate(((2, SUIT_RED), (0, SUIT_BLUE), (0, SUIT_RED), (2, SUIT_BLUE))):
        mini_card(px, x0 + 4 + i * 8, bottom + 4 + dy, suit)
    return img


def up(img):
    return img.resize((OUT, OUT), Image.NEAREST)


def main():
    bg = up(swirl())
    fg = up(card())
    nodpi = os.path.join(RES, "drawable-nodpi")
    bg.save(os.path.join(nodpi, "ds_icon_bg.png"))
    fg.save(os.path.join(nodpi, "ds_icon_fg.png"))
    legacy = bg.convert("RGBA")
    legacy.alpha_composite(fg)
    legacy.resize((512, 512), Image.NEAREST).save(os.path.join(RES, "mipmap-nodpi", "ds_icon.png"))
    print("release icon regenerated")


if __name__ == "__main__":
    main()
