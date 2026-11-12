// The piano roll's layout, as pure functions: which rectangle each note record becomes. The
// records are the very objects the conductor handed to the instruments (its note log), so the
// roll cannot show a note that was not played, or miss one that was.
//
// Time runs left to right on the audio clock. The "now" line sits at nowFraction of the width:
// to its left, the last `seconds` of notes scroll away; to its right are notes already booked
// (up to 100 ms ahead) and the rest of the notes still sounding.

import { SLOT_COLORS } from './colors.js';

export const NOW_FRACTION = 0.8;
/** The pitch range never shrinks below two octaves, so a one-note phrase is not a fat bar. */
export const MIN_SPAN = 24;

/** The lowest and highest MIDI notes to show, from the notes visible in the window. */
export function pitchRange(notes, now, seconds) {
  let lo = Infinity, hi = -Infinity;
  for (const n of notes) {
    if (n.time + n.duration < now - seconds) continue;
    lo = Math.min(lo, n.midi); hi = Math.max(hi, n.midi);
  }
  if (lo === Infinity) { lo = 54; hi = 78; }
  const pad = Math.max(2, (MIN_SPAN - (hi - lo)) / 2);
  return { low: Math.floor(lo - pad), high: Math.ceil(hi + pad) };
}

/**
 * Rectangles for every note visible at time `now`. Each carries its record, so a caller (or a
 * test) can check that the rectangle is that note.
 */
export function rollRects(notes, now, { width, height, seconds, low, high, nowFraction = NOW_FRACTION }) {
  const nowX = width * nowFraction;
  const pxPerSecond = nowX / seconds;
  const rows = high - low + 1;
  const rowHeight = height / rows;
  const rects = [];
  for (const n of notes) {
    const x = nowX + (n.time - now) * pxPerSecond;
    const w = Math.max(1, n.duration * pxPerSecond);
    if (x + w < 0 || x > width) continue;
    rects.push({
      x, y: (high - n.midi) * rowHeight, w, h: Math.max(1, rowHeight - 1),
      color: SLOT_COLORS[n.slot] ?? '#888',
      muted: n.muted,
      sounding: n.time <= now && now < n.time + n.duration,
      record: n,
    });
  }
  return { nowX, rects };
}
