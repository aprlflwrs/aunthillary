// The conductor turns voices into notes. It is the engine's logic with no Web Audio in it:
// it takes the audio clock's time as an argument and hands every note it books to an `output`
// object, so Node tests can run it against a fake clock and a fake output (the browser engine
// in engine.js supplies the real one).
//
// How a voice becomes notes, on every step k of the 8th-note grid:
//   1. The path source (colony or noise) has given the voice a path; it was built into a
//      sampled curve when the state message arrived.
//   2. The voice's own step is j = (k − entryStep) mod N, where N = floor(length / d) + 1 is how
//      many 8th notes one walk of the path takes at d world units per 8th note. So the voice
//      walks its path from the nest end outward, d units per 8th note, and loops back.
//   3. The point at distance s = j × d along the path is sampled (position, height, heading,
//      turning, curvature, strength) and handed to the selected mapping, which returns a scale
//      degree, velocity, length and timbre, or a rest.
//   4. The engine applies the scale (degreeToMidi) and the grid: the note starts exactly on
//      step k and lasts `length` 8th notes. A voice is monophonic, so while a note holds, the
//      steps it covers play nothing; a hold is cut at the end of the path, so every walk of an
//      unchanged path plays the same phrase.
//   5. The resulting note record goes into the note log (which the piano roll draws) and to the
//      output (which plays it): one object, so what is drawn is what is played.
//
// Nothing here looks at a voice's id or slot to choose a note. Voices whose paths share a trunk
// therefore play the same opening, and diverge where their paths split.

import { createGridScheduler, LOOKAHEAD } from './scheduler.js';
import { createVoiceTracker } from './voices.js';
import { buildPath, sampleAt } from './path.js';
import { degreeToMidi } from './scales.js';
import { MAPPINGS } from './mappings/index.js';
import { SOURCES } from './sources/index.js';
import { defaultParams, coerceParam, eighthSeconds } from './params.js';
import { summarizeAnts, tickRate, createTickGenerator } from './ticks.js';

/** The longest note a mapping may ask for, in 8th notes. */
export const MAX_LENGTH = 8;
/** Notes sound for this share of their length, so repeated notes are heard as separate notes. */
export const LEGATO = 0.92;
/** The grid's first step is this long after start(), so the first note is never booked late. */
export const START_DELAY = 0.05;

const mod = (a, n) => ((a % n) + n) % n;
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/** How many 8th notes one walk along a path of `length` takes at `stepDistance` per 8th note. */
export function stepsPerLoop(length, stepDistance) {
  return Math.max(1, Math.floor(length / stepDistance) + 1);
}

/**
 * The input a mapping receives for one point (the `p` of the plan's mapping interface).
 * `voice` deliberately leaves out id, slot and foodId, so a mapping cannot tell voices apart.
 */
export function mappingInput({ voice, path, voiceStep, stepDistance, nest, params }) {
  const at = sampleAt(path, voiceStep * stepDistance);
  return Object.freeze({
    voice: Object.freeze({ strength: voice.strength ?? 0, bornTick: voice.bornTick ?? 0, length: path.length }),
    step: voiceStep,
    s: at.s,
    x: at.x, y: at.y, height: at.height,
    heading: at.heading, curvature: at.curvature, turning: at.turning,
    strength: at.strength,
    distanceFromNest: Math.hypot(at.x - (nest?.x ?? 256), at.y - (nest?.y ?? 256)),
    colony: Object.freeze({ store: nest?.store ?? 0, population: nest?.population ?? 0 }),
    params,
  });
}

export function createConductor({ output, params: initial = {}, random = Math.random } = {}) {
  const params = { ...defaultParams() };
  for (const [k, v] of Object.entries(initial)) { const c = coerceParam(k, v); if (c !== undefined) params[k] = c; }
  let frozenParams = Object.freeze({ ...params });

  const tracker = createVoiceTracker();
  const paths = new Map();       // voice id → built path
  const holdUntil = new Map();   // voice id → first grid step after its current note
  const lastBySlot = new Map();  // slot → last note record
  const noteLog = [];            // every booked note, oldest first (pruned to rollSeconds)
  const ticks = createTickGenerator(random);
  let grid = null;
  let lastState = null;
  let heightAt = null;
  let antSummary = null;
  let antFocus = null;
  let seq = 0;

  const stepDuration = () => eighthSeconds(params.tempo);

  /** Runs the path source over the last state, follows entries and fades, and builds the paths. */
  function rebuild() {
    if (!lastState) return { entered: [], faded: [], exited: [] };
    const source = SOURCES[params.source] ?? SOURCES.colony;
    const voices = source.voices(lastState, { heightAt });
    const changes = tracker.update(voices, {
      tick: lastState.tick ?? 0,
      nextStep: grid ? grid.nextStep : 0,
      stepDuration: grid ? grid.stepDuration : stepDuration(),
    });
    for (const id of changes.exited) { paths.delete(id); holdUntil.delete(id); }
    for (const rec of tracker.active.values()) paths.set(rec.id, buildPath(rec.voice.points));
    return changes;
  }

  /** Books the notes of every voice for grid step k at audio time `time`. */
  function bookStep(k, time) {
    const d = params.stepDistance;
    const mapping = MAPPINGS[params.mapping] ?? MAPPINGS.turning;
    const nest = lastState?.nest;
    const voices = [...tracker.active.values()].sort((a, b) => a.slot - b.slot);
    for (const rec of voices) {
      const path = paths.get(rec.id);
      if (!path || path.n < 2) continue;
      const loop = stepsPerLoop(path.length, d);
      const voiceStep = mod(k - rec.entryStep, loop);
      if (voiceStep === 0) holdUntil.delete(rec.id); // a new walk of the path: drop any hold
      if (k < (holdUntil.get(rec.id) ?? -Infinity)) continue;

      const p = mappingInput({ voice: rec.voice, path, voiceStep, stepDistance: d, nest, params: frozenParams });
      const note = mapping.noteAt(p);
      if (!note) continue;
      const length = Math.min(clamp(Math.round(note.length ?? 1), 1, MAX_LENGTH), loop - voiceStep);
      holdUntil.set(rec.id, k + length);
      const degree = Math.round(note.degree ?? 0);
      const record = {
        seq: seq++,
        voiceId: rec.id,
        slot: rec.slot,
        step: k,
        voiceStep,
        time,
        duration: length * grid.stepDuration * LEGATO,
        length,
        degree,
        midi: degreeToMidi(degree, params.scale, params.tonic),
        velocity: clamp(note.velocity ?? 0.7, 0, 1),
        timbre: clamp(note.timbre ?? 0.5, 0, 1),
        muted: Boolean(params[`mute${rec.slot}`]),
        x: p.x, y: p.y, height: p.height, s: p.s,
        mapping: mapping.id,
        source: params.source,
      };
      noteLog.push(record);
      lastBySlot.set(rec.slot, record);
      output.play(record);
    }
  }

  return {
    params,
    noteLog,
    get grid() { return grid; },

    /** Starts the grid: step 0 falls START_DELAY seconds after `now` on the audio clock. */
    start(now) {
      if (grid) return;
      grid = createGridScheduler({ stepDuration: stepDuration(), startTime: now + START_DELAY });
      // Voices seen before the start (while the page waited for Join) are entered afresh, so
      // their phase is measured from the latest state rather than from when they were first seen.
      tracker.active.clear();
      paths.clear();
      holdUntil.clear();
      rebuild();
    },

    /** A new state message. Returns the entries, fades and exits it caused. */
    onState(state) {
      lastState = state;
      return rebuild();
    },

    onAnts(ants) { antSummary = summarizeAnts(ants, antFocus); },
    setFocus(focus) { antFocus = focus; },
    /** `fn(x, y)` returns the terrain height at a world position, or null to clear it. */
    setHeightSampler(fn) { heightAt = fn; if (params.source !== 'colony') rebuild(); },

    setParam(key, value) {
      const v = coerceParam(key, value);
      if (v === undefined) return;
      params[key] = v;
      frozenParams = Object.freeze({ ...params });
      if (key === 'tempo' && grid) grid.setStepDuration(stepDuration());
      if (key === 'source') rebuild(); // same voice ids, new paths: the walk continues
    },

    /** One wake of the scheduler: books every note and tick due before now + LOOKAHEAD. */
    wake(now) {
      if (!grid) return;
      for (const { step, time } of grid.advance(now)) bookStep(step, time);
      for (const time of ticks.book(now, now + LOOKAHEAD, tickRate(antSummary, params))) {
        const carryingShare = antSummary.count ? antSummary.carrying / antSummary.count : 0;
        output.tick({ time, carrying: random() < carryingShare, velocity: 0.25 + 0.25 * random(), detune: (random() - 0.5) * 100 });
      }
      // Keep the note log as long as the piano roll shows.
      const keepAfter = now - params.rollSeconds;
      let drop = 0;
      while (drop < noteLog.length && noteLog[drop].time + noteLog[drop].duration < keepAfter) drop++;
      if (drop) noteLog.splice(0, drop);
    },

    /** After the tab was hidden: skip the steps that passed, keeping the grid's phase. */
    resync(now) { return grid ? grid.resync(now) : 0; },

    /** The voices playing now: [{ id, slot, strength, fading }]. */
    activeVoices() {
      return [...tracker.active.values()].map(({ id, slot, strength, fading }) => ({ id, slot, strength, fading }));
    },

    /** Where a slot's last note came from on its path ({ x, y, height }), or null. */
    voicePosition(slot) {
      const r = lastBySlot.get(slot);
      return r ? { x: r.x, y: r.y, height: r.height } : null;
    },
  };
}
