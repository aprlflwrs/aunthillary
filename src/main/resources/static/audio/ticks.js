// Ant ticks, the sound of the Ants level: soft percussive clicks, more of them where more ants
// are about. There is no grid and no melody here on purpose: at this level of description the
// colony is "scattered noise". The ticks are a Poisson process (random, independent times)
// whose rate rises with the density of ants, and each tick is booked through the same lookahead
// scheduler as the notes.
//
// "Nearby" activity: by default the whole world counts. Phase 2 can call setFocus with the
// camera's view (a centre and radius in world units) so that only the ants on screen tick.

import { WORLD_SIZE } from '../net/protocol.js';

const FLAG_CARRYING_FOOD = 2;

/**
 * Reads an ant frame into a small summary: how many ants are in the focus circle, how many of
 * those carry food, and their density per 1000 square world units. One pass over typed arrays,
 * cheap enough for 100,000 ants at 30 frames a second.
 */
export function summarizeAnts(ants, focus = null) {
  const n = ants.count;
  if (!focus) {
    let carrying = 0;
    for (let i = 0; i < n; i++) if (ants.flags[i] & FLAG_CARRYING_FOOD) carrying++;
    return { count: n, carrying, density: (n / (WORLD_SIZE * WORLD_SIZE)) * 1000 };
  }
  // Compare in the frame's quantized units (0–65535) to avoid converting every ant.
  const q = 65535 / WORLD_SIZE;
  const cx = focus.x * q, cy = focus.y * q, r2 = (focus.radius * q) ** 2;
  let count = 0, carrying = 0;
  for (let i = 0; i < n; i++) {
    const dx = ants.x[i] - cx, dy = ants.y[i] - cy;
    if (dx * dx + dy * dy <= r2) {
      count++;
      if (ants.flags[i] & FLAG_CARRYING_FOOD) carrying++;
    }
  }
  return { count, carrying, density: (count / (Math.PI * focus.radius * focus.radius)) * 1000 };
}

/** Ticks per second for a summary: rises with density and levels off at tickRateMax. */
export function tickRate(summary, params) {
  if (!summary) return 0;
  return params.tickRateMax * (1 - Math.exp(-summary.density / params.tickDensityRef));
}

/**
 * Books ticks as a Poisson process. `book(from, to, rate)` returns the tick times in [from, to)
 * not booked before; successive windows continue the same process.
 */
export function createTickGenerator(random = Math.random) {
  let next = null;
  const gap = (rate) => -Math.log(1 - random()) / rate;
  return {
    book(from, to, rate) {
      if (!(rate > 0)) { next = null; return []; }
      if (next === null || next < from) next = from + gap(rate);
      const times = [];
      while (next < to) { times.push(next); next += gap(rate); }
      return times;
    },
  };
}
