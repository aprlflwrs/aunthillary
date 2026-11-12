// Entry and fade detection by comparing successive states, the four-voice cap, and the
// entry phase.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createVoiceTracker, TICKS_PER_SECOND } from '../../main/resources/static/audio/voices.js';
import { voice, curve } from './audio-fixtures.mjs';

const pts = curve({ angle: 0, length: 40 });
const ctx = (tick, nextStep = 100) => ({ tick, nextStep, stepDuration: 0.3 });

test('entries, fades and exits come from comparing successive states', () => {
  const t = createVoiceTracker();
  let c = t.update([voice(1, 0, pts, { strength: 0.5, bornTick: 0 })], ctx(0));
  assert.deepEqual(c, { entered: [1], faded: [], exited: [] });

  c = t.update([voice(1, 0, pts, { strength: 0.6 }), voice(2, 1, pts, { strength: 0.2, bornTick: 15 })], ctx(15));
  assert.deepEqual(c, { entered: [2], faded: [], exited: [] });
  assert.equal(t.active.get(1).fading, false);

  c = t.update([voice(1, 0, pts, { strength: 0.4 }), voice(2, 1, pts, { strength: 0.3 })], ctx(30));
  assert.deepEqual(c, { entered: [], faded: [1], exited: [] }, 'strength fell: fading');
  assert.equal(t.active.get(1).fading, true);

  c = t.update([voice(1, 0, pts, { strength: 0.4 }), voice(2, 1, pts, { strength: 0.3 })], ctx(45));
  assert.deepEqual(c.faded, [], 'a fade is reported once');
  assert.equal(t.active.get(1).fading, true, 'holding steady while fading stays fading');

  c = t.update([voice(1, 0, pts, { strength: 0.5 }), voice(2, 1, pts, { strength: 0.3 })], ctx(60));
  assert.equal(t.active.get(1).fading, false, 'rising again: no longer fading');

  c = t.update([voice(2, 1, pts, { strength: 0.3 })], ctx(75));
  assert.deepEqual(c, { entered: [], faded: [], exited: [1] });
  assert.ok(!t.active.has(1));
});

test('small strength jitter is not a fade', () => {
  const t = createVoiceTracker();
  t.update([voice(1, 0, pts, { strength: 0.5 })], ctx(0));
  const c = t.update([voice(1, 0, pts, { strength: 0.4995 })], ctx(15));
  assert.deepEqual(c.faded, []);
});

test('at most four voices, one per slot; playing voices keep their slots', () => {
  const t = createVoiceTracker();
  const many = [0, 1, 2, 3].map((s) => voice(10 + s, s, pts, { strength: 0.5 }));
  t.update(many, ctx(0));
  // A fifth voice claiming slot 2 with more strength does not take it from a living voice.
  const c = t.update([...many, voice(99, 2, pts, { strength: 0.95 }), voice(98, 7, pts)], ctx(15));
  assert.deepEqual(c.entered, []);
  assert.equal(t.active.size, 4);
  assert.ok(!t.active.has(99) && !t.active.has(98));
  // Once slot 2 is free, the strongest waiting voice takes it.
  const c2 = t.update([many[0], many[1], many[3], voice(99, 2, pts, { strength: 0.95 }), voice(97, 2, pts, { strength: 0.2 })], ctx(30));
  assert.deepEqual(c2.entered, [99]);
  assert.deepEqual(c2.exited, [12]);
});

test('entry phase: a voice born before the page joined is already part-way through its phrase', () => {
  const t = createVoiceTracker();
  // Born 6 s ago (180 ticks): 20 8th notes at 0.3 s each.
  t.update([voice(1, 0, pts, { bornTick: 820 }), voice(2, 1, pts, { bornTick: 1000 })], ctx(1000, 50));
  assert.equal(t.active.get(1).entryStep, 50 - 20);
  assert.equal(TICKS_PER_SECOND, 30);
  assert.equal(t.active.get(2).entryStep, 50, 'born now: enters on the next step');
});
