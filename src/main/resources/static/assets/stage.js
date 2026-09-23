/**
 * Der Mannequin-Viewer: die Vorschau eines `.awclip` auf der Figur, die die
 * Workbench selbst mitliefert.
 *
 * Der Plan hielt bewusst kein Modell bereit ("kein Modell, keine Bibliothek",
 * O7) - die Rechtefrage an einer fremden Figur stellte sich damit gar nicht.
 * Das Mannequin ist unseres, also stellt sie sich auch jetzt nicht: es ist
 * dieselbe `AW_Default_Mannequin.prefab`, die im Paket liegt, exportiert mit
 * `docs-site/tools/unity-mesh-to-glb.py`.
 *
 * Das Strichmaennchen bleibt: im Katalog (24 WebGL-Kontexte fuer 24 Karten
 * gibt kein Browser her), als Skelettansicht in dieser Buehne, und als
 * Rueckfall, wenn WebGL fehlt.
 *
 * ── Warum hier ueberhaupt gerechnet wird ────────────────────────────────
 *
 * Die Vorschau im `.awclip` ist auf IRGENDEINER humanoiden Figur gebacken -
 * der, die der Hochladende in der Workbench ausgewaehlt hat. Sie bringt
 * Weltrotationen je Knochen mit, und die tragen die Achsenkonvention SEINES
 * Rigs. Das Mannequin ist ein Rigify-Rig mit eigener Konvention. Wer die
 * Rotationen einfach uebernimmt, bekommt bei gleicher Konvention ein exaktes
 * Bild und bei anderer eine verdrehte Figur.
 *
 * Also wird pro Knochen eine feste Korrektur bestimmt. Dafuer braucht es ZWEI
 * Richtungen, die in beiden Skeletten bekannt sind:
 *
 *   1. die Richtung zum Kindknochen - im lokalen Raum des Knochens (Quelle:
 *      `rest`, Ziel: die Bindepose des Modells). Sie sagt, wo der Knochen
 *      hinzeigt.
 *   2. die Querachse des Koerpers - Bein zu Bein und Schulter zu Schulter -,
 *      in den lokalen Raum des Knochens zurueckgerechnet und ueber alle Frames
 *      gemittelt. Sie sagt, wie er UM sich selbst gedreht ist.
 *
 * Aus beiden entsteht je ein Dreibein, und die Drehung zwischen den Dreibeinen
 * ist die Korrektur. Bei gleicher Konvention ist sie die Einheit.
 *
 * WARUM ZWEI UND NICHT EINE. Bis zum 2026-09-21 stand hier nur die erste
 * Richtung und `setFromUnitVectors` dazu. Das bildet eine Richtung auf eine
 * andere ab und laesst die Drehung UM diese Richtung offen - es waehlt die
 * kuerzeste, und das ist eine willkuerliche Wahl. Der Kommentar hier
 * behauptete, das falle nur als "feste Rolle um die Knochenachse" an, "die an
 * einem runden Gliedmass niemand sieht". Fuer Oberschenkel und Oberarm stimmt
 * das. Fuer Becken und Brustkorb nicht, denn ein Rumpf ist breit: gemessen
 * standen Beckenachse und Schulterachse um 87 bzw. 92 Grad verdreht, und die
 * Figur trug den Oberkoerper quer zu den Beinen. Mit der zweiten Richtung sind
 * es 0,2 bzw. 0,6 Grad.
 *
 * WARUM DIE ZWEITE RICHTUNG GEMESSEN UND NICHT GEERBT WIRD, steht unten bei
 * der Rechnung. Kurz: der erste Anlauf nahm dafuer ein symmetrisches
 * Kinderpaar am Knochen selbst und liess alle uebrigen Knochen es vom
 * Vorfahren erben. Das ergab einen Knoten im Bauch - ein Rig dreht den Roll
 * seiner Wirbelsaeule, und die Vererbung legt den Sprung an die falsche
 * Stelle.
 */

import {
  ACESFilmicToneMapping, Box3, BufferAttribute, BufferGeometry, CanvasTexture, Color,
  DirectionalLight, GLTFLoader, Group, cloneSkinned, HemisphereLight, LineBasicMaterial, LineSegments,
  Matrix4, Mesh, MeshBasicMaterial, MeshStandardMaterial, PerspectiveCamera,
  PlaneGeometry, Points, PointsMaterial, Quaternion, SRGBColorSpace, Scene, ShadowMaterial,
  Vector3, WebGLRenderer,
} from './vendor/three.module.js';

// Relativ zu diesem Modul, damit das Mannequin dieselbe Build-Version traegt
// wie das Skript, das es laedt (siehe StaticAssets.kt).
const MODEL_URL = new URL('./models/aw-mannequin.glb', import.meta.url).href;

const ACCENT = 0x8e77ff;
const WARM = 0xfb923c;

/** Die zwei Unity-Materialien unter dem Namen, den der Export uebernimmt. Sie
 *  heissen verkehrt herum: "Joints" ist die grosse Schale, "Main" die schmalen
 *  Ringe an jedem Gelenk. */
const SEAMS = 'AW_Default_M_Main_URP';

/**
 * GLTFLoader schickt jeden Knotennamen durch `PropertyBinding.sanitizeNodeName`
 * und das LOESCHT `. [ ] : /`. Das Mannequin ist ein Rigify-Rig, also heisst
 * `DEF-spine.001` nach dem Laden `DEF-spine001`. Wer unter dem echten Namen
 * sucht, findet nichts - lautlos.
 */
const boneKey = (name) => name.replace(/[[\]./:]/g, '').replace(/\s/g, '_');

/** Unity-Knochenname -> Knochen im Mannequin. */
const BONE_MAP = (() => {
  const map = {
    Hips: 'DEF-hips',
    Spine: 'DEF-spine.001',
    Chest: 'DEF-spine.002',
    UpperChest: 'DEF-spine.003',
    Neck: 'DEF-neck',
    Head: 'DEF-head',
  };
  const fingers = { Thumb: 'thumb', Index: 'f_index', Middle: 'f_middle', Ring: 'f_ring', Little: 'f_pinky' };
  const segments = { Proximal: '01', Intermediate: '02', Distal: '03' };

  for (const side of ['Left', 'Right']) {
    const s = side === 'Left' ? 'L' : 'R';
    Object.assign(map, {
      [`${side}Shoulder`]: `DEF-shoulder.${s}`,
      [`${side}UpperArm`]: `DEF-upper_arm.${s}`,
      [`${side}LowerArm`]: `DEF-forearm.${s}`,
      [`${side}Hand`]: `DEF-hand.${s}`,
      [`${side}UpperLeg`]: `DEF-thigh.${s}`,
      [`${side}LowerLeg`]: `DEF-shin.${s}`,
      [`${side}Foot`]: `DEF-foot.${s}`,
      [`${side}Toes`]: `DEF-toe.${s}`,
    });
    for (const [finger, def] of Object.entries(fingers)) {
      for (const [segment, nr] of Object.entries(segments)) {
        map[`${side}${finger}${segment}`] = `DEF-${def}.${nr}.${s}`;
      }
    }
  }
  return map;
})();

/**
 * Welches Kind die Knochenrichtung angibt, wenn ein Knochen mehrere hat. Die
 * Hüfte traegt drei (zwei Beine und die Wirbelsaeule), die Hand fuenf Finger -
 * und die Wahl muss auf beiden Skeletten DIESELBE sein, sonst vergleicht die
 * Korrektur zwei verschiedene Richtungen.
 */
const AIM_CHILD = {
  Hips: ['Spine', 'Chest', 'UpperChest'],
  Spine: ['Chest', 'UpperChest', 'Neck'],
  Chest: ['UpperChest', 'Neck', 'Head'],
  UpperChest: ['Neck', 'Head'],
  Neck: ['Head'],
  LeftHand: ['LeftMiddleProximal', 'LeftIndexProximal', 'LeftRingProximal'],
  RightHand: ['RightMiddleProximal', 'RightIndexProximal', 'RightRingProximal'],
};

/**
 * Die Bezugsgeruste, aus denen die Rolle eines Knochens bestimmt wird.
 *
 * Aus jedem entsteht je Frame ein Achsenkreuz in Weltkoordinaten - quer, hoch,
 * und das Kreuzprodukt als dritte -, und das beantwortet die Frage, die die
 * Richtung zum Kind offen laesst: wie ist ein Knochen UM seine eigene Achse
 * gedreht.
 *
 * ZWEI QUERPAARE FUER DEN KOERPER, weil eines allein kippen kann: ein Bein
 * hebt sich, eine Schulter zieht hoch. Gemittelt bleibt die Achse ruhig.
 *
 * DREI ACHSEN, NICHT EINE, weil ein Dreibein zwei Richtungen braucht, die
 * nicht parallel sind. Ein Schluesselbein zeigt selbst nach der Seite und
 * faellt mit der Querachse zusammen - es greift dann zur Hochachse.
 *
 * UND EIGENE GERUESTE FUER DIE HAENDE. Eine Hand dreht sich staendig gegen
 * den Koerper; die Koerperachsen stehen in ihrem lokalen Raum darum nur
 * maessig still. Gemessen ueber 56 Frames (1,0 = ueber alle Frames
 * identisch):
 *
 *                       Koerperachse   Handachse
 *   LeftHand               0,813         1,000
 *   LeftIndexProximal      0,811         1,000
 *   LeftMiddleProximal     0,801         1,000
 *   Hips                   0,976         0,477
 *   UpperChest             0,972         0,660
 *
 * Die Handachse liegt quer durch die Fingeransaetze, also GENAU in der Achse,
 * um die ein Finger sich beugt - deshalb bewegt die Beugung sie nicht. Ohne
 * sie waren die Finger verworren.
 */
const AXIS_GROUPS = {
  body: {
    across: [['LeftUpperLeg', 'RightUpperLeg'], ['LeftShoulder', 'RightShoulder']],
    up: ['Hips', 'UpperChest'],
  },
  leftHand: {
    across: [['LeftIndexProximal', 'LeftLittleProximal']],
    up: ['LeftHand', 'LeftMiddleProximal'],
  },
  rightHand: {
    across: [['RightIndexProximal', 'RightLittleProximal']],
    up: ['RightHand', 'RightMiddleProximal'],
  },
};

/** Zu welchem Geruest ein Knochen gehoert. Finger und Hand zur Hand, Rest zum Koerper. */
function axisGroupOf(name) {
  if (name === 'LeftHand' || /^Left(Thumb|Index|Middle|Ring|Little)/.test(name)) return 'leftHand';
  if (name === 'RightHand' || /^Right(Thumb|Index|Middle|Ring|Little)/.test(name)) return 'rightHand';
  return 'body';
}

/** Feine Knochen - dieselbe Unterscheidung wie im Strichmaennchen. */
const DETAIL = /^(Left|Right)(Thumb|Index|Middle|Ring|Little)/;

/**
 * Knochen, die in ihrer Bindepose bleiben, statt der Vorschau zu folgen.
 *
 * DIE ZEHEN, UND DAS IST EINE EINSCHRAENKUNG, KEINE LOESUNG. Ein Zehenknochen
 * hat kein Kind und damit keine eigene Richtung; er kann seine Korrektur nur
 * erben. Was er dabei erbt, ist die Konvention des FUSSES, und die passt
 * nicht: gemessen traegt die Zehe 121,8 Grad lokale Drehung gegen den Fuss,
 * bewegt sich im ganzen Clip aber nur um 5,2. Der grosse Rest ist
 * Achsenkonvention, und geerbt bleibt er als Knick stehen - die Fussspitzen
 * klappten nach unten, um 109 und 133 Grad an ihrer Bindepose vorbei.
 *
 * Trennen liesse sich Konvention von Haltung nur mit den Ruhepose-
 * Orientierungen der Quelle, und die stehen nicht im `.awclip`: `rest` sind
 * blosse Versaetze, summiert man sie ohne Rotationen auf, liegt die Figur auf
 * einer Geraden. Das Format muesste ein Feld mehr tragen (und Client, Server
 * und Formatversion muessten es zusammen bekommen).
 *
 * Bis dahin ist ein glatter Fuss ohne Abrollen ehrlicher als ein geknickter
 * mit. Es kostet die 5 Grad, die die Zehen in einem Clip wirklich tun.
 *
 * Die FINGER stehen aus demselben Grund nicht hier: bei ihnen ist die grosse
 * lokale Drehung tatsaechlich die Haltung - die Faust -, und Erben gibt sie
 * richtig weiter.
 */
const KEEP_BIND_POSE = /Toes$/;

/**
 * Proportionsvarianten: dieselbe Figur in anderen Massen.
 *
 * WARUM UEBERHAUPT. Mixamo laesst eine Figur waehlen, weil Adobe die Figuren
 * besitzt. Hier geht es nicht um Identitaet, sondern um die Frage, die jemand
 * an einer Vorschau wirklich hat: traegt die Bewegung auch einen anderen
 * Koerperbau? Ein Schritt, der auf langen Beinen schleift, ein Arm, der an
 * einem breiten Rumpf haengenbleibt - das zeigt sich nur, wenn sich die MASSE
 * aendern, nicht das Gesicht. Also keine zweite Figur, sondern dieselbe in
 * anderen Massen: kein fremdes Modell, keine Rechtefrage, nichts zu
 * moderieren.
 *
 * WIE. Jede Gruppe skaliert EINEN Wurzelknochen gleichfoermig, und die ganze
 * Kette darunter haengt daran - `DEF-thigh` groesser heisst laengeres und
 * dickeres ganzes Bein, in einem Zug. Gleichfoermig ist dabei keine
 * Bequemlichkeit: eine ungleiche Skalierung schert die Haut, sobald der
 * Knochen sich dreht, eine gleichfoermige nie.
 *
 * `keep` nimmt die Aenderung an einer Stelle wieder zurueck. Der Rumpf traegt
 * Arme und Kopf; waechst er allein, wachsen sie mit, und es entsteht ein Riese
 * statt eines schweren Menschen. Die Gegenskalierung am Oberarm laesst den ARM
 * in seiner Groesse und den WEG dorthin - das Schluesselbein - gewachsen.
 * Genau das ist ein breiter Koerper.
 */
const PROPORTION_GROUPS = {
  legs: { roots: ['LeftUpperLeg', 'RightUpperLeg'] },
  arms: { roots: ['LeftUpperArm', 'RightUpperArm'] },
  torso: { roots: ['Spine'], keep: ['LeftUpperArm', 'RightUpperArm', 'Head'] },
};

/**
 * Die Auswahl. Die Beschriftungen stehen so in der Bedienung.
 *
 * `default` ist das Mannequin, wie es im Paket liegt, und bleibt die Vorgabe:
 * der Katalog und das Bild fuer Discord-Vorschauen zeigen immer diese Masse,
 * sonst zeigten zwei Leute denselben Clip und meinten zwei Figuren.
 */
export const PROPORTIONS = {
  default: { label: 'Default', groups: {} },
  tall: { label: 'Tall', groups: { legs: 1.14, arms: 1.07 } },
  short: { label: 'Short', groups: { legs: 0.87, arms: 0.91 } },
  heavy: { label: 'Heavy', groups: { torso: 1.2, legs: 0.94 } },
};

/** Eine Auswahl als Faktor je Unity-Knochenname. */
function proportionFactors(name) {
  const preset = PROPORTIONS[name] || PROPORTIONS.default;
  const factors = new Map();
  const multiply = (bone, f) => factors.set(bone, (factors.get(bone) ?? 1) * f);
  for (const [group, factor] of Object.entries(preset.groups)) {
    const { roots, keep = [] } = PROPORTION_GROUPS[group];
    for (const bone of roots) multiply(bone, factor);
    for (const bone of keep) multiply(bone, 1 / factor);
  }
  return factors;
}

// ── Unity -> glTF ────────────────────────────────────────────────────────
//
// Unity ist linkshaendig mit +Z nach vorn, glTF rechtshaendig mit -Z nach vorn.
// Die Umrechnung ist die Spiegelung S = diag(1,1,-1), angewandt durch
// Konjugation: Punkte und Vektoren kehren z um, Quaternionen x und y. Weil die
// Zielkonvention ihre Haendigkeit mitdreht, ist das KEINE Spiegelung im Bild -
// links bleibt links.

const toVec = (v) => new Vector3(v[0], v[1], -v[2]);
const toQuat = (a, i) => new Quaternion(-a[i], -a[i + 1], a[i + 2], a[i + 3]);

/**
 * Loest die Vorschau in Weltposen auf: je Frame und Knochen eine Weltrotation
 * und eine Weltposition, bereits in glTF-Koordinaten.
 */
function solvePreview(preview) {
  const count = preview.parents.length;
  const frames = preview.hips.length;
  const rest = preview.rest.map(toVec);

  const rotations = [];
  const positions = [];
  let floorY = Infinity;

  for (let f = 0; f < frames; f++) {
    const raw = preview.rotations[f];
    const rot = new Array(count);
    const pos = new Array(count);

    for (let i = 0; i < count; i++) {
      const local = toQuat(raw, i * 4);
      const parent = preview.parents[i];
      if (parent < 0) {
        rot[i] = local;
        pos[i] = toVec(preview.hips[f]);
      } else {
        rot[i] = rot[parent].clone().multiply(local);
        pos[i] = rest[i].clone().applyQuaternion(rot[parent]).add(pos[parent]);
      }
      if (pos[i].y < floorY) floorY = pos[i].y;
    }
    rotations.push(rot);
    positions.push(pos);
  }

  return { rotations, positions, floorY, frames, count };
}

/** Kantenlaenge des Lichtscheins unter der Figur. */
const GROUND_SIZE = 6.4;

/** Ein Rasterfeld, in Metern. */
const GRID_CELL = 0.32;
const GRID_COLOR = 0xa894ff;

/** Wie weit das Raster ueber die Wege der Figur hinaus zu sehen ist, in
 *  Koerperhoehen - bei 1,8 Metern gut acht Meter. */
const GRID_REACH = 4.5;

/** three.js `RepeatWrapping`. Das Buendel exportiert die Konstante nicht
 *  (`vendor/entry.js` nennt nur, was das Portal bis dahin brauchte); beim
 *  naechsten Neubau dort aufnehmen und hier importieren. */
const REPEAT_WRAPPING = 1000;

/** Den Boden zeichnen wir in Leinwaende - der Abfall kostet dann pro Bild
 *  nichts, und ein Rand entsteht gar nicht erst.
 *
 *  EINMAL FUER ALLE BUEHNEN, seit der Katalog seine Karten auf der Figur
 *  zeigt: 24 Karten waeren sonst 24 Leinwaende von 1024 Pixeln - 96 MiB
 *  Grafikspeicher fuer denselben Boden.
 *
 *  DREI Leinwaende statt einer: der Schein wandert als Licht mit der Figur,
 *  das Raster liegt fest in der Welt. Frueher war beides ein Bild, das der
 *  Huefte folgte - Figur, Kamera und Raster gingen gleich schnell, und jeder
 *  Clip mit Root Motion lief auf der Stelle. Danach folgte nur noch das
 *  Sichtfenster des Rasters; das sah aus wie ein Teppich, der mitlaeuft.
 *  Jetzt ist das Raster so gross, dass es die ganzen Wege der Figur traegt
 *  ([MannequinStage.layGround]). */
const groundTextures = {};

function groundCanvas(size) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  return canvas;
}

// Die Fahne muss WEIT vor dem Rand auf null sein. Eine Ebene unter diesem
// Winkel presst ihre letzte Tiefe in ein paar Pixel, und ein Verlauf, der
// dort noch bei einem Zehntel steht, kommt als gezogener Strich an.
//
// Null ist sie bei 0,3 der Kantenlaenge - [MannequinStage.layGround] rechnet
// damit.
function fadeOut(ctx, size) {
  const mid = size / 2;
  const fade = ctx.createRadialGradient(mid, mid, 0, mid, mid, size * 0.3);
  fade.addColorStop(0, 'rgba(0,0,0,1)');
  fade.addColorStop(0.55, 'rgba(0,0,0,0.92)');
  fade.addColorStop(0.8, 'rgba(0,0,0,0.35)');
  fade.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.globalCompositeOperation = 'destination-in';
  ctx.fillStyle = fade;
  ctx.fillRect(0, 0, size, size);
}

/** Der Lichtschein unter der Figur, schon mit Abfall. Wandert mit. */
function poolTexture(size = 1024) {
  if (groundTextures.pool) return groundTextures.pool;
  const canvas = groundCanvas(size);
  const ctx = canvas.getContext('2d');
  const mid = size / 2;

  const pool = ctx.createRadialGradient(mid, mid, 0, mid, mid, size * 0.26);
  pool.addColorStop(0, 'rgba(70, 60, 118, 0.85)');
  pool.addColorStop(0.45, 'rgba(42, 36, 72, 0.55)');
  pool.addColorStop(1, 'rgba(18, 16, 28, 0)');
  ctx.fillStyle = pool;
  ctx.fillRect(0, 0, size, size);
  fadeOut(ctx, size);

  const texture = new CanvasTexture(canvas);
  texture.colorSpace = SRGBColorSpace;
  texture.anisotropy = 4;
  return (groundTextures.pool = texture);
}

/** Nur der Abfall: weiss, die Deckung im Alpha. Ueber die ganze Rasterebene
 *  gespannt, laesst er das Raster nach aussen auslaufen. */
function fadeTexture(size = 1024) {
  if (groundTextures.fade) return groundTextures.fade;
  const canvas = groundCanvas(size);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, size, size);
  fadeOut(ctx, size);

  const texture = new CanvasTexture(canvas);
  texture.anisotropy = 4;
  return (groundTextures.fade = texture);
}

/** EIN Rasterfeld, als Graustufen auf Schwarz, und die Ebene kachelt es. Die
 *  Buehne nimmt es als `alphaMap`, die Farbe kommt aus dem Material.
 *
 *  Gekachelt, weil das Raster jetzt so gross ist, wie die Figur laeuft - ein
 *  Bild fuer die ganze Flaeche haette bei einem Sprint ein paar Pixel je Feld.
 *  Die Linie sitzt halb an jedem Rand, so treffen sich die Kacheln zu einer
 *  ganzen; ihre Breite ist dieselbe wie im frueheren grossen Bild (2 von 51
 *  Pixeln je Feld).
 *
 *  UNDURCHSICHTIG, nicht Linien auf Transparenz: eine Leinwand vergisst die
 *  Farbe durchsichtiger Pixel, und beim Filtern und in den Mipmaps zoegen die
 *  schwarzen Nachbarn die Linien dunkel. Graustufen mitteln sich richtig. */
function gridTexture(size = 64) {
  if (groundTextures.grid) return groundTextures.grid;
  const canvas = groundCanvas(size);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, size, size);

  //  Ein Pfad, einmal gefuellt: wo sich die Linien kreuzen, zaehlt die
  //  Deckung einmal und nicht doppelt.
  const half = size / 51.2;
  ctx.fillStyle = 'rgba(255, 255, 255, 0.5)';
  ctx.beginPath();
  ctx.rect(0, 0, half, size);
  ctx.rect(size - half, 0, half, size);
  ctx.rect(0, 0, size, half);
  ctx.rect(0, size - half, size, half);
  ctx.fill('nonzero');

  const texture = new CanvasTexture(canvas);
  texture.wrapS = texture.wrapT = REPEAT_WRAPPING;
  texture.anisotropy = 4;
  return (groundTextures.grid = texture);
}

let dotTextureCache = null;

/** PointsMaterial zeichnet Quadrate; ein Gelenk ist rund. */
function dotTexture() {
  if (!dotTextureCache) {
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 64;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(32, 32, 30, 0, Math.PI * 2);
    ctx.fill();
    dotTextureCache = new CanvasTexture(canvas);
  }
  return dotTextureCache;
}

let modelPromise = null;

/** Das Modell wird einmal geladen und danach geklont - zwei Buehnen auf einer
 *  Seite sollen nicht zweimal 326 KiB holen. */
export function loadModel() {
  if (!modelPromise) {
    modelPromise = new Promise((resolve, reject) => {
      new GLTFLoader().load(MODEL_URL, (gltf) => resolve(gltf), undefined, reject);
    });
  }
  return modelPromise;
}

export class MannequinStage {
  constructor(canvas, preview, gltf, options = {}) {
    this.canvas = canvas;
    this.preview = preview;
    this.onFrame = options.onFrame || null;

    //  Eine Karte im Katalog bringt ihren Renderer mit, statt einen eigenen zu
    //  eroeffnen - alle Karten teilen sich einen (card-stage.js). Die Buehne
    //  zeichnet dann nicht selbst: sie stellt Figur und Kamera, und wer die
    //  Flaeche haelt, holt sich das Bild ab.
    this.surface = options.surface || null;

    this.solved = solvePreview(preview);
    this.fps = preview.frameRate;
    this.duration = Math.max(1 / this.fps, (this.solved.frames - 1) / this.fps);
    this.time = 0;
    this.playing = options.autoplay !== false;

    this._showMesh = true;
    this._showGrid = true;
    this.cameraFollow = true;

    this.yaw = options.yaw ?? Math.PI - 0.55;
    this.pitch = options.pitch ?? 0.2;
    this.zoom = 1;
    this.defaults = { yaw: this.yaw, pitch: this.pitch, zoom: 1 };

    /** Um wie viel die mittlere Maustaste das Kameraziel verschoben hat (Welt, Meter). */
    this.pan = new Vector3();

    this.proportions = PROPORTIONS[options.proportions] ? options.proportions : 'default';

    /**
     * EINE EIGENE FIGUR STATT DES MANNEQUINS.
     *
     * `boneMap` kommt dann aus der Datei selbst (`extras.humanoid`, von der
     * Workbench geschrieben): nur der Exporteur kennt beide Namen - die
     * Vorschau nennt ihren Knochen `LeftUpperArm`, das Skelett in der Datei
     * heisst vielleicht `B_UpperArm_L`.
     *
     * Alles andere an der Rechnung bleibt, wie es ist. Die Korrektur je
     * Knochen wird ohnehin aus der Bindepose des ZIELS bestimmt, und ob das
     * Ziel unser Mannequin ist oder die Figur von jemandem, macht dabei
     * keinen Unterschied.
     */
    this.own = !!options.own;
    this.boneMap = options.boneMap || BONE_MAP;

    /**
     * METER JE ZAHLENSCHRITT DER DATEI - und der Grund, warum das hier
     * ueberhaupt steht.
     *
     * Ein FBX rechnet in Zentimetern, und `parseFigure` setzt darum die
     * Wurzel auf 0,01. Die BINDEPOSE wird davon nicht beruehrt: die
     * `boneInverses` haben die Haeute schon beim Laden bekommen, also aus
     * der Zeit VOR der Skalierung. Von da an stehen zwei Raeume
     * nebeneinander - `bone.matrixWorld` in Metern, die Bindepose in
     * Zentimetern -, und `bindRetarget` misst in beiden.
     *
     * Gemessen an einer .fbx von 2,02 m: `dstLeg` kam als 89,07 heraus statt
     * als 0,8907, der Massstab wurde 110 statt 1,1, und die Huefte landete
     * 84 m ueber dem Boden. Sichtbar war davon nichts - keine Warnung, kein
     * Fehler, nur eine leere Buehne.
     */
    this.unitScale = options.unitScale || 1;

    /** Die gemessene Hoehe der eigenen Figur; 0 beim Mannequin. */
    this.figureHeight = options.figureHeight || 0;

    this.buildScene(gltf);
    this.applyProportions();
    this.buildSkeletonLines();
    this.bindRetarget();

    if (options.interactive !== false) this.attachInput();
    this.resize();
    this.observer = new ResizeObserver(() => this.resize());
    this.observer.observe(canvas);

    this.last = null;
    this.drawnKey = null;
    this.loop = this.loop.bind(this);
    if (!this.surface) {
      this.renderer.setAnimationLoop(this.loop);
      this.pauseWhenHidden();
    }
  }

  /**
   * Aus dem Bild heisst: keine Schleife. Vorher lief sie weiter, samt
   * Schattendurchgang, auch wenn man laengst bei den Kommentaren war.
   *
   * `last` wird dabei vergessen, damit die Zeit nach der Rueckkehr dort
   * weiterlaeuft, wo sie stand, statt um die Abwesenheit zu springen.
   */
  pauseWhenHidden() {
    if (typeof IntersectionObserver !== 'function') return;
    this.visibility = new IntersectionObserver(([entry]) => {
      if (!this.renderer) return;
      this.last = null;
      this.drawnKey = null;
      this.renderer.setAnimationLoop(entry.isIntersecting ? this.loop : null);
    });
    this.visibility.observe(this.canvas);
  }

  /**
   * Ziehen dreht, Rad zoomt.
   *
   * WAAGERECHT gegen die Maus: nach rechts ziehen dreht die Figur nach links,
   * als haette man sie angefasst.
   *
   * SENKRECHT MIT der Maus: nach oben ziehen schiebt die Kamera NACH UNTEN,
   * man schaut also von unten herauf. Hergeleitet, nicht geraten - in
   * [placeCamera] steht `position.y = target.y + sin(pitch) * distance`, ein
   * groesserer Pitch hebt die Kamera also. Nach oben ziehen muss den Pitch
   * folglich SENKEN, und dafuer steht das Plus in der Zeile unten.
   *
   * Wer hier ein Vorzeichen drehen will: erst pruefen, welcher Stand
   * ausgeliefert ist (der Build-Stempel im Fuss sagt es). Genau daran ist es
   * schon zweimal gescheitert - beurteilt wurde ein Stand, der den vorigen
   * Fix noch gar nicht enthielt.
   */
  attachInput() {
    let drag = null;

    //  DIE MITTLERE TASTE VERSCHIEBT - wie in der Single Clip View der
    //  Workbench (PanCamera im Vorschau-Renderer). Ohne das hier startet
    //  Chrome unter Windows beim Druck auf das Rad das Auto-Scrollen, und
    //  die Seite faehrt davon, statt dass sich die Kamera bewegt.
    this.canvas.addEventListener('mousedown', (e) => {
      if (e.button === 1) e.preventDefault();
    });

    this.canvas.addEventListener('pointerdown', (e) => {
      this.wheelArmed = true;
      if (e.button === 1) {
        e.preventDefault();
        drag = { pan: true, x: e.clientX, y: e.clientY };
        this.canvas.classList.add('is-panning');
      } else {
        drag = { x: e.clientX, y: e.clientY, yaw: this.yaw, pitch: this.pitch };
      }
      this.canvas.setPointerCapture(e.pointerId);
    });
    this.canvas.addEventListener('pointermove', (e) => {
      if (!drag) return;
      if (drag.pan) {
        this.panBy(e.clientX - drag.x, e.clientY - drag.y);
        drag.x = e.clientX;
        drag.y = e.clientY;
        return;
      }
      this.yaw = drag.yaw - (e.clientX - drag.x) * 0.01;
      this.pitch = Math.max(-0.9, Math.min(1.1, drag.pitch + (e.clientY - drag.y) * 0.01));
    });
    const release = () => {
      drag = null;
      this.canvas.classList.remove('is-panning');
    };
    this.canvas.addEventListener('pointerup', release);
    this.canvas.addEventListener('pointercancel', release);
    //  DAS RAD ZOOMT ERST NACH DEM ANFASSEN. Vorher fing die Buehne jedes Rad
    //  ab, und wer ueber die Clip-Seite scrollte, blieb an 480 Pixeln Figur
    //  haengen. Jetzt: einmal hineinklicken oder Strg halten, und das Rad
    //  gehoert der Figur, bis der Zeiger die Buehne verlaesst.
    this.canvas.addEventListener('pointerleave', () => (this.wheelArmed = false));
    this.canvas.addEventListener('wheel', (e) => {
      if (!this.wheelArmed && !e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      this.zoom = Math.max(0.35, Math.min(4, this.zoom * (e.deltaY > 0 ? 0.9 : 1.1)));
    }, { passive: false });
  }

  // ── Aufbau ─────────────────────────────────────────────────────────────

  buildScene(gltf) {
    //  SCHATTEN NUR, WO DIE BUEHNE ALLEIN STEHT. Er kostet je Bild einen
    //  zweiten Durchgang durch die ganze Szene, und auf einer Karte von 206
    //  Pixeln Hoehe ist er ohnehin ein Fleck - der Schein im Boden verankert
    //  die Figur dort genauso.
    const shadows = !this.surface;

    if (this.surface) {
      this.renderer = this.surface.renderer;
    } else {
      this.renderer = new WebGLRenderer({ canvas: this.canvas, antialias: true, alpha: true });
      this.renderer.setClearAlpha(0);
      this.renderer.toneMapping = ACESFilmicToneMapping;
      this.renderer.toneMappingExposure = 1.12;
      this.renderer.shadowMap.enabled = true;
    }

    this.scene = new Scene();
    this.camera = new PerspectiveCamera(32, 1, 0.05, 60);

    this.scene.add(new HemisphereLight(0xb9aeff, 0x0b0a10, 1.0));

    const key = new DirectionalLight(0xfff4e8, 2.5);
    key.position.set(2.1, 4.9, -2.4);
    key.castShadow = shadows;
    key.shadow.mapSize.set(1024, 1024);
    key.shadow.radius = 4;
    key.shadow.bias = -0.0015;
    key.shadow.normalBias = 0.02;
    const shadowCam = key.shadow.camera;
    shadowCam.near = 1.5; shadowCam.far = 14;
    shadowCam.left = -1.8; shadowCam.right = 1.8;
    shadowCam.top = 1.8; shadowCam.bottom = -1.8;
    shadowCam.updateProjectionMatrix();
    this.scene.add(key);
    this.key = key;

    const rim = new DirectionalLight(ACCENT, 3.0);
    rim.position.set(-3.0, 2.2, 3.0);
    this.scene.add(rim);

    const fill = new DirectionalLight(0x6f7bb0, 0.85);
    fill.position.set(-2.6, 1.0, -2.2);
    this.scene.add(fill);

    //  Schein und Raster als zwei Ebenen - warum, steht bei [groundTextures].
    this.pool = new Mesh(new PlaneGeometry(GROUND_SIZE, GROUND_SIZE),
      new MeshBasicMaterial({ map: poolTexture(), transparent: true, depthWrite: false, toneMapped: false }));
    this.pool.rotation.x = -Math.PI / 2;

    //  Das Raster liegt in der WELT, nicht am Knoten, der der Huefte folgt.
    //  Groesse und Lage setzt [layGround], sobald der Massstab feststeht - die
    //  Ebene hat deshalb die Kantenlaenge 1 und wird skaliert.
    //
    //  Eine EIGENE Kopie der Kachel, weil die Zahl der Wiederholungen an der
    //  Textur haengt und jede Karte im Katalog anders weit laeuft. Die Kopie
    //  teilt sich das Bild mit dem Original, auf der Grafikkarte liegt es nur
    //  einmal.
    this.gridMap = gridTexture().clone();
    this.grid = new Mesh(new PlaneGeometry(1, 1), new MeshBasicMaterial({
      color: GRID_COLOR, map: fadeTexture(), alphaMap: this.gridMap,
      transparent: true, depthWrite: false, toneMapped: false,
    }));
    this.grid.rotation.x = -Math.PI / 2;
    this.grid.renderOrder = 0.5;
    this.scene.add(this.grid);

    this.contact = new Mesh(new PlaneGeometry(3.2, 3.2), new ShadowMaterial({ opacity: 0.5 }));
    this.contact.rotation.x = -Math.PI / 2;
    this.contact.position.y = 0.002;
    this.contact.visible = shadows;
    this.contact.receiveShadow = true;
    this.contact.renderOrder = 1;
    this.scene.add(this.contact);

    // Schein und Schatten folgen der Huefte, deshalb haengen sie an einem
    // eigenen Knoten statt an der Welt. Das Raster haengt nicht daran.
    this.floor = new Group();
    this.floor.add(this.pool, this.contact);
    this.scene.add(this.floor);

    this.figure = new Group();
    this.scene.add(this.figure);

    // clone() TEILT das Skelett: zwei Buehnen auf einer Seite posieren dann
    // dieselben Knochen und werfen sich gegenseitig aus der Pose. cloneSkinned
    // (SkeletonUtils) haengt die geklonte Haut an die geklonten Knochen.
    const model = cloneSkinned(gltf.scene);
    this.skins = [];
    model.traverse((node) => {
      if (node.isMesh || node.isSkinnedMesh) {
        node.castShadow = shadows;
        node.receiveShadow = false;
        node.frustumCulled = false; // die Haut ist quantisiert, ihr eigener Kasten luegt

        //  Die eigene Figur behaelt ihre Materialien. Das Mannequin bekommt
        //  seine zwei: es ist unser Schaustueck, und es soll auf jeder Seite
        //  gleich aussehen.
        if (!this.own) {
          const seam = node.material.name === SEAMS;
          node.material = seam
            ? new MeshStandardMaterial({ color: ACCENT, emissive: 0x2b1f6b, roughness: 0.38 })
            : new MeshStandardMaterial({ color: 0xe9e6f2, roughness: 0.55 });
        }

        if (node.isSkinnedMesh) this.skins.push(node);
        if (!this.skinned) this.skinned = node;
      }
    });

    //  Eine modulare Figur bringt mehrere Haeute mit (Kopf, Koerper, Haare),
    //  und jede nennt nur die Knochen, die SIE braucht. Gerechnet wird auf der
    //  Vereinigung - sonst faende die Zuordnung eine Hand nicht, nur weil die
    //  erste Haut sie nicht benutzt.
    this.bones = [];
    const seen = new Set();
    for (const skin of this.skins) {
      for (const bone of skin.skeleton.bones) {
        if (bone && !seen.has(bone.uuid)) { seen.add(bone.uuid); this.bones.push(bone); }
      }
    }
    this.figure.add(model);
    this.model = model;
    this.scene.updateMatrixWorld(true);
  }

  /**
   * Die gewaehlten Masse auf die Knochen legen.
   *
   * ERST ZURUECK AUF ANFANG. Eine zweite Wahl darf nicht auf der ersten
   * aufbauen, und mitten im Clip stehen die Knochen in einer POSE, nicht in
   * ihrer Ruhe - ohne das Zuruecksetzen fror die naechste Bindung genau diese
   * Pose als Bindepose ein, und die Figur bliebe schief stehen.
   */
  applyProportions() {
    //  Die Masse gehoeren dem Mannequin: sie skalieren Knochen, die es unter
    //  diesen Namen nur dort gibt. Eine eigene Figur HAT ihre Proportionen.
    if (this.own) { this.boneScale = this.bones.map(() => 1); return; }

    const bones = this.bones;
    const byKey = new Map(bones.map((b, i) => [b.name, i]));

    if (!this.boneRestScale) this.boneRestScale = bones.map((b) => b.scale.clone());
    bones.forEach((b, i) => {
      b.scale.copy(this.boneRestScale[i]);
      if (this.bindLocal) b.quaternion.copy(this.bindLocal[i]);
    });

    this.boneScale = bones.map(() => 1);
    for (const [srcName, factor] of proportionFactors(this.proportions)) {
      const ti = byKey.get(boneKey(this.boneMap[srcName] || ''));
      if (ti === undefined) continue;
      bones[ti].scale.multiplyScalar(factor);
      this.boneScale[ti] = factor;
    }
    this.scene.updateMatrixWorld(true);
  }

  /**
   * Eine andere Figur derselben Familie.
   *
   * BINDET NEU, weil Massstab, Hoehe und Bodenhoehe an den Massen haengen. Die
   * Schrittweite der Vorschau wird auf die Beinlaenge des Modells gerechnet,
   * und zu laengeren Beinen gehoeren groessere Schritte - ohne die neue
   * Bindung liefe eine lange Figur mit den Schritten einer kurzen und
   * rutschte ueber den Boden.
   */
  setProportions(name) {
    if (!PROPORTIONS[name] || name === this.proportions) return;
    this.proportions = name;
    this.applyProportions();
    this.bindRetarget();
    this.applyFrame(Math.min(this.solved.frames - 1, Math.round(this.time * this.fps)));
  }

  // ── Bindung an die Vorschau ────────────────────────────────────────────

  bindRetarget() {
    const bones = this.bones;
    const byKey = new Map(bones.map((b, i) => [b.name, i]));

    // Quelle: Index je Unity-Name, und das Kind, das die Richtung angibt.
    const srcIndex = new Map(this.preview.bones.map((n, i) => [n, i]));
    const childrenOf = new Map();
    this.preview.parents.forEach((p, i) => {
      if (p < 0) return;
      if (!childrenOf.has(p)) childrenOf.set(p, []);
      childrenOf.get(p).push(i);
    });

    const aimChildOf = (srcName) => {
      const i = srcIndex.get(srcName);
      const kids = childrenOf.get(i) || [];
      const preferred = AIM_CHILD[srcName];
      if (preferred) {
        for (const name of preferred) {
          const k = srcIndex.get(name);
          if (k !== undefined && kids.includes(k)) return name;
        }
        return null;
      }
      // Ein Knochen mit genau einem Kind braucht keine Wahl; mehrere ohne
      // Eintrag oben waeren eine Luecke in AIM_CHILD - dann lieber nichts.
      return kids.length === 1 ? this.preview.bones[kids[0]] : null;
    };


    //  Bindepose des Modells: Weltmatrix je Knochen ist die Inverse der
    //  inversen Bindematrix.
    //
    //  Die Inversen stehen an den HAEUTEN, und eine modulare Figur hat
    //  mehrere davon - jede mit ihrer eigenen Reihenfolge. Nachgeschlagen
    //  wird deshalb ueber den Knochen, nicht ueber den Platz in einer Haut.
    const inverses = new Map();
    for (const skin of this.skins) {
      skin.skeleton.bones.forEach((bone, i) => {
        if (bone && !inverses.has(bone.uuid)) inverses.set(bone.uuid, skin.skeleton.boneInverses[i]);
      });
    }

    //  DIE BINDEPOSE IN DEN HEUTIGEN WELTRAUM HEBEN.
    //
    //  `boneInverses` stehen im Raum von damals - dem Stand beim Laden, als
    //  die Wurzel noch 1 war. Seither hat `parseFigure` sie auf `unitScale`
    //  gesetzt, und genau diese Differenz fehlt hier. Eine reine Skalierung
    //  ist die ganze Differenz: `parseFigure` fasst nichts anderes an.
    //
    //  Der Rueckfall unten (`bone.matrixWorld`) braucht sie NICHT - der steht
    //  schon im Weltraum.
    const unit = this.unitScale !== 1
      ? new Matrix4().makeScale(this.unitScale, this.unitScale, this.unitScale)
      : null;

    const bindWorld = bones.map((bone) => {
      const inverse = inverses.get(bone.uuid);
      if (!inverse) return bone.matrixWorld.clone();
      const bind = inverse.clone().invert();
      return unit ? bind.premultiply(unit) : bind;
    });
    const bindPos = bindWorld.map((m) => new Vector3().setFromMatrixPosition(m));
    const bindRot = bindWorld.map((m) => new Quaternion().setFromRotationMatrix(m));

    //  Reihenfolge: Eltern vor Kindern. Zweimal gebraucht - gleich fuer die
    //  Proportionen und weiter unten beim Umrechnen ins Lokale.
    const parentIndex = bones.map((b) => (b.parent ? byKey.get(b.parent.name) ?? -1 : -1));
    const depth = bones.map((b) => {
      let d = 0;
      for (let p = b.parent; p; p = p.parent) d++;
      return d;
    });
    const order = bones.map((_, i) => i).sort((a, b) => depth[a] - depth[b]);

    /**
     * DIE PROPORTIONEN GEHOEREN IN DIE BINDEPOSE, nicht nur an die Knochen.
     *
     * `boneInverses` kennt das Modell nur so, wie es geladen wurde; was danach
     * skaliert wird, steht dort nicht. Wer es hier auslaesst, rechnet alles
     * Folgende an der falschen Figur - die Korrektur je Knochen, die
     * Koerperachsen, und vor allem den Massstab, der die Schrittweite an der
     * Beinlaenge misst.
     *
     * Ein Knochen skaliert den VERSATZ SEINER KINDER, nicht seinen eigenen;
     * `outer` ist darum die aufgelaufene Skalierung des Elternknochens. Die
     * RICHTUNG jedes Versatzes bleibt unberuehrt - gleichfoermig skaliert
     * heisst gleich gerichtet -, und weil die Korrektur unten nur Richtungen
     * vergleicht, aendert sich an ihr durch die Proportionen nichts.
     */
    if (this.boneScale && this.boneScale.some((f) => f !== 1)) {
      const rest = bindPos.map((p) => p.clone());
      const accumulated = bones.map(() => 1);
      for (const i of order) {
        const p = parentIndex[i];
        const outer = p < 0 ? 1 : accumulated[p];
        accumulated[i] = outer * this.boneScale[i];
        if (p >= 0) bindPos[i].copy(bindPos[p]).add(rest[i].clone().sub(rest[p]).multiplyScalar(outer));
      }
    }

    /**
     * Die Richtung zum Kind, einmal in der Quelle und einmal im Modell, beide
     * im lokalen Raum des Knochens `ti`.
     */
    const dirTo = (ti, childName) => {
      const ci = srcIndex.get(childName);
      const tci = byKey.get(boneKey(this.boneMap[childName] || ''));
      if (ci === undefined || tci === undefined) return null;
      //  Quelle: `rest` ist der Versatz des Kindes IM lokalen Raum des
      //  Elternknochens - genau die Richtung, die wir brauchen.
      const src = toVec(this.preview.rest[ci]);
      const dst = bindPos[tci].clone().sub(bindPos[ti]).applyQuaternion(bindRot[ti].clone().invert());
      if (src.lengthSq() < 1e-12 || dst.lengthSq() < 1e-12) return null;
      return { src: src.normalize(), dst: dst.normalize() };
    };


    /**
     * Die Referenzachse je Knochen - das, was die Richtung zum Kind offen
     * laesst: wie der Knochen UM seine eigene Achse gedreht ist.
     *
     * ERSTER VERSUCH WAR: ein symmetrisches Kinderpaar am Knochen selbst
     * (Beckenbreite, Schulterbreite), und wer keins hat, erbt vom Vorfahren.
     * Das ergab einen Knoten im Bauch. Der Grund: ein Rig dreht den Roll
     * seiner Wirbelsaeulenknochen, gemessen um 180 Grad zwischen Becken und
     * Spine. Wer die Beckenkonvention weitergibt, legt den Sprung an die
     * falsche Stelle - zwischen Chest und UpperChest - und verdrillt den Rumpf
     * dazwischen.
     *
     * JETZT WIRD SIE GEMESSEN STATT VERERBT. Jedes Geruest aus [AXIS_GROUPS]
     * gibt drei Achsen, und alle drei sind in jedem Frame aus der Geometrie
     * bekannt. Rechnet man sie in den lokalen Raum eines Knochens zurueck,
     * stehen sie ueber die Frames fast still - dreht sich das Geruest, dreht
     * sich die Weltrotation des Knochens mit und hebt die Drehung auf. Nur
     * eine echte Verdrehung DIESES Knochens gegen sein Geruest bewegt sie, und
     * die mittelt sich ueber einen Clip heraus.
     *
     * Die Wahl der Achse faellt an der QUELLE und gilt fuer beide Seiten. Zwei
     * verschiedene Achsen zu vergleichen waere schlimmer als eine schlechte.
     */
    const groupNames = Object.keys(AXIS_GROUPS);

    /** Das Achsenkreuz eines Geruests aus einer Haltung (Weltkoordinaten). */
    const axesOf = (group, positionOf) => {
      const across = new Vector3();
      for (const [leftName, rightName] of group.across) {
        const left = positionOf(leftName), right = positionOf(rightName);
        if (!left || !right) continue;
        const d = left.clone().sub(right);
        if (d.lengthSq() > 1e-12) across.add(d.normalize());
      }
      const base = positionOf(group.up[0]), tip = positionOf(group.up[1]);
      if (across.lengthSq() < 1e-12 || !base || !tip) return null;
      across.normalize();

      const up = tip.clone().sub(base);
      if (up.lengthSq() < 1e-12) return null;
      up.normalize();

      const forward = new Vector3().crossVectors(across, up);
      if (forward.lengthSq() < 1e-12) return null;
      return [across, up, forward.normalize()];
    };

    //  Quelle: je Knochen und Achse ueber alle Frames mitteln - in dem
    //  Geruest, zu dem der Knochen gehoert.
    const srcAxes = this.preview.bones.map(() => [new Vector3(), new Vector3(), new Vector3()]);
    const groupOfBone = this.preview.bones.map((name) => axisGroupOf(name));
    const groupUsable = {};

    for (const key of groupNames) {
      const group = AXIS_GROUPS[key];
      const members = [];
      for (let i = 0; i < this.preview.bones.length; i++) if (groupOfBone[i] === key) members.push(i);
      if (members.length === 0) { groupUsable[key] = false; continue; }

      let frames = 0;
      for (let f = 0; f < this.solved.frames; f++) {
        const positions = this.solved.positions[f];
        const axes = axesOf(group, (name) => {
          const i = srcIndex.get(name);
          return i === undefined ? null : positions[i];
        });
        if (!axes) break;
        frames++;
        const rotations = this.solved.rotations[f];
        for (const i of members) {
          const inverse = rotations[i].clone().invert();
          for (let k = 0; k < 3; k++) srcAxes[i][k].add(axes[k].clone().applyQuaternion(inverse));
        }
      }
      groupUsable[key] = frames > 0;
    }
    srcAxes.forEach((set) => set.forEach((v) => { if (v.lengthSq() > 1e-12) v.normalize(); }));

    //  Ziel: dieselben Gerueste aus der Bindepose des Modells, in Weltkoordinaten.
    const dstAxes = {};
    for (const key of groupNames) {
      if (!groupUsable[key]) continue;
      dstAxes[key] = axesOf(AXIS_GROUPS[key], (name) => {
        const t = byKey.get(boneKey(this.boneMap[name] || ''));
        return t === undefined ? null : bindPos[t];
      });
    }

    /**
     * Welche der drei Achsen dieser Knochen als Referenz nimmt: die, die am
     * weitesten von seiner eigenen Richtung wegzeigt.
     *
     * Die Frage stellt sich, weil ein Knochen mit seiner Achse zusammenfallen
     * kann - ein Schluesselbein zeigt selbst nach der Seite, wo die Querachse
     * liegt. Gemessen 10,9 Grad an der linken Schulter, wo 90 stehen sollten.
     * Aus so einem Paar wird kein Dreibein, sondern Rauschen, und das Rauschen
     * sass sichtbar im Oberkoerper.
     */
    const pickAxis = (si, aimSrc) => {
      let best = -1, bestDot = 1;
      for (let k = 0; k < 3; k++) {
        const axis = srcAxes[si][k];
        if (axis.lengthSq() < 1e-12) continue;
        const dot = Math.abs(aimSrc.dot(axis));
        if (dot < bestDot) { bestDot = dot; best = k; }
      }
      return best;
    };

    const referenceOf = (ti, si, aimSrc) => {
      const axes = dstAxes[groupOfBone[si]];
      if (!axes) return null;
      const k = pickAxis(si, aimSrc);
      if (k < 0) return null;
      return { src: srcAxes[si][k], dst: axes[k].clone().applyQuaternion(bindRot[ti].clone().invert()) };
    };

    /** Rechtshaendiges Dreibein aus einer Haupt- und einer Hilfsrichtung. */
    const frame = (aim, side) => {
      const u = aim.clone().normalize();
      const w = new Vector3().crossVectors(u, side);
      if (w.lengthSq() < 1e-6) return null;      // (anti)parallel - nichts zu holen
      w.normalize();
      const v = new Vector3().crossVectors(w, u).normalize();
      return new Quaternion().setFromRotationMatrix(new Matrix4().makeBasis(u, v, w));
    };


    this.tracks = [];
    const corrections = new Map();

    for (const [srcName, defName] of Object.entries(this.boneMap)) {
      const si = srcIndex.get(srcName);
      const ti = byKey.get(boneKey(defName));
      if (si === undefined || ti === undefined) continue;
      //  Ohne Track bleibt der Knochen in seiner Bindepose - siehe applyFrame.
      if (KEEP_BIND_POSE.test(srcName)) continue;

      const childName = aimChildOf(srcName);
      const aim = childName ? dirTo(ti, childName) : null;
      let correction = null;

      if (aim) {
        //  DIE REFERENZACHSE IST DER GRUND, WARUM HIER EIN DREIBEIN STEHT UND
        //  NICHT DIE KUERZESTE DREHUNG. `setFromUnitVectors` bildet eine
        //  Richtung auf eine andere ab und laesst die Drehung UM diese Richtung
        //  offen; sie waehlt die kuerzeste, und das ist eine willkuerliche
        //  Wahl. An einem Oberschenkel sieht das niemand - der ist rund. Am
        //  Becken und am Brustkorb sieht es jeder, denn ein Rumpf ist breit:
        //  die Figur stand mit dem Oberkoerper quer zu den Beinen.
        const reference = referenceOf(ti, si, aim.src);
        if (reference) {
          const qSrc = frame(aim.src, reference.src);
          const qDst = frame(aim.dst, reference.dst);
          if (qSrc && qDst) correction = qSrc.multiply(qDst.invert());
        }
        //  Ohne brauchbare Referenz bleibt die alte Naeherung: die Richtung
        //  stimmt, die Rolle ist geraten. Dazu kommt es nur, wenn die
        //  Koerperachsen selbst fehlen - also bei einer Vorschau ohne Beine
        //  oder ohne Schultern.
        if (!correction) correction = new Quaternion().setFromUnitVectors(aim.dst, aim.src);
      }

      this.tracks.push({ src: si, bone: ti, srcName, correction });
      if (correction) corrections.set(srcName, correction);
    }

    /**
     * FINGER RECHNEN NICHT SELBST, sie nehmen die Korrektur ihrer Hand.
     *
     * Eine eigene je Fingerglied klingt genauer und ist es nicht. Ein
     * Fingerglied ist kurz und bewegt sich viel; die gemessene Achse streut
     * dort staerker als am Rumpf, und schon kleine Unterschiede zwischen
     * benachbarten Gliedern summieren sich zu einer sichtbaren Rolle - die
     * Finger standen gespreizt und verdreht, wo eine Faust sein sollte.
     *
     * Tragen alle Glieder DIESELBE Korrektur C, wird aus der Umrechnung eine
     * Konjugation - C hoch -1 mal lokale Drehung mal C -, und die erhaelt den
     * Winkel. Die Fingerbewegung kommt damit unveraendert an, nur in den Raum
     * des Modells gedreht. Gemessen, Quelle gegen Modell:
     *
     *                                   eigene Achse   Hand geerbt
     *   Hand -> IndexProximal    51,2       97,0          51,2
     *   Proximal -> Intermediate 71,3       71,2          71,3
     *   Intermediate -> Distal   63,3      128,7          63,3
     *
     * Das gilt, weil ein Rig seine Konvention innerhalb einer Hand nicht
     * wechselt. An der Wirbelsaeule tut es das sehr wohl - dort waere Erben
     * falsch, und genau daran ist ein frueherer Anlauf gescheitert.
     */
    for (const track of this.tracks) {
      if (!DETAIL.test(track.srcName)) continue;
      const hand = corrections.get(track.srcName.startsWith('Left') ? 'LeftHand' : 'RightHand');
      if (hand) track.correction = hand;
    }

    //  Endknochen - Zehen, Fingerspitzen - haben kein Kind und damit keine
    //  eigene Richtung. Sie erben die Korrektur ihres Elternknochens.
    //
    //  EIN UMWEG, DEN ICH GEGANGEN BIN: die Zehen knickten einmal nach unten,
    //  und ich habe daraufhin versucht, ihnen aus zwei Koerperachsen ein
    //  eigenes Dreibein zu bauen. Das war die falsche Stelle - geknickt hatte
    //  sie die damals noch falsche FUSSKORREKTUR, die sie erbten. Gemessen ist
    //  die Vererbung exakt (Fuss zu Zehe: 121,6 Grad in der Quelle, 121,6 im
    //  Modell), das eigene Dreibein lag bei 176,2. Ein Rig wechselt seine
    //  Konvention am letzten Glied einer Kette nicht.
    for (const track of this.tracks) {
      if (track.correction) continue;

      let p = this.preview.parents[track.src];
      while (p >= 0) {
        const parentName = this.preview.bones[p];
        if (corrections.has(parentName)) { track.correction = corrections.get(parentName); break; }
        p = this.preview.parents[p];
      }
      if (!track.correction) track.correction = new Quaternion();
    }

    this.order = order;
    this.trackOf = new Map(this.tracks.map((t) => [t.bone, t]));
    this.bones = bones;
    this.bindLocal = bones.map((b) => b.quaternion.clone());
    this.worldQuats = bones.map(() => new Quaternion());
    this.parentIndex = parentIndex;

    // Der oberste Knochen haengt unter einem gewoehnlichen Knoten; dessen
    // Weltdrehung ist die Grundlage und aendert sich nie.
    const topBone = bones[this.order[0]];
    this.baseQuat = topBone.parent
      ? topBone.parent.getWorldQuaternion(new Quaternion())
      : new Quaternion();

    // ── Massstab und Boden ───────────────────────────────────────────────
    const legOf = (a, b, c) => {
      const ia = srcIndex.get(a), ib = srcIndex.get(b), ic = srcIndex.get(c);
      if (ia === undefined || ib === undefined || ic === undefined) return 0;
      return toVec(this.preview.rest[ib]).length() + toVec(this.preview.rest[ic]).length();
    };
    const srcLeg = legOf('LeftUpperLeg', 'LeftLowerLeg', 'LeftFoot')
      || legOf('RightUpperLeg', 'RightLowerLeg', 'RightFoot');

    //  DAS BEIN DES ZIELS, UEBER DIE ZUORDNUNG GESUCHT - nicht ueber die
    //  Namen des Mannequins. Hier standen `DEF-thigh.L` und seine zwei
    //  Geschwister fest verdrahtet: fuer unser Hausmodell richtig, fuer jede
    //  eigene Figur ins Leere. `dstLeg` blieb dann 0, der Massstab 1, und die
    //  Vorschau schob die Huefte um die Schrittweite des Mannequins.
    //
    //  Bei einer Workbench-Figur faellt das kaum auf - die ist auch ungefaehr
    //  1,7 m gross. Bei einer .fbx in Zentimetern waere es ein Faktor 100
    //  gewesen: die Figur haette auf der Stelle gerutscht.
    const legBone = (part) => byKey.get(boneKey(this.boneMap[part] || ''));
    const tLeg = ['UpperLeg', 'LowerLeg', 'Foot'].map((part) => legBone('Left' + part));
    const tRight = ['UpperLeg', 'LowerLeg', 'Foot'].map((part) => legBone('Right' + part));
    const chain = tLeg.every((i) => i !== undefined) ? tLeg
      : tRight.every((i) => i !== undefined) ? tRight : null;
    const dstLeg = chain
      ? bindPos[chain[1]].distanceTo(bindPos[chain[0]]) + bindPos[chain[2]].distanceTo(bindPos[chain[1]])
      : 0;
    this.scale = (srcLeg > 1e-4 && dstLeg > 1e-4) ? dstLeg / srcLeg : 1;

    this.hipsBone = bones[this.tracks.find((t) => t.srcName === 'Hips').bone];
    this.hipsParentInverse = this.hipsBone.parent
      ? new Matrix4().copy(this.hipsBone.parent.matrixWorld).invert()
      : new Matrix4();

    // Groesse: der Abstand vom tiefsten zum hoechsten Knochen INNERHALB eines
    // Bildes. Ueber den ganzen Clip gemessen zaehlt die Wurzelbewegung mit,
    // und ein Clip, der zwei Meter faellt, haette eine Figur von vier Metern.
    this.height = 0.6;
    for (const frame of this.solved.positions) {
      let lo = Infinity, hi = -Infinity;
      for (const p of frame) { if (p.y < lo) lo = p.y; if (p.y > hi) hi = p.y; }
      this.height = Math.max(this.height, (hi - lo) * this.scale);
    }

    //  DIE HAUT ZAEHLT AUCH MIT.
    //
    //  Oben steht die Spanne der KNOCHEN, auf die Beinlaenge der Figur
    //  umgerechnet. Fuer unser Mannequin ist das die Figur; fuer eine fremde
    //  ist es eine Schaetzung, die nur den Strich kennt und nicht die
    //  Silhouette. Bei kurzen Beinen und grossem Kopf - einer stilisierten
    //  Figur - kam 1,03 m heraus, wo 3,60 m stehen: die Kamera rahmte einen
    //  Meter und stand damit INNERHALB des Kopfes.
    //
    //  Das Maximum, nicht der gemessene Wert allein: die Messung ist die
    //  Ruhepose, und ein Clip, der die Arme ueber den Kopf nimmt, ist
    //  hoeher als die Figur dasteht.
    if (this.figureHeight) this.height = Math.max(this.height, this.figureHeight);

    this.target = new Vector3(0, this.height * 0.5, 0);

    this.offsetY = 0;
    this.offsetY = -this.measureFloor();
    this.layGround();
  }

  /**
   * Legt das Raster aus: fest in der Welt und so gross, dass es alle Wege der
   * Figur traegt, mit [GRID_REACH] Koerperhoehen Rand in jede Richtung. Ein
   * Clip auf der Stelle bekommt damit gut acht Meter Boden um sich, ein
   * Sprint den ganzen Weg und dieselben acht Meter um beide Enden.
   *
   * Hier und nicht einmal im Aufbau, weil es am Massstab haengt - und der
   * aendert sich mit jeder neuen Bindung ([setProportions]).
   *
   * Der Abfall ist bei 0,3 der Kantenlaenge auf null ([fadeOut]); die Ebene
   * ist also so gross, dass genau dort die Reichweite endet. Die Mitte sitzt
   * auf einer Rasterlinie, und die Zahl der Felder ist gerade - so gehen die
   * Linien durch den Ursprung, wo fast jeder Clip anfaengt.
   */
  layGround() {
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    for (const frame of this.solved.positions) {
      const hips = frame[0];
      if (hips.x < minX) minX = hips.x;
      if (hips.x > maxX) maxX = hips.x;
      if (hips.z < minZ) minZ = hips.z;
      if (hips.z > maxZ) maxZ = hips.z;
    }

    const travel = Math.hypot(maxX - minX, maxZ - minZ) * 0.5 * this.scale;
    const reach = travel + this.height * GRID_REACH;
    const cells = 2 * Math.ceil(reach / 0.6 / GRID_CELL);
    const size = cells * GRID_CELL;
    const snap = (v) => Math.round(v / GRID_CELL) * GRID_CELL;

    this.grid.position.set(snap((minX + maxX) * 0.5 * this.scale), 0, snap((minZ + maxZ) * 0.5 * this.scale));
    this.grid.scale.set(size, size, 1);
    this.gridMap.repeat.set(cells, cells);
  }

  /**
   * Wie tief die Figur im tiefsten Bild des Clips reicht - an der FIGUR
   * gemessen, nicht am Skelett. Der tiefste Knochen ist der Zeh, die Sohle
   * liegt darunter, und um wie viel haengt am Modell. Einmal gemessen, mit der
   * Pose, in der die Quelle am tiefsten steht.
   */
  measureFloor() {
    let lowest = 0;
    let lowestY = Infinity;
    this.solved.positions.forEach((frame, f) => {
      for (const p of frame) {
        if (p.y < lowestY) { lowestY = p.y; lowest = f; }
      }
    });

    this.applyFrame(lowest);
    this.scene.updateMatrixWorld(true);

    const union = new Box3();
    let any = false;
    for (const skin of this.skins) {
      skin.computeBoundingBox();
      if (!skin.boundingBox) continue;
      union.union(new Box3().copy(skin.boundingBox).applyMatrix4(skin.matrixWorld));
      any = true;
    }

    if (!any) return this.solved.floorY * this.scale;
    return union.min.y;
  }

  /** Das Strichmaennchen, in der Buehne: dieselben Seitenfarben wie im
   *  Katalog, nur als Linien im Raum statt auf einer 2D-Leinwand. */
  buildSkeletonLines() {
    const pairs = [];
    this.preview.parents.forEach((p, i) => { if (p >= 0) pairs.push([p, i]); });
    this.linePairs = pairs;

    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(new Float32Array(pairs.length * 6), 3));
    const colors = new Float32Array(pairs.length * 6);
    const left = new Color(ACCENT), right = new Color(WARM), mid = new Color(0xd9d5e4);
    pairs.forEach(([, i], n) => {
      const name = this.preview.bones[i];
      const base = name.startsWith('Left') ? left : name.startsWith('Right') ? right : mid;
      const fine = DETAIL.test(name);
      const c = base.clone().multiplyScalar(fine ? 0.45 : 1);
      for (let v = 0; v < 2; v++) c.toArray(colors, n * 6 + v * 3);
    });
    geometry.setAttribute('color', new BufferAttribute(colors, 3));

    // Ohne `toneMapped: false` laufen die Seitenfarben durch ACES und kommen
    // als zwei Grautoene heraus - die Unterscheidung links/rechts waere weg.
    this.lines = new LineSegments(
      geometry,
      new LineBasicMaterial({ vertexColors: true, toneMapped: false }),
    );
    this.lines.frustumCulled = false;
    this.lines.visible = false;
    this.scene.add(this.lines);

    // Gelenke als Punkte, der Kopf groesser - ein Strichmaennchen ohne Kopf
    // liest sich nicht. Die feinen Fingerknochen bleiben aus: 30 Punkte um
    // jede Hand sind nur Rauschen (dieselbe Entscheidung wie im Katalog).
    const shown = this.preview.bones
      .map((name, i) => ({ name, i }))
      .filter(({ name }) => !DETAIL.test(name));
    this.dotIndex = shown.map(({ i }) => i);

    const points = (count, size) => {
      const geometry = new BufferGeometry();
      geometry.setAttribute('position', new BufferAttribute(new Float32Array(count * 3), 3));
      const mesh = new Points(geometry, new PointsMaterial({
        color: 0xeeecf3,
        size,
        sizeAttenuation: true,
        map: dotTexture(),
        alphaTest: 0.5,
        transparent: true,
        toneMapped: false,
      }));
      mesh.frustumCulled = false;
      mesh.visible = false;
      this.scene.add(mesh);
      return mesh;
    };

    // Der Kopf braucht eine eigene Groesse; PointsMaterial kennt nur eine je
    // Wolke, also sind es zwei.
    this.dots = points(shown.length, 0.055);
    this.headDot = points(1, 0.19);
    this.headIndex = this.preview.bones.indexOf('Head');
  }

  // ── Zustand ────────────────────────────────────────────────────────────

  get showMesh() { return this._showMesh; }
  set showMesh(on) {
    this._showMesh = on;
    this.figure.visible = on;
    this.lines.visible = !on;
    this.dots.visible = !on;
    this.headDot.visible = !on && this.headIndex >= 0;
  }

  get showGrid() { return this._showGrid; }
  set showGrid(on) {
    this._showGrid = on;
    this.grid.visible = on;
    this.pool.visible = on;
  }

  resetCamera() {
    this.yaw = this.defaults.yaw;
    this.pitch = this.defaults.pitch;
    this.zoom = this.defaults.zoom;
    this.pan.set(0, 0, 0);
  }

  /**
   * Das Kameraziel in der Bildebene verschieben, um so viele CSS-Pixel, wie
   * die Maus gezogen wurde.
   *
   * GENAU UNTER DER MAUS, nicht mit einem Faktor. Die Workbench nimmt
   * 0,002 x Abstand je Pixel, und das passt dort zu einer festen Brennweite;
   * hier rechnet die Kamera aus Abstand und Oeffnungswinkel, wie viele Meter
   * ein Pixel in der Tiefe des Ziels misst. Was man anfasst, bleibt unter dem
   * Zeiger - die Szene folgt der Hand, wie in der Workbench auch.
   *
   * Der Versatz liegt auf dem Ziel und bleibt beim Mitfuehren der Kamera
   * erhalten: ist "Follow" an, folgt die Kamera der Huefte mit diesem Versatz.
   */
  panBy(dx, dy) {
    const height = this.canvas.clientHeight || 1;
    const perPixel = (2 * (this.distance || 1) * Math.tan((this.camera.fov * Math.PI) / 360)) / height;

    this.camera.updateMatrixWorld();
    const right = (this._panRight || (this._panRight = new Vector3()))
      .setFromMatrixColumn(this.camera.matrixWorld, 0);
    const up = (this._panUp || (this._panUp = new Vector3()))
      .setFromMatrixColumn(this.camera.matrixWorld, 1);

    this.pan.addScaledVector(right, -dx * perPixel).addScaledVector(up, dy * perPixel);
  }

  setTime(t) { this.time = Math.max(0, Math.min(this.duration, t)); }

  // ── Bild ───────────────────────────────────────────────────────────────

  resize() {
    const rect = this.canvas.getBoundingClientRect();
    const w = Math.max(1, Math.round(rect.width));
    const h = Math.max(1, Math.round(rect.height));
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();

    if (!this.surface) {
      this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      this.renderer.setSize(w, h, false);
      return;
    }

    //  Auf einer Karte ist die eigene Leinwand eine 2D-Leinwand: sie bekommt
    //  das fertige Bild kopiert. Gezeichnet wird in der gemeinsamen Flaeche,
    //  und die muss die groesste Karte fassen.
    //
    //  Nicht `this.height` - das ist die Groesse der FIGUR, und die steht in
    //  Metern.
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    this.pixelWidth = Math.round(w * ratio);
    this.pixelHeight = Math.round(h * ratio);
    this.canvas.width = this.pixelWidth;
    this.canvas.height = this.pixelHeight;
    this.surface.fit(this.pixelWidth, this.pixelHeight);
  }

  applyFrame(frame) {
    const rot = this.solved.rotations[frame];
    const pos = this.solved.positions[frame];
    //  Wiederverwendet statt je Bild neu angelegt - das hier laeuft 60-mal in
    //  der Sekunde.
    const desired = this._desired || (this._desired = new Quaternion());

    for (const i of this.order) {
      const parent = this.parentIndex[i];
      const parentWorld = parent >= 0 ? this.worldQuats[parent] : this.baseQuat;
      const track = this.trackOf.get(i);

      if (track) {
        desired.copy(rot[track.src]).multiply(track.correction);
        this.worldQuats[i].copy(desired);
        this.bones[i].quaternion.copy(parentWorld).invert().multiply(desired);
      } else {
        this.bones[i].quaternion.copy(this.bindLocal[i]);
        this.worldQuats[i].copy(parentWorld).multiply(this.bindLocal[i]);
      }
    }

    const hips = (this._hips || (this._hips = new Vector3())).copy(pos[0]).multiplyScalar(this.scale);
    hips.y += this.offsetY;
    this.hipsBone.position.copy(hips.applyMatrix4(this.hipsParentInverse));

    if (this.dots.visible) {
      const attr = this.dots.geometry.getAttribute('position');
      const array = attr.array;
      this.dotIndex.forEach((i, n) => {
        array[n * 3] = pos[i].x * this.scale;
        array[n * 3 + 1] = pos[i].y * this.scale + this.offsetY;
        array[n * 3 + 2] = pos[i].z * this.scale;
      });
      attr.needsUpdate = true;

      if (this.headIndex >= 0) {
        const head = this.headDot.geometry.getAttribute('position');
        const p = pos[this.headIndex];
        head.array[0] = p.x * this.scale;
        head.array[1] = p.y * this.scale + this.offsetY;
        head.array[2] = p.z * this.scale;
        head.needsUpdate = true;
      }
    }

    if (this.lines.visible) {
      const attr = this.lines.geometry.getAttribute('position');
      const array = attr.array;
      this.linePairs.forEach(([a, b], n) => {
        const pa = pos[a], pb = pos[b];
        array[n * 6] = pa.x * this.scale;
        array[n * 6 + 1] = pa.y * this.scale + this.offsetY;
        array[n * 6 + 2] = pa.z * this.scale;
        array[n * 6 + 3] = pb.x * this.scale;
        array[n * 6 + 4] = pb.y * this.scale + this.offsetY;
        array[n * 6 + 5] = pb.z * this.scale;
      });
      attr.needsUpdate = true;
    }

    return pos[0];
  }

  placeCamera(hips) {
    const follow = this.cameraFollow ? hips : new Vector3(0, 0, 0);
    this.target.set(follow.x * this.scale, this.height * 0.52, follow.z * this.scale).add(this.pan);

    // Schein und Schatten wandern mit, damit der Lichtkegel unter der Figur
    // bleibt statt am Ursprung zu kleben. Das Raster bleibt liegen.
    this.floor.position.set(hips.x * this.scale, 0, hips.z * this.scale);
    this.key.position.set(this.floor.position.x + 2.1, 4.9, this.floor.position.z - 2.4);
    this.key.target.position.copy(this.floor.position);
    this.key.target.updateMatrixWorld();

    // Abstand aus beiden Massen: die Figur soll stehen, ohne oben anzustossen,
    // und in einer schmalen Buehne auch nicht seitlich.
    const half = Math.tan((this.camera.fov * Math.PI) / 360);
    const distance = Math.max(
      (this.height * 0.70) / half,
      (this.height * 0.46) / half / Math.max(0.35, this.camera.aspect),
    ) / this.zoom;
    //  Gemerkt fuer [panBy]: wie viele Meter ein Pixel misst, haengt daran.
    this.distance = distance;
    const cp = Math.cos(this.pitch), sp = Math.sin(this.pitch);
    this.camera.position.set(
      this.target.x + Math.sin(this.yaw) * cp * distance,
      this.target.y + sp * distance,
      this.target.z + Math.cos(this.yaw) * cp * distance,
    );
    this.camera.lookAt(this.target);
  }

  /**
   * Die Zeit weiterstellen und Figur und Kamera dorthin setzen - ohne zu
   * zeichnen.
   *
   * Getrennt vom Zeichnen, weil eine Karte im Katalog beides nicht zusammen
   * tun kann: sie teilt sich ihren Renderer mit 23 anderen, und wer ihn haelt,
   * setzt erst den Ausschnitt und zeichnet dann (card-stage.js).
   *
   * Zurueck kommt die Nummer des Bildes, auf dem die Figur jetzt steht - wer
   * dasselbe Bild schon gezeichnet hat, kann es sich sparen.
   */
  step(timestamp) {
    if (this.last !== null && this.playing) {
      //  `speed`: 0.25 bis 2 aus der Transportleiste (viewer-ui.js).
      this.time += ((timestamp - this.last) / 1000) * (this.speed || 1);
      if (this.time > this.duration) this.time %= this.duration;
    }
    this.last = timestamp;

    const frame = Math.min(this.solved.frames - 1, Math.round(this.time * this.fps));
    this.placeCamera(this.applyFrame(frame));
    return frame;
  }

  loop(timestamp) {
    const frame = this.step(timestamp);

    //  NUR ZEICHNEN, WENN SICH ETWAS GEAENDERT HAT. Eine angehaltene Figur,
    //  die niemand dreht, ist Bild fuer Bild dasselbe - vorher wurde es
    //  trotzdem jedes Mal gerendert, Schatten inklusive. Der Schluessel haelt
    //  alles fest, was das Bild veraendert; wer von aussen etwas umstellt,
    //  das hier fehlt, ruft [invalidate].
    const c = this.renderer.domElement;
    const key = frame + '|' + this.yaw + '|' + this.pitch + '|' + this.zoom + '|'
      + this.pan.x + ',' + this.pan.y + ',' + this.pan.z + '|'
      + this.cameraFollow + '|' + this._showMesh + '|' + this._showGrid + '|'
      + this.proportions + '|' + c.width + 'x' + c.height;
    if (key !== this.drawnKey) {
      this.renderer.render(this.scene, this.camera);
      this.drawnKey = key;
    }
    if (this.onFrame) this.onFrame(this.time, this.duration);
  }

  /** Das naechste Bild auf jeden Fall zeichnen. */
  invalidate() {
    this.drawnKey = null;
  }

  /**
   * Abbauen - aber nur das Eigene.
   *
   * DIE HAUT GEHOERT NICHT DIESER BUEHNE. `cloneSkinned` klont das Skelett und
   * TEILT die Geometrie mit dem einmal geladenen Modell, also mit jeder
   * anderen Buehne auf der Seite. Wer sie hier freigibt, nimmt sie den anderen
   * weg. Dasselbe gilt fuer den Boden, der seit dem Katalog allen gehoert, und
   * fuer den Renderer, wenn er geliehen ist.
   *
   * Nur die Rasterkopie ist eigen. Sie teilt ihr Bild mit den anderen, three.js
   * zaehlt die Nutzer aber mit und loescht es erst mit der letzten.
   */
  dispose() {
    if (!this.surface) this.renderer.setAnimationLoop(null);
    if (this.observer) this.observer.disconnect();
    if (this.visibility) this.visibility.disconnect();

    const shared = new Set();
    this.model.traverse((node) => { if (node.geometry) shared.add(node.geometry); });

    this.scene.traverse((node) => {
      if (node.geometry && !shared.has(node.geometry)) node.geometry.dispose();
      if (node.material) {
        (Array.isArray(node.material) ? node.material : [node.material]).forEach((m) => m.dispose());
      }
    });
    this.gridMap.dispose();

    if (!this.surface) this.renderer.dispose();
  }
}

/**
 * Eine eigene Figur aus dem Speicher - die Datei, die die Workbench
 * geschrieben und der Nutzer hier abgelegt hat. Sie geht nie an den Server:
 * ein gekauftes Modell gehoert seinem Kaeufer, und im eigenen Browser ist es
 * dieselbe Ansicht, die Unity zwei Fenster weiter auch zeigt.
 *
 * `kind` ist `glb` oder `fbx`, `scale` die Anzahl Meter je Zahlenschritt der
 * Datei - beides kommt aus der Ablage. Ein FBX rechnet fast immer in
 * Zentimetern, und ohne die Umrechnung stuende hier eine Figur von 96 Metern.
 */
export async function parseFigure(buffer, kind = 'glb', scale = 1) {
  const { parseModel } = await import('./skeleton.js');
  const model = await parseModel(buffer, kind);
  if (scale && scale !== 1) model.scene.scale.setScalar(scale);
  return model;
}

/**
 * Baut eine Buehne. Wirft, wenn WebGL fehlt oder das Modell nicht kommt - der
 * Aufrufer faellt dann auf das Strichmaennchen zurueck.
 *
 * `options.figure` ist eine eigene Figur: `{ buffer, humanoid, kind, scale }`,
 * alles aus der abgelegten Datei. Ohne sie steht das Mannequin des Hauses da.
 */
export async function createMannequinStage(canvas, preview, options = {}) {
  const own = options.figure || null;
  const gltf = own ? await parseFigure(own.buffer, own.kind, own.scale) : await loadModel();

  return new MannequinStage(canvas, preview, gltf, own
    ? { ...options, own: true, boneMap: own.humanoid, unitScale: own.scale || 1,
        figureHeight: own.height || 0 }
    : options);
}
