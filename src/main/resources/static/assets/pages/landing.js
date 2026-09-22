/**
 * Die Startseite: ein kurzer Kopf, der Umfang, die Schlagworte und zwei
 * Reihen Karten.
 *
 * IM KOPF STEHT EINE FIGUR, aber nicht die von frueher. Hier lief einmal ein
 * herausgegriffener Clip; der ist weg geblieben, weil das Gitter darunter
 * dieselbe Frage besser beantwortet - dort laufen alle, und keiner ist
 * willkuerlich gewaehlt. Was jetzt oben rechts steht, ist das Mannequin aus
 * der Doku-Site: prozedural, kein Clip, die Figur selbst als Auskunft.
 *
 * SIE KOMMT IM LEERLAUF, NICHT WAEHREND DES AUFBAUS. Der zweite Grund von
 * damals war, dass die Buehne den ersten WebGL-Kontext der Seite nahm, bevor
 * eine einzige Karte zu sehen war. Der Versuch, sie dafuer HINTER die Karten
 * zu haengen, war ein Denkfehler und ist wieder raus: `card-stage.js` wird
 * erst geholt, wenn eine Karte ins Bild scrollt, und wer nicht scrollt, holt
 * es nie - gemessen auf der Live-Seite, wo es nach sieben Sekunden noch immer
 * nicht angefordert war. Eine Reihenfolge, die es nicht gibt, laesst sich
 * nicht einhalten.
 *
 * Was stattdessen gilt und stimmt: die Figur steht oberhalb der Falz, sie IST
 * das Erste, was jemand sieht, und sie wird im Leerlauf nachgeholt, damit sie
 * sich den Aufbau nicht mit dem HTML und der Uebersichts-Abfrage teilt. Ihr
 * Kontext ist der zweite auf der Seite - und solange niemand bis zu den
 * Karten scrollt, der einzige.
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

/**
 * Die Figur im Kopf.
 *
 * Erst fragen, ob sie ueberhaupt sichtbar ist: unter 900 px blendet das
 * Stylesheet sie aus, und dann waere schon das Laden des Moduls und der 326
 * KiB Mannequin verschwendet. `clientWidth` ist dafuer die ehrliche Frage -
 * sie ist 0, wenn `display: none` gilt, egal aus welchem Grund.
 */
function mountHeroStage() {
  const frame = document.getElementById('hero-stage');
  const canvas = document.getElementById('hero-stage-canvas');
  if (!frame || !canvas || !frame.clientWidth) return;

  import('../hero-stage.js')
    .then(({ createStage }) => {
      const stage = createStage(canvas, {
        modelUrl: new URL('../models/aw-mannequin.glb', import.meta.url).href,
        onReady: () => frame.classList.add('is-ready'),
        onError: (error) => console.warn('[landing] mannequin unavailable', error),
      });

      //  Eine Buehne, die niemand ansieht, soll kein Bild anfordern.
      if (typeof IntersectionObserver === 'function') {
        new IntersectionObserver(
          ([entry]) => stage.setOnScreen(entry.isIntersecting),
          { rootMargin: '120px' },
        ).observe(frame);
      }
    })
    //  Kein WebGL, kein Modell, kein Drama: der Schein aus CSS steht schon da
    //  und bleibt stehen.
    .catch((error) => console.warn('[landing] hero stage unavailable', error));
}

/** Im Leerlauf, sonst nach einem Zug der Ereignisschleife. */
function whenIdle(run) {
  if (typeof requestIdleCallback === 'function') requestIdleCallback(run, { timeout: 2000 });
  else setTimeout(run, 200);
}

mountOverview().catch((error) => console.warn('[landing] no overview', error));

mountRow('new', 'latest', null, 'latest-state')
  .catch((error) => notice(document.getElementById('latest-state'), error.message, 'error'));

//  Haengt an nichts: die Figur ist ihr eigener Abschnitt wie jeder andere hier.
whenIdle(mountHeroStage);

//  Die zweite Reihe erscheint nur, wenn sie etwas anderes zeigt als die erste.
//  Im jungen Katalog sind "neu" und "am meisten benutzt" dieselben sechs Clips,
//  und eine Seite, die zweimal dasselbe zeigt, wirkt leerer als eine, die es
//  einmal tut.
api('GET', '/api/v1/packages?sort=popular&size=6').then((page) => {
  const used = page.items.filter((item) => item.downloads > 0);
  if (used.length < 3) return null;
  return mountRow('popular', 'popular', 'popular-section', null, page);
}).catch((error) => console.warn('[landing] no popular row', error));
