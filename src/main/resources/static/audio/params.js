// The sound engine's own parameters (the "client parameters" of the plan). Like the server's
// registry, each is declared once here with a label, range and default; the Sound folder of the
// tuning panel is built from this list, and presets can read and write them by key.

import { SCALES } from './scales.js';
import { MAPPINGS } from './mappings/index.js';
import { SOURCES } from './sources/index.js';

/** A numeric parameter has min/max/step; a choice has `options` (value → label); a switch is a boolean default. */
export const CLIENT_PARAMS = [
  // ---- music ----
  { key: 'tempo', label: 'Tempo (BPM, quarter notes)', min: 40, max: 200, step: 1, default: 100 },
  { key: 'scale', label: 'Scale', options: Object.fromEntries(Object.keys(SCALES).map((k) => [k, k])), default: 'pentatonic' },
  { key: 'tonic', label: 'Tonic (MIDI note)', min: 48, max: 72, step: 1, default: 60 },
  { key: 'mapping', label: 'Mapping', options: Object.fromEntries(Object.values(MAPPINGS).map((m) => [m.id, m.label])), default: 'turning' },
  { key: 'source', label: 'Path source', options: Object.fromEntries(Object.values(SOURCES).map((s) => [s.id, s.label])), default: 'colony' },
  { key: 'stepDistance', label: 'Distance per 8th note (world units)', min: 1, max: 20, step: 0.5, default: 5 },
  { key: 'voiceFullStrength', label: 'Voice strength at full volume', min: 0.05, max: 1, step: 0.01, default: 0.5 },
  // ---- volumes ----
  { key: 'masterVolume', label: 'Master volume', min: 0, max: 1, step: 0.01, default: 0.8 },
  { key: 'voiceVolume', label: 'Voices volume', min: 0, max: 1, step: 0.01, default: 0.8 },
  { key: 'organVolume', label: 'Organ point volume', min: 0, max: 1, step: 0.01, default: 0.5 },
  { key: 'symbolVolume', label: 'Symbol tones volume', min: 0, max: 1, step: 0.01, default: 0.4 },
  { key: 'tickVolume', label: 'Ant ticks volume', min: 0, max: 1, step: 0.01, default: 0.5 },
  // ---- organ point and ticks ----
  { key: 'storeFull', label: 'Nest store for a full organ point', min: 1, max: 1000, step: 1, default: 100 },
  { key: 'tickRateMax', label: 'Ant ticks per second (max)', min: 0, max: 40, step: 0.5, default: 12 },
  { key: 'tickDensityRef', label: 'Ant density for most ticks (per 1000 sq units)', min: 1, max: 500, step: 1, default: 60 },
  // ---- piano roll ----
  { key: 'rollSeconds', label: 'Piano roll history (s)', min: 2, max: 30, step: 1, default: 8 },
  // ---- per-voice mutes (by slot) ----
  { key: 'mute0', label: 'Mute voice 1', default: false },
  { key: 'mute1', label: 'Mute voice 2', default: false },
  { key: 'mute2', label: 'Mute voice 3', default: false },
  { key: 'mute3', label: 'Mute voice 4', default: false },
];

/** A fresh object of every client parameter at its default. */
export function defaultParams() {
  return Object.fromEntries(CLIENT_PARAMS.map((p) => [p.key, p.default]));
}

/** Clamps or validates a value for a parameter; returns undefined for an unknown key. */
export function coerceParam(key, value) {
  const spec = CLIENT_PARAMS.find((p) => p.key === key);
  if (!spec) return undefined;
  if (spec.options) return value in spec.options ? value : spec.default;
  if (typeof spec.default === 'boolean') return Boolean(value);
  const v = Number(value);
  return Number.isFinite(v) ? Math.min(spec.max, Math.max(spec.min, v)) : spec.default;
}

/** Seconds per 8th note at a tempo in quarter-note beats per minute. */
export function eighthSeconds(tempo) {
  return 60 / tempo / 2;
}
