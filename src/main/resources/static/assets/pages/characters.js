/**
 * Die Seite mit den eigenen Figuren.
 *
 * Sie holt nichts vom Server und schickt nichts hin: alles, was hier steht,
 * liegt in der IndexedDB dieses Browsers (`figures.js`). Der Server liefert
 * nur den Rahmen.
 *
 * WARUM ES DIESE SEITE GIBT. Die Auswahl steht als Schalterreihe an der
 * Buehne, und das bleibt auch so - dort entscheidet man, auf WEM der Clip
 * gerade laeuft. Aber ein Schalter ist kein Ort: Figuren hinzufuegen,
 * ansehen, wieder loswerden, nachsehen wie gross sie sind und wie viele
 * Knochen sie tragen - das braucht eine Seite, und die braucht einen Eintrag
 * in der Leiste, sonst findet die Funktion niemand.
 */

import {
  addFigure, getFigure, listFigures, rememberFigure, rememberedFigure, removeFigure, saveMapping,
} from '../figures.js';
import { editBoneMap } from '../bone-map.js';
import { BONES, REQUIRED, label } from '../humanoid.js';
import { parseFigure } from '../stage.js';
import {
  Box3, DirectionalLight, Group, HemisphereLight, PerspectiveCamera, Scene,
  SRGBColorSpace, Vector3, WebGLRenderer,
} from '../vendor/three.module.js';

const grid = document.getElementById('figures');
const drop = document.getElementById('drop');
const picker = document.getElementById('file');
const note = document.getElementById('note');

if (grid) start();

// ── Meldungen ─────────────────────────────────────────────────────────────

let noteTimer = 0;

function say(text, bad) {
  if (!note) return;
  note.textContent = text;
  note.className = 'notice' + (bad ? ' error' : '');
  note.hidden = false;
  clearTimeout(noteTimer);
  if (!bad) noteTimer = setTimeout(() => { note.hidden = true; }, 6000);
}

// ── Das Bild zur Figur ────────────────────────────────────────────────────

/**
 * EIN Renderer fuer alle Karten.
 *
 * Ein Browser gibt ungefaehr sechzehn WebGL-Kontexte her, und eine Seite mit
 * zwanzig Figuren haette gern zwanzig. Also zeichnet einer reihum in seine
 * eigene Flaeche, und jede Karte kopiert sich ihr fertiges Bild auf ihre 2D-
 * Leinwand - derselbe Griff wie im Katalog (`card-stage.js`).
 *
 * Ein Standbild reicht hier: wer die Figur in Bewegung sehen will, schickt
 * einen Clip darauf.
 */
const SIZE = 320;
let painter = null;

function makePainter() {
  const canvas = document.createElement('canvas');
  canvas.width = SIZE;
  canvas.height = SIZE;

  const renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(1);
  renderer.setSize(SIZE, SIZE, false);
  renderer.outputColorSpace = SRGBColorSpace;

  const scene = new Scene();
  scene.background = null;
  scene.add(new HemisphereLight(0xb9aeff, 0x0b0a10, 1.4));

  const key = new DirectionalLight(0xfff4e8, 2.4);
  key.position.set(2.1, 3.4, 2.6);
  scene.add(key);

  const rim = new DirectionalLight(0x8e77ff, 2.2);
  rim.position.set(-2.6, 1.8, -2.2);
  scene.add(rim);

  const camera = new PerspectiveCamera(30, 1, 0.05, 60);
  const holder = new Group();
  scene.add(holder);

  return { canvas, renderer, scene, camera, holder };
}

/** Zeichnet die Figur einmal und gibt die Flaeche zurueck, von der zu kopieren ist. */
async function paint(entry) {
  if (!painter) painter = makePainter();
  const { renderer, scene, camera, holder, canvas } = painter;

  const gltf = await parseFigure(await entry.blob.arrayBuffer(), entry.kind || 'glb', entry.scale || 1);
  holder.clear();
  holder.add(gltf.scene);
  scene.updateMatrixWorld(true);

  //  GEMESSEN WIRD AM SKELETT, NICHT AM MESH.
  //
  //  `Box3.setFromObject` nimmt fuer eine gehaeutete Haut ihren Geometrie-
  //  Kasten, und der steht im Mesh-Raum, vor jeder Haeutung. Bei einer Datei
  //  mit quantisierten Ecken liegt er zwischen -1 und 1, waehrend die Figur
  //  1,74 m gross ist - das Bild zeigte die Figur dann angeschnitten, und es
  //  waere nie aufgefallen, wenn man nicht genau hinsieht.
  //
  //  Die Knochen stehen dagegen dort, wo die Figur wirklich steht. Ein
  //  Zuschlag von acht Prozent nimmt mit, was ueber sie hinausragt - Kopf,
  //  Fuesse, Haare.
  const box = new Box3();
  let bones = 0;
  gltf.scene.traverse((node) => {
    if (node.isBone) { box.expandByPoint(node.getWorldPosition(new Vector3())); bones++; }
  });

  if (bones < 2) box.setFromObject(gltf.scene);
  else box.expandByScalar((box.max.y - box.min.y) * 0.08);

  const size = new Vector3();
  const middle = new Vector3();
  box.getSize(size);
  box.getCenter(middle);

  const height = Math.max(0.01, size.y);
  gltf.scene.position.sub(middle);
  gltf.scene.position.y += height / 2;
  scene.updateMatrixWorld(true);

  //  Von schraeg vorn, derselbe Blickwinkel wie auf der Buehne.
  const half = Math.tan((camera.fov * Math.PI) / 360);
  const distance = (height * 0.54) / half;
  const yaw = Math.PI - 0.55;
  camera.position.set(
    Math.sin(yaw) * Math.cos(0.18) * distance,
    height * 0.52 + Math.sin(0.18) * distance,
    Math.cos(yaw) * Math.cos(0.18) * distance,
  );
  camera.lookAt(0, height * 0.52, 0);
  camera.updateProjectionMatrix();

  renderer.render(scene, camera);

  //  Die Figur wieder abhaengen, aber ihre Geometrie NICHT entsorgen: sie
  //  gehoert dem frisch geparsten glTF, das gleich ohnehin verfaellt.
  holder.clear();
  return canvas;
}

// ── Die Karten ────────────────────────────────────────────────────────────

const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};

const kb = (bytes) => (bytes > 1024 * 1024
  ? (bytes / 1024 / 1024).toFixed(1) + ' MB'
  : Math.round(bytes / 1024) + ' KB');

function card(entry, active) {
  const box = el('article', 'figure-card' + (active ? ' active' : ''));

  const canvas = document.createElement('canvas');
  canvas.width = SIZE;
  canvas.height = SIZE;
  canvas.className = 'figure-shot';
  box.append(canvas);

  const body = el('div', 'figure-body');
  body.append(el('h3', null, entry.name));

  const facts = [
    entry.height ? entry.height.toFixed(2) + ' m' : null,
    entry.joints ? entry.joints + ' bones' : null,
    entry.triangles ? entry.triangles.toLocaleString() + ' triangles' : null,
    entry.textures ? (entry.textures === 1 ? '1 texture' : entry.textures + ' textures') : null,
    kb(entry.bytes),
  ].filter(Boolean);

  body.append(el('p', 'muted small', facts.join(' · ')));

  //  Die Zahl sagt, wie viel von der Vorschau ueberhaupt ankommen kann. 55
  //  sind alle; wer weniger hat, sieht die fehlenden Glieder stillstehen.
  const mapped = Object.keys(entry.humanoid || {}).length;
  body.append(el('p', 'muted small', mapped + ' of ' + BONES.length + ' humanoid bones mapped'));

  //  WOHER DIE ZUORDNUNG KOMMT, gehoert auf die Karte. Aus der Workbench ist
  //  sie richtig - dort kennt Unity beide Namen. Geraten ist sie ein
  //  Vorschlag, und ein Vorschlag, den niemand angesehen hat, soll nicht so
  //  aussehen wie eine Tatsache.
  const gaps = REQUIRED.filter((bone) => !(entry.humanoid || {})[bone]);
  if (gaps.length) {
    box.classList.add('figure-broken');
    body.append(el('p', 'figure-flag bad',
      'No clip can run yet: ' + gaps.map(label).join(', ').toLowerCase() + ' missing.'));
  } else if (entry.checked === false) {
    box.classList.add('figure-unsure');
    body.append(el('p', 'figure-flag',
      'Bones guessed from the file - worth a look before you trust it.'));
  }

  for (const line of entry.notes || []) body.append(el('p', 'figure-flag', line));

  const actions = el('div', 'figure-actions');

  const use = el('button', 'button' + (active ? '' : ' primary'), active ? 'In use' : 'Use this one');
  use.type = 'button';
  use.disabled = !!active || gaps.length > 0;
  use.addEventListener('click', () => {
    rememberFigure(entry.id);
    say(entry.name + ' will carry the clips from now on.');
    draw();
  });

  const check = el('button', 'button', entry.checked === false || gaps.length ? 'Check bones' : 'Bones');
  check.type = 'button';
  check.addEventListener('click', () => openMapping(entry.id));

  const forget = el('button', 'button danger', 'Forget');
  forget.type = 'button';
  forget.addEventListener('click', async () => {
    const sure = await AW.confirmDialog({
      title: 'Forget ' + entry.name + '?',
      body: 'It goes from this browser. The file on your disk stays where it is.',
      confirm: 'Forget', danger: true,
    });
    if (!sure) return;
    await removeFigure(entry.id);
    if (rememberedFigure() === entry.id) rememberFigure('');
    say(entry.name + ' is gone from this browser.');
    draw();
  });

  actions.append(use, check, forget);
  body.append(actions);
  box.append(body);

  //  Das Bild kommt nach, wenn die Karte steht - ein Renderer zeichnet reihum,
  //  und niemand soll auf eine leere Seite schauen, bis er fertig ist.
  paint(entry).then((source) => {
    const ctx = canvas.getContext('2d');
    if (ctx) ctx.drawImage(source, 0, 0);
  }).catch((error) => {
    canvas.remove();
    console.warn('[characters] no picture for ' + entry.name, error);
  });

  return box;
}

function empty() {
  const box = el('div', 'figure-empty');
  box.append(el('p', null, 'No characters here yet.'));
  box.append(el('p', 'muted small',
    'Until you add one, every clip runs on the mannequin the Workbench ships with.'));
  return box;
}

async function draw() {
  const figures = await listFigures();
  const active = rememberedFigure();

  grid.replaceChildren();
  if (!figures.length) {
    grid.append(empty());
    return;
  }

  //  Die gewaehlte Figur zuerst: sie ist die Antwort auf die Frage, mit der
  //  man hierherkommt.
  figures.sort((a, b) => (b.id === active) - (a.id === active) || a.added - b.added);
  for (const entry of figures) grid.append(card(entry, entry.id === active));
}

// ── Die Knochen nachsehen ─────────────────────────────────────────────────

/**
 * Die Maske oeffnen und das Ergebnis ablegen.
 *
 * Die Knochenliste liegt nur bei geratenen Figuren in der Ablage - eine
 * Workbench-Figur brachte ihre Zuordnung ja mit. Wer sie trotzdem ansehen
 * will, bekommt sie hier frisch aus der Datei gelesen; das kostet einen
 * Moment und passiert nur auf Klick.
 */
async function openMapping(id) {
  const entry = await getFigure(id);
  if (!entry) return;

  let joints = entry.skeleton;
  if (!joints || !joints.length) {
    say('Reading the skeleton of ' + entry.name + ' …');
    try {
      const { readJoints } = await import('../skeleton.js');
      joints = await readJoints(await entry.blob.arrayBuffer(), entry.name + '.' + (entry.kind || 'glb'));
    } catch (error) {
      say('The skeleton of ' + entry.name + ' could not be read.', true);
      console.warn('[characters] cannot read bones', error);
      return;
    }
  }

  const chosen = await editBoneMap({
    name: entry.name,
    joints,
    humanoid: entry.humanoid || {},
    guessed: entry.checked === false ? entry.humanoid : null,
  });
  if (!chosen) return;

  await saveMapping(id, chosen);
  say('The bones of ' + entry.name + ' are set.');
  await draw();
}

// ── Hereinziehen ──────────────────────────────────────────────────────────

async function take(file) {
  if (!file) return;

  //  Eine fremde .fbx kann sechzehn Megabyte gross sein, und sie zu oeffnen
  //  dauert dann spuerbar. Ohne ein Wort dazu sieht die Seite aus, als haette
  //  sie das Ablegen verschluckt.
  say('Reading ' + file.name + ' …');

  try {
    const entry = await addFigure(file);

    if (entry.checked === false) {
      //  Geraten heisst vorlegen. Die Maske geht von selbst auf - wer eine
      //  fremde Datei ablegt, soll nicht erst einen Knopf finden muessen, um
      //  zu erfahren, dass hier etwas geraten wurde.
      const gaps = REQUIRED.filter((bone) => !entry.humanoid[bone]);
      say(gaps.length
        ? file.name + ' is in, but ' + gaps.length + ' bones a clip needs are still open.'
        : entry.name + ' is here now - please check the bones.');
      await draw();
      await openMapping(entry.id);

      //  NEU NACHSEHEN, nicht `entry` glauben: der liegt noch so da, wie er
      //  vor der Maske war. Erst die Zuordnung, die jetzt in der Ablage
      //  steht, entscheidet, ob die Figur einen Clip tragen kann.
      const saved = await getFigure(entry.id);
      const ready = saved && !REQUIRED.some((bone) => !(saved.humanoid || {})[bone]);
      if (ready) {
        rememberFigure(entry.id);
        say(saved.name + ' will carry the clips from now on.');
      }
      await draw();
      return;
    }

    rememberFigure(entry.id);
    say(entry.name + ' is here now, and clips will run on it.');
    await draw();
  } catch (error) {
    say(error.message || 'That file could not be read.', true);
    console.warn('[characters] import failed', error);
  }
}

function start() {
  draw();

  document.getElementById('pick').addEventListener('click', () => picker.click());
  picker.addEventListener('change', () => {
    const file = picker.files && picker.files[0];
    picker.value = '';
    take(file);
  });

  //  Auf die ganze Seite ziehen, nicht nur auf das Feld: wer eine Datei in
  //  der Hand hat, zielt nicht.
  let depth = 0;
  addEventListener('dragenter', (event) => {
    event.preventDefault();
    if (++depth === 1) drop.classList.add('on');
  });
  addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; drop.classList.remove('on'); } });
  addEventListener('dragover', (event) => event.preventDefault());
  addEventListener('drop', (event) => {
    event.preventDefault();
    depth = 0;
    drop.classList.remove('on');
    take(event.dataTransfer && event.dataTransfer.files[0]);
  });
}
