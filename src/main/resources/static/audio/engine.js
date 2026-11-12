// The sound engine: the one entry point the rest of the client uses.
//
//   const engine = createSoundEngine({ panel });   // panel: the tuning panel (for its Sound folder)
//   joinButton.onclick = () => engine.start();     // audio needs a user gesture
//   onMessage: state → engine.onState(m), ants → engine.onAnts(m), terrain → engine.onTerrain(m)
//   levelSlider.oninput = () => engine.setLevel(value);   // 0 Ants … 1 Signals … 2 Symbols
//   engine.attachPianoRoll(canvas);
//
// The audio graph:
//
//   voice slot 0..3: 2 organ Samplers ─► voice GainNode (strength × volume × level) ─┐
//   ant ticks: woodblock Sampler ─► lowpass ─► ticks GainNode (volume × level) ──────┤
//   organ point oscillators ─► organ GainNode (volume × level) ──────────────────────┼─► master ─► compressor ─► destination
//   symbol tones oscillators ─► symbols GainNode (volume × level) ───────────────────┘
//
// Each voice's GainNode is its own output, so Phase 2 can route it into a Three.js
// PositionalAudio placed at its trail (detachVoiceOutput), and the level mix still applies to it.
// The logic that turns voices into notes is in conductor.js; this file is the Web Audio glue.

import { createConductor } from './conductor.js';
import { createLookaheadClock } from './scheduler.js';
import { CLIENT_PARAMS } from './params.js';
import { levelMix, voiceGain, organPoint } from './mix.js';
import { loadSamples, createVoiceInstrument, createTickInstrument, SAMPLE_GAIN } from './instruments.js';
import { createOrganPoint } from './organ-point.js';
import { createSymbolTones } from './symbol-tones.js';
import { createPianoRoll } from './piano-roll.js';
import { addSoundFolder } from './sound-panel.js';
import { MAX_VOICES } from './voices.js';
import { WORLD_SIZE } from '../net/protocol.js';

/** Time constant (seconds) for gain changes, so nothing clicks. */
const SMOOTH = 0.08;
/** Gain changes that follow the colony (a voice's strength) glide more slowly. */
const FOLLOW = 0.5;
/** The woodblock is short and loses its edge in the lowpass, so it gets extra gain (measured:
 * ticks alone peaked near 0.03 without it). */
const TICK_BOOST = 2;

/**
 * Creates the engine. `audioContext` should be the Three.js AudioListener's context once the
 * renderer exists (listener.context), so positional audio shares it; `destination` defaults to
 * the context's destination (pass listener.getInput() to go through the listener).
 */
export function createSoundEngine({ panel = null, audioContext = null, destination = null, params = {} } = {}) {
  const ctx = audioContext ?? new AudioContext({ latencyHint: 'interactive' });

  // ---- the graph ----
  const master = ctx.createGain();
  const compressor = ctx.createDynamicsCompressor(); // a safety net against four loud voices at once
  compressor.threshold.value = -12;
  compressor.ratio.value = 4;
  master.connect(compressor).connect(destination ?? ctx.destination);

  const bus = (to = master) => { const g = ctx.createGain(); g.gain.value = 0; g.connect(to); return g; };
  const voiceOuts = Array.from({ length: MAX_VOICES }, () => bus());
  const ticksBus = bus();
  const tickFilter = ctx.createBiquadFilter(); // takes the edge off the woodblock: "soft" ticks
  tickFilter.type = 'lowpass';
  tickFilter.frequency.value = 2500;
  tickFilter.connect(ticksBus);
  const organBus = bus();
  const symbolsBus = bus();

  const organ = createOrganPoint(ctx, organBus);
  const symbolTones = createSymbolTones(ctx, symbolsBus);

  // ---- instruments (loading starts at once; playing waits for start()) ----
  let voiceInstruments = null;
  let tickInstrument = null;
  const ready = loadSamples(ctx).then(async (samples) => {
    voiceInstruments = await Promise.all(voiceOuts.map((out) => createVoiceInstrument(ctx, out, samples)));
    tickInstrument = await createTickInstrument(ctx, tickFilter, samples);
  });
  ready.catch((e) => console.error('Sound engine: samples failed to load', e));

  // ---- the conductor: voices → notes ----
  const conductor = createConductor({
    params,
    // A failing instrument must not stop the clock, so errors are reported, not thrown.
    output: {
      play(record) { if (!record.muted) safely(() => voiceInstruments?.[record.slot]?.play(record)); },
      tick(t) { safely(() => tickInstrument?.play(t)); },
    },
  });
  const p = conductor.params;

  let level = 1;
  let lastState = null;
  let soundFolder = null;

  // ---- gains ----
  function applyGains(timeConstant = SMOOTH) {
    const t = ctx.currentTime;
    const mix = levelMix(level);
    master.gain.setTargetAtTime(hidden ? 0 : p.masterVolume, t, timeConstant);
    ticksBus.gain.setTargetAtTime(TICK_BOOST * SAMPLE_GAIN * p.tickVolume * mix.ticks, t, timeConstant);
    organBus.gain.setTargetAtTime(p.organVolume * mix.organ, t, timeConstant);
    symbolsBus.gain.setTargetAtTime(p.symbolVolume * mix.symbols, t, timeConstant);
    applyVoiceGains(timeConstant);
  }
  function applyVoiceGains(timeConstant = FOLLOW) {
    const t = ctx.currentTime;
    const mix = levelMix(level);
    const strengthBySlot = new Map(conductor.activeVoices().map((v) => [v.slot, v.strength]));
    voiceOuts.forEach((out, slot) => {
      const g = SAMPLE_GAIN * p.voiceVolume * mix.voices * voiceGain(strengthBySlot.get(slot) ?? 0, p.voiceFullStrength);
      out.gain.setTargetAtTime(g, t, timeConstant);
    });
  }

  // ---- the clock ----
  let hidden = false;
  const clock = createLookaheadClock({
    now: () => ctx.currentTime,
    onWake: (now) => conductor.wake(now),
    onHidden() { hidden = true; applyGains(0.2); },
    onVisible(now) { hidden = false; conductor.resync(now); applyGains(0.2); },
  });

  organ.setTonic(p.tonic);
  applyGains(0.01);

  let starting = null;

  const engine = {
    /** The AudioContext (shared with Three.js once Phase 2 passes the listener's context in). */
    context: ctx,

    /**
     * Starts the sound. Call it from a user gesture (the Join button): browsers keep audio
     * suspended until one. Resolves once the samples are loaded and the grid is running.
     */
    start() {
      starting ??= (async () => {
        await ctx.resume();
        await ready;
        organ.start();
        conductor.start(ctx.currentTime);
        clock.start();
      })();
      return starting;
    },

    /** A colony state message. */
    onState(state) {
      lastState = state;
      conductor.onState(state);
      applyVoiceGains();
      organ.set(organPoint(state.nest?.store, p.storeFull));
      symbolTones.update(state.symbols, p);
    },

    /** An ant frame: the density of ants sets the rate of the ticks heard at the Ants level. */
    onAnts(ants) { conductor.onAnts(ants); },

    /** A terrain frame: the noise path source reads the ground height under its random paths. */
    onTerrain(terrain) { conductor.setHeightSampler(heightSampler(terrain)); },

    /** The level of description, from 0 (Ants) through 1 (Signals) to 2 (Symbols). */
    setLevel(value) {
      level = Math.min(2, Math.max(0, Number(value) || 0));
      applyGains(0.15);
    },
    getLevel: () => level,

    /** Starts drawing the piano roll into a canvas. Returns { stop }. */
    attachPianoRoll(canvas) {
      return createPianoRoll(canvas, {
        // The time being heard lags the audio clock by the output latency.
        now: () => ctx.currentTime - (ctx.outputLatency || ctx.baseLatency || 0),
        notes: conductor.noteLog,
        seconds: () => p.rollSeconds,
      });
    },

    // ---- client parameters (for the Sound folder and presets) ----
    /** Every client parameter with its spec and current value, like the server's params message. */
    clientParams: () => CLIENT_PARAMS.map((s) => ({ ...s, value: p[s.key] })),
    getParams: () => ({ ...p }),
    setParam(key, value) {
      conductor.setParam(key, value);
      if (key === 'tonic') organ.setTonic(p.tonic);
      if (key === 'storeFull' && lastState) organ.set(organPoint(lastState.nest?.store, p.storeFull));
      if ((key === 'scale' || key === 'tonic') && lastState) symbolTones.update(lastState.symbols, p);
      applyGains();
      soundFolder?.refresh();
    },

    // ---- spatial audio hooks (Phase 2) ----
    /** The GainNode that carries one voice slot (0–3), after its strength, volume and level gains. */
    voiceOutput: (slot) => voiceOuts[slot],
    /**
     * Takes a voice's output off the master bus and returns it, for routing elsewhere. For a
     * Three.js PositionalAudio at the voice's trail:
     *   positional.setNodeSource(engine.detachVoiceOutput(slot));
     * (PositionalAudio connects the node to its panner, and the panner to the listener.)
     */
    detachVoiceOutput(slot) {
      voiceOuts[slot].disconnect();
      return voiceOuts[slot];
    },
    /** Puts a detached voice output back on the master bus. */
    reattachVoiceOutput(slot) {
      voiceOuts[slot].disconnect();
      voiceOuts[slot].connect(master);
    },
    /** Where on its trail a voice's latest note comes from ({ x, y, height }), or null. */
    voicePosition: (slot) => conductor.voicePosition(slot),
    /** Limits the ant ticks to ants near a point ({ x, y, radius } in world units), or null for all. */
    setFocus: (focus) => conductor.setFocus(focus),

    /** For the demo page and debugging: the conductor's live note log and active voices. */
    noteLog: conductor.noteLog,
    activeVoices: () => conductor.activeVoices(),
  };

  if (panel?.gui) soundFolder = addSoundFolder(panel.gui, engine);
  return engine;
}

let lastError = '';
function safely(fn) {
  try { fn(); } catch (e) {
    if (String(e) !== lastError) console.error('Sound engine:', e); // once per distinct error
    lastError = String(e);
  }
}

/** A bilinear height lookup over a terrain frame (rows south to north, columns west to east). */
function heightSampler(terrain) {
  const { n, heights } = terrain;
  const cell = WORLD_SIZE / n;
  const at = (c, r) => heights[Math.min(n - 1, Math.max(0, r)) * n + Math.min(n - 1, Math.max(0, c))];
  return (x, y) => {
    const fx = x / cell - 0.5, fy = y / cell - 0.5;
    const c = Math.floor(fx), r = Math.floor(fy);
    const u = fx - c, v = fy - r;
    return (at(c, r) * (1 - u) + at(c + 1, r) * u) * (1 - v) + (at(c, r + 1) * (1 - u) + at(c + 1, r + 1) * u) * v;
  };
}
