/**
 * Ein fremdes Skelett lesen.
 *
 * ── Das Problem ──────────────────────────────────────────────────────────
 *
 * Eine Vorschau nennt ihre Knochen wie Unity sie nennt: `LeftUpperArm`. Das
 * Skelett in einer fremden Datei nennt denselben Knochen `mixamorig:LeftArm`,
 * `upperarm_l`, `UpperArmL`, `DEF-upper_arm.L` oder `CC_Base_L_Upperarm`.
 * Ohne eine Bruecke zwischen beiden Namen laesst sich kein Clip auf die Figur
 * rechnen - die Datei aus der Workbench bringt sie mit (`extras.humanoid`),
 * eine .fbx von Mixamo oder eine .glb aus Blender nicht.
 *
 * Also raten. Aber sichtbar raten: was hier herauskommt, ist ein VORSCHLAG,
 * den die Seite zeigt und den jemand korrigieren kann. Ein stiller Fehlgriff
 * waere schlimmer als eine offene Luecke - eine vertauschte Schulter faellt
 * erst auf, wenn der Clip laeuft und seltsam aussieht.
 *
 * ── Drei Schichten ───────────────────────────────────────────────────────
 *
 * 1. NAMEN. Eine Tabelle bekannter Schreibweisen, auf Wortmarken statt auf
 *    Zeichenketten: `UpperArmR` und `upperarm_l` und `DEF-upper_arm.L`
 *    zerfallen alle in `upper` + `arm` + Seite.
 *
 * 2. HIERARCHIE. Der Name allein reicht nicht - Mixamo nennt den OBERarm
 *    `Arm` und den UNTERschenkel `Leg`, und ein Unreal-Rig traegt
 *    `shoulderAttach_l` direkt neben `clavicle_l`. Also muss jede Kette auch
 *    haengen wie eine Kette: die Hand unter dem Unterarm, der unter dem
 *    Oberarm. Was nicht haengt, fliegt raus; was fehlt und genau einen
 *    Kandidaten auf dem Weg hat, wird nachgetragen.
 *
 * 3. GEOMETRIE. Links und rechts stehen im Namen, aber nicht zwingend
 *    richtig. Wo Zehen da sind, laesst sich die Blickrichtung messen und
 *    damit beantworten, welche Seite wirklich links ist.
 *
 * Kein three.js hier drin, mit Absicht: was hereinkommt, ist eine schlichte
 * Liste `{ name, parent, pos }`. So laesst sich der Rater gegen echte
 * Skelette pruefen, ohne einen Browser zu oeffnen.
 */

// ── Die 55 ────────────────────────────────────────────────────────────────

const SIDES = ['Left', 'Right'];
const FINGERS = ['Thumb', 'Index', 'Middle', 'Ring', 'Little'];
const SEGMENTS = ['Proximal', 'Intermediate', 'Distal'];

const sided = (...parts) => SIDES.flatMap((side) => parts.map((part) => side + part));

/** Nach Koerperteil gruppiert - so steht es auch in der Maske. */
export const GROUPS = [
  { title: 'Body', bones: ['Hips', 'Spine', 'Chest', 'UpperChest', 'Neck', 'Head'] },
  { title: 'Arms', bones: sided('Shoulder', 'UpperArm', 'LowerArm', 'Hand') },
  { title: 'Legs', bones: sided('UpperLeg', 'LowerLeg', 'Foot', 'Toes') },
  { title: 'Face', bones: ['LeftEye', 'RightEye', 'Jaw'] },
  {
    title: 'Fingers',
    bones: SIDES.flatMap((side) => FINGERS.flatMap((f) => SEGMENTS.map((s) => side + f + s))),
  },
];

export const BONES = GROUPS.flatMap((group) => group.bones);

/**
 * Ohne die geht es nicht. Dieselben fuenfzehn, die auch Unity verlangt: ein
 * Rumpf, zwei Arme, zwei Beine. Alles andere darf fehlen - ein nicht
 * zugeordneter Knochen bleibt einfach in seiner Ruhelage stehen.
 *
 * `Hips` steht hier nicht aus Ordnungsliebe: die Buehne sucht die Huefte
 * namentlich heraus, um die Figur zu setzen, und WIRFT, wenn es sie nicht
 * gibt.
 */
export const REQUIRED = [
  'Hips', 'Spine', 'Head',
  ...sided('UpperArm', 'LowerArm', 'Hand'),
  ...sided('UpperLeg', 'LowerLeg', 'Foot'),
];

/** `LeftUpperArm` -> `Left upper arm`, fuer die Maske. */
export function label(bone) {
  return bone
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/^(\w)/, (c) => c.toUpperCase())
    .replace(/ (\w)/g, (m, c) => ' ' + c.toLowerCase())
    .replace(/^left /, 'Left ').replace(/^right /, 'Right ');
}

// ── Namen in Wortmarken zerlegen ──────────────────────────────────────────

/**
 * Was vor dem eigentlichen Namen steht und nichts bedeutet. Wird als ganze
 * Wortmarke verglichen, nie als Zeichenkette: sonst faende `b` sich mitten in
 * `abdomen` wieder.
 */
const NOISE = new Set([
  'def', 'org', 'mch', 'wgt', 'ctrl', 'ctl', 'jnt', 'joint', 'bn', 'bone', 'b', 'j',
  'sk', 'skel', 'skeleton', 'rig', 'bip', 'bip01', 'biped', 'cc', 'base', 'mixamorig',
  'armature', 'char', 'character', 'avatar', 'body', 'main', 'grp', 'null', 'dummy',
  //  Rigify schreibt `DEF-f_index.01.L` - das `f` steht fuer "finger" und
  //  wuerde den Namen sonst zu `findex` verkleben, das in keiner Tabelle steht.
  'f',
]);

/**
 * Wer eine dieser Marken traegt, ist ueberhaupt kein Koerperknochen.
 *
 * Das ist nicht Feinschliff, sondern die halbe Miete: ein Unreal-Skelett legt
 * `shoulderAttach_l` NEBEN `clavicle_l` und `lowerarm_twist_01_l` neben
 * `lowerarm_l`. Beide wuerden auf dieselbe Rolle passen, und der Falsche
 * stuende genauso plausibel da wie der Richtige.
 *
 * `end`, `tip` und `nub` stehen mit drin, weil fast jeder Exporteur die
 * Kettenenden verlaengert: `HeadEnd` ist nicht der Kopf, `Index04R_end` ist
 * kein Fingerglied.
 */
const NEVER = new Set([
  'ik', 'fk', 'twist', 'roll', 'attach', 'socket', 'pole', 'target', 'tgt',
  'helper', 'tweak', 'corrective', 'cor', 'adj', 'jiggle', 'phys', 'collision',
  'dyn', 'prop', 'weapon', 'cloth', 'skirt', 'cape', 'hair', 'tail', 'wing',
  'ear', 'tongue', 'breast', 'belt', 'end', 'tip', 'nub', 'marker', 'cam',
  'camera', 'light', 'palm', 'metacarpal', 'buttock', 'pectoral',
]);

const LEFT = new Set(['l', 'lf', 'lt', 'left', 'links']);
const RIGHT = new Set(['r', 'rt', 'rf', 'right', 'rechts']);

/**
 * Einen Knochennamen in Wortmarken, Seite und Nummer zerlegen.
 *
 * Die Trennstellen sind Satzzeichen, Gross-/Kleinwechsel UND der Uebergang
 * zwischen Buchstabe und Ziffer - erst damit faellt `Index01R` in
 * `index` + `01` + `r` auseinander, und `DEF-upper_arm.L` in dieselbe Form
 * wie `UpperArmL`.
 */
export function read(name) {
  const parts = String(name)
    .split(':').pop()                             // mixamorig:Hips
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')       // UpperArm  -> Upper Arm
    .replace(/([A-Za-z])(\d)/g, '$1 $2')          // Index01   -> Index 01
    .replace(/(\d)([A-Za-z])/g, '$1 $2')          // 01R       -> 01 R
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((part) => part.toLowerCase());

  let side = '';
  let number = 0;
  const words = [];

  for (const part of parts) {
    if (NEVER.has(part)) return null;
    if (/^\d+$/.test(part)) { if (!number) number = Number(part); continue; }
    if (LEFT.has(part)) { side = side || 'Left'; continue; }
    if (RIGHT.has(part)) { side = side || 'Right'; continue; }
    if (NOISE.has(part)) continue;
    words.push(part);
  }

  return { key: words.join(''), words, side, number };
}

// ── Welcher Name auf welche Rolle passt ───────────────────────────────────

/**
 * Wortmarke -> Rolle, mit einem Gewicht.
 *
 * 10 heisst eindeutig: wer `forearm` heisst, IST der Unterarm. 6 heisst
 * plausibel, aber geteilt - Mixamo nennt den Oberarm schlicht `Arm` und den
 * Unterschenkel schlicht `Leg`, und genau diese beiden Faelle sind es, die
 * ohne die Hierarchie aus Schicht 2 nicht zu entscheiden waeren.
 *
 * Die Wirbelsaeule steht NICHT hier: `spine`, `spine1`, `spine_02`,
 * `abdomen` - wie viele es sind und welcher davon die Brust ist, entscheidet
 * sich an der Kette, nicht am Namen. Siehe [spine].
 */
const NAMES = {
  Hips: [['hips', 10], ['hip', 9], ['pelvis', 10], ['cog', 6]],
  Neck: [['neck', 10]],
  Head: [['head', 10], ['skull', 8]],
  Jaw: [['jaw', 10]],
  Eye: [['eye', 10], ['eyeball', 9]],

  Shoulder: [['shoulder', 9], ['clavicle', 10], ['collar', 9]],
  UpperArm: [['upperarm', 10], ['armupper', 10], ['uparm', 10], ['bicep', 8],
    ['humerus', 8], ['oberarm', 8], ['shldrbend', 8], ['shldr', 6], ['arm', 6]],
  LowerArm: [['lowerarm', 10], ['armlower', 10], ['forearm', 10], ['forearmbend', 10],
    ['unterarm', 8], ['radius', 7], ['elbow', 6]],
  Hand: [['hand', 10], ['wrist', 8]],

  UpperLeg: [['upperleg', 10], ['legupper', 10], ['upleg', 10], ['thigh', 10],
    ['thighbend', 10], ['femur', 8], ['oberschenkel', 8]],
  LowerLeg: [['lowerleg', 10], ['leglower', 10], ['calf', 10], ['shin', 10],
    ['tibia', 8], ['unterschenkel', 8], ['knee', 6], ['leg', 6]],
  Foot: [['foot', 10], ['ankle', 8], ['fuss', 8]],
  Toes: [['toes', 10], ['toe', 10], ['toebase', 10], ['ball', 8], ['ballfoot', 9]],
};

/** Die Wirbelsaeule, von unten nach oben - Kandidaten, noch ohne Rolle. */
const SPINE = new Set(['spine', 'abdomen', 'abdomenlower', 'abdomenupper', 'torso',
  'waist', 'chest', 'ribcage', 'upperchest', 'chestupper', 'spinelower', 'spineupper']);

const FINGER_NAMES = {
  Thumb: ['thumb'],
  Index: ['index', 'pointer'],
  Middle: ['middle'],
  Ring: ['ring'],
  Little: ['little', 'pinky', 'pinkie'],
};

const SEGMENT_WORDS = { proximal: 0, intermediate: 1, middle: 1, distal: 2, meta: -1 };

// ── Das Skelett als Baum ──────────────────────────────────────────────────

/**
 * Nimmt die flache Liste und haengt sie auf: je Gelenk der Vater, die Kinder
 * und wie tief es sitzt. Ein Name kann doppelt vorkommen (zwei Haeute, die
 * denselben Knochen nennen) - dann gewinnt der erste.
 */
function tree(joints) {
  const nodes = joints.map((j, i) => ({
    i,
    name: j.name,
    pos: j.pos || [0, 0, 0],
    parent: -1,
    children: [],
    read: read(j.name),
  }));

  const byName = new Map();
  nodes.forEach((n) => { if (!byName.has(n.name)) byName.set(n.name, n); });

  joints.forEach((j, i) => {
    const p = j.parent ? byName.get(j.parent) : null;
    if (p && p.i !== i) { nodes[i].parent = p.i; p.children.push(i); }
  });

  return { nodes, byName };
}

const isAncestor = (nodes, older, younger) => {
  for (let at = younger; at >= 0; at = nodes[at].parent) if (at === older) return true;
  return false;
};

/**
 * Die Gelenke zwischen zwei Verwandten, das juengere ausgenommen.
 *
 * LEER, wenn die beiden gar nicht verwandt sind. Ohne diese Bremse laeuft der
 * Aufstieg bis zur Wurzel durch und liefert einen Strang, den es so nicht
 * gibt - und der sieht von aussen aus wie ein Ergebnis.
 */
function between(nodes, older, younger) {
  const path = [];
  let at = nodes[younger].parent;
  for (; at >= 0 && at !== older; at = nodes[at].parent) path.push(at);
  return at === older ? path.reverse() : [];
}

// ── Schicht 1: die Namen ──────────────────────────────────────────────────

/**
 * Fuer jede Rolle die Kandidaten sammeln, die der Name hergibt, und die beste
 * Besetzung waehlen.
 *
 * Eine Rolle mit Seite nimmt nur Kandidaten DIESER Seite - und eine Rolle
 * ohne Seite nimmt nur Kandidaten ohne. Das ist der Grund, warum ein Rig mit
 * `pelvisL`/`pelvisR` neben `Hips` nicht die halbe Huefte in den Bauch legt:
 * beide tragen eine Seite, die Huefte hat keine.
 */
function byName(nodes) {
  const scored = new Map(); // Rolle -> [{ i, score }]
  const add = (role, i, score) => {
    if (!scored.has(role)) scored.set(role, []);
    scored.get(role).push({ i, score });
  };

  for (const node of nodes) {
    const r = node.read;
    if (!r || !r.key) continue;

    //  Finger zuerst: sie tragen eine Nummer und wuerden sonst als
    //  Namensrest in die falsche Tabelle laufen.
    const finger = Object.entries(FINGER_NAMES)
      .find(([, keys]) => keys.includes(r.key));
    if (finger) {
      const segment = r.number ? r.number - 1 : SEGMENT_WORDS[r.words[r.words.length - 1]] ?? 0;
      if (segment >= 0 && segment <= 2 && r.side) {
        add(r.side + finger[0] + SEGMENTS[segment], node.i, 10);
      }
      continue;
    }

    for (const [role, keys] of Object.entries(NAMES)) {
      const hit = keys.find(([key]) => key === r.key);
      if (!hit) continue;

      const sideless = role === 'Hips' || role === 'Neck' || role === 'Head' || role === 'Jaw';
      if (sideless && r.side) continue;
      if (!sideless && !r.side) continue;

      add(sideless ? role : r.side + role, node.i, hit[1]);
    }
  }

  //  Beste Besetzung je Rolle, und kein Gelenk in zwei Rollen. Bei gleichem
  //  Gewicht gewinnt das Gelenk, das weiter oben in der Datei steht - das ist
  //  in aller Regel das, was auch weiter oben im Skelett haengt.
  const map = new Map();
  const taken = new Set();
  const order = [...scored.entries()]
    .map(([role, list]) => ({ role, best: list.sort((a, b) => b.score - a.score || a.i - b.i)[0], list }))
    .sort((a, b) => b.best.score - a.best.score);

  for (const { role, list } of order) {
    const pick = list.find((c) => !taken.has(c.i));
    if (!pick) continue;
    map.set(role, pick.i);
    taken.add(pick.i);
  }

  return map;
}

// ── Schicht 2: die Ketten ─────────────────────────────────────────────────

const ARM = ['Shoulder', 'UpperArm', 'LowerArm', 'Hand'];
const LEG = ['UpperLeg', 'LowerLeg', 'Foot', 'Toes'];

/**
 * Eine Kette muss haengen wie eine Kette.
 *
 * Geprueft wird paarweise von unten nach oben: haengt die Hand wirklich unter
 * dem Unterarm? Wenn nicht, ist EINER der beiden falsch - und das ist fast
 * immer der mit dem schwaecheren Namen, also der weiter hinten in der
 * Tabelle. Statt zu raten, welcher, faellt das juengere Glied heraus und
 * bekommt in Schicht 2b eine zweite Chance aus der Hierarchie.
 */
function keepChain(nodes, map, chain) {
  let last = -1;
  for (const role of chain) {
    const at = map.get(role);
    if (at === undefined) continue;
    if (last >= 0 && !isAncestor(nodes, last, at)) { map.delete(role); continue; }
    last = at;
  }
}

/**
 * Was zwischen zwei sicheren Gliedern fehlt, nachtragen.
 *
 * Wenn Oberarm und Hand stehen, der Unterarm aber nicht, und auf dem Weg
 * dazwischen liegt GENAU EIN Gelenk, dann ist das der Unterarm. Kein Name der
 * Welt muss dafuer passen - die Kette laesst gar nichts anderes zu.
 *
 * Bei mehreren Gelenken auf dem Weg wird NICHT geraten: ein Rig mit
 * Drehknochen haette dort zwei, und die Wahl waere ein Muenzwurf.
 */
function fillGaps(nodes, map, chain) {
  for (let i = 1; i < chain.length - 1; i++) {
    if (map.has(chain[i])) continue;

    //  Den naechsten gesetzten Nachbarn nach oben und nach unten suchen.
    let above = -1, below = -1;
    for (let k = i - 1; k >= 0 && above < 0; k--) if (map.has(chain[k])) above = map.get(chain[k]);
    for (let k = i + 1; k < chain.length && below < 0; k++) if (map.has(chain[k])) below = map.get(chain[k]);
    if (above < 0 || below < 0) continue;

    const path = between(nodes, above, below).filter((at) => nodes[at].read);
    if (path.length === 1) map.set(chain[i], path[0]);
  }
}

/**
 * Die Wirbelsaeule aufteilen.
 *
 * Wie viele Wirbel eine Figur hat, ist Geschmackssache des Riggers: einer,
 * drei, fuenf. Unity kennt drei Stufen. Also nicht nach Namen fragen, sondern
 * die Kette von der Huefte zum Hals ablaufen und verteilen - der erste ist
 * `Spine`, der letzte `UpperChest`, der mittlere `Chest`. Was darueber
 * hinaus da ist, bleibt frei und steht in seiner Ruhelage: das ist eine
 * ruhigere Figur als eine, bei der ein Wirbel die Drehung zweier bekommt.
 */
function spine(nodes, map) {
  const hips = map.get('Hips');
  if (hips === undefined) return;

  //  Das obere Ende: der Hals, sonst der Kopf, sonst die hoechste Schulter.
  const top = map.get('Neck') ?? map.get('Head')
    ?? [map.get('LeftShoulder'), map.get('RightShoulder'), map.get('LeftUpperArm')]
      .find((at) => at !== undefined);
  if (top === undefined) return;

  const chain = between(nodes, hips, top)
    .filter((at) => nodes[at].read && (SPINE.has(nodes[at].read.key) || !nodes[at].read.side));
  if (!chain.length) return;

  const slots = chain.length === 1 ? ['Spine']
    : chain.length === 2 ? ['Spine', 'Chest']
      : ['Spine', 'Chest', 'UpperChest'];

  const pick = chain.length <= 2 ? chain
    : [chain[0], chain[(chain.length - 1) >> 1], chain[chain.length - 1]];

  slots.forEach((role, i) => map.set(role, pick[i]));
}

/**
 * Die Huefte muss die Beine tragen.
 *
 * Das ist keine Anatomie, sondern Mechanik: die Buehne setzt die Figur an der
 * Huefte, und was nicht unter ihr haengt, geht diese Bewegung nicht mit. Eine
 * Huefte ohne Beine darunter ist keine.
 *
 * Deshalb zaehlt hier der Bau vor dem Namen. Ein echtes Beispiel: ein Rig mit
 * `Root -> Hips -> pelvisL/pelvisR` als totem Ast und `Root -> Spine1` als
 * dem, woran die Oberschenkel wirklich haengen. `Hips` HEISST Huefte und ist
 * auf den Zentimeter an derselben Stelle - aber wer ihn nimmt, bewegt einen
 * Ast, an dem keine Beine sind.
 *
 * Gesucht wird der tiefste gemeinsame Vorfahr beider Oberschenkel. Nicht die
 * Wurzel des Skeletts: die heisst meistens `root`, sitzt auf dem Boden, und
 * wer sie nimmt, bekommt eine Figur, die sich um ihre Fuesse dreht.
 */
function findHips(nodes, map) {
  const left = map.get('LeftUpperLeg');
  const right = map.get('RightUpperLeg');

  //  Ohne beide Beine laesst sich nichts pruefen - dann bleibt der Name.
  if (left === undefined || right === undefined) return;

  const named = map.get('Hips');
  if (named !== undefined && isAncestor(nodes, named, left) && isAncestor(nodes, named, right)) return;

  map.delete('Hips');
  for (let at = nodes[left].parent; at >= 0; at = nodes[at].parent) {
    if (isAncestor(nodes, at, right)) { map.set('Hips', at); return; }
  }
}

// ── Schicht 3: links und rechts ───────────────────────────────────────────

const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

/**
 * Stimmen die Seiten?
 *
 * Der Name sagt `_l`, aber ob der Knochen auch links sitzt, sagt nur die
 * Lage. Und "links" ist keine Himmelsrichtung, sondern haengt daran, wohin
 * die Figur schaut - also wird die Blickrichtung gemessen, am ZEH: er liegt
 * vor dem Sprunggelenk, und das gilt fuer jede Figur, die auf Fuessen steht.
 *
 * Daraus die Querachse als Kreuzprodukt (rechtshaendig, Y oben):
 * links = hoch x vorne. Ein Paar, das dem widerspricht, steht verkehrt.
 *
 * Ohne Zehen laesst sich das nicht entscheiden. Dann wird nichts getauscht -
 * lieber die Namen glauben als eine Muenze werfen.
 */
function checkSides(nodes, map) {
  const at = (role) => (map.has(role) ? nodes[map.get(role)].pos : null);

  const forward = [0, 0, 0];
  let feet = 0;
  for (const side of SIDES) {
    const foot = at(side + 'Foot');
    const toes = at(side + 'Toes');
    if (!foot || !toes) continue;
    const d = sub(toes, foot);
    forward[0] += d[0]; forward[2] += d[2];
    feet++;
  }
  if (!feet || (forward[0] === 0 && forward[2] === 0)) return null;

  //  links = hoch x vorne, mit hoch = (0,1,0).
  const leftward = [forward[2], 0, -forward[0]];

  let agree = 0;
  let against = 0;
  for (const part of ['UpperArm', 'Hand', 'UpperLeg', 'Foot', 'Shoulder']) {
    const l = at('Left' + part);
    const r = at('Right' + part);
    if (!l || !r) continue;
    if (dot(sub(l, r), leftward) >= 0) agree++; else against++;
  }

  if (!against || against < agree) return null;

  //  Alle Paare stehen verkehrt: die Datei meint es andersherum. Tauschen.
  for (const bone of BONES) {
    if (!bone.startsWith('Left')) continue;
    const mirror = 'Right' + bone.slice(4);
    const a = map.get(bone);
    const b = map.get(mirror);
    if (a === undefined && b === undefined) continue;
    if (b === undefined) { map.delete(bone); map.set(mirror, a); } else if (a === undefined) { map.delete(mirror); map.set(bone, b); } else { map.set(bone, b); map.set(mirror, a); }
  }

  return 'Left and right sat the other way round in this file; they have been swapped.';
}

// ── Der Rater ─────────────────────────────────────────────────────────────

/**
 * Aus einer Gelenkliste einen Vorschlag machen.
 *
 * `joints`: `[{ name, parent, pos: [x, y, z] }]` - Vater als Name, Lage in
 * Weltkoordinaten der Ruhepose.
 *
 * Zurueck kommt die Zuordnung (Rolle -> Knochenname), was davon fehlt und was
 * erwaehnenswert war. Der Aufrufer zeigt beides; hier wird nichts
 * verschwiegen und nichts beschoenigt.
 */
export function guess(joints) {
  const { nodes } = tree(joints || []);
  const map = byName(nodes);

  for (const side of SIDES) {
    keepChain(nodes, map, ARM.map((part) => side + part));
    keepChain(nodes, map, LEG.map((part) => side + part));
  }

  findHips(nodes, map);
  spine(nodes, map);

  for (const side of SIDES) {
    fillGaps(nodes, map, [side + 'Shoulder', ...ARM.slice(1).map((p) => side + p)]);
    fillGaps(nodes, map, LEG.map((part) => side + part));
  }

  const notes = [];
  const swapped = checkSides(nodes, map);
  if (swapped) notes.push(swapped);

  const out = {};
  for (const bone of BONES) if (map.has(bone)) out[bone] = nodes[map.get(bone)].name;

  const missing = REQUIRED.filter((bone) => !out[bone]);
  return { map: out, missing, notes, joints: nodes.map((n) => n.name) };
}
