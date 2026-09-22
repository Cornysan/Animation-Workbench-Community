/**
 * Den Clip mitnehmen - EIN Knopf, dahinter die Formate.
 *
 * WARUM. Bis hierher stand auf dieser Seite genau ein Knopf: "Download
 * .awclip". Das ist unser eigenes Format, und ausserhalb der Workbench kann
 * niemand etwas damit anfangen - wer aus Blender, Godot oder einem eigenen
 * Betrachter kommt, sieht eine Seite voller Bewegungen und hat am Ende eine
 * Datei, die sein Programm nicht kennt.
 *
 * Es gibt zwei Arten, einen Clip mitzunehmen, und sie beantworten zwei
 * verschiedene Fragen:
 *
 *   NUR DIE BEWEGUNG - das Skelett mit seinen Namen und sonst nichts. Das ist
 *   die ehrliche Form dessen, was hier liegt: ein Clip IST eine Bewegung und
 *   keine Figur. Wer ein eigenes Modell hat, will genau das.
 *
 *   MIT FIGUR - dasselbe, aber auf dem Mannequin, das die Workbench mitbringt.
 *   Zum Ansehen, zum Weitergeben, und fuer jeden, der erst einmal irgendeine
 *   Figur braucht, um zu sehen, ob die Bewegung passt.
 *
 * `.awclip` bleibt der kurze Weg fuer Unity: dort kommt der Clip mit Kurven
 * statt mit gebackenen Bildern an, also genauer, und er legt sich auf jedes
 * humanoide Rig.
 *
 * WIE BEI MIXAMO: ein Knopf "Download", und erst der Klick zeigt die Wahl.
 * Vorher standen bis zu vier Textknoepfe untereinander, zwei davon mit
 * demselben Namen bis auf die Endung - die Spalte war eine Liste von
 * Dateiendungen, und man musste sie lesen, bevor man wusste, welcher Knopf
 * der eigene ist. Jetzt steht an jeder Zeile, WOFUER sie ist.
 *
 * Was nicht geht, steht trotzdem da und sagt warum - "Sign in" bei der
 * .awclip, "No preview" ohne Vorschau. Eine Zeile, die fehlt, verraet nicht,
 * dass es sie gaebe.
 */

const { api, ensureCsrf, el, icon, popover, closePopover, toast, toastError, signInUrl } = AW;

//  Die beiden Schreiber holt erst der Klick. Zusammen sind es rund 12 KB
//  gzip, die jeder Besucher der Seite lud, auch wer nie etwas herunterlaedt.
const glbExport = () => import('../glb-export.js');
const fbxExport = () => import('../fbx-export.js');

const box = document.getElementById('downloads');
const slug = new URLSearchParams(location.search).get('p');

/** Dateinamen duerfen nicht alles, und ein Clip heisst, wie jemand will. */
function safeName(title) {
  const name = (title || 'clip').normalize('NFKD')
    .replace(/[^a-zA-Z0-9-_ ]/g, '')
    .trim().replace(/\s+/g, '_').slice(0, 60);
  return name || 'clip';
}

function save(bytes, fileName, type) {
  const url = URL.createObjectURL(new Blob([bytes], { type: type || 'model/gltf-binary' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.append(a);
  a.click();
  a.remove();
  //  Erst freigeben, wenn der Browser den Griff hat. Sofort widerrufen laedt
  //  in Firefox eine leere Datei herunter.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/*
 * WAS DIE FORMATE BRAUCHEN, KOMMT ZU VERSCHIEDENEN ZEITEN. Die .awclip nur
 * eine Anmeldung - sie kommt vom Server. Die beiden Skelette brauchen die
 * Vorschau, "mit Figur" zusaetzlich die stehende Buehne: es schreibt genau
 * die Pose heraus, die sie zeigt. Beides meldet `clip-viewer.js` mit
 * `aw:viewer`; bis dahin stehen die Zeilen auf "Loading".
 */
let staged = null;
let previewMissing = false;

document.addEventListener('aw:viewer', (event) => {
  staged = event.detail;
});
document.addEventListener('aw:no-preview', () => {
  previewMissing = true;
});

const signedIn = () => document.body.dataset.signedIn === 'true';
const title = () => document.querySelector('h1')?.textContent || 'clip';

const FORMATS = [
  {
    id: 'awclip',
    name: 'Unity (.awclip)',
    about: 'For the Animation Workbench: curves, fits any humanoid rig.',
    //  Kein Anmeldeweg heisst: die Zeile bleibt, sagt es aber.
    blocked: () => (signedIn() ? null : signInUrl ? 'Sign in' : 'Unavailable'),
    run: downloadAwclip,
  },
  {
    id: 'glb',
    name: 'Animation (.glb)',
    about: 'The skeleton and its motion, for your own character.',
    blocked: previewBlocked,
    run: async () => {
      const { preview } = staged;
      const { skeletonGlb } = await glbExport();
      save(skeletonGlb(preview, { name: title() }), safeName(title()) + '.glb');
      toast(preview.hips.length + ' frames, ' + preview.bones.length + ' bones, no mesh.', { kind: 'ok' });
    },
  },
  {
    id: 'fbx',
    name: 'Animation (.fbx)',
    about: 'The same motion as a real skeleton, for Blender, Maya or Unity.',
    blocked: previewBlocked,
    run: async () => {
      const { preview } = staged;
      const { skeletonFbx } = await fbxExport();
      save(skeletonFbx(preview, { name: title() }), safeName(title()) + '.fbx', 'application/octet-stream');
      toast(preview.hips.length + ' frames, ' + preview.bones.length + ' bones, in centimetres as FBX expects.',
        { kind: 'ok' });
    },
  },
  {
    id: 'character',
    name: 'With character (.glb)',
    about: 'The motion on the mannequin, mesh included.',
    //  Faellt die Buehne auf das Strichmaennchen zurueck (kein WebGL, ein
    //  generischer Clip, eine Vorschau, die sich nicht umrechnen laesst),
    //  gibt es keine Figur herauszuschreiben.
    blocked: () => previewBlocked() || (staged.viewer && staged.viewer.mode === 'mannequin' ? null : 'No character'),
    run: downloadWithCharacter,
  },
];

function previewBlocked() {
  if (previewMissing) return 'No preview';
  if (!staged) return 'Loading';
  return null;
}

async function downloadAwclip() {
  if (!signedIn()) {
    if (signInUrl) location.href = signInUrl;
    return;
  }
  await ensureCsrf();
  const link = await api('POST', '/api/v1/packages/' + encodeURIComponent(slug) + '/unlock');
  const a = el('a', { href: link.url, download: link.fileName });
  document.body.append(a);
  a.click();
  a.remove();
  toast('Downloaded. In Unity: Tools > Animation Workbench > Community > Import .awclip File. License: '
    + link.license + '.', { kind: 'ok', duration: 9000 });
}

async function downloadWithCharacter() {
  const { viewer, preview } = staged;
  const stage = viewer.stage;
  const frames = preview.hips.length;
  const dismiss = toast('Putting it together...', { duration: 60_000 });
  try {
    const response = await fetch(new URL('../models/aw-mannequin.glb', import.meta.url));
    if (!response.ok) throw new Error('the figure could not be loaded');
    const mannequin = new Uint8Array(await response.arrayBuffer());

    //  Die Buehne wird dabei durch alle Bilder gestellt. Danach steht sie
    //  auf dem letzten - also zurueck auf das, was der Betrachter sah.
    const { bakeFromStage, injectAnimation } = await glbExport();
    const playing = stage.playing;
    const tracks = bakeFromStage(stage, frames);
    const { bytes, missing } = injectAnimation(mannequin, tracks, {
      name: title(), frameRate: preview.frameRate || 30, frames,
    });
    stage.playing = playing;
    if (stage.invalidate) stage.invalidate();

    save(bytes, safeName(title()) + '_character.glb');
    dismiss();
    toast(frames + ' frames on the mannequin'
      + (missing.length ? ', ' + missing.length + ' bones skipped' : '') + '.', { kind: 'ok' });
  } catch (error) {
    dismiss();
    throw error;
  }
}

/** Die Wahl unter dem Knopf - jede Zeile ist die Handlung selbst. */
function sheet() {
  return el('div', { class: 'format-sheet', role: 'menu', 'aria-label': 'Download formats' },
    FORMATS.map((format) => {
      const reason = format.blocked();
      //  "Sign in" ist kein Hindernis, sondern ein Weg - die Zeile bleibt
      //  anklickbar und fuehrt dorthin.
      const disabled = reason !== null && reason !== 'Sign in';
      const row = el('button', {
        type: 'button', class: 'format-row', role: 'menuitem', disabled,
      },
        el('span', { class: 'format-text' },
          el('strong', {}, format.name),
          el('span', { class: 'faint small' }, format.about)),
        reason ? el('span', { class: 'format-tag' }, reason) : icon('download'));

      row.addEventListener('click', async () => {
        closePopover();
        try {
          await format.run();
        } catch (error) {
          console.warn('[download] ' + format.id, error);
          toastError(error);
        }
      });
      return row;
    }));
}

if (box && slug) {
  const button = el('button', {
    type: 'button', class: 'primary download-button', 'aria-haspopup': 'menu',
  }, icon('download'), 'Download');
  button.addEventListener('click', () => {
    popover(button, sheet()).classList.add('format-popover');
  });
  box.replaceChildren(button);
}
