/**
 * Die Startseite: ein Clip im Kopf, darunter der Umfang, die Schlagworte und
 * zwei Reihen.
 *
 * Der Clip im Kopf ist ein echter aus dem Katalog, nicht ein mitgeliefertes
 * Beispiel - die Seite zeigt damit im selben Atemzug, was das Portal ist und
 * was gerade darin liegt. Faellt die Buehne aus, uebernimmt das
 * Strichmaennchen; findet sich gar kein Clip mit Vorschau, bleibt der Kopf
 * leer und der Rest der Seite steht trotzdem.
 *
 * JEDER ABSCHNITT STEHT FUER SICH. Faellt einer aus, fehlt er - die anderen
 * merken nichts davon. Eine Startseite, die an einer misslungenen Abfrage ganz
 * leer bleibt, waere das Gegenteil dessen, wofuer sie da ist.
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

/**
 * Umfang und Schlagworte - beides aus einer Antwort.
 *
 * Die Zahlen sind kein Schmuck: ein Portal, das nicht sagt, wie viel darin
 * liegt, laesst jeden Besucher raten, ob sich das Stoebern lohnt. Was null ist,
 * steht nicht da - "0 creators" waere schlechter als Schweigen.
 */
async function mountOverview() {
  const overview = await api('GET', '/api/v1/overview');

  const stats = document.getElementById('hero-stats');
  const rows = [
    [overview.clips, overview.clips === 1 ? 'clip' : 'clips'],
    [overview.creators, overview.creators === 1 ? 'creator' : 'creators'],
    [overview.installs, overview.installs === 1 ? 'import' : 'imports'],
  ].filter(([value]) => value > 0);

  if (rows.length) {
    stats.hidden = false;
    stats.replaceChildren(...rows.map(([value, label]) => el('div', { class: 'stat' },
      el('dt', {}, value.toLocaleString()),
      el('dd', {}, label))));
  }

  if (overview.tags.length) {
    const bar = document.getElementById('tag-bar');
    bar.hidden = false;
    bar.replaceChildren(
      ...overview.tags.slice(0, 14).map((entry) => el('a', {
        class: 'tag',
        href: '/browse.html?tag=' + encodeURIComponent(entry.tag),
      }, entry.tag, el('span', { class: 'tag-count' }, entry.count))),
    );
  }
}

/** Eine Reihe Karten. Leer heisst: der Abschnitt bleibt weg. */
async function mountRow(sort, containerId, sectionId, stateId) {
  const page = await api('GET', `/api/v1/packages?sort=${sort}&size=6`);
  const container = document.getElementById(containerId);

  if (page.items.length === 0) {
    if (stateId) {
      document.getElementById(stateId).replaceChildren(
        el('div', { class: 'empty' },
          el('p', {}, 'Nothing shared yet. Yours could be the first.'),
          el('a', { class: 'button', href: '/rules.html' }, 'How sharing works')));
    }
    return;
  }

  const observer = previewObserver();
  container.replaceChildren(...page.items.map((item) => clipCard(item, observer)));
  if (sectionId) document.getElementById(sectionId).hidden = false;
}

mountHero().catch((error) => {
  console.warn('[landing] no hero clip', error);
  box.replaceChildren(el('div', { class: 'viewer-empty' }, 'The preview could not be loaded.'));
});

mountOverview().catch((error) => console.warn('[landing] no overview', error));

mountRow('new', 'latest', null, 'latest-state')
  .catch((error) => notice(document.getElementById('latest-state'), error.message, 'error'));

//  Die zweite Reihe erscheint nur, wenn sie etwas anderes zeigt als die erste.
//  Im jungen Katalog sind "neu" und "am meisten benutzt" dieselben sechs Clips,
//  und eine Seite, die zweimal dasselbe zeigt, wirkt leerer als eine, die es
//  einmal tut.
api('GET', '/api/v1/packages?sort=popular&size=6').then((page) => {
  const used = page.items.filter((item) => item.downloads > 0);
  if (used.length < 3) return null;
  return mountRow('popular', 'popular', 'popular-section', null);
}).catch((error) => console.warn('[landing] no popular row', error));
