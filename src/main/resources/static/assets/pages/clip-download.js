/**
 * Den Clip mitnehmen - fuer alles, was nicht Unity ist.
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
 * `.awclip` bleibt daneben stehen und bleibt der kurze Weg fuer Unity: dort
 * kommt der Clip mit Kurven statt mit gebackenen Bildern an, also genauer,
 * und er legt sich auf jedes humanoide Rig.
 */

import { skeletonGlb, injectAnimation, bakeFromStage } from '../glb-export.js';
import { skeletonFbx } from '../fbx-export.js';

const box = document.getElementById('downloads');

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

function button(label, hint) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'button';
  b.textContent = label;
  b.title = hint;
  return b;
}

document.addEventListener('aw:viewer', (event) => {
  const { viewer, preview } = event.detail;
  if (!box || !preview) return;

  const title = document.querySelector('h1')?.textContent || 'clip';
  const base = safeName(title);
  const frames = preview.hips.length;

  const state = document.createElement('p');
  state.className = 'faint small';

  /*
   * "For everything else" ergibt nur Sinn, wenn darueber etwas steht. Fuer
   * wen die .awclip fehlt - also jeden, der nicht angemeldet ist - haengt
   * der Satz in der Luft und verweist auf nichts.
   *
   * Gefragt wird der Server, nicht das Dokument. Der .awclip-Knopf entsteht
   * in `clip.js`, und das ist ein eigener Einstiegspunkt mit eigenem Tempo:
   * ein Blick ins DOM traf ihn mal an und mal nicht. `data-signed-in` steht
   * schon im ausgelieferten Markup und ist damit immer da.
   */
  const head = document.createElement('p');
  head.className = 'faint small';
  head.textContent = document.body.dataset.signedIn === 'true'
    ? 'For everything else:'
    : 'Download:';

  const row = document.createElement('div');
  row.className = 'actions';

  // ── Nur die Bewegung ───────────────────────────────────────────────────
  const plain = button('Animation (.glb)', 'The skeleton and its motion - put it on your own character');
  plain.addEventListener('click', () => {
    plain.disabled = true;
    state.textContent = '';
    try {
      save(skeletonGlb(preview, { name: title }), base + '.glb');
      state.textContent = frames + ' frames, ' + preview.bones.length + ' bones, no mesh.';
    } catch (error) {
      state.textContent = 'That did not work: ' + error.message;
      console.warn('[download] skeleton glb', error);
    } finally {
      plain.disabled = false;
    }
  });
  row.append(plain);

  /*
   * UND DASSELBE ALS .fbx.
   *
   * Nicht doppelt gemoppelt: glTF kennt Knochen nur ueber eine Haut, und eine
   * Haut braucht ein Netz. Ein Skelett OHNE Figur kommt dort also als Kette
   * leerer Knoten an - brauchbar, aber in Blender kein Armature. FBX hat mit
   * `LimbNode` einen echten Begriff dafuer, und genau deshalb steht dieser
   * Knopf neben dem anderen.
   */
  const fbx = button('Animation (.fbx)', 'The same motion as a real skeleton - for Unity, Blender, Maya');
  fbx.addEventListener('click', () => {
    fbx.disabled = true;
    state.textContent = '';
    try {
      save(skeletonFbx(preview, { name: title }), base + '.fbx', 'application/octet-stream');
      state.textContent = frames + ' frames, ' + preview.bones.length
        + ' bones, in centimetres as FBX expects.';
    } catch (error) {
      state.textContent = 'That did not work: ' + error.message;
      console.warn('[download] skeleton fbx', error);
    } finally {
      fbx.disabled = false;
    }
  });
  row.append(fbx);

  /*
   * MIT FIGUR NUR, WENN EINE STEHT. Gezeigt wird genau die Buehne, die auf
   * der Seite laeuft - faellt sie auf das Strichmaennchen zurueck (kein
   * WebGL, ein generischer Clip, eine Vorschau, die sich nicht umrechnen
   * laesst), gibt es nichts herauszuschreiben, und der Knopf bleibt weg
   * statt zu enttaeuschen.
   */
  const stage = viewer && viewer.mode === 'mannequin' ? viewer.stage : null;
  if (stage) {
    const dressed = button('With character (.glb)', 'The same motion on the mannequin, mesh included');
    dressed.addEventListener('click', async () => {
      dressed.disabled = true;
      state.textContent = 'Putting it together...';
      try {
        const response = await fetch('/models/aw-mannequin.glb');
        if (!response.ok) throw new Error('the figure could not be loaded');
        const mannequin = new Uint8Array(await response.arrayBuffer());

        //  Die Buehne wird dabei durch alle Bilder gestellt. Danach steht sie
        //  auf dem letzten - also zurueck auf das, was der Betrachter sah.
        const playing = stage.playing;
        const tracks = bakeFromStage(stage, frames);
        const { bytes, missing } = injectAnimation(mannequin, tracks, {
          name: title, frameRate: preview.frameRate || 30, frames,
        });
        stage.playing = playing;

        save(bytes, base + '_character.glb');
        state.textContent = frames + ' frames on the mannequin'
          + (missing.length ? ', ' + missing.length + ' bones skipped' : '') + '.';
      } catch (error) {
        state.textContent = 'That did not work: ' + error.message;
        console.warn('[download] character glb', error);
      } finally {
        dressed.disabled = false;
      }
    });
    row.append(dressed);
  }

  box.append(head, row, state);
});
