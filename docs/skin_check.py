"""
Contrast and category-separation check for DriveTrace's instrument skins.

    python docs/skin_check.py            # full report, exit 1 if any floor is missed

DESIGN_SYSTEM.md section 3 states contrast floors for the text tokens and section 8 rule 4 says
the six category accents have to stay mutually distinguishable. Both are measurable claims, and
with more than one skin they have to be re-measured per skin rather than inherited from the one
they were originally tuned for. This is that measurement.

What it computes:

  - WCAG 2.x contrast ratio for every text and accent token against every surface it can land on.
  - CIEDE2000 between all 15 pairs of category accents (the pre-attentive channel Ware's ~8-item
    ceiling is about), and between every category accent and every status colour (rule 5's
    "status never doubles as a category" line, measured rather than asserted).
  - That every daylight twin is brighter than its standard twin and has not drifted in hue, which
    is rule 11's actual content.

The floors are the shipped Instrument skin's own worst case, not an abstract threshold. A new
skin has to be at least as separable as the one this design system was written around; anything
looser is a claim nobody checked. Near-neutral tokens (chalk, ash) have meaningless hue angles,
so the hue-drift line flags them and the ratio columns are what matter there.

Keep the palettes below in sync with ui/theme/Skin.kt. They are duplicated on purpose: this
script has to be runnable without an Android toolchain.
"""
import math, itertools, sys

def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def hexrgb(h):
    h = h.lstrip('#')
    if len(h) == 8:  # AARRGGBB
        h = h[2:]
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def lum(h):
    r, g, b = (srgb_to_lin(v) for v in hexrgb(h))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b

def contrast(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

def to_xyz(h):
    r, g, b = (srgb_to_lin(v) for v in hexrgb(h))
    x = r*0.4124564 + g*0.3575761 + b*0.1804375
    y = r*0.2126729 + g*0.7151522 + b*0.0721750
    z = r*0.0193339 + g*0.1191920 + b*0.9503041
    return x, y, z

def to_lab(h):
    xn, yn, zn = 0.95047, 1.00000, 1.08883
    x, y, z = to_xyz(h)
    def f(t):
        return t ** (1/3) if t > 0.008856 else (7.787 * t + 16/116)
    fx, fy, fz = f(x/xn), f(y/yn), f(z/zn)
    return 116*fy - 16, 500*(fx-fy), 200*(fy-fz)

def de2000(h1, h2):
    L1, a1, b1 = to_lab(h1); L2, a2, b2 = to_lab(h2)
    kL = kC = kH = 1.0
    C1 = math.hypot(a1, b1); C2 = math.hypot(a2, b2)
    Cb = (C1 + C2) / 2
    G = 0.5 * (1 - math.sqrt(Cb**7 / (Cb**7 + 25**7))) if Cb > 0 else 0
    a1p, a2p = (1+G)*a1, (1+G)*a2
    C1p, C2p = math.hypot(a1p, b1), math.hypot(a2p, b2)
    h1p = math.degrees(math.atan2(b1, a1p)) % 360 if (a1p or b1) else 0
    h2p = math.degrees(math.atan2(b2, a2p)) % 360 if (a2p or b2) else 0
    dLp = L2 - L1
    dCp = C2p - C1p
    if C1p*C2p == 0:
        dhp = 0
    else:
        d = h2p - h1p
        dhp = d - 360 if d > 180 else (d + 360 if d < -180 else d)
    dHp = 2*math.sqrt(C1p*C2p)*math.sin(math.radians(dhp)/2)
    Lbp = (L1+L2)/2; Cbp = (C1p+C2p)/2
    if C1p*C2p == 0:
        hbp = h1p + h2p
    else:
        s = h1p + h2p
        if abs(h1p-h2p) > 180:
            hbp = (s + 360)/2 if s < 360 else (s - 360)/2
        else:
            hbp = s/2
    T = (1 - 0.17*math.cos(math.radians(hbp-30)) + 0.24*math.cos(math.radians(2*hbp))
         + 0.32*math.cos(math.radians(3*hbp+6)) - 0.20*math.cos(math.radians(4*hbp-63)))
    dTh = 30*math.exp(-(((hbp-275)/25)**2))
    Rc = 2*math.sqrt(Cbp**7/(Cbp**7+25**7)) if Cbp > 0 else 0
    Sl = 1 + (0.015*(Lbp-50)**2)/math.sqrt(20+(Lbp-50)**2)
    Sc = 1 + 0.045*Cbp
    Sh = 1 + 0.015*Cbp*T
    Rt = -math.sin(math.radians(2*dTh))*Rc
    return math.sqrt((dLp/(kL*Sl))**2 + (dCp/(kC*Sc))**2 + (dHp/(kH*Sh))**2
                     + Rt*(dCp/(kC*Sc))*(dHp/(kH*Sh)))

def hue_angle(h):
    L, a, b = to_lab(h)
    return math.degrees(math.atan2(b, a)) % 360

def chroma(h):
    L, a, b = to_lab(h)
    return math.hypot(a, b)

def hue_gap(h1, h2):
    d = abs(hue_angle(h1) - hue_angle(h2)) % 360
    return min(d, 360 - d)


INSTRUMENT = dict(
    name="Instrument",
    ink="#06090E", panel="#0D131B", panelRaised="#141C26", panelActive="#1A2431",
    hairline="#202B39", hairlineBright="#33455A",
    chalk="#F2F6FA", mist="#9AA9BA", ash="#7C8B9E", slate="#4A5768",
    mixture="#2ED3C6", airpath="#8E7BFF", thermal="#5AC8FA", ignition="#FF66C4",
    live="#31C56A", caution="#FFC53D", fault="#FF4D4F",
    dChalk="#FFFFFF", dMixture="#7DF5E8", dAirpath="#C0B4FF",
    dThermal="#A8E4FF", dIgnition="#FFA8DC",
)

AMBER = dict(
    name="Amber",
    ink="#0A0805",
    panel="#15100A", panelRaised="#1F1810", panelActive="#2A2015",
    hairline="#3A2C1B", hairlineBright="#5C4629",
    chalk="#FFF3E0", mist="#C7AC84", ash="#A08A65", slate="#63543B",
    mixture="#1FD8CC", airpath="#A78BFF", thermal="#5CB8FF", ignition="#FF70C0",
    live="#34C765", caution="#FFA51F", fault="#FF5340",
    dChalk="#FFFFFF", dMixture="#79F2E9", dAirpath="#CDBFFF",
    dThermal="#AEDCFF", dIgnition="#FFB2DA",
)

SKINS = [INSTRUMENT, AMBER]

# Section 3's table, read as floors rather than as descriptions. `slate` is deliberately below
# body contrast (disabled should look disabled), so its floor only asserts it stays visible at
# all. Instrument's own `mist` measures 8.32 against the ~9:1 the table claims, so the floor is
# the table's number and Instrument is noted as the pre-existing exception rather than the bar
# being quietly lowered to match it.
TEXT_FLOORS = {"chalk": 18.0, "mist": 9.0, "ash": 5.4, "slate": 2.0}
TEXT_FLOOR_EXCEPTIONS = {("Instrument", "mist"): 8.3}


def report(sk, failures):
    print("=" * 72)
    print(sk["name"])
    print("=" * 72)
    print("-- text on ink / on panel / on panelRaised / on panelActive --")
    for t in ("chalk", "mist", "ash", "slate"):
        floor = TEXT_FLOOR_EXCEPTIONS.get((sk["name"], t), TEXT_FLOORS[t])
        if contrast(sk[t], sk["ink"]) < floor:
            failures.append(f"{sk['name']}: {t} is {contrast(sk[t], sk['ink']):.2f}:1 on ink, "
                            f"floor {floor}")
        print(f"  {t:<6} {sk[t]}  ink {contrast(sk[t], sk['ink']):5.2f}  "
              f"panel {contrast(sk[t], sk['panel']):5.2f}  "
              f"raised {contrast(sk[t], sk['panelRaised']):5.2f}  "
              f"active {contrast(sk[t], sk['panelActive']):5.2f}   "
              f"(floor {TEXT_FLOORS[t]})")
    print("-- accents / status on ink and on panelRaised --")
    for t in ("mixture", "airpath", "thermal", "ignition", "live", "caution", "fault"):
        print(f"  {t:<8} {sk[t]}  ink {contrast(sk[t], sk['ink']):5.2f}  "
              f"raised {contrast(sk[t], sk['panelRaised']):5.2f}  "
              f"L* {to_lab(sk[t])[0]:5.1f}  C* {chroma(sk[t]):5.1f}  h {hue_angle(sk[t]):5.1f}")
    print("-- hairlines on their grounds --")
    print(f"  hairline       {contrast(sk['hairline'], sk['panel']):5.2f} on panel, "
          f"{contrast(sk['hairline'], sk['ink']):5.2f} on ink")
    print(f"  hairlineBright {contrast(sk['hairlineBright'], sk['panel']):5.2f} on panel")
    print("-- daylight twins on ink --")
    for s, d in (("chalk", "dChalk"), ("mixture", "dMixture"), ("airpath", "dAirpath"),
                 ("thermal", "dThermal"), ("ignition", "dIgnition")):
        brighter = lum(sk[d]) > lum(sk[s])
        # Near-neutral tokens have an arbitrary hue angle, so only judge drift where there is
        # enough chroma for the angle to mean anything.
        chromatic = min(chroma(sk[s]), chroma(sk[d])) > 10
        ok = brighter and (hue_gap(sk[s], sk[d]) < 25 or not chromatic)
        if not brighter:
            failures.append(f"{sk['name']}: daylight {s} is not brighter than its standard twin")
        if chromatic and hue_gap(sk[s], sk[d]) >= 25:
            failures.append(f"{sk['name']}: daylight {s} drifted "
                            f"{hue_gap(sk[s], sk[d]):.0f} deg in hue")
        mark = "OK " if ok else "!! "
        note = "" if chromatic else "  (near-neutral, hue angle not meaningful)"
        print(f"  {mark}{s:<8} {sk[s]} -> {sk[d]}  contrast {contrast(sk[s], sk['ink']):5.2f}"
              f" -> {contrast(sk[d], sk['ink']):5.2f}   hue drift "
              f"{hue_gap(sk[s], sk[d]):4.1f}{note}")

    cats = {"MOTION": sk["chalk"], "MIXTURE": sk["mixture"], "AIRPATH": sk["airpath"],
            "THERMAL": sk["thermal"], "IGNITION": sk["ignition"], "HOUSEKEEPING": sk["ash"]}
    print("-- pairwise category separation (dE2000, hue gap deg) --")
    worst = (1e9, None)
    for (n1, c1), (n2, c2) in itertools.combinations(cats.items(), 2):
        d = de2000(c1, c2)
        g = hue_gap(c1, c2)
        if d < worst[0]:
            worst = (d, f"{n1}/{n2}")
        print(f"  {n1:<13}{n2:<13} dE {d:6.2f}   hue {g:5.1f}")
    print(f"  WORST CATEGORY PAIR: {worst[1]}  dE {worst[0]:.2f}")

    stats = {"LIVE": sk["live"], "CAUTION": sk["caution"], "FAULT": sk["fault"],
             "UNKNOWN": sk["slate"]}
    print("-- category vs status cross-channel separation --")
    worstx = (1e9, None)
    for n1, c1 in cats.items():
        for n2, c2 in stats.items():
            d = de2000(c1, c2)
            if d < worstx[0]:
                worstx = (d, f"{n1}/{n2}")
            print(f"  cat {n1:<13}status {n2:<8} dE {d:6.2f}")
    for n1, c1 in stats.items():
        for n2, c2 in stats.items():
            if n1 < n2:
                d = de2000(c1, c2)
                print(f"  status {n1:<8}{n2:<8} dE {d:6.2f}")
    print(f"  WORST CATEGORY-vs-STATUS PAIR: {worstx[1]}  dE {worstx[0]:.2f}")
    print()
    return worst, worstx


failures = []
results = [(sk["name"], *report(sk, failures)) for sk in SKINS]

baseline = next(r for r in results if r[0] == "Instrument")
print("BENCHMARK  (Instrument is the bar; every other skin has to clear it)")
for name, cat, cross in results:
    print(f"  {name:<12} category floor {cat[0]:6.2f} ({cat[1]:<22}) "
          f"cross-channel floor {cross[0]:6.2f} ({cross[1]})")
    if name == "Instrument":
        continue
    if cat[0] < baseline[1][0]:
        failures.append(f"{name}: category floor {cat[0]:.2f} is below Instrument's "
                        f"{baseline[1][0]:.2f}")
    if cross[0] < baseline[2][0]:
        failures.append(f"{name}: category-vs-status floor {cross[0]:.2f} is below "
                        f"Instrument's {baseline[2][0]:.2f}")

print()
if failures:
    print("FAILED")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print("PASS: every skin meets section 3's contrast floors and is at least as separable as "
      "Instrument.")
