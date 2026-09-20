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

const ICONS = {
  mesh: '<path d="M12 3.6 20 8v8l-8 4.4L4 16V8z"/><path d="M12 12 20 8M12 12v8.4M12 12 4 8"/>',
  skeleton: '<circle cx="12" cy="5" r="2.4"/><path d="M12 7.4v5.2M12 12.6 8.4 20M12 12.6 15.6 20M7 9.4h10"/>',
  grid: '<path d="M3 9h18M3 15h18M9 3v18M15 3v18"/>',
  follow: '<circle cx="12" cy="12" r="3.2"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3"/>',
  reset: '<path d="M4.5 12a7.5 7.5 0 1 1 2.2 5.3"/><path d="M4 7.5V12h4.5"/>',
  play: '<path d="M8 5.5v13l10-6.5z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M9 5.5v13M15 5.5v13"/>',
};

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
  controls.append(play, scrub, time);

  box.replaceChildren(wrap, controls);

  let scrubbing = false;
  const onFrame = (t, duration) => {
    if (!scrubbing) scrub.value = String(Math.round((t / duration) * 1000));
    time.textContent = t.toFixed(2) + ' s';
  };

  let stage = null;
  let mode = 'mannequin';

  try {
    stage = await createMannequinStage(canvas, preview, { onFrame, autoplay: options.autoplay });
  } catch (error) {
    // Kein WebGL, kein Modell, kein Drama: das Strichmaennchen kann das auch.
    if (typeof SkeletonViewer !== 'function') throw error;
    console.warn('[viewer] mannequin unavailable, falling back to the skeleton', error);
    stage = new SkeletonViewer(canvas, preview, { onFrame, autoplay: options.autoplay });
    mode = 'skeleton';
  }

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

  const keys = new Map([[' ', () => setPlaying(!stage.playing)]]);

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
  }

  // Die Tasten gehoeren der Seite, aber nicht, solange jemand tippt - ein "g"
  // im Suchfeld darf das Raster nicht umschalten.
  const onKey = (event) => {
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    const target = event.target;
    if (target && (target.closest('input, textarea, select, [contenteditable]'))) return;
    const action = keys.get(event.key.toLowerCase());
    if (!action) return;
    event.preventDefault();
    action();
  };
  document.addEventListener('keydown', onKey);

  return {
    stage,
    mode,
    destroy() {
      document.removeEventListener('keydown', onKey);
      if (stage.dispose) stage.dispose();
    },
  };
}
