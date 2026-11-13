"""Split the Nous girl art into theme-tintable layers.

Inputs (authored art, cream paper background):
  art/nous_girl_portrait_source.png   432x432 portrait (same art as the launcher icon foreground)
  art/overlay_state_atlas_source.png  5 rows x 30 cells of 96 px, overlay animation

Outputs in app/src/main/res/drawable-nodpi:
  <name>_base.png     the drawing with the paper background removed and headset accents cut out
  <name>_accent.png   headset accents as white-to-grey shading; the app multiplies it by the theme colour
  <name>_outline.png  white silhouette ring; the app paints it in the theme's outline colour

The paper is removed by flood fill from the top, left and right edges. The torso is open at the
bottom edge, so the fill never enters the bottom strip; a bottom-strip column is cleared only
when the paper directly above it was removed. Run from the android directory:
  python tools/mascot_layers.py
"""
from collections import deque
from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/res/drawable-nodpi'
PAPER = (252, 236, 217)
ACCENT_REF = 165.0  # luminance of the authored gold; maps to the full theme colour


def paper(p, tol):
    return p[3] > 0 and sum(abs(p[i] - PAPER[i]) for i in range(3)) < tol


def accent_strength(p):
    if p[3] == 0:
        return 0.0
    warm = p[0] - p[2]
    return max(0.0, min(1.0, (warm - 50) / 35.0)) if p[0] > 110 else 0.0


def cut_paper(cell, strip, tol=60):
    """Return a copy of cell with the paper background made transparent."""
    img = cell.copy()
    px = img.load()
    w, h = img.size
    limit = h - strip  # rows >= limit form the protected bottom strip
    seen = bytearray(w * h)
    queue = deque([(x, 0) for x in range(w)] + [(0, y) for y in range(limit)] + [(w - 1, y) for y in range(limit)])
    while queue:
        x, y = queue.popleft()
        if not (0 <= x < w and 0 <= y < limit) or seen[y * w + x]:
            continue
        seen[y * w + x] = 1
        if not paper(px[x, y], tol):
            continue
        px[x, y] = (0, 0, 0, 0)
        queue.extend(((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))
    for x in range(w):
        if px[x, limit - 1][3] == 0:
            for y in range(limit, h):
                if paper(px[x, y], tol) or px[x, y][3] == 0:
                    px[x, y] = (0, 0, 0, 0)
                else:
                    break
    # Soften the remaining anti-aliased paper fringe next to removed pixels.
    alpha = img.getchannel('A')
    hole = alpha.point(lambda v: 255 if v == 0 else 0).filter(ImageFilter.MaxFilter(3))
    hp = hole.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a and hp[x, y] and paper((r, g, b, a), 110):
                px[x, y] = (r, g, b, a // 3)
    return img


def split(img, ring):
    w, h = img.size
    px = img.load()
    base = img.copy()
    bp = base.load()
    accent = Image.new('RGBA', (w, h), (255, 255, 255, 0))
    ap = accent.load()
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            s = accent_strength(p)
            if s <= 0:
                continue
            lum = 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]
            v = int(max(0, min(255, 255 * lum / ACCENT_REF)))
            ap[x, y] = (v, v, v, int(p[3] * s))
            bp[x, y] = (p[0], p[1], p[2], int(p[3] * (1 - s)))
    sil = img.getchannel('A').point(lambda v: 255 if v > 40 else 0)
    grown = sil.filter(ImageFilter.MaxFilter(ring * 2 + 1)).filter(ImageFilter.GaussianBlur(0.6))
    # The art is cut flat at the bottom; no outline along that cut.
    bbox = sil.getbbox()
    if bbox:
        gp = grown.load()
        for y in range(bbox[3], h):
            for x in range(w):
                gp[x, y] = 0
    outline = Image.new('RGBA', (w, h), (255, 255, 255, 0))
    outline.putalpha(grown)
    return base, accent, outline


def save(layers, name):
    for suffix, im in zip(('base', 'accent', 'outline'), layers):
        im.save(OUT / f'{name}_{suffix}.png', optimize=True)


def portrait():
    src = Image.open(ROOT / 'art/nous_girl_portrait_source.png').convert('RGBA')
    x0, y0, x1, y1 = src.getchannel('A').getbbox()
    cut = cut_paper(src.crop((x0, y0, x1, y1)), strip=7)
    pad = 8
    framed = Image.new('RGBA', (cut.width + 2 * pad, cut.height + 2 * pad), (0, 0, 0, 0))
    framed.alpha_composite(cut, (pad, pad))
    save(split(framed, ring=3), 'nous_girl_portrait')


def atlas():
    src = Image.open(ROOT / 'art/overlay_state_atlas_source.png').convert('RGBA')
    cell = 96
    rows, cols = src.height // cell, src.width // cell
    layers = [Image.new('RGBA', src.size, (0, 0, 0, 0)) for _ in range(3)]
    for r in range(rows):
        for c in range(cols):
            box = (c * cell, r * cell, (c + 1) * cell, (r + 1) * cell)
            parts = split(cut_paper(src.crop(box), strip=3), ring=2)
            for layer, part in zip(layers, parts):
                layer.alpha_composite(part, box[:2])
    save(layers, 'overlay_state_atlas')


if __name__ == '__main__':
    portrait()
    atlas()
    print('ok')
