/**
 * Die Startseite: ein kurzer Kopf, der Umfang, die Schlagworte und bis zu
 * zwei Reihen Karten.
 *
 * KEINE FIGUR IM KOPF, KEINE SAMMLUNGEN (2026-09-24). Oben rechts stand
 * zuletzt das Mannequin aus der Doku-Site (`hero-stage.js`), weiter unten
 * eine Reihe oeffentlicher Sammlungen. Beides ist raus: die Karten zeigen die
 * Figur ohnehin, und zwar mit echten Clips, und Sammlungen stehen auf den
 * Profilen. Die Startseite zeigt Clips.
 *
 * JEDER ABSCHNITT STEHT FUER SICH. Faellt einer aus, fehlt er - die anderen
 * merken nichts davon. Eine Startseite, die an einer misslungenen Abfrage ganz
 * leer bleibt, waere das Gegenteil dessen, wofuer sie da ist.
 */

const { api, el, clipCard, previewObserver, notice } = AW;

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

/**
 * Eine Reihe Karten. Leer heisst: der Abschnitt bleibt weg. `page` reicht eine
 * schon geholte Seite durch - die Popular-Reihe fragt vorher, ob sie sich
 * ueberhaupt lohnt, und muss dann nicht zweimal dasselbe holen.
 */
async function mountRow(sort, containerId, sectionId, stateId, page = null) {
  page = page || await api('GET', `/api/v1/packages?sort=${sort}&size=6`);
  const container = document.getElementById(containerId);

  if (page.items.length === 0) {
    container.replaceChildren();
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

mountOverview().catch((error) => console.warn('[landing] no overview', error));

AW.placeholderCards(document.getElementById('latest'), 6);
mountRow('new', 'latest', null, 'latest-state')
  .catch((error) => {
    document.getElementById('latest').replaceChildren();
    notice(document.getElementById('latest-state'), error.message, 'error');
  });

//  Die zweite Reihe erscheint nur, wenn sie etwas anderes zeigt als die erste.
//  Im jungen Katalog sind "neu" und "am meisten benutzt" dieselben sechs Clips,
//  und eine Seite, die zweimal dasselbe zeigt, wirkt leerer als eine, die es
//  einmal tut.
api('GET', '/api/v1/packages?sort=popular&size=6').then((page) => {
  const used = page.items.filter((item) => item.downloads > 0);
  if (used.length < 3) return null;
  return mountRow('popular', 'popular', 'popular-section', null, page);
}).catch((error) => console.warn('[landing] no popular row', error));
