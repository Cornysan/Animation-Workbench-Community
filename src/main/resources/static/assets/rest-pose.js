/**
 * Die Ruhepose einer Vorschau: je Knochen die lokale Drehung der T-Pose der
 * Quellfigur, in Unitys Raum und nach derselben Formel wie
 * `preview.rotations` (Drehung relativ zum Elternknochen der Vorschau).
 *
 * WOZU. Wer aus der Vorschau eine Datei MIT SKELETT schreibt (`.fbx`,
 * `.glb`), muss dem Skelett eine Ruhelage geben, und Unity baut den Avatar
 * einer importierten Datei genau aus ihr. Bis zum 2026-09-24 stand dort
 * Bild 0 - bei einem Kampfclip eine Kampfpose. Gemessen an einem echten
 * Clip lagen die Oberarme damit 71 bis 76 Grad neben der T-Pose der Quelle,
 * die Unterarme 44 bis 67, die Finger bis 75; der Avatar hielt die
 * Kampfhaltung fuer seine Nullstellung, und die Bewegung kam auf jeder Figur
 * verdreht an.
 *
 * ZWEI QUELLEN. Seit dem 2026-09-24 schickt die Workbench die T-Pose beim
 * Upload mit (`restRot`, gemessen mit allen Muskeln auf 0 - siehe
 * `AWClipPreviewBaker.BakeRestPose`). Dann gilt sie, und es gibt nichts zu
 * schaetzen. Alle aelteren Clips und die Starter-Clips (sie kommen als
 * Datei, ohne das Formularfeld) haben sie nicht - fuer sie steht hier die
 * Schaetzung.
 *
 * ── Die Schaetzung ──────────────────────────────────────────────────────
 *
 * Unitys Humanoid schreibt jeden Knochen als  lokal(t) = T * delta(t):
 * T-Pose mal Muskeldrehung. Kennt man die RICHTUNG, in die ein Knochen in
 * der T-Pose zeigt (der Arm zur Seite, das Bein nach unten), laesst sich die
 * Schwenkung aus jedem Bild herausnehmen:
 *
 *     T = lokal(t) * fromTo(a, lokal(t)^-1 * t_dir)
 *
 * (`a` = Richtung zum Kind im eigenen Raum, aus `rest`, exakt). Uebrig bleibt
 * die Drehung UM die Knochenachse, die der Clip in diesem Bild hatte. Hand,
 * Schulter, Finger haben in Unitys Muskelraum gar keine - fuer sie ist das
 * exakt. Fuer alle anderen bleibt die mittlere Verdrehung des Clips als
 * Fehler, gemittelt ueber alle Bilder.
 *
 * Wo das Skelett selbst mehr sagt, gilt das Skelett:
 *   - Becken: Bein zu Bein ist die Querachse, zur Wirbelsaeule ist oben.
 *   - Kopf: Auge zu Auge ist die Querachse.
 *   - Hand: Zeige- vor Kleinfinger heisst Handflaeche nach unten. Unity legt
 *     die Unterarm-Verdrehung zur Haelfte auf die Hand; der Unterarm wird
 *     deshalb von oben (Oberarm) UND von unten (Hand) geschaetzt und
 *     gemittelt - die beiden Haelften heben sich auf. Gemessen: Unterarm 25
 *     bzw. 37 Grad Fehler von oben allein, 5 bzw. 8 gemittelt.
 *   - Fuss: in einem Bild, in dem er flach am Boden steht, haben Neigung und
 *     Kippung dieselbe Lage wie in der T-Pose; nur die Blickrichtung ist frei,
 *     und die ist dort "nach vorn".
 *
 * Gemessen an einer Sidekick-Figur, deren Avatar-T-Pose in ihrer `.meta`
 * steht: im Mittel 5,7 Grad je Knochen statt 36,3 mit Bild 0. Der Rest ist
 * Rig-Eigenart, die sich ohne die echte T-Pose nicht zeigt - vor allem die
 * Kruemmung des Rumpfs (siehe TORSO_TILT) und die Fingerhaltung.
 */

// ── Quaternionen [x, y, z, w] ─────────────────────────────────────────────

const mul = (a, b) => [
  a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
  a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
  a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
  a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
];
const inv = (q) => [-q[0], -q[1], -q[2], q[3]];

function rot(q, v) {
  const r = mul(mul(q, [v[0], v[1], v[2], 0]), inv(q));
  return [r[0], r[1], r[2]];
}

const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const scale = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const unit = (a) => scale(a, 1 / (len(a) || 1));

function unit4(q) {
  const l = Math.hypot(q[0], q[1], q[2], q[3]) || 1;
  return [q[0] / l, q[1] / l, q[2] / l, q[3] / l];
}

/** Die kuerzeste Drehung, die Richtung a auf Richtung b legt. */
function fromTo(a, b) {
  a = unit(a); b = unit(b);
  const d = dot(a, b);
  if (d < -0.999999) {
    let axis = cross(a, [1, 0, 0]);
    if (len(axis) < 1e-6) axis = cross(a, [0, 1, 0]);
    axis = unit(axis);
    return [axis[0], axis[1], axis[2], 0];
  }
  const c = cross(a, b);
  return unit4([c[0], c[1], c[2], 1 + d]);
}

/** Die Drehung, deren lokales X auf x zeigt und deren Y so nah wie moeglich an y. */
function basis(x, y) {
  x = unit(x);
  const z = unit(cross(x, y));
  y = cross(z, x);
  const m00 = x[0], m10 = x[1], m20 = x[2];
  const m01 = y[0], m11 = y[1], m21 = y[2];
  const m02 = z[0], m12 = z[1], m22 = z[2];
  const trace = m00 + m11 + m22;
  if (trace > 0) {
    const s = Math.sqrt(trace + 1) * 2;
    return [(m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, s / 4];
  }
  if (m00 > m11 && m00 > m22) {
    const s = Math.sqrt(1 + m00 - m11 - m22) * 2;
    return [s / 4, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s];
  }
  if (m11 > m22) {
    const s = Math.sqrt(1 + m11 - m00 - m22) * 2;
    return [(m01 + m10) / s, s / 4, (m12 + m21) / s, (m02 - m20) / s];
  }
  const s = Math.sqrt(1 + m22 - m00 - m11) * 2;
  return [(m02 + m20) / s, (m12 + m21) / s, s / 4, (m10 - m01) / s];
}

/** Weltdrehung W mit W*a = A genau und W*b so nah an B wie moeglich. */
const framePairs = (a, A, b, B) => mul(basis(A, B), inv(basis(a, b)));

/** Mittel mehrerer Drehungen, die nahe beieinander liegen. */
function average(qs) {
  const ref = qs[0];
  const acc = [0, 0, 0, 0];
  for (const q of qs) {
    const s = q[0] * ref[0] + q[1] * ref[1] + q[2] * ref[2] + q[3] * ref[3] < 0 ? -1 : 1;
    for (let k = 0; k < 4; k++) acc[k] += q[k] * s;
  }
  return unit4(acc);
}

function slerp(a, b, t) {
  let d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
  if (d < 0) { b = b.map((v) => -v); d = -d; }
  if (d > 0.999999) return a;
  const th = Math.acos(d);
  const sa = Math.sin((1 - t) * th) / Math.sin(th);
  const sb = Math.sin(t * th) / Math.sin(th);
  return unit4([0, 1, 2, 3].map((k) => a[k] * sa + b[k] * sb));
}

// ── Die T-Pose in Unitys Raum ─────────────────────────────────────────────
//
// Die Figur schaut nach +Z, oben ist +Y - und weil Unity linkshaendig ist,
// liegt ihre LINKE Seite bei -X.

const UP = [0, 1, 0];
const DOWN = [0, -1, 0];
const FWD = [0, 0, 1];
const LEFT = [-1, 0, 0];
const RIGHT = [1, 0, 0];

const RAD = Math.PI / 180;

/** v um `deg` Grad nach vorn geneigt. */
const tilted = (v, deg) => unit(add(scale(v, Math.cos(deg * RAD)), scale(FWD, Math.sin(deg * RAD))));

/**
 * Wie weit ein Rumpfglied in der T-Pose nach vorn geneigt ist, in Grad.
 *
 * Das ist der Teil, den nur die echte T-Pose wirklich kennt: Unity richtet
 * Arme und Beine aus, die Kruemmung der Wirbelsaeule laesst es, wie der Rigger
 * sie gebaut hat. Die Werte sind das Mittel aus zwei Rigs dieses Projekts
 * (Sidekick / Feel-Mixamo): Becken->Wirbelsaeule 4 / 19, Wirbelsaeule->Brust
 * 8 / 5, Brust->obere Brust 3 / -, zum Hals -6 / -6, Hals->Kopf 17 / 11.
 * Ein anderes Rig liegt einige Grad daneben - dafuer gibt es `restRot`.
 */
const TORSO_TILT = {
  'Hips>Spine': 12,
  'Spine>Chest': 7,
  'Chest>UpperChest': 3,
  'Chest>Neck': -6,
  'UpperChest>Neck': -6,
  'Neck>Head': 14,
};

/**
 * Der Daumen zeigt in der T-Pose nicht zur Seite, sondern schraeg nach vorn:
 * gemessen 43 Grad vor und 5 Grad unter der Handachse (Sidekick), 41 und 8
 * (Feel). Mit der Handachse geschaetzt lag der Daumen 48 Grad daneben.
 */
const THUMB_FORWARD = 42;
const THUMB_DOWN = 6;

/** Wenn kein Bild den Fuss flach zeigt: Fuss->Zehen so weit unter der Waagrechten. */
const FOOT_PITCH = 30;

const FINGERS = ['Index', 'Middle', 'Ring', 'Little', 'Thumb'];

// ── Oeffentlich ───────────────────────────────────────────────────────────

/**
 * Die Ruhepose einer Vorschau, je Knochen [x, y, z, w] in Unitys Raum.
 *
 * `measured` sagt, ob sie von der Quellfigur stammt (`restRot`) oder
 * geschaetzt ist. Eine generische Vorschau hat keine T-Pose; sie bekommt
 * ihr erstes Bild, so wie bisher.
 */
export function restPose(preview) {
  const measured = measuredRestPose(preview);
  if (measured) return { rotations: measured, measured: true };
  return { rotations: estimateRestPose(preview), measured: false };
}

/** `restRot`, wenn es zur Vorschau passt - sonst null. */
function measuredRestPose(preview) {
  const rows = preview.restRot;
  if (!Array.isArray(rows) || rows.length !== preview.bones.length) return null;
  const out = [];
  for (const q of rows) {
    if (!Array.isArray(q) || q.length !== 4 || !q.every(Number.isFinite)) return null;
    if (Math.abs(Math.hypot(q[0], q[1], q[2], q[3]) - 1) > 1e-2) return null;
    out.push(unit4(q));
  }
  return out;
}

const frameZero = (preview) =>
  preview.bones.map((_, i) => unit4(preview.rotations[0].slice(i * 4, i * 4 + 4)));

export function estimateRestPose(preview) {
  const names = preview.bones;
  const parents = preview.parents;
  const index = new Map(names.map((n, i) => [n, i]));
  const has = (n) => index.has(n);

  if (names[0] !== 'Hips' || !['Spine', 'LeftUpperLeg', 'RightUpperLeg'].every(has)) return frameZero(preview);

  const count = names.length;
  const rest = preview.rest;
  const frames = preview.rotations.map((row) => names.map((_, i) => row.slice(i * 4, i * 4 + 4)));
  const offset = (n) => rest[index.get(n)];
  const dirTo = (n) => unit(offset(n));

  const W = new Array(count);
  const T = new Array(count);

  /** Die Schwenkung aus jedem Bild nehmen, die Verdrehung mitteln. */
  const swingBack = (i, a, t) =>
    average(frames.map((f) => mul(f[i], fromTo(a, rot(inv(f[i]), t)))));
  const meanLocal = (i) => average(frames.map((f) => f[i]));

  // Der Rumpf, soweit es ihn gibt - fehlt ein Glied, zeigt der Vorgaenger
  // auf das naechste vorhandene.
  const chain = ['Hips', 'Spine', 'Chest', 'UpperChest', 'Neck', 'Head'].filter(has);
  const next = new Map(chain.slice(0, -1).map((n, k) => [n, chain[k + 1]]));

  const sideOf = (n) => (n.startsWith('Left') ? LEFT : RIGHT);
  const prefix = (n) => (n.startsWith('Left') ? 'Left' : 'Right');
  const thumbDirection = (s) => {
    const flat = unit(add(scale(s, Math.cos(THUMB_FORWARD * RAD)), scale(FWD, Math.sin(THUMB_FORWARD * RAD))));
    return unit(add(scale(flat, Math.cos(THUMB_DOWN * RAD)), scale(DOWN, Math.sin(THUMB_DOWN * RAD))));
  };

  /** [Kind, Richtung in der T-Pose] oder null. */
  function plan(n) {
    if (next.has(n)) return [next.get(n), tilted(UP, TORSO_TILT[n + '>' + next.get(n)] || 0)];
    const side = prefix(n);
    const limbs = {
      Shoulder: ['UpperArm', sideOf(n)], UpperArm: ['LowerArm', sideOf(n)],
      LowerArm: ['Hand', sideOf(n)], Hand: ['MiddleProximal', sideOf(n)],
      UpperLeg: ['LowerLeg', DOWN], LowerLeg: ['Foot', DOWN],
    };
    for (const [part, [child, dir]] of Object.entries(limbs)) {
      if (n === side + part) return has(side + child) ? [side + child, dir] : null;
    }
    for (const finger of FINGERS) {
      for (const [from, to] of [['Proximal', 'Intermediate'], ['Intermediate', 'Distal']]) {
        if (n === side + finger + from && has(side + finger + to)) {
          return [side + finger + to, finger === 'Thumb' ? thumbDirection(sideOf(n)) : sideOf(n)];
        }
      }
    }
    return null;
  }

  const poses = worldPoses(preview, frames);

  for (let i = 0; i < count; i++) {
    const n = names[i];
    const p = parents[i];
    const Wp = p >= 0 ? W[p] : [0, 0, 0, 1];

    if (i === 0) {
      const lateral = sub(offset('LeftUpperLeg'), offset('RightUpperLeg'));
      W[0] = framePairs(dirTo(next.get('Hips')), tilted(UP, TORSO_TILT['Hips>' + next.get('Hips')] || 0), lateral, LEFT);
      T[0] = W[0];
      continue;
    }

    const side = n.startsWith('Left') ? 'Left' : n.startsWith('Right') ? 'Right' : '';
    const route = plan(n);

    if (side && n === side + 'Hand' && route && has(side + 'IndexProximal') && has(side + 'LittleProximal')) {
      // Handflaeche nach unten: der Zeigefinger sitzt vor dem kleinen.
      const a = dirTo(route[0]);
      const across = sub(offset(side + 'IndexProximal'), offset(side + 'LittleProximal'));
      const hand = framePairs(a, route[1], across, FWD);
      // Den Unterarm von unten her: die Hand ohne ihre Schwenkung, zurueckgerechnet.
      const fromBelow = mul(hand, inv(swingBack(i, a, rot(inv(Wp), route[1]))));
      const lower = slerp(Wp, fromBelow, 0.5);
      W[p] = lower;
      T[p] = mul(inv(W[parents[p]]), lower);
      W[i] = hand;
      T[i] = mul(inv(lower), hand);
      continue;
    }

    if (side && n === side + 'Foot') {
      const toes = side + 'Toes';
      const flat = has(toes) ? plantedFoot(preview, poses, i, index.get(toes)) : null;
      if (flat) {
        W[i] = flat;
        T[i] = mul(inv(Wp), flat);
        continue;
      }
      if (has(toes)) {
        T[i] = swingBack(i, dirTo(toes), rot(inv(Wp), unit([0, -Math.sin(FOOT_PITCH * RAD), Math.cos(FOOT_PITCH * RAD)])));
      } else {
        T[i] = meanLocal(i);
      }
      W[i] = mul(Wp, T[i]);
      continue;
    }

    if (route) {
      T[i] = swingBack(i, dirTo(route[0]), rot(inv(Wp), route[1]));
    } else if (n === 'Head') {
      // Kein Kind in Kopfrichtung - die Achse des Halses gilt weiter (so
      // bauen Rigs ihre Ketten), und der Kopf steht in der T-Pose aufrecht.
      const a = unit(rest[i]);
      if (has('LeftEye') && has('RightEye')) {
        W[i] = framePairs(a, UP, sub(offset('LeftEye'), offset('RightEye')), LEFT);
        T[i] = mul(inv(Wp), W[i]);
        continue;
      }
      T[i] = swingBack(i, a, rot(inv(Wp), UP));
    } else if (/(Distal|Hand)$/.test(n)) {
      // Endglied: geradeaus weiter in der Achse des Elternknochens.
      const a = unit(rest[i]);
      T[i] = swingBack(i, a, n.endsWith('Hand') ? rot(inv(Wp), sideOf(n)) : a);
    } else {
      // Zehen, Augen, Kiefer: bewegen sich kaum, ihr Mittel ist die Ruhe.
      T[i] = meanLocal(i);
    }
    W[i] = mul(Wp, T[i]);
  }

  return T;
}

// ── Der flache Fuss ───────────────────────────────────────────────────────

/** Weltdrehung und -lage jedes Knochens in jedem Bild. */
function worldPoses(preview, frames) {
  const parents = preview.parents;
  return frames.map((f, k) => {
    const W = new Array(f.length);
    const P = new Array(f.length);
    for (let i = 0; i < f.length; i++) {
      if (parents[i] < 0) {
        W[i] = f[i];
        P[i] = preview.hips[k];
      } else {
        W[i] = mul(W[parents[i]], f[i]);
        P[i] = add(P[parents[i]], rot(W[parents[i]], preview.rest[i]));
      }
    }
    return { W, P };
  });
}

/**
 * Die T-Pose des Fusses aus den Bildern, in denen er am tiefsten steht -
 * Knoechel UND Zehen zugleich, also flach. Dort sind Neigung und Kippung die
 * der T-Pose; die Blickrichtung wird nach vorn gedreht.
 *
 * Null, wenn kein Bild beides zugleich zeigt oder die Neigung nicht nach
 * einem stehenden Fuss aussieht (ein Clip auf Zehenspitzen).
 */
function plantedFoot(preview, poses, foot, toes) {
  const a = unit(preview.rest[toes]);
  const leg = len(preview.rest[foot]) + len(preview.rest[preview.parents[foot]]);
  const tolerance = Math.max(0.005, 0.015 * leg);

  const footY = poses.map((pose) => pose.P[foot][1]);
  const toesY = poses.map((pose) => pose.P[toes][1]);
  const lowFoot = Math.min(...footY);
  const lowToes = Math.min(...toesY);

  const picks = [];
  poses.forEach((pose, k) => {
    if (footY[k] > lowFoot + tolerance || toesY[k] > lowToes + tolerance) return;
    const d = rot(pose.W[foot], a);
    const flat = [d[0], 0, d[2]];
    if (len(flat) < 1e-3) return;
    picks.push(mul(fromTo(flat, FWD), pose.W[foot]));
  });
  if (!picks.length) return null;

  const W = average(picks);
  const down = -rot(W, a)[1];
  const pitch = Math.asin(Math.max(-1, Math.min(1, down))) / RAD;
  return pitch >= 5 && pitch <= 60 ? W : null;
}
