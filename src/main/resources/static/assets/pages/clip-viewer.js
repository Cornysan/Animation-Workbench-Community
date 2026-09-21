/**
 * Die Vorschau auf der Clip-Seite.
 *
 * Sie holt sich ihre Daten selbst und haengt nicht an `clip.js`: `stage.js` ist
 * ein Modul, `clip.js` ein gewoehnliches Skript, und eins auf das andere warten
 * zu lassen kostet mehr Umstand als der Seite einen zweiten Einstiegspunkt zu
 * geben. Faellt dieser hier aus, bleibt die Seite vollstaendig - nur die
 * Vorschau sagt, dass sie nicht kam.
 */

import { mountViewer } from '../viewer-ui.js';

const box = document.getElementById('viewer');
const slug = new URLSearchParams(location.search).get('p');

function message(text) {
  const div = document.createElement('div');
  div.className = 'viewer-empty';
  div.textContent = text;
  box.replaceChildren(div);
}

(async () => {
  if (!box || !slug) return;
  message('Loading the preview...');

  let preview;
  try {
    const response = await fetch(`/api/v1/packages/${encodeURIComponent(slug)}/preview`,
      { credentials: 'same-origin' });
    if (!response.ok) throw new Error(String(response.status));
    preview = await response.json();
  } catch {
    // 404 heisst hier: der Clip bringt keine Vorschau mit. clip.js sagt
    // ohnehin, wenn der Clip selbst nicht erreichbar ist.
    message('This clip has no preview.');
    return;
  }

  try {
    //  Das Rig steht als `data-rig` im Markup: der Server hat den Clip fuer
    //  die Vorschaukarten ohnehin schon geladen, also kostet es keinen
    //  zweiten Aufruf. Fehlt es - etwa bei einem privaten Clip, den der
    //  Server ohne Anmeldung nicht sieht -, bleibt es bei `humanoid`, und
    //  scheitert die Umrechnung, faengt sie das Strichmaennchen auf.
    const viewer = await mountViewer(box, preview, { rig: box.dataset.rig });

    //  WEITERSAGEN, DASS DIE BUEHNE STEHT. Die Ausgabe als .glb "mit Figur"
    //  schreibt die Pose dieser Buehne heraus, Bild fuer Bild - sie braucht
    //  also genau diesen Griff. Ein Ereignis statt eines Aufrufs, weil die
    //  beiden nichts voneinander wissen muessen: kommt niemand, passiert
    //  nichts, und kommt die Buehne nie, wartet niemand vergebens.
    document.dispatchEvent(new CustomEvent('aw:viewer', { detail: { viewer, preview, slug } }));
  } catch (error) {
    console.warn('[clip] no viewer', error);
    message('The preview could not be loaded.');
  }
})();
