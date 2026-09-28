/**
 * Das Vorschaubild, das Discord, Slack und Google zu einem Link zeigen -
 * 1200 x 630, gerendert mit derselben Buehne wie die Clip-Seite.
 *
 * WARUM IM BROWSER. Der Server hat weder WebGL noch eine Schrift; was er
 * selbst zeichnen konnte, war eine flache Strichfigur mit Trapez als Rumpf
 * (bis 2026-09-27, `ClipCard.kt`), und die sah in Discord aus wie ein
 * Platzhalter. Hier steht das Mannequin im selben Licht, auf demselben Boden
 * wie auf der Seite, auf die der Link fuehrt.
 *
 * Wer es rendert: der Besitzer, wenn er seinen Clip ansieht und das Bild noch
 * fehlt (clip.js), und ein Admin fuer alle auf einmal (admin.js). Hochladen
 * duerfen nur die beiden - siehe `web/PreviewCards.kt`.
 *
 * WAS AUF DEM BILD STEHT. Eine Pose, und zwar die ausladendste des Clips
 * ([bestFrame]): mitten im Schritt, oben im Sprung, nicht der ruhige Anfang.
 * Legt die Huefte einen Weg zurueck, stehen zwei blasse Zwischenposen davor -
 * dann sieht man im Standbild, dass und wohin sich die Figur bewegt. Auf der
 * Stelle waeren sie nur ein Durcheinander um dieselbe Mitte.
 */

import { MannequinStage, loadModel } from './stage.js';
import { ACESFilmicToneMapping, Vector3, WebGLRenderer } from './vendor/three.module.js';

export const WIDTH = 1200;
export const HEIGHT = 630;

/** Doppelt so gross rendern und verkleinern - die Kanten danken es. */
const SUPERSAMPLE = 2;

/** Ab diesem Weg (in Figurenhoehen, Radius um die Mitte) gibt es Zwischenposen. */
const TRAVEL_FOR_GHOSTS = 0.35;

let shared = null;

/** Ein Renderer fuer alle Bilder - die Admin-Seite rendert Dutzende hintereinander. */
function sharedRenderer() {
  if (!shared) {
    const canvas = document.createElement('canvas');
    const renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
    renderer.setPixelRatio(1);
    renderer.setSize(WIDTH * SUPERSAMPLE, HEIGHT * SUPERSAMPLE, false);
    renderer.setClearAlpha(0);
    renderer.toneMapping = ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.12;
    renderer.shadowMap.enabled = true;
    shared = renderer;
  }
  return shared;
}

/**
 * Die ausladendste Pose zwischen zwei Anteilen des Clips: Haende, Fuesse und
 * Kopf am weitesten von der Huefte. Das ist fast immer das Bild, an dem man
 * die Bewegung erkennt.
 */
function bestFrame(stage, from, to) {
  const { positions, frames } = stage.solved;
  const names = stage.preview.bones;
  const ends = ['LeftHand', 'RightHand', 'LeftFoot', 'RightFoot', 'Head']
    .map((name) => names.indexOf(name)).filter((i) => i >= 0);

  const first = Math.max(0, Math.floor((frames - 1) * from));
  const last = Math.min(frames - 1, Math.ceil((frames - 1) * to));
  let best = Math.round((first + last) / 2);
  let most = -1;
  for (let f = first; f <= last; f++) {
    const pos = positions[f];
    let reach = 0;
    for (const i of ends) reach += pos[i].distanceTo(pos[0]);
    if (reach > most) { most = reach; best = f; }
  }
  return best;
}

function blank() {
  const canvas = document.createElement('canvas');
  canvas.width = WIDTH;
  canvas.height = HEIGHT;
  const ctx = canvas.getContext('2d');

  //  Der Himmel hinter der Figur - `--stage-bg` aus app.css, dunkles Thema:
  //  radial-gradient(120% 90% at 50% 15%, #1e1e23 0%, #0f0f12 70%).
  ctx.fillStyle = '#0f0f12';
  ctx.fillRect(0, 0, WIDTH, HEIGHT);
  const rx = WIDTH * 1.2;
  const ry = HEIGHT * 0.9;
  ctx.save();
  ctx.translate(WIDTH / 2, HEIGHT * 0.15);
  ctx.scale(rx / ry, 1);
  const sky = ctx.createRadialGradient(0, 0, 0, 0, 0, ry);
  sky.addColorStop(0, '#1e1e23');
  sky.addColorStop(0.7, '#0f0f12');
  sky.addColorStop(1, '#0f0f12');
  ctx.fillStyle = sky;
  ctx.fillRect(-WIDTH, -HEIGHT, WIDTH * 2, HEIGHT * 2);
  ctx.restore();
  return { canvas, ctx };
}

/**
 * Boden, Zwischenposen und Figur auf die Leinwand - in drei Durchgaengen,
 * damit die Zwischenposen blass werden koennen, ohne den Boden mitzunehmen.
 *
 * `shiftX` schiebt die Figur um so viele Bildpunkte nach rechts (die Seite
 * braucht links Platz fuer Text), `zoom` holt sie naeher heran. `figure` und
 * `look` sind die des Clips (figure-looks.js) - das Bild zeigt ihn so, wie
 * ihn alle auf der Seite sehen.
 */
async function paintStage(ctx, preview, { shiftX = 0, zoom = 1, figure, look } = {}) {
  const gltf = await loadModel(figure);
  const renderer = sharedRenderer();
  //  Die Buehne leiht sich den Renderer, wie eine Karte im Katalog - dann
  //  oeffnet sie keinen eigenen Kontext und startet keine Schleife.
  const holder = document.createElement('canvas');
  const stage = new MannequinStage(holder, preview, gltf, {
    surface: { renderer, fit() {} }, interactive: false, autoplay: false, theme: 'dark',
    house: figure, look,
  });

  try {
    //  Die Leinwand haengt in keinem Dokument und misst 0 x 0 - die Masse
    //  kommen von hier, nicht vom Beobachter.
    if (stage.observer) stage.observer.disconnect();
    stage.camera.aspect = WIDTH / HEIGHT;
    stage.camera.updateProjectionMatrix();
    stage.zoom = zoom;

    //  Auf geliehener Flaeche schaltet die Buehne Schatten ab (auf einer Karte
    //  von 206 Pixeln sind sie ein Fleck). Hier stellen sie die Figur hin.
    stage.key.castShadow = true;
    stage.model.traverse((node) => { if (node.isMesh || node.isSkinnedMesh) node.castShadow = true; });

    const travels = stage.travelRadius > stage.height * TRAVEL_FOR_GHOSTS;
    const main = travels ? bestFrame(stage, 0.7, 0.97) : bestFrame(stage, 0.08, 0.92);
    const ghosts = travels
      ? [bestFrame(stage, 0.02, 0.25), bestFrame(stage, 0.35, 0.6)].filter((f) => Math.abs(f - main) > 2)
      : [];

    const pose = (frame) => stage.placeCamera(stage.applyFrame(frame));
    pose(main);

    if (shiftX) {
      //  Wie viele Meter ein Bildpunkt in der Tiefe des Ziels misst - dieselbe
      //  Rechnung wie `panBy`, nur mit den Massen dieses Bildes.
      const perPixel = (2 * stage.distance * Math.tan((stage.camera.fov * Math.PI) / 360)) / HEIGHT;
      stage.camera.updateMatrixWorld();
      const right = new Vector3().setFromMatrixColumn(stage.camera.matrixWorld, 0);
      stage.pan.addScaledVector(right, -shiftX * perPixel);
      pose(main);
    }

    const show = ({ figure, ground, shadow }) => {
      stage.figure.visible = figure;
      stage.backdrop.visible = ground;
      stage.pool.visible = ground;
      if (stage.grid) stage.grid.visible = ground;
      stage.contact.visible = shadow;
    };
    const layer = (alpha) => {
      renderer.render(stage.scene, stage.camera);
      ctx.globalAlpha = alpha;
      ctx.drawImage(renderer.domElement, 0, 0, WIDTH, HEIGHT);
      ctx.globalAlpha = 1;
    };

    //  1. Boden, Raster und der Schein unter der Figur.
    show({ figure: false, ground: true, shadow: false });
    layer(1);

    //  2. Die Zwischenposen, blass und die fruehere blasser.
    show({ figure: true, ground: false, shadow: false });
    ghosts.forEach((frame, index) => {
      pose(frame);
      layer(index === 0 && ghosts.length > 1 ? 0.26 : 0.42);
    });

    //  3. Die Figur selbst, mit ihrem Schatten.
    pose(main);
    show({ figure: true, ground: false, shadow: true });
    layer(1);
  } finally {
    stage.dispose();
  }
}

let lockup = null;

/** Das Logo als Bild - dieselbe Datei wie im Kopf der Seite, in Weiss. */
async function logo() {
  if (!lockup) {
    const image = new Image();
    image.src = new URL('./brand/playmations-lockup-weiss.svg', import.meta.url).href;
    await image.decode();
    lockup = image;
  }
  return lockup;
}

/** Hoehe zu Breite des Logos (viewBox 1008.81 x 164). */
const LOGO_RATIO = 1008.81 / 164;

function toPng(canvas) {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('the image could not be written'))), 'image/png');
  });
}

/**
 * Das Bild eines Clips: die Figur in der Mitte, das Logo klein unten links.
 * Kein Text - Titel und Beschreibung setzt Discord ohnehin daneben.
 */
export async function renderClipCard(preview, dress = {}) {
  const { canvas, ctx } = blank();
  await paintStage(ctx, preview, dress);

  const mark = await logo();
  const h = 30;
  ctx.globalAlpha = 0.9;
  ctx.drawImage(mark, 44, HEIGHT - 44 - h, h * LOGO_RATIO, h);
  ctx.globalAlpha = 1;
  return toPng(canvas);
}

/**
 * Das Bild der Seite (Startseite, Share, Clips ohne eigenes Bild): links, was
 * das hier ist, rechts eine Figur in Bewegung.
 */
export async function renderSiteCard(preview) {
  const { canvas, ctx } = blank();
  await paintStage(ctx, preview, { shiftX: 250, zoom: 1.08 });

  //  Links wird es dunkler, damit die Schrift nicht auf dem Raster steht.
  const shade = ctx.createLinearGradient(0, 0, 700, 0);
  shade.addColorStop(0, 'rgba(15, 15, 18, 0.92)');
  shade.addColorStop(0.62, 'rgba(15, 15, 18, 0.6)');
  shade.addColorStop(1, 'rgba(15, 15, 18, 0)');
  ctx.fillStyle = shade;
  ctx.fillRect(0, 0, 700, HEIGHT);

  const font = "'Inter Variable', Inter, system-ui, sans-serif";
  await Promise.all([
    document.fonts.load(`700 66px ${font}`),
    document.fonts.load(`450 27px ${font}`),
  ]).catch(() => {});

  const x = 76;
  const mark = await logo();
  ctx.drawImage(mark, x, 118, 44 * LOGO_RATIO, 44);

  ctx.fillStyle = '#f4f3f8';
  ctx.font = `700 66px ${font}`;
  ctx.textBaseline = 'alphabetic';
  ctx.fillText('Free humanoid', x, 274);
  ctx.fillText('animations', x, 350);

  ctx.fillStyle = '#a9a4b6';
  ctx.font = `450 27px ${font}`;
  ctx.fillText('Preview them in the browser, download', x, 416);
  ctx.fillText('FBX or GLB, or take them into Unity.', x, 454);

  ctx.fillStyle = '#8e77ff';
  ctx.font = `600 24px ${font}`;
  ctx.fillText('playmations.com', x, 540);

  return toPng(canvas);
}

function xsrf() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

/** Hochladen - `path` ist `/api/v1/packages/<slug>/card` oder `/api/v1/admin/site-card`. */
export async function uploadCard(path, blob) {
  if (!xsrf()) await fetch('/api/v1/status', { credentials: 'same-origin' });
  const response = await fetch(path, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'image/png', 'X-XSRF-TOKEN': xsrf() },
    body: blob,
  });
  if (!response.ok) {
    let message = 'upload failed (' + response.status + ')';
    try { message = (await response.json()).error.message || message; } catch { /* kein JSON */ }
    throw new Error(message);
  }
}

/** Die Vorschau eines Clips holen - dieselbe, die die Clip-Seite zeigt. */
export async function fetchPreview(slug) {
  const response = await fetch('/api/v1/packages/' + encodeURIComponent(slug) + '/preview', { credentials: 'same-origin' });
  if (!response.ok) throw new Error('no preview (' + response.status + ')');
  return response.json();
}

/** Figur und Look eines Clips, wie der Ersteller sie gewaehlt hat. */
async function fetchDress(slug) {
  const response = await fetch('/api/v1/packages/' + encodeURIComponent(slug), { credentials: 'same-origin' });
  if (!response.ok) return {};
  const clip = await response.json();
  return { figure: clip.figure, look: clip.look };
}

/**
 * Rendern und hochladen, in einem - fuer die Seite des Besitzers und die
 * Admin-Liste. Ohne `dress` holt es sich Figur und Look des Clips selbst.
 */
export async function refreshClipCard(slug, preview, dress) {
  const blob = await renderClipCard(preview || await fetchPreview(slug), dress || await fetchDress(slug));
  await uploadCard('/api/v1/packages/' + encodeURIComponent(slug) + '/card', blob);
  return blob;
}
