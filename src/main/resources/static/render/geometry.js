// Vertex data for the ground and the trail ribbons, as pure functions writing into typed
// arrays (no three.js import, so the tests can check them under Node). render/terrain.js and
// render/ribbons.js wrap these arrays in three.js buffers.

import { WORLD_SIZE, simToX, simToZ } from './coords.js';

/**
 * The ground: an n × n grid of vertices covering the whole world, one per terrain sample.
 * Sample (row, col) sits at x = col / (n − 1) × 512, y = row / (n − 1) × 512 (rows run south
 * to north, as in the terrain frame), raised to its height times `relief`.
 *
 * UVs are the simulation position divided by the world size: u runs west to east and v south
 * to north. The pheromone textures use the same row order (row 0 is south) and a DataTexture's
 * row 0 is v = 0, so the pheromone lines up with the world whatever the two grid sizes are.
 */
export function buildGround(n, heights, blocked, relief) {
  const positions = new Float32Array(n * n * 3);
  const uvs = new Float32Array(n * n * 2);
  const blockedOut = new Float32Array(n * n);
  for (let row = 0; row < n; row++) {
    for (let col = 0; col < n; col++) {
      const i = row * n + col;
      const x = (col / (n - 1)) * WORLD_SIZE;
      const y = (row / (n - 1)) * WORLD_SIZE;
      uvs[i * 2] = x / WORLD_SIZE;
      uvs[i * 2 + 1] = y / WORLD_SIZE;
      positions[i * 3] = simToX(x);
      positions[i * 3 + 2] = simToZ(y);
      blockedOut[i] = blocked ? blocked[i] : 0;
    }
  }
  setGroundHeights(positions, n, heights, relief);

  // Two triangles per grid square. With a = (row, col), b = east of a, c = north of a and
  // d = north-east, the triangles (a, b, c) and (b, d, c) wind counter-clockwise seen from
  // above, so their front faces point up (+Y).
  const index = new Uint32Array((n - 1) * (n - 1) * 6);
  let k = 0;
  for (let row = 0; row < n - 1; row++) {
    for (let col = 0; col < n - 1; col++) {
      const a = row * n + col, b = a + 1, c = a + n, d = c + 1;
      index[k++] = a; index[k++] = b; index[k++] = c;
      index[k++] = b; index[k++] = d; index[k++] = c;
    }
  }
  return { positions, uvs, blocked: blockedOut, index };
}

/** Rewrites only the heights (the Y of every vertex), for a new terrain frame of the same size. */
export function setGroundHeights(positions, n, heights, relief) {
  for (let i = 0; i < n * n; i++) positions[i * 3 + 1] = heights ? heights[i] * relief : 0;
}

/**
 * One trail ribbon: a flat strip `width` wide that follows a voice's points
 * ([x, y, height, strength] in simulation units), lifted `lift` above the ground.
 *
 * Each point becomes two vertices, one either side of the curve, offset along the curve's
 * normal in the ground plane. The normal comes from the direction between the point's
 * neighbours, which keeps the strip smooth at bends. Also writes each vertex's strength and
 * its fraction along the curve (0 at the hub, 1 at the food).
 *
 * Writes at most `maxPoints` points and returns how many it wrote (the strip then has
 * 2 × that many vertices).
 */
export function writeRibbon(points, width, lift, relief, maxPoints, positions, strengths, along) {
  const count = Math.min(points.length, maxPoints);
  if (count < 2) return 0;
  const half = width / 2;
  for (let i = 0; i < count; i++) {
    const p = points[i];
    const prev = points[i > 0 ? i - 1 : 0];
    const next = points[i < count - 1 ? i + 1 : count - 1];
    let dx = next[0] - prev[0];
    let dy = next[1] - prev[1];
    const len = Math.hypot(dx, dy) || 1;
    dx /= len; dy /= len;
    // Left of the direction of travel, in simulation coordinates: (−dy, dx).
    const lx = -dy * half, ly = dx * half;
    const Y = (p[2] ?? 0) * relief + lift;
    const v = i * 6;
    positions[v] = simToX(p[0] + lx);     positions[v + 1] = Y; positions[v + 2] = simToZ(p[1] + ly);
    positions[v + 3] = simToX(p[0] - lx); positions[v + 4] = Y; positions[v + 5] = simToZ(p[1] - ly);
    const s = p[3] ?? 1;
    strengths[i * 2] = s; strengths[i * 2 + 1] = s;
    const f = i / (count - 1);
    along[i * 2] = f; along[i * 2 + 1] = f;
  }
  return count;
}

/** Triangle indices for a ribbon of up to `maxPoints` points: two triangles per segment. */
export function ribbonIndex(maxPoints) {
  const index = new Uint32Array((maxPoints - 1) * 6);
  for (let i = 0, k = 0; i < maxPoints - 1; i++) {
    const a = i * 2, b = a + 1, c = a + 2, d = a + 3;
    index[k++] = a; index[k++] = b; index[k++] = c;
    index[k++] = b; index[k++] = d; index[k++] = c;
  }
  return index;
}
