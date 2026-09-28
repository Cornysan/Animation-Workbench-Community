/**
 * Wie das Mannequin aussieht - der Look, den der Ersteller beim Teilen waehlt.
 *
 * ── Warum Shader und keine Texturen ─────────────────────────────────────
 *
 * Das Mannequin hat keine UVs: alle 8462 Ecken liegen auf demselben
 * UV-Punkt, in Unity wie hier. Eine Textur verschmoelze zu einer Farbe. Also
 * rechnet jeder Look sein Muster selbst aus - aus der Lage auf dem Koerper
 * (`position` ist die Bindepose, das Muster klebt also an der Haut und
 * laeuft beim Bewegen mit) oder aus der Blickrichtung (Galaxy steht fest im
 * Bild, die Figur ist das Fenster davor).
 *
 * Die Bindepose ist der Mesh-Raum der Datei: x seitlich, y Tiefe (vorn
 * negativ), z senkrecht (oben NEGATIV), etwa -1..1 fuer die ganze Figur.
 * Dasselbe gilt fuer die weibliche Figur - sie ist dieselbe Datei in anderer
 * Form (tools/mannequin/female.py).
 *
 * ── Jede Figur hat ihre eigenen Looks ───────────────────────────────────
 *
 * Seit 2026-09-29 gehoert jeder Look genau EINER Figur, und jeder hat ein
 * Gegenstueck bei der anderen ([LOOK_INFO]): Gold beim Mann, Rose Gold bei
 * der Frau, Classic und Crimson als Standard. Wer auf der Clip-Seite die
 * Figur wechselt, sieht das Gegenstueck statt eines Looks, der nicht zu ihr
 * gehoert - [lookFor] ist die EINE Stelle, die das entscheidet.
 *
 * ── Wer was entscheidet ──────────────────────────────────────────────────
 *
 * WELCHE Looks es gibt und welche davon Pro sind, sagt der Server
 * (`FigureLooks.kt`). Hier steht, wie sie AUSSEHEN - und, weil die Buehne
 * ohne Server die Figur wechselt, noch einmal Figur und Gegenstueck. Ein
 * Schluessel, den diese Datei nicht kennt, zeigt den Standard der Figur -
 * eine neue Seite mit altem Skript im Cache soll keine leere Figur zeigen.
 *
 * Eigene Figuren tragen keinen Look: sie behalten ihre Materialien.
 *
 * Die Workbench zeigt dieselben Looks in ihrer Vorschau beim Teilen und
 * rechnet sie dafuer nach: die Farben in `Characters/AWMannequinLooks.cs`,
 * die Muster in `Shaders/AWMannequinLook.shader` - dort mit denselben
 * Farbwerten als Material-Parameter. Aendert sich hier ein Look, dort mitziehen.
 */

import { CanvasTexture, MeshStandardMaterial, SRGBColorSpace } from './vendor/three.module.js';

/** `THREE.EquirectangularReflectionMapping` - der Buendel exportiert den Namen
 *  nicht, der Renderer versteht den Wert (vendor/entry.js, README). */
const EQUIRECT_REFLECTION = 303;

const ACCENT = 0x8e77ff;
const WARM = 0xfb923c;

// ── GLSL, das mehrere Looks teilen ──────────────────────────────────────────

const NOISE = /* glsl */ `
  float awHash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
  }
  float awNoise(vec3 x) {
    vec3 i = floor(x), f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(awHash(i + vec3(0, 0, 0)), awHash(i + vec3(1, 0, 0)), f.x),
                   mix(awHash(i + vec3(0, 1, 0)), awHash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(awHash(i + vec3(0, 0, 1)), awHash(i + vec3(1, 0, 1)), f.x),
                   mix(awHash(i + vec3(0, 1, 1)), awHash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
  }
  float awFbm(vec3 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * awNoise(p); p = p * 2.03 + vec3(1.7, 9.2, 3.1); a *= 0.5; }
    return v;
  }
  // Wie flach auf die Flaeche geschaut wird: 0 frontal, 1 am Rand.
  float awRim(vec3 n, vec3 viewPos) {
    return 1.0 - clamp(dot(normalize(n), normalize(viewPos)), 0.0, 1.0);
  }
`;

/**
 * Ein MeshStandardMaterial mit eingesetztem Code.
 *
 * `vertex: true` reicht `vAwObj` (Bindepose) und `vAwWorld` (Welt) an den
 * Fragment-Teil weiter. `color` ersetzt die Grundfarbe, `emissive` addiert
 * Leuchten - jeweils ein GLSL-Schnipsel. Im `emissive`-Teil steht die
 * Flaechennormale (`normal`) schon fest; wer die Deckung aendern will, setzt
 * dort `diffuseColor.a` - davor gibt es `normal` noch nicht.
 *
 * `customProgramCacheKey` ist Pflicht: ohne ihn teilen sich zwei Looks mit
 * gleichen Grundparametern ein Programm, und der zweite zeigt den ersten.
 */
function shaded(key, params, { vertex = false, color = '', emissive = '' }, uniforms) {
  const material = new MeshStandardMaterial(params);
  material.onBeforeCompile = (shader) => {
    shader.uniforms.uAwTime = uniforms.time;

    if (vertex) {
      shader.vertexShader = shader.vertexShader
        .replace('#include <common>', '#include <common>\nvarying vec3 vAwObj;\nvarying vec3 vAwWorld;')
        .replace('#include <begin_vertex>', '#include <begin_vertex>\nvAwObj = position;')
        .replace('#include <skinning_vertex>',
          '#include <skinning_vertex>\nvAwWorld = (modelMatrix * vec4(transformed, 1.0)).xyz;');
    }

    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', '#include <common>\nuniform float uAwTime;\n'
        + (vertex ? 'varying vec3 vAwObj;\nvarying vec3 vAwWorld;\n' : '') + NOISE)
      .replace('#include <color_fragment>', '#include <color_fragment>\n' + color)
      .replace('#include <emissivemap_fragment>', '#include <emissivemap_fragment>\n' + emissive);
  };
  material.customProgramCacheKey = () => 'aw-look-' + key;
  return material;
}

// ── Das Studio, in dem Metall glaenzt ───────────────────────────────────────

let studio = null;

/**
 * Eine Umgebung fuer Gold und Chrome. Metall zeigt, was um es herum ist - ohne
 * Umgebung ist ein poliertes Metall schwarz mit ein paar Lichtpunkten. Ein
 * gemaltes Rundum-Bild: dunkler Raum, eine grosse Lichtflaeche oben, zwei
 * schmale an den Seiten, ein heller Boden. Der Renderer bereitet es selbst
 * fuer die Spiegelung auf (PMREM), einmal je Seite.
 */
function studioTexture() {
  if (studio) return studio;

  const canvas = document.createElement('canvas');
  canvas.width = 512;
  canvas.height = 256;
  const ctx = canvas.getContext('2d');

  //  Heller als die Buehne selbst: Chrome zeigt fast nur die Umgebung, und
  //  ein dunkler Raum machte es schwarz.
  const sky = ctx.createLinearGradient(0, 0, 0, 256);
  sky.addColorStop(0, '#a4a4ae');
  sky.addColorStop(0.45, '#5a5a64');
  sky.addColorStop(0.52, '#3a3a42');
  sky.addColorStop(1, '#b8b6be');
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, 512, 256);

  const box = (x, y, w, h, alpha) => {
    const g = ctx.createRadialGradient(x + w / 2, y + h / 2, 0, x + w / 2, y + h / 2, Math.max(w, h) / 1.4);
    g.addColorStop(0, `rgba(255,255,255,${alpha})`);
    g.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = g;
    ctx.fillRect(x - w, y - h, w * 3, h * 3);
  };
  box(200, 18, 120, 40, 1);      // oben, ueber der Figur
  box(40, 90, 30, 70, 0.85);     // links
  box(420, 96, 34, 64, 0.7);     // rechts, etwas schwaecher

  studio = new CanvasTexture(canvas);
  studio.mapping = EQUIRECT_REFLECTION;
  studio.colorSpace = SRGBColorSpace;
  return studio;
}

// ── Muster mit Farbwerten ───────────────────────────────────────────────────
//
// Die Muster der Frau sind die des Mannes in anderen Farben (Rose Quartz ist
// Marble, Nebula ist Galaxy ...). Also baut EINE Funktion je Muster den
// GLSL-Schnipsel, mit den Farben als Konstanten. Unity bekommt dieselben
// Zahlen als Material-Parameter (_AWPatternA..D).

/** Eine GLSL-Zahl - `1` muss `1.0` heissen. */
const glf = (x) => (Number.isInteger(x) ? x.toFixed(1) : String(x));
const vec3 = ([r, g, b]) => `vec3(${glf(r)}, ${glf(g)}, ${glf(b)})`;

/** Polierter Stein: Grundton hell/dunkel, dazu die Adern. */
const marblePattern = (stoneLight, stoneDark, vein) => /* glsl */ `
  // Gratrauschen: 1 dort, wo das Feld die Mitte kreuzt - weiche Adern
  // mit Verlauf statt harter Risse.
  vec3 p = vAwObj * 2.6;
  float ridge = 1.0 - abs(2.0 * awFbm(p + awFbm(p * 0.7) * 1.8) - 1.0);
  float fine = 1.0 - abs(2.0 * awFbm(p * 2.3 + 11.0) - 1.0);
  float v = pow(ridge, 7.0) * 0.75 + pow(fine, 12.0) * 0.35;
  vec3 stone = mix(${vec3(stoneLight)}, ${vec3(stoneDark)}, awFbm(p * 3.0));
  diffuseColor.rgb = pow(mix(stone, ${vec3(vein)}, clamp(v, 0.0, 1.0)), vec3(2.2));`;

/** Dunkle Schale, ein Hauch Farbe an den Kanten. */
const neonPattern = (rim) => /* glsl */ `
  totalEmissiveRadiance += ${vec3(rim)} * pow(awRim(normal, vViewPosition), 4.5) * 0.3;`;

/**
 * Nebel und Sterne FEST IM BILD, aus der Blickrichtung.
 *
 * Die Sterne haben eine feste Groesse in PIXELN, nicht in der
 * Blickrichtung: `s` haengt am Sichtfeld, und ein Stern fester Groesse in
 * `s` wurde bei engem Sichtfeld und grossem Bild zum Fleck (in Unity
 * deutlich, 2026-09-29). `fwidth(s)` sagt, wie viel `s` ein Pixel ist; ein
 * Stern ist ein kleiner Gauss-Punkt, die hellsten mit schwachem Hof. Wird
 * eine Schicht so dicht, dass ihre Zellen kaum noch Pixel breit sind,
 * blendet sie aus, statt als Rauschen zu flimmern.
 */
const galaxyPattern = (nebA, nebB, nebBoth, rim) => /* glsl */ `
  vec3 dir = normalize(-vViewPosition);
  vec2 s = dir.xy / max(-dir.z, 0.2);
  float n1 = awFbm(vec3(s * 2.6, uAwTime * 0.015));
  float n2 = awFbm(vec3(s * 5.5 + 7.3, 2.0 - uAwTime * 0.01));
  // Meist tiefes Blauschwarz; Nebel nur in Flecken, wo beide Felder hoch sind.
  vec3 neb = vec3(0.004, 0.003, 0.018);
  neb += ${vec3(nebA)} * pow(smoothstep(0.5, 0.82, n1), 1.5);
  neb += ${vec3(nebB)} * pow(smoothstep(0.55, 0.85, n2), 1.5);
  neb += ${vec3(nebBoth)} * smoothstep(0.62, 0.8, n1) * smoothstep(0.55, 0.8, n2) * 0.7;

  vec2 sPx = fwidth(s);
  float pxS = max(max(sPx.x, sPx.y), 1e-6);
  vec3 stars = vec3(0.0);
  for (int l = 0; l < 3; l++) {
    float scale = l == 0 ? 140.0 : (l == 1 ? 70.0 : 30.0);
    vec2 g = s * scale;
    vec2 cell = floor(g);
    float h = awHash(vec3(cell, float(l) * 13.0));
    if (h > (l == 0 ? 0.86 : (l == 1 ? 0.93 : 0.975))) {
      float cellPx = 1.0 / (pxS * scale);
      vec2 c = vec2(awHash(vec3(cell, 3.0)), awHash(vec3(cell, 5.0))) * 0.6 + 0.2;
      float d = length(fract(g) - c) * cellPx;
      float r = min(l == 0 ? 0.55 : (l == 1 ? 0.75 : 1.0), cellPx * 0.2);
      float core = exp(-d * d / (r * r + 0.2));
      float halo = l == 2 ? 0.22 * exp(-d / (r * 2.5)) : 0.0;
      float tw = 0.6 + 0.4 * sin(uAwTime * (1.5 + h * 3.0) + h * 40.0);
      float fade = smoothstep(1.5, 4.0, cellPx);
      vec3 tint = mix(vec3(0.78, 0.86, 1.0), vec3(1.0, 0.9, 0.8), awHash(vec3(cell, 7.0 + float(l))));
      stars += tint * (core + halo) * tw * fade * (l == 0 ? 0.9 : (l == 1 ? 1.2 : 1.7));
    }
  }
  float rim = pow(awRim(normal, vViewPosition), 3.0);
  totalEmissiveRadiance += neb + stars + ${vec3(rim)} * rim * 0.35;`;

/** Durchscheinend, Kanten hell, Linien laufen nach oben; die Farbe verlaeuft von den Fuessen zum Kopf. */
const hologramPattern = (feet, head) => /* glsl */ `
  float rim = pow(awRim(normal, vViewPosition), 1.6);
  float scan = 0.72 + 0.28 * step(0.5, fract(vAwWorld.y * 38.0 - uAwTime * 0.6));
  vec3 tone = mix(${vec3(feet)}, ${vec3(head)}, clamp(-vAwObj.z * 0.5 + 0.5, 0.0, 1.0));
  totalEmissiveRadiance += tone * (0.22 + rim * 1.1) * scan;
  diffuseColor.a = 0.34 + rim * 0.55;`;

/**
 * Perlmutt: ein warmes Weiss, das zu den Kanten hin zwischen Rosa und
 * Himmelblau schillert, je nach Blickwinkel und Stelle. Bewusst nur diese
 * zwei - ein voller Regenbogen brachte Gruen und Gelb, und das sah schmutzig aus.
 */
const pearlColor = (base) => /* glsl */ `
  // Weiche Wolken zwischen Rosa und Blau, wie Perlmutt im Licht.
  // (Eigener Name: Farb- und Leucht-Schnipsel landen im selben main().)
  float nacreN = awFbm(vAwObj * 1.6);
  vec3 nacre = mix(vec3(1.0, 0.86, 0.93), vec3(0.84, 0.91, 1.0), smoothstep(0.35, 0.65, nacreN));
  diffuseColor.rgb = pow(${vec3(base)} * nacre, vec3(2.2));`;
const pearlSheen = /* glsl */ `
  float rim = awRim(normal, vViewPosition);
  float n = awFbm(vAwObj * 1.6);
  vec3 irid = mix(vec3(1.0, 0.72, 0.88), vec3(0.7, 0.84, 1.0), 0.5 + 0.5 * sin(6.2832 * (rim * 0.9 + n * 0.5)));
  totalEmissiveRadiance += irid * (0.08 + 0.5 * pow(rim, 1.4));`;

// ── Die Looks ───────────────────────────────────────────────────────────────

const flat = (shell, seams) => () => ({
  shell: new MeshStandardMaterial(shell),
  seams: new MeshStandardMaterial(seams),
});

/** Roségold fuer Ringe und Schalen der Frau - Metall braucht das Studio. */
const ROSE_GOLD = 0xf4bfae;

/**
 * Jeder Look baut ZWEI Materialien: `shell` fuer die grossen Schalen, `seams`
 * fuer die Ringe an jedem Gelenk. Erst die zehn des Mannes, dann die zehn der
 * Frau, jeweils in derselben Reihenfolge - das Gegenstueck steht an derselben
 * Stelle ([LOOK_INFO]).
 */
export const FIGURE_LOOKS = {
  // ── Mann ──────────────────────────────────────────────────────────────────

  classic: flat(
    { color: 0xe9e6f2, roughness: 0.55 },
    { color: ACCENT, emissive: 0x2b1f6b, roughness: 0.38 }),

  graphite: flat(
    { color: 0x3b3e46, roughness: 0.6 },
    { color: WARM, emissive: 0x5a2a08, roughness: 0.4 }),

  mint: flat(
    { color: 0xb6ead6, roughness: 0.55 },
    { color: 0x0f766e, emissive: 0x04302c, roughness: 0.4 }),

  ocean: flat(
    { color: 0x1f3f95, roughness: 0.45 },
    { color: 0x22d3ee, emissive: 0x0b6b80, roughness: 0.35 }),

  //  Polierter weisser Stein mit grauen Adern, die Ringe Gold.
  marble: (u) => ({
    shell: shaded('marble', { roughness: 0.22 }, {
      vertex: true,
      color: marblePattern([0.96, 0.95, 0.93], [0.88, 0.87, 0.86], [0.45, 0.45, 0.48]),
    }, u),
    seams: new MeshStandardMaterial({
      color: 0xd9ad4a, metalness: 1, roughness: 0.3, envMap: studioTexture(),
    }),
  }),

  //  Dunkle Schalen, leuchtende Ringe, ein Hauch Magenta an den Kanten.
  neon: (u) => ({
    shell: shaded('neon', { color: 0x121218, roughness: 0.32, metalness: 0.2 }, {
      emissive: neonPattern([0.95, 0.1, 0.75]),
    }, u),
    seams: new MeshStandardMaterial({ color: 0x000000, emissive: 0x19e6ff, emissiveIntensity: 1.6 }),
  }),

  gold: () => ({
    shell: new MeshStandardMaterial({
      color: 0xe8b64c, metalness: 1, roughness: 0.26, envMap: studioTexture(),
    }),
    seams: new MeshStandardMaterial({ color: 0x1d1a16, roughness: 0.45 }),
  }),

  chrome: () => ({
    shell: new MeshStandardMaterial({
      color: 0xeceef2, metalness: 1, roughness: 0.08, envMap: studioTexture(),
    }),
    seams: new MeshStandardMaterial({ color: ACCENT, emissive: 0x2b1f6b, roughness: 0.38 }),
  }),

  //  Nebel und Sterne stehen FEST IM BILD, nicht auf der Haut: gerechnet aus
  //  der Blickrichtung. Die Figur ist ein Fenster in den Himmel dahinter, und
  //  wer sich bewegt, gleitet darueber - so sehen die Galaxy-Figuren aus
  //  Spielen aus.
  galaxy: (u) => ({
    shell: shaded('galaxy', { color: 0x07051a, roughness: 0.3 }, {
      emissive: galaxyPattern([0.30, 0.04, 0.48], [0.02, 0.12, 0.50], [0.90, 0.22, 0.62], [0.45, 0.4, 1.0]),
    }, u),
    seams: new MeshStandardMaterial({ color: 0x000000, emissive: 0xe879f9, emissiveIntensity: 1.2 }),
  }),

  hologram: (u) => ({
    shell: shaded('hologram', {
      color: 0x000000, roughness: 0.4, transparent: true, depthWrite: true,
    }, {
      vertex: true,
      emissive: hologramPattern([0.25, 0.85, 1.0], [0.25, 0.85, 1.0]),
    }, u),
    seams: new MeshStandardMaterial({
      color: 0x000000, emissive: 0x7df9ff, emissiveIntensity: 1.3, transparent: true, opacity: 0.85,
    }),
  }),

  // ── Frau ──────────────────────────────────────────────────────────────────

  //  Ihr Standard: glaenzendes Rot, schwarze Ringe.
  crimson: flat(
    { color: 0xc0172a, roughness: 0.32 },
    { color: 0x121114, roughness: 0.3 }),

  //  Schwarzlack mit roségoldenen Ringen.
  noir: () => ({
    shell: new MeshStandardMaterial({ color: 0x111015, roughness: 0.2 }),
    seams: new MeshStandardMaterial({
      color: ROSE_GOLD, metalness: 1, roughness: 0.3, envMap: studioTexture(),
    }),
  }),

  //  Zartes Rosa, tiefrosa Ringe.
  blush: flat(
    { color: 0xf2c4cc, roughness: 0.55 },
    { color: 0xc9577a, emissive: 0x3a0c1c, roughness: 0.4 }),

  //  Von den Fuessen zum Kopf: Violett, Rosa, Orange, Gelb.
  sunset: (u) => ({
    shell: shaded('sunset', { roughness: 0.5 }, {
      vertex: true,
      color: /* glsl */ `
        float t = clamp(-vAwObj.z * 0.52 + 0.5, 0.0, 1.0);
        vec3 c = mix(vec3(0.23, 0.03, 0.39), vec3(0.86, 0.15, 0.47), smoothstep(0.0, 0.45, t));
        c = mix(c, vec3(0.98, 0.45, 0.09), smoothstep(0.42, 0.8, t));
        c = mix(c, vec3(0.99, 0.88, 0.28), smoothstep(0.78, 1.0, t));
        diffuseColor.rgb = pow(c, vec3(2.2));`,
    }, u),
    seams: new MeshStandardMaterial({ color: 0xfff4e6, emissive: 0x3a2a1a, roughness: 0.35 }),
  }),

  //  Rosa Stein mit hellen Adern, die Ringe Roségold.
  rosequartz: (u) => ({
    shell: shaded('rosequartz', { roughness: 0.22 }, {
      vertex: true,
      color: marblePattern([0.93, 0.70, 0.76], [0.84, 0.58, 0.66], [1.0, 0.95, 0.96]),
    }, u),
    seams: new MeshStandardMaterial({
      color: ROSE_GOLD, metalness: 1, roughness: 0.3, envMap: studioTexture(),
    }),
  }),

  //  Dunkle Pflaume, pink leuchtende Ringe, violette Kanten.
  magenta: (u) => ({
    shell: shaded('magenta', { color: 0x160c16, roughness: 0.32, metalness: 0.2 }, {
      emissive: neonPattern([0.5, 0.3, 1.0]),
    }, u),
    seams: new MeshStandardMaterial({ color: 0x000000, emissive: 0xff2fb4, emissiveIntensity: 1.6 }),
  }),

  rosegold: () => ({
    shell: new MeshStandardMaterial({
      color: ROSE_GOLD, metalness: 1, roughness: 0.26, envMap: studioTexture(),
    }),
    seams: new MeshStandardMaterial({ color: 0x2a1a1e, roughness: 0.45 }),
  }),

  pearl: (u) => ({
    shell: shaded('pearl', { roughness: 0.25 }, {
      vertex: true,
      color: pearlColor([0.93, 0.9, 0.89]),
      emissive: pearlSheen,
    }, u),
    seams: new MeshStandardMaterial({
      color: 0xe8d6d0, metalness: 1, roughness: 0.25, envMap: studioTexture(),
    }),
  }),

  //  Galaxy in Rosa und Violett.
  nebula: (u) => ({
    shell: shaded('nebula', { color: 0x12061a, roughness: 0.3 }, {
      emissive: galaxyPattern([0.42, 0.04, 0.30], [0.22, 0.05, 0.42], [1.0, 0.42, 0.72], [0.95, 0.5, 0.95]),
    }, u),
    seams: new MeshStandardMaterial({ color: 0x000000, emissive: 0xf5d0fe, emissiveIntensity: 1.2 }),
  }),

  //  Hologramm von Rosa an den Fuessen zu Violett am Kopf.
  aurora: (u) => ({
    shell: shaded('aurora', {
      color: 0x000000, roughness: 0.4, transparent: true, depthWrite: true,
    }, {
      vertex: true,
      emissive: hologramPattern([1.0, 0.42, 0.8], [0.55, 0.45, 1.0]),
    }, u),
    seams: new MeshStandardMaterial({
      color: 0x000000, emissive: 0xffb3ec, emissiveIntensity: 1.3, transparent: true, opacity: 0.85,
    }),
  }),
};

/** Die Schluessel in der Reihenfolge der Auswahl. */
export const LOOK_KEYS = Object.keys(FIGURE_LOOKS);

export const DEFAULT_LOOK = 'classic';

/** Der Standard je Figur - der einzige Look, den Lite traegt. */
export const DEFAULT_LOOKS = { default: 'classic', female: 'crimson' };

/**
 * Die Paare: links der Look des Mannes, rechts sein Gegenstueck bei der Frau.
 * Dieselbe Tabelle steht im Server (`FigureLooks.kt`) und in der Workbench
 * (`AWMannequinLooks.cs`).
 */
const PAIRS = [
  ['classic', 'crimson'],
  ['graphite', 'noir'],
  ['mint', 'blush'],
  ['ocean', 'sunset'],
  ['marble', 'rosequartz'],
  ['neon', 'magenta'],
  ['gold', 'rosegold'],
  ['chrome', 'pearl'],
  ['galaxy', 'nebula'],
  ['hologram', 'aurora'],
];

/** Je Look: welcher Figur er gehoert und welcher ihm bei der anderen entspricht. */
export const LOOK_INFO = Object.fromEntries(PAIRS.flatMap(([male, female]) => [
  [male, { figure: 'default', twin: female }],
  [female, { figure: 'female', twin: male }],
]));

/** Looks, die es nicht mehr gibt, und was ein Clip mit ihnen je Figur traegt. */
const RETIRED = { coral: { default: 'classic', female: 'crimson' } };

/**
 * Der Look, den `figure` fuer `key` traegt: der Look selbst, wenn er ihr
 * gehoert, sonst sein Gegenstueck - und der Standard der Figur fuer einen
 * Schluessel, den es nicht (mehr) gibt.
 */
export function lookFor(figure, key) {
  const fig = DEFAULT_LOOKS[figure] ? figure : 'default';
  if (RETIRED[key]) return RETIRED[key][fig];
  const info = LOOK_INFO[key];
  if (!info) return DEFAULT_LOOKS[fig];
  return info.figure === fig ? key : info.twin;
}

/**
 * Die zwei Materialien eines Looks. `uniforms.time` ist ein `{ value }`, das
 * die Buehne je Bild fortschreibt ([MannequinStage.step]) - Galaxy funkelt,
 * die Hologramm-Linien laufen.
 */
export function lookMaterials(key, uniforms) {
  return (FIGURE_LOOKS[key] || FIGURE_LOOKS[DEFAULT_LOOK])(uniforms);
}
