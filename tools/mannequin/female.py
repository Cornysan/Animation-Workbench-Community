"""Weibliche Variante des AW-Mannequins.

Dasselbe Skelett und dieselben Knochen, nur die Haut aendert ihre Form. Damit
bleibt alles, was am Mannequin haengt (BONE_MAP, Ruhepose, FBX-Export),
ohne Aenderung gueltig.

Eine Brust gibt es nur mit BUST > 0. Dann wird der Brustmuskel des Originals
geglaettet und der Brustkorb feiner unterteilt: das Original traegt dort zwei
Eckenreihen, und auf zwei Reihen laesst sich keine runde Unterseite legen.
Mit BUST = 0 bleibt die Brust, wie sie ist, und das Netz behaelt alle Ecken
und Dreiecke des Originals.

Gerechnet wird im Mesh-Raum der Datei: x = seitlich (L negativ),
y = Tiefe (vorn negativ), z = senkrecht (oben negativ). Hier heisst h = -z.

    python tools/mannequin/female.py src/main/resources/static/models/aw-mannequin.glb \
                                     src/main/resources/static/models/aw-mannequin-f.glb
"""
import sys
import numpy as np
from glbio import Glb, repack

SRC, DST = sys.argv[1], sys.argv[2]
g = Glb(SRC)
mesh = g.j['meshes'][0]
attrs = mesh['primitives'][0]['attributes']
POS, NRM, UV, JNT, WGT = (attrs[k] for k in ('POSITION', 'NORMAL', 'TEXCOORD_0', 'JOINTS_0', 'WEIGHTS_0'))
for p in mesh['primitives']:
    assert p['attributes'] == attrs
IDX = [p['indices'] for p in mesh['primitives']]
names = [g.j['nodes'][n]['name'].replace('DEF-', '') for n in g.j['skins'][0]['joints']]

# Wie weit die Brust vor dem Brustkorb steht, in Mesh-Einheiten (~0.93 m).
# 0 = keine, Brustkorb wie beim Original; 0.036 war die kleine Fassung,
# 0.054 die erste, 0.07 stand einen Abend lang drin. Pablo, 2026-09-28:
# wieder weg - auf dem groben Netz wird sie nicht rund genug.
BUST = 0.0
BUST_X = 0.074      # Mitte jeder Seite, seitlich
BUST_R = 0.078      # Breite jeder Seite
BUST_LOWER = 1.5    # Unterseite: kleiner = runder, mit schaerferer Falte
BUST_UNDER = 0.06   # wie weit der Brustkorb darunter zuruecktritt

# Ohne Mass, alles Faktoren auf das Original
HEAD_SCALE = 0.93   # Kopf um den Halsansatz
NECK = 0.10         # Hals schlanker um seine Achse
SHOULDER_SLOPE = 0.06
SHOULDER = 0.14     # Kappe und Pfanne zum Drehpunkt, siehe unten
SHOULDER_ROUND = 40     # Glaettschritte um den Drehpunkt, siehe unten
SHOULDER_ROUND_R = 0.20 # bis wohin, vom Drehpunkt aus
BACK = 0.86         # Tiefe des Brustkorbs, Mitte bis oben
ARM_SLIM = 1.2      # Verstaerkung der Armkurve

# Glaetten: Brustkorb nach dem Verfeinern, und das Feld "vorn" (siehe dort)
CHEST_SMOOTH = 40
FRONT_SMOOTH = 15


def smoothstep(a, b, t):
    s = np.clip((t - a) / (b - a), 0, 1)
    return s * s * (3 - 2 * s)


def bump(u):
    """Glatter, kompakter Huegel: 1 in der Mitte, 0 ab u = 1."""
    u = np.clip(u, 0, 1)
    return (1 - u * u) ** 2


def curve(keys, t, smooth=0.02):
    """Stueckweise lineare Kurve, mit einer Gaussglocke geglaettet - kein Knick,
    der sich als Falte in die Flaeche legt."""
    ks = np.array(keys, dtype=float)
    grid = np.linspace(ks[0, 0] - 0.5, ks[-1, 0] + 0.5, 4000)
    v = np.interp(grid, ks[:, 0], ks[:, 1])
    step = grid[1] - grid[0]
    r = int(3 * smooth / step)
    k = np.exp(-0.5 * (np.arange(-r, r + 1) * step / smooth) ** 2)
    v = np.convolve(np.pad(v, r, mode='edge'), k / k.sum(), mode='valid')
    return np.interp(t, grid, v)


def weld(P):
    """Ecken an derselben Stelle (UV-Naht, Materialgrenze) als eine."""
    key = np.round(P * 20000).astype(np.int64)
    _, first, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
    return first, inv.ravel()


class Skin:
    """Die Haut als Arrays: Ecken mit allem, was an ihnen haengt, und die
    Dreiecke je Material."""

    def __init__(self):
        self.P = g.acc(POS) / 32767.0          # Form, die wir verformen
        self.P0 = self.P.copy()                # Original, fuer die Normalen
        self.N = g.acc(NRM) / 32767.0
        self.UV = g.acc(UV).astype(float)
        self.J = g.acc(JNT).astype(int)
        self.W = g.acc(WGT) / 255.0
        self.T = [g.acc(i).reshape(-1, 3).astype(int) for i in IDX]

    @property
    def tris(self):
        return np.vstack(self.T)

    def subdivide(self, select):
        """Rot-gruene Verfeinerung: ausgewaehlte Dreiecke in vier, Nachbarn so
        weit mit, dass kein T-Stoss (und damit kein Riss) entsteht. Kanten
        zaehlen nach Lage, nicht nach Index - an einer UV-Naht liegen zwei
        Ecken aufeinander, und beide Seiten muessen dieselbe Kante teilen."""
        _, wid = weld(self.P)
        key = lambda a, b: (min(wid[a], wid[b]), max(wid[a], wid[b]))
        split = set()
        for pi, T in enumerate(self.T):
            for t in T[select(self.P[T].mean(1), pi, T)]:
                split.update(key(t[i], t[(i + 1) % 3]) for i in range(3))
        changed = True
        while changed:
            changed = False
            for T in self.T:
                for t in T:
                    ks = [key(t[i], t[(i + 1) % 3]) for i in range(3)]
                    if sum(k in split for k in ks) == 2:
                        split.update(ks)
                        changed = True

        new = {k: [] for k in ('P', 'P0', 'N', 'UV', 'J', 'W')}
        mids = {}
        n0 = len(self.P)

        def mid(a, b):
            k = (min(a, b), max(a, b))
            if k not in mids:
                mids[k] = n0 + len(mids)
                for name in ('P', 'P0', 'UV'):
                    arr = getattr(self, name)
                    new[name].append((arr[a] + arr[b]) / 2)
                nn = self.N[a] + self.N[b]
                new['N'].append(nn / max(np.linalg.norm(nn), 1e-12))
                w = {}
                for v in (a, b):
                    for j, ww in zip(self.J[v], self.W[v]):
                        if ww > 0:
                            w[j] = w.get(j, 0) + ww / 2
                top = sorted(w.items(), key=lambda kv: -kv[1])[:4]
                top += [(0, 0.0)] * (4 - len(top))
                s = sum(ww for _, ww in top)
                new['J'].append([j for j, _ in top])
                new['W'].append([ww / s for _, ww in top])
            return mids[k]

        out = []
        for T in self.T:
            res = []
            for a, b, c in T:
                s = [key(a, b) in split, key(b, c) in split, key(c, a) in split]
                n = sum(s)
                if n == 0:
                    res.append((a, b, c))
                elif n == 3:
                    ab, bc, ca = mid(a, b), mid(b, c), mid(c, a)
                    res += [(a, ab, ca), (ab, b, bc), (ca, bc, c), (ab, bc, ca)]
                else:
                    while not s[0]:
                        a, b, c = b, c, a
                        s = s[1:] + s[:1]
                    ab = mid(a, b)
                    res += [(a, ab, c), (ab, b, c)]
            out.append(np.array(res, dtype=int))
        self.T = out
        for name, rows in new.items():
            if rows:
                setattr(self, name, np.vstack([getattr(self, name), np.array(rows)]))
        return len(mids)


def pieces(s):
    """Welches Schalenteil (Kopf+Brustkorb, Taille, Becken, ...) eine Ecke
    traegt; Ringe -1. Die Teile ueberlappen sich in der Hoehe - das
    Taillenstueck reicht innen bis weit in den Brustkorb -, deshalb geht es
    nach Zusammenhang und nicht nach Lage."""
    _, wid = weld(s.P)
    par = np.arange(wid.max() + 1)

    def f(a):
        while par[a] != a:
            par[a] = par[par[a]]
            a = par[a]
        return a
    for t in s.T[0]:
        a, b, c = f(wid[t[0]]), f(wid[t[1]]), f(wid[t[2]])
        par[a] = b
        par[f(c)] = f(b)
    out = np.full(len(s.P), -1)
    used = np.unique(s.T[0])
    out[used] = [f(wid[v]) for v in used]
    return out


def taubin(P, tris, mask, iters, lam=0.5, mu=-0.53):
    """Glaetten ohne zu schrumpfen, auf der verschweissten Flaeche."""
    first, inv = weld(P)
    Q = P[first].copy()
    m = np.zeros(len(Q))
    np.maximum.at(m, inv, mask)
    e = inv[np.vstack([tris[:, [0, 1]], tris[:, [1, 2]], tris[:, [2, 0]]])]
    e = np.vstack([e, e[:, ::-1]])
    e = e[e[:, 0] != e[:, 1]]
    deg = np.bincount(e[:, 0], minlength=len(Q)).astype(float)
    for k in range(iters * 2):
        acc = np.zeros_like(Q)
        np.add.at(acc, e[:, 0], Q[e[:, 1]])
        avg = np.where(deg[:, None] > 0, acc / np.maximum(deg, 1)[:, None], Q)
        Q += ((lam if k % 2 == 0 else mu) * m)[:, None] * (avg - Q)
    return Q[inv]


ARM = lambda n: n.split('.')[0] in ('upper_arm', 'forearm', 'hand') or n.startswith(('f_', 'thumb'))
LEG = lambda n: n.split('.')[0] in ('thigh', 'shin', 'foot', 'toe')
HAND = lambda n: n.split('.')[0] == 'hand' or n.startswith(('f_', 'thumb'))


def masks(s):
    def group(pred):
        ids = np.array([pred(n) for n in names])
        return (s.W * ids[s.J]).sum(1)
    wa, wl, wh = group(ARM), group(LEG), group(HAND)
    ring = np.zeros(len(s.P), bool)
    ring[np.unique(s.T[1])] = True
    return wa, wl, wh, np.clip(1 - wa - wl, 0, 1), ring


def chest_mask(P, wt, is_chest):
    x, y, h = P[:, 0], P[:, 1], -P[:, 2]
    return (smoothstep(0.31, 0.35, h) * smoothstep(0.60, 0.52, h)
            * smoothstep(0.235, 0.18, np.abs(x)) * smoothstep(0.03, -0.03, y) * wt * is_chest)


skin = Skin()


def chest_piece(s):
    piece = pieces(s)
    shell = piece >= 0
    top = np.where(shell)[0][np.argmin(s.P[shell, 2])]   # hoechste Ecke: der Kopf
    return piece == piece[top]

# 1. Brustmuskel weg: die Kante darunter und die Kerbe in der Mitte gehoeren
#    zum Mann. Auf dem groben Netz, da reicht ein Bruchteil der Schritte.
wa, wl, wh, wt, ring = masks(skin)
is_chest = chest_piece(skin)
if BUST > 0:
    skin.P = taubin(skin.P, skin.tris, chest_mask(skin.P, wt, is_chest), iters=70)


# 2. Verfeinern: einmal den ganzen Brustkorb vorn, dann noch einmal die Brust.
def sel_chest(c, prim, T):
    x, y, h = c[:, 0], c[:, 1], -c[:, 2]
    return (prim == 0) & chest_piece(skin)[T[:, 0]] & (h > 0.29) & (h < 0.58) & (np.abs(x) < 0.23) & (y < 0.03)


def sel_bust(c, prim, T):
    x, y, h = c[:, 0], c[:, 1], -c[:, 2]
    u = np.minimum(((x - 0.074) / 0.10) ** 2, ((x + 0.074) / 0.10) ** 2) + ((h - 0.40) / 0.10) ** 2
    return (prim == 0) & chest_piece(skin)[T[:, 0]] & (u < 1) & (y < -0.02)


if BUST > 0:
    added = skin.subdivide(sel_chest) + skin.subdivide(sel_bust)
    wa, wl, wh, wt, ring = masks(skin)
    is_chest = chest_piece(skin)
    skin.P = taubin(skin.P, skin.tris, chest_mask(skin.P, wt, is_chest), iters=CHEST_SMOOTH)
    print(f'subdivided: +{added} verts -> {len(skin.P)}')
chest = chest_mask(skin.P, wt, is_chest) * (BUST > 0)

x, y, h = skin.P[:, 0], skin.P[:, 1], -skin.P[:, 2]
N0 = skin.N


def welded_normals(P, T):
    _, inv = weld(P)
    a, b, c = P[T[:, 0]], P[T[:, 1]], P[T[:, 2]]
    fn = np.cross(b - a, c - a)
    acc = np.zeros((inv.max() + 1, 3))
    for k in range(3):
        np.add.at(acc, inv[T[:, k]], fn)
    vn = acc[inv]
    return vn / np.maximum(np.linalg.norm(vn, axis=1, keepdims=True), 1e-12)


def smooth_field(f, P, T, iters):
    """Ein Wert je Ecke, ueber die Nachbarn gemittelt - auf der verschweissten Flaeche."""
    if iters <= 0:
        return f
    first, inv = weld(P)
    v = np.zeros(inv.max() + 1)
    n = np.zeros_like(v)
    np.add.at(v, inv, f)
    np.add.at(n, inv, 1)
    v /= np.maximum(n, 1)
    e = inv[np.vstack([T[:, [0, 1]], T[:, [1, 2]], T[:, [2, 0]]])]
    e = np.vstack([e, e[:, ::-1]])
    e = e[e[:, 0] != e[:, 1]]
    deg = np.bincount(e[:, 0], minlength=len(v)).astype(float)
    for _ in range(iters):
        acc = np.zeros_like(v)
        np.add.at(acc, e[:, 0], v[e[:, 1]])
        v = np.where(deg > 0, 0.5 * v + 0.5 * acc / np.maximum(deg, 1), v)
    return v[inv]


# "Vorn" nach der geglaetteten Flaeche, nicht nach den gezeichneten Normalen -
# die knicken an den alten Kanten und legen dort eine Falte in die Brust.
Nsm = welded_normals(skin.P, skin.T[0])

# --- Rumpf: Hueften breiter, Taille schmal, Brustkorb und Schultern schmaler --
Y_AXIS = 0.02
# Sanduhr: das Becken laeuft oben schnell ein, die Taille ist die schmalste
# Stelle, der Brustkorb verjuengt sich nach unten in sie hinein.
sx_t = curve([(-0.12, 1.12), (0.00, 1.16), (0.06, 1.15), (0.10, 1.10), (0.14, 0.88),
              (0.18, 0.74), (0.22, 0.70), (0.26, 0.72), (0.30, 0.78), (0.34, 0.81),
              (0.38, 0.83), (0.42, 0.85), (0.46, 0.87), (0.54, 0.86),
              (0.62, 0.84), (0.66, 0.84), (0.70, 0.96), (0.95, 0.96)], h)
sy_t = curve([(-0.12, 1.00), (0.08, 1.00), (0.14, 0.92), (0.22, 0.84), (0.33, 0.88),
              (0.45, BACK), (0.58, BACK - 0.02), (0.65, 0.86), (0.70, 0.96), (0.95, 0.96)], h)

# SCHULTERGELENK: um den Drehpunkt des Oberarms bleibt der Rumpf, wie er ist.
# Das Skelett ist das des Originals, der Arm dreht also um denselben Punkt -
# wird der Brustkorb dort schmaler oder tiefer, steht die Schulterkappe bei
# haengendem Arm frei neben dem Koerper (in Unity gut zu sehen). Schmaler
# werden darf er erst ein Stueck vom Gelenk weg.
ax_abs = np.abs(x)
ARM_PIVOT = np.array([0.197, 0.0885, 0.541])   # |x|, y, h von DEF-upper_arm
to_pivot = np.sqrt((ax_abs - ARM_PIVOT[0]) ** 2 + (y - ARM_PIVOT[1]) ** 2 + (h - ARM_PIVOT[2]) ** 2)
socket = 1 - smoothstep(0.07, 0.14, to_pivot)
sx_t = sx_t + (1 - sx_t) * socket
sy_t = sy_t + (1 - sy_t) * socket

tx = x * sx_t
ty = Y_AXIS + (y - Y_AXIS) * sy_t
th = h.copy()
# Kopf: ein wenig kleiner, um den Halsansatz
HEAD_H = 0.66
th += smoothstep(0.64, 0.70, h) * (HEAD_H + (h - HEAD_H) * HEAD_SCALE - h)

# Schultern: der Nacken faellt flacher ab - nur zwischen Hals und Gelenk
slope = SHOULDER_SLOPE * smoothstep(0.50, 0.64, h) * smoothstep(0.05, 0.15, ax_abs) * (1 - socket)
th -= slope

# Hals: schlanker um seine Achse, Kopf und Schultern bleiben
neck = smoothstep(0.575, 0.605, h) * smoothstep(0.665, 0.635, h) * smoothstep(0.11, 0.07, ax_abs)
band = (h > 0.60) & (h < 0.66) & (ax_abs < 0.07)
neck_y = np.median(y[band]) if band.any() else Y_AXIS
tx *= 1 - NECK * neck
ty = neck_y + (ty - neck_y) * (1 - NECK * neck)

# Brust: zwei Huegel vorn, oben flach auslaufend, unten rund, leicht nach aussen
front = smoothstep(-0.1, 0.55, -Nsm[:, 1]) * smoothstep(0.0, -0.05, y) * is_chest
# Die Flaechenrichtung ist auf dem groben Netz fleckig, und jeder Fleck wurde
# eine Beule ueber der Brust. Das Feld selbst glaetten, nicht die Form.
front = smooth_field(front, skin.P, skin.T[0], FRONT_SMOOTH)
bust = np.zeros_like(x)
side = np.zeros_like(x)
for sx in (-1, 1):
    bx, bh = BUST_X * sx, 0.422
    dh = h - bh
    rh = np.where(dh > 0, 0.11, 0.068)
    u = np.sqrt(((x - bx) / BUST_R) ** 2 + (dh / rh) ** 2)
    # Oben lang und weich auslaufend, unten runder mit deutlicher Falte.
    b = np.where(dh > 0, bump(u) ** 0.9, np.clip(1 - u * u, 0, 1) ** BUST_LOWER)
    side = np.where(b > bust, (x - bx) / BUST_R, side)
    bust = np.maximum(bust, b)
bust *= front * (BUST > 0)
ty -= BUST * bust
tx += BUST * 0.14 * bust * np.sign(x)
th -= BUST * 0.14 * bust
# darunter tritt der Brustkorb zurueck, sonst steht die Brust auf einer Wand.
# Alles, was dort vorn liegt - auch das Taillenstueck innen und der Ring -,
# sonst sticht es durch.
under = (smoothstep(0.26, 0.33, h) * smoothstep(0.39, 0.345, h)
         * smoothstep(0.17, 0.10, ax_abs) * smoothstep(0.0, -0.04, y))
ty += BUST * BUST_UNDER * under

# Gesaess: nach hinten. NUR NACH LAGE, nicht nach Normale und nicht nach
# Hautgewicht - das Beckenteil hat unten eine nach innen gebogene Kante, und
# die Oberschenkel stecken darin. Haengt die Verschiebung an der Normale oder
# am Rumpfgewicht, wandert die Aussenhaut, die Kante bleibt stehen, und
# dazwischen klappt eine Lasche auf (in Unity gut zu sehen). Ein glattes Feld
# nimmt alles an derselben Stelle gleich weit mit. Wird nach dem Mischen
# addiert, siehe unten.
glute = np.zeros_like(x)
for sx in (-1, 1):
    u = np.sqrt(((x - 0.075 * sx) / 0.10) ** 2 + ((h + 0.01) / 0.13) ** 2)
    glute = np.maximum(glute, bump(u))
glute *= smoothstep(0.0, 0.07, y) * (1 - wa)

# --- Beine: oben voller und nach aussen gestellt, Knie und Knoechel schlanker --
LEG_X = 0.0915
leg_side = np.sign(x)
leg_y = np.interp(-h, [-0.018, 0.394, 0.834], [0.0198, 0.0227, 0.0581])
rl = curve([(-0.95, 0.92), (-0.80, 0.88), (-0.70, 0.90), (-0.55, 0.94), (-0.40, 0.90),
            (-0.28, 0.97), (-0.12, 1.07), (0.02, 1.10), (0.10, 1.10)], h, 0.03)
shift = LEG_X * 0.14 * smoothstep(-0.30, 0.02, h)
lx = leg_side * (LEG_X + shift) + (x - leg_side * LEG_X) * rl
ly = leg_y + (y - leg_y) * np.where(h < -0.84, 1.0, rl)
# Fuss: etwas kuerzer, Sohle bleibt am Boden
foot = smoothstep(-0.80, -0.86, h)
ly = leg_y + (ly - leg_y) * (1 - 0.05 * foot)

# --- Arme: schlanker um die Knochenachse ------------------------------------
# Am Ansatz fast voll - die Kappe muss die Schulterpfanne des Rumpfs fuellen,
# der dort unveraendert bleibt (siehe socket) -, dann deutlich duenner.
ARM_H = 0.541
arm_y = np.interp(ax_abs, [0.197, 0.4796, 0.76], [0.0885, 0.0933, 0.0885])
ra = 1 - ARM_SLIM * (1 - curve([(0.10, 0.97), (0.20, 0.90), (0.27, 0.79), (0.34, 0.76), (0.47, 0.80),
            (0.60, 0.79), (0.74, 0.81), (0.80, 0.92), (1.0, 0.95)], ax_abs))
ah = ARM_H + (h - ARM_H) * ra
ay = arm_y + (y - arm_y) * ra

# --- Mischen nach Hautgewicht -----------------------------------------------
nx = wt * tx + wl * lx + wa * x
ny = wt * ty + wl * ly + wa * ay
nh = wt * th + wl * h + wa * ah
ny += 0.026 * glute

# SCHULTER: Kappe (haengt am Oberarm) und Pfanne (Rumpf) GEMEINSAM zum
# Drehpunkt ziehen. Der Arm dreht um diesen Punkt - was um ihn herum
# gleichmaessig kleiner wird, passt in jeder Armhaltung weiter ineinander.
for sx in (-1, 1):
    pv = np.array([ARM_PIVOT[0] * sx, ARM_PIVOT[1], ARM_PIVOT[2]])
    d = np.sqrt((nx - pv[0]) ** 2 + (ny - pv[1]) ** 2 + (nh - pv[2]) ** 2)
    k = 1 - SHOULDER * (1 - smoothstep(0.09, 0.17, d)) * (np.sign(x) == sx)
    nx, ny, nh = pv[0] + (nx - pv[0]) * k, pv[1] + (ny - pv[1]) * k, pv[2] + (nh - pv[2]) * k

P1 = np.stack([nx, ny, -nh], 1)
tris = skin.tris

# SCHULTER RUNDER: Kappe und Pfanne sind grobe Schalen mit flachem Deckel und
# harten Kanten. Geglaettet wird nur die Schale (die Ringe bleiben, wie sie
# sind), und nur um den Drehpunkt - Kappe und Pfanne sind getrennte Teile,
# das Glaetten zieht sie also nicht ineinander. Die Zone reicht bis an den
# Hals, sonst bleiben Beulen auf dem Nacken stehen; mehr als 40 Schritte
# bringen nichts mehr und kerben die Kappe seitlich ein.
round_zone = np.zeros(len(P1))
for sx in (-1, 1):
    d = np.linalg.norm(P1 - np.array([ARM_PIVOT[0] * sx, ARM_PIVOT[1], -ARM_PIVOT[2]]), axis=1)
    round_zone = np.maximum(round_zone, 1 - smoothstep(SHOULDER_ROUND_R * 0.6, SHOULDER_ROUND_R, d))
round_zone *= ~ring
if SHOULDER_ROUND:
    P1 = taubin(P1, skin.T[0], round_zone, iters=SHOULDER_ROUND)


# --- Normalen ---------------------------------------------------------------
def vertex_normals(P, T):
    a, b, c = P[T[:, 0]], P[T[:, 1]], P[T[:, 2]]
    fn = np.cross(b - a, c - a)
    vn = np.zeros_like(P)
    for k in range(3):
        np.add.at(vn, T[:, k], fn)
    return vn / np.maximum(np.linalg.norm(vn, axis=1, keepdims=True), 1e-12)


def unit(v):
    return v / np.maximum(np.linalg.norm(v, axis=1, keepdims=True), 1e-12)


# Ueberall: die Aenderung der Flaechennormalen auf die gezeichneten uebertragen.
N1 = unit(N0 + vertex_normals(P1, tris) - vertex_normals(skin.P0, tris))
# Brust: neue Form, dafuer gibt es nichts Gezeichnetes - glatt ueber die Naht.
# Schulter: die harten Kanten stehen auch in den gezeichneten Normalen.
region = np.clip(np.maximum(np.maximum(chest, bust * 3), round_zone), 0, 1)[:, None]
N1 = np.where(ring[:, None], N1, unit(N1 * (1 - region) + welded_normals(P1, skin.T[0]) * region))

assert np.abs(P1).max() <= 1.0, np.abs(P1).max()
move = np.linalg.norm(P1 - skin.P0, axis=1)
print(f'moved: max {move.max():.4f}  mean {move.mean():.4f}')


# --- Schreiben --------------------------------------------------------------
def quant_weights(W):
    q = np.floor(W * 255 + 0.5).astype(int)
    q[np.arange(len(q)), q.argmax(1)] += 255 - q.sum(1)
    return q


qp = np.round(P1 * 32767)
arrays = {
    POS: qp,
    NRM: np.round(N1 * 32767),
    UV: skin.UV,
    JNT: skin.J,
    WGT: quant_weights(skin.W),
}
for i, T in zip(IDX, skin.T):
    arrays[i] = T.reshape(-1, 1)
assert len(skin.P) < 65535
repack(g, arrays)
g.j['accessors'][POS]['min'] = [float(v) for v in qp.min(0)]
g.j['accessors'][POS]['max'] = [float(v) for v in qp.max(0)]
g.j['extras'] = dict(g.j.get('extras', {}), variant='female')
g.save(DST)
print('wrote', DST, len(g.bin), 'bytes bin')
