/**
 * Die Maske, in der jemand die geratene Zuordnung nachbessert.
 *
 * WARUM ES SIE GIBT. Eine fremde Datei traegt Knochennamen, aber keine
 * Zuordnung; `humanoid.js` raet sie aus Namen, Bau und Lage. Das geht bei den
 * gaengigen Rigs gut und bei einem eigenwilligen daneben - und ein stiller
 * Fehlgriff waere das Schlimmste, was hier passieren kann. Eine vertauschte
 * Schulter sieht man nicht in einer Liste, man sieht sie erst, wenn der Clip
 * laeuft und die Figur sich falsch bewegt.
 *
 * Also wird der Vorschlag vorgelegt, nicht angewandt: hier steht Zeile fuer
 * Zeile, welcher Knochen der Datei welche Rolle uebernimmt, und jede Zeile
 * laesst sich aendern.
 *
 * WAS HIER NICHT PASSIERT: nichts wird hochgeladen, nichts nachgeschlagen.
 * Die Namen stammen aus der Datei im Browser, und die Antwort geht in die
 * IndexedDB daneben.
 */

import { BONES, GROUPS, REQUIRED, label } from './humanoid.js';

const NONE = '';

const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};

/**
 * Eine Auswahl ueber alle Knochen der Datei.
 *
 * ALLE, auch die, die der Rater verworfen hat. Er wirft Drehknochen und
 * Aufhaengepunkte weg, weil sie ihn in die Irre fuehren - aber wer von Hand
 * zuordnet, weiss es besser als er, und dann darf die Liste nicht die Haelfte
 * verschweigen.
 */
function chooser(joints, current) {
  const select = el('select', 'bone-pick');
  select.append(new Option('— not in this figure —', NONE, false, !current));
  for (const name of joints) select.append(new Option(name, name, false, name === current));
  if (current && !joints.includes(current)) {
    //  Die abgelegte Zuordnung nennt einen Knochen, den die Datei nicht (mehr)
    //  hat. Stehen lassen und zeigen - lautlos verschlucken waere schlimmer.
    select.append(new Option(current + ' (not in the file)', current, false, true));
  }
  return select;
}

/**
 * Zeigt die Maske und antwortet mit der neuen Zuordnung - oder mit `null`,
 * wenn abgebrochen wurde.
 */
export function editBoneMap({ name, joints, humanoid, guessed }) {
  return new Promise((resolve) => {
    const picks = new Map();

    const summary = el('div', 'notice');
    const form = el('form');
    form.method = 'dialog';

    form.append(el('h2', null, 'Which bone is which'));
    form.append(el('p', 'muted small',
      'The clip names its bones the way Unity does. ' + name + ' names them its own way. '
      + 'This is the bridge between the two - guessed from the file, and yours to correct.'));
    form.append(summary);

    const rows = [];

    //  Nur die Liste scrollt. Ueberschrift, Zaehlung und Knoepfe bleiben
    //  stehen - sonst scrollt man die Zusammenfassung weg, waehrend man die
    //  Zeile sucht, auf die sie sich bezieht.
    const list = el('div', 'bone-scroll');
    form.append(list);

    for (const group of GROUPS) {
      const fold = group.title === 'Fingers' || group.title === 'Face';
      const box = el(fold ? 'details' : 'section', 'bone-group');

      if (fold) {
        const head = el('summary', null, group.title);
        head.append(el('span', 'muted small', '  ' + group.bones.length + ' bones'));
        box.append(head);
      } else {
        box.append(el('h3', null, group.title));
      }

      for (const bone of group.bones) {
        const row = el('label', 'bone-row');
        const caption = el('span', 'bone-name', label(bone));
        if (REQUIRED.includes(bone)) caption.append(el('span', 'bone-must', '*'));

        const select = chooser(joints, humanoid[bone] || '');
        select.addEventListener('change', update);
        picks.set(bone, select);

        row.append(caption, select);

        //  Was der Rater von sich aus gefunden hat, wird als solches
        //  gekennzeichnet: wer korrigiert, soll sehen, wo er dem Automaten
        //  widerspricht und wo er seine eigene Eingabe von vorhin aendert.
        if (guessed && guessed[bone]) row.classList.add('was-guessed');

        box.append(row);
        rows.push({ bone, row, select });
      }

      list.append(box);
    }

    const actions = el('div', 'dialog-actions');
    const cancel = el('button', 'ghost', 'Cancel');
    cancel.type = 'button';
    const save = el('button', 'primary', 'Save');
    save.type = 'submit';
    actions.append(cancel, save);
    form.append(actions);

    const dialog = el('dialog', 'sheet bone-sheet');
    dialog.append(form);
    document.body.append(dialog);

    /** Nach jeder Aenderung: zaehlen, Pflichtluecken nennen, Doppel markieren. */
    function update() {
      const used = new Map();
      for (const [bone, select] of picks) {
        if (!select.value) continue;
        if (!used.has(select.value)) used.set(select.value, []);
        used.get(select.value).push(bone);
      }

      for (const { bone, row, select } of rows) {
        const twice = select.value && used.get(select.value).length > 1;
        row.classList.toggle('bone-clash', !!twice);
        row.classList.toggle('bone-gap', !select.value && REQUIRED.includes(bone));
      }

      const set = [...picks.values()].filter((s) => s.value).length;
      const gaps = REQUIRED.filter((bone) => !picks.get(bone).value);
      const clashes = [...used.values()].filter((list) => list.length > 1).length;

      const lines = [set + ' of ' + BONES.length + ' bones mapped.'];
      if (gaps.length) {
        lines.push('A clip cannot run without ' + gaps.map(label).join(', ').toLowerCase() + '.');
      }
      if (clashes) {
        lines.push(clashes === 1
          ? 'One bone of the figure is used for two roles.'
          : clashes + ' bones of the figure are used for two roles each.');
      }

      summary.textContent = lines.join(' ');
      summary.className = 'notice' + (gaps.length ? ' error' : clashes ? ' warn' : ' ok');
    }

    //  Schliessen, entfernen und antworten an EINER Stelle - dieselbe Vorsicht
    //  wie beim Sammlungs-Dialog in app.js: das `close`-Ereignis ist nicht auf
    //  jeder Browserfassung verlaesslich, und ein unsichtbar stehengebliebener
    //  Dialog faengt danach jeden Klick der Seite ab.
    let done = false;
    const finish = (result) => {
      if (done) return;
      done = true;
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    cancel.addEventListener('click', (event) => { event.preventDefault(); finish(null); });
    dialog.addEventListener('cancel', (event) => { event.preventDefault(); finish(null); });

    form.addEventListener('submit', (event) => {
      event.preventDefault();
      const out = {};
      for (const bone of BONES) {
        const value = picks.get(bone).value;
        if (value) out[bone] = value;
      }
      finish(out);
    });

    update();
    dialog.showModal();
  });
}
