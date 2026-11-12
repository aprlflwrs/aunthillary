// The noise path source: matched random paths (same start, same length, same mean bending,
// same entry and fade times), seeded by voice ID so a path does not change between messages.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import noise, { noisePathFor, bending } from '../../main/resources/static/audio/sources/noise.js';
import colony from '../../main/resources/static/audio/sources/colony.js';
import { polylineLength, buildPath, sampleAt } from '../../main/resources/static/audio/path.js';
import { curve, voice, fakeState, NEST, lcg } from './audio-fixtures.mjs';

test('the colony source passes the server\'s voices through unchanged', () => {
  const state = fakeState(100);
  assert.equal(colony.voices(state), state.voices);
});

test('a noise path matches its voice: same start, same length, same mean bending', () => {
  const random = lcg(11);
  for (let i = 0; i < 40; i++) {
    const pts = curve({ angle: random() * 6.28, length: 60 + random() * 150, bend: (random() - 0.5) * 0.03 });
    const v = voice(1 + i, i % 4, pts);
    const out = noisePathFor(v, NEST, null);
    assert.deepEqual(out[0].slice(0, 2), pts[0].slice(0, 2), 'same start at the hub\'s edge');
    const L = polylineLength(pts), Ln = polylineLength(out);
    assert.ok(Math.abs(Ln - L) / L < 0.01, `length ${Ln} vs ${L}`);
    const B = bending(pts), Bn = bending(out);
    assert.ok(Math.abs(Bn - B) <= 0.05 * B + 1e-4, `bending ${Bn} vs ${B}`);
    // Its points are about every 2 units, like the server's.
    for (let k = 1; k < out.length; k++) assert.ok(Math.hypot(out[k][0] - out[k - 1][0], out[k][1] - out[k - 1][1]) <= 2.0001);
    // It starts straight out from the nest.
    const h0 = Math.atan2(out[1][1] - out[0][1], out[1][0] - out[0][0]);
    const radial = Math.atan2(pts[0][1] - NEST.y, pts[0][0] - NEST.x);
    assert.ok(Math.abs(Math.atan2(Math.sin(h0 - radial), Math.cos(h0 - radial))) < 0.3);
  }
});

test('noise paths are seeded by voice ID: stable across messages, different between voices', () => {
  const pts = curve({ angle: 0.7, length: 150, bend: 0.004 });
  const a1 = noisePathFor(voice(5, 0, pts), NEST, null);
  const a2 = noisePathFor(voice(5, 3, pts.map((p) => [...p])), NEST, null);
  assert.deepEqual(a1, a2, 'same ID, same path (slot does not matter)');
  const b = noisePathFor(voice(6, 0, pts), NEST, null);
  assert.notDeepEqual(a1, b);
  // A slightly drifted real voice gives a slightly changed noise path, not a new one.
  const drifted = curve({ angle: 0.7, length: 152, bend: 0.0042 });
  const a3 = noisePathFor(voice(5, 0, drifted), NEST, null);
  const mid = Math.floor(a1.length / 2);
  assert.ok(Math.hypot(a3[mid][0] - a1[mid][0], a3[mid][1] - a1[mid][1]) < 15);
});

test('noise voices keep id, slot, bornTick and strength, so they enter and fade with the colony', () => {
  const state = fakeState(1000);
  const out = noise.voices(state, {});
  assert.equal(out.length, state.voices.length);
  out.forEach((v, i) => {
    const r = state.voices[i];
    for (const k of ['id', 'slot', 'foodId', 'bornTick', 'strength']) assert.equal(v[k], r[k]);
    assert.notDeepEqual(v.points, r.points);
  });
});

test('noise paths take height from the terrain under them, and strength from the real profile', () => {
  const pts = curve({ angle: 2, length: 80, bend: 0.002 });
  const out = noisePathFor(voice(9, 0, pts), NEST, (x, y) => x / 100 + y / 1000);
  for (const p of out) assert.ok(Math.abs(p[2] - (p[0] / 100 + p[1] / 1000)) < 1e-9);
  const real = buildPath(pts);
  let s = 0;
  out.forEach((p, i) => {
    if (i) s += Math.hypot(p[0] - out[i - 1][0], p[1] - out[i - 1][1]);
    assert.ok(Math.abs(p[3] - sampleAt(real, s).strength) < 1e-9);
  });
});

test('a straight voice gets a straight noise path; a voice too short to walk passes through', () => {
  const pts = [];
  for (let i = 0; i <= 40; i++) pts.push([268 + 2 * i, 256, 0, 1]);
  const out = noisePathFor(voice(2, 0, pts), NEST, null);
  assert.ok(bending(out) < 1e-6);
  assert.deepEqual(noisePathFor(voice(3, 0, [[268, 256, 0, 1]]), NEST, null), [[268, 256, 0, 1]]);
});
