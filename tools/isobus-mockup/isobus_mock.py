#!/usr/bin/env python3
"""IsoBus panel mockups, driven by the real captures.

Fills only: ImageMagick has no rsvg delegate here and its internal renderer silently
drops strokes, so every outline is a filled shape. That is also what ClusterIcons.kt
does, so these translate straight into Compose paths.
"""
import json, math, os, subprocess, sys

ROOT = "/workspace/VDTelemetry/examples/json/telemetry"
OUT = os.path.dirname(os.path.abspath(__file__)) + "/mock"

# Resolved from this file so the tool works from any cwd once it lives in the repo.
if not os.path.isdir(ROOT):
    ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "examples", "json", "telemetry"))

# App theme (theme/Theme.kt)
PANEL = "#F0F0F2"
BORDER = "#D1D5DB"
TEXT = "#333333"
MUTED = "#666666"
GREEN = "#2D8633"
ACCENT = "#00A35C"
BLUE = "#2563EB"
TRACK = "#E5E7EB"
AMBER = "#D97706"
RED = "#DC2626"
STEEL = "#4B5563"
DARKSTEEL = "#374151"

W, H = 580, 376  # a 6x4 landscape tile


def esc(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def rect(x, y, w, h, fill, rx=0):
    return f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{rx}" fill="{fill}"/>'


def outline(x, y, w, h, fill, t=2, rx=0, inner=PANEL):
    """An outline drawn as a filled ring (no strokes available)."""
    return (rect(x, y, w, h, fill, rx) +
            rect(x + t, y + t, w - 2 * t, h - 2 * t, inner, max(0, rx - 1)))


def stat(x, y, value, label, w=104, h=52, tone=TEXT):
    """A boxed working figure, as both reference terminals put in their corners."""
    s = outline(x, y, w, h, BORDER, 1, 3, inner="#FFFFFF")
    s += text(x + w / 2, y + 26, value, 19, tone, anchor="middle", weight="bold", mono=True)
    s += text(x + w / 2, y + 43, label, 10, MUTED, anchor="middle")
    return s


def readout(cx, cy, s, w=136, h=34, size=19):
    """The boxed number both reference terminals lay over the machine body."""
    out = outline(cx - w / 2, cy - h / 2, w, h, DARKSTEEL, 2, 3, inner="#FFFFFF")
    # baseline placed off the box centre rather than its top, so the digits sit optically centred
    out += text(cx, cy + size * 0.36, s, size, TEXT, anchor="middle", weight="bold", mono=True)
    return out


FONTS = {
    ("sans", False): "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ("sans", True): "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ("mono", False): "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    ("mono", True): "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
}

# Text is NOT emitted as SVG: this ImageMagick has no fontconfig link, so its internal renderer fails
# every <text> element outright ("unable to read font"). Shapes go through SVG, labels are composited
# afterwards with -annotate, which talks to freetype directly and works.
_labels = []


def text(x, y, s, size=13, fill=TEXT, anchor="start", weight="normal", mono=False):
    if s:
        _labels.append(dict(x=x, y=y, s=str(s), size=size, fill=fill, anchor=anchor,
                            font=FONTS[("mono" if mono else "sans", weight == "bold")]))
    return ""


def annotate_args():
    """-annotate arguments for the queued labels, in order."""
    args = []
    for L in _labels:
        # -annotate has no text-anchor, so shift by the measured advance width.
        gravity = "NorthWest"
        x = L["x"]
        if L["anchor"] in ("middle", "end"):
            adv = measure(L["s"], L["font"], L["size"])
            x -= adv / 2 if L["anchor"] == "middle" else adv
        args += ["-font", L["font"], "-pointsize", str(L["size"]), "-fill", L["fill"],
                 "-gravity", gravity, "-annotate", f"+{x:.0f}+{L['y']:.0f}", L["s"]]
    return args


_measure_cache = {}


def measure(s, font, size):
    key = (s, font, size)
    if key not in _measure_cache:
        out = subprocess.run(["magick", "-font", font, "-pointsize", str(size),
                              "label:" + s, "-format", "%w", "info:"],
                             capture_output=True, text=True)
        _measure_cache[key] = float(out.stdout.strip() or 0)
    return _measure_cache[key]


def circle(cx, cy, r, fill):
    return f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r:.1f}" fill="{fill}"/>'


def poly(pts, fill):
    p = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
    return f'<polygon points="{p}" fill="{fill}"/>'


# ---------------------------------------------------------------- machines
def wheels(cx, y, r=13, gap=26, n=2):
    s = ""
    start = cx - (gap * (n - 1)) / 2
    for i in range(n):
        s += circle(start + i * gap, y, r, DARKSTEEL) + circle(start + i * gap, y, r * 0.42, PANEL)
    return s


def drawbar(x, y, w):
    return poly([(x, y), (x + w, y - 5), (x + w, y + 3), (x, y + 6)], STEEL)


def slurry_tanker(cx, cy, fill_frac, external):
    """Side view: cylindrical barrel on a tandem axle, dribble bar behind."""
    bw, bh = 300, 104
    x, y = cx - bw / 2, cy - bh / 2
    s = drawbar(x - 74, y + bh - 18, 78)
    # barrel: rounded cylinder body
    s += rect(x, y, bw, bh, STEEL, rx=bh / 2)
    # Level fills the barrel bottom-up, the way the liquid actually sits in it — a left-to-right fill
    # reads as "how far along the tank" rather than "how much is in it".
    ih = (bh - 12) * fill_frac
    s += rect(x + 6, y + bh - 6 - ih, bw - 12, ih, ACCENT, rx=min(ih, (bh - 12) / 2))
    s += wheels(cx - 4, y + bh + 14, 25, 56, 2)
    # rear dome / pump
    s += circle(x + bw - 6, cy, 20, DARKSTEEL)
    # dribble bar hanging off the back
    bar_y = y + bh + 46
    s += rect(x + bw - 12, cy + 10, 18, bar_y - cy - 10, DARKSTEEL)
    s += rect(cx - 200, bar_y, 400, 9, DARKSTEEL, rx=4)
    for i in range(17):
        hx = cx - 192 + i * 24
        s += rect(hx, bar_y + 9, 5, 17, ACCENT if external else STEEL)
    return s


def manure_spreader(cx, cy, fill_frac):
    """Side view: box body, drawbar, vertical beaters and spreading discs at the rear."""
    bw, bh = 268, 108
    x, y = cx - bw / 2 - 24, cy - bh / 2
    s = drawbar(x - 72, y + bh - 14, 74)
    s += poly([(x, y + 12), (x + bw, y), (x + bw, y + bh), (x, y + bh)], STEEL)
    # Muck sits in the body as a heap, so the load fills bottom-up across the full floor.
    ih = (bh - 22) * fill_frac
    s += poly([(x + 7, y + bh - 7 - ih), (x + bw - 7, y + bh - 7 - ih - 10 * fill_frac),
               (x + bw - 7, y + bh - 7), (x + 7, y + bh - 7)], "#8B5E3C")
    s += wheels(cx - 26, y + bh + 14, 25, 56, 2)
    # vertical beater drums at the rear
    for off in (16, 42):
        s += rect(x + bw + off - 9, y - 10, 16, bh + 20, DARKSTEEL, rx=8)
    # spreading discs below them, seen edge-on
    for dx in (18, 44):
        s += circle(x + bw + dx, y + bh + 18, 20, GREEN) + circle(x + bw + dx, y + bh + 18, 7, PANEL)
    return s


def solid_spreader(cx, cy, fill_frac, throwing=False):
    """Rear view: hopper narrowing onto twin spinning discs, with the spread fan below."""
    hw, hh = 236, 96
    x, y = cx - hw / 2, cy - hh
    s = poly([(x, y), (x + hw, y), (x + hw - 44, y + hh), (x + 44, y + hh)], STEEL)
    ih = (hh - 14) * fill_frac
    iy = y + hh - 7 - ih
    # The hopper tapers, so the surface of the load narrows as it empties.
    inset = 8 + 34 * (1 - fill_frac)
    s += poly([(x + inset, iy), (x + hw - inset, iy), (x + hw - 40, y + hh - 7), (x + 40, y + hh - 7)], ACCENT)
    dy = y + hh + 30
    for dx in (-52, 52):
        # Vanes first, so the hub sits on top of them. The rotation is computed here rather than
        # written as transform="rotate(...)": this renderer drops transforms as silently as it drops
        # strokes, so anything rotated has to arrive as literal coordinates.
        for k in range(6):
            a = math.radians(k * 30)
            ca, sa = math.cos(a), math.sin(a)
            def rot(px, py):
                return (cx + dx + px * ca - py * sa, dy + px * sa + py * ca)
            s += poly([rot(-3, -30), rot(3, -30), rot(3, -3), rot(-3, -3)], GREEN)
        s += circle(cx + dx, dy, 13, DARKSTEEL) + circle(cx + dx, dy, 4.5, PANEL)
    if throwing:
        for k in range(9):
            fx = cx - 168 + k * 42
            s += poly([(fx, dy + 24), (fx + 13, dy + 24), (fx + 6.5, dy + 44)], "#7DD3FC")
    return s


def sprayer(cx, cy, fill_frac):
    """Front view: tank over the boom centre, Jaltest-style."""
    tw, th = 168, 108
    x, y = cx - tw / 2, cy - th
    s = poly([(x + 18, y), (x + tw - 18, y), (x + tw, y + th), (x, y + th)], STEEL)
    ih = (th - 14) * fill_frac
    # The tank tapers, so the liquid surface narrows with it.
    inset = 9 + 16 * (1 - fill_frac)
    s += poly([(x + inset, y + th - 7 - ih), (x + tw - inset, y + th - 7 - ih),
               (x + tw - 9, y + th - 7), (x + 9, y + th - 7)], ACCENT)
    return s


def boom(cx, y, sections, half=270):
    """Truss boom with one block per section; inactive sections read as outline only."""
    s = rect(cx - half, y, half * 2, 6, DARKSTEEL)
    # truss lattice, drawn as filled triangles
    for i in range(18):
        bx = cx - half + i * (half * 2 / 18)
        s += poly([(bx, y), (bx + half * 2 / 36, y - 11), (bx + half * 2 / 18, y)], STEEL)
    if not sections:
        return s
    n = len(sections)
    bw = (half * 2 - (n - 1) * 4) / n
    for i, sec in enumerate(sections):
        bx = cx - half + i * (bw + 4)
        if sec.get("active", True):
            s += rect(bx, y + 10, bw, 15, ACCENT, rx=2)
            s += poly([(bx + bw / 2 - 9, y + 29), (bx + bw / 2 + 9, y + 29), (bx + bw / 2, y + 44)], "#7DD3FC")
        else:
            s += outline(bx, y + 10, bw, 15, MUTED, 2, 2)
    return s


def seed_drill(cx, cy, fill_frac):
    """Side view: hopper on a frame with a row of disc coulters."""
    hw, hh = 176, 68
    x, y = cx - hw / 2, cy - hh - 4
    s = poly([(x, y), (x + hw, y), (x + hw - 26, y + hh), (x + 26, y + hh)], STEEL)
    ih = (hh - 12) * fill_frac
    s += poly([(x + 8 + 18 * (1 - fill_frac), y + hh - 6 - ih), (x + hw - 8 - 18 * (1 - fill_frac), y + hh - 6 - ih),
               (x + hw - 22, y + hh - 6), (x + 22, y + hh - 6)], ACCENT)
    s += rect(cx - 150, y + hh + 12, 300, 7, DARKSTEEL, rx=3)
    for i in range(11):
        dx = cx - 140 + i * 28
        s += rect(dx - 2, y + hh + 19, 5, 16, STEEL)
        s += circle(dx, y + hh + 40, 11, DARKSTEEL) + circle(dx, y + hh + 40, 3.5, PANEL)
    return s


def plow(cx, cy, side):
    """Rear view: bodies thrown to one side; the idle set is ghosted."""
    s = rect(cx - 146, cy - 44, 296, 8, DARKSTEEL, rx=4)
    s += drawbar(cx - 174, cy - 44, 30)
    for i in range(5):
        bx = cx - 116 + i * 58
        s += rect(bx - 3, cy - 36, 7, 30, STEEL)
        flip = -1 if side == "LEFT" else 1
        s += poly([(bx, cy - 8), (bx + flip * 30, cy + 6), (bx + flip * 26, cy + 26), (bx - flip * 2, cy + 14)], GREEN)
    return s


def cultivator(cx, cy, deep):
    """Side view: three ranks of tines behind a frame, then packer discs."""
    s = rect(cx - 150, cy - 34, 310, 9, DARKSTEEL, rx=4)
    s += drawbar(cx - 178, cy - 34, 30)
    depth = 40 if deep else 26
    for rank, off in enumerate((-70, 0, 70)):
        for i in range(6):
            tx = cx + off - 60 + i * 24
            if tx < cx - 156 or tx > cx + 150:
                continue
            s += poly([(tx - 3, cy - 25), (tx + 3, cy - 25), (tx + 7, cy - 25 + depth), (tx - 1, cy - 25 + depth)], STEEL)
            s += poly([(tx - 4, cy - 25 + depth), (tx + 11, cy - 25 + depth), (tx + 4, cy - 14 + depth)], GREEN)
    for i in range(7):
        s += circle(cx + 96 + 0, cy - 25 + depth + 6, 0, STEEL)
    for i in range(8):
        s += circle(cx - 150 + i * 43, cy - 25 + depth + 14, 13, DARKSTEEL) + circle(cx - 150 + i * 43, cy - 25 + depth + 14, 4, PANEL)
    return s


# ---------------------------------------------------------------- panel
def find(obj, key):
    """Depth-first search for the first object carrying `key`."""
    v = obj.get("vehicle", obj)
    if key in v:
        return v, v[key]
    def walk(node):
        for imp in node.get("implement", []) or []:
            if key in imp:
                return imp, imp[key]
            got = walk(imp)
            if got:
                return got
        return None
    return walk(v) or (None, None)


def level_for(owner, fill_type, root):
    """Fill level for `fill_type`, searched on the owner then anywhere in the rig."""
    def units(node):
        return ((node.get("fillUnits") or {}).get("fillUnit") or [])
    for node in ([owner] if owner else []):
        for u in units(node):
            if u.get("type") == fill_type:
                return u
    found = []
    def walk(node):
        for u in units(node):
            if u.get("type") == fill_type:
                found.append(u)
        for imp in node.get("implement", []) or []:
            walk(imp)
    walk(root.get("vehicle", {}))
    return found[0] if found else None


def working_hint(node):
    return any(a.get("processing") for a in (node.get("workAreas") or []))


def panel(capture, title=None):
    d = json.load(open(capture))
    v = d.get("vehicle", {})
    s = [rect(0, 0, W, H, PANEL)]

    owner, spraying = find(d, "spraying")
    _, sowing = find(d, "sowing")
    _, plow_a = find(d, "plow")
    _, tillage = find(d, "tillage")
    node = owner or v

    kind = (spraying or {}).get("kind")
    fill_type = (spraying or {}).get("fillType") or (sowing or {}).get("fillType")
    unit = level_for(node, fill_type, d) if fill_type else None
    frac = (unit.get("fillLevelPercentage", 0) / 100) if unit else 0.0

    # Which machine to DRAW. A combination machine carries several aspects at once, and the base
    # game's own `type` cannot be enumerated — so the silhouette is picked by priority over the
    # aspects present, most-defining first: what you call a fertilizing seed drill is a seed drill.
    # Every material it carries still gets a row in the footer, so nothing is hidden by the choice.
    if sowing:
        shape = "SEED_DRILL"
    elif plow_a:
        shape = "PLOUGH"
    elif tillage:
        shape = (tillage or {}).get("kind", "CULTIVATOR")
    else:
        shape = kind

    # header
    label = shape.replace("_", " ") if shape else "IMPLEMENT"
    # A machine that is two things says so, rather than silently dropping the other half.
    extra = []
    if sowing and spraying:
        extra.append((spraying.get("kind") or "").replace("_", " ").title())
    if (sowing or spraying) and tillage and tillage.get("kind") != shape:
        extra.append(tillage.get("kind", "").replace("_", " ").title())
    if extra:
        label += "  +  " + " + ".join(extra)
    s.append(rect(0, 0, W, 34, "#FFFFFF"))
    s.append(rect(0, 33, W, 1, BORDER))
    s.append(text(14, 23, label, 15, GREEN, weight="bold"))
    s.append(text(W - (108 if working_hint(node) else 14), 23, node.get("name", ""), 13, MUTED, anchor="end"))

    # A machine carrying two materials needs a taller footer, so the band is measured against it.
    n_rows = sum(1 for a in (sowing, spraying) if a and a.get("title"))
    foot = 92 if n_rows > 1 else 74

    # The machine sits between two columns of working figures, centred in what is left.
    cx, cy = W / 2, 34 + (H - foot - 34) / 2
    sections = ((node.get("workWidth") or {}).get("sections")) or []
    readout_at = cy  # each machine says where its number sits best on the body

    areas = node.get("workAreas") or []
    working = any(a.get("processing") for a in areas)

    # ---- working figures, laid out in the band's four corners like both reference terminals
    ww = node.get("workWidth") or {}
    width_m = ww.get("total")
    if width_m is None and areas:
        # No variable-width sections: the work areas still measure themselves.
        widths = [a.get("width") for a in areas if a.get("width")]
        width_m = max(widths) if widths else None

    left, right = [], []
    spd = v.get("speed") or {}
    left.append((f"{spd.get('value', 0):.1f}", spd.get("unit", "km/h")))
    rate = (spraying or {}).get("nominalUsagePerMin")
    if rate:
        left.append((f"{rate / 1000:.1f}" if rate >= 1000 else f"{rate:.0f}",
                     "m³/min max" if rate >= 1000 else "l/min max"))
    elif sowing and sowing.get("seedCount"):
        left.append((f"{sowing.get('seedIndex')}/{sowing.get('seedCount')}", "seed"))

    if width_m:
        right.append((f"{width_m:.1f}", f"{ww.get('unit', 'm')} width"))
    if sections:
        right.append((f"{ww.get('activeCount', len(sections))}/{len(sections)}", "sections"))
    elif plow_a and plow_a.get("side"):
        right.append((plow_a["side"][:1], "turned"))
    elif tillage:
        right.append(("DEEP" if tillage.get("deepMode") else "SHLW", "mode"))

    band_top = 44
    for i, (value, label) in enumerate(left[:2]):
        s.append(stat(12, band_top + i * 60, value, label))
    for i, (value, label) in enumerate(right[:2]):
        s.append(stat(W - 116, band_top + i * 60, value, label))

    # "Working" is a state rather than a figure, so it rides in the header as a lit pill — and out of
    # the band, where a wide boom would have run straight through it.
    if working:
        s.append(rect(W - 98, 9, 84, 18, ACCENT, rx=9))
        s.append(text(W - 56, 22, "WORKING", 11, "#FFFFFF", anchor="middle", weight="bold"))

    if shape == "SEED_DRILL":
        s.append(seed_drill(cx, cy - 14, frac))
        readout_at = cy - 62
    elif shape == "PLOUGH":
        s.append(plow(cx, cy, plow_a.get("side")))
        readout_at = None
    elif shape in ("CULTIVATOR", "POWER_HARROW", "SUBSOILER"):
        s.append(cultivator(cx, cy - 6, tillage.get("deepMode")))
        readout_at = None
    elif shape == "SLURRY_TANKER":
        s.append(slurry_tanker(cx, cy - 22, frac, bool((spraying or {}).get("externalSource"))))
        readout_at = cy - 22
    elif shape == "MANURE_SPREADER":
        s.append(manure_spreader(cx, cy - 18, frac))
        readout_at = cy - 24
    elif shape == "SOLID_FERTILIZER":
        s.append(solid_spreader(cx, cy - 4, frac, working))
        readout_at = cy - 76
    elif shape in ("LIQUID_FERTILIZER", "SPRAYER"):
        s.append(sprayer(cx, cy - 22, frac))
        s.append(boom(cx, cy + 8, sections))
        readout_at = cy - 74

    # the level readout, laid over the machine body the way both reference terminals do it
    if unit and readout_at is not None:
        s.append(readout(cx, readout_at, f"{unit.get('value', 0):,.0f} {unit.get('unit', '')}".replace(",", " ")))

    # Footer: one row per material the machine carries. A combination machine has two, and showing
    # only the one that picked the silhouette would hide half of what the operator has to watch.
    fy = H - foot
    s.append(rect(0, fy, W, 1, BORDER))

    rows = []
    if sowing and sowing.get("title"):
        rows.append((sowing["title"], level_for(node, sowing.get("fillType"), d),
                     f"seed {sowing.get('seedIndex')} of {sowing.get('seedCount')}"))
    if spraying and spraying.get("title"):
        note = "from towing vehicle" if spraying.get("externalSource") else (spraying.get("category") or "").lower()
        rows.append((spraying["title"], unit, note))

    if not rows:
        # No material: the machine's own state is the whole story.
        if plow_a and plow_a.get("side"):
            s.append(text(14, fy + 30, f"TURNED {plow_a['side']}", 17, GREEN, weight="bold"))
            s.append(text(14, fy + 52, "rotation locked" if not plow_a.get("canToggleRotation") else "rotation free",
                          11, MUTED))
        if tillage:
            s.append(text(14, fy + 30, "DEEP MODE" if tillage.get("deepMode") else "SHALLOW MODE", 17, GREEN, weight="bold"))
        if working:
            s.append(text(W - 14, fy + 30, "WORKING", 15, ACCENT, anchor="end", weight="bold"))
    else:
        top = fy + 12
        for i, (title, u, note) in enumerate(rows[:2]):
            ry = top + i * 30
            s.append(text(14, ry + 15, title.upper(), 13, TEXT, weight="bold"))
            if note:
                s.append(text(14 + measure(title.upper(), FONTS[("sans", True)], 13) + 10, ry + 15,
                              note, 11, AMBER if "towing" in note else MUTED))
            if u:
                pct = u.get("fillLevelPercentage", 0)
                s.append(rect(W - 232, ry + 4, 140, 13, TRACK, rx=6))
                s.append(rect(W - 232, ry + 4, 140 * pct / 100, 13, ACCENT if pct > 15 else RED, rx=6))
                s.append(text(W - 82, ry + 15, f"{pct:>3}%", 13, TEXT, weight="bold", mono=True))
        rate = (spraying or {}).get("nominalUsagePerMin")
        if rate and len(rows) < 2:
            s.append(text(W - 14, top + 48, f"{rate:,.0f} l/min at max speed".replace(",", " "), 11, MUTED, anchor="end"))

    body = "\n".join(s)
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}">{body}</svg>'


CASES = [
    ("seeding_cultivator", "precisionFarming/seedingCultivator.json"),
    ("vredo_injector", "precisionFarming/vredoLiquidManure_discHarrow.json"),
    ("slurry_tanker", "precisionFarming/liquidManure_dribbleBar.json"),
    ("manure_spreader", "precisionFarming/manureSpreader.json"),
    ("solid_fertilizer", "precisionFarming/fertilizerSpreader.json"),
    ("lime", "precisionFarming/fertilizerSpreader_lime.json"),
    ("sprayer", "precisionFarming/selfDrivingSprayer.json"),
    ("seed_drill", "precisionFarming/sowingMachine.json"),
    ("plow", "precisionFarming/plow_workingMode.json"),
    ("cultivator", "precisionFarming/deepCultivator.json"),
]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    made = []
    for name, rel in CASES:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            print("missing", rel); continue
        _labels.clear()
        svg = os.path.join(OUT, name + ".svg")
        png = os.path.join(OUT, name + ".png")
        open(svg, "w").write(panel(path))
        subprocess.run(["magick", svg, *annotate_args(), png], check=True)
        made.append(png)
    # contact sheet
    sheet = os.path.join(OUT, "_all.png")
    subprocess.run(["magick", "montage", "-label", "", *made, "-tile", "2x", "-geometry", "+8+8",
                    "-background", "#9CA3AF", sheet], check=True)
    print("\n".join(made)); print(sheet)
