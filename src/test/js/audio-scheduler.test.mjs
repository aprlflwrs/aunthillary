// The lookahead scheduler against a fake clock: ten simulated minutes of a jittery timer, tempo
// changes, stalls and a hidden tab.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createGridScheduler, LOOKAHEAD, WAKE_MS } from '../../main/resources/static/audio/scheduler.js';
import { eighthSeconds } from '../../main/resources/static/audio/params.js';
import { lcg } from './audio-fixtures.mjs';

const EIGHTH = eighthSeconds(100); // 0.3 s at 100 BPM

test('ten minutes of a jittery timer: every step booked once, on time, exactly on the grid', () => {
  const start = 0.05;
  const grid = createGridScheduler({ stepDuration: EIGHTH, startTime: start });
  const random = lcg(1);
  const booked = [];
  let now = 0;
  while (now < 600 + 1) {
    for (const { step, time } of grid.advance(now)) {
      assert.ok(time >= now, `step ${step} booked late`);
      assert.ok(time < now + LOOKAHEAD);
      booked.push({ step, time });
    }
    // Wakes every 25 ms, each up to 20 ms late, and an occasional 70 ms hiccup.
    now += WAKE_MS / 1000 + random() * 0.02 + (random() < 0.01 ? 0.05 : 0);
  }
  assert.equal(grid.stats.dropped, 0);
  booked.forEach((b, i) => assert.equal(b.step, i, 'no step skipped or repeated'));
  // Drift: the grid time of every step equals start + step × 0.3 s to within a nanosecond.
  for (const b of booked) assert.ok(Math.abs(b.time - (start + b.step * EIGHTH)) < 1e-9);
  // 100 BPM is 200 8th notes a minute: step 2000 falls exactly at ten minutes.
  const s2000 = booked.find((b) => b.step === 2000);
  assert.ok(Math.abs(s2000.time - (start + 600)) < 1e-9);
  assert.ok(booked.length >= 2000);
});

test('a stall longer than the lookahead drops the missed steps instead of playing them late', () => {
  const grid = createGridScheduler({ stepDuration: EIGHTH, startTime: 0 });
  grid.advance(0);              // books step 0
  const late = grid.advance(1.0); // stalled for a second: steps 1–3 (0.3, 0.6, 0.9) are past
  assert.equal(grid.stats.dropped, 3);
  assert.deepEqual(late, []);
  const next = grid.advance(1.15); // step 4 at 1.2 is within the lookahead
  assert.deepEqual(next.map((s) => s.step), [4]);
  assert.ok(Math.abs(next[0].time - 1.2) < 1e-12);
});

test('a tempo change takes effect at the next unbooked step and keeps the grid continuous', () => {
  const grid = createGridScheduler({ stepDuration: 0.3, startTime: 0 });
  const times = [];
  let now = 0;
  for (; now < 3; now += 0.025) times.push(...grid.advance(now));
  grid.setStepDuration(0.25);
  for (; now < 6; now += 0.025) times.push(...grid.advance(now));
  times.forEach((t, i) => assert.equal(t.step, i));
  const changeAt = times.find((t, i) => i > 0 && Math.abs(t.time - times[i - 1].time - 0.25) < 1e-9);
  assert.ok(changeAt, 'the new tempo is used');
  for (let i = 1; i < times.length; i++) {
    const gap = times[i].time - times[i - 1].time;
    assert.ok(Math.abs(gap - 0.3) < 1e-9 || Math.abs(gap - 0.25) < 1e-9, `gap ${gap}`);
  }
});

test('resync after a hidden tab skips the passed steps and keeps the phase', () => {
  const grid = createGridScheduler({ stepDuration: EIGHTH, startTime: 0 });
  for (let now = 0; now < 2; now += 0.025) grid.advance(now);
  const skipped = grid.resync(62.0); // a minute in the background
  assert.equal(skipped, 200);        // steps 7 to 206
  const due = grid.advance(62.05);
  // 62.1 is step 207 (207 × 0.3 = 62.1): back on the same grid.
  assert.deepEqual(due.map((d) => d.step), [207]);
  assert.ok(Math.abs(due[0].time - 62.1) < 1e-9);
});
