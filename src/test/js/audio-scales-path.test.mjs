// Scale application and path geometry: degrees become pitches only through degreeToMidi, and
// a path is walked from its nest end outward by distance.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { degreeToMidi, SCALES, MIDI_LOW, MIDI_HIGH } from '../../main/resources/static/audio/scales.js';
import { buildPath, sampleAt, meanBending, resample, smooth, polylineLength, wrapAngle } from '../../main/resources/static/audio/path.js';
import { stepsPerLoop } from '../../main/resources/static/audio/conductor.js';
import { curve } from './audio-fixtures.mjs';

test('pentatonic degrees: tonic, steps up, octaves above and below', () => {
  const m = (d) => degreeToMidi(d, 'pentatonic', 60);
  assert.deepEqual([0, 1, 2, 3, 4, 5].map(m), [60, 62, 64, 67, 69, 72]);
  assert.deepEqual([-1, -2, -5].map(m), [57, 55, 48]);
  assert.equal(m(10), 84);
});

test('every scale stays in its pitch classes, and notes fold into the samples\' range', () => {
  for (const [name, intervals] of Object.entries(SCALES)) {
    for (let d = -40; d <= 40; d++) {
      const midi = degreeToMidi(d, name, 62);
      assert.ok(midi >= MIDI_LOW && midi <= MIDI_HIGH, `${name} ${d} → ${midi}`);
      assert.ok(intervals.includes((((midi - 62) % 12) + 12) % 12), `${name} ${d} out of scale`);
    }
  }
  assert.equal(degreeToMidi(2.4, 'major', 60), 64, 'degrees are rounded to whole steps');
});

test('a straight path: distance, heading, no turning, no curvature', () => {
  const pts = [];
  for (let i = 0; i <= 10; i++) pts.push([100 + 2 * i, 100, i, 0.5]);
  const path = buildPath(pts);
  assert.equal(path.length, 20);
  const at = sampleAt(path, 7);
  assert.ok(Math.abs(at.x - 107) < 1e-9 && Math.abs(at.y - 100) < 1e-9);
  assert.ok(Math.abs(at.height - 3.5) < 1e-9, 'height is interpolated');
  assert.equal(at.heading, 0);
  assert.equal(at.turning, 0);
  assert.equal(at.curvature, 0);
  assert.equal(sampleAt(path, -5).x, 100, 'clamped at the nest end');
  assert.equal(sampleAt(path, 99).x, 120, 'clamped at the food end');
});

test('a quarter circle turning left ends with turning ≈ +π/2 and constant curvature', () => {
  const r = 40, pts = [];
  for (let i = 0; i <= 60; i++) {
    const a = -Math.PI / 2 + (i / 60) * (Math.PI / 2);
    pts.push([200 + r * Math.cos(a), 200 + r * Math.sin(a), 0, 1]);
  }
  const path = buildPath(pts);
  const end = sampleAt(path, path.length);
  assert.ok(Math.abs(end.turning - Math.PI / 2) < 0.03, `turning ${end.turning}`);
  const mid = sampleAt(path, path.length / 2);
  assert.ok(Math.abs(mid.curvature - 1 / r) < 0.002, `curvature ${mid.curvature}`);
  assert.ok(Math.abs(meanBending(pts) - 1 / r) < 0.002);
});

test('headings unwrap across ±π, so turning counts the real amount turned', () => {
  // A full circle turning left: the heading passes through π and keeps counting.
  const pts = [];
  for (let i = 0; i <= 100; i++) {
    const a = (i / 100) * 2 * Math.PI;
    pts.push([256 + 30 * Math.cos(a), 256 + 30 * Math.sin(a), 0, 1]);
  }
  const end = sampleAt(buildPath(pts), Infinity);
  assert.ok(Math.abs(end.turning - 2 * Math.PI) < 0.1, `turning ${end.turning}`);
  assert.ok(Math.abs(wrapAngle(3 * Math.PI) - Math.PI) < 1e-12);
});

test('walking a path: one 8th note per fixed distance, from the nest end, looping', () => {
  const pts = curve({ angle: 0, length: 100 });
  const path = buildPath(pts);
  const d = 5;
  const loop = stepsPerLoop(path.length, d);
  assert.equal(loop, Math.floor(path.length / d) + 1);
  // Step 0 is the hub's edge; each step is d further; after `loop` steps it starts again.
  assert.equal(sampleAt(path, 0).x, pts[0][0]);
  for (let j = 0; j < loop; j++) assert.ok(Math.abs(sampleAt(path, j * d).s - j * d) < 1e-9);
  assert.ok((loop - 1) * d <= path.length && loop * d > path.length);
  assert.equal(stepsPerLoop(path.length, 1000), 1);
  assert.equal(stepsPerLoop(0, 5), 1);
});

test('resample keeps the end exactly, and smoothing keeps both ends', () => {
  const pts = curve({ angle: 1, length: 50, bend: 0.03 });
  const r = resample(pts, 2, 31);
  const path = buildPath(r);
  assert.ok(Math.abs(path.s[path.n - 1] - polylineLength(r)) < 1e-9);
  assert.ok(Math.abs(polylineLength(r) - 31) < 0.05);
  const sm = smooth(pts, 3);
  assert.deepEqual(sm[0], pts[0]);
  assert.deepEqual(sm[sm.length - 1], pts[pts.length - 1]);
});
