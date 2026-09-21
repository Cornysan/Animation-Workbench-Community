/**
 * Die eigenen Figuren - im Browser, nicht auf dem Server.
 *
 * ── Warum lokal ──────────────────────────────────────────────────────────
 *
 * Eine Figur ist ein Mesh, und ein Mesh gehoert jemandem. Ein gekauftes
 * Synty- oder Mixamo-Modell auf unserem Server waere eine Kopie eines
 * lizenzierten Assets - mit allem, was daran haengt: Rechte, Loeschfristen,
 * Haftung. Im Browser des Kaeufers ist es dieselbe Ansicht, die Unity zwei
 * Fenster weiter auch zeigt.
 *
 * Deshalb IndexedDB und kein Upload. Die Datei kommt aus der Workbench
 * (Figuren-Ansicht, "Use my figures on the portal", Export), wird hier
 * abgelegt und von hier geladen. Sie verlaesst dieses Geraet nie, und was
 * hier liegt, sieht niemand sonst - auch wir nicht.
 *
 * ── Was in der Datei steht ───────────────────────────────────────────────
 *
 * Ein gewoehnliches `.glb`, das jeder Betrachter oeffnen kann, plus ein
 * `extras`-Block, den die Workbench schreibt:
 *
 *   { "aw": 1, "name": "...", "rig": "humanoid", "pose": "tpose",
 *     "height": 1.74, "bones": {...}, "humanoid": { "Hips": "B_Hip", ... } }
 *
 * `humanoid` ist der Teil, ohne den nichts geht: eine Vorschau nennt ihre
 * Knochen `LeftUpperArm`, das Skelett in der Datei heisst vielleicht
 * `B_UpperArm_L`. Nur der Exporteur kennt beide Namen.
 *
 * ── Und fremde Dateien ───────────────────────────────────────────────────
 *
 * Eine `.fbx` von Mixamo oder eine `.glb` aus Blender bringt diesen Block
 * nicht mit. Die kommt trotzdem herein: dann werden die Knochennamen gelesen
 * und die Zuordnung GERATEN (`skeleton.js`, `humanoid.js`). Was dabei
 * herauskommt, ist ein Vorschlag - `checked: false` -, und die Seite legt ihn
 * vor, statt ihn zu verschweigen.
 */

const DB = 'aw-figures';
const STORE = 'figures';
const VERSION = 1;

let dbPromise = null;

function open() {
  if (dbPromise) return dbPromise;

  dbPromise = new Promise((resolve, reject) => {
    if (!('indexedDB' in window)) {
      reject(new Error('This browser keeps no local storage for figures.'));
      return;
    }

    const request = indexedDB.open(DB, VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'id' });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });

  //  Ein Fehlschlag darf sich nicht festsetzen: im privaten Fenster kann der
  //  naechste Versuch gelingen.
  dbPromise.catch(() => { dbPromise = null; });
  return dbPromise;
}

function run(mode, work) {
  return open().then((db) => new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, mode);
    const request = work(tx.objectStore(STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  }));
}

/** Alle abgelegten Figuren, aelteste zuerst - die Reihenfolge, in der sie kamen. */
export async function listFigures() {
  try {
    const all = await run('readonly', (store) => store.getAll());
    return (all || []).sort((a, b) => a.added - b.added);
  } catch (error) {
    console.warn('[figures] cannot read the local store', error);
    return [];
  }
}

export async function getFigure(id) {
  try {
    return await run('readonly', (store) => store.get(id));
  } catch {
    return null;
  }
}

export async function removeFigure(id) {
  await run('readwrite', (store) => store.delete(id));
}

// ── Lesen, was in der Datei steht ─────────────────────────────────────────

const MAGIC = 0x46546c67; // "glTF"
const JSON_CHUNK = 0x4e4f534a;

/**
 * Den JSON-Kopf eines `.glb` lesen, ohne three.js zu bemuehen. Der Kopf ist
 * das Erste in der Datei, und mehr braucht die Ablage nicht: Name, Masse und
 * die Zuordnung der Knochen. Das Mesh wird erst geladen, wenn jemand die
 * Figur auch ansieht.
 */
function readHeader(buffer) {
  const view = new DataView(buffer);
  if (buffer.byteLength < 20 || view.getUint32(0, true) !== MAGIC) {
    throw new Error('This is not a .glb file.');
  }

  let offset = 12;
  while (offset + 8 <= buffer.byteLength) {
    const length = view.getUint32(offset, true);
    const kind = view.getUint32(offset + 4, true);
    if (kind === JSON_CHUNK) {
      const text = new TextDecoder().decode(new Uint8Array(buffer, offset + 8, length));
      return JSON.parse(text);
    }
    offset += 8 + length;
  }

  throw new Error('This .glb carries no description.');
}

/**
 * Was sich am Kopf schon abzaehlen laesst: Dreiecke, Gelenke, Teile. Die
 * Zahlen stehen auf der Karte - sie beantworten "ist das die Figur, die ich
 * meinte?" schneller als ein Name es kann.
 */
function facts(header) {
  const accessors = header.accessors || [];
  let triangles = 0;
  let parts = 0;

  for (const mesh of header.meshes || []) {
    for (const prim of mesh.primitives || []) {
      parts++;
      const accessor = accessors[prim.indices];
      if (accessor) triangles += Math.floor(accessor.count / 3);
    }
  }

  const joints = (header.skins || []).reduce((most, skin) => Math.max(most, (skin.joints || []).length), 0);
  return { triangles, parts, joints, textures: (header.images || []).length };
}

/**
 * Die Zuordnung aus einer Workbench-Datei lesen - oder merken, dass keine
 * drinsteht. Wirft nicht: eine fremde Datei ist kein Fehler mehr, sondern der
 * zweite Weg.
 */
function awExtras(buffer) {
  let header;
  try {
    header = readHeader(buffer);
  } catch {
    return null; // keine .glb, also erst recht keine Workbench-Datei
  }

  const extras = (header.extras && header.extras.aw) ? header.extras : null;
  if (!extras || extras.rig !== 'humanoid') return null;
  if (!extras.humanoid || !Object.keys(extras.humanoid).length) return null;
  return { header, extras };
}

const put = async (entry) => {
  await run('readwrite', (store) => store.put(entry));
  return entry;
};

/**
 * Eine Datei ablegen. Gibt den Eintrag zurueck, unter dem sie danach steht.
 *
 * Gleicher Name, gleiche Figur: ein zweiter Export derselben Figur ERSETZT
 * den ersten, statt daneben zu liegen. Wer in Unity etwas an seiner Figur
 * aendert und neu exportiert, will sie ersetzt haben, nicht doppelt.
 *
 * ── Zwei Tueren ──────────────────────────────────────────────────────────
 *
 * Kommt die Datei aus der Workbench, steht die Knochenzuordnung drin, und der
 * Weg ist kurz: Kopf lesen, ablegen, fertig. Das Mesh wird dafuer gar nicht
 * erst geladen.
 *
 * Kommt sie von woanders - eine .fbx von Mixamo, eine .glb aus Blender -,
 * dann traegt sie Knochennamen, aber keine Zuordnung. Die muss geraten
 * werden, und dafuer muss die Datei wirklich aufgemacht werden.
 *
 * DER TEURE WEG WIRD ERST GEHOLT, WENN ER GEBRAUCHT WIRD. `skeleton.js` zieht
 * three.js mit herein; wer nur Workbench-Figuren ablegt, zahlt das nie.
 *
 * `checked` trennt beides danach: eine Zuordnung aus der Workbench ist
 * richtig, eine geratene ist ein Vorschlag, bis jemand sie angesehen hat.
 */
export async function addFigure(file) {
  const buffer = await file.arrayBuffer();
  const aw = awExtras(buffer);

  const base = {
    bytes: buffer.byteLength,
    added: Date.now(),
    blob: new Blob([buffer], { type: aw ? 'model/gltf-binary' : 'application/octet-stream' }),
  };

  if (aw) {
    const name = String(aw.extras.name || file.name.replace(/\.glb$/i, '')).slice(0, 64);
    return put({
      ...base,
      id: name.toLowerCase(),
      name,
      kind: 'glb',
      scale: 1,
      checked: true,
      height: Number(aw.extras.height) || 0,
      bones: aw.extras.bones || {},
      humanoid: aw.extras.humanoid,
      ...facts(aw.header),
    });
  }

  const { readForeign } = await import('./skeleton.js');
  const read = await readForeign(buffer, file.name);
  const name = file.name.replace(/\.(glb|fbx)$/i, '').slice(0, 64) || 'Figure';

  return put({
    ...base,
    id: name.toLowerCase(),
    name,
    kind: read.kind,
    scale: read.scale,
    checked: false,
    height: read.height,
    bones: {},
    humanoid: read.map,
    skeleton: read.joints,
    missing: read.missing,
    notes: read.notes,
    ...read.facts,
  });
}

/**
 * Eine von Hand nachgebesserte Zuordnung festhalten.
 *
 * Ab hier gilt die Figur als angesehen, auch wenn noch Knochen fehlen: wer
 * eine Figur ohne Zehen ablegt, soll nicht bei jedem Besuch daran erinnert
 * werden.
 */
export async function saveMapping(id, humanoid) {
  const entry = await getFigure(id);
  if (!entry) return null;
  return put({ ...entry, humanoid, checked: true });
}

/** Die Figur, wie die Buehne sie braucht. */
export async function figureForStage(id) {
  const entry = await getFigure(id);
  if (!entry) return null;

  return {
    buffer: await entry.blob.arrayBuffer(),
    humanoid: entry.humanoid,
    name: entry.name,
    //  Aeltere Eintraege kennen beide Felder nicht: die lagen alle als .glb
    //  aus der Workbench da, und die ist in Metern.
    kind: entry.kind || 'glb',
    scale: entry.scale || 1,
  };
}

// ── Welche Figur zuletzt gewaehlt war ─────────────────────────────────────

const CHOICE_KEY = 'aw.viewer.figure';

/**
 * In try/catch, weil `localStorage` nicht nur leer sein, sondern WERFEN kann -
 * im privaten Fenster und bei gesperrten Seitendaten schon beim Lesen.
 */
export function rememberedFigure() {
  try {
    return localStorage.getItem(CHOICE_KEY) || '';
  } catch {
    return '';
  }
}

export function rememberFigure(id) {
  try {
    if (id) localStorage.setItem(CHOICE_KEY, id);
    else localStorage.removeItem(CHOICE_KEY);
  } catch {
    /* dann eben nur fuer diesen Clip */
  }
}
