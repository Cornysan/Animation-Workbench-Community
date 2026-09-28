import json, struct, numpy as np

DT = {5126: np.float32, 5122: np.int16, 5121: np.uint8, 5123: np.uint16}
NC = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}

class Glb:
    def __init__(self, path):
        b = open(path, 'rb').read()
        jl, _ = struct.unpack_from('<II', b, 12)
        self.j = json.loads(b[20:20 + jl])
        bl, _ = struct.unpack_from('<II', b, 20 + jl)
        self.bin = bytearray(b[28 + jl:28 + jl + bl])

    def acc(self, i):
        a = self.j['accessors'][i]; bv = self.j['bufferViews'][a['bufferView']]
        dt = DT[a['componentType']]; nc = NC[a['type']]
        off = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
        assert bv.get('byteStride') in (None, np.dtype(dt).itemsize * nc)
        return np.frombuffer(bytes(self.bin), dtype=dt, count=a['count'] * nc, offset=off).reshape(a['count'], nc).copy()

    def put(self, i, arr):
        a = self.j['accessors'][i]; bv = self.j['bufferViews'][a['bufferView']]
        dt = DT[a['componentType']]
        off = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
        raw = np.ascontiguousarray(arr.astype(dt)).tobytes()
        assert len(raw) == a['count'] * NC[a['type']] * np.dtype(dt).itemsize
        self.bin[off:off + len(raw)] = raw

    def save(self, path):
        js = json.dumps(self.j, separators=(',', ':')).encode()
        js += b' ' * (-len(js) % 4)
        bn = bytes(self.bin) + b'\0' * (-len(self.bin) % 4)
        out = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(bn))
        out += struct.pack('<II', len(js), 0x4E4F534A) + js
        out += struct.pack('<II', len(bn), 0x004E4942) + bn
        open(path, 'wb').write(out)


def repack(g, arrays):
    """Baut den Binaerteil neu: ein bufferView je Accessor, in Accessor-Reihenfolge.
    `arrays` ersetzt einzelne Accessoren (Anzahl darf sich aendern)."""
    views, out = [], bytearray()
    for i, a in enumerate(g.j['accessors']):
        old_view = g.j['bufferViews'][a['bufferView']]
        arr = arrays[i] if i in arrays else g.acc(i)
        arr = np.ascontiguousarray(arr.astype(DT[a['componentType']]))
        raw = arr.tobytes()
        out += b'\0' * (-len(out) % 4)
        v = {'buffer': 0, 'byteOffset': len(out), 'byteLength': len(raw)}
        if 'target' in old_view:
            v['target'] = old_view['target']
        views.append(v)
        out += raw
        a['bufferView'] = i
        a.pop('byteOffset', None)
        a['count'] = arr.shape[0]
    out += b'\0' * (-len(out) % 4)
    g.j['bufferViews'] = views
    g.j['buffers'] = [{'byteLength': len(out)}]
    g.bin = out
