/**
 * Die Startseite: ein Clip im Kopf, die neuesten darunter.
 *
 * Der Clip im Kopf ist ein echter aus dem Katalog, nicht ein mitgeliefertes
 * Beispiel - die Seite zeigt damit im selben Atemzug, was das Portal ist und
 * was gerade darin liegt. Faellt die Buehne aus, uebernimmt das
 * Strichmaennchen; findet sich gar kein Clip mit Vorschau, bleibt der Kopf
 * leer und der Rest der Seite steht trotzdem.
 */

import { mountViewer } from '../viewer-ui.js';

const { api, el, clipCard, previewObserver, notice, formatDuration } = AW;

const box = document.getElementById('hero-viewer');
const caption = document.getElementById('hero-caption');

/** Der beliebteste Clip mit Vorschau; ohne einen solchen der neueste. */
async function pickFeature() {
  for (const sort of ['popular', 'new']) {
    const page = await api('GET', `/api/v1/packages?sort=${sort}&size=12`).catch(() => null);
    const item = page && page.items.find((entry) => entry.hasPreview);
    if (item) return item;
  }
  return null;
}

async function mountHero() {
  const item = await pickFeature();
  if (!item) {
    box.replaceChildren(el('div', { class: 'viewer-empty' }, 'No clips yet.'));
    return;
  }

  const preview = await api('GET', `/api/v1/packages/${encodeURIComponent(item.slug)}/preview`);
  await mountViewer(box, preview);

  caption.href = '/clip.html?p=' + encodeURIComponent(item.slug);
  caption.hidden = false;
  caption.replaceChildren(
    el('strong', {}, item.title),
    el('span', { class: 'dot' }, '·'),
    el('span', { class: 'muted' }, 'by ' + item.author),
    el('span', { class: 'dot' }, '·'),
    el('span', { class: 'muted' }, formatDuration(item.durationSeconds)),
    el('span', { class: 'hero-caption-cta' }, 'Open clip'),
  );
}

async function mountLatest() {
  const results = document.getElementById('latest');
  const state = document.getElementById('latest-state');
  let page;
  try {
    page = await api('GET', '/api/v1/packages?sort=new&size=6');
  } catch (error) {
    notice(state, error.message, 'error');
    return;
  }
  if (page.items.length === 0) {
    state.replaceChildren(el('div', { class: 'empty' }, 'Nothing shared yet.'));
    return;
  }
  const observer = previewObserver();
  results.replaceChildren(...page.items.map((item) => clipCard(item, observer)));
}

mountHero().catch((error) => {
  console.warn('[landing] no hero clip', error);
  box.replaceChildren(el('div', { class: 'viewer-empty' }, 'The preview could not be loaded.'));
});
mountLatest();
