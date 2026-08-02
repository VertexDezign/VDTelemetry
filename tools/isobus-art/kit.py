"""Flat-vector drawing kit for the ISOBUS machine art.

Geometry is authored once and emitted twice:

- **SVG** is the deliverable. Compose Resources on 1.11.1 generates
  `Res.drawable.<name>` for `.svg` (verified against this build), so the app loads
  it exactly like the old PNG, at a few KB instead of a hundred.
- **PNG via ImageMagick MVG** is the preview, because this sandbox has no SVG
  rasteriser — ImageMagick's internal SVG reader silently drops strokes and
  transforms, so it cannot be used to check the deliverable. MVG honours both.

Both backends read the same op list, so the preview cannot drift from the SVG.
Coordinates are in a 1600x1000 space with the ground line at y=880.
"""
import os
import re
import subprocess

W, H = 1600, 1000
GROUND = 880
SS = 3  # preview supersampling

# House palette, sampled straight out of mb_trac.png rather than invented.
OUTLINE = "#1B1E1B"
BODY = "#CFCC50"    # olive-yellow bodywork
BODY_D = "#7D7F4B"  # shaded/secondary green
TYRE = "#49483D"
FRAME = "#4A4942"
HUB = "#CAAF39"
GLASS = "#BBD9D6"
STEEL = "#6E6E60"   # steel wear parts (shares, discs, mouldboards)
EMPTY = "#C9C8B6"   # an empty vessel, readable on both #F0F0F2 and black

# Materials, for the fill layers.
SLURRY = "#8A6A3C"
MUCK = "#7A5636"
GRANULE = "#BFA164"
LIQUID = "#5A8AA6"
SEED = "#B07C2E"

MAIN, MED, FINE = 13, 9, 6


class Draw:
    """Accumulates structured ops. `layer` decides whether a call paints or is
    skipped, so the body and the fill come out of one geometry pass and cannot
    drift apart."""

    def __init__(self, layer="body"):
        self.layer = layer
        self.ops = []
        self._stack = [self.ops]

    def _add(self, op):
        if self.layer != "body":
            return
        self._stack[-1].append(op)

    # -- primitives ----------------------------------------------------------
    def path(self, d, fill=BODY, stroke=OUTLINE, w=MAIN):
        self._add(dict(k="path", d=d, fill=fill, stroke=stroke, w=w))

    def rr(self, x1, y1, x2, y2, r, fill=BODY, stroke=OUTLINE, w=MAIN):
        self._add(dict(k="rr", x1=x1, y1=y1, x2=x2, y2=y2, r=r, fill=fill, stroke=stroke, w=w))

    def circ(self, cx, cy, r, fill=BODY, stroke=OUTLINE, w=MAIN):
        self._add(dict(k="circ", cx=cx, cy=cy, r=r, fill=fill, stroke=stroke, w=w))

    def ell(self, cx, cy, rx, ry, fill=BODY, stroke=OUTLINE, w=MAIN):
        self._add(dict(k="ell", cx=cx, cy=cy, rx=rx, ry=ry, fill=fill, stroke=stroke, w=w))

    def poly(self, pts, fill=BODY, stroke=OUTLINE, w=MAIN):
        self._add(dict(k="poly", pts=list(pts), fill=fill, stroke=stroke, w=w))

    def rot(self, cx, cy, deg, body):
        """body: a callable taking a Draw, drawing around the origin."""
        if self.layer != "body":
            return
        g = dict(k="g", cx=cx, cy=cy, deg=deg, ops=[])
        self._stack[-1].append(g)
        self._stack.append(g["ops"])
        body(self)
        self._stack.pop()

    # -- the one call that paints on both layers -----------------------------
    def cavity(self, d, material):
        """The vessel interior. On the body layer it is an empty tank; on the fill
        layer it is the material, alone, at identical coordinates."""
        self._stack[-1].append(dict(k="path", d=d, stroke=None, w=0,
                                    fill=EMPTY if self.layer == "body" else material))


# ---------------------------------------------------------------- emitters
def to_svg(d):
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">',
           '<g stroke-linejoin="round" stroke-linecap="round">']

    def style(o):
        s = f' fill="{o["fill"]}"' if o["fill"] else ' fill="none"'
        if o.get("stroke"):
            s += f' stroke="{o["stroke"]}" stroke-width="{o["w"]}"'
        return s

    def emit(ops, ind="  "):
        for o in ops:
            if o["k"] == "g":
                out.append(f'{ind}<g transform="translate({o["cx"]},{o["cy"]}) rotate({o["deg"]:.3f})">')
                emit(o["ops"], ind + "  ")
                out.append(f"{ind}</g>")
            elif o["k"] == "path":
                out.append(f'{ind}<path d="{o["d"]}"{style(o)}/>')
            elif o["k"] == "rr":
                out.append(f'{ind}<rect x="{o["x1"]}" y="{o["y1"]}" width="{o["x2"] - o["x1"]}"'
                           f' height="{o["y2"] - o["y1"]}" rx="{o["r"]}"{style(o)}/>')
            elif o["k"] == "circ":
                out.append(f'{ind}<circle cx="{o["cx"]}" cy="{o["cy"]}" r="{o["r"]}"{style(o)}/>')
            elif o["k"] == "ell":
                out.append(f'{ind}<ellipse cx="{o["cx"]}" cy="{o["cy"]}" rx="{o["rx"]}"'
                           f' ry="{o["ry"]}"{style(o)}/>')
            elif o["k"] == "poly":
                p = " ".join(f"{x},{y}" for x, y in o["pts"])
                out.append(f'{ind}<polygon points="{p}"{style(o)}/>')

    emit(d.ops)
    out += ["</g>", "</svg>"]
    return "\n".join(out)


_NUM = re.compile(r"-?\d+(?:\.\d+)?")


def to_mvg(d):
    """The preview backend. Numbers are scaled by SS here; the SVG stays at 1x."""

    def sp(path):
        return _NUM.sub(lambda m: f"{float(m.group()) * SS:.2f}", path)

    def n(v):
        return f"{v * SS:.2f}"

    out = ["stroke-linejoin round stroke-linecap round"]

    def style(o):
        s = f"fill '{o['fill']}' " if o["fill"] else "fill none "
        s += f"stroke '{o['stroke']}' stroke-width {n(o['w'])} " if o.get("stroke") else "stroke none "
        return s

    def emit(ops):
        for o in ops:
            if o["k"] == "g":
                out.append(f"push graphic-context translate {n(o['cx'])},{n(o['cy'])} rotate {o['deg']:.3f}")
                emit(o["ops"])
                out.append("pop graphic-context")
                continue
            s = style(o)
            if o["k"] == "path":
                out.append(s + f"path '{sp(o['d'])}'")
            elif o["k"] == "rr":
                out.append(s + f"roundrectangle {n(o['x1'])},{n(o['y1'])} {n(o['x2'])},{n(o['y2'])} "
                               f"{n(o['r'])},{n(o['r'])}")
            elif o["k"] == "circ":
                out.append(s + f"circle {n(o['cx'])},{n(o['cy'])} {n(o['cx'])},{n(o['cy'] + o['r'])}")
            elif o["k"] == "ell":
                out.append(s + f"ellipse {n(o['cx'])},{n(o['cy'])} {n(o['rx'])},{n(o['ry'])} 0,360")
            elif o["k"] == "poly":
                p = " ".join(f"{n(x)},{n(y)}" for x, y in o["pts"])
                out.append(s + f"polygon {p}")

    emit(d.ops)
    return " ".join(out)


# ---------------------------------------------------------------- parts
def tyre(d, cx, cy, r, lugs=22):
    """A tyre in the reference's own language: dark carcass, notched tread, amber
    rim, dark centre. mb_trac's wheels are the strongest style cue in the set."""
    for i in range(lugs):
        d.rot(cx, cy, 360.0 * i / lugs,
              lambda dd, r=r: dd.rr(-r * 0.075, -r * 1.045, r * 0.075, -r * 0.80,
                                    r * 0.035, TYRE, OUTLINE, MED))
    d.circ(cx, cy, r, TYRE, OUTLINE, MAIN)
    d.circ(cx, cy, r * 0.56, HUB, OUTLINE, MED)
    d.circ(cx, cy, r * 0.20, TYRE, OUTLINE, MED)


def hitch(d, x, y, r=30):
    """Drawbar eye — the detail that says 'this gets pulled'."""
    d.circ(x, y, r, FRAME, OUTLINE, MAIN)
    d.circ(x, y, r * 0.42, EMPTY, OUTLINE, MED)


def jack(d, x, ytop, ybot):
    """Parking leg."""
    d.rr(x - 20, ytop, x + 20, ybot, 7, FRAME, OUTLINE, MED)
    d.rr(x - 38, ybot - 6, x + 38, ybot + 18, 8, FRAME, OUTLINE, MED)


def spinner(d, cx, cy, rx, vanes=5):
    """A horizontal spinning disc, seen from the side or from directly behind — so it
    is edge-on: a flattened ellipse with the throwing vanes standing up off the plate.
    Drawn face-on as a circle it reads as a wheel, which is the one thing a spreader
    disc must not look like."""
    ry = rx * 0.30
    d.ell(cx, cy, rx, ry, STEEL, OUTLINE, MAIN)
    span = rx * 1.30
    for k in range(vanes):
        vx = cx - span / 2 + k * span / (vanes - 1)
        d.rr(vx - rx * 0.075, cy - ry - rx * 0.34, vx + rx * 0.075, cy - ry * 0.15,
             4, BODY_D, OUTLINE, FINE)
    d.ell(cx, cy - ry * 0.30, rx * 0.28, ry * 0.40, FRAME, OUTLINE, MED)


def disc(d, cx, cy, r):
    """A concave coulter/packer disc."""
    d.circ(cx, cy, r, STEEL, OUTLINE, MAIN)
    d.circ(cx, cy, r * 0.34, FRAME, OUTLINE, MED)


OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def write(name, d):
    os.makedirs(OUT, exist_ok=True)
    svg = os.path.join(OUT, name + ".svg")
    png = os.path.join(OUT, name + ".png")
    with open(svg, "w") as f:
        f.write(to_svg(d))
    subprocess.run(
        ["magick", "-size", f"{W * SS}x{H * SS}", "xc:none", "-draw", to_mvg(d),
         "-filter", "Lanczos", "-resize", f"{W}x{H}", "-strip", png],
        check=True,
    )
    return svg, png
