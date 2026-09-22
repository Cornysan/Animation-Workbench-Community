/**
 * Die Bedienung um die Buehne herum: Wiedergabe, Zeitleiste und die
 * Sichtschalter.
 *
 * Die Schalter sind dieselben wie in der Einzelclip-Ansicht der Workbench
 * (`AWWindow.SingleClip.cs`, `DrawSingleClipHUD_VisualTogglesLeft`) - Mesh
 * gegen Skelett, Raster, Kamera folgt, Kamera zuruecksetzen, und dieselben
 * Buchstaben: T, G, C, R. Wer das Werkzeug kennt, muss hier nichts neu lernen.
 *
 * Faellt die Buehne aus - kein WebGL, Modell nicht erreichbar -, uebernimmt das
 * Strichmaennchen aus `viewer.js`. Dann bleiben Wiedergabe und Zeitleiste; die
 * Sichtschalter waeren ohne Buehne leere Versprechen und erscheinen gar nicht.
 */

import { createMannequinStage } from './stage.js';
import { figureForStage, listFigures, rememberFigure, rememberedFigure } from './figures.js';

const ICONS = {
  mesh: '<path d="M12 3.6 20 8v8l-8 4.4L4 16V8z"/><path d="M12 12 20 8M12 12v8.4M12 12 4 8"/>',
  skeleton: '<circle cx="12" cy="5" r="2.4"/><path d="M12 7.4v5.2M12 12.6 8.4 20M12 12.6 15.6 20M7 9.4h10"/>',
  grid: '<path d="M3 9h18M3 15h18M9 3v18M15 3v18"/>',
  follow: '<circle cx="12" cy="12" r="3.2"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3"/>',
  reset: '<path d="M4.5 12a7.5 7.5 0 1 1 2.2 5.3"/><path d="M4 7.5V12h4.5"/>',
  play: '<path d="M8 5.5v13l10-6.5z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M9 5.5v13M15 5.5v13"/>',
  expand: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  shrink: '<path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/>',
};

/** Die Tempostufen der Transportleiste - ein Klick geht eine weiter. */
const SPEEDS = [1, 2, 0.25, 0.5];

function icon(name) {
  return `<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor"
    stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${ICONS[name]}</svg>`;
}

function hudButton(name, label, pressed) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'hud-toggle';
  button.title = label;
  button.setAttribute('aria-label', label);
  if (pressed !== undefined) button.setAttribute('aria-pressed', String(pressed));
  button.innerHTML = icon(name);
  return button;
}

const PROPORTIONS_KEY = 'aw.viewer.proportions';

/**
 * Die gewaehlte Figur haelt ueber den Clip hinaus: wer einmal auf der langen
 * Figur schaut, will beim naechsten Clip nicht wieder umschalten.
 *
 * In try/catch, weil `localStorage` nicht nur leer sein, sondern WERFEN kann
 * - im privaten Fenster und bei gesperrten Seitendaten schon beim Lesen. Ohne
 * Gedaechtnis steht hier die Vorgabe, und das ist kein Schaden.
 *
 * Nur noch gelesen, nicht mehr geschrieben: die Reihe, die das eingestellt
 * hat, ist weg. Ein alter Eintrag gilt weiter, damit niemandem seine Figur
 * unter den Haenden umspringt.
 */
function rememberedProportions() {
  try {
    return localStorage.getItem(PROPORTIONS_KEY) || undefined;
  } catch {
    return undefined;
  }
}


/**
 * Die Reihe mit den eigenen Figuren: das Mannequin des Hauses, dann alles, was
 * jemand aus der Workbench hier abgelegt hat, dann ein Plus.
 *
 * WARUM SIE AN DER BUEHNE STEHT und nicht in einem Menue: es ist dieselbe
 * Frage wie die Proportionen darunter - auf WEM laeuft der Clip. Die Antwort
 * gehoert dorthin, wo man sie sieht.
 *
 * Die Dateien kommen aus der Figuren-Ansicht der Workbench ("Use my figures on
 * the portal", dann Export) und bleiben in diesem Browser.
 */
/**
 * Die Reihe mit den eigenen Figuren: das Mannequin des Hauses, dann alles, was
 * jemand abgelegt hat, dann ein Plus, das zur Characters-Seite fuehrt.
 *
 * WARUM SIE HIER STEHT und nicht in einem Menue: es ist dieselbe Frage wie die
 * Proportionen darunter - auf WEM laeuft der Clip. Die Antwort gehoert dorthin,
 * wo man sie sieht. Hinzufuegen und Loeschen gehoeren dagegen auf eine Seite.
 */
/**
 * Ob die eigenen Figuren ueberhaupt angeboten werden.
 *
 * Der Wert kommt aus dem gemeinsamen Kopf (`fragments/shell`), also vom
 * Server und ohne eine weitere Abfrage. Fehlt das Feld - eine alte Seite im
 * Cache, ein Rahmen ohne unseren Kopf -, gilt AUS: eine Reihe, die auf eine
 * Seite fuehrt, die es nicht mehr gibt, waere die schlechtere Antwort.
 */
export function charactersEnabled() {
  const meta = document.querySelector('meta[name="aw-characters"]');
  return !!meta && meta.content === 'true';
}

async function mountFigureRow(row, activeId, remount) {
  const chip = (label, title, pressed) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'hud-toggle hud-chip';
    button.textContent = label;
    button.title = title;
    button.setAttribute('aria-label', title);
    if (pressed !== undefined) button.setAttribute('aria-pressed', String(pressed));
    return button;
  };

  const stored = await listFigures();
  row.replaceChildren();

  if (stored.length) {
    const house = chip('Mannequin', 'The figure the Workbench ships with', !activeId);
    house.addEventListener('click', () => { if (activeId) remount(''); });
    row.append(house);

    for (const entry of stored) {
      const one = chip(entry.name, 'Run this clip on ' + entry.name, entry.id === activeId);
      one.addEventListener('click', () => { if (entry.id !== activeId) remount(entry.id); });
      row.append(one);
    }
  }

  //  Hinzufuegen und Verwalten stehen auf der Characters-Seite. Hier wird nur
  //  gewaehlt: ein Schalter ist kein Ort, und wer eine Figur loswerden will,
  //  sucht sie nicht am Rand einer Buehne.
  const add = document.createElement('a');
  add.className = 'hud-toggle hud-chip';
  add.href = '/characters.html';
  add.textContent = stored.length ? '+' : '+ My figure';
  add.title = stored.length ? 'Add or manage your characters' : 'Run clips on your own character';
  add.setAttribute('aria-label', add.title);
  row.append(add);
}

/**
 * Haengt Buehne und Bedienung in `box`. Gibt einen Griff zurueck, der die
 * Buehne wieder abbauen kann; `mode` sagt, was am Ende dort steht.
 */
export async function mountViewer(box, preview, options = {}) {
  const canvas = document.createElement('canvas');
  const wrap = document.createElement('div');
  wrap.className = 'stage';
  wrap.append(canvas);

  const controls = document.createElement('div');
  controls.className = 'viewer-controls';

  const play = hudButton('pause', 'Pause (Space)');
  play.classList.add('play-toggle');
  const scrub = document.createElement('input');
  scrub.type = 'range';
  scrub.min = '0';
  scrub.max = '1000';
  scrub.value = '0';
  scrub.setAttribute('aria-label', 'Time');
  const time = document.createElement('span');
  time.className = 'muted small time';
  time.textContent = '0.00 s';

  /*
   * TEMPO UND VOLLBILD - die zwei Dinge, die man an einer Bewegung wirklich
   * braucht, wenn man sie beurteilen will: einen schnellen Schlag langsam
   * sehen, und die Figur gross. Einzelbilder gehen ueber `,` und `.`.
   */
  const speed = document.createElement('button');
  speed.type = 'button';
  speed.className = 'hud-toggle speed-toggle';
  const fullscreen = hudButton('expand', 'Full screen (F)');
  controls.append(play, scrub, time, speed);
  if (box.requestFullscreen) controls.append(fullscreen);

  box.replaceChildren(wrap, controls);

  let scrubbing = false;
  const onFrame = (t, duration) => {
    if (!scrubbing) scrub.value = String(Math.round((t / duration) * 1000));
    time.textContent = t.toFixed(2) + ' s';
  };

  let stage = null;
  let mode = 'mannequin';
  let failure = null;

  /**
   * DIE FIGUR IST EIN VERSPRECHEN, DAS NUR EIN HUMANOIDER CLIP EINLOESEN KANN.
   *
   * Ein generischer Clip haengt an SEINEM Skelett - eine Tuer, ein Schwanz,
   * ein Kranarm. Ihn auf das Mannequin zu rechnen gaebe kein schlechteres
   * Bild, sondern ein erfundenes: zwischen einem Tuerscharnier und einem
   * Oberschenkel gibt es keine Entsprechung, auf die man beide abbilden
   * koennte.
   *
   * Das Strichmaennchen zeichnet dagegen genau das Skelett, das die Vorschau
   * mitbringt. Fuer einen generischen Clip ist es darum nicht der Rueckfall,
   * sondern die richtige Ansicht - und es braucht dafuer keine einzige Figur.
   */
  const rig = options.rig || 'humanoid';

  /**
   * DIE EIGENE FIGUR, WENN EINE ABGELEGT IST.
   *
   * Sie liegt im Browser (`figures.js`), nicht bei uns. Ist sie nicht mehr da
   * oder laesst sie sich nicht laden, steht das Mannequin - eine leere Buehne
   * waere die schlechtere Antwort auf eine geloeschte Datei.
   */
  //  AUSGESCHALTET HEISST AUCH: NICHT BENUTZT. Die zuletzt gewaehlte Figur
  //  steht weiter im localStorage und ihre Datei weiter in der IndexedDB -
  //  beides bleibt unangetastet. Sie wird nur nicht mehr aufgelegt, sonst
  //  liefe der Clip weiter auf einer Figur, die nirgends mehr zu sehen oder
  //  zu wechseln ist.
  let ownId = charactersEnabled()
    ? (options.figureId !== undefined ? options.figureId : rememberedFigure())
    : '';
  let own = null;

  if (rig === 'humanoid' && ownId) {
    try {
      own = await figureForStage(ownId);
    } catch (error) {
      console.warn('[viewer] own figure unavailable', error);
    }
    if (!own) ownId = '';
  }

  if (rig === 'humanoid') {
    try {
      stage = await createMannequinStage(canvas, preview, {
        onFrame, autoplay: options.autoplay, proportions: rememberedProportions(),
        figure: own,
      });
    } catch (error) {
      failure = error;
      console.warn('[viewer] figure unavailable', error);
    }

    //  Die eigene Figur ist gescheitert - noch einmal mit dem Mannequin, bevor
    //  es das Strichmaennchen wird.
    if (!stage && own) {
      ownId = '';
      own = null;
      try {
        stage = await createMannequinStage(canvas, preview, {
          onFrame, autoplay: options.autoplay, proportions: rememberedProportions(),
        });
      } catch (error) {
        // Kein WebGL, kein Modell, kein Drama: das Strichmaennchen kann das auch.
        failure = error;
        console.warn('[viewer] mannequin unavailable, falling back to the skeleton', error);
      }
    }
  }

  if (!stage) {
    if (typeof SkeletonViewer !== 'function') throw failure || new Error('no skeleton viewer');
    stage = new SkeletonViewer(canvas, preview, { onFrame, autoplay: options.autoplay });
    mode = 'skeleton';
  }

  const setSpeed = (value) => {
    stage.speed = value;
    speed.textContent = value + '×';
    speed.title = 'Speed: ' + value + '× (click to change)';
    speed.setAttribute('aria-label', speed.title);
    speed.classList.toggle('on', value !== 1);
  };
  setSpeed(1);
  speed.addEventListener('click', () => {
    setSpeed(SPEEDS[(SPEEDS.indexOf(stage.speed) + 1) % SPEEDS.length]);
  });

  /** Ein Bild weiter oder zurueck - angehalten, am Ende herum. */
  const stepFrame = (delta) => {
    setPlaying(false);
    const fps = stage.fps || preview.frameRate || 30;
    const frames = Math.max(1, Math.round(stage.duration * fps) + 1);
    const current = Math.round(stage.time * fps);
    stage.setTime((((current + delta) % frames + frames) % frames) / fps);
    onFrame(stage.time, stage.duration);
  };

  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else if (box.requestFullscreen) box.requestFullscreen().catch(() => {});
  };
  fullscreen.addEventListener('click', toggleFullscreen);
  const onFullscreen = () => {
    const on = document.fullscreenElement === box;
    fullscreen.innerHTML = icon(on ? 'shrink' : 'expand');
    fullscreen.title = on ? 'Leave full screen (F)' : 'Full screen (F)';
    fullscreen.setAttribute('aria-label', fullscreen.title);
  };
  document.addEventListener('fullscreenchange', onFullscreen);

  const setPlaying = (on) => {
    stage.playing = on;
    play.innerHTML = icon(on ? 'pause' : 'play');
    play.title = on ? 'Pause (Space)' : 'Play (Space)';
    play.setAttribute('aria-label', play.title);
  };
  setPlaying(stage.playing);

  play.addEventListener('click', () => setPlaying(!stage.playing));
  scrub.addEventListener('input', () => {
    scrubbing = true;
    setPlaying(false);
    stage.setTime((Number(scrub.value) / 1000) * stage.duration);
  });
  scrub.addEventListener('change', () => (scrubbing = false));

  const keys = new Map([
    [' ', () => setPlaying(!stage.playing)],
    [',', () => stepFrame(-1)],
    ['.', () => stepFrame(1)],
    ['f', toggleFullscreen],
  ]);

  // Die Tasten gehoeren der Seite, aber nicht, solange jemand tippt - ein "g"
  // im Suchfeld darf das Raster nicht umschalten.
  const onKey = (event) => {
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    const target = event.target;
    if (target && (target.closest('input, textarea, select, [contenteditable]'))) return;
    //  Die Leertaste auf einem fokussierten Knopf oder Link gehoert dem Knopf.
    //  Vorher schaltete sie die Wiedergabe um und schluckte den Druck - wer
    //  per Tastatur auf das Herz oder den Download ging, drueckte ins Leere.
    if (event.key === ' ' && target && target.closest('button, a[href], summary, [role="button"]')) return;
    const action = keys.get(event.key.toLowerCase());
    if (!action) return;
    event.preventDefault();
    action();
  };

  const destroy = () => {
    document.removeEventListener('keydown', onKey);
    document.removeEventListener('fullscreenchange', onFullscreen);
    if (stage.dispose) stage.dispose();
  };

  /**
   * Eine andere Figur heisst: alles noch einmal.
   *
   * Nicht aus Bequemlichkeit - eine Buehne haengt an ihrer Leinwand, und eine
   * Leinwand gibt ihren WebGL-Kontext nicht wieder her. Der Aufbau ist
   * ohnehin ein Ladevorgang; ihn zu wiederholen ist ehrlicher als einen
   * halben Zustand weiterzureichen.
   */
  const remount = async (nextId) => {
    rememberFigure(nextId);
    const playing = stage.playing;
    destroy();
    await mountViewer(box, preview, { ...options, figureId: nextId, autoplay: playing });
  };

  if (mode === 'mannequin') {
    const hud = document.createElement('div');
    hud.className = 'stage-hud';

    const meshToggle = hudButton('mesh', 'Skeleton (T)', true);
    const gridToggle = hudButton('grid', 'Grid (G)', true);
    const followToggle = hudButton('follow', 'Camera follow (C)', true);
    const resetButton = hudButton('reset', 'Reset camera (R)');

    const setMesh = (on) => {
      stage.showMesh = on;
      meshToggle.innerHTML = icon(on ? 'mesh' : 'skeleton');
      meshToggle.title = on ? 'Skeleton (T)' : 'Mesh (T)';
      meshToggle.setAttribute('aria-label', meshToggle.title);
      meshToggle.setAttribute('aria-pressed', String(on));
    };
    const setGrid = (on) => {
      stage.showGrid = on;
      gridToggle.setAttribute('aria-pressed', String(on));
    };
    const setFollow = (on) => {
      stage.cameraFollow = on;
      followToggle.setAttribute('aria-pressed', String(on));
    };

    meshToggle.addEventListener('click', () => setMesh(!stage.showMesh));
    gridToggle.addEventListener('click', () => setGrid(!stage.showGrid));
    followToggle.addEventListener('click', () => setFollow(!stage.cameraFollow));
    resetButton.addEventListener('click', () => stage.resetCamera());

    keys.set('t', () => setMesh(!stage.showMesh));
    keys.set('g', () => setGrid(!stage.showGrid));
    keys.set('c', () => setFollow(!stage.cameraFollow));
    keys.set('r', () => stage.resetCamera());

    hud.append(meshToggle, gridToggle, followToggle, resetButton);
    wrap.append(hud);

    /**
     * HIER STAND EINE REIHE "Default / Tall / Short / Heavy".
     *
     * Sie aenderte die Proportionen des Mannequins, und sie ist raus. Der
     * Clip wird dadurch nicht anders - sie beantwortete eine Frage, die auf
     * einer Seite zum Ansehen und Mitnehmen niemand stellt, und stand dabei
     * neben den Schaltern, die wirklich etwas tun.
     *
     * Die Buehne KANN es weiterhin (`stage.setProportions`), und die
     * gemerkte Wahl wird beim Aufbau noch gelesen - wer sie frueher gesetzt
     * hat, sieht seine Figur unveraendert. Nur zu bedienen ist sie nicht
     * mehr.
     */
    const figures = document.createElement('div');
    figures.className = 'stage-figures';

    const ownRow = document.createElement('div');
    ownRow.className = 'stage-row';
    figures.append(ownRow);

    wrap.append(figures);
    //  Ist der Schalter aus, kommt die Reihe gar nicht erst - weder die
    //  abgelegten Figuren noch das Plus, das auf die Seite fuehren wuerde.
    if (charactersEnabled()) mountFigureRow(ownRow, ownId, remount);
  }

  document.addEventListener('keydown', onKey);

  return { stage, mode, destroy };
}
