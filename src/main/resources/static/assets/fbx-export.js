/**
 * Einen Clip als binaere `.fbx` herausgeben.
 *
 * WARUM BINAER UND NICHT TEXT. Eine ASCII-FBX ist ein Bruchteil der Arbeit -
 * und Blender liest sie nicht. Sein Importeur nimmt ausschliesslich binaere
 * Dateien ab Version 7.1; eine Textfassung waere also genau fuer das Programm
 * unbrauchbar, fuer das man `.fbx` am dringendsten braucht. Damit war die
 * Entscheidung getroffen, bevor die erste Zeile stand.
 *
 * WORAN DER AUFBAU ABGESCHAUT IST. Nicht an der Spezifikation aus dem
 * Gedaechtnis, sondern an einer Datei, die nachweislich funktioniert: eine
 * `.fbx` aus dem FBX SDK 2014.2.1 wurde aufgemacht und Knoten fuer Knoten
 * ausgelesen - welche Abschnitte darin stehen, wie ein Knochen als
 * `Model`/`LimbNode` aussieht, wie eine Kurve ihre Schluessel ablegt, welche
 * Verbindungen es gibt und wie der Fussblock endet. Was hier steht, ist
 * dieselbe Form.
 *
 * DIE DREI ZAHLEN, DIE MAN NICHT RATEN DARF:
 *
 *   Zeit    46 186 158 000 Einheiten je Sekunde. Nachgemessen an der fremden
 *           Datei: ihr Schluesselabstand war 1 539 538 600, und das ist
 *           genau 1/30 Sekunde.
 *   Drehung Euler-Winkel in GRAD, nicht Quaternionen. Die Reihenfolge
 *           eEulerXYZ von FBX entspricht 'ZYX' in three.js - deshalb wird
 *           hier mit 'ZYX' aus dem Quaternion gerechnet.
 *   Einheit Zentimeter, `UnitScaleFactor` 100. Das ist die Form, in der Maya,
 *           3ds Max und Mixamo ausgeben, und die Unity und Blender beide
 *           richtig umrechnen. Unsere Zahlen stehen in Metern, also mal 100.
 *
 * Was `.glb` hier besser kann: nichts. Was `.fbx` besser kann: ein Skelett
 * OHNE Netz. glTF kennt Knochen nur ueber eine Haut, und eine Haut braucht
 * ein Netz - ein Skelett allein kommt dort als Haufen leerer Knoten an. FBX
 * hat mit `LimbNode` einen echten Begriff dafuer.
 */

import { Euler, Quaternion } from './vendor/three.module.js';

/** FBX-Zeiteinheiten je Sekunde. */
const TIME_UNIT = 46186158000;

/** Unsere Zahlen sind Meter, die Datei rechnet in Zentimetern. */
const TO_CM = 100;

/** "Kubisch mit automatischer Tangente" - derselbe Wert wie in der Fremddatei. */
const KEY_FLAGS = 8456;

const VERSION = 7400;
const DEG = 180 / Math.PI;

/** Der Trenner, mit dem FBX einen Objektnamen von seiner Art trennt. */
const SEP = String.fromCharCode(0) + String.fromCharCode(1);

// ---- Der Container ------------------------------------------------------

/**
 * Ein Knoten der Datei. `props` sind seine Werte, `kids` die Unterknoten.
 *
 * Geschrieben wird in zwei Durchgaengen, weil im Kopf jedes Knotens das
 * ABSOLUTE Ende seines eigenen Bereichs steht - man muss also wissen, wie
 * gross er wird, bevor man ihn anfaengt.
 */
function node(name, props, kids) {
  return { name, props: props || [], kids: kids || [] };
}

const P = {
  int: (v) => ({ t: 'I', v }),
  long: (v) => ({ t: 'L', v: BigInt(v) }),
  double: (v) => ({ t: 'D', v }),
  str: (v) => ({ t: 'S', v }),
  raw: (v) => ({ t: 'R', v }),
  floats: (v) => ({ t: 'f', v }),
  ints: (v) => ({ t: 'i', v }),
  longs: (v) => ({ t: 'l', v }),
};

const utf8 = new TextEncoder();

function propSize(p) {
  switch (p.t) {
    case 'I': return 5;
    case 'L': case 'D': return 9;
    case 'S': return 5 + utf8.encode(p.v).byteLength;
    case 'R': return 5 + p.v.byteLength;
    case 'f': case 'i': return 13 + p.v.length * 4;
    case 'l': return 13 + p.v.length * 8;
    default: throw new Error('unknown property ' + p.t);
  }
}

function nodeSize(n) {
  let size = 13 + utf8.encode(n.name).byteLength;
  for (const p of n.props) size += propSize(p);
  for (const k of n.kids) size += nodeSize(k);
  if (n.kids.length) size += 13;
  return size;
}

class Writer {
  constructor(size) {
    this.bytes = new Uint8Array(size);
    this.view = new DataView(this.bytes.buffer);
    this.at = 0;
  }
  u1(v) { this.bytes[this.at++] = v; }
  u4(v) { this.view.setUint32(this.at, v, true); this.at += 4; }
  i4(v) { this.view.setInt32(this.at, v, true); this.at += 4; }
  f4(v) { this.view.setFloat32(this.at, v, true); this.at += 4; }
  f8(v) { this.view.setFloat64(this.at, v, true); this.at += 8; }
  i8(v) { this.view.setBigInt64(this.at, v, true); this.at += 8; }
  blob(v) { this.bytes.set(v, this.at); this.at += v.byteLength; }

  prop(p) {
    this.u1(p.t.charCodeAt(0));
    if (p.t === 'I') { this.i4(p.v); return; }
    if (p.t === 'L') { this.i8(p.v); return; }
    if (p.t === 'D') { this.f8(p.v); return; }
    if (p.t === 'S' || p.t === 'R') {
      const b = p.t === 'S' ? utf8.encode(p.v) : p.v;
      this.u4(b.byteLength);
      this.blob(b);
      return;
    }
    //  Felder: Laenge, Kodierung (0 = unverpackt), Bytes, dann die Zahlen.
    const each = p.t === 'l' ? 8 : 4;
    this.u4(p.v.length);
    this.u4(0);
    this.u4(p.v.length * each);
    for (const x of p.v) {
      if (p.t === 'f') this.f4(x);
      else if (p.t === 'i') this.i4(x);
      else this.i8(BigInt(x));
    }
  }

  node(n) {
    const end = this.at + nodeSize(n);
    const name = utf8.encode(n.name);
    let propBytes = 0;
    for (const p of n.props) propBytes += propSize(p);

    this.u4(end);
    this.u4(n.props.length);
    this.u4(propBytes);
    this.u1(name.byteLength);
    this.blob(name);
    for (const p of n.props) this.prop(p);
    if (n.kids.length) {
      for (const k of n.kids) this.node(k);
      //  Der Abschluss einer Kinderliste: dreizehn Null-Byte. NUR wenn es
      //  Kinder gibt - sonst erwartet ein Leser sie nicht und verliest sich.
      for (let i = 0; i < 13; i++) this.u1(0);
    }
    if (this.at !== end) throw new Error('node ' + n.name + ': ' + this.at + ' != ' + end);
  }
}

/** Die sechzehn Byte, an denen ein Leser das Ende erkennt. */
const FOOTER_MAGIC = new Uint8Array([
  0xf8, 0x5a, 0x8c, 0x6a, 0xde, 0xf5, 0xd9, 0x7e,
  0xec, 0xe9, 0x0c, 0xe3, 0x75, 0x8f, 0x29, 0x0b,
]);

/**
 * Kopf, Inhalt, Fuss.
 *
 * Der Fussblock ist nachgerechnet, nicht erinnert: in der Fremddatei endete
 * der Inhalt, dann kamen sechzehn Byte Kennung, dann Nullen bis zur naechsten
 * Grenze von sechzehn (dort stand die Datei bei 477 056 - restlos teilbar),
 * dann vier Null-Byte, die Version, 120 Null-Byte und die Magie.
 */
function toFile(roots) {
  let content = 13;
  for (const n of roots) content += nodeSize(n);

  const afterId = 27 + content + 16;
  const pad = (16 - (afterId % 16)) % 16;
  const total = afterId + pad + 4 + 4 + 120 + 16;

  const w = new Writer(total);
  w.blob(utf8.encode('Kaydara FBX Binary  '));
  w.u1(0x00); w.u1(0x1a); w.u1(0x00);
  w.u4(VERSION);

  for (const n of roots) w.node(n);
  for (let i = 0; i < 13; i++) w.u1(0);

  //  Die Kennung bleibt leer. Echte SDK-Dateien leiten sie aus ihrer FileId
  //  ab; kein Importeur, den wir nachsehen konnten, prueft sie, und erfundene
  //  Bytes waeren eine Behauptung ueber etwas, das wir nicht nachrechnen.
  for (let i = 0; i < 16; i++) w.u1(0);
  for (let i = 0; i < pad; i++) w.u1(0);
  w.u4(0);
  w.u4(VERSION);
  for (let i = 0; i < 120; i++) w.u1(0);
  w.blob(FOOTER_MAGIC);

  if (w.at !== total) throw new Error('footer: ' + w.at + ' != ' + total);
  return w.bytes;
}

// ---- Die Szene ----------------------------------------------------------

/**
 * Namen stehen in der Datei umgedreht und mit einem Trenner: aus dem Knochen
 * `Hips` wird `Hips` + Trenner + `Model`. So machen es die echten Dateien,
 * und so erwartet es jeder Leser, der sie liest.
 */
const objectName = (name, kind) => name + SEP + kind;

/** Eine Eigenschaft im Block `Properties70`, mit Zahlen als Werten. */
const prop70 = (name, type, sub, flag, values) =>
  node('P', [P.str(name), P.str(type), P.str(sub), P.str(flag)]
    .concat((values || []).map(P.double)));

const timeMode = (fps) =>
  ({ 120: 1, 100: 2, 60: 3, 50: 4, 48: 5, 30: 6, 24: 11, 1000: 12 }[fps] || 14);

/**
 * Der Clip als Skelett, ohne Netz.
 *
 * Genau das, was glTF nicht kann: `LimbNode` ist ein echter Knochen, und ein
 * Importeur baut daraus ein Skelett statt einer Kette leerer Objekte.
 */
export function skeletonFbx(preview, options) {
  const clipName = (options && options.name) || 'Clip';
  const bones = preview.bones;
  const parents = preview.parents;
  const frames = preview.hips.length;
  const fps = preview.frameRate || 30;

  let nextId = 1000000;
  const id = () => nextId++;

  const modelId = bones.map(() => id());
  const attrId = bones.map(() => id());
  const stackId = id();
  const layerId = id();
  const docId = id();

  const objects = [];
  const connections = [];
  const conn = (...parts) => connections.push(node('C', parts.map((v) =>
    typeof v === 'string' ? P.str(v) : P.long(v))));

  const euler = new Euler();
  const quat = new Quaternion();
  const eulerAt = (i, f) => {
    const row = preview.rotations[f];
    quat.set(row[i * 4], row[i * 4 + 1], row[i * 4 + 2], row[i * 4 + 3]).normalize();
    euler.setFromQuaternion(quat, 'ZYX');
    return euler;
  };

  // ---- Die Knochen ------------------------------------------------------
  bones.forEach((bone, i) => {
    const rest = preview.rest[i];
    //  Die Ruhelage bekommt das erste Bild. FBX legt sie in die
    //  Eigenschaften des Knotens; die Kurven schreiben sie dann ueber, und
    //  ein Programm, das nur die Ruhepose ansieht, zeigt trotzdem etwas
    //  Sinnvolles statt einer T-Pose aus Nullen.
    const e = eulerAt(i, 0);

    objects.push(node('Model', [
      P.long(modelId[i]), P.str(objectName(bone, 'Model')), P.str('LimbNode'),
    ], [
      node('Version', [P.int(232)]),
      node('Properties70', [], [
        node('P', [P.str('InheritType'), P.str('enum'), P.str(''), P.str(''), P.int(1)]),
        node('P', [P.str('DefaultAttributeIndex'), P.str('int'), P.str('Integer'), P.str(''), P.int(0)]),
        prop70('Lcl Translation', 'Lcl Translation', '', 'A',
          [rest[0] * TO_CM, rest[1] * TO_CM, rest[2] * TO_CM]),
        prop70('Lcl Rotation', 'Lcl Rotation', '', 'A', [e.x * DEG, e.y * DEG, e.z * DEG]),
        prop70('Lcl Scaling', 'Lcl Scaling', '', 'A', [1, 1, 1]),
      ]),
      node('Culling', [P.str('CullingOff')]),
    ]));

    objects.push(node('NodeAttribute', [
      P.long(attrId[i]), P.str(objectName(bone, 'NodeAttribute')), P.str('LimbNode'),
    ], [
      node('Properties70', [], [
        node('P', [P.str('Size'), P.str('double'), P.str('Number'), P.str(''), P.double(1)]),
      ]),
      node('TypeFlags', [P.str('Skeleton')]),
    ]));

    conn('OO', attrId[i], modelId[i]);
    conn('OO', modelId[i], parents[i] < 0 ? 0 : modelId[parents[i]]);
  });

  // ---- Die Bewegung -----------------------------------------------------
  const stop = Math.round(((frames - 1) / fps) * TIME_UNIT);
  const times = [];
  for (let f = 0; f < frames; f++) times.push(Math.round((f / fps) * TIME_UNIT));

  objects.push(node('AnimationStack', [
    P.long(stackId), P.str(objectName(clipName, 'AnimStack')), P.str(''),
  ], [
    node('Properties70', [], [
      node('P', [P.str('LocalStart'), P.str('KTime'), P.str('Time'), P.str(''), P.long(0)]),
      node('P', [P.str('LocalStop'), P.str('KTime'), P.str('Time'), P.str(''), P.long(stop)]),
      node('P', [P.str('ReferenceStart'), P.str('KTime'), P.str('Time'), P.str(''), P.long(0)]),
      node('P', [P.str('ReferenceStop'), P.str('KTime'), P.str('Time'), P.str(''), P.long(stop)]),
    ]),
  ]));
  objects.push(node('AnimationLayer', [
    P.long(layerId), P.str(objectName('Layer0', 'AnimLayer')), P.str(''),
  ]));
  conn('OO', layerId, stackId);

  /**
   * Eine Spur: ein `AnimationCurveNode` mit drei `AnimationCurve` daran.
   *
   * `KeyAttrFlags`, `KeyAttrDataFloat` und `KeyAttrRefCount` stehen je LAUF,
   * nicht je Schluessel - ein Lauf ueber alle Schluessel genuegt, und die
   * Datei wird dadurch erheblich kleiner. Die Fremddatei schrieb einen Lauf
   * je Schluessel; das ist dieselbe Aussage, nur umstaendlicher.
   */
  const track = (target, property, channels) => {
    const label = property === 'Lcl Translation' ? 'T' : 'R';
    const cnId = id();
    objects.push(node('AnimationCurveNode', [
      P.long(cnId), P.str(objectName(label, 'AnimCurveNode')), P.str(''),
    ], [
      node('Properties70', [], ['X', 'Y', 'Z'].map((axis, k) =>
        node('P', [P.str('d|' + axis), P.str('Number'), P.str(''), P.str('A'),
          P.double(channels[k][0])]))),
    ]));
    conn('OO', cnId, layerId);
    conn('OP', cnId, target, property);

    ['X', 'Y', 'Z'].forEach((axis, k) => {
      const curveId = id();
      objects.push(node('AnimationCurve', [
        P.long(curveId), P.str(objectName('', 'AnimCurve')), P.str(''),
      ], [
        node('Default', [P.double(channels[k][0])]),
        node('KeyVer', [P.int(4008)]),
        node('KeyTime', [P.longs(times)]),
        node('KeyValueFloat', [P.floats(channels[k])]),
        node('KeyAttrFlags', [P.ints([KEY_FLAGS])]),
        node('KeyAttrDataFloat', [P.floats([0, 0, 0, 0])]),
        node('KeyAttrRefCount', [P.ints([frames])]),
      ]));
      conn('OP', curveId, cnId, 'd|' + axis);
    });
  };

  bones.forEach((bone, i) => {
    const x = new Float32Array(frames);
    const y = new Float32Array(frames);
    const z = new Float32Array(frames);
    for (let f = 0; f < frames; f++) {
      const e = eulerAt(i, f);
      x[f] = e.x * DEG; y[f] = e.y * DEG; z[f] = e.z * DEG;
    }
    track(modelId[i], 'Lcl Rotation', [x, y, z]);
  });

  //  Und die Wurzel wandert. Ohne diese Spur laeuft jeder Schritt auf der
  //  Stelle - die Beine gehen, die Figur kommt nicht vom Fleck.
  const rx = new Float32Array(frames);
  const ry = new Float32Array(frames);
  const rz = new Float32Array(frames);
  for (let f = 0; f < frames; f++) {
    rx[f] = preview.hips[f][0] * TO_CM;
    ry[f] = preview.hips[f][1] * TO_CM;
    rz[f] = preview.hips[f][2] * TO_CM;
  }
  track(modelId[0], 'Lcl Translation', [rx, ry, rz]);

  // ---- Die Datei --------------------------------------------------------
  const now = new Date();
  const counts = {};
  for (const o of objects) counts[o.name] = (counts[o.name] || 0) + 1;

  return toFile([
    node('FBXHeaderExtension', [], [
      node('FBXHeaderVersion', [P.int(1003)]),
      node('FBXVersion', [P.int(VERSION)]),
      node('EncryptionType', [P.int(0)]),
      node('CreationTimeStamp', [], [
        node('Version', [P.int(1000)]),
        node('Year', [P.int(now.getFullYear())]),
        node('Month', [P.int(now.getMonth() + 1)]),
        node('Day', [P.int(now.getDate())]),
        node('Hour', [P.int(now.getHours())]),
        node('Minute', [P.int(now.getMinutes())]),
        node('Second', [P.int(now.getSeconds())]),
        node('Millisecond', [P.int(0)]),
      ]),
      node('Creator', [P.str('Animation Workbench Community')]),
    ]),
    node('FileId', [P.raw(new Uint8Array(16))]),
    node('CreationTime', [P.str(now.toISOString())]),
    node('Creator', [P.str('Animation Workbench Community')]),

    node('GlobalSettings', [], [
      node('Version', [P.int(1000)]),
      node('Properties70', [], [
        node('P', [P.str('UpAxis'), P.str('int'), P.str('Integer'), P.str(''), P.int(1)]),
        node('P', [P.str('UpAxisSign'), P.str('int'), P.str('Integer'), P.str(''), P.int(1)]),
        node('P', [P.str('FrontAxis'), P.str('int'), P.str('Integer'), P.str(''), P.int(2)]),
        node('P', [P.str('FrontAxisSign'), P.str('int'), P.str('Integer'), P.str(''), P.int(1)]),
        node('P', [P.str('CoordAxis'), P.str('int'), P.str('Integer'), P.str(''), P.int(0)]),
        node('P', [P.str('CoordAxisSign'), P.str('int'), P.str('Integer'), P.str(''), P.int(1)]),
        node('P', [P.str('OriginalUpAxis'), P.str('int'), P.str('Integer'), P.str(''), P.int(1)]),
        node('P', [P.str('OriginalUpAxisSign'), P.str('int'), P.str('Integer'), P.str(''), P.int(1)]),
        node('P', [P.str('UnitScaleFactor'), P.str('double'), P.str('Number'), P.str(''), P.double(TO_CM)]),
        node('P', [P.str('OriginalUnitScaleFactor'), P.str('double'), P.str('Number'), P.str(''), P.double(TO_CM)]),
        node('P', [P.str('TimeMode'), P.str('enum'), P.str(''), P.str(''), P.int(timeMode(fps))]),
        node('P', [P.str('CustomFrameRate'), P.str('double'), P.str('Number'), P.str(''), P.double(fps)]),
        node('P', [P.str('TimeSpanStart'), P.str('KTime'), P.str('Time'), P.str(''), P.long(0)]),
        node('P', [P.str('TimeSpanStop'), P.str('KTime'), P.str('Time'), P.str(''), P.long(stop)]),
      ]),
    ]),

    node('Documents', [], [
      node('Count', [P.int(1)]),
      node('Document', [P.long(docId), P.str('Scene'), P.str('Scene')], [
        node('Properties70', [], [
          node('P', [P.str('SourceObject'), P.str('object'), P.str(''), P.str('')]),
          node('P', [P.str('ActiveAnimStackName'), P.str('KString'), P.str(''), P.str(''), P.str(clipName)]),
        ]),
        node('RootNode', [P.long(0)]),
      ]),
    ]),
    node('References', []),

    node('Definitions', [], [
      node('Version', [P.int(100)]),
      node('Count', [P.int(objects.length + 1)]),
      node('ObjectType', [P.str('GlobalSettings')], [node('Count', [P.int(1)])]),
    ].concat(Object.keys(counts).map((name) =>
      node('ObjectType', [P.str(name)], [node('Count', [P.int(counts[name])])])))),

    node('Objects', [], objects),
    node('Connections', [], connections),
    node('Takes', [], [node('Current', [P.str(clipName)])]),
  ]);
}
