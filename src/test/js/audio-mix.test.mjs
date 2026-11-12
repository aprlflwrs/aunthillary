// The level mix, organ point, symbol tones and ant ticks: the pure sums behind the gains.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { levelMix, organPoint, symbolTone, voiceGain, VOICES_AT_ANTS } from '../../main/resources/static/audio/mix.js';
import { summarizeAnts, tickRate, createTickGenerator } from '../../main/resources/static/audio/ticks.js';
import { defaultParams } from '../../main/resources/static/audio/params.js';
import { lcg } from './audio-fixtures.mjs';

const near = (a, b) => Math.abs(a - b) < 1e-9;

test('level mix: ticks at Ants, melodies at Signals, the fugue over symbol tones at Symbols', () => {
  const ants = levelMix(0), signals = levelMix(1), symbols = levelMix(2);
  assert.ok(near(ants.ticks, 1) && near(ants.voices, VOICES_AT_ANTS) && near(ants.symbols, 0));
  assert.ok(near(signals.ticks, 0) && near(signals.voices, 1) && near(signals.symbols, 0));
  assert.ok(near(symbols.ticks, 0) && near(symbols.voices, 1) && near(symbols.symbols, 1));
  assert.ok(ants.organ > 0 && near(signals.organ, 1) && near(symbols.organ, 1));
  // A continuous crossfade: small slider moves make small changes, and it clamps outside 0–2.
  for (let l = 0; l < 2; l += 0.01) {
    const a = levelMix(l), b = levelMix(l + 0.01);
    for (const k of Object.keys(a)) assert.ok(Math.abs(a[k] - b[k]) < 0.03, `${k} jumps at ${l}`);
  }
  assert.deepEqual(levelMix(-1), ants);
  assert.deepEqual(levelMix(5), symbols);
  // Equal power half way between Ants and Signals: ticks² + (voice share)² ≈ 1.
  const half = levelMix(0.5);
  const voiceShare = (half.voices - VOICES_AT_ANTS) / (1 - VOICES_AT_ANTS);
  assert.ok(near(half.ticks ** 2 + voiceShare ** 2, 1));
});

test('voice gain follows its trail\'s strength', () => {
  assert.equal(voiceGain(0, 0.5), 0);
  assert.equal(voiceGain(0.25, 0.5), 0.5);
  assert.equal(voiceGain(0.9, 0.5), 1);
});

test('organ point: louder and richer as the store fills, never silent', () => {
  const empty = organPoint(0, 100), half = organPoint(50, 100), full = organPoint(100, 100), over = organPoint(400, 100);
  assert.ok(empty.gain > 0);
  assert.ok(empty.gain < half.gain && half.gain < full.gain);
  assert.ok(empty.cutoffHz < half.cutoffHz && half.cutoffHz < full.cutoffHz);
  assert.ok(empty.upperGain < full.upperGain);
  assert.deepEqual(over, full);
});

test('symbol tones: direction picks the degree, strength the loudness, an octave below the tonic', () => {
  const params = defaultParams(); // pentatonic on C4 (60)
  const east = symbolTone({ direction: 0, strength: 0.4 }, params);
  assert.deepEqual(east, { degree: 0, midi: 48, gain: 0.4 });
  // Counterclockwise round the ring climbs the scale: a fifth of the way round is one step.
  assert.equal(symbolTone({ direction: (2 * Math.PI) / 5, strength: 1 }, params).degree, 1);
  assert.equal(symbolTone({ direction: -(2 * Math.PI) / 5, strength: 1 }, params).degree, 4);
  assert.equal(symbolTone({ direction: 2 * Math.PI - 0.01, strength: 1 }, params).degree, 0, 'wraps round');
});

test('ant ticks: rate rises with density and levels off; focus counts only nearby ants', () => {
  const params = defaultParams();
  const frame = (n, inside) => {
    const x = new Uint16Array(n), y = new Uint16Array(n), flags = new Uint8Array(n);
    for (let i = 0; i < n; i++) {
      const at = i < inside ? 256 : 20;
      x[i] = Math.round((at / 512) * 65535); y[i] = x[i];
      flags[i] = i % 4 === 0 ? 2 : 0;
    }
    return { count: n, x, y, flags };
  };
  const all = summarizeAnts(frame(1000, 300));
  assert.equal(all.count, 1000);
  assert.equal(all.carrying, 250);
  const focused = summarizeAnts(frame(1000, 300), { x: 256, y: 256, radius: 30 });
  assert.equal(focused.count, 300);
  assert.equal(tickRate(null, params), 0);
  const rates = [10, 100, 1000, 10000, 100000].map((n) => tickRate(summarizeAnts(frame(n, 0)), params));
  for (let i = 1; i < rates.length; i++) assert.ok(rates[i] > rates[i - 1]);
  assert.ok(rates.at(-1) <= params.tickRateMax);
});

test('tick generator: a Poisson process at the asked rate, continuous across windows', () => {
  const gen = createTickGenerator(lcg(4));
  const times = [];
  for (let t = 0; t < 600; t += 0.025) times.push(...gen.book(t, t + 0.1, 10));
  assert.ok(Math.abs(times.length / 600 - 10) < 0.5, `${times.length / 600} per second`);
  for (let i = 1; i < times.length; i++) assert.ok(times[i] > times[i - 1], 'never booked twice');
  assert.deepEqual(gen.book(700, 700.1, 0), []);
});
