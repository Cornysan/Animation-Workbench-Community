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

const MODEL_URL = '/models/aw-mannequin.glb';

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
 * Welche Knochenpaare die Breite des Koerpers aufspannen.
 *
 * Aus ihnen entsteht je Frame eine Querachse in Weltkoordinaten, und die
 * beantwortet die Frage, die die Richtung zum Kind offen laesst: wie ist ein
 * Knochen UM seine eigene Achse gedreht. Zwei Paare, weil eines allein
 * kippen kann - ein Bein hebt sich, eine Schulter zieht hoch; gemittelt
 * bleibt die Achse ruhig.
 *
 * Es sind bewusst GLOBALE Paare, keine Kinderpaare je Knochen: ein Knochen
 * braucht die Breite nicht an sich selbst zu tragen, um zu wissen, wo links
 * ist. Siehe die Rechnung weiter unten.
 *
 * [BODY_UP] gibt die zweite Achse - von der Huefte zum Brustkorb. Aus beiden
 * folgt die dritte. Drei braucht es, weil ein Schluesselbein selbst nach der
 * Seite zeigt und mit der Querachse zusammenfaellt; es greift dann zur
 * Hochachse.
 */
const BODY_UP = ['Hips', 'UpperChest'];

const BODY_WIDTH_PAIRS = [
  ['LeftUpperLeg', 'RightUpperLeg'],
  ['LeftShoulder', 'RightShoulder'],
];

/** Feine Knochen - dieselbe Unterscheidung wie im Strichmaennchen. */
const DETAIL = /^(Left|Right)(Thumb|Index|Middle|Ring|Little)/;

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

/** Den Boden zeichnen wir einmal in eine Leinwand - der Abfall kostet dann pro
 *  Bild nichts, und ein Rand entsteht gar nicht erst. */
function groundTexture(size = 1024) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');
  const mid = size / 2;

  const pool = ctx.createRadialGradient(mid, mid, 0, mid, mid, size * 0.26);
  pool.addColorStop(0, 'rgba(70, 60, 118, 0.85)');
  pool.addColorStop(0.45, 'rgba(42, 36, 72, 0.55)');
  pool.addColorStop(1, 'rgba(18, 16, 28, 0)');
  ctx.fillStyle = pool;
  ctx.fillRect(0, 0, size, size);

  ctx.strokeStyle = 'rgba(168, 148, 255, 0.5)';
  ctx.lineWidth = Math.max(1, size / 512);
  const step = size / 20;
  ctx.beginPath();
  for (let i = 0; i <= 20; i++) {
    const p = Math.round(i * step) + 0.5;
    ctx.moveTo(p, 0); ctx.lineTo(p, size);
    ctx.moveTo(0, p); ctx.lineTo(size, p);
  }
  ctx.stroke();

  // Die Fahne muss WEIT vor dem Rand auf null sein. Eine Ebene unter diesem
  // Winkel presst ihre letzte Tiefe in ein paar Pixel, und ein Verlauf, der
  // dort noch bei einem Zehntel steht, kommt als gezogener Strich an.
  const fade = ctx.createRadialGradient(mid, mid, 0, mid, mid, size * 0.3);
  fade.addColorStop(0, 'rgba(0,0,0,1)');
  fade.addColorStop(0.55, 'rgba(0,0,0,0.92)');
  fade.addColorStop(0.8, 'rgba(0,0,0,0.35)');
  fade.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.globalCompositeOperation = 'destination-in';
  ctx.fillStyle = fade;
  ctx.fillRect(0, 0, size, size);

  const texture = new CanvasTexture(canvas);
  texture.colorSpace = SRGBColorSpace;
  texture.anisotropy = 4;
  return texture;
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
function loadModel() {
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

    this.buildScene(gltf);
    this.buildSkeletonLines();
    this.bindRetarget();

    if (options.interactive !== false) this.attachInput();
    this.resize();
    this.observer = new ResizeObserver(() => this.resize());
    this.observer.observe(canvas);

    this.last = null;
    this.loop = this.loop.bind(this);
    this.renderer.setAnimationLoop(this.loop);
  }

  /** Ziehen dreht, Rad zoomt - beide Achsen GEGEN die Maus, wie im
   *  Strichmaennchen. Wer hier ein Vorzeichen drehen will: erst pruefen,
   *  welcher Stand ausgeliefert ist (der Build-Stempel im Fuss sagt es). */
  attachInput() {
    let drag = null;
    this.canvas.addEventListener('pointerdown', (e) => {
      drag = { x: e.clientX, y: e.clientY, yaw: this.yaw, pitch: this.pitch };
      this.canvas.setPointerCapture(e.pointerId);
    });
    this.canvas.addEventListener('pointermove', (e) => {
      if (!drag) return;
      this.yaw = drag.yaw - (e.clientX - drag.x) * 0.01;
      this.pitch = Math.max(-0.9, Math.min(1.1, drag.pitch - (e.clientY - drag.y) * 0.01));
    });
    this.canvas.addEventListener('pointerup', () => (drag = null));
    this.canvas.addEventListener('pointercancel', () => (drag = null));
    this.canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      this.zoom = Math.max(0.35, Math.min(4, this.zoom * (e.deltaY > 0 ? 0.9 : 1.1)));
    }, { passive: false });
  }

  // ── Aufbau ─────────────────────────────────────────────────────────────

  buildScene(gltf) {
    this.renderer = new WebGLRenderer({ canvas: this.canvas, antialias: true, alpha: true });
    this.renderer.setClearAlpha(0);
    this.renderer.toneMapping = ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.12;
    this.renderer.shadowMap.enabled = true;

    this.scene = new Scene();
    this.camera = new PerspectiveCamera(32, 1, 0.05, 60);

    this.scene.add(new HemisphereLight(0xb9aeff, 0x0b0a10, 1.0));

    const key = new DirectionalLight(0xfff4e8, 2.5);
    key.position.set(2.1, 4.9, -2.4);
    key.castShadow = true;
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

    this.groundMap = groundTexture();
    this.ground = new Mesh(
      new PlaneGeometry(6.4, 6.4),
      new MeshBasicMaterial({ map: this.groundMap, transparent: true, depthWrite: false, toneMapped: false }),
    );
    this.ground.rotation.x = -Math.PI / 2;
    this.scene.add(this.ground);

    this.contact = new Mesh(new PlaneGeometry(3.2, 3.2), new ShadowMaterial({ opacity: 0.5 }));
    this.contact.rotation.x = -Math.PI / 2;
    this.contact.position.y = 0.002;
    this.contact.receiveShadow = true;
    this.contact.renderOrder = 1;
    this.scene.add(this.contact);

    // Die Buehne folgt der Huefte; damit Boden und Schatten mitgehen, haengen
    // sie an einem eigenen Knoten statt an der Welt.
    this.floor = new Group();
    this.floor.add(this.ground, this.contact);
    this.scene.add(this.floor);

    this.figure = new Group();
    this.scene.add(this.figure);

    // clone() TEILT das Skelett: zwei Buehnen auf einer Seite posieren dann
    // dieselben Knochen und werfen sich gegenseitig aus der Pose. cloneSkinned
    // (SkeletonUtils) haengt die geklonte Haut an die geklonten Knochen.
    const model = cloneSkinned(gltf.scene);
    model.traverse((node) => {
      if (node.isMesh || node.isSkinnedMesh) {
        node.castShadow = true;
        node.receiveShadow = false;
        node.frustumCulled = false; // die Haut ist quantisiert, ihr eigener Kasten luegt
        const seam = node.material.name === SEAMS;
        node.material = seam
          ? new MeshStandardMaterial({ color: ACCENT, emissive: 0x2b1f6b, roughness: 0.38 })
          : new MeshStandardMaterial({ color: 0xe9e6f2, roughness: 0.55 });
        if (!this.skinned) this.skinned = node;
      }
    });
    this.figure.add(model);
    this.model = model;
    this.scene.updateMatrixWorld(true);
  }

  // ── Bindung an die Vorschau ────────────────────────────────────────────

  bindRetarget() {
    const skeleton = this.skinned.skeleton;
    const bones = skeleton.bones;
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


    // Bindepose des Modells: Weltmatrix je Knochen ist die Inverse der
    // inversen Bindematrix.
    const bindWorld = skeleton.boneInverses.map((m) => m.clone().invert());
    const bindPos = bindWorld.map((m) => new Vector3().setFromMatrixPosition(m));
    const bindRot = bindWorld.map((m) => new Quaternion().setFromRotationMatrix(m));

    /**
     * Die Richtung zum Kind, einmal in der Quelle und einmal im Modell, beide
     * im lokalen Raum des Knochens `ti`.
     */
    const dirTo = (ti, childName) => {
      const ci = srcIndex.get(childName);
      const tci = byKey.get(boneKey(BONE_MAP[childName] || ''));
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
     * JETZT WIRD SIE GEMESSEN STATT VERERBT. Der Koerper hat drei Achsen, und
     * alle drei sind in jedem Frame aus der Geometrie bekannt: quer (Bein zu
     * Bein, Schulter zu Schulter), hoch (Huefte zum Brustkorb) und vor (das
     * Kreuzprodukt). Rechnet man sie in den lokalen Raum eines Knochens
     * zurueck, stehen sie ueber die Frames fast still - dreht sich der ganze
     * Koerper, dreht sich die Weltrotation des Knochens mit und hebt die
     * Drehung auf. Nur eine echte Verdrehung DIESES Knochens gegen den Koerper
     * bewegt sie, und die mittelt sich ueber einen Clip heraus. Gemessen an
     * einem Clip mit 56 Frames: Stabilitaet 0,97 bis 1,00.
     *
     * WARUM DREI UND NICHT NUR DIE QUERACHSE. Ein Dreibein braucht zwei
     * Richtungen, die nicht parallel sind. Fuer Rumpf und Beine steht die
     * Querachse schoen quer - aber ein Schluesselbein zeigt selbst nach der
     * Seite, und damit faellt es mit ihr zusammen: gemessen 10,9 Grad an der
     * linken Schulter, 17,4 an der rechten. Aus so einem Paar wird kein
     * Dreibein, sondern Rauschen, und das Rauschen sass sichtbar im
     * Oberkoerper. Jeder Knochen nimmt deshalb die Achse, die am weitesten von
     * seiner eigenen Richtung wegzeigt - die Schultern greifen zur Hochachse
     * und stehen damit bei 85 Grad.
     *
     * Die Wahl faellt an der QUELLE und gilt fuer beide Seiten. Zwei
     * verschiedene Achsen zu vergleichen waere schlimmer als eine schlechte.
     */
    const bodyAxesAt = (positions) => {
      const across = new Vector3();
      for (const [leftName, rightName] of BODY_WIDTH_PAIRS) {
        const li = srcIndex.get(leftName), ri = srcIndex.get(rightName);
        if (li === undefined || ri === undefined) continue;
        const d = positions[li].clone().sub(positions[ri]);
        if (d.lengthSq() > 1e-12) across.add(d.normalize());
      }
      const hi = srcIndex.get(BODY_UP[0]), ti = srcIndex.get(BODY_UP[1]);
      if (across.lengthSq() < 1e-12 || hi === undefined || ti === undefined) return null;
      across.normalize();

      const up = positions[ti].clone().sub(positions[hi]);
      if (up.lengthSq() < 1e-12) return null;
      up.normalize();

      const forward = new Vector3().crossVectors(across, up);
      if (forward.lengthSq() < 1e-12) return null;
      return [across, up, forward.normalize()];
    };

    //  Quelle: je Knochen und Achse ueber alle Frames mitteln.
    const srcAxes = this.preview.bones.map(() => [new Vector3(), new Vector3(), new Vector3()]);
    let haveAxes = false;
    for (let f = 0; f < this.solved.frames; f++) {
      const axes = bodyAxesAt(this.solved.positions[f]);
      if (!axes) break;
      haveAxes = true;
      const rotations = this.solved.rotations[f];
      for (let i = 0; i < srcAxes.length; i++) {
        const inverse = rotations[i].clone().invert();
        for (let k = 0; k < 3; k++) srcAxes[i][k].add(axes[k].clone().applyQuaternion(inverse));
      }
    }
    srcAxes.forEach((set) => set.forEach((v) => { if (v.lengthSq() > 1e-12) v.normalize(); }));

    //  Ziel: dieselben drei aus der Bindepose des Modells, in Weltkoordinaten.
    const dstAxes = (() => {
      if (!haveAxes) return null;
      const across = new Vector3();
      for (const [leftName, rightName] of BODY_WIDTH_PAIRS) {
        const tl = byKey.get(boneKey(BONE_MAP[leftName] || ''));
        const tr = byKey.get(boneKey(BONE_MAP[rightName] || ''));
        if (tl === undefined || tr === undefined) continue;
        const d = bindPos[tl].clone().sub(bindPos[tr]);
        if (d.lengthSq() > 1e-12) across.add(d.normalize());
      }
      const th = byKey.get(boneKey(BONE_MAP[BODY_UP[0]] || ''));
      const tt = byKey.get(boneKey(BONE_MAP[BODY_UP[1]] || ''));
      if (across.lengthSq() < 1e-12 || th === undefined || tt === undefined) return null;
      across.normalize();
      const up = bindPos[tt].clone().sub(bindPos[th]);
      if (up.lengthSq() < 1e-12) return null;
      up.normalize();
      const forward = new Vector3().crossVectors(across, up);
      if (forward.lengthSq() < 1e-12) return null;
      return [across, up, forward.normalize()];
    })();

    /**
     * Welche der drei Achsen dieser Knochen als Referenz nimmt: die, die am
     * weitesten von seiner eigenen Richtung wegzeigt.
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
      if (!dstAxes) return null;
      const k = pickAxis(si, aimSrc);
      if (k < 0) return null;
      return { src: srcAxes[si][k], dst: dstAxes[k].clone().applyQuaternion(bindRot[ti].clone().invert()) };
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

    /**
     * Ein Endknochen - Zehen, Fingerspitzen, Kopf - hat kein Kind und damit
     * keine eigene Richtung. Bis hierher erbte er die Korrektur seines
     * Elternknochens; an den Zehen sah man das, sie knickten nach unten.
     *
     * Er braucht sie nicht zu erben: die drei Koerperachsen gibt es auch fuer
     * ihn, und ZWEI davon bestimmen eine Orientierung vollstaendig. Genommen
     * werden die beiden, die am weitesten auseinanderliegen.
     */
    const endBoneFrame = (ti, si) => {
      if (!dstAxes) return null;
      let bi = 0, bj = 1, bestDot = 1;
      for (let k = 0; k < 3; k++) {
        for (let l = k + 1; l < 3; l++) {
          const dot = Math.abs(srcAxes[si][k].dot(srcAxes[si][l]));
          if (dot < bestDot) { bestDot = dot; bi = k; bj = l; }
        }
      }
      const inverse = bindRot[ti].clone().invert();
      const qSrc = frame(srcAxes[si][bi], srcAxes[si][bj]);
      const qDst = frame(dstAxes[bi].clone().applyQuaternion(inverse), dstAxes[bj].clone().applyQuaternion(inverse));
      return qSrc && qDst ? qSrc.multiply(qDst.invert()) : null;
    };

    this.tracks = [];
    const corrections = new Map();

    for (const [srcName, defName] of Object.entries(BONE_MAP)) {
      const si = srcIndex.get(srcName);
      const ti = byKey.get(boneKey(defName));
      if (si === undefined || ti === undefined) continue;

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

    // Endknochen (Zehen, Fingerspitzen) haben kein Kind und damit keine eigene
    // Richtung. Sie bekommen ihr Dreibein aus zwei Koerperachsen - siehe
    // [endBoneFrame]. Erst wenn auch das nicht geht, erben sie vom
    // Elternknochen; die Zehen knickten genau davon nach unten.
    for (const track of this.tracks) {
      if (track.correction) continue;

      track.correction = endBoneFrame(track.bone, track.src);
      if (track.correction) continue;

      let p = this.preview.parents[track.src];
      while (p >= 0) {
        const parentName = this.preview.bones[p];
        if (corrections.has(parentName)) { track.correction = corrections.get(parentName); break; }
        p = this.preview.parents[p];
      }
      if (!track.correction) track.correction = new Quaternion();
    }

    // Reihenfolge: Eltern vor Kindern, sonst steht beim Umrechnen ins Lokale
    // die Weltdrehung des Elternknochens noch nicht fest.
    const depth = bones.map((b) => {
      let d = 0;
      for (let p = b.parent; p; p = p.parent) d++;
      return d;
    });
    this.order = bones.map((_, i) => i).sort((a, b) => depth[a] - depth[b]);
    this.trackOf = new Map(this.tracks.map((t) => [t.bone, t]));
    this.bones = bones;
    this.bindLocal = bones.map((b) => b.quaternion.clone());
    this.worldQuats = bones.map(() => new Quaternion());
    this.parentIndex = bones.map((b) => (b.parent ? byKey.get(b.parent.name) ?? -1 : -1));

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
    const tThigh = byKey.get(boneKey('DEF-thigh.L'));
    const tShin = byKey.get(boneKey('DEF-shin.L'));
    const tFoot = byKey.get(boneKey('DEF-foot.L'));
    const dstLeg = (tThigh !== undefined && tShin !== undefined && tFoot !== undefined)
      ? bindPos[tShin].distanceTo(bindPos[tThigh]) + bindPos[tFoot].distanceTo(bindPos[tShin])
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
    this.target = new Vector3(0, this.height * 0.5, 0);

    this.offsetY = 0;
    this.offsetY = -this.measureFloor();
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
    this.skinned.computeBoundingBox();
    const box = this.skinned.boundingBox;
    if (!box) return this.solved.floorY * this.scale;
    return new Box3().copy(box).applyMatrix4(this.skinned.matrixWorld).min.y;
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
    this.ground.visible = on;
  }

  resetCamera() {
    this.yaw = this.defaults.yaw;
    this.pitch = this.defaults.pitch;
    this.zoom = this.defaults.zoom;
  }

  setTime(t) { this.time = Math.max(0, Math.min(this.duration, t)); }

  // ── Bild ───────────────────────────────────────────────────────────────

  resize() {
    const rect = this.canvas.getBoundingClientRect();
    const w = Math.max(1, Math.round(rect.width));
    const h = Math.max(1, Math.round(rect.height));
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }

  applyFrame(frame) {
    const rot = this.solved.rotations[frame];
    const pos = this.solved.positions[frame];
    const desired = new Quaternion();

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

    const hips = pos[0].clone().multiplyScalar(this.scale);
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
    this.target.set(follow.x * this.scale, this.height * 0.52, follow.z * this.scale);

    // Boden und Schatten wandern mit, damit der Lichtkegel unter der Figur
    // bleibt statt am Ursprung zu kleben.
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
    const cp = Math.cos(this.pitch), sp = Math.sin(this.pitch);
    this.camera.position.set(
      this.target.x + Math.sin(this.yaw) * cp * distance,
      this.target.y + sp * distance,
      this.target.z + Math.cos(this.yaw) * cp * distance,
    );
    this.camera.lookAt(this.target);
  }

  loop(timestamp) {
    if (this.last !== null && this.playing) {
      this.time += (timestamp - this.last) / 1000;
      if (this.time > this.duration) this.time %= this.duration;
    }
    this.last = timestamp;

    const frame = Math.min(this.solved.frames - 1, Math.round(this.time * this.fps));
    const hips = this.applyFrame(frame);
    this.placeCamera(hips);
    this.renderer.render(this.scene, this.camera);
    if (this.onFrame) this.onFrame(this.time, this.duration);
  }

  dispose() {
    this.renderer.setAnimationLoop(null);
    if (this.observer) this.observer.disconnect();
    this.scene.traverse((node) => {
      if (node.geometry) node.geometry.dispose();
      if (node.material) {
        (Array.isArray(node.material) ? node.material : [node.material]).forEach((m) => m.dispose());
      }
    });
    this.groundMap.dispose();
    this.renderer.dispose();
  }
}

/**
 * Baut eine Buehne. Wirft, wenn WebGL fehlt oder das Modell nicht kommt - der
 * Aufrufer faellt dann auf das Strichmaennchen zurueck.
 */
export async function createMannequinStage(canvas, preview, options) {
  const gltf = await loadModel();
  return new MannequinStage(canvas, preview, gltf, options);
}
