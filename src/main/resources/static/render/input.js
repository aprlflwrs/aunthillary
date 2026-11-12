// Mouse input on the 3D view: scroll zooms towards the cursor, dragging pans, and a click
// (a press and release without moving more than DRAG_THRESHOLD pixels) places food at the
// world point under the cursor.
//
// Zoom and pan change the camera state through cameraRig.js. A click is turned into a world
// point by casting a ray from the active camera through the cursor and intersecting it with
// the ground plane (Y = 0). That ignores the terrain's few units of height, which is a
// fraction of a world unit of error in the top-down view. Intersecting the 100,000-instance
// ant mesh (raycaster.intersectObjects(scene)) is deliberately avoided.

import * as THREE from 'three';
import { zoomAt, panBy } from './cameraRig.js';
import { xToSim, zToSim, WORLD_SIZE } from './coords.js';

const DRAG_THRESHOLD = 4; // pixels

export function attachInput(canvas, { camState, aspect, camera, onPlaceFood }) {
  let enabled = true;
  let press = null; // { id, x, y, lastX, lastY, dragging } while a button is down

  const raycaster = new THREE.Raycaster();
  const ndc = new THREE.Vector2();
  const ground = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
  const hit = new THREE.Vector3();

  const toNdcX = (clientX, rect) => ((clientX - rect.left) / rect.width) * 2 - 1;
  const toNdcY = (clientY, rect) => -(((clientY - rect.top) / rect.height) * 2 - 1);

  canvas.addEventListener('wheel', (event) => {
    if (!enabled) return;
    event.preventDefault();
    const rect = canvas.getBoundingClientRect();
    // deltaMode 1 is lines (Firefox with a mouse wheel), 0 is pixels (trackpads, Chrome).
    const delta = event.deltaY * (event.deltaMode === 1 ? 16 : 1);
    const factor = Math.exp(delta * 0.0015);
    zoomAt(camState, aspect(), toNdcX(event.clientX, rect), toNdcY(event.clientY, rect), factor);
  }, { passive: false });

  canvas.addEventListener('pointerdown', (event) => {
    if (!enabled || press) return;
    press = { id: event.pointerId, x: event.clientX, y: event.clientY, lastX: event.clientX, lastY: event.clientY, dragging: false, button: event.button };
    canvas.setPointerCapture(event.pointerId);
  });

  canvas.addEventListener('pointermove', (event) => {
    if (!press || event.pointerId !== press.id) return;
    if (!press.dragging && Math.hypot(event.clientX - press.x, event.clientY - press.y) > DRAG_THRESHOLD) {
      press.dragging = true;
      canvas.style.cursor = 'grabbing';
    }
    if (press.dragging) {
      const rect = canvas.getBoundingClientRect();
      panBy(camState, aspect(),
        toNdcX(press.lastX, rect), toNdcY(press.lastY, rect),
        toNdcX(event.clientX, rect), toNdcY(event.clientY, rect));
      press.lastX = event.clientX;
      press.lastY = event.clientY;
    }
  });

  function release(event, cancelled) {
    if (!press || event.pointerId !== press.id) return;
    const wasClick = !press.dragging && press.button === 0 && !cancelled;
    press = null;
    canvas.style.cursor = '';
    if (wasClick) placeFoodAt(event);
  }
  canvas.addEventListener('pointerup', (event) => release(event, false));
  canvas.addEventListener('pointercancel', (event) => release(event, true));
  canvas.addEventListener('contextmenu', (event) => event.preventDefault()); // right-drag pans

  function placeFoodAt(event) {
    const rect = canvas.getBoundingClientRect();
    ndc.set(toNdcX(event.clientX, rect), toNdcY(event.clientY, rect));
    raycaster.setFromCamera(ndc, camera());
    if (!raycaster.ray.intersectPlane(ground, hit)) return;
    const x = xToSim(hit.x);
    const y = zToSim(hit.z);
    if (x < 0 || x > WORLD_SIZE || y < 0 || y > WORLD_SIZE) return; // off the map
    onPlaceFood(x, y);
  }

  return {
    setEnabled(e) { enabled = e; if (!e) press = null; },
  };
}
