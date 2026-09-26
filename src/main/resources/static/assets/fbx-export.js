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
 *   Einheit Zentimeter, `UnitScaleFactor` 1. Das ist die Form, in der Maya,
 *           3ds Max und Mixamo ausgeben, und die Unity und Blender beide
 *           richtig umrechnen. Unsere Zahlen stehen in Metern, also mal 100.
 *
 *           BIS 2026-09-26 STAND HIER `UnitScaleFactor` 100 - und das heisst
 *           "eine Einheit der Datei ist ein METER". Mit den Zahlen in
 *           Zentimetern kam jede Datei hundertfach zu gross an: in Blender
 *           gemessen die Huefte auf 91,6 m, die Figur 164 m hoch. In Unity
 *           fiel es nicht auf, solange der Clip als Humanoid importiert
 *           wurde - Muskelwerte kennen keine Groesse.
 *
 * UND DIE EINE, DIE BIS ZUM 2026-09-24 FEHLTE: die Haendigkeit. Die Vorschau
 * steht in Unitys Raum, und der ist linkshaendig; FBX ist rechtshaendig.
 * Unitys Importeur spiegelt beim Einlesen an der YZ-Ebene (x kippt), also
 * spiegelt der Schreiber genau so - Punkte (-x, y, z), Drehungen
 * (x, -y, -z, w) - und die Runde durch Unity ist die Identitaet. Ohne das kam
 * jede Figur seitenverkehrt an: der linke Arm rechts, Wirbelsaeule und Kopf
 * gegensinnig verdreht.
 *
 * DIE RUHELAGE ist die T-Pose der Quellfigur, nicht Bild 0 - Unity baut den
 * Avatar einer importierten Datei aus ihr. Woher sie kommt, steht in
 * `rest-pose.js`.
 *
 * Seit 2026-09-26 gibt es auch die Figur als `.fbx` (`characterFbx`, unten) -
 * das Mannequin mit Netz und Haut, wie "With Skin" bei Mixamo.
 *
 * Was `.glb` hier besser kann: nichts. Was `.fbx` besser kann: ein Skelett
 * OHNE Netz. glTF kennt Knochen nur ueber eine Haut, und eine Haut braucht
 * ein Netz - ein Skelett allein kommt dort als Haufen leerer Knoten an. FBX
 * hat mit `LimbNode` einen echten Begriff dafuer.
 */

import { Euler, Matrix4, Quaternion, Vector3 } from './vendor/three.module.js';
import { restPose } from './rest-pose.js';
import { openGlb } from './glb-export.js';

/** FBX-Zeiteinheiten je Sekunde. */
const TIME_UNIT = 46186158000;

/** Unsere Zahlen sind Meter, die Datei rechnet in Zentimetern. */
const TO_CM = 100;

/** Und sagt das auch: 1 = eine Einheit der Datei ist ein Zentimeter. */
const UNIT_SCALE = 1;

/** "Kubisch mit automatischer Tangente" - derselbe Wert wie in der Fremddatei. */
const KEY_FLAGS = 8456;

const VERSION = 7400;
const DEG = 180 / Math.PI;

/** Unity -> FBX: an der YZ-Ebene gespiegelt, so wie Unitys Importeur zurueckspiegelt. */
const point = (v) => [-v[0] * TO_CM, v[1] * TO_CM, v[2] * TO_CM];
const turn = (q) => new Quaternion(q[0], -q[1], -q[2], q[3]).normalize();

/**
 * Unter den gleichwertigen Euler-Tripeln das, das dem vorigen Bild am
 * naechsten liegt: jeder Winkel um volle Umdrehungen verschoben, und die
 * zweite Loesung (x + 180, 180 - y, z + 180) dazu. Ein Leser, der zwischen den
 * Schluesseln in Euler-Winkeln interpoliert (Blender), dreht sonst bei jedem
 * Sprung von 179 auf -179 Grad einmal ganz herum.
 */
function nearestEuler(previous, e) {
  const turns = (a, to) => a + 2 * Math.PI * Math.round((to - a) / (2 * Math.PI));
  let best = null;
  let bestDistance = Infinity;
  for (const c of [[e.x, e.y, e.z], [e.x + Math.PI, Math.PI - e.y, e.z + Math.PI]]) {
    const v = c.map((a, k) => turns(a, previous[k]));
    const distance = v.reduce((sum, a, k) => sum + (a - previous[k]) ** 2, 0);
    if (distance < bestDistance) { best = v; bestDistance = distance; }
  }
  return best;
}

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
  bool: (v) => ({ t: 'C', v: v ? 1 : 0 }),
  floats: (v) => ({ t: 'f', v }),
  doubles: (v) => ({ t: 'd', v }),
  ints: (v) => ({ t: 'i', v }),
  longs: (v) => ({ t: 'l', v }),
};

const utf8 = new TextEncoder();

function propSize(p) {
  switch (p.t) {
    case 'C': return 2;
    case 'I': return 5;
    case 'L': case 'D': return 9;
    case 'S': return 5 + utf8.encode(p.v).byteLength;
    case 'R': return 5 + p.v.byteLength;
    case 'f': case 'i': return 13 + p.v.length * 4;
    case 'l': case 'd': return 13 + p.v.length * 8;
    default: throw new Error('unknown property ' + p.t);
  }
}

/**
 * OB EIN KNOTEN MIT EINEM ABSCHLUSS-BLOCK ENDET.
 *
 * Das war der Fehler, an dem Unity die erste Fassung als "File is corrupted"
 * abgewiesen hat, waehrend Blender und three.js sie anstandslos lasen. Die
 * Regel steht so in Blenders eigenem Exporteur, samt Kommentar ("Awful
 * exceptions"), und sie ist nicht zu erraten:
 *
 *   - ein Knoten MIT Kindern bekommt ihn immer,
 *   - ein Knoten OHNE Eigenschaften bekommt ihn, solange er nicht der letzte
 *     unter seinen Geschwistern ist,
 *   - und `AnimationStack` und `AnimationLayer` bekommen ihn IMMER, auch
 *     ohne Kinder und mit Eigenschaften.
 *
 * Unsere `AnimationLayer` hat Eigenschaften und keine Kinder - genau der
 * Fall, den die dritte Regel abfaengt.
 */
const ALWAYS_SENTINEL = new Set(['AnimationStack', 'AnimationLayer']);

function needsSentinel(n, isLast) {
  if (n.kids.length) return true;
  if (ALWAYS_SENTINEL.has(n.name)) return true;
  return n.props.length === 0 && !isLast;
}

function nodeSize(n, isLast) {
  let size = 13 + utf8.encode(n.name).byteLength;
  for (const p of n.props) size += propSize(p);
  n.kids.forEach((k, i) => { size += nodeSize(k, i === n.kids.length - 1); });
  if (needsSentinel(n, isLast)) size += 13;
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
    if (p.t === 'C') { this.u1(p.v); return; }
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
    const each = p.t === 'l' || p.t === 'd' ? 8 : 4;
    this.u4(p.v.length);
    this.u4(0);
    this.u4(p.v.length * each);
    for (const x of p.v) {
      if (p.t === 'f') this.f4(x);
      else if (p.t === 'd') this.f8(x);
      else if (p.t === 'i') this.i4(x);
      else this.i8(BigInt(x));
    }
  }

  node(n, isLast) {
    const end = this.at + nodeSize(n, isLast);
    const name = utf8.encode(n.name);
    let propBytes = 0;
    for (const p of n.props) propBytes += propSize(p);

    this.u4(end);
    this.u4(n.props.length);
    this.u4(propBytes);
    this.u1(name.byteLength);
    this.blob(name);
    for (const p of n.props) this.prop(p);
    n.kids.forEach((k, i) => this.node(k, i === n.kids.length - 1));
    if (needsSentinel(n, isLast)) for (let i = 0; i < 13; i++) this.u1(0);
    if (this.at !== end) throw new Error('node ' + n.name + ': ' + this.at + ' != ' + end);
  }
}

/**
 * Die drei festen Kennungen, die zusammengehoeren.
 *
 * Eine echte SDK-Datei leitet die Fuss-Kennung aus ihrer `FileId` und ihrer
 * Erstellungszeit ab. Wer das nachbauen will, braucht die Verschluesselung;
 * wer es nicht tut, nimmt EIN zusammenpassendes Tripel und benutzt es immer.
 * Genau das tut Blenders Exporteur seit Jahren, und seine Dateien gehen
 * ueberall hinein - also stehen hier seine drei Werte.
 */
const FILE_ID = new Uint8Array([
  0x28, 0xb3, 0x2a, 0xeb, 0xb6, 0x24, 0xcc, 0xc2,
  0xbf, 0xc8, 0xb0, 0x2a, 0xa9, 0x2b, 0xfc, 0xf1,
]);
const TIME_ID = '1970-01-01 10:00:00:000';
const FOOT_ID = new Uint8Array([
  0xfa, 0xbc, 0xab, 0x09, 0xd0, 0xc8, 0xd4, 0x66,
  0xb1, 0x76, 0xfb, 0x83, 0x1c, 0xf7, 0x26, 0x7e,
]);

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
  roots.forEach((n, i) => { content += nodeSize(n, i === roots.length - 1); });

  //  REIHENFOLGE UND AUFFUELLUNG GENAU SO. Kennung, dann VIER Null-Byte,
  //  dann erst bis zur naechsten Grenze von sechzehn auffuellen - und wenn
  //  es dort schon aufgeht, trotzdem volle sechzehn. Beides andersherum
  //  gemacht ergibt eine Datei, die three.js und Blender noch lesen und der
  //  FBX SDK von Unity nicht mehr.
  const beforePad = 27 + content + 16 + 4;
  let pad = ((beforePad + 15) & ~15) - beforePad;
  if (pad === 0) pad = 16;
  const total = beforePad + pad + 4 + 120 + 16;

  const w = new Writer(total);
  w.blob(utf8.encode('Kaydara FBX Binary  '));
  w.u1(0x00); w.u1(0x1a); w.u1(0x00);
  w.u4(VERSION);

  roots.forEach((n, i) => w.node(n, i === roots.length - 1));
  for (let i = 0; i < 13; i++) w.u1(0);

  w.blob(FOOT_ID);
  w.u4(0);
  for (let i = 0; i < pad; i++) w.u1(0);
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

/** Ein Model-Knoten mit seiner Ruhelage - fuer Knochen, Leerknoten und das Netz dieselbe Form. */
function model(modelId, name, kind, translation, rotationDeg, scale) {
  return node('Model', [P.long(modelId), P.str(objectName(name, 'Model')), P.str(kind)], [
    node('Version', [P.int(232)]),
    node('Properties70', [], [
      node('P', [P.str('InheritType'), P.str('enum'), P.str(''), P.str(''), P.int(1)]),
      node('P', [P.str('DefaultAttributeIndex'), P.str('int'), P.str('Integer'), P.str(''), P.int(0)]),
      prop70('Lcl Translation', 'Lcl Translation', '', 'A+', translation),
      prop70('Lcl Rotation', 'Lcl Rotation', '', 'A+', rotationDeg),
      prop70('Lcl Scaling', 'Lcl Scaling', '', 'A+', scale || [1, 1, 1]),
    ]),
    //  Die vier stehen an JEDEM Model einer echten Datei. Sie sagen nichts
    //  ueber unser Skelett aus - aber eine Form, die ueberall gleich ist,
    //  gibt einem fremden Leser keinen Anlass, es anders zu machen.
    node('MultiLayer', [P.int(0)]),
    node('MultiTake', [P.int(0)]),
    node('Shading', [P.bool(true)]),
    node('Culling', [P.str('CullingOff')]),
  ]);
}

/** Der Knochen-Anhang an einem `LimbNode`. */
function limbAttribute(attrId, name) {
  return node('NodeAttribute', [P.long(attrId), P.str(objectName(name, 'NodeAttribute')), P.str('LimbNode')], [
    node('TypeFlags', [P.str('Skeleton')]),
    node('Properties70', [], [
      node('P', [P.str('Size'), P.str('double'), P.str('Number'), P.str(''), P.double(1)]),
    ]),
  ]);
}

/**
 * Stapel, Ebene und der Weg, eine Spur daran zu haengen.
 *
 * Eine Spur ist ein `AnimationCurveNode` mit drei `AnimationCurve` daran.
 * `KeyAttrFlags`, `KeyAttrDataFloat` und `KeyAttrRefCount` stehen je LAUF,
 * nicht je Schluessel - ein Lauf ueber alle Schluessel genuegt, und die
 * Datei wird dadurch erheblich kleiner. Die Fremddatei schrieb einen Lauf
 * je Schluessel; das ist dieselbe Aussage, nur umstaendlicher.
 */
function animation(objects, conn, id, clipName, frames, fps) {
  const stackId = id();
  const layerId = id();
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

  return { track, stop };
}

/**
 * Drehungen je Bild als drei Euler-Kurven in Grad. `quaternionAt(f)` liefert
 * die Drehung schon im Raum der Datei.
 */
function rotationChannels(frames, quaternionAt) {
  const euler = new Euler();
  const x = new Float32Array(frames);
  const y = new Float32Array(frames);
  const z = new Float32Array(frames);
  let previous = null;
  for (let f = 0; f < frames; f++) {
    const e = euler.setFromQuaternion(quaternionAt(f), 'ZYX');
    const v = previous ? nearestEuler(previous, e) : [e.x, e.y, e.z];
    x[f] = v[0] * DEG; y[f] = v[1] * DEG; z[f] = v[2] * DEG;
    previous = v;
  }
  return [x, y, z];
}

/** Kopf, Einstellungen, Dokument, Definitionen, Objekte, Verbindungen - fuer jede Datei dieselbe Huelle. */
function fbxFile(objects, connections, clipName, fps, stop, docId) {
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
    node('FileId', [P.raw(FILE_ID)]),
    node('CreationTime', [P.str(TIME_ID)]),
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
        //  1 = Zentimeter. Die Zahlen stehen in Zentimetern (TO_CM), also sagt
        //  die Datei das auch - siehe den Kopf dieser Datei zur 100.
        node('P', [P.str('UnitScaleFactor'), P.str('double'), P.str('Number'), P.str(''), P.double(UNIT_SCALE)]),
        node('P', [P.str('OriginalUnitScaleFactor'), P.str('double'), P.str('Number'), P.str(''), P.double(UNIT_SCALE)]),
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

/** Zaehler fuer Kennungen und die Liste der Verbindungen - der Rahmen jeder Datei. */
function sceneParts() {
  let nextId = 1000000;
  const objects = [];
  const connections = [];
  return {
    objects,
    connections,
    id: () => nextId++,
    conn: (...parts) => connections.push(node('C', parts.map((v) =>
      typeof v === 'string' ? P.str(v) : P.long(v)))),
  };
}

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

  const { objects, connections, id, conn } = sceneParts();
  const modelId = bones.map(() => id());
  const attrId = bones.map(() => id());
  const docId = id();

  const euler = new Euler();
  const eulerOf = (q) => euler.setFromQuaternion(turn(q), 'ZYX');
  const rest = restPose(preview).rotations;

  // ---- Die Knochen ------------------------------------------------------
  bones.forEach((bone, i) => {
    //  Die Ruhelage steht in den Eigenschaften des Knotens, die Kurven
    //  schreiben sie beim Abspielen ueber. Unity baut den Avatar aus IHR -
    //  deshalb die T-Pose und nicht Bild 0 (siehe rest-pose.js).
    const e = eulerOf(rest[i]);
    objects.push(model(modelId[i], bone, 'LimbNode', point(preview.rest[i]), [e.x * DEG, e.y * DEG, e.z * DEG]));
    objects.push(limbAttribute(attrId[i], bone));
    conn('OO', attrId[i], modelId[i]);
    conn('OO', modelId[i], parents[i] < 0 ? 0 : modelId[parents[i]]);
  });

  // ---- Die Bewegung -----------------------------------------------------
  const { track, stop } = animation(objects, conn, id, clipName, frames, fps);

  bones.forEach((bone, i) => {
    track(modelId[i], 'Lcl Rotation',
      rotationChannels(frames, (f) => turn(preview.rotations[f].slice(i * 4, i * 4 + 4))));
  });

  //  Und die Wurzel wandert. Ohne diese Spur laeuft jeder Schritt auf der
  //  Stelle - die Beine gehen, die Figur kommt nicht vom Fleck.
  const rx = new Float32Array(frames);
  const ry = new Float32Array(frames);
  const rz = new Float32Array(frames);
  for (let f = 0; f < frames; f++) {
    [rx[f], ry[f], rz[f]] = point(preview.hips[f]);
  }
  track(modelId[0], 'Lcl Translation', [rx, ry, rz]);

  return fbxFile(objects, connections, clipName, fps, stop, docId);
}

// ---- Mit Figur ------------------------------------------------------------

/*
 * DIE BEWEGUNG AUF DEM MANNEQUIN, NETZ UND HAUT EINGESCHLOSSEN - wie "With
 * Skin" bei Mixamo. Bis 2026-09-26 gab es die Figur nur als .glb, und die
 * liest Unity ohne Zusatzpaket nicht.
 *
 * DIE QUELLE IST DIESELBE DATEI, DIE DIE BUEHNE ZEIGT (`aw-mannequin.glb`),
 * und die Bewegung dieselbe, die `bakeFromStage` fuer die .glb abschreibt.
 * Nichts wird neu gerechnet oder geschaetzt; es wird nur umgeschrieben.
 *
 * DREI POSEN, DIE MAN AUSEINANDERHALTEN MUSS:
 *   Ruhelage der Knoten  die Avatar-T-Pose (`extras.pose = "tpose"`). Sie wird
 *                        die Ruhelage der Knochen in der Datei - Unity baut
 *                        den Humanoid-Avatar aus ihr.
 *   Bindepose            aus den inversen Bindematrizen der Haut. Sie wird die
 *                        Bindepose der Datei (`TransformLink`, `BindPose`).
 *                        Damit verformt die Haut EXAKT wie auf der Buehne -
 *                        eine Neubindung in der T-Pose waere an Schulter und
 *                        Daumen sichtbar anders (dort liegen 20-27 Grad
 *                        zwischen beiden).
 *   Netzraum             Die .glb speichert das Netz quantisiert (16 Bit, um
 *                        90 Grad gekippt, der Massstab steckt in den
 *                        Bindematrizen). So abgeschrieben laege es in der FBX
 *                        auf dem Ruecken und in falscher Groesse - Umrisse und
 *                        Sichtbarkeitspruefung eines Importeurs gingen davon
 *                        aus. Deshalb wird es mit G = (Huefte in Ruhe) x
 *                        (ihre inverse Bindematrix) aufgerichtet, und jede
 *                        Bindematrix bekommt G^-1 dazu: J * IBM * G^-1 * G * v
 *                        ist dieselbe Verformung.
 *
 * DER RAUM. Das Mannequin kam aus Unity per Spiegelung an z in die .glb (es
 * schaut dort nach -z); Unitys FBX-Importeur spiegelt an x. Beides zusammen
 * ist eine Drehung um 180 Grad um y - und genau die bekommt hier alles
 * (Punkte (-x, y, -z), Drehungen (-x, y, -z, w), Matrizen R*M*R). Keine
 * Spiegelung, also bleibt die Wickelrichtung der Dreiecke, und die Figur
 * steht in Unity wieder so da, wie sie im Paket liegt.
 */

const GL_BYTE = 5120;
const GL_UNSIGNED_BYTE = 5121;
const GL_SHORT = 5122;
const GL_UNSIGNED_SHORT = 5123;
const GL_UNSIGNED_INT = 5125;
const GL_FLOAT = 5126;
const COMPONENTS = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT4: 16 };

/** Ein Zahlenfeld der .glb als gewoehnliche Zahlen - mit der Normalisierung, die KHR_mesh_quantization verlangt. */
function readAccessor(json, binary, index) {
  const accessor = json.accessors[index];
  const view = json.bufferViews[accessor.bufferView];
  const width = COMPONENTS[accessor.type];
  const type = accessor.componentType;
  const size = { [GL_BYTE]: 1, [GL_UNSIGNED_BYTE]: 1, [GL_SHORT]: 2, [GL_UNSIGNED_SHORT]: 2,
    [GL_UNSIGNED_INT]: 4, [GL_FLOAT]: 4 }[type];
  const stride = view.byteStride || width * size;
  const start = (view.byteOffset || 0) + (accessor.byteOffset || 0);
  const data = new DataView(binary.buffer, binary.byteOffset, binary.byteLength);

  const read = {
    [GL_BYTE]: (at) => data.getInt8(at),
    [GL_UNSIGNED_BYTE]: (at) => data.getUint8(at),
    [GL_SHORT]: (at) => data.getInt16(at, true),
    [GL_UNSIGNED_SHORT]: (at) => data.getUint16(at, true),
    [GL_UNSIGNED_INT]: (at) => data.getUint32(at, true),
    [GL_FLOAT]: (at) => data.getFloat32(at, true),
  }[type];
  const scale = !accessor.normalized ? null : {
    [GL_BYTE]: (v) => Math.max(v / 127, -1),
    [GL_UNSIGNED_BYTE]: (v) => v / 255,
    [GL_SHORT]: (v) => Math.max(v / 32767, -1),
    [GL_UNSIGNED_SHORT]: (v) => v / 65535,
  }[type];

  const out = new Float64Array(accessor.count * width);
  for (let i = 0; i < accessor.count; i++) {
    for (let k = 0; k < width; k++) {
      const v = read(start + i * stride + k * size);
      out[i * width + k] = scale ? scale(v) : v;
    }
  }
  return out;
}

/** Knotenname, wie ihn GLTFLoader hinterlaesst - die Buehne kennt nur diesen (siehe glb-export.js). */
const boneKey = (name) => name.replace(/[[\]./:]/g, '').replace(/\s/g, '_');

/** Die 180-Grad-Drehung um y, in Zentimetern: fuer Punkte, Drehungen, Matrizen. */
const R_POINT = (x, y, z) => [-x * TO_CM, y * TO_CM, -z * TO_CM];
const R_TURN = (q) => new Quaternion(-q[0], q[1], -q[2], q[3]).normalize();
function R_MATRIX(m) {
  //  R*M*R mit R = diag(-1, 1, -1, 1): Eintrag (i, j) mal s_i * s_j. Dazu die
  //  Verschiebung in Zentimeter. `elements` ist spaltenweise, also Spalte j.
  const s = [-1, 1, -1, 1];
  const e = m.elements.slice();
  for (let col = 0; col < 4; col++) {
    for (let row = 0; row < 4; row++) e[col * 4 + row] *= s[row] * s[col];
  }
  e[12] *= TO_CM; e[13] *= TO_CM; e[14] *= TO_CM;
  return e;
}

/** Linear (glTF) nach sRGB (FBX-Farben sind, was ein Farbwaehler zeigt). */
const toSrgb = (c) => (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);

/**
 * Das Mannequin mit der Bewegung als `.fbx`.
 *
 * @param mannequin die Bytes von `aw-mannequin.glb`
 * @param tracks    `bakeFromStage(...)` - je Knochen die lokalen Drehungen je
 *                  Bild (glTF-Raum, wie die Buehne sie setzt), an der Huefte
 *                  dazu die Verschiebung
 * @returns `{ bytes, missing }` - wie `injectAnimation`: Spuren, deren Knochen
 *          es in der Figur nicht gibt, stehen in `missing`.
 */
export function characterFbx(mannequin, tracks, options = {}) {
  const clipName = options.name || 'Clip';
  const fps = options.frameRate || 30;
  const frames = options.frames;
  const { json, binary } = openGlb(mannequin);

  const nodes = json.nodes || [];
  const skin = json.skins && json.skins[0];
  const meshIndex = nodes.findIndex((n) => n.mesh !== undefined && n.skin !== undefined);
  if (!skin || meshIndex < 0) throw new Error('The figure carries no skinned mesh.');
  const meshNode = nodes[meshIndex];
  const primitives = json.meshes[meshNode.mesh].primitives;

  const parentOf = new Array(nodes.length).fill(-1);
  nodes.forEach((n, i) => (n.children || []).forEach((c) => { parentOf[c] = i; }));
  const jointSet = new Set(skin.joints);

  // ---- Ruhelage der Knoten (glTF-Raum) ---------------------------------
  const localOf = nodes.map((n) => {
    const m = new Matrix4();
    if (n.matrix) return m.fromArray(n.matrix);
    return m.compose(
      new Vector3().fromArray(n.translation || [0, 0, 0]),
      new Quaternion().fromArray(n.rotation || [0, 0, 0, 1]),
      new Vector3().fromArray(n.scale || [1, 1, 1]));
  });
  const worldOf = [];
  const world = (i) => {
    if (worldOf[i]) return worldOf[i];
    const m = parentOf[i] < 0 ? localOf[i].clone() : world(parentOf[i]).clone().multiply(localOf[i]);
    worldOf[i] = m;
    return m;
  };

  // ---- Netzraum aufrichten: G = Huefte in Ruhe x ihre IBM ---------------
  const ibmData = readAccessor(json, binary, skin.inverseBindMatrices);
  const ibm = skin.joints.map((_, j) => new Matrix4().fromArray(ibmData, j * 16));
  const hipsJoint = skin.joints.findIndex((n) => /hips|pelvis/i.test(nodes[n].name || ''));
  const anchor = hipsJoint >= 0 ? hipsJoint : 0;
  const G = world(skin.joints[anchor]).clone().multiply(ibm[anchor]);
  const Ginv = G.clone().invert();
  //  Normalen mit der Inversen-Transponierten (ohne Verschiebung, danach normiert).
  const normalG = G.clone().invert().transpose();

  // ---- Das Netz ---------------------------------------------------------
  //  Alle Teile teilen sich dieselben Ecken (so liegt es in der Datei):
  //  EINE Geometrie, und je Dreieck steht dabei, welches Material es traegt.
  const attributes = primitives[0].attributes;
  for (const p of primitives) {
    if (p.attributes.POSITION !== attributes.POSITION)
      throw new Error('The figure parts do not share their vertices.');
  }

  const positions = readAccessor(json, binary, attributes.POSITION);
  const normals = attributes.NORMAL !== undefined ? readAccessor(json, binary, attributes.NORMAL) : null;
  const uvs = attributes.TEXCOORD_0 !== undefined ? readAccessor(json, binary, attributes.TEXCOORD_0) : null;
  const joints = readAccessor(json, binary, attributes.JOINTS_0);
  const weights = readAccessor(json, binary, attributes.WEIGHTS_0);
  const vertexCount = positions.length / 3;

  const vertices = new Float64Array(vertexCount * 3);
  const vertexNormals = normals ? new Float64Array(vertexCount * 3) : null;
  const p = new Vector3();
  for (let v = 0; v < vertexCount; v++) {
    p.set(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]).applyMatrix4(G);
    vertices.set(R_POINT(p.x, p.y, p.z), v * 3);
    if (normals) {
      p.set(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2]).transformDirection(normalG);
      vertexNormals.set([-p.x, p.y, -p.z], v * 3);
    }
  }

  //  Dreiecke: der letzte Eckenindex eines Polygons steht bitweise negiert.
  const polygonVertexIndex = [];
  const polygonMaterial = [];
  primitives.forEach((primitive, materialSlot) => {
    const indices = readAccessor(json, binary, primitive.indices);
    for (let t = 0; t < indices.length; t += 3) {
      polygonVertexIndex.push(indices[t], indices[t + 1], ~indices[t + 2]);
      polygonMaterial.push(materialSlot);
    }
  });

  //  UV je Polygonecke ueber einen Index auf die Ecke: dieselben Werte wie
  //  die Ecken, nur mit v gekippt (glTF zaehlt von oben, FBX von unten).
  const uvValues = uvs ? new Float64Array(vertexCount * 2) : null;
  if (uvs) for (let v = 0; v < vertexCount; v++) {
    uvValues[v * 2] = uvs[v * 2];
    uvValues[v * 2 + 1] = 1 - uvs[v * 2 + 1];
  }
  const uvIndex = polygonVertexIndex.map((i) => (i < 0 ? ~i : i));

  const { objects, connections, id, conn } = sceneParts();
  const docId = id();

  // ---- Knoten: Leerknoten und Knochen -----------------------------------
  const modelIds = nodes.map(() => null);
  const euler = new Euler();
  nodes.forEach((n, i) => {
    if (i === meshIndex) return;
    modelIds[i] = id();
  });
  nodes.forEach((n, i) => {
    if (i === meshIndex) return;
    const t = new Vector3();
    const q = new Quaternion();
    const s = new Vector3();
    localOf[i].decompose(t, q, s);
    const e = euler.setFromQuaternion(R_TURN([q.x, q.y, q.z, q.w]), 'ZYX');
    const name = n.name || 'Node' + i;
    const isJoint = jointSet.has(i);
    objects.push(model(modelIds[i], name, isJoint ? 'LimbNode' : 'Null',
      R_POINT(t.x, t.y, t.z), [e.x * DEG, e.y * DEG, e.z * DEG], [s.x, s.y, s.z]));
    if (isJoint) {
      const attrId = id();
      objects.push(limbAttribute(attrId, name));
      conn('OO', attrId, modelIds[i]);
    }
    conn('OO', modelIds[i], parentOf[i] < 0 ? 0 : modelIds[parentOf[i]]);
  });

  // ---- Das Netz als Objekt ----------------------------------------------
  const meshName = meshNode.name || 'Mesh';
  const meshModelId = id();
  const geometryId = id();
  objects.push(model(meshModelId, meshName, 'Mesh', [0, 0, 0], [0, 0, 0], [1, 1, 1]));
  //  Das Netz haengt, wo es in der .glb haengt - an einem Knoten ohne
  //  Drehung. Der glTF-Standard ueberspringt die Lage eines gehaeuteten
  //  Netzes ohnehin; seine Ecken stehen schon im Raum der Szene.
  conn('OO', meshModelId, parentOf[meshIndex] < 0 ? 0 : modelIds[parentOf[meshIndex]]);

  const layerElements = [];
  const geometryKids = [
    node('Properties70', []),
    node('GeometryVersion', [P.int(124)]),
    node('Vertices', [P.doubles(vertices)]),
    node('PolygonVertexIndex', [P.ints(polygonVertexIndex)]),
  ];
  if (vertexNormals) {
    geometryKids.push(node('LayerElementNormal', [P.int(0)], [
      node('Version', [P.int(101)]),
      node('Name', [P.str('')]),
      node('MappingInformationType', [P.str('ByVertice')]),
      node('ReferenceInformationType', [P.str('Direct')]),
      node('Normals', [P.doubles(vertexNormals)]),
    ]));
    layerElements.push('LayerElementNormal');
  }
  if (uvValues) {
    geometryKids.push(node('LayerElementUV', [P.int(0)], [
      node('Version', [P.int(101)]),
      node('Name', [P.str('UVMap')]),
      node('MappingInformationType', [P.str('ByPolygonVertex')]),
      node('ReferenceInformationType', [P.str('IndexToDirect')]),
      node('UV', [P.doubles(uvValues)]),
      node('UVIndex', [P.ints(uvIndex)]),
    ]));
    layerElements.push('LayerElementUV');
  }
  geometryKids.push(node('LayerElementMaterial', [P.int(0)], [
    node('Version', [P.int(101)]),
    node('Name', [P.str('')]),
    node('MappingInformationType', [P.str('ByPolygon')]),
    node('ReferenceInformationType', [P.str('IndexToDirect')]),
    node('Materials', [P.ints(polygonMaterial)]),
  ]));
  layerElements.push('LayerElementMaterial');
  geometryKids.push(node('Layer', [P.int(0)], [node('Version', [P.int(100)])].concat(
    layerElements.map((type) => node('LayerElement', [], [
      node('Type', [P.str(type)]),
      node('TypedIndex', [P.int(0)]),
    ])))));

  objects.push(node('Geometry', [P.long(geometryId), P.str(objectName(meshName, 'Geometry')), P.str('Mesh')],
    geometryKids));
  conn('OO', geometryId, meshModelId);

  // ---- Materialien: nur die Farbe - das Mannequin hat keine Texturen ----
  primitives.forEach((primitive, slot) => {
    const source = (json.materials || [])[primitive.material] || {};
    const color = ((source.pbrMetallicRoughness || {}).baseColorFactor || [0.8, 0.8, 0.8, 1]).slice(0, 3).map(toSrgb);
    const materialId = id();
    objects.push(node('Material', [P.long(materialId), P.str(objectName(source.name || 'Material' + slot, 'Material')), P.str('')], [
      node('Version', [P.int(102)]),
      node('ShadingModel', [P.str('Phong')]),
      node('MultiLayer', [P.int(0)]),
      node('Properties70', [], [
        prop70('DiffuseColor', 'Color', '', 'A', color),
        prop70('DiffuseFactor', 'Number', '', 'A', [1]),
        prop70('SpecularColor', 'Color', '', 'A', [0.2, 0.2, 0.2]),
        prop70('SpecularFactor', 'Number', '', 'A', [0.2]),
        prop70('ShininessExponent', 'Number', '', 'A', [20]),
        prop70('Opacity', 'double', 'Number', '', [1]),
      ]),
    ]));
    //  Die Reihenfolge der Verbindungen ist die Nummer des Materials.
    conn('OO', materialId, meshModelId);
  });

  // ---- Die Haut ---------------------------------------------------------
  const skinId = id();
  objects.push(node('Deformer', [P.long(skinId), P.str(objectName(meshName, 'Deformer')), P.str('Skin')], [
    node('Version', [P.int(101)]),
    node('Link_DeformAcuracy', [P.double(50)]),
  ]));
  conn('OO', skinId, geometryId);

  //  Gewichte je Gelenk einsammeln: die .glb sagt je Ecke "diese vier",
  //  FBX je Knochen "diese Ecken".
  const perJoint = skin.joints.map(() => ({ indexes: [], weights: [] }));
  for (let v = 0; v < vertexCount; v++) {
    for (let k = 0; k < 4; k++) {
      const w = weights[v * 4 + k];
      if (w <= 0) continue;
      const slot = perJoint[joints[v * 4 + k]];
      slot.indexes.push(v);
      slot.weights.push(w);
    }
  }

  const identity = new Matrix4();
  const bindOf = skin.joints.map((_, j) => G.clone().multiply(ibm[j].clone().invert()));
  skin.joints.forEach((nodeIndex, j) => {
    const slot = perJoint[j];
    if (slot.indexes.length === 0) return;
    const clusterId = id();
    objects.push(node('Deformer', [P.long(clusterId), P.str(objectName(nodes[nodeIndex].name || 'Joint' + j, 'SubDeformer')), P.str('Cluster')], [
      node('Version', [P.int(100)]),
      node('UserData', [P.str(''), P.str('')]),
      node('Indexes', [P.ints(slot.indexes)]),
      node('Weights', [P.doubles(slot.weights)]),
      //  Wo das Netz beim Binden stand (Ursprung) und wo der Knochen.
      node('Transform', [P.doubles(R_MATRIX(bindOf[j].clone().invert()))]),
      node('TransformLink', [P.doubles(R_MATRIX(bindOf[j]))]),
    ]));
    conn('OO', clusterId, skinId);
    conn('OO', modelIds[nodeIndex], clusterId);
  });

  //  Dieselbe Bindepose noch einmal als `Pose`: Blender baut seine
  //  Ruheknochen daraus, auch fuer Knochen ohne eigene Ecken.
  const poseId = id();
  const poseNodes = [node('PoseNode', [], [
    node('Node', [P.long(meshModelId)]),
    node('Matrix', [P.doubles(R_MATRIX(identity))]),
  ])].concat(skin.joints.map((nodeIndex, j) => node('PoseNode', [], [
    node('Node', [P.long(modelIds[nodeIndex])]),
    node('Matrix', [P.doubles(R_MATRIX(bindOf[j]))]),
  ])));
  objects.push(node('Pose', [P.long(poseId), P.str(objectName('BindPose', 'Pose')), P.str('BindPose')], [
    node('Type', [P.str('BindPose')]),
    node('Version', [P.int(100)]),
    node('NbPoseNodes', [P.int(poseNodes.length)]),
  ].concat(poseNodes)));

  // ---- Die Bewegung -----------------------------------------------------
  const { track, stop } = animation(objects, conn, id, clipName, frames, fps);
  const byKey = new Map();
  nodes.forEach((n, i) => { if (n.name && i !== meshIndex) byKey.set(boneKey(n.name), i); });

  const missing = [];
  for (const t of tracks) {
    const nodeIndex = byKey.get(boneKey(t.bone));
    if (nodeIndex === undefined) { missing.push(t.bone); continue; }
    const target = modelIds[nodeIndex];
    if (t.rotations) {
      const r = t.rotations;
      track(target, 'Lcl Rotation',
        rotationChannels(frames, (f) => R_TURN([r[f * 4], r[f * 4 + 1], r[f * 4 + 2], r[f * 4 + 3]])));
    }
    if (t.translations) {
      const x = new Float32Array(frames);
      const y = new Float32Array(frames);
      const z = new Float32Array(frames);
      for (let f = 0; f < frames; f++) {
        [x[f], y[f], z[f]] = R_POINT(t.translations[f * 3], t.translations[f * 3 + 1], t.translations[f * 3 + 2]);
      }
      track(target, 'Lcl Translation', [x, y, z]);
    }
  }
  if (missing.length === tracks.length) throw new Error('None of the clip bones exist in this figure.');

  return { bytes: fbxFile(objects, connections, clipName, fps, stop, docId), missing };
}
