"""Slurry tanker + its rear attachments, each a separate layer.

The tanker draws alone. The attachment is a separate image on the same canvas, so
the app overlays it with no positioning maths — the same trick that keeps the fill
layer registered. Which attachment (if any) comes from the telemetry: the child
implement with `spraying.externalSource == true` is the attachment, and whether it
also carries `tillage` tells a disc injector from a dribble bar. No such child
means the bar is built into the tanker and invisible to us, so nothing is drawn.

The working-width sections are NOT here — the app draws those as a plan-view strip
below the machine, from live data.
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kit import *  # noqa

# Barrel shell, dished ends. Front (hitch) to the left, wheels on the ground line.
SHELL = ("M 400,368 L 1164,368 C 1214,368 1246,420 1246,528 "
         "C 1246,636 1214,688 1164,688 L 400,688 "
         "C 350,688 318,636 318,528 C 318,420 350,368 400,368 Z")
# The tank window: the barrel minus a walkway spine on top and a skirt below, so the
# machine still reads as a machine at 100%, when the fill covers everything inside it.
CAV = ("M 452,416 L 1120,416 C 1150,416 1166,454 1166,528 "
       "C 1166,602 1150,640 1120,640 L 452,640 "
       "C 422,640 404,602 404,528 C 404,454 422,416 452,416 Z")


def tanker(layer):
    d = Draw(layer)

    # --- running gear
    d.rr(500, 674, 1180, 716, 12, FRAME, OUTLINE, MED)           # chassis rail
    tyre(d, 690, 774, 106)
    tyre(d, 946, 774, 106)
    jack(d, 442, 684, 848)

    # --- drawbar and hitch, at the left edge
    d.poly([(170, 620), (368, 576), (380, 620), (182, 664)], FRAME)
    hitch(d, 146, 642, 30)
    d.rr(228, 588, 268, 666, 8, FRAME, OUTLINE, FINE)            # hydraulic ram
    d.rr(258, 412, 274, 696, 6, FRAME, OUTLINE, FINE)            # ladder
    d.rr(298, 412, 314, 696, 6, FRAME, OUTLINE, FINE)
    for k in range(5):
        d.rr(256, 440 + k * 62, 316, 456 + k * 62, 5, FRAME, OUTLINE, FINE)

    # --- barrel
    d.path(SHELL, BODY, OUTLINE, MAIN)
    d.cavity(CAV, SLURRY)
    d.path(CAV, None, OUTLINE, FINE)
    # dished ends, as seams rather than added shapes
    d.path("M 1176,374 C 1214,430 1214,626 1176,682", None, OUTLINE, MED)
    d.path("M 388,374 C 350,430 350,626 388,682", None, OUTLINE, MED)
    # reinforcing hoops, showing above and below the tank window
    for hx in (560, 780, 1000):
        d.rr(hx - 13, 370, hx + 13, 418, 4, BODY_D, OUTLINE, FINE)
        d.rr(hx - 13, 638, hx + 13, 686, 4, BODY_D, OUTLINE, FINE)

    # --- top hardware
    d.rr(404, 304, 588, 372, 30, BODY_D, OUTLINE, MED)           # vacuum pump
    d.rr(556, 250, 592, 310, 14, FRAME, OUTLINE, FINE)           # exhaust stack
    d.rr(694, 328, 834, 372, 14, BODY_D, OUTLINE, MED)           # manhole
    d.circ(764, 338, 13, FRAME, OUTLINE, FINE)
    return d


SHOES = [1288 + i * 36 for i in range(7)]


def dribble_bar(layer="body"):
    """Side-on the bar is edge-on, so what has to read is the distributor drum, the
    hose bundle dropping out of it, and the trailing shoes on the ground."""
    d = Draw(layer)
    d.rr(1230, 586, 1330, 634, 10, FRAME, OUTLINE, MED)          # arm off the barrel
    d.rr(1298, 470, 1350, 700, 12, FRAME, OUTLINE, MED)          # mast
    d.rr(1272, 400, 1498, 500, 46, BODY_D, OUTLINE, MAIN)        # distributor drum
    for x in SHOES:                                               # outlet nipples
        d.rr(x - 8, 496, x + 8, 524, 5, FRAME, OUTLINE, FINE)
    for x in SHOES:                                               # hose bundle
        d.path(f"M {x},518 C {x - 8},600 {x + 8},664 {x},740", None, FRAME, MED)
    d.rr(1258, 736, 1526, 776, 16, FRAME, OUTLINE, MAIN)         # shoe rail, edge-on
    for x in SHOES:
        d.rr(x - 6, 776, x + 6, 818, 4, FRAME, OUTLINE, FINE)
        d.path(f"M {x - 12},812 C {x - 12},848 {x + 2},872 {x + 20},878 "
               f"L {x + 20},880 L {x - 14},880 Z", STEEL, OUTLINE, FINE)
    return d


def injector(layer="body"):
    """Side-on: a short toolbar of disc coulters that cut the slurry into the soil."""
    d = Draw(layer)
    d.rr(1230, 586, 1330, 634, 10, FRAME, OUTLINE, MED)          # arm off the barrel
    d.rr(1266, 548, 1530, 608, 14, FRAME, OUTLINE, MAIN)         # toolbar
    for i in range(3):
        x = 1322 + i * 92
        d.rr(x + 17, 600, x + 31, 706, 5, FRAME, OUTLINE, FINE)  # feed pipe
        d.rr(x - 14, 608, x + 14, 790, 7, FRAME, OUTLINE, MED)   # leg
    for i in range(3):
        disc(d, 1322 + i * 92, 822, 56)
    return d


if __name__ == "__main__":
    write("isobus_slurry_tanker_body", tanker("body"))
    write("isobus_slurry_tanker_fill", tanker("fill"))
    write("isobus_att_dribble_bar", dribble_bar())
    write("isobus_att_injector", injector())
    print("wrote", OUT)
