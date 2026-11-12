// Shared fake data for the sound engine tests: voices shaped like the fake colony's (curves
// from the hub's edge, points every 2 world units), and a fake audio clock.

export const NEST = { x: 256, y: 256, hubRadius: 12, store: 40, population: 18000, moundHeight: 3 };

const ground = (x, y) => 2.5 * (Math.sin(x / 60) + Math.cos(y / 75) + 2) + 1.5 * Math.sin((x + y) / 33);

/**
 * Points every 2 units along a curve leaving the hub at `angle`, `length` long, bending by
 * `bend` radians per unit (constant curvature), optionally continuing from `trunk` points.
 */
export function curve({ angle, length, bend = 0, trunk = null, strength = 0.9 }) {
  const pts = trunk ? trunk.map((p) => [...p]) : [];
  let x, y, h;
  if (pts.length) {
    const a = pts[pts.length - 2], b = pts[pts.length - 1];
    [x, y] = b; h = Math.atan2(b[1] - a[1], b[0] - a[0]) + angle;
  } else {
    x = NEST.x + NEST.hubRadius * Math.cos(angle); y = NEST.y + NEST.hubRadius * Math.sin(angle); h = angle;
    pts.push([x, y, ground(x, y), strength]);
  }
  for (let s = 2; s <= length; s += 2) {
    h += bend * 2;
    x += 2 * Math.cos(h); y += 2 * Math.sin(h);
    pts.push([x, y, ground(x, y), strength * (0.8 + 0.2 * Math.sin(s / 25))]);
  }
  return pts;
}

export function voice(id, slot, points, { strength = 0.8, bornTick = 0, foodId = id } = {}) {
  return { id, slot, foodId, bornTick, strength, points };
}

/** A state with three voices; voice 2 shares voice 1's first 70 units, like the fake colony. */
export function fakeState(tick = 0, { drift = 0 } = {}) {
  const a = curve({ angle: 0.7, length: 180, bend: 0.002 + drift });
  const b = curve({ angle: 0.6, length: 100, bend: -0.004, trunk: a.slice(0, 36) });
  const c = curve({ angle: 3.6, length: 150, bend: 0.01 });
  return {
    type: 'state',
    tick,
    nest: NEST,
    food: [],
    voices: [voice(1, 0, a), voice(2, 1, b, { bornTick: 300 }), voice(3, 2, c, { bornTick: 600 })],
    symbols: [{ id: 1, direction: 0.7, width: 0.5, strength: 0.5, bornTick: 0, voiceIds: [1, 2] }],
    headings: new Array(16).fill(1 / 16),
  };
}

/** A fake audio clock that a test moves forward by hand. */
export function fakeClock(start = 0) {
  let t = start;
  return { now: () => t, advance: (dt) => { t += dt; return t; }, set: (v) => { t = v; } };
}

/** A small seeded generator for jittering the fake timer (independent of the engine's). */
export function lcg(seed) {
  let s = seed >>> 0;
  return () => ((s = (Math.imul(s, 1664525) + 1013904223) >>> 0) / 4294967296);
}
