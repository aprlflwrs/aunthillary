// How strongly each layer is drawn at a level of description, as a pure function.
//
// The level runs continuously from 0 (Ants) through 1 (Signals) to 2 (Symbols). Following the
// table in the Visualization Plan:
//   Ants     every ant drawn; pheromone faint
//   Signals  ants fade; trails drawn as coloured ribbons, one colour per voice
//   Symbols  trails fade; the heading ring (with its lobes, in Phase 4) around the hill
//
// Each layer has its own curve, and between the named levels everything blends linearly, so
// moving the slider crossfades rather than switches. Nothing goes fully to zero except the
// layers that belong to one level only: the ants never vanish entirely (they are what the
// higher levels are made of), and the pheromone stays as a faint ground for the ribbons.

export const LEVEL_ANTS = 0;
export const LEVEL_SIGNALS = 1;
export const LEVEL_SYMBOLS = 2;

// Opacity of each layer at exactly Ants, Signals and Symbols.
const TABLE = {
  ants:      [1.0, 0.18, 0.08],
  pheromone: [0.35, 0.9, 0.45],
  ribbons:   [0.0, 1.0, 0.15],
  symbols:   [0.0, 0.0, 1.0],
};

/** Opacity (0–1) of each layer at `level`. Writes into `out` so the frame loop allocates nothing. */
export function levelWeights(level, out = { ants: 0, pheromone: 0, ribbons: 0, symbols: 0 }) {
  const l = Math.min(2, Math.max(0, Number.isFinite(level) ? level : 0));
  const i = l >= 1 ? 1 : 0;   // which segment: Ants→Signals or Signals→Symbols
  const f = l - i;
  for (const key in TABLE) {
    const row = TABLE[key];
    out[key] = row[i] + (row[i + 1] - row[i]) * f;
  }
  return out;
}

/** The nearest named level, for labelling the slider. */
export function levelName(level) {
  return ['Ants', 'Signals', 'Symbols'][Math.round(Math.min(2, Math.max(0, level)))];
}
