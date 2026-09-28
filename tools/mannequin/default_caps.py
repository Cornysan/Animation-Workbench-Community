"""Die Schulterkappen des Default-Mannequins: Gewichte glaetten, im Unity-Mesh.

    python tools/mannequin/default_caps.py \
        "<Workbench>/Assets/Animation Workbench/Prefabs/Mannequin_URP/AW_Default_Mannequin_Mesh.mesh" \
        src/main/resources/static/models/aw-mannequin.glb

Danach die beiden Web-Kopien neu bauen (`unity-mesh-to-glb.py` in der
Workbench, docs-site/tools: Startseite ohne, Portal mit `--tpose`), dann
`female.py` und `unity_female.py` - die weibliche Figur erbt die Gewichte.

Der innere Rand jeder Kappe haengt zum Teil am Schluesselbein, damit er bei
haengendem Arm auf der Brust liegen bleibt. Im Original springt das Verhaeltnis
aber von Ecke zu Ecke (0,15 neben 0,64, an einer Kante bis 0,68): dreht der
Oberarm um die eigene Achse - Faeuste hoch, Bizeps zeigen -, klappt der Rand
ueber sich selbst, eine dunkle Knautschfalte an der Schulter (Pablo,
2026-09-28, im Portal). Geglaettet ueber die Kappe bleibt der Rand am
Schluesselbein, nur der Uebergang wird gleichmaessig. Gemessen ueber 960
Posen aus 24 Clips (Bone-Matrizen der Portal-Buehne, in Python nachgeskinnt):
vorher standen in fast jeder Pose Dreiecke der Kappe gegen ihre eigene
Normale, danach in keiner. 10 Schritte halbieren das nur.

Nur die Gewichte aendern sich, Ecken und Dreiecke bleiben bitgenau. Das Web-
Modell dient zur Kontrolle und fuer die Knochennamen: seine Ecken, Dreiecke und
Knochen muessen die des Unity-Meshes sein.

Laeuft einmal: ist die Kappe schon glatt (kein Sprung ueber CAP_ROUGH), bleibt
die Datei, wie sie ist - ein zweiter Lauf glaettet nicht weiter.
"""
import re
import sys
from pathlib import Path

import numpy as np
import yaml

from glbio import Glb

try:
    Loader = yaml.CSafeLoader
except AttributeError:
    Loader = yaml.SafeLoader

MESH, WEB = Path(sys.argv[1]), Path(sys.argv[2])
FLIP = np.array([1.0, 1.0, -1.0])
ARM_PIVOT = np.array([0.197, 0.0885, 0.541])   # |x|, y, h von DEF-upper_arm, Web-Raum
CAP_SMOOTH = 20     # Glaettschritte
CAP_R = 0.14        # bis wohin, vom Drehpunkt aus
CAP_ROUGH = 0.3     # groesster Sprung an einer Kante: Original 0,68, geglaettet 0,11


def write_like(src, dst, content):
    """Mit den Zeilenenden der Vorlage - im Arbeitsbaum der Workbench CRLF."""
    newline = '\r\n' if b'\r\n' in src.read_bytes()[:4096] else '\n'
    dst.write_text(content, encoding='utf-8', newline=newline)


text = MESH.read_text(encoding='utf-8')
doc = yaml.load(text.split('\n', 3)[3], Loader=Loader)['Mesh']
assert not doc.get('m_MeshCompression'), 'compressed mesh'
assert not doc['m_VariableBoneCountWeights']['m_Data'], 'variable bone weights'


# --- Vertexdaten: Stroeme, wie Unity sie ablegt (16 Byte ausgerichtet) --------
vd = doc['m_VertexData']
count = int(vd['m_VertexCount'])
channels = vd['m_Channels']
raw = bytearray(bytes.fromhex(vd['_typelessdata']))
strides, starts, cursor = {}, {}, 0
for ch in channels:
    if int(ch['dimension']):
        s = int(ch['stream'])
        strides[s] = max(strides.get(s, 0), int(ch['offset']) + 4 * int(ch['dimension']))
for s in sorted(strides):
    starts[s] = cursor
    cursor = (cursor + strides[s] * count + 15) & ~15
assert abs(cursor - len(raw)) < 16, 'unexpected vertex data size'


def channel(index):
    """Sicht auf einen Kanal in `raw`: je Ecke die Bytes an seiner Stelle im Strom."""
    ch = channels[index]
    s, o = int(ch['stream']), int(ch['offset'])
    rows = np.frombuffer(raw, np.uint8, count=strides[s] * count, offset=starts[s]).reshape(count, strides[s])
    return rows[:, o:o + 4 * int(ch['dimension'])]


pos = channel(0).copy().view(np.float32).reshape(-1, 3)
weights = channel(12).copy().view(np.float32).reshape(-1, 4)
joints = channel(13).copy().view(np.uint32).reshape(-1, 4)
bindposes = np.array([[[float(bp['e%d%d' % (r, c)]) for c in range(4)] for r in range(4)]
                      for bp in doc['m_BindPose']])
indices = np.frombuffer(bytes.fromhex(doc['m_IndexBuffer']), dtype='<u2')
shell_first = int(doc['m_SubMeshes'][0]['firstByte']) // 2
shell = indices[shell_first:shell_first + int(doc['m_SubMeshes'][0]['indexCount'])].reshape(-1, 3)


# --- Zuordnung zum Web-Modell pruefen ------------------------------------------
# Wie in unity_female.py: die Positionen, so quantisiert wie im Web, muessen Ecke
# fuer Ecke die des Web-Modells ergeben, dazu Dreiecke und Knochen.
p = pos.astype(np.float64) * FLIP
lo, hi = p.min(0), p.max(0)
centre, scale = (lo + hi) / 2, float(np.max(hi - lo) / 2)
q = np.clip(np.rint((p - centre) / scale * 32767), -32767, 32767)

g = Glb(WEB)
prim = g.j['meshes'][0]['primitives'][0]
if np.abs(q - g.acc(prim['attributes']['POSITION'])).max() > 1:
    raise SystemExit('web model does not match this mesh vertex for vertex')
if not np.array_equal(g.acc(prim['indices']).reshape(-1, 3)[:, ::-1], shell):
    raise SystemExit('web triangles do not match this mesh')
jw = g.acc(prim['attributes']['JOINTS_0']).astype(np.uint32)
names = [g.j['nodes'][n]['name'].replace('DEF-', '') for n in g.j['skins'][0]['joints']]

# Im Web-Raum weiter: x seitlich (L negativ), y Tiefe, z senkrecht (oben negativ)
P = q / 32767.0


def weld(P):
    """Ecken an derselben Stelle (UV-Naht, Materialgrenze) als eine."""
    key = np.round(P * 20000).astype(np.int64)
    _, inv = np.unique(key, axis=0, return_inverse=True)
    return inv.ravel()


inv = weld(P)
par = np.arange(inv.max() + 1)


def root(a):
    while par[a] != a:
        par[a] = par[par[a]]
        a = par[a]
    return a


for t in shell:
    a, b, c = root(inv[t[0]]), root(inv[t[1]]), root(inv[t[2]])
    par[a] = b
    par[root(c)] = root(b)
piece = np.full(len(P), -1)
used = np.unique(shell)
piece[used] = [root(inv[v]) for v in used]


# --- Gewichte glaetten ------------------------------------------------------------
W = weights.astype(np.float64)
J = joints.astype(np.int64)
rough = 0.0
touched = np.zeros(len(P), bool)
for side, sx in (('L', -1), ('R', 1)):
    ish, iup = names.index('shoulder.' + side), names.index('upper_arm.' + side)
    ws = (W * (J == ish)).sum(1)
    wu = (W * (J == iup)).sum(1)
    cap = np.zeros(len(P), bool)
    for pc in np.unique(piece[wu > 0.5]):
        if pc >= 0 and (piece == pc).sum() < 400:     # die Oberarmschale, nicht der Rumpf
            cap |= piece == pc
    pivot = np.array([ARM_PIVOT[0] * sx, ARM_PIVOT[1], -ARM_PIVOT[2]])
    near = cap & (np.linalg.norm(P - pivot, axis=1) < CAP_R)
    touched |= near
    both = ws + wu
    ratio = np.where(both > 0, ws / np.maximum(both, 1e-12), 0)

    # Auf der verschweissten Kappe, nur ueber ihre eigenen Kanten - sonst liefe
    # das Schluesselbein ueber die Naht in den Rumpf.
    T = shell[cap[shell].all(1)]
    e = inv[np.vstack([T[:, [0, 1]], T[:, [1, 2]], T[:, [2, 0]]])]
    e = np.vstack([e, e[:, ::-1]])
    e = e[e[:, 0] != e[:, 1]]
    v = np.zeros(inv.max() + 1)
    n = np.zeros_like(v)
    np.add.at(v, inv[cap], ratio[cap])
    np.add.at(n, inv[cap], 1)
    v /= np.maximum(n, 1)
    rough = max(rough, np.abs(v[e[:, 0]] - v[e[:, 1]]).max())
    deg = np.bincount(e[:, 0], minlength=len(v)).astype(float)
    free = np.zeros(len(v), bool)
    free[inv[near]] = True
    free &= deg > 0
    for _ in range(CAP_SMOOTH):
        acc = np.zeros_like(v)
        np.add.at(acc, e[:, 0], v[e[:, 1]])
        v = np.where(free, 0.5 * v + 0.5 * acc / np.maximum(deg, 1), v)
    ratio = v[inv]

    for i in np.where(near & (both > 0))[0]:
        for j, w in ((ish, ratio[i] * both[i]), (iup, (1 - ratio[i]) * both[i])):
            slot = np.where((J[i] == j) & (W[i] > 0))[0]
            if not len(slot):
                slot = np.where(W[i] == 0)[0]
                if w < 1e-3 or not len(slot):
                    continue
                J[i, slot[0]] = j
            W[i, slot[0]] = w
        # Unity erwartet das schwerste Gewicht vorn, wie im Original
        order = np.argsort(-W[i], kind='stable')
        J[i], W[i] = J[i][order], W[i][order] / W[i].sum()

# Knochen: ausserhalb der Kappen muessen sie die des Web-Modells sein. An den
# Kappen nicht - nach einem Lauf und vor dem Neubauen der Web-Kopien stehen dort
# im Unity-Mesh schon die geglaetteten.
keep = ~touched[:, None] & (weights > 0)
if not np.array_equal(np.where(keep, joints, 0), np.where(keep, jw, 0)):
    raise SystemExit('web bones do not match this mesh')

if rough < CAP_ROUGH:
    print(f'caps already smooth (largest jump {rough:.2f}), {MESH.name} unchanged')
    sys.exit(0)

new_weights = W.astype('<f4')
new_joints = J.astype('<u4')
changed = (new_weights != weights).any(1) | (new_joints != joints).any(1)
channel(12)[:] = new_weights.view(np.uint8).reshape(count, 16)
channel(13)[:] = new_joints.view(np.uint8).reshape(count, 16)
print(f'smoothed: {changed.sum()} vertices, largest jump was {rough:.2f}')


# --- Knochen-Boxen, so wie Unity sie rechnet (am Original nachgeprueft) ----------
def bones_aabb(J, W):
    """Je Knochen die Box aller Ecken, die er bewegt, in seinem Raum."""
    hom = np.column_stack([pos.astype(np.float64), np.ones(count)])
    res = []
    for b in range(len(bindposes)):
        use = ((J == b) & (W > 0)).any(1)
        if not use.any():
            res.append(None)
            continue
        local = (bindposes[b] @ hom[use].T).T[:, :3]
        res.append((local.min(0), local.max(0)))
    return res


def fmt(v):
    """Kuerzeste Schreibweise, die denselben float32 ergibt - wie Unity."""
    return np.format_float_positional(np.float32(v), unique=True, trim='-')


def vec(v):
    return '{x: %s, y: %s, z: %s}' % tuple(fmt(c) for c in v)


old_boxes = bones_aabb(joints, weights)
for got, want in zip(old_boxes, doc['m_BonesAABB']):
    if got is None:
        continue
    mn = np.array([float(want['m_Min'][k]) for k in 'xyz'])
    assert np.abs(got[0] - mn).max() < 1e-4, 'bone AABB rule does not reproduce the original'

# Nur die Boxen neu schreiben, die sich aendern; die anderen Zeilen bleiben
# wortgleich stehen.
old_lines = re.search(r'^  m_BonesAABB:\n((?:  [ -].*\n)*?)(?=  m_VariableBoneCountWeights:)',
                      text, re.M).group(1).splitlines()
assert len(old_lines) == 2 * len(old_boxes)
lines = []
for b, (old, box) in enumerate(zip(old_boxes, bones_aabb(new_joints, new_weights))):
    if box is None:
        assert old is None, 'a bone lost all its vertices'
        lines += old_lines[2 * b:2 * b + 2]
    elif old is not None and np.array_equal(old[0], box[0]) and np.array_equal(old[1], box[1]):
        lines += old_lines[2 * b:2 * b + 2]
    else:
        lines += ['  - m_Min: ' + vec(box[0]), '    m_Max: ' + vec(box[1])]
        print(f'bone box: {names[b]}')


def sub_once(pattern, repl, s):
    out, n = re.subn(pattern, repl, s, count=1, flags=re.M)
    assert n == 1, pattern
    return out


out = sub_once(r'^    _typelessdata: [0-9a-f]+$', lambda m: '    _typelessdata: ' + bytes(raw).hex(), text)
out = sub_once(r'^  m_BonesAABB:\n(?:  [ -].*\n)*?(?=  m_VariableBoneCountWeights:)',
               lambda m: '  m_BonesAABB:\n' + '\n'.join(lines) + '\n', out)
write_like(MESH, MESH, out)
print('wrote', MESH)
