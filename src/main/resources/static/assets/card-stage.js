/**
 * Die Figur auf den Karten des Katalogs.
 *
 * WARUM ES DAS BRAUCHT. Bis hierher zeichnete der Katalog Strichmaennchen, und
 * der Grund stand als Satz in `app.js`: "eine Seite zeigt bis zu 24 Karten,
 * und so viele WebGL-Kontexte gibt kein Browser her". Der Satz stimmt - ein
 * Browser gibt etwa 16 her und nimmt dem aeltesten seinen weg, sobald der 17.
 * kommt. Er beantwortet nur die falsche Frage: eine Seite braucht keine 24
 * Kontexte, sie braucht 24 BILDER.
 *
 * Also EIN Kontext, der reihum fuer jede Karte zeichnet, und je Karte eine
 * gewoehnliche 2D-Leinwand, die sich das fertige Bild herauskopiert. Der Preis
 * ist diese Kopie; der Gegenwert ist, dass auf einer Karte dieselbe Figur
 * steht wie auf der Clip-Seite - dieselbe Umrechnung, dasselbe Licht,
 * derselbe Blickwinkel.
 *
 * WAS EINE KARTE ANDERS MACHT ALS DIE GROSSE BUEHNE:
 *
 *   - Kein Schatten. Er kostet je Bild einen zweiten Durchgang durch die
 *     Szene, und auf 206 Pixeln Hoehe ist er ein Fleck.
 *   - Weniger Posen. Eine Vorschau darf 3600 Bilder mitbringen (zwei Minuten),
 *     und die rechnet die Buehne beim Aufbau ALLE aus. Einmal ist das nichts,
 *     24-mal ist es der Arbeitsspeicher. Auf einer Karte genuegen 15 Posen je
 *     Sekunde - siehe [thinPreview].
 *   - Nur was im Bild ist, laeuft. Wer weiterblaettert, laesst nichts zurueck,
 *     das weiterrechnet.
 *
 * Das Strichmaennchen bleibt der Rueckfall: ohne WebGL, ohne Modell, und fuer
 * eine Vorschau, die sich nicht auf die Figur umrechnen laesst.
 */

import { MannequinStage, loadModel } from './stage.js';
import { ACESFilmicToneMapping, WebGLRenderer } from './vendor/three.module.js';

/** Bilder je Sekunde, die eine Karte zeichnet. */
const SWEEP_FPS = 30;

/** Posen je Sekunde, die eine Karte aus der Vorschau ausrechnet. */
const PREVIEW_FPS = 15;

/** Und hoechstens so viele, egal wie lang der Clip ist. */
const MAX_PREVIEW_FRAMES = 300;

/** Kantenlaenge, die die gemeinsame Flaeche nicht ueberschreitet. Mehr Karten
 *  als darauf passen werden in mehreren Zuegen gezeichnet. */
const MAX_SURFACE = 2048;

/**
 * Dieselbe Bewegung mit weniger Posen.
 *
 * Die Buehne loest beim Aufbau jede Pose der Vorschau auf und behaelt sie -
 * je Bild und Knochen eine Drehung und eine Lage. Fuer eine einzelne Buehne
 * ist das die richtige Rechnung; auf 24 Karten ist es dieselbe Rechnung
 * 24-mal.
 *
 * Ausgeduennt wird die VORSCHAU, nicht die Buehne: jede n-te Pose, und die
 * Bildrate faellt im selben Mass. Die Bewegung dauert damit genauso lang und
 * sieht an derselben Stelle gleich aus - sie ist nur grober abgetastet, und
 * das sieht auf einer Karte niemand.
 */
function thinPreview(preview) {
  const frames = preview.hips.length;
  let stride = Math.max(1, Math.round(preview.frameRate / PREVIEW_FPS));
  if (frames / stride > MAX_PREVIEW_FRAMES) stride = Math.ceil(frames / MAX_PREVIEW_FRAMES);
  if (stride <= 1) return preview;

  const hips = [];
  const rotations = [];
  for (let f = 0; f < frames; f += stride) {
    hips.push(preview.hips[f]);
    rotations.push(preview.rotations[f]);
  }

  return { ...preview, frameRate: preview.frameRate / stride, hips, rotations };
}

/**
 * Der eine Renderer, den sich alle Karten teilen, und die Leinwand, auf der er
 * arbeitet. Sie haengt in keinem Dokument - sie ist die Werkbank, von der jede
 * Karte ihr Bild abholt.
 *
 * SIE IST IN KACHELN AUFGETEILT, eine je Karte, und das ist kein Schmuck. Wer
 * je Karte zeichnet und sofort kopiert, zwingt die Grafikkarte zwischen jedem
 * Paar zum Gleichstand: gemessen 2,3 ms je Karte, gegen 0,3 ms fuers Zeichnen
 * und 0,6 ms fuers Kopieren einzeln. Erst ALLE zeichnen, dann ALLE kopieren,
 * und der Gleichstand faellt einmal an statt 24-mal.
 */
class CardSurface {
  constructor() {
    this.canvas = document.createElement('canvas');
    this.renderer = new WebGLRenderer({
      canvas: this.canvas,
      antialias: true,
      alpha: true,
      //  Das Bild wird herauskopiert, nicht angezeigt. Ohne diese Zusage darf
      //  der Browser den Puffer verwerfen, sobald er die Kontrolle
      //  zurueckbekommt - und dazwischen liegen die Kopien.
      preserveDrawingBuffer: true,
    });
    //  Gerechnet wird in GERAETEPUNKTEN (jede Karte misst sich selbst), also
    //  darf der Renderer nicht noch einmal mit dem Bildschirm multiplizieren.
    this.renderer.setPixelRatio(1);
    this.renderer.setClearAlpha(0);
    this.renderer.toneMapping = ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.12;
    this.renderer.shadowMap.enabled = false;
    //  Jede Karte bekommt ihre Kachel; ohne Schere wuerde das Leeren des
    //  Bildes die Kacheln der anderen mitnehmen.
    this.renderer.setScissorTest(true);

    this.cards = new Set();
    this.frame = null;
    this.lastSweep = 0;
    this.lost = false;

    this.tileWidth = 0;
    this.tileHeight = 0;
    this.columns = 0;
    this.rows = 0;

    this.tick = this.tick.bind(this);

    //  Geht der Kontext verloren, friert jede Karte auf ihrem letzten Bild
    //  ein. Das ist besser als eine Schleife, die ins Leere zeichnet.
    this.canvas.addEventListener('webglcontextlost', (event) => {
      event.preventDefault();
      this.lost = true;
      console.warn('[cards] the shared WebGL context was lost');
    });
  }

  /** Wie viele Karten in einem Zug Platz haben. */
  get capacity() {
    return Math.max(1, this.columns * this.rows);
  }

  /** Eine Kachel muss die groesste Karte fassen. Ruft die Buehne, wenn sie
   *  sich misst. */
  fit(width, height) {
    if (width <= this.tileWidth && height <= this.tileHeight) return;
    this.reshape(Math.max(width, this.tileWidth), Math.max(height, this.tileHeight), this.capacity);
    this.wake();
  }

  /** Platz fuer so viele Karten in einem Zug. */
  reserve(count) {
    if (count <= this.columns * this.rows) return;
    this.reshape(this.tileWidth, this.tileHeight, count);
  }

  /**
   * Das Kachelraster neu legen. Die Flaeche waechst dabei, sie schrumpft nie:
   * jede Aenderung leert sie und kostet eine neue Zuteilung, und der Katalog
   * scrollt - die Zahl der sichtbaren Karten schwankt staendig.
   */
  reshape(tileWidth, tileHeight, count) {
    if (tileWidth < 1 || tileHeight < 1) return;

    let columns = Math.max(this.columns, Math.ceil(Math.sqrt(count)));
    let rows = Math.max(this.rows, Math.ceil(count / columns));
    while (columns > 1 && columns * tileWidth > MAX_SURFACE) columns--;
    while (rows > 1 && rows * tileHeight > MAX_SURFACE) rows--;

    if (tileWidth === this.tileWidth && tileHeight === this.tileHeight
      && columns === this.columns && rows === this.rows) return;

    this.tileWidth = tileWidth;
    this.tileHeight = tileHeight;
    this.columns = columns;
    this.rows = rows;
    this.renderer.setSize(columns * tileWidth, rows * tileHeight, false);

    //  Eine neue Groesse leert die Flaeche - stillgestellte Karten brauchen
    //  ihr Bild noch einmal.
    for (const card of this.cards) card.drawn = false;
  }

  /**
   * Der Platz einer Karte: links und oben im BILD, und wo WebGL denselben
   * Ausschnitt sieht - dort zaehlt die Hoehe von unten.
   */
  tile(index, width, height) {
    const left = (index % this.columns) * this.tileWidth;
    const top = Math.floor(index / this.columns) * this.tileHeight;
    return { left, top, bottom: this.canvas.height - top - height, width, height };
  }

  add(card) {
    this.cards.add(card);
    this.wake();
  }

  remove(card) {
    this.cards.delete(card);
  }

  wake() {
    if (this.frame === null && !this.lost) this.frame = requestAnimationFrame(this.tick);
  }

  /**
   * Ein Durchgang durch alle Karten.
   *
   * Die Schleife laeuft nur, solange es etwas zu zeichnen gibt: eine Karte
   * ausserhalb des Bildes zaehlt nicht, und eine stillgestellte Karte zaehlt
   * nur bis zu ihrem ersten Bild. Eine Seite, auf der nichts im Bild ist,
   * fordert kein Bild mehr an.
   */
  tick(timestamp) {
    this.frame = null;
    if (this.lost) return;

    const work = [];
    for (const card of this.cards) {
      if (!card.visible) continue;
      if (!card.stage.playing && card.drawn) continue;
      work.push(card);
    }
    if (work.length === 0) return;

    //  30 Bilder je Sekunde reichen fuer eine Karte, und die andere Haelfte
    //  der Arbeit bleibt der Seite.
    if (timestamp - this.lastSweep >= 1000 / SWEEP_FPS - 1) {
      this.lastSweep = timestamp;
      this.sweep(work, timestamp);
    }

    this.frame = requestAnimationFrame(this.tick);
  }

  /**
   * Erst alle zeichnen, dann alle kopieren - gruppenweise, so viele wie
   * Kacheln auf die Flaeche passen.
   *
   * VORHER ABER DIE ZEIT WEITERSTELLEN, und zwar fuer jede Karte: das kostet
   * 0,02 ms, und es sagt, ob ueberhaupt ein neues Bild ansteht. Eine Karte
   * rechnet 15 Posen je Sekunde und der Durchgang laeuft mit 30 - jedes zweite
   * Mal stuende dieselbe Pose da, und das Zeichnen und Kopieren waere umsonst.
   */
  sweep(work, timestamp) {
    const fresh = [];
    for (const card of work) {
      const frame = card.stage.step(timestamp);
      if (frame === card.frame && card.drawn) continue;
      card.frame = frame;
      fresh.push(card);
    }
    if (fresh.length === 0) return;

    this.reserve(fresh.length);
    const capacity = this.capacity;

    for (let start = 0; start < fresh.length; start += capacity) {
      const batch = fresh.slice(start, start + capacity);
      const tiles = batch.map((card, index) => this.tile(
        index,
        Math.min(card.stage.pixelWidth, this.tileWidth),
        Math.min(card.stage.pixelHeight, this.tileHeight),
      ));

      batch.forEach((card, index) => card.render(tiles[index]));
      batch.forEach((card, index) => card.copy(tiles[index]));
    }
  }
}

let surface = null;
let surfaceFailed = false;

/** Die Flaeche, beim ersten Mal gebaut. Wirft, wo es kein WebGL gibt - und
 *  merkt sich das, statt es 24-mal zu versuchen. */
function sharedSurface() {
  if (surfaceFailed) throw new Error('no WebGL for the card previews');
  if (!surface) {
    try {
      surface = new CardSurface();
    } catch (error) {
      surfaceFailed = true;
      throw error;
    }
  }
  return surface;
}

/**
 * Eine Karte: die Buehne, ihre 2D-Leinwand und die Frage, ob sie im Bild ist.
 */
class CardPreview {
  constructor(surface, canvas, stage) {
    this.surface = surface;
    this.canvas = canvas;
    this.context = canvas.getContext('2d');
    this.stage = stage;
    this.visible = true;
    this.drawn = false;
    this.frame = -1;
    surface.add(this);
  }

  get duration() {
    return this.stage.duration;
  }

  /** In die eigene Kachel zeichnen. Die Pose steht schon - [CardSurface.sweep]
   *  stellt sie fuer alle Karten, bevor die erste zeichnet. */
  render(tile) {
    this.surface.renderer.setViewport(tile.left, tile.bottom, tile.width, tile.height);
    this.surface.renderer.setScissor(tile.left, tile.bottom, tile.width, tile.height);
    this.surface.renderer.render(this.stage.scene, this.stage.camera);
  }

  /** Und das Ergebnis auf die eigene Leinwand holen. */
  copy(tile) {
    this.context.clearRect(0, 0, tile.width, tile.height);
    this.context.drawImage(this.surface.canvas, tile.left, tile.top, tile.width, tile.height,
      0, 0, tile.width, tile.height);
    //  Das erste Bild steht - die Karte darf die Leinwand einblenden (app.css).
    if (!this.drawn) this.canvas.classList.add('is-drawn');
    this.drawn = true;
  }

  setVisible(on) {
    if (this.visible === on) return;
    this.visible = on;
    //  Wer zurueckkommt, soll nicht den Sprung nachholen, den er verschlafen
    //  hat: die Buehne rechnet aus dem Abstand zum letzten Bild.
    if (on) {
      this.stage.last = null;
      this.surface.wake();
    }
  }

  setTime(time) {
    this.stage.setTime(time);
    this.frame = -1;
    this.drawn = false;
    this.surface.wake();
  }

  destroy() {
    this.surface.remove(this);
    this.stage.dispose();
  }
}

/**
 * Haengt die Figur in die Leinwand einer Karte. Wirft, wenn das nicht geht -
 * der Aufrufer nimmt dann das Strichmaennchen.
 */
export async function mountCardStage(canvas, preview, options = {}) {
  const gltf = await loadModel();
  const stage = new MannequinStage(canvas, thinPreview(preview), gltf, {
    surface: sharedSurface(),
    interactive: false,
    autoplay: options.autoplay,
    //  Nur Boden und Horizont - das Raster gehoert der Clip-Seite.
    grid: false,
  });
  return new CardPreview(stage.surface, canvas, stage);
}
