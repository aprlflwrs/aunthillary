// Compass position (the original design): where the point is in the world sets the note.
//   - north–south position (y) sets the scale degree: one step up the scale every 24 world
//     units north of the world's middle, one step down every 24 units south;
//   - east–west position (x) sets timbre: mellow stops in the west, brighter reed stops in the east;
//   - terrain height sets loudness and note length: high ground is louder and holds notes longer.
// Its known weakness: a straight trail's melody is set almost entirely by its direction, so a
// trail running due east repeats one note.

const WORLD_SIZE = 512;
/** World units north per scale step. */
const UNITS_PER_DEGREE = 24;
/** Height (world units) treated as "high ground"; the fake colony's hills reach about 12. */
const HIGH_GROUND = 12;

const clamp01 = (v) => Math.min(1, Math.max(0, v));

export default {
  id: 'compass',
  label: 'Compass position',
  noteAt(p) {
    const high = clamp01(p.height / HIGH_GROUND);
    return {
      degree: Math.round((p.y - WORLD_SIZE / 2) / UNITS_PER_DEGREE),
      velocity: 0.4 + 0.6 * high,
      length: 1 + Math.round(2 * high), // 1 to 3 eighth notes
      timbre: clamp01(p.x / WORLD_SIZE),
    };
  },
};
