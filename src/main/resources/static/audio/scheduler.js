// The lookahead scheduler: the standard "two clocks" pattern.
//
// Two clocks are involved. The JavaScript timer (setInterval) is imprecise: it can fire late by
// tens of milliseconds, or stall for longer while the page is busy. The audio clock
// (AudioContext.currentTime) is sample-accurate but cannot call code. So the timer only wakes
// the scheduler, every 25 ms, and each wake books every 8th note that falls in the next 100 ms
// at its exact time on the audio clock. As long as no wake is more than 75 ms late, every note
// is booked before it is due and plays exactly on time, however the timer jitters.
//
// The grid never drifts because note times are never accumulated: step k is always at
// anchorTime + (k − anchorStep) × stepDuration. A tempo change moves the anchor to the next
// unbooked step, so the grid stays continuous across it.
//
// The core below is pure logic: it takes "now" as an argument and returns the steps to book, so
// it runs in Node against a fake clock (src/test/js/scheduler.test.mjs simulates ten minutes).
// createLookaheadClock is the thin browser wrapper around it.

/** How far ahead each wake books notes, in seconds. */
export const LOOKAHEAD = 0.1;
/** How often the timer wakes the scheduler, in milliseconds. */
export const WAKE_MS = 25;

/**
 * A grid of steps (8th notes) on the audio clock, starting with step 0 at `startTime`.
 *
 * A step whose time has already passed when it is first considered (because the timer stalled
 * for longer than the lookahead, or the tab was hidden) is dropped, never played late: a late
 * note would be off the beat, while a dropped one is just a rest.
 */
export function createGridScheduler({ stepDuration, startTime, lookahead = LOOKAHEAD }) {
  let anchorStep = 0;
  let anchorTime = startTime;
  let duration = stepDuration;
  let next = 0; // the first step not yet booked or dropped
  const stats = { booked: 0, dropped: 0 };

  const timeOf = (step) => anchorTime + (step - anchorStep) * duration;

  /** The first step at or after `time`. */
  function firstStepAtOrAfter(time) {
    let k = anchorStep + Math.ceil((time - anchorTime) / duration);
    // Guard against rounding at exact boundaries.
    while (timeOf(k - 1) >= time) k--;
    while (timeOf(k) < time) k++;
    return k;
  }

  /** Drops every step whose time is before `now`. Returns how many were dropped. */
  function dropPast(now) {
    const first = firstStepAtOrAfter(now);
    if (first <= next) return 0;
    const dropped = first - next;
    next = first;
    stats.dropped += dropped;
    return dropped;
  }

  return {
    stats,
    timeOf,
    get nextStep() { return next; },
    get stepDuration() { return duration; },

    /** Called on every wake: returns [{ step, time }] for every step due in [now, now + lookahead). */
    advance(now) {
      dropPast(now);
      const due = [];
      while (timeOf(next) < now + lookahead) {
        due.push({ step: next, time: timeOf(next) });
        next++;
      }
      stats.booked += due.length;
      return due;
    },

    /** Changes the step length from the next unbooked step on; booked steps keep their times. */
    setStepDuration(newDuration) {
      if (newDuration === duration) return;
      anchorTime = timeOf(next);
      anchorStep = next;
      duration = newDuration;
    },

    /**
     * After a pause (a hidden tab), skips the steps that passed meanwhile. The grid keeps its
     * phase, so voices pick up where the time says they are, not where they stopped.
     */
    resync(now) {
      return dropPast(now);
    },
  };
}

/**
 * The browser wrapper: wakes `onWake(now)` every WAKE_MS while the page is visible, with `now`
 * read from the audio clock. While the tab is hidden, browsers throttle timers to about once a
 * second, which would drop most notes; so the wrapper stops instead, calls `onHidden`, and on
 * return calls `onVisible(now)` (where the engine resyncs the grid) before waking again.
 */
export function createLookaheadClock({ now, onWake, onHidden = () => {}, onVisible = () => {} }) {
  let timer = null;
  let running = false;

  const wake = () => onWake(now());
  const startTimer = () => { if (!timer) { wake(); timer = setInterval(wake, WAKE_MS); } };
  const stopTimer = () => { if (timer) { clearInterval(timer); timer = null; } };

  function onVisibility() {
    if (!running) return;
    if (document.hidden) {
      stopTimer();
      onHidden();
    } else {
      onVisible(now());
      startTimer();
    }
  }

  return {
    start() {
      if (running) return;
      running = true;
      document.addEventListener('visibilitychange', onVisibility);
      if (!document.hidden) startTimer(); else onHidden();
    },
    stop() {
      running = false;
      document.removeEventListener('visibilitychange', onVisibility);
      stopTimer();
    },
  };
}
