export {
  ACESFilmicToneMapping, Box3, BufferAttribute, BufferGeometry, CanvasTexture, Color,
  DirectionalLight, Euler, Group, HemisphereLight, LineBasicMaterial, LineSegments, MathUtils,
  Matrix4, Mesh, MeshBasicMaterial, MeshStandardMaterial, Object3D, PerspectiveCamera,
  PlaneGeometry, Points, PointsMaterial, Quaternion, SRGBColorSpace, Scene, ShadowMaterial,
  Vector2, Vector3,
  WebGLRenderer,
} from 'three';
export { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
export { clone as cloneSkinned } from 'three/addons/utils/SkeletonUtils.js';

//  Fremde Figuren kommen meistens als .fbx - das ist das Format, in dem
//  Mixamo ausgibt und in dem ein gekauftes Modell im Ordner liegt. Der Loader
//  bringt fflate mit (ein FBX komprimiert seine Zahlenfelder) und NURBSCurve;
//  zusammen macht das den Buendel groesser, und das ist der Preis dafuer,
//  dass niemand erst durch Unity muss.
export { FBXLoader } from 'three/addons/loaders/FBXLoader.js';

//  Ein FBX nennt seine Texturen als DATEINAMEN daneben, und der Loader
//  holt sie dann brav vom Server - `BarbarianBodyTexture.png` gegen
//  unsere eigene Adresse, dreimal je Figur und jedes Mal 404. Neben
//  liegt hier nichts, also braucht `skeleton.js` einen eigenen Manager,
//  der diese Anfragen gar nicht erst stellt.
export { LoadingManager } from 'three';
