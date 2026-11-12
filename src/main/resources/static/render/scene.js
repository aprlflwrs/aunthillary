// The Three.js view of the colony: one WebGLRenderer, one scene, two cameras (orthographic
// for the flat top-down view, perspective for the tilted one), and the frame loop.
//
// Scene graph (draw order in brackets; everything but the ground is transparent):
//   scene
//   ├── terrain.mesh    ground from the terrain frame, pheromone textures on it  [0]
//   ├── ants.mesh       one InstancedMesh, one instance per ant                  [1]
//   ├── markers.group   nest circle, food piles                                  [3]
//   │   └── ring        heading ring (Symbols level), lifted in the stacked view [4]
//   └── ribbons.group   one ribbon per voice, lifted in the stacked view         [2]
//
// Messages only store data and set flags; the frame loop (requestAnimationFrame, at the
// display's refresh rate) does the work: it writes the newest ant frame into the instance
// buffers if one has arrived since the last frame, applies the level and tilt, poses the
// camera, and renders. The server ticks at 30 Hz and the display usually runs faster, so most
// frames only re-render.

import * as THREE from 'three';
import { createAnts } from './ants.js';
import { createTerrain } from './terrain.js';
import { createRibbons } from './ribbons.js';
import { createMarkers } from './markers.js';
import { createCameraState, cameraPose, newPose } from './cameraRig.js';
import { levelWeights } from './levels.js';
import { heightAt } from './coords.js';
import { attachInput } from './input.js';

/** How far above the ground the stacked view lifts the ribbons; the ring goes twice as high. */
const STACK_GAP = 60;

/** The renderer's own settings. The tuning panel's View folder edits this object. */
export function defaultViewParams() {
  return { antSize: 1.6, ribbonWidth: 3, relief: 1, foodGain: 1, homeGain: 0.6 };
}

/** Copies a camera pose (from cameraRig.js) onto the matching three.js camera. */
export function applyPose(pose, ortho, perspective) {
  const cam = pose.perspective ? perspective : ortho;
  if (pose.perspective) {
    perspective.fov = pose.fov;
    perspective.aspect = pose.aspect;
  } else {
    ortho.left = -pose.halfW; ortho.right = pose.halfW;
    ortho.top = pose.halfH; ortho.bottom = -pose.halfH;
  }
  cam.near = pose.near;
  cam.far = pose.far;
  cam.position.set(pose.eyeX, pose.eyeY, pose.eyeZ);
  // The up vector must be set before lookAt, which builds the camera's frame from it.
  cam.up.set(pose.upX, pose.upY, pose.upZ);
  cam.lookAt(pose.eyeX - pose.backX, pose.eyeY - pose.backY, pose.eyeZ - pose.backZ);
  cam.updateProjectionMatrix();
  cam.updateMatrixWorld();
  return cam;
}

export function createRenderView(canvas, { onPlaceFood = () => {} } = {}) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.setClearColor(0x000000, 1);

  const scene = new THREE.Scene();
  const ortho = new THREE.OrthographicCamera(-1, 1, 1, -1, 1, 2000);
  const perspective = new THREE.PerspectiveCamera(40, 1, 1, 4000);

  const terrain = createTerrain();
  const ants = createAnts();
  const ribbons = createRibbons();
  const markers = createMarkers();
  scene.add(terrain.mesh, ants.mesh, markers.group, ribbons.group);

  const params = defaultViewParams();
  const camState = createCameraState();
  const pose = newPose();
  const weights = levelWeights(0);
  let level = 0;
  let activeCamera = ortho;

  // The latest message of each kind. The frame loop reads these.
  let antFrame = null;
  let antsDirty = false;
  let terrainFrame = null;
  let stateMessage = null;

  let running = true;
  let width = 1, height = 1;

  function resize() {
    width = canvas.clientWidth || window.innerWidth;
    height = canvas.clientHeight || window.innerHeight;
    renderer.setSize(width, height, false);
  }
  window.addEventListener('resize', resize);
  resize();

  const aspect = () => width / Math.max(1, height);
  const groundY = (x, y) => (terrainFrame ? heightAt(terrainFrame.heights, terrainFrame.n, x, y) * params.relief : 0);

  let framesDrawn = 0;

  function frame() {
    if (!running) return;
    requestAnimationFrame(frame);
    framesDrawn++;

    if (antsDirty && antFrame) {
      ants.update(antFrame, terrainFrame, params.relief, params.antSize);
      antsDirty = false;
    }

    levelWeights(level, weights);
    ants.setOpacity(weights.ants);
    terrain.setPheromoneOpacity(weights.pheromone);
    ribbons.setOpacity(weights.ribbons);
    markers.setRingOpacity(weights.symbols);

    // The stacked view: tilting lifts the ribbons and the ring onto planes above the ground.
    ribbons.group.position.y = camState.tilt * STACK_GAP;
    markers.setRingLift(camState.tilt * 2 * STACK_GAP);

    cameraPose(camState, aspect(), pose);
    activeCamera = applyPose(pose, ortho, perspective);
    renderer.render(scene, activeCamera);
  }
  requestAnimationFrame(frame);

  const input = attachInput(canvas, {
    camState,
    aspect,
    camera: () => activeCamera,
    onPlaceFood,
  });

  function refreshParams() {
    terrain.setRelief(params.relief);
    terrain.uniforms.foodGain.value = params.foodGain;
    terrain.uniforms.homeGain.value = params.homeGain;
    if (stateMessage) {
      ribbons.setVoices(stateMessage.voices, params.ribbonWidth, params.relief);
      markers.setState(stateMessage, groundY);
    }
    antsDirty = true;
  }

  return {
    params,
    /** Call after changing `params` (the View folder does this). */
    refreshParams,
    setAnts(message) { antFrame = message; antsDirty = true; },
    setPheromone(message) { terrain.setPheromone(message); },
    setTerrain(message) { terrainFrame = message; terrain.setTerrain(message); refreshParams(); },
    setState(message) {
      stateMessage = message;
      ribbons.setVoices(message.voices, params.ribbonWidth, params.relief);
      markers.setState(message, groundY);
    },
    /** The level of description, 0 (Ants) to 2 (Symbols). */
    setLevel(l) { level = l; },
    /** 0 = flat top-down view, 1 = fully tilted stacked view. */
    setTilt(t) { camState.tilt = Math.min(1, Math.max(0, t)); },
    /** How many frames were drawn since the last call (the status line shows it once a second). */
    takeFrameCount() { const n = framesDrawn; framesDrawn = 0; return n; },
    /** Shows or hides the view; while hidden the frame loop stops and costs nothing. */
    setVisible(visible) {
      canvas.style.display = visible ? '' : 'none';
      if (visible && !running) {
        running = true;
        resize();
        antsDirty = true;
        requestAnimationFrame(frame);
      }
      if (!visible) running = false;
      input.setEnabled(visible);
    },
  };
}
