// Coordinate rules shared by the whole renderer, as pure functions (no three.js import, so the
// tests can run them under Node). The rules are in "World and coordinates" in ProjectPlan.md:
//
//   simulation (x, y, height): x east, y north, origin at the south-west corner, 0..512
//   three.js   (X, Y, Z):       X = x − 256,  Y = height,  Z = 256 − y
//
// So the world is centered on the three.js origin, the ground is the XZ plane, Y is up, and
// north is −Z. A camera looking down −Y with its up vector at −Z puts north at the top.

export const WORLD_SIZE = 512;
export const HALF_WORLD = WORLD_SIZE / 2;

export const simToX = (x) => x - HALF_WORLD;
export const simToZ = (y) => HALF_WORLD - y;
export const xToSim = (X) => X + HALF_WORLD;
export const zToSim = (Z) => HALF_WORLD - Z;

/** Ant frames quantize positions to u16 over the world: round(value / 512 × 65535). */
export const QUANT_TO_WORLD = WORLD_SIZE / 65535;

// Headings arrive as one byte: round(heading / 2π × 256) mod 256. A 256-entry table turns a
// byte straight into its cosine and sine, so the per-ant loop does no trigonometry.
export const HEADING_COS = new Float32Array(256);
export const HEADING_SIN = new Float32Array(256);
for (let i = 0; i < 256; i++) {
  const a = (i / 256) * 2 * Math.PI;
  HEADING_COS[i] = Math.cos(a);
  HEADING_SIN[i] = Math.sin(a);
}

/**
 * Writes one ant's 4×4 instance matrix into `out` at `offset` (16 floats, column-major, the
 * layout three.js keeps in InstancedMesh.instanceMatrix).
 *
 * The matrix is a uniform scale `s`, then a rotation about +Y by the simulation heading, then
 * a translation to (X, Y, Z). The ant geometry points along +X; rotating +X about +Y by angle
 * θ gives (cos θ, 0, −sin θ), which is the simulation direction (cos θ, sin θ) in three.js
 * coordinates (east is +X, north is −Z). So heading 0 faces east and π/2 faces north.
 */
export function writeAntMatrix(out, offset, X, Y, Z, cos, sin, s) {
  out[offset + 0] = cos * s;  out[offset + 1] = 0; out[offset + 2] = -sin * s; out[offset + 3] = 0;
  out[offset + 4] = 0;        out[offset + 5] = s; out[offset + 6] = 0;        out[offset + 7] = 0;
  out[offset + 8] = sin * s;  out[offset + 9] = 0; out[offset + 10] = cos * s; out[offset + 11] = 0;
  out[offset + 12] = X;       out[offset + 13] = Y; out[offset + 14] = Z;      out[offset + 15] = 1;
}

/**
 * Height of the ground at simulation point (x, y), from a terrain grid of n × n heights whose
 * rows run south to north. The ground mesh puts grid sample (row, col) at
 * x = col / (n − 1) × 512, y = row / (n − 1) × 512, so this bilinear lookup agrees with the mesh
 * (up to its choice of triangle diagonal).
 */
export function heightAt(heights, n, x, y) {
  if (!heights || n < 2) return 0;
  const scale = (n - 1) / WORLD_SIZE;
  let gx = x * scale;
  let gy = y * scale;
  if (gx < 0) gx = 0; else if (gx > n - 1) gx = n - 1;
  if (gy < 0) gy = 0; else if (gy > n - 1) gy = n - 1;
  const c0 = Math.min(gx | 0, n - 2);
  const r0 = Math.min(gy | 0, n - 2);
  const fx = gx - c0;
  const fy = gy - r0;
  const i = r0 * n + c0;
  const h00 = heights[i], h10 = heights[i + 1], h01 = heights[i + n], h11 = heights[i + n + 1];
  return (h00 * (1 - fx) + h10 * fx) * (1 - fy) + (h01 * (1 - fx) + h11 * fx) * fy;
}

export const clampToWorld = (v) => Math.min(WORLD_SIZE, Math.max(0, v));
