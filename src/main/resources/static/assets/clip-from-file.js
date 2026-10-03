/**
 * Einen Clip aus einer fremden Datei lesen - einer `.fbx` oder `.glb` aus
 * Blender, Maya, einem Mocap-Werkzeug - und daraus die Vorschau bauen, die
 * sonst die Workbench backt (`AWClipPreviewBaker`).
 *
 * WARUM DIE VORSCHAU UND NICHT DIE KURVEN. Ein Clip im Portal ist eine
 * `.awclip`, und deren Kurven sind Unitys MUSKELN ("Left Arm Down-Up"). Aus
 * Knochendrehungen Muskeln zu machen kann nur Unity selbst
 * (`HumanPoseHandler`). Der Browser schreibt deshalb Format 2: keine Kurven,
 * dafuer die Vorschau - je Bild die Drehung jedes Knochens - und die T-Pose
 * der Quelle (`restRot`). Die Workbench backt die Muskeln beim Import daraus
 * (`AWClipPreviewBake`), mit derselben Uebertragung, mit der sie ihre Kacheln
 * stellt. Fuer die Buehne hier und auf der Clip-Seite ist die Vorschau ohnehin
 * alles, was sie braucht.
 *
 * ── Was die Vorschau verlangt ──────────────────────────────────────────
 *
 * Genau das, was `AWClipPreviewBaker.Bake` schreibt, Feld fuer Feld:
 *
 *   bones      Unitys Namen der zugeordneten Knochen, Eltern vor Kindern.
 *   parents    je Knochen der naechste ZUGEORDNETE Vorfahr (-1: die Huefte).
 *   rest       Versatz zum Elternknochen in dessen Raum; die Huefte in Welt.
 *   hips       die Weltlage der Huefte je Bild.
 *   rotations  je Bild und Knochen  inverse(Eltern-Weltdrehung) * Weltdrehung,
 *              bei der Huefte die Weltdrehung. Knochen dazwischen, die keine
 *              Rolle haben (Drehknochen, ein zweites Rueckenglied), stecken
 *              damit in der Drehung ihres zugeordneten Kindes - wie in Unity.
 *   restRot    dieselbe Formel in der T-Pose.
 *
 * Alles in Unitys Raum: linkshaendig, Y oben, Meter, die Figur blickt nach +Z.
 *
 * ── Drei Umrechnungen ──────────────────────────────────────────────────
 *
 * 1. MASSEINHEIT. Ein FBX rechnet in Zentimetern, und die Datei sagt es
 *    (`skeleton.js`, `metresPerUnit`). Nur Lagen werden umgerechnet, Drehungen
 *    haben keine Einheit.
 *
 * 2. BLICKRICHTUNG. Eine Figur aus Blender kann in jede Richtung schauen, und
 *    eine Bindepose im Raum des Meshes kann sogar liegen. Bevor irgendetwas
 *    gemessen wird, kommt die GANZE Datei unter eine Drehung G, die die Figur
 *    aufrecht nach +Z stellt - hoch aus den Beinen, quer von Huefte zu Huefte,
 *    wie `standingFrame` in `stage.js`. Steht sie schon aufrecht, dreht G nur
 *    um die Hochachse: auch Unity laesst einer Figur ihre Neigung. G aendert
 *    keine lokale Drehung (es kuerzt sich heraus), nur Huefte und Wurzelweg.
 *
 * 3. HAENDIGKEIT. three.js ist rechtshaendig, Unity linkshaendig: gespiegelt
 *    an der YZ-Ebene, Punkte (-x, y, z), Drehungen (x, -y, -z, w) - genau die
 *    Spiegelung, mit der Unitys glTF- und FBX-Importer eine solche Datei
 *    lesen, und die Umkehrung von `glb-export.js`.
 *
 * ── Die T-Pose ─────────────────────────────────────────────────────────
 *
 * Ohne sie laesst sich der Clip auf keine andere Figur legen: `restRot` sagt,
 * welche Haltung der Quelle die Nullstellung ist. Die Datei bringt eine
 * BINDEPOSE mit, und die ist oft eine A-Pose - Arme 30 bis 50 Grad unter der
 * Waagerechten. Als T-Pose genommen hingen die Arme dann in jedem Bild genau
 * so viel zu hoch.
 *
 * Also wird die Bindepose in die T-Pose GESCHWENKT: Ober- und Unterarm, Hand,
 * Ober- und Unterschenkel - Arme waagerecht zur Seite, Beine senkrecht nach
 * unten. Das ist, was Unitys "Enforce T-Pose" mit einem Avatar macht, und
 * gemessen zwischen zwei Unity-T-Posen liegen genau diese Knochen nur 1 bis 7
 * Grad auseinander (`SWING_TO_TPOSE` in `stage.js`).
 *
 * Dazu die FINGER. Jede Unity-T-Pose haelt sie gestreckt, eine Bindepose in
 * Ruhehaltung gekruemmt - beim Export aus der Workbench 33 bis 37 Grad je
 * Gelenk. Als Nullstellung genommen bog sich jeder Finger, der in der
 * Animation weniger gekruemmt war, auf dem Mannequin nach hinten
 * ([straightenFingers]).
 *
 * Schluesselbein, Wirbelsaeule und Fuss legt jeder Rigger anders, dort ist
 * die eigene Bindepose die beste Antwort. Die Drehung UM jeden Knochen kommt
 * immer aus der Bindepose: genau die laesst sich aus Bewegung allein nicht
 * zuverlaessig schaetzen (`rest-pose.js`).
 */

import { readBones, kindOf, metresPerUnit, parseModel } from './skeleton.js';
import { BONES, REQUIRED, guess } from './humanoid.js';
import { THUMB_DOWN, THUMB_FORWARD } from './rest-pose.js';
import { Matrix4, Quaternion, Vector3 } from './vendor/three.module.js';

/** So viele Bilder je Sekunde hat eine Vorschau hoechstens - wie in der Workbench. */
const PREVIEW_FPS = 30;

/** Format-Grenzen (`AwclipSchema`). */
const MAX_PREVIEW_FRAMES = 3600;
const MAX_DURATION = 600;

/** So weit duerfen Anfang und Ende auseinanderliegen, damit es als Schleife gilt (Grad je Knochen). */
const LOOP_DEGREES = 4;

/** ... und so weit die Huefte in der Hoehe (Meter). */
const LOOP_HEIGHT = 0.02;

/**
 * Unter diesem Winkel gilt die Bindepose eines Knochens schon als T-Pose
 * und bleibt stehen (Grad). Unity zwingt eine T-Pose nicht auf die Waagerechte:
 * gemessen an einem Clip aus der Workbench hielt sein Avatar die Arme 4 Grad
 * darunter und die Beine 4 Grad gespreizt, und genau so steht es in seiner
 * `restRot`. Eine A-Pose haengt die Arme 30 bis 50 Grad - die wird
 * geschwenkt. Bei den Beinen ist der Spielraum enger: zehn Grad Spreizung,
 * stehen gelassen, kamen im Rundlauf als zehn Grad Fehler an.
 */
const KEEP_BELOW = { left: 15, right: 15, down: 6 };

/** Welche Knochen in die T-Pose geschwenkt werden, in dieser Reihenfolge (Eltern zuerst). */
const SWINGS = [
  ['LeftUpperArm', 'LeftLowerArm', 'left'], ['LeftLowerArm', 'LeftHand', 'left'],
  ['LeftHand', 'LeftMiddleProximal', 'left'],
  ['RightUpperArm', 'RightLowerArm', 'right'], ['RightLowerArm', 'RightHand', 'right'],
  ['RightHand', 'RightMiddleProximal', 'right'],
  ['LeftUpperLeg', 'LeftLowerLeg', 'down'], ['LeftLowerLeg', 'LeftFoot', 'down'],
  ['RightUpperLeg', 'RightLowerLeg', 'down'], ['RightLowerLeg', 'RightFoot', 'down'],
];

/**
 * Wohin die geschwenkten Knochen zeigen - im Raum NACH G, also bei einer Figur,
 * die nach +Z blickt: ihre linke Seite liegt bei +X.
 */
const TPOSE_DIRECTION = {
  left: new Vector3(1, 0, 0),
  right: new Vector3(-1, 0, 0),
  down: new Vector3(0, -1, 0),
};

/**
 * Unter diesem Winkel bleibt ein Fingerglied, wie die Datei es haelt (Grad).
 * Unitys T-Pose streckt die Finger ganz: gemessen am Mannequin 0 Grad aus der
 * Handflaeche, 0 Grad an Mittel- und Endgelenk, beim Daumen genauso. Ein paar
 * Grad Eigenart eines Riggers bleiben stehen, eine Ruhehaltung nicht.
 */
const FINGER_KEEP_BELOW = 5;

/**
 * ... und das Grundglied des Daumens, gemessen an seiner Richtung in der
 * T-Pose (`THUMB_FORWARD`, `THUMB_DOWN`). Die halten Rigs verschieden - 40 bis
 * 43 Grad vor der Handachse, 5 bis 8 darunter -, daher mehr Spielraum.
 */
const THUMB_KEEP_BELOW = 10;

/**
 * `mixamorig:Hips`, `mixamorig1:Hips`, `mixamorig_Hips` - und so, wie die
 * Lader sie hinterlassen: three.js wirft den Doppelpunkt aus Knotennamen,
 * aus `mixamorig:Hips` wird `mixamorigHips`. Darum nur der Anfang.
 */
const MIXAMO = /^mixamorig/i;

/** three.js' Regel fuer Knotennamen in Animationsspuren (`PropertyBinding.sanitizeNodeName`). */
const sanitize = (name) => String(name || '').replace(/\s/g, '_').replace(/[[\]./:]/g, '');

// ── Datei oeffnen ─────────────────────────────────────────────────────────

/**
 * Eine Datei oeffnen und nachsehen, was drinsteckt.
 *
 * Wirft mit einem Satz, den die Seite so zeigen kann: keine Modelldatei, kein
 * Skelett, keine Animation, oder ein Mixamo-Rig.
 */
export async function openClipFile(buffer, filename = '') {
  const kind = kindOf(buffer, filename);
  if (!kind) throw new Error('That is neither an .fbx nor a .glb file.');

  const model = await parseModel(buffer, kind);
  const scene = model.scene;
  const animations = (model.animations && model.animations.length ? model.animations : scene.animations) || [];

  const metres = metresPerUnit(scene, kind);
  let joints = readBones(scene, metres);

  //  Ohne Haut gibt es keine Knochen: three.js nennt einen Knoten nur dann
  //  `Bone`, wenn ein Skin ihn benutzt. Eine reine Bewegungsdatei - das
  //  "Skeleton only" dieses Portals, ein Mocap-Export - traegt ihr Skelett
  //  als schlichte Knoten. Dann zaehlt jeder Knoten, der kein Mesh, keine
  //  Kamera und kein Licht ist.
  if (joints.length < 8) joints = readNodes(scene, metres);
  if (joints.length < 8) {
    throw new Error('There is no skeleton in this file. A clip needs bones to move.');
  }

  //  Mixamo ist das eine Rig, das sich am Namen sicher erkennen laesst - und
  //  seine Lizenz erlaubt das Weitergeben der Animationen nicht. Dieselbe
  //  Sperre steht in der Workbench (AWClipOriginDetector).
  const mixamo = joints.some((j) => MIXAMO.test(j.name));

  const usable = animations.filter((clip) => clip && clip.tracks && clip.tracks.length > 0);
  if (!usable.length) {
    throw new Error('This file has a skeleton but no animation on it.');
  }

  let hasMesh = false;
  scene.traverse((node) => { if (node.isSkinnedMesh) hasMesh = true; });

  return {
    kind,
    scene,
    metres,
    hasMesh,
    mixamo,
    animations: usable.map((clip, index) => ({ index, name: clip.name || 'Animation ' + (index + 1),
      duration: clip.duration, clip })),
    ...guess(joints),
  };
}

/** Wie `readBones` in `skeleton.js`, aber fuer Knoten ohne Haut (siehe [openClipFile]). */
function readNodes(scene, metres) {
  scene.updateMatrixWorld(true);
  const plain = (node) => node && node !== scene && !node.isMesh && !node.isSkinnedMesh && !node.isCamera && !node.isLight;
  const joints = [];
  const at = new Vector3();
  scene.traverse((node) => {
    if (!plain(node)) return;
    node.getWorldPosition(at);
    joints.push({
      name: node.name,
      parent: plain(node.parent) ? node.parent.name : null,
      pos: [at.x * metres, at.y * metres, at.z * metres],
    });
  });
  return joints;
}

// ── Vorschau bauen ────────────────────────────────────────────────────────

/**
 * Die Vorschau einer Animation, mit der Zuordnung `mapping` (Unity-Name ->
 * Knochenname der Datei). Gibt `{ preview, frameRate, duration, loops, report }`
 * zurueck; `preview` ist fertig fuer die Buehne und fuer die Datei.
 */
export function buildPreview(file, animationIndex, mapping) {
  const entry = file.animations.find((a) => a.index === animationIndex) || file.animations[0];
  const clip = entry.clip;
  const scene = file.scene;
  const metres = file.metres || 1;

  const missing = REQUIRED.filter((bone) => !mapping[bone]);
  if (missing.length) {
    throw new Error('Map these bones first: ' + missing.join(', ') + '.');
  }

  const nodes = mappedNodes(scene, mapping);
  const tree = mappedTree(nodes);
  if (!tree.order.length || tree.order[0] !== 'Hips') {
    throw new Error('The hips have to carry the rest of the skeleton.');
  }

  //  Die Ruhelage merken - das Abtasten stellt die Knochen um, und am Ende
  //  soll die Datei wieder so dastehen, wie sie geladen wurde.
  const saved = [];
  scene.traverse((node) => saved.push([node, node.position.clone(), node.quaternion.clone(), node.scale.clone()]));
  const restore = () => {
    for (const [node, p, q, s] of saved) { node.position.copy(p); node.quaternion.copy(q); node.scale.copy(s); }
    scene.updateMatrixWorld(true);
  };

  try {
    restore();
    const bind = sample(nodes, tree.order, metres);

    const G = uprightTurn(bind);
    if (!G) throw new Error('Could not tell which way the figure faces - check the legs in the bone mapping.');
    turnPose(bind, G);

    const tpose = swingToTPose(bind, tree);

    // ── Bilder ──────────────────────────────────────────────────────────
    const sourceFps = keyRate(clip);
    const duration = Math.min(MAX_DURATION, clip.duration > 0 ? clip.duration : 1 / sourceFps);
    let fps = Math.min(PREVIEW_FPS, sourceFps);
    if (Math.ceil(duration * fps) + 1 > MAX_PREVIEW_FRAMES) fps = Math.floor((MAX_PREVIEW_FRAMES - 1) / duration);
    if (fps < 1) throw new Error('This animation is too long to share.');

    //  Etwas Luft beim Aufrunden: 2,7 s in float32 sind 2,7000000477, und
    //  das gaebe ein Bild mehr als in Unity.
    const frames = Math.max(1, Math.ceil(duration * fps - 1e-3) + 1);
    const tracks = bindTracks(scene, clip);

    const hips = [];
    const rotations = [];
    let origin = null;

    for (let f = 0; f < frames; f++) {
      const time = Math.min(f / fps, duration);
      restore();
      for (const t of tracks) t.apply(time);
      scene.updateMatrixWorld(true);

      const pose = sample(nodes, tree.order, metres);
      turnPose(pose, G);

      //  Die Huefte startet ueber dem Ursprung: ein Clip, der zwei Meter
      //  neben der Mitte beginnt, stuende sonst auf jeder Buehne daneben. Die
      //  Hoehe bleibt, der Weg danach auch.
      const at = pose.get('Hips').position;
      if (!origin) origin = new Vector3(at.x, 0, at.z);
      hips.push(unityPoint(at.clone().sub(origin)));
      rotations.push(localRotations(pose, tree).flat());
    }

    const rest = tree.order.map((bone) => {
      const parent = tree.parent.get(bone);
      const own = bind.get(bone).position;
      if (!parent) return unityPoint(own.clone().sub(origin));
      const p = bind.get(parent);
      return unityPoint(own.clone().sub(p.position).applyQuaternion(p.rotation.clone().invert()));
    });

    const preview = {
      frameRate: fps,
      bones: [...tree.order],
      parents: tree.order.map((bone) => (tree.parent.get(bone) ? tree.order.indexOf(tree.parent.get(bone)) : -1)),
      rest: rest.map((v) => v.map(round5)),
      hips: hips.map((v) => v.map(round5)),
      rotations: rotations.map((row) => row.map(round6)),
      restRot: localRotations(tpose, tree).map(unit4).map((q) => q.map(round7)),
    };

    const report = {
      bones: tree.order.length,
      dropped: tree.dropped,
      frames,
      sourceFps,
      //  Kopf bis Fuss in der Ruhelage - fuer die Pruefung, ob die
      //  Masseinheit stimmt (eine Figur von 0,02 oder 180 m ist keine).
      height: span(bind),
      turned: Math.round(angleOf(G)),
    };

    return { preview, frameRate: sourceFps, duration, loops: looksLikeLoop(preview), report };
  } finally {
    restore();
  }
}

// ── Knochen ───────────────────────────────────────────────────────────────

/**
 * Rolle -> Knoten der Datei. Namen koennen doppelt vorkommen (ein
 * Sidekick-FBX traegt manche zweimal); ein Knochen schlaegt dann den Rest.
 */
function mappedNodes(scene, mapping) {
  const exact = new Map();
  const loose = new Map();
  scene.traverse((node) => {
    const keep = (map, key) => {
      const had = map.get(key);
      if (!had || (node.isBone && !had.isBone)) map.set(key, node);
    };
    keep(exact, node.name);
    keep(loose, sanitize(node.name));
  });

  const out = new Map();
  for (const bone of BONES) {
    const name = mapping[bone];
    if (!name) continue;
    const node = exact.get(name) || loose.get(sanitize(name));
    if (node) out.set(bone, node);
  }
  return out;
}

/**
 * Der Baum der zugeordneten Knochen: Eltern = naechster zugeordneter Vorfahr,
 * Reihenfolge nach Tiefe und Namen wie in `AWClipPreviewBaker.CollectBones`.
 * Wer keinen zugeordneten Vorfahr hat und nicht die Huefte ist, faellt heraus
 * - er haette keinen Platz im Baum.
 */
function mappedTree(nodes) {
  const roleOf = new Map([...nodes].map(([bone, node]) => [node, bone]));
  const depth = (node) => { let d = 0; for (let p = node.parent; p; p = p.parent) d++; return d; };

  const entries = [...nodes].map(([bone, node]) => ({ bone, node, depth: depth(node) }))
    .sort((a, b) => a.depth - b.depth || (a.bone < b.bone ? -1 : a.bone > b.bone ? 1 : 0));

  const order = [];
  const parent = new Map();
  const dropped = [];
  const placed = new Set();

  for (const { bone, node } of entries) {
    let up = null;
    for (let p = node.parent; p; p = p.parent) {
      const role = roleOf.get(p);
      if (role && placed.has(role)) { up = role; break; }
    }
    if (!up && order.length > 0) { dropped.push(bone); continue; }
    if (!up && bone !== 'Hips') { dropped.push(bone); continue; }
    order.push(bone);
    placed.add(bone);
    parent.set(bone, up);
  }

  const children = new Map(order.map((bone) => [bone, []]));
  for (const bone of order) if (parent.get(bone)) children.get(parent.get(bone)).push(bone);

  return { order, parent, children, nodes, dropped };
}

/** Weltlage und -drehung jedes zugeordneten Knochens, Lagen in Metern. */
function sample(nodes, order, metres) {
  const pose = new Map();
  const p = new Vector3();
  const q = new Quaternion();
  const s = new Vector3();
  for (const bone of order) {
    nodes.get(bone).matrixWorld.decompose(p, q, s);
    pose.set(bone, { position: p.clone().multiplyScalar(metres), rotation: q.clone().normalize() });
  }
  return pose;
}

/** Je Knochen  inverse(Eltern-Welt) * Welt, bei der Huefte die Welt - in Unitys Raum. */
function localRotations(pose, tree) {
  return tree.order.map((bone) => {
    const own = pose.get(bone).rotation;
    const parent = tree.parent.get(bone);
    const local = parent ? pose.get(parent).rotation.clone().invert().multiply(own) : own.clone();
    return unityRotation(local.normalize());
  });
}

// ── Aufstellen ────────────────────────────────────────────────────────────

/**
 * Die Drehung G, die die Figur aufrecht nach +Z stellt (siehe Kopf).
 * Hoch aus den Beinen, quer von Huefte zu Huefte - wie `standingFrame` in
 * `stage.js`; ohne Beine aus Armen und Rumpf. Null, wenn sich nichts messen
 * laesst.
 */
function uprightTurn(pose) {
  const at = (bone) => (pose.get(bone) ? pose.get(bone).position : null);
  let up = null;
  let left = null;

  const lh = at('LeftUpperLeg'), rh = at('RightUpperLeg'), lf = at('LeftFoot'), rf = at('RightFoot');
  if (lh && rh && lf && rf) {
    up = lh.clone().add(rh).sub(lf).sub(rf);
    left = lh.clone().sub(rh);
  }
  if (!up || up.lengthSq() < 1e-10 || left.lengthSq() < 1e-10) {
    const la = at('LeftUpperArm'), ra = at('RightUpperArm'), head = at('Head'), hips = at('Hips');
    if (!la || !ra || !head || !hips) return null;
    up = head.clone().sub(hips);
    left = la.clone().sub(ra);
  }
  up.normalize();

  //  Steht sie schon aufrecht, nur um die Hochachse wenden - die Neigung
  //  ihrer Haltung gehoert ihr (stage.js, turnBetween).
  const upright = up.y > Math.cos(Math.PI / 4);
  if (upright) up.set(0, 1, 0);

  const forward = new Vector3().crossVectors(left, up);   // links x hoch = vorn
  forward.addScaledVector(up, -forward.dot(up));
  if (forward.lengthSq() < 1e-10) return null;
  forward.normalize();

  const right = new Vector3().crossVectors(forward, up).normalize();
  const source = new Matrix4().makeBasis(right, up, forward);
  const target = new Matrix4().makeBasis(new Vector3(-1, 0, 0), new Vector3(0, 1, 0), new Vector3(0, 0, 1));
  return new Quaternion().setFromRotationMatrix(target.multiply(source.transpose())).normalize();
}

function turnPose(pose, G) {
  for (const entry of pose.values()) {
    entry.position.applyQuaternion(G);
    entry.rotation.premultiply(G).normalize();
  }
}

/**
 * Die Bindepose in die T-Pose schwenken (siehe Kopf): je Knochen die kuerzeste
 * Drehung, die seine Richtung zum Kind auf die Richtung der T-Pose legt -
 * angewandt auf ihn und alles, was an ihm haengt.
 */
function swingToTPose(bind, tree) {
  const pose = new Map([...bind].map(([bone, e]) => [bone, { position: e.position.clone(), rotation: e.rotation.clone() }]));

  for (const [bone, child, side] of SWINGS) {
    const a = pose.get(bone), b = pose.get(child);
    if (!a || !b) continue;
    const direction = b.position.clone().sub(a.position);
    if (direction.lengthSq() < 1e-12) continue;
    direction.normalize();
    if (degrees(direction, TPOSE_DIRECTION[side]) < KEEP_BELOW[side]) continue;
    swingBelow(pose, tree, bone, new Quaternion().setFromUnitVectors(direction, TPOSE_DIRECTION[side]));
  }

  //  Nach G blickt die Figur nach +Z, ihre linke Seite liegt bei +X: die
  //  linke Handflaeche zeigt dann in Richtung Handachse x Quer, die rechte
  //  entgegen.
  straightenFingers(pose, tree, 'Left', 1);
  straightenFingers(pose, tree, 'Right', -1);
  return pose;
}

/** Den Knochen und alles, was an ihm haengt, um seinen Ansatz drehen. */
function swingBelow(pose, tree, bone, swing) {
  const pivot = pose.get(bone).position.clone();
  const open = [bone];
  while (open.length) {
    const name = open.pop();
    const e = pose.get(name);
    e.rotation.premultiply(swing).normalize();
    e.position.sub(pivot).applyQuaternion(swing).add(pivot);
    open.push(...(tree.children.get(name) || []));
  }
}

const degrees = (a, b) => a.angleTo(b) * 180 / Math.PI;

/**
 * Die Finger einer Hand strecken (siehe Kopf). Gemessen in der Handflaeche -
 * Handachse zum Mittelfinger, quer vom kleinen zum Zeigefinger -, nicht im
 * Raum: wie die Hand selbst liegt, ist Sache der Arme.
 *
 *   Grundglied     aus der Handflaeche heraus zurueck in sie hinein; war die
 *                  Hand gekruemmt, dazu parallel zum Mittelfinger (`relaxed`).
 *                  Beim Daumen in die Richtung der T-Pose, vor der Handachse
 *                  und etwas zur Handflaeche hin.
 *   Mittelglied    in die Richtung des Glieds davor.
 *   Endglied       ebenso. Es hat kein Kind, an dem sich seine Richtung
 *                  ablesen liesse; es zeigt, wohin seine Achse zeigt - die
 *                  Achse, entlang der das Glied davor zu ihm reicht. So baut
 *                  jedes Rig seine Ketten, und so schaetzt auch `rest-pose.js`.
 *
 * Jeder Schritt dreht das Glied samt allem dahinter um sein Gelenk, wie die
 * Arme: die Drehung um die Gliedachse bleibt die der Datei.
 */
function straightenFingers(pose, tree, side, handedness) {
  const at = (bone) => pose.get(side + bone);
  const hand = at('Hand'), middle = at('MiddleProximal'), index = at('IndexProximal'), little = at('LittleProximal');
  if (!hand || !middle || !index || !little) return;

  const along = middle.position.clone().sub(hand.position);
  const across = index.position.clone().sub(little.position);
  if (along.lengthSq() < 1e-12) return;
  along.normalize();
  across.addScaledVector(along, -across.dot(along));
  if (across.lengthSq() < 1e-12) return;
  across.normalize();
  const palm = new Vector3().crossVectors(along, across).multiplyScalar(handedness);

  const forward = THUMB_FORWARD * Math.PI / 180, down = THUMB_DOWN * Math.PI / 180;
  const thumb = along.clone().multiplyScalar(Math.cos(forward)).addScaledVector(across, Math.sin(forward))
    .multiplyScalar(Math.cos(down)).addScaledVector(palm, Math.sin(down));

  const towards = (from, to) => pose.get(to).position.clone().sub(pose.get(from).position);

  const chains = new Map();
  for (const finger of ['Middle', 'Index', 'Ring', 'Little', 'Thumb']) {
    const chain = ['Proximal', 'Intermediate', 'Distal'].map((part) => side + finger + part).filter((b) => pose.has(b));
    //  Nur eine Kette, die auch im Baum eine ist - eine verunglueckte
    //  Zuordnung bleibt, wie sie ist, statt etwas Falsches zu strecken.
    if (chain.length < 2 || chain.some((b, k) => k > 0 && tree.parent.get(b) !== chain[k - 1])) continue;
    chains.set(finger, chain);
  }

  //  Eine gekruemmte Hand ist auch gespreizt: in der Ruhehaltung stehen Zeige-
  //  und kleiner Finger 8 bis 16 Grad vom Mittelfinger weg, in Unitys T-Pose
  //  liegen alle vier auf 3 Grad parallel. Dann richten sie sich nach dem
  //  Mittelfinger. Eine gestreckte Hand behaelt ihre Spreizung - die hat ihr
  //  Rigger so gebaut, und Unity laesst sie ihr.
  const relaxed = ['Index', 'Middle', 'Ring', 'Little'].some((finger) => {
    const chain = chains.get(finger);
    if (!chain) return false;
    const first = towards(chain[0], chain[1]).normalize();
    if (Math.abs(90 - degrees(first, palm)) >= FINGER_KEEP_BELOW) return true;
    return chain.length > 2 && degrees(first, towards(chain[1], chain[2]).normalize()) >= FINGER_KEEP_BELOW;
  });
  let parallel = null;

  for (const [finger, chain] of chains) {
    for (let k = 0; k < chain.length - 1; k++) {
      const direction = towards(chain[k], chain[k + 1]);
      if (direction.lengthSq() < 1e-12) break;
      direction.normalize();

      let target;
      let keep = FINGER_KEEP_BELOW;
      if (k > 0) {
        target = towards(chain[k - 1], chain[k]).normalize();
      } else if (finger === 'Thumb') {
        target = thumb;
        keep = THUMB_KEEP_BELOW;
      } else if (relaxed && parallel) {
        target = parallel;
      } else {
        target = direction.clone().addScaledVector(palm, -direction.dot(palm));
        target = target.lengthSq() > 1e-6 ? target.normalize() : along.clone();
        //  Der Mittelfinger kommt zuerst und gibt die Richtung vor.
        if (finger === 'Middle') parallel = target;
      }
      if (degrees(direction, target) >= keep) {
        swingBelow(pose, tree, chain[k], new Quaternion().setFromUnitVectors(direction, target));
      }
    }

    const last = chain[chain.length - 1];
    const before = pose.get(chain[chain.length - 2]);
    const reach = towards(chain[chain.length - 2], last);
    if (reach.lengthSq() < 1e-12) continue;
    reach.normalize();
    const tip = reach.clone().applyQuaternion(before.rotation.clone().invert()).applyQuaternion(pose.get(last).rotation);
    if (degrees(tip, reach) >= FINGER_KEEP_BELOW) {
      swingBelow(pose, tree, last, new Quaternion().setFromUnitVectors(tip, reach));
    }
  }
}

// ── Animation abtasten ────────────────────────────────────────────────────

/**
 * Die Spuren einer Animation an ihre Knoten binden. Ohne AnimationMixer (der
 * nicht im Buendel steckt): jede Spur bringt ihren eigenen Interpolanten mit,
 * fuer Drehungen schon den sphaerischen.
 */
function bindTracks(scene, clip) {
  const nodes = new Map();
  scene.traverse((node) => {
    const key = sanitize(node.name);
    const had = nodes.get(key);
    if (!had || (node.isBone && !had.isBone)) nodes.set(key, node);
  });

  const bound = [];
  for (const track of clip.tracks) {
    const dot = track.name.lastIndexOf('.');
    if (dot < 0) continue;
    const node = nodes.get(sanitize(track.name.slice(0, dot)));
    const property = track.name.slice(dot + 1);
    if (!node) continue;

    const interpolant = track.createInterpolant();
    if (property === 'quaternion') {
      bound.push({ apply: (t) => { const v = interpolant.evaluate(t); node.quaternion.set(v[0], v[1], v[2], v[3]).normalize(); } });
    } else if (property === 'position') {
      bound.push({ apply: (t) => { const v = interpolant.evaluate(t); node.position.set(v[0], v[1], v[2]); } });
    } else if (property === 'scale') {
      bound.push({ apply: (t) => { const v = interpolant.evaluate(t); node.scale.set(v[0], v[1], v[2]); } });
    }
  }
  return bound;
}

/**
 * Die Bildrate der Quelle, aus dem Abstand ihrer Schluessel - fuer
 * `manifest.frameRate`, das Unity als Bildrate der Clip uebernimmt. Gerundet
 * auf die ueblichen Werte; ohne Anhalt 30.
 */
function keyRate(clip) {
  let best = null;
  for (const track of clip.tracks) if (!best || track.times.length > best.times.length) best = track;
  if (!best || best.times.length < 3) return PREVIEW_FPS;

  const steps = [];
  for (let i = 1; i < best.times.length; i++) steps.push(best.times[i] - best.times[i - 1]);
  steps.sort((a, b) => a - b);
  const step = steps[Math.floor(steps.length / 2)];
  if (!(step > 0)) return PREVIEW_FPS;

  const raw = 1 / step;
  const common = [12, 15, 24, 25, 30, 48, 50, 60, 90, 100, 120, 240];
  const near = common.find((c) => Math.abs(c - raw) / c < 0.03);
  return Math.max(1, Math.min(240, near || Math.round(raw)));
}

/** Gleichen sich erstes und letztes Bild, ist es vermutlich eine Schleife. */
function looksLikeLoop(preview) {
  const first = preview.rotations[0];
  const last = preview.rotations[preview.rotations.length - 1];
  if (!first || !last || preview.rotations.length < 3) return false;

  for (let i = 0; i < first.length; i += 4) {
    const d = Math.abs(first[i] * last[i] + first[i + 1] * last[i + 1] + first[i + 2] * last[i + 2] + first[i + 3] * last[i + 3]);
    const degrees = 2 * Math.acos(Math.min(1, d)) * 180 / Math.PI;
    if (degrees > LOOP_DEGREES) return false;
  }
  const h0 = preview.hips[0], h1 = preview.hips[preview.hips.length - 1];
  return Math.abs(h0[1] - h1[1]) <= LOOP_HEIGHT;
}

// ── Unitys Raum ───────────────────────────────────────────────────────────

/** three.js -> Unity: an der YZ-Ebene gespiegelt (die Umkehrung von glb-export.js). */
const unityPoint = (v) => [-v.x, v.y, v.z];
const unityRotation = (q) => [q.x, -q.y, -q.z, q.w];

function unit4(q) {
  const l = Math.hypot(q[0], q[1], q[2], q[3]) || 1;
  return [q[0] / l, q[1] / l, q[2] / l, q[3] / l];
}

const round5 = (x) => Math.round(x * 1e5) / 1e5 || 0;
const round6 = (x) => Math.round(x * 1e6) / 1e6 || 0;
const round7 = (x) => Math.round(x * 1e7) / 1e7 || 0;

function span(pose) {
  let low = Infinity, high = -Infinity;
  for (const { position } of pose.values()) { low = Math.min(low, position.y); high = Math.max(high, position.y); }
  return high > low ? high - low : 0;
}

function angleOf(q) {
  return 2 * Math.acos(Math.min(1, Math.abs(q.w))) * 180 / Math.PI;
}

// ── Die Datei ─────────────────────────────────────────────────────────────

/**
 * Eine `.awclip` nach Format 2, gzip-verpackt - so, wie der Server sie liest.
 * `curves` bleibt leer: die Muskeln backt die Workbench beim Import.
 */
export async function writeAwclip({ preview, title, description, tags, license, loops, frameRate, duration }) {
  const doc = {
    format: 'awclip',
    version: 2,
    manifest: {
      title,
      description: description || '',
      tags: tags || [],
      license,
      rig: 'humanoid',
      frameRate: Math.max(1, Math.min(240, frameRate || PREVIEW_FPS)),
      duration: Math.round(duration * 1e6) / 1e6,
      tool: 'Playmations upload',
    },
    settings: { loopTime: !!loops },
    origin: 'unknown',
    curves: [],
    preview,
  };

  const text = JSON.stringify(doc);
  const stream = new Blob([text]).stream().pipeThrough(new CompressionStream('gzip'));
  return new Blob([await new Response(stream).arrayBuffer()], { type: 'application/gzip' });
}
