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
    this.yaw = options.yaw ?? -0.6;
    this.pitch = options.pitch ?? 0.25;
    this.zoom = 1;
    this.lastTimestamp = null;

    //  Fingerknochen sind 30 der 55 und ergeben in voller Staerke ein Gekrissel
    //  um jede Hand, das die Silhouette auffrisst - die Figur liest sich dann
    //  wie ein Insekt statt wie ein Mensch. Die Workbench trifft diese
    //  Unterscheidung laengst (AWCommunitySkeleton.DetailPrefixes: „Knochen,
    //  die auf Kartengroesse nur Matsch ergeben"); hier fehlte sie.
    this.detail = this.preview.bones.map((b) => SkeletonViewer.DETAIL.test(b));

    this.worldFrames = this.preview.hips.map((_, f) => this.solveFrame(f));
    this.computeBounds();

    if (this.interactive) this.attachInput();
    this.resize();
    new ResizeObserver(() => this.resize()).observe(canvas);
    this.loop = this.loop.bind(this);
    requestAnimationFrame(this.loop);
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
    const first = this.worldFrames[0];
    for (const frame of this.worldFrames) {
      for (const p of frame) {
        minY = Math.min(minY, p[1]);
        maxY = Math.max(maxY, p[1]);
      }
    }
    for (const p of first) {
      maxExtent = Math.max(maxExtent, Math.hypot(p[0] - first[0][0], p[2] - first[0][2]));
    }
    this.floorY = minY;
    this.height = Math.max(0.5, maxY - minY);
    this.extent = maxExtent;
  }

  // ── Eingabe ───────────────────────────────────────────────────────────

  attachInput() {
    let dragging = null;
    this.canvas.addEventListener("pointerdown", (e) => {
      dragging = { x: e.clientX, y: e.clientY, yaw: this.yaw, pitch: this.pitch };
      this.canvas.setPointerCapture(e.pointerId);
    });
    this.canvas.addEventListener("pointermove", (e) => {
      if (!dragging) return;
      this.yaw = dragging.yaw + (e.clientX - dragging.x) * 0.01;
      this.pitch = Math.max(-1.2, Math.min(1.2, dragging.pitch + (e.clientY - dragging.y) * 0.01));
    });
    this.canvas.addEventListener("pointerup", () => (dragging = null));
    this.canvas.addEventListener("wheel", (e) => {
      e.preventDefault();
      this.zoom = Math.max(0.3, Math.min(4, this.zoom * (e.deltaY > 0 ? 0.9 : 1.1)));
    }, { passive: false });
  }

  resize() {
    const ratio = window.devicePixelRatio || 1;
    const rect = this.canvas.getBoundingClientRect();
    this.canvas.width = Math.max(1, Math.round(rect.width * ratio));
    this.canvas.height = Math.max(1, Math.round(rect.height * ratio));
  }

  setTime(t) {
    this.time = Math.max(0, Math.min(this.duration, t));
  }

  // ── Zeichnen ──────────────────────────────────────────────────────────

  loop(timestamp) {
    if (this.lastTimestamp !== null && this.playing) {
      this.time += (timestamp - this.lastTimestamp) / 1000;
      if (this.time > this.duration) this.time = this.time % this.duration;
    }
    this.lastTimestamp = timestamp;

    this.draw();
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

    // Kamera folgt der Hüfte waagerecht, damit Laufzyklen im Bild bleiben.
    const target = [hips[0], this.floorY + this.height * 0.5, hips[2]];
    //  Einpassung aus BEIDEN Maßen: die Figur soll rund 84 % der Höhe füllen,
    //  in schmalen Karten aber nicht seitlich oder unten anstoßen.
    const scale = (Math.min(h * 0.84, w * 0.62) / this.height) * this.zoom;

    const cy = Math.cos(this.yaw), sy = Math.sin(this.yaw);
    const cp = Math.cos(this.pitch), sp = Math.sin(this.pitch);
    const project = (p) => {
      const x = p[0] - target[0], y = p[1] - target[1], z = p[2] - target[2];
      const x1 = cy * x + sy * z;
      const z1 = -sy * x + cy * z;
      const y2 = cp * y - sp * z1;
      const z2 = sp * y + cp * z1;
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
