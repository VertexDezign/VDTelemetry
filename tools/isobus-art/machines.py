"""The rest of the ISOBUS machine set.

Side-on facing left, hitch at the left edge, wheels on the ground line at y=880 —
except the solid fertilizer spreader and the sprayer, which are drawn from directly
behind, because that is the only view where twin spinning discs and a boom mean
anything. Working-width sections are never baked in: the app draws those as a
plan-view strip below the machine.

Every vessel keeps a generous margin of bodywork around its cavity. The fill layer
covers the cavity exactly, so anything inside it vanishes at 100% — the machine has
to still read as that machine with a full tank.
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kit import *  # noqa


def drawbar(d, ytop, xend=380):
    """The pulled-implement front: tapering bar out to a hitch eye at the left edge."""
    d.poly([(170, ytop + 8), (xend - 12, ytop - 36), (xend, ytop + 8), (182, ytop + 52)], FRAME)
    hitch(d, 146, ytop + 30, 30)


def headstock(d, ytop=470, ybot=620):
    """The mounted-implement front: a three-point headstock at the left edge."""
    d.rr(110, ytop, 250, ybot, 12, FRAME, OUTLINE, MAIN)
    d.circ(180, ytop + 22, 20, EMPTY, OUTLINE, MED)
    d.rr(96, ybot - 40, 130, ybot + 4, 8, FRAME, OUTLINE, MED)


def depth_wheel(d, x, r, legx, legtop):
    """A depth/transport wheel on an L-shaped arm — an unattached wheel reads as debris."""
    d.rr(legx, legtop, legx + 42, GROUND - r + 8, 14, FRAME, OUTLINE, MED)
    d.rr(legx + 16, GROUND - r - 24, x + 6, GROUND - r + 20, 14, FRAME, OUTLINE, MED)
    tyre(d, x, GROUND - r, r)


# ---------------------------------------------------------------- manure spreader
MS_CAV = "M 396,472 L 1116,472 L 1116,624 L 396,624 Z"


def manure_spreader(layer):
    d = Draw(layer)
    d.rr(330, 668, 1180, 712, 12, FRAME, OUTLINE, MED)           # chassis rail
    tyre(d, 636, 774, 106)
    tyre(d, 892, 774, 106)
    jack(d, 402, 680, 848)
    drawbar(d, 592, 372)

    d.rr(330, 412, 1180, 700, 10, BODY, OUTLINE, MAIN)           # body shell
    d.cavity(MS_CAV, MUCK)
    d.path(MS_CAV, None, OUTLINE, FINE)
    d.rr(314, 386, 1196, 436, 10, BODY_D, OUTLINE, MED)          # top rail
    for rx in (500, 700, 900, 1100):                              # side ribs
        d.rr(rx - 13, 636, rx + 13, 698, 4, BODY_D, OUTLINE, FINE)

    d.rr(1156, 716, 1424, 770, 14, FRAME, OUTLINE, MED)          # spreader shroud
    for dx in (1230, 1338):                                       # vertical beaters
        d.rr(dx - 40, 356, dx + 40, 724, 40, FRAME, OUTLINE, MAIN)
        for j in range(5):
            d.rot(dx, 400 + j * 70, 26,
                  lambda dd: dd.rr(-34, -9, 34, 9, 5, BODY_D, OUTLINE, FINE))
    for dx in (1214, 1364):                                       # spreading discs
        d.rr(dx - 11, 764, dx + 11, 818, 6, FRAME, OUTLINE, FINE)
        spinner(d, dx, 846, 78)
    return d


# ---------------------------------------------------------------- solid fertilizer (rear view)
SF_CAV = "M 444,414 L 1156,414 L 984,646 L 616,646 Z"


def solid_fertilizer(layer):
    """Rear view: hopper narrowing onto twin spinning discs."""
    d = Draw(layer)
    d.rr(730, 272, 870, 362, 12, FRAME, OUTLINE, MAIN)           # headstock
    d.circ(800, 290, 19, EMPTY, OUTLINE, MED)

    d.poly([(380, 362), (1220, 362), (1010, 692), (590, 692)], BODY, OUTLINE, MAIN)
    d.cavity(SF_CAV, GRANULE)
    d.path(SF_CAV, None, OUTLINE, FINE)
    d.rr(358, 334, 1242, 388, 12, BODY_D, OUTLINE, MED)          # top rim

    d.rr(596, 680, 1004, 742, 10, FRAME, OUTLINE, MED)           # metering box
    for ox in (676, 924):
        d.rr(ox - 50, 740, ox + 50, 774, 8, BODY_D, OUTLINE, FINE)  # outlet
        d.rr(ox - 13, 768, ox + 13, 812, 6, FRAME, OUTLINE, FINE)   # disc shaft

    for cx in (676, 924):                                         # twin spinner discs
        spinner(d, cx, 846, 100)
    return d


# ---------------------------------------------------------------- sprayer (rear view)
SP_SHELL = ("M 694,328 L 906,328 Q 946,328 954,374 L 1022,606 Q 1040,672 976,672 "
            "L 624,672 Q 560,672 578,606 L 646,374 Q 654,328 694,328 Z")
SP_CAV = ("M 722,390 L 878,390 Q 898,390 903,420 L 946,582 Q 954,616 924,616 "
          "L 676,616 Q 646,616 654,582 L 697,420 Q 702,390 722,390 Z")


def sprayer(layer):
    """Rear view: tank over the boom centre, boom truss included. No running gear —
    from behind, a boom-mounted tank shows no wheels, and inventing them read as
    wrong."""
    d = Draw(layer)
    # boom: a real truss — top chord, tubular bottom bar, diagonals between them
    d.rr(124, 632, 1476, 660, 14, FRAME, OUTLINE, MED)
    zig = "M 138,720"
    for i in range(22):
        x = 138 + i * 61
        zig += f" L {x + 30},660 L {x + 61},720"
    d.path(zig, None, FRAME, MED)
    d.rr(96, 718, 1504, 760, 21, FRAME, OUTLINE, MAIN)           # boom bar
    for i in range(14):                                           # nozzle bodies
        x = 152 + i * 100
        d.rr(x - 9, 758, x + 9, 788, 4, FRAME, OUTLINE, FINE)
    for ex in (114, 1486):                                        # end plates
        d.rr(ex - 16, 620, ex + 16, 796, 10, FRAME, OUTLINE, MED)
    d.rr(742, 616, 858, 788, 14, BODY_D, OUTLINE, MAIN)          # centre hinge

    d.path(SP_SHELL, BODY, OUTLINE, MAIN)                        # tank
    d.cavity(SP_CAV, LIQUID)
    d.path(SP_CAV, None, OUTLINE, FINE)
    d.rr(742, 296, 858, 336, 16, BODY_D, OUTLINE, MED)           # lid
    for sx in (648, 952):                                        # moulded ribs
        d.rr(sx - 20, 450, sx + 20, 620, 12, BODY_D, OUTLINE, FINE)
    d.rr(762, 664, 838, 704, 10, FRAME, OUTLINE, MED)            # sump
    return d


# ---------------------------------------------------------------- seed drill
SD_CAV = "M 496,384 L 1116,384 L 1054,558 L 556,558 Z"


def seed_drill(layer):
    d = Draw(layer)
    drawbar(d, 560, 546)
    d.poly([(430, 332), (1180, 332), (1094, 602), (516, 602)], BODY, OUTLINE, MAIN)
    d.cavity(SD_CAV, SEED)
    d.path(SD_CAV, None, OUTLINE, FINE)
    d.rr(410, 306, 1200, 358, 12, BODY_D, OUTLINE, MED)          # hopper rim

    for mx in (620, 800, 980):                                    # metering rollers
        d.circ(mx, 630, 26, FRAME, OUTLINE, MED)
    d.rr(556, 600, 604, 672, 8, FRAME, OUTLINE, MED)             # frame legs
    d.rr(996, 600, 1044, 672, 8, FRAME, OUTLINE, MED)
    d.rr(150, 662, 1450, 710, 22, FRAME, OUTLINE, MAIN)          # toolbar

    for i in range(7):                                            # disc coulters
        x = 260 + i * 190
        d.poly([(x - 13, 706), (x + 13, 706), (x + 26, 806), (x, 806)], FRAME)
    for i in range(7):
        disc(d, 260 + i * 190 + 13, 826, 54)
    return d


# ---------------------------------------------------------------- plough
def plough(side):
    """`side` is -1 for bodies turned left, +1 for right."""
    d = Draw("body")
    headstock(d, 452, 606)
    d.rr(240, 496, 1300, 560, 24, BODY, OUTLINE, MAIN)           # main beam
    for i in range(5):
        x = 372 + i * 214
        d.poly([(x - 19, 556), (x + 19, 556), (x + 27, 700), (x - 5, 700)], FRAME)
        s = side
        d.path(f"M {x - 8},688 C {x + s * 40},700 {x + s * 100},738 {x + s * 112},798 "
               f"L {x + s * 52},876 C {x + s * 44},800 {x + s * 10},750 {x - 8},738 Z",
               STEEL, OUTLINE, MAIN)
    depth_wheel(d, 1450, 76, 1284, 528)
    return d


# ---------------------------------------------------------------- tillage
def cultivator():
    d = Draw("body")
    headstock(d, 448, 600)
    d.rr(230, 494, 1160, 556, 24, BODY, OUTLINE, MAIN)           # frame beam
    for i in range(7):                                            # sprung S-tines
        x = 296 + i * 106
        d.poly([(x - 14, 548), (x + 18, 548), (x + 2, 790), (x - 26, 790)], FRAME)
        d.poly([(x - 44, 782), (x + 14, 782), (x - 16, 876)], STEEL)
    d.rr(1122, 528, 1186, 796, 18, FRAME, OUTLINE, MED)          # packer arm
    d.rr(1044, 800, 1512, 842, 21, FRAME, OUTLINE, MED)          # packer axle
    for i in range(4):
        disc(d, 1100 + i * 120, 822, 58)
    return d


def power_harrow():
    d = Draw("body")
    headstock(d, 470, 622)
    d.rr(252, 588, 1256, 700, 16, BODY, OUTLINE, MAIN)           # rotor housing
    d.rr(240, 550, 1268, 600, 12, BODY_D, OUTLINE, MED)          # top deck
    d.rr(556, 470, 724, 556, 12, FRAME, OUTLINE, MED)            # gearbox
    for i in range(8):                                            # tine pairs
        x = 302 + i * 122
        d.rr(x - 10, 696, x + 10, 876, 6, FRAME, OUTLINE, MED)
        d.rr(x + 26, 696, x + 46, 876, 6, FRAME, OUTLINE, MED)
    d.rr(1240, 628, 1330, 700, 14, FRAME, OUTLINE, MED)          # roller arm
    d.circ(1382, 790, 90, FRAME, OUTLINE, MAIN)                  # packer roller
    d.circ(1382, 790, 30, STEEL, OUTLINE, MED)
    return d


def subsoiler():
    d = Draw("body")
    headstock(d, 416, 566)
    d.rr(220, 460, 1250, 528, 24, BODY, OUTLINE, MAIN)           # heavy beam
    for i in range(5):                                            # deep legs
        x = 330 + i * 208
        d.poly([(x - 21, 524), (x + 21, 524), (x + 35, 838), (x - 7, 838)], FRAME)
        d.poly([(x - 25, 830), (x + 46, 830), (x + 10, 882)], STEEL)
    depth_wheel(d, 1440, 76, 1276, 492)
    return d


if __name__ == "__main__":
    write("isobus_manure_spreader_body", manure_spreader("body"))
    write("isobus_manure_spreader_fill", manure_spreader("fill"))
    write("isobus_solid_fertilizer_body", solid_fertilizer("body"))
    write("isobus_solid_fertilizer_fill", solid_fertilizer("fill"))
    write("isobus_sprayer_body", sprayer("body"))
    write("isobus_sprayer_fill", sprayer("fill"))
    write("isobus_seed_drill_body", seed_drill("body"))
    write("isobus_seed_drill_fill", seed_drill("fill"))
    write("isobus_plough_left", plough(-1))
    write("isobus_plough_right", plough(+1))
    write("isobus_cultivator", cultivator())
    write("isobus_power_harrow", power_harrow())
    write("isobus_subsoiler", subsoiler())
    print("wrote", OUT)
