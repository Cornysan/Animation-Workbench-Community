/**
 * Einen Clip aus einer Datei teilen - die Seite hinter "Upload an animation".
 *
 * Der Weg ist derselbe wie beim Teilen aus der Workbench, nur dass die
 * Vorschau hier im Browser entsteht (`clip-from-file.js`) statt in Unity:
 * Datei oeffnen, Knochen zuordnen, auf der Buehne ansehen, beschreiben,
 * hochladen. Hinaus geht eine `.awclip` nach Format 2 - die Vorschau mit ihrer
 * T-Pose, ohne Kurven; die Muskeln backt die Workbench beim Import.
 *
 * WAS NICHT HINAUSGEHT: die Datei. Kein Mesh, keine Textur, nicht einmal die
 * Knochennamen - die Vorschau nennt jeden Knochen so, wie Unity ihn nennt.
 */

import { buildPreview, openClipFile, writeAwclip } from '../clip-from-file.js';
import { editBoneMap } from '../bone-map.js';
import { BONES, REQUIRED, label } from '../humanoid.js';
import { mountViewer } from '../viewer-ui.js';

const { api, ensureCsrf, el, notice } = AW;

const $ = (id) => document.getElementById(id);

const drop = $('drop');
const picker = $('file');
const note = $('note');
const work = $('work');
const viewerBox = $('viewer');
const bonesBox = $('bones');
const form = $('form');
const animationField = $('animation-field');
const animationSelect = $('animation');
const title = $('title');
const description = $('description');
const counter = $('counter');
const loops = $('loops');
const announceField = $('announce-field');
const announce = $('announce');
const declared = $('declared');
const declarationLine = $('declaration');
const error = $('error');
const uploadButton = $('upload');
const done = $('done');

const PUBLIC = 'CC0-1.0';
const ANNOUNCE_KEY = 'aw.upload.announce';

/** So gross darf eine Datei sein, die der Browser oeffnet - sie geht ja nicht hinaus. */
const MAX_FILE_BYTES = 200 * 1024 * 1024;

let status = null;
let opened = null;        // openClipFile(...)
let fileName = '';
let animationIndex = 0;
let mapping = {};
let built = null;         // buildPreview(...)
let viewer = null;        // mountViewer(...)
let busy = false;

const tags = AWTags.field({
  context: () => ({ title: title.value, text: description.value }),
});
$('tags').append(tags.element);
$('tags-label').htmlFor = tags.inputId;
$('tags-label').append(' ', el('span', { class: 'faint small' }, 'up to ' + AWTags.MAX_TAGS));

if (drop) start();

// ── Anfang ────────────────────────────────────────────────────────────────

async function start() {
  try {
    status = await api('GET', '/api/v1/status');
  } catch {
    status = null;
  }

  if (status && status.uploadsEnabled === false) {
    drop.hidden = true;
    notice(note, 'Uploads are paused right now. Try again later.', 'warn');
    return;
  }

  if (status && status.declarationText) declarationLine.textContent = status.declarationText;

  picker.addEventListener('change', () => { if (picker.files[0]) load(picker.files[0]); picker.value = ''; });
  $('pick').addEventListener('click', () => picker.click());

  drop.addEventListener('dragover', (event) => { event.preventDefault(); drop.classList.add('on'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('on'));
  drop.addEventListener('drop', (event) => {
    event.preventDefault();
    drop.classList.remove('on');
    const file = event.dataTransfer && event.dataTransfer.files[0];
    if (file) load(file);
  });

  description.addEventListener('input', () => { counter.textContent = description.value.length + '/2000'; });
  let retitled = 0;
  const refreshTags = () => { clearTimeout(retitled); retitled = setTimeout(tags.refresh, 500); };
  title.addEventListener('input', refreshTags);
  description.addEventListener('input', refreshTags);

  for (const choice of form.elements.visibility) choice.addEventListener('change', syncAnnounce);
  announce.checked = remembered(ANNOUNCE_KEY);
  announce.addEventListener('change', () => remember(ANNOUNCE_KEY, announce.checked));
  syncAnnounce();

  animationSelect.addEventListener('change', () => {
    animationIndex = Number(animationSelect.value);
    title.value = tidyTitle(opened.animations.find((a) => a.index === animationIndex).name, fileName);
    rebuild({ fresh: true });
  });

  form.addEventListener('submit', (event) => { event.preventDefault(); upload(); });
}

/** Das Discord-Haekchen nur bei oeffentlich, und nur wenn es das Schaufenster gibt. */
function syncAnnounce() {
  announceField.hidden = !(status && status.discordShowcase) || visibility() !== PUBLIC;
}

function visibility() {
  const checked = [...form.elements.visibility].find((r) => r.checked);
  return checked ? checked.value : PUBLIC;
}

// ── Datei ─────────────────────────────────────────────────────────────────

async function load(file) {
  if (busy) return;
  done.hidden = true;
  error.replaceChildren();

  if (file.size > MAX_FILE_BYTES) {
    notice(note, 'That file is larger than 200 MB. Export the animation without the mesh and try again.', 'error');
    return;
  }

  notice(note, 'Reading ' + file.name + '…');
  let next;
  try {
    next = await openClipFile(await file.arrayBuffer(), file.name);
  } catch (e) {
    notice(note, e.message || 'That file could not be read.', 'error');
    return;
  }

  //  Mixamo ist das eine Rig, das sich sicher erkennen laesst - und seine
  //  Animationen weiterzugeben erlaubt seine Lizenz nicht. Dieselbe Sperre
  //  steht in der Workbench.
  if (next.mixamo) {
    work.hidden = true;
    notice(note, 'This is a Mixamo rig. Mixamo\'s license does not allow sharing its animations, '
      + 'so this one cannot go into the community.', 'error');
    return;
  }

  note.replaceChildren();
  opened = next;
  fileName = file.name;
  mapping = { ...next.map };
  animationIndex = next.animations[0].index;

  animationField.hidden = next.animations.length < 2;
  animationSelect.replaceChildren(...next.animations.map((a) =>
    el('option', { value: String(a.index) }, a.name + ' (' + a.duration.toFixed(2) + ' s)')));

  title.value = tidyTitle(next.animations[0].name, file.name);
  work.hidden = false;
  rebuild({ fresh: true });
  work.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/**
 * Vorschau neu bauen und auf die Buehne legen - nach dem Oeffnen, nach einer
 * anderen Animation und nach jeder Aenderung an der Zuordnung.
 *
 * @param fresh eine neue Animation: dann gilt auch die Schleifen-Vermutung neu.
 */
async function rebuild({ fresh = false } = {}) {
  built = null;
  uploadButton.disabled = true;
  if (viewer) { viewer.destroy(); viewer = null; }

  const missing = REQUIRED.filter((bone) => !mapping[bone]);
  if (missing.length) {
    showBones(missing);
    viewerBox.replaceChildren(el('div', { class: 'viewer-empty' }, 'Map the bones to see it move.'));
    return;
  }

  try {
    built = buildPreview(opened, animationIndex, mapping);
  } catch (e) {
    showBones([]);
    viewerBox.replaceChildren(el('div', { class: 'viewer-empty' }, e.message || 'This animation could not be read.'));
    return;
  }

  if (fresh) loops.checked = built.loops;
  showBones([]);
  uploadButton.disabled = false;

  viewerBox.replaceChildren(el('div', { class: 'viewer-empty' }, 'Loading the preview…'));
  try {
    viewer = await mountViewer(viewerBox, built.preview, { autoplay: true });
  } catch (e) {
    viewerBox.replaceChildren(el('div', { class: 'viewer-empty' }, 'The preview could not start in this browser.'));
  }
}

/** Was unter der Buehne steht: wie viel zugeordnet ist, was fehlt, und der Weg zur Maske. */
function showBones(missing) {
  const mapped = BONES.filter((bone) => mapping[bone]).length;
  const lines = [];

  if (missing.length) {
    lines.push(el('p', {}, el('strong', {}, 'Some bones are missing. '),
      'A clip cannot move without ' + missing.map((b) => label(b).toLowerCase()).join(', ') + '.'));
  } else {
    lines.push(el('p', {}, mapped + ' of ' + BONES.length + ' bones mapped. '
      + 'If an arm or a leg moves wrong, check which bone is which.'));
  }

  for (const line of opened.notes || []) lines.push(el('p', { class: 'muted small' }, line));

  if (built && built.report.dropped.length) {
    lines.push(el('p', { class: 'muted small' }, 'Left out, because they do not hang under the hips: '
      + built.report.dropped.map(label).join(', ') + '.'));
  }

  //  Eine Figur von zwei Zentimetern oder hundertachtzig Metern: dann stimmt
  //  die Masseinheit der Datei nicht. Die Bewegung ist trotzdem richtig, nur
  //  die Wege der Huefte waeren zu kurz oder zu lang.
  if (built && (built.report.height < 0.5 || built.report.height > 4)) {
    lines.push(el('p', { class: 'muted small' }, 'The skeleton measures ' + built.report.height.toFixed(2)
      + ' m from head to feet. If that is not its real size, the file\'s unit is off and the walking '
      + 'distance will be too.'));
  }

  if (!opened.hasMesh) {
    lines.push(el('p', { class: 'muted small' }, 'This file has no mesh, so its rest pose is taken from the '
      + 'skeleton as it stands. Check that the arms move as they should.'));
  }

  const check = el('button', { type: 'button', class: missing.length ? 'primary' : '' },
    missing.length ? 'Map the bones' : 'Check the bones');
  check.addEventListener('click', editBones);

  bonesBox.replaceChildren(el('div', { class: 'notice' + (missing.length ? ' error' : '') }, ...lines, check));
}

async function editBones() {
  const result = await editBoneMap({
    name: fileName,
    joints: opened.joints,
    humanoid: mapping,
    guessed: opened.map,
  });
  if (!result) return;
  mapping = result;
  rebuild();
}

// ── Hochladen ─────────────────────────────────────────────────────────────

async function upload() {
  if (busy || !built) return;
  error.replaceChildren();

  if (!title.value.trim()) {
    notice(error, 'Give it a title.', 'error');
    title.focus();
    return;
  }
  if (!declared.checked) {
    notice(error, 'Confirm that you made this animation to share it.', 'error');
    return;
  }

  busy = true;
  uploadButton.disabled = true;
  uploadButton.textContent = 'Uploading…';

  try {
    const license = visibility();
    const file = await writeAwclip({
      preview: built.preview,
      title: title.value.trim(),
      description: description.value.trim(),
      tags: tags.commit(),
      license,
      loops: loops.checked,
      frameRate: built.frameRate,
      duration: built.duration,
    });

    const body = new FormData();
    body.append('file', file, 'clip.awclip');
    body.append('declarationText', status ? status.declarationText : '');
    body.append('declarationVersion', String(status ? status.declarationVersion : ''));
    body.append('declarationAccepted', 'true');
    body.append('announce', String(license === PUBLIC && !announceField.hidden && announce.checked));

    await ensureCsrf();
    const clip = await api('POST', '/api/v1/packages', body);
    finished(clip, license);
  } catch (e) {
    notice(error, e.message || 'The upload did not go through.', 'error');
  } finally {
    busy = false;
    uploadButton.disabled = !built;
    uploadButton.textContent = 'Upload';
  }
}

function finished(clip, license) {
  if (viewer) { viewer.destroy(); viewer = null; }
  work.hidden = true;
  opened = null;
  built = null;

  const href = '/clip.html?p=' + encodeURIComponent(clip.slug);
  const again = el('button', { type: 'button' }, 'Upload another');
  again.addEventListener('click', () => {
    done.hidden = true;
    title.value = '';
    description.value = '';
    counter.textContent = '0/2000';
    declared.checked = false;
    picker.click();
  });

  done.replaceChildren(el('div', { class: 'notice ok upload-done' },
    el('p', {}, el('strong', {}, '‘' + clip.title + '’'),
      license === PUBLIC ? ' is in the community.' : ' is shared - only you can see it.'),
    el('div', { class: 'upload-done-actions' },
      el('a', { class: 'button primary', href }, 'Open the clip'),
      again)));
  done.hidden = false;
  done.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

// ── Kleinkram ─────────────────────────────────────────────────────────────

/**
 * Ein Titel aus dem Namen der Animation - wie beim Teilen in der Workbench
 * ohne Unterstriche und Ablagereste. Blender schreibt `Armature|Walk`,
 * Mixamo `mixamo.com`, manche Werkzeuge nur `Take 001`; dann lieber der
 * Dateiname.
 */
function tidyTitle(name, file) {
  const clean = (text) => String(text || '')
    .split('|').pop()
    .replace(/\.(fbx|glb)$/i, '')
    .replace(/[_]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  let text = clean(name);
  if (!text || /^(take|animation|anim|clip|action|scene|mixamo\.com|default)\s*\d*$/i.test(text)) text = clean(file);
  return text.slice(0, 80);
}

function remembered(key) {
  try { return localStorage.getItem(key) === '1'; } catch { return false; }
}

function remember(key, on) {
  try { localStorage.setItem(key, on ? '1' : '0'); } catch { /* privat - dann eben nicht */ }
}
