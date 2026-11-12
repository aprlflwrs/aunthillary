// Symbol tones: each symbol holds one sustained tone above the organ point, synthesized the same
// way (oscillators). Its direction on the ring picks the scale degree and its strength sets its
// loudness (mix.js symbolTone). Symbols are followed by id, like voices: a new id fades a tone
// in, a vanished one fades it out. A symbol whose trails are gone keeps its tone, which fades
// as the colony unlearns that direction: the colony's beliefs made audible.
//
// The plan schedules the Symbols level for Phase 4; this is small enough to build now, and the
// level mix keeps it silent until the slider moves past Signals.

import { symbolTone, midiToHz } from './mix.js';

/** Each tone's level at full strength, before the symbol volume and level mix. */
const LEVEL = 0.08;
const FADE_IN = 1.0;   // time constants, seconds
const FADE_OUT = 2.0;
const GLIDE = 0.3;

export function createSymbolTones(ctx, destination) {
  const tones = new Map(); // symbol id → { oscs, gain }

  function create(midi) {
    const gain = ctx.createGain();
    gain.gain.value = 0;
    gain.connect(destination);
    // A sine and a slightly detuned triangle: a soft tone with a slow shimmer.
    const oscs = [['sine', 0, 1], ['triangle', 5, 0.5]].map(([type, detune, level]) => {
      const osc = ctx.createOscillator();
      osc.type = type;
      osc.detune.value = detune;
      osc.frequency.value = midiToHz(midi);
      const g = ctx.createGain();
      g.gain.value = level;
      osc.connect(g).connect(gain);
      osc.start();
      return osc;
    });
    return { oscs, gain };
  }

  return {
    /** Follows the symbols of a state message. */
    update(symbols, params) {
      const t = ctx.currentTime;
      const seen = new Set();
      for (const symbol of symbols ?? []) {
        seen.add(symbol.id);
        const { midi, gain } = symbolTone(symbol, params);
        let tone = tones.get(symbol.id);
        if (!tone) { tone = create(midi); tones.set(symbol.id, tone); }
        for (const osc of tone.oscs) osc.frequency.setTargetAtTime(midiToHz(midi), t, GLIDE);
        tone.gain.gain.setTargetAtTime(LEVEL * gain, t, FADE_IN);
      }
      for (const [id, tone] of tones) {
        if (seen.has(id)) continue;
        tone.gain.gain.setTargetAtTime(0, t, FADE_OUT);
        for (const osc of tone.oscs) osc.stop(t + FADE_OUT * 5);
        tones.delete(id);
      }
    },
  };
}
