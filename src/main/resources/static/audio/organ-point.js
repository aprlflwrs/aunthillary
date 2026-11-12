// The organ point: a low sustained note that holds the key under everything, the device the
// Tortoise points out in the dialogue (pp. 334–335). It is synthesized with a few oscillators
// rather than sampled, because sampled notes decay and smplr's looping is still experimental.
//
//   tonic two octaves down (sine)  ─┐
//   tonic three octaves down (sine) ├──────────────────────────► out ─► destination
//   fifth above it (triangle)  ─┐   │
//   octave above (2 saws, ─────┴► upper gain ► lowpass filter ─┘
//     slightly detuned)
//
// Its loudness and richness follow the nest's food store (mix.js organPoint): a full store
// opens the filter and brings in the upper partials, an empty one leaves a dull, quiet hum.

import { midiToHz } from './mix.js';

/** Seconds for the drone to follow a change in the store (a slow glide, not a jump). */
const FOLLOW = 1.5;
/** The drone's overall level at full loudness, before the organ volume and level mix. */
const LEVEL = 0.22;

export function createOrganPoint(ctx, destination) {
  const out = ctx.createGain();
  out.gain.value = 0;
  out.connect(destination);

  const filter = ctx.createBiquadFilter();
  filter.type = 'lowpass';
  filter.Q.value = 0.7;
  filter.connect(out);
  const upper = ctx.createGain();
  upper.gain.value = 0;
  upper.connect(filter);

  const voices = [
    { type: 'sine', ratio: 1, gain: 1, to: out },
    { type: 'sine', ratio: 0.5, gain: 0.6, to: out },
    { type: 'triangle', ratio: 1.5, gain: 0.35, to: upper },
    { type: 'sawtooth', ratio: 2, gain: 0.12, to: upper, detune: -4 },
    { type: 'sawtooth', ratio: 2, gain: 0.12, to: upper, detune: 4 },
  ].map((v) => {
    const osc = ctx.createOscillator();
    osc.type = v.type;
    if (v.detune) osc.detune.value = v.detune;
    const g = ctx.createGain();
    g.gain.value = v.gain;
    osc.connect(g).connect(v.to);
    return { osc, ratio: v.ratio };
  });

  let started = false;
  return {
    start() {
      if (started) return;
      started = true;
      for (const v of voices) v.osc.start();
    },
    /** Glides to the tonic (MIDI note of the scale's tonic; the drone sits two octaves below). */
    setTonic(tonicMidi) {
      const f = midiToHz(tonicMidi - 24);
      for (const v of voices) {
        if (started) v.osc.frequency.setTargetAtTime(f * v.ratio, ctx.currentTime, 0.2);
        else v.osc.frequency.value = f * v.ratio;
      }
    },
    /** Applies mix.organPoint's settings ({ gain, cutoffHz, upperGain }). */
    set({ gain, cutoffHz, upperGain }) {
      const t = ctx.currentTime;
      out.gain.setTargetAtTime(LEVEL * gain, t, FOLLOW);
      filter.frequency.setTargetAtTime(cutoffHz, t, FOLLOW);
      upper.gain.setTargetAtTime(upperGain, t, FOLLOW);
    },
  };
}
