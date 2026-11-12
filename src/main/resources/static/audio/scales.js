// Scales, and the one place a scale degree becomes a pitch. Mappings choose degrees only; the
// engine calls degreeToMidi afterwards, the same way for every mapping and both path sources.

/** Semitones above the tonic for each step of one octave. */
export const SCALES = {
  pentatonic: [0, 2, 4, 7, 9],          // major pentatonic: no two notes clash
  minorPentatonic: [0, 3, 5, 7, 10],
  major: [0, 2, 4, 5, 7, 9, 11],
  minor: [0, 2, 3, 5, 7, 8, 10],        // natural minor
  dorian: [0, 2, 3, 5, 7, 9, 10],
};

/** The lowest and highest notes the voice samples cover (C2 to C7, see vendor/samples/). */
export const MIDI_LOW = 36;
export const MIDI_HIGH = 96;

/** Steps per octave of a scale, by name. Mappings that think in octaves (registers) use this. */
export function stepsPerOctave(scaleName) {
  return (SCALES[scaleName] ?? SCALES.pentatonic).length;
}

/**
 * Turns a scale degree into a MIDI note. Degree 0 is the tonic; each whole number is one step
 * up the scale, and degrees below 0 or past the top of the scale move into other octaves.
 * A note outside the samples' range is moved by whole octaves until it fits, so it keeps its
 * pitch class (and so stays in the scale).
 */
export function degreeToMidi(degree, scaleName, tonic, low = MIDI_LOW, high = MIDI_HIGH) {
  const intervals = SCALES[scaleName] ?? SCALES.pentatonic;
  const n = intervals.length;
  const d = Math.round(degree);
  const octave = Math.floor(d / n);
  const step = d - octave * n; // 0 … n-1, also for negative degrees
  let midi = tonic + 12 * octave + intervals[step];
  while (midi < low) midi += 12;
  while (midi > high) midi -= 12;
  return midi;
}
