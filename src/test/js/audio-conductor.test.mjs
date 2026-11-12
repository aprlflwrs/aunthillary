// The conductor end to end, against a fake clock and a fake output: ten minutes of fake voices
// with state messages twice a second, switching mapping and path source while playing, and the
// piano roll drawn from the same records the output played.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createConductor, START_DELAY, LEGATO } from '../../main/resources/static/audio/conductor.js';
import { eighthSeconds } from '../../main/resources/static/audio/params.js';
import { rollRects, pitchRange } from '../../main/resources/static/audio/roll-model.js';
import { SLOT_COLORS } from '../../main/resources/static/audio/colors.js';
import { WAKE_MS } from '../../main/resources/static/audio/scheduler.js';
import noise from '../../main/resources/static/audio/sources/noise.js';
import { buildPath, sampleAt } from '../../main/resources/static/audio/path.js';
import { fakeState, lcg } from './audio-fixtures.mjs';

const EIGHTH = eighthSeconds(100);

/** Fake states over time: voice 3 fades from 4:00 and is gone at 4:30; voice 4 enters at 6:00. */
function stateAt(seconds) {
  const tick = Math.round(seconds * 30);
  const s = fakeState(tick, { drift: 0.001 * Math.sin(seconds / 20) });
  const v3 = s.voices[2];
  if (seconds >= 240) v3.strength = Math.max(0, 0.8 - (seconds - 240) / 30 * 0.8);
  if (seconds >= 270) s.voices.pop();
  if (seconds >= 360) s.voices.push({ ...v3, id: 4, strength: 0.7, bornTick: 360 * 30 });
  return s;
}

function run({ minutes = 10, schedule = [], seed = 5 } = {}) {
  const random = lcg(seed);
  const played = [];
  let clock = 0;
  const conductor = createConductor({
    output: {
      play(record) {
        assert.ok(record.time >= clock, `note booked late: ${record.time} < ${clock}`);
        played.push(record);
      },
      tick() {},
    },
    random: lcg(seed + 1),
  });
  conductor.onState(stateAt(0));
  conductor.start(0);
  let nextState = 0.5;
  const pending = [...schedule];
  const snapshots = [];
  while (clock < minutes * 60) {
    if (clock >= nextState) { conductor.onState(stateAt(nextState)); nextState += 0.5; }
    while (pending.length && clock >= pending[0].at) {
      const { key, value, at } = pending.shift();
      const ids = () => conductor.activeVoices().map((v) => `${v.id}:${v.slot}`).sort();
      const before = ids();
      conductor.setParam(key, value);
      snapshots.push({ at, before, after: ids() });
    }
    conductor.wake(clock);
    clock += WAKE_MS / 1000 + random() * 0.03; // a jittery 25–55 ms timer
  }
  return { played, conductor, clock, snapshots };
}

test('ten minutes of fake voices: in time, no drift, nothing dropped', () => {
  const { played, conductor } = run();
  assert.equal(conductor.grid.stats.dropped, 0);
  assert.ok(played.length > 3000, `${played.length} notes`);
  for (const r of played) {
    // Exactly on the 8th-note grid, after ten minutes as at the start.
    assert.ok(Math.abs(r.time - (START_DELAY + r.step * EIGHTH)) < 1e-9, `note at ${r.time} is off the grid`);
    assert.ok(Math.abs(r.duration - r.length * EIGHTH * LEGATO) < 1e-12);
  }
  const last = played[played.length - 1];
  assert.ok(last.time > 599 && last.step >= 1990, `last note at ${last.time}`);
  // A voice never overlaps itself: each note ends before the voice's next one starts.
  const bySlot = new Map();
  for (const r of played) {
    const prev = bySlot.get(r.voiceId);
    if (prev) assert.ok(prev.time + prev.duration <= r.time + 1e-9);
    bySlot.set(r.voiceId, r);
  }
  // Voice 3 fades and leaves; voice 4 enters in its slot.
  assert.ok(!played.some((r) => r.voiceId === 3 && r.time > 271));
  assert.ok(played.some((r) => r.voiceId === 4 && r.slot === 2));
});

test('mapping and path source switch while playing, without voices re-entering', () => {
  const schedule = [
    { at: 120, key: 'mapping', value: 'compass' },
    { at: 180, key: 'mapping', value: 'distanceHeight' },
    { at: 300, key: 'source', value: 'noise' },
    { at: 420, key: 'source', value: 'colony' },
  ];
  const { played, snapshots } = run({ schedule });
  const between = (a, b) => played.filter((r) => r.time > a + 0.2 && r.time < b);
  assert.ok(between(0, 120).every((r) => r.mapping === 'turning'));
  assert.ok(between(120, 180).every((r) => r.mapping === 'compass') && between(120, 180).length > 100);
  assert.ok(between(180, 600).every((r) => r.mapping === 'distanceHeight'));
  assert.ok(between(300, 420).every((r) => r.source === 'noise') && between(300, 420).length > 100);
  assert.ok(between(420, 600).every((r) => r.source === 'colony'));
  // The grid carries straight on through each switch.
  for (const { at } of schedule) {
    const near = played.filter((r) => r.time > at - 2 && r.time < at + 2).map((r) => r.step);
    const steps = [...new Set(near)];
    for (let i = 1; i < steps.length; i++) assert.ok(steps[i] - steps[i - 1] <= 2, `gap at ${at}`);
  }
  // Switching the source keeps the same voices (same ids), so nothing enters or leaves.
  for (const snap of snapshots) assert.deepEqual(snap.after, snap.before);
  assert.deepEqual(snapshots.find((s) => s.at === 300).after, ['1:0', '2:1']);
});

test('voices sharing a trunk share their opening, and the noise control loses it', () => {
  const opening = () => {
    const { played } = run({ minutes: 1 });
    const phrase = (id) => new Map(played.filter((r) => r.voiceId === id && r.time > 10).map((r) => [r.voiceStep, r.midi]));
    const a = phrase(1), b = phrase(2);
    // Voices 1 and 2 share the first 70 world units: 8th notes 0–13 at 5 units each.
    const shared = [...Array(14).keys()].filter((j) => a.has(j) && b.has(j));
    assert.ok(shared.length >= 10, `only ${shared.length} opening steps compared`);
    return shared.filter((j) => a.get(j) === b.get(j)).length / shared.length;
  };
  assert.equal(opening(), 1, 'the colony: the same opening phrase');
  // The noise control's two paths start at the same point (both voices leave the hub there) but
  // wander independently, so along the shared trunk they are already apart. (Whether their
  // notes differ depends on the mapping: Turning hears little difference in two nearly straight
  // paths.)
  const [n1, n2] = noise.voices(fakeState(900), {});
  const at = (pts, s) => sampleAt(buildPath(pts), s);
  const a = at(n1.points, 70), b = at(n2.points, 70);
  assert.ok(Math.hypot(a.x - b.x, a.y - b.y) > 3, 'noise paths do not share a trunk');
});

test('the piano roll draws exactly the notes that were played, from the same records', () => {
  const { played, conductor, clock } = run({ minutes: 1 });
  const log = conductor.noteLog;
  // The log is the tail of what was played: the very same objects, in the same order.
  const tail = played.slice(played.length - log.length);
  assert.ok(log.length > 20);
  log.forEach((r, i) => assert.equal(r, tail[i]));
  // Every note still visible in the roll's window is in the log.
  const seconds = conductor.params.rollSeconds;
  const visible = played.filter((r) => r.time + r.duration >= clock - seconds);
  for (const r of visible) assert.ok(log.includes(r));

  const { low, high } = pitchRange(log, clock, seconds);
  const { rects, nowX } = rollRects(log, clock, { width: 1000, height: 100, seconds, low, high });
  const pxPerSecond = nowX / seconds;
  for (const rect of rects) {
    const r = rect.record;
    assert.ok(played.includes(r));
    assert.ok(Math.abs(rect.x - (nowX + (r.time - clock) * pxPerSecond)) < 1e-9);
    assert.ok(Math.abs(rect.w - Math.max(1, r.duration * pxPerSecond)) < 1e-9);
    assert.equal(rect.color, SLOT_COLORS[r.slot]);
    assert.ok(Math.abs(rect.y - (high - r.midi) * (100 / (high - low + 1))) < 1e-9);
  }
  // Every logged note whose rectangle lies on the canvas is drawn.
  assert.equal(rects.length, log.filter((r) => nowX + (r.time + r.duration - clock) * pxPerSecond >= 0).length);
});

test('a muted voice is still logged (and drawn as an outline) but marked muted', () => {
  const played = [];
  const c = createConductor({ output: { play: (r) => played.push(r), tick() {} }, params: { mute1: true } });
  c.start(0);
  c.onState(fakeState(900));
  for (let now = 0; now < 10; now += 0.025) c.wake(now);
  assert.ok(played.some((r) => r.slot === 1 && r.muted));
  assert.ok(played.filter((r) => r.slot !== 1).every((r) => !r.muted));
});
