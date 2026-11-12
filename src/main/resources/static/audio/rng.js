// A small seeded random generator for the client. The noise path source needs one so that a
// voice's random path is the same in every state message: the seed is the voice's ID.

/** mulberry32: a fast 32-bit generator. Returns a function giving numbers in [0, 1). */
export function seededRandom(seed) {
  let a = seed >>> 0;
  return function next() {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** A standard normal sample (mean 0, spread 1) from a uniform generator, by Box–Muller. */
export function normal(random) {
  const u = 1 - random(); // (0, 1], so the log is finite
  const v = random();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}
