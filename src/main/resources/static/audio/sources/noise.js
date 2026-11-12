// The noise control: each of the colony's voices is replaced by a random wandering path that is
// matched to it in everything the ants could not have contributed (see "Noise control" in the
// plan), so that whatever a listener hears differently between the two comes from the ants.
//
// Matched to the real voice:
//   - the same start point at the hub's edge, heading straight out from the nest;
//   - the same length;
//   - the same mean bending (radians of turning per world unit), measured after smoothing by
//     the same function for both sources (`bending` below);
//   - the same entry and fade times, because it keeps the voice's id, slot, bornTick and strength;
//   - the same pheromone strength profile along its length (point strength at distance s is the
//     real voice's strength at distance s), so a mapping that rests where a trail is thin rests
//     equally often on both;
//   - height read from the real terrain under the random path (when the engine has a terrain
//     message), since the ground is part of the world, not of the colony.
// Not matched, on purpose: where the path goes. It never shares a trunk, never heads for food,
// and does not curve around terrain.
//
// The random path is seeded by the voice's ID: the same voice gets the same sequence of random
// turns in every state message, and only the scale of those turns (to match the bending) and the
// length change as the real voice changes. So the noise path drifts as gently as the real one.

import { seededRandom, normal } from '../rng.js';
import { meanBending, resample, smooth, polylineLength, buildPath, sampleAt } from '../path.js';

/** Spacing of the generated points, matching the server's curves (about every 2 world units). */
export const SPACING = 2;
/**
 * Smoothing passes applied to the raw random walk. This stands in for the trail detector's
 * smoothing, which the client cannot run; Phase 2 should replace it with the same smoother.
 */
export const SMOOTHING_PASSES = 4;

const WORLD_SIZE = 512;
const SEED_SALT = 0x9e3779b9;

/** The one bending measure both sources are compared by: mean bending at an even spacing. */
export function bending(points) {
  return meanBending(resample(points, SPACING));
}

// Per voice ID, a lazily grown list of standard normal turn increments.
const incrementCache = new Map();
function increments(id, count) {
  let entry = incrementCache.get(id);
  if (!entry) {
    entry = { random: seededRandom((id * 2654435761) ^ SEED_SALT), z: [] };
    incrementCache.set(id, entry);
    if (incrementCache.size > 256) incrementCache.delete(incrementCache.keys().next().value);
  }
  while (entry.z.length < count) entry.z.push(normal(entry.random));
  return entry.z;
}

/**
 * A random walk from `start`, `SPACING` units per step, turning by `scale × z[i]` radians at
 * step i, bouncing off the world's edges; then smoothed and cut to exactly `length`.
 */
function walk(id, start, heading0, length, scale) {
  let steps = Math.ceil(length / SPACING) + 8;
  for (;;) {
    const z = increments(id, steps);
    const raw = [[start[0], start[1]]];
    let x = start[0], y = start[1], h = heading0;
    for (let i = 0; i < steps; i++) {
      h += scale * z[i];
      x += SPACING * Math.cos(h);
      y += SPACING * Math.sin(h);
      if (x < 0) { x = -x; h = Math.PI - h; }
      if (x > WORLD_SIZE) { x = 2 * WORLD_SIZE - x; h = Math.PI - h; }
      if (y < 0) { y = -y; h = -h; }
      if (y > WORLD_SIZE) { y = 2 * WORLD_SIZE - y; h = -h; }
      raw.push([x, y]);
    }
    const smoothed = smooth(raw, SMOOTHING_PASSES);
    // Smoothing shortens a bendy path; walk further until it is long enough to cut.
    if (polylineLength(smoothed) >= length || steps > 64 * (length / SPACING + 8)) {
      return resample(smoothed, SPACING, length);
    }
    steps *= 2;
  }
}

/**
 * A matched random path for one voice. `nest` is {x, y}; `heightAt(x, y)` reads the terrain,
 * or is null when the engine has no terrain yet.
 */
export function noisePathFor(voice, nest, heightAt) {
  const points = voice.points ?? [];
  if (points.length < 2) return points;
  const real = buildPath(points);
  const length = real.length;
  const target = bending(points);
  const start = points[0];
  const outward = Math.hypot(start[0] - nest.x, start[1] - nest.y) > 1e-6
    ? Math.atan2(start[1] - nest.y, start[0] - nest.x)
    : real.heading[0];

  // Find the turn scale whose smoothed path bends as much as the real one. Bending grows with
  // the scale, so a bracket by doubling and then bisection finds it.
  let best = walk(voice.id, start, outward, length, 0);
  if (target > 1e-6) {
    let lo = 0, hi = 0.05;
    while (bending(walk(voice.id, start, outward, length, hi)) < target && hi < 8) hi *= 2;
    for (let k = 0; k < 30; k++) {
      const mid = (lo + hi) / 2;
      if (bending(walk(voice.id, start, outward, length, mid)) < target) lo = mid; else hi = mid;
    }
    best = walk(voice.id, start, outward, length, (lo + hi) / 2);
  }

  // Height from the terrain under the new path (or the real profile if there is no terrain yet),
  // and strength from the real voice at the same distance along.
  let s = 0;
  return best.map((p, i) => {
    if (i > 0) s += Math.hypot(p[0] - best[i - 1][0], p[1] - best[i - 1][1]);
    const r = sampleAt(real, s);
    const height = heightAt ? heightAt(p[0], p[1]) : r.height;
    return [p[0], p[1], height, r.strength];
  });
}

export default {
  id: 'noise',
  label: 'Noise control',
  voices(state, { heightAt = null } = {}) {
    const nest = state.nest ?? { x: WORLD_SIZE / 2, y: WORLD_SIZE / 2 };
    return (state.voices ?? []).map((v) => ({ ...v, points: noisePathFor(v, nest, heightAt) }));
  },
};
