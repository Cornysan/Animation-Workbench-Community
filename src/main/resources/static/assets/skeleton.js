/**
 * Eine Modelldatei aufmachen und nachsehen, was fuer ein Skelett drinsteckt.
 *
 * Zwei Tueren fuehren auf diese Seite. Durch die eine kommt eine `.glb` aus
 * der Workbench: die traegt ihre Knochenzuordnung mit, und dann ist hier
 * nichts zu raten. Durch die andere kommt eine fremde Datei - eine `.fbx` von
 * Mixamo, eine `.glb` aus Blender -, und die traegt Namen, aber keine
 * Zuordnung. Was dann passiert, steht in `humanoid.js`.
 *
 * Hier steht nur das Handwerkliche: Datei aufmachen, Knochen einsammeln,
 * Groesse messen, Masseinheit geraderuecken.
 */

import { guess } from './humanoid.js';
import { Box3, FBXLoader, GLTFLoader, Vector3 } from './vendor/three.module.js';

const GLB_MAGIC = 0x46546c67; // "glTF"

/** Woran man die Datei erkennt - am Inhalt, nicht am Namen. */
export function kindOf(buffer, filename = '') {
  const view = new DataView(buffer);
  if (buffer.byteLength >= 12 && view.getUint32(0, true) === GLB_MAGIC) return 'glb';

  //  Ein binaeres FBX faengt mit "Kaydara FBX Binary" an, ein textuelles mit
  //  einem Kommentar. Der Loader nimmt beide; wir muessen sie nur von einer
  //  .glb unterscheiden koennen.
  const head = new TextDecoder().decode(new Uint8Array(buffer, 0, Math.min(64, buffer.byteLength)));
  if (head.startsWith('Kaydara FBX Binary') || /FBX \d/.test(head)) return 'fbx';

  return /\.fbx$/i.test(filename) ? 'fbx' : /\.glb$/i.test(filename) ? 'glb' : '';
}

/**
 * Die Datei zu einem Szenenbaum machen.
 *
 * Gibt immer `{ scene }` zurueck, auch fuer FBX - der Loader liefert dort
 * eine blanke Gruppe, und der Rest des Portals rechnet mit einem glTF.
 */
export function parseModel(buffer, kind) {
  if (kind === 'fbx') return Promise.resolve({ scene: new FBXLoader().parse(buffer, '') });
  return new Promise((resolve, reject) => new GLTFLoader().parse(buffer, '', resolve, reject));
}

/**
 * Wie viele Meter ein Zahlenschritt in der Datei bedeutet.
 *
 * glTF ist laut Norm in Metern - da gibt es nichts zu rechnen. FBX ist es
 * NICHT: dort steht die Einheit in der Datei, als "wie viele Zentimeter ist
 * ein Schritt". Fast jedes FBX sagt 1, also Zentimeter, und eine Figur steht
 * dann mit der Huefte bei 96 statt bei 0,96.
 *
 * Das aus der Groesse zu erraten waere moeglich und waere falsch: ein Riese
 * und eine Figur in Zentimetern sehen in Zahlen gleich aus. Die Datei weiss
 * es, also wird sie gefragt. Zoll-Dateien (2.54) fallen damit gleich mit ab.
 */
function metresPerUnit(root, kind) {
  if (kind !== 'fbx') return 1;
  const factor = root && root.userData && root.userData.unitScaleFactor;
  return Number.isFinite(factor) && factor > 0 ? factor / 100 : 0.01;
}

/**
 * Wie gross die Figur ist.
 *
 * GEMESSEN AN DER HAUT, NICHT AN DEN KNOCHEN. Der oberste Knochen ist der
 * Kopf, und ueber dem Kopf liegt noch ein halber Schaedel: am Mannequin sind
 * das 1,41 m Knochenspanne bei 1,74 m Figur, also 19 % zu wenig. Umgekehrt
 * haengen manche Rigs einen Hilfsknochen ueber den Scheitel und messen dann
 * zu viel.
 *
 * `computeBoundingBox` einer gehaeuteten Haut rechnet jede Ecke durch das
 * Skelett - das ist die einzige Zahl, die wirklich die Figur meint. Am
 * Mannequin kommt damit 1,741 m heraus, und 1,74 ist die richtige Antwort.
 *
 * Wenn keine Haut da ist (ein Skelett allein), bleibt die Knochenspanne.
 *
 * Rechnet in den Einheiten der DATEI; umgerechnet wird einmal, beim Aufrufer.
 */
function measure(scene) {
  const box = new Box3();
  scene.traverse((node) => {
    if (!node.isSkinnedMesh) return;
    node.computeBoundingBox();
    if (node.boundingBox) box.union(node.boundingBox.clone().applyMatrix4(node.matrixWorld));
  });

  if (!box.isEmpty()) return box.max.y - box.min.y;

  const span = new Box3();
  const at = new Vector3();
  scene.traverse((node) => { if (node.isBone) span.expandByPoint(node.getWorldPosition(at)); });
  return span.isEmpty() ? 0 : span.max.y - span.min.y;
}

/** Was sich an der Karte abzaehlen laesst. */
function facts(scene) {
  let triangles = 0;
  let parts = 0;
  const textures = new Set();
  const bones = new Set();

  scene.traverse((node) => {
    if (node.isBone) bones.add(node);
    if (!node.isMesh && !node.isSkinnedMesh) return;
    parts++;
    const index = node.geometry.getIndex();
    const position = node.geometry.getAttribute('position');
    triangles += Math.floor(((index ? index.count : position ? position.count : 0)) / 3);
    for (const material of [].concat(node.material || [])) {
      for (const slot of ['map', 'emissiveMap', 'normalMap', 'roughnessMap', 'metalnessMap']) {
        if (material && material[slot]) textures.add(material[slot]);
      }
    }
  });

  return { triangles, parts, joints: bones.size, textures: textures.size };
}

/**
 * Das Skelett als flache Liste, in Metern.
 *
 * Die Lage kommt aus der Ruhepose - die Datei ist frisch geladen, es laeuft
 * keine Animation, also stehen die Knochen genau dort, wo der Rigger sie
 * hingesetzt hat. Genau das braucht die Seitenpruefung in `humanoid.js`.
 */
function readBones(scene, metres) {
  scene.updateMatrixWorld(true);

  const joints = [];
  const at = new Vector3();
  scene.traverse((node) => {
    if (!node.isBone) return;
    node.getWorldPosition(at);
    joints.push({
      name: node.name,
      parent: node.parent && node.parent.isBone ? node.parent.name : null,
      pos: [at.x * metres, at.y * metres, at.z * metres],
    });
  });

  return joints;
}

/**
 * Nur die Knochennamen, ohne zu raten.
 *
 * Fuer die Maske: eine Figur aus der Workbench bringt ihre Zuordnung mit, und
 * ihre Knochenliste steht nirgends in der Ablage - wer sie trotzdem einmal
 * ansehen will, bekommt sie hier, frisch aus der Datei.
 */
export async function readJoints(buffer, filename) {
  const kind = kindOf(buffer, filename);
  if (!kind) return [];
  const model = await parseModel(buffer, kind);
  return readBones(model.scene, 1).map((j) => j.name);
}

/**
 * Eine fremde Datei lesen und einen Vorschlag machen.
 *
 * Der Vorschlag ist ein Vorschlag. Was hier herauskommt, geht in die Maske
 * und nicht stillschweigend in die Ablage - eine vertauschte Schulter faellt
 * sonst erst auf, wenn der Clip laeuft und seltsam aussieht.
 */
export async function readForeign(buffer, filename) {
  const kind = kindOf(buffer, filename);
  if (!kind) throw new Error('That is neither a .glb nor an .fbx.');

  const model = await parseModel(buffer, kind);
  const scene = model.scene;
  const metres = metresPerUnit(scene, kind);

  const joints = readBones(scene, metres);
  if (joints.length < 8) {
    throw new Error('There is no skeleton in this file - only a shape. A clip needs bones to move.');
  }

  //  `guess` gibt unter `joints` ALLE Knochennamen zurueck, auch die, die es
  //  selbst verworfen hat (Drehknochen, Aufhaengepunkte). Die Maske braucht
  //  sie: wer von Hand zuordnet, soll jeden Knochen waehlen duerfen, den die
  //  Datei hergibt - auch einen, den der Rater fuer Beiwerk hielt.
  return {
    kind,
    scale: metres,
    height: measure(scene) * metres,
    facts: facts(scene),
    ...guess(joints),
  };
}
