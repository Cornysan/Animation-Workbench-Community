/**
 * Einen Clip als `.glb` herausgeben.
 *
 * WARUM DAS HIER STEHT UND NICHT AUF DEM SERVER. Alles, was dafuer noetig
 * ist, liegt ohnehin schon im Browser: die Vorschau kommt fuer die Buehne,
 * das Mannequin ebenso. Auf dem Server muesste beides noch einmal geholt,
 * gerechnet und als Datei ausgeliefert werden - fuer jeden Abruf neu und auf
 * unsere Rechnung. Hier kostet es nichts und geht ohne Wartezeit.
 *
 * WARUM ES FAST NUR ABSCHREIBEN IST. Eine Vorschau traegt genau das, was
 * glTF fuer eine Animation verlangt:
 *
 *   preview.parents    die Hierarchie
 *   preview.rest[i]    der Versatz eines Knochens IM ELTERNRAUM  -> node.translation
 *   preview.rotations  die Drehung je Bild, LOKAL                -> channel "rotation"
 *   preview.hips[f]    die Wurzel je Bild, in Weltkoordinaten    -> channel "translation"
 *
 * Nichts zu retargeten - aber EINE Umrechnung. Hier stand bis zum 2026-09-24,
 * beide Seiten seien rechtshaendig und die Spiegelung beim Schreiben der
 * Vorschau laengst passiert. Das stimmte nie: die Vorschau steht in Unitys
 * Raum, und der ist linkshaendig (`AWClipPreviewBaker` sagt es so). Die Datei
 * kam seitenverkehrt an. Gespiegelt wird jetzt an der YZ-Ebene - Punkte
 * (-x, y, z), Drehungen (x, -y, -z, w) -, denn glTF will die Vorderseite bei
 * +Z, und dort steht sie in Unity auch; Unitys glTF-Importeure spiegeln
 * genau so zurueck.
 *
 * Und eine Ruhelage: die T-Pose der Quellfigur als `node.rotation`, siehe
 * `rest-pose.js`. Ohne sie ruht das Skelett mit lauter Einheitsdrehungen -
 * eine Figur, die auf einer Geraden liegt.
 */

import { restPose } from './rest-pose.js';

/** Unity -> glTF: an der YZ-Ebene gespiegelt. */
const point = (v) => [-v[0], v[1], v[2]];
const turn = (q) => [q[0], -q[1], -q[2], q[3]];

const MAGIC = 0x46546c67;        // "glTF"
const CHUNK_JSON = 0x4e4f534a;   // "JSON"
const CHUNK_BIN = 0x004e4942;    // "BIN"
const FLOAT = 5126;

/**
 * Sammelt die Zahlenfelder und merkt sich, wo jedes liegt.
 *
 * glTF verlangt, dass jede Ansicht an einer durch 4 teilbaren Stelle
 * beginnt. Alles hier ist `float32`, also ist das ohnehin erfuellt - der
 * Ausgleich steht trotzdem da, weil die Regel nicht an unserem Datentyp
 * haengt und die naechste Erweiterung sonst still falsch liegt.
 */
class BufferBuilder {
  constructor() {
    this.parts = [];
    this.length = 0;
    this.views = [];
    this.accessors = [];
  }

  /** Ein Feld ablegen und die Nummer seiner Ansicht zurueckgeben. */
  view(data) {
    while (this.length % 4 !== 0) { this.parts.push(new Uint8Array(1)); this.length += 1; }
    const bytes = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    this.views.push({ buffer: 0, byteOffset: this.length, byteLength: bytes.byteLength });
    this.parts.push(bytes);
    this.length += bytes.byteLength;
    return this.views.length - 1;
  }

  /**
   * Ein Feld als Zugriff anmelden.
   *
   * `min`/`max` sind fuer die ZEITACHSE Pflicht - ohne sie weiss ein Leser
   * nicht, wie lang die Animation ist, und manche weigern sich rundheraus.
   */
  accessor(data, type, count, { min, max } = {}) {
    const entry = { bufferView: this.view(data), componentType: FLOAT, count, type };
    if (min) entry.min = min;
    if (max) entry.max = max;
    this.accessors.push(entry);
    return this.accessors.length - 1;
  }

  finish() {
    const out = new Uint8Array(this.length);
    let at = 0;
    for (const part of this.parts) { out.set(part, at); at += part.byteLength; }
    return out;
  }
}

/** Die Zeitachse: ein Bild je Eintrag, in Sekunden. */
function timeline(frames, frameRate) {
  const times = new Float32Array(frames);
  for (let f = 0; f < frames; f++) times[f] = f / frameRate;
  return times;
}

/**
 * Der Container. Zwei Bloecke hinter einem Kopf von zwoelf Byte, jeder auf
 * ein Vielfaches von vier aufgefuellt - JSON mit Leerzeichen, weil ein
 * Nullbyte mitten im Text manche Leser aus dem Tritt bringt, der Binaerblock
 * mit Nullen.
 */
function container(json, binary) {
  const text = new TextEncoder().encode(JSON.stringify(json));
  const jsonPad = (4 - (text.byteLength % 4)) % 4;
  const binPad = (4 - (binary.byteLength % 4)) % 4;

  const total = 12 + 8 + text.byteLength + jsonPad + 8 + binary.byteLength + binPad;
  const out = new Uint8Array(total);
  const view = new DataView(out.buffer);
  let at = 0;

  view.setUint32(at, MAGIC, true); at += 4;
  view.setUint32(at, 2, true); at += 4;
  view.setUint32(at, total, true); at += 4;

  view.setUint32(at, text.byteLength + jsonPad, true); at += 4;
  view.setUint32(at, CHUNK_JSON, true); at += 4;
  out.set(text, at); at += text.byteLength;
  for (let i = 0; i < jsonPad; i++) out[at++] = 0x20;

  view.setUint32(at, binary.byteLength + binPad, true); at += 4;
  view.setUint32(at, CHUNK_BIN, true); at += 4;
  out.set(binary, at); at += binary.byteLength;
  for (let i = 0; i < binPad; i++) out[at++] = 0x00;

  return out;
}

/**
 * Der Clip als Skelett - ohne Figur.
 *
 * Das ist die ehrliche Form dessen, was ein Clip IST: eine Bewegung, keine
 * Figur. Wer sie auf sein eigenes Modell legen will, bekommt hier genau die
 * Knochen mit ihren Namen und nichts weiter.
 */
export function skeletonGlb(preview, options = {}) {
  const name = options.name || 'clip';
  const bones = preview.bones;
  const parents = preview.parents;
  const frames = preview.hips.length;
  const frameRate = preview.frameRate || 30;

  const buffer = new BufferBuilder();
  const times = timeline(frames, frameRate);
  const timeAccessor = buffer.accessor(times, 'SCALAR', frames, {
    min: [times[0]], max: [times[frames - 1]],
  });

  const rest = restPose(preview).rotations;
  const nodes = bones.map((bone, i) => ({
    name: bone, translation: point(preview.rest[i]), rotation: turn(rest[i]), children: [],
  }));
  parents.forEach((parent, i) => { if (parent >= 0) nodes[parent].children.push(i); });
  for (const node of nodes) if (node.children.length === 0) delete node.children;

  const samplers = [];
  const channels = [];

  //  Eine Drehspur je Knochen. Die Werte stehen in der Vorschau schon lokal
  //  und in der Reihenfolge x,y,z,w - glTF will nur die andere Haendigkeit.
  for (let i = 0; i < bones.length; i++) {
    const values = new Float32Array(frames * 4);
    for (let f = 0; f < frames; f++) {
      values.set(turn(preview.rotations[f].slice(i * 4, i * 4 + 4)), f * 4);
    }
    samplers.push({ input: timeAccessor, output: buffer.accessor(values, 'VEC4', frames), interpolation: 'LINEAR' });
    channels.push({ sampler: samplers.length - 1, target: { node: i, path: 'rotation' } });
  }

  //  Und die Wurzel wandert. Ohne diese Spur laeuft jeder Schritt auf der
  //  Stelle - die Beine gehen, die Figur kommt nicht vom Fleck.
  const roots = new Float32Array(frames * 3);
  for (let f = 0; f < frames; f++) roots.set(point(preview.hips[f]), f * 3);
  samplers.push({ input: timeAccessor, output: buffer.accessor(roots, 'VEC3', frames), interpolation: 'LINEAR' });
  channels.push({ sampler: samplers.length - 1, target: { node: 0, path: 'translation' } });

  const binary = buffer.finish();
  const json = {
    asset: { version: '2.0', generator: 'Animation Workbench Community' },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes,
    animations: [{ name, samplers, channels }],
    buffers: [{ byteLength: binary.byteLength }],
    bufferViews: buffer.views,
    accessors: buffer.accessors,
  };

  return container(json, binary);
}

// ── Mit Figur ────────────────────────────────────────────────────────────

/**
 * Knochennamen, wie ein glTF-Leser sie hinterlaesst.
 *
 * Der Rigify-Rest des Mannequins heisst `DEF-spine.001`; `GLTFLoader` wirft
 * beim Laden `. [ ] : /` weg, also heisst derselbe Knochen in der Szene
 * `DEF-spine001`. Wer im JSON der Datei nachschlaegt, findet den einen, wer
 * in der geladenen Szene nachsieht, den anderen - und ohne diesen Schluessel
 * finden sie sich nicht wieder.
 */
const boneKey = (name) => name.replace(/[[\]./:]/g, '').replace(/\s/g, '_');

/** Den Container aufmachen: JSON-Block und Binaerblock einer .glb. */
function openGlb(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint32(0, true) !== MAGIC) throw new Error('That is not a .glb.');

  let at = 12;
  let json = null;
  let binary = new Uint8Array(0);
  while (at + 8 <= bytes.byteLength) {
    const length = view.getUint32(at, true);
    const kind = view.getUint32(at + 4, true);
    const body = new Uint8Array(bytes.buffer, bytes.byteOffset + at + 8, length);
    if (kind === CHUNK_JSON) json = JSON.parse(new TextDecoder().decode(body));
    else if (kind === CHUNK_BIN) binary = body;
    at += 8 + length;
  }
  if (!json) throw new Error('This .glb carries no scene.');
  return { json, binary };
}

/**
 * Eine Animation in eine fertige `.glb` HINEINLEGEN, statt eine neue zu
 * schreiben.
 *
 * Der Umweg ist der kurze. Eine Figur selbst auszugeben hiesse Netz,
 * Material, Textur, Haut und Bindematrizen neu zu schreiben - viel Arbeit,
 * und jede Zeile davon eine Gelegenheit, das Mannequin schlechter aussehen
 * zu lassen, als es ist. Die Datei liegt fertig auf dem Server. Wir haengen
 * ihre Zahlenfelder hinten an, melden ein paar Zugriffe nach und schreiben
 * den Container neu; das Modell selbst wird dabei nicht angefasst.
 *
 * `tracks` ist eine Liste aus `{ bone, rotations, translations }`, die Werte
 * je Bild und im LOKALEN Raum des Knochens - genau, wie die Buehne sie eben
 * gesetzt hat.
 */
export function injectAnimation(mannequin, tracks, options = {}) {
  const { json, binary } = openGlb(mannequin);
  const name = options.name || 'clip';
  const frameRate = options.frameRate || 30;
  const frames = options.frames;

  //  Namen der Datei auf ihre Nummer. Nachgeschlagen wird ueber den
  //  bereinigten Namen, weil die Buehne nur den kennt.
  const byKey = new Map();
  (json.nodes || []).forEach((node, i) => {
    if (node.name) byKey.set(boneKey(node.name), i);
  });

  json.bufferViews = json.bufferViews || [];
  json.accessors = json.accessors || [];
  json.buffers = json.buffers && json.buffers.length ? json.buffers : [{ byteLength: 0 }];

  //  Die neuen Felder kommen HINTER den vorhandenen Binaerblock. Sein Ende
  //  wird auf ein Vielfaches von vier gebracht, sonst beginnt die erste
  //  neue Ansicht schief.
  const extra = [];
  let extraLength = 0;
  const pad = (4 - (binary.byteLength % 4)) % 4;
  if (pad) { extra.push(new Uint8Array(pad)); extraLength += pad; }
  const base = binary.byteLength + pad;

  const addView = (data) => {
    while (extraLength % 4 !== 0) { extra.push(new Uint8Array(1)); extraLength += 1; }
    const bytes = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    json.bufferViews.push({ buffer: 0, byteOffset: base + extraLength, byteLength: bytes.byteLength });
    extra.push(bytes);
    extraLength += bytes.byteLength;
    return json.bufferViews.length - 1;
  };
  const addAccessor = (data, type, count, extras) => {
    json.accessors.push(Object.assign(
      { bufferView: addView(data), componentType: FLOAT, count, type }, extras || {}));
    return json.accessors.length - 1;
  };

  const times = timeline(frames, frameRate);
  const timeAccessor = addAccessor(times, 'SCALAR', frames, {
    min: [times[0]], max: [times[frames - 1]],
  });

  const samplers = [];
  const channels = [];
  const missing = [];

  for (const track of tracks) {
    const node = byKey.get(boneKey(track.bone));
    if (node === undefined) { missing.push(track.bone); continue; }
    if (track.rotations) {
      samplers.push({ input: timeAccessor, output: addAccessor(track.rotations, 'VEC4', frames), interpolation: 'LINEAR' });
      channels.push({ sampler: samplers.length - 1, target: { node, path: 'rotation' } });
    }
    if (track.translations) {
      samplers.push({ input: timeAccessor, output: addAccessor(track.translations, 'VEC3', frames), interpolation: 'LINEAR' });
      channels.push({ sampler: samplers.length - 1, target: { node, path: 'translation' } });
    }
  }

  if (channels.length === 0) throw new Error('None of the clip bones exist in this figure.');

  json.animations = [{ name, samplers, channels }];
  json.asset = Object.assign({}, json.asset, { generator: 'Animation Workbench Community' });

  const merged = new Uint8Array(base + extraLength);
  merged.set(binary, 0);
  let at = base;
  for (const part of extra) { merged.set(part, at); at += part.byteLength; }
  json.buffers[0] = { byteLength: merged.byteLength };

  return { bytes: container(json, merged), missing };
}

/**
 * Die Pose der Buehne abschreiben, Bild fuer Bild.
 *
 * DIE BUEHNE IST DIE UMRECHNUNG. Was hier gebraucht wird - die Bewegung
 * einer Vorschau, uebersetzt auf das Skelett DIESER Figur - rechnet sie
 * ohnehin bei jedem Bild aus, samt der Korrektur je Knochen aus der
 * Bindepose. Sie ein zweites Mal aufzuschreiben hiesse, zwei Fassungen
 * derselben schwierigen Rechnung zu pflegen; die zweite waere die, die
 * niemand ansieht, und sie wuerde als Erste falsch.
 *
 * Also wird gefragt statt gerechnet: einmal durch alle Bilder, und nach
 * jedem steht die Antwort in `bone.quaternion`.
 */
export function bakeFromStage(stage, frames) {
  const bones = stage.bones;
  const rotations = bones.map(() => new Float32Array(frames * 4));
  const root = new Float32Array(frames * 3);
  const hips = stage.hipsBone;

  for (let f = 0; f < frames; f++) {
    stage.applyFrame(f);
    bones.forEach((bone, i) => {
      const q = bone.quaternion;
      rotations[i][f * 4] = q.x;
      rotations[i][f * 4 + 1] = q.y;
      rotations[i][f * 4 + 2] = q.z;
      rotations[i][f * 4 + 3] = q.w;
    });
    root[f * 3] = hips.position.x;
    root[f * 3 + 1] = hips.position.y;
    root[f * 3 + 2] = hips.position.z;
  }

  return bones.map((bone, i) => ({
    bone: bone.name,
    rotations: rotations[i],
    translations: bone === hips ? root : null,
  }));
}
