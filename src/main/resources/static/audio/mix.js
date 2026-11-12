// The level-of-description mix, and the other small sums that turn colony numbers into gains.
// All pure, so they are tested in Node; engine.js applies them to Web Audio gain nodes.
//
// The slider runs continuously from 0 (Ants) through 1 (Signals) to 2 (Symbols), and the mix
// crossfades with it, following the Visualization Plan table:
//   Ants     soft ticks from ant activity, with the trail voices only faintly underneath;
//   Signals  each trail's melody line; the ticks are gone;
//   Symbols  the full fugue over the symbol tones (including tones whose trails are gone).
// The organ point holds the key at every level, a little quieter at the Ants level.
// Crossfades use equal-power (sine and cosine) curves, so the total loudness stays even mid-way.

import { degreeToMidi, stepsPerOctave } from './scales.js';

/** How loud the voices still are at the Ants level, so the trails are faintly there. */
export const VOICES_AT_ANTS = 0.15;
/** How loud the organ point is at the Ants level. */
export const ORGAN_AT_ANTS = 0.5;

const clamp01 = (v) => Math.min(1, Math.max(0, v));

/** Gains (0–1) for each part of the mix at a level from 0 (Ants) to 2 (Symbols). */
export function levelMix(level) {
  const l = Math.min(2, Math.max(0, Number(level) || 0));
  const toSignals = clamp01(l);       // 0 at Ants, 1 at Signals and beyond
  const toSymbols = clamp01(l - 1);   // 0 up to Signals, 1 at Symbols
  return {
    ticks: Math.cos((Math.PI / 2) * toSignals),
    voices: VOICES_AT_ANTS + (1 - VOICES_AT_ANTS) * Math.sin((Math.PI / 2) * toSignals),
    symbols: Math.sin((Math.PI / 2) * toSymbols),
    organ: ORGAN_AT_ANTS + (1 - ORGAN_AT_ANTS) * toSignals,
  };
}

/** A voice's gain from its strength: it fades with its trail's pheromone. */
export function voiceGain(strength, fullStrength) {
  return clamp01((strength ?? 0) / fullStrength);
}

/**
 * The organ point's settings from the nest's food store: `amount` (0–1) of a full store sets
 * both its loudness (never quite silent, so the key is always held) and its richness (how much
 * of the upper partials passes the filter).
 */
export function organPoint(store, storeFull) {
  const amount = clamp01((store ?? 0) / storeFull);
  return {
    amount,
    gain: 0.35 + 0.65 * amount,
    cutoffHz: 180 + 2200 * amount * amount,
    upperGain: amount,
  };
}

/**
 * A symbol's tone: its direction on the ring picks a scale degree within one octave (east is
 * the tonic, going counterclockwise up the scale), played an octave below the tonic, above the
 * organ point; its strength sets the loudness.
 */
export function symbolTone(symbol, params) {
  const n = stepsPerOctave(params.scale);
  const turn = (((symbol.direction ?? 0) / (2 * Math.PI)) % 1 + 1) % 1; // 0–1 around the ring
  const degree = Math.round(turn * n) % n;
  return {
    degree,
    midi: degreeToMidi(degree - n, params.scale, params.tonic),
    gain: clamp01(symbol.strength ?? 0),
  };
}

export function midiToHz(midi) {
  return 440 * 2 ** ((midi - 69) / 12);
}
