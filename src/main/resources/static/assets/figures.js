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
 * Eine Datei ablegen. Gibt den Eintrag zurueck, unter dem sie danach steht.
 *
 * Gleicher Name, gleiche Figur: ein zweiter Export derselben Figur ERSETZT
 * den ersten, statt daneben zu liegen. Wer in Unity etwas an seiner Figur
 * aendert und neu exportiert, will sie ersetzt haben, nicht doppelt.
 */
export async function addFigure(file) {
  const buffer = await file.arrayBuffer();
  const header = readHeader(buffer);
  const extras = (header.extras && header.extras.aw) ? header.extras : null;

  if (!extras) {
    throw new Error('This .glb did not come from the Animation Workbench.');
  }

  if (extras.rig !== 'humanoid' || !extras.humanoid || !Object.keys(extras.humanoid).length) {
    throw new Error('Only humanoid figures can carry a clip.');
  }

  const name = String(extras.name || file.name.replace(/\.glb$/i, '')).slice(0, 64);
  const entry = {
    id: name.toLowerCase(),
    name,
    height: Number(extras.height) || 0,
    bones: extras.bones || {},
    humanoid: extras.humanoid,
    bytes: buffer.byteLength,
    added: Date.now(),
    blob: new Blob([buffer], { type: 'model/gltf-binary' }),
  };

  await run('readwrite', (store) => store.put(entry));
  return entry;
}

/** Die Figur, wie die Buehne sie braucht. */
export async function figureForStage(id) {
  const entry = await getFigure(id);
  if (!entry) return null;

  return {
    buffer: await entry.blob.arrayBuffer(),
    humanoid: entry.humanoid,
    name: entry.name,
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
