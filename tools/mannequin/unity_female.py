"""Die weibliche Variante als Unity-Asset, neben dem Default-Mannequin.

    python tools/mannequin/unity_female.py \
        "<Workbench>/Assets/Animation Workbench/Prefabs/Mannequin_URP" \
        src/main/resources/static/models/aw-mannequin.glb \
        src/main/resources/static/models/aw-mannequin-f.glb

Schreibt in den Ordner:

    AW_Female_Mannequin_Mesh.mesh   Kopie des Default-Meshes, nur Positionen,
                                    Normalen, Tangenten und Grenzen neu
    AW_Female_Mannequin.prefab      Kopie des Default-Prefabs, das auf das neue
                                    Mesh zeigt; Avatar, Controller, Materialien
                                    und Skelett sind dieselben

Die Form kommt aus `female.py`: die Differenz zwischen `aw-mannequin.glb` und
`aw-mannequin-f.glb`, zurueck in den Mesh-Raum von Unity gerechnet. Das geht,
weil `unity-mesh-to-glb.py` (Workbench, docs-site/tools) die Ecken in ihrer
Reihenfolge laesst - und das wird hier geprueft, nicht angenommen: die
Positionen des Unity-Meshes, so quantisiert wie dort, muessen Ecke fuer Ecke
die des Web-Modells ergeben. Die Brust (BUST > 0) fuegt Ecken hinzu, dann
passt nichts mehr zusammen, und das Skript bricht ab.

GUIDs bleiben bei jedem Lauf dieselben: liegt die .meta schon, wird ihre guid
weiterbenutzt, sonst wird eine neue gewuerfelt. Das Prefab traegt statt
`AWDefaultCharacter_URP` das Label `AWCharacter_URP` - dasselbe Pipeline-Signal
fuer `AWRenderPipelineUtil`, aber kein Anspruch darauf, die Standardfigur zu
sein.
"""
import re
import sys
import uuid
from pathlib import Path

import numpy as np
import yaml

from glbio import Glb

try:
    Loader = yaml.CSafeLoader
except AttributeError:
    Loader = yaml.SafeLoader

FOLDER, WEB_DEFAULT, WEB_FEMALE = (Path(a) for a in sys.argv[1:4])
SRC_MESH = FOLDER / 'AW_Default_Mannequin_Mesh.mesh'
SRC_PREFAB = FOLDER / 'AW_Default_Mannequin.prefab'
DST_MESH = FOLDER / 'AW_Female_Mannequin_Mesh.mesh'
DST_PREFAB = FOLDER / 'AW_Female_Mannequin.prefab'
FLIP = np.array([1.0, 1.0, -1.0])

def write_like(src, dst, content):
    """Mit den Zeilenenden der Vorlage - im Arbeitsbaum der Workbench CRLF."""
    newline = '\r\n' if b'\r\n' in src.read_bytes()[:4096] else '\n'
    dst.write_text(content, encoding='utf-8', newline=newline)


text = SRC_MESH.read_text(encoding='utf-8')
doc = yaml.load(text.split('\n', 3)[3], Loader=Loader)['Mesh']
assert not doc.get('m_MeshCompression'), 'compressed mesh'


# --- Vertexdaten: Stroeme, wie Unity sie ablegt (16 Byte ausgerichtet) --------
vd = doc['m_VertexData']
count = int(vd['m_VertexCount'])
channels = vd['m_Channels']
raw = bytearray(bytes.fromhex(vd['_typelessdata']))
strides = {}
for ch in channels:
    if int(ch['dimension']):
        s = int(ch['stream'])
        strides[s] = max(strides.get(s, 0), int(ch['offset']) + 4 * int(ch['dimension']))
starts, cursor = {}, 0
for s in sorted(strides):
    starts[s] = cursor
    cursor = (cursor + strides[s] * count + 15) & ~15
assert cursor >= len(raw) - 15, 'unexpected vertex data size'


def channel(index, dtype, dim):
    ch = channels[index]
    assert int(ch['dimension']) == dim and int(ch['format']) in (0, 10)
    s = int(ch['stream'])
    base = starts[s] + int(ch['offset'])
    idx = base + np.arange(count)[:, None] * strides[s] + np.arange(dim * 4)[None, :]
    return idx, np.frombuffer(bytes(raw), np.uint8)[idx].copy().view(dtype).reshape(count, dim)


pos_idx, pos = channel(0, np.float32, 3)
nrm_idx, nrm = channel(1, np.float32, 3)
has_tan = int(channels[2]['dimension']) == 4
if has_tan:
    tan_idx, tan = channel(2, np.float32, 4)
_, weights = channel(12, np.float32, 4)
_, joints = channel(13, np.uint32, 4)

indices = np.frombuffer(bytes.fromhex(doc['m_IndexBuffer']), dtype='<u2')
bindposes = np.array([[[float(bp['e%d%d' % (r, c)]) for c in range(4)] for r in range(4)]
                      for bp in doc['m_BindPose']])


# --- Zuordnung zum Web-Modell pruefen ------------------------------------------
p = pos.astype(np.float64) * FLIP
lo, hi = p.min(0), p.max(0)
centre, scale = (lo + hi) / 2, float(np.max(hi - lo) / 2)
q_unity = np.clip(np.rint((p - centre) / scale * 32767), -32767, 32767)

g0, gf = Glb(WEB_DEFAULT), Glb(WEB_FEMALE)
attrs0 = g0.j['meshes'][0]['primitives'][0]['attributes']
attrsf = gf.j['meshes'][0]['primitives'][0]['attributes']
q0, qf = g0.acc(attrs0['POSITION']).astype(float), gf.acc(attrsf['POSITION']).astype(float)
n0, nf = g0.acc(attrs0['NORMAL']) / 32767.0, gf.acc(attrsf['NORMAL']) / 32767.0
if len(qf) != count or len(q0) != count:
    raise SystemExit(f'vertex count differs (unity {count}, web {len(q0)}/{len(qf)}) - BUST > 0?')
off = np.abs(q_unity - q0).max()
if off > 1:
    raise SystemExit(f'web model does not match this mesh vertex for vertex (max {off} steps)')
print(f'mapping ok: {count} vertices, max quantisation step {off:.0f}')


# --- Neue Form -------------------------------------------------------------------
def unit(v):
    return v / np.maximum(np.linalg.norm(v, axis=1, keepdims=True), 1e-12)


new_pos = pos.astype(np.float64) + (qf - q0) / 32767.0 * scale * FLIP
new_nrm = unit(nrm.astype(np.float64) + (nf - n0) * FLIP)
moved = np.linalg.norm(new_pos - pos, axis=1)
print(f'moved: {(moved > 1e-6).sum()} vertices, max {moved.max() * 100:.1f} cm')


def put(idx, values):
    flat = np.ascontiguousarray(values.astype('<f4')).view(np.uint8).reshape(count, -1)
    buf = np.frombuffer(bytes(raw), np.uint8).copy()
    buf[idx] = flat
    raw[:] = buf.tobytes()


put(pos_idx, new_pos)
put(nrm_idx, new_nrm)
if has_tan:
    t = tan[:, :3].astype(np.float64)
    t = unit(t - new_nrm * np.sum(t * new_nrm, axis=1, keepdims=True))
    put(tan_idx, np.column_stack([t, tan[:, 3]]))


# --- Grenzen, so wie Unity sie rechnet (am Original nachgeprueft) ---------------
def bones_aabb(P):
    """Je Knochen die Box aller Ecken, die er bewegt, in seinem Raum."""
    hom = np.column_stack([P, np.ones(len(P))])
    out = []
    for b in range(len(bindposes)):
        use = ((joints == b) & (weights > 0)).any(1)
        if not use.any():
            out.append(None)
            continue
        local = (bindposes[b] @ hom[use].T).T[:, :3]
        out.append((local.min(0), local.max(0)))
    return out


def fmt(v):
    v = float(np.float32(v))
    return '0' if v == 0 else '%.9g' % v


def vec(v):
    return '{x: %s, y: %s, z: %s}' % tuple(fmt(c) for c in v)


stored = doc['m_BonesAABB']
check = bones_aabb(pos.astype(np.float64))
for got, want in zip(check, stored):
    if got is None:
        assert str(want['m_Min']['x']) in ('inf', 'Infinity') or want['m_Min']['x'] == float('inf')
        continue
    mn = np.array([float(want['m_Min'][k]) for k in 'xyz'])
    assert np.abs(got[0] - mn).max() < 1e-4, 'bone AABB rule does not reproduce the original'

bone_lines = []
for box in bones_aabb(new_pos):
    if box is None:
        bone_lines += ['  - m_Min: {x: Infinity, y: Infinity, z: Infinity}',
                       '    m_Max: {x: -Infinity, y: -Infinity, z: -Infinity}']
    else:
        bone_lines += ['  - m_Min: ' + vec(box[0]), '    m_Max: ' + vec(box[1])]


def aabb(P):
    lo, hi = P.min(0), P.max(0)
    return (lo + hi) / 2, (hi - lo) / 2


def submesh_boxes(P):
    boxes = []
    for sm in doc['m_SubMeshes']:
        first = int(sm['firstByte']) // 2
        used = np.unique(indices[first:first + int(sm['indexCount'])])
        boxes.append(aabb(P[used]))
    return boxes


for (c, e), sm in zip(submesh_boxes(pos.astype(np.float64)), doc['m_SubMeshes']):
    want = np.array([float(sm['localAABB']['m_Center'][k]) for k in 'xyz'])
    assert np.abs(c - want).max() < 1e-4, 'submesh AABB rule does not reproduce the original'


# --- Text umschreiben: nur die Zeilen, die sich aendern -------------------------
def sub_once(pattern, repl, s, count=1):
    out, n = re.subn(pattern, repl, s, count=count, flags=re.M)
    assert n == count, pattern
    return out


out = text
out = sub_once(r'^  m_Name: AW_Default_Mannequin_Mesh$', '  m_Name: AW_Female_Mannequin_Mesh', out)
out = sub_once(r'^    _typelessdata: [0-9a-f]+$', '    _typelessdata: ' + raw.hex(), out)

# Die Submesh-Boxen stehen vor m_Shapes, eine je Submesh, der Reihe nach.
head, rest = out.split('  m_Shapes:', 1)
boxes = iter(submesh_boxes(new_pos))
head, n = re.subn(r'^      m_Center: \{[^}]*\}\n      m_Extent: \{[^}]*\}$',
                  lambda m: '      m_Center: %s\n      m_Extent: %s' % tuple(vec(v) for v in next(boxes)),
                  head, flags=re.M)
assert n == len(doc['m_SubMeshes'])
out = head + '  m_Shapes:' + rest

out = sub_once(r'^  m_BonesAABB:\n(?:  [ -].*\n)*?(?=  m_VariableBoneCountWeights:)',
               lambda m: '  m_BonesAABB:\n' + '\n'.join(bone_lines) + '\n', out)
c, e = aabb(new_pos)
out = sub_once(r'^  m_LocalAABB:\n    m_Center: \{[^}]*\}\n    m_Extent: \{[^}]*\}$',
               lambda m: '  m_LocalAABB:\n    m_Center: %s\n    m_Extent: %s' % (vec(c), vec(e)), out)
write_like(SRC_MESH, DST_MESH, out)


# --- .meta: guid behalten, wenn es sie schon gibt ------------------------------
def guid_of(meta):
    if meta.exists():
        m = re.search(r'^guid: ([0-9a-f]{32})$', meta.read_text(encoding='utf-8'), re.M)
        if m:
            return m.group(1)
    return uuid.uuid4().hex


def write_meta(src, dst, guid, labels=None):
    meta = src.read_text(encoding='utf-8')
    meta = meta.split('AssetOrigin:')[0]          # Stempel des Asset Stores, kommt beim Packen
    meta = sub_once(r'^guid: [0-9a-f]{32}$', 'guid: ' + guid, meta)
    if labels is not None:
        meta = sub_once(r'^labels:\n(?:- .*\n)+', 'labels:\n' + ''.join('- %s\n' % l for l in labels), meta)
    write_like(src, dst, meta)


mesh_guid = guid_of(DST_MESH.with_name(DST_MESH.name + '.meta'))
write_meta(SRC_MESH.with_name(SRC_MESH.name + '.meta'), DST_MESH.with_name(DST_MESH.name + '.meta'), mesh_guid)

src_meta = SRC_PREFAB.with_name(SRC_PREFAB.name + '.meta').read_text(encoding='utf-8')
src_mesh_guid = re.search(r'^guid: ([0-9a-f]{32})$',
                          SRC_MESH.with_name(SRC_MESH.name + '.meta').read_text(encoding='utf-8'), re.M).group(1)
labels = [l.replace('AWDefaultCharacter', 'AWCharacter')
          for l in re.findall(r'^- (\S+)$', src_meta.split('labels:')[1].split('PrefabImporter')[0], re.M)]

prefab = SRC_PREFAB.read_text(encoding='utf-8')
prefab = sub_once(r'^  m_Mesh: \{fileID: 4300000, guid: %s, type: 2\}$' % src_mesh_guid,
                  '  m_Mesh: {fileID: 4300000, guid: %s, type: 2}' % mesh_guid, prefab)
prefab = sub_once(r'^  m_Name: AW_Default_Mannequin$', '  m_Name: AW_Female_Mannequin', prefab)
write_like(SRC_PREFAB, DST_PREFAB, prefab)
prefab_guid = guid_of(DST_PREFAB.with_name(DST_PREFAB.name + '.meta'))
write_meta(SRC_PREFAB.with_name(SRC_PREFAB.name + '.meta'), DST_PREFAB.with_name(DST_PREFAB.name + '.meta'),
           prefab_guid, labels)

print('wrote', DST_MESH.name, mesh_guid)
print('wrote', DST_PREFAB.name, prefab_guid, 'labels', labels)
