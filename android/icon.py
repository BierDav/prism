"""Prism's launcher icon, its themed (monochrome) layer and the 24 dp glyph.

The mark is the macOS app's glyph (MenuBar.swift, PrismGlyph): a beam into a
prism and three rays out of it. Here it is chunky and rounded: a solid glass
prism, a white beam, three coloured rays, on a vivid indigo field.

usage: python3 icon.py res [preview-dir]
Writes the vector drawables into res/ and, given a directory, SVG previews of
the same geometry (normal, squircle, themed light and dark, the safe zone,
the small glyph) to look at before building.
"""
import math
import os
import sys

res = sys.argv[1]
preview = sys.argv[2] if len(sys.argv) > 2 else None
os.makedirs(res + "/drawable", exist_ok=True)
os.makedirs(res + "/mipmap-anydpi", exist_ok=True)

C = (54.0, 54.0)
SAFE = 33.0

A, BL, BR = (50.0, 28.0), (26.5, 70.5), (73.5, 70.5)
RADIUS = 3.6  # the prism's corner radius
BEAM = ((24.0, 57.0), (41.0, 50.5))
EXIT = (59.0, 50.5)
RAYS = [((80.5, 41.0), "#FF6B6B"), ((82.5, 50.5), "#FFC94A"), ((80.5, 60.0), "#4FE0D0")]
STROKE = 5.2


def f(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def P(p):
    return f"{f(p[0])},{f(p[1])}"


def towards(p, q, d):
    l = math.dist(p, q)
    return (p[0] + (q[0] - p[0]) * d / l, p[1] + (q[1] - p[1]) * d / l)


def rounded(points, r):
    """A closed polygon (clockwise on screen) with circular fillets of radius r."""
    n = len(points)
    out = []
    for i, v in enumerate(points):
        prev, nxt = points[i - 1], points[(i + 1) % n]
        u1 = ((prev[0] - v[0]) / math.dist(prev, v), (prev[1] - v[1]) / math.dist(prev, v))
        u2 = ((nxt[0] - v[0]) / math.dist(nxt, v), (nxt[1] - v[1]) / math.dist(nxt, v))
        theta = math.acos(max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1])))
        d = r / math.tan(theta / 2)
        a, b = towards(v, prev, d), towards(v, nxt, d)
        out.append(("L" if out else "M") + P(a))
        out.append(f"A{f(r)},{f(r)} 0 0,1 {P(b)}")
    return " ".join(out) + " Z"


PRISM = rounded([A, BR, BL], RADIUS)
# The shaded right face: from the apex down to a point on the base.
FACE_BASE = (BL[0] + (BR[0] - BL[0]) * 0.60, BL[1])
# Unrounded: drawn clipped to the prism.
FACE = f"M{P(A)} L{P((BR[0] + 6, BR[1] + 6))} L{P((FACE_BASE[0], BL[1] + 6))} Z"

# Safe zone check: every stroke end plus half the stroke width, every vertex.
ends = [BEAM[0]] + [r for r, _ in RAYS]
worst = max([math.dist(p, C) + STROKE / 2 for p in ends] + [math.dist(p, C) for p in (A, BL, BR)])
assert worst <= SAFE, worst

ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'


def argb(hex6, alpha=1.0):
    return "#%02X%s" % (round(alpha * 255), hex6.lstrip("#").upper())


_ids = iter(range(1, 10_000))


class Drawable:
    """Writes the same picture as an Android vector drawable and as SVG."""

    def __init__(self, size, viewport):
        self.size, self.vp = size, viewport
        self.xml, self.svg, self.defs, self.n = [], [], [], 0

    def linear(self, x1, y1, x2, y2, stops):
        gid = f"g{next(_ids)}"
        items = "".join(f'<item android:offset="{o}" android:color="{argb(c, a)}"/>' for o, c, a in stops)
        xml = (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="{f(x1)}" '
               f'android:startY="{f(y1)}" android:endX="{f(x2)}" android:endY="{f(y2)}">{items}</gradient></aapt:attr>')
        st = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops)
        self.defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{f(x1)}" y1="{f(y1)}" '
                         f'x2="{f(x2)}" y2="{f(y2)}">{st}</linearGradient>')
        return xml, f"url(#{gid})"

    def radial(self, cx, cy, r, stops):
        gid = f"g{next(_ids)}"
        items = "".join(f'<item android:offset="{o}" android:color="{argb(c, a)}"/>' for o, c, a in stops)
        xml = (f'<aapt:attr name="android:fillColor"><gradient android:type="radial" android:centerX="{f(cx)}" '
               f'android:centerY="{f(cy)}" android:gradientRadius="{f(r)}">{items}</gradient></aapt:attr>')
        st = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops)
        self.defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{f(cx)}" cy="{f(cy)}" '
                         f'r="{f(r)}">{st}</radialGradient>')
        return xml, f"url(#{gid})"

    def fill(self, d, color, alpha=1.0, comment=None):
        if comment:
            self.xml.append(f"    <!-- {comment} -->")
        if isinstance(color, tuple):
            self.xml.append(f'    <path android:pathData="{d}">\n        {color[0]}\n    </path>')
            self.svg.append(f'<path d="{d}" fill="{color[1]}"/>')
        else:
            self.xml.append(f'    <path android:fillColor="{argb(color, alpha)}" android:pathData="{d}" />')
            self.svg.append(f'<path d="{d}" fill="{color}" fill-opacity="{alpha}"/>')

    def begin_clip(self, d):
        cid = f"c{next(_ids)}"
        self.xml.append(f'    <group>\n    <clip-path android:pathData="{d}" />')
        self.defs.append(f'<clipPath id="{cid}"><path d="{d}"/></clipPath>')
        self.svg.append(f'<g clip-path="url(#{cid})">')

    def end_clip(self):
        self.xml.append("    </group>")
        self.svg.append("</g>")

    def line(self, a, b, color, width, alpha=1.0, comment=None):
        if comment:
            self.xml.append(f"    <!-- {comment} -->")
        d = f"M{P(a)} L{P(b)}"
        self.xml.append(f'    <path android:strokeColor="{argb(color, alpha)}" android:strokeWidth="{f(width)}" '
                        f'android:strokeLineCap="round" android:pathData="{d}" />')
        self.svg.append(f'<path d="{d}" stroke="{color}" stroke-opacity="{alpha}" stroke-width="{f(width)}" '
                        f'stroke-linecap="round" fill="none"/>')

    def write(self, name, comment, aapt=False):
        ns = ANDROID + (" " + AAPT if aapt else "")
        body = "\n".join(self.xml)
        xml = (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} -->\n<vector {ns}\n'
               f'    android:width="{self.size}dp" android:height="{self.size}dp" '
               f'android:viewportWidth="{self.vp}" android:viewportHeight="{self.vp}">\n{body}\n</vector>\n')
        open(f"{res}/drawable/{name}.xml", "w").write(xml)
        return self

    def svg_body(self):
        return "<defs>" + "".join(self.defs) + "</defs>" + "".join(self.svg)


GEN = "Generated by android/icon.py; edit that, not this file."

# Background: a vivid indigo field, lighter at the top left, with a soft glow
# behind the prism.
bg = Drawable(108, 108)
bg.fill("M0,0h108v108h-108z", bg.linear(10, 4, 98, 104, [(0, "#6366F5", 1), (0.5, "#3E37C9", 1), (1, "#22197A", 1)]))
bg.fill("M0,0h108v108h-108z", bg.radial(50, 50, 38, [(0, "#9AA4FF", 0.45), (0.55, "#9AA4FF", 0.12), (1, "#9AA4FF", 0)]))
bg.write("ic_launcher_background", "Prism's launcher icon, background layer. " + GEN, aapt=True)

# Foreground: shadow, rays (under the glass, so they leave from inside it),
# the beam, the prism with a shaded face and a highlight.
fg = Drawable(108, 108)
shadow = "M24,75 a26,3.8 0 1,0 52,0 a26,3.8 0 1,0 -52,0 Z"
fg.fill(shadow, "#0B0730", 0.28, comment="A soft shadow under the prism.")
for end, color in RAYS:
    fg.line(EXIT, end, color, STROKE)
fg.line(BEAM[0], BEAM[1], "#FFFFFF", STROKE, comment="The beam of white light.")
fg.fill(PRISM, fg.linear(A[0], A[1], A[0], BL[1], [(0, "#FFFFFF", 1), (1, "#E4E5FF", 1)]), comment="The glass.")
fg.begin_clip(PRISM)
fg.fill(FACE, fg.linear(A[0], A[1], BR[0], BR[1], [(0, "#C9CBFF", 1), (1, "#A9ABFA", 1)]), comment="Its shaded face.")
edge = f"M{P(A)} L{P(BL)} L{P(towards(BL, BR, 3.2))} L{P(towards(A, BR, 2.0))} Z"
fg.fill(edge, "#FFFFFF", 0.95, comment="A highlight along the lit edge.")
fg.end_clip()
fg.write("ic_launcher_foreground",
         "Prism's launcher icon, foreground: a white beam into a glass prism, three coloured rays out "
         "(the macOS app's glyph). All inside the 66 dp safe zone. " + GEN, aapt=True)

# Monochrome (themed icons): the system tints this one colour, so the parts
# can't overlap as they do in colour. The beam and the rays stop short of the
# prism with a gap, its shaded face is a lighter tone, and the whole glyph is
# smaller (50 dp wide), as Android's themed icons are: it sits in the middle
# of the 66 dp safe zone like the other apps' glyphs.
MONO_W = 50.0
gx0, gx1 = BEAM[0][0] - STROKE / 2, max(e[0] for e, _ in RAYS) + STROKE / 2
gy0, gy1 = A[1], BL[1]
ms = MONO_W / (gx1 - gx0)
def M(p):
    return (C[0] + (p[0] - (gx0 + gx1) / 2) * ms, C[1] + (p[1] - (gy0 + gy1) / 2) * ms)
def edge_x(a, b, y):
    """x on the line a-b at height y."""
    return a[0] + (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1])
GAP = 3.4
beam_end = (edge_x(A, BL, BEAM[1][1]) - GAP, BEAM[1][1])
beam_start = towards(beam_end, BEAM[0], math.dist(BEAM[0], BEAM[1]))
mono = Drawable(108, 108)
mono_stroke = STROKE * ms * 1.15
def leaves(end):
    """Where a ray starts: spread along the prism's right edge, a gap past it,
    so the three read apart in one colour."""
    y = EXIT[1] + (end[1] - EXIT[1]) * 0.75
    return (edge_x(A, BR, y) + GAP * 1.6, y)
for end, _ in RAYS:
    mono.line(M(leaves(end)), M(end), "#FFFFFF", mono_stroke)
mono.line(M(beam_start), M(beam_end), "#FFFFFF", mono_stroke)
mono_prism = rounded([M(A), M(BR), M(BL)], RADIUS * ms * 1.2)
mono_face = f"M{P(M(A))} L{P(M((BR[0] + 6, BR[1] + 6)))} L{P(M((FACE_BASE[0], BL[1] + 6)))} Z"
mono.begin_clip(mono_prism)
# The lit part: the prism without its face (even-odd: the square minus the face).
lit = "M0,0h108v108h-108z " + mono_face
mono.xml.append(f'    <!-- The prism: lit in full, its shaded face lighter. -->\n'
                f'    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{lit}" />')
mono.svg.append(f'<path d="{lit}" fill="#FFFFFF" fill-rule="evenodd"/>')
mono.fill(mono_face, "#FFFFFF", 0.55)
mono.end_clip()
mono.write("ic_launcher_monochrome", "Prism's launcher icon for themed icons: the glyph in one colour. " + GEN)

# The 24 dp glyph for notifications and the quick-settings tile: the same
# picture scaled from the glyph's box, strokes a little bolder.
box = (21.0, 27.5, 85.5, 71.0)  # x0, y0, x1, y1 of the foreground glyph
k = 22.0 / (box[2] - box[0])
def T(p):
    return (1.0 + (p[0] - box[0]) * k, 12.0 + (p[1] - (box[1] + box[3]) / 2) * k)
small = Drawable(24, 24)
small.fill(rounded([T(A), T(BR), T(BL)], RADIUS * k * 1.2), "#FFFFFF")
for end, _ in RAYS:
    small.line(T(EXIT), T(end), "#FFFFFF", 2.1)
small.line(T(BEAM[0]), T(BEAM[1]), "#FFFFFF", 2.1)
small.write("ic_prism", "Prism's glyph for notifications and the quick-settings tile (white, tinted by the "
            "system). " + GEN)

open(res + "/mipmap-anydpi/ic_launcher.xml", "w").write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')

print("worst distance from centre:", round(worst, 2), "of", SAFE)
if preview is None:
    sys.exit(0)
os.makedirs(preview, exist_ok=True)


# Previews: normal, themed (light and dark), the small glyph; masked by a
# circle, a squircle and the safe zone outline.
def svg(content, size=108, view=108):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 {view} {view}">{content}</svg>')

clip = '<clipPath id="m"><circle cx="54" cy="54" r="45"/></clipPath>'
sq = '<clipPath id="s"><rect x="9" y="9" width="90" height="90" rx="28"/></clipPath>'
normal = f'<g clip-path="url(#m)">{bg.svg_body()}{fg.svg_body()}</g>'
open(preview + "/normal.svg", "w").write(svg(clip + normal, 432))
open(preview + "/squircle.svg", "w").write(svg(sq + f'<g clip-path="url(#s)">{bg.svg_body()}{fg.svg_body()}</g>', 432))
mono_body = mono.svg_body().replace('fill="#FFFFFF"', 'fill="#1D2A6B"').replace('stroke="#FFFFFF"', 'stroke="#1D2A6B"')
open(preview + "/themed-light.svg", "w").write(svg(clip + f'<g clip-path="url(#m)"><rect width="108" height="108" fill="#DCE1FF"/>{mono_body}</g>', 432))
mono_dark = mono.svg_body().replace('fill="#FFFFFF"', 'fill="#C3CBFF"').replace('stroke="#FFFFFF"', 'stroke="#C3CBFF"')
open(preview + "/themed-dark.svg", "w").write(svg(clip + f'<g clip-path="url(#m)"><rect width="108" height="108" fill="#252A3D"/>{mono_dark}</g>', 432))
open(preview + "/safe.svg", "w").write(svg(bg.svg_body() + fg.svg_body() + '<circle cx="54" cy="54" r="33" fill="none" stroke="red" stroke-width="0.4"/>', 432))
open(preview + "/small.svg", "w").write(svg(f'<rect width="24" height="24" fill="#202124"/>{small.svg_body()}', 96, 24))
