// Camera maths, as pure functions on a small state object, so they can be tested under Node.
// render/scene.js copies the pose this computes onto the real three.js cameras.
//
// The state is what the viewer controls:
//   targetX, targetZ  the ground point (three.js coordinates) at the centre of the screen
//   viewHeight        how many world units fit from the bottom to the top of the screen there
//   tilt              0 = flat top-down view (orthographic), 1 = fully tilted (perspective)
//
// The camera's frame is built from the tilt angle φ (from straight down):
//   right = (1, 0, 0)                      east is always to the right
//   back  = (0, cos φ, sin φ)               from the target towards the camera
//   up    = back × right = (0, sin φ, −cos φ)
// At φ = 0 the camera looks down −Y with up = −Z, which is the top-down view in the plan
// (north at the top). Tilting swings the camera south of the target, looking north.
//
// Zoom and pan are written once, for both cameras, using "the ground point under the cursor":
// to zoom towards the cursor, find that point, change viewHeight, find it again, and move the
// target by the difference, so the point stays under the cursor. Panning moves the target so
// the point that was under the previous pointer position ends up under the current one.

import { WORLD_SIZE } from './coords.js';

export const MAX_TILT = (60 * Math.PI) / 180;
export const FOV_DEGREES = 40;
export const MIN_VIEW_HEIGHT = 6;      // about six ants tall: as close as it gets
export const MAX_VIEW_HEIGHT = 1400;
export const ORTHO_DISTANCE = 1000;           // how far above the target the orthographic camera sits

export function createCameraState() {
  return { targetX: 0, targetZ: 0, viewHeight: WORLD_SIZE * 1.08, tilt: 0 };
}

/** Is the perspective camera the one in use? Only the exactly-flat view is orthographic. */
export const isPerspective = (state) => state.tilt > 0.001;

/**
 * The camera pose for the current state: eye position, up vector, and the projection
 * numbers. `out` is reused so the per-frame call allocates nothing.
 */
export function cameraPose(state, aspect, out = newPose()) {
  const phi = state.tilt * MAX_TILT;
  const bx = 0, by = Math.cos(phi), bz = Math.sin(phi);
  out.perspective = isPerspective(state);
  out.halfH = state.viewHeight / 2;
  out.halfW = out.halfH * aspect;
  out.fov = FOV_DEGREES;
  // The perspective camera sits at the distance where the ground at the target shows exactly
  // viewHeight units top to bottom, so tilting does not jump the zoom.
  const tanHalf = Math.tan((FOV_DEGREES * Math.PI) / 360);
  const distance = out.perspective ? out.halfH / tanHalf : ORTHO_DISTANCE;
  out.eyeX = state.targetX + bx * distance;
  out.eyeY = by * distance;
  out.eyeZ = state.targetZ + bz * distance;
  out.upX = 0; out.upY = Math.sin(phi); out.upZ = -Math.cos(phi);
  out.backX = bx; out.backY = by; out.backZ = bz;
  out.tanHalf = tanHalf;
  out.aspect = aspect;
  out.near = out.perspective ? distance * 0.01 : 1;
  out.far = out.perspective ? distance * 4 + 2000 : ORTHO_DISTANCE * 2;
  return out;
}

export function newPose() {
  return {
    perspective: false, halfW: 1, halfH: 1, fov: FOV_DEGREES, aspect: 1, tanHalf: 1,
    eyeX: 0, eyeY: 0, eyeZ: 0, upX: 0, upY: 0, upZ: -1, backX: 0, backY: 1, backZ: 0,
    near: 1, far: 2000,
  };
}

/**
 * The point on the horizontal plane Y = groundY seen at normalized device coordinates
 * (nx, ny), each −1..1 with +ny at the top of the screen. Writes {X, Z} into `out` and
 * returns it, or returns null if that ray never reaches the ground (above the horizon).
 */
export function groundPointAt(state, aspect, nx, ny, out = { X: 0, Z: 0 }, groundY = 0) {
  const p = cameraPose(state, aspect, scratchPose);
  let ox, oy, oz, dx, dy, dz;
  if (p.perspective) {
    ox = p.eyeX; oy = p.eyeY; oz = p.eyeZ;
    const sx = nx * p.tanHalf * p.aspect, sy = ny * p.tanHalf;
    dx = -p.backX + sx;          // right is (1, 0, 0)
    dy = -p.backY + sy * p.upY;
    dz = -p.backZ + sy * p.upZ;
  } else {
    ox = p.eyeX + nx * p.halfW;
    oy = p.eyeY + ny * p.halfH * p.upY;
    oz = p.eyeZ + ny * p.halfH * p.upZ;
    dx = -p.backX; dy = -p.backY; dz = -p.backZ;
  }
  if (dy > -1e-6) return null;
  const t = (groundY - oy) / dy;
  out.X = ox + t * dx;
  out.Z = oz + t * dz;
  return out;
}
const scratchPose = newPose();

const p0 = { X: 0, Z: 0 };
const p1 = { X: 0, Z: 0 };

/** Zooms by `factor` (< 1 zooms in) keeping the ground point under (nx, ny) fixed on screen. */
export function zoomAt(state, aspect, nx, ny, factor) {
  const before = groundPointAt(state, aspect, nx, ny, p0);
  state.viewHeight = Math.min(MAX_VIEW_HEIGHT, Math.max(MIN_VIEW_HEIGHT, state.viewHeight * factor));
  const after = before && groundPointAt(state, aspect, nx, ny, p1);
  if (before && after) {
    state.targetX += before.X - after.X;
    state.targetZ += before.Z - after.Z;
  }
  clampTarget(state);
}

/** Pans so the ground point under (nx0, ny0) moves to (nx1, ny1): the map follows the pointer. */
export function panBy(state, aspect, nx0, ny0, nx1, ny1) {
  const a = groundPointAt(state, aspect, nx0, ny0, p0);
  const b = groundPointAt(state, aspect, nx1, ny1, p1);
  if (!a || !b) return;
  state.targetX += a.X - b.X;
  state.targetZ += a.Z - b.Z;
  clampTarget(state);
}

/** Keeps the centre of the view over (or near) the world, so it cannot be lost off-screen. */
function clampTarget(state) {
  const limit = WORLD_SIZE * 0.75;
  state.targetX = Math.min(limit, Math.max(-limit, state.targetX));
  state.targetZ = Math.min(limit, Math.max(-limit, state.targetZ));
}
