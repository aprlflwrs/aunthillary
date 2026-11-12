// The sampled instruments, played through smplr 1.2.0. Every sample is self-hosted under
// vendor/samples/ (see vendor/VERSIONS.txt for the source and licence) and nothing is ever
// fetched from the internet:
//   - this module fetches each MP3 itself from this page's own server, decodes it once, and
//     hands smplr the decoded AudioBuffers, so smplr has nothing to download;
//   - every smplr instance also gets a storage that refuses any URL outside this origin, so
//     even a code path we did not expect could not reach smplr's default online samples;
//   - we never call smplr's Soundfont, Reverb or other loaders that have built-in remote URLs.
//
// Each voice slot gets its own pair of Samplers (a mellow and a bright organ stop, crossfaded
// per note by the mapping's timbre), sharing the same decoded buffers, writing into that slot's
// GainNode. The ant ticks are a woodblock.
//
// smplr's own scheduler plays any note due within 200 ms at once (with its exact start time),
// and the engine books notes at most 100 ms ahead, so every note goes straight to
// AudioBufferSourceNode.start(time): there is no second layer of scheduling.

import { Sampler } from 'smplr';

const SAMPLE_BASE = new URL('../vendor/samples/fluidr3-gm/', import.meta.url);

/** Every third semitone from C2 to C7; smplr pitch-shifts the notes in between (at most ±1 semitone). */
export const ORGAN_NOTES = ['C2', 'Eb2', 'Gb2', 'A2', 'C3', 'Eb3', 'Gb3', 'A3', 'C4', 'Eb4', 'Gb4', 'A4',
  'C5', 'Eb5', 'Gb5', 'A5', 'C6', 'Eb6', 'Gb6', 'A6', 'C7'];
export const TICK_NOTES = ['C5', 'C6'];
/** The mellow and bright stops: drawbar organ and reed organ from FluidR3_GM. */
export const MELLOW = 'drawbar_organ';
export const BRIGHT = 'reed_organ';
export const TICK = 'woodblock';

/**
 * The FluidR3 samples peak around −22 dBFS (smplr's own Soundfont loader adds a gain of 5 for
 * the same files), so the voice and tick outputs are raised by this much. Measured in headless
 * Chrome at the default volumes, two voices at 4 then peaked near 0.08, so it is doubled.
 */
export const SAMPLE_GAIN = 8;

const localOnlyStorage = {
  fetch(url) {
    const u = new URL(url, location.href);
    if (u.origin !== location.origin) {
      return Promise.reject(new Error(`Refusing to fetch ${u.href}: samples must be self-hosted`));
    }
    return fetch(u);
  },
};

/**
 * Decodes one sample, mixes it to mono (the files are dual mono; this halves the memory) and
 * trims its leading silence (the MP3 encoder adds about 10–20 ms), so notes start on the beat.
 */
async function decodeSample(ctx, url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Sample ${url} failed: HTTP ${response.status}`);
  const decoded = await ctx.decodeAudioData(await response.arrayBuffer());
  const channels = [];
  for (let c = 0; c < decoded.numberOfChannels; c++) channels.push(decoded.getChannelData(c));
  const mix = (i) => channels.reduce((sum, ch) => sum + ch[i], 0) / channels.length;
  const maxTrim = Math.floor(0.06 * decoded.sampleRate);
  let start = 0;
  while (start < maxTrim && Math.abs(mix(start)) < 0.002) start++;
  const out = ctx.createBuffer(1, decoded.length - start, decoded.sampleRate);
  const data = out.getChannelData(0);
  for (let i = 0; i < data.length; i++) data[i] = mix(i + start);
  return out;
}

/** Loads a set of notes of one instrument: { noteName: AudioBuffer }. */
async function loadSet(ctx, instrument, notes) {
  const entries = await Promise.all(notes.map(async (note) =>
    [note, await decodeSample(ctx, new URL(`${instrument}/${note}.mp3`, SAMPLE_BASE))]));
  return Object.fromEntries(entries);
}

/** Loads (and decodes) every sample the engine uses. */
export async function loadSamples(ctx) {
  const [mellow, bright, tick] = await Promise.all([
    loadSet(ctx, MELLOW, ORGAN_NOTES),
    loadSet(ctx, BRIGHT, ORGAN_NOTES),
    loadSet(ctx, TICK, TICK_NOTES),
  ]);
  return { mellow, bright, tick };
}

function sampler(ctx, destination, buffers, decayTime) {
  // detune and lpfCutoffHz must be given: smplr 1.2.0's Sampler copies them into its defaults
  // even when undefined, which overrides its own defaults and makes every note's detune NaN.
  return new Sampler(ctx, { destination, buffers, storage: localOnlyStorage, decayTime, detune: 0, lpfCutoffHz: 20000 });
}

/** One voice's organ: two stops into `destination`, crossfaded per note by timbre. */
export async function createVoiceInstrument(ctx, destination, samples) {
  const mellow = sampler(ctx, destination, samples.mellow, 0.12);
  const bright = sampler(ctx, destination, samples.bright, 0.12);
  await Promise.all([mellow.ready, bright.ready]);
  return {
    /** Plays one note record from the conductor. */
    play(record) {
      // Equal-power crossfade: timbre 0 is all mellow, 1 all bright, 0.5 both at −3 dB.
      const weights = [Math.cos((Math.PI / 2) * record.timbre), Math.sin((Math.PI / 2) * record.timbre)];
      [mellow, bright].forEach((stop, i) => {
        const velocity = Math.round(127 * record.velocity * weights[i]);
        if (velocity < 1) return;
        stop.start({ note: record.midi, velocity, time: record.time, duration: record.duration, stopId: `${record.seq}:${i}` });
      });
    },
    stop(time) { mellow.stop({ time }); bright.stop({ time }); },
  };
}

/** The ant ticks: a woodblock, the higher sample for ants carrying food. */
export async function createTickInstrument(ctx, destination, samples) {
  const wood = sampler(ctx, destination, samples.tick, 0.05);
  await wood.ready;
  return {
    play({ time, carrying, velocity, detune }) {
      wood.start({ note: carrying ? 'C6' : 'C5', velocity: Math.round(127 * velocity), time, detune, duration: 0.25 });
    },
  };
}
