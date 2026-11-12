// The three starting mappings: each is pure, returns only scale degrees (never pitches or
// times), ignores which voice it is playing, and behaves as its description says.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAPPINGS } from '../../main/resources/static/audio/mappings/index.js';
import { buildPath } from '../../main/resources/static/audio/path.js';
import { mappingInput, createConductor } from '../../main/resources/static/audio/conductor.js';
import { defaultParams } from '../../main/resources/static/audio/params.js';
import { curve, voice, NEST, fakeState, lcg } from './audio-fixtures.mjs';

const params = Object.freeze(defaultParams());
const inputsAlong = (pts, d = 5) => {
  const path = buildPath(pts);
  const v = voice(1, 0, pts);
  const out = [];
  for (let j = 0; j * d <= path.length; j++) out.push(mappingInput({ voice: v, path, voiceStep: j, stepDistance: d, nest: NEST, params }));
  return out;
};

test('every mapping is pure and returns a well-formed note or a rest', () => {
  const random = lcg(7);
  for (const m of Object.values(MAPPINGS)) {
    for (let i = 0; i < 300; i++) {
      const pts = curve({ angle: random() * 6.28, length: 40 + random() * 160, bend: (random() - 0.5) * 0.04 });
      for (const p of inputsAlong(pts, 7)) {
        const a = m.noteAt(p);
        const b = m.noteAt(p);
        assert.deepEqual(a, b, `${m.id} gave two answers for one input`);
        if (a === null) continue;
        assert.deepEqual(Object.keys(a).sort(), ['degree', 'length', 'timbre', 'velocity'], `${m.id} returns only these fields`);
        assert.ok(Number.isInteger(a.degree), `${m.id} degree ${a.degree}`);
        assert.ok(a.velocity >= 0 && a.velocity <= 1);
        assert.ok(a.timbre >= 0 && a.timbre <= 1);
        assert.ok(Number.isInteger(a.length) && a.length >= 1);
      }
    }
  }
});

test('the mapping input cannot tell voices apart: no id, slot or foodId', () => {
  const [p] = inputsAlong(curve({ angle: 0, length: 20 }));
  for (const key of ['id', 'slot', 'foodId']) {
    assert.ok(!(key in p.voice), `p.voice.${key}`);
    assert.ok(!(key in p), `p.${key}`);
  }
  assert.ok(Object.isFrozen(p) && Object.isFrozen(p.voice), 'a mapping cannot change its input');
});

test('swapping voice ids and slots changes no note: mappings ignore which voice plays', () => {
  for (const mapping of Object.keys(MAPPINGS)) {
    const run = (relabel) => {
      const played = [];
      const c = createConductor({ output: { play: (r) => played.push(r), tick() {} }, params: { mapping }, random: lcg(3) });
      c.start(0);
      const state = fakeState(900);
      state.voices = state.voices.map((v, i) => ({ ...v, ...relabel(v, i) }));
      c.onState(state);
      for (let now = 0; now < 30; now += 0.025) c.wake(now);
      // Compare by voice position in the list (the same path), ignoring id and slot.
      const order = new Map(state.voices.map((v, i) => [v.id, i]));
      return played.map((r) => [order.get(r.voiceId), r.time, r.midi, r.velocity, r.length, r.timbre]);
    };
    const a = run(() => ({}));
    const b = run((v, i) => ({ id: 100 + 7 * i, slot: 3 - i }));
    assert.ok(a.length > 50, `${mapping} played ${a.length} notes`);
    assert.deepEqual(a.sort(), b.sort(), mapping);
  }
});

test('compass: a trail running due east repeats one note; going north raises it', () => {
  const east = [];
  for (let i = 0; i <= 60; i++) east.push([268 + 2 * i, 300, 4, 0.9]);
  const degrees = inputsAlong(east).map((p) => MAPPINGS.compass.noteAt(p).degree);
  assert.equal(new Set(degrees).size, 1);
  const north = [];
  for (let i = 0; i <= 60; i++) north.push([300, 268 + 2 * i, 4, 0.9]);
  const up = inputsAlong(north).map((p) => MAPPINGS.compass.noteAt(p).degree);
  assert.ok(up[up.length - 1] > up[0] + 3);
  // West is mellow, east bright.
  const west = inputsAlong([[20, 256, 0, 1], [22, 256, 0, 1]])[0];
  const eastP = inputsAlong([[490, 256, 0, 1], [492, 256, 0, 1]])[0];
  assert.ok(MAPPINGS.compass.noteAt(west).timbre < 0.1 && MAPPINGS.compass.noteAt(eastP).timbre > 0.9);
});

test('turning: a straight run repeats, turning left climbs, turning right falls, thin trail rests', () => {
  const straight = inputsAlong(curve({ angle: 2, length: 100 })).map((p) => MAPPINGS.turning.noteAt(p).degree);
  assert.equal(new Set(straight).size, 1);
  const left = inputsAlong(curve({ angle: 2, length: 100, bend: 0.01 })).map((p) => MAPPINGS.turning.noteAt(p).degree);
  assert.ok(left[left.length - 1] >= 2, `left ends at ${left.at(-1)}`);
  const right = inputsAlong(curve({ angle: 2, length: 100, bend: -0.01 })).map((p) => MAPPINGS.turning.noteAt(p).degree);
  assert.ok(right[right.length - 1] <= -2);
  // Same shape pointing another way: same melody (direction does not matter, only bending).
  const left2 = inputsAlong(curve({ angle: -1, length: 100, bend: 0.01 })).map((p) => MAPPINGS.turning.noteAt(p).degree);
  assert.deepEqual(left2, left);
  const thin = inputsAlong(curve({ angle: 0, length: 20, strength: 0.1 }));
  assert.ok(thin.every((p) => MAPPINGS.turning.noteAt(p) === null));
});

test('distance and height: the register climbs with distance from the nest', () => {
  // Flat ground at height 0, so only distance acts.
  const flat = [];
  for (let i = 0; i <= 100; i++) flat.push([268 + 2 * i, 256, 0, 0.8]);
  const notes = inputsAlong(flat).map((p) => [p.distanceFromNest, MAPPINGS.distanceHeight.noteAt(p).degree]);
  const near = notes.find(([d]) => d < 80)[1], mid = notes.find(([d]) => d >= 80 && d < 160)[1], far = notes.find(([d]) => d >= 160)[1];
  assert.deepEqual([near, mid, far], [-5, 0, 5], 'one octave (5 pentatonic steps) per 80 units');
  // A hill raises the note within the register.
  const hill = inputsAlong([[300, 256, 0, 1], [302, 256, 6, 1]]);
  const atTop = mappingInput({ voice: voice(1, 0, []), path: buildPath([[300, 256, 6, 1], [302, 256, 6, 1]]), voiceStep: 0, stepDistance: 5, nest: NEST, params });
  assert.equal(MAPPINGS.distanceHeight.noteAt(atTop).degree - MAPPINGS.distanceHeight.noteAt(hill[0]).degree, 4);
});
