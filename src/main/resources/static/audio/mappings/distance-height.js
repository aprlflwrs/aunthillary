// Distance and height: the walk outward from the nest climbs in register, and the ground under
// the trail sets the note within the register, so a trail climbing over a hill sounds like one.
//   - distance from the nest sets the register: an octave below the tonic near the hub, the
//     tonic's octave from 80 world units out, an octave above from 160, and so on;
//   - terrain height sets the scale degree within that: one step per 1.5 world units of height;
//   - loudness follows the pheromone strength, and high ground gets the brighter stop.
// Because the melody walks from the nest outward and loops, every phrase rises through the
// registers and drops back when it starts again.

import { stepsPerOctave } from '../scales.js';

/** World units of distance from the nest per octave of register. */
const UNITS_PER_OCTAVE = 80;
/** World units of terrain height per scale step. */
const HEIGHT_PER_DEGREE = 1.5;
/** Height (world units) treated as "high ground", for the timbre. */
const HIGH_GROUND = 12;

const clamp01 = (v) => Math.min(1, Math.max(0, v));

export default {
  id: 'distanceHeight',
  label: 'Distance and height',
  noteAt(p) {
    const octave = Math.floor(p.distanceFromNest / UNITS_PER_OCTAVE) - 1;
    return {
      degree: octave * stepsPerOctave(p.params.scale) + Math.round(p.height / HEIGHT_PER_DEGREE),
      velocity: 0.45 + 0.45 * clamp01(p.strength),
      length: 1,
      timbre: clamp01(p.height / HIGH_GROUND),
    };
  },
};
