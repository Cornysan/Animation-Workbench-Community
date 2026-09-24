/**
 * Die Figur im Kopf der Startseite.
 *
 * ABSCHRIFT AUS DER DOKU-SITE, ZEILE FUER ZEILE. Das Original steht in
 * `docs-site/src/components/MannequinStage/scene.js` und laeuft dort seit dem
 * 2026-09-xx auf docs.playmations.com. Hier ist es dieselbe Datei mit genau
 * zwei Aenderungen: die beiden Importe zeigen auf unser Buendel statt auf
 * Docusaurus' `three`, und die `THREE.`-Praefixe sind weg, weil wir benannt
 * importieren. Sonst nichts.
 *
 * DIE KOMMENTARE BLEIBEN ENGLISCH, und das ist Absicht. Zwei Kopien laufen
 * auseinander, sobald jemand eine davon anfasst; solange diese hier Wort fuer
 * Wort die andere ist, zeigt ein `diff` gegen das Original genau, was sich
 * bewegt hat. Uebersetzte Kommentare haetten diesen Abgleich zerstoert - und
 * der ist hier mehr wert als eine einheitliche Sprache im Ordner.
 *
 * Geteilt werden koennen die beiden nicht: die Doku-Site ist Docusaurus mit
 * eigenem Buendel, das Portal liefert nackte ES-Module aus. Es gibt keinen
 * Ort, an dem beide dieselbe Datei lesen koennten.
 *
 * ALLES HIER IST PROZEDURAL. Es wird kein Clip abgespielt - die Figur atmet,
 * verlagert das Gewicht und sieht dem Zeiger nach. Das ist der Unterschied zu
 * dem, was hier frueher stand: ein herausgegriffener Clip aus dem Katalog
 * beantwortete eine Frage, die das Gitter darunter besser beantwortet. Das
 * Mannequin beantwortet eine andere - wie die Figur aussieht, auf der die
 * Clips laufen -, und das tut kein Kachelgitter.
 */

import {
  ACESFilmicToneMapping, Box3, CanvasTexture, DirectionalLight, Euler,
  Group, HemisphereLight, MathUtils, Mesh, MeshBasicMaterial,
  MeshStandardMaterial, PerspectiveCamera, PlaneGeometry, Quaternion,
  SRGBColorSpace, Scene, ShadowMaterial, Vector2, Vector3, WebGLRenderer,
  GLTFLoader,
} from './vendor/three.module.js';

const ACCENT = 0x8e77ff;

/* The two Unity materials, under the names the exporter carries over. They are
   swapped relative to what they cover: "Joints" is the big shell, "Main" the
   narrow rings at every articulation. Going by the asset name keeps the mapping
   checkable against the prefab rather than against a guess. */
const SEAMS = 'AW_Default_M_Main_URP';

const TAU = Math.PI * 2;

/* Unity's forward is +Z; the handedness flip in the exporter turns that into
   -Z, so untouched the mannequin shows the camera its back. */
const BASE_YAW = Math.PI;

/*
 * GLTFLoader runs every node name through PropertyBinding.sanitizeNodeName,
 * which DELETES `. [ ] : /`. The rig is Rigify, so every bone below the spine
 * is called something like DEF-spine.001 - and looking one up under its real
 * name finds nothing, silently. The names below are the ones in the prefab;
 * boneKey() is what makes them match again.
 */
const boneKey = (name) => name.replace(/[[\]./:]/g, '').replace(/\s/g, '_');

/*
 * The pose the figure stands in, as an offset from the prefab's own rest pose,
 * in degrees. The prefab rests with its arms half raised in front of the chest,
 * which reads as a zombie rather than as a model waiting for a pose - so the
 * arms come down and the elbows keep a little bend.
 */
const STANCE = new Map(
  Object.entries({
    'DEF-upper_arm.L': [58, 0, 0],
    'DEF-upper_arm.R': [58, 0, 0],
    'DEF-forearm.L': [-22, 0, 0],
    'DEF-forearm.R': [-22, 0, 0],
  }).map(([name, angles]) => [boneKey(name), angles]),
);

/* Everything the idle is made of, in degrees and seconds, in one place. The
   amplitudes are deliberately small: this has to read as a figure standing
   still and breathing, not as a clip playing. */
const IDLE = {
  breath: {period: 4.6, spine2: 1.2, spine3: -0.8, chest: 0.9, lift: 0.005},
  sway: {period: 9.0, hips: 1.5, spine1: -1.0, head: -0.8, shift: 0.014},
  arms: {period: 6.2, upper: 1.7, fore: 1.2},
  legs: {knee: 1.1},
  turn: {period: 26.0, amount: 8.0},
  look: {yaw: 18.0, pitch: 11.0, ease: 2.6},
};

const rad = (deg) => (deg * Math.PI) / 180;

/**
 * The stage: a ring in a pool of light, drawn once into a canvas so the
 * falloff costs nothing per frame. No grid - the floor under it
 * ([floorTexture]) runs out to the horizon instead, and a grid on it would
 * only be noise next to the headline.
 *
 * The fade has to finish well inside the texture. A plane seen at this angle
 * compresses its last stretch of depth into a handful of pixels, so a gradient
 * that is still at a tenth of its alpha near the border arrives on screen as a
 * hard horizontal line - the plane's far edge, drawn in.
 */
function groundTexture(size = 1024) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');
  const mid = size / 2;

  const pool = ctx.createRadialGradient(mid, mid, 0, mid, mid, size * 0.22);
  pool.addColorStop(0, 'rgba(78, 66, 130, 0.9)');
  pool.addColorStop(0.45, 'rgba(46, 39, 78, 0.6)');
  pool.addColorStop(1, 'rgba(20, 17, 32, 0)');
  ctx.fillStyle = pool;
  ctx.fillRect(0, 0, size, size);

  ctx.strokeStyle = 'rgba(196, 182, 255, 0.6)';
  ctx.lineWidth = Math.max(1.5, size / 340);
  ctx.beginPath();
  ctx.arc(mid, mid, size * 0.115, 0, TAU);
  ctx.stroke();

  const fade = ctx.createRadialGradient(mid, mid, 0, mid, mid, size * 0.23);
  fade.addColorStop(0, 'rgba(0, 0, 0, 1)');
  fade.addColorStop(0.52, 'rgba(0, 0, 0, 0.94)');
  fade.addColorStop(0.78, 'rgba(0, 0, 0, 0.4)');
  fade.addColorStop(1, 'rgba(0, 0, 0, 0)');
  ctx.globalCompositeOperation = 'destination-in';
  ctx.fillStyle = fade;
  ctx.fillRect(0, 0, size, size);

  const texture = new CanvasTexture(canvas);
  texture.colorSpace = SRGBColorSpace;
  texture.anisotropy = 4;
  return texture;
}

/**
 * The floor under everything: full near the figure, running out softly in the
 * distance. Where it fades into the page, the horizon is - a skybox without
 * one. White with the coverage in alpha; the colour comes from the page
 * (`--hero-floor`), because this stage stands on the page itself and the page
 * can be light or dark.
 *
 * Perspective squeezes the last metres into a thin strip just below the
 * horizon, so the fade sits far out: whatever blends away lands exactly where
 * floor and sky meet. The canvas edges fade out in CSS (`.hero-stage >
 * canvas`), or the floor would stop at them in a hard line.
 */
function floorTexture(size = 512) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');
  const mid = size / 2;

  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, size, size);

  /* Linear in the inverse of the distance, from a quarter of the radius out.
     On screen a floor point sits 1/distance below the horizon, so this is
     what makes the coverage rise evenly there - a fade that is even in metres
     gets its last third squeezed into ten pixels, and the horizon an edge. */
  const fade = ctx.createRadialGradient(mid, mid, 0, mid, mid, size * 0.48);
  fade.addColorStop(0, 'rgba(0, 0, 0, 1)');
  for (let i = 0; i <= 12; i++) {
    const r = 0.25 + (0.75 * i) / 12;
    fade.addColorStop(r, `rgba(0, 0, 0, ${((1 / r - 1) / 3).toFixed(3)})`);
  }
  ctx.globalCompositeOperation = 'destination-in';
  ctx.fillStyle = fade;
  ctx.fillRect(0, 0, size, size);

  return new CanvasTexture(canvas);
}

/** Radius of that floor, in metres. Far enough out that it fades just below
 *  the horizon rather than a hand's width under it; the camera's far plane
 *  has to reach past it. */
const FLOOR_RADIUS = 60;

export function createStage(canvas, options = {}) {
  const {modelUrl, onReady, onError} = options;

  const renderer = new WebGLRenderer({
    canvas,
    antialias: true,
    alpha: true,
    powerPreference: 'high-performance',
  });
  renderer.setClearAlpha(0);
  renderer.toneMapping = ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.15;
  // PCFShadowMap is the default and the only soft option left in three 186 -
  // setting PCFSoftShadowMap explicitly only earns a removal warning.
  renderer.shadowMap.enabled = true;

  const scene = new Scene();

  const camera = new PerspectiveCamera(30, 1, 0.1, 80);
  const AIM = new Vector3(0, 0.82, 0);
  /* Where the camera stands, as a direction from the aim point. The distance
     is not fixed: resize() works it out from the frame it has been given. */
  const EYE = new Vector3(1.16, 0.58, 2.84).normalize();
  /* What has to stay inside the frame, in metres: the FIGURE, with a little air
     over its head and floor under its feet. The pool of light is allowed to run
     off the left and right edges - it fades out there anyway - but the floor
     being cut off along the bottom shows as a hard line, so the height is what
     decides the distance on every stage that is not extremely narrow. */
  const FIT = {height: 2.35, width: 1.4};

  /* A warm key from the front, the brand violet as a rim from behind, and a
     cool bounce so the shadow side never reaches pure black. */
  scene.add(new HemisphereLight(0xb9aeff, 0x0b0a10, 1.0));

  const key = new DirectionalLight(0xfff4e8, 2.6);
  key.position.set(2.1, 4.9, 2.4);
  key.castShadow = true;
  key.shadow.mapSize.set(1024, 1024);
  key.shadow.radius = 4;
  key.shadow.bias = -0.0015;
  key.shadow.normalBias = 0.02;
  const shadowCam = key.shadow.camera;
  shadowCam.near = 1.5;
  shadowCam.far = 12;
  shadowCam.left = -1.6;
  shadowCam.right = 1.6;
  shadowCam.top = 1.6;
  shadowCam.bottom = -1.6;
  shadowCam.updateProjectionMatrix();
  scene.add(key);

  const rim = new DirectionalLight(ACCENT, 3.4);
  rim.position.set(-3.4, 2.2, -2.6);
  scene.add(rim);

  const fill = new DirectionalLight(0x6f7bb0, 0.9);
  fill.position.set(-2.4, 1.0, 2.8);
  scene.add(fill);

  /* The floor first, under everything, in the page's own floor colour. It
     follows the theme switch while the page is open. */
  const floorMap = floorTexture();
  const floorMaterial = new MeshBasicMaterial({
    map: floorMap,
    transparent: true,
    depthWrite: false,
    toneMapped: false,
  });
  const floor = new Mesh(new PlaneGeometry(FLOOR_RADIUS * 2, FLOOR_RADIUS * 2), floorMaterial);
  floor.rotation.x = -Math.PI / 2;
  floor.renderOrder = -1;
  scene.add(floor);

  const tintFloor = () => {
    const value = getComputedStyle(document.documentElement).getPropertyValue('--hero-floor').trim();
    floorMaterial.color.set(value || '#1f1c2b');
  };
  tintFloor();
  const themeWatch = typeof MutationObserver === 'function'
    ? new MutationObserver(() => {
      tintFloor();
      // With reduced motion there is no loop to pick the new colour up.
      if (!running && ready) frame();
    })
    : null;
  if (themeWatch) themeWatch.observe(document.documentElement, {attributes: true, attributeFilter: ['data-theme']});

  /* The pool of light is unlit so the ring keeps its own colour, and a
     shadow-only plane a hair above it does the contact. That plane must not
     reach past the shadow camera's footprint, or its border shows. */
  const groundMap = groundTexture();
  const ground = new Mesh(
    new PlaneGeometry(5.6, 5.6),
    new MeshBasicMaterial({
      map: groundMap,
      transparent: true,
      depthWrite: false,
      toneMapped: false,
    }),
  );
  ground.rotation.x = -Math.PI / 2;
  scene.add(ground);

  const contact = new Mesh(
    new PlaneGeometry(3.0, 3.0),
    new ShadowMaterial({opacity: 0.55}),
  );
  contact.rotation.x = -Math.PI / 2;
  contact.position.y = 0.002;
  contact.receiveShadow = true;
  contact.renderOrder = 1;
  scene.add(contact);

  /* State --------------------------------------------------------------- */

  const figure = new Group();
  figure.rotation.y = BASE_YAW;
  scene.add(figure);

  const bones = new Map(); // sanitised bone name -> {bone, stance}
  let ready = false;
  let disposed = false;
  let onScreen = true;
  let running = false;

  const pointer = new Vector2(0, 0);
  const aimed = new Vector2(0, 0);
  const reduceMotion =
    typeof matchMedia === 'function' &&
    matchMedia('(prefers-reduced-motion: reduce)').matches;

  const scratch = new Quaternion();
  const euler = new Euler();

  /** Rotate a bone off its stance, in its own local frame, in degrees. */
  function turn(name, x, y, z) {
    const entry = bones.get(boneKey(name));
    if (!entry) return;
    euler.set(rad(x), rad(y), rad(z));
    entry.bone.quaternion.copy(entry.stance).multiply(scratch.setFromEuler(euler));
  }

  function poseAt(time) {
    const breath = Math.sin((time / IDLE.breath.period) * TAU);
    const sway = Math.sin((time / IDLE.sway.period) * TAU);
    const swing = Math.sin((time / IDLE.arms.period) * TAU);

    turn('DEF-hips', 0, sway * 0.6, sway * IDLE.sway.hips);
    turn('DEF-spine.001', breath * 0.4, 0, sway * IDLE.sway.spine1);
    turn('DEF-spine.002', breath * IDLE.breath.spine2, 0, sway * -0.4);
    turn('DEF-spine.003', breath * IDLE.breath.spine3, 0, 0);

    turn('DEF-shoulder.L', breath * IDLE.breath.chest, 0, 0);
    turn('DEF-shoulder.R', breath * IDLE.breath.chest, 0, 0);
    turn('DEF-upper_arm.L', swing * IDLE.arms.upper, 0, breath * 0.7);
    turn('DEF-upper_arm.R', -swing * IDLE.arms.upper, 0, breath * -0.7);
    turn('DEF-forearm.L', swing * IDLE.arms.fore, 0, 0);
    turn('DEF-forearm.R', -swing * IDLE.arms.fore, 0, 0);

    // The knees take the weight shift, half a beat behind the hips.
    turn('DEF-shin.L', Math.max(0, sway) * IDLE.legs.knee, 0, 0);
    turn('DEF-shin.R', Math.max(0, -sway) * IDLE.legs.knee, 0, 0);

    turn(
      'DEF-neck',
      aimed.y * IDLE.look.pitch * 0.45,
      aimed.x * IDLE.look.yaw * 0.4,
      sway * IDLE.sway.head * 0.5,
    );
    turn(
      'DEF-head',
      aimed.y * IDLE.look.pitch * 0.55,
      aimed.x * IDLE.look.yaw * 0.6,
      sway * IDLE.sway.head * 0.5,
    );

    figure.position.x = sway * IDLE.sway.shift;
    figure.position.y = breath * IDLE.breath.lift;
    figure.rotation.y =
      BASE_YAW +
      rad(Math.sin((time / IDLE.turn.period) * TAU) * IDLE.turn.amount) +
      aimed.x * 0.16;
  }

  /* Load ----------------------------------------------------------------- */

  new GLTFLoader().load(
    modelUrl,
    (gltf) => {
      if (disposed) {
        return;
      }
      const model = gltf.scene;

      model.traverse((node) => {
        if (node.isMesh || node.isSkinnedMesh) {
          node.castShadow = true;
          node.receiveShadow = false;
          node.frustumCulled = false; // the skin is quantised; its own box lies
          const seam = node.material.name === SEAMS;
          node.material.dispose();
          node.material = seam
            ? new MeshStandardMaterial({
                color: ACCENT,
                emissive: 0x2b1f6b,
                roughness: 0.38,
                metalness: 0.0,
              })
            : new MeshStandardMaterial({
                color: 0xe9e6f2,
                roughness: 0.55,
                metalness: 0.0,
              });
        }
        if (node.isBone) {
          const stance = STANCE.get(node.name);
          const rest = node.quaternion.clone();
          if (stance) {
            euler.set(rad(stance[0]), rad(stance[1]), rad(stance[2]));
            rest.multiply(new Quaternion().setFromEuler(euler));
            node.quaternion.copy(rest);
          }
          bones.set(node.name, {bone: node, stance: rest});
        }
      });

      figure.add(model);

      // Stand it on the floor and centre it, whatever height the prefab's own
      // root happens to sit at.
      const box = new Box3().setFromObject(model);
      model.position.y -= box.min.y;
      model.position.x -= (box.min.x + box.max.x) / 2;
      model.position.z -= (box.min.z + box.max.z) / 2;

      ready = true;
      if (onReady) onReady({height: box.max.y - box.min.y, bones: bones.size});
    },
    undefined,
    (error) => {
      if (onError) onError(error);
    },
  );

  /* Frame ----------------------------------------------------------------- */

  function resize() {
    const parent = canvas.parentElement;
    if (!parent) return;
    const w = Math.max(1, parent.clientWidth);
    const h = Math.max(1, parent.clientHeight);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    const half = Math.tan(MathUtils.degToRad(camera.fov) / 2);
    const distance = Math.max(
      FIT.height / 2 / half,
      FIT.width / 2 / half / camera.aspect,
    );
    camera.position.copy(EYE).multiplyScalar(distance).add(AIM);
    camera.lookAt(AIM);
    camera.updateProjectionMatrix();
    // With reduced motion there is no loop to pick the new frame up.
    if (!running && ready) frame();
  }

  const observer =
    typeof ResizeObserver === 'function' ? new ResizeObserver(resize) : null;
  if (observer && canvas.parentElement) observer.observe(canvas.parentElement);
  resize();

  function onPointerMove(event) {
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    pointer.x = MathUtils.clamp(
      ((event.clientX - rect.left) / rect.width) * 2 - 1,
      -1.5,
      1.5,
    );
    pointer.y = MathUtils.clamp(
      -(((event.clientY - rect.top) / rect.height) * 2 - 1),
      -1.5,
      1.5,
    );
  }
  window.addEventListener('pointermove', onPointerMove, {passive: true});

  let last = 0;
  function frame() {
    if (disposed) return;
    const now = performance.now() / 1000;
    const dt = last ? Math.min(now - last, 0.1) : 0;
    last = now;
    if (ready) {
      // Reduced motion means motionless, not slower: the figure takes its
      // stance, holds it, ignores the cursor, and the loop shuts down after
      // the one frame it needs.
      if (reduceMotion) {
        poseAt(0);
        renderer.render(scene, camera);
        stop();
        return;
      }
      aimed.lerp(pointer, 1 - Math.exp(-IDLE.look.ease * dt));
      poseAt(now);
    }
    renderer.render(scene, camera);
  }

  function start() {
    if (disposed || running || !onScreen || document.hidden) return;
    running = true;
    last = 0;
    renderer.setAnimationLoop(frame);
  }

  function stop() {
    if (!running) return;
    running = false;
    renderer.setAnimationLoop(null);
  }

  function onVisibility() {
    if (document.hidden) stop();
    else start();
  }
  document.addEventListener('visibilitychange', onVisibility);

  start();

  return {
    /** Called by the IntersectionObserver upstairs: a stage nobody is looking
        at should not be asking for frames. */
    setOnScreen(next) {
      onScreen = next;
      if (next) start();
      else stop();
    },
    resize,
    debug: {scene, camera, renderer, figure, bones, IDLE},
    dispose() {
      disposed = true;
      stop();
      window.removeEventListener('pointermove', onPointerMove);
      document.removeEventListener('visibilitychange', onVisibility);
      if (observer) observer.disconnect();
      if (themeWatch) themeWatch.disconnect();
      scene.traverse((node) => {
        if (node.geometry) node.geometry.dispose();
        if (node.material) {
          const list = Array.isArray(node.material) ? node.material : [node.material];
          list.forEach((m) => m.dispose());
        }
      });
      groundMap.dispose();
      floorMap.dispose();
      renderer.dispose();
    },
  };
}
