// Every ant, drawn in one draw call: a single InstancedMesh of a small flat arrow, with one
// instance per ant. Each instance has a 4×4 matrix (where the ant is and which way it faces)
// and a colour (from its flags).
//
// The mesh is created once, sized for MAX_ANTS, and its `count` is set to the live population
// each frame, so the population can change without reallocating anything. Both instance
// buffers are marked DynamicDrawUsage (they change every tick), and frustum culling is off:
// three.js would otherwise test the mesh's bounding sphere, which it computes once from
// whatever the instances were then, and the ants span the whole map anyway.
//
// Each ant is drawn at its latest position with no interpolation between ticks (see the
// Renderer bullet in ProjectPlan.md); at colony zoom an ant moves a pixel or two per tick.

import * as THREE from 'three';
import { FLAG_RETURNING, FLAG_CARRYING_FOOD, FLAG_SCOUT } from '../net/protocol.js';
import { QUANT_TO_WORLD, HEADING_COS, HEADING_SIN, simToX, simToZ, writeAntMatrix, heightAt } from './coords.js';

export const MAX_ANTS = 100_000;

// Colour for each of the 8 flag combinations, filled in below. Carrying food wins over
// everything (it is what makes a trail), then returning empty-handed, then scout.
const COLOR_DEFAULT = '#d9d3c7';   // outbound forager
const COLOR_SCOUT = '#7fd3ff';
const COLOR_RETURNING = '#b7a3e0'; // returning without food
const COLOR_FOOD = '#ffa94d';      // carrying food home (matches the debug view)

/** The colour (CSS hex) an ant with these flags is drawn in. */
export function antColorHex(flags) {
  if (flags & FLAG_CARRYING_FOOD) return COLOR_FOOD;
  if (flags & FLAG_RETURNING) return COLOR_RETURNING;
  if (flags & FLAG_SCOUT) return COLOR_SCOUT;
  return COLOR_DEFAULT;
}

export function createAnts() {
  // An arrow pointing along +X in the ground plane, about one world unit (one ant) long.
  // Its origin is near the middle of the body, so it turns about its centre.
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute([
    0.6, 0, 0,     -0.45, 0, -0.32,   -0.2, 0, 0,     // left half (north side when facing east)
    0.6, 0, 0,     -0.2, 0, 0,        -0.45, 0, 0.32, // right half
  ], 3));

  // The material's colour is white, so each ant shows exactly its instance colour. It is
  // transparent so the level slider can fade the ants; depthWrite is off so faded ants never
  // hide the ribbons drawn after them.
  const material = new THREE.MeshBasicMaterial({
    color: 0xffffff, transparent: true, depthWrite: false, side: THREE.DoubleSide,
  });

  const mesh = new THREE.InstancedMesh(geometry, material, MAX_ANTS);
  mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  // instanceColor must exist before the first render: three.js compiles the shader once,
  // and only includes per-instance colour if the attribute is there at that moment.
  mesh.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(MAX_ANTS * 3), 3);
  mesh.instanceColor.setUsage(THREE.DynamicDrawUsage);
  mesh.frustumCulled = false;
  mesh.count = 0;
  mesh.renderOrder = 1;

  // The 8 flag combinations as linear RGB, in one flat table. THREE.Color converts the sRGB
  // hex codes to the linear values the shader expects.
  const palette = new Float32Array(8 * 3);
  const c = new THREE.Color();
  for (let f = 0; f < 8; f++) {
    c.set(antColorHex(f));
    palette[f * 3] = c.r; palette[f * 3 + 1] = c.g; palette[f * 3 + 2] = c.b;
  }

  const matrices = mesh.instanceMatrix.array;
  const colors = mesh.instanceColor.array;

  /**
   * Writes one ant frame into the instance buffers. `terrain` is the latest terrain frame (or
   * null) so ants sit on the ground; `relief` and `size` come from the View settings.
   * Allocation-free: everything goes straight into the existing typed arrays.
   */
  function update(frame, terrain, relief, size) {
    const n = Math.min(frame.count, MAX_ANTS);
    const { x, y, heading, flags } = frame;
    const heights = terrain ? terrain.heights : null;
    const tn = terrain ? terrain.n : 0;
    const lift = 0.3; // just above the ground, so the ground never hides an ant
    for (let i = 0; i < n; i++) {
      const sx = x[i] * QUANT_TO_WORLD;
      const sy = y[i] * QUANT_TO_WORLD;
      const h = heights ? heightAt(heights, tn, sx, sy) * relief : 0;
      const hq = heading[i];
      writeAntMatrix(matrices, i * 16, simToX(sx), h + lift, simToZ(sy), HEADING_COS[hq], HEADING_SIN[hq], size);
      const p = (flags[i] & 7) * 3;
      colors[i * 3] = palette[p]; colors[i * 3 + 1] = palette[p + 1]; colors[i * 3 + 2] = palette[p + 2];
    }
    mesh.count = n;
    // Upload only the live part of each buffer, not all 100,000 slots.
    mesh.instanceMatrix.clearUpdateRanges();
    mesh.instanceMatrix.addUpdateRange(0, n * 16);
    mesh.instanceMatrix.needsUpdate = true;
    mesh.instanceColor.clearUpdateRanges();
    mesh.instanceColor.addUpdateRange(0, n * 3);
    mesh.instanceColor.needsUpdate = true;
  }

  function setOpacity(opacity) {
    material.opacity = opacity;
    mesh.visible = opacity > 0.001;
  }

  return { mesh, update, setOpacity };
}
