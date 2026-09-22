// Strichmännchen-Viewer für die Vorschau eines .awclip.
//
// Daten (siehe AWClipPreview in der Workbench): Knochen mit Elternindex,
// Ruhe-Offset im Raum des Elternknochens, pro Frame Hüftposition und lokale
// Rotation je Knochen. Weltlage: R_welt[i] = R_welt[eltern] · R_lokal[i],
// P[i] = P[eltern] + R_welt[eltern] · offset[i].
//
// Koordinaten wie in Unity (Y oben, Z in den Bildschirm). Kein Modell, keine
// Bibliothek - ein Canvas genügt, und die Rechtefrage an einer Figur stellt
// sich gar nicht (Plan O7).

class SkeletonViewer {
  /** Dieselben Prefixe wie AWCommunitySkeleton.DetailPrefixes in der Workbench. */
  static DETAIL = /^(Left|Right)(Thumb|Index|Middle|Ring|Little)/;

  constructor(canvas, preview, options = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.preview = preview;
    this.interactive = options.interactive !== false;
    this.onFrame = options.onFrame || null;

    this.frameCount = preview.hips.length;
    this.fps = preview.frameRate;
    this.duration = Math.max(1 / this.fps, (this.frameCount - 1) / this.fps);
    this.time = 0;
    this.playing = options.autoplay !== false;
    //  Blickwinkel wie auf der Mannequin-Buehne (stage.js): dieselben Zahlen,
    //  und seit der Rechnung unten in [draw] auch dieselbe Bedeutung. Wer
    //  einen Clip erst als Karte sieht und dann oeffnet, sieht ihn zweimal aus
    //  demselben Winkel.
    this.yaw = options.yaw ?? Math.PI - 0.55;
    this.pitch = options.pitch ?? 0.2;
    this.zoom = 1;
    this.lastTimestamp = null;

    //  Eine Karte ausserhalb des Bildes zeichnet nicht. Der Katalog schaltet
    //  das (app.js), die Clip-Seite laesst es stehen.
    this.visible = true;

    //  Fingerknochen sind 30 der 55 und ergeben in voller Staerke ein Gekrissel
    //  um jede Hand, das die Silhouette auffrisst - die Figur liest sich dann
    //  wie ein Insekt statt wie ein Mensch. Die Workbench trifft diese
    //  Unterscheidung laengst (AWCommunitySkeleton.DetailPrefixes: „Knochen,
    //  die auf Kartengroesse nur Matsch ergeben"); hier fehlte sie.
    this.detail = this.preview.bones.map((b) => SkeletonViewer.DETAIL.test(b));

    this.worldFrames = this.preview.hips.map((_, f) => this.solveFrame(f));

    //  DER VORSCHAU-BLOCK TRAEGT SEIN RIG NICHT. Es steht am Paket (`rig`),
    //  und dorthin reicht dieser Viewer nicht. Der Wurzelknochen sagt es aber
    //  eindeutig genug: humanoid heisst er immer `Hips`, weil die Namen dort
    //  aus einer festen Menge kommen. Alles andere bringt sein eigenes
    //  Skelett mit und wird deshalb anders eingepasst (siehe [computeBounds]).
    this.humanoid = this.preview.bones[0] === "Hips";
    this.computeBounds();

    if (this.interactive) this.attachInput();
    this.resize();
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(canvas);
    this.loop = this.loop.bind(this);
    this.running = false;
    this.drawnKey = null;
    this.start();
  }

  /**
   * Die Schleife anwerfen, falls sie steht. Sie haelt von selbst an, sobald
   * die Leinwand aus dem Bild ist - vorher lief sie auf jeder Karte weiter,
   * auch zwanzig Bildschirme weiter oben, und rechnete nur nicht mehr.
   */
  start() {
    if (this.running || this.destroyed) return;
    this.running = true;
    this.lastTimestamp = null;
    requestAnimationFrame(this.loop);
  }

  /** Fuer immer anhalten - eine Karte, die ersetzt wird. */
  destroy() {
    this.destroyed = true;
    this.visible = false;
    this.resizeObserver.disconnect();
  }

  // ── Mathematik ────────────────────────────────────────────────────────

  static qmul(a, b) {
    return [
      a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
      a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
      a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
      a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    ];
  }

  static qrot(q, v) {
    const [x, y, z, w] = q;
    const tx = 2 * (y * v[2] - z * v[1]);
    const ty = 2 * (z * v[0] - x * v[2]);
    const tz = 2 * (x * v[1] - y * v[0]);
    return [
      v[0] + w * tx + (y * tz - z * ty),
      v[1] + w * ty + (z * tx - x * tz),
      v[2] + w * tz + (x * ty - y * tx),
    ];
  }

  solveFrame(f) {
    const { parents, rest } = this.preview;
    const rotations = this.preview.rotations[f];
    const count = parents.length;
    const worldRot = new Array(count);
    const worldPos = new Array(count);

    for (let i = 0; i < count; i++) {
      const local = [rotations[i * 4], rotations[i * 4 + 1], rotations[i * 4 + 2], rotations[i * 4 + 3]];
      const parent = parents[i];
      if (parent < 0) {
        worldRot[i] = local;
        worldPos[i] = this.preview.hips[f].slice();
      } else {
        worldRot[i] = SkeletonViewer.qmul(worldRot[parent], local);
        const offset = SkeletonViewer.qrot(worldRot[parent], rest[i]);
        worldPos[i] = [worldPos[parent][0] + offset[0], worldPos[parent][1] + offset[1], worldPos[parent][2] + offset[2]];
      }
    }
    return worldPos;
  }

  computeBounds() {
    let minY = Infinity, maxY = -Infinity, maxExtent = 0.5;
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    const first = this.worldFrames[0];
    for (const frame of this.worldFrames) {
      for (const p of frame) {
        if (p[0] < minX) minX = p[0];
        if (p[0] > maxX) maxX = p[0];
        if (p[1] < minY) minY = p[1];
        if (p[1] > maxY) maxY = p[1];
        if (p[2] < minZ) minZ = p[2];
        if (p[2] > maxZ) maxZ = p[2];
      }
    }
    for (const p of first) {
      maxExtent = Math.max(maxExtent, Math.hypot(p[0] - first[0][0], p[2] - first[0][2]));
    }
    this.floorY = minY;
    this.height = Math.max(0.5, maxY - minY);
    this.extent = maxExtent;

    //  Die ganze Box ueber alle Bilder - fuer alles, was keine aufrechte Figur
    //  ist. Eine Tuer haengt an ihrer Angel und steht damit NEBEN dem
    //  Ursprung, ein Kranarm liegt quer: auf die Wurzel zentriert und nach der
    //  Hoehe eingepasst stand so ein Clip als Strich am Kartenrand.
    this.box = {
      cx: (minX + maxX) / 2,
      cy: (minY + maxY) / 2,
      cz: (minZ + maxZ) / 2,
      height: Math.max(1e-3, maxY - minY),
      girth: Math.max(1e-3, maxX - minX, maxZ - minZ),
    };
  }

  // ── Eingabe ───────────────────────────────────────────────────────────

  attachInput() {
    let dragging = null;
    this.canvas.addEventListener("pointerdown", (e) => {
      this.wheelArmed = true;
      dragging = { x: e.clientX, y: e.clientY, yaw: this.yaw, pitch: this.pitch };
      this.canvas.setPointerCapture(e.pointerId);
    });
    this.canvas.addEventListener("pointermove", (e) => {
      if (!dragging) return;
      //  Waagerecht gegen die Maus, SENKRECHT MIT ihr: nach oben ziehen
      //  schiebt die Kamera nach unten, man schaut von unten herauf. Dieselbe
      //  Bedienung wie in der Mannequin-Buehne (stage.js), wo die Herleitung
      //  steht.
      //
      //  Dieselbe ZEILE wie dort bedeutet seit dem 2026-09-20 auch dasselbe
      //  BILD: bis dahin hob ein groesserer Pitch die Kamera in der Buehne und
      //  senkte sie hier, und beide Dateien trugen trotzdem denselben
      //  Ausdruck. Abgeglichen worden war die Formel. Die Rechnung steht
      //  jetzt in [draw].
      //
      //  Das Vorzeichen der EINGABE mit der
      //  Workbench zu vergleichen fuehrt weiterhin in die Irre: dort dreht ein
      //  `Quaternion.Euler` eine echte Kamera, hier projiziert die Methode
      //  unten von Hand, und beide laufen bei gleichem Vorzeichen auf
      //  entgegengesetzte Bilder hinaus. Massstab ist das Bild, nicht die
      //  Formel.
      //
      //  Wer hier wieder ein Vorzeichen drehen will: erst pruefen, WELCHER
      //  Stand gerade ausgeliefert ist (der Build-Stempel im Fuss sagt es).
      //  Genau daran ist es zweimal gescheitert - beurteilt wurde ein Stand,
      //  der den vorigen Fix noch gar nicht enthielt, und die Korrektur drehte
      //  dann gegen die falsche Ausgangslage.
      this.yaw = dragging.yaw - (e.clientX - dragging.x) * 0.01;
      this.pitch = Math.max(-0.9, Math.min(1.1, dragging.pitch + (e.clientY - dragging.y) * 0.01));
    });
    this.canvas.addEventListener("pointerup", () => (dragging = null));
    this.canvas.addEventListener("pointercancel", () => (dragging = null));
    //  Das Rad zoomt erst, wenn man die Figur angefasst hat (oder mit Strg).
    //  Vorher fing die Leinwand JEDES Rad ab, und wer ueber die Seite
    //  scrollte, blieb an der Figur haengen. Wie in stage.js.
    this.canvas.addEventListener("pointerleave", () => (this.wheelArmed = false));
    this.canvas.addEventListener("wheel", (e) => {
      if (!this.wheelArmed && !e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      this.zoom = Math.max(0.3, Math.min(4, this.zoom * (e.deltaY > 0 ? 0.9 : 1.1)));
    }, { passive: false });
  }

  resize() {
    const ratio = window.devicePixelRatio || 1;
    const rect = this.canvas.getBoundingClientRect();
    this.canvas.width = Math.max(1, Math.round(rect.width * ratio));
    this.canvas.height = Math.max(1, Math.round(rect.height * ratio));
    //  Breite setzen leert die Leinwand, auch bei gleichem Wert - also neu
    //  zeichnen, selbst wenn der Schluessel in [loop] derselbe bliebe.
    this.drawnKey = null;
  }

  setTime(t) {
    this.time = Math.max(0, Math.min(this.duration, t));
  }

  /** Ausserhalb des Bildes wird weder gerechnet noch gezeichnet, und die
   *  Schleife steht; kommt die Leinwand zurueck, springt sie wieder an. */
  setVisible(on) {
    this.visible = on;
    if (on) this.start();
  }

  // ── Zeichnen ──────────────────────────────────────────────────────────

  loop(timestamp) {
    if (!this.visible) {
      this.running = false;
      return;
    }

    if (this.lastTimestamp !== null && this.playing) {
      this.time += (timestamp - this.lastTimestamp) / 1000;
      if (this.time > this.duration) this.time = this.time % this.duration;
    }
    this.lastTimestamp = timestamp;

    //  Nur zeichnen, was sich geaendert hat. Eine angehaltene Figur, die
    //  niemand dreht, ist jedes Mal dasselbe Bild.
    const frame = Math.min(this.frameCount - 1, Math.round(this.time * this.fps));
    const key = frame + "|" + this.yaw + "|" + this.pitch + "|" + this.zoom + "|"
      + this.canvas.width + "x" + this.canvas.height;
    if (key !== this.drawnKey) {
      this.draw();
      this.drawnKey = key;
      if (!this.shown) {
        this.shown = true;
        this.canvas.classList.add("is-drawn");
      }
    }
    if (this.onFrame) this.onFrame(this.time, this.duration);
    requestAnimationFrame(this.loop);
  }

  draw() {
    const { ctx, canvas } = this;
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const frame = Math.min(this.frameCount - 1, Math.round(this.time * this.fps));
    const points = this.worldFrames[frame];
    const hips = points[0];

    //  Aufrecht: die Kamera folgt der Hüfte waagerecht, damit Laufzyklen im
    //  Bild bleiben, und die Figur füllt rund 84 % der Höhe - in schmalen
    //  Karten aber nicht seitlich oder unten anstoßend.
    //
    //  Generisch: die Mitte der Box statt der Wurzel, und eingepasst in BEIDE
    //  Richtungen. Sonst entscheidet der Zufall, wo der Ursprung des Rigs
    //  liegt, über die Bildmitte.
    const target = this.humanoid
      ? [hips[0], this.floorY + this.height * 0.5, hips[2]]
      : [this.box.cx, this.box.cy, this.box.cz];

    const scale = (this.humanoid
      ? Math.min(h * 0.84, w * 0.62) / this.height
      : Math.min(h * 0.84 / this.box.height, w * 0.78 / this.box.girth)) * this.zoom;

    const cy = Math.cos(this.yaw), sy = Math.sin(this.yaw);
    const cp = Math.cos(this.pitch), sp = Math.sin(this.pitch);

    //  ERST UM DIE HOCHACHSE DREHEN, DANN KIPPEN, DANN VERKLEINERN. `z2`
    //  waechst von der Kamera weg.
    //
    //  YAW UND PITCH BEDEUTEN HIER DASSELBE WIE IN DER BUEHNE (stage.js) -
    //  nachgerechnet, nicht abgeschrieben. Dort steht die Kamera bei
    //  target + (sin(yaw)*cos(pitch), sin(pitch), cos(yaw)*cos(pitch)) mal
    //  Abstand, in glTF-Koordinaten, also mit umgedrehtem z. In Unitys Raum -
    //  dem hier - ist das (sin(yaw)*cos(pitch), sin(pitch),
    //  -cos(yaw)*cos(pitch)), und genau diese Kamera beschreiben die beiden
    //  Zeilen unten: ein groesserer Pitch HEBT sie.
    //
    //  Bis zum 2026-09-20 stand im Kipp-Schritt das andere Vorzeichen. Damit
    //  schaute dieselbe Zahl von unten herauf statt von oben herab, und eine
    //  Karte im Katalog zeigte ihren Clip aus einem anderen Winkel als die
    //  Clip-Seite, die man von ihr aus oeffnet - in der Hoehe gespiegelt und
    //  um eine halbe Drehung daneben.
    const project = (p) => {
      const x = p[0] - target[0], y = p[1] - target[1], z = p[2] - target[2];
      const x1 = cy * x + sy * z;
      const z1 = -sy * x + cy * z;
      const y2 = cp * y + sp * z1;
      const z2 = cp * z1 - sp * y;
      const perspective = 3.5 / (3.5 + z2 / this.height);
      return [w / 2 + x1 * scale * perspective, h / 2 - y2 * scale * perspective, z2];
    };

    this.drawFloor(project, target, w);

    const projected = points.map(project);
    const parents = this.preview.parents;
    const order = projected.map((_, i) => i).sort((a, b) => projected[b][2] - projected[a][2]);

    ctx.lineCap = "round";
    const lineWidth = Math.max(2, w / 260);

    for (const i of order) {
      const parent = parents[i];
      if (parent < 0) continue;
      const a = projected[parent], b = projected[i];
      const name = this.preview.bones[i];
      //  Seitenfarben aus der Marke: Akzentviolett links, Warmton rechts.
      ctx.strokeStyle = name.startsWith("Left") ? "#8e77ff" : name.startsWith("Right") ? "#fb923c" : "#d9d5e4";
      //  Feine Knochen duenner und blasser - sie sollen die Silhouette
      //  ergaenzen, nicht mit ihr konkurrieren. Werte wie in der Workbench.
      const fine = this.detail[i];
      ctx.lineWidth = fine ? lineWidth * 0.55 : lineWidth;
      ctx.globalAlpha = fine ? 0.45 : 1;
      ctx.beginPath();
      ctx.moveTo(a[0], a[1]);
      ctx.lineTo(b[0], b[1]);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }

    ctx.fillStyle = "#eeecf3";
    for (const i of order) {
      const p = projected[i];
      if (this.detail[i]) continue;  // 30 weisse Punkte an den Fingern sind nur Rauschen
      const name = this.preview.bones[i];
      const radius = name === "Head" ? lineWidth * 3.2 : lineWidth * 0.9;
      ctx.beginPath();
      ctx.arc(p[0], p[1], radius, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  drawFloor(project, target, width) {
    const { ctx } = this;
    const size = Math.max(1, Math.ceil(this.extent * 3));
    const step = Math.max(0.25, size / 6);
    const y = this.floorY;
    ctx.strokeStyle = "rgba(169, 164, 182, 0.16)";
    ctx.lineWidth = Math.max(1, width / 900);

    const cx = Math.round(target[0] / step) * step;
    const cz = Math.round(target[2] / step) * step;
    for (let i = -6; i <= 6; i++) {
      const a = project([cx + i * step, y, cz - 6 * step]);
      const b = project([cx + i * step, y, cz + 6 * step]);
      const c = project([cx - 6 * step, y, cz + i * step]);
      const d = project([cx + 6 * step, y, cz + i * step]);
      ctx.beginPath();
      ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]);
      ctx.moveTo(c[0], c[1]); ctx.lineTo(d[0], d[1]);
      ctx.stroke();
    }
  }
}
